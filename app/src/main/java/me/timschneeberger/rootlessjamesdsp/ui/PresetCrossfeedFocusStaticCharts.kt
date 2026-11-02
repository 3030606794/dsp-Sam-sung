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
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedController
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** Observers of saved targets; no parameter writes, listening actions or private A/B snapshots. */
internal class PresetCrossfeedParameterChart(context: Context) : PresetCrossfeedFocusStaticBase(context, false)
internal class PresetCrossfeedIllustrationChart(context: Context) : PresetCrossfeedFocusStaticBase(context, true)

private data class PresetCrossfeedFocusSnapshot(
    val settings: P.Settings, val rate: Int, val effectOn: Boolean,
    val supported: Boolean, val advancedSupported: Boolean,
    val audition: Int, val matching: Boolean, val matchingReady: Boolean, val savedA: Boolean, val savedB: Boolean
) {
    val bs2b: Boolean get() = settings.base < 2
    val highHz: Double get() = min(20000.0, rate * .499)
    fun value(control: BassParameters.Control): Int = when (control.key) {
        P.cutoff.key -> settings.cutoff
        P.attenuation.key -> settings.attenuation
        P.mix.key -> settings.mix
        P.delay.key -> settings.delay
        P.output.key -> settings.output
        P.spatial.key -> settings.spatial
        P.leftToRight.key -> settings.advanced.leftToRight
        P.rightToLeft.key -> settings.advanced.rightToLeft
        P.filterCutoff.key -> settings.advanced.filterHz
        P.center.key -> settings.advanced.center
        else -> control.default
    }
    fun number(control: BassParameters.Control): Double = value(control).toDouble() / control.divisor
    fun active(focus: PresetCrossfeedChartFocus, key: String): Boolean {
        if (!effectOn || !supported) return false
        if (focus == PresetCrossfeedChartFocus.RESPONSE && key in setOf(P.BASE, P.PROFESSIONAL, "crossfeed6_presets")) return true
        if (!settings.professional) return false
        return when (focus) {
            PresetCrossfeedChartFocus.RESPONSE -> bs2b
            PresetCrossfeedChartFocus.SPACE -> !bs2b
            PresetCrossfeedChartFocus.PATH -> advancedSupported && settings.advanced.pathOn &&
                (key != P.rightToLeft.key || !settings.advanced.linked)
            PresetCrossfeedChartFocus.FILTER -> advancedSupported && settings.advanced.filterOn
            PresetCrossfeedChartFocus.CENTER -> advancedSupported && settings.advanced.centerOn
            else -> true
        }
    }
    fun target(focus: PresetCrossfeedChartFocus, key: String): P.Settings {
        var extra: P.Advanced = if (advancedSupported) settings.advanced else P.Advanced()
        // Gray previews retain inactive saved control geometry without changing the real switch.
        if (focus == PresetCrossfeedChartFocus.PATH) extra = settings.advanced.copy(pathOn = true,
            linked = if (key == P.rightToLeft.key) false else settings.advanced.linked)
        if (focus == PresetCrossfeedChartFocus.FILTER) extra = settings.advanced.copy(filterOn = true)
        if (focus == PresetCrossfeedChartFocus.CENTER) extra = settings.advanced.copy(centerOn = true)
        val originalSelected: Boolean = focus == PresetCrossfeedChartFocus.RESPONSE &&
            key in setOf(P.BASE, P.PROFESSIONAL, "crossfeed6_presets")
        return settings.copy(professional = if (originalSelected) settings.professional && supported else true, advanced = extra)
    }
    fun inactiveReason(focus: PresetCrossfeedChartFocus, key: String): String = when {
        !effectOn -> "模块关闭 · 保存目标预览"
        !supported -> "驱动不支持精调 · 参数预览"
        !settings.professional -> "专业精调关闭 · 参数预览"
        focus == PresetCrossfeedChartFocus.RESPONSE && !bs2b -> "空间预设不使用 BS2B 参数"
        focus == PresetCrossfeedChartFocus.SPACE && bs2b -> "BS2B 不使用空间支路电平"
        focus in setOf(PresetCrossfeedChartFocus.PATH, PresetCrossfeedChartFocus.FILTER, PresetCrossfeedChartFocus.CENTER) && !advancedSupported -> "进阶驱动未支持 · 参数预览"
        focus == PresetCrossfeedChartFocus.PATH && !settings.advanced.pathOn -> "左右路径精调关闭 · 预览"
        focus == PresetCrossfeedChartFocus.PATH && key == P.rightToLeft.key && settings.advanced.linked -> "左右联动中 · 右侧保存值预览"
        focus == PresetCrossfeedChartFocus.FILTER && !settings.advanced.filterOn -> "支路低通关闭 · 参数预览"
        focus == PresetCrossfeedChartFocus.CENTER && !settings.advanced.centerOn -> "中央补偿关闭 · 参数预览"
        else -> "保存目标预览"
    }
}

private data class PresetCrossfeedStaticRequest(val settings: P.Settings, val rate: Int, val phase: Boolean)
private data class PresetCrossfeedStaticResult(
    val x: DoubleArray, val values: List<DoubleArray>, val phase: Boolean, val failed: Boolean = false
)

/** One running calculation per owner, one latest waiting target; same-focus drawing never flashes empty. */
private object PresetCrossfeedStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "preset-crossfeed-focus-static").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(10, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private data class Task(val request: PresetCrossfeedStaticRequest, val callback: (PresetCrossfeedStaticResult) -> Unit)
    private class Owner {
        var running: PresetCrossfeedStaticRequest? = null
        var waiting: Task? = null
        var future: Future<*>? = null
    }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<PresetCrossfeedStaticRequest, PresetCrossfeedStaticResult> =
        object : LinkedHashMap<PresetCrossfeedStaticRequest, PresetCrossfeedStaticResult>(12, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PresetCrossfeedStaticRequest, PresetCrossfeedStaticResult>?): Boolean = size > 12
        }
    @Synchronized fun release(owner: Any): Unit {
        owners.remove(owner)?.let { state -> state.waiting = null; state.future?.cancel(true); state.future = null }
    }
    @Synchronized fun request(owner: Any, request: PresetCrossfeedStaticRequest, callback: (PresetCrossfeedStaticResult) -> Unit): Unit {
        val state: Owner = owners.getOrPut(owner) { Owner() }
        if (state.running == request) { state.waiting = null; return }
        state.waiting = Task(request, callback)
        if (state.future == null) start(owner, state)
    }
    @Synchronized private fun start(owner: Any, state: Owner): Unit {
        val task: Task = state.waiting ?: return
        state.waiting = null; state.running = task.request
        state.future = executor.submit {
            fun cancel(): Unit { if (Thread.currentThread().isInterrupted) throw CancellationException() }
            val result: PresetCrossfeedStaticResult = try {
                cancel()
                val existing: PresetCrossfeedStaticResult? = synchronized(this) { cache[task.request] }
                existing ?: run {
                    val values: Map<String, Any> = task.request.settings.values() + (P.ENABLED to true)
                    val model: PresetCrossfeedDashboardModel = PresetCrossfeedDashboardModel.from(values, task.request.rate, true)
                    val curve = model.frequency(::cancel)
                    val rows: List<DoubleArray> = if (task.request.phase) model.coherentPhase(::cancel) else curve.decibels
                    cancel()
                    PresetCrossfeedStaticResult(curve.frequencies, rows, task.request.phase)
                }
            } catch (_: CancellationException) { return@submit
            } catch (_: InterruptedException) { return@submit
            } catch (_: Exception) { PresetCrossfeedStaticResult(doubleArrayOf(), emptyList(), task.request.phase, true) }
            val deliver: Boolean = synchronized(this) {
                if (owners[owner] !== state) false else {
                    if (!result.failed) cache[task.request] = result
                    state.future = null; state.running = null
                    if (state.waiting != null) start(owner, state)
                    true
                }
            }
            if (deliver) main.post { task.callback(result) }
        }
    }
}

internal open class PresetCrossfeedFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
    private val density: Float = resources.displayMetrics.density
    private fun d(value: Float): Float = value * density
    private val foreground: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val teal: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(109, 212, 206) else Color.rgb(15, 121, 116)
    private val amber: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(246, 185, 105) else Color.rgb(162, 88, 20)
    private val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(9f * resources.displayMetrics.scaledDensity, d(11f)) }
    private val linePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(1.5f) }
    private val fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(.65f); color = ColorUtils.setAlphaComponent(foreground, 0x2a) }
    private val plot: RectF = RectF()
    private val path: Path = Path()
    private var settings: P.Settings = P.baseSettings(5)
    private var rate: Int = 48000
    private var model: PresetCrossfeedFocusSnapshot = snapshot()
    private var focus: PresetCrossfeedChartFocus = PresetCrossfeedChartFocus.RESPONSE
    private var key: String = P.BASE
    private var active: Boolean = true
    private var request: PresetCrossfeedStaticRequest? = null
    private var response: PresetCrossfeedStaticResult? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = model.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x77)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x4b)
    private fun snapshot(): PresetCrossfeedFocusSnapshot = PresetCrossfeedFocusSnapshot(settings, rate,
        PresetCrossfeedController.preferences(context).getBoolean(P.ENABLED, false),
        PresetCrossfeedController.supported(context), PresetCrossfeedController.advancedSupported(context),
        PresetCrossfeedController.audition, PresetCrossfeedController.match, PresetCrossfeedController.matchingReady(),
        PresetCrossfeedController.hasComparison(0), PresetCrossfeedController.hasComparison(1))
    init {
        PresetCrossfeedDashboardModel.initialize(context)
        isLongClickable = true
        setOnLongClickListener {
            detailDialog?.dismiss()
            detailDialog = GlassAlertDialogBuilder(context).setTitle(if (illustration) focus.rightTitle else focus.leftTitle)
                .setMessage(details()).setPositiveButton(android.R.string.ok, null).show()
            true
        }
        updateDescription()
    }
    fun setConfiguration(settings: P.Settings, rate: Int): Unit {
        this.settings = settings.takeIf { it.valid() } ?: P.baseSettings(settings.base.coerceIn(0, 5))
        this.rate = rate.takeIf { it in 8000..768000 } ?: 48000
        val next: PresetCrossfeedFocusSnapshot = snapshot()
        if (next == model) return
        model = next
        updateDescription(); updateResponse()
        if (active) invalidate()
    }
    fun setFocus(focus: PresetCrossfeedChartFocus, key: String): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; PresetCrossfeedStaticWork.release(this); request = null; response = null }
        this.focus = focus; this.key = key
        updateDescription(); updateResponse()
        if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) {
            generation++; PresetCrossfeedStaticWork.release(this); request = null
            detailDialog?.dismiss(); detailDialog = null
        } else { updateResponse(); invalidate() }
    }
    private fun updateResponse(): Unit {
        if (illustration || !active) return
        if (focus in setOf(PresetCrossfeedChartFocus.MATCH, PresetCrossfeedChartFocus.AUDITION)) {
            PresetCrossfeedStaticWork.release(this); request = null; return
        }
        val next: PresetCrossfeedStaticRequest = PresetCrossfeedStaticRequest(model.target(focus, key), rate, focus == PresetCrossfeedChartFocus.DELAY)
        if (request == next) return // Audition, meter and capability-only refreshes never restart unchanged math.
        request = next
        val token: Long = generation
        PresetCrossfeedStaticWork.request(this, next) { result ->
            if (active && token == generation && result.phase == (focus == PresetCrossfeedChartFocus.DELAY)) {
                // An intermediate same-focus result remains useful while the latest drag target is computing.
                if (!result.failed || response == null) response = result
                invalidate()
            }
        }
    }
    private fun updateDescription(): Unit { contentDescription = details() }
    private fun n(control: BassParameters.Control): Double = model.number(control)
    private fun fmt(value: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%." + decimals + "f", value)
    private fun signed(value: Double): String = String.format(Locale.ROOT, "%+.1f", value)
    private fun px(value: Double): Float = plot.left + plot.width() * value.coerceIn(0.0, 1.0).toFloat()
    private fun py(value: Double): Float = plot.bottom - plot.height() * value.coerceIn(0.0, 1.0).toFloat()
    private fun fx(frequency: Double): Float = px(ln(frequency.coerceIn(20.0, 20000.0) / 20.0) / ln(1000.0))
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, space: Float,
        tint: Int = foreground, align: Paint.Align = Paint.Align.LEFT): Unit {
        if (space <= 0) return
        textPaint.color = tint; textPaint.textAlign = align
        val count: Int = textPaint.breakText(value, true, space, null)
        canvas.drawText(if (count == value.length) value else value.take((count - 1).coerceAtLeast(0)) + "…", x, y, textPaint)
    }
    private fun caption(canvas: Canvas, value: String): Unit = text(canvas, value, d(3f), d(11f), width - d(6f), color)
    private fun footer(canvas: Canvas, value: String): Unit = text(canvas,
        if (enabled) value else model.inactiveReason(focus, key), d(3f), height - d(3f), width - d(6f), ColorUtils.setAlphaComponent(foreground, 0xa0))
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
        linePaint.color = tint
        linePaint.pathEffect = if (dashed || !enabled) DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f) else null
        canvas.save(); canvas.clipRect(plot); canvas.drawPath(path, linePaint); canvas.restore()
        linePaint.pathEffect = null
    }
    private fun trace(canvas: Canvas, tint: Int = color, dashed: Boolean = false, value: (Double) -> Double): Unit {
        path.rewind(); var started: Boolean = false
        for (index in 0..128) {
            val x: Double = index / 128.0; val y: Double = value(x)
            if (!y.isFinite()) { started = false; continue }
            if (!started) { path.moveTo(px(x), py(y)); started = true } else path.lineTo(px(x), py(y))
        }
        stroke(canvas, tint, dashed)
    }
    private fun marker(canvas: Canvas, x: Float, tint: Int = color): Unit {
        linePaint.color = tint; linePaint.pathEffect = DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f)
        canvas.drawLine(x, plot.top, x, plot.bottom, linePaint); linePaint.pathEffect = null
    }
    private fun point(canvas: Canvas, x: Float, y: Float, tint: Int = color): Unit {
        fillPaint.color = tint; canvas.drawCircle(x, y, d(2.3f), fillPaint)
    }
    override fun onDraw(canvas: Canvas): Unit {
        super.onDraw(canvas)
        if (!active || width < d(55f) || height < d(55f)) return
        plot.set(d(28f), d(21f), width - d(7f), height - d(26f))
        if (illustration) drawIllustration(canvas) else drawParameters(canvas)
    }
    private fun drawParameters(canvas: Canvas): Unit = when (focus) {
        PresetCrossfeedChartFocus.MATCH -> drawMatch(canvas)
        PresetCrossfeedChartFocus.AUDITION -> drawAudition(canvas)
        else -> drawResponse(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        PresetCrossfeedChartFocus.RESPONSE, PresetCrossfeedChartFocus.SPACE -> drawFusion(canvas)
        PresetCrossfeedChartFocus.PATH -> drawPaths(canvas)
        PresetCrossfeedChartFocus.DELAY -> drawArrival(canvas)
        PresetCrossfeedChartFocus.FILTER -> drawFilter(canvas)
        PresetCrossfeedChartFocus.CENTER -> drawCenter(canvas)
        PresetCrossfeedChartFocus.LEVEL -> drawMix(canvas)
        PresetCrossfeedChartFocus.MATCH -> drawMeasurement(canvas)
        PresetCrossfeedChartFocus.AUDITION -> drawComparison(canvas)
    }
    private fun drawResponse(canvas: Canvas): Unit {
        val phase: Boolean = focus == PresetCrossfeedChartFocus.DELAY
        caption(canvas, if (phase) "同相信号相位 ° · 参数模型" else when (focus) {
            PresetCrossfeedChartFocus.CENTER -> "同相／反相响应 dB · 参数模型"
            PresetCrossfeedChartFocus.FILTER -> "交叉支路低通 dB · 参数模型"
            PresetCrossfeedChartFocus.PATH -> "左右交叉支路 dB · 参数模型"
            PresetCrossfeedChartFocus.SPACE -> "HRTF 原核与交叉支路 dB"
            else -> "直达／交叉响应 dB · 参数模型"
        })
        grid(canvas, if (phase) "+180°" else "+18 dB", if (phase) "−180°" else "−60", "20 Hz", "20k Hz")
        if (rate * .499 < 20000) {
            fillPaint.color = ColorUtils.setAlphaComponent(foreground, 0x0b)
            canvas.drawRect(fx(rate * .499), plot.top, plot.right, plot.bottom, fillPaint)
        }
        val current: PresetCrossfeedStaticResult? = response
        if (current != null && !current.failed) {
            val channels: List<Int> = when {
                phase -> listOf(0, 1)
                focus == PresetCrossfeedChartFocus.CENTER -> listOf(4, 5, 6, 7)
                else -> listOf(0, 1, 2, 3)
            }
            val palette: List<Int> = listOf(color, secondary, third, ColorUtils.setAlphaComponent(third, 0x92))
            channels.forEachIndexed channel@{ slot: Int, channel: Int ->
                val samples: DoubleArray = current.values.getOrNull(channel) ?: return@channel
                path.rewind(); var started: Boolean = false; var previous: Double = Double.NaN
                samples.forEachIndexed sample@{ index: Int, value: Double ->
                    if (!value.isFinite()) { started = false; previous = Double.NaN; return@sample }
                    val x: Float = fx(current.x[index]); val y: Float = py(if (phase) (value + 180) / 360 else (value + 60) / 78)
                    if (!started || (phase && previous.isFinite() && abs(value - previous) > 180)) path.moveTo(x, y) else path.lineTo(x, y)
                    started = true; previous = value
                }
                stroke(canvas, palette[slot], slot == 1 || slot == 3)
            }
        } else {
            text(canvas, if (current?.failed == true) "模型暂不可用" else "正在计算参数模型", plot.centerX(), plot.centerY(), plot.width(), secondary, Paint.Align.CENTER)
        }
        if (focus == PresetCrossfeedChartFocus.FILTER) marker(canvas, fx(min(n(P.filterCutoff), rate * .45)))
        if (focus == PresetCrossfeedChartFocus.RESPONSE && model.bs2b) marker(canvas, fx(n(P.cutoff)), secondary)
        footer(canvas, when {
            phase -> "同相 L／R · 分数延迟已计入"
            focus == PresetCrossfeedChartFocus.CENTER -> "紫／青 同相 · 金／虚线 反相"
            else -> "紫／青 直达 · 金／虚线 交叉"
        })
    }
    private fun drawMatch(canvas: Canvas): Unit {
        caption(canvas, "A 比 B 较响 · 固定衰减参考")
        grid(canvas, "0 dB", "−12", "相对差 0", "+12 dB")
        trace(canvas) { 1 - it }
        trace(canvas, secondary, true) { 1.0 }
        footer(canvas, if (model.matching) "A/B 已启用固定匹配" else "匹配未启用 · 不提升电平")
    }
    private fun box(canvas: Canvas, label: String, left: Double, top: Double, right: Double, bottom: Double, tint: Int = color): Unit {
        val bounds: RectF = RectF(px(left), py(top), px(right), py(bottom))
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x24); canvas.drawRoundRect(bounds, d(3f), d(3f), fillPaint)
        text(canvas, label, bounds.centerX(), bounds.centerY() + d(3f), bounds.width() - d(3f), tint, Paint.Align.CENTER)
    }
    private fun drawAudition(canvas: Canvas): Unit {
        caption(canvas, "当前路径 · " + auditionName(model.audition))
        val labels: List<String> = listOf("正常", "原声", "差分", "交叉", "快照 A", "快照 B", "测量")
        labels.forEachIndexed { index: Int, label: String ->
            val row: Int = index / 4; val column: Int = index % 4
            box(canvas, label, column / 4.0 + .015, .95 - row * .53, (column + 1) / 4.0 - .015, .62 - row * .53,
                if (index == model.audition) color else secondary)
        }
        footer(canvas, "图纸只读 · 原生按钮控制试听")
    }
    private fun head(canvas: Canvas): Unit {
        val x: Float = plot.centerX(); val y: Float = py(.35); val r: Float = min(plot.height() * .25f, plot.width() * .15f)
        linePaint.color = secondary
        canvas.drawOval(RectF(x - r * .7f, y - r, x + r * .7f, y + r), linePaint)
        canvas.drawLine(x - r * .2f, y - r, x, y - r * 1.22f, linePaint)
        canvas.drawLine(x, y - r * 1.22f, x + r * .2f, y - r, linePaint)
        point(canvas, x - r * .8f, y, secondary); point(canvas, x + r * .8f, y, secondary)
    }
    private fun crossPath(canvas: Canvas, reverse: Boolean, gain: Double, tint: Int): Unit {
        val visual: Double = gain.coerceIn(0.0, 2.0) / 2
        path.rewind(); path.moveTo(px(if (reverse) .94 else .06), py(.92))
        path.quadTo(px(if (reverse) .97 else .03), py(.18), px(if (reverse) .40 else .60), py(.35))
        linePaint.strokeWidth = d((.7 + 3.3 * visual).toFloat())
        stroke(canvas, ColorUtils.setAlphaComponent(tint, (75 + 180 * visual).roundToInt()))
        linePaint.strokeWidth = d(1.5f)
    }
    private fun drawFusion(canvas: Canvas): Unit {
        caption(canvas, if (model.bs2b) "BS2B · 双耳低频交叉融合" else "空间原核 · 交叉分量叠加")
        head(canvas)
        val target: P.Settings = model.target(focus, key)
        val effective: P.Settings = if (target.professional) target else P.baseSettings(target.base)
        val gain: Double = if (model.bs2b) 10.0.pow(-effective.attenuation / 200.0) else 10.0.pow(effective.spatial / 200.0)
        crossPath(canvas, false, gain, color); crossPath(canvas, true, gain, third)
        footer(canvas, if (model.bs2b) "支路衰减越大，交叉融合越弱" else "相对声场示意 · 非实测位置")
    }
    private fun drawPaths(canvas: Canvas): Unit {
        caption(canvas, "双向混入 · 0 dB 保持原模型")
        head(canvas)
        val lr: Double = n(P.leftToRight)
        val rl: Double = if (settings.advanced.linked && key != P.rightToLeft.key) lr else n(P.rightToLeft)
        crossPath(canvas, false, 10.0.pow(lr / 20), color); crossPath(canvas, true, 10.0.pow(rl / 20), third)
        footer(canvas, "左→右 " + signed(lr) + " / 右→左 " + signed(rl) + " dB")
    }
    private fun drawArrival(canvas: Canvas): Unit {
        caption(canvas, "只增加交叉支路延时")
        grid(canvas, "直达", "交叉", "0 μs", "500 μs")
        val delay: Double = n(P.delay)
        linePaint.color = secondary; canvas.drawLine(plot.left, py(.76), plot.right, py(.76), linePaint)
        point(canvas, plot.left, py(.76), secondary)
        linePaint.color = color; canvas.drawLine(plot.left, py(.24), px(delay / 500), py(.24), linePaint)
        point(canvas, px(delay / 500), py(.24))
        text(canvas, fmt(delay * rate / 1e6, 3) + " 样本", plot.left, py(.45), plot.width(), color)
        footer(canvas, "附加 " + fmt(delay, 0) + " μs · 原核自身延迟保留")
    }
    private fun drawFilter(canvas: Canvas): Unit {
        caption(canvas, "仅交叉支路低通 · 直达保留")
        grid(canvas, "0 dB", "−60", "20 Hz", "20k Hz")
        val cutoff: Double = min(n(P.filterCutoff), rate * .45)
        val stages: Int = settings.advanced.slope
        trace(canvas, secondary, true) { 1.0 }
        trace(canvas) { position: Double ->
            val frequency: Double = 20.0 * 1000.0.pow(position)
            if (frequency >= rate * .499) return@trace Double.NaN
            val warped: Double = tan(PI * frequency / rate) / tan(PI * cutoff / rate)
            val decibels: Double = -10 * stages * log10(1 + warped * warped)
            (decibels + 60) / 60
        }
        marker(canvas, fx(cutoff))
        footer(canvas, (if (stages == 1) "6" else "12") + " dB/oct · " + fmt(cutoff, 0) + " Hz")
    }
    private fun bar(canvas: Canvas, index: Int, amount: Double, label: String, number: String, tint: Int): Unit {
        val cell: Float = plot.width() / 3
        val x: Float = plot.left + index * cell + cell * .20f
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x45)
        canvas.drawRoundRect(RectF(x, py(amount), x + cell * .60f, plot.bottom), d(2f), d(2f), fillPaint)
        text(canvas, number, x + cell * .30f, max(plot.top + d(9f), py(amount) - d(3f)), cell, tint, Paint.Align.CENTER)
        text(canvas, label, x + cell * .30f, plot.bottom + d(11f), cell, foreground, Paint.Align.CENTER)
    }
    private fun drawCenter(canvas: Canvas): Unit {
        val gain: Double = 10.0.pow(n(P.center) / 20)
        caption(canvas, "中央共有成分 · 侧向比例保留")
        bar(canvas, 0, .5, "原 Mid", "1×", secondary)
        bar(canvas, 1, gain / 2, "补偿 Mid", signed(n(P.center)) + "dB", color)
        bar(canvas, 2, .5, "Side", "1×", third)
        footer(canvas, "共同成分增益示意 · 非人声提取")
    }
    private fun drawMix(canvas: Canvas): Unit {
        val mix: Double = n(P.mix) / 100
        val output: Double = 10.0.pow(n(P.output) / 20)
        caption(canvas, "干湿线性混合 · 输出共同衰减")
        bar(canvas, 0, 1 - mix, "原声", fmt(100 * (1 - mix), 0) + "%", secondary)
        bar(canvas, 1, mix, "处理", fmt(100 * mix, 0) + "%", color)
        bar(canvas, 2, output, "输出", fmt(n(P.output)) + "dB", third)
        footer(canvas, "混合 0% 仍受输出衰减控制")
    }
    private fun drawMeasurement(canvas: Canvas): Unit {
        caption(canvas, "同一输入同时测 A/B · 2 秒")
        box(canvas, "同一输入", .18, .95, .82, .69, secondary)
        box(canvas, "处理 A", .0, .52, .43, .23, color)
        box(canvas, "处理 B", .57, .52, 1.0, .23, third)
        linePaint.color = secondary
        canvas.drawLine(px(.5), py(.69), px(.22), py(.52), linePaint)
        canvas.drawLine(px(.5), py(.69), px(.78), py(.52), linePaint)
        text(canvas, if (model.matchingReady) "测量已就绪" else "模型预热 → 测量 → 固定衰减", plot.centerX(), py(.02), plot.width(), color, Paint.Align.CENTER)
        footer(canvas, "不持续追踪 · 不提升较小声组")
    }
    private fun drawComparison(canvas: Canvas): Unit {
        caption(canvas, "当前页面快照 · 不写入预设")
        box(canvas, "A " + (if (model.savedA) "已保存" else "未保存"), .0, .95, .45, .57, secondary)
        box(canvas, "B " + (if (model.savedB) "已保存" else "未保存"), .55, .95, 1.0, .57, third)
        box(canvas, auditionName(model.audition), .0, .34, 1.0, .02)
        footer(canvas, if (model.matching) "匹配开启 · 使用固定测量衰减" else "匹配关闭 · 保留各自输出电平")
    }
    private fun auditionName(value: Int): String = listOf("正常处理", "本模块原声", "效果差分（−6 dB）", "仅交叉支路", "快照 A", "快照 B", "测量 A/B").getOrElse(value) { "正常处理" }
    private fun details(): String {
        val controls: List<BassParameters.Control> = when (focus) {
            PresetCrossfeedChartFocus.RESPONSE -> listOf(P.cutoff, P.attenuation)
            PresetCrossfeedChartFocus.SPACE -> listOf(P.spatial)
            PresetCrossfeedChartFocus.PATH -> listOf(P.leftToRight, P.rightToLeft)
            PresetCrossfeedChartFocus.DELAY -> listOf(P.delay)
            PresetCrossfeedChartFocus.FILTER -> listOf(P.filterCutoff)
            PresetCrossfeedChartFocus.CENTER -> listOf(P.center)
            PresetCrossfeedChartFocus.LEVEL -> listOf(P.mix, P.output)
            else -> emptyList()
        }
        val lines: MutableList<String> = mutableListOf(P.baseNames[settings.base] + " · " + rate + " Hz · 保存参数模型",
            if (enabled) "当前参数可用。空间图表示相对关系，不是头部或房间实测。" else model.inactiveReason(focus, key))
        controls.forEach { control: BassParameters.Control -> lines += control.title + " " + control.label(model.value(control)) }
        lines += "专业精调：" + (if (settings.professional) "开启" else "关闭")
        if (focus == PresetCrossfeedChartFocus.PATH) lines += "路径精调：" + (if (settings.advanced.pathOn) "开启" else "关闭") +
            "；左右联动：" + (if (settings.advanced.linked) "开启，使用左→右共同值" else "关闭，各自使用保存值")
        if (focus == PresetCrossfeedChartFocus.FILTER) lines += "交叉支路低通：" + (if (settings.advanced.filterOn) "开启" else "关闭") +
            "；斜率 " + (if (settings.advanced.slope == 1) "6 dB/oct（一阶）" else "12 dB/oct（两级一阶）")
        if (focus == PresetCrossfeedChartFocus.CENTER) lines += "中央补偿：" + (if (settings.advanced.centerOn) "开启" else "关闭")
        lines += when (focus) {
            PresetCrossfeedChartFocus.RESPONSE -> "六种基础预设一直保留。只有 BS2B 弱／强使用可调截止频率与交叉衰减，衰减是正 dB，越大越弱；其余四种空间预设使用各自原始 HRTF 核，不能用统一低通代替。专业关闭时恢复当前基础预设的原始参数。左图复用原 BS2B／HRTF 线性模型，包含分数延迟、混合、进阶处理与输出衰减；不是实际声学串音测量。"
            PresetCrossfeedChartFocus.SPACE -> "空间交叉支路电平只用于出头、环绕一、环绕二与 Joe0Blogs 四种空间预设，0 dB 保持原核交叉分量，负值减弱、正值增强。BS2B 不使用这个数值。原核含有自己的频率、相位与时间信息，示意连线粗细仅表示配置增益，不代表声源的实际距离或方向；非48 kHz的HRTF响应包含SRC近似。"
            PresetCrossfeedChartFocus.PATH -> "左右独立增益仅缩放混入对侧耳朵的交叉分量，0 dB 保持原模型，不直接调整本耳直达分量。联动时两侧使用左→右，右→左保存值不丢失；聚焦被联动遮蔽的右路时，仅显示其独立灰色预览，不会关闭真实联动。需要配套进阶驱动、专业精调与路径子开关。"
            PresetCrossfeedChartFocus.DELAY -> "单位 μs，0–500 微秒是交叉支路的额外延时，0 不会移除基础模型自身的延迟。样本数 = 微秒 × 内部采样率 / 1000000，使用相邻采样线性分数插值。左图为同相信号左右输出的包裹相位，固定 −180° 到 +180°；近零响应不显示相位，换相点断开。不是左右声源的真实到达时间测量。"
            PresetCrossfeedChartFocus.FILTER -> "新增低通只作用于交叉分量，直达支路保留。6 dB/oct 为一阶，12 dB/oct 是两个一阶串联，所以设置频率处约 −6 dB，并非二阶 Butterworth 的 −3 dB。实际频率限制在内部采样率 45%；BS2B 还包含其基础截止，空间模式还包含各自 HRTF 原核。需要进阶驱动、专业精调和低通子开关。"
            PresetCrossfeedChartFocus.CENTER -> "中央补偿作用于混合之后两声道共有成分，相当于共同 Mid 乘以补偿增益，Side 保持原比例；不是人声分离或中心声像提取。左图展示同相／反相线性响应，右图是单位 Mid／Side 增益示意，不是实时 RMS。需要进阶驱动、专业精调和中央补偿开关。"
            PresetCrossfeedChartFocus.LEVEL -> "混合采用原声与处理声线性叠加，不是等功率淡化；输出衰减随后共同作用，因此 0% 混合仍受输出衰减影响。左图包含完整原模型的相位干涉，右图只表示配置比例，不假定实际输入左右相同或功率不变。"
            PresetCrossfeedChartFocus.MATCH -> "先保存 A/B，原模型充分预热后用同一输入同时处理 A/B 并测量 2 秒。匹配只固定衰减较响的一组，不提升电平，不持续追踪，也不是 LUFS 或设备最终响度。左图用 A 比 B 响 0–12 dB 的参考范围解释匹配，不表示实际测量或算法最大限制。当前匹配" + (if (model.matching) "开启" else "关闭") + "，测量" + (if (model.matchingReady) "就绪" else "未就绪") + "；重测只由原生按钮触发。"
            PresetCrossfeedChartFocus.AUDITION -> "当前：" + auditionName(model.audition) + "。A " + (if (model.savedA) "已保存" else "未保存") + "，B " + (if (model.savedB) "已保存" else "未保存") + "。七个选项包括正常、本模块原声、−6 dB 效果差分、仅交叉支路、快照A、快照B和电平测量。快照和匹配只存于页面会话，静图不读取私有快照内容；A/B时显示当前保存参数模型，实时图读取实际处理状态。"
        }
        lines += "频响坐标固定 20 Hz–20 kHz、−60 至 +18 dB；低于内部奈奎斯特上限之外不画响应。同一图纸的新参数计算完成前保留上一响应，避免拖动闪空。长按只查看说明，不修改任何参数。"
        return lines.joinToString(System.lineSeparator() + System.lineSeparator())
    }
    override fun onAttachedToWindow(): Unit { super.onAttachedToWindow(); if (active) updateResponse() }
    override fun onDetachedFromWindow(): Unit {
        generation++; PresetCrossfeedStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}
