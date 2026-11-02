package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.*
import me.timschneeberger.rootlessjamesdsp.fusion.AnalogChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.AnalogController
import me.timschneeberger.rootlessjamesdsp.fusion.AnalogDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.AnalogParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** AnalogX display observers. Stored parameters, the tube page and all A/B operations stay independent. */
internal class AnalogParameterChart(context: Context) : AnalogFocusStaticBase(context, false)
internal class AnalogIllustrationChart(context: Context) : AnalogFocusStaticBase(context, true)

private data class AnalogFocusSnapshot(
    val settings: P.Settings, val rate: Int, val actualRate: Boolean, val moduleOn: Boolean,
    val supported: Boolean, val audition: Int, val matching: Boolean, val matchingReady: Boolean,
    val savedA: Boolean, val savedB: Boolean
) {
    fun baseFocus(focus: AnalogChartFocus, key: String): Boolean = focus == AnalogChartFocus.CORE &&
        key in setOf(P.BASE, P.ENABLED, "v_analog_pro_baseline")
    fun active(focus: AnalogChartFocus, key: String): Boolean {
        if (focus == AnalogChartFocus.AUDITION && key in setOf("v_analog_pro_save_a", "v_analog_pro_save_b")) return supported
        if (!moduleOn) return false
        if (baseFocus(focus, key)) return true
        if (!supported || !settings.professional) return false
        return when (focus) {
            AnalogChartFocus.CORE, AnalogChartFocus.HARMONICS -> settings.coreOn
            AnalogChartFocus.FILTER, AnalogChartFocus.TONE -> settings.toneOn
            AnalogChartFocus.MIX -> settings.mixOn
            AnalogChartFocus.LEVEL -> settings.outputOn
            AnalogChartFocus.QUALITY -> settings.qualityOn
            AnalogChartFocus.MONITOR -> settings.meters
            else -> true
        }
    }
    fun reason(focus: AnalogChartFocus, key: String): String = when {
        !moduleOn -> "谐波染色关闭 · 保存目标预览"
        !supported -> "专业驱动未支持 · 保存目标预览"
        !settings.professional -> "当前原三档 · 专业保存预览"
        focus == AnalogChartFocus.CORE || focus == AnalogChartFocus.HARMONICS -> "核心精调关闭 · 保存目标预览"
        focus == AnalogChartFocus.FILTER || focus == AnalogChartFocus.TONE -> "音色精调关闭 · 保存目标预览"
        focus == AnalogChartFocus.MIX -> "混合关闭 · 保存比例预览"
        focus == AnalogChartFocus.LEVEL -> "输出增益关闭 · 保存衰减预览"
        focus == AnalogChartFocus.QUALITY -> "过采样关闭 · 保存倍率预览"
        focus == AnalogChartFocus.MONITOR -> "电平显示关闭 · 测点示意"
        else -> "保存目标预览"
    }
    fun target(focus: AnalogChartFocus, key: String): P.Settings {
        if (baseFocus(focus, key)) return if (supported) settings.copy(meters = false)
            else settings.copy(professional = false, meters = false)
        val base: P.Settings = settings.copy(professional = true, meters = false)
        return when (focus) {
            AnalogChartFocus.CORE, AnalogChartFocus.HARMONICS -> base.copy(coreOn = true)
            AnalogChartFocus.FILTER, AnalogChartFocus.TONE -> base.copy(toneOn = true)
            AnalogChartFocus.MIX -> base.copy(mixOn = true)
            AnalogChartFocus.LEVEL -> base.copy(outputOn = true)
            AnalogChartFocus.QUALITY -> base.copy(qualityOn = true)
            else -> if (supported) settings.copy(meters = false) else P.defaults(settings.baseMode)
        }
    }
}

private data class AnalogStaticRequest(val settings: P.Settings, val rate: Int)
/** A bounded shared worker. Dragging replaces only the waiting target; completed curves stay visible. */
private object AnalogStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "analog-focus-static").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    private data class Task(val request: AnalogStaticRequest, val callback: (AnalogDashboardModel) -> Unit)
    private class Owner {
        var running: AnalogStaticRequest? = null
        var waiting: Task? = null
        var future: Future<*>? = null
    }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<AnalogStaticRequest, AnalogDashboardModel> =
        object : LinkedHashMap<AnalogStaticRequest, AnalogDashboardModel>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AnalogStaticRequest, AnalogDashboardModel>?): Boolean = size > 16
        }
    @Synchronized fun release(owner: Any): Unit {
        owners.remove(owner)?.let { state -> state.waiting = null; state.future?.cancel(true); state.future = null }
    }
    @Synchronized fun request(owner: Any, request: AnalogStaticRequest, callback: (AnalogDashboardModel) -> Unit): Unit {
        val state: Owner = owners.getOrPut(owner) { Owner() }
        if (state.running == request) { state.waiting = null; return }
        state.waiting = Task(request, callback)
        if (state.future == null) start(owner, state)
    }
    @Synchronized private fun start(owner: Any, state: Owner): Unit {
        val task: Task = state.waiting ?: return
        state.waiting = null; state.running = task.request
        state.future = executor.submit {
            if (Thread.currentThread().isInterrupted) return@submit
            val result: AnalogDashboardModel = synchronized(this) { cache[task.request] } ?: run {
                val values: Map<String, Any> = task.request.settings.values() + (P.ENABLED to true)
                AnalogDashboardModel.from(values, task.request.rate, true)
            }
            if (Thread.currentThread().isInterrupted) return@submit
            val deliver: Boolean = synchronized(this) {
                if (owners[owner] !== state) false else {
                    cache[task.request] = result; state.running = null; state.future = null
                    if (state.waiting != null) start(owner, state)
                    true
                }
            }
            if (deliver) main.post { task.callback(result) }
        }
    }
}

internal open class AnalogFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
    private val density: Float = resources.displayMetrics.density
    private fun d(value: Float): Float = value * density
    private val foreground: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val teal: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(110, 216, 208) else Color.rgb(17, 124, 117)
    private val amber: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(247, 184, 109) else Color.rgb(163, 90, 23)
    private val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(9f * resources.displayMetrics.scaledDensity, d(11f)) }
    private val linePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(1.5f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(.65f); color = ColorUtils.setAlphaComponent(foreground, 0x2a) }
    private val plot: RectF = RectF()
    private val path: Path = Path()
    private var configuration: AnalogDashboardModel = AnalogDashboardModel.from(emptyMap<String, Any>())
    private var shown: AnalogDashboardModel = configuration
    private var snapshot: AnalogFocusSnapshot = snapshot(configuration)
    private var focus: AnalogChartFocus = AnalogChartFocus.CORE
    private var key: String = P.BASE
    private var active: Boolean = true
    private var request: AnalogStaticRequest? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = snapshot.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x78)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x4c)
    private fun snapshot(model: AnalogDashboardModel): AnalogFocusSnapshot = AnalogFocusSnapshot(
        model.settings, model.rate, model.actualRate, model.enabled, AnalogController.supported(context),
        AnalogController.audition, AnalogController.match, AnalogController.matchingReady(),
        AnalogController.hasComparison(0), AnalogController.hasComparison(1))
    init {
        isLongClickable = true
        setOnLongClickListener {
            detailDialog?.dismiss()
            detailDialog = GlassAlertDialogBuilder(context).setTitle(if (illustration) focus.rightTitle else focus.leftTitle)
                .setMessage(details()).setPositiveButton(android.R.string.ok, null).show()
            true
        }
        updateDescription()
    }
    fun setConfiguration(model: AnalogDashboardModel): Unit {
        configuration = model
        val next: AnalogFocusSnapshot = snapshot(model)
        if (next == snapshot) return
        snapshot = next; updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setFocus(focus: AnalogChartFocus, key: String): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; AnalogStaticWork.release(this); request = null }
        this.focus = focus; this.key = key
        updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) {
            generation++; AnalogStaticWork.release(this); request = null
            detailDialog?.dismiss(); detailDialog = null
        } else { updateTarget(); invalidate() }
    }
    private fun updateTarget(): Unit {
        if (!active) return
        val target: P.Settings = snapshot.target(focus, key)
        val next: AnalogStaticRequest = AnalogStaticRequest(target, configuration.rate)
        if (request == next) return
        request = next
        val token: Long = generation
        AnalogStaticWork.request(this, next) { value ->
            if (active && token == generation) { shown = value; invalidate() }
        }
    }
    private fun updateDescription(): Unit { contentDescription = details() }
    private fun fmt(value: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%." + decimals + "f", value)
    private fun signed(value: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%+." + decimals + "f", value)
    private fun px(value: Double): Float = plot.left + plot.width() * value.coerceIn(0.0, 1.0).toFloat()
    private fun py(value: Double): Float = plot.bottom - plot.height() * value.coerceIn(0.0, 1.0).toFloat()
    private fun fx(frequency: Double): Float = px(ln(frequency.coerceIn(20.0, 20000.0) / 20) / ln(1000.0))
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, space: Float,
        tint: Int = foreground, align: Paint.Align = Paint.Align.LEFT): Unit {
        if (space <= 0) return
        textPaint.color = tint; textPaint.textAlign = align
        val count: Int = textPaint.breakText(value, true, space, null)
        canvas.drawText(if (count == value.length) value else value.take((count - 1).coerceAtLeast(0)) + "…", x, y, textPaint)
    }
    private fun caption(canvas: Canvas, value: String): Unit = text(canvas, value, d(3f), d(11f), width - d(6f), color)
    private fun footer(canvas: Canvas, value: String): Unit = text(canvas,
        if (enabled) value else snapshot.reason(focus, key), d(3f), height - d(3f), width - d(6f), ColorUtils.setAlphaComponent(foreground, 0xa0))
    private fun grid(canvas: Canvas, top: String, bottom: String, left: String, right: String): Unit {
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
    private fun stroke(canvas: Canvas, tint: Int, dashed: Boolean = false): Unit {
        linePaint.color = tint; linePaint.pathEffect = if (dashed || !enabled) DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f) else null
        canvas.save(); canvas.clipRect(plot); canvas.drawPath(path, linePaint); canvas.restore(); linePaint.pathEffect = null
    }
    private fun trace(canvas: Canvas, tint: Int = color, dashed: Boolean = false, value: (Double) -> Double): Unit {
        path.rewind(); var started: Boolean = false
        for (index in 0..160) {
            val x: Double = index / 160.0; val y: Double = value(x)
            if (!y.isFinite()) { started = false; continue }
            if (!started) { path.moveTo(px(x), py(y)); started = true } else path.lineTo(px(x), py(y))
        }
        stroke(canvas, tint, dashed)
    }
    private fun frequencyTrace(canvas: Canvas, values: DoubleArray, tint: Int, dashed: Boolean = false,
        floor: Double = -48.0, ceiling: Double = 12.0): Unit {
        path.rewind()
        shown.frequencies.forEachIndexed { index: Int, frequency: Double ->
            val x: Float = fx(frequency); val y: Float = py((values[index] - floor) / (ceiling - floor))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        stroke(canvas, tint, dashed)
    }
    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, tint: Int, dashed: Boolean = false): Unit {
        linePaint.color = tint; linePaint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f) else null
        canvas.drawLine(x1, y1, x2, y2, linePaint); linePaint.pathEffect = null
    }
    private fun dot(canvas: Canvas, x: Float, y: Float, tint: Int = color, radius: Float = d(2.2f)): Unit {
        fillPaint.color = tint; canvas.drawCircle(x, y, radius, fillPaint)
    }
    override fun onDraw(canvas: Canvas): Unit {
        super.onDraw(canvas)
        if (!active || width < d(55f) || height < d(55f)) return
        plot.set(d(28f), d(21f), width - d(7f), height - d(26f))
        if (illustration) drawIllustration(canvas) else drawParameters(canvas)
    }

    private fun drawParameters(canvas: Canvas): Unit = when (focus) {
        AnalogChartFocus.CORE -> drawCore(canvas)
        AnalogChartFocus.HARMONICS -> drawHarmonics(canvas)
        AnalogChartFocus.FILTER -> drawFilters(canvas)
        AnalogChartFocus.TONE -> drawTone(canvas)
        AnalogChartFocus.MIX -> drawMix(canvas)
        AnalogChartFocus.LEVEL -> drawLevel(canvas)
        AnalogChartFocus.QUALITY -> drawQuality(canvas)
        AnalogChartFocus.MONITOR -> drawMonitor(canvas)
        AnalogChartFocus.MATCH -> drawMatch(canvas)
        AnalogChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        AnalogChartFocus.CORE -> drawColorFlow(canvas)
        AnalogChartFocus.HARMONICS -> drawHarmonicWave(canvas)
        AnalogChartFocus.FILTER -> drawFilterRoutes(canvas)
        AnalogChartFocus.TONE -> drawToneBands(canvas)
        AnalogChartFocus.MIX -> drawBlend(canvas)
        AnalogChartFocus.LEVEL -> drawLevelWave(canvas)
        AnalogChartFocus.QUALITY -> drawSampleLattice(canvas)
        AnalogChartFocus.MONITOR -> drawMonitorRoutes(canvas)
        AnalogChartFocus.MATCH -> drawBalance(canvas)
        AnalogChartFocus.AUDITION -> drawListening(canvas)
    }
    private fun drawCore(canvas: Canvas): Unit {
        caption(canvas, shown.baseName + " · 驱动 " + signed(20 * log10(shown.drive)) + "dB · 注入 " + fmt(shown.amount * 100, 0) + "%")
        grid(canvas, "+3 FS", "−3", "−1.5 FS", "+1.5 FS")
        trace(canvas, ColorUtils.setAlphaComponent(foreground, 0x55), true) { x: Double -> .5 + (-1.5 + 3 * x) / 6 }
        trace(canvas, secondary, true) { x: Double -> .5 + .8 * (-1.5 + 3 * x) / 6 }
        trace(canvas) { x: Double -> .5 + shown.shape(-1.5 + 3 * x) / 6 }
        dot(canvas, px(.5), py(.5))
        footer(canvas, "去常数瞬时切片 · 不含滤波与去直流记忆")
    }
    private fun drawHarmonics(canvas: Canvas): Unit {
        caption(canvas, "系数 ×10⁻³ · 含本档原系数")
        grid(canvas, "+80", "−60", "", "")
        val zero: Float = py(60.0 / 140)
        line(canvas, plot.left, zero, plot.right, zero, ColorUtils.setAlphaComponent(foreground, 0x60))
        shown.harmonicCoefficients.forEachIndexed { index: Int, value: Double ->
            val cell: Float = plot.width() / 4; val x: Float = plot.left + (index + .5f) * cell
            val y: Float = py((value * 1000 + 60) / 140)
            val tint: Int = if (index % 2 == 0) color else secondary
            fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x65)
            canvas.drawRoundRect(RectF(x - cell * .22f, min(y, zero), x + cell * .22f, max(y, zero)), d(2f), d(2f), fillPaint)
            text(canvas, signed(value * 1000), x, if (value >= 0) max(plot.top + d(9f), y - d(3f)) else min(plot.bottom, y + d(10f)),
                cell - d(2f), tint, Paint.Align.CENTER)
            text(canvas, "H" + (index + 2), x, plot.bottom + d(11f), cell - d(2f), tint, Paint.Align.CENTER)
        }
        footer(canvas, "正负系数目标 · 不等于音乐谐波能量")
    }
    /** Isolated cookbook LP / octave-bandwidth PEQ reference, using the model's effective targets. */
    private fun filterDb(frequency: Double, peakOnly: Boolean): Double {
        val cutoff: Double = if (peakOnly) shown.peakHz else shown.lowpassHz
        val omega: Double = 2 * PI * cutoff / shown.rate
        val cosine: Double = cos(omega); val sine: Double = sin(omega)
        val alpha: Double = if (peakOnly) sinh(shown.peakBandwidthOctaves * ln(2.0) * omega / (2 * sine)) * sine
            else sine / (2 * shown.lowpassQ)
        val gain: Double = 10.0.pow(shown.peakGainDb / 40)
        val coefficients: DoubleArray = if (peakOnly) doubleArrayOf(1 + alpha * gain, -2 * cosine, 1 - alpha * gain,
            1 + alpha / gain, -2 * cosine, 1 - alpha / gain)
            else doubleArrayOf((1 - cosine) * .5, 1 - cosine, (1 - cosine) * .5, 1 + alpha, -2 * cosine, 1 - alpha)
        val angle: Double = 2 * PI * frequency / shown.rate
        val c1: Double = cos(angle); val s1: Double = sin(angle)
        val c2: Double = cos(2 * angle); val s2: Double = sin(2 * angle)
        val nr: Double = coefficients[0] + coefficients[1] * c1 + coefficients[2] * c2
        val ni: Double = coefficients[1] * s1 + coefficients[2] * s2
        val dr: Double = coefficients[3] + coefficients[4] * c1 + coefficients[5] * c2
        val di: Double = coefficients[4] * s1 + coefficients[5] * s2
        return 10 * log10(((nr * nr + ni * ni) / (dr * dr + di * di).coerceAtLeast(1e-24)).coerceAtLeast(1e-24))
    }
    private fun drawFilters(canvas: Canvas): Unit {
        caption(canvas, "HP " + fmt(shown.highpassHz, 0) + " · LP " + fmt(shown.lowpassHz, 0) + " Hz")
        grid(canvas, "+12 dB", "−48", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.preResidualDb, secondary)
        frequencyTrace(canvas, shown.frequencies.map { frequency: Double -> filterDb(frequency, false) }.toDoubleArray(), color)
        text(canvas, "Q " + fmt(shown.highpassQ, 3) + " / " + fmt(shown.lowpassQ, 3), plot.left + d(3f), plot.top + d(11f), plot.width() - d(6f))
        footer(canvas, "HP 只染色路 · LP 作用合并后的整路湿声")
    }
    private fun drawTone(canvas: Canvas): Unit {
        caption(canvas, fmt(shown.peakHz, 0) + "Hz · " + signed(shown.peakGainDb, 2) + "dB · " + fmt(shown.peakBandwidthOctaves, 2) + "oct")
        grid(canvas, "+12 dB", "−12", "20 Hz", "20 kHz")
        val response: DoubleArray = shown.frequencies.map { frequency: Double -> filterDb(frequency, true) }.toDoubleArray()
        frequencyTrace(canvas, response, color, floor = -12.0, ceiling = 12.0)
        line(canvas, plot.left, py(.5), plot.right, py(.5), ColorUtils.setAlphaComponent(foreground, 0x45), true)
        dot(canvas, fx(shown.peakHz), py((shown.peakGainDb + 12) / 24))
        footer(canvas, "峰形 EQ · 带宽单位 oct，不是 Q")
    }
    private fun bar(canvas: Canvas, index: Int, count: Int, amount: Double, label: String, number: String, tint: Int): Unit {
        val cell: Float = plot.width() / count; val x: Float = plot.left + index * cell + cell * .20f
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x45)
        canvas.drawRoundRect(RectF(x, py(amount), x + cell * .60f, plot.bottom), d(2f), d(2f), fillPaint)
        text(canvas, number, x + cell * .30f, max(plot.top + d(9f), py(amount) - d(3f)), cell, tint, Paint.Align.CENTER)
        text(canvas, label, x + cell * .30f, plot.bottom + d(11f), cell, foreground, Paint.Align.CENTER)
    }
    private fun drawMix(canvas: Canvas): Unit {
        caption(canvas, "干 " + fmt((1 - shown.mix) * 100) + "% · 湿 " + fmt(shown.mix * 100) + "%")
        grid(canvas, "1×", "0", "", "")
        bar(canvas, 0, 2, 1 - shown.mix, "对齐原声", fmt(1 - shown.mix, 3) + "×", secondary)
        bar(canvas, 1, 2, shown.mix, "完整染色", fmt(shown.mix, 3) + "×", color)
        footer(canvas, "线性混合 · 固有 ×0.8 已包含在湿声内")
    }
    private fun drawLevel(canvas: Canvas): Unit {
        val decibels: Double = 20 * log10(shown.outputGain)
        caption(canvas, "额外 " + signed(decibels) + "dB · " + fmt(shown.outputGain, 3) + "×")
        grid(canvas, "+1 FS", "−1", "−1 FS", "+1 FS")
        trace(canvas, secondary, true) { x: Double -> x }
        trace(canvas) { x: Double -> .5 + (2 * x - 1) * shown.outputGain * .5 }
        footer(canvas, "混合后固定衰减 · 0 dB 保留原固有电平")
    }
    private fun drawQuality(canvas: Canvas): Unit {
        caption(canvas, shown.effectiveFactor.toString() + "× · 对齐 " + shown.latencySamples + " 样本 / " + fmt(1000.0 * shown.latencySamples / shown.rate, 3) + " ms")
        grid(canvas, "+12 dB", "−48", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.reconstructionDb, color)
        footer(canvas, "FIR 升降配对参考 · 不依赖核心精调开关")
    }
    private fun drawMonitor(canvas: Canvas): Unit {
        caption(canvas, (if (configuration.actualRate) "实际 " else "参考 ") + configuration.rate + " Hz · 配置测点")
        grid(canvas, "输入", "输出", "处理前", "试听后")
        val labels: List<String> = listOf("输入峰值 / RMS", "正常完整染色效果", "当前试听输出")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.82 - index * .32)
            line(canvas, px(.07), y, px(.92), y, if (index == 1) third else color, !snapshot.settings.meters)
            dot(canvas, px(.10), y, secondary)
            text(canvas, label, px(.20), y - d(4f), plot.width() * .78f)
        }
        footer(canvas, "静图说明测点 · 实际电平见下方动态图")
    }
    private fun drawMatch(canvas: Canvas): Unit {
        caption(canvas, if (snapshot.matchingReady) "已测量 · " + (if (snapshot.matching) "匹配开启" else "匹配关闭") else "同一输入 · A/B 并行测量")
        grid(canvas, "预热", "测量", "0 s", "3 s")
        fillPaint.color = ColorUtils.setAlphaComponent(third, 0x55)
        canvas.drawRoundRect(RectF(px(0.0), py(.85), px(.5 / 3), py(.55)), d(2f), d(2f), fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x55)
        canvas.drawRoundRect(RectF(px(.5 / 3), py(.42), px(2.5 / 3), py(.12)), d(2f), d(2f), fillPaint)
        text(canvas, "≥500 ms", px(.05), py(.50), plot.width() * .75f, third)
        text(canvas, "2 秒 RMS", px(.30), py(.06), plot.width() * .65f, color)
        footer(canvas, "待支路稳定再测量 · 只衰减到最低能量")
    }
    private val routeLabels: List<String> = listOf("正常处理", "本模块原声", "处理后效果", "效果差分", "快照 A", "快照 B", "测量 A/B")
    private fun drawAudition(canvas: Canvas): Unit {
        caption(canvas, "A " + (if (snapshot.savedA) "已保存" else "未保存") + " · B " + (if (snapshot.savedB) "已保存" else "未保存"))
        routeLabels.forEachIndexed { index: Int, label: String ->
            val row: Int = index % 4; val column: Int = index / 4
            val x: Float = plot.left + plot.width() * (if (column == 0) .03f else .53f)
            val y: Float = plot.top + plot.height() * (.14f + .23f * row)
            dot(canvas, x, y - d(2f), if (snapshot.audition == index) color else ColorUtils.setAlphaComponent(foreground, 0x35))
            text(canvas, label, x + d(6f), y + d(1f), plot.width() * .43f,
                if (snapshot.audition == index) color else foreground)
        }
        footer(canvas, "当前 " + routeLabels[snapshot.audition.coerceIn(0, 6)] + " · 静图仍为保存参数")
    }
    private fun wave(canvas: Canvas, box: RectF, tint: Int, amplitude: Double = 1.0, shape: (Double) -> Double): Unit {
        path.rewind()
        for (index in 0..100) {
            val fraction: Double = index / 100.0
            val value: Double = shape(sin(fraction * 4 * PI)) * amplitude
            val x: Float = box.left + box.width() * fraction.toFloat()
            val y: Float = box.centerY() - box.height() * .34f * value.coerceIn(-1.5, 1.5).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        stroke(canvas, tint)
    }
    private fun roundedNode(canvas: Canvas, box: RectF, label: String, tint: Int, selected: Boolean = false): Unit {
        fillPaint.color = ColorUtils.setAlphaComponent(tint, if (selected) 0x30 else 0x10)
        canvas.drawRoundRect(box, d(4f), d(4f), fillPaint)
        linePaint.color = ColorUtils.setAlphaComponent(tint, if (selected) 0xd0 else 0x65)
        canvas.drawRoundRect(box, d(4f), d(4f), linePaint)
        text(canvas, label, box.centerX(), box.centerY() + d(3f), box.width() - d(4f), tint, Paint.Align.CENTER)
    }
    private fun drawColorFlow(canvas: Canvas): Unit {
        caption(canvas, shown.baseName + " · 基底注入 " + fmt(shown.baseGain, 1) + "× · 修正 " + fmt(shown.amount, 2) + "×")
        val topBox: RectF = RectF(plot.left, plot.top + d(8f), plot.right, plot.centerY() - d(5f))
        val bottomBox: RectF = RectF(plot.left, plot.centerY() + d(9f), plot.right, plot.bottom - d(8f))
        fillPaint.shader = LinearGradient(plot.left, plot.top, plot.right, plot.bottom,
            intArrayOf(ColorUtils.setAlphaComponent(secondary, 0x08), ColorUtils.setAlphaComponent(color, 0x28),
                ColorUtils.setAlphaComponent(third, 0x12)), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(plot.left, plot.top + d(3f), plot.right, plot.bottom), d(10f), d(10f), fillPaint)
        fillPaint.shader = null
        wave(canvas, topBox, secondary) { value: Double -> value }
        wave(canvas, bottomBox, ColorUtils.setAlphaComponent(foreground, 0x40)) { value: Double -> .8 * value }
        wave(canvas, bottomBox, color) { value: Double -> shown.shape(value) }
        text(canvas, "本模块输入", plot.left + d(3f), topBox.top, plot.width() - d(6f), secondary)
        text(canvas, "去常数染色切片", plot.left + d(3f), bottomBox.top, plot.width() - d(6f), color)
        val center: Float = plot.centerY() + d(1f)
        for (index in 0..2) {
            val x: Float = px(.18 + index * .32)
            dot(canvas, x, center, if (shown.baseMode == index) color else ColorUtils.setAlphaComponent(foreground, 0x35), d(2f))
            text(canvas, P.baseNames[index], x, plot.bottom + d(10f), plot.width() * .30f,
                if (shown.baseMode == index) color else foreground, Paint.Align.CENTER)
        }
        footer(canvas, "染色与原声合并 · 湿声固有 ×0.8")
    }
    private fun drawHarmonicWave(canvas: Canvas): Unit {
        caption(canvas, "偶次影响半波 · 奇次改变形状 · 系数示意")
        val box: RectF = RectF(plot.left, plot.top + d(5f), plot.right, py(.35))
        wave(canvas, box, ColorUtils.setAlphaComponent(foreground, 0x45)) { value: Double -> .8 * value }
        wave(canvas, box, color) { value: Double -> shown.shape(value) }
        val baseline: Float = py(.15)
        line(canvas, plot.left, baseline, plot.right, baseline, ColorUtils.setAlphaComponent(foreground, 0x45))
        shown.harmonicCoefficients.forEachIndexed { index: Int, coefficient: Double ->
            val x: Float = px((index + .5) / 4)
            val amount: Double = (coefficient / .08).coerceIn(-1.0, 1.0)
            val y: Float = baseline - plot.height() * .19f * amount.toFloat()
            val tint: Int = if (index % 2 == 0) color else secondary
            line(canvas, x, baseline, x, y, tint)
            dot(canvas, x, y, tint, d(2f))
            text(canvas, "H" + (index + 2), x, plot.bottom + d(10f), plot.width() / 4, tint, Paint.Align.CENTER)
        }
        footer(canvas, "正负表示整形方向 · 不是频谱实测柱")
    }
    private fun drawFilterRoutes(canvas: Canvas): Unit {
        caption(canvas, "低频原声保留 · 高频由整体低通收束")
        val rawY: Float = py(.80); val colorY: Float = py(.42); val finalY: Float = py(.08)
        line(canvas, px(.03), rawY, px(.93), rawY, secondary)
        line(canvas, px(.03), colorY, px(.93), colorY, color)
        text(canvas, "原声基底 · 不经前低切", px(.03), rawY - d(6f), plot.width() * .94f, secondary)
        text(canvas, "HP → 谐波染色支路", px(.03), colorY - d(6f), plot.width() * .94f, color)
        val frequencies: List<Double> = listOf(80.0, 1000.0, 12000.0)
        frequencies.forEachIndexed { index: Int, frequency: Double ->
            val position: Int = shown.frequencies.indices.minByOrNull { abs(shown.frequencies[it] - frequency) } ?: 0
            val amount: Double = 10.0.pow(shown.preResidualDb[position] / 20).coerceIn(0.0, 1.0)
            val x: Float = px(.16 + index * .29)
            fillPaint.color = ColorUtils.setAlphaComponent(color, (30 + 190 * amount).toInt())
            canvas.drawRoundRect(RectF(x - d(7f), colorY - d(3f), x + d(7f), colorY + d(3f)), d(2f), d(2f), fillPaint)
            text(canvas, listOf("低", "中", "高")[index], x, colorY + d(13f), d(20f), color, Paint.Align.CENTER)
        }
        line(canvas, px(.91), rawY, px(.91), finalY, third)
        roundedNode(canvas, RectF(px(.04), finalY - d(7f), px(.68), finalY + d(7f)), "合并后 LP + 音色", third, true)
        frequencies.forEachIndexed { index: Int, frequency: Double ->
            val amount: Double = 10.0.pow(filterDb(frequency, false) / 20).coerceIn(0.0, 1.2) / 1.2
            val x: Float = px(.75 + index * .08)
            fillPaint.color = ColorUtils.setAlphaComponent(third, (40 + 180 * amount).toInt())
            canvas.drawRoundRect(RectF(x - d(1.5f), finalY + d(5f) - d(12f) * amount.toFloat(), x + d(1.5f), finalY + d(5f)),
                d(1f), d(1f), fillPaint)
        }
        footer(canvas, "HP " + fmt(shown.highpassHz, 0) + " / LP " + fmt(shown.lowpassHz, 0) + " Hz · Q 决定拐点鼓起")
    }
    private fun drawToneBands(canvas: Canvas): Unit {
        caption(canvas, "补偿 " + signed(shown.peakGainDb, 2) + "dB · 宽 " + fmt(shown.peakBandwidthOctaves, 2) + "oct")
        val bands: List<Double> = listOf(80.0, 500.0, 4000.0)
        val labels: List<String> = listOf("低频", "主体", "明亮")
        val colors: List<Int> = listOf(third, color, secondary)
        for (index in 0..2) {
            val x1: Float = px(index / 3.0); val x2: Float = px((index + 1) / 3.0)
            fillPaint.shader = LinearGradient(x1, plot.top, x1, plot.bottom,
                ColorUtils.setAlphaComponent(colors[index], 0x2a), ColorUtils.setAlphaComponent(colors[index], 0x05), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(x1 + d(1f), plot.top + d(5f), x2 - d(1f), plot.bottom), d(6f), d(6f), fillPaint)
            fillPaint.shader = null
            val decibels: Double = filterDb(bands[index], true)
            val y: Float = py(.50 + decibels / 18)
            line(canvas, x1 + d(6f), py(.5), x2 - d(6f), py(.5), ColorUtils.setAlphaComponent(foreground, 0x35), true)
            line(canvas, (x1 + x2) / 2, py(.5), (x1 + x2) / 2, y, colors[index])
            dot(canvas, (x1 + x2) / 2, y, colors[index], d(3f))
            text(canvas, signed(decibels), (x1 + x2) / 2, max(plot.top + d(12f), y - d(6f)), x2 - x1 - d(2f), colors[index], Paint.Align.CENTER)
            text(canvas, labels[index], (x1 + x2) / 2, plot.bottom + d(10f), x2 - x1, colors[index], Paint.Align.CENTER)
        }
        footer(canvas, "80 / 500 / 4000 Hz 补偿目标 · 非听感测量")
    }

    private fun drawBlend(canvas: Canvas): Unit {
        caption(canvas, "干 " + fmt((1 - shown.mix) * 100) + "%  +  湿 " + fmt(shown.mix * 100) + "%")
        val splitX: Float = px(.10); val joinX: Float = px(.82)
        val dryY: Float = py(.77); val wetY: Float = py(.25)
        line(canvas, splitX, dryY, joinX, dryY, secondary, shown.mix >= 1)
        line(canvas, splitX, wetY, joinX, wetY, color, shown.mix <= 0)
        roundedNode(canvas, RectF(px(.22), dryY - d(10f), px(.72), dryY + d(10f)), "对齐原声", secondary, shown.mix < 1)
        roundedNode(canvas, RectF(px(.22), wetY - d(10f), px(.72), wetY + d(10f)), "完整染色", color, shown.mix > 0)
        line(canvas, splitX, dryY, splitX, wetY, foreground)
        line(canvas, joinX, dryY, joinX, wetY, third)
        dot(canvas, joinX, py(.50), third, d(6f))
        text(canvas, "+", joinX, py(.50) + d(3f), d(12f), Color.WHITE, Paint.Align.CENTER)
        fillPaint.color = ColorUtils.setAlphaComponent(secondary, 0x40)
        canvas.drawRect(RectF(plot.left, plot.bottom - d(2f), px(1 - shown.mix), plot.bottom + d(2f)), fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x60)
        canvas.drawRect(RectF(px(1 - shown.mix), plot.bottom - d(2f), plot.right, plot.bottom + d(2f)), fillPaint)
        footer(canvas, "百分比是混合系数 · 能量还取决于信号")
    }
    private fun drawLevelWave(canvas: Canvas): Unit {
        caption(canvas, "输出 " + signed(20 * log10(shown.outputGain)) + " dB · 固定衰减")
        val box: RectF = RectF(plot.left, plot.top + d(10f), plot.right, plot.bottom - d(8f))
        fillPaint.shader = LinearGradient(plot.left, plot.top, plot.left, plot.bottom,
            ColorUtils.setAlphaComponent(color, 0x22), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, d(8f), d(8f), fillPaint); fillPaint.shader = null
        wave(canvas, box, ColorUtils.setAlphaComponent(secondary, 0x85)) { value: Double -> value }
        wave(canvas, box, color, shown.outputGain) { value: Double -> value }
        text(canvas, "衰减前", plot.left, plot.top + d(4f), plot.width() * .5f, secondary)
        text(canvas, "衰减后", plot.right, plot.bottom + d(8f), plot.width() * .5f, color, Paint.Align.RIGHT)
        footer(canvas, "波形形状保留 · 幅度 ×" + fmt(shown.outputGain, 3))
    }
    private fun drawSampleLattice(canvas: Canvas): Unit {
        val factor: Int = shown.effectiveFactor
        caption(canvas, factor.toString() + "× 染色支路 · 升采样→塑形→重建")
        val waveBox: RectF = RectF(plot.left, plot.top + d(7f), plot.right, py(.42))
        wave(canvas, waveBox, ColorUtils.setAlphaComponent(color, 0x60)) { value: Double -> value * .70 }
        repeat(9) { index: Int ->
            val x: Float = plot.left + plot.width() * index / 8
            val y: Float = waveBox.centerY() - waveBox.height() * .44f * .70f * sin(index * PI / 2).toFloat()
            line(canvas, x, waveBox.bottom, x, y, ColorUtils.setAlphaComponent(secondary, 0x45))
            dot(canvas, x, y, secondary)
        }
        for (index in 0..8 * factor) {
            if (index % factor == 0) continue
            val fraction: Double = index / (8.0 * factor)
            val x: Float = plot.left + plot.width() * fraction.toFloat()
            val y: Float = waveBox.centerY() - waveBox.height() * .44f * .70f * sin(fraction * 4 * PI).toFloat()
            dot(canvas, x, y, third, d(1.2f))
        }
        val baseline: Float = py(.13)
        line(canvas, plot.left, baseline, plot.right, baseline, secondary)
        dot(canvas, plot.left, baseline, secondary)
        dot(canvas, px(shown.latencySamples / 40.0), baseline, color, d(3f))
        line(canvas, plot.left, baseline - d(8f), px(shown.latencySamples / 40.0), baseline - d(8f), color)
        text(canvas, "0", plot.left, baseline + d(12f), d(15f))
        text(canvas, shown.latencySamples.toString() + " 样本", plot.right, baseline + d(12f), plot.width() * .75f, color, Paint.Align.RIGHT)
        footer(canvas, "采样时刻示意 · 升降两段共 " + fmt(1000.0 * shown.latencySamples / shown.rate, 3) + " ms")
    }
    private fun drawMonitorRoutes(canvas: Canvas): Unit {
        caption(canvas, "本模块输入 · 正常效果 · 当前输出")
        val x1: Float = px(.02); val x2: Float = px(.98)
        roundedNode(canvas, RectF(x1, py(.93), x2, py(.72)), "输入 / 对齐原声", secondary, true)
        roundedNode(canvas, RectF(x1, py(.57), x2, py(.36)), "正常处理效果 · 试听前", color, true)
        roundedNode(canvas, RectF(x1, py(.21), x2, py(.00)), "当前输出 · 试听后", third, true)
        line(canvas, px(.50), py(.72), px(.50), py(.57), foreground)
        line(canvas, px(.50), py(.36), px(.50), py(.21), foreground)
        footer(canvas, "真实数值由动态图显示 · 不代表耳内声压")
    }
    private fun drawBalance(canvas: Canvas): Unit {
        caption(canvas, if (snapshot.matchingReady) "等电平结果就绪 · 只作固定衰减" else "保存 A + B → 预热 → 测量 → 对照")
        val cx: Float = px(.50); val centerY: Float = py(.56)
        line(canvas, cx, py(.13), cx, py(.80), color)
        line(canvas, px(.16), centerY, px(.84), centerY, color)
        path.rewind(); path.moveTo(px(.35), py(.10)); path.lineTo(cx, py(.24)); path.lineTo(px(.65), py(.10)); path.close()
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x35); canvas.drawPath(path, fillPaint)
        for (index in 0..1) {
            val x: Float = px(if (index == 0) .18 else .82)
            line(canvas, x, centerY, x, py(.34), secondary)
            val saved: Boolean = if (index == 0) snapshot.savedA else snapshot.savedB
            roundedNode(canvas, RectF(x - plot.width() * .16f, py(.34), x + plot.width() * .16f, py(.13)),
                if (index == 0) "A" else "B", if (saved) color else third, saved)
        }
        text(canvas, "原声 / A / B → 最低 RMS", cx, py(.92), plot.width(), color, Paint.Align.CENTER)
        footer(canvas, "图示测量流程 · 衰减值由驱动实测提供")
    }
    private fun drawListening(canvas: Canvas): Unit {
        val selected: Int = snapshot.audition.coerceIn(0, 6)
        caption(canvas, "当前：" + routeLabels[selected])
        val sourceLabels: List<String> = listOf("原声", "完整染色", "快照 A", "快照 B")
        val chosen: Set<Int> = when (selected) { 1 -> setOf(0); 4 -> setOf(2); 5 -> setOf(3); 6 -> setOf(0, 2, 3); else -> setOf(1) }
        sourceLabels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.85 - index * .23)
            val on: Boolean = index in chosen
            val tint: Int = if (on) color else ColorUtils.setAlphaComponent(foreground, 0x55)
            roundedNode(canvas, RectF(plot.left, y - d(7f), px(.52), y + d(7f)), label, tint, on)
            path.rewind(); path.moveTo(px(.52), y); path.cubicTo(px(.72), y, px(.66), py(.51), px(.84), py(.51))
            stroke(canvas, tint, !on)
        }
        val ear: RectF = RectF(px(.80), py(.69), plot.right, py(.32))
        linePaint.color = color; canvas.drawArc(ear, -85f, 280f, false, linePaint)
        line(canvas, ear.centerX(), ear.centerY(), ear.centerX(), ear.bottom, color)
        footer(canvas, if (selected == 3) "差分 = 完整效果 − 原声 · 不等于纯谐波" else "独立试听路径 · 保存参数不受图纸影响")
    }

    private fun details(): String {
        val s: P.Settings = snapshot.settings
        val status: String = if (snapshot.baseFocus(focus, key)) {
            (if (snapshot.moduleOn) "当前档位基础参考。" else "模块关闭，显示保存档位预览。") +
                (if (!snapshot.supported || !s.professional) "实际使用当前原三档声音。" else "已纳入当前生效的专业分组目标。")
        } else if (enabled) "本组已启用，显示保存参数目标。" else snapshot.reason(focus, key) + "；灰色图形只预览保存值，不会开启声音处理。"
        val explanation: String = when (focus) {
            AnalogChartFocus.CORE -> "基础档位：“" + P.baseNames[s.baseMode] + "”。三档染色支路增益依次为 0.6、1.2、2.4；原高切依次 19650、18233、16307 Hz，受有效采样率限制。\n保存输入驱动修正 " + signed(s.drive / 10.0) + " dB；染色注入倍率 " + fmt(s.amount / 10.0) + "% 。专业核心关闭时分别恢复 0 dB 与 100%。驱动作用于进入 Harmonic 多项式的信号，不是整体输出音量；非中性驱动或谐波偏移会限制整形输入范围。注入量只缩放染色支路，与三档原注入增益相乘。\n左图为去除常数项后的瞬时多项式切片：原声与染色合并，再乘湿声固有 0.8。虚线分别是原输入与只乘 0.8 的参考，不代表完整频响。真实处理还包括前低切、Harmonic 去直流与启动状态、后低通和音色补偿；静图不是实测波形或音乐的真实失真比例。"
            AnalogChartFocus.HARMONICS -> "原二至五次系数：0.020000、0.000100、0.001000、0.000000。保存偏移依次为 " +
                signed(s.h2 / 1000000.0, 6) + "、" + signed(s.h3 / 1000000.0, 6) + "、" +
                signed(s.h4 / 1000000.0, 6) + "、" + signed(s.h5 / 1000000.0, 6) + "。图中柱值包含原系数与保存偏移，并使用原 Harmonic 权重归一化约定；原始偏移单位 ppm。\n正负表示多项式整形方向，负系数不等于负能量。柱图是参数系数，不是实测频谱，也不是音乐中各次谐波所占百分比。右图展示去常数瞬时切片与相对系数方向，实际音乐还受输入频谱、幅度、驱动、滤波与记忆状态影响。四次与五次的独立原生滑杆仍按原回调处理。"
            AnalogChartFocus.FILTER -> "保存染色前低切 " + s.hp + " Hz / Q " + fmt(s.hpQ / 1000.0, 3) + "；当前档原高切 " + P.baseLowpass[s.baseMode] + " Hz，保存相对偏移 " + signed(s.lpDelta.toDouble(), 0) + " Hz / Q " + fmt(s.lpQ / 1000.0, 3) + "。\n低切只限制进入 Harmonic 的染色支路，原声基底保留；高切作用于原声与染色合并后的整路湿声。两条曲线各为独立二阶滤波幅度参考，不是把两滤波相乘冒充整个非线性模块频响。Q 改变拐点的共振与弯曲，不能等同于新的滚降阶数。原音色控制关闭时仍有本档原低切 240 Hz / Q 0.717 与原高切 / Q 0.717，专业关闭不会取消原有滤波。频率在/超过奈奎斯特时按原驱动移至 0.45×采样率。"
            AnalogChartFocus.TONE -> "保存峰形补偿中心 " + s.peak + " Hz，增益 " + signed(s.peakGain / 100.0, 2) + " dB，带宽 " + fmt(s.peakBW / 1000.0, 3) + " oct。这里是八度带宽，不是 Q 值；左图使用原 MultiBiquad 的 octave-bandwidth 约定。\n此峰形 EQ 作用于整路湿声，原音色为 633 Hz、+0.58 dB、6.28 oct；关闭专业音色后恢复该原补偿，并非变成完全平直。右图抽取 80 / 500 / 4000 Hz 三点的补偿目标，提示低频、主体、明亮频区的相对变化，不是测得的人声厚度或听感评价。"
            AnalogChartFocus.MIX -> "保存干湿混合 " + fmt(s.mix / 10.0) + "%；开启后 y = 原声×(1−混合) + 完整染色湿声×混合，再乘额外输出增益。两路按实际延迟对齐。\n湿声已经包含原固有 0.8 增益、原声与染色合并以及后低通/音色 EQ；原声支路没有额外乘 0.8。因此混合 0% 是本模块对齐原声，不是衰减到 80% 的原声。比例是线性混合系数，不是实测能量或听感百分比。混合分组关闭时实际为 100% 湿声。"
            AnalogChartFocus.LEVEL -> "保存染色输出增益 " + signed(s.output / 10.0) + " dB，额外线性系数 " + fmt(10.0.pow(s.output / 200.0), 4) + "。它位于干湿混合之后，0 dB 保留原模块固有电平，不补偿或取消湿声内部的 ×0.8。\n关闭本分组时额外系数为 1。图中直线与波形仅表示固定输出缩放，不包含三档注入、滤波或真实采样峰值；不能将此处系数当作整体音乐 RMS 变化。"
            AnalogChartFocus.QUALITY -> "保存过采样 " + s.factor + "×。原三档始终含非线性染色，所以专业过采样可独立于“核心谐波精调”开启。关闭时实际 1×、正常内核额外延迟 0 样本；2× / 4× 对染色支路作 Blackman 窗 FIR 升降采样，两段合计正常内核 31 个基准采样延迟（当前参考 " + fmt(31000.0 / snapshot.rate, 4) + " ms）。\n左图仅为两段 FIR 配对幅度参考；并非实际谐波生成量、THD 或整个模块的固定频响。右图是加密采样时刻示意，不冒充 FIR 的完整时域波形。原声另作相同延迟对齐；A/B 或切换过渡的共同延迟以下方真实状态为准。过采样增加计算量，不增加音源本来没有的信息。"
            AnalogChartFocus.MONITOR -> "原生“显示实时电平”保存状态：" + (if (s.meters) "开启" else "关闭") + "。原生输入/输出峰值与 RMS 来自本模块，专业模式与原生电平开关控制该组标量。新增只读音频图还可观察对齐原声、正常完整处理效果、当前试听输出，三者测点不同。\n完整染色效果包含原声基底和原有滤波，不等于纯新增泛音；效果差分也不等同于纯谐波。此静图只说明测点，真实数值见动态图及保留的原生说明。采样峰值不是耳内声压或真峰值；频谱幅度不能直接解读为 THD。"
            AnalogChartFocus.MATCH -> "快照 A " + (if (snapshot.savedA) "已保存" else "未保存") + "，B " + (if (snapshot.savedB) "已保存" else "未保存") + "；等电平匹配" + (if (snapshot.matching) "开启" else "关闭") + "。\n同一输入并行处理原声、A、B，预热至少 500 ms 并包含共同对齐，再测量 2 秒 RMS。有效时取三路最低能量，只作固定衰减，不动态追踪、不提升声音；这是等 RMS 电平，不代表心理声学的等响度。信号不足时不建立匹配。静图仅显示流程和快照是否存在，不读取私有快照参数、不代造实测衰减。"
            AnalogChartFocus.AUDITION -> "当前试听：“" + routeLabels[snapshot.audition.coerceIn(0, 6)] + "”。正常、本模块原声、完整处理后效果、效果差分、快照 A、快照 B、测量 A/B 是独立路线。效果差分为完整效果减去对齐原声，包含滤波、电平和谐波的共同差异，不是纯新增泛音。\n静图始终表示页面保存参数；试听 A/B 时真实音频输出以下方动态图与原生读数为准。所有原生保存快照、测量、匹配、试听和停止按钮继续负责原来的操作，图纸不写入其状态或参数。"
        }
        return (if (illustration) focus.rightTitle else focus.leftTitle) + "\n\n" + status + "\n\n" + explanation +
            "\n\n" + (if (snapshot.actualRate) "配置实际采样率 " else "参考采样率 ") + snapshot.rate + " Hz；静图仅为参数模型与相对示意。"
    }
    override fun onDetachedFromWindow(): Unit {
        generation++; AnalogStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}

