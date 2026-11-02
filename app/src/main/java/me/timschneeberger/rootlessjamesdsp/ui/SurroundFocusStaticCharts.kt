package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.*
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundController
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalParameters as P
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalRuntime
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** Saved targets only. Neither view can change an audio parameter, comparison or calibration signal. */
internal class SurroundParameterChart(context: Context) : SurroundFocusStaticBase(context, false)
internal class SurroundIllustrationChart(context: Context) : SurroundFocusStaticBase(context, true)

private data class SurroundFocusModel(
    val settings: SurroundController.Settings, val rate: Int, val effectOn: Boolean,
    val supported: Boolean, val professionalSupported: Boolean, val listeningSupported: Boolean,
    val audition: Int, val calibration: Int, val savedA: Boolean, val savedB: Boolean
) {
    fun raw(key: String) = P.value(settings, key)
    fun number(key: String) = raw(key).toDouble() / P.byKey.getValue(key).control.divisor
    fun on(key: String) = P.on(settings, key)
    val professional get() = professionalSupported && on("enabled")
    val highHz get() = min(20000.0, rate * .45)
    val geometry get() = P.geometry(settings)
    val linked get() = on("link")
    fun active(focus: SurroundChartFocus, key: String): Boolean {
        if (focus == SurroundChartFocus.GEOMETRY) return true
        if (focus == SurroundChartFocus.DIAGNOSTIC) return on("diagnostics_on")
        if (!effectOn || !supported) return false
        return when (focus) {
            SurroundChartFocus.BAND -> key !in setOf("hp_slope", "lp_slope") || professional
            SurroundChartFocus.CROSSFEED, SurroundChartFocus.DELAY -> when {
                key.startsWith("legacy_") -> !professional
                key == "enabled" -> professionalSupported
                key.startsWith("rl_") -> professional && !linked
                else -> professional
            }
            SurroundChartFocus.LEVEL -> professional
            // These controls are independent of the professional cancellation/mix switch.
            SurroundChartFocus.RMS -> professionalSupported && on("rms_on")
            SurroundChartFocus.PEAK -> professionalSupported && on("peak_on")
            SurroundChartFocus.MATCH -> listeningSupported && on("match_on")
            SurroundChartFocus.CALIBRATION -> listeningSupported && on("cal_on")
            SurroundChartFocus.MONITOR -> professionalSupported && on("meters")
            SurroundChartFocus.AUDITION -> professionalSupported
            else -> true
        }
    }
    /** Display-only previews let inactive saved controls retain their own gray target geometry. */
    fun linearTarget(focus: SurroundChartFocus, key: String): SurroundController.Settings {
        val extra = settings.professional.toMutableMap()
        extra["enabled"] = if (professional) 1 else 0
        if (key.startsWith("legacy_")) extra["enabled"] = 0
        if (focus == SurroundChartFocus.LEVEL || key in setOf("hp_slope", "lp_slope") ||
            key.startsWith("lr_") || key.startsWith("rl_")) extra["enabled"] = 1
        if (key.startsWith("rl_") && linked) extra["link"] = 0
        return settings.copy(professional = extra)
    }
    fun branchDb(rightToLeft: Boolean, focus: SurroundChartFocus, key: String): Double {
        val target = linearTarget(focus, key)
        if (!P.on(target, "enabled")) return target.attenuationMilliDb / 1000.0
        val route = if (rightToLeft && !P.on(target, "link")) "rl" else "lr"
        return P.value(target, route + "_attenuation") / 1000.0
    }
    fun delayUs(rightToLeft: Boolean, focus: SurroundChartFocus, key: String): Double {
        val target = linearTarget(focus, key)
        if (!P.on(target, "enabled")) return target.delayUs.toDouble()
        val route = if (rightToLeft && !P.on(target, "link")) "rl" else "lr"
        return P.value(target, route + "_delay") / 10.0
    }
}

private data class SurroundStaticRequest(
    val settings: SurroundController.Settings, val rate: Int, val diagnostic: Boolean, val view: Int, val window: Int
)
private data class SurroundStaticResult(
    val x: DoubleArray, val values: List<DoubleArray>, val labels: List<String>,
    val unit: String, val logarithmic: Boolean, val failed: Boolean = false
)

/** A bounded, latest-only shared worker; canceled owners do not retain callbacks or queued calculation. */
private object SurroundStaticWork {
    private val main = Handler(Looper.getMainLooper())
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "surround-focus-static").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(10, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private class Pending {
        val owners = IdentityHashMap<Any, (SurroundStaticResult) -> Unit>()
        var future: Future<*>? = null
    }
    private val jobs = linkedMapOf<SurroundStaticRequest, Pending>()
    @Synchronized fun release(owner: Any) {
        val empty = mutableListOf<SurroundStaticRequest>()
        jobs.forEach { (request, pending) ->
            pending.owners.remove(owner)
            if (pending.owners.isEmpty()) { pending.future?.cancel(true); empty += request }
        }
        empty.forEach { jobs.remove(it) }
    }
    @Synchronized fun request(owner: Any, request: SurroundStaticRequest, callback: (SurroundStaticResult) -> Unit) {
        release(owner)
        jobs[request]?.let { it.owners[owner] = callback; return }
        val pending = Pending()
        pending.owners[owner] = callback; jobs[request] = pending
        pending.future = executor.submit {
            fun cancel() { if (Thread.currentThread().isInterrupted) throw CancellationException() }
            val result = try {
                cancel()
                val model = SurroundDashboardModel.from(request.settings, request.rate, true, true, 0)
                if (request.diagnostic) {
                    val response = model.diagnostic(::cancel)
                    cancel()
                    SurroundStaticResult(response.x, response.values, response.labels, response.unit, response.logarithmic)
                } else {
                    val response = model.frequency(::cancel)
                    cancel()
                    SurroundStaticResult(response.frequencies, response.decibels, response.labels, "dB", true)
                }
            } catch (_: CancellationException) { return@submit
            } catch (_: InterruptedException) { return@submit
            } catch (_: Exception) { SurroundStaticResult(doubleArrayOf(), emptyList(), emptyList(), "", true, true) }
            val callbacks = synchronized(this) {
                if (jobs[request] !== pending) return@synchronized emptyList()
                jobs.remove(request)
                pending.owners.values.toList().also { pending.owners.clear() }
            }
            main.post { callbacks.forEach { it(result) } }
        }
    }
}

internal open class SurroundFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
    private val density = resources.displayMetrics.density
    private fun d(value: Float) = value * density
    private val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val teal = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(111, 213, 209) else Color.rgb(16, 125, 119)
    private val amber = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(246, 185, 107) else Color.rgb(166, 91, 21)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(9f * resources.displayMetrics.scaledDensity, d(11f)) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(1.5f) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(.65f); color = ColorUtils.setAlphaComponent(foreground, 0x2a) }
    private val plot = RectF()
    private val path = Path()
    private var settings = SurroundController.defaults.first()
    private var rate = 48000
    private var model: SurroundFocusModel = snapshot()
    private var focus = SurroundChartFocus.BAND
    private var key = "legacy_low"
    private var active = true
    private var request: SurroundStaticRequest? = null
    private var response: SurroundStaticResult? = null
    private var responseRequest: SurroundStaticRequest? = null
    private var revision = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled get() = model.active(focus, key)
    private val color get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x77)
    private val secondary get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x48)
    private fun snapshot(): SurroundFocusModel = SurroundFocusModel(settings, rate, SurroundController.enabled(context),
        SurroundController.supported(context), SurroundController.professionalSupported(context), SurroundController.listeningSupported(context),
        SurroundProfessionalRuntime.audition, SurroundProfessionalRuntime.calibration,
        SurroundProfessionalRuntime.hasComparison(3), SurroundProfessionalRuntime.hasComparison(4))
    init {
        isLongClickable = true
        setOnLongClickListener {
            detailDialog?.dismiss()
            detailDialog = GlassAlertDialogBuilder(context)
                .setTitle(if (illustration) focus.rightTitle else focus.leftTitle)
                .setMessage(details()).setPositiveButton(android.R.string.ok, null).show()
            true
        }
        updateDescription()
    }
    fun setConfiguration(settings: SurroundController.Settings, rate: Int) {
        this.settings = settings.sanitized().let { it.copy(professional = it.professional.toMap()) }
        this.rate = rate.takeIf { it in 8000..768000 } ?: 48000
        val next = snapshot()
        if (next == model) return
        model = next; updateDescription(); updateResponse()
        if (active) invalidate()
    }
    fun setFocus(focus: SurroundChartFocus, key: String) {
        if (this.focus == focus && this.key == key) return
        this.focus = focus; this.key = key
        updateDescription(); updateResponse()
        if (active) invalidate()
    }
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (!active) { revision++; SurroundStaticWork.release(this); request = null; detailDialog?.dismiss(); detailDialog = null }
        else { updateResponse(); invalidate() }
    }
    private fun updateResponse() {
        if (illustration || !active) return
        val diagnostic = focus == SurroundChartFocus.DIAGNOSTIC || focus == SurroundChartFocus.DELAY
        if (!diagnostic && focus !in setOf(SurroundChartFocus.BAND, SurroundChartFocus.CROSSFEED, SurroundChartFocus.LEVEL)) {
            revision++; SurroundStaticWork.release(this); request = null; response = null; responseRequest = null; return
        }
        val selectedView = if (diagnostic) (if (focus == SurroundChartFocus.DELAY) 0 else model.raw("diagnostics_view"))
            else if (focus == SurroundChartFocus.BAND) model.raw("response_view") else 1
        val target = model.linearTarget(focus, key)
        val visibleKeys = setOf("enabled", "link", "lr_attenuation", "rl_attenuation", "lr_delay", "rl_delay",
            "hp_slope", "lp_slope", "mix", "pre_db", "output_db")
        val calculation = target.copy(professional = target.professional.filterKeys { it in visibleKeys } +
            mapOf("response_view" to if (diagnostic) 0 else selectedView,
                "diagnostics_view" to if (diagnostic) selectedView else 0,
                "impulse_window" to if (diagnostic && selectedView == 2) model.raw("impulse_window") else 20))
        val next = SurroundStaticRequest(calculation, rate, diagnostic, selectedView,
            if (diagnostic && selectedView == 2) model.raw("impulse_window") else 0)
        if (next == request) return
        // Keep a completed curve and its own axes while the newest parameter request runs.
        request = next; revision++
        val token = revision
        SurroundStaticWork.request(this, next) { value ->
            if (active && request == next && revision == token) {
                response = value; responseRequest = next; invalidate()
            }
        }
    }
    private fun updateDescription() { contentDescription = details() }
    private fun n(key: String) = model.number(key)
    private fun fmt(value: Double, decimals: Int = 1) = String.format(Locale.ROOT, "%." + decimals + "f", value)
    private fun signed(value: Double) = String.format(Locale.ROOT, "%+.1f", value)
    private fun px(value: Double) = plot.left + plot.width() * value.coerceIn(0.0, 1.0).toFloat()
    private fun py(value: Double) = plot.bottom - plot.height() * value.coerceIn(0.0, 1.0).toFloat()
    private fun fx(frequency: Double) = px(ln(frequency.coerceIn(20.0, model.highHz) / 20.0) / ln(model.highHz / 20.0))
    private fun frequencyEnd() = fmt(model.highHz / 1000, if (model.highHz % 1000 == 0.0) 0 else 1) + "k Hz"
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, space: Float,
        tint: Int = foreground, align: Paint.Align = Paint.Align.LEFT) {
        if (space <= 0) return
        textPaint.color = tint; textPaint.textAlign = align
        val count = textPaint.breakText(value, true, space, null)
        canvas.drawText(if (count == value.length) value else value.take((count - 1).coerceAtLeast(0)) + "…", x, y, textPaint)
    }
    private fun caption(canvas: Canvas, value: String) = text(canvas, value, d(3f), d(11f), width - d(6f), color)
    private fun footer(canvas: Canvas, value: String) = text(canvas,
        if (enabled) value else "未生效 · 保存目标预览", d(3f), height - d(3f), width - d(6f), ColorUtils.setAlphaComponent(foreground, 0xa0))
    private fun grid(canvas: Canvas, top: String, bottom: String, left: String, right: String) {
        canvas.drawRect(plot, gridPaint)
        for (index in 1..3) {
            canvas.drawLine(px(index / 4.0), plot.top, px(index / 4.0), plot.bottom, gridPaint)
            canvas.drawLine(plot.left, py(index / 4.0), plot.right, py(index / 4.0), gridPaint)
        }
        text(canvas, top, plot.left - d(3f), plot.top + d(3f), d(25f), align = Paint.Align.RIGHT)
        text(canvas, bottom, plot.left - d(3f), plot.bottom, d(25f), align = Paint.Align.RIGHT)
        text(canvas, left, plot.left, plot.bottom + d(11f), plot.width() / 2)
        text(canvas, right, plot.right, plot.bottom + d(11f), plot.width() / 2, align = Paint.Align.RIGHT)
    }
    private fun trace(canvas: Canvas, tint: Int = color, dashed: Boolean = false, value: (Double) -> Double) {
        path.rewind(); var started = false
        for (index in 0..128) {
            val position = index / 128.0; val y = value(position)
            if (!y.isFinite()) { started = false; continue }
            if (!started) { path.moveTo(px(position), py(y)); started = true } else path.lineTo(px(position), py(y))
        }
        stroke(canvas, tint, dashed)
    }
    private fun stroke(canvas: Canvas, tint: Int, dashed: Boolean = false) {
        linePaint.color = tint
        linePaint.pathEffect = if (dashed || !enabled) DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f) else null
        canvas.save(); canvas.clipRect(plot); canvas.drawPath(path, linePaint); canvas.restore()
        linePaint.pathEffect = null
    }
    private fun marker(canvas: Canvas, x: Float, tint: Int = color) {
        linePaint.color = tint; linePaint.pathEffect = DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f)
        canvas.drawLine(x, plot.top, x, plot.bottom, linePaint); linePaint.pathEffect = null
    }
    private fun point(canvas: Canvas, x: Float, y: Float, tint: Int = color) {
        fillPaint.color = tint; canvas.drawCircle(x, y, d(2.3f), fillPaint)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || width < d(55f) || height < d(55f)) return
        plot.set(d(28f), d(21f), width - d(7f), height - d(26f))
        if (illustration) drawIllustration(canvas) else drawParameters(canvas)
    }
    private fun drawParameters(canvas: Canvas) = when (focus) {
        SurroundChartFocus.BAND, SurroundChartFocus.CROSSFEED, SurroundChartFocus.DELAY,
        SurroundChartFocus.LEVEL, SurroundChartFocus.DIAGNOSTIC -> drawCalculated(canvas)
        SurroundChartFocus.RMS -> drawRms(canvas)
        SurroundChartFocus.PEAK -> drawPeak(canvas)
        SurroundChartFocus.GEOMETRY -> drawGeometryNumbers(canvas)
        SurroundChartFocus.MATCH -> drawMatching(canvas)
        SurroundChartFocus.CALIBRATION -> drawCalibrationSpectrum(canvas)
        SurroundChartFocus.MONITOR -> drawMeterRoute(canvas)
        SurroundChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas) = when (focus) {
        SurroundChartFocus.BAND -> drawFrequencyRegions(canvas)
        SurroundChartFocus.CROSSFEED -> drawCrossPaths(canvas)
        SurroundChartFocus.DELAY -> drawArrival(canvas)
        SurroundChartFocus.LEVEL -> drawLevelBars(canvas)
        SurroundChartFocus.RMS -> drawRmsEnvelope(canvas)
        SurroundChartFocus.PEAK -> drawPeakRecovery(canvas)
        SurroundChartFocus.GEOMETRY -> drawGeometry(canvas)
        SurroundChartFocus.MATCH -> drawMeasurementWindow(canvas)
        SurroundChartFocus.CALIBRATION -> drawCalibrationEnvelope(canvas)
        SurroundChartFocus.DIAGNOSTIC -> drawDiagnosticMeaning(canvas)
        SurroundChartFocus.MONITOR -> drawMeterChannels(canvas)
        SurroundChartFocus.AUDITION -> drawComparison(canvas)
    }
    private fun drawCalculated(canvas: Canvas) {
        val current = response
        if (current == null || current.failed) {
            caption(canvas, if (current?.failed == true) "响应暂不可用" else "静态响应计算中")
            grid(canvas, "", "", if (focus == SurroundChartFocus.DELAY) "20 Hz" else "", "")
            footer(canvas, "当前保存参数 · 线性模型")
            return
        }
        val displayed = responseRequest ?: request
        val diagnostic = displayed?.diagnostic == true
        val impulse = diagnostic && displayed?.view == 2
        val pending = displayed != request
        val displayedHighHz = min(20000.0, (displayed?.rate ?: rate) * .45)
        var minimum = if (diagnostic) Double.POSITIVE_INFINITY else -36.0
        var maximum = if (diagnostic) Double.NEGATIVE_INFINITY else 6.0
        current.values.forEach { series -> series.forEach { value ->
            if (value.isFinite()) { minimum = min(minimum, value); maximum = max(maximum, value) }
        } }
        if (!minimum.isFinite() || !maximum.isFinite()) { minimum = -1.0; maximum = 1.0 }
        if (maximum - minimum < .01) { minimum -= .5; maximum += .5 }
        val padding = (maximum - minimum) * .07
        minimum -= padding; maximum += padding
        caption(canvas, when {
            diagnostic -> listOf("展开相位 °", "群延迟 ms", "脉冲响应 · 幅度")[displayed?.view ?: 0]
            else -> listOf("抵消处理频段 dB", "直达／交叉响应 dB", "同相／反相响应 dB")[displayed?.view ?: 0]
        })
        grid(canvas, fmt(maximum, if (abs(maximum) < 10) 1 else 0), fmt(minimum, if (abs(minimum) < 10) 1 else 0),
            if (impulse) "0 ms" else "20 Hz", if (impulse) "${displayed?.window} ms" else fmt(displayedHighHz / 1000, 1) + "k Hz")
        val palette = listOf(color, secondary, third, ColorUtils.setAlphaComponent(color, 0x85))
        current.values.forEachIndexed { channel, series ->
            path.rewind(); var started = false
            fun add(index: Int) {
                val value = series[index]
                if (!value.isFinite()) { started = false; return }
                val x = if (current.logarithmic) px(ln(current.x[index].coerceIn(20.0, displayedHighHz) / 20.0) / ln(displayedHighHz / 20.0))
                    else px(current.x[index] / (displayed?.window ?: 20).coerceAtLeast(1))
                val y = py((value - minimum) / (maximum - minimum))
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            if (impulse && series.size > 512) {
                // Preserve narrow direct/echo peaks while bounding Canvas work in a half-width chart.
                val bucket = ceil(series.size / 200.0).toInt()
                var from = 0
                while (from < series.size) {
                    val until = min(from + bucket, series.size)
                    var low = from; var high = from
                    for (index in from + 1 until until) { if (series[index] < series[low]) low = index; if (series[index] > series[high]) high = index }
                    add(min(low, high)); if (low != high) add(max(low, high))
                    from = until
                }
            } else series.indices.forEach(::add)
            stroke(canvas, palette[channel % palette.size], channel == 1 || channel == 3)
        }
        if (focus == SurroundChartFocus.BAND) {
            val saved = displayed?.settings ?: settings
            val scale = min(1.0, displayedHighHz / saved.highHz)
            fun markerX(hz: Double) = px(ln(hz.coerceIn(20.0, displayedHighHz) / 20.0) / ln(displayedHighHz / 20.0))
            marker(canvas, markerX(saved.lowHz * scale), secondary); marker(canvas, markerX(saved.highHz * scale), third)
        }
        footer(canvas, (if (pending) "更新中 · " else "") +
            if (diagnostic) "紫／青 直达 · 金／虚线 交叉" else current.labels.joinToString(" / "))
    }
    private fun drawRms(canvas: Canvas) {
        val limit = n("rms_limit"); val onlyDown = model.on("rms_attenuation_only")
        caption(canvas, "补偿范围 " + (if (onlyDown) "−" else "±") + fmt(limit) + " dB")
        grid(canvas, "+6 dB", "−6", "输出偏差 −12", "+12 dB")
        trace(canvas) { ((12 - 24 * it).coerceIn(-limit, if (onlyDown) 0.0 else limit) + 6) / 12 }
        marker(canvas, px(.5), secondary)
        footer(canvas, "相对预衰减原声 · 输出校准之前")
    }
    private fun drawPeak(canvas: Canvas) {
        val ceiling = n("peak_ceiling")
        caption(canvas, "采样峰值上限 " + fmt(ceiling) + " dBFS")
        grid(canvas, "0 dBFS", "−24", "输入 −24", "+6 dBFS")
        trace(canvas, secondary, true) { (-24 + 30 * it + 24) / 24 }
        trace(canvas) { (min(-24 + 30 * it, ceiling) + 24) / 24 }
        marker(canvas, px((ceiling + 24) / 30), third)
        footer(canvas, "本模块采样峰值 · 非真峰值保证")
    }
    private fun drawGeometryNumbers(canvas: Canvas) {
        val geometry = model.geometry
        caption(canvas, "对称摆位 · 343 m/s 路径估算")
        text(canvas, "夹角 " + fmt(geometry.angleDegrees, 2) + "°", plot.left, py(.82), plot.width(), color)
        text(canvas, "路程差 " + fmt(geometry.differenceMm, 2) + " mm", plot.left, py(.56), plot.width(), secondary)
        text(canvas, "延迟 " + fmt(geometry.delayTenthsUs / 10.0) + " μs", plot.left, py(.30), plot.width(), third)
        footer(canvas, if (geometry.applicable) "应用仅写入双向延迟目标" else "超出 20–210 μs · 不强行截断")
    }
    private fun drawMatching(canvas: Canvas) {
        val limit = n("match_limit")
        caption(canvas, "只衰减较响组 · 最大 " + fmt(limit) + " dB")
        grid(canvas, "0 dB", "−12", "A/B 差异 0", "+12 dB")
        trace(canvas) { 1 - min(12 * it, limit) / 12 }
        marker(canvas, px(limit / 12), secondary)
        footer(canvas, "测量完成后固定 · 非持续 AGC")
    }
    private fun drawCalibrationSpectrum(canvas: Canvas) {
        val noise = model.raw("cal_mode") == 3
        caption(canvas, if (noise) "带限噪声 · 静态频段目标" else "正弦信号 · 静态频率目标")
        grid(canvas, fmt(n("cal_level")) + " dBFS", "", "20 Hz", frequencyEnd())
        if (noise) {
            val low = min(n("cal_low"), rate * .45 - 1); val high = min(n("cal_high"), rate * .45)
            fillPaint.color = ColorUtils.setAlphaComponent(color, 0x30)
            canvas.drawRect(fx(low), py(.8), fx(high), plot.bottom, fillPaint)
            marker(canvas, fx(low), secondary); marker(canvas, fx(high), third)
        } else {
            val frequency = min(n("cal_frequency"), rate * .45)
            val x = fx(frequency)
            linePaint.color = color; canvas.drawLine(x, plot.bottom, x, py(.85), linePaint); point(canvas, x, py(.85))
        }
        footer(canvas, listOf("左声道", "右声道", "居中单声道", "居中带限噪声")[model.raw("cal_mode")] + " · 仅预览")
    }
    private fun box(canvas: Canvas, label: String, left: Double, top: Double, right: Double, bottom: Double, tint: Int = color) {
        val bounds = RectF(px(left), py(top), px(right), py(bottom))
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x24); canvas.drawRoundRect(bounds, d(3f), d(3f), fillPaint)
        text(canvas, label, bounds.centerX(), bounds.centerY() + d(3f), bounds.width() - d(3f), tint, Paint.Align.CENTER)
    }
    private fun route(canvas: Canvas, x1: Double, y1: Double, x2: Double, y2: Double, tint: Int = color) {
        linePaint.color = tint; canvas.drawLine(px(x1), py(y1), px(x2), py(y2), linePaint); point(canvas, px(x2), py(y2), tint)
    }
    private fun drawMeterRoute(canvas: Canvas) {
        caption(canvas, "输入 → 本模块处理 → 监测 → 试听")
        box(canvas, "输入", .0, .94, .32, .65, secondary)
        box(canvas, "处理", .64, .94, 1.0, .65)
        route(canvas, .32, .8, .64, .8)
        box(canvas, "监测点", .0, .40, .42, .10, third)
        box(canvas, "试听", .64, .40, 1.0, .10, secondary)
        route(canvas, .82, .65, .21, .40, third); route(canvas, .42, .25, .64, .25, secondary)
        footer(canvas, model.on("meters").let { if (it) "模块监测开启 · 试听之前" else "模块监测关闭 · 音频继续处理" })
    }
    private fun drawAudition(canvas: Canvas) {
        caption(canvas, "当前 · " + SurroundProfessionalRuntime.auditionLabels.getOrElse(model.audition) { "正常处理" })
        val labels = listOf("正常", "原声", "增量", "参数 A", "参数 B")
        labels.forEachIndexed { index, label ->
            val top = if (index < 3) .95 else .40
            val column = if (index < 3) index else index - 3
            val count = if (index < 3) 3.0 else 2.0
            box(canvas, label, column / count + .02, top, (column + 1) / count - .02, top - .30,
                if (model.audition == index) color else secondary)
        }
        footer(canvas, "当前状态只读 · 原生按钮控制试听")
    }
    private fun drawFrequencyRegions(canvas: Canvas) {
        val target = model.linearTarget(focus, key)
        val scale = min(1.0, rate * .45 / settings.highHz)
        val low = settings.lowHz * scale; val high = settings.highHz * scale
        val hp = if (P.on(target, "enabled")) 6 * (1 shl P.value(target, "hp_slope")) else 12
        val lp = if (P.on(target, "enabled")) 6 * (1 shl P.value(target, "lp_slope")) else 12
        caption(canvas, "仅频段内参与串音抵消")
        grid(canvas, "作用", "保留", "20 Hz", frequencyEnd())
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x25)
        canvas.drawRect(fx(low), plot.top, fx(high), plot.bottom, fillPaint)
        marker(canvas, fx(low), secondary); marker(canvas, fx(high), third)
        text(canvas, hp.toString() + " dB/oct", plot.left, py(.74), plot.width() * .5f, secondary)
        text(canvas, lp.toString() + " dB/oct", plot.right, py(.34), plot.width() * .5f, third, Paint.Align.RIGHT)
        footer(canvas, fmt(low, 0) + "–" + fmt(high, 0) + " Hz · 柔和边界")
    }
    private fun head(canvas: Canvas, x: Float, y: Float, radius: Float) {
        linePaint.color = secondary
        canvas.drawOval(RectF(x - radius * .7f, y - radius, x + radius * .7f, y + radius), linePaint)
        canvas.drawLine(x - radius * .20f, y - radius, x, y - radius * 1.22f, linePaint)
        canvas.drawLine(x, y - radius * 1.22f, x + radius * .20f, y - radius, linePaint)
        fillPaint.color = secondary; canvas.drawCircle(x - radius * .8f, y, d(2f), fillPaint); canvas.drawCircle(x + radius * .8f, y, d(2f), fillPaint)
    }
    private fun drawCrossPaths(canvas: Canvas) {
        val lr = model.branchDb(false, focus, key); val rl = model.branchDb(true, focus, key)
        caption(canvas, "抵消支路 · 交叉路径为反向混入")
        head(canvas, plot.centerX(), py(.35), plot.height() * .22f)
        listOf(false, true).forEach { reverse ->
            val attenuation = if (reverse) rl else lr
            val gain = 10.0.pow(-attenuation / 20)
            val tint = if (reverse) third else color
            val start = if (reverse) .92 else .08; val end = if (reverse) .36 else .64
            path.rewind(); path.moveTo(px(start), py(.95)); path.quadTo(px(if (reverse) .95 else .05), py(.32), px(end), py(.35))
            linePaint.strokeWidth = d((.7 + 3.5 * gain).toFloat())
            stroke(canvas, ColorUtils.setAlphaComponent(tint, (80 + 175 * gain).roundToInt()))
            point(canvas, px(start), py(.95), tint)
        }
        linePaint.strokeWidth = d(1.5f)
        footer(canvas, "左→右 −" + fmt(lr, 3) + " / 右→左 −" + fmt(rl, 3) + " dB")
    }
    private fun drawArrival(canvas: Canvas) {
        val lr = model.delayUs(false, focus, key); val rl = model.delayUs(true, focus, key)
        caption(canvas, "附加延迟 · 静态到达时序")
        grid(canvas, "左→右", "右→左", "0 μs", "210 μs")
        listOf(lr, rl).forEachIndexed { index, delay ->
            val y = py(if (index == 0) .72 else .25); val tint = if (index == 0) color else third
            linePaint.color = tint
            canvas.drawLine(plot.left, y, px(delay / 210), y, linePaint)
            point(canvas, px(delay / 210), y, tint)
            text(canvas, fmt(delay * rate / 1e6, 3) + " 样本", plot.left, y - d(5f), plot.width(), tint)
        }
        footer(canvas, fmt(lr) + " / " + fmt(rl) + " μs · 线性分数延迟")
    }
    private fun bar(canvas: Canvas, index: Int, label: String, decibels: Double, tint: Int) {
        val cell = plot.width() / 3
        val left = plot.left + cell * index + cell * .20f
        val normalized = (decibels + 24) / 30
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x45)
        canvas.drawRoundRect(RectF(left, py(normalized), left + cell * .60f, plot.bottom), d(2f), d(2f), fillPaint)
        text(canvas, signed(decibels), left + cell * .30f, max(plot.top + d(9f), py(normalized) - d(3f)), cell, tint, Paint.Align.CENTER)
        text(canvas, label, left + cell * .30f, plot.bottom + d(11f), cell, foreground, Paint.Align.CENTER)
    }
    private fun drawLevelBars(canvas: Canvas) {
        caption(canvas, "参考增益 · 混合 " + fmt(n("mix")) + "%")
        bar(canvas, 0, "输入", n("pre_db"), secondary)
        bar(canvas, 1, "校准", n("output_db"), third)
        bar(canvas, 2, "两级合计", n("pre_db") + n("output_db"), color)
        footer(canvas, "不含抵消矩阵／RMS／峰值变化")
    }
    private fun envelope(canvas: Canvas, attack: Double, release: Double, scale: Double = 1.0): Double {
        val total = 4 * (attack + release)
        trace(canvas) { fraction ->
            val time = fraction * total
            scale * if (time < 4 * attack) 1 - exp(-time / attack) else exp(-(time - 4 * attack) / release)
        }
        marker(canvas, px(attack / (attack + release)), secondary)
        return total
    }
    private fun drawRmsEnvelope(canvas: Canvas) {
        val attack = n("rms_attack"); val release = n("rms_release")
        caption(canvas, "衰减 " + fmt(attack, 0) + " / 恢复 " + fmt(release, 0) + " ms")
        grid(canvas, "6 dB", "0", "0", fmt(4 * (attack + release), 0) + " ms")
        envelope(canvas, attack, release, n("rms_limit") / 6)
        footer(canvas, if (model.on("rms_attenuation_only")) "仅衰减 · RMS 匹配响应示意" else "双向匹配 · RMS 响应示意")
    }
    private fun drawPeakRecovery(canvas: Canvas) {
        val release = n("peak_release")
        caption(canvas, "峰值保护恢复 " + fmt(release, 0) + " ms")
        grid(canvas, "衰减", "恢复", "0", "2000 ms")
        trace(canvas) { exp(-2000 * it / release) }
        marker(canvas, px(release / 2000), secondary)
        footer(canvas, "恢复包络示意 · 上限 " + fmt(n("peak_ceiling")) + " dBFS")
    }
    private fun drawGeometry(canvas: Canvas) {
        val spacing = P.value(settings, "speaker_spacing").toDouble()
        val distance = P.value(settings, "listening_distance").toDouble()
        val earSpacing = P.value(settings, "ear_spacing").toDouble()
        val top = d(33f); val bottom = height - d(33f)
        if (bottom <= top) return
        val scale = min((width - d(42f)) / max(spacing, earSpacing * 2).toFloat(),
            (bottom - top) / distance.toFloat())
        val centerX = width * .5f
        val earY = top + distance.toFloat() * scale
        val speakers = floatArrayOf(centerX - spacing.toFloat() * scale / 2,
            centerX + spacing.toFloat() * scale / 2)
        val ears = floatArrayOf(centerX - earSpacing.toFloat() * scale / 2,
            centerX + earSpacing.toFloat() * scale / 2)
        val oval = RectF()
        caption(canvas, "扬声器 / 双耳俯视 · 摆位模型")
        linePaint.pathEffect = null
        repeat(2) { index ->
            val tint = if (index == 0) color else secondary
            val attenuation = model.branchDb(index == 1, focus, key)
            val opacity = (200 - attenuation.coerceIn(0.0, 12.0) * 10).roundToInt().coerceIn(60, 200)
            val delay = model.delayUs(index == 1, focus, key)
            linePaint.strokeWidth = d(1.2f)
            linePaint.color = ColorUtils.setAlphaComponent(foreground, 0x50)
            canvas.drawLine(speakers[index], top, ears[index], earY, linePaint)
            val other = 1 - index
            linePaint.color = ColorUtils.setAlphaComponent(tint, opacity)
            linePaint.strokeWidth = d((2.1 - attenuation.coerceIn(0.0, 12.0) * .1).toFloat())
            linePaint.pathEffect = DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f)
            canvas.drawLine(speakers[index], top, ears[other], earY, linePaint)
            linePaint.pathEffect = null
            // Static ripple position encodes the saved delay; no recurring animation clock.
            val position = .24f + ((delay - 20) / 190).coerceIn(0.0, 1.0).toFloat() * .52f
            val waveX = speakers[index] + (ears[other] - speakers[index]) * position
            val waveY = top + (earY - top) * position
            for (ring in 1..3) {
                val radius = d(2f + ring * 2.2f)
                linePaint.color = ColorUtils.setAlphaComponent(tint, (opacity - ring * 28).coerceAtLeast(30))
                oval.set(waveX - radius, waveY - radius, waveX + radius, waveY + radius)
                canvas.drawArc(oval, 20f, 140f, false, linePaint)
            }
            val speaker = RectF(speakers[index] - d(7f), top - d(7f),
                speakers[index] + d(7f), top + d(10f))
            fillPaint.color = ColorUtils.setAlphaComponent(tint, 30)
            canvas.drawRoundRect(RectF(speaker.left + d(1f), speaker.top + d(1f),
                speaker.right + d(1f), speaker.bottom + d(1f)), d(3f), d(3f), fillPaint)
            fillPaint.color = ColorUtils.setAlphaComponent(tint, 55)
            canvas.drawRoundRect(speaker, d(2f), d(2f), fillPaint)
            linePaint.color = ColorUtils.setAlphaComponent(tint, 220); linePaint.strokeWidth = d(1f)
            canvas.drawRoundRect(speaker, d(2f), d(2f), linePaint)
            fillPaint.color = tint
            canvas.drawCircle(speakers[index], top - d(3f), d(1.4f), fillPaint)
            canvas.drawCircle(speakers[index], top + d(4f), d(3.2f), fillPaint)
            fillPaint.color = ColorUtils.setAlphaComponent(foreground, 100)
            canvas.drawCircle(speakers[index], top + d(4f), d(1.1f), fillPaint)
            point(canvas, ears[index], earY, foreground)
            text(canvas, if (index == 0) "L" else "R", speakers[index], top - d(8f), d(25f), tint, Paint.Align.CENTER)
        }
        linePaint.strokeWidth = d(1.5f); linePaint.pathEffect = null
        val headWidth = max(d(8f), (ears[1] - ears[0]) * .8f)
        oval.set(centerX - headWidth / 2, earY - headWidth * .55f,
            centerX + headWidth / 2, earY + headWidth * .55f)
        fillPaint.color = ColorUtils.setAlphaComponent(foreground, 18); canvas.drawOval(oval, fillPaint)
        linePaint.color = ColorUtils.setAlphaComponent(foreground, 145); linePaint.strokeWidth = d(1f)
        canvas.drawOval(oval, linePaint)
        path.rewind(); path.moveTo(centerX - d(1.3f), oval.top + d(.8f))
        path.lineTo(centerX, oval.top - d(2f)); path.lineTo(centerX + d(1.3f), oval.top + d(.8f))
        canvas.drawPath(path, linePaint)
        linePaint.strokeWidth = d(1.5f)
        text(canvas, "灰色直达 · 紫青交叉", centerX, height - d(17f), width - d(6f), foreground, Paint.Align.CENTER)
        footer(canvas, "波纹随支路延迟 · 非声程实测")
    }
    private fun drawMeasurementWindow(canvas: Canvas) {
        val window = n("match_window")
        caption(canvas, "同一段音乐 · 每组 " + fmt(window, 0) + " ms")
        grid(canvas, "测量", "等待", "0", "10500 ms")
        val stable = 500.0 / 10500; val end = (500 + window) / 10500
        fillPaint.color = ColorUtils.setAlphaComponent(secondary, 0x20)
        canvas.drawRect(plot.left, plot.top, px(stable), plot.bottom, fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x30)
        canvas.drawRect(px(stable), plot.top, px(end), plot.bottom, fillPaint)
        marker(canvas, px(stable), secondary); marker(canvas, px(end))
        footer(canvas, "先稳定 500 ms → 测量 → 固定衰减")
    }
    private fun drawCalibrationEnvelope(canvas: Canvas) {
        val duration = n("cal_duration"); val fade = n("cal_fade")
        val gain = 10.0.pow(n("cal_level") / 20)
        caption(canvas, fmt(duration / 1000, 2) + " s · 淡入出 " + fmt(fade, 0) + " ms")
        grid(canvas, "峰值", "0", "0", fmt(duration, 0) + " ms")
        trace(canvas) { position ->
            val time = position * duration
            val edge = min(time / fade, (duration - time) / fade).coerceIn(0.0, 1.0)
            edge * gain / .252
        }
        marker(canvas, px(fade / duration), secondary); marker(canvas, px(1 - fade / duration), third)
        footer(canvas, if (model.calibration == 0) "静态目标预览 · 由原生按钮开始" else "正在校准 · 图中仍为配置目标")
    }
    private fun drawDiagnosticMeaning(canvas: Canvas) {
        val view = model.raw("diagnostics_view")
        caption(canvas, listOf("频率越高，相位旋转随路径改变", "群延迟描述频率的到达时差", "直达脉冲与递归抵消尾部")[view])
        if (view == 2) {
            grid(canvas, "直达", "尾部", "0", model.raw("impulse_window").toString() + " ms")
            val delay = model.delayUs(false, focus, key) / 1000
            val window = n("impulse_window")
            val attenuation = 10.0.pow(-model.branchDb(false, focus, key) / 20)
            for (index in 0..8) {
                val time = index * delay
                if (time <= window) {
                    val x = px(time / window); val amplitude = attenuation.pow(index) * .85
                    linePaint.color = if (index % 2 == 0) color else third
                    canvas.drawLine(x, py(.1), x, py(.1 + amplitude), linePaint)
                }
            }
            footer(canvas, "回声列为路径示意 · 左图为精确模型")
        } else {
            head(canvas, plot.centerX(), py(.38), plot.height() * .22f)
            val delay = model.delayUs(false, focus, key) / 210
            linePaint.color = color
            repeat(3) { index ->
                val radius = (plot.width() * (.12 + .11 * index + .08 * delay)).toFloat()
                canvas.drawArc(RectF(plot.centerX() - radius, py(.38) - radius, plot.centerX() + radius, py(.38) + radius), 200f, 140f, false, linePaint)
            }
            footer(canvas, "线性相位与时间模型 · 非房间声场")
        }
    }
    private fun drawMeterChannels(canvas: Canvas) {
        caption(canvas, "RMS / 峰值 / 相关度")
        box(canvas, "输入 L/R", .02, .95, .98, .70, secondary)
        box(canvas, "输出 L/R", .02, .57, .98, .32)
        text(canvas, "RMS 补偿  /  峰值衰减", plot.centerX(), py(.05), plot.width(), third, Paint.Align.CENTER)
        footer(canvas, "真实读数见下方动态图和原生状态")
    }
    private fun drawComparison(canvas: Canvas) {
        caption(canvas, "当前页面快照 · 离页清除")
        box(canvas, "A " + (if (model.savedA) "已保存" else "未保存"), .0, .95, .45, .56, secondary)
        box(canvas, "B " + (if (model.savedB) "已保存" else "未保存"), .55, .95, 1.0, .56, third)
        box(canvas, "监测点在试听之前", .0, .35, 1.0, .04)
        route(canvas, .23, .56, .4, .35, secondary); route(canvas, .77, .56, .6, .35, third)
        footer(canvas, "静图显示保存参数 · 动图为实际处理")
    }
    private fun details(): String {
        val keys = when (focus) {
            SurroundChartFocus.BAND -> listOf("hp_slope", "lp_slope", "response_view")
            SurroundChartFocus.CROSSFEED -> listOf("enabled", "link", "lr_attenuation", "rl_attenuation")
            SurroundChartFocus.DELAY -> listOf("link", "lr_delay", "rl_delay")
            SurroundChartFocus.LEVEL -> listOf("enabled", "mix", "pre_db", "output_db")
            SurroundChartFocus.RMS -> listOf("rms_on", "rms_limit", "rms_attack", "rms_release", "rms_attenuation_only")
            SurroundChartFocus.PEAK -> listOf("peak_on", "peak_ceiling", "peak_release")
            SurroundChartFocus.GEOMETRY -> listOf("speaker_spacing", "listening_distance", "ear_spacing")
            SurroundChartFocus.MATCH -> listOf("match_on", "match_limit", "match_window")
            SurroundChartFocus.CALIBRATION -> listOf("cal_on", "cal_mode", "cal_level", "cal_frequency", "cal_low", "cal_high", "cal_duration", "cal_fade")
            SurroundChartFocus.DIAGNOSTIC -> listOf("diagnostics_on", "diagnostics_view", "impulse_window")
            SurroundChartFocus.MONITOR -> listOf("meters")
            SurroundChartFocus.AUDITION -> emptyList()
        }
        val choices = mapOf("hp_slope" to listOf("6 dB/oct", "12 dB/oct", "24 dB/oct"),
            "lp_slope" to listOf("6 dB/oct", "12 dB/oct", "24 dB/oct"),
            "response_view" to listOf("处理频段", "完整直达／交叉响应", "同相／反相信号响应"),
            "cal_mode" to listOf("左声道正弦", "右声道正弦", "居中单声道正弦", "居中带限噪声"),
            "diagnostics_view" to listOf("展开相位", "群延迟", "脉冲响应"))
        val lines = mutableListOf("当前保存参数 · " + rate + " Hz · " + (if (model.professional) "专业抵消" else "基础抵消"),
            if (enabled) "参数模型与相对示意；不代表房间实测。" else "当前参数未生效，显示保存目标的灰色预览。")
        if (focus == SurroundChartFocus.BAND) lines += "基础频段 " + settings.lowHz + "–" + settings.highHz + " Hz"
        if (focus == SurroundChartFocus.CROSSFEED) lines += "基础抵消支路衰减 " + fmt(settings.attenuationMilliDb / 1000.0, 3) + " dB"
        if (focus == SurroundChartFocus.DELAY) lines += "基础附加延迟 " + settings.delayUs + " μs；专业双向延迟的原始整数除以 10 才是 μs。"
        keys.forEach { name ->
            val entry = P.byKey.getValue(name); val value = model.raw(name)
            lines += entry.control.title + "：" + when (entry.kind) {
                "switch" -> if (value == 1) "开启" else "关闭"
                "choice" -> choices[name]?.getOrElse(value) { value.toString() } ?: value.toString()
                else -> entry.control.label(value)
            }
        }
        lines += when (focus) {
            SurroundChartFocus.BAND -> "基础低／高频边界为 10–800 Hz 与 4000–16000 Hz。内部采样率较低时两边按同一比例缩放。专业抵消关闭时使用原版 12 dB/oct；开启后两端各自使用 6／12／24 dB/oct Butterworth 斜率。完整矩阵响应包括递归抵消、分数延迟、干湿混合与增益，不含 RMS、峰值保护、试听匹配及后续音效。"
            SurroundChartFocus.CROSSFEED -> "本模块是负向串音抵消，区别于正向耳机交叉馈送。支路衰减存为千分之一 dB，正数越大，抵消支路越弱。专业抵消关闭时使用基础衰减与延迟；开启时使用双向精调。联动开启以左→右为共同值，右→左保存值保留；聚焦无效右路参数时仅以灰色独立路径预览，不解除真实联动。"
            SurroundChartFocus.DELAY -> "所有延迟为微秒，不是毫秒。基础延迟 20–210 μs，专业双向整数 200–2100 对应 20.0–210.0 μs。样本数 = 微秒 × 内部采样率 / 1000000；模型使用整采样延迟与相邻样本的线性分数插值，并包括递归路径。右图只是到达顺序示意，不是实际声音测量。"
            SurroundChartFocus.LEVEL -> "专业抵消开启后，输入预衰减先缩放原声，干湿采用线性混合，输出校准随后执行。左图使用完整线性矩阵；柱形仅展示两个增益级的配置目标，不冒充实时能量。专业关闭时这些保存参数仅以灰色预览。"
            SurroundChartFocus.RMS -> "RMS 与专业抵消开关独立。比较输入预衰减后的原声与处理声，并在输出校准之前匹配；可选择只衰减或双向补偿。衰减启动与增益恢复单位 ms，最大补偿单位 dB。时间线为一阶响应示意，不是 LUFS，也不代表设备最终响度。"
            SurroundChartFocus.PEAK -> "采样峰值保护与专业抵消开关独立，针对本模块离散样本。上限单位负 dBFS，恢复时间 ms；后续音效仍能继续改变电平，因此静态模型不提供最终采样峰值或真峰值保证。右图只表示恢复系数响应。"
            SurroundChartFocus.GEOMETRY -> "扬声器中心间距、听音距离与双耳间距均存为 mm，界面除以 10 显示 cm。按对称摆位和 343 m/s 声速计算左右路径差与延迟，未测量头部或房间。图中几何比例是参数模型，不是实时追踪。应用按钮仅写入当前槽双向延迟，超出 20–210 μs 不会强行截断，也不会自动开启专业处理。"
            SurroundChartFocus.MATCH -> "保存 A/B 后，使用同一段音乐分别试听测量；每组先等待 500 ms 稳定，再按设置窗口测量处理声相对原声 RMS，只衰减较响组。测完固定，不随音乐继续追踪；达到最大衰减仍可能不完全等响。清除测量只由原生按钮触发，图纸不会测量或改变快照。"
            SurroundChartFocus.CALIBRATION -> "校准允许开关不会开始发声；仍需原生开始按钮，并保持有效音频会话。所选信号在本模块前替换音乐，后续音效仍参与。频率按内部采样率限制，淡入／淡出和时长单位 ms。这里仅显示静态目标，不生成、开始或停止任何测试信号。"
            SurroundChartFocus.DIAGNOSTIC -> "左图复用原线性矩阵和时间递推计算：展开相位、群延迟 ms、脉冲幅度。包含双向路径、分数延迟与混合；不含动态 RMS、峰值、A/B 匹配及后续音效。低于 −120 dB 的路径不绘相位或群延迟；脉冲窗口之后的尾部未绘出。右图只是解释路径，不能换算真实串音消除量。"
            SurroundChartFocus.MONITOR -> "监测读取 James 内部输入与输出 RMS、采样峰值、相关度以及 RMS 补偿与峰值衰减，位置在临时试听分量选择之前。监测开关不会关闭实际抵消、RMS 或峰值处理；采样率不是设备最终输出采样率。上方通道图只说明测点，实时值见动态图与原生读数。"
            SurroundChartFocus.AUDITION -> "当前试听：" + SurroundProfessionalRuntime.auditionLabels.getOrElse(model.audition) { "正常处理" } + "。A 快照" + (if (model.savedA) "已保存" else "未保存") + "；B 快照" + (if (model.savedB) "已保存" else "未保存") + "。五路为正常、本模块原声、仅处理增量、参数 A、参数 B。快照保存在页面会话内，静图只观察保存参数；A/B 实际处理值见动态图。所有保存／试听／停止与校准仍由原生按钮执行。"
        }
        return lines.joinToString(System.lineSeparator() + System.lineSeparator())
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (active) updateResponse() }
    override fun onDetachedFromWindow() {
        revision++; SurroundStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}
