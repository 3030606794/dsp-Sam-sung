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
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderController
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** Compander observers: relative spectral coefficients and standard thresholds have separate units. */
internal class CompanderParameterChart(context: Context) : CompanderFocusStaticBase(context, false)
internal class CompanderIllustrationChart(context: Context) : CompanderFocusStaticBase(context, true)

private data class CompanderFocusSnapshot(
    val settings: P.Settings, val rate: Int, val actualRate: Boolean, val moduleOn: Boolean, val valid: Boolean,
    val supported: Boolean, val audition: Int, val matching: Boolean, val matchingReady: Boolean,
    val savedA: Boolean, val savedB: Boolean
) {
    fun applicable(focus: CompanderChartFocus, key: String): Boolean = when (focus) {
        CompanderChartFocus.CURVE, CompanderChartFocus.RELATIVE -> settings.engine == 0
        CompanderChartFocus.STANDARD -> settings.engine == 1 && (key != P.ratio.key || settings.type != 3)
        CompanderChartFocus.HYSTERESIS -> settings.engine == 1 && settings.type == 3
        CompanderChartFocus.SIDECHAIN, CompanderChartFocus.LOOKAHEAD -> settings.engine == 1
        CompanderChartFocus.LIMITS -> when (key) {
            P.maxBoost.key -> settings.engine == 0 || settings.type == 1
            P.maxCut.key -> settings.engine == 0 || settings.type != 1
            else -> true
        }
        CompanderChartFocus.TIMING -> key != P.backgroundDelta.key || settings.engine == 0
        CompanderChartFocus.DETECTOR -> when (key) {
            P.weakFloor.key -> settings.engine == 0 || settings.type == 1
            P.rmsWindow.key -> settings.engine == 1 && settings.detector == 1
            P.DETECTOR -> settings.engine == 1
            else -> true
        }
        else -> true
    }
    fun foundationTakenOver(key: String): Boolean = settings.professional && (settings.engine == 1 || key == P.BASE_TIME && settings.timeOn)
    fun active(focus: CompanderChartFocus, key: String): Boolean {
        if (!valid) return false
        if (focus == CompanderChartFocus.AUDITION && key in setOf("compander_pro_save_a", "compander_pro_save_b")) return supported
        if (!moduleOn) return false
        if (focus == CompanderChartFocus.FOUNDATION) return !foundationTakenOver(key)
        if (focus == CompanderChartFocus.CURVE && key == P.BASE_CURVE &&
            !(settings.professional && (settings.engine == 1 || settings.coreOn))) return true
        if (!supported || !settings.professional || !applicable(focus, key)) return false
        return when (focus) {
            CompanderChartFocus.CURVE, CompanderChartFocus.STANDARD, CompanderChartFocus.HYSTERESIS -> settings.coreOn
            CompanderChartFocus.RELATIVE, CompanderChartFocus.RANGE -> settings.detectOn
            CompanderChartFocus.LIMITS -> settings.limitsOn
            CompanderChartFocus.TIMING -> settings.timeOn
            CompanderChartFocus.DETECTOR -> if (key == P.DETECTOR || key == P.rmsWindow.key) settings.coreOn else settings.detectOn
            CompanderChartFocus.SIDECHAIN -> settings.sidechainOn
            CompanderChartFocus.LOOKAHEAD -> settings.lookaheadOn
            CompanderChartFocus.MIX -> settings.mixOn
            CompanderChartFocus.LEVEL -> settings.outputOn
            CompanderChartFocus.MONITOR -> settings.meters
            else -> true
        }
    }
    fun reason(focus: CompanderChartFocus, key: String): String = when {
        !valid -> "原基础格式异常 · 安全参考预览"
        !moduleOn -> "压扩器关闭 · 保存目标预览"
        focus == CompanderChartFocus.FOUNDATION && foundationTakenOver(key) -> "原基础已被接管 · 旧保存值预览"
        !applicable(focus, key) -> "当前引擎/类型不适用 · 保存目标预览"
        !supported -> "专业驱动未支持 · 保存目标预览"
        !settings.professional -> "当前原版处理 · 专业保存预览"
        focus == CompanderChartFocus.CURVE || focus == CompanderChartFocus.STANDARD || focus == CompanderChartFocus.HYSTERESIS -> "核心精调关闭 · 保存目标预览"
        focus == CompanderChartFocus.RELATIVE || focus == CompanderChartFocus.RANGE -> "检测与范围关闭 · 保存目标预览"
        focus == CompanderChartFocus.LIMITS -> "作用量限制关闭 · 保存边界预览"
        focus == CompanderChartFocus.TIMING -> "时间精调关闭 · 保存包络预览"
        focus == CompanderChartFocus.DETECTOR -> "对应检测组关闭 · 保存目标预览"
        focus == CompanderChartFocus.SIDECHAIN -> "侧链滤波关闭 · 保存选频预览"
        focus == CompanderChartFocus.LOOKAHEAD -> "前瞻关闭 · 保存延迟预览"
        focus == CompanderChartFocus.MIX -> "混合关闭 · 保存比例预览"
        focus == CompanderChartFocus.LEVEL -> "输出增益关闭 · 保存衰减预览"
        focus == CompanderChartFocus.MONITOR -> "电平显示关闭 · 测点示意"
        else -> "保存目标预览"
    }
    fun target(focus: CompanderChartFocus, key: String): P.Settings {
        val saved: P.Settings = settings.copy(meters = false, response = false)
        if (focus == CompanderChartFocus.FOUNDATION) return saved.copy(professional = false)
        if (focus == CompanderChartFocus.CURVE && key == P.BASE_CURVE &&
            !(settings.professional && (settings.engine == 1 || settings.coreOn))) return saved
        val base: P.Settings = saved.copy(professional = true)
        return when (focus) {
            CompanderChartFocus.CURVE -> base.copy(engine = 0, coreOn = true)
            CompanderChartFocus.STANDARD -> base.copy(engine = 1, coreOn = true)
            CompanderChartFocus.HYSTERESIS -> base.copy(engine = 1, type = 3, coreOn = true)
            CompanderChartFocus.RELATIVE -> base.copy(engine = 0, detectOn = true)
            CompanderChartFocus.LIMITS -> base.copy(limitsOn = true)
            CompanderChartFocus.TIMING -> base.copy(timeOn = true, engine = if (key == P.backgroundDelta.key) 0 else base.engine)
            CompanderChartFocus.DETECTOR -> when (key) {
                P.DETECTOR, P.rmsWindow.key -> base.copy(engine = 1, coreOn = true, detector = if (key == P.rmsWindow.key) 1 else base.detector)
                else -> base.copy(detectOn = true)
            }
            CompanderChartFocus.RANGE -> base.copy(detectOn = true)
            CompanderChartFocus.SIDECHAIN -> base.copy(engine = 1, sidechainOn = true)
            CompanderChartFocus.LOOKAHEAD -> base.copy(engine = 1, lookaheadOn = true)
            CompanderChartFocus.MIX -> base.copy(mixOn = true)
            CompanderChartFocus.LEVEL -> base.copy(outputOn = true)
            else -> if (supported) saved else saved.copy(professional = false)
        }
    }
}

private data class CompanderStaticRequest(val settings: P.Settings, val rate: Int)
/** A bounded shared worker. Dragging replaces only the waiting target; completed curves stay visible. */
private object CompanderStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "compander-focus-static").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    private data class Task(val request: CompanderStaticRequest, val callback: (CompanderDashboardModel) -> Unit)
    private class Owner {
        var running: CompanderStaticRequest? = null
        var waiting: Task? = null
        var future: Future<*>? = null
    }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<CompanderStaticRequest, CompanderDashboardModel> =
        object : LinkedHashMap<CompanderStaticRequest, CompanderDashboardModel>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CompanderStaticRequest, CompanderDashboardModel>?): Boolean = size > 16
        }
    @Synchronized fun release(owner: Any): Unit {
        owners.remove(owner)?.let { state -> state.waiting = null; state.future?.cancel(true); state.future = null }
    }
    @Synchronized fun request(owner: Any, request: CompanderStaticRequest, callback: (CompanderDashboardModel) -> Unit): Unit {
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
            val result: CompanderDashboardModel = synchronized(this) { cache[task.request] } ?: run {
                val values: Map<String, Any> = task.request.settings.values() + (P.ENABLED to true)
                CompanderDashboardModel.from(values, task.request.rate, true)
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

internal open class CompanderFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
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
    private var configuration: CompanderDashboardModel = CompanderDashboardModel.from(emptyMap<String, Any>())
    private var shown: CompanderDashboardModel = configuration
    private var snapshot: CompanderFocusSnapshot = snapshot(configuration)
    private var focus: CompanderChartFocus = CompanderChartFocus.FOUNDATION
    private var key: String = P.BASE_TRANSFORM
    private var active: Boolean = true
    private var request: CompanderStaticRequest? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = snapshot.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x78)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x4c)
    private fun snapshot(model: CompanderDashboardModel): CompanderFocusSnapshot = CompanderFocusSnapshot(
        model.settings, model.rate, model.actualRate, model.enabled, model.validFoundation, CompanderController.supported(context),
        CompanderController.audition, CompanderController.match, CompanderController.matchingReady(),
        CompanderController.hasComparison(0), CompanderController.hasComparison(1))
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
    fun setConfiguration(model: CompanderDashboardModel): Unit {
        configuration = model
        val next: CompanderFocusSnapshot = snapshot(model)
        if (next == snapshot) return
        snapshot = next; updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setFocus(focus: CompanderChartFocus, key: String = ""): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; CompanderStaticWork.release(this); request = null }
        this.focus = focus; this.key = key
        updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) {
            generation++; CompanderStaticWork.release(this); request = null
            detailDialog?.dismiss(); detailDialog = null
        } else { updateTarget(); invalidate() }
    }
    private fun updateTarget(): Unit {
        if (!active) return
        val target: P.Settings = snapshot.target(focus, key)
        val next: CompanderStaticRequest = CompanderStaticRequest(target, configuration.rate)
        if (request == next) return
        request = next
        val token: Long = generation
        CompanderStaticWork.request(this, next) { value ->
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
            val x: Float = fx(frequency)
            val y: Float = plot.bottom - plot.height() * ((values[index] - floor) / (ceiling - floor)).toFloat()
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
    private fun bar(canvas: Canvas, index: Int, count: Int, ratio: Double, label: String, value: String, tint: Int): Unit {
        if (count <= 0 || index !in 0 until count) return
        val column: Float = plot.width() / count
        val center: Float = plot.left + column * (index + .5f)
        val fraction: Double = if (ratio.isFinite()) ratio.coerceIn(0.0, 1.0) else 0.0
        val box: RectF = RectF(center - column * .27f, py(fraction), center + column * .27f, plot.bottom)
        fillPaint.color = ColorUtils.setAlphaComponent(tint, if (enabled) 0x85 else 0x45)
        canvas.drawRoundRect(box, d(3f), d(3f), fillPaint)
        text(canvas, value, center, plot.top + d(11f), column - d(4f), tint, Paint.Align.CENTER)
        text(canvas, label, center, plot.bottom + d(11f), column - d(4f), tint, Paint.Align.CENTER)
    }
    override fun onDraw(canvas: Canvas): Unit {
        super.onDraw(canvas)
        if (!active || width < d(55f) || height < d(55f)) return
        plot.set(d(28f), d(21f), width - d(7f), height - d(26f))
        if (illustration) drawIllustration(canvas) else drawParameters(canvas)
    }
    private fun outputGain(): Double = 10.0.pow(shown.outputDb / 20)
    private fun nodeIndex(): Int {
        val frequency: Int = P.frequencies.indexOfFirst { it.key == key }
        return if (frequency >= 0) frequency else P.coefficients.indexOfFirst { it.key == key }
    }
    private fun palette(index: Int): Int = when (index % 3) { 0 -> color; 1 -> secondary; else -> third }
    private fun traceUnclamped(canvas: Canvas, tint: Int, dashed: Boolean = false, value: (Double) -> Double): Unit {
        path.rewind(); var started: Boolean = false
        for (index in 0..160) {
            val x: Double = index / 160.0; val y: Double = value(x)
            if (!y.isFinite()) { started = false; continue }
            val yy: Float = plot.bottom - plot.height() * y.toFloat()
            if (!started) { path.moveTo(px(x), yy); started = true } else path.lineTo(px(x), yy)
        }
        stroke(canvas, tint, dashed)
    }
    private fun drawParameters(canvas: Canvas): Unit = when (focus) {
        CompanderChartFocus.FOUNDATION -> drawFoundation(canvas)
        CompanderChartFocus.CURVE -> drawCurve(canvas)
        CompanderChartFocus.ENGINE -> if (shown.standard) drawStandard(canvas) else drawRelative(canvas)
        CompanderChartFocus.STANDARD -> drawStandard(canvas)
        CompanderChartFocus.RELATIVE -> drawRelative(canvas)
        CompanderChartFocus.LIMITS -> drawLimits(canvas)
        CompanderChartFocus.TIMING -> drawTiming(canvas)
        CompanderChartFocus.DETECTOR -> drawDetector(canvas)
        CompanderChartFocus.RANGE -> drawRange(canvas)
        CompanderChartFocus.SIDECHAIN -> drawSidechain(canvas)
        CompanderChartFocus.LOOKAHEAD -> drawLookahead(canvas)
        CompanderChartFocus.HYSTERESIS -> drawHysteresis(canvas)
        CompanderChartFocus.MIX -> drawMix(canvas)
        CompanderChartFocus.LEVEL -> drawLevel(canvas)
        CompanderChartFocus.RESPONSE -> drawResponse(canvas)
        CompanderChartFocus.MONITOR -> drawMonitor(canvas)
        CompanderChartFocus.MATCH -> drawMatch(canvas)
        CompanderChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        CompanderChartFocus.FOUNDATION -> drawFoundationFlow(canvas)
        CompanderChartFocus.CURVE -> drawPointActions(canvas)
        CompanderChartFocus.ENGINE -> drawEngineRoutes(canvas)
        CompanderChartFocus.STANDARD -> drawStandardExamples(canvas)
        CompanderChartFocus.RELATIVE -> drawRelativeTrigger(canvas)
        CompanderChartFocus.LIMITS -> drawLimitBox(canvas)
        CompanderChartFocus.TIMING -> drawTimeFlow(canvas)
        CompanderChartFocus.DETECTOR -> drawDetectorRoutes(canvas)
        CompanderChartFocus.RANGE -> drawRangeRoutes(canvas)
        CompanderChartFocus.SIDECHAIN -> drawSidechainRoutes(canvas)
        CompanderChartFocus.LOOKAHEAD -> drawAheadArrival(canvas)
        CompanderChartFocus.HYSTERESIS -> drawGateThresholds(canvas)
        CompanderChartFocus.MIX -> drawMixRoutes(canvas)
        CompanderChartFocus.LEVEL -> drawLevelWave(canvas)
        CompanderChartFocus.RESPONSE -> drawActionLayers(canvas)
        CompanderChartFocus.MONITOR -> drawMonitorRoutes(canvas)
        CompanderChartFocus.MATCH -> drawBalance(canvas)
        CompanderChartFocus.AUDITION -> drawListening(canvas)
    }
    private fun drawFoundation(canvas: Canvas): Unit {
        caption(canvas, shown.transformName + " · 粒度 " + (shown.settings.legacyGranularity.toInt() + 1) + "/5")
        grid(canvas, "500 ms", "0", "", "")
        bar(canvas, 0, 2, shown.backgroundMs / 500, "背景", fmt(shown.backgroundMs, 0) + "ms", color)
        bar(canvas, 1, 2, 1000.0 * shown.nominalDelaySamples / shown.rate / 500, "标称延迟",
            fmt(1000.0 * shown.nominalDelaySamples / shown.rate, 1) + "ms", secondary)
        footer(canvas, "基础更新 " + fmt(shown.updateMs, 3) + " ms · 实际延迟见驱动")
    }
    private fun drawCurve(canvas: Canvas): Unit {
        val selected: Int = nodeIndex()
        caption(canvas, if (selected >= 0) "点" + (selected + 1) + " " + fmt(shown.nodes[selected], 3) + "Hz / C " + signed(shown.coefficients[selected], 3)
            else "七点 C · 实际频率映射 ×" + fmt(shown.frequencyScale, 0))
        grid(canvas, "+1.2 C", "−1.2", "1 定义Hz", "24 kHz")
        trace(canvas) { x: Double -> (shown.coefficientAt(24000.0.pow(x) * shown.frequencyScale) + 1.2) / 2.4 }
        for (index in 0..6) {
            val x: Float = px(ln(shown.nodes[index].coerceIn(1.0, 24000.0)) / ln(24000.0))
            dot(canvas, x, py((shown.coefficients[index] + 1.2) / 2.4), if (selected < 0 || selected == index) color else secondary,
                if (selected == index) d(3f) else d(1.8f))
        }
        footer(canvas, "C 无量纲非增益 · PCHIP 按线性 Hz 插值")
    }
    private fun drawStandard(canvas: Canvas): Unit {
        caption(canvas, shown.typeName + " · " + fmt(shown.thresholdDb) + "dBFS · " + if (shown.settings.type == 3) "门" else fmt(shown.ratio, 2) + ":1")
        grid(canvas, "+12 输出", "−120", "−80 输入", "0 dBFS")
        traceUnclamped(canvas, secondary, true) { x: Double -> (-80 + x * 80 + 120) / 132 }
        traceUnclamped(canvas, color) { x: Double ->
            val input: Double = -80 + 80 * x
            (shown.outputForGain(input, shown.standardTarget(input)) + 120) / 132
        }
        line(canvas, px((shown.thresholdDb + 80) / 80), plot.top, px((shown.thresholdDb + 80) / 80), plot.bottom, third, true)
        footer(canvas, "稳态检测器 I/O · 不含音乐包络/频段相位")
    }
    private fun drawRelative(canvas: Canvas): Unit {
        caption(canvas, "相对差 " + fmt(shown.relativeDb) + "dB · 软拐 " + fmt(shown.kneeDb) + "dB")
        grid(canvas, "+24 增益", "−48", "背景 −24", "+36 dB")
        val selected: Int = nodeIndex()
        for (band in 0..6) traceUnclamped(canvas, if (selected < 0 || selected == band) palette(band) else ColorUtils.setAlphaComponent(foreground, 0x35)) { x: Double ->
            val level: Double = shown.backgroundReferenceDb - 24 + 60 * x
            (shown.targetGain(level, shown.nodeFrequencies[band], shown.coefficients[band]) + 48) / 72
        }
        footer(canvas, "示例背景 −36 dBFS · 非固定阈值压缩")
    }
    private fun drawLimits(canvas: Canvas): Unit {
        caption(canvas, "提升 ≤" + fmt(shown.maxBoostDb) + " / 衰减 ≤" + fmt(shown.maxCutDb) + " dB")
        grid(canvas, "+24 dB", "−48", "提升", "衰减")
        val top: Float = py((shown.maxBoostDb + 48) / 72)
        val bottom: Float = py((-shown.maxCutDb + 48) / 72)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x20)
        canvas.drawRoundRect(RectF(plot.left, top, plot.right, bottom), d(3f), d(3f), fillPaint)
        line(canvas, plot.left, top, plot.right, top, color)
        line(canvas, plot.left, bottom, plot.right, bottom, third)
        line(canvas, plot.left, py(48.0 / 72), plot.right, py(48.0 / 72), secondary, true)
        footer(canvas, "0 dB 禁止该方向增减 · 不改静态输出增益")
    }
    private fun timeAt(position: Double): Double = 10 * (2001.0.pow(position) - 1)
    private fun drawTiming(canvas: Canvas): Unit {
        caption(canvas, "A " + fmt(shown.attackMs) + " / H " + fmt(shown.holdMs, 0) + " / R " + fmt(shown.releaseMs, 0) + " ms")
        grid(canvas, "100%", "0", "0 ms", "20 s · 对数")
        trace(canvas, color) { x: Double -> 1 - exp(-timeAt(x) / shown.attackMs) }
        trace(canvas, third) { x: Double -> if (timeAt(x) < shown.holdMs) 1.0 else exp(-(timeAt(x) - shown.holdMs) / shown.releaseMs) }
        if (!shown.standard) trace(canvas, secondary, true) { x: Double -> 1 - exp(-timeAt(x) / shown.backgroundMs) }
        footer(canvas, if (shown.standard && shown.settings.type == 3) "门开启用攻击 · 保持后用释放关闭" else "目标变化归一化 · 频域仍受帧更新约束")
    }
    private fun drawDetector(canvas: Canvas): Unit {
        if (key == P.weakFloor.key) {
            caption(canvas, "弱信号保护 " + fmt(shown.weakFloorDb) + " dBFS")
            grid(canvas, "允许作用", "停止作用", "−100 dBFS", "0")
            trace(canvas) { x: Double -> if (-100 + 100 * x < shown.weakFloorDb) 0.0 else 1.0 }
            footer(canvas, "保护只停止动态作用 · 不静音原声")
        } else {
            caption(canvas, shown.detectorName + " · " + if (shown.detectOn && shown.settings.linked == 1) "较强声道" else "左右平均")
            grid(canvas, "取样窗", "", "0 ms", "100 ms")
            if (shown.standard && shown.settings.detector == 1) {
                fillPaint.color = ColorUtils.setAlphaComponent(color, 0x40)
                canvas.drawRect(RectF(plot.left, py(.80), px(shown.settings.rmsWindow / 100.0), py(.15)), fillPaint)
                text(canvas, shown.settings.rmsWindow.toString() + " ms 矩形窗", plot.left + d(2f), plot.top + d(12f), plot.width() - d(4f), color)
            } else {
                line(canvas, plot.left, py(.12), plot.left, py(.85), color)
                text(canvas, if (shown.standard) "峰值 · 当前样本" else "每频带 · 当前频谱", plot.left + d(4f), py(.52), plot.width() - d(8f), color)
            }
            footer(canvas, "检测方式/时间窗示意 · 无实际电平代填")
        }
    }
    private fun drawRange(canvas: Canvas): Unit {
        caption(canvas, "作用范围 " + fmt(shown.rangeLowHz, 0) + "–" + (if (shown.rangeHighHz == 0.0) "不限" else fmt(shown.rangeHighHz, 0)) + " Hz")
        grid(canvas, "作用 1", "0", "20 Hz", "20 kHz")
        path.rewind()
        shown.frequencies.forEachIndexed { index: Int, frequency: Double ->
            val x: Float = fx(frequency); val y: Float = py(shown.rangeMask[index])
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        stroke(canvas, color)
        footer(canvas, if (shown.standard) "标准：滤波处理支路 · 带外原声保留" else "原版：各频谱格点作用范围 · 不是声音低切")
    }
    private fun drawSidechain(canvas: Canvas): Unit {
        caption(canvas, "检测 HP " + fmt(shown.sideHighpassHz, 0) + " / LP " + fmt(shown.sideLowpassHz, 0) + " Hz")
        grid(canvas, "+12 dB", "−48", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.sidechainDb, color)
        footer(canvas, "处理带 + 侧链合成检测响应 · 不切听音主干")
    }
    private fun drawLookahead(canvas: Canvas): Unit {
        caption(canvas, "前瞻目标 " + fmt(shown.lookaheadMs) + " ms · " + shown.nominalDelaySamples + " 样本")
        grid(canvas, "检测", "音频", "0 ms", "20 ms")
        dot(canvas, plot.left, py(.80), secondary, d(3f))
        dot(canvas, px(shown.lookaheadMs / 20), py(.20), color, d(3f))
        line(canvas, plot.left, py(.50), px(shown.lookaheadMs / 20), py(.50), third)
        footer(canvas, "先检测、音频延后 · 不是砖墙限幅器")
    }
    private fun drawHysteresis(canvas: Canvas): Unit {
        caption(canvas, "开 " + fmt(shown.thresholdDb) + " / 关 " + fmt(shown.thresholdDb - shown.hysteresisDb) + " dBFS")
        grid(canvas, "+12 输出", "−120", "−100 输入", "0 dBFS")
        for (state in 0..1) traceUnclamped(canvas, if (state == 0) color else third, state == 1) { x: Double ->
            val input: Double = -100 + 100 * x
            (shown.outputForGain(input, shown.standardTarget(input, state == 1)) + 120) / 132
        }
        footer(canvas, "两条门状态稳态参考 · 回差单位 dB")
    }
    private fun drawMix(canvas: Canvas): Unit {
        caption(canvas, "对齐原声 " + fmt((1 - shown.mix) * 100) + "% · 效果 " + fmt(shown.mix * 100) + "%")
        grid(canvas, "1×", "0", "", "")
        bar(canvas, 0, 2, 1 - shown.mix, "对齐原声", fmt(1 - shown.mix, 3) + "×", secondary)
        bar(canvas, 1, 2, shown.mix, "压扩结果", fmt(shown.mix, 3) + "×", color)
        footer(canvas, "按算法实际延迟对齐 · 仍含频段滤波相位")
    }
    private fun drawLevel(canvas: Canvas): Unit {
        caption(canvas, "输出 " + signed(shown.outputDb) + " dB · " + fmt(outputGain(), 3) + "×")
        grid(canvas, "+1 FS", "−1", "−1 FS", "+1 FS")
        trace(canvas, secondary, true) { x: Double -> x }
        trace(canvas) { x: Double -> .5 + (2 * x - 1) * outputGain() * .5 }
        footer(canvas, "完整混合后固定衰减 · 与动态作用量限制不同")
    }
    private fun drawResponse(canvas: Canvas): Unit {
        caption(canvas, "距触发判据 6 / 12 / 24 dB · 参考")
        grid(canvas, "+36 dB", "−60", "20 Hz", "20 kHz")
        shown.actionCurves.forEachIndexed { index: Int, values: DoubleArray ->
            frequencyTrace(canvas, values, palette(index), index == 2, -60.0, 36.0)
        }
        footer(canvas, if (shown.standard) "固定检测电平下单频响应 · 非音乐实测" else "背景假设 −36 dBFS · 与真实动态增益分开")
    }
    private fun drawMonitor(canvas: Canvas): Unit {
        caption(canvas, (if (configuration.actualRate) "实际基础 " else "参考基础 ") + configuration.rate + " Hz")
        val labels: List<String> = listOf("输入 / 延迟对齐原声", "检测器 / 历史背景", "频率增益控制量", "正常处理 / 试听输出")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.86 - index * .25)
            line(canvas, px(.02), y, px(.97), y, if (index == 1) third else color, !snapshot.settings.meters)
            dot(canvas, px(.05), y, secondary)
            text(canvas, label, px(.12), y - d(4f), plot.width() * .85f)
        }
        footer(canvas, "动态系数 C 与实际增益 dB 是不同数据")
    }
    private fun drawMatch(canvas: Canvas): Unit {
        caption(canvas, if (snapshot.matchingReady) "已测量 · " + (if (snapshot.matching) "匹配开启" else "匹配关闭") else "同一输入 · A/B 并行 RMS 测量")
        grid(canvas, "预热", "测量", "开始", "完成")
        fillPaint.color = ColorUtils.setAlphaComponent(third, 0x55)
        canvas.drawRoundRect(RectF(px(0.0), py(.85), px(.30), py(.55)), d(2f), d(2f), fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x55)
        canvas.drawRoundRect(RectF(px(.35), py(.42), px(.85), py(.12)), d(2f), d(2f), fillPaint)
        text(canvas, "各快照状态预热", px(.02), py(.49), plot.width() * .98f, third)
        text(canvas, "2 秒 RMS", px(.40), py(.06), plot.width() * .55f, color)
        footer(canvas, "流程示意 · 实际时间/衰减见原生状态")
    }
    private val routeLabels: List<String> = listOf("正常处理", "对齐输入原声", "处理后效果", "效果差分", "快照 A", "快照 B", "测量 A/B")
    private fun drawAudition(canvas: Canvas): Unit {
        caption(canvas, "当前：" + routeLabels[snapshot.audition.coerceIn(0, 6)])
        routeLabels.forEachIndexed { index: Int, label: String ->
            val column: Int = index / 4; val row: Int = index % 4
            val x: Float = px(if (column == 0) .02 else .52)
            val y: Float = plot.top + plot.height() * (.14f + .23f * row)
            dot(canvas, x, y - d(2f), if (snapshot.audition == index) color else ColorUtils.setAlphaComponent(foreground, 0x35), d(1.7f))
            text(canvas, label, x + d(6f), y + d(1f), plot.width() * .43f, if (snapshot.audition == index) color else foreground)
        }
        footer(canvas, "A " + (if (snapshot.savedA) "已保存" else "未保存") + " · B " + (if (snapshot.savedB) "已保存" else "未保存"))
    }
    private fun roundedNode(canvas: Canvas, box: RectF, label: String, tint: Int, selected: Boolean = false): Unit {
        fillPaint.color = ColorUtils.setAlphaComponent(tint, if (selected) 0x30 else 0x10)
        canvas.drawRoundRect(box, d(4f), d(4f), fillPaint)
        linePaint.color = ColorUtils.setAlphaComponent(tint, if (selected) 0xd0 else 0x65)
        canvas.drawRoundRect(box, d(4f), d(4f), linePaint)
        text(canvas, label, box.centerX(), box.centerY() + d(3f), box.width() - d(4f), tint, Paint.Align.CENTER)
    }
    private fun wave(canvas: Canvas, box: RectF, tint: Int, value: (Double) -> Double): Unit {
        path.rewind()
        for (index in 0..120) {
            val t: Double = index / 120.0
            val x: Float = box.left + box.width() * t.toFloat()
            val y: Float = box.centerY() - box.height() * .38f * value(t).coerceIn(-1.2, 1.2).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        stroke(canvas, tint)
    }
    private fun drawFoundationFlow(canvas: Canvas): Unit {
        caption(canvas, "原保存变换 · " + shown.transformName + " · 网格 " + (shown.settings.legacyGranularity.toInt() + 1) + "/5")
        val names: List<String> = listOf("STFT", "CWT", "CWT/STFT", "Gammatone")
        names.forEachIndexed { index: Int, label: String ->
            val x: Float = plot.left + plot.width() * (index % 2) / 2
            val y: Float = plot.top + plot.height() * (index / 2) / 2
            roundedNode(canvas, RectF(x + d(1f), y + d(7f), x + plot.width() / 2 - d(1f), y + plot.height() / 2 - d(2f)), label,
                if (index == shown.transform) color else ColorUtils.setAlphaComponent(foreground, 0x65), index == shown.transform)
        }
        footer(canvas, "帧更新 " + fmt(shown.updateMs, 3) + " ms · 背景 " + fmt(shown.backgroundMs, 0) + " ms")
    }
    private fun drawPointActions(canvas: Canvas): Unit {
        val selected: Int = nodeIndex()
        caption(canvas, if (selected >= 0) "点" + (selected + 1) + " C=" + signed(shown.coefficients[selected], 3) + " · 映射 " + fmt(shown.nodeFrequencies[selected], 0) + "Hz"
            else "正系数提升 / 负系数衰减 · 需触发")
        val zero: Float = py(.50)
        line(canvas, plot.left, zero, plot.right, zero, ColorUtils.setAlphaComponent(foreground, 0x45))
        for (index in 0..6) {
            val x: Float = px((index + .5) / 7)
            val amount: Double = shown.coefficients[index] / 1.2
            val y: Float = py(.5 + amount * .40)
            val tint: Int = if (amount < 0) third else color
            val thickness: Float = linePaint.strokeWidth
            linePaint.strokeWidth = d(if (index == selected) 4f else 2.5f)
            line(canvas, x, zero, x, y, if (selected < 0 || selected == index) tint else ColorUtils.setAlphaComponent(tint, 0x55))
            linePaint.strokeWidth = thickness
            dot(canvas, x, y, tint, d(2f))
            text(canvas, (index + 1).toString(), x, plot.bottom + d(11f), plot.width() / 7, tint, Paint.Align.CENTER)
        }
        footer(canvas, "系数不是 dB / 比率 · 实际增减还看历史背景")
    }
    private fun drawEngineRoutes(canvas: Canvas): Unit {
        caption(canvas, if (shown.standard) "当前：标准阈值压扩" else "当前：原版七点动态曲线")
        roundedNode(canvas, RectF(plot.left, py(.91), plot.right, py(.65)), "历史背景差 → 七点 C", secondary, !shown.standard)
        roundedNode(canvas, RectF(plot.left, py(.40), plot.right, py(.14)), "固定 dBFS → 类型 / 比率", color, shown.standard)
        line(canvas, px(.50), py(.65), px(.50), py(.40), ColorUtils.setAlphaComponent(foreground, 0x35), true)
        footer(canvas, "引擎互斥 · 旧基础值保留，可切回原版")
    }
    private fun drawStandardExamples(canvas: Canvas): Unit {
        caption(canvas, shown.typeName + " · 固定检测电平示例")
        val levels: List<Double> = listOf(-48.0, -6.0)
        for (index in 0..1) {
            val input: Double = levels[index]; val gain: Double = shown.standardTarget(input)
            val y: Float = py(if (index == 0) .74 else .28)
            roundedNode(canvas, RectF(plot.left, y - d(10f), px(.50), y + d(10f)), if (index == 0) "弱声 −48" else "强声 −6", secondary, true)
            line(canvas, px(.53), y, px(.89), y, if (gain < 0) third else color)
            text(canvas, signed(gain) + " dB", plot.right, y - d(5f), plot.width() * .50f,
                if (gain < 0) third else color, Paint.Align.RIGHT)
        }
        footer(canvas, if (shown.settings.type == 3) "门按开闭状态工作 · 不使用压扩比率" else "参数稳态作用量 · 不代表真实音乐电平")
    }
    private fun drawRelativeTrigger(canvas: Canvas): Unit {
        caption(canvas, "示例背景 −36 dBFS · 相对触发差")
        val y: Float = py(.58)
        line(canvas, plot.left, y, plot.right, y, ColorUtils.setAlphaComponent(foreground, 0x45))
        val background: Float = px(.15)
        val trigger: Float = px(.15 + shown.relativeDb / 36)
        line(canvas, background, py(.87), background, py(.24), secondary)
        line(canvas, trigger, py(.87), trigger, py(.24), color)
        text(canvas, "历史背景", plot.left, py(.98), plot.width() * .7f, secondary)
        text(canvas, "+" + fmt(shown.relativeDb) + " dB 才触发", plot.right, py(.08), plot.width(), color, Paint.Align.RIGHT)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x25)
        canvas.drawRect(RectF(trigger, py(.83), plot.right, py(.31)), fillPaint)
        footer(canvas, "背景随输入变化 · 不是固定 −36 dBFS 阈值")
    }
    private fun drawLimitBox(canvas: Canvas): Unit {
        caption(canvas, "增减有边界 · 固定输出增益另设")
        val top: Float = py((shown.maxBoostDb + 48) / 72)
        val bottom: Float = py((-shown.maxCutDb + 48) / 72)
        fillPaint.shader = LinearGradient(plot.left, plot.top, plot.left, plot.bottom,
            ColorUtils.setAlphaComponent(color, 0x25), ColorUtils.setAlphaComponent(third, 0x25), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(plot.left, top, plot.right, bottom), d(6f), d(6f), fillPaint); fillPaint.shader = null
        line(canvas, plot.left, top, plot.right, top, color)
        line(canvas, plot.left, bottom, plot.right, bottom, third)
        text(canvas, "+" + fmt(shown.maxBoostDb) + " dB", plot.left + d(3f), max(plot.top + d(11f), top + d(12f)), plot.width() - d(6f), color)
        text(canvas, "−" + fmt(shown.maxCutDb) + " dB", plot.right - d(3f), min(plot.bottom, bottom - d(4f)), plot.width() - d(6f), third, Paint.Align.RIGHT)
        footer(canvas, "某方向设 0 dB = 不允许该方向动态作用")
    }
    private fun drawTimeFlow(canvas: Canvas): Unit {
        caption(canvas, "攻击 " + fmt(shown.attackMs) + " · 保持 " + fmt(shown.holdMs, 0) + " · 释放 " + fmt(shown.releaseMs, 0) + " ms")
        val times: List<Double> = listOf(shown.attackMs, shown.holdMs, shown.releaseMs)
        val denominator: Double = times.sum().coerceAtLeast(1.0)
        var start: Double = 0.0
        val names: List<String> = if (shown.standard && shown.settings.type == 3) listOf("开门", "保持", "关门") else listOf("起效", "保持", "恢复")
        for (index in 0..2) {
            val end: Double = start + times[index] / denominator
            fillPaint.color = ColorUtils.setAlphaComponent(palette(index), 0x55)
            canvas.drawRect(RectF(px(start), py(.78), px(end), py(.28)), fillPaint)
            text(canvas, names[index], px((index + .5) / 3), plot.bottom + d(10f), plot.width() / 3, palette(index), Paint.Align.CENTER)
            start = end
        }
        footer(canvas, "阶段时长比例示意 · 频域还受更新间隔限制")
    }
    private fun drawDetectorRoutes(canvas: Canvas): Unit {
        caption(canvas, if (shown.settings.linked == 1 && shown.detectOn) "左右较强声道联动" else "左右平均联动")
        roundedNode(canvas, RectF(plot.left, py(.94), px(.38), py(.71)), "L", secondary, true)
        roundedNode(canvas, RectF(px(.62), py(.94), plot.right, py(.71)), "R", secondary, true)
        line(canvas, px(.19), py(.71), px(.50), py(.50), color)
        line(canvas, px(.81), py(.71), px(.50), py(.50), color)
        roundedNode(canvas, RectF(px(.08), py(.60), px(.92), py(.36)), shown.detectorName, color, true)
        roundedNode(canvas, RectF(px(.08), py(.23), px(.92), py(.01)), "弱信号保护 ≠ 静音", third, true)
        footer(canvas, "保护仅原版 / 向上压缩使用 · 其它类型保留处理")
    }
    private fun drawRangeRoutes(canvas: Canvas): Unit {
        caption(canvas, "作用范围外保留原声")
        line(canvas, plot.left, py(.84), plot.right, py(.84), secondary)
        text(canvas, "原声基础", plot.left, py(.84) - d(6f), plot.width(), secondary)
        path.rewind(); path.moveTo(plot.left, py(.12))
        shown.frequencies.forEachIndexed { index: Int, frequency: Double -> path.lineTo(fx(frequency), py(.12 + .46 * shown.rangeMask[index])) }
        path.lineTo(fx(shown.frequencies.last()), py(.12)); path.close()
        fillPaint.shader = LinearGradient(plot.left, plot.top, plot.right, plot.bottom,
            ColorUtils.setAlphaComponent(color, 0x65), ColorUtils.setAlphaComponent(third, 0x25), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fillPaint); fillPaint.shader = null
        footer(canvas, if (shown.standard) "只对滤出支路加动态增量 · 不当整路 EQ" else "范围外停止该频格动态作用，不删除音频")
    }
    private fun drawSidechainRoutes(canvas: Canvas): Unit {
        caption(canvas, "声音通路与检测通路分开")
        val mainY: Float = py(.79); val detectorY: Float = py(.25)
        line(canvas, plot.left, mainY, plot.right, mainY, secondary)
        text(canvas, "原声不经过侧链滤波", plot.left, mainY - d(6f), plot.width(), secondary)
        line(canvas, px(.15), mainY, px(.15), detectorY, color, true)
        roundedNode(canvas, RectF(px(.24), detectorY - d(10f), plot.right, detectorY + d(10f)), "HP / LP → 检测", color, true)
        line(canvas, px(.15), detectorY, px(.24), detectorY, color, true)
        footer(canvas, "侧链只改变触发 · 不是听音的低切高切")
    }
    private fun drawAheadArrival(canvas: Canvas): Unit {
        caption(canvas, "先检测 · 音频延后 " + fmt(shown.lookaheadMs) + " ms")
        val offset: Double = shown.lookaheadMs / 25
        val upper: RectF = RectF(plot.left, plot.top + d(10f), plot.right, plot.centerY() - d(6f))
        val lower: RectF = RectF(plot.left, plot.centerY() + d(12f), plot.right, plot.bottom - d(3f))
        wave(canvas, upper, secondary) { t: Double -> exp(-((t - .15) / .045).pow(2)) }
        wave(canvas, lower, color) { t: Double -> exp(-((t - .15 - offset) / .045).pow(2)) }
        text(canvas, "检测", plot.left, upper.top, plot.width(), secondary)
        text(canvas, "音频", plot.left, lower.top, plot.width(), color)
        footer(canvas, "目标 " + shown.nominalDelaySamples + " 样本 · 不是消除全部过载")
    }
    private fun drawGateThresholds(canvas: Canvas): Unit {
        caption(canvas, "门打开与关闭使用不同阈值")
        val opens: Double = (shown.thresholdDb + 100) / 100
        val closes: Double = (shown.thresholdDb - shown.hysteresisDb + 100) / 100
        fillPaint.color = ColorUtils.setAlphaComponent(third, 0x20)
        canvas.drawRect(RectF(plot.left, py(.80), px(closes), py(.18)), fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x25)
        canvas.drawRect(RectF(px(opens), py(.80), plot.right, py(.18)), fillPaint)
        line(canvas, px(closes), py(.90), px(closes), py(.08), third)
        line(canvas, px(opens), py(.90), px(opens), py(.08), color)
        text(canvas, "关 " + fmt(shown.thresholdDb - shown.hysteresisDb), plot.left, plot.top + d(7f), plot.width() * .75f, third)
        text(canvas, "开 " + fmt(shown.thresholdDb), plot.right, plot.bottom + d(10f), plot.width() * .75f, color, Paint.Align.RIGHT)
        footer(canvas, "中间 " + fmt(shown.hysteresisDb) + " dB 保持原门状态，减少来回跳动")
    }
    private fun drawMixRoutes(canvas: Canvas): Unit {
        caption(canvas, "原声 " + fmt((1 - shown.mix) * 100, 0) + "% + 效果 " + fmt(shown.mix * 100, 0) + "%")
        val dryY: Float = py(.77); val wetY: Float = py(.25)
        line(canvas, px(.06), dryY, px(.88), dryY, secondary, shown.mix >= 1)
        line(canvas, px(.06), wetY, px(.88), wetY, color, shown.mix <= 0)
        roundedNode(canvas, RectF(px(.16), dryY - d(10f), px(.73), dryY + d(10f)), "按算法对齐原声", secondary, shown.mix < 1)
        roundedNode(canvas, RectF(px(.16), wetY - d(10f), px(.73), wetY + d(10f)), "压扩处理结果", color, shown.mix > 0)
        line(canvas, px(.06), dryY, px(.06), wetY, foreground)
        line(canvas, px(.88), dryY, px(.88), wetY, third)
        dot(canvas, px(.88), py(.50), third, d(5f))
        text(canvas, "+", px(.88), py(.50) + d(3f), d(10f), Color.WHITE, Paint.Align.CENTER)
        footer(canvas, "延迟对齐不等于消除所有滤波相位差")
    }
    private fun drawLevelWave(canvas: Canvas): Unit {
        caption(canvas, "输出 " + signed(shown.outputDb) + " dB · 固定衰减")
        val box: RectF = RectF(plot.left, plot.top + d(9f), plot.right, plot.bottom - d(8f))
        fillPaint.shader = LinearGradient(plot.left, plot.top, plot.left, plot.bottom,
            ColorUtils.setAlphaComponent(color, 0x25), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, d(8f), d(8f), fillPaint); fillPaint.shader = null
        wave(canvas, box, secondary) { t: Double -> sin(4 * PI * t) }
        wave(canvas, box, color) { t: Double -> outputGain() * sin(4 * PI * t) }
        footer(canvas, "完整混合后 · 幅度 ×" + fmt(outputGain(), 3))
    }
    private fun drawActionLayers(canvas: Canvas): Unit {
        caption(canvas, "固定检测条件下的频率作用示意")
        grid(canvas, "+36 dB", "−60", "20 Hz", "20 kHz")
        shown.actionCurves.forEachIndexed { index: Int, values: DoubleArray -> frequencyTrace(canvas, values, palette(index), index == 2, -60.0, 36.0) }
        footer(canvas, "6 / 12 / 24 dB 判据距离 · 不代表实测频谱")
    }
    private fun drawMonitorRoutes(canvas: Canvas): Unit {
        caption(canvas, "输入 / 检测与背景 / 动态控制 / 输出")
        val labels: List<String> = listOf("输入与对齐原声", "检测器 / 频带背景", "控制增益 dB ≠ 系数 C", "正常处理 / 试听输出")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.86 - index * .25)
            roundedNode(canvas, RectF(plot.left, y - d(7f), plot.right, y + d(7f)), label, if (index == 2) third else color, true)
        }
        footer(canvas, "真实测量见动态图 · 模型不生成音频读数")
    }
    private fun drawBalance(canvas: Canvas): Unit {
        caption(canvas, if (snapshot.matchingReady) "等电平就绪 · 只作固定衰减" else "保存 A + B → 稳定预热 → 测量")
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
        footer(canvas, "真实阶段见原生状态 · 不等于心理声学等响")
    }
    private fun drawListening(canvas: Canvas): Unit {
        val selected: Int = snapshot.audition.coerceIn(0, 6)
        caption(canvas, "当前：" + routeLabels[selected])
        val labels: List<String> = listOf("对齐原声", if (selected == 3) "处理差分" else "压扩处理", "快照 A", "快照 B")
        val choice: Int = when (selected) { 1 -> 0; 4 -> 2; 5 -> 3; else -> 1 }
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.85 - index * .23)
            val tint: Int = if (index == choice) color else ColorUtils.setAlphaComponent(foreground, 0x55)
            roundedNode(canvas, RectF(plot.left, y - d(7f), px(.62), y + d(7f)), label, tint, index == choice)
            path.rewind(); path.moveTo(px(.62), y); path.cubicTo(px(.78), y, px(.70), py(.51), px(.88), py(.51))
            stroke(canvas, tint, index != choice)
        }
        val ear: RectF = RectF(px(.83), py(.69), plot.right, py(.32))
        linePaint.color = color; canvas.drawArc(ear, -85f, 280f, false, linePaint)
        footer(canvas, "静图为保存参数 · A/B实际结果由动态图显示")
    }
    private fun details(): String {
        val s: P.Settings = snapshot.settings
        val state: String = if (enabled) "显示保存配置的参数参考。" else snapshot.reason(focus, key) + "；灰色预览不切换真实引擎，不写入参数。"
        val explanation: String = when (focus) {
            CompanderChartFocus.FOUNDATION -> "原时间保存 " + fmt(s.legacyTime.toDouble(), 3) + " 秒；原粒度保存 " + fmt(s.legacyGranularity.toDouble(), 3) + "，驱动按整数 " + s.legacyGranularity.toInt() + "（显示 " + (s.legacyGranularity.toInt() + 1) + "/5）使用；原变换编号 " + s.legacyTransform + "。四种变换为 STFT、CWT、伪多分辨率频域及 Gammatone 时域，具有不同频谱网格与更新节奏。\n旧七点和旧时间在对应专业组接管时置灰；标准引擎开启会接管全部四项旧基础，但原保存值保留。本图取旧基础副本说明原算法；若被接管则灰色显示，不把它冒充标准引擎当前输出。标称延迟只是模型目标：原版频域实际原声追踪/分块也影响对齐，实际值由驱动报告；时域标称 0 样本不代表端到端零延迟。"
            CompanderChartFocus.CURVE -> "七点是“定义频率 Hz → 动态系数 C”，不是七个输入阈值或七个固定压缩器。频率保存修正单位 0.001 Hz，系数修正单位 0.001，加在原七点 Float 线协议基础上；派生系数限制 −1.2～+1.2，频率按采样率保持合法和有序，原保存值不改。\n原 FFT/STFT/CWT 类算法非精确映射时在定义曲线 f/4 处取值，因此映射频率为定义频点×4；Gammatone 或实际 Hz 映射为×1。映射点若超过当前奈奎斯特频率，并不代表那里存在可用的音频频格，更不是实测活动频点。左图横轴明确显示定义频率，插值本身为线性 Hz 的 PCHIP，画在对数轴上不等于用对数插值；低于显示窗口的点只在边缘标记，长按可读取精确值。\n总作用强度保存 " + fmt(s.amount / 10.0) + "% ，只在原版核心组生效。正 C 在超出各频带背景时产生提升，负 C 产生衰减，C=0 不作动态作用；实际量还由背景、相对判据、保护、作用量限制和平滑决定。C 无量纲，不能标作 dB 或标准压缩比。\n当前派生定义频点：" + shown.nodes.joinToString(" / ") { fmt(it, 3) } + " Hz；当前系数：" + shown.coefficients.joinToString(" / ") { signed(it, 3) } + "。"
            CompanderChartFocus.ENGINE -> "原版七点引擎按各频带历史背景与当前频谱的差异决定动态增减，再由七点系数控制方向和强度；背景是随内容变化的各频带状态，没有一个固定全局阈值。\n标准引擎采用固定 dBFS 阈值、压扩比率和四种动态类型，原七点系数、旧变换与旧时间在标准引擎不参与作用。开启标准核心组才开始阈值压扩，核心关闭为不作该动态增减。切回原版恢复原保存基础。图纸的禁用组只在显示副本中预览，不暗改引擎选择。"
            CompanderChartFocus.STANDARD -> "类型：“" + shown.typeName + "”；阈值保存 " + fmt(s.threshold / 10.0) + " dBFS，比率 " + fmt(s.ratio / 100.0, 2) + ":1，标准软拐点 " + fmt(s.standardKnee / 10.0) + " dB。\n向下压缩在高于阈值时降低增益；向上压缩在低于阈值时提升，低于 −100 dBFS 停止提升，检测组还可加弱信号保护；向下扩展让低于阈值的信号更弱；噪声门按开闭判据衰减，不使用比率。软拐点在边界平滑过渡。\n左图是固定检测电平下的稳态 I/O 参数参考，已包含保存混合与输出；不模拟真实包络、侧链内容或处理频段相位。显示窗口以外曲线裁绘，并非另外添加封顶。真正频段合成与实时增益以下方动态图为准。"
            CompanderChartFocus.RELATIVE -> "相对触发差保存 " + fmt(s.relative / 10.0) + " dB，软拐点 " + fmt(s.knee / 10.0) + " dB。仅原版七点引擎使用：超出历史背景与触发差后，按软拐点平滑再乘该频率系数，专业核心还可乘总强度。\n左图固定用 −36 dBFS 作为示例历史背景，横轴是对背景的相对电平，七条线对应七个频率系数的参考动态增益。−36 只是绘图参照，不是固定阈值或从驱动测到的全局背景。真实背景按原频谱格点分别跟随，需实际分频监测读取；总检测器读数不能替代七点/频格背景。"
            CompanderChartFocus.LIMITS -> "最大提升保存 " + fmt(s.maxBoost / 10.0) + " dB，最大衰减保存 " + fmt(s.maxCut / 10.0) + " dB。0 dB 表示禁止相应方向的动态作用，不是输出静音。\n原版可以限制正负两方向；标准向上压缩只使用提升限制，向下压缩、向下扩展和噪声门只使用衰减限制。不适用方向灰色保留。关闭限制恢复原算法边界：标准门关闭深度通常为 60 dB，启用后由最大衰减决定；其它处理仍受原生安全范围约束。此组限制动态作用量，固定输出增益是另一位置。"
            CompanderChartFocus.TIMING -> "背景时间修正保存 " + signed(s.backgroundDelta / 10.0) + " ms；攻击 " + fmt(s.attack / 10.0) + " ms；保持 " + s.hold + " ms；释放 " + s.release + " ms。原时间单位秒先转毫秒，再加背景修正并限制 1～4000 ms；背景项仅原版使用。\n一般处理在动态作用绝对量增加或方向反转时用攻击，向中性恢复时经过保持再用释放。噪声门不同：开启用攻击，关闭经过保持后用释放，不能把关门过程标成恢复到零衰减。图中包络归一化显示时间目标；曲线不是实际增益历史。\n频域原算法受 STFT/CWT 帧更新间隔约束，攻击数值很小不保证每采样立即更新；实际更新周期与包络状态由驱动提供。"
            CompanderChartFocus.DETECTOR -> "左右联动保存为" + (if (s.linked == 1) "较强声道" else "左右平均") + "，仅检测组开启时较强选择才生效。标准检测保存为" + (if (s.detector == 1) "矩形时间窗 RMS" else "当前样本峰值") + "；RMS 窗保存 " + s.rmsWindow + " ms。它是滑动均方窗，不应冒充另一条指数攻击包络。\n弱信号保护保存 " + fmt(s.weakFloor / 10.0) + " dBFS，只适用于原版或标准向上压缩：低于保护电平停止动态增减/提升，但保留原声，不是静音门限。标准向下压缩、向下扩展、噪声门不使用这个保护；低电平仍按各类型处理。图中只说明检测权重和保护条件，不生成实际检测值。"
            CompanderChartFocus.RANGE -> "保存处理下限 " + s.rangeLow + " Hz，上限 " + s.rangeHigh + " Hz，0 为该侧不限。需检测与作用范围组开启。\n原版按实际频谱格点决定是否施加动态作用，范围外停止该频格增减，音频仍保留；标准引擎以一阶滤波取出处理支路，只对该支路叠加动态增量再与原声相加。左图是两种不同的作用包络，不把原版格点开关画成真实滤波器。\n标准支路滤波相位会影响完整混合，所以控制增益曲线不能冒充整个模块的固定频响。频率按采样率安全限幅，实际边界由驱动报告。"
            CompanderChartFocus.SIDECHAIN -> "标准侧链低切保存 " + s.sideHP + " Hz，高切 " + s.sideLP + " Hz；0 为旁路。只有标准引擎使用。侧链滤波是从已选处理频段继续滤出检测信号，所以左图明确为“处理带+侧链”的合成检测幅度参考。\n这些一阶高/低通只改变触发电平，不把听音原声做相同低切高切。核心、范围和侧链开关分别保留原职责；灰色图是保存目标预览，不会替用户开启声音算法。"
            CompanderChartFocus.LOOKAHEAD -> "前瞻保存 " + fmt(s.lookahead / 10.0) + " ms，仅标准引擎有效。启用后检测当前样本，同时将音频按当前基础采样率换算并四舍五入到整采样延后，最大保存前瞻 20 ms。\n左图与右图展示参数目标和固定脉冲示意，不是实测端到端延迟。前瞻会增加延迟，不能消除全部超载，也不是砖墙限制器；A/B、切换和原版频域引擎的共同对齐可能增加其它延迟，以实际驱动状态为准。"
            CompanderChartFocus.HYSTERESIS -> "门开启阈值 " + fmt(s.threshold / 10.0) + " dBFS，回差 " + fmt(s.hysteresis / 10.0) + " dB，因此关闭阈值为 " + fmt((s.threshold - s.hysteresis) / 10.0) + " dBFS。仅标准噪声门使用，回差不是毫秒。\n关闭状态达到开启阈值才开门，打开状态跌破较低的关闭阈值才关门；中间带保持前一门状态，减少反复抖动。软拐点控制关门作用的过渡深度，攻击/保持/释放控制随时间变化。两条 I/O 线分别以原来开/关为前提，是稳态状态参考，不是音乐中的真实门控轨迹。"
            CompanderChartFocus.MIX -> "效果比例保存 " + fmt(s.mix / 10.0) + "% 。0% 是按当前引擎实际延迟对齐的输入，100% 是处理结果，再乘输出增益。原频域算法的原声参考与分块/帧调度相关，不能只拿标称延迟代替真实对齐；标准引擎另有前瞻。\n固定延迟对齐不能消除所有处理频段滤波相位。图中两柱为线性混合系数，不是能量百分比或试听响度；正常处理 PCM 已包含当前混合，不等于单独动态增量。"
            CompanderChartFocus.LEVEL -> "模块输出增益保存 " + signed(s.output / 10.0) + " dB，线性系数 " + fmt(10.0.pow(s.output / 200.0), 4) + "。它位于完整干湿混合后，只作固定衰减。0 dB 不额外缩放；与提升/衰减限制、七点系数、标准比率都不是同一参数。\n示例波形只表示固定幅度缩放，不是实际 RMS、真峰值或耳内声压。"
            CompanderChartFocus.RESPONSE -> "三条参数参考线分别取距判据 6 / 12 / 24 dB 的固定检测条件。原版参照历史背景 −36 dBFS 加超额；标准向下压缩取阈值上方，其余标准类型取阈值下方。曲线包含保存混合与输出，标准处理带还按复数滤波支路合成。\n这不是音乐实测频谱，也不是原生实际动态控制量。真实图纸中的七点/32点控制增益 dB 与原始动态系数 C、检测电平、频带背景分别来自不同数据；背景只有分频读数，不能当全局轨迹。原“显示实时动态增益图”开关继续控制原响应遥测，顶部静态保存模型不因此消失。"
            CompanderChartFocus.MONITOR -> "原生电平开关保存为" + (if (s.meters) "开启" else "关闭") + "；响应监测开关保存为" + (if (s.response) "开启" else "关闭") + "。输入/输出峰值、RMS、检测电平、实际提升/衰减、门状态和频率增益控制量有不同测点。\n七点是原模型的频率系数采样，不应称为七个独立固定阈值压缩通道。标准引擎具有共享宽带控制量，原版具有原频谱网格；七根遥测柱并不代表新增七分频 DSP。静图只解释位置，真实音频、控制量与 A/B 状态由下方实时图和原生长说明提供。当前采样率是本处理器基础率，可能与宿主率不同。"
            CompanderChartFocus.MATCH -> "快照 A " + (if (snapshot.savedA) "已保存" else "未保存") + "，B " + (if (snapshot.savedB) "已保存" else "未保存") + "；匹配" + (if (snapshot.matching) "开启" else "关闭") + "。同一输入同时处理两快照；预热由各引擎的背景/释放与延迟状态决定，再测量 2 秒 RMS。图中只画流程，不假设两个私有快照的实际预热长度。\n有效时以原声、A、B 三路最低能量为目标，只作固定衰减，不持续追踪或提升音量；等 RMS 电平不等于心理声学等响度。静图不读取或改写私有快照参数。"
            CompanderChartFocus.AUDITION -> "当前：“" + routeLabels[snapshot.audition.coerceIn(0, 6)] + "”。原生七路正常、对齐原声、仅效果、效果差分、A、B、测量操作全部保留。效果差分包含算法重构、相位、增益与动态的共同差异，不是纯压缩量或谐波。\n静图始终表示页面保存参数；A/B 实际输出以下方实时音频/状态为准。保存快照、匹配、试听及停止均由原按钮负责，图纸不发参数或存储写入。"
        }
        val note: String = if (snapshot.applicable(focus, key)) "" else "\n\n当前引擎或类型不使用本组；为解释保存值而构造的灰色显示副本不会切换真实引擎。"
        return (if (illustration) focus.rightTitle else focus.leftTitle) + "\n\n" + state + "\n\n" + explanation + note +
            "\n\n" + (if (snapshot.actualRate) "实际基础采样率 " else "参考基础采样率 ") + snapshot.rate + " Hz；模型窗口外曲线裁绘，真实数据不由静图模拟。"
    }
    override fun onDetachedFromWindow(): Unit {
        generation++; CompanderStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}


