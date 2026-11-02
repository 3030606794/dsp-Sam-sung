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
import me.timschneeberger.rootlessjamesdsp.fusion.WidthAdvancedParameters as A
import me.timschneeberger.rootlessjamesdsp.fusion.WidthAdvancedRuntime
import me.timschneeberger.rootlessjamesdsp.fusion.WidthChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.WidthController
import me.timschneeberger.rootlessjamesdsp.fusion.WidthDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.WidthParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** Saved-parameter observers. No chart owns an editor, audio lease or comparison operation. */
internal class WidthParameterChart(context: Context) : WidthFocusStaticBase(context, false)
internal class WidthIllustrationChart(context: Context) : WidthFocusStaticBase(context, true)

private data class WidthFocusModel(
    val values: Map<String, *>, val rate: Int, val supported: Boolean, val advanced: Boolean,
    val audition: Int, val savedA: Boolean, val savedB: Boolean, val comparisonMatch: Boolean
) {
    private val entries = A.entries.associateBy { it.control.key }
    val mode get() = if (supported) P.choice(values, P.MODE) else 0
    val modeName get() = listOf("原版旧刻度", "全频 M/S", "三段 M/S")[mode]
    val effectOn get() = P.on(values, P.ENABLED)
    val highHz get() = min(20000.0, rate * .45)
    val legacy get() = (values[P.LEGACY] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: 60.0
    fun raw(control: BassParameters.Control) = entries[control.key]?.let { A.value(values, it) } ?: P.value(values, control)
    fun number(control: BassParameters.Control) = raw(control).toDouble() / control.divisor
    fun extra(key: String) = entries[key]?.let { A.value(values, it).toDouble() / it.control.divisor } ?: 0.0
    fun on(key: String) = if (key in entries) advanced && A.value(values, entries.getValue(key)) == 1 else P.on(values, key)
    fun label(control: BassParameters.Control) = control.title + " " + control.label(raw(control))
    fun configuration(vararg overrides: Pair<String, Any?>): WidthDashboardModel {
        val target = values.toMutableMap()
        target[P.MODE] = mode
        if (!advanced) A.entries.filter { it.kind == "switch" }.forEach { target[it.control.key] = false }
        overrides.forEach { target[it.first] = it.second }
        return WidthDashboardModel.from(target, rate, true)
    }
    val dashboard by lazy { configuration() }
    val threeBand by lazy { configuration(P.MODE to 2) }
    val monoPreview by lazy { configuration(P.MONO to true) }
    val withoutEq by lazy { configuration("width2_eq0_on" to false, "width2_eq1_on" to false) }
    fun dynamicIndex(key: String): Int = A.entries.firstOrNull { it.control.key == key && it.group == 1 }?.band
        ?: if (mode == 1) 3 else 0
    fun active(focus: WidthChartFocus, key: String): Boolean {
        if (!effectOn) return false
        if (focus == WidthChartFocus.WIDTH && key == P.LEGACY) return mode == 0
        if (!supported) return false
        return when (focus) {
            WidthChartFocus.WIDTH -> key == P.MODE || mode != 0
            WidthChartFocus.BANDS -> mode == 2
            WidthChartFocus.MONO -> on(P.MONO)
            WidthChartFocus.LEVEL -> true
            WidthChartFocus.AUTO -> on(P.AUTO) && (!key.startsWith("width2_") || on("width2_auto_on"))
            WidthChartFocus.GUARD -> mode != 0 && dashboard.guardEnabled &&
                (!key.startsWith("width2_") || on("width2_guard_on")) &&
                (key !in setOf("width2_guard_low", "width2_guard_mid", "width2_guard_high") ||
                    (mode == 2 && extra("width2_guard_mode") == 1.0))
            WidthChartFocus.DYNAMIC -> advanced && dashboard.dynamics[dynamicIndex(key)].active
            WidthChartFocus.TRANSIENT -> mode != 0 && on("width2_transient_on")
            WidthChartFocus.EQ -> mode != 0 && on(if (key.startsWith("width2_eq1_")) "width2_eq1_on" else "width2_eq0_on")
            WidthChartFocus.MONITOR -> !key.startsWith("width2_") || advanced
            WidthChartFocus.AUDITION -> !key.startsWith("width2_") || (advanced && mode != 0)
        }
    }
    private val frequencySamples by lazy { List(97) { 20.0 * (highHz / 20.0).pow(it / 96.0) } }
    // Each immutable configuration computes these small saved-target traces once, never per animation frame.
    val widthCurve by lazy { frequencySamples.map { dashboard.widthAt(it) } }
    val bandCurve by lazy { frequencySamples.map { threeBand.widthAt(it) } }
    val monoCurve by lazy { frequencySamples.map { monoPreview.monoSideGainAt(it) } }
    val noEqCurve by lazy { frequencySamples.map { withoutEq.widthAt(it) } }
    private val eqPreview0 by lazy { configuration(P.MODE to if (mode == 0) 1 else mode, "width2_eq0_on" to true) }
    private val eqPreview1 by lazy { configuration(P.MODE to if (mode == 0) 1 else mode, "width2_eq1_on" to true) }
    private val eqReference by lazy { configuration(P.MODE to if (mode == 0) 1 else mode,
        "width2_eq0_on" to false, "width2_eq1_on" to false) }
    val eqCurve0 by lazy { frequencySamples.map { eqPreview0.widthAt(it) } }
    val eqCurve1 by lazy { frequencySamples.map { eqPreview1.widthAt(it) } }
    val eqReferenceCurve by lazy { frequencySamples.map { eqReference.widthAt(it) } }
}

internal open class WidthFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
    private val density = resources.displayMetrics.density
    private fun d(value: Float) = value * density
    private val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val teal = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(108, 214, 205) else Color.rgb(18, 124, 116)
    private val amber = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(245, 186, 109) else Color.rgb(161, 92, 23)
    private val colors get() = listOf(color, secondary, third)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(9f * resources.displayMetrics.scaledDensity, d(11f)) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(1.5f) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(foreground, 0x29); style = Paint.Style.STROKE; strokeWidth = d(.65f) }
    private val plot = RectF()
    private val path = Path()
    private var values: Map<String, *> = emptyMap<String, Any>()
    private var rate = 48000
    private var model = snapshot()
    private var focus = WidthChartFocus.WIDTH
    private var key = P.MODE
    private var active = true
    private var detailDialog: AlertDialog? = null
    private val enabled get() = model.active(focus, key)
    private val color get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x77)
    private val secondary get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x4b)
    private fun snapshot() = WidthFocusModel(values, rate, WidthController.supported(context),
        WidthController.supportedAdvanced(context), WidthController.audition,
        WidthController.hasComparison(8), WidthController.hasComparison(9), WidthController.compareMatch)

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
        val observed = P.newKeys + A.entries.map { it.control.key } + P.LEGACY + P.ENABLED
        this.values = values.filterKeys { it in observed }.toMap()
        this.rate = rate.takeIf { it in 8000..768000 } ?: 48000
        val next = snapshot()
        if (next == model) return
        model = next
        updateDescription()
        if (active) invalidate()
    }
    fun setFocus(focus: WidthChartFocus, key: String) {
        if (this.focus == focus && this.key == key) return
        this.focus = focus; this.key = key
        updateDescription()
        if (active) invalidate()
    }
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (!active) { detailDialog?.dismiss(); detailDialog = null } else invalidate()
    }
    private fun updateDescription() { contentDescription = details() }
    private fun fmt(value: Double, decimals: Int = 1) = String.format(Locale.ROOT, "%." + decimals + "f", value)
    private fun signed(value: Double) = String.format(Locale.ROOT, "%+.1f", value)
    private fun frequencyEnd() = if (model.highHz >= 1000) fmt(model.highHz / 1000, if (model.highHz % 1000 == 0.0) 0 else 1) + "k Hz" else fmt(model.highHz, 0) + " Hz"
    private fun db(value: Double) = 20.0 * log10(value.coerceAtLeast(1e-6))
    private fun n(control: BassParameters.Control) = model.number(control)
    private fun a(name: String) = model.extra(name)
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, space: Float,
        tint: Int = foreground, align: Paint.Align = Paint.Align.LEFT) {
        if (space <= 0) return
        textPaint.color = tint; textPaint.textAlign = align
        val count = textPaint.breakText(value, true, space, null)
        canvas.drawText(if (count == value.length) value else value.take((count - 1).coerceAtLeast(0)) + "…", x, y, textPaint)
    }
    private fun caption(canvas: Canvas, value: String) = text(canvas, value, d(3f), d(11f), width - d(6f), color)
    private fun footer(canvas: Canvas, value: String) = text(canvas,
        if (enabled) value else "未生效 · 参数预览", d(3f), height - d(3f), width - d(6f), ColorUtils.setAlphaComponent(foreground, 0xa0))
    private fun px(value: Double) = plot.left + plot.width() * value.coerceIn(0.0, 1.0).toFloat()
    private fun py(value: Double) = plot.bottom - plot.height() * value.coerceIn(0.0, 1.0).toFloat()
    private fun fx(hz: Double) = px(ln(hz.coerceIn(20.0, model.highHz) / 20.0) / ln(model.highHz / 20.0))
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
    private fun trace(canvas: Canvas, tint: Int = color, dashed: Boolean = false, response: (Double) -> Double) {
        path.rewind()
        var started = false
        for (index in 0..96) {
            val x = index / 96.0; val y = response(x)
            if (!y.isFinite()) { started = false; continue }
            if (!started) { path.moveTo(px(x), py(y)); started = true } else path.lineTo(px(x), py(y))
        }
        linePaint.color = tint
        linePaint.pathEffect = if (dashed || !enabled) DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f) else null
        canvas.save(); canvas.clipRect(plot); canvas.drawPath(path, linePaint); canvas.restore()
        linePaint.pathEffect = null
    }
    private fun cachedTrace(canvas: Canvas, samples: List<Double>, tint: Int = color, dashed: Boolean = false,
        transform: (Double) -> Double) = trace(canvas, tint, dashed) { transform(samples[(it * (samples.size - 1)).roundToInt()]) }
    private fun marker(canvas: Canvas, x: Float, tint: Int = color) {
        linePaint.color = ColorUtils.setAlphaComponent(tint, 0xb0)
        linePaint.pathEffect = DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f)
        canvas.drawLine(x, plot.top, x, plot.bottom, linePaint); linePaint.pathEffect = null
    }
    private fun horizontal(canvas: Canvas, y: Float, tint: Int = color, dashed: Boolean = false) {
        linePaint.color = tint
        linePaint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f) else null
        canvas.drawLine(plot.left, y, plot.right, y, linePaint); linePaint.pathEffect = null
    }
    private fun point(canvas: Canvas, x: Float, y: Float, tint: Int = color) {
        fillPaint.color = tint; canvas.drawCircle(x, y, d(2.3f), fillPaint)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || width < d(55f) || height < d(55f)) return
        plot.set(d(27f), d(21f), width - d(7f), height - d(26f))
        if (illustration) drawIllustration(canvas) else drawParameters(canvas)
    }
    private fun drawParameters(canvas: Canvas) = when (focus) {
        WidthChartFocus.WIDTH -> drawWidth(canvas, false)
        WidthChartFocus.BANDS -> drawWidth(canvas, true)
        WidthChartFocus.MONO -> drawMono(canvas)
        WidthChartFocus.LEVEL -> drawLevel(canvas)
        WidthChartFocus.AUTO -> drawAuto(canvas)
        WidthChartFocus.GUARD -> drawGuard(canvas)
        WidthChartFocus.DYNAMIC -> drawDynamic(canvas)
        WidthChartFocus.TRANSIENT -> drawTransient(canvas)
        WidthChartFocus.EQ -> drawEq(canvas)
        WidthChartFocus.MONITOR -> drawMonitor(canvas)
        WidthChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas) = when (focus) {
        WidthChartFocus.WIDTH, WidthChartFocus.BANDS -> drawRadar(canvas)
        WidthChartFocus.MONO -> drawMonoIllustration(canvas)
        WidthChartFocus.LEVEL -> drawEnergy(canvas)
        WidthChartFocus.AUTO -> drawAutoEnvelope(canvas)
        WidthChartFocus.GUARD -> drawProtectionEnvelope(canvas, false)
        WidthChartFocus.DYNAMIC -> drawProtectionEnvelope(canvas, true)
        WidthChartFocus.TRANSIENT -> drawTransientEnvelope(canvas)
        WidthChartFocus.EQ -> drawIncrementRoute(canvas)
        WidthChartFocus.MONITOR -> drawMonitorRoute(canvas)
        WidthChartFocus.AUDITION -> drawComparison(canvas)
    }
    private fun drawWidth(canvas: Canvas, bands: Boolean) {
        if (!bands && model.mode == 0) {
            caption(canvas, "原算法 · 旧控制量 " + fmt(model.legacy, 2))
            grid(canvas, "旧刻度", "", "30", "75")
            val x = ((model.legacy - 30) / 45).coerceIn(0.0, 1.0)
            fillPaint.color = ColorUtils.setAlphaComponent(color, 0x30)
            canvas.drawRect(plot.left, py(.62), px(x), py(.38), fillPaint)
            horizontal(canvas, py(.5), secondary, true); marker(canvas, px(x)); point(canvas, px(x), py(.5))
            footer(canvas, "驱动目标取整 " + model.legacy.toInt() + " · 非百分比")
            return
        }
        val current = if (bands) model.threeBand else model.dashboard
        caption(canvas, if (bands) "低／中／高 × 总体 · 参数模型" else "总体 " + fmt(model.dashboard.masterWidth * 100) + "% · Side/Mid")
        grid(canvas, "4×", "0", "20 Hz", frequencyEnd())
        horizontal(canvas, py(.25), secondary, true)
        if (bands) {
            val cutoffs = listOf(20.0, current.crossLowHz, current.crossHighHz, model.highHz)
            repeat(3) { index ->
                fillPaint.color = ColorUtils.setAlphaComponent(colors[index], 0x16)
                canvas.drawRect(fx(cutoffs[index]), plot.top, fx(cutoffs[index + 1]), plot.bottom, fillPaint)
            }
            marker(canvas, fx(current.crossLowHz), secondary); marker(canvas, fx(current.crossHighHz), third)
        }
        cachedTrace(canvas, if (bands) model.bandCurve else model.widthCurve) { it / 4 }
        footer(canvas, if (bands) (if (P.choice(values, P.SPLIT_SLOPE) == 0) "LR12" else "LR24") +
            " · " + (if (model.on(P.LINK)) "频段联动编辑" else "各频段独立编辑") else "1× 为原 Side/Mid 比例")
    }
    private fun drawMono(canvas: Canvas) {
        caption(canvas, fmt(n(P.monoHz), 0) + " Hz · " + fmt(n(P.monoAmount)) + "% 收拢")
        grid(canvas, "0 dB", "−60", "20 Hz", frequencyEnd())
        cachedTrace(canvas, model.monoCurve) { (db(it) + 60) / 60 }
        marker(canvas, fx(model.monoPreview.monoHz))
        footer(canvas, "Side 高通 · " + model.monoPreview.monoOrder * 6 + " dB/oct")
    }
    private fun drawLevel(canvas: Canvas) {
        val mix = n(P.mix) / 100; val output = 10.0.pow(n(P.output) / 20)
        val mid = (1 - mix + mix * 10.0.pow(n(P.midGain) / 20)) * output
        caption(canvas, "混合 " + fmt(n(P.mix)) + "% · 输出 " + signed(n(P.output)) + " dB")
        grid(canvas, "+12", "−24", "输入 −24 dB", "0 dB")
        trace(canvas, secondary, true) { ((-24 + 24 * it) + 24) / 36 }
        trace(canvas, color) { ((-24 + 24 * it) + db(mid) + 24) / 36 }
        trace(canvas, third) { ((-24 + 24 * it) + n(P.output) + 24) / 36 }
        footer(canvas, if (model.mode == 0) "单位 Mid 参考 · 不含原版矩阵" else "紫 Mid · 金 输出校准 · 静态增益")
    }
    private fun drawAuto(canvas: Canvas) {
        val maximum = n(P.autoMax)
        val advanced = model.on("width2_auto_on")
        val deadband = if (advanced) a("width2_auto_deadband") else 0.0
        val attenuationOnly = advanced && a("width2_auto_direction") == 1.0
        if (key == P.autoWindow.key) {
            val window = n(P.autoWindow)
            caption(canvas, "RMS 分析平滑 " + fmt(window, 0) + " ms")
            grid(canvas, "1", "0", "阶跃 t = 0", "6000 ms")
            trace(canvas) { 1 - exp(-6000 * it / window) }
            marker(canvas, px(window / 6000), secondary)
        } else if (key == "width2_auto_floor") {
            val floor = a("width2_auto_floor")
            caption(canvas, "低于 " + fmt(floor) + " dBFS 冻结匹配")
            grid(canvas, "更新", "冻结", "−90 dBFS", "−30")
            val x = (floor + 90) / 60
            fillPaint.color = ColorUtils.setAlphaComponent(secondary, 0x20)
            canvas.drawRect(plot.left, plot.top, px(x), plot.bottom, fillPaint)
            trace(canvas) { if (it >= x) .8 else .2 }; marker(canvas, px(x))
        } else {
            caption(canvas, "分析 " + fmt(n(P.autoWindow), 0) + " ms · 限幅 ±" + fmt(maximum) + " dB")
            grid(canvas, "+6 dB", "−6", "候选偏差 −12", "+12 dB")
            trace(canvas) {
                val deviation = -12 + 24 * it
                val correction = if (abs(deviation) < deadband) 0.0 else -deviation
                (correction.coerceIn(-maximum, if (attenuationOnly) 0.0 else maximum) + 6) / 12
            }
            marker(canvas, px((12 - deadband) / 24), secondary); marker(canvas, px((12 + deadband) / 24), secondary)
        }
        footer(canvas, if (advanced) (if (attenuationOnly) "仅衰减" else "双向") + " · 上一补偿 0 dB 参考" else "双向匹配 · RMS 比值目标")
    }
    private fun drawGuard(canvas: Canvas) {
        val thresholds = model.dashboard.guardThresholds
        val hysteresis = n(P.hysteresis)
        if (key == "width2_guard_floor") {
            caption(canvas, "有效信号 ≥" + fmt(a("width2_guard_floor")) + " dBFS")
            grid(canvas, "参与", "冻结", "−90 dBFS", "−30")
            val threshold = (a("width2_guard_floor") + 90) / 60
            trace(canvas) { if (it >= threshold) .8 else .2 }; marker(canvas, px(threshold))
        } else if (key == "width2_guard_window") {
            val window = a("width2_guard_window")
            caption(canvas, "相关度检测平滑 " + fmt(window, 0) + " ms")
            grid(canvas, "1", "0", "阶跃 t = 0", "1500 ms")
            trace(canvas) { 1 - exp(-1500 * it / window) }
            marker(canvas, px(window / 1500), secondary)
        } else {
            caption(canvas, "相关度 · 触发 / 恢复迟滞 " + fmt(hysteresis, 2))
            grid(canvas, "恢复", "收回", "−1.0", "+1.0")
            thresholds.forEachIndexed { index, threshold ->
                val bottom = .10 + index * .22
                trace(canvas, colors[index]) { if (-1 + 2 * it < threshold) bottom else bottom + .20 }
                marker(canvas, px((threshold + 1) / 2), colors[index])
                marker(canvas, px((threshold + hysteresis + 1) / 2), colors[index])
            }
        }
        val advanced = model.on("width2_guard_on")
        footer(canvas, if (advanced) fmt(a("width2_guard_window"), 0) + " ms · " +
            (if (a("width2_guard_point") == 0.0) "湿声候选" else "干湿候选") else "第一代保护 · 仅回收新增扩宽")
    }
    private fun dynamic() = model.dashboard.dynamics[model.dynamicIndex(key)]
    private fun drawDynamic(canvas: Canvas) {
        val band = dynamic()
        caption(canvas, band.label + " · " + fmt(band.ratio) + ":1 · Knee " + fmt(band.kneeDb) + " dB")
        grid(canvas, "0 dB", "−12", "Side/Mid −30", "+6 dB")
        trace(canvas) { 1 - band.reductionDb(-30 + 36 * it) / 12 }
        marker(canvas, px((band.thresholdDb + 30) / 36))
        footer(canvas, if (band.width <= 1) "宽度 ≤100% · 无新增扩宽可回收" else "稳态回收上限 " + fmt(band.maximumReductionDb) + " dB")
    }
    private fun drawTransient(canvas: Canvas) {
        val threshold = a("width2_transient_threshold")
        caption(canvas, "快／慢包络差 · 阈值 " + fmt(threshold) + " dB")
        grid(canvas, "回收", "保留", "0 dB", "+18 dB")
        val amount = a("width2_transient_max") / 100
        trace(canvas) { if (18 * it > threshold) amount else 0.0 }
        marker(canvas, px(threshold / 18))
        footer(canvas, "0.5 / 50 ms 检测 · 新增 Side 保护")
    }
    private fun drawEq(canvas: Canvas) {
        val index = if (key.startsWith("width2_eq1_")) 1 else 0
        val prefix = "width2_eq" + index + "_"
        caption(canvas, "新增 Side PEQ " + (index + 1) + " · Q " + fmt(a(prefix + "q"), 2))
        grid(canvas, "+18", "−18", "20 Hz", frequencyEnd())
        cachedTrace(canvas, model.eqReferenceCurve, secondary, true) { (db(it) + 18) / 36 }
        cachedTrace(canvas, if (enabled) model.widthCurve else if (index == 0) model.eqCurve0 else model.eqCurve1) { (db(it) + 18) / 36 }
        marker(canvas, fx(a(prefix + "hz")))
        footer(canvas, "Side/Mid dB · 虚线为增量 EQ 关闭")
    }
    private fun drawMonitor(canvas: Canvas) {
        val history = model.dashboard.historySeconds.toDouble()
        val smoothing = n(P.meterWindow)
        caption(canvas, "平滑 " + fmt(smoothing, 0) + " ms · 历史 " + fmt(history, 0) + " s")
        grid(canvas, "1", "0", "阶跃 t = 0", "3000 ms")
        trace(canvas) { 1 - exp(-3000 * it / smoothing) }
        marker(canvas, px(smoothing / 3000), secondary)
        footer(canvas, "监测响应示意 · 不改变声音")
    }
    private fun drawAudition(canvas: Canvas) {
        caption(canvas, "当前路径 · " + WidthAdvancedRuntime.auditionLabel(model.audition))
        val labels = listOf("正常", "原声", "Mid", "Side", "低频", "中频", "高频", "增量", "A", "B")
        labels.forEachIndexed { index, label ->
            val column = index % 5; val row = index / 5
            val box = RectF(plot.left + plot.width() * column / 5 + d(1f), plot.top + plot.height() * row / 2 + d(3f),
                plot.left + plot.width() * (column + 1) / 5 - d(1f), plot.top + plot.height() * (row + 1) / 2 - d(3f))
            fillPaint.color = ColorUtils.setAlphaComponent(if (index == model.audition) color else foreground, if (index == model.audition) 0x38 else 0x0b)
            canvas.drawRoundRect(box, d(3f), d(3f), fillPaint)
            text(canvas, label, box.centerX(), box.centerY() + d(3f), box.width(), if (index == model.audition) color else foreground, Paint.Align.CENTER)
        }
        footer(canvas, "监测点位于试听分量选择之前")
    }
    /** Relative fan opening is a parameter illustration, not a source direction or distance estimate. */
    private fun fan(canvas: Canvas, radius: Float, widthRatio: Double, tint: Int, centerX: Float = plot.centerX(), base: Float = plot.bottom) {
        val halfAngle = atan(widthRatio.coerceAtLeast(0.0)) * 180 / PI
        path.rewind(); path.moveTo(centerX, base)
        val oval = RectF(centerX - radius, base - radius, centerX + radius, base + radius)
        path.arcTo(oval, (-90 - halfAngle).toFloat(), (halfAngle * 2).toFloat(), false); path.close()
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x30); canvas.drawPath(path, fillPaint)
        linePaint.color = tint; linePaint.pathEffect = if (enabled) null else DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f)
        canvas.drawPath(path, linePaint); linePaint.pathEffect = null
    }
    private fun radarGrid(canvas: Canvas): Float {
        val radius = min(plot.width() / 2, plot.height())
        for (index in 1..3) {
            val r = radius * index / 3
            canvas.drawArc(RectF(plot.centerX() - r, plot.bottom - r, plot.centerX() + r, plot.bottom + r), 180f, 180f, false, gridPaint)
        }
        canvas.drawLine(plot.centerX(), plot.top, plot.centerX(), plot.bottom, gridPaint)
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, gridPaint)
        return radius
    }
    private fun drawRadar(canvas: Canvas) {
        val radius = radarGrid(canvas)
        if (focus == WidthChartFocus.WIDTH && model.mode == 0) {
            caption(canvas, "原算法 · 控制范围示意")
            val normalized = ((model.legacy - 30) / 45).coerceIn(0.0, 1.0)
            fan(canvas, radius, .25 + normalized * 1.75, color)
            footer(canvas, "旧刻度相对示意 · 非 M/S 倍率")
            return
        }
        val bands = focus == WidthChartFocus.BANDS || model.mode == 2
        caption(canvas, if (bands) "低／中／高 · 彩色扇区" else "Mid 居中 · Side 向两侧展开")
        if (bands) {
            model.dashboard.bandWidths.indices.reversed().forEach { index ->
                fan(canvas, radius * (index + 1) / 3f, model.dashboard.bandWidths[index], colors[index])
            }
        } else fan(canvas, radius, model.dashboard.masterWidth, color)
        footer(canvas, "相对开合示意 · 非实际方位")
    }
    private fun drawMonoIllustration(canvas: Canvas) {
        val radius = radarGrid(canvas)
        caption(canvas, "低频保留 Mid · 收回 Side")
        fan(canvas, radius, 1.0, secondary)
        val lowFraction = (ln(n(P.monoHz) / 20) / ln(model.highHz / 20)).coerceIn(.08, .7)
        val remaining = 1 - n(P.monoAmount) / 100
        fan(canvas, radius * lowFraction.toFloat(), remaining, color)
        linePaint.color = color
        canvas.drawLine(plot.centerX(), plot.bottom, plot.centerX(), plot.bottom - radius * lowFraction.toFloat(), linePaint)
        footer(canvas, fmt(n(P.monoHz), 0) + " Hz 以下柔和收拢 · 非硬切")
    }
    private fun bar(canvas: Canvas, index: Int, count: Int, value: Double, name: String, amount: String, tint: Int) {
        val cell = plot.width() / count
        val x = plot.left + cell * index + cell * .22f
        val top = py(value)
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x45)
        canvas.drawRoundRect(RectF(x, top, x + cell * .56f, plot.bottom), d(2f), d(2f), fillPaint)
        text(canvas, amount, x + cell * .28f, max(plot.top + d(9f), top - d(3f)), cell * .92f, tint, Paint.Align.CENTER)
        text(canvas, name, x + cell * .28f, plot.bottom + d(11f), cell, foreground, Paint.Align.CENTER)
    }
    private fun drawEnergy(canvas: Canvas) {
        val mix = n(P.mix) / 100; val out = 10.0.pow(n(P.output) / 20)
        val mid = (1 - mix + mix * 10.0.pow(n(P.midGain) / 20)) * out
        caption(canvas, "相同输入幅度 · 相对增益示意")
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, gridPaint)
        bar(canvas, 0, 3, (1 - mix) / 2, "原声", fmt((1 - mix) * 100, 0) + "%", secondary)
        bar(canvas, 1, 3, mid / 2, "Mid", signed(db(mid)) + "dB", color)
        bar(canvas, 2, 3, out / 2, "输出", signed(n(P.output)) + "dB", third)
        footer(canvas, "实际能量还取决于输入内容")
    }
    private fun envelope(canvas: Canvas, attack: Double, hold: Double, release: Double, amount: Double, tint: Int = color): Double {
        val total = (attack * 4 + hold + release * 4).coerceAtLeast(1.0)
        val endAttack = attack * 4; val endHold = endAttack + hold
        trace(canvas, tint) { fraction ->
            val time = total * fraction
            val value = if (time < endAttack) 1 - exp(-time / attack.coerceAtLeast(.001))
                else if (time < endHold) 1.0 else exp(-(time - endHold) / release.coerceAtLeast(.001))
            amount * value
        }
        marker(canvas, px(endAttack / total), secondary)
        if (hold > 0) marker(canvas, px(endHold / total), third)
        return total
    }
    private fun drawAutoEnvelope(canvas: Canvas) {
        val advanced = model.on("width2_auto_on") || key.startsWith("width2_auto_")
        val attack = if (advanced) a("width2_auto_attack") else n(P.autoWindow)
        val release = if (advanced) a("width2_auto_release") else n(P.autoWindow)
        caption(canvas, "衰减 " + fmt(attack, 0) + " / 恢复 " + fmt(release, 0) + " ms")
        val total = attack * 4 + release * 4
        grid(canvas, "补偿", "0", "0", fmt(total, 0) + " ms")
        envelope(canvas, attack, 0.0, release, n(P.autoMax) / 6)
        footer(canvas, if (advanced) "静音冻结 " + fmt(a("width2_auto_floor")) + " dBFS" else "基础 RMS 平滑 · 目标响应示意")
    }
    private fun drawProtectionEnvelope(canvas: Canvas, isDynamic: Boolean) {
        val band = dynamic()
        val attack = if (isDynamic) band.attackMs else n(P.attack)
        val release = if (isDynamic) band.releaseMs else n(P.release)
        val amount = if (isDynamic) band.maximumWithdrawal else n(P.strength) / 100
        caption(canvas, (if (isDynamic) band.label else "相关度保护") + " · " + fmt(attack, 0) + " / " + fmt(release, 0) + " ms")
        grid(canvas, "100%", "0", "0", fmt(4 * (attack + release), 0) + " ms")
        envelope(canvas, attack, 0.0, release, amount)
        footer(canvas, "回收量示意 · 基准 Side 保留")
    }
    private fun drawTransientEnvelope(canvas: Canvas) {
        val attack = a("width2_transient_attack"); val hold = a("width2_transient_hold"); val release = a("width2_transient_release")
        val mask = a("width2_transient_bands").toInt()
        caption(canvas, fmt(attack, 0) + " / 保持 " + fmt(hold, 0) + " / " + fmt(release, 0) + " ms")
        grid(canvas, "100%", "0", "0", fmt(4 * (attack + release) + hold, 0) + " ms")
        if (model.mode == 2) repeat(3) { index ->
            fillPaint.color = ColorUtils.setAlphaComponent(colors[index], if (mask and (1 shl index) != 0) 0x20 else 0x05)
            canvas.drawRect(px(index / 3.0), plot.top, px((index + 1) / 3.0), plot.bottom, fillPaint)
        }
        envelope(canvas, attack, hold, release, a("width2_transient_max") / 100)
        footer(canvas, if (model.mode == 1) "全频 M/S · 全频瞬态回收" else "作用频段 " + listOf("低", "中", "高").filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString("＋").ifEmpty { "未选择" })
    }
    private fun box(canvas: Canvas, label: String, left: Double, top: Double, right: Double, bottom: Double, tint: Int = color) {
        val box = RectF(px(left), py(bottom), px(right), py(top))
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x24)
        canvas.drawRoundRect(box, d(3f), d(3f), fillPaint)
        text(canvas, label, box.centerX(), box.centerY() + d(3f), box.width() - d(3f), tint, Paint.Align.CENTER)
    }
    private fun route(canvas: Canvas, x1: Double, y1: Double, x2: Double, y2: Double, tint: Int = color) {
        linePaint.color = tint
        canvas.drawLine(px(x1), py(y1), px(x2), py(y2), linePaint)
        point(canvas, px(x2), py(y2), tint)
    }
    private fun drawIncrementRoute(canvas: Canvas) {
        caption(canvas, "只有新增 Side 经过两段 PEQ")
        box(canvas, "基准 Side", .0, .92, .46, .65, secondary)
        box(canvas, "保留", .6, .92, 1.0, .65, secondary)
        route(canvas, .46, .78, .6, .78, secondary)
        box(canvas, "新增扩宽", .0, .4, .36, .10)
        box(canvas, "EQ1", .41, .4, .67, .10, if (model.on("width2_eq0_on")) color else secondary)
        box(canvas, "EQ2", .73, .4, 1.0, .10, if (model.on("width2_eq1_on")) color else secondary)
        route(canvas, .36, .25, .41, .25); route(canvas, .67, .25, .73, .25)
        footer(canvas, "宽度 ≤100% 时没有新增侧声")
    }
    private fun drawMonitorRoute(canvas: Canvas) {
        caption(canvas, "音频继续处理 · 监测单独读取")
        box(canvas, "处理前", .0, .94, .4, .65, secondary)
        box(canvas, "正常输出", .6, .94, 1.0, .65, color)
        route(canvas, .4, .8, .6, .8)
        box(canvas, "低／中／高", .0, .40, .55, .10, secondary)
        box(canvas, if (model.dashboard.nativeMetersEnabled) "详细监测" else "监测关闭", .62, .40, 1.0, .10, third)
        route(canvas, .55, .25, .62, .25, third)
        fillPaint.color = ColorUtils.setAlphaComponent(third, 0x70)
        canvas.drawRect(plot.left, plot.bottom - d(2f), px(model.dashboard.historySeconds / 30.0), plot.bottom, fillPaint)
        footer(canvas, "历史 " + model.dashboard.historySeconds + " / 30 s · 试听前测量")
    }
    private fun drawComparison(canvas: Canvas) {
        caption(canvas, if (model.comparisonMatch) "A/B 电平匹配 · 最大 ±3 dB" else "A/B 保留各自电平")
        box(canvas, "A " + (if (model.savedA) "已保存" else "未保存"), .0, .95, .43, .57, secondary)
        box(canvas, "B " + (if (model.savedB) "已保存" else "未保存"), .57, .95, 1.0, .57, third)
        box(canvas, WidthAdvancedRuntime.auditionLabel(model.audition), .05, .36, .95, .02)
        route(canvas, .21, .57, .40, .36, secondary); route(canvas, .78, .57, .60, .36, third)
        footer(canvas, "观察当前试听 · 原生按钮控制快照")
    }
    private fun details(): String {
        val controls = when (focus) {
            WidthChartFocus.WIDTH -> listOf(P.master)
            WidthChartFocus.BANDS -> P.bands + listOf(P.master, P.crossLow, P.crossHigh)
            WidthChartFocus.MONO -> listOf(P.monoHz, P.monoAmount)
            WidthChartFocus.LEVEL -> listOf(P.midGain, P.mix, P.output)
            WidthChartFocus.AUTO -> listOf(P.autoWindow, P.autoMax)
            WidthChartFocus.GUARD -> listOf(P.threshold, P.hysteresis, P.attack, P.release, P.strength)
            WidthChartFocus.MONITOR -> listOf(P.meterWindow)
            else -> emptyList()
        }
        val group = when (focus) {
            WidthChartFocus.DYNAMIC -> 1; WidthChartFocus.TRANSIENT -> 2; WidthChartFocus.GUARD -> 3
            WidthChartFocus.EQ -> 4; WidthChartFocus.AUTO -> 5; WidthChartFocus.MONITOR -> 6
            else -> 0
        }
        val lines = mutableListOf(model.modeName + " · " + model.rate + " Hz · 参数目标",
            if (enabled) "当前参数可用；图纸为静态模型与相对示意。" else "当前参数未生效，显示保存目标的灰色预览。")
        if (focus == WidthChartFocus.WIDTH) lines += "原算法旧控制量 " + fmt(model.legacy, 2) + "；驱动取整目标 " + model.legacy.toInt() + "。30–75 为原控制刻度，并非宽度百分比。"
        controls.forEach { lines += model.label(it) }
        A.entries.filter { it.group == group && (group != 1 || it.band == model.dynamicIndex(key)) }.forEach { entry ->
            val raw = A.value(values, entry)
            lines += entry.control.title + "：" + when (entry.kind) {
                "switch" -> if (raw == 1) "开启" else "关闭"
                "choice" -> entry.items.getOrElse(raw) { raw.toString() }
                else -> entry.control.label(raw)
            }
        }
        lines += when (focus) {
            WidthChartFocus.WIDTH -> "专业模式：总体 0% 为单声道，100% 保持原 Side/Mid 比例，200% 将 Side 放大到两倍。单声道录音没有 Side，扩大参数不会生成新的立体声。雷达只表示参数的相对开合，不表示实际声源方位、距离或房间大小。"
            WidthChartFocus.BANDS -> "专业三段 M/S 才使用三个频段宽度与分频点。图中复用相位匹配的 Linkwitz–Riley 分频响应；分频斜率 " + (if (P.choice(values, P.SPLIT_SLOPE) == 0) "12" else "24") + " dB/oct。总体与分段宽度相乘，最大 4×。频段联动编辑：" + if (model.on(P.LINK)) "开启；改变一段时原编辑器联动写入，图纸仅观察结果。" else "关闭。"
            WidthChartFocus.MONO -> "低频收拢 " + (if (model.on(P.MONO)) "开启" else "关闭") + "，Butterworth Side 高通 " + model.monoPreview.monoOrder * 6 + " dB/oct。曲线保留滤波相位后与未处理 Side 混合；Mid 保留，不是低于截止频率立即硬切。原版与专业模式均可使用。"
            WidthChartFocus.LEVEL -> "干湿比例线性混合，中置增益作用于处理 Mid，输出校准随后同时缩放两声道。曲线与柱形采用单位 Mid 输入参考，不包含原版矩阵自身的音色与相位改变；表示相对增益，不是实际 RMS 或听感响度。原版模式也使用这些控制。"
            WidthChartFocus.AUTO -> "自动匹配 " + (if (model.on(P.AUTO)) "开启" else "关闭") + "。使用处理前后的 RMS 比值，按最大补偿范围限制；高级响应还需要同时开启高级开关。死区与上一保持的补偿目标比较，图中采用上一目标 0 dB 的参考情景，不是固定的零电平门限。死区单位 dB，静音冻结门限单位 dBFS；衰减响应和增益恢复单位 ms。原版模式也支持自动匹配。图中时间线仅示意系数响应，不是现场测量。"
            WidthChartFocus.GUARD -> "相关度阈值和恢复迟滞是无单位的相关系数，不是毫秒。高级保护启用后接管第一代触发检测；三段模式的分段独立联动才使用低／中／高三个阈值，其它情况使用全局阈值。窗口单位 ms，有效信号门限 dBFS，回收／恢复时间 ms。只回收超过 100% 的新增 Side，不继续收窄基准，也不修复原录音反相。"
            WidthChartFocus.DYNAMIC -> model.dashboard.compressionReferenceNote + "。横轴为候选 Side/Mid 比值 dB，不是输入 dBFS。曲线使用原生软拐点公式并受最大新增扩宽回收限制；宽度不超过 100% 时衰减为零。全频动态仅全频 M/S 生效，三段动态仅三段模式生效。时间包络单独示意启动和恢复。"
            WidthChartFocus.TRANSIENT -> "阈值是 0.5 ms 快包络相对 50 ms 慢包络的能量差 dB，不是输入 dBFS。只抑制新增扩宽。全频 M/S 作用于全频，三段 M/S 使用所选低／中／高频组合；没有勾选频段时三段回收不触发。启动／保持／恢复均为毫秒。"
            WidthChartFocus.EQ -> "两段峰值均衡仅串联处理超过 100% 的新增 Side，基准 Side 与 Mid 不经过这些 PEQ，不能当作全局音色均衡。曲线由现有模型计算，包含当前分频与干湿混合；虚线是相同配置关闭两段增量 EQ 的参考。原版模式不使用增量 EQ。"
            WidthChartFocus.MONITOR -> "平滑时间只改变 RMS／相关度读数显示；详细监测开关控制分段读数，关闭不会停止动态宽度、相关度保护或瞬态处理。历史长度单位秒。监测位于试听分量选择之前，采样率为 James 内部处理采样率，并非设备最终硬件输出。"
            WidthChartFocus.AUDITION -> "当前路径：" + WidthAdvancedRuntime.auditionLabel(model.audition) + "。A/B 快照仅由原生保存按钮写入，图纸只读。三段试听只用于三段 M/S；新增扩宽可单独试听。A/B 电平匹配在 ±3 dB 范围内校准，退出试听后恢复。测点位于试听之前，监测不会跟随仅 Mid／Side 或 A/B 路径假装变成分量测量。"
        }
        return lines.joinToString(System.lineSeparator() + System.lineSeparator())
    }
    override fun onDetachedFromWindow() {
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}
