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
import me.timschneeberger.rootlessjamesdsp.fusion.TubeChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.TubeController
import me.timschneeberger.rootlessjamesdsp.fusion.TubeDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.TubeParameters as P
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

/** ViPER tube observers. The JamesDSP tube, saved parameters and listening operations are independent. */
internal class TubeParameterChart(context: Context) : TubeFocusStaticBase(context, false)
internal class TubeIllustrationChart(context: Context) : TubeFocusStaticBase(context, true)

private data class TubeFocusSnapshot(
    val settings: P.Settings, val rate: Int, val actualRate: Boolean, val moduleOn: Boolean,
    val supported: Boolean, val audition: Int, val matching: Boolean, val matchingReady: Boolean,
    val savedA: Boolean, val savedB: Boolean
) {
    fun active(focus: TubeChartFocus, key: String): Boolean {
        if (focus == TubeChartFocus.AUDITION && key in setOf("v_tube_pro_save_a", "v_tube_pro_save_b")) return supported
        if (!moduleOn || !supported || !settings.professional) return false
        return when (focus) {
            TubeChartFocus.SATURATION -> settings.satOn
            TubeChartFocus.SOFTEN -> settings.toneOn
            TubeChartFocus.FILTER -> settings.toneOn && (key != P.preHP.key || settings.satOn)
            TubeChartFocus.MIX -> settings.mixOn
            TubeChartFocus.LEVEL -> settings.outputOn
            TubeChartFocus.QUALITY -> settings.satOn && settings.qualityOn
            TubeChartFocus.MONITOR -> settings.meters
            else -> true
        }
    }
    fun reason(focus: TubeChartFocus, key: String): String = when {
        !moduleOn -> "电子管关闭 · 保存目标预览"
        !supported -> "专业驱动未支持 · 保存目标预览"
        !settings.professional -> "当前原版柔化 · 专业保存预览"
        focus == TubeChartFocus.SATURATION -> "饱和关闭 · 保存塑形预览"
        focus == TubeChartFocus.FILTER && key == P.preHP.key && !settings.satOn -> "饱和关闭 · 残差低切预览"
        focus == TubeChartFocus.SOFTEN || focus == TubeChartFocus.FILTER -> "音色关闭 · 保存滤波预览"
        focus == TubeChartFocus.MIX -> "混合关闭 · 保存比例预览"
        focus == TubeChartFocus.LEVEL -> "输出增益关闭 · 保存衰减预览"
        focus == TubeChartFocus.QUALITY -> "过采样未生效 · 保存倍率预览"
        focus == TubeChartFocus.MONITOR -> "电平显示关闭 · 测点示意"
        else -> "保存目标预览"
    }
    /** Display copies only: opening a disabled group's preview never opens its audio switch. */
    fun target(focus: TubeChartFocus, key: String): P.Settings {
        val base: P.Settings = settings.copy(professional = true, meters = false)
        return when (focus) {
            TubeChartFocus.SATURATION -> base.copy(satOn = true)
            TubeChartFocus.SOFTEN -> base.copy(toneOn = true)
            TubeChartFocus.FILTER -> base.copy(toneOn = true, satOn = settings.satOn || key == P.preHP.key)
            TubeChartFocus.MIX -> base.copy(mixOn = true)
            TubeChartFocus.LEVEL -> base.copy(outputOn = true)
            TubeChartFocus.QUALITY -> base.copy(satOn = true, qualityOn = true)
            else -> if (supported) settings.copy(meters = false) else P.defaults()
        }
    }
}

private data class TubeStaticRequest(val settings: P.Settings, val rate: Int)
/** A bounded shared worker. Dragging replaces only the waiting target; completed curves stay visible. */
private object TubeStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "tube-focus-static").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    private data class Task(val request: TubeStaticRequest, val callback: (TubeDashboardModel) -> Unit)
    private class Owner {
        var running: TubeStaticRequest? = null
        var waiting: Task? = null
        var future: Future<*>? = null
    }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<TubeStaticRequest, TubeDashboardModel> =
        object : LinkedHashMap<TubeStaticRequest, TubeDashboardModel>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TubeStaticRequest, TubeDashboardModel>?): Boolean = size > 16
        }
    @Synchronized fun release(owner: Any): Unit {
        owners.remove(owner)?.let { state -> state.waiting = null; state.future?.cancel(true); state.future = null }
    }
    @Synchronized fun request(owner: Any, request: TubeStaticRequest, callback: (TubeDashboardModel) -> Unit): Unit {
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
            val result: TubeDashboardModel = synchronized(this) { cache[task.request] } ?: run {
                val values: Map<String, Any> = task.request.settings.values() + (P.ENABLED to true)
                TubeDashboardModel.from(values, task.request.rate, true)
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

internal open class TubeFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
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
    private var configuration: TubeDashboardModel = TubeDashboardModel.from(emptyMap<String, Any>())
    private var shown: TubeDashboardModel = configuration
    private var snapshot: TubeFocusSnapshot = snapshot(configuration)
    private var focus: TubeChartFocus = TubeChartFocus.SATURATION
    private var key: String = P.PROFESSIONAL
    private var active: Boolean = true
    private var request: TubeStaticRequest? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = snapshot.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x78)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x4c)
    private fun snapshot(model: TubeDashboardModel): TubeFocusSnapshot = TubeFocusSnapshot(
        model.settings, model.rate, model.actualRate, model.enabled, TubeController.supported(context),
        TubeController.audition, TubeController.match, TubeController.matchingReady(),
        TubeController.hasComparison(0), TubeController.hasComparison(1))
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
    fun setConfiguration(model: TubeDashboardModel): Unit {
        configuration = model
        val next: TubeFocusSnapshot = snapshot(model)
        if (next == snapshot) return
        snapshot = next; updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setFocus(focus: TubeChartFocus, key: String): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; TubeStaticWork.release(this); request = null }
        this.focus = focus; this.key = key
        updateDescription(); updateTarget()
        if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) {
            generation++; TubeStaticWork.release(this); request = null
            detailDialog?.dismiss(); detailDialog = null
        } else { updateTarget(); invalidate() }
    }
    private fun updateTarget(): Unit {
        if (!active) return
        val target: P.Settings = snapshot.target(focus, key)
        val next: TubeStaticRequest = TubeStaticRequest(target, configuration.rate)
        if (request == next) return
        request = next
        val token: Long = generation
        TubeStaticWork.request(this, next) { value ->
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
    private fun frequencyTrace(canvas: Canvas, values: DoubleArray, tint: Int, dashed: Boolean = false): Unit {
        path.rewind()
        shown.frequencies.forEachIndexed { index: Int, frequency: Double ->
            val x: Float = fx(frequency); val y: Float = py((values[index] + 48) / 54)
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
        TubeChartFocus.SATURATION -> drawTransfer(canvas)
        TubeChartFocus.SOFTEN -> drawSoftening(canvas)
        TubeChartFocus.FILTER -> drawFilters(canvas)
        TubeChartFocus.MIX -> drawMix(canvas)
        TubeChartFocus.LEVEL -> drawLevel(canvas)
        TubeChartFocus.QUALITY -> drawQuality(canvas)
        TubeChartFocus.MONITOR -> drawMonitor(canvas)
        TubeChartFocus.MATCH -> drawMatch(canvas)
        TubeChartFocus.AUDITION -> drawAudition(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        TubeChartFocus.SATURATION -> drawTube(canvas)
        TubeChartFocus.SOFTEN -> drawSoftWave(canvas)
        TubeChartFocus.FILTER -> drawFilterRoutes(canvas)
        TubeChartFocus.MIX -> drawBlend(canvas)
        TubeChartFocus.LEVEL -> drawLevelWave(canvas)
        TubeChartFocus.QUALITY -> drawSampleLattice(canvas)
        TubeChartFocus.MONITOR -> drawMonitorRoutes(canvas)
        TubeChartFocus.MATCH -> drawBalance(canvas)
        TubeChartFocus.AUDITION -> drawListening(canvas)
    }
    private fun drawTransfer(canvas: Canvas): Unit {
        caption(canvas, fmt(shown.settings.drive / 10.0) + "dB · 软 " + fmt(shown.softness * 100) + "% · " + signed(shown.settings.asym / 10.0) + "%")
        grid(canvas, "+1.5", "−1.5", "−1.5 FS", "+1.5 FS")
        trace(canvas, ColorUtils.setAlphaComponent(foreground, 0x55), true) { x: Double -> x }
        trace(canvas, third, true) { x: Double -> (shown.hard(-1.5 + x * 3) + 1.5) / 3 }
        trace(canvas, secondary, true) { x: Double -> (shown.soft(-1.5 + x * 3) + 1.5) / 3 }
        trace(canvas) { x: Double -> (shown.shape(-1.5 + x * 3) + 1.5) / 3 }
        dot(canvas, px(.5), py(.5))
        footer(canvas, "输入→输出 · 硬/软/所选瞬时内核")
    }
    private fun drawSoftening(canvas: Canvas): Unit {
        caption(canvas, "柔化 " + fmt(shown.softenMix * 100) + "% · " + fmt(shown.softenCutoffHz, 0) + " Hz")
        grid(canvas, "+6 dB", "−48", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.softeningDb, color)
        val reference: DoubleArray = shown.frequencies.map { frequency: Double ->
            val magnitude: Double = .5 / sqrt(1.25 - cos(2 * PI * frequency / shown.rate))
            20 * log10(magnitude.coerceAtLeast(1e-12))
        }.toDoubleArray()
        frequencyTrace(canvas, reference, secondary, true)
        footer(canvas, "实线保存柔化 · 虚线原版系数 0.5")
    }
    private fun postDb(frequency: Double): Double {
        if (shown.postLpHz <= 0) return 0.0
        val pole: Double = (1 - tan(PI * shown.postLpHz / shown.rate)) / (1 + tan(PI * shown.postLpHz / shown.rate))
        val cosine: Double = cos(2 * PI * frequency / shown.rate)
        val magnitude: Double = (1 - pole) * .5 * sqrt((2 + 2 * cosine).coerceAtLeast(0.0)) /
            sqrt((1 + pole * pole - 2 * pole * cosine).coerceAtLeast(1e-24))
        return 20 * log10(magnitude.coerceAtLeast(1e-12))
    }
    private fun drawFilters(canvas: Canvas): Unit {
        caption(canvas, "残差 HP " + fmt(shown.preHpHz, 0) + " · 湿声 LP " + fmt(shown.postLpHz, 0) + " Hz")
        grid(canvas, "+6 dB", "−48", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.preResidualDb, secondary, !snapshot.settings.satOn)
        frequencyTrace(canvas, shown.frequencies.map { frequency: Double -> postDb(frequency) }.toDoubleArray(), color)
        if (shown.preHpHz > 0) dot(canvas, fx(shown.preHpHz), py(45.0 / 54), secondary)
        if (shown.postLpHz > 0) dot(canvas, fx(shown.postLpHz), py(45.0 / 54))
        footer(canvas, "前低切仅作用饱和残差 · 基底低频保留")
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
        bar(canvas, 0, 2, 1 - shown.mix, "干声", fmt(1 - shown.mix, 3) + "×", secondary)
        bar(canvas, 1, 2, shown.mix, "处理后", fmt(shown.mix, 3) + "×", color)
        footer(canvas, "线性相加 · 两路按处理延迟对齐")
    }
    private fun drawLevel(canvas: Canvas): Unit {
        val decibels: Double = 20 * log10(shown.outputGain)
        caption(canvas, signed(decibels) + " dB · " + fmt(shown.outputGain, 3) + "×")
        grid(canvas, "+1 FS", "−1", "−1 FS", "+1 FS")
        trace(canvas, secondary, true) { x: Double -> x }
        trace(canvas) { x: Double -> .5 + (2 * x - 1) * shown.outputGain * .5 }
        footer(canvas, "固定输出衰减 · 不改变饱和驱动")
    }
    private fun drawQuality(canvas: Canvas): Unit {
        caption(canvas, shown.effectiveFactor.toString() + "× · 对齐 " + shown.latencySamples + " 样本 / " + fmt(1000.0 * shown.latencySamples / shown.rate, 3) + " ms")
        grid(canvas, "+6 dB", "−48", "20 Hz", "20 kHz")
        frequencyTrace(canvas, shown.reconstructionDb, color)
        footer(canvas, "升/降采样 FIR 配对参考 · 仅饱和残差")
    }
    private fun drawMonitor(canvas: Canvas): Unit {
        caption(canvas, (if (configuration.actualRate) "实际 " else "参考 ") + configuration.rate + " Hz · 配置测点")
        grid(canvas, "输入", "输出", "处理前", "试听后")
        val labels: List<String> = listOf("输入峰值 / RMS", "正常处理后效果", "当前试听输出")
        labels.forEachIndexed { index: Int, label: String ->
            val y: Float = py(.82 - index * .32)
            line(canvas, px(.07), y, px(.92), y, if (index == 1) third else color, !snapshot.settings.meters)
            dot(canvas, px(.10), y, secondary)
            text(canvas, label, px(.20), y - d(4f), plot.width() * .78f)
        }
        footer(canvas, "静图说明测点 · 实际电平见下方动态图")
    }
    private fun drawMatch(canvas: Canvas): Unit {
        caption(canvas, if (snapshot.matchingReady) "已测量 · " + if (snapshot.matching) "匹配开启" else "匹配关闭" else "同一输入 · A/B 并行测量")
        grid(canvas, "预热", "测量", "0 s", "3 s")
        fillPaint.color = ColorUtils.setAlphaComponent(third, 0x55)
        canvas.drawRoundRect(RectF(px(0.0), py(.85), px(.5 / 3), py(.55)), d(2f), d(2f), fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x55)
        canvas.drawRoundRect(RectF(px(.5 / 3), py(.42), px(2.5 / 3), py(.12)), d(2f), d(2f), fillPaint)
        text(canvas, "≥500 ms", px(.05), py(.50), plot.width() * .75f, third)
        text(canvas, "2 秒 RMS", px(.30), py(.06), plot.width() * .65f, color)
        footer(canvas, "含共同对齐预热 · 只衰减到三路最低能量")
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
            val y: Float = box.centerY() - box.height() * .44f * value.coerceIn(-1.2, 1.2).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        stroke(canvas, tint)
    }
    private fun drawTube(canvas: Canvas): Unit {
        caption(canvas, "硬 ← " + fmt(shown.softness * 100) + "% → 柔 · 半波 " + signed(shown.settings.asym / 10.0) + "%")
        val cx: Float = px(.50); val top: Float = py(.97); val bottom: Float = py(.15)
        val half: Float = plot.width() * .17f
        val glass: RectF = RectF(cx - half, top, cx + half, bottom)
        fillPaint.shader = LinearGradient(glass.left, glass.top, glass.right, glass.bottom,
            intArrayOf(ColorUtils.setAlphaComponent(color, 0x0c), ColorUtils.setAlphaComponent(color, 0x32),
                ColorUtils.setAlphaComponent(third, 0x10)), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(glass, half * .72f, half * .72f, fillPaint); fillPaint.shader = null
        linePaint.color = ColorUtils.setAlphaComponent(color, 0x85)
        canvas.drawRoundRect(glass, half * .72f, half * .72f, linePaint)
        line(canvas, cx - half * .62f, top + d(6f), cx - half * .62f, bottom - d(7f), ColorUtils.setAlphaComponent(foreground, 0x25))
        val plate: RectF = RectF(cx - half * .57f, top + glass.height() * .24f, cx + half * .57f, top + glass.height() * .72f)
        fillPaint.color = ColorUtils.setAlphaComponent(third, (34 + shown.settings.drive * 46 / 180).coerceIn(0, 255))
        canvas.drawOval(plate, fillPaint)
        repeat(5) { index: Int ->
            val y: Float = plate.top + plate.height() * (index + 1) / 6
            line(canvas, plate.left, y, plate.right, y, ColorUtils.setAlphaComponent(color, 0x60))
        }
        path.rewind()
        repeat(9) { index: Int ->
            val x: Float = cx + if (index % 2 == 0) -half * .20f else half * .20f
            val y: Float = plate.top + plate.height() * index / 8
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        stroke(canvas, third)
        line(canvas, plate.left, plate.top, plate.left, plate.bottom, color)
        line(canvas, plate.right, plate.top, plate.right, plate.bottom, color)
        repeat(5) { index: Int ->
            val x: Float = cx - half * .72f + half * 1.44f * index / 4
            line(canvas, x, bottom - d(2f), x, bottom + d(5f), secondary)
        }
        fillPaint.color = ColorUtils.setAlphaComponent(foreground, 0x28)
        canvas.drawRoundRect(RectF(cx - half * 1.06f, bottom - d(6f), cx + half * 1.06f, bottom), d(2f), d(2f), fillPaint)
        wave(canvas, RectF(plot.left, py(.77), cx - half - d(3f), py(.32)), secondary) { value: Double -> value }
        wave(canvas, RectF(cx + half + d(3f), py(.77), plot.right, py(.32)), color) { value: Double -> shown.shape(value) }
        text(canvas, "输入", px(.10), py(.13), plot.width() * .23f, secondary, Paint.Align.CENTER)
        text(canvas, "塑形", px(.88), py(.13), plot.width() * .23f, color, Paint.Align.CENTER)
        footer(canvas, "归一化塑形示意 · 不含柔化滤波记忆")
    }
    private fun drawSoftWave(canvas: Canvas): Unit {
        caption(canvas, "柔化比例 " + fmt(shown.softenMix * 100) + "% · 高频细节示意")
        val topBox: RectF = RectF(plot.left, plot.top + d(5f), plot.right, plot.centerY() - d(6f))
        val bottomBox: RectF = RectF(plot.left, plot.centerY() + d(11f), plot.right, plot.bottom)
        line(canvas, topBox.left, topBox.centerY(), topBox.right, topBox.centerY(), ColorUtils.setAlphaComponent(secondary, 0x28))
        line(canvas, bottomBox.left, bottomBox.centerY(), bottomBox.right, bottomBox.centerY(), ColorUtils.setAlphaComponent(color, 0x28))
        val pole: Double = if (shown.settings.softenCut > 0) {
            val c: Double = 2 - cos(2 * PI * shown.softenCutoffHz / shown.rate)
            c - sqrt(c * c - 1)
        } else .5
        var memory: Double = 0.0
        val inputPath: Path = Path(); val outputPath: Path = Path()
        for (index in 0..160) {
            val fraction: Double = index / 160.0
            val input: Double = .58 * sin(fraction * 4 * PI) + .24 * sin(fraction * 48 * PI)
            memory = (1 - pole) * input + pole * memory
            val output: Double = input + shown.softenMix * (memory - input)
            val x: Float = plot.left + plot.width() * fraction.toFloat()
            val y1: Float = topBox.centerY() - topBox.height() * .45f * input.toFloat()
            val y2: Float = bottomBox.centerY() - bottomBox.height() * .45f * output.toFloat()
            if (index == 0) { inputPath.moveTo(x, y1); outputPath.moveTo(x, y2) }
            else { inputPath.lineTo(x, y1); outputPath.lineTo(x, y2) }
        }
        linePaint.color = secondary; canvas.drawPath(inputPath, linePaint)
        linePaint.color = color; canvas.drawPath(outputPath, linePaint)
        text(canvas, "原波形", plot.left, topBox.top, plot.width(), secondary)
        text(canvas, "柔化目标", plot.left, bottomBox.top, plot.width(), color)
        footer(canvas, "合成波形示例 · 低通有记忆，不是饱和")
    }
    private fun roundedNode(canvas: Canvas, box: RectF, label: String, tint: Int, selected: Boolean = false): Unit {
        fillPaint.color = ColorUtils.setAlphaComponent(tint, if (selected) 0x30 else 0x10)
        canvas.drawRoundRect(box, d(4f), d(4f), fillPaint)
        linePaint.color = ColorUtils.setAlphaComponent(tint, if (selected) 0xd0 else 0x65)
        canvas.drawRoundRect(box, d(4f), d(4f), linePaint)
        text(canvas, label, box.centerX(), box.centerY() + d(3f), box.width() - d(4f), tint, Paint.Align.CENTER)
    }
    private fun drawFilterRoutes(canvas: Canvas): Unit {
        caption(canvas, "残差 HP " + fmt(shown.preHpHz, 0) + " · 湿声 LP " + fmt(shown.postLpHz, 0) + " Hz")
        val base: Float = py(.79); val residual: Float = py(.43); val output: Float = py(.10)
        line(canvas, px(.02), base, px(.97), base, secondary)
        line(canvas, px(.02), residual, px(.97), residual, color, !snapshot.settings.satOn)
        text(canvas, "原柔化基底 · 低频保留", px(.04), base - d(6f), plot.width() * .93f, secondary)
        text(canvas, "前 HP → 饱和残差", px(.04), residual - d(6f), plot.width() * .93f, color)
        val thickness: Float = linePaint.strokeWidth
        repeat(3) { index: Int ->
            val frequency: Double = listOf(60.0, 1000.0, 12000.0)[index]
            val n: Int = shown.frequencies.indices.minByOrNull { abs(shown.frequencies[it] - frequency) } ?: 0
            val participation: Double = 10.0.pow(shown.preResidualDb[n] / 20)
            val x: Float = px(.18 + index * .30)
            fillPaint.color = ColorUtils.setAlphaComponent(color, (35 + participation.coerceIn(0.0, 1.0) * 155).toInt())
            canvas.drawRoundRect(RectF(x - d(6f), residual - d(3f), x + d(6f), residual + d(3f)), d(2f), d(2f), fillPaint)
            text(canvas, listOf("低", "中", "高")[index], x, residual + d(14f), d(20f), color, Paint.Align.CENTER)
        }
        linePaint.strokeWidth = thickness
        line(canvas, px(.88), base, px(.88), residual, third)
        line(canvas, px(.88), residual, px(.88), output, third)
        roundedNode(canvas, RectF(px(.08), output - d(7f), px(.72), output + d(7f)), "合并后低通", third, true)
        repeat(3) { index: Int ->
            val frequency: Double = listOf(60.0, 1000.0, 12000.0)[index]
            val amount: Double = 10.0.pow(postDb(frequency) / 20).coerceIn(0.0, 1.0)
            val x: Float = px(.78 + index * .08)
            fillPaint.color = ColorUtils.setAlphaComponent(third, (40 + 180 * amount).toInt())
            canvas.drawRoundRect(RectF(x - d(1.4f), output + d(5f) - d(11f) * amount.toFloat(), x + d(1.4f), output + d(5f)),
                d(1f), d(1f), fillPaint)
        }
        footer(canvas, "低切不是整路低频删除 · 零频率为旁路")
    }
    private fun drawBlend(canvas: Canvas): Unit {
        caption(canvas, "干 " + fmt((1 - shown.mix) * 100) + "%  +  湿 " + fmt(shown.mix * 100) + "%")
        val splitX: Float = px(.10); val joinX: Float = px(.82)
        val dryY: Float = py(.77); val wetY: Float = py(.25)
        line(canvas, splitX, dryY, joinX, dryY, secondary, shown.mix >= 1)
        line(canvas, splitX, wetY, joinX, wetY, color, shown.mix <= 0)
        roundedNode(canvas, RectF(px(.22), dryY - d(10f), px(.72), dryY + d(10f)), "对齐原声", secondary, shown.mix < 1)
        roundedNode(canvas, RectF(px(.22), wetY - d(10f), px(.72), wetY + d(10f)), "电子管湿声", color, shown.mix > 0)
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
        caption(canvas, factor.toString() + "× 饱和残差 · 升采样→塑形→重建")
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
        caption(canvas, if (snapshot.matchingReady) "等响度结果就绪 · 只作固定衰减" else "保存 A + B → 预热 → 测量 → 对照")
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
        val sourceLabels: List<String> = listOf("原声", "电子管", "快照 A", "快照 B")
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
        val status: String = if (enabled) "本组已启用，显示保存配置目标。" else snapshot.reason(focus, key) + "；灰色曲线随保存数值变化，不会开启声音处理。"
        val explanation: String = when (focus) {
            TubeChartFocus.SATURATION -> "驱动 " + fmt(s.drive / 10.0) + " dB；软硬度 " + fmt(s.softness / 10.0) + "%（0 硬，100 柔）；非对称度 " + signed(s.asym / 10.0) + "%（内部偏置除以 2000）。\n硬曲线为 (clip(g×x+b)−b)/g；软曲线为 (tanh(g×x+b)−tanh(b))/(g×(1−tanh²(b)))，按软硬度线性混合。驱动经归一化，不是把最终音量直接放大。曲线只表示瞬时饱和内核；实际模块还包含柔化滤波记忆、残差支路、5 Hz 去直流、干湿混合及输出衰减。原版专业关闭时仍有系数 0.5 的低通柔化，不能用一条恒等线代替整模块响应。"
            TubeChartFocus.SOFTEN -> "原版柔化比例 " + fmt(s.softenMix / 10.0) + "%；柔化低通保存 " + s.softenCut + " Hz。0 Hz 使用原版极点 0.5；非零频率最高限制至采样率的 45%。基底为输入与一阶记忆低通按比例混合；虚线是原版低通参考。右图仅为固定合成信号经过此柔化目标的示例，不是播放中的波形，也不包含后续饱和与输出处理。"
            TubeChartFocus.FILTER -> "饱和前低切 " + s.preHP + " Hz；处理后高切 " + s.postLP + " Hz。0 Hz 为该滤波旁路。前低切需专业、饱和与音色三开关同时开启，只作用于生成非线性残差的输入，低频基底仍保留；高切作用于合并后的整路湿声。曲线分别是两条支路的一阶滤波参考，不是把非线性模块当作固定完整频响。关闭项目以灰色保存目标预览。"
            TubeChartFocus.MIX -> "保存干湿混合 " + fmt(s.mix / 10.0) + "%；启用后 y = 干声×(1−混合) + 完整处理湿声×混合，再施加输出衰减。两路按实际处理延迟对齐。比例不是能量或听感百分比：实际相位与输入内容会影响相加结果。混合关闭时实际使用 100% 处理后声音。"
            TubeChartFocus.LEVEL -> "输出增益保存 " + signed(s.output / 10.0) + " dB，线性系数 " + fmt(10.0.pow(s.output / 200.0), 4) + "。此开关只对模块混合后的声音施加固定衰减，不代替饱和驱动。关闭时实际 0 dB。右图是相同波形缩小的相对示意，不是实际 RMS 或耳内声压。"
            TubeChartFocus.QUALITY -> "保存倍率 " + s.factor + "×。需专业、饱和、过采样三个开关同时开启；否则实际 1×、正常内核额外延迟 0 样本。2× / 4× 均仅对饱和残差作 Blackman 窗 FIR 升降采样，每段长 31×倍率+1 抽头，两段合计正常内核 31 个基准采样延迟（当前参考 " + fmt(31000.0 / snapshot.rate, 4) + " ms）。左图是升降采样 FIR 配对幅度参考，不是失真频响或真实新增谐波。A/B 或切换过渡时实际共同对齐可由其他内核决定；以下方真实状态为准。右图仅显示采样时刻，不冒充 FIR 插值波形。"
            TubeChartFocus.MONITOR -> "原生“显示实时电平”开关目前" + (if (s.meters) "开启" else "关闭") + "。输入/输出标量峰值与 RMS 来自本模块；新增只读音频图可显示对齐原声、正常处理完整效果、当前试听输出，位置不同。完整效果不等于纯新增谐波，当前输出还会受到试听选择影响。此静图只说明测点，不产生模拟的实时读数；采样电源独立控制后台图纸采样。"
            TubeChartFocus.MATCH -> "A/B 状态：A " + (if (snapshot.savedA) "已保存" else "未保存") + "，B " + (if (snapshot.savedB) "已保存" else "未保存") + "；匹配" + (if (snapshot.matching) "开启" else "关闭") + "。测量采用同一输入并行处理 A/B，预热至少 500 ms 并包含共同对齐延迟，再测量 2 秒；有效时以原声、A、B 中最低能量为目标，只作固定衰减。信号不足时不建立匹配。静图只说明流程与快照是否存在，不读取或更改快照私有参数，也不填造实测衰减值。"
            TubeChartFocus.AUDITION -> "当前试听：“" + routeLabels[snapshot.audition.coerceIn(0, 6)] + "”。正常、本模块原声、仅处理后效果、效果差分、快照 A、快照 B、测量 A/B 是独立路径。效果差分为完整效果减去对齐原声，不等同于纯谐波或 THD。A/B 试听的真实输出由动态图与原生状态显示；两张静图始终表示页面保存参数。所有保存、测量、试听和停止动作仍由原生按钮负责。"
        }
        return (if (illustration) focus.rightTitle else focus.leftTitle) + "\n\n" + status + "\n\n" + explanation +
            "\n\n" + (if (snapshot.actualRate) "配置实际采样率 " else "参考采样率 ") + snapshot.rate + " Hz；图纸只读，不写入参数、预设或 A/B 快照。"
    }
    override fun onDetachedFromWindow(): Unit {
        generation++; TubeStaticWork.release(this); request = null
        detailDialog?.dismiss(); detailDialog = null
        super.onDetachedFromWindow()
    }
}
