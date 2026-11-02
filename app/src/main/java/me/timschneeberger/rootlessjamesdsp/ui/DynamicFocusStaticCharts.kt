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
import me.timschneeberger.rootlessjamesdsp.fusion.DynamicChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.DynamicController
import me.timschneeberger.rootlessjamesdsp.fusion.DynamicDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.DynamicParameters as P
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalResponse.Complex
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** Dynamic tone observers; this is neither a multiband compressor nor the adaptive-bass page. */
internal class DynamicParameterChart(context: Context) : DynamicFocusStaticBase(context, false)
internal class DynamicIllustrationChart(context: Context) : DynamicFocusStaticBase(context, true)

private data class DynamicFocusSnapshot(
    val settings: P.Settings, val rate: Int, val actualRate: Boolean, val moduleOn: Boolean,
    val supported: Boolean, val audition: Int, val matching: Boolean, val matchingReady: Boolean,
    val savedA: Boolean, val savedB: Boolean
) {
    fun applicable(focus: DynamicChartFocus): Boolean = when (focus) {
        DynamicChartFocus.GAIN, DynamicChartFocus.BRANCH, DynamicChartFocus.CROSSOVER -> settings.base != 4
        DynamicChartFocus.DEEP -> settings.base == 4
        else -> true
    }
    fun active(focus: DynamicChartFocus, key: String): Boolean {
        if (focus == DynamicChartFocus.AUDITION && key in setOf("dynamic_pro_save_a", "dynamic_pro_save_b")) return supported
        if (!moduleOn) return false
        if (focus == DynamicChartFocus.BASE) return true
        if (!supported || !settings.professional || !applicable(focus)) return false
        return when (focus) {
            DynamicChartFocus.GAIN, DynamicChartFocus.BRANCH -> settings.coreOn
            DynamicChartFocus.DEEP -> if (key == P.deepFreqDelta.key || key == P.TONE_ON) settings.toneOn else settings.coreOn
            DynamicChartFocus.CROSSOVER, DynamicChartFocus.FILTER -> settings.toneOn
            DynamicChartFocus.DETECTOR, DynamicChartFocus.DYNAMICS, DynamicChartFocus.TIMING -> settings.adaptiveOn
            DynamicChartFocus.MIX -> settings.mixOn
            DynamicChartFocus.LEVEL -> settings.outputOn
            DynamicChartFocus.MONITOR -> settings.meters
            else -> true
        }
    }
    fun reason(focus: DynamicChartFocus, key: String): String = when {
        !moduleOn -> "动态音色关闭 · 保存目标预览"
        !applicable(focus) && settings.base == 4 -> "当前深低音模式 · 分带参数保存预览"
        !applicable(focus) -> "当前分带模式 · 深低音保存预览"
        !supported -> "专业驱动未支持 · 保存目标预览"
        !settings.professional -> "当前原预设声音 · 专业保存预览"
        focus == DynamicChartFocus.GAIN || focus == DynamicChartFocus.BRANCH -> "核心精调关闭 · 保存目标预览"
        focus == DynamicChartFocus.DEEP && (key == P.deepFreqDelta.key || key == P.TONE_ON) -> "音色组关闭 · 深低音频率保存预览"
        focus == DynamicChartFocus.DEEP -> "核心精调关闭 · 注入与 Q 保存预览"
        focus == DynamicChartFocus.CROSSOVER || focus == DynamicChartFocus.FILTER -> "音色组关闭 · 保存频带预览"
        focus == DynamicChartFocus.DETECTOR || focus == DynamicChartFocus.DYNAMICS || focus == DynamicChartFocus.TIMING -> "电平自适应关闭 · 保存目标预览"
        focus == DynamicChartFocus.MIX -> "混合关闭 · 保存比例预览"
        focus == DynamicChartFocus.LEVEL -> "输出增益关闭 · 保存衰减预览"
        focus == DynamicChartFocus.MONITOR -> "实时电平关闭 · 测点示意"
        else -> "保存目标预览"
    }
    fun target(focus: DynamicChartFocus, key: String): P.Settings {
        val saved: P.Settings = settings.copy(meters = false, response = false)
        if (focus == DynamicChartFocus.BASE) return if (supported) saved else saved.copy(professional = false)
        val base: P.Settings = saved.copy(professional = true)
        return when (focus) {
            // Inapplicable groups are explicitly grey saved-parameter previews, never preset writes.
            DynamicChartFocus.GAIN, DynamicChartFocus.BRANCH -> base.copy(coreOn = true, base = if (base.base == 4) 2 else base.base)
            DynamicChartFocus.DEEP -> if (key == P.deepFreqDelta.key || key == P.TONE_ON) base.copy(base = 4, toneOn = true)
                else base.copy(base = 4, coreOn = true)
            DynamicChartFocus.CROSSOVER -> base.copy(toneOn = true, base = if (base.base == 4) 2 else base.base)
            DynamicChartFocus.FILTER -> base.copy(toneOn = true)
            DynamicChartFocus.DETECTOR, DynamicChartFocus.DYNAMICS, DynamicChartFocus.TIMING -> base.copy(adaptiveOn = true)
            DynamicChartFocus.MIX -> base.copy(mixOn = true)
            DynamicChartFocus.LEVEL -> base.copy(outputOn = true)
            else -> if (supported) saved else saved.copy(professional = false)
        }
    }
}

private data class DynamicStaticResult(val model: DynamicDashboardModel, val phase: DoubleArray, val recoveredPhase: DoubleArray)

/** Native complex-path phase, evaluated separately from amplitude without audio or detector state. */
private object DynamicStaticPhase {
    fun calculate(model: DynamicDashboardModel, recoveryDb: Double): DoubleArray {
        val s: P.Settings = model.settings
        val foundation: IntArray = P.foundation(s.base)
        var bass: Float = ((100 + 20 * s.strength) / 100.0).toFloat()
        var lowGain: Float = foundation[5] / 100f; var highGain: Float = foundation[4] / 100f
        var q: Float = min(1600.0, ((bass.toDouble() - 1) / 20) * 1600).toFloat() / 666f + .5f
        var inject: Float = 1f
        if (model.coreOn) {
            if (model.deep) { inject = max(0f, 1 + s.injectDelta / 1000f); q = (q + s.qDelta / 100f).coerceIn(.1f, 4f) }
            else { bass *= 10.0.pow(s.bassDelta / 200.0).toFloat(); lowGain = max(0f, lowGain + s.lowGainDelta / 1000f); highGain = max(0f, highGain + s.highGainDelta / 1000f) }
        }
        val gain: Double = 10.0.pow(-(if (model.adaptive) recoveryDb.coerceIn(0.0, model.maxReductionDb) else 0.0) / 20)
        return model.frequencies.map { frequency: Double ->
            val z: Complex = Complex(cos(2 * PI * frequency / model.rate), -sin(2 * PI * frequency / model.rate))
            val z3: Complex = z * z * z
            val direct: Complex = if (model.deep) Complex(1.0) else z3
            val unchanged: Complex
            var low: Complex
            if (model.deep) {
                val omega: Double = 2 * PI * model.deepHz.toFloat() / model.rate
                val cosine: Double = cos(omega); val alpha: Double = sin(omega) / (2 * q.toDouble())
                low = (Complex((1 - cosine) * .5) + z * (1 - cosine) + z * z * ((1 - cosine) * .5)) /
                    (Complex(1 + alpha) - z * (2 * cosine) + z * z * (1 - alpha)) * (2 * inject.toDouble())
                unchanged = Complex(1.0)
            } else {
                val x: Complex = poles(model.xHz, model.rate, z)
                low = x * (z3 * highGain.toDouble() + poles(model.yHigh, model.rate, z) * (1 - highGain.toDouble()) +
                    poles(model.yLow, model.rate, z) * (lowGain.toDouble() - 1)) * bass.toDouble()
                unchanged = z3 - x
            }
            if (model.hpHz > 0) low *= filter(model.hpHz, model.rate, true, z)
            if (model.lpHz > 0) low *= filter(model.lpHz, model.rate, false, z)
            val response: Complex = (direct * (1 - model.mix) + (unchanged + low * gain) * model.mix) * model.outputGain
            if (response.magnitude() < 1e-10) Double.NaN else atan2(response.imaginary, response.real) * 180 / PI
        }.toDoubleArray()
    }
    private fun poles(hz: Int, rate: Int, z: Complex): Complex {
        val a: Double = (hz.toFloat() * PI.toFloat() / rate.toFloat()).toDouble()
        val pole: Complex = Complex(a) / (Complex(1.0) - z * (1 - a))
        val square: Complex = pole * pole
        return square * square
    }
    private fun filter(hz: Int, rate: Int, high: Boolean, z: Complex): Complex {
        val omega: Double = 2 * PI * hz / rate; val cosine: Double = cos(omega); val alpha: Double = sin(omega) / sqrt(2.0)
        val first: Double = if (high) 1 + cosine else 1 - cosine
        return (Complex(first * .5) + z * (if (high) -first else first) + z * z * (first * .5)) /
            (Complex(1 + alpha) - z * (2 * cosine) + z * z * (1 - alpha))
    }
}

private data class DynamicStaticRequest(val settings: P.Settings, val rate: Int)
/** A bounded shared worker. Dragging replaces only the waiting target; completed curves stay visible. */
private object DynamicStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "dynamic-focus-static").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    private data class Task(val request: DynamicStaticRequest, val callback: (DynamicStaticResult) -> Unit)
    private class Owner {
        var running: DynamicStaticRequest? = null
        var waiting: Task? = null
        var future: Future<*>? = null
    }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<DynamicStaticRequest, DynamicStaticResult> =
        object : LinkedHashMap<DynamicStaticRequest, DynamicStaticResult>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DynamicStaticRequest, DynamicStaticResult>?): Boolean = size > 16
        }
    @Synchronized fun release(owner: Any): Unit {
        owners.remove(owner)?.let { state -> state.waiting = null; state.future?.cancel(true); state.future = null }
    }
    @Synchronized fun request(owner: Any, request: DynamicStaticRequest, callback: (DynamicStaticResult) -> Unit): Unit {
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
            val result: DynamicStaticResult = synchronized(this) { cache[task.request] } ?: run {
                val values: Map<String, Any> = task.request.settings.values() + (P.ENABLED to true)
                val model: DynamicDashboardModel = DynamicDashboardModel.from(values, task.request.rate, true)
                DynamicStaticResult(model, DynamicStaticPhase.calculate(model, 0.0), DynamicStaticPhase.calculate(model, model.maxRecoveryDb))
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

internal open class DynamicFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
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
    private var configuration: DynamicDashboardModel = DynamicDashboardModel.from(emptyMap<String, Any>())
    private var shown: DynamicDashboardModel = configuration
    private var shownPhase: DoubleArray = DoubleArray(configuration.frequencies.size)
    private var shownRecoveredPhase: DoubleArray = shownPhase
    private var snapshot: DynamicFocusSnapshot = snapshot(configuration)
    private var focus: DynamicChartFocus = DynamicChartFocus.BASE
    private var key: String = P.BASE
    private var active: Boolean = true
    private var request: DynamicStaticRequest? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = snapshot.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x78)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x4c)
    private fun snapshot(model: DynamicDashboardModel): DynamicFocusSnapshot = DynamicFocusSnapshot(
        model.settings, model.rate, model.actualRate, model.enabled, DynamicController.supported(context),
        DynamicController.audition, DynamicController.match, DynamicController.matchingReady(),
        DynamicController.hasComparison(0), DynamicController.hasComparison(1))
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
    fun setConfiguration(model: DynamicDashboardModel): Unit {
        configuration = model
        val next: DynamicFocusSnapshot = snapshot(model)
        if (next == snapshot) return
        snapshot = next; updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setFocus(focus: DynamicChartFocus, key: String): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; DynamicStaticWork.release(this); request = null }
        this.focus = focus; this.key = key
        updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) {
            generation++; DynamicStaticWork.release(this); request = null
            detailDialog?.dismiss(); detailDialog = null
        } else { updateTarget(); invalidate() }
    }
    private fun updateTarget(): Unit {
        if (!active) return
        val target: P.Settings = snapshot.target(focus, key)
        val next: DynamicStaticRequest = DynamicStaticRequest(target, configuration.rate)
        if (request == next) return
        request = next
        val token: Long = generation
        DynamicStaticWork.request(this, next) { value ->
            if (active && token == generation) { shown = value.model; shownPhase = value.phase; shownRecoveredPhase = value.recoveredPhase; invalidate() }
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
    private fun bassGain(): Double = (1 + .2 * shown.settings.strength) *
        if (shown.coreOn && !shown.deep) 10.0.pow(shown.settings.bassDelta / 200.0) else 1.0
    private fun lowRatio(): Double = max(0.0, P.foundation(shown.baseIndex)[5] / 100.0 + if (shown.coreOn) shown.settings.lowGainDelta / 1000.0 else 0.0)
    private fun highRatio(): Double = max(0.0, P.foundation(shown.baseIndex)[4] / 100.0 + if (shown.coreOn) shown.settings.highGainDelta / 1000.0 else 0.0)
    private fun injection(): Double = if (shown.coreOn && shown.deep) max(0.0, 1 + shown.settings.injectDelta / 1000.0) else 1.0
    private fun quality(): Double {
        val foundationGain: Float = ((100 + 20 * shown.settings.strength) / 100.0).toFloat()
        val original: Float = min(1600.0, ((foundationGain.toDouble() - 1) / 20) * 1600).toFloat() / 666f + .5f
        return if (shown.coreOn && shown.deep) (original + shown.settings.qDelta / 100f).coerceIn(.1f, 4f).toDouble() else original.toDouble()
    }
    private fun drawParameters(canvas: Canvas): Unit = when (focus) {
        DynamicChartFocus.BASE -> drawBase(canvas)
        DynamicChartFocus.GAIN -> drawGain(canvas)
        DynamicChartFocus.BRANCH -> drawBranch(canvas)
        DynamicChartFocus.DEEP -> drawDeep(canvas)
        DynamicChartFocus.CROSSOVER -> drawCrossover(canvas)
        DynamicChartFocus.FILTER -> drawFilter(canvas)
        DynamicChartFocus.DETECTOR -> drawDetector(canvas)
        DynamicChartFocus.DYNAMICS -> drawDynamics(canvas)
        DynamicChartFocus.TIMING -> drawTiming(canvas)
        DynamicChartFocus.MIX -> drawMix(canvas)
        DynamicChartFocus.LEVEL -> drawLevel(canvas)
        DynamicChartFocus.RESPONSE -> drawResponse(canvas)
        DynamicChartFocus.MONITOR -> drawMonitor(canvas)
        DynamicChartFocus.MATCH -> drawMatch(canvas)
        DynamicChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        DynamicChartFocus.BASE -> drawFoundation(canvas)
        DynamicChartFocus.GAIN -> drawGainWave(canvas)
        DynamicChartFocus.BRANCH -> drawBranchRoutes(canvas)
        DynamicChartFocus.DEEP -> drawDeepCloud(canvas)
        DynamicChartFocus.CROSSOVER -> drawCoverage(canvas)
        DynamicChartFocus.FILTER -> drawColorCoverage(canvas)
        DynamicChartFocus.DETECTOR -> drawDetectorRoutes(canvas)
        DynamicChartFocus.DYNAMICS -> drawRecoveryRoutes(canvas)
        DynamicChartFocus.TIMING -> drawRecoveryTiming(canvas)
        DynamicChartFocus.MIX -> drawMixRoutes(canvas)
        DynamicChartFocus.LEVEL -> drawLevelWave(canvas)
        DynamicChartFocus.RESPONSE -> drawPhase(canvas)
        DynamicChartFocus.MONITOR -> drawMonitorRoutes(canvas)
        DynamicChartFocus.MATCH -> drawBalance(canvas)
        DynamicChartFocus.AUDITION -> drawListening(canvas)
    }
    private fun responseGrid(canvas: Canvas): Unit = grid(canvas, "+60 dB", "−60", "20 Hz", "20 kHz")
    private fun drawBase(canvas: Canvas): Unit {
        caption(canvas, shown.baseName + " · 原强度 " + shown.settings.strength + " · " + if (shown.deep) "深低音" else "分带")
        responseGrid(canvas)
        frequencyTrace(canvas, shown.foundationDb, secondary, true, -60.0, 60.0)
        frequencyTrace(canvas, shown.referenceDb, color, floor = -60.0, ceiling = 60.0)
        footer(canvas, if (shown.deep) "原预设/当前目标 · 左右同相等幅参考" else "原预设/当前目标 · 分带原声对齐 3 样本")
    }
    private fun drawGain(canvas: Canvas): Unit {
        caption(canvas, "低频整体 ×" + fmt(bassGain(), 3) + " · 修正 " + signed(shown.settings.bassDelta / 10.0) + " dB")
        responseGrid(canvas)
        frequencyTrace(canvas, shown.foundationDb, secondary, true, -60.0, 60.0)
        frequencyTrace(canvas, shown.referenceDb, color, floor = -60.0, ceiling = 60.0)
        footer(canvas, "原基础 × dB 修正 · 只调低频处理支路")
    }
    private fun bar(canvas: Canvas, index: Int, count: Int, amount: Double, label: String, number: String, tint: Int): Unit {
        val cell: Float = plot.width() / count; val x: Float = plot.left + index * cell + cell * .20f
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x45)
        canvas.drawRoundRect(RectF(x, py(amount), x + cell * .60f, plot.bottom), d(2f), d(2f), fillPaint)
        text(canvas, number, x + cell * .30f, max(plot.top + d(9f), py(amount) - d(3f)), cell, tint, Paint.Align.CENTER)
        text(canvas, label, x + cell * .30f, plot.bottom + d(11f), cell, foreground, Paint.Align.CENTER)
    }
    private fun drawBranch(canvas: Canvas): Unit {
        caption(canvas, "预设基础 + 比例百分点修正")
        grid(canvas, "200%", "0", "", "")
        bar(canvas, 0, 2, lowRatio() / 2, "低段", fmt(lowRatio() * 100) + "%", color)
        bar(canvas, 1, 2, highRatio() / 2, "高段", fmt(highRatio() * 100) + "%", secondary)
        footer(canvas, "线性比例 · 百分点不是 dB 或压缩比")
    }
    private fun drawDeep(canvas: Canvas): Unit {
        caption(canvas, fmt(shown.deepHz.toDouble(), 0) + " Hz · Q " + fmt(quality(), 3) + " · 注入 " + fmt(injection() * 100, 0) + "%")
        responseGrid(canvas)
        frequencyTrace(canvas, shown.enhancementDb, third, true, -60.0, 60.0)
        frequencyTrace(canvas, shown.referenceDb, color, floor = -60.0, ceiling = 60.0)
        footer(canvas, "左右同相等幅小信号 · L+R 注入两侧")
    }
    private fun drawCrossover(canvas: Canvas): Unit {
        caption(canvas, "主基准 " + shown.xHz + " · 支路 " + shown.yLow + "/" + shown.yHigh + " Hz")
        responseGrid(canvas)
        frequencyTrace(canvas, shown.enhancementDb, color, floor = -60.0, ceiling = 60.0)
        frequencyTrace(canvas, shown.unchangedDb, secondary, true, -60.0, 60.0)
        for (hz in listOf(shown.xHz, shown.yLow, shown.yHigh)) line(canvas, fx(hz.toDouble()), plot.top,
            fx(hz.toDouble()), plot.bottom, ColorUtils.setAlphaComponent(foreground, 0x45), true)
        footer(canvas, "参数基准非标准 −3 dB 点 · 重组保留相位")
    }
    private fun drawFilter(canvas: Canvas): Unit {
        caption(canvas, "增强 HP " + shown.hpHz + " / LP " + shown.lpHz + " Hz")
        grid(canvas, "+12 dB", "−48", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.branchFilterDb, color)
        footer(canvas, "二阶 Butterworth · 仅增强支路 · 0 Hz 旁路")
    }
    private fun drawDetector(canvas: Canvas): Unit {
        caption(canvas, (if (shown.detectorIsRms) "20 ms RMS" else "双声道峰值") + " · " + shown.detectorLowHz + "–" + shown.detectorHighHz + " Hz")
        grid(canvas, "+12 dB", "−48", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.detectorDb, color)
        footer(canvas, "输入选频只用于检测 · 不等于音频主干滤波")
    }
    private fun drawDynamics(canvas: Canvas): Unit {
        caption(canvas, "阈 " + fmt(shown.thresholdDb) + " · 拐 " + fmt(shown.kneeDb) + " · 最大 " + fmt(shown.maxReductionDb) + " dB")
        grid(canvas, "0 dB", "−18", "−60 dBFS", "0 dBFS")
        val left: Float = px((shown.thresholdDb - shown.kneeDb / 2 + 60) / 60)
        val right: Float = px((shown.thresholdDb + shown.kneeDb / 2 + 60) / 60)
        fillPaint.color = ColorUtils.setAlphaComponent(third, 0x20)
        canvas.drawRect(RectF(left, plot.top, right, plot.bottom), fillPaint)
        trace(canvas) { x: Double -> 1 - shown.recoveryForInput(-60 + x * 60) / 18 }
        line(canvas, px((shown.thresholdDb + 60) / 60), plot.top, px((shown.thresholdDb + 60) / 60), plot.bottom, secondary, true)
        footer(canvas, "纵轴为增强支路负增益 · 不是总输出 GR")
    }
    private fun timeAt(position: Double): Double = 10 * (1001.0.pow(position) - 1)
    private fun drawTiming(canvas: Canvas): Unit {
        caption(canvas, "攻击 " + fmt(shown.attackMs) + " ms · 释放 " + fmt(shown.releaseMs, 0) + " ms")
        grid(canvas, "0 dB", "−18", "0 ms", "10 s · 对数")
        trace(canvas, color) { x: Double -> 1 - shown.maxReductionDb * (1 - exp(-timeAt(x) / shown.attackMs)) / 18 }
        trace(canvas, third) { x: Double -> 1 - shown.maxReductionDb * exp(-timeAt(x) / shown.releaseMs) / 18 }
        footer(canvas, "紫：回收加深 · 棕：衰减归零 · 时间常数")
    }
    private fun drawMix(canvas: Canvas): Unit {
        caption(canvas, "对齐原声 " + fmt((1 - shown.mix) * 100) + "% · 效果 " + fmt(shown.mix * 100) + "%")
        grid(canvas, "1×", "0", "", "")
        bar(canvas, 0, 2, 1 - shown.mix, "对齐原声", fmt(1 - shown.mix, 3) + "×", secondary)
        bar(canvas, 1, 2, shown.mix, "完整效果", fmt(shown.mix, 3) + "×", color)
        footer(canvas, "固定对齐 " + shown.latencySamples + " 样本 · 滤波相位仍参与混合")
    }
    private fun drawLevel(canvas: Canvas): Unit {
        caption(canvas, "输出 " + signed(20 * log10(shown.outputGain)) + " dB · " + fmt(shown.outputGain, 3) + "×")
        grid(canvas, "+1 FS", "−1", "−1 FS", "+1 FS")
        trace(canvas, secondary, true) { x: Double -> x }
        trace(canvas) { x: Double -> .5 + (2 * x - 1) * shown.outputGain * .5 }
        footer(canvas, "完整混合后衰减 · 与增强支路回收不同")
    }
    private fun drawResponse(canvas: Canvas): Unit {
        caption(canvas, "无回收 / 最大回收 " + fmt(shown.maxRecoveryDb) + " dB · 合成参考")
        responseGrid(canvas)
        frequencyTrace(canvas, shown.referenceDb, color, floor = -60.0, ceiling = 60.0)
        frequencyTrace(canvas, shown.recoveryFloorDb, third, true, -60.0, 60.0)
        footer(canvas, if (shown.deep) "L=R 同相小信号 · 回收只改增强分量" else "复数相位重组 · 回收不保证每频点都下降")
    }
    private fun drawMonitor(canvas: Canvas): Unit {
        caption(canvas, (if (configuration.actualRate) "实际 " else "参考 ") + configuration.rate + " Hz · 本模块测点")
        val labels: List<String> = listOf("输入 / 对齐原声", "检测选频电平", "回收后增强支路", "正常处理 / 试听输出")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.86 - index * .25)
            line(canvas, px(.02), y, px(.97), y, if (index == 1) third else color, !snapshot.settings.meters)
            dot(canvas, px(.05), y, secondary)
            text(canvas, label, px(.12), y - d(4f), plot.width() * .85f)
        }
        footer(canvas, "支路电平与总输出分开 · 无数据不代填")
    }
    private fun drawMatch(canvas: Canvas): Unit {
        caption(canvas, if (snapshot.matchingReady) "已测量 · " + (if (snapshot.matching) "匹配开启" else "匹配关闭") else "同一输入 · A/B 并行 RMS 测量")
        grid(canvas, "预热", "测量", "开始", "完成")
        fillPaint.color = ColorUtils.setAlphaComponent(third, 0x55)
        canvas.drawRoundRect(RectF(px(0.0), py(.85), px(.25), py(.55)), d(2f), d(2f), fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x55)
        canvas.drawRoundRect(RectF(px(.30), py(.42), px(.55), py(.12)), d(2f), d(2f), fillPaint)
        text(canvas, "≥0.5 s · 按状态预热", px(.02), py(.49), plot.width() * .98f, third)
        text(canvas, "2 秒 RMS", px(.32), py(.06), plot.width() * .65f, color)
        footer(canvas, "流程示意 · 实际预热见驱动状态，再测 2 秒")
    }
    private val routeLabels: List<String> = listOf("正常处理", "对齐输入原声", "处理后效果", "效果差分", "快照 A", "快照 B",
        "测量 A/B", "增强支路独听", "检测频段独听")
    private fun drawAudition(canvas: Canvas): Unit {
        caption(canvas, "当前：" + routeLabels[snapshot.audition.coerceIn(0, 8)])
        routeLabels.forEachIndexed { index: Int, label: String ->
            val column: Int = index / 5; val row: Int = index % 5
            val x: Float = px(if (column == 0) .02 else .52)
            val y: Float = plot.top + plot.height() * (.12f + .20f * row)
            dot(canvas, x, y - d(2f), if (snapshot.audition == index) color else ColorUtils.setAlphaComponent(foreground, 0x35), d(1.5f))
            text(canvas, label, x + d(5f), y + d(1f), plot.width() * .43f,
                if (snapshot.audition == index) color else foreground)
        }
        footer(canvas, "A " + (if (snapshot.savedA) "已保存" else "未保存") + " · B " + (if (snapshot.savedB) "已保存" else "未保存"))
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
    private fun roundedNode(canvas: Canvas, box: RectF, label: String, tint: Int, selected: Boolean = false): Unit {
        fillPaint.color = ColorUtils.setAlphaComponent(tint, if (selected) 0x30 else 0x10)
        canvas.drawRoundRect(box, d(4f), d(4f), fillPaint)
        linePaint.color = ColorUtils.setAlphaComponent(tint, if (selected) 0xd0 else 0x65)
        canvas.drawRoundRect(box, d(4f), d(4f), linePaint)
        text(canvas, label, box.centerX(), box.centerY() + d(3f), box.width() - d(4f), tint, Paint.Align.CENTER)
    }
    private fun drawFoundation(canvas: Canvas): Unit {
        caption(canvas, shown.baseName + " · 原强度 " + shown.settings.strength + " · 参数示意")
        for (index in 0..5) {
            val column: Int = index % 3; val row: Int = index / 3
            val x: Float = plot.left + plot.width() * column / 3
            val y: Float = plot.top + plot.height() * row / 2
            val name: String = me.timschneeberger.rootlessjamesdsp.fusion.ViperParameters.dynamicPresets.keys.elementAtOrElse(index) { (index + 1).toString() }
            roundedNode(canvas, RectF(x + d(1f), y + d(8f), x + plot.width() / 3 - d(1f), y + plot.height() / 2 - d(1f)),
                name, if (index == shown.baseIndex) color else ColorUtils.setAlphaComponent(foreground, 0x65), index == shown.baseIndex)
        }
        footer(canvas, if (shown.deep) "深低音同相注入 · 参数不暗改算法" else "原预设是基础 · 专业数值为相对修正")
    }
    private fun drawGainWave(canvas: Canvas): Unit {
        caption(canvas, "低频处理支路 ×" + fmt(bassGain(), 2) + " · 相对幅度示意")
        val box: RectF = RectF(plot.left, plot.top + d(9f), plot.right, plot.bottom - d(7f))
        fillPaint.shader = LinearGradient(plot.left, plot.top, plot.left, plot.bottom,
            ColorUtils.setAlphaComponent(color, 0x30), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, d(8f), d(8f), fillPaint); fillPaint.shader = null
        val target: Double = ln(1 + bassGain()) / ln(85.0)
        val original: Double = ln(2 + .2 * shown.settings.strength) / ln(85.0)
        wave(canvas, box, secondary) { t: Double -> original * sin(4 * PI * t) }
        wave(canvas, box, color) { t: Double -> target * sin(4 * PI * t) }
        text(canvas, "基础", plot.left, plot.top + d(3f), plot.width() * .5f, secondary)
        text(canvas, "修正后", plot.right, plot.bottom + d(10f), plot.width() * .5f, color, Paint.Align.RIGHT)
        footer(canvas, "幅度经压缩示意 · 不代表实测总音量")
    }
    private fun drawBranchRoutes(canvas: Canvas): Unit {
        caption(canvas, "支路比例 · 低 " + fmt(lowRatio() * 100, 0) + "% / 高 " + fmt(highRatio() * 100, 0) + "%")
        val labels: List<String> = listOf("低段", "高段")
        val values: List<Double> = listOf(lowRatio(), highRatio())
        for (index in 0..1) {
            val y: Float = py(if (index == 0) .73 else .27)
            val tint: Int = if (index == 0) color else secondary
            roundedNode(canvas, RectF(plot.left, y - d(10f), px(.38), y + d(10f)), labels[index], tint, true)
            val thickness: Float = linePaint.strokeWidth
            linePaint.strokeWidth = d((.7 + 3 * values[index]).toFloat())
            line(canvas, px(.42), y, px(.90), y, tint)
            linePaint.strokeWidth = thickness
        }
        line(canvas, px(.90), py(.73), px(.90), py(.27), third)
        text(canvas, "合成", px(.90), py(.48), plot.width() * .4f, third, Paint.Align.RIGHT)
        footer(canvas, "比例不是压缩比 · 合成仍受滤波相位影响")
    }
    private fun drawDeepCloud(canvas: Canvas): Unit {
        caption(canvas, "共通低音 " + shown.deepHz + "Hz · Q " + fmt(quality(), 2))
        val center: Float = px(.50)
        val centerY: Float = py(.52)
        val radius: Float = min(plot.width(), plot.height()) * .28f
        for (index in 3 downTo 1) {
            val halfWidth: Float = min(plot.width() * .46f, radius * (1 + .22f * index) / sqrt(quality().toFloat()))
            fillPaint.color = ColorUtils.setAlphaComponent(color, (10 + injection() * 10).toInt().coerceIn(0, 80))
            canvas.drawOval(RectF(center - halfWidth, centerY - radius * index / 3,
                center + halfWidth, centerY + radius * index / 3), fillPaint)
        }
        roundedNode(canvas, RectF(plot.left, py(.94), px(.36), py(.73)), "L + R", secondary, true)
        line(canvas, px(.36), py(.83), center, centerY - radius, secondary)
        text(canvas, "低通", center, centerY + d(3f), plot.width() * .60f, color, Paint.Align.CENTER)
        for (index in 0..1) {
            val x: Float = px(if (index == 0) .15 else .85)
            line(canvas, center, centerY + radius, x, py(.12), color)
            dot(canvas, x, py(.12), color, d(4f))
            text(canvas, if (index == 0) "L" else "R", x, plot.bottom + d(9f), plot.width() * .3f, color, Paint.Align.CENTER)
        }
        footer(canvas, "注入 " + fmt(injection() * 100, 0) + "% · 宽窄示意共振，非声源距离")
    }
    private fun drawCoverage(canvas: Canvas): Unit {
        caption(canvas, "主分带与支路分界 · 非理想砖墙分频")
        val limits: List<Int> = listOf(20, shown.yLow, shown.yHigh, shown.xHz, 20000).sorted()
        limits.zipWithNext().forEachIndexed { index: Int, pair: Pair<Int, Int> ->
            val left: Float = fx(pair.first.toDouble()); val right: Float = fx(pair.second.toDouble())
            fillPaint.color = ColorUtils.setAlphaComponent(if (index % 2 == 0) color else secondary, 0x2a)
            canvas.drawRoundRect(RectF(left, py(.82), max(left + d(.5f), right), py(.21)), d(3f), d(3f), fillPaint)
        }
        for (hz in listOf(shown.yLow, shown.yHigh, shown.xHz)) {
            val x: Float = fx(hz.toDouble())
            line(canvas, x, py(.85), x, py(.10), color, true)
        }
        text(canvas, "下 " + shown.yLow + " / 上 " + shown.yHigh + " Hz", plot.left, plot.bottom + d(10f), plot.width())
        footer(canvas, "主基准 " + shown.xHz + " Hz · 上基准在重组中抵消")
    }
    private fun drawColorCoverage(canvas: Canvas): Unit {
        caption(canvas, "滤波只改变低频增强支路")
        line(canvas, plot.left, py(.84), plot.right, py(.84), secondary)
        text(canvas, "未受回收的主干保留", plot.left, py(.84) - d(5f), plot.width(), secondary)
        path.rewind(); path.moveTo(plot.left, py(.12))
        shown.frequencies.forEachIndexed { index: Int, frequency: Double ->
            path.lineTo(fx(frequency), py(.12 + .48 * 10.0.pow(shown.branchFilterDb[index] / 20).coerceIn(0.0, 1.0)))
        }
        path.lineTo(fx(shown.frequencies.last()), py(.12)); path.close()
        fillPaint.shader = LinearGradient(plot.left, plot.top, plot.right, plot.bottom,
            ColorUtils.setAlphaComponent(color, 0x65), ColorUtils.setAlphaComponent(third, 0x25), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fillPaint); fillPaint.shader = null
        footer(canvas, "HP " + shown.hpHz + " / LP " + shown.lpHz + " Hz · 0 为旁路")
    }
    private fun drawDetectorRoutes(canvas: Canvas): Unit {
        caption(canvas, if (shown.detectorIsRms) "左右均方平均 → 20 ms RMS" else "左右瞬时峰值取较大")
        roundedNode(canvas, RectF(plot.left, py(.93), px(.35), py(.70)), "L", secondary, true)
        roundedNode(canvas, RectF(px(.65), py(.93), plot.right, py(.70)), "R", secondary, true)
        line(canvas, px(.18), py(.70), px(.50), py(.51), color)
        line(canvas, px(.82), py(.70), px(.50), py(.51), color)
        roundedNode(canvas, RectF(px(.12), py(.60), px(.88), py(.35)), shown.detectorLowHz.toString() + "–" + shown.detectorHighHz + " Hz", color, true)
        line(canvas, px(.50), py(.35), px(.50), py(.13), third)
        roundedNode(canvas, RectF(px(.12), py(.25), px(.88), py(.01)), if (shown.detectorIsRms) "20 ms 平滑能量" else "当下峰值", third, true)
        footer(canvas, "检测频段不直接删减主干 · 仅控制增强回收")
    }
    private fun drawRecoveryRoutes(canvas: Canvas): Unit {
        caption(canvas, "只回收增强增量 · 最大 −" + fmt(shown.maxReductionDb) + " dB")
        line(canvas, plot.left, py(.78), plot.right, py(.78), secondary)
        text(canvas, "主干路径保持", plot.left, py(.78) - d(6f), plot.width(), secondary)
        val gain: Double = 10.0.pow(-shown.maxReductionDb / 20)
        val start: Float = px(.08); val end: Float = px(.92)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x35)
        path.rewind(); path.moveTo(start, py(.52)); path.lineTo(end, py(.29 + .23 * gain)); path.lineTo(end, py(.29 - .23 * gain)); path.lineTo(start, py(.06)); path.close()
        canvas.drawPath(path, fillPaint)
        text(canvas, "×1.000", start, py(.52) - d(3f), plot.width() * .5f, color)
        text(canvas, "×" + fmt(gain, 3), end, py(.09), plot.width() * .55f, color, Paint.Align.RIGHT)
        footer(canvas, "与主干复数相加后 · 每频点未必都下降")
    }
    private fun drawRecoveryTiming(canvas: Canvas): Unit {
        caption(canvas, "攻击加深回收 · 释放恢复原增强量")
        grid(canvas, "0 dB", "−18", "0 ms", "10 s · 对数")
        trace(canvas, color) { x: Double -> 1 - shown.maxReductionDb * (1 - exp(-timeAt(x) / shown.attackMs)) / 18 }
        trace(canvas, third) { x: Double -> 1 - shown.maxReductionDb * exp(-timeAt(x) / shown.releaseMs) / 18 }
        text(canvas, fmt(shown.attackMs) + " ms", plot.left + d(2f), plot.top + d(10f), plot.width() * .55f, color)
        text(canvas, fmt(shown.releaseMs, 0) + " ms", plot.right - d(2f), plot.bottom - d(3f), plot.width() * .55f, third, Paint.Align.RIGHT)
        footer(canvas, "时间是指数包络常数 · 不等于瞬间全到位")
    }
    private fun drawMixRoutes(canvas: Canvas): Unit {
        caption(canvas, "原声对齐 " + shown.latencySamples + " 样本 · 效果 " + fmt(shown.mix * 100, 0) + "%")
        val dryY: Float = py(.77); val wetY: Float = py(.25)
        line(canvas, px(.06), dryY, px(.88), dryY, secondary, shown.mix >= 1)
        line(canvas, px(.06), wetY, px(.88), wetY, color, shown.mix <= 0)
        roundedNode(canvas, RectF(px(.16), dryY - d(10f), px(.73), dryY + d(10f)), "延迟对齐输入", secondary, shown.mix < 1)
        roundedNode(canvas, RectF(px(.16), wetY - d(10f), px(.73), wetY + d(10f)), "完整动态音色", color, shown.mix > 0)
        line(canvas, px(.06), dryY, px(.06), wetY, foreground)
        line(canvas, px(.88), dryY, px(.88), wetY, third)
        dot(canvas, px(.88), py(.50), third, d(5f))
        text(canvas, "+", px(.88), py(.50) + d(3f), d(10f), Color.WHITE, Paint.Align.CENTER)
        footer(canvas, "0% 是对齐输入 · 与频率相位补偿不同")
    }
    private fun drawLevelWave(canvas: Canvas): Unit {
        caption(canvas, "输出 " + signed(20 * log10(shown.outputGain)) + " dB · 固定衰减")
        val box: RectF = RectF(plot.left, plot.top + d(9f), plot.right, plot.bottom - d(8f))
        fillPaint.shader = LinearGradient(plot.left, plot.top, plot.left, plot.bottom,
            ColorUtils.setAlphaComponent(color, 0x25), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, d(8f), d(8f), fillPaint); fillPaint.shader = null
        wave(canvas, box, secondary) { t: Double -> sin(4 * PI * t) }
        wave(canvas, box, color) { t: Double -> shown.outputGain * sin(4 * PI * t) }
        footer(canvas, "完整混合输出 · 幅度 ×" + fmt(shown.outputGain, 3))
    }
    private fun drawPhase(canvas: Canvas): Unit {
        caption(canvas, "合成相位 · 无回收 / 最大回收")
        grid(canvas, "+180°", "−180", "20 Hz", "20 kHz")
        for (index in 0..1) {
            val phases: DoubleArray = if (index == 0) shownPhase else shownRecoveredPhase
            path.rewind(); var connected: Boolean = false; var previous: Double = 0.0
            phases.forEachIndexed { n: Int, degrees: Double ->
                if (!degrees.isFinite()) { connected = false; return@forEachIndexed }
                val x: Float = fx(shown.frequencies[n]); val y: Float = py((degrees + 180) / 360)
                if (!connected || abs(degrees - previous) > 180) path.moveTo(x, y) else path.lineTo(x, y)
                connected = true; previous = degrees
            }
            stroke(canvas, if (index == 0) color else third, index == 1)
        }
        footer(canvas, if (shown.deep) "同相 L=R 小信号 · 包含注入相位" else "包含 3 样本重构与滤波相位 · 非实测")
    }
    private fun drawMonitorRoutes(canvas: Canvas): Unit {
        caption(canvas, "检测 / 增强支路 / 正常处理 / 试听输出")
        val labels: List<String> = listOf("输入 → 检测频段", "增强支路 → 回收", "主干 + 支路 → 混合", "当前试听 → 输出")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.86 - index * .25)
            roundedNode(canvas, RectF(plot.left, y - d(7f), plot.right, y + d(7f)), label,
                if (index == 1) third else color, true)
        }
        footer(canvas, "支路 RMS 不等于总输出 · 读数见动态图")
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
        footer(canvas, "实际预热/测量见原生状态 · 不代表等响度")
    }
    private fun drawListening(canvas: Canvas): Unit {
        val selected: Int = snapshot.audition.coerceIn(0, 8)
        caption(canvas, "当前：" + routeLabels[selected])
        val labels: List<String> = listOf("对齐原声", "完整处理", "快照 A/B", "增强 / 检测")
        val choice: Int = when (selected) { 1 -> 0; 4, 5, 6 -> 2; 7, 8 -> 3; else -> 1 }
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.85 - index * .23)
            val tint: Int = if (index == choice) color else ColorUtils.setAlphaComponent(foreground, 0x55)
            roundedNode(canvas, RectF(plot.left, y - d(7f), px(.62), y + d(7f)), label, tint, index == choice)
            path.rewind(); path.moveTo(px(.62), y); path.cubicTo(px(.78), y, px(.70), py(.51), px(.88), py(.51))
            stroke(canvas, tint, index != choice)
        }
        val ear: RectF = RectF(px(.83), py(.69), plot.right, py(.32))
        linePaint.color = color; canvas.drawArc(ear, -85f, 280f, false, linePaint)
        footer(canvas, if (selected == 7) "增强支路是原生独听测点 · 不等于效果差分" else if (selected == 8) "检测独听取输入选频 · 不等于正常处理" else "静图显示保存参数 · 实际试听见动态图")
    }
    private fun details(): String {
        val s: P.Settings = snapshot.settings
        val status: String = if (enabled) "显示页面保存配置的静态目标。" else snapshot.reason(focus, key) + "；灰色目标不会更改实际预设或开启声音处理。"
        val explanation: String = when (focus) {
            DynamicChartFocus.BASE -> "基础预设编号 " + (s.base + 1) + "，原强度 " + s.strength + "。原六预设始终是基础，专业修正叠加其上；原强度为 0–100 刻度，低频基底倍率为 1+0.2×强度，不能误解成百分比混合。专业核心开启时接管原强度编辑，以原保存值作基础，关闭后恢复该杆。\n预设索引 4 使用深低音：L+R 经共用低通后同相注入左右。其余预设是分带重组；分带原声重构固定延迟 3 个采样，深低音直接路径为 0。左右两侧的静图是保存配置小信号参考，不是播放中实际音频或房间声学测量。深低音曲线以 L=R 同相等幅输入为前提，不能拿它代表任意左右声道组合。"
            DynamicChartFocus.GAIN -> "保存低频整体增益修正 " + signed(s.bassDelta / 10.0) + " dB。只适用于分带预设，实际低频支路倍率为 原强度基底倍率×10^(修正 dB/20)，再按低/高段比例重组。此项不等于整个模块的输出增益，也不是压缩阈值。\n虚线为当前原预设基础，实线为保存修正后的合成参考。当前若为深低音，原生此项不生效；灰色模型仅用一个分带基础演示该保存修正，绝不暗中切换实际预设。"
            DynamicChartFocus.BRANCH -> "低段比例修正 " + signed(s.lowGainDelta / 10.0) + " 个百分点；高段比例修正 " + signed(s.highGainDelta / 10.0) + " 个百分点。数值加在当前原预设比例上，最低限制为 0。这里是百分点，不是 dB、压缩比或真实频段能量。\n分带预设的原基础低/高比例各不相同；切换基础保留修正。两支路与主干通过滤波相位重新合成，因此比例上升不保证所有频点都变大。深低音预设不使用这些比例，灰色图仅为保存参数预览，不更改音频算法。"
            DynamicChartFocus.DEEP -> "保存深低音注入修正 " + signed(s.injectDelta / 10.0) + " 个百分点，Q 修正 " + signed(s.qDelta / 100.0, 2) + "，频率修正 " + signed(s.deepFreqDelta.toDouble(), 0) + " Hz。仅深低音预设有效。\n注入量以 100% 为原基础，加偏移后最低 0；原 Q 由原强度推导，再加修正并限制 0.1–4。频率以 55 Hz 为基础，由音色组决定是否加偏移。注入/Q 归核心开关，频率归音色开关，各自禁用只显示保存目标，图纸不会替用户开启另一组。\nL+R 经过共用二阶低通后注入两侧；左图为 L=R 同相等幅的小信号参考，不能当作空间距离、独立声道增益或真实低频能量。其它基础预设不执行此算法，灰色预览使用显示副本，实际预设保持不变。"
            DynamicChartFocus.CROSSOVER -> "保存主分带基准偏移 " + signed(s.xDelta.toDouble(), 0) + " Hz，支路下/上分界偏移 " + signed(s.lowFreqDelta.toDouble(), 0) + " / " + signed(s.highFreqDelta.toDouble(), 0) + " Hz。加在当前原预设频率上后由驱动限幅并保证顺序；切换预设不清除保存偏移。\n参数来自原 PolesFilter 基准，不是标准 −3 dB 截止点或理想砖墙。主分带上基准在支路重新相加时抵消，所以没有为它新增无效滑杆。左图分开说明增强与其余路径，右图给参数分区示意，实际滤波仍是平滑且有相位的响应。深低音模式不采用这三个分带修正，灰色保存预览不改变实际模式。"
            DynamicChartFocus.FILTER -> "增强支路低切保存 " + s.hp + " Hz，高切保存 " + s.lp + " Hz；0 Hz 为旁路。专业音色组开启后使用二阶 Butterworth 高/低通，作用于低频增强处理支路，不是整路主干滤波。\n滤波产生的幅度和相位都参与重新合成；不能把单独增强包络与主干 dB 直接相加。低切/高切受采样率安全限幅；存储值与实际值可能不同，实际音频情况见原生状态和实时图。"
            DynamicChartFocus.DETECTOR -> "检测方式保存为" + (if (s.detector == 1) "20 ms RMS" else "双声道峰值") + "；检测下/上限 " + s.detectorHP + " / " + s.detectorLP + " Hz。选频来自模块输入，左/右联动：RMS 将两声道均方平均后以 20 ms 平滑；峰值取左右瞬时绝对值较大者。\n检测频段是控制信号，不会直接删减主干。只有电平自适应开启且最大回收不为 0 时才驱动增强回收；静图展示保存的选频包络，不伪造实际检测电平。原生检测独听是第 8 号试听路由，和增强支路独听不同。"
            DynamicChartFocus.DYNAMICS -> "回收阈值 " + fmt(s.threshold / 10.0) + " dBFS，软拐点宽度 " + fmt(s.knee / 10.0) + " dB，最大增强回收 " + fmt(s.maxReduction / 10.0) + " dB。\n检测电平超出阈值后，所需回收为超出量；软拐点在阈值±半宽内按平方过渡，随后封顶最大回收。本算法没有可调压缩比参数。图中纵轴取负增益：0 dB 为不回收，向下为增强支路衰减。\n实际仅将增强分量乘 10^(−回收 dB/20)，其余主干不整体压缩；分带或深低音路径随后按复数相位重新合成，增强回收不保证每个频点的最终幅度都下降。最大回收 0 dB 时自适应没有音频作用。此页不是动态压扩器，也不是多频段 AGC。"
            DynamicChartFocus.TIMING -> "保存攻击 " + fmt(s.attack / 10.0) + " ms，释放 " + s.release + " ms。所需回收比当前更深时用攻击，较浅时用释放；系数分别为 exp(−1/(采样率×时间))，作用于回收 dB 状态。\n紫线表示从 0 逐步加深到最大回收，棕线表示从最大衰减逐步恢复到 0 dB；时间轴固定为 0–10 秒的对数式展开。时间常数处约完成 63.2% 的变化，不是毫秒一到就完成全部。RMS 检测还另有固定 20 ms 能量平滑，不能与攻击时间混作一项。两线为固定目标阶跃参考，不是实际音乐 GR 历史。"
            DynamicChartFocus.MIX -> "保存效果比例 " + fmt(s.mix / 10.0) + "% 。输出为 固定延迟对齐输入×(1−效果比例)+完整动态音色×效果比例，再乘输出增益。分带对齐 3 个采样，深低音 0；在 A/B 或切换过渡时共同对齐可能采用其它内核所需延迟，以下方实际状态为准。\n0% 为对齐模块输入，100% 为完整处理。这只补固定重构时差，不能消除频率相关滤波相位。当前效果 PCM 已经含效果比例；它不是独立的纯低频增强支路。"
            DynamicChartFocus.LEVEL -> "模块输出增益保存 " + signed(s.output / 10.0) + " dB，线性系数 " + fmt(10.0.pow(s.output / 200.0), 4) + "。它对完整混合输出作固定衰减；0 dB 不额外缩放，关闭分组也恢复 0 dB。\n它与只作用增强支路的自适应回收位置不同。右图仅说明波形的固定幅度缩放，不是实际 RMS、真峰值或耳内声压。"
            DynamicChartFocus.RESPONSE -> "静态幅度与相位按当前保存预设、修正、滤波、混合、输出和采样率计算，分别比较不回收与当前已启用自适应的最大回收。左图使用现有参数响应模型，右图按同样的复数路径计算合成相位；±180°跳变处断开，极小幅度零点不连接伪相位。\n深低音预测前提是 L=R 同相等幅输入。分带包含 3 样本固定重构时差和滤波相位，深低音直接路径是 0 样本。增强衰减后与主干相加可能减轻原来的相消，某些频点反而升高，不能强行将最大回收曲线压在正常曲线下面。\n原生“显示响应预测”只控制旧驱动响应监测，不改变声音参数；顶部静态参数模型仍可读取保存值。动态图的原子驱动响应为当前实际参数预测，仍不是音乐实测 FFT 或房间测量。"
            DynamicChartFocus.MONITOR -> "原生实时电平开关保存为" + (if (s.meters) "开启" else "关闭") + "。输入/输出峰值、RMS、超限帧数、检测电平、增强支路电平与实际回收量有不同测点。\n增强支路电平是回收后的原生处理支路；它不等于总输出，也不等同于完整效果减去原声。正常处理 PCM 含完整处理与当前效果比例，实际试听输出还受原声、A/B、增强或检测独听影响。没有独立支路 PCM 时，普通输入/处理/输出频谱不能标成纯支路 FFT。静图只说明测点，真实读数由动态图和原生长说明显示。"
            DynamicChartFocus.MATCH -> "A " + (if (snapshot.savedA) "已保存" else "未保存") + "，B " + (if (snapshot.savedB) "已保存" else "未保存") + "；匹配" + (if (snapshot.matching) "开启" else "关闭") + "。\n同一输入并行处理 A/B，预热至少 0.5 秒；开启自适应时还考虑三倍释放时间，并取各快照状态所需的最大预热，再测量 2 秒 RMS。静图画流程，不冒充未知的两个私有快照实际预热长度。\n有效时以原声、A、B 三路最低能量为目标，只作固定衰减，不持续追踪、不提升音量；等 RMS 电平不等于心理声学等响度。真实阶段、剩余时间与衰减以下方驱动状态为准。"
            DynamicChartFocus.AUDITION -> "当前：“" + routeLabels[snapshot.audition.coerceIn(0, 8)] + "”。完整保留九种原生路由：正常、对齐输入原声、完整处理后效果、效果差分、快照 A、快照 B、A/B 测量、增强支路独听、检测频段独听。\n第 7 路输出原生当前低频处理支路，第 8 路输出输入检测带；两者不是同一个信号。效果差分包含频率相位与原声重构的差异，不一定等于纯增强。静图始终展示页面保存参数；A/B 的实际音频输出由动态图和原生状态显示，图纸不读取或改动私有快照设置。"
        }
        val preview: String = if (snapshot.applicable(focus)) "" else "\n\n当前模式不使用本组；图中只用显示副本解释保存修正，原生预设没有切换。"
        return (if (illustration) focus.rightTitle else focus.leftTitle) + "\n\n" + status + "\n\n" + explanation + preview +
            "\n\n" + (if (snapshot.actualRate) "实际 " else "参考 ") + snapshot.rate + " Hz；图纸只读，不写入参数、预设或 A/B。"
    }
    override fun onDetachedFromWindow(): Unit {
        generation++; DynamicStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}


