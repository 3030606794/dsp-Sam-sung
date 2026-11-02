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
import me.timschneeberger.rootlessjamesdsp.fusion.CrossfeedAdvancedParameters as A
import me.timschneeberger.rootlessjamesdsp.fusion.CrossfeedChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.CrossfeedController
import me.timschneeberger.rootlessjamesdsp.fusion.CrossfeedDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.CrossfeedParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** Headphone/Bauer targets only; the separate six-preset crossfeed module is never accessed here. */
internal class CrossfeedParameterChart(context: Context) : CrossfeedFocusStaticBase(context, false)
internal class CrossfeedIllustrationChart(context: Context) : CrossfeedFocusStaticBase(context, true)

private data class CrossfeedFocusSnapshot(
    val settings: P.Settings, val rate: Int, val effectOn: Boolean, val supported: Boolean, val advancedSupported: Boolean,
    val audition: Int, val matching: Boolean, val matchingReady: Boolean, val savedA: Boolean, val savedB: Boolean
) {
    val separate: Boolean get() = settings.advanced.words[3] == 1
    val legacyName: String get() = listOf("轻", "中", "强")[settings.legacy]
    fun value(control: BassParameters.Control): Int = when (control.key) {
        P.cutoff.key -> settings.cutoff
        P.attenuation.key -> settings.attenuation
        P.mix.key -> settings.mix
        P.delay.key -> settings.delay
        P.output.key -> settings.output
        A.amount.key -> settings.advanced.words[2]
        A.left.key -> settings.advanced.words[4]
        A.right.key -> settings.advanced.words[5]
        A.leftDelay.key -> settings.advanced.words[6]
        A.rightDelay.key -> settings.advanced.words[7]
        else -> control.default
    }
    fun number(control: BassParameters.Control): Double = value(control).toDouble() / control.divisor
    fun flag(index: Int): Boolean = settings.advanced.words[index] == 1
    fun legacyView(focus: CrossfeedChartFocus, key: String): Boolean = focus == CrossfeedChartFocus.RESPONSE &&
        (key == P.LEGACY || key == P.ENABLED && !settings.professional)
    fun active(focus: CrossfeedChartFocus, key: String): Boolean {
        if (!effectOn) return false
        if (legacyView(focus, key)) return !settings.professional
        if (!supported || !settings.professional) return false
        return when (focus) {
            CrossfeedChartFocus.PATH -> advancedSupported && separate
            CrossfeedChartFocus.DELAY -> if (key == P.delay.key) !(advancedSupported && separate) else advancedSupported && separate
            CrossfeedChartFocus.FILTER -> key == P.FILTER || advancedSupported && flag(8)
            CrossfeedChartFocus.CENTER -> advancedSupported && flag(1)
            CrossfeedChartFocus.MONITOR -> advancedSupported && flag(10)
            else -> true
        }
    }
    fun reason(focus: CrossfeedChartFocus, key: String): String = when {
        !effectOn -> "模块关闭 · 参数目标保留"
        legacyView(focus, key) && settings.professional -> "专业参数接管 · 原三档预览"
        !supported -> "专业驱动未支持 · 保存模型"
        !settings.professional -> "原三档处理中 · 保存专业模型"
        focus == CrossfeedChartFocus.DELAY && key == P.delay.key && advancedSupported && separate -> "独立路径接管 · 共用延时预览"
        focus in setOf(CrossfeedChartFocus.PATH, CrossfeedChartFocus.CENTER, CrossfeedChartFocus.MONITOR) && !advancedSupported -> "进阶驱动未支持 · 参数预览"
        focus == CrossfeedChartFocus.PATH && !separate -> "左右独立关闭 · 保存值预览"
        focus == CrossfeedChartFocus.DELAY && key != P.delay.key && !separate -> "使用共用延时 · 独立值预览"
        focus == CrossfeedChartFocus.CENTER && !flag(1) -> "中央补偿关闭 · 保存值预览"
        focus == CrossfeedChartFocus.FILTER && key != P.FILTER && !flag(8) -> "支路精调关闭 · 斜率目标预览"
        focus == CrossfeedChartFocus.MONITOR && !flag(10) -> "诊断关闭 · 测点示意"
        else -> "参数目标预览"
    }
    fun target(focus: CrossfeedChartFocus, key: String): P.Settings {
        val words: MutableList<Int> = (if (advancedSupported) settings.advanced.words else A.Settings().words).toMutableList()
        if (focus == CrossfeedChartFocus.PATH || focus == CrossfeedChartFocus.DELAY && key != P.delay.key) {
            for (index in listOf(3, 4, 5, 6, 7)) words[index] = settings.advanced.words[index]
            words[3] = 1
        }
        if (focus == CrossfeedChartFocus.DELAY && key == P.delay.key) words[3] = 0
        if (focus == CrossfeedChartFocus.FILTER && key != P.FILTER) {
            words[8] = 1; words[9] = settings.advanced.words[9]
        }
        if (focus == CrossfeedChartFocus.CENTER) { words[1] = 1; words[2] = settings.advanced.words[2] }
        words[10] = 0 // Diagnostic display state is absent from the math identity.
        return settings.copy(professional = true, advanced = A.Settings(words.toList()))
    }
}

private data class CrossfeedStaticRequest(val settings: P.Settings, val rate: Int, val phase: Boolean)
private data class CrossfeedStaticResult(val x: DoubleArray, val values: List<DoubleArray>, val phase: Boolean, val failed: Boolean = false)

/** Complete one useful same-focus result, then the latest waiting target; never blank a curve mid-drag. */
private object CrossfeedStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "headphone-crossfeed-static").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true)
    }
    private data class Task(val request: CrossfeedStaticRequest, val callback: (CrossfeedStaticResult) -> Unit)
    private class Owner {
        var running: CrossfeedStaticRequest? = null
        var waiting: Task? = null
        var future: Future<*>? = null
    }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<CrossfeedStaticRequest, CrossfeedStaticResult> =
        object : LinkedHashMap<CrossfeedStaticRequest, CrossfeedStaticResult>(12, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CrossfeedStaticRequest, CrossfeedStaticResult>?): Boolean = size > 12
        }
    @Synchronized fun release(owner: Any): Unit {
        owners.remove(owner)?.let { state -> state.waiting = null; state.future?.cancel(true); state.future = null }
    }
    @Synchronized fun request(owner: Any, request: CrossfeedStaticRequest, callback: (CrossfeedStaticResult) -> Unit): Unit {
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
            val result: CrossfeedStaticResult = try {
                cancel()
                val saved: CrossfeedStaticResult? = synchronized(this) { cache[task.request] }
                saved ?: if (task.request.phase) delayPhase(task.request, ::cancel) else {
                    val values: Map<String, Any> = task.request.settings.values() + ("v_cure" to true)
                    val model: CrossfeedDashboardModel = CrossfeedDashboardModel.from(values, task.request.rate, true)
                    val curve = model.frequency(::cancel)
                    cancel()
                    CrossfeedStaticResult(curve.frequencies, curve.decibels, false)
                }
            } catch (_: CancellationException) { return@submit
            } catch (_: InterruptedException) { return@submit
            } catch (_: Exception) { CrossfeedStaticResult(doubleArrayOf(), emptyList(), task.request.phase, true) }
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
    /** Isolated extra-delay transfer z^whole * ((1-fraction)+fraction*z), excluding Bauer/filter phase. */
    private fun delayPhase(request: CrossfeedStaticRequest, cancel: () -> Unit): CrossfeedStaticResult {
        val settings: P.Settings = request.settings
        val words: List<Int> = settings.advanced.words
        val separate: Boolean = words[3] == 1
        val delays: List<Double> = listOf(if (separate) words[6].toDouble() else settings.delay.toDouble(),
            if (separate) words[7].toDouble() else settings.delay.toDouble())
        val high: Double = min(20000.0, request.rate * .45)
        val frequencies: DoubleArray = DoubleArray(256) { 20.0 * (high / 20).pow(it / 255.0) }
        val phase: List<DoubleArray> = delays.map { delay ->
            val samples: Double = delay * request.rate / 1e6
            val whole: Double = floor(samples); val fraction: Double = samples - whole
            DoubleArray(frequencies.size) { index ->
                if (index % 32 == 0) cancel()
                val w: Double = 2 * PI * frequencies[index] / request.rate
                val angle: Double = -whole * w + atan2(-fraction * sin(w), 1 - fraction + fraction * cos(w))
                atan2(sin(angle), cos(angle)) * 180 / PI
            }
        }
        return CrossfeedStaticResult(frequencies, phase, true)
    }
}

internal open class CrossfeedFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
    private val density: Float = resources.displayMetrics.density
    private fun d(value: Float): Float = value * density
    private val foreground: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val teal: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(115, 215, 213) else Color.rgb(20, 126, 122)
    private val amber: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(247, 187, 113) else Color.rgb(161, 90, 24)
    private val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(9f * resources.displayMetrics.scaledDensity, d(11f)) }
    private val linePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(1.5f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(.65f); color = ColorUtils.setAlphaComponent(foreground, 0x29) }
    private val plot: RectF = RectF()
    private val path: Path = Path()
    private var settings: P.Settings = P.capture(emptyMap<String, Any>())
    private var rate: Int = 48000
    private var model: CrossfeedFocusSnapshot = snapshot()
    private var focus: CrossfeedChartFocus = CrossfeedChartFocus.RESPONSE
    private var key: String = P.LEGACY
    private var active: Boolean = true
    private var request: CrossfeedStaticRequest? = null
    private var response: CrossfeedStaticResult? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = model.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x79)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x4b)
    private fun snapshot(): CrossfeedFocusSnapshot = CrossfeedFocusSnapshot(settings, rate,
        CrossfeedController.preferences(context).getBoolean("v_cure", false), CrossfeedController.supported(context),
        CrossfeedController.advancedSupported(context), CrossfeedController.audition, CrossfeedController.match,
        CrossfeedController.matchingReady(), CrossfeedController.hasComparison(0), CrossfeedController.hasComparison(1))
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
    fun setConfiguration(settings: P.Settings, rate: Int): Unit {
        this.settings = settings.takeIf { it.valid() }?.let { it.copy(advanced = it.advanced.copy(words = it.advanced.words.toList())) }
            ?: P.capture(emptyMap<String, Any>())
        this.rate = rate.takeIf { it in 8000..768000 } ?: 48000
        val next: CrossfeedFocusSnapshot = snapshot()
        if (next == model) return
        model = next; updateDescription(); updateResponse()
        if (active) invalidate()
    }
    fun setFocus(focus: CrossfeedChartFocus, key: String): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; CrossfeedStaticWork.release(this); request = null; response = null }
        this.focus = focus; this.key = key
        updateDescription(); updateResponse()
        if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) {
            generation++; CrossfeedStaticWork.release(this); request = null
            detailDialog?.dismiss(); detailDialog = null
        } else { updateResponse(); invalidate() }
    }
    private fun updateResponse(): Unit {
        if (illustration || !active) return
        if (model.legacyView(focus, key) || focus in setOf(CrossfeedChartFocus.MATCH, CrossfeedChartFocus.MONITOR, CrossfeedChartFocus.AUDITION)) {
            CrossfeedStaticWork.release(this); request = null; return
        }
        val next: CrossfeedStaticRequest = CrossfeedStaticRequest(model.target(focus, key), rate, focus == CrossfeedChartFocus.DELAY)
        if (request == next) return
        request = next
        val token: Long = generation
        CrossfeedStaticWork.request(this, next) { result ->
            if (active && token == generation && result.phase == (focus == CrossfeedChartFocus.DELAY)) {
                if (!result.failed || response == null) response = result
                invalidate()
            }
        }
    }
    private fun updateDescription(): Unit { contentDescription = details() }
    private fun n(control: BassParameters.Control): Double = model.number(control)
    private fun fmt(value: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%." + decimals + "f", value)
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
        if (enabled) value else model.reason(focus, key), d(3f), height - d(3f), width - d(6f), ColorUtils.setAlphaComponent(foreground, 0xa0))
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
    private fun drawParameters(canvas: Canvas): Unit = when {
        model.legacyView(focus, key) -> drawLegacy(canvas)
        focus == CrossfeedChartFocus.MATCH -> drawMatch(canvas)
        focus == CrossfeedChartFocus.MONITOR -> drawMonitor(canvas)
        focus == CrossfeedChartFocus.AUDITION -> drawAudition(canvas)
        else -> drawResponse(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        CrossfeedChartFocus.RESPONSE, CrossfeedChartFocus.PATH -> drawEars(canvas)
        CrossfeedChartFocus.DELAY -> drawArrival(canvas)
        CrossfeedChartFocus.FILTER -> drawFilter(canvas)
        CrossfeedChartFocus.CENTER -> drawCenter(canvas)
        CrossfeedChartFocus.LEVEL -> drawMix(canvas)
        CrossfeedChartFocus.MONITOR -> drawMonitorChannels(canvas)
        CrossfeedChartFocus.MATCH -> drawMeasurement(canvas)
        CrossfeedChartFocus.AUDITION -> drawComparison(canvas)
    }
    private fun drawLegacy(canvas: Canvas): Unit {
        caption(canvas, "原版三档 · 离散强度选择")
        linePaint.color = secondary
        canvas.drawLine(px(.14), py(.5), px(.86), py(.5), linePaint)
        listOf("轻", "中", "强").forEachIndexed { index: Int, label: String ->
            val x: Float = px(.14 + .36 * index)
            fillPaint.color = ColorUtils.setAlphaComponent(if (index == settings.legacy) color else secondary, 0x32)
            canvas.drawCircle(x, py(.5), d(if (index == settings.legacy) 7f else 4f), fillPaint)
            point(canvas, x, py(.5), if (index == settings.legacy) color else secondary)
            text(canvas, label, x, py(.22), plot.width() / 3, foreground, Paint.Align.CENTER)
        }
        footer(canvas, "档位相对示意 · 不对应专业 dB")
    }
    private fun drawResponse(canvas: Canvas): Unit {
        val phase: Boolean = focus == CrossfeedChartFocus.DELAY
        caption(canvas, if (phase) "附加路径相位 ° · 不含基础滤波" else when (focus) {
            CrossfeedChartFocus.CENTER -> "保存专业模型 · 同相／反相 dB"
            CrossfeedChartFocus.FILTER -> "保存专业模型 · 支路／输出 dB"
            CrossfeedChartFocus.PATH -> "保存专业模型 · 双向交叉 dB"
            else -> "保存专业模型 · 直达／交叉 dB"
        })
        grid(canvas, if (phase) "+180°" else "+18 dB", if (phase) "−180°" else "−60", "20 Hz", "20k Hz")
        if (rate * .45 < 20000) {
            fillPaint.color = ColorUtils.setAlphaComponent(foreground, 0x0b)
            canvas.drawRect(fx(rate * .45), plot.top, plot.right, plot.bottom, fillPaint)
        }
        val current: CrossfeedStaticResult? = response
        if (current != null && !current.failed) {
            val channels: List<Int> = if (phase) listOf(0, 1) else if (focus == CrossfeedChartFocus.CENTER) listOf(4, 5, 6, 7) else listOf(0, 1, 2, 3)
            val palette: List<Int> = listOf(color, secondary, third, ColorUtils.setAlphaComponent(third, 0x92))
            channels.forEachIndexed channel@{ slot: Int, channel: Int ->
                val samples: DoubleArray = current.values.getOrNull(channel) ?: return@channel
                path.rewind(); var started: Boolean = false; var previous: Double = Double.NaN
                samples.forEachIndexed sample@{ index: Int, value: Double ->
                    if (!value.isFinite()) { started = false; previous = Double.NaN; return@sample }
                    val x: Float = fx(current.x[index]); val y: Float = py(if (phase) (value + 180) / 360 else (value + 60) / 78)
                    if (!started || phase && previous.isFinite() && abs(value - previous) > 180) path.moveTo(x, y) else path.lineTo(x, y)
                    started = true; previous = value
                }
                stroke(canvas, palette[slot], slot == 1 || slot == 3)
            }
        } else text(canvas, if (current?.failed == true) "模型暂不可用" else "正在计算保存目标", plot.centerX(), plot.centerY(), plot.width(), secondary, Paint.Align.CENTER)
        if (focus == CrossfeedChartFocus.RESPONSE || focus == CrossfeedChartFocus.FILTER) marker(canvas, fx(n(P.cutoff)), secondary)
        footer(canvas, if (phase) "左→右／右→左 · 线性分数延迟" else if (focus == CrossfeedChartFocus.CENTER)
            "紫／青 同相 · 金／虚线 反相" else "紫／青 直达 · 金／虚线 交叉")
    }
    private fun box(canvas: Canvas, label: String, left: Double, top: Double, right: Double, bottom: Double, tint: Int = color): Unit {
        val bounds: RectF = RectF(px(left), py(top), px(right), py(bottom))
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x24); canvas.drawRoundRect(bounds, d(3f), d(3f), fillPaint)
        text(canvas, label, bounds.centerX(), bounds.centerY() + d(3f), bounds.width() - d(3f), tint, Paint.Align.CENTER)
    }
    private fun drawMatch(canvas: Canvas): Unit {
        caption(canvas, "A 比 B 较响 · 固定衰减参考")
        grid(canvas, "0 dB", "−12", "相对差 0", "+12 dB")
        trace(canvas) { 1 - it }; trace(canvas, secondary, true) { 1.0 }
        footer(canvas, if (model.matching) "A/B 已启用固定匹配" else "匹配未启用 · 不提升电平")
    }
    private fun drawMonitor(canvas: Canvas): Unit {
        caption(canvas, "理论频响与模块试听点诊断")
        box(canvas, "Bauer 处理", .0, .95, 1.0, .66)
        box(canvas, "试听选择", .0, .52, .44, .23, secondary)
        box(canvas, "输出诊断", .56, .52, 1.0, .23, third)
        linePaint.color = secondary
        canvas.drawLine(px(.5), py(.66), px(.22), py(.52), linePaint)
        canvas.drawLine(px(.44), py(.38), px(.56), py(.38), linePaint)
        footer(canvas, "峰值在试听之后 · 后续效果仍参与")
    }
    private fun drawAudition(canvas: Canvas): Unit {
        caption(canvas, "当前路径 · " + auditionName(model.audition))
        val labels: List<String> = listOf("正常", "原声", "差分", "交叉", "快照 A", "快照 B", "测量")
        labels.forEachIndexed { index: Int, label: String ->
            val row: Int = index / 4; val column: Int = index % 4
            box(canvas, label, column / 4.0 + .015, .95 - row * .53, (column + 1) / 4.0 - .015, .62 - row * .53,
                if (index == model.audition) color else secondary)
        }
        footer(canvas, "原生按钮管理试听 · 图纸只读")
    }
    /** Layered head, ear cushions and curved paths provide a stable explanatory top view. */
    private fun drawHead(canvas: Canvas): RectF {
        val cx: Float = plot.centerX(); val cy: Float = py(.44)
        val rx: Float = min(plot.width() * .19f, plot.height() * .27f); val ry: Float = rx * 1.22f
        val head: RectF = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
        fillPaint.color = ColorUtils.setAlphaComponent(secondary, 0x12)
        canvas.drawOval(RectF(head.left - d(3f), head.top - d(3f), head.right + d(3f), head.bottom + d(3f)), fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(secondary, 0x20); canvas.drawOval(head, fillPaint)
        linePaint.color = ColorUtils.setAlphaComponent(secondary, 0xb0); canvas.drawOval(head, linePaint)
        path.rewind(); path.moveTo(cx - rx * .20f, head.top + ry * .1f); path.lineTo(cx, head.top - ry * .15f)
        path.lineTo(cx + rx * .20f, head.top + ry * .1f)
        linePaint.color = secondary; canvas.drawPath(path, linePaint)
        canvas.drawArc(RectF(head.left - rx * .38f, head.top - ry * .10f, head.right + rx * .38f, head.bottom - ry * .22f), 185f, 170f, false, linePaint)
        repeat(2) { index: Int ->
            val sign: Float = if (index == 0) -1f else 1f
            val x: Float = cx + sign * rx * 1.08f
            val cup: RectF = RectF(x - rx * .23f, cy - ry * .45f, x + rx * .23f, cy + ry * .43f)
            fillPaint.color = ColorUtils.setAlphaComponent(if (index == 0) color else third, 0x32)
            canvas.drawRoundRect(cup, rx * .18f, rx * .18f, fillPaint)
            linePaint.color = if (index == 0) color else third
            canvas.drawRoundRect(cup, rx * .18f, rx * .18f, linePaint)
            canvas.drawLine(x, cup.top + ry * .12f, x, cup.bottom - ry * .12f, linePaint)
        }
        text(canvas, "L", head.left - rx * .65f, cy + d(3f), rx, color, Paint.Align.CENTER)
        text(canvas, "R", head.right + rx * .65f, cy + d(3f), rx, third, Paint.Align.CENTER)
        return head
    }
    private fun earPath(canvas: Canvas, head: RectF, reverse: Boolean, strength: Double, delay: Double, tint: Int): Unit {
        val start: Float = if (reverse) head.right + head.width() * .05f else head.left - head.width() * .05f
        val end: Float = if (reverse) head.left - head.width() * .05f else head.right + head.width() * .05f
        val y: Float = head.centerY()
        val bend: Float = if (reverse) head.bottom + head.height() * .45f else head.top - head.height() * .45f
        val visual: Double = strength.coerceIn(0.0, 1.0)
        path.rewind(); path.moveTo(start, y)
        path.cubicTo(start, bend, end, bend, end, y)
        linePaint.pathEffect = null
        linePaint.color = ColorUtils.setAlphaComponent(tint, 0x20)
        linePaint.strokeWidth = d((4.0 + 5 * visual).toFloat()); canvas.drawPath(path, linePaint)
        linePaint.strokeWidth = d((.8 + 2.3 * visual).toFloat())
        stroke(canvas, ColorUtils.setAlphaComponent(tint, (95 + 155 * visual).roundToInt()))
        linePaint.strokeWidth = d(1.5f)
        point(canvas, end, y, tint)
        val radius: Float = d((3.0 + 4.0 * delay.coerceIn(0.0, 500.0) / 500.0).toFloat())
        linePaint.color = ColorUtils.setAlphaComponent(tint, 0x70)
        canvas.drawCircle(end, y, radius, linePaint)
    }
    private fun drawEars(canvas: Canvas): Unit {
        val legacy: Boolean = model.legacyView(focus, key)
        caption(canvas, if (legacy) "原版档位 · 双耳融合相对示意" else "对侧低频混入 · 双耳融合示意")
        val head: RectF = drawHead(canvas)
        val target: P.Settings = model.target(focus, key)
        val separate: Boolean = target.advanced.words[3] == 1
        val lr: Double = if (legacy) .27 + settings.legacy * .22 else 10.0.pow(-(if (separate) target.advanced.words[4] else target.attenuation) / 200.0)
        val rl: Double = if (legacy) lr else 10.0.pow(-(if (separate) target.advanced.words[5] else target.attenuation) / 200.0)
        earPath(canvas, head, false, lr, if (separate) target.advanced.words[6].toDouble() else target.delay.toDouble(), color)
        earPath(canvas, head, true, rl, if (separate) target.advanced.words[7].toDouble() else target.delay.toDouble(), third)
        footer(canvas, if (legacy) "三档相对示意 · 不表示实际 dB" else "交叉衰减越大，对侧混入越弱")
    }
    private fun drawArrival(canvas: Canvas): Unit {
        val target: P.Settings = model.target(focus, key)
        val separate: Boolean = target.advanced.words[3] == 1
        val delays: List<Double> = listOf(if (separate) target.advanced.words[6].toDouble() else target.delay.toDouble(),
            if (separate) target.advanced.words[7].toDouble() else target.delay.toDouble())
        caption(canvas, "交叉支路附加时差 · 非总延迟")
        grid(canvas, "左→右", "右→左", "0 μs", "500 μs")
        delays.forEachIndexed { index: Int, delay: Double ->
            val y: Float = py(if (index == 0) .73 else .23); val tint: Int = if (index == 0) color else third
            linePaint.color = tint; canvas.drawLine(plot.left, y, px(delay / 500), y, linePaint)
            point(canvas, px(delay / 500), y, tint)
            text(canvas, fmt(delay * rate / 1e6, 3) + " 样本", plot.left, y - d(5f), plot.width(), tint)
        }
        footer(canvas, if (separate) "独立双向延时 · 共用值暂不使用" else "共用附加延时 · 基础频变延迟保留")
    }
    private fun postGain(frequency: Double): Double {
        fun section(hz: Double, high: Boolean): Double {
            val angle: Float = PI.toFloat() * hz.toFloat() / rate.toFloat()
            val t: Float = tan(angle.toDouble()).toFloat()
            val b: Float = if (high) 1f / (1f + t) else t / (1f + t)
            val a: Float = (1f - t) / (1f + t)
            val w: Double = 2 * PI * frequency / rate
            val b1: Double = (if (high) -b else b).toDouble()
            return hypot(b + b1 * cos(w), -b1 * sin(w)) / hypot(1 - a * cos(w), a * sin(w))
        }
        return section(10.0, true) * section(min(18000.0, rate * .45), false).pow(3)
    }
    private fun drawFilter(canvas: Canvas): Unit {
        val output: Boolean = key == P.FILTER
        val stages: Int = settings.advanced.words[9]
        caption(canvas, if (output) "输出滤波 · 原版 / 保留全频" else "交叉支路 · 归一化低通包络")
        grid(canvas, "0 dB", "−60", "20 Hz", "20k Hz")
        trace(canvas, secondary, true) { 1.0 }
        trace(canvas) { position: Double ->
            val frequency: Double = 20.0 * 1000.0.pow(position)
            if (frequency > rate * .45) return@trace Double.NaN
            val gain: Double = if (output) {
                if (settings.filter == 1) 1.0 else postGain(frequency)
            } else {
                val pole: Double = exp(-2 * PI * settings.cutoff / rate)
                val w: Double = 2 * PI * frequency / rate
                ((1 - pole) / sqrt(1 + pole * pole - 2 * pole * cos(w))).pow(stages)
            }
            (20 * log10(gain.coerceAtLeast(1e-9)) + 60) / 60
        }
        if (!output) marker(canvas, fx(n(P.cutoff)))
        footer(canvas, if (output) (if (settings.filter == 0) "10 Hz 高通 + 3级 18k 低通" else "保留全频 · 不含附加输出滤波") else (stages * 6).toString() + " dB/oct · 只影响交叉支路")
    }
    private fun drawCenter(canvas: Canvas): Unit {
        caption(canvas, "中央 M 音色补偿 · 非人声提取")
        val head: RectF = drawHead(canvas)
        val amount: Double = n(A.amount) / 100
        fillPaint.color = ColorUtils.setAlphaComponent(color, (25 + 120 * amount).roundToInt())
        val bounds: RectF = RectF(head.centerX() - head.width() * .13f, head.top + head.height() * .10f,
            head.centerX() + head.width() * .13f, head.bottom - head.height() * .12f)
        canvas.drawRoundRect(bounds, d(3f), d(3f), fillPaint)
        earPath(canvas, head, false, .35, 0.0, secondary); earPath(canvas, head, true, .35, 0.0, third)
        footer(canvas, "补偿强度 " + fmt(n(A.amount), 0) + "% · Side 保留处理比例")
    }
    private fun bar(canvas: Canvas, index: Int, amount: Double, label: String, number: String, tint: Int): Unit {
        val cell: Float = plot.width() / 3
        val x: Float = plot.left + index * cell + cell * .20f
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x45)
        canvas.drawRoundRect(RectF(x, py(amount), x + cell * .60f, plot.bottom), d(2f), d(2f), fillPaint)
        text(canvas, number, x + cell * .30f, max(plot.top + d(9f), py(amount) - d(3f)), cell, tint, Paint.Align.CENTER)
        text(canvas, label, x + cell * .30f, plot.bottom + d(11f), cell, foreground, Paint.Align.CENTER)
    }
    private fun drawMix(canvas: Canvas): Unit {
        val mix: Double = n(P.mix) / 100
        caption(canvas, "原声与处理声 · 线性混合")
        bar(canvas, 0, 1 - mix, "原声", fmt(100 * (1 - mix), 0) + "%", secondary)
        bar(canvas, 1, mix, "效果", fmt(100 * mix, 0) + "%", color)
        bar(canvas, 2, 10.0.pow(n(P.output) / 20), "输出", fmt(n(P.output)) + "dB", third)
        footer(canvas, "0% 混合仍受输出衰减控制")
    }
    private fun drawMonitorChannels(canvas: Canvas): Unit {
        caption(canvas, "保存参数模型 + 试听点输出峰值")
        box(canvas, "理论直达 / 交叉", .0, .95, 1.0, .66, secondary)
        box(canvas, "同相中央响应", .0, .52, 1.0, .23)
        text(canvas, "真实峰值见下方动态图", plot.centerX(), py(.02), plot.width(), third, Paint.Align.CENTER)
        footer(canvas, "不等于耳机／房间实测频响")
    }
    private fun drawMeasurement(canvas: Canvas): Unit {
        caption(canvas, "同一输入 · 预热 100 ms + 测量 2 s")
        box(canvas, "同一输入", .18, .95, .82, .69, secondary)
        box(canvas, "处理 A", .0, .52, .43, .23)
        box(canvas, "处理 B", .57, .52, 1.0, .23, third)
        linePaint.color = secondary
        canvas.drawLine(px(.5), py(.69), px(.22), py(.52), linePaint)
        canvas.drawLine(px(.5), py(.69), px(.78), py(.52), linePaint)
        text(canvas, if (model.matchingReady) "测量就绪 · 可固定匹配" else "保存 A/B 后由原生按钮测量", plot.centerX(), py(.02), plot.width(), color, Paint.Align.CENTER)
        footer(canvas, "只衰减较响组 · 不持续追踪")
    }
    private fun drawComparison(canvas: Canvas): Unit {
        caption(canvas, "当前页面快照 · 不写入预设")
        box(canvas, "A " + (if (model.savedA) "已保存" else "未保存"), .0, .95, .45, .57, secondary)
        box(canvas, "B " + (if (model.savedB) "已保存" else "未保存"), .55, .95, 1.0, .57, third)
        box(canvas, auditionName(model.audition), .0, .34, 1.0, .02)
        footer(canvas, if (model.matching) "固定匹配开启 · 不提升较小声组" else "匹配关闭 · 保留各自输出电平")
    }
    private fun auditionName(value: Int): String = listOf("正常处理", "本模块原声", "效果差分（−6 dB）", "仅交叉支路", "快照 A", "快照 B", "测量 A/B").getOrElse(value) { "正常处理" }
    private fun details(): String {
        val controls: List<BassParameters.Control> = when (focus) {
            CrossfeedChartFocus.RESPONSE -> listOf(P.cutoff, P.attenuation)
            CrossfeedChartFocus.PATH -> listOf(A.left, A.right)
            CrossfeedChartFocus.DELAY -> listOf(P.delay, A.leftDelay, A.rightDelay)
            CrossfeedChartFocus.FILTER -> listOf(P.cutoff)
            CrossfeedChartFocus.CENTER -> listOf(A.amount)
            CrossfeedChartFocus.LEVEL -> listOf(P.mix, P.output)
            else -> emptyList()
        }
        val lines: MutableList<String> = mutableListOf("耳机交叉馈送 · " + rate + " Hz · " + (if (model.legacyView(focus, key)) "原三档示意" else "保存的专业参数模型"),
            if (enabled) "当前焦点参数可用；图形表示参数目标与相对关系。" else model.reason(focus, key),
            "原版档位：" + model.legacyName + "；专业调节：" + (if (settings.professional) "开启，接管原三档" else "关闭，实际使用原三档"))
        controls.forEach { control: BassParameters.Control -> lines += control.title + " " + control.label(model.value(control)) }
        if (focus == CrossfeedChartFocus.PATH || focus == CrossfeedChartFocus.DELAY) lines += "左右独立：" + (if (model.separate) "开启，双向值接管共用延时" else "关闭，使用共用衰减与附加延时")
        if (focus == CrossfeedChartFocus.FILTER) lines += "输出方式：" + (if (settings.filter == 0) "原版滤波" else "保留全频") +
            "；交叉支路滤波精调：" + (if (model.flag(8)) "开启" else "关闭，实际保持一级 6 dB/oct") + "；保存级数 " + settings.advanced.words[9]
        if (focus == CrossfeedChartFocus.CENTER) lines += "中央补偿：" + (if (model.flag(1)) "开启" else "关闭")
        if (focus == CrossfeedChartFocus.MONITOR) lines += "响应与电平诊断：" + (if (model.flag(10)) "开启" else "关闭")
        lines += when (focus) {
            CrossfeedChartFocus.RESPONSE -> "原版为三档离散旧算法，不能将保存的专业参数曲线当成原三档真实响应。聚焦原三档只显示相对强弱位置。专业模型使用 Bauer／BS2B 的直达与低通交叉支路，交叉衰减是正 dB，越大越弱；提高截止频率会扩展参与交叉混入的范围。专业关闭时其它焦点以灰色显示保存的专业参数预览，不改变真实音频路径。耳图不是声源距离、位置或实际混入能量测量。"
            CrossfeedChartFocus.PATH -> "独立调节开启后，左右交叉衰减分别接管两个方向，正 dB 越大，对侧混入越弱；不同于正 dB 直接增益。独立参数默认继承原共用值，具体初始化仍由原编辑器负责。新增路径不改变原三档旧算法，需要专业调节及进阶驱动。图纸只读取保存值，不自动启用独立开关。"
            CrossfeedChartFocus.DELAY -> "0–500 μs 是交叉支路的额外延迟，不是毫秒，也不包含原 BS2B 固有的频变延迟。独立路径开启后两个方向的独立延时接管共用值，共用值保留；灰色图显示当前未生效控制的保存目标。左图只计算附加整采样延迟加相邻两抽头线性分数插值的相位，不包括 Bauer 与输出滤波相位。样本数 = μs × 内部采样率 / 1000000；0 μs 不表示整个处理器零群延迟。"
            CrossfeedChartFocus.FILTER -> "交叉支路滤波关闭时保持一级 6 dB/oct；开启后为 1–4 个同极点低通串联，6／12／18／24 dB/oct，不是单个 Butterworth 高阶节。输出滤波是另一组：原版方式为 10 Hz 高通加三个约 18 kHz 一阶低通（目标最多内部采样率 45%）；保留全频移除此组。输出滤波作用于处理声，干湿混合的原声支路不额外经过它。右图为归一化滤波目标，左图包含完整专业矩阵。"
            CrossfeedChartFocus.CENTER -> "中央音色补偿处理左右相加的共同 M 成分，不是人声提取。100% 时使交叉处理前后的中央成分一致，仍受所选输出滤波、混合和输出衰减影响；处理后的 Side 保留。百分比是补偿程度，不是 dB 增益。右图高亮表示补偿强度，左图同相／反相模型展示实际配置响应。"
            CrossfeedChartFocus.LEVEL -> "专业干湿采用线性混合，0% 保留本模块原声；输出衰减随后共同作用，因此 0% 混合仍会被衰减。左右输入内容与相位会影响最终功率，柱形只表示比例，不是实时 RMS。原版三档不使用这些独立专业参数。"
            CrossfeedChartFocus.MONITOR -> "诊断默认关闭。静态专业模型表示保存参数的理论直达、交叉与同相中央响应，不代表耳机或房间实测。输出峰值的测点在本模块临时试听之后，后续效果仍会改变音频；不是独立交叉支路 RMS，也不是设备最终输出电平。采样开关和UI折叠仍由顶部控制，与音频参数无关。"
            CrossfeedChartFocus.MATCH -> "先保存 A/B，再用同一输入同时处理两份配置；预热 100 ms 后测量 2 秒。匹配使用固定衰减，只降低较响组，不提升电平，不持续追踪，不是 LUFS 或设备最终响度。左图 0–12 dB 是解释相对差异的参考坐标，不是实际读数或算法限额。当前匹配" + (if (model.matching) "开启" else "关闭") + "；测量" + (if (model.matchingReady) "就绪" else "未就绪") + "。图纸不会发起重测。"
            CrossfeedChartFocus.AUDITION -> "当前路径：" + auditionName(model.audition) + "。A " + (if (model.savedA) "已保存" else "未保存") + "，B " + (if (model.savedB) "已保存" else "未保存") + "。七项为正常、本模块原声、−6 dB差分、仅交叉支路、快照A、快照B和电平测量。快照只存于页面会话，图纸不读取其私有参数；静图为保存参数，实时图为当前试听输出。所有保存、试听、停止和匹配操作保留在原生按钮中。"
        }
        lines += "频响固定 20 Hz–20 kHz、−60 至 +18 dB；附加相位固定 ±180°。这些图形为保存参数模型；实时音乐读数请看下方动态图与保留的原生说明。"
        return lines.joinToString(System.lineSeparator() + System.lineSeparator())
    }
    override fun onAttachedToWindow(): Unit { super.onAttachedToWindow(); if (active) updateResponse() }
    override fun onDetachedFromWindow(): Unit {
        generation++; CrossfeedStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}
