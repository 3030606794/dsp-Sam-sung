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
import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters
import me.timschneeberger.rootlessjamesdsp.fusion.DiffAdvancedParameters as A
import me.timschneeberger.rootlessjamesdsp.fusion.DiffChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.DiffController
import me.timschneeberger.rootlessjamesdsp.fusion.DiffParameters as P
import me.timschneeberger.rootlessjamesdsp.fusion.DiffResponse
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** These views observe saved targets only; they never own an audio editor or audition. */
internal class DiffParameterChart(context: Context) : DiffStaticChartBase(context, false)
internal class DiffIllustrationChart(context: Context) : DiffStaticChartBase(context, true)

private data class DiffComplex(val real: Double, val imaginary: Double = 0.0) {
    operator fun plus(other: DiffComplex) = DiffComplex(real + other.real, imaginary + other.imaginary)
    operator fun minus(other: DiffComplex) = DiffComplex(real - other.real, imaginary - other.imaginary)
    operator fun times(scale: Double) = DiffComplex(real * scale, imaginary * scale)
    operator fun div(other: DiffComplex): DiffComplex {
        val denominator = (other.real * other.real + other.imaginary * other.imaginary).coerceAtLeast(1e-24)
        return DiffComplex((real * other.real + imaginary * other.imaginary) / denominator,
            (imaginary * other.real - real * other.imaginary) / denominator)
    }
    fun magnitude() = hypot(real, imaginary)
}

private data class DiffStaticModel(
    val values: Map<String, *>, val rate: Int, val supported: Boolean, val advanced: Boolean, val audition: Int
) {
    val mode get() = if (supported) P.mode(values) else 0
    val professional get() = mode == 1
    val effectOn get() = values["v_diff"] == true
    val modeName get() = if (professional) "专业精密" else "原版兼容"
    val delayedLeft get() = professional && P.channel(values) == 1
    val channelName get() = if (delayedLeft) "左" else "右"
    val frequencyHigh get() = min(20000.0, rate * .5 - 100.0)
    fun raw(control: BassParameters.Control): Int = if (control.key in A.newKeys) A.value(values, control) else P.value(values, control)
    fun number(control: BassParameters.Control) = raw(control).toDouble() / control.divisor
    fun label(control: BassParameters.Control) = control.title + " " + control.label(raw(control))
    val fineOn get() = professional && advanced && A.enabled(values, A.FINE_ENABLED)
    val quality get() = if (advanced) A.quality(values) else 0
    val targetMilliseconds get() = if (!professional) number(P.legacy) else if (fineOn) number(A.fine) else number(P.delay)
    fun effectiveValues(preview: Boolean = false, forceBand: Boolean = false): Map<String, *> =
        values.toMutableMap().apply {
            put(P.MODE, if (preview) 1 else mode)
            if (!advanced) {
                put(A.QUALITY, 0); put(A.FINE_ENABLED, false); put(A.window.key, 40)
                put(A.wet.key, 0); put(A.output.key, 0)
            }
            if (forceBand) put(P.BAND_ENABLED, true)
        }
    val configuration get() = DiffResponse.configuration(rate, effectiveValues())
    fun responseConfiguration(focus: DiffChartFocus) = if (focus == DiffChartFocus.BAND)
        DiffResponse.configuration(rate, effectiveValues(preview = true, forceBand = true))
        else configuration
    fun commonSamples(configuration: DiffResponse.Configuration = this.configuration): Int {
        var block = 1
        while (block < configuration.rate / 375.0) block *= 2
        return (if (configuration.band) (configuration.rate * configuration.window / 2000.0).roundToInt() + block else 0) +
            if (configuration.quality == 1) 1 else 0
    }
    fun active(focus: DiffChartFocus, key: String): Boolean {
        if (!effectOn) return false
        if (focus == DiffChartFocus.MONITOR) return advanced // Monitoring boundaries also classify legacy output.
        if (focus == DiffChartFocus.AUDITION) return supported
        if (focus == DiffChartFocus.DELAY) return when (key) {
            P.legacy.key -> !professional
            P.delay.key -> professional && !fineOn
            A.fine.key -> fineOn
            A.FINE_ENABLED -> professional && advanced
            else -> true
        }
        if (!professional) return false
        return when (focus) {
            DiffChartFocus.MIX -> supported
            DiffChartFocus.BAND -> supported && P.bandEnabled(values) && (key != A.window.key || advanced)
            DiffChartFocus.QUALITY, DiffChartFocus.LEVEL -> advanced
            DiffChartFocus.GUARD -> advanced && A.enabled(values, A.GUARD_ENABLED) && when (key) {
                A.correlation.key, A.hysteresis.key -> A.detector(values) != 1
                A.fold.key -> A.detector(values) != 0
                else -> true
            }
            DiffChartFocus.TRANSIENT -> advanced && A.enabled(values, A.TRANSIENT_ENABLED)
            else -> true
        }
    }
    fun lowpass(frequency: Double, cutoff: Double): DiffComplex {
        val k = tan(PI * cutoff.coerceIn(1.0, rate * .5 - 100.0) / rate)
        val b = k / (1.0 + k); val a = (k - 1.0) / (k + 1.0)
        val w = 2.0 * PI * frequency / rate
        val z = DiffComplex(cos(w), -sin(w))
        return (DiffComplex(1.0) + z) * b / (DiffComplex(1.0) + z * a)
    }
    fun interpolation(frequency: Double): DiffComplex {
        val samples = configuration.delaySamples
        val fraction = samples - floor(samples)
        val weights = if (quality == 0) doubleArrayOf(1.0 - fraction, fraction) else
            doubleArrayOf(-fraction * (fraction - 1) * (fraction - 2) / 6,
                (1 + fraction) * (fraction - 1) * (fraction - 2) / 2,
                -(1 + fraction) * fraction * (fraction - 2) / 2,
                (1 + fraction) * fraction * (fraction - 1) / 6)
        val w = 2.0 * PI * frequency / rate
        return weights.foldIndexed(DiffComplex(0.0)) { index, sum, weight ->
            val phase = -w * (index - quality)
            sum + DiffComplex(cos(phase), sin(phase)) * weight
        }
    }
}

private data class DiffStaticResponse(
    val configuration: DiffResponse.Configuration,
    val points: List<DiffResponse.Point>,
    val band: List<Pair<Double, Double>>,
    val failed: Boolean = false
)

/** A shared, bounded latest-only worker. The two small views share identical FIR work. */
private object DiffStaticResponseWork {
    private val main = Handler(Looper.getMainLooper())
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "diff-focus-static-model").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(10, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private class Pending(val configuration: DiffResponse.Configuration) {
        val owners = IdentityHashMap<Any, (DiffStaticResponse) -> Unit>()
        var future: Future<*>? = null
    }
    private val jobs = linkedMapOf<DiffResponse.Configuration, Pending>()
    private val cache = object : LinkedHashMap<DiffResponse.Configuration, DiffStaticResponse>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DiffResponse.Configuration, DiffStaticResponse>?) = size > 6
    }
    @Synchronized fun request(owner: Any, configuration: DiffResponse.Configuration, callback: (DiffStaticResponse) -> Unit) {
        release(owner)
        cache[configuration]?.let { result -> main.post { callback(result) }; return }
        val existing = jobs[configuration]
        if (existing != null) { existing.owners[owner] = callback; return }
        val pending = Pending(configuration)
        pending.owners[owner] = callback; jobs[configuration] = pending
        pending.future = executor.submit {
            val result = try {
                DiffStaticResponse(configuration, DiffResponse.calculate(configuration, 80), firBand(configuration))
            } catch (_: CancellationException) { return@submit
            } catch (_: InterruptedException) { return@submit
            } catch (_: Exception) { DiffStaticResponse(configuration, emptyList(), emptyList(), true) }
            val callbacks = synchronized(this) {
                if (jobs[configuration] !== pending) return@synchronized emptyList()
                jobs.remove(configuration)
                if (!result.failed) cache[configuration] = result
                pending.owners.values.toList().also { pending.owners.clear() }
            }
            main.post { callbacks.forEach { it(result) } }
        }
    }
    @Synchronized fun release(owner: Any) {
        val iterator = jobs.entries.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next().value
            pending.owners.remove(owner)
            if (pending.owners.isEmpty()) { pending.future?.cancel(true); iterator.remove() }
        }
    }
    private fun firBand(c: DiffResponse.Configuration): List<Pair<Double, Double>> {
        if (!c.band) return emptyList()
        val half = (c.rate * c.window / 2000.0).roundToInt()
        val coefficients = DoubleArray(half + 1) { index ->
            if (index == 0) 2.0 * (c.high - c.low) / c.rate else
                (sin(2 * PI * c.high * index / c.rate) - sin(2 * PI * c.low * index / c.rate)) /
                    (PI * index) * (1.0 - index.toDouble() / (half + 1))
        }
        val upper = min(20000.0, c.rate * .5 - 100.0)
        return List(96) { index ->
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val hz = 20.0 * (upper / 20.0).pow(index / 95.0)
            val cosine = cos(2.0 * PI * hz / c.rate)
            var previous = 1.0; var current = cosine; var sum = coefficients[0]
            for (tap in 1..half) {
                sum += 2 * coefficients[tap] * current
                val next = 2 * cosine * current - previous; previous = current; current = next
            }
            hz to sum
        }
    }
}

internal open class DiffStaticChartBase(context: Context, private val illustration: Boolean) : View(context) {
    private val density = resources.displayMetrics.density
    private fun d(value: Float) = value * density
    private val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val secondary = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(111, 205, 229) else Color.rgb(17, 121, 149)
    private val tertiary = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(247, 180, 103) else Color.rgb(160, 85, 18)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(9f * resources.displayMetrics.scaledDensity, d(11f)) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foreground, 0x2c); style = Paint.Style.STROKE; strokeWidth = d(.7f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(1.5f) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plot = RectF()
    private val path = Path()
    private var values: Map<String, *> = emptyMap<String, Any>()
    private var rate = 48000
    private var model = snapshot()
    private var focus = DiffChartFocus.DELAY
    private var focusKey = P.MODE
    private var active = true
    private val workOwner = Any()
    private var revision = 0L
    private var requested: DiffResponse.Configuration? = null
    private var response: DiffStaticResponse? = null
    private var detailDialog: AlertDialog? = null
    private val color get() = if (model.active(focus, focusKey)) accent else ColorUtils.setAlphaComponent(foreground, 0x7a)
    private val other get() = if (model.active(focus, focusKey)) secondary else ColorUtils.setAlphaComponent(foreground, 0x59)
    private fun snapshot() = DiffStaticModel(values, rate, DiffController.supported(context),
        DiffController.advancedSupported(context), DiffController.audition)
    init {
        isLongClickable = true
        setOnLongClickListener {
            detailDialog?.dismiss()
            detailDialog = GlassAlertDialogBuilder(context).setTitle(if (illustration) focus.rightTitle else focus.leftTitle)
                .setMessage(details()).setPositiveButton(android.R.string.ok, null).show()
            true
        }
        contentDescription = details()
    }
    fun setConfiguration(values: Map<String, *>, rate: Int) {
        val keys = P.all.map { it.key } + P.newKeys + A.newKeys + "v_diff"
        this.values = values.filterKeys { it in keys }.toMap()
        this.rate = rate.takeIf { it in 8000..768000 } ?: 48000
        val next = snapshot()
        if (next == model) return
        model = next; contentDescription = details(); updateResponse()
        if (active) invalidate()
    }
    fun setFocus(focus: DiffChartFocus, key: String) {
        if (this.focus == focus && focusKey == key) return
        this.focus = focus; focusKey = key
        contentDescription = details(); updateResponse()
        if (active) invalidate()
    }
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (active) { updateResponse(); invalidate() } else {
            cancelWork(); detailDialog?.dismiss(); detailDialog = null
        }
    }
    private fun cancelWork() { revision++; requested = null; DiffStaticResponseWork.release(workOwner) }
    private fun updateResponse() {
        val needs = focus == DiffChartFocus.MIX || focus == DiffChartFocus.BAND
        if (!active || !isAttachedToWindow || !needs) { cancelWork(); return }
        val configuration = model.responseConfiguration(focus)
        if (requested == configuration) return
        requested = configuration; val token = ++revision
        DiffStaticResponseWork.request(workOwner, configuration) { result ->
            if (!active || !isAttachedToWindow || revision != token || requested != configuration) return@request
            response = result; invalidate()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateResponse() }
    override fun onDetachedFromWindow() {
        cancelWork(); detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
    private fun format(value: Double, digits: Int = 1) = String.format(Locale.ROOT, "%." + digits + "f", value)
    private fun signed(value: Double) = String.format(Locale.ROOT, "%+.1f", value)
    private fun amplitude(db: Double) = 10.0.pow(db / 20.0)
    private fun db(value: Double) = 20.0 * log10(value.coerceAtLeast(1e-9))
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, available: Float,
        tint: Int = foreground, align: Paint.Align = Paint.Align.LEFT) {
        if (available <= 0f) return
        textPaint.color = tint; textPaint.textAlign = align
        val count = textPaint.breakText(value, true, available, null)
        canvas.drawText(if (count >= value.length) value else value.take((count - 1).coerceAtLeast(0)) + "…", x, y, textPaint)
    }
    private fun caption(canvas: Canvas, value: String) = text(canvas, value, d(3f), d(11f), width - d(6f), color)
    private fun footer(canvas: Canvas, value: String) = text(canvas,
        if (!model.active(focus, focusKey)) "未生效 · 灰色参数预览" else value,
        d(3f), height - d(3f), width - d(6f), ColorUtils.setAlphaComponent(foreground, 0xa0))
    private fun grid(canvas: Canvas, top: String, bottom: String, left: String, right: String) {
        canvas.drawRect(plot, gridPaint)
        for (step in 1..3) {
            val x = plot.left + plot.width() * step / 4f
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
        }
        canvas.drawLine(plot.left, plot.centerY(), plot.right, plot.centerY(), gridPaint)
        text(canvas, top, plot.left - d(3f), plot.top + d(3f), d(25f), align = Paint.Align.RIGHT)
        text(canvas, bottom, plot.left - d(3f), plot.bottom, d(25f), align = Paint.Align.RIGHT)
        text(canvas, left, plot.left, plot.bottom + d(11f), plot.width() / 2f)
        text(canvas, right, plot.right, plot.bottom + d(11f), plot.width() / 2f, align = Paint.Align.RIGHT)
    }
    private fun px(position: Double) = plot.left + plot.width() * position.coerceIn(0.0, 1.0).toFloat()
    private fun py(position: Double) = plot.bottom - plot.height() * position.coerceIn(0.0, 1.0).toFloat()
    private fun hz(position: Double) = 20.0 * (model.frequencyHigh / 20.0).pow(position)
    private fun fx(frequency: Double) = px(ln(frequency.coerceIn(20.0, model.frequencyHigh) / 20.0) / ln(model.frequencyHigh / 20.0))
    private fun trace(canvas: Canvas, tint: Int, dashed: Boolean = false, value: (Double) -> Double) {
        path.rewind()
        for (index in 0..128) {
            val p = index / 128.0; val y = value(p)
            if (!y.isFinite()) continue
            if (index == 0) path.moveTo(px(p), py(y)) else path.lineTo(px(p), py(y))
        }
        linePaint.color = tint
        linePaint.pathEffect = if (dashed || !model.active(focus, focusKey))
            DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f) else null
        canvas.save(); canvas.clipRect(plot); canvas.drawPath(path, linePaint); canvas.restore()
        linePaint.pathEffect = null
    }
    private fun sampled(canvas: Canvas, points: List<Pair<Double, Double>>, tint: Int, y: (Double) -> Double) {
        if (points.isEmpty()) return
        path.rewind()
        points.forEachIndexed { index, item ->
            val value = y(item.second)
            if (index == 0) path.moveTo(fx(item.first), py(value)) else path.lineTo(fx(item.first), py(value))
        }
        linePaint.color = tint
        linePaint.pathEffect = if (model.active(focus, focusKey)) null else DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f)
        canvas.save(); canvas.clipRect(plot); canvas.drawPath(path, linePaint); canvas.restore()
        linePaint.pathEffect = null
    }
    private fun responseLine(canvas: Canvas, points: List<DiffResponse.Point>, tint: Int, value: (DiffResponse.Point) -> Double) {
        path.rewind()
        points.forEachIndexed { index, point ->
            val y = (value(point).coerceIn(-36.0, 6.0) + 36.0) / 42.0
            if (index == 0) path.moveTo(fx(point.frequency), py(y)) else path.lineTo(fx(point.frequency), py(y))
        }
        linePaint.color = tint
        linePaint.pathEffect = if (model.active(focus, focusKey)) null else DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f)
        canvas.save(); canvas.clipRect(plot); canvas.drawPath(path, linePaint); canvas.restore()
        linePaint.pathEffect = null
    }
    private fun marker(canvas: Canvas, x: Float, tint: Int = color) {
        linePaint.color = ColorUtils.setAlphaComponent(tint, 0x99)
        linePaint.pathEffect = DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f)
        canvas.drawLine(x, plot.top, x, plot.bottom, linePaint); linePaint.pathEffect = null
    }
    private fun point(canvas: Canvas, x: Float, y: Float, tint: Int = color) {
        fillPaint.color = tint; canvas.drawCircle(x, y, d(2.5f), fillPaint)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || width < d(50f) || height < d(55f)) return
        plot.set(d(28f), d(20f), width - d(7f), height - d(25f))
        if (illustration) drawIllustration(canvas) else drawParameters(canvas)
    }
    private fun drawParameters(canvas: Canvas) = when (focus) {
        DiffChartFocus.DELAY -> drawTiming(canvas)
        DiffChartFocus.MIX -> drawResponse(canvas)
        DiffChartFocus.BAND -> drawBand(canvas)
        DiffChartFocus.QUALITY -> drawInterpolationResponse(canvas)
        DiffChartFocus.LEVEL -> drawLevels(canvas)
        DiffChartFocus.GUARD -> drawGuard(canvas)
        DiffChartFocus.TRANSIENT -> drawTransient(canvas)
        DiffChartFocus.MONITOR -> drawMonitor(canvas)
        DiffChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas) = when (focus) {
        DiffChartFocus.DELAY -> drawArrivals(canvas)
        DiffChartFocus.MIX -> drawMix(canvas)
        DiffChartFocus.BAND -> drawBandScope(canvas)
        DiffChartFocus.QUALITY -> drawSampleLattice(canvas)
        DiffChartFocus.LEVEL -> drawLevelBars(canvas)
        DiffChartFocus.GUARD -> drawEnvelope(canvas, model.number(A.attack), model.number(A.release), model.number(A.maximum))
        DiffChartFocus.TRANSIENT -> drawEnvelope(canvas, model.number(A.onset), model.number(A.recovery), model.number(A.protection))
        DiffChartFocus.MONITOR -> drawMonitorMatrix(canvas)
        DiffChartFocus.AUDITION -> drawAuditionRoute(canvas)
    }
    private fun drawTiming(canvas: Canvas) {
        val cfg = model.configuration
        val effectiveMs = cfg.delaySamples * 1000.0 / model.rate
        caption(canvas, format(model.targetMilliseconds, if (model.fineOn) 3 else 2) + " ms · " + format(cfg.delaySamples, 3) + " 采样")
        grid(canvas, "1", "0", "0", "20 ms")
        marker(canvas, px(effectiveMs / 20.0))
        linePaint.color = color
        canvas.drawLine(px(effectiveMs / 20.0), plot.bottom, px(effectiveMs / 20.0), plot.top + d(5f), linePaint)
        point(canvas, px(effectiveMs / 20.0), plot.top + d(5f))
        val common = model.commonSamples()
        footer(canvas, if (!model.professional) "原版仅右声道 · 整采样" else
            if (model.fineOn) "微秒接管 · 公共 " + common + " 采样" else "0.01 ms目标 · 公共 " + common + " 采样")
    }
    private fun arrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, tint: Int) {
        linePaint.color = tint; linePaint.pathEffect = null
        canvas.drawLine(x1, y1, x2, y2, linePaint)
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        for (offset in doubleArrayOf(-.55, .55))
            canvas.drawLine(x2, y2, x2 - d(5f) * cos(angle + offset).toFloat(),
                y2 - d(5f) * sin(angle + offset).toFloat(), linePaint)
    }
    private fun drawArrivals(canvas: Canvas) {
        val cfg = model.configuration; val delayMs = cfg.delaySamples * 1000.0 / model.rate
        caption(canvas, "延迟" + model.channelName + "侧 · 相对到达")
        val start = d(18f); val end = width - d(12f)
        val early = start + (end - start) * .18f
        val late = early + (end - early) * (delayMs / 20.0).toFloat()
        val first = height * .40f; val second = height * .69f
        text(canvas, "L", d(3f), first + d(3f), d(15f))
        text(canvas, "R", d(3f), second + d(3f), d(15f))
        arrow(canvas, start, first, end, first, ColorUtils.setAlphaComponent(foreground, 0x5a))
        arrow(canvas, start, second, end, second, ColorUtils.setAlphaComponent(foreground, 0x5a))
        point(canvas, if (model.delayedLeft) late else early, first, color)
        point(canvas, if (model.delayedLeft) early else late, second, other)
        text(canvas, "时差 " + format(delayMs, 3) + " ms", width / 2f, height - d(19f),
            width - d(6f), align = Paint.Align.CENTER)
        footer(canvas, "位置仅表示时间 · 非距离")
    }
    private fun readyResponse() = response?.takeIf { it.configuration == model.responseConfiguration(focus) && !it.failed }
    private fun pending(canvas: Canvas) = text(canvas, if (response?.failed == true) "参数模型暂不可用" else "正在更新参数模型",
        plot.centerX(), plot.centerY(), plot.width(), align = Paint.Align.CENTER)
    private fun drawResponse(canvas: Canvas) {
        caption(canvas, "延迟" + model.channelName + "侧 · 同相输入模型")
        grid(canvas, "+6", "−36", "20", format(model.frequencyHigh / 1000.0, 0) + "k Hz")
        val result = readyResponse()
        if (result == null) pending(canvas) else {
            responseLine(canvas, result.points, ColorUtils.setAlphaComponent(foreground, 0x70)) { it.other }
            responseLine(canvas, result.points, color) { it.selected }
            responseLine(canvas, result.points, other) { it.mono }
        }
        footer(canvas, "紫 延迟声道 · 青 Mono")
    }
    private fun drawMix(canvas: Canvas) {
        val cfg = model.configuration; val mix = cfg.mix
        caption(canvas, model.channelName + "侧混合 " + format(mix * 100.0, 0) + "%")
        val left = d(8f); val right = width - d(8f); val top = height * .35f; val bottom = height * .62f
        fillPaint.color = ColorUtils.setAlphaComponent(other, 0x95)
        canvas.drawRect(left, top, left + (right - left) * (1 - mix).toFloat(), bottom, fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x9c)
        canvas.drawRect(left + (right - left) * (1 - mix).toFloat(), top, right, bottom, fillPaint)
        text(canvas, "原声 " + format((1 - mix) * 100, 0) + "%", left, top - d(5f), (right - left) * .6f, other)
        text(canvas, "迟到 " + format(mix * 100, 0) + "%", right, bottom + d(13f), (right - left) * .6f, color, Paint.Align.RIGHT)
        footer(canvas, "只混所选声道 · 非越多越宽")
    }
    private fun drawBand(canvas: Canvas) {
        val cfg = model.responseConfiguration(focus)
        caption(canvas, cfg.low.toString() + "–" + cfg.high + " Hz · FIR " + cfg.window + " ms")
        grid(canvas, "100%", "0", "20", format(model.frequencyHigh / 1000.0, 0) + "k Hz")
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x18)
        canvas.drawRect(fx(cfg.low.toDouble()), plot.top, fx(cfg.high.toDouble()), plot.bottom, fillPaint)
        marker(canvas, fx(cfg.low.toDouble())); marker(canvas, fx(cfg.high.toDouble()))
        val result = readyResponse()
        if (result == null) pending(canvas) else sampled(canvas, result.band, color) { it }
        footer(canvas, "平滑 FIR · 公共 " + format(model.commonSamples(cfg) * 1000.0 / model.rate, 2) + " ms")
    }
    private fun drawBandScope(canvas: Canvas) {
        val cfg = model.responseConfiguration(focus)
        val common = model.commonSamples(cfg)
        caption(canvas, "双方共同滤波延时")
        val top = height * .32f
        val commonMs = common * 1000.0 / model.rate
        val commonX = d(23f) + (width - d(29f)) * (commonMs / 50.0).coerceIn(0.0, 1.0).toFloat()
        val ribbonLeft = d(8f); val ribbonWidth = width - d(16f)
        fun position(hz: Int) = (ln(hz.toDouble().coerceIn(20.0, model.frequencyHigh) / 20.0) /
            ln(model.frequencyHigh / 20.0)).toFloat()
        fillPaint.color = ColorUtils.setAlphaComponent(foreground, 0x16)
        canvas.drawRect(ribbonLeft, d(20f), ribbonLeft + ribbonWidth, d(28f), fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x88)
        canvas.drawRect(ribbonLeft + ribbonWidth * position(cfg.low), d(20f),
            ribbonLeft + ribbonWidth * position(cfg.high), d(28f), fillPaint)
        for (index in 0..1) {
            val y = top + index * d(33f)
            text(canvas, if (index == 0) "L" else "R", d(5f), y + d(4f), d(18f))
            arrow(canvas, d(23f), y, width - d(6f), y, ColorUtils.setAlphaComponent(foreground, 0x58))
            linePaint.color = other
            canvas.drawLine(commonX, y - d(7f), commonX, y + d(7f), linePaint)
        }
        text(canvas, format(common * 1000.0 / model.rate, 2) + " ms / " + common + " 采样",
            width / 2f, height - d(27f), width - d(7f), align = Paint.Align.CENTER)
        text(canvas, "仅选定彩带增加差分延时", width / 2f, height - d(15f), width - d(7f), align = Paint.Align.CENTER)
        footer(canvas, "FIR窗长不是左右时差")
    }
    private fun drawInterpolationResponse(canvas: Canvas) {
        val cfg = model.configuration; val fraction = cfg.delaySamples - floor(cfg.delaySamples)
        caption(canvas, (if (model.quality == 0) "线性" else "三阶 Lagrange") + " · 分数 " + format(fraction, 3))
        grid(canvas, "+2 dB", "−12", "20", format(model.frequencyHigh / 1000.0, 0) + "k Hz")
        trace(canvas, color) { (db(model.interpolation(hz(it)).magnitude()) + 12.0) / 14.0 }
        trace(canvas, other, true) {
            val f = hz(it); val response = model.interpolation(f)
            val idealPhase = -2 * PI * f * fraction / model.rate
            val phaseError = atan2(sin(atan2(response.imaginary, response.real) - idealPhase),
                cos(atan2(response.imaginary, response.real) - idealPhase))
            (phaseError / PI + 1.0) / 2.0
        }
        footer(canvas, "紫 幅度 · 青 相位误差 ±180°")
    }
    private fun sampleCurve(x: Double, quality: Int): Double {
        val nodes = doubleArrayOf(-1.0, 0.0, 1.0, 2.0)
        val samples = doubleArrayOf(-.65, .35, .85, -.2)
        if (quality == 0) {
            val segment = floor(x + 1).toInt().coerceIn(0, 2)
            val fraction = (x - nodes[segment]).coerceIn(0.0, 1.0)
            return samples[segment] * (1 - fraction) + samples[segment + 1] * fraction
        }
        return nodes.indices.sumOf { index ->
            var weight = 1.0
            nodes.indices.filter { it != index }.forEach { other -> weight *= (x - nodes[other]) / (nodes[index] - nodes[other]) }
            samples[index] * weight
        }
    }
    private fun drawSampleLattice(canvas: Canvas) {
        val fraction = model.configuration.delaySamples.let { it - floor(it) }
        caption(canvas, "采样示例 · " + if (model.quality == 0) "直线插值" else "三次多项式")
        grid(canvas, "+1", "−1", "n−1", "n+2")
        trace(canvas, color) { (sampleCurve(it * 3 - 1, model.quality) + 1.0) / 2.0 }
        for (index in 0..3) point(canvas, px(index / 3.0), py((sampleCurve(index - 1.0, 0) + 1) / 2), other)
        marker(canvas, px((fraction + 1) / 3))
        point(canvas, px((fraction + 1) / 3), py((sampleCurve(fraction, model.quality) + 1) / 2))
        footer(canvas, if (model.quality == 1) "公共 +1采样 · 插值示意" else "分数采样插值示意")
    }
    private fun drawLevels(canvas: Canvas) {
        caption(canvas, "支路 " + signed(model.number(A.wet)) + " / 输出 " + signed(model.number(A.output)) + " dB")
        grid(canvas, "+1", "−1", "输入 −1", "+1")
        trace(canvas, color) { .5 + (it - .5) * amplitude(model.number(A.wet)) }
        trace(canvas, other) { .5 + (it - .5) * amplitude(model.number(A.output)) }
        footer(canvas, "支路仅迟到声 · 输出作用双方")
    }
    private fun drawLevelBars(canvas: Canvas) {
        caption(canvas, "配置幅度比例")
        val wet = amplitude(model.number(A.wet)); val output = amplitude(model.number(A.output))
        val levels = listOf(wet, output); val names = listOf("迟到支路", "双方输出")
        val baseline = height - d(28f); val available = height - d(53f)
        levels.forEachIndexed { index, level ->
            val x = width * (if (index == 0) .28f else .72f); val half = width * .1f
            fillPaint.color = if (index == 0) color else other
            canvas.drawRoundRect(x - half, baseline - available * level.toFloat(), x + half, baseline, d(2f), d(2f), fillPaint)
            text(canvas, format(level * 100, 0) + "%", x, baseline - available * level.toFloat() - d(4f), width * .4f, align = Paint.Align.CENTER)
            text(canvas, names[index], x, baseline + d(12f), width * .42f, align = Paint.Align.CENTER)
        }
        footer(canvas, "零时差也可能改变声道平衡")
    }
    private fun drawGuard(canvas: Canvas) {
        val detector = A.detector(model.values)
        val folding = detector == 1 || focusKey == A.fold.key
        val maximum = model.number(A.maximum) / 100.0
        if (folding) {
            val allowed = model.number(A.fold)
            caption(canvas, "允许折叠恶化 " + format(allowed) + " dB")
            grid(canvas, "100%", "0", "0", "12 dB 恶化")
            marker(canvas, px(allowed / 12))
            trace(canvas, color) { if (it * 12 > allowed) maximum else 0.0 }
            footer(canvas, "需相对对齐基线恶化")
        } else {
            val threshold = model.number(A.correlation); val interval = model.number(A.hysteresis)
            caption(canvas, "相关 " + format(threshold, 2) + " · 间隔 " + format(interval, 2))
            grid(canvas, "100%", "0", "−1", "+1 相关度")
            fillPaint.color = ColorUtils.setAlphaComponent(color, 0x22)
            canvas.drawRect(px((threshold + 1) / 2), plot.top, px((threshold + interval + 1) / 2), plot.bottom, fillPaint)
            trace(canvas, color) { if (it * 2 - 1 < threshold) maximum else 0.0 }
            trace(canvas, other, true) { if (it * 2 - 1 < threshold + interval) maximum else 0.0 }
            footer(canvas, if (detector == 2) "联合：另看折叠恶化 " + format(model.number(A.fold)) + "dB" else "还需比对齐基线更差")
        }
    }
    private fun drawTransient(canvas: Canvas) {
        val threshold = model.number(A.threshold); val amount = model.number(A.protection)
        caption(canvas, "起音差 " + format(threshold) + " dB · 保护 " + format(amount, 0) + "%")
        grid(canvas, "100%", "0", "0", "24 dB 差值")
        marker(canvas, px(threshold / 24))
        trace(canvas, color) { if (it * 24 > threshold) amount / 100 else 0.0 }
        footer(canvas, "包络差阈值 · 不是 dBFS")
    }
    private fun drawEnvelope(canvas: Canvas, attack: Double, release: Double, maximum: Double) {
        caption(canvas, "介入 " + format(attack) + " / 恢复 " + format(release, 0) + " ms")
        grid(canvas, "100%", "0", "0", "5000 ms")
        fun time(p: Double) = expm1(p * ln1p(5000.0))
        trace(canvas, color) { (1 - exp(-time(it) / attack.coerceAtLeast(.01))) * maximum / 100 }
        trace(canvas, other) { exp(-time(it) / release.coerceAtLeast(.01)) * maximum / 100 }
        point(canvas, px(ln1p(attack) / ln1p(5000.0)), py((1 - exp(-1.0)) * maximum / 100))
        point(canvas, px(ln1p(release) / ln1p(5000.0)), py(exp(-1.0) * maximum / 100), other)
        footer(canvas, "回收新增混合 · 固定对数时间轴")
    }
    private fun drawMonitor(canvas: Canvas) {
        caption(canvas, "监测目标 " + format(model.number(A.low), 0) + " / " + format(model.number(A.high), 0) + " Hz")
        grid(canvas, "0 dB", "−36", "20", format(model.frequencyHigh / 1000, 0) + "k Hz")
        trace(canvas, color) { (db(model.lowpass(hz(it), model.number(A.low)).magnitude()) + 36) / 36 }
        trace(canvas, other) { (db((model.lowpass(hz(it), model.number(A.high)) - model.lowpass(hz(it), model.number(A.low))).magnitude()) + 36) / 36 }
        trace(canvas, if (model.active(focus, focusKey)) tertiary else foreground) {
            (db((DiffComplex(1.0) - model.lowpass(hz(it), model.number(A.high))).magnitude()) + 36) / 36
        }
        marker(canvas, fx(model.number(A.low))); marker(canvas, fx(model.number(A.high)))
        footer(canvas, "仅监测滤波 · 不改处理频带")
    }
    private fun box(canvas: Canvas, rect: RectF, label: String, chosen: Boolean = false) {
        fillPaint.color = ColorUtils.setAlphaComponent(if (chosen) color else foreground, if (chosen) 0x28 else 0x0d)
        canvas.drawRoundRect(rect, d(3f), d(3f), fillPaint); canvas.drawRoundRect(rect, d(3f), d(3f), gridPaint)
        text(canvas, label, rect.centerX(), rect.centerY() + d(3f), rect.width() - d(3f),
            if (chosen) color else foreground, Paint.Align.CENTER)
    }
    private fun drawMonitorMatrix(canvas: Canvas) {
        caption(canvas, "三组信号 × 三个监测频带")
        val names = listOf("基线", "候选", "输出")
        val top = d(31f); val h = (height - d(57f)) / 3
        val x0 = d(28f); val w = (width - x0 - d(4f)) / 3
        listOf("低", "中", "高").forEachIndexed { index, label ->
            text(canvas, label, x0 + (index + .5f) * w, d(24f), w, align = Paint.Align.CENTER)
        }
        names.forEachIndexed { row, name ->
            text(canvas, name, d(3f), top + (row + .5f) * h + d(3f), d(26f))
            for (column in 0..2) box(canvas, RectF(x0 + column * w + d(1f), top + row * h,
                x0 + (column + 1) * w - d(1f), top + (row + 1) * h - d(2f)), "ρ / 折叠")
        }
        footer(canvas, "分界仅用于观察 · 不改变声音")
    }
    private val auditionNames get() = listOf("正常处理结果", "本模块旁路", "处理后 Mono", "延时对齐旁路", "本模块效果差分")
    private fun drawAudition(canvas: Canvas) {
        caption(canvas, "临时试听 · 不保存到预设")
        val top = d(20f); val h = (height - d(37f)) / 5
        auditionNames.forEachIndexed { index, name ->
            box(canvas, RectF(d(4f), top + index * h, width - d(4f), top + (index + 1) * h - d(2f)),
                name, index == model.audition)
        }
        footer(canvas, "测点在试听前 · 后续效果另算")
    }
    private fun drawAuditionRoute(canvas: Canvas) {
        caption(canvas, "当前：" + auditionNames.getOrElse(model.audition) { auditionNames[0] })
        box(canvas, RectF(width * .16f, d(22f), width * .84f, d(44f)), "原始 L / R")
        arrow(canvas, width * .5f, d(44f), width * .5f, height * .5f, color)
        box(canvas, RectF(width * .08f, height * .5f, width * .92f, height * .70f), "延时处理 → 监测点", true)
        arrow(canvas, width * .5f, height * .70f, width * .5f, height - d(29f), other)
        text(canvas, "试听切换 → 后续效果", width * .5f, height - d(18f), width - d(6f), align = Paint.Align.CENTER)
        footer(canvas, "差分 ≠ Side · 不做响度归一")
    }
    private fun details(): String {
        val controls = when (focus) {
            DiffChartFocus.DELAY -> listOf(P.legacy, P.delay, A.fine)
            DiffChartFocus.MIX -> listOf(P.mix)
            DiffChartFocus.BAND -> listOf(P.low, P.high, A.window)
            DiffChartFocus.QUALITY -> emptyList()
            DiffChartFocus.LEVEL -> listOf(A.wet, A.output)
            DiffChartFocus.GUARD -> A.guard
            DiffChartFocus.TRANSIENT -> A.transient
            DiffChartFocus.MONITOR -> listOf(A.low, A.high)
            DiffChartFocus.AUDITION -> emptyList()
        }
        val note = when (focus) {
            DiffChartFocus.DELAY -> "原版只延迟右声道并取整采样。专业精密开启微秒精调后，独立0.001 ms参数接管0.01 ms参数；控制刻度不代表测量误差保证。图中到达位置表示相对时间，不是实际距离或测得声像。"
            DiffChartFocus.MIX -> "只在所选声道混合原声和迟到声，另一声道不增加差分时差。左图复用原静态响应模型，假设输入左右同相等幅，灰色另一声道、紫色延迟声道、青色单声道合并；不包含动态保护、输入音频或后续效果。"
            DiffChartFocus.BAND -> "曲线使用原模型的Fejer平滑FIR，不是硬切频带。20/40/80 ms是FIR窗长，不是左右时间差。双方共同延时包括FIR半窗、FFT分块延时，以及三阶插值的额外1采样。原始全频保留，只有选定频带的新增差量被延时。"
            DiffChartFocus.QUALITY -> "左图为当前分数采样位置的插值幅度与相位误差，相位误差相对理想分数延时。右图以四个人工采样点演示直线/三阶Lagrange，不是真实音乐波形。三阶双方匹配额外1采样公共延时，仍有幅相误差；原版模式不使用该专业选择。"
            DiffChartFocus.LEVEL -> "支路电平只缩放迟到分量，输出衰减作用于双方。条形图是保存参数对应的幅度比例，不是实测RMS。零时间差下，降低迟到支路也可能改变所选声道音量和平衡。"
            DiffChartFocus.GUARD -> "相关度恢复间隔是无量纲相关系数差，不是时间。相关度判据还需候选比对齐基线更差；折叠判据比较候选与基线的Mono电平恶化。联合模式任一有效判据恶化可介入；图仅解释阈值和时间，不预测实际音乐触发，也不能修复原录音反相。"
            DiffChartFocus.TRANSIENT -> "起音阈值是快慢包络差值（dB），不是dBFS。瞬态介入时间单位ms，最小0.5 ms；恢复时间也为ms。保护只回收新增混合而不移动延时读点，不是鼓点/人声分离，也不保证消除最初起音误差或FIR前振铃。"
            DiffChartFocus.MONITOR -> "低/中/高使用相同的一阶互补监测滤波器，边界柔和。分界只改变监测分析，不改变音频。三组为对齐基线、保护前候选和实际输出，均在临时试听之前，真实值来自完整100 ms窗口；本静态图不是实时测量。"
            DiffChartFocus.AUDITION -> "五种路径分别是正常、原始模块旁路、处理后Mono、保持公共延时的对齐旁路、正常输出减对齐原声的效果差分。差分不是Side，也不做响度归一化。所有监测点均在试听之前，后续混响等仍可改变最终输出。"
        }
        val cfg = model.configuration
        val common = model.commonSamples(cfg)
        val status = if (model.active(focus, focusKey)) "当前组可用，以下仅为参数模型。" else
            "当前音效、模式或子开关未启用此组，仅灰色参数预览。"
        val extra = when (focus) {
            DiffChartFocus.DELAY -> "当前有效时差目标 " + format(cfg.delaySamples * 1000.0 / model.rate, 3) + " ms / " +
                format(cfg.delaySamples, 3) + "采样；双方公共延时目标 " + format(common * 1000.0 / model.rate, 3) + " ms / " + common + "采样。"
            DiffChartFocus.BAND -> "当前处理公共延时目标 " + common + "采样；选频预览公共延时 " +
                model.commonSamples(model.responseConfiguration(focus)) + "采样。"
            DiffChartFocus.GUARD -> "判据：" + listOf("相关度", "单声道折叠差", "联合判断")[A.detector(values)] + "。"
            DiffChartFocus.QUALITY -> "插值目标：" + if (model.quality == 0) "第一轮线性。" else "三阶Lagrange。"
            else -> "建模采样率 " + model.rate + " Hz。"
        }
        return (listOf(model.modeName + " · 延迟" + model.channelName + "声道", status) +
            controls.map(model::label) + listOf(extra, note)).joinToString(System.lineSeparator())
    }
}
