package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlin.math.*
import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters
import me.timschneeberger.rootlessjamesdsp.fusion.FieldAdvancedParameters as A
import me.timschneeberger.rootlessjamesdsp.fusion.FieldChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.FieldController
import me.timschneeberger.rootlessjamesdsp.fusion.FieldParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** Parameter observers only. Neither chart owns an editor, audio switch or audition. */
internal class FieldParameterChart(context: Context) : FieldStaticChartBase(context, false)
internal class FieldIllustrationChart(context: Context) : FieldStaticChartBase(context, true)

private data class FieldComplex(val re: Double, val im: Double = 0.0) {
    operator fun plus(other: FieldComplex) = FieldComplex(re + other.re, im + other.im)
    operator fun minus(other: FieldComplex) = FieldComplex(re - other.re, im - other.im)
    operator fun times(other: FieldComplex) = FieldComplex(re * other.re - im * other.im, re * other.im + im * other.re)
    operator fun times(scale: Double) = FieldComplex(re * scale, im * scale)
    operator fun div(other: FieldComplex): FieldComplex {
        val denominator = (other.re * other.re + other.im * other.im).coerceAtLeast(1e-24)
        return FieldComplex((re * other.re + im * other.im) / denominator,
            (im * other.re - re * other.im) / denominator)
    }
    fun magnitude() = hypot(re, im)
}

private data class FieldStaticModel(
    val values: Map<String, *>, val rate: Int, val supported: Boolean, val advanced: Boolean, val audition: Int
) {
    val mode get() = if (supported) P.mode(values) else 0
    val modeName get() = if (mode == 0) "原版兼容" else "专业 M/S"
    val effectOn get() = values["v_field"] == true
    val frequencyHigh get() = min(20000.0, rate * .5 - 100.0)
    fun raw(control: BassParameters.Control): Int = if (control.key in A.newKeys) A.value(values, control) else P.value(values, control)
    fun number(control: BassParameters.Control) = raw(control).toDouble() / control.divisor
    fun label(control: BassParameters.Control) = control.title + " " + control.label(raw(control))
    fun enabled(key: String) = if (key in A.switches) A.enabled(values, key) else P.enabled(values, key)
    fun clampFrequency(frequency: Double) = frequency.coerceIn(1.0, rate * .5 - 100.0)
    val outputGain get() = 10.0.pow(number(P.outputTrim) / 20.0)
    val midGain: Double get() {
        val trim = 10.0.pow(number(P.midTrim) / 20.0)
        return if (mode == 0) 2.0 * number(P.legacyCenter) / 100.0 /
            (number(P.legacyWidth) / 100.0 + 2.0) * trim else trim
    }
    val sideGain: Double get() {
        val widen = number(P.legacyWidth) / 100.0
        return if (mode == 0) 2.0 * (widen + 1.0) / (widen + 2.0) else number(P.width) / 100.0
    }
    private fun lowpass(frequency: Double, cutoff: Double): FieldComplex {
        val k = tan(PI * clampFrequency(cutoff) / rate)
        val b = k / (1.0 + k); val a = (k - 1.0) / (k + 1.0)
        val omega = 2.0 * PI * frequency.coerceIn(0.0, rate * .5 - .01) / rate
        val z = FieldComplex(cos(omega), -sin(omega))
        return (FieldComplex(1.0) + z) * b / (FieldComplex(1.0) + z * a)
    }
    fun side(frequency: Double, forceBand: Boolean = false, forceFocus: Boolean = false): FieldComplex {
        val band = if (enabled(P.BAND_ENABLED) || forceBand)
            (FieldComplex(1.0) - lowpass(frequency, number(P.bandLow))) * lowpass(frequency, number(P.bandHigh))
            else FieldComplex(1.0)
        val widened = FieldComplex(1.0) + band * (sideGain - 1.0)
        val focused = if (enabled(P.FOCUS_ENABLED) || forceFocus)
            FieldComplex(1.0) + lowpass(frequency, number(P.focusFrequency)) * (number(P.focusWidth) / 100.0 - 1.0)
            else FieldComplex(1.0)
        return widened * focused
    }
    fun focusRetention(frequency: Double) =
        (FieldComplex(1.0) + lowpass(frequency, number(P.focusFrequency)) * (number(P.focusWidth) / 100.0 - 1.0)).magnitude()
    val delaySamples get() = number(A.delayTime) * rate / 1000.0
    fun delayResponse(frequency: Double): FieldComplex {
        val integer = floor(delaySamples); val fraction = delaySamples - integer
        val omega = 2.0 * PI * frequency / rate
        // Native uses two adjacent stationary read taps and linear fractional interpolation.
        val first = FieldComplex(cos(omega * integer), -sin(omega * integer))
        val second = FieldComplex(cos(omega * (integer + 1.0)), -sin(omega * (integer + 1.0)))
        val delayed = first * (1.0 - fraction) + second * fraction
        val mix = number(A.delayMix) / 100.0
        return FieldComplex(1.0 - mix) + delayed * mix
    }
    fun diffusionFrequencies(): List<Double> {
        val stages = raw(A.diffusionStages)
        return List(stages) { index ->
            val position = if (stages == 1) 0.0 else index.toDouble() / (stages - 1) - .5
            clampFrequency(number(A.diffusionCenter) * 2.0.pow(position * number(A.diffusionSpan)))
        }
    }
    fun diffusionPhase(frequency: Double): Double = diffusionFrequencies().sumOf { cutoff ->
        -2.0 * atan(tan(PI * frequency / rate) / tan(PI * cutoff / rate))
    }
    fun diffusionResponse(frequency: Double): FieldComplex {
        val phase = diffusionPhase(frequency); val mix = number(A.diffusionMix) / 100.0
        return FieldComplex(1.0 - mix) + FieldComplex(cos(phase), sin(phase)) * mix
    }
    fun active(focus: FieldChartFocus, key: String): Boolean {
        if (!effectOn) return false
        return when (focus) {
            FieldChartFocus.WIDTH -> when (key) {
                P.legacyWidth.key, P.legacyCenter.key -> mode == 0
                P.width.key -> supported && mode == 1
                P.midTrim.key -> supported
                else -> supported || mode == 0
            }
            FieldChartFocus.GAIN -> supported
            FieldChartFocus.DEPTH -> enabled(P.depthKey(mode)) &&
                (key !in setOf(P.LEGACY_DEPTH_ENABLED, P.MS_DEPTH_ENABLED) || key == P.depthKey(mode))
            FieldChartFocus.BAND -> supported && enabled(P.BAND_ENABLED)
            FieldChartFocus.FOCUS -> supported && enabled(P.FOCUS_ENABLED)
            FieldChartFocus.DYNAMIC -> advanced && enabled(A.DYNAMIC_ENABLED)
            FieldChartFocus.GUARD -> advanced && enabled(A.GUARD_ENABLED)
            FieldChartFocus.TRANSIENT -> advanced && enabled(A.TRANSIENT_ENABLED)
            FieldChartFocus.DELAY -> advanced && enabled(A.DELAY_ENABLED)
            FieldChartFocus.DIFFUSION -> advanced && enabled(A.DIFFUSION_ENABLED)
            FieldChartFocus.AUDITION -> supported
        }
    }
}

/** Shared small-chart drawing tools; all formulas describe configured steady-state targets. */
internal open class FieldStaticChartBase(context: Context, private val illustration: Boolean) : View(context) {
    private val density = resources.displayMetrics.density
    private fun d(value: Float) = value * density
    private val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val secondary = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(115, 208, 230) else Color.rgb(16, 121, 147)
    private val warning = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(246, 179, 100) else Color.rgb(162, 89, 20)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(9f * resources.displayMetrics.scaledDensity, d(11f)) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(1.5f) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foreground, 0x2c); style = Paint.Style.STROKE; strokeWidth = d(.7f)
    }
    private val plot = RectF()
    private val path = Path()
    private var values: Map<String, *> = emptyMap<String, Any>()
    private var rate = 48000
    private var model = snapshot()
    private var focus = FieldChartFocus.WIDTH
    private var focusKey = P.legacyWidth.key
    private var active = true
    private var detailDialog: AlertDialog? = null
    private val usableColor get() = if (model.active(focus, focusKey)) accent else ColorUtils.setAlphaComponent(foreground, 0x77)
    private val otherColor get() = if (model.active(focus, focusKey)) secondary else ColorUtils.setAlphaComponent(foreground, 0x55)
    private fun snapshot() = FieldStaticModel(values, rate, FieldController.supported(context),
        FieldController.advancedSupported(context), FieldController.audition)
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
    fun setConfiguration(values: Map<String, *>, rate: Int) {
        val keys = P.all.map { it.key } + P.switches + P.MODE + A.newKeys + "v_field"
        this.values = values.filterKeys { it in keys }.toMap()
        this.rate = rate.takeIf { it in 8000..768000 } ?: 48000
        val next = snapshot()
        if (next == model) return
        model = next; updateDescription()
        if (active) invalidate()
    }
    fun setFocus(focus: FieldChartFocus, key: String) {
        if (this.focus == focus && focusKey == key) return
        this.focus = focus; focusKey = key; updateDescription()
        if (active) invalidate()
    }
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (!active) { detailDialog?.dismiss(); detailDialog = null } else invalidate()
    }
    private fun updateDescription() { contentDescription = details() }
    private fun format(value: Double, digits: Int = 1) = String.format(Locale.ROOT, "%." + digits + "f", value)
    private fun signed(value: Double) = String.format(Locale.ROOT, "%+.1f", value)
    private fun db(amplitude: Double) = 20.0 * log10(amplitude.coerceAtLeast(1e-6))
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, width: Float,
        color: Int = foreground, align: Paint.Align = Paint.Align.LEFT) {
        textPaint.color = color; textPaint.textAlign = align
        if (width <= 0f) return
        val count = textPaint.breakText(value, true, width, null)
        val result = if (count >= value.length) value else value.take((count - 1).coerceAtLeast(0)) + "…"
        canvas.drawText(result, x, y, textPaint)
    }
    private fun caption(canvas: Canvas, value: String) = text(canvas, value, d(3f), d(11f), width - d(6f), usableColor)
    private fun footer(canvas: Canvas, value: String) = text(canvas,
        if (!model.active(focus, focusKey)) "未生效 · 参数预览" else value,
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
    private fun frequency(position: Double) = 20.0 * (model.frequencyHigh / 20.0).pow(position)
    private fun frequencyX(hz: Double) = px(ln(hz.coerceIn(20.0, model.frequencyHigh) / 20.0) / ln(model.frequencyHigh / 20.0))
    private fun trace(canvas: Canvas, color: Int, dashed: Boolean = false, value: (Double) -> Double) {
        path.rewind()
        for (index in 0..160) {
            val position = index / 160.0
            val y = value(position)
            if (!y.isFinite()) continue
            if (index == 0) path.moveTo(px(position), py(y)) else path.lineTo(px(position), py(y))
        }
        linePaint.color = color
        linePaint.pathEffect = if (dashed || !model.active(focus, focusKey)) DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f) else null
        canvas.save(); canvas.clipRect(plot); canvas.drawPath(path, linePaint); canvas.restore()
        linePaint.pathEffect = null
    }
    private fun marker(canvas: Canvas, x: Float, color: Int = usableColor) {
        linePaint.color = ColorUtils.setAlphaComponent(color, 0xa0)
        linePaint.pathEffect = DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f)
        canvas.drawLine(x, plot.top, x, plot.bottom, linePaint); linePaint.pathEffect = null
    }
    private fun point(canvas: Canvas, x: Float, y: Float, color: Int = usableColor) {
        fillPaint.color = color; canvas.drawCircle(x, y, d(2.5f), fillPaint)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || width <= d(50f) || height <= d(55f)) return
        plot.set(d(28f), d(20f), width - d(7f), height - d(25f))
        if (illustration) drawIllustration(canvas) else drawParameters(canvas)
    }
    private fun drawParameters(canvas: Canvas) = when (focus) {
        FieldChartFocus.WIDTH, FieldChartFocus.BAND -> drawWidthCurve(canvas)
        FieldChartFocus.GAIN -> drawGainCurve(canvas)
        FieldChartFocus.DEPTH -> drawDepthControl(canvas)
        FieldChartFocus.FOCUS -> drawFocusCurve(canvas)
        FieldChartFocus.DYNAMIC -> drawCompression(canvas)
        FieldChartFocus.GUARD -> drawGuard(canvas)
        FieldChartFocus.TRANSIENT -> drawTransient(canvas)
        FieldChartFocus.DELAY -> drawDelaySamples(canvas)
        FieldChartFocus.DIFFUSION -> drawDiffusionPhase(canvas)
        FieldChartFocus.AUDITION -> drawRouting(canvas)
    }
    private fun drawIllustration(canvas: Canvas) = when (focus) {
        FieldChartFocus.WIDTH, FieldChartFocus.BAND, FieldChartFocus.FOCUS, FieldChartFocus.DEPTH -> drawRadar(canvas)
        FieldChartFocus.GAIN -> drawGainBars(canvas)
        FieldChartFocus.DYNAMIC -> drawEnvelope(canvas, model.number(A.dynamicAttack), model.number(A.dynamicRelease), 100.0, "归一化 %")
        FieldChartFocus.GUARD -> drawEnvelope(canvas, model.number(A.guardWithdraw), model.number(A.guardRecover), model.number(A.guardMaximum), "回收 %")
        FieldChartFocus.TRANSIENT -> drawEnvelope(canvas, null, model.number(A.transientRecovery), model.number(A.transientAmount), "保护 %")
        FieldChartFocus.DELAY -> drawMixedResponse(canvas, false)
        FieldChartFocus.DIFFUSION -> drawMixedResponse(canvas, true)
        FieldChartFocus.AUDITION -> drawFold(canvas)
    }

    private fun drawWidthCurve(canvas: Canvas) {
        val bandPreview = focus == FieldChartFocus.BAND
        val title = if (bandPreview) format(model.number(P.bandLow), 0) + "–" + format(model.number(P.bandHigh), 0) + " Hz"
            else model.modeName + " · Mid / Side"
        caption(canvas, title); grid(canvas, "+12", "−36", "20", format(model.frequencyHigh / 1000.0, 0) + "k Hz")
        if (model.enabled(P.BAND_ENABLED) || bandPreview) {
            fillPaint.color = ColorUtils.setAlphaComponent(usableColor, 0x19)
            canvas.drawRect(frequencyX(model.number(P.bandLow)), plot.top,
                frequencyX(model.number(P.bandHigh)), plot.bottom, fillPaint)
            marker(canvas, frequencyX(model.number(P.bandLow))); marker(canvas, frequencyX(model.number(P.bandHigh)))
        }
        trace(canvas, otherColor) { (db(model.midGain * model.outputGain) + 36.0) / 48.0 }
        trace(canvas, usableColor) { position ->
            (db(model.side(frequency(position), forceBand = bandPreview).magnitude() * model.outputGain) + 36.0) / 48.0
        }
        footer(canvas, if (bandPreview) "柔和作用区 · 非硬分频" else "紫 Side · 青 Mid · dB")
    }
    private fun drawGainCurve(canvas: Canvas) {
        caption(canvas, "输出 " + signed(model.number(P.outputTrim)) + " dB")
        grid(canvas, "+1", "−1", "输入 −1", "+1")
        trace(canvas, ColorUtils.setAlphaComponent(foreground, 0x66), true) { it }
        trace(canvas, usableColor) { position -> .5 + (position - .5) * model.outputGain }
        footer(canvas, "线性幅度缩放 · 参数模型")
    }
    private fun drawGainBars(canvas: Canvas) {
        caption(canvas, "同一输入的相对能量")
        val baseline = height - d(28f); val available = height - d(52f)
        val barWidth = width * .22f
        listOf(1.0, model.outputGain * model.outputGain).forEachIndexed { index, level ->
            val x = width * (if (index == 0) .28f else .72f)
            fillPaint.color = if (index == 0) ColorUtils.setAlphaComponent(foreground, 0x55) else usableColor
            canvas.drawRoundRect(x - barWidth / 2, baseline - available * level.toFloat(), x + barWidth / 2,
                baseline, d(2f), d(2f), fillPaint)
            text(canvas, format(level * 100.0, 0) + "%", x, baseline - available * level.toFloat() - d(3f), width * .4f,
                align = Paint.Align.CENTER)
            text(canvas, if (index == 0) "衰减前" else "衰减后", x, baseline + d(12f), width * .42f,
                align = Paint.Align.CENTER)
        }
        footer(canvas, "能量比例模型 · 非实测")
    }
    private fun drawDepthControl(canvas: Canvas) {
        val depth = model.number(P.depth); val mix = model.number(P.depthMix)
        caption(canvas, "原单位 " + format(depth, 0) + " · 混合 " + format(mix, 0) + "%")
        grid(canvas, "100%", "0", "200", "800 原单位")
        trace(canvas, otherColor, true) { it }
        trace(canvas, usableColor) { position -> position * mix / 100.0 }
        val x = px((depth - 200.0) / 600.0)
        marker(canvas, x); point(canvas, x, py((depth - 200.0) / 600.0 * mix / 100.0))
        footer(canvas, "强度位置示意 · 非 ms / 米")
    }
    private fun drawFocusCurve(canvas: Canvas) {
        caption(canvas, format(model.number(P.focusFrequency), 0) + " Hz · 保留 " + format(model.number(P.focusWidth), 0) + "%")
        grid(canvas, "100%", "0", "20", format(model.frequencyHigh / 1000.0, 0) + "k Hz")
        trace(canvas, usableColor) { model.focusRetention(frequency(it)) }
        marker(canvas, frequencyX(model.number(P.focusFrequency)))
        footer(canvas, if (model.number(P.focusWidth) == 100.0) "100% 保留 · 不额外收窄" else "低频 Side 渐变收拢")
    }
    private fun drawCompression(canvas: Canvas) {
        val threshold = model.number(A.dynamicThreshold); val ratio = model.number(A.dynamicRatio)
        val maximum = model.number(A.dynamicMaximum)
        caption(canvas, format(threshold) + " dBFS · " + format(ratio) + ":1")
        grid(canvas, "0", "−12", "−60", "0 dBFS")
        marker(canvas, px((threshold + 60.0) / 60.0))
        trace(canvas, usableColor) { position ->
            1.0 - ((position * 60.0 - 60.0 - threshold) * (1.0 - 1.0 / ratio)).coerceIn(0.0, maximum) / 12.0
        }
        footer(canvas, "Side 衰减上限 " + format(maximum) + " dB")
    }
    private fun drawEnvelope(canvas: Canvas, rise: Double?, fall: Double, amount: Double, unit: String) {
        caption(canvas, (rise?.let { format(it, 0) + " / " } ?: "恢复 ") + format(fall, 0) + " ms")
        grid(canvas, "100%", "0", "0", "5000 ms")
        fun time(position: Double) = expm1(position * ln1p(5000.0))
        if (rise != null) {
            trace(canvas, usableColor) { (1.0 - exp(-time(it) / rise.coerceAtLeast(.01))) * amount / 100.0 }
            val x = px(ln1p(rise) / ln1p(5000.0))
            point(canvas, x, py((1.0 - exp(-1.0)) * amount / 100.0))
        }
        trace(canvas, otherColor) { exp(-time(it) / fall.coerceAtLeast(.01)) * amount / 100.0 }
        point(canvas, px(ln1p(fall) / ln1p(5000.0)), py(exp(-1.0) * amount / 100.0), otherColor)
        footer(canvas, unit + " · 固定对数时间轴")
    }
    private fun drawGuard(canvas: Canvas) {
        val threshold = model.number(A.guardThreshold); val interval = model.number(A.guardHysteresis)
        val amount = model.number(A.guardMaximum) / 100.0
        caption(canvas, "阈值 " + format(threshold, 2) + " · 间隔 " + format(interval, 2))
        grid(canvas, "100%", "0", "−1", "+1 相关度")
        fillPaint.color = ColorUtils.setAlphaComponent(usableColor, 0x24)
        canvas.drawRect(px((threshold + 1.0) / 2.0), plot.top,
            px((threshold + interval + 1.0) / 2.0), plot.bottom, fillPaint)
        trace(canvas, usableColor) { if (it * 2.0 - 1.0 < threshold) amount else 0.0 }
        trace(canvas, otherColor, true) { if (it * 2.0 - 1.0 < threshold + interval) amount else 0.0 }
        marker(canvas, px((threshold + 1.0) / 2.0))
        footer(canvas, "还需比兼容基线更差")
    }
    private fun drawTransient(canvas: Canvas) {
        val threshold = model.number(A.transientThreshold); val amount = model.number(A.transientAmount)
        caption(canvas, "起音差 " + format(threshold) + " dB · 保护 " + format(amount, 0) + "%")
        grid(canvas, "100%", "0", "0", "24 dB 差值")
        marker(canvas, px(threshold / 24.0))
        trace(canvas, usableColor) { if (it * 24.0 > threshold) amount / 100.0 else 0.0 }
        footer(canvas, "快慢包络差 · 非 dBFS")
    }
    private fun drawDelaySamples(canvas: Canvas) {
        val delay = model.number(A.delayTime); val mix = model.number(A.delayMix) / 100.0
        caption(canvas, format(delay, 2) + " ms = " + format(model.delaySamples, 3) + " 采样")
        grid(canvas, "1", "0", "0", "5.00 ms")
        val dryX = plot.left + d(2f); val wetX = px(delay / 5.0)
        linePaint.pathEffect = null; linePaint.color = otherColor
        canvas.drawLine(dryX, plot.bottom, dryX, py(1.0 - mix), linePaint)
        point(canvas, dryX, py(1.0 - mix), otherColor)
        linePaint.color = usableColor
        canvas.drawLine(wetX, plot.bottom, wetX, py(mix), linePaint); point(canvas, wetX, py(mix))
        marker(canvas, wetX)
        footer(canvas, "Side 支路 · 混合 " + format(mix * 100.0, 0) + "%")
    }
    private fun drawDiffusionPhase(canvas: Canvas) {
        val stages = model.raw(A.diffusionStages)
        caption(canvas, stages.toString() + " 级 · " + format(model.number(A.diffusionCenter), 0) + " Hz")
        grid(canvas, "0°", "−" + (stages * 180).toString() + "°", "20", format(model.frequencyHigh / 1000.0, 0) + "k Hz")
        trace(canvas, usableColor) { 1.0 + model.diffusionPhase(frequency(it)) / (stages * PI) }
        model.diffusionFrequencies().forEach { marker(canvas, frequencyX(it), otherColor) }
        footer(canvas, if (stages == 1) "1级仅中心频点参与" else "跨度 " + format(model.number(A.diffusionSpan)) + " oct · 相位模型")
    }
    private fun drawMixedResponse(canvas: Canvas, diffusion: Boolean) {
        val mix = model.number(if (diffusion) A.diffusionMix else A.delayMix)
        caption(canvas, "干湿混合 " + format(mix, 0) + "% · Side 目标")
        grid(canvas, "0 dB", "−36", "20", format(model.frequencyHigh / 1000.0, 0) + "k Hz")
        trace(canvas, usableColor) {
            val response = if (diffusion) model.diffusionResponse(frequency(it)) else model.delayResponse(frequency(it))
            (db(response.magnitude()) + 36.0) / 36.0
        }
        if (!diffusion) trace(canvas, otherColor, true) {
            val response = model.delayResponse(frequency(it))
            (atan2(response.im, response.re) / PI + 1.0) / 2.0
        }
        footer(canvas, if (diffusion) "混合可能衰减 · 非实测" else "紫 幅频 · 青 相位 ±180°")
    }

    private fun fan(canvas: Canvas, cx: Float, cy: Float, outer: Float, inner: Float, halfAngle: Double, color: Int) {
        val angle = halfAngle.coerceIn(.0, 84.0)
        path.rewind()
        for (index in 0..48) {
            val theta = (90.0 - angle + 2.0 * angle * index / 48.0) * PI / 180.0
            val x = cx - outer * cos(theta).toFloat(); val y = cy - outer * sin(theta).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (index in 48 downTo 0) {
            val theta = (90.0 - angle + 2.0 * angle * index / 48.0) * PI / 180.0
            path.lineTo(cx - inner * cos(theta).toFloat(), cy - inner * sin(theta).toFloat())
        }
        path.close(); fillPaint.color = color; canvas.drawPath(path, fillPaint)
    }
    private fun drawRadar(canvas: Canvas) {
        val cx = width / 2f; val cy = height - d(24f)
        val radius = min(width * .43f, height - d(46f))
        fun angle(frequency: Double) = atan(model.side(frequency,
            forceBand = focus == FieldChartFocus.BAND, forceFocus = focus == FieldChartFocus.FOCUS).magnitude() /
            model.midGain.coerceAtLeast(1e-6)) * 180.0 / PI
        val depth = (model.number(P.depth) - 200.0) / 600.0
        val mix = model.number(P.depthMix) / 100.0
        val low = model.clampFrequency(model.number(P.bandLow))
        val high = model.clampFrequency(model.number(P.bandHigh))
        val focusHz = model.clampFrequency(model.number(P.focusFrequency))
        val title = when (focus) {
            FieldChartFocus.DEPTH -> "相对层次 · 混合 " + format(mix * 100.0, 0) + "%"
            FieldChartFocus.BAND -> "频段 " + format(low, 0) + "–" + format(high, 0) + " Hz"
            FieldChartFocus.FOCUS -> "低音收拢 · 保留 " + format(model.number(P.focusWidth), 0) + "%"
            else -> if (model.mode == 0) "拓展 " + format(model.number(P.legacyWidth), 0) + "% · 中央 " +
                format(model.number(P.legacyCenter), 0) + "%" else "M/S " + format(model.number(P.width), 0) + "%"
        }
        caption(canvas, title)
        for (step in 1..3) {
            val r = radius * step / 3f
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f, false, gridPaint)
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint)
        canvas.drawLine(cx, cy, cx, cy - radius, gridPaint)
        val alpha = if (model.active(focus, focusKey)) 0x65 else 0x2b
        when (focus) {
            FieldChartFocus.DEPTH -> {
                val inner = radius * .30f
                val outer = radius * (.45 + depth * .55).toFloat()
                fan(canvas, cx, cy, radius, inner, angle(1000.0), ColorUtils.setAlphaComponent(otherColor, 0x20))
                fan(canvas, cx, cy, outer, inner * (1f - mix.toFloat() * .4f), angle(1000.0),
                    ColorUtils.setAlphaComponent(usableColor, (alpha * mix).roundToInt()))
                point(canvas, cx, cy - outer, usableColor)
            }
            FieldChartFocus.BAND -> {
                fun r(hz: Double) = radius * (ln(hz.coerceIn(20.0, model.frequencyHigh) / 20.0) /
                    ln(model.frequencyHigh / 20.0)).toFloat()
                fan(canvas, cx, cy, radius, 0f, 45.0, ColorUtils.setAlphaComponent(foreground, 0x0e))
                fan(canvas, cx, cy, r(high), r(low), angle(sqrt(low * high)), ColorUtils.setAlphaComponent(usableColor, alpha))
            }
            FieldChartFocus.FOCUS -> {
                val boundary = radius * (ln(focusHz.coerceIn(20.0, model.frequencyHigh) / 20.0) /
                    ln(model.frequencyHigh / 20.0)).toFloat()
                fan(canvas, cx, cy, radius, boundary, angle(model.frequencyHigh * .5), ColorUtils.setAlphaComponent(usableColor, 0x29))
                fan(canvas, cx, cy, boundary, 0f, angle(20.0), ColorUtils.setAlphaComponent(usableColor, alpha))
                point(canvas, cx, cy - boundary, otherColor)
            }
            else -> {
                val frequencies = listOf(80.0, 1000.0, min(8000.0, model.frequencyHigh))
                frequencies.forEachIndexed { index, frequency ->
                    fan(canvas, cx, cy, radius * (index + 1) / 3f, radius * index / 3f,
                        angle(frequency), ColorUtils.setAlphaComponent(usableColor, alpha - index * 10))
                }
            }
        }
        val centerStrength = (model.midGain / 2.0).coerceIn(.15, 1.0)
        fillPaint.color = ColorUtils.setAlphaComponent(otherColor, 0x9a)
        val half = d(1f) + d(2f) * centerStrength.toFloat()
        canvas.drawRect(cx - half, cy - radius * centerStrength.toFloat(), cx + half, cy, fillPaint)
        text(canvas, "左", cx - radius, cy + d(11f), d(20f))
        text(canvas, "中央", cx, cy + d(11f), d(40f), align = Paint.Align.CENTER)
        text(canvas, "右", cx + radius, cy + d(11f), d(20f), align = Paint.Align.RIGHT)
        footer(canvas, when (focus) {
            FieldChartFocus.DEPTH -> "内外仅层次示意 · 非距离"
            FieldChartFocus.BAND, FieldChartFocus.FOCUS -> "半径为对数频率 · 参数示意"
            else -> "开合为相对宽度 · 非实测方位"
        })
    }
    private fun box(canvas: Canvas, bounds: RectF, label: String, selected: Boolean = false) {
        fillPaint.color = ColorUtils.setAlphaComponent(if (selected) usableColor else foreground, if (selected) 0x26 else 0x0d)
        canvas.drawRoundRect(bounds, d(3f), d(3f), fillPaint)
        canvas.drawRoundRect(bounds, d(3f), d(3f), gridPaint)
        text(canvas, label, bounds.centerX(), bounds.centerY() + d(3f), bounds.width() - d(3f),
            if (selected) usableColor else foreground, Paint.Align.CENTER)
    }
    private fun drawRouting(canvas: Canvas) {
        caption(canvas, "读取试听状态 · 不改变音频")
        val labels = listOf("正常立体声", "中央 Mid", "侧向 Side", "第二轮差分")
        val selected = when (model.audition) { 1, 2 -> 1; 3 -> 2; 4 -> 3; else -> 0 }
        val left = d(4f); val right = width - d(4f); val top = d(23f)
        val row = (height - d(45f)) / 4f
        labels.forEachIndexed { index, label ->
            box(canvas, RectF(left, top + row * index, right, top + row * index + row - d(2f)), label, index == selected)
        }
        footer(canvas, "差分不是 Side · 不存入预设")
    }
    private fun drawFold(canvas: Canvas) {
        caption(canvas, "单声道合并保留中央分量")
        val cx = width * .5f; val top = d(23f); val bar = d(23f)
        box(canvas, RectF(d(5f), top, cx - d(5f), top + bar), "L", true)
        box(canvas, RectF(cx + d(5f), top, width - d(5f), top + bar), "R", true)
        linePaint.color = usableColor; linePaint.pathEffect = null
        canvas.drawLine(width * .25f, top + bar, cx, height * .59f, linePaint)
        canvas.drawLine(width * .75f, top + bar, cx, height * .59f, linePaint)
        box(canvas, RectF(width * .22f, height * .59f, width * .78f, height * .81f), "(L+R)/2", true)
        text(canvas, "Side (L−R)/2 互相抵消", cx, height - d(18f), width - d(8f), align = Paint.Align.CENTER)
        footer(canvas, "抵消程度取决于原始音频")
    }

    private fun details(): String {
        val controls = when (focus) {
            FieldChartFocus.WIDTH -> if (model.mode == 0) listOf(P.legacyWidth, P.legacyCenter, P.midTrim) else listOf(P.width, P.midTrim)
            FieldChartFocus.GAIN -> listOf(P.outputTrim)
            FieldChartFocus.DEPTH -> listOf(P.depth, P.depthMix)
            FieldChartFocus.BAND -> listOf(P.bandLow, P.bandHigh)
            FieldChartFocus.FOCUS -> listOf(P.focusFrequency, P.focusWidth)
            FieldChartFocus.DYNAMIC -> A.dynamic
            FieldChartFocus.GUARD -> A.guard
            FieldChartFocus.TRANSIENT -> A.transient
            FieldChartFocus.DELAY -> A.delay
            FieldChartFocus.DIFFUSION -> A.diffusion
            FieldChartFocus.AUDITION -> emptyList()
        }
        val note = when (focus) {
            FieldChartFocus.WIDTH -> "Mid 是中央和声，Side 是左右差分。中央声像是原版矩阵增益，不是人声提取。曲线仅为稳态宽度与增益目标，不含深度和动态保护的瞬时结果。"
            FieldChartFocus.GAIN -> "左图表示线性幅度缩放；右图在同一输入下按幅度比例的平方表示相对能量，不是当前音乐的 RMS 或峰值测量。"
            FieldChartFocus.DEPTH -> "深度 200–800 沿用原单位，不能换算成毫秒或米。左图仅说明强度位置与混合比例，右图是相对纵深示意，不是混响尾音或实际房间距离。两模式分别记忆深度开关。"
            FieldChartFocus.BAND -> "仅对 Side 宽度调整作柔和选频。上下限不是硬分频边界；范围很窄时作用可能较弱。中央增益仍可作用于全频。"
            FieldChartFocus.FOCUS -> "低频 Side 逐渐收拢；100% 保留当前宽度，0% 最大收窄，截止处仍是柔和过渡。图中曲线按保留参数预览，不会自动打开低频聚焦。"
            FieldChartFocus.DYNAMIC -> "阈值单位 dBFS，比率单位 :1，最大 Side 衰减单位 dB；启动和恢复均为 ms。右图用归一化阶跃说明时间常数，不代表正在播放的实际包络。"
            FieldChartFocus.GUARD -> "恢复间隔是相关系数差值，不是毫秒。只有新增处理比兼容基线更差并低于阈值时才回收；单一阈值图不预测实际触发，也不能修复原始反相。收回和恢复时间均为 ms。"
            FieldChartFocus.TRANSIENT -> "起音检测阈值是快慢包络的电平差（dB），不是绝对 dBFS。保护量表示暂时收回新增空间的比例，不是人声或鼓点分离。"
            FieldChartFocus.DELAY -> "侧向微延时为真实 ms，稳态目标采样数 = ms × 采样率 / 1000。分数部分由相邻采样线性插值。右图包括该插值与混合的频响/相位；参数切换时的过渡不在稳态模型中。只处理已有 Side，不能让单声道自动产生立体声。"
            FieldChartFocus.DIFFUSION -> "全通级数仅 1、2、4；跨度单位 oct。频点按当前采样率限制。左图为稳态全通链相位，右图为与原 Side 混合后的幅度，混合可能产生衰减和染色；不是实测频谱。1 级只有中心频点，跨度不会改变该频点。"
            FieldChartFocus.AUDITION -> "正常、Mid、Side 与第二轮效果差分只是临时试听。差分是第二轮处理声减去其旁路参考，不是 Side 独奏。真实监测位于输出衰减之后、临时试听之前。"
        }
        val status = if (model.active(focus, focusKey)) "当前组已启用；以下为参数模型。" else "当前音效、模式或子开关未启用此组；仅灰色参数预览。"
        val extra = when (focus) {
            FieldChartFocus.DELAY -> "目标采样数 " + format(model.delaySamples, 3) + "；建模采样率 " + model.rate + " Hz。"
            FieldChartFocus.DIFFUSION -> "稳态全通频点 " + model.diffusionFrequencies().joinToString(" / ") { format(it, 0) } + " Hz。"
            else -> "建模采样率 " + model.rate + " Hz。"
        }
        return (listOf(model.modeName + " · 参数示意", status) + controls.map(model::label) + listOf(extra, note))
            .joinToString(System.lineSeparator())
    }
    override fun onDetachedFromWindow() {
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}
