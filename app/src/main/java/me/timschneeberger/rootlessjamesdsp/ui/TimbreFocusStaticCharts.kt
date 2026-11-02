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
import me.timschneeberger.rootlessjamesdsp.fusion.TimbreChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.TimbreController
import me.timschneeberger.rootlessjamesdsp.fusion.TimbreDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.TimbreParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** JamesDSP timbre observers; ViPER tube and AnalogX pages remain independent. */
internal class TimbreParameterChart(context: Context) : TimbreFocusStaticBase(context, false)
internal class TimbreIllustrationChart(context: Context) : TimbreFocusStaticBase(context, true)

private data class TimbreFocusSnapshot(
    val settings: P.Settings, val rate: Int, val actualRate: Boolean, val moduleOn: Boolean, val legacyDrive: Double,
    val supported: Boolean, val extended: Boolean, val audition: Int, val matching: Boolean,
    val matchingReady: Boolean, val savedA: Boolean, val savedB: Boolean, val soloGain: Int
) {
    fun baseFocus(focus: TimbreChartFocus, key: String): Boolean = focus == TimbreChartFocus.CORE &&
        key in setOf(P.LEGACY, "timbre_pro_baseline", P.ENABLED)
    fun active(focus: TimbreChartFocus, key: String): Boolean {
        if (focus == TimbreChartFocus.AUDITION && key in setOf("timbre_pro_save_a", "timbre_pro_save_b")) return supported
        if (!moduleOn) return false
        if (baseFocus(focus, key)) return true
        if (!supported || !settings.professional) return false
        if (focus in setOf(TimbreChartFocus.CORE, TimbreChartFocus.BANDS) && settings.amount > 2000 && !extended) return false
        return when (focus) {
            TimbreChartFocus.CORE, TimbreChartFocus.BANDS -> settings.coreOn
            TimbreChartFocus.CROSSOVER, TimbreChartFocus.FILTER -> settings.toneOn
            TimbreChartFocus.DC -> settings.toneOn && settings.dcOn
            TimbreChartFocus.MIX -> settings.mixOn
            TimbreChartFocus.LEVEL -> settings.outputOn
            TimbreChartFocus.COMPENSATION -> settings.compOn
            TimbreChartFocus.QUALITY -> settings.qualityOn
            TimbreChartFocus.MONITOR -> settings.meters
            TimbreChartFocus.AUDITION -> key != "timbre_pro_solo_gain" || extended || soloGain == 0
            else -> true
        }
    }
    fun reason(focus: TimbreChartFocus, key: String): String = when {
        !moduleOn -> "音色仿真关闭 · 保存目标预览"
        !supported -> "专业驱动未支持 · 保存目标预览"
        !settings.professional -> "当前原版兼容 · 专业保存预览"
        focus in setOf(TimbreChartFocus.CORE, TimbreChartFocus.BANDS) && settings.amount > 2000 && !extended -> "高量染色需增强驱动 · 保存预览"
        focus == TimbreChartFocus.CORE || focus == TimbreChartFocus.BANDS -> "核心染色关闭 · 保存目标预览"
        focus == TimbreChartFocus.DC && !settings.toneOn -> "分频音色关闭 · 直流消除目标预览"
        focus == TimbreChartFocus.DC -> "直流消除关闭 · 保存目标预览"
        focus == TimbreChartFocus.CROSSOVER || focus == TimbreChartFocus.FILTER -> "分频音色关闭 · 保存目标预览"
        focus == TimbreChartFocus.MIX -> "混合关闭 · 保存比例预览"
        focus == TimbreChartFocus.LEVEL -> "输出增益关闭 · 保存衰减预览"
        focus == TimbreChartFocus.COMPENSATION -> "沿用完整反向补偿 · 保存比例预览"
        focus == TimbreChartFocus.QUALITY -> "原版自动 IIR · 专业 FIR 目标预览"
        focus == TimbreChartFocus.MONITOR -> "电平显示关闭 · 测点示意"
        key == "timbre_pro_solo_gain" -> "临时独听增益需增强驱动"
        else -> "保存目标预览"
    }
    fun target(focus: TimbreChartFocus, key: String): P.Settings {
        if (baseFocus(focus, key)) return if (supported) settings.copy(meters = false) else settings.copy(professional = false, meters = false)
        val base: P.Settings = settings.copy(professional = true, meters = false)
        return when (focus) {
            // The displayed square-residual envelope is explicitly before dry/wet scaling.
            TimbreChartFocus.CORE, TimbreChartFocus.BANDS -> base.copy(coreOn = true, mixOn = false)
            TimbreChartFocus.CROSSOVER, TimbreChartFocus.FILTER -> base.copy(toneOn = true)
            TimbreChartFocus.DC -> base.copy(toneOn = true, dcOn = true)
            TimbreChartFocus.MIX -> base.copy(mixOn = true)
            TimbreChartFocus.LEVEL -> base.copy(outputOn = true)
            TimbreChartFocus.COMPENSATION -> base.copy(compOn = true)
            TimbreChartFocus.QUALITY -> base.copy(qualityOn = true)
            else -> if (supported) settings.copy(meters = false) else P.defaults()
        }
    }
}

private data class TimbreStaticRequest(val settings: P.Settings, val rate: Int, val legacyDrive: Float)
/** A bounded shared worker. Dragging replaces only the waiting target; completed curves stay visible. */
private object TimbreStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "timbre-focus-static").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    private data class Task(val request: TimbreStaticRequest, val callback: (TimbreDashboardModel) -> Unit)
    private class Owner {
        var running: TimbreStaticRequest? = null
        var waiting: Task? = null
        var future: Future<*>? = null
    }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<TimbreStaticRequest, TimbreDashboardModel> =
        object : LinkedHashMap<TimbreStaticRequest, TimbreDashboardModel>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TimbreStaticRequest, TimbreDashboardModel>?): Boolean = size > 16
        }
    @Synchronized fun release(owner: Any): Unit {
        owners.remove(owner)?.let { state -> state.waiting = null; state.future?.cancel(true); state.future = null }
    }
    @Synchronized fun request(owner: Any, request: TimbreStaticRequest, callback: (TimbreDashboardModel) -> Unit): Unit {
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
            val result: TimbreDashboardModel = synchronized(this) { cache[task.request] } ?: run {
                val values: Map<String, Any> = task.request.settings.values() + (P.ENABLED to true) + (P.LEGACY to task.request.legacyDrive)
                TimbreDashboardModel.from(values, task.request.rate, true)
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

internal open class TimbreFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
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
    private var configuration: TimbreDashboardModel = TimbreDashboardModel.from(emptyMap<String, Any>())
    private var shown: TimbreDashboardModel = configuration
    private var snapshot: TimbreFocusSnapshot = snapshot(configuration)
    private var focus: TimbreChartFocus = TimbreChartFocus.CORE
    private var key: String = P.PROFESSIONAL
    private var active: Boolean = true
    private var request: TimbreStaticRequest? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = snapshot.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x78)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x4c)
    private fun snapshot(model: TimbreDashboardModel): TimbreFocusSnapshot = TimbreFocusSnapshot(
        model.settings, model.rate, model.actualRate, model.enabled, model.legacyDriveDb,
        TimbreController.supported(context), TimbreController.supportedExtended(context), TimbreController.audition,
        TimbreController.match, TimbreController.matchingReady(), TimbreController.hasComparison(0),
        TimbreController.hasComparison(1), TimbreController.soloGain)
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
    fun setConfiguration(model: TimbreDashboardModel): Unit {
        configuration = model
        val next: TimbreFocusSnapshot = snapshot(model)
        if (next == snapshot) return
        snapshot = next; updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setFocus(focus: TimbreChartFocus, key: String): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; TimbreStaticWork.release(this); request = null }
        this.focus = focus; this.key = key
        updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) {
            generation++; TimbreStaticWork.release(this); request = null
            detailDialog?.dismiss(); detailDialog = null
        } else { updateTarget(); invalidate() }
    }
    private fun updateTarget(): Unit {
        if (!active) return
        val target: P.Settings = snapshot.target(focus, key)
        val next: TimbreStaticRequest = TimbreStaticRequest(target, configuration.rate, configuration.legacyDriveDb.toFloat())
        if (request == next) return
        request = next
        val token: Long = generation
        TimbreStaticWork.request(this, next) { value ->
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

    private fun autoFactor(rate: Int): Int = when { rate >= 65000 -> 1; rate >= 30000 -> 2; rate >= 20000 -> 3; rate >= 14000 -> 4; else -> 5 }
    private fun bandColor(index: Int): Int = when (index % 4) {
        0 -> color
        1 -> secondary
        2 -> third
        else -> if (enabled) Color.rgb(94, 149, 72) else ColorUtils.setAlphaComponent(foreground, 0x65)
    }
    private fun bandIndex(): Int = listOf(P.band1.key, P.band2.key, P.band3.key, P.band4.key).indexOf(key)
    private fun drawParameters(canvas: Canvas): Unit = when (focus) {
        TimbreChartFocus.CORE -> drawCore(canvas)
        TimbreChartFocus.BANDS -> drawBands(canvas)
        TimbreChartFocus.CROSSOVER -> drawCrossover(canvas)
        TimbreChartFocus.FILTER -> drawFilter(canvas)
        TimbreChartFocus.DC -> drawDc(canvas)
        TimbreChartFocus.MIX -> drawMix(canvas)
        TimbreChartFocus.LEVEL -> drawLevel(canvas)
        TimbreChartFocus.COMPENSATION -> drawCompensation(canvas)
        TimbreChartFocus.QUALITY -> drawQuality(canvas)
        TimbreChartFocus.MONITOR -> drawMonitor(canvas)
        TimbreChartFocus.MATCH -> drawMatch(canvas)
        TimbreChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        TimbreChartFocus.CORE -> drawSquareWave(canvas)
        TimbreChartFocus.BANDS -> drawBandAmounts(canvas)
        TimbreChartFocus.CROSSOVER -> drawSixBands(canvas)
        TimbreChartFocus.FILTER -> drawColorCoverage(canvas)
        TimbreChartFocus.DC -> drawBiasWave(canvas)
        TimbreChartFocus.MIX -> drawMixRoutes(canvas)
        TimbreChartFocus.LEVEL -> drawLevelWave(canvas)
        TimbreChartFocus.COMPENSATION -> drawCompensationRoutes(canvas)
        TimbreChartFocus.QUALITY -> drawQualityLattice(canvas)
        TimbreChartFocus.MONITOR -> drawMonitorRoutes(canvas)
        TimbreChartFocus.MATCH -> drawBalance(canvas)
        TimbreChartFocus.AUDITION -> drawListening(canvas)
    }
    private fun drawCore(canvas: Canvas): Unit {
        caption(canvas, signed(20 * log10(shown.drive)) + "dB · 总染色 " + fmt(shown.amount * 100, 0) + "%")
        grid(canvas, "+48 dBr", "−96", "20 Hz", "20 kHz")
        repeat(4) { band: Int -> frequencyTrace(canvas, shown.squareResidualDb[band], bandColor(band), floor = -96.0, ceiling = 48.0) }
        footer(canvas, "1FS 单音 → 输出 2f · 各路混合前目标")
    }
    private fun drawBands(canvas: Canvas): Unit {
        val selected: Int = bandIndex()
        caption(canvas, if (selected >= 0) "第" + (selected + 1) + "路 · " + fmt(shown.bandAmounts[selected] * 100) + "%" else "四路独立 · 总量 × 各路系数")
        grid(canvas, "+48 dBr", "−96", "20 Hz", "20 kHz")
        repeat(4) { band: Int ->
            val tint: Int = if (selected < 0 || selected == band) bandColor(band) else ColorUtils.setAlphaComponent(bandColor(band), 0x40)
            frequencyTrace(canvas, shown.squareResidualDb[band], tint, selected >= 0 && selected != band, -96.0, 48.0)
        }
        footer(canvas, "0% 只去除该路染色 · 线性基础声保留")
    }
    private fun drawCrossover(canvas: Canvas): Unit {
        val selected: Int = P.cuts.indexOfFirst { it.key == key }
        caption(canvas, if (selected >= 0) "分频点 " + (selected + 1) + " · " + fmt(shown.cuts[selected], 0) + " Hz" else "五点六段 · 中间四段参与染色")
        grid(canvas, "+12 dB", "−48", "20 Hz", "20 kHz")
        repeat(4) { band: Int -> frequencyTrace(canvas, shown.componentDb[band], bandColor(band)) }
        shown.cuts.forEachIndexed { index: Int, hz: Double ->
            line(canvas, fx(hz), plot.top, fx(hz), plot.bottom,
                if (index == selected) color else ColorUtils.setAlphaComponent(foreground, 0x35), true)
        }
        footer(canvas, "四路选频包络 · 两端基础声仍参与重组")
    }
    private fun drawFilter(canvas: Canvas): Unit {
        caption(canvas, "染色 HP " + fmt(shown.highpassHz, 0) + " / LP " + fmt(shown.lowpassHz, 0) + " Hz")
        grid(canvas, "+12 dB", "−48", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.globalFilterDb, color)
        footer(canvas, if (shown.dcHz > 0) "染色 HP / LP + DC 总滤波 · 基础声保留" else "只过滤平方染色 · 0 Hz 为旁路")
    }
    private fun firstOrderMagnitude(hz: Double, cutoff: Double, high: Boolean): Double {
        if (cutoff <= 0) return 1.0
        val fs: Double = shown.rate.toDouble() * shown.effectiveFactor
        val k: Double = tan(PI * cutoff.coerceIn(.1, fs * .45) / fs)
        val pole: Double = (1 - k) / (1 + k)
        val c: Double = cos(2 * PI * hz / fs)
        val denominator: Double = (1 + pole * pole - 2 * pole * c).coerceAtLeast(1e-24)
        val square: Double = if (high) (1 + pole).pow(2) * .5 * (1 - c) / denominator
            else (1 - pole).pow(2) * .5 * (1 + c) / denominator
        return sqrt(square.coerceAtLeast(0.0))
    }
    private fun drawDc(canvas: Canvas): Unit {
        caption(canvas, "染色 DC 高通 · " + fmt(shown.dcHz) + " Hz")
        grid(canvas, "0 dB", "−48", "0.1 Hz", "100 Hz")
        trace(canvas) { position: Double ->
            val hz: Double = .1 * 1000.0.pow(position)
            (20 * log10(firstOrderMagnitude(hz, shown.dcHz, true).coerceAtLeast(1e-12)) + 48) / 48
        }
        footer(canvas, "需分频音色 + DC 两开关 · 不切基础低音")
    }
    private fun bar(canvas: Canvas, index: Int, count: Int, amount: Double, label: String, number: String, tint: Int): Unit {
        val cell: Float = plot.width() / count; val x: Float = plot.left + index * cell + cell * .20f
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x45)
        canvas.drawRoundRect(RectF(x, py(amount), x + cell * .60f, plot.bottom), d(2f), d(2f), fillPaint)
        text(canvas, number, x + cell * .30f, max(plot.top + d(9f), py(amount) - d(3f)), cell, tint, Paint.Align.CENTER)
        text(canvas, label, x + cell * .30f, plot.bottom + d(11f), cell, foreground, Paint.Align.CENTER)
    }
    private fun drawMix(canvas: Canvas): Unit {
        caption(canvas, "基础声 100% · 染色 " + fmt(shown.mix * 100) + "%")
        grid(canvas, "1×", "0", "", "")
        bar(canvas, 0, 2, 1.0, "匹配基础", "1.000×", secondary)
        bar(canvas, 1, 2, shown.mix, "染色增量", fmt(shown.mix, 3) + "×", color)
        footer(canvas, "输出 = 基础 + 染色 × 混合 · 非输入直通")
    }
    private fun drawLevel(canvas: Canvas): Unit {
        caption(canvas, "输出 " + signed(20 * log10(shown.outputGain)) + " dB · " + fmt(shown.outputGain, 3) + "×")
        grid(canvas, "+1 FS", "−1", "−1 FS", "+1 FS")
        trace(canvas, secondary, true) { x: Double -> x }
        trace(canvas) { x: Double -> .5 + (2 * x - 1) * shown.outputGain * .5 }
        footer(canvas, "完整混合后衰减 · 基础与染色同步缩放")
    }
    private fun drawCompensation(canvas: Canvas): Unit {
        val percent: Double = shown.settings.comp / 1000.0
        val driveDb: Double = 20 * log10(shown.drive)
        caption(canvas, "驱动 " + signed(driveDb) + "dB · 补偿 " + fmt(percent * 100, 0) + "%")
        grid(canvas, "+24 dB", "−24", "0% 补偿", "100%")
        trace(canvas, secondary) { x: Double -> (.0 + driveDb * (1 - x) + 24) / 48 }
        trace(canvas, color) { x: Double -> (driveDb * (2 - x) + 24) / 48 }
        dot(canvas, px(percent), py((driveDb * (1 - percent) + 24) / 48), secondary)
        dot(canvas, px(percent), py((driveDb * (2 - percent) + 24) / 48))
        footer(canvas, "青：线性基础 · 紫：平方项驱动/补偿系数")
    }
    private fun drawQuality(canvas: Canvas): Unit {
        caption(canvas, "基础 " + shown.rate + " Hz · " + (if (shown.firOn) "FIR " else "自动 IIR ") + shown.effectiveFactor + "×")
        grid(canvas, "5×", "0", "", "")
        bar(canvas, 0, 2, autoFactor(shown.rate) / 5.0, "原版 IIR", autoFactor(shown.rate).toString() + "×", secondary)
        bar(canvas, 1, 2, shown.effectiveFactor / 5.0, "专业 FIR", shown.effectiveFactor.toString() + "×", color)
        footer(canvas, "FIR 对齐 " + shown.latencySamples + " 基础样本 / " + fmt(1000.0 * shown.latencySamples / shown.rate, 3) + " ms")
    }
    private fun drawMonitor(canvas: Canvas): Unit {
        caption(canvas, (if (configuration.actualRate) "实际基础 " else "参考基础 ") + configuration.rate + " Hz")
        val labels: List<String> = listOf("本模块输入", "四路混合前染色", "含混合的正常处理", "当前试听输出 / DC")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.86 - index * .25)
            line(canvas, px(.02), y, px(.97), y, if (index == 1) third else color, !snapshot.settings.meters)
            dot(canvas, px(.05), y, secondary)
            text(canvas, label, px(.12), y - d(4f), plot.width() * .85f)
        }
        footer(canvas, "真实读数见动态图 · 采样率不等同于宿主")
    }
    private fun drawMatch(canvas: Canvas): Unit {
        caption(canvas, if (snapshot.matchingReady) "已测量 · " + (if (snapshot.matching) "匹配开启" else "匹配关闭") else "同一输入 · A/B 并行 RMS 测量")
        grid(canvas, "预热", "测量", "0 s", "3 s")
        fillPaint.color = ColorUtils.setAlphaComponent(third, 0x55)
        canvas.drawRoundRect(RectF(px(0.0), py(.85), px(.5 / 3), py(.55)), d(2f), d(2f), fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x55)
        canvas.drawRoundRect(RectF(px(.5 / 3), py(.42), px(2.5 / 3), py(.12)), d(2f), d(2f), fillPaint)
        text(canvas, "≥500 ms", px(.05), py(.50), plot.width() * .75f, third)
        text(canvas, "2 秒 RMS", px(.30), py(.06), plot.width() * .65f, color)
        footer(canvas, "共同对齐后测量 · 只衰减到三路最低能量")
    }
    private val routeLabels: List<String> = listOf("正常处理", "输入原声", "处理后效果", "效果差分", "快照 A", "快照 B", "测量 A/B",
        "第一路独听", "第二路独听", "第三路独听", "第四路独听", "总染色独听")
    private fun drawAudition(canvas: Canvas): Unit {
        caption(canvas, "当前 " + routeLabels[snapshot.audition.coerceIn(0, 11)] + " · 独听 " + signed(snapshot.soloGain / 10.0, 0) + "dB")
        routeLabels.forEachIndexed { index: Int, label: String ->
            val column: Int = index / 6; val row: Int = index % 6
            val x: Float = px(if (column == 0) .02 else .52)
            val y: Float = plot.top + plot.height() * (.10f + .17f * row)
            dot(canvas, x, y - d(2f), if (snapshot.audition == index) color else ColorUtils.setAlphaComponent(foreground, 0x35), d(1.5f))
            text(canvas, label, x + d(5f), y + d(1f), plot.width() * .43f,
                if (snapshot.audition == index) color else foreground)
        }
        footer(canvas, "独听增益仅临时使用 · 不写入参数或快照")
    }
    private fun wave(canvas: Canvas, box: RectF, tint: Int, shape: (Double) -> Double): Unit {
        path.rewind()
        for (index in 0..120) {
            val position: Double = index / 120.0
            val x: Float = box.left + box.width() * position.toFloat()
            val y: Float = box.centerY() - box.height() * .38f * shape(position).coerceIn(-1.2, 1.2).toFloat()
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
    private fun drawSquareWave(canvas: Canvas): Unit {
        caption(canvas, "输入分段 → 平方增量 → 与基础声相加")
        val sourceBox: RectF = RectF(plot.left, plot.top + d(6f), plot.right, plot.centerY() - d(6f))
        val squareBox: RectF = RectF(plot.left, plot.centerY() + d(10f), plot.right, plot.bottom - d(5f))
        fillPaint.shader = LinearGradient(plot.left, plot.top, plot.right, plot.bottom,
            ColorUtils.setAlphaComponent(color, 0x25), ColorUtils.setAlphaComponent(third, 0x08), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(plot, d(8f), d(8f), fillPaint); fillPaint.shader = null
        wave(canvas, sourceBox, secondary) { t: Double -> sin(4 * PI * t) }
        val scaled: Double = shown.amount * shown.drive * shown.drive * shown.compensation
        val visualScale: Double = 1.7 * ln(1 + scaled) / ln(161.0)
        wave(canvas, squareBox, color) { t: Double ->
            val value: Double = sin(4 * PI * t)
            (value * value - .5) * visualScale
        }
        text(canvas, "单段正弦", plot.left + d(3f), sourceBox.top, plot.width() - d(6f), secondary)
        text(canvas, "平方交流增量 · 压缩显示", plot.left + d(3f), squareBox.top, plot.width() - d(6f), color)
        text(canvas, "总量×" + fmt(shown.amount, 2), plot.right - d(3f), plot.centerY() + d(2f), plot.width() * .75f, color, Paint.Align.RIGHT)
        footer(canvas, "平方示意 · 增量驱动/补偿缩放 ×" + fmt(scaled, 2))
    }
    private fun drawBandAmounts(canvas: Canvas): Unit {
        caption(canvas, "总量 " + fmt(shown.amount * 100, 0) + "% × 各路强度")
        grid(canvas, "200%", "0", "", "")
        repeat(4) { band: Int ->
            bar(canvas, band, 4, shown.bandAmounts[band] / 2, (band + 1).toString(),
                fmt(shown.bandAmounts[band] * 100, 0) + "%", bandColor(band))
        }
        footer(canvas, "染色倍率目标 · 不是音乐中的谐波占比")
    }
    private fun drawSixBands(canvas: Canvas): Unit {
        caption(canvas, "低端基础 + 四路染色 + 高端基础")
        val bounds: List<Double> = listOf(20.0) + shown.cuts.toList() + listOf(20000.0)
        repeat(6) { index: Int ->
            val left: Float = fx(bounds[index]); val right: Float = fx(bounds[index + 1])
            val tint: Int = if (index in 1..4) bandColor(index - 1) else ColorUtils.setAlphaComponent(foreground, 0x65)
            fillPaint.shader = LinearGradient(left, plot.top, left, plot.bottom,
                ColorUtils.setAlphaComponent(tint, 0x45), ColorUtils.setAlphaComponent(tint, 0x08), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(left + d(.7f), py(.83), max(left + d(.8f), right - d(.7f)), py(.20)), d(3f), d(3f), fillPaint)
            fillPaint.shader = null
            if (right - left > d(13f)) text(canvas, if (index in 1..4) index.toString() else "基", (left + right) / 2, py(.48),
                right - left, tint, Paint.Align.CENTER)
        }
        shown.cuts.forEachIndexed { index: Int, frequency: Double ->
            val x: Float = fx(frequency)
            line(canvas, x, py(.83), x, plot.bottom, ColorUtils.setAlphaComponent(foreground, 0x55), true)
            dot(canvas, x, py(.12), color, d(1.5f))
            if (index == P.cuts.indexOfFirst { it.key == key }) text(canvas, fmt(frequency, 0) + "Hz", x, plot.top + d(8f),
                plot.width(), color, Paint.Align.CENTER)
        }
        text(canvas, "20 Hz", plot.left, plot.bottom + d(11f), plot.width() * .5f)
        text(canvas, "20 kHz", plot.right, plot.bottom + d(11f), plot.width() * .5f, align = Paint.Align.RIGHT)
        footer(canvas, "六段相位匹配重组 · 仅中间四段加染色")
    }
    private fun drawColorCoverage(canvas: Canvas): Unit {
        caption(canvas, "染色支路滤波 · 基础声不被切除")
        line(canvas, plot.left, py(.84), plot.right, py(.84), secondary)
        text(canvas, "基础声保持", plot.left, py(.84) - d(5f), plot.width(), secondary)
        val heightRatio: Float = .48f
        path.rewind(); path.moveTo(plot.left, py(.12))
        shown.frequencies.forEachIndexed { index: Int, frequency: Double ->
            val amount: Double = 10.0.pow(shown.globalFilterDb[index] / 20).coerceIn(0.0, 1.0)
            path.lineTo(fx(frequency), py(.12 + heightRatio * amount))
        }
        path.lineTo(fx(shown.frequencies.last()), py(.12)); path.close()
        fillPaint.shader = LinearGradient(plot.left, plot.top, plot.right, plot.bottom,
            ColorUtils.setAlphaComponent(color, 0x65), ColorUtils.setAlphaComponent(third, 0x25), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fillPaint); fillPaint.shader = null
        text(canvas, "染色通过区域", plot.left, py(.12) + d(12f), plot.width(), color)
        footer(canvas, "HP " + fmt(shown.highpassHz, 0) + " · LP " + fmt(shown.lowpassHz, 0) + " Hz · 只改变附加量")
    }
    private fun drawBiasWave(canvas: Canvas): Unit {
        caption(canvas, "偏置阶跃恢复 · 截止 " + fmt(shown.dcHz) + " Hz")
        val box: RectF = RectF(plot.left, plot.top + d(7f), plot.right, plot.bottom - d(5f))
        line(canvas, plot.left, box.centerY(), plot.right, box.centerY(), ColorUtils.setAlphaComponent(foreground, 0x45), true)
        wave(canvas, box, secondary) { t: Double -> .60 + .16 * sin(12 * PI * t) }
        wave(canvas, box, color) { t: Double -> .60 * exp(-2 * PI * shown.dcHz * t * .5) + .16 * sin(12 * PI * t) }
        text(canvas, "原偏置", plot.left, plot.top + d(3f), plot.width() * .5f, secondary)
        text(canvas, "向零恢复", plot.right, plot.bottom + d(10f), plot.width() * .65f, color, Paint.Align.RIGHT)
        footer(canvas, "0–500 ms 包络参考 · 不表示实际输出 DC")
    }
    private fun drawMixRoutes(canvas: Canvas): Unit {
        caption(canvas, "相位匹配基础 1× + 染色 " + fmt(shown.mix * 100, 0) + "%")
        val baseY: Float = py(.77); val colorY: Float = py(.25)
        line(canvas, px(.05), baseY, px(.88), baseY, secondary)
        line(canvas, px(.05), colorY, px(.88), colorY, color, shown.mix <= 0)
        roundedNode(canvas, RectF(px(.14), baseY - d(10f), px(.72), baseY + d(10f)), "同分频/采样基础", secondary, true)
        roundedNode(canvas, RectF(px(.14), colorY - d(10f), px(.72), colorY + d(10f)), "四路染色增量", color, shown.mix > 0)
        line(canvas, px(.05), baseY, px(.05), colorY, foreground)
        line(canvas, px(.88), baseY, px(.88), colorY, third)
        dot(canvas, px(.88), py(.50), third, d(5f))
        text(canvas, "+", px(.88), py(.50) + d(3f), d(10f), Color.WHITE, Paint.Align.CENTER)
        footer(canvas, "0% 仍经相位匹配基础 · 不等于输入原声")
    }
    private fun drawLevelWave(canvas: Canvas): Unit {
        caption(canvas, "输出 " + signed(20 * log10(shown.outputGain)) + " dB · 固定衰减")
        val box: RectF = RectF(plot.left, plot.top + d(9f), plot.right, plot.bottom - d(8f))
        fillPaint.shader = LinearGradient(plot.left, plot.top, plot.left, plot.bottom,
            ColorUtils.setAlphaComponent(color, 0x25), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, d(8f), d(8f), fillPaint); fillPaint.shader = null
        wave(canvas, box, ColorUtils.setAlphaComponent(secondary, 0x85)) { t: Double -> sin(4 * PI * t) }
        wave(canvas, box, color) { t: Double -> shown.outputGain * sin(4 * PI * t) }
        text(canvas, "衰减前", plot.left, plot.top + d(4f), plot.width() * .5f, secondary)
        text(canvas, "衰减后", plot.right, plot.bottom + d(10f), plot.width() * .5f, color, Paint.Align.RIGHT)
        footer(canvas, "完整混合输出 · 幅度 ×" + fmt(shown.outputGain, 3))
    }
    private fun drawCompensationRoutes(canvas: Canvas): Unit {
        val base: Double = shown.drive * shown.compensation
        val square: Double = shown.drive * shown.drive * shown.compensation
        caption(canvas, "反向系数 " + fmt(shown.compensation, 3) + "× · 基础 " + fmt(base, 3) + "×")
        val rows: List<Pair<String, Double>> = listOf("驱动" to shown.drive, "基础" to base, "平方项" to square)
        rows.forEachIndexed { index: Int, row: Pair<String, Double> ->
            val y: Float = py(.82 - index * .32)
            val tint: Int = if (index == 1) secondary else color
            roundedNode(canvas, RectF(plot.left, y - d(8f), px(.40), y + d(8f)), row.first, tint, true)
            val amount: Double = (ln(1 + row.second) / ln(17.0)).coerceIn(0.0, 1.0)
            line(canvas, px(.45), y, px(.45 + .50 * amount), y, tint)
            text(canvas, "×" + fmt(row.second, 2), plot.right, y - d(5f), plot.width() * .55f, tint, Paint.Align.RIGHT)
        }
        footer(canvas, if (shown.drive == 1.0) "驱动 0 dB · 补偿不额外改变幅度" else "仅驱动与补偿缩放 · 不含总量/频带滤波")
    }
    private fun drawQualityLattice(canvas: Canvas): Unit {
        caption(canvas, (if (shown.firOn) "专业 FIR " else "自动 IIR ") + shown.effectiveFactor + "× · 采样时刻")
        val factor: Int = shown.effectiveFactor
        val box: RectF = RectF(plot.left, plot.top + d(8f), plot.right, py(.39))
        wave(canvas, box, color) { t: Double -> .70 * sin(4 * PI * t) }
        repeat(9) { index: Int ->
            val x: Float = plot.left + plot.width() * index / 8
            val y: Float = box.centerY() - box.height() * .38f * .70f * sin(index * PI / 2).toFloat()
            dot(canvas, x, y, secondary)
        }
        for (index in 0..8 * factor) {
            if (index % factor == 0) continue
            val t: Double = index / (8.0 * factor)
            dot(canvas, px(t), box.centerY() - box.height() * .38f * .70f * sin(4 * PI * t).toFloat(), third, d(1.2f))
        }
        val y: Float = py(.12)
        line(canvas, plot.left, y, plot.right, y, secondary)
        dot(canvas, plot.left, y, secondary)
        dot(canvas, px(shown.latencySamples / 40.0), y, color, d(3f))
        text(canvas, "0", plot.left, y + d(12f), d(20f))
        text(canvas, "FIR +" + shown.latencySamples + " 基础样本", plot.right, y + d(12f), plot.width() * .85f, color, Paint.Align.RIGHT)
        footer(canvas, "基础与四路同样重建 · 自动 IIR 延迟另有记忆")
    }
    private fun drawMonitorRoutes(canvas: Canvas): Unit {
        caption(canvas, "输入 / 染色增量 / 正常处理 / 试听输出")
        val labels: List<String> = listOf("输入原声", "四路染色 · 混合前", "正常处理 · 已含混合", "当前试听输出 + DC")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.86 - index * .25)
            roundedNode(canvas, RectF(plot.left, y - d(7f), plot.right, y + d(7f)), label,
                if (index == 1) third else color, true)
        }
        footer(canvas, "混合前染色 RMS ≠ 正常处理 PCM 能量")
    }
    private fun drawBalance(canvas: Canvas): Unit {
        caption(canvas, if (snapshot.matchingReady) "等电平结果就绪 · 只作固定衰减" else "保存 A + B → 预热 → RMS 测量")
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
        text(canvas, "输入原声 / A / B → 最低 RMS", cx, py(.92), plot.width(), color, Paint.Align.CENTER)
        footer(canvas, "等 RMS 电平示意 · 不等于心理声学等响度")
    }
    private fun drawListening(canvas: Canvas): Unit {
        val selected: Int = snapshot.audition.coerceIn(0, 11)
        caption(canvas, routeLabels[selected] + " · 独听临时 " + signed(snapshot.soloGain / 10.0, 0) + " dB")
        val labels: List<String> = listOf("输入原声", "正常混合", "快照 A/B", "四路/总染色")
        val choice: Int = when (selected) { 1 -> 0; 4, 5, 6 -> 2; in 7..11 -> 3; else -> 1 }
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.85 - index * .23)
            val tint: Int = if (index == choice) color else ColorUtils.setAlphaComponent(foreground, 0x55)
            roundedNode(canvas, RectF(plot.left, y - d(7f), px(.63), y + d(7f)), label, tint, index == choice)
            path.rewind(); path.moveTo(px(.63), y); path.cubicTo(px(.78), y, px(.70), py(.51), px(.88), py(.51))
            stroke(canvas, tint, index != choice)
        }
        val ear: RectF = RectF(px(.83), py(.69), plot.right, py(.32))
        linePaint.color = color; canvas.drawArc(ear, -85f, 280f, false, linePaint)
        footer(canvas, if (selected in 7..11) "独听取混合前增量 · 临时增益不改正常声音" else "原声试听是输入 · 与 0% 混合基础声不同")
    }

    private fun details(): String {
        val s: P.Settings = snapshot.settings
        val status: String = if (snapshot.baseFocus(focus, key)) {
            if (snapshot.moduleOn) "原版基础与当前配置的只读参考。" else "模块关闭，显示保存基础预览。"
        } else if (enabled) "本组已启用，图中显示保存参数目标。" else snapshot.reason(focus, key) + "；灰色图形随保存值变化，不会打开处理开关。"
        val explanation: String = when (focus) {
            TimbreChartFocus.CORE -> "保存染色驱动 " + signed(s.drive / 10.0) + " dB，总染色强度 " + fmt(s.amount / 10.0) + "% 。驱动先放大进入分频的信号，再按补偿比例作反向缩放；总量只控制附加平方染色，不是整体音量。\n五个分频点形成六段，交替反相后进行相位匹配重组。中间四段各产生 band²×系数×总量×各路倍率×补偿 的残差；倍率大于 1 时平方系数为 0.2，单倍率为 0.25。左右两端保留基础声。\n左图分别显示四路以 1FS 单音为输入、在输出 2f 位置的交流分量参考，显示在混合前、包含保存的补偿/输出与染色滤波；没有把四条 dB 曲线直接相加冒充整体音乐频谱。右图按对数压缩显示相对平方形变，不代表真实播放波形或 THD。\n旧驱动保存读值 " + fmt(snapshot.legacyDrive) + " dB 仅作原兼容状态参考，不等于新染色驱动；本图不会恢复旧滑杆或改动其保存值。"
            TimbreChartFocus.BANDS -> "保存四路强度依次为 " + fmt(s.band1 / 10.0) + "%、" + fmt(s.band2 / 10.0) + "%、" + fmt(s.band3 / 10.0) + "%、" + fmt(s.band4 / 10.0) + "%，分别与总量 " + fmt(s.amount / 10.0) + "% 相乘。某路 0% 只去除该路附加染色，不会删除该频段的线性基础声。100% 为原基础染色，不是音乐中 100% 都是谐波。\n左图是四路混合前的 1FS 正弦输入→2f 平方交流增量模型，颜色突出当前焦点。为说明混合前参数目标，图纸自身不再乘页面干湿比例；该显示副本不会写回参数。真正的混合前四路 RMS 必须由驱动读数显示；真实音乐含多个频率、相位与交调，不能用此单音示意替代。"
            TimbreChartFocus.CROSSOVER -> "保存五点依次为 " + listOf(s.cut1, s.cut2, s.cut3, s.cut4, s.cut5).joinToString(" / ") + " Hz。它们将输入分为六段，中间四段参与附加染色，低端与高端仍参与基础重组。四条曲线使用现有六段分频与相位匹配模型，不是四个互不相关的任意钟形。\n专业分频要求相邻至少 50 Hz；低采样率下按原驱动将整组频率同比缩放，保持顺序与比例。全中性配置走原版兼容路径时，实际原分频还随自动倍率而变；原生状态里的实际频率优先于保存值。改变分频会改变残差生成区，并不等同于给主干做整体 EQ。"
            TimbreChartFocus.FILTER -> "染色支路低切保存 " + s.colorHP + " Hz；高切保存 " + s.colorLP + " Hz。0 Hz 为该滤波旁路，非零频率受基础采样率安全上限限制。需开启专业分频与音色组才生效。\n这些一阶滤波在每路平方残差上执行；基础声绕过它们。左图显示当前保存目标的染色支路合成 HP / LP 滤波，若 DC 开关开启则也包含直流消除高通，不是整模块主干频响。右图只表示附加染色的通过频带；过滤某段增量不会把该段输入原声全部删除。"
            TimbreChartFocus.DC -> "保存截止 " + fmt(s.dcHz / 10.0) + " Hz。必须同时开启“分频与音色”及“染色支路直流消除”，否则该项虽保存但不参与处理。\n平方会产生常数偏置与倍频交流成分，DC 高通作用在每路平方残差上，基础声保留。左图为本高通在 0.1–100 Hz 的独立幅频参考；右图为 0–500 ms 偏置阶跃趋近零的包络示意，截止越高恢复越快。它不是实际输出直流均值，真实直流读数以下方动态图/原生状态为准。"
            TimbreChartFocus.MIX -> "保存混合 " + fmt(s.mix / 10.0) + "% 。本模块实际形式为 基础声 + 混合×总染色，再乘输出增益；它与“输入原声×(1−混合)+湿声×混合”的普通干湿插值在测点含义上不同。\n这里的 0% 仍是经过相同分频、相位重组与升降采样的线性基础声，不是未经处理的模块输入；真正输入原声须用原生“本模块输入原声”试听。100% 包含全部染色。正常处理 PCM 的 effect/wet 已经含当前混合，不能当作独立的混合前染色支路；只有四路或总染色独听与对应真实 RMS 才表示混合前增量。"
            TimbreChartFocus.LEVEL -> "保存模块输出增益 " + signed(s.output / 10.0) + " dB，额外线性系数 " + fmt(10.0.pow(s.output / 200.0), 4) + "。它对完整混合输出作固定衰减，基础声与染色增量一同缩放；0 dB 不作额外衰减。\n图中直线与波形仅说明固定幅度缩放，不代表真实音乐峰值、RMS 或耳内声压。染色驱动、反向补偿、频段选择和试听独听增益是不同的位置，不应混为同一个音量控制。"
            TimbreChartFocus.COMPENSATION -> "保存反向补偿比例 " + fmt(s.comp / 10.0) + "% 。关闭自定义补偿时实际沿用完整反向补偿（100%），不是零补偿。设驱动倍数为 D、补偿比例为 C，反向系数为 D^(−C)；线性基础缩放为 D×D^(−C)，平方项的驱动/补偿部分为 D²×D^(−C)。\n左图沿补偿 0–100% 展示这两种缩放，圆点标出保存目标；右图说明当前系数。这里只抽取驱动与补偿，不把总量、各路强度、分频/残差滤波混入曲线。驱动 0 dB 时 D=1，各补偿比例均为 1，不应伪造幅度变化。降低正驱动的补偿会提高基础和染色电平。"
            TimbreChartFocus.QUALITY -> "专业倍率保存 " + s.factor + "×。关闭专业抗混叠时沿用原版自动 IIR 倍率：基础采样率 ≥65000 为 1×，≥30000 为 2×，≥20000 为 3×，≥14000 为 4×，其余为 5×。这不是“关闭后必定 1×”。\n开启专业抗混叠使用 FIR 2× / 4×，基础线性路及四路平方残差采用同样的重建与对齐，新增固定 31 个基础采样延迟；当前参考 " + fmt(31000.0 / snapshot.rate, 4) + " ms。原版 IIR 仍有自身滤波记忆，0 个新增 FIR 样本不等于端到端零延迟。\n图中采样率是本模块内部基础采样率，可能与宿主不同，不能把 31 基础样本直接当成 31 宿主样本。A/B 与过渡的共同延迟见驱动实际状态。右图只显示采样时刻，不冒充真实音频或 FIR 插值响应。"
            TimbreChartFocus.MONITOR -> "原生实时电平与直流开关保存为" + (if (s.meters) "开启" else "关闭") + "。输入/输出峰值、RMS、超限帧数与输出 DC 来自本模块；四路染色 RMS 取自混合前增量。\n正常处理 PCM 已包含当前干湿混合，当前输出 PCM 还会受到原声、A/B、差分或独听选择影响；这几个测点必须分别说明。静图只展示位置，不生成模拟的实时数字。基础采样率与宿主采样率可能不同，不能用宿主率标注内部 FFT 频点。真实音频测量与采样电源仍由原监控逻辑负责。"
            TimbreChartFocus.MATCH -> "A " + (if (snapshot.savedA) "已保存" else "未保存") + "，B " + (if (snapshot.savedB) "已保存" else "未保存") + "；匹配" + (if (snapshot.matching) "开启" else "关闭") + "。\n同一输入原声与两快照并行处理，预热至少 500 ms 并含共同对齐，再测量 2 秒 RMS；以三路最低能量为目标，只作固定衰减，不持续追踪或提升音量。等 RMS 电平并不代表心理声学等响度。信号不足时不建立匹配。静图只显示流程与快照是否存在，不读取私有快照参数或生成实测衰减值。"
            TimbreChartFocus.AUDITION -> "当前：“" + routeLabels[snapshot.audition.coerceIn(0, 11)] + "”。完整保留原生 12 路选择：正常、输入原声、处理后效果、差分、A、B、测量，以及四路染色独听、混合前总染色独听。\n保存临时独听增益 " + signed(snapshot.soloGain / 10.0, 0) + " dB，仅在四路或总染色独听时作用于实际监听输出；0 / 6 / 12 / 18 dB 不改变正常声音、不写入预设或 A/B 快照。总染色独听与非零临时增益需配套增强能力；停止或离页恢复。\n独听输出只含混合前附加增量；正常处理 effect PCM 不是这个独立支路。原声试听是模块输入，与混合 0% 的相位匹配基础声不同；效果差分包含分频相位、采样和染色的共同差异，不等于纯谐波或 THD。静图仍显示页面保存参数，A/B 实际音频以下方实时读数为准。"
        }
        return (if (illustration) focus.rightTitle else focus.leftTitle) + "\n\n" + status + "\n\n" + explanation +
            "\n\n" + (if (snapshot.actualRate) "实际基础采样率 " else "参考基础采样率 ") + snapshot.rate + " Hz；图纸只读，不写参数、预设或 A/B。"
    }
    override fun onDetachedFromWindow(): Unit {
        generation++; TimbreStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}


