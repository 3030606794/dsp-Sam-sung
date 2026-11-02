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
import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbController
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** Room/SF observers only. ViPER reverb, parameter storage and A/B operations remain independent. */
internal class RoomReverbParameterChart(context: Context) : RoomReverbFocusStaticBase(context, false)
internal class RoomReverbIllustrationChart(context: Context) : RoomReverbFocusStaticBase(context, true)

private data class RoomReverbFocusSnapshot(
    val settings: P.Settings, val rate: Int, val moduleOn: Boolean, val supported: Boolean,
    val audition: Int, val matching: Boolean, val matchingReady: Boolean, val savedA: Boolean, val savedB: Boolean
) {
    fun active(focus: RoomReverbChartFocus, key: String): Boolean {
        if (!moduleOn) return false
        if (focus == RoomReverbChartFocus.ROOM && key == P.BASE) return true
        if (!supported) return false
        if (focus == RoomReverbChartFocus.AUDITION && key in setOf("room_reverb_save_a", "room_reverb_save_b")) return true
        if (focus == RoomReverbChartFocus.ROOM) return true
        if (!settings.professional) return false
        return when (focus) {
            RoomReverbChartFocus.DECAY, RoomReverbChartFocus.EARLY -> settings.timeOn
            RoomReverbChartFocus.PREDELAY -> settings.predelayOn
            RoomReverbChartFocus.LEVELS -> settings.levelsOn && !settings.linked
            RoomReverbChartFocus.MIX -> settings.linked
            RoomReverbChartFocus.WIDTH -> settings.widthsOn
            RoomReverbChartFocus.TONE -> settings.toneOn
            RoomReverbChartFocus.OUTPUT -> settings.outputOn
            RoomReverbChartFocus.MONITOR -> settings.meters
            else -> true
        }
    }
    fun reason(focus: RoomReverbChartFocus): String = when {
        !moduleOn -> "场景混响关闭 · 保存目标预览"
        !supported -> "专业驱动未支持 · 参数预览"
        !settings.professional -> "原房型处理中 · 专业保存预览"
        focus == RoomReverbChartFocus.DECAY || focus == RoomReverbChartFocus.EARLY -> "时间精调关闭 · 保存目标预览"
        focus == RoomReverbChartFocus.PREDELAY -> "附加预延迟关闭 · 保存目标预览"
        focus == RoomReverbChartFocus.LEVELS && settings.linked -> "干湿联动接管 · 独立增益预览"
        focus == RoomReverbChartFocus.LEVELS -> "三路精调关闭 · 保存增益预览"
        focus == RoomReverbChartFocus.MIX -> "干湿联动关闭 · 比例目标预览"
        focus == RoomReverbChartFocus.WIDTH -> "宽度精调关闭 · 保存目标预览"
        focus == RoomReverbChartFocus.TONE -> "音色精调关闭 · 保存目标预览"
        focus == RoomReverbChartFocus.OUTPUT -> "输出增益关闭 · 保存目标预览"
        focus == RoomReverbChartFocus.MONITOR -> "实时电平关闭 · 测点示意"
        else -> "保存目标预览"
    }
    fun target(focus: RoomReverbChartFocus): P.Settings {
        if (focus == RoomReverbChartFocus.ROOM) return if (supported) settings else settings.copy(professional = false)
        val base: P.Settings = settings.copy(professional = true)
        return when (focus) {
            RoomReverbChartFocus.DECAY, RoomReverbChartFocus.EARLY -> base.copy(timeOn = true)
            RoomReverbChartFocus.PREDELAY -> base.copy(predelayOn = true)
            RoomReverbChartFocus.LEVELS -> base.copy(levelsOn = true, linked = false)
            RoomReverbChartFocus.MIX -> base.copy(linked = true)
            RoomReverbChartFocus.WIDTH -> base.copy(widthsOn = true)
            RoomReverbChartFocus.TONE -> base.copy(toneOn = true)
            RoomReverbChartFocus.OUTPUT -> base.copy(outputOn = true)
            else -> base
        }
    }
}

private data class RoomReverbStaticRequest(val settings: P.Settings, val rate: Int)
/** Single background worker with a bounded cache; every owner keeps only its newest waiting target. */
private object RoomReverbStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "room-reverb-focus-static").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    private data class Task(val request: RoomReverbStaticRequest, val callback: (RoomReverbDashboardModel) -> Unit)
    private class Owner {
        var running: RoomReverbStaticRequest? = null
        var waiting: Task? = null
        var future: Future<*>? = null
    }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<RoomReverbStaticRequest, RoomReverbDashboardModel> =
        object : LinkedHashMap<RoomReverbStaticRequest, RoomReverbDashboardModel>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RoomReverbStaticRequest, RoomReverbDashboardModel>?): Boolean = size > 16
        }
    @Synchronized fun release(owner: Any): Unit {
        owners.remove(owner)?.let { state -> state.waiting = null; state.future?.cancel(true); state.future = null }
    }
    @Synchronized fun request(owner: Any, request: RoomReverbStaticRequest, callback: (RoomReverbDashboardModel) -> Unit): Unit {
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
            val result: RoomReverbDashboardModel = synchronized(this) { cache[task.request] } ?: run {
                val values: Map<String, Any> = task.request.settings.values() + (P.ENABLED to true)
                RoomReverbDashboardModel.from(values, task.request.rate, true)
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

internal open class RoomReverbFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
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
    private var configuration: RoomReverbDashboardModel = RoomReverbDashboardModel.from(emptyMap<String, Any>())
    private var shown: RoomReverbDashboardModel = configuration
    private var snapshot: RoomReverbFocusSnapshot = snapshot(configuration)
    private var focus: RoomReverbChartFocus = RoomReverbChartFocus.ROOM
    private var key: String = P.BASE
    private var active: Boolean = true
    private var request: RoomReverbStaticRequest? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = snapshot.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x78)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x4c)
    private fun snapshot(model: RoomReverbDashboardModel): RoomReverbFocusSnapshot = RoomReverbFocusSnapshot(
        model.settings, model.rate, model.enabled, RoomReverbController.supported(context), RoomReverbController.audition,
        RoomReverbController.match, RoomReverbController.matchingReady(), RoomReverbController.hasComparison(0), RoomReverbController.hasComparison(1))
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
    fun setConfiguration(model: RoomReverbDashboardModel): Unit {
        configuration = model
        val next: RoomReverbFocusSnapshot = snapshot(model)
        if (next == snapshot) return
        snapshot = next; updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setFocus(focus: RoomReverbChartFocus, key: String): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; RoomReverbStaticWork.release(this); request = null }
        this.focus = focus; this.key = key
        updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) {
            generation++; RoomReverbStaticWork.release(this); request = null
            detailDialog?.dismiss(); detailDialog = null
        } else { updateTarget(); invalidate() }
    }
    private fun updateTarget(): Unit {
        if (!active) return
        val target: P.Settings = snapshot.target(focus).copy(meters = false)
        val next: RoomReverbStaticRequest = RoomReverbStaticRequest(target, configuration.rate)
        if (request == next) return
        request = next
        val token: Long = generation
        RoomReverbStaticWork.request(this, next) { value ->
            if (active && token == generation) { shown = value; invalidate() }
        }
    }
    private fun updateDescription(): Unit { contentDescription = details() }
    private fun fmt(value: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%." + decimals + "f", value)
    private fun signed(value: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%+." + decimals + "f", value)
    private fun px(value: Double): Float = plot.left + plot.width() * value.coerceIn(0.0, 1.0).toFloat()
    private fun py(value: Double): Float = plot.bottom - plot.height() * value.coerceIn(0.0, 1.0).toFloat()
    private fun fx(frequency: Double): Float = px(ln(frequency.coerceIn(20.0, 20000.0) / 20) / ln(1000.0))
    private fun timeAt(position: Double): Double = 100 * (exp(position * ln(601.0)) - 1)
    private fun timeX(milliseconds: Double): Float = px(ln(1 + milliseconds.coerceAtLeast(0.0) / 100) / ln(601.0))
    private fun totalDb(model: RoomReverbDashboardModel, channel: Int): Double {
        // Native linked dry uses the original input, not the preset's already-attenuated dry branch.
        val baseDb: Double = if (channel == 0 && model.linked) 0.0 else model.baseLevelDb[channel]
        return baseDb + model.relativeGainDb[channel]
    }
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, space: Float,
        tint: Int = foreground, align: Paint.Align = Paint.Align.LEFT): Unit {
        if (space <= 0) return
        textPaint.color = tint; textPaint.textAlign = align
        val count: Int = textPaint.breakText(value, true, space, null)
        canvas.drawText(if (count == value.length) value else value.take((count - 1).coerceAtLeast(0)) + "…", x, y, textPaint)
    }
    private fun caption(canvas: Canvas, value: String): Unit = text(canvas, value, d(3f), d(11f), width - d(6f), color)
    private fun footer(canvas: Canvas, value: String): Unit = text(canvas,
        if (enabled) value else snapshot.reason(focus), d(3f), height - d(3f), width - d(6f), ColorUtils.setAlphaComponent(foreground, 0xa0))
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
        RoomReverbChartFocus.ROOM, RoomReverbChartFocus.DECAY -> drawDecay(canvas)
        RoomReverbChartFocus.EARLY -> drawEarly(canvas)
        RoomReverbChartFocus.PREDELAY -> drawPredelay(canvas)
        RoomReverbChartFocus.LEVELS, RoomReverbChartFocus.MIX -> drawLevels(canvas)
        RoomReverbChartFocus.WIDTH -> drawWidth(canvas)
        RoomReverbChartFocus.TONE -> drawTone(canvas)
        RoomReverbChartFocus.OUTPUT -> drawOutput(canvas)
        RoomReverbChartFocus.MONITOR -> drawMonitor(canvas)
        RoomReverbChartFocus.MATCH -> drawMatch(canvas)
        RoomReverbChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        RoomReverbChartFocus.ROOM, RoomReverbChartFocus.EARLY -> drawRoom(canvas)
        RoomReverbChartFocus.DECAY -> drawTail(canvas)
        RoomReverbChartFocus.PREDELAY -> drawArrival(canvas)
        RoomReverbChartFocus.LEVELS -> drawLevelIllustration(canvas)
        RoomReverbChartFocus.MIX -> drawMix(canvas)
        RoomReverbChartFocus.WIDTH -> drawWidthFans(canvas)
        RoomReverbChartFocus.TONE -> drawToneCloud(canvas)
        RoomReverbChartFocus.OUTPUT -> drawLevelIllustration(canvas)
        RoomReverbChartFocus.MONITOR -> drawMonitorChannels(canvas)
        RoomReverbChartFocus.MATCH -> drawMeasurement(canvas)
        RoomReverbChartFocus.AUDITION -> drawComparison(canvas)
    }
    private fun drawDecay(canvas: Canvas): Unit {
        caption(canvas, (if (focus == RoomReverbChartFocus.ROOM) shown.roomName + " · " else "参考 RT60 ") + fmt(shown.rt60Seconds, 2) + " s")
        grid(canvas, "0 dB", "−90", "0", "60 s")
        trace(canvas) { position: Double ->
            val time: Double = timeAt(position)
            if (time < shown.lateOnsetMs) Double.NaN else (90 - 60 * (time - shown.lateOnsetMs) / (shown.rt60Seconds * 1000)) / 90
        }
        marker(canvas, timeX(shown.lateOnsetMs), secondary)
        point(canvas, timeX(shown.lateOnsetMs + shown.rt60Seconds * 1000), py(1.0 / 3))
        if (focus == RoomReverbChartFocus.ROOM) {
            linePaint.color = secondary
            shown.earlyTapMs[0].forEach { time: Double -> canvas.drawLine(timeX(time), py(.12), timeX(time), py(.30), linePaint) }
        }
        footer(canvas, if (shown.stabilityProtected) "参考包络 · 已限制低频反馈" else "参考尾音包络 · 非实测房间 RT60")
    }
    private fun drawEarly(canvas: Canvas): Unit {
        caption(canvas, "早反射尺度 " + fmt(shown.earlyScale, 2) + "× · 抽头时序")
        grid(canvas, "L", "R", "0 ms", "500 ms")
        shown.earlyTapMs.forEachIndexed { channel: Int, times: DoubleArray ->
            val tint: Int = if (channel == 0) color else third
            val base: Float = py(if (channel == 0) .56 else .06)
            linePaint.color = tint
            times.forEach { time: Double ->
                val x: Float = px(time / 500)
                canvas.drawLine(x, base, x, base - plot.height() * .30f, linePaint)
            }
        }
        footer(canvas, "18 + 18 抽头位置 · 等高不代表 IR 幅度")
    }
    private fun drawPredelay(canvas: Canvas): Unit {
        caption(canvas, "总湿声附加 " + fmt(shown.predelayMs, 1) + " ms")
        grid(canvas, "干声", "湿声", "0 ms", "200 ms")
        point(canvas, plot.left, py(.80), secondary)
        linePaint.color = color
        canvas.drawLine(px(shown.predelayMs / 200), py(.45), px(shown.predelayMs / 200), py(.1), linePaint)
        point(canvas, px(shown.predelayMs / 200), py(.45))
        footer(canvas, "房型尾音内延迟 " + fmt(shown.lateOnsetMs - shown.predelayMs, 1) + " ms 保留")
    }
    private fun bar(canvas: Canvas, index: Int, count: Int, amount: Double, label: String, number: String, tint: Int): Unit {
        val cell: Float = plot.width() / count; val x: Float = plot.left + index * cell + cell * .20f
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x45)
        canvas.drawRoundRect(RectF(x, py(amount), x + cell * .60f, plot.bottom), d(2f), d(2f), fillPaint)
        text(canvas, number, x + cell * .30f, max(plot.top + d(9f), py(amount) - d(3f)), cell, tint, Paint.Align.CENTER)
        text(canvas, label, x + cell * .30f, plot.bottom + d(11f), cell, foreground, Paint.Align.CENTER)
    }
    private fun drawLevels(canvas: Canvas): Unit {
        caption(canvas, if (shown.linked) "联动目标系数 · 干声使用原输入" else "房型基底 + 相对增益 + 输出 · dB")
        grid(canvas, "+12 dB", "−60", "", "")
        val labels: List<String> = listOf("干声", "早反射", "尾音")
        val colors: List<Int> = listOf(secondary, color, third)
        repeat(3) { channel: Int ->
            val value: Double = totalDb(shown, channel)
            bar(canvas, channel, 3, if (value.isFinite()) (value + 60) / 72 else 0.0, labels[channel],
                if (value.isFinite()) signed(value) else "−∞", colors[channel])
        }
        footer(canvas, "配置增益系数 · 不等于真实三路能量")
    }
    private fun drawWidth(canvas: Canvas): Unit {
        caption(canvas, "早 " + signed(shown.earlyWidth, 3) + " · 尾 " + fmt(shown.lateWidth, 3))
        grid(canvas, "+1", "−1", "Side 输入 −1", "+1")
        trace(canvas, secondary, true) { it }
        trace(canvas, color) { .5 + (it - .5) * shown.earlyWidth }
        trace(canvas, third) { .5 + (it - .5) * shown.lateWidth }
        footer(canvas, "早反射负值为交叉成像 · M 系数不变")
    }
    private fun drawTone(canvas: Canvas): Unit {
        caption(canvas, "总湿声滤波 / 阻尼 / 房型固定滤波")
        grid(canvas, "0 dB", "−60", "20 Hz", "20k Hz")
        val arrays: List<DoubleArray> = listOf(shown.wetFilterDb, shown.dampingDb, shown.inputOutputDb)
        val colors: List<Int> = listOf(color, secondary, third)
        arrays.forEachIndexed { row: Int, values: DoubleArray ->
            path.rewind()
            values.forEachIndexed { index: Int, value: Double ->
                val x: Float = fx(shown.frequencies[index]); val y: Float = py((value + 60) / 60)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            stroke(canvas, colors[row], row != 0)
        }
        if (shown.lowCutHz > 0) marker(canvas, fx(shown.lowCutHz), color)
        if (shown.highCutHz > 0) marker(canvas, fx(shown.highCutHz), color)
        marker(canvas, fx(shown.dampHz), secondary)
        footer(canvas, "低/高切 0 Hz 旁路 · 阻尼不等于输出 EQ")
    }
    private fun drawOutput(canvas: Canvas): Unit {
        caption(canvas, "整体输出 " + fmt(shown.outputDb, 1) + " dB")
        grid(canvas, "+1", "−1", "输入 −1", "+1")
        trace(canvas, secondary, true) { it }
        trace(canvas) { .5 + (it - .5) * 10.0.pow(shown.outputDb / 20) }
        footer(canvas, "干声、早反射、尾音一起衰减")
    }
    private fun box(canvas: Canvas, label: String, left: Double, top: Double, right: Double, bottom: Double, tint: Int = color): Unit {
        val bounds: RectF = RectF(px(left), py(top), px(right), py(bottom))
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x24); canvas.drawRoundRect(bounds, d(3f), d(3f), fillPaint)
        text(canvas, label, bounds.centerX(), bounds.centerY() + d(3f), bounds.width() - d(3f), tint, Paint.Align.CENTER)
    }
    private fun drawMonitor(canvas: Canvas): Unit {
        caption(canvas, "正常湿声观察 与 当前试听输出")
        box(canvas, "原输入 → 正常房型", .0, .95, 1.0, .68, secondary)
        box(canvas, "正常湿声", .0, .50, .46, .22)
        box(canvas, "试听输出", .56, .50, 1.0, .22, third)
        linePaint.color = secondary
        canvas.drawLine(px(.5), py(.68), px(.23), py(.50), linePaint); canvas.drawLine(px(.5), py(.68), px(.78), py(.50), linePaint)
        footer(canvas, "真实输出峰值 / RMS / 超限帧见下方")
    }
    private fun drawMatch(canvas: Canvas): Unit {
        caption(canvas, "相对最低 RMS · 固定衰减参考")
        grid(canvas, "0 dB", "−18", "相对差 0", "+18 dB")
        trace(canvas) { 1 - it }; trace(canvas, secondary, true) { 1.0 }
        footer(canvas, "参考取原声、A、B 的最低能量")
    }
    private fun drawAudition(canvas: Canvas): Unit {
        caption(canvas, "当前路径 · " + auditionName(snapshot.audition))
        val labels: List<String> = listOf("正常", "原声", "早反射", "尾音", "总湿声", "快照 A", "快照 B", "测量")
        labels.forEachIndexed { index: Int, label: String ->
            val row: Int = index / 4; val column: Int = index % 4
            box(canvas, label, column / 4.0 + .015, .95 - row * .53, (column + 1) / 4.0 - .015, .62 - row * .53,
                if (index == snapshot.audition) color else secondary)
        }
        footer(canvas, "原生按钮管理试听 · 图纸只读")
    }
    private fun drawRoom(canvas: Canvas): Unit {
        caption(canvas, "早反射时间 " + fmt(shown.earlyScale, 2) + "× · 相对路径")
        val amount: Double = ((shown.earlyScale - .5) / 2).coerceIn(0.0, 1.0)
        val walls: RectF = RectF(px(.14 - .10 * amount), py(.95), px(.86 + .10 * amount), py(.07))
        fillPaint.shader = LinearGradient(walls.left, walls.top, walls.right, walls.bottom,
            ColorUtils.setAlphaComponent(secondary, 0x22), ColorUtils.setAlphaComponent(color, 0x06), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(walls, d(10f), d(10f), fillPaint); fillPaint.shader = null
        linePaint.color = ColorUtils.setAlphaComponent(secondary, 0x90)
        canvas.drawRoundRect(walls, d(10f), d(10f), linePaint)
        val inner: RectF = RectF(walls.left + d(4f), walls.top + d(4f), walls.right - d(4f), walls.bottom - d(4f))
        canvas.drawRoundRect(inner, d(7f), d(7f), gridPaint)
        val cx: Float = inner.centerX(); val base: Float = inner.bottom - d(6f)
        path.rewind(); path.moveTo(cx, base)
        path.lineTo(inner.left + d(3f), inner.top + inner.height() * .27f)
        path.lineTo(inner.right - d(3f), inner.top + inner.height() * .20f)
        path.lineTo(cx, base - inner.height() * .40f)
        linePaint.color = ColorUtils.setAlphaComponent(color, 0xc0); canvas.drawPath(path, linePaint)
        path.rewind(); path.moveTo(cx, base); path.lineTo(inner.right - d(4f), inner.top + inner.height() * .45f)
        path.lineTo(inner.left + d(4f), inner.top + inner.height() * .56f); path.lineTo(cx, base - inner.height() * .40f)
        linePaint.color = ColorUtils.setAlphaComponent(third, 0xa0); canvas.drawPath(path, linePaint)
        repeat(3) { ring: Int ->
            val r: Float = inner.width() * (.13f + ring * .10f)
            linePaint.color = ColorUtils.setAlphaComponent(secondary, 100 - ring * 20)
            canvas.drawArc(RectF(cx - r, base - r, cx + r, base + r), 205f, 130f, false, linePaint)
        }
        point(canvas, cx, base, secondary)
        footer(canvas, "反射时间与扩散示意 · 非真实房间尺寸")
    }
    private fun drawTail(canvas: Canvas): Unit {
        caption(canvas, "参考尾音 " + fmt(shown.rt60Seconds, 2) + " s · 渐隐示意")
        val start: Float = timeX(shown.lateOnsetMs)
        val end: Float = timeX(shown.lateOnsetMs + shown.rt60Seconds * 1000)
        repeat(3) { row: Int ->
            val y: Float = plot.top + plot.height() * (.26f + .24f * row)
            val tint: Int = listOf(color, secondary, third)[row]
            fillPaint.shader = LinearGradient(start, y, max(start + d(1f), end), y,
                ColorUtils.setAlphaComponent(tint, 0xb0 - row * 15), ColorUtils.setAlphaComponent(tint, 0x04), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(start, y - d(7f), max(start + d(1f), end), y + d(7f)), d(7f), d(7f), fillPaint)
            fillPaint.shader = null
        }
        marker(canvas, end)
        footer(canvas, "同一参考包络分层绘制 · 非三频段 RT60")
    }
    private fun drawArrival(canvas: Canvas): Unit {
        caption(canvas, "干声时序保留 · 完整湿声后移")
        grid(canvas, "干声", "湿声", "0 ms", "500 ms")
        point(canvas, plot.left, py(.78), secondary)
        val early: Double = shown.earlyTapMs[0].first()
        point(canvas, px(early / 500), py(.40), color)
        point(canvas, px(shown.lateOnsetMs / 500), py(.12), third)
        marker(canvas, px(shown.predelayMs / 500), secondary)
        footer(canvas, "早反射源抽头与尾音起点配置示意")
    }
    private fun drawLevelIllustration(canvas: Canvas): Unit {
        caption(canvas, "房型基底各不同 · 相对增益示意")
        val colors: List<Int> = listOf(secondary, color, third)
        val labels: List<String> = listOf("干声", "早反射", "尾音")
        repeat(3) { channel: Int ->
            val gain: Double = totalDb(shown, channel)
            val amplitude: Double = if (gain.isFinite()) 10.0.pow(gain / 20) else 0.0
            val x: Float = px((channel + .5) / 3)
            val radius: Float = plot.width() / 8 * (.25 + .75 * (amplitude / 2).coerceIn(0.0, 1.0)).toFloat()
            fillPaint.color = ColorUtils.setAlphaComponent(colors[channel], 0x25)
            canvas.drawCircle(x, plot.centerY(), radius + d(3f), fillPaint)
            fillPaint.color = ColorUtils.setAlphaComponent(colors[channel], 0x90); canvas.drawCircle(x, plot.centerY(), radius, fillPaint)
            text(canvas, labels[channel], x, py(.15), plot.width() / 3, colors[channel], Paint.Align.CENTER)
        }
        footer(canvas, "参考系数大小 · 不表示真实播放能量")
    }
    private fun drawMix(canvas: Canvas): Unit {
        caption(canvas, "联动 " + fmt(shown.mix * 100, 1) + "% · 线性比例")
        bar(canvas, 0, 2, 1 - shown.mix, "原声", fmt((1 - shown.mix) * 100, 1) + "%", secondary)
        bar(canvas, 1, 2, shown.mix, "房型湿声", fmt(shown.mix * 100, 1) + "%", color)
        footer(canvas, "接管三路精调 · 0% 为原输入")
    }
    private fun fan(canvas: Canvas, radius: Float, width: Double, tint: Int): Unit {
        val cx: Float = plot.centerX(); val base: Float = plot.bottom
        if (abs(width) < 1e-9) {
            linePaint.color = tint; canvas.drawLine(cx, base, cx, base - radius, linePaint)
            return
        }
        val spread: Double = 80 * abs(width)
        val oval: RectF = RectF(cx - radius, base - radius, cx + radius, base + radius)
        fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x26)
        canvas.drawArc(oval, (270 - spread).toFloat(), (2 * spread).toFloat(), true, fillPaint)
        linePaint.color = tint; canvas.drawArc(oval, (270 - spread).toFloat(), (2 * spread).toFloat(), false, linePaint)
        if (width < 0) {
            canvas.drawLine(cx - radius * .6f, base - radius * .7f, cx + radius * .4f, base - radius * .2f, linePaint)
            canvas.drawLine(cx + radius * .6f, base - radius * .7f, cx - radius * .4f, base - radius * .2f, linePaint)
        }
    }
    private fun drawWidthFans(canvas: Canvas): Unit {
        caption(canvas, "早反射 / 尾音 · 两层宽度")
        val radius: Float = min(plot.width() * .48f, plot.height() * .9f)
        repeat(3) { ring: Int ->
            val r: Float = radius * (ring + 1) / 3
            canvas.drawArc(RectF(plot.centerX() - r, plot.bottom - r, plot.centerX() + r, plot.bottom + r), 180f, 180f, false, gridPaint)
        }
        fan(canvas, radius, shown.lateWidth, third)
        fan(canvas, radius * .70f, shown.earlyWidth, color)
        point(canvas, plot.centerX(), plot.bottom, secondary)
        footer(canvas, if (shown.earlyWidth < 0) "早反射交叉成像 · 尾音保持正宽度" else "宽度相对示意 · 不是声源角度")
    }
    private fun drawToneCloud(canvas: Canvas): Unit {
        caption(canvas, "低频 / 主体 / 高频 · 尾音明暗")
        val centers: List<Double> = listOf(80.0, 1000.0, 8000.0)
        val colors: List<Int> = listOf(color, secondary, third)
        repeat(3) { band: Int ->
            val index: Int = shown.frequencies.indices.minByOrNull { abs(ln(shown.frequencies[it] / centers[band])) } ?: 0
            val gain: Double = 10.0.pow((shown.wetFilterDb[index] + shown.dampingDb[index]) / 20)
            val x: Float = px((band + .5) / 3)
            val radius: Float = plot.width() / 7 * (.30 + .70 * gain).toFloat()
            fillPaint.color = ColorUtils.setAlphaComponent(colors[band], (30 + 100 * gain).roundToInt().coerceIn(20, 160))
            canvas.drawCircle(x, plot.centerY(), radius + d(3f), fillPaint)
            fillPaint.color = ColorUtils.setAlphaComponent(colors[band], (60 + 145 * gain).roundToInt().coerceIn(30, 220))
            canvas.drawCircle(x, plot.centerY(), radius, fillPaint)
        }
        footer(canvas, "湿声滤波与阻尼保留 · 同一输入参考")
    }
    private fun drawMonitorChannels(canvas: Canvas): Unit {
        caption(canvas, "正常湿声与试听输出分开观察")
        box(canvas, "正常湿声 FFT / 相位", .0, .95, 1.0, .68)
        box(canvas, "当前试听 RMS / 峰值", .0, .53, 1.0, .26, third)
        text(canvas, "两处测点不可混当同一路", plot.centerX(), py(.03), plot.width(), secondary, Paint.Align.CENTER)
        footer(canvas, "参数图是预测 · 下方动态图读取音频")
    }
    private fun drawMeasurement(canvas: Canvas): Unit {
        caption(canvas, "同一输入 · 两套房型预热后测量")
        box(canvas, "原声", .02, .95, .30, .68, secondary)
        box(canvas, "房型 A", .36, .95, .64, .68)
        box(canvas, "房型 B", .70, .95, .98, .68, third)
        box(canvas, "共同 RMS 基准取三者最小值", .0, .45, 1.0, .15)
        footer(canvas, "预热随 RT/预延迟变化 · 测量 2 秒")
    }
    private fun drawComparison(canvas: Canvas): Unit {
        caption(canvas, "当前页面快照 · 原房型或精调")
        box(canvas, "A " + (if (snapshot.savedA) "已保存" else "未保存"), .0, .95, .45, .57, secondary)
        box(canvas, "B " + (if (snapshot.savedB) "已保存" else "未保存"), .55, .95, 1.0, .57, third)
        box(canvas, auditionName(snapshot.audition), .0, .34, 1.0, .02)
        footer(canvas, "静图为保存参数 · 实际试听读数在下方")
    }
    private fun auditionName(value: Int): String = listOf("正常处理", "本模块原声", "仅早反射", "仅后期尾音", "完整湿声", "快照 A", "快照 B", "测量 A/B").getOrElse(value) { "正常处理" }
    private fun details(): String {
        val settings: P.Settings = snapshot.settings
        val values: Map<String, Any> = settings.values()
        val controls: List<BassParameters.Control> = when (focus) {
            RoomReverbChartFocus.ROOM -> P.timeNumbers + P.widthNumbers
            RoomReverbChartFocus.DECAY -> listOf(P.rt60)
            RoomReverbChartFocus.EARLY -> listOf(P.earlyScale)
            RoomReverbChartFocus.PREDELAY -> listOf(P.predelay)
            RoomReverbChartFocus.LEVELS -> P.levelNumbers
            RoomReverbChartFocus.MIX -> listOf(P.mix)
            RoomReverbChartFocus.WIDTH -> P.widthNumbers
            RoomReverbChartFocus.TONE -> P.toneNumbers
            RoomReverbChartFocus.OUTPUT -> listOf(P.output)
            else -> emptyList()
        }
        val lines: MutableList<String> = mutableListOf(P.baseName(settings.base) + " · " + configuration.rate + " Hz · " +
            (if (settings.professional) "专业精调" else "原房型"), if (enabled) "参数模型与相对示意，不是房间实测。" else snapshot.reason(focus))
        controls.forEach { control: BassParameters.Control -> lines += control.title + " " + control.label(P.value(values, control)) }
        lines += "房型基底干／早反射／尾音增益 " + configuration.baseLevelDb.joinToString(" / ") { fmt(it, 1) } + " dB；各房型不同。"
        lines += when (focus) {
            RoomReverbChartFocus.ROOM -> "保留原13个SF房型，各自RT60、早反射时间尺度、立体声宽度、干／早／尾基础电平、固定滤波与内部延时均不同。选择房型会由原编辑器加载该房型原参数并关闭精调分组。房型墙体与路径只解释反射时间尺度，不能换算真实房间尺寸、距离或声源方位。"
            RoomReverbChartFocus.DECAY -> "RT60为算法尾音参考时间，10–3000厘秒对应0.10–30.00秒；实际尾音仍随频率、阻尼、调制与输入改变。左图使用归一化指数参考包络，不是实测脉冲或真实音乐RT60。时间精调会一并接管早反射尺度；RT很长时原生会限制低频反馈，静态模型如实显示保护状态，不改变保存值。右图三层只是同一个参考尾音的视觉分层，不代表三频段不同RT60。"
            RoomReverbChartFocus.EARLY -> "早反射时间尺度为0.50–2.50倍，按当前房型原始抽头长度及内部采样率缩放；原房型自带尺度也不同。左图为原生18个左、18个右源抽头的时间位置，等高竖线不代表真实幅度，不能当完整IR；后续全通、滤波、交叉与调制会继续改变波形。右图仅是反射路径示意，不是物理距离测量。"
            RoomReverbChartFocus.PREDELAY -> "原始整数除以10为毫秒，最多200ms，完整早反射与尾音湿声一起延后，干声时序不变。原房型自带的尾音延迟继续保留，0ms只撤销附加延迟；源抽头位置也不等于完整处理首次到达时间。灰色图是未开启时的保存目标，不会启用真实预延迟。"
            RoomReverbChartFocus.LEVELS -> "干声、早反射、尾音滑杆都是相对当前房型的dB修正；0dB保持该房型原电平，不是三路都变成单位增益。左图合并房型基底、相对修正与输出衰减作为系数目标；图示大小不代表真实音乐能量。干湿联动开启时接管三路独立增益，灰色图临时展示解除接管后的保存值，音频状态保持不变。"
            RoomReverbChartFocus.MIX -> "联动干湿采用原始输入×(1−混合)+房型早反射／尾音×混合，接管三路独立增益。干声直接用原始输入，不能再叠加房型自身的dry dB；湿声仍含房型早反射与尾音原增益。0%为原输入，100%为房型湿声，不是等功率淡化。"
            RoomReverbChartFocus.WIDTH -> "早反射宽度−1.000至+1.000，尾音0至1.000。两者同侧／交侧权重分别为(1+宽度)/2和(1−宽度)/2，共同M不变、Side按宽度缩放。早反射负值是原算法允许的交叉成像，Side符号翻转，不是负距离或异常；部分小房型默认就是−0.400。尾音宽度独立，扇区仅表示相对开合，不是实际角度。"
            RoomReverbChartFocus.TONE -> "总湿声低／高切作用于早反射与尾音，0Hz单独旁路，非零低切须小于高切；每边是两级一阶串联。反馈阻尼是尾音回路中的滤波，改变高频消失速度，不等于输出EQ。图中分别显示总湿声滤波、反馈阻尼和房型固定输入／输出滤波，不能直接相加冒充完整SF混响实测频响；房型过采样和固有滤波仍保留。"
            RoomReverbChartFocus.OUTPUT -> "输出增益在本模块混合后整体执行，同时缩放干声、早反射和尾音；范围−12至0dB。图中是参考增益系数，不是设备最终峰值，后续音效仍会改变电平。关闭开关后保存值保留，灰色图只预览该值。"
            RoomReverbChartFocus.MONITOR -> "原生电平监测读取当前试听输出的采样峰值、RMS和超限帧数；关闭再开启可清零。正常湿声只读采样位于试听选择之前，用于频谱与相位，不能把它和当前A/B或原声试听输出当成同一路。真实音频图见下方；静图不通过参数猜测动态电平、频谱或声场。"
            RoomReverbChartFocus.MATCH -> "同一输入同时处理两套快照，按较长RT与附加预延迟充分预热后测量2秒。共同目标RMS取原声、A、B三者最低值，只固定衰减，不提升或持续追踪。左图0–18dB仅用于解释相对差值，不是实时读数或算法上限。当前匹配" + (if (snapshot.matching) "开启" else "关闭") + "，测量" + (if (snapshot.matchingReady) "就绪" else "未就绪") + "。"
            RoomReverbChartFocus.AUDITION -> "当前：" + auditionName(snapshot.audition) + "。A " + (if (snapshot.savedA) "已保存" else "未保存") + "，B " + (if (snapshot.savedB) "已保存" else "未保存") + "。八条路径包括正常、原声、早反射、尾音、总湿声、快照A、快照B与测量。原生按钮可以保存原房型或专业声音，图纸不读取私有快照参数，不执行保存／试听／测量；静图为保存配置，实际试听看实时读数。"
        }
        lines += "未开启或被接管的数值显示灰色保存目标，只改变图纸解释，不更改原生参数、开关、算法或A/B状态。"
        return lines.joinToString(System.lineSeparator() + System.lineSeparator())
    }
    override fun onAttachedToWindow(): Unit { super.onAttachedToWindow(); if (active) updateTarget() }
    override fun onDetachedFromWindow(): Unit {
        generation++; RoomReverbStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}
