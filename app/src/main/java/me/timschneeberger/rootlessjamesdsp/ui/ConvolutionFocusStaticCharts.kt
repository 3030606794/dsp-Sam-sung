package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.*
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
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionController
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionParameters as P
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionAdvancedParameters as A
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionAdvancedAnalysis
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder

internal class ConvolutionParameterChart(context: Context) : ConvolutionFocusStaticBase(context, false)
internal class ConvolutionIllustrationChart(context: Context) : ConvolutionFocusStaticBase(context, true)

private data class ConvStaticSnapshot(
    val values: Map<String, *>, val prepared: ConvolutionController.PreparedIr?,
    val preparation: ConvolutionController.PreparedIr?, val rate: Int, val actualRate: Boolean,
    val supported: Boolean, val advanced: Boolean, val confirmed: Boolean, val audition: Int, val comparison: Int
) {
    val enabled: Boolean get() = values[P.ENABLED] as? Boolean == true
    val sourceChannels: Int get() = prepared?.sourceChannels ?: preparation?.sourceChannels ?: 0
    fun validLegacy(): Boolean = P.legacy(values)?.let { fields ->
        fields[0] in -160..0 && fields[1] in -160..0 && (2..5).all { fields[it] in 0..Int.MAX_VALUE - 99 }
    } == true
    fun pathAvailable(index: Int): Boolean = index == 0 || index == 3 || sourceChannels == 4
    fun selectedPath(key: String): Int = A.gain.indexOfFirst { it.key == key }.takeIf { it >= 0 }
        ?: A.delay.indexOfFirst { it.key == key }.takeIf { it >= 0 }
        ?: (0..3).indexOfFirst { key == A.mute(it) || key == A.invert(it) }
    fun active(focus: ConvolutionChartFocus, key: String): Boolean {
        if (focus == ConvolutionChartFocus.FILE) return prepared?.hasWorkingKernel == true && enabled
        if (!enabled) return false
        return when (focus) {
            ConvolutionChartFocus.MIX, ConvolutionChartFocus.WET, ConvolutionChartFocus.OUTPUT,
            ConvolutionChartFocus.PREDELAY, ConvolutionChartFocus.CHANNEL, ConvolutionChartFocus.AUDITION -> supported
            ConvolutionChartFocus.LEGACY_TRIM -> (values[P.LEGACY_MODE] as? String ?: "0") != "0" && validLegacy()
            ConvolutionChartFocus.LEGACY_SHIFT -> validLegacy()
            ConvolutionChartFocus.WINDOW -> P.enabled(values, P.WINDOW_ENABLED)
            ConvolutionChartFocus.NORMALIZATION -> P.normMode(values) == 1
            ConvolutionChartFocus.HIGH_PASS -> advanced && A.on(values, A.enabledFilter(0))
            ConvolutionChartFocus.LOW_PASS -> advanced && A.on(values, A.enabledFilter(1))
            ConvolutionChartFocus.LOW_SHELF -> advanced && A.on(values, A.enabledFilter(2))
            ConvolutionChartFocus.HIGH_SHELF -> advanced && A.on(values, A.enabledFilter(3))
            ConvolutionChartFocus.MATRIX -> advanced && (key == A.ROUTE || A.on(values, A.MATRIX)) &&
                selectedPath(key).let { it < 0 || pathAvailable(it) }
            ConvolutionChartFocus.FRACTIONAL -> advanced && A.on(values, A.FRACTIONAL) &&
                selectedPath(key).let { it < 0 || pathAvailable(it) }
            ConvolutionChartFocus.SHAPE -> advanced && A.on(values, A.SHAPE)
            ConvolutionChartFocus.TRANSITION -> advanced && A.choice(values, A.SWITCH) != 0 &&
                (key !in setOf(A.tail.key, A.exitFade.key) || A.choice(values, A.SWITCH) == 2)
            ConvolutionChartFocus.ANALYSIS -> A.on(values, A.ANALYSIS) && prepared?.advancedAnalysis != null
            ConvolutionChartFocus.COMPARISON -> advanced
            else -> true
        }
    }
    fun reason(focus: ConvolutionChartFocus, key: String): String = when {
        !enabled -> "卷积关闭 · 保存目标预览"
        focus == ConvolutionChartFocus.FILE -> "等待完整生成IR · 不代入示例脉冲"
        focus == ConvolutionChartFocus.LEGACY_TRIM && !validLegacy() -> "原六参数无效 · 原文本保留"
        focus == ConvolutionChartFocus.LEGACY_TRIM -> "原始模式不裁剪 · 相对阈值目标预览"
        focus == ConvolutionChartFocus.LEGACY_SHIFT -> "原六参数无效 · 原文本保留"
        focus == ConvolutionChartFocus.WINDOW -> "时间窗关闭 · 保存窗口预览"
        focus == ConvolutionChartFocus.NORMALIZATION -> "归一化关闭 · 参考目标预览"
        focus == ConvolutionChartFocus.ANALYSIS -> "深度分析未就绪 · 不生成测量曲线"
        focus in setOf(ConvolutionChartFocus.MIX, ConvolutionChartFocus.WET, ConvolutionChartFocus.OUTPUT,
            ConvolutionChartFocus.PREDELAY, ConvolutionChartFocus.CHANNEL, ConvolutionChartFocus.AUDITION) -> "配套功能未支持 · 参数目标预览"
        !advanced -> "高级驱动未支持 · 保存目标预览"
        selectedPath(key).let { it >= 0 && !pathAvailable(it) } -> "原IR没有此交叉路径 · 保存目标预览"
        focus == ConvolutionChartFocus.TRANSITION && A.choice(values, A.SWITCH) == 1 -> "当前线性换核 · 保尾参数灰色预览"
        focus == ConvolutionChartFocus.TRANSITION -> "沿用原切换 · 高级过渡值预览"
        else -> "对应高级组关闭 · 保存目标预览"
    }
    fun displayValues(focus: ConvolutionChartFocus): Map<String, *> {
        val target: MutableMap<String, Any?> = values.toMutableMap()
        when (focus) {
            ConvolutionChartFocus.WINDOW -> target[P.WINDOW_ENABLED] = true
            ConvolutionChartFocus.NORMALIZATION -> target[P.NORM_MODE] = 1
            ConvolutionChartFocus.HIGH_PASS -> target[A.enabledFilter(0)] = true
            ConvolutionChartFocus.LOW_PASS -> target[A.enabledFilter(1)] = true
            ConvolutionChartFocus.LOW_SHELF -> target[A.enabledFilter(2)] = true
            ConvolutionChartFocus.HIGH_SHELF -> target[A.enabledFilter(3)] = true
            ConvolutionChartFocus.MATRIX -> target[A.MATRIX] = true
            ConvolutionChartFocus.FRACTIONAL -> target[A.FRACTIONAL] = true
            ConvolutionChartFocus.SHAPE -> target[A.SHAPE] = true
            else -> Unit
        }
        return target
    }
}

private data class ConvStaticRequest(val base: ConvolutionDashboardModel, val values: Map<String, *>)
private data class ConvStaticDrawing(val model: ConvolutionDashboardModel,
    val original: List<FloatArray>, val working: List<FloatArray>)
/** Bounded observer cache; no file decoding, re-editing or parameter writes. */
private object ConvStaticWork {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "convolution-focus-static").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    private data class Task(val request: ConvStaticRequest, val callback: (ConvStaticDrawing) -> Unit)
    private class Owner { var running: ConvStaticRequest? = null; var waiting: Task? = null; var future: Future<*>? = null }
    private val owners: IdentityHashMap<Any, Owner> = IdentityHashMap()
    private val cache: LinkedHashMap<ConvStaticRequest, ConvStaticDrawing> = object : LinkedHashMap<ConvStaticRequest, ConvStaticDrawing>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConvStaticRequest, ConvStaticDrawing>?): Boolean = size > 8
    }
    @Synchronized fun release(owner: Any): Unit { owners.remove(owner)?.let { it.waiting = null; it.future?.cancel(true); it.future = null } }
    @Synchronized fun request(owner: Any, request: ConvStaticRequest, callback: (ConvStaticDrawing) -> Unit): Unit {
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
            val result: ConvStaticDrawing = synchronized(this) { cache[task.request] } ?: run {
                val model: ConvolutionDashboardModel = task.request.base.withDisplayValues(task.request.values)
                val analysis: ConvolutionAdvancedAnalysis.Data? = model.prepared?.advancedAnalysis
                val smooth: Int = A.choice(task.request.values, A.SMOOTH)
                ConvStaticDrawing(model, analysis?.originalDb?.map { ConvolutionAdvancedAnalysis.smooth(analysis.hz, it, smooth) }.orEmpty(),
                    analysis?.workingDb?.map { ConvolutionAdvancedAnalysis.smooth(analysis.hz, it, smooth) }.orEmpty())
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

internal open class ConvolutionFocusStaticBase(context: Context, private val illustration: Boolean) : View(context) {
    private val density: Float = resources.displayMetrics.density
    private fun d(value: Float): Float = value * density
    private val foreground: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val teal: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(110, 216, 208) else Color.rgb(17, 124, 117)
    private val amber: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(247, 184, 109) else Color.rgb(163, 90, 23)
    private val blue: Int = if (ColorUtils.calculateLuminance(foreground) > .5) Color.rgb(132, 184, 255) else Color.rgb(55, 101, 164)
    private val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(9f * resources.displayMetrics.scaledDensity, d(11f)) }
    private val linePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(1.5f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d(.65f); color = ColorUtils.setAlphaComponent(foreground, 0x28) }
    private val plot: RectF = RectF()
    private val path: Path = Path()
    private var configuration: ConvolutionDashboardModel = ConvolutionDashboardModel.from(emptyMap<String, Any>())
    private var drawing: ConvStaticDrawing = ConvStaticDrawing(configuration, emptyList(), emptyList())
    private val shown: ConvolutionDashboardModel get() = drawing.model
    private var snapshot: ConvStaticSnapshot = snapshot(configuration)
    private var focus: ConvolutionChartFocus = ConvolutionChartFocus.FILE
    private var key: String = P.FILE
    private var active: Boolean = true
    private var request: ConvStaticRequest? = null
    private var generation: Long = 0L
    private var detailDialog: AlertDialog? = null
    private val enabled: Boolean get() = snapshot.active(focus, key)
    private val color: Int get() = if (enabled) primary else ColorUtils.setAlphaComponent(foreground, 0x78)
    private val secondary: Int get() = if (enabled) teal else ColorUtils.setAlphaComponent(foreground, 0x60)
    private val third: Int get() = if (enabled) amber else ColorUtils.setAlphaComponent(foreground, 0x50)
    private fun palette(index: Int): Int = when (index % 4) { 0 -> color; 1 -> secondary; 2 -> third; else -> if (enabled) blue else secondary }
    private fun snapshot(model: ConvolutionDashboardModel): ConvStaticSnapshot {
        val rows: List<ConvolutionController.Reading> = ConvolutionController.runtime.filter { it.enabled && it.processing == OutputRuntimeStatus.Processing.ACTIVE }
        val native = rows.singleOrNull()?.snapshot
        val confirmed: Boolean = model.prepared?.let { ir -> native?.loaded == true && native.crc == ir.crc && native.rate == ir.processingSampleRate } == true
        return ConvStaticSnapshot(model.values, model.prepared, model.preparation, model.rate, model.actualRate,
            ConvolutionController.supported(context), ConvolutionController.advancedSupported(context), confirmed,
            ConvolutionController.audition, ConvolutionController.comparisonTenths)
    }
    init {
        isLongClickable = true
        setOnLongClickListener {
            detailDialog?.dismiss(); detailDialog = GlassAlertDialogBuilder(context)
                .setTitle(if (illustration) focus.rightTitle else focus.leftTitle).setMessage(details())
                .setPositiveButton(android.R.string.ok, null).show(); true
        }
    }
    fun setConfiguration(model: ConvolutionDashboardModel): Unit {
        configuration = model
        val next: ConvStaticSnapshot = snapshot(model)
        if (next == snapshot) return
        snapshot = next; updateTarget(); updateDescription(); if (active) invalidate()
    }
    fun setFocus(focus: ConvolutionChartFocus, key: String = ""): Unit {
        if (this.focus == focus && this.key == key) return
        if (this.focus != focus) { generation++; ConvStaticWork.release(this); request = null }
        this.focus = focus; this.key = key; updateTarget(); updateDescription(); if (active) invalidate()
    }
    fun setActive(active: Boolean): Unit {
        if (this.active == active) return
        this.active = active
        if (!active) { generation++; ConvStaticWork.release(this); request = null; detailDialog?.dismiss(); detailDialog = null }
        else { updateTarget(); invalidate() }
    }
    private fun updateTarget(): Unit {
        if (!active) return
        val next: ConvStaticRequest = ConvStaticRequest(configuration, snapshot.displayValues(focus))
        if (next == request) return
        request = next
        val token: Long = generation
        ConvStaticWork.request(this, next) { value -> if (active && token == generation) { drawing = value; updateDescription(); invalidate() } }
    }
    private fun updateDescription(): Unit { contentDescription = details() }
    private fun fmt(value: Double, places: Int = 1): String = if (value.isFinite()) String.format(Locale.ROOT, "%." + places + "f", value) else if (value == Double.NEGATIVE_INFINITY) "−∞" else "—"
    private fun signed(value: Double, places: Int = 1): String = String.format(Locale.ROOT, "%+." + places + "f", value)
    private fun px(value: Double): Float = plot.left + plot.width() * value.toFloat()
    private fun py(value: Double): Float = plot.bottom - plot.height() * value.toFloat()
    private fun fx(value: Double): Float = px(ln(value.coerceIn(20.0, 20000.0) / 20) / ln(1000.0))
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, space: Float, tint: Int = foreground, align: Paint.Align = Paint.Align.LEFT): Unit {
        if (space <= 0) return
        textPaint.color = tint; textPaint.textAlign = align
        val count: Int = textPaint.breakText(value, true, space, null)
        canvas.drawText(if (count == value.length) value else value.take((count - 1).coerceAtLeast(0)) + "…", x, y, textPaint)
    }
    private fun caption(canvas: Canvas, value: String): Unit = text(canvas, value, d(3f), d(11f), width - d(6f), color)
    private fun footer(canvas: Canvas, value: String): Unit = text(canvas, if (enabled) value else snapshot.reason(focus, key), d(3f), height - d(3f), width - d(6f), ColorUtils.setAlphaComponent(foreground, 0xa0))
    private fun stamp(): String = when {
        shown.prepared == null -> "等待生成IR"
        shown.prepared !== snapshot.prepared -> "上一生成核预览"
        configuration.pending -> "新核准备中 · 保留旧生成核"
        snapshot.confirmed -> "CRC/率匹配 · 生成核分析"
        else -> "生成核待装载确认"
    }
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
        path.rewind(); var joined: Boolean = false
        for (index in 0..160) {
            val x: Double = index / 160.0; val y: Double = value(x)
            if (!y.isFinite()) { joined = false; continue }
            if (joined) path.lineTo(px(x), py(y)) else path.moveTo(px(x), py(y)); joined = true
        }
        stroke(canvas, tint, dashed)
    }
    private fun series(canvas: Canvas, axis: DoubleArray, values: DoubleArray, tint: Int, low: Double, high: Double,
        frequency: Boolean = true, span: Double = 1.0, dashed: Boolean = false, phase: Boolean = false): Unit {
        path.rewind(); var joined: Boolean = false; var prior: Double = Double.NaN
        for (index in 0 until min(axis.size, values.size)) {
            val value: Double = values[index]; val coordinate: Double = axis[index]
            if (!value.isFinite() || !coordinate.isFinite() || frequency && coordinate >= shown.irRate / 2.0) { joined = false; continue }
            if (phase && prior.isFinite() && abs(value - prior) > 180) joined = false
            val x: Float = if (frequency) fx(coordinate) else px(coordinate / span)
            val y: Float = py((value - low) / (high - low))
            if (joined) path.lineTo(x, y) else path.moveTo(x, y); joined = true; prior = value
        }
        stroke(canvas, tint, dashed)
    }
    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, tint: Int, dashed: Boolean = false): Unit {
        linePaint.color = tint; linePaint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(d(3f), d(2f)), 0f) else null
        canvas.drawLine(x1, y1, x2, y2, linePaint); linePaint.pathEffect = null
    }
    private fun dot(canvas: Canvas, x: Float, y: Float, tint: Int = color, radius: Float = d(2.2f)): Unit { fillPaint.color = tint; canvas.drawCircle(x, y, radius, fillPaint) }
    private fun bar(canvas: Canvas, index: Int, count: Int, ratio: Double, label: String, value: String, tint: Int): Unit {
        if (count <= 0 || index !in 0 until count) return
        val column: Float = plot.width() / count; val center: Float = plot.left + column * (index + .5f)
        fillPaint.color = ColorUtils.setAlphaComponent(tint, if (enabled) 0x85 else 0x45)
        canvas.drawRoundRect(RectF(center - column * .25f, py(ratio.coerceIn(0.0, 1.0)), center + column * .25f, plot.bottom), d(3f), d(3f), fillPaint)
        text(canvas, value, center, plot.top + d(11f), column - d(3f), tint, Paint.Align.CENTER)
        text(canvas, label, center, plot.bottom + d(11f), column - d(3f), tint, Paint.Align.CENTER)
    }
    private fun node(canvas: Canvas, box: RectF, label: String, tint: Int, strong: Boolean = true): Unit {
        fillPaint.color = ColorUtils.setAlphaComponent(tint, if (strong) 0x24 else 0x0c); canvas.drawRoundRect(box, d(5f), d(5f), fillPaint)
        linePaint.color = tint; linePaint.pathEffect = if (strong) null else DashPathEffect(floatArrayOf(d(2f), d(2f)), 0f)
        canvas.drawRoundRect(box, d(5f), d(5f), linePaint); linePaint.pathEffect = null
        text(canvas, label, box.centerX(), box.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, box.width() - d(5f), tint, Paint.Align.CENTER)
    }
    private fun wait(canvas: Canvas, reason: String): Unit = text(canvas, reason, plot.left + d(3f), plot.centerY(), plot.width() - d(6f), secondary)
    private fun timeSpan(): Double {
        val ir: ConvolutionController.PreparedIr? = shown.prepared
        val source: Double = ir?.let { it.sourceFrames * 1000.0 / it.sourceSampleRate.coerceAtLeast(1) } ?: 500.0
        val required: Double = max(1.0, source + 25)
        return listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 30000.0, 60000.0).firstOrNull { it >= required }
            ?: ceil(required / 60000) * 60000
    }
    override fun onDraw(canvas: Canvas): Unit {
        super.onDraw(canvas); if (!active || width < d(55f) || height < d(55f)) return
        plot.set(d(28f), d(21f), width - d(7f), height - d(26f))
        if (illustration) drawIllustration(canvas) else drawParameters(canvas)
    }
    private fun drawParameters(canvas: Canvas): Unit = when (focus) {
        ConvolutionChartFocus.FILE -> drawIrFrequency(canvas)
        ConvolutionChartFocus.MIX -> drawMix(canvas)
        ConvolutionChartFocus.WET -> drawGain(canvas, true)
        ConvolutionChartFocus.OUTPUT -> drawGain(canvas, false)
        ConvolutionChartFocus.PREDELAY -> drawPredelay(canvas)
        ConvolutionChartFocus.LEGACY_TRIM -> drawTrim(canvas)
        ConvolutionChartFocus.LEGACY_SHIFT -> drawLegacyShift(canvas)
        ConvolutionChartFocus.WINDOW -> drawWindow(canvas)
        ConvolutionChartFocus.NORMALIZATION -> drawNormalization(canvas)
        ConvolutionChartFocus.HIGH_PASS, ConvolutionChartFocus.LOW_PASS,
        ConvolutionChartFocus.LOW_SHELF, ConvolutionChartFocus.HIGH_SHELF -> drawFilter(canvas)
        ConvolutionChartFocus.CHANNEL -> drawChannel(canvas)
        ConvolutionChartFocus.MATRIX -> drawMatrixValues(canvas)
        ConvolutionChartFocus.FRACTIONAL -> drawFractional(canvas)
        ConvolutionChartFocus.SHAPE -> drawShape(canvas)
        ConvolutionChartFocus.TRANSITION -> drawTransition(canvas)
        ConvolutionChartFocus.ANALYSIS -> drawAnalysis(canvas)
        ConvolutionChartFocus.AUDITION -> drawAudition(canvas)
        ConvolutionChartFocus.COMPARISON -> drawComparison(canvas)
    }
    private fun drawIllustration(canvas: Canvas): Unit = when (focus) {
        ConvolutionChartFocus.FILE -> drawEnergy(canvas)
        ConvolutionChartFocus.MIX -> drawMixRoutes(canvas)
        ConvolutionChartFocus.WET -> drawGainWave(canvas, true)
        ConvolutionChartFocus.OUTPUT -> drawGainWave(canvas, false)
        ConvolutionChartFocus.PREDELAY -> drawArrival(canvas)
        ConvolutionChartFocus.LEGACY_TRIM -> drawTrimPicture(canvas)
        ConvolutionChartFocus.LEGACY_SHIFT -> drawLegacyPositions(canvas)
        ConvolutionChartFocus.WINDOW -> drawWindowPicture(canvas)
        ConvolutionChartFocus.NORMALIZATION -> drawNormalizeGroup(canvas)
        ConvolutionChartFocus.HIGH_PASS, ConvolutionChartFocus.LOW_PASS,
        ConvolutionChartFocus.LOW_SHELF, ConvolutionChartFocus.HIGH_SHELF -> drawFilterArea(canvas)
        ConvolutionChartFocus.CHANNEL -> drawChannelRoutes(canvas)
        ConvolutionChartFocus.MATRIX -> drawMatrixRoutes(canvas, false)
        ConvolutionChartFocus.FRACTIONAL -> drawInterpolation(canvas)
        ConvolutionChartFocus.SHAPE -> drawShapePicture(canvas)
        ConvolutionChartFocus.TRANSITION -> drawTransitionPicture(canvas)
        ConvolutionChartFocus.ANALYSIS -> drawAnalysisGuide(canvas)
        ConvolutionChartFocus.AUDITION -> drawAudition(canvas)
        ConvolutionChartFocus.COMPARISON -> drawComparisonPicture(canvas)
    }
    private fun drawIrFrequency(canvas: Canvas): Unit {
        caption(canvas, stamp())
        grid(canvas, "+30 dB", "−90", "20 Hz", "20 kHz")
        val analysis: ConvolutionAdvancedAnalysis.Data? = shown.prepared?.advancedAnalysis
        if (analysis != null) {
            val axis: DoubleArray = analysis.hz.map { it.toDouble() }.toDoubleArray()
            drawing.original.forEach { row -> series(canvas, axis, row.map { it.toDouble() }.toDoubleArray(), ColorUtils.setAlphaComponent(foreground, 0x65), -90.0, 30.0, dashed = true) }
            drawing.working.forEachIndexed { index, row -> series(canvas, axis, row.map { it.toDouble() }.toDoubleArray(), palette(index), -90.0, 30.0) }
        } else if (shown.irDb.isNotEmpty()) shown.irDb.forEachIndexed { index, row -> series(canvas, shown.frequencies, row, palette(index), -90.0, 30.0) }
        else wait(canvas, "等待真实工作IR频响缓存")
        footer(canvas, if (drawing.original.isEmpty()) "工作核彩线 · 原IR对照需深度分析缓存" else "灰原IR/彩工作核 · 不含湿声滤波与混合")
    }
    private fun drawWaveform(canvas: Canvas, prefix: String = "生成IR峰值桶包络"): Unit {
        caption(canvas, prefix)
        val span: Double = timeSpan()
        grid(canvas, "+1", "−1", "0 ms", fmt(span, 0) + " ms")
        if (shown.waveform.isEmpty()) wait(canvas, "等待真实IR包络缓存")
        shown.waveform.forEachIndexed { index, row -> series(canvas, shown.timeMs, row.map { it.toDouble() }.toDoubleArray(), palette(index), -1.0, 1.0, false, span) }
        footer(canvas, stamp() + " · 桶内峰值不是RMS或完整采样")
    }
    private fun drawEnergy(canvas: Canvas): Unit {
        val data: ConvolutionAdvancedAnalysis.Data? = shown.prepared?.advancedAnalysis
        if (data == null) { drawWaveform(canvas, "真实IR包络 · 能量分析待就绪"); return }
        val span: Double = timeSpan()
        caption(canvas, "工作核剩余能量 · 相对本核总能量")
        grid(canvas, "0 dB", "−90", "0 ms", fmt(span, 0) + " ms")
        val axis: DoubleArray = data.timeMs.map { it.toDouble() }.toDoubleArray()
        data.energyDb.forEachIndexed { index, row -> series(canvas, axis, row.map { it.toDouble() }.toDoubleArray(), palette(index), -90.0, 0.0, false, span) }
        footer(canvas, "已有工作IR的前后分布 · 不是房间实测RT60")
    }
    private fun drawMix(canvas: Canvas): Unit {
        caption(canvas, "线性系数 · 湿声 " + fmt(shown.mix * 100) + "%")
        grid(canvas, "1×", "0", "", "")
        bar(canvas, 0, 2, 1 - shown.mix, "原声", fmt(1 - shown.mix, 3) + "×", secondary)
        bar(canvas, 1, 2, shown.mix, "卷积", fmt(shown.mix, 3) + "×", color)
        footer(canvas, "受真实IR相位影响 · 不能把dB按比例相加")
    }
    private fun drawGain(canvas: Canvas, wet: Boolean): Unit {
        val value: Double = if (wet) shown.wetGainDb else shown.outputDb
        caption(canvas, signed(value) + "dB · ×" + fmt(10.0.pow(value / 20), 3))
        grid(canvas, "+1 FS", "−1", "输入幅度", "缩放幅度")
        trace(canvas, secondary, true) { x -> x }
        trace(canvas) { x -> .5 + (x - .5) * 10.0.pow(value / 20) }
        footer(canvas, if (wet) "只缩放卷积分量 · 干声保持" else "本模块混合输出衰减 · 不是末端限幅")
    }
    private fun drawPredelay(canvas: Canvas): Unit {
        caption(canvas, "预延时目标 " + fmt(shown.predelayMs, 3) + "ms")
        grid(canvas, "湿声", "原声", "0 ms", "200 ms")
        line(canvas, plot.left, py(.25), plot.right, py(.25), secondary, true)
        dot(canvas, plot.left, py(.25), secondary, d(3f))
        line(canvas, plot.left, py(.75), px(shown.predelayMs / 200), py(.75), color)
        dot(canvas, px(shown.predelayMs / 200), py(.75), color, d(3f))
        footer(canvas, (if (shown.actualRate) "实际率量化 " else "工作/参考率目标 ") + shown.rate + "Hz · 不改核内移位")
    }
    private fun drawTrim(canvas: Canvas): Unit {
        val legacy: IntArray? = P.legacy(shown.values)
        caption(canvas, "相对整组IR峰值 · 非音乐门限")
        grid(canvas, "0 dB", "−160", "头部", "尾部")
        if (legacy == null || legacy[0] !in -160..0 || legacy[1] !in -160..0) wait(canvas, "六参数文本/阈值无效 · 原文保留") else for (index in 0..1) {
            val x1: Float = px(if (index == 0) .02 else .53); val x2: Float = px(if (index == 0) .47 else .98)
            val y: Float = py((legacy[index] + 160) / 160.0)
            line(canvas, x1, y, x2, y, palette(index), true)
            text(canvas, legacy[index].toString() + "dB", (x1 + x2) / 2, y - d(3f), plot.width() / 2, palette(index), Paint.Align.CENTER)
        }
        footer(canvas, "只定义裁剪判据 · 不据桶包络猜裁剪位置")
    }
    private fun drawLegacyShift(canvas: Canvas): Unit {
        val legacy: IntArray? = P.legacy(shown.values)
        caption(canvas, "四个IR核的原版整数采样值")
        if (legacy == null) { wait(canvas, "六参数文本无效 · 不伪造移位值"); return }
        for (index in 0..3) {
            val y: Float = py(.88 - .25 * index)
            node(canvas, RectF(plot.left, y - d(7f), plot.right, y + d(7f)), "核" + (index + 1) + "：" + legacy[index + 2] + " 采样", palette(index))
        }
        footer(canvas, "原值不换成独立播放声道延时 · 不重复移工作核")
    }
    private fun drawLegacyPositions(canvas: Canvas): Unit {
        val legacy: IntArray? = P.legacy(shown.values)
        caption(canvas, "原核整数偏移 · 源核1/2/3/4顺序")
        val span: Double = max(1.0, shown.prepared?.let { (it.beforeWindowFrames - 1).coerceAtLeast(0).toDouble() } ?: 200000.0)
        grid(canvas, "", "", "0 采样", fmt(span, 0) + " 采样")
        if (legacy == null) { wait(canvas, "六参数文本无效 · 原值保留"); return }
        val count: Int = snapshot.sourceChannels
        for (index in 0..3) {
            val value: Int = legacy[index + 2]
            val available: Boolean = count in listOf(1, 2, 4) && index < count
            val tint: Int = if (available) palette(index) else ColorUtils.setAlphaComponent(foreground, 0x55)
            val y: Float = py(.88 - index * .25)
            val x: Float = px((value / span).coerceIn(0.0, 1.0))
            line(canvas, plot.left, y, plot.right, y, ColorUtils.setAlphaComponent(foreground, 0x35), true)
            fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x35)
            canvas.drawRoundRect(RectF(plot.left, y - d(3f), max(plot.left + d(1f), x), y + d(3f)), d(3f), d(3f), fillPaint)
            dot(canvas, x, y, tint, d(3f))
            text(canvas, "核" + (index + 1) + " " + value + "采样", plot.right, y - d(4f), plot.width(), tint, Paint.Align.RIGHT)
        }
        footer(canvas, "窗口前工作核采样 · 灰核不存在/未知 · 越界标右缘")
    }
    private fun windowEnvelope(time: Double): Double {
        val start: Double = shown.windowStartMs
        val before: Double = shown.prepared?.let { it.beforeWindowFrames * 1000.0 / it.processingSampleRate } ?: 0.0
        val length: Double = if (shown.windowLengthMs > 0) shown.windowLengthMs else max(0.0, before - start)
        if (length <= 0 || time < start || time > start + length) return 0.0
        val local: Double = time - start
        var value: Double = 1.0
        if (shown.fadeInMs > 0 && local < shown.fadeInMs) value *= .5 - .5 * cos(PI * local / shown.fadeInMs)
        if (shown.fadeOutMs > 0 && length - local < shown.fadeOutMs) value *= .5 - .5 * cos(PI * max(0.0, length - local) / shown.fadeOutMs)
        return value
    }
    private fun drawWindow(canvas: Canvas): Unit {
        caption(canvas, "起点 " + fmt(shown.windowStartMs) + " / 长度 " + (if (shown.windowLengthMs == 0.0) "全部" else fmt(shown.windowLengthMs)) + "ms")
        val span: Double = timeSpan()
        grid(canvas, "包络1", "0", "0 ms", fmt(span, 0) + " ms")
        trace(canvas) { x -> windowEnvelope(x * span) }
        footer(canvas, "窗口目标包络 · 不在生成工作核上重复加窗")
    }
    private fun drawNormalization(canvas: Canvas): Unit {
        val ir: ConvolutionController.PreparedIr? = shown.prepared
        caption(canvas, "源率参考目标 " + fmt(P.value(shown.values, P.normTarget) / 10.0) + "dBFS")
        grid(canvas, "+12", "−120", "窗口前", "工作核")
        val target: Float = py((P.value(shown.values, P.normTarget) / 10.0 + 120) / 132)
        line(canvas, plot.left, target, plot.right, target, third, true)
        val levels: List<Double?> = listOf(ir?.originalPeakDb, ir?.workingPeakDb)
        levels.forEachIndexed { index, value -> if (value != null && value.isFinite()) {
            val x: Float = px(if (index == 0) .25 else .75)
            dot(canvas, x, py((value + 120) / 132), palette(index), d(4f))
            text(canvas, fmt(value, 2), x, plot.top + d(10f), plot.width() / 2, palette(index), Paint.Align.CENTER)
        } }
        if (ir == null) wait(canvas, "等待真实IR峰值与归一化记录")
        footer(canvas, "工作峰值可随重采样改变 · 不与目标强行相等")
    }
    private fun filterIndex(): Int = when (focus) { ConvolutionChartFocus.HIGH_PASS -> 0; ConvolutionChartFocus.LOW_PASS -> 1; ConvolutionChartFocus.LOW_SHELF -> 2; else -> 3 }
    private fun drawFilter(canvas: Canvas): Unit {
        val index: Int = filterIndex()
        caption(canvas, fmt(shown.filterHz[index], 0) + "Hz · " + if (index < 2) (shown.filterOrders[index] * 6).toString() + "dB/oct" else signed(shown.shelfGainsDb[index - 2]) + "dB")
        grid(canvas, "+12 dB", "−60", "20 Hz", "20 kHz")
        series(canvas, shown.frequencies, shown.eqDb, secondary, -60.0, 12.0, dashed = true)
        series(canvas, shown.frequencies, shown.eqBandsDb[index], color, -60.0, 12.0)
        footer(canvas, "只绘湿声滤波 · 不含IR/混合/输出校准")
    }
    private fun drawChannel(canvas: Canvas): Unit {
        caption(canvas, "湿声输出校准 · 原声支路不变")
        grid(canvas, "+12 dB", "−12", "左", "右")
        for (index in 0..1) {
            val value: Double = if (index == 0) shown.leftTrimDb else shown.rightTrimDb
            val x: Float = px(if (index == 0) .25 else .75)
            line(canvas, x, py(.5), x, py((value + 12) / 24), palette(index)); dot(canvas, x, py((value + 12) / 24), palette(index), d(4f))
            text(canvas, signed(value) + "dB", x, plot.top + d(10f), plot.width() / 2, palette(index), Paint.Align.CENTER)
        }
        footer(canvas, "联动只同步后续编辑 · 已存左右差异仍保留")
    }
    private fun drawMatrixValues(canvas: Canvas): Unit {
        caption(canvas, if (shown.route == 1) "输入先取(L+R)/2 · 路径参数目标" else "四路径参数目标 · 非真实声像角度")
        for (index in 0..3) {
            val y: Float = py(.88 - index * .25)
            val available: Boolean = snapshot.pathAvailable(index)
            val label: String = A.paths[index] + " " + signed(shown.pathGainsDb[index]) + "dB" +
                (if (shown.pathMuted[index]) " 静音" else "") + (if (shown.pathInverted[index]) " −" else " +")
            node(canvas, RectF(plot.left, y - d(7f), plot.right, y + d(7f)), label, if (available) palette(index) else ColorUtils.setAlphaComponent(foreground, 0x55), available)
        }
        footer(canvas, "原1/2核只有直通 · 原4核才可编辑交叉路径")
    }
    private fun drawFractional(canvas: Canvas): Unit {
        val common: Int = if (shown.fractionalMethod == 0) 1 else 16
        caption(canvas, (if (shown.fractionalMethod == 0) "三阶Lagrange" else "33抽头sinc FIR") + " · 公共 " + common + "采样")
        grid(canvas, "20 ms", "0", "", "")
        for (index in 0..3) bar(canvas, index, 4, shown.pathDelayMs[index] / 20, (index + 1).toString(), fmt(shown.pathDelayMs[index], 3),
            if (snapshot.pathAvailable(index)) palette(index) else ColorUtils.setAlphaComponent(foreground, 0x55))
        footer(canvas, "追加各路径目标 · 公共延迟同步匹配干声")
    }
    private fun drawShape(canvas: Canvas): Unit {
        val span: Double = timeSpan()
        caption(canvas, "前 " + signed(shown.earlyDb) + " / 尾 " + signed(shown.lateDb) + "dB · " + fmt(shown.decayDbPerSecond) + "dB/s")
        grid(canvas, "+6 dB", "−60", "0 ms", fmt(span, 0) + " ms")
        trace(canvas) { x -> (shown.shapingDb(x * span) + 60) / 66 }
        val split: Float = px((shown.splitMs / span).coerceIn(0.0, 1.0))
        line(canvas, split, plot.top, split, plot.bottom, third, true)
        footer(canvas, "相对窗口后IR起点 · 参数包络，不重复乘生成核")
    }
    private fun transitionMode(): Int {
        val saved: Int = A.choice(shown.values, A.SWITCH)
        return if (saved == 0 || saved == 1 && key in setOf(A.tail.key, A.exitFade.key)) 2 else saved
    }
    private fun drawTransition(canvas: Canvas): Unit {
        val values: Map<String, *> = shown.values
        val fade: Double = A.value(values, A.fade) / 1000.0
        val tail: Double = A.value(values, A.tail).toDouble()
        val exit: Double = min(A.value(values, A.exitFade) / 1000.0, tail)
        val end: Double = fade + tail
        val mode: Int = transitionMode()
        caption(canvas, if (mode == 1) "新/旧输出权重 · 淡化 " + fmt(fade) + "ms" else
            "输入淡化 " + fmt(fade) + " / 保尾≤" + fmt(tail, 0) + " / 退出 " + fmt(exit) + "ms")
        grid(canvas, "权重1", "0", "0 ms", "3500 ms")
        trace(canvas) { x -> (x * 3500 / fade).coerceIn(0.0, 1.0) }
        trace(canvas, secondary) { x -> 1 - (x * 3500 / fade).coerceIn(0.0, 1.0) }
        if (mode == 2) {
            fillPaint.color = ColorUtils.setAlphaComponent(third, 0x1f)
            canvas.drawRect(px(fade / 3500), plot.top, px(end / 3500), plot.bottom, fillPaint)
            fillPaint.color = ColorUtils.setAlphaComponent(third, 0x30)
            canvas.drawRect(px((end - exit) / 3500), plot.top, px(end / 3500), plot.bottom, fillPaint)
            trace(canvas, third, true) { x ->
                val time: Double = x * 3500
                if (time <= fade) 1.0 else ((end - time) / max(exit, .001)).coerceIn(0.0, 1.0)
            }
        }
        footer(canvas, if (mode == 1) "输出交叉淡化 · 旧尾上限/退出参数不参与" else "橙虚：旧尾输出允许窗上限 · 不是实际尾音能量")
    }
    private fun drawAnalysis(canvas: Canvas): Unit {
        val mode: Int = A.choice(shown.values, A.GRAPH)
        if (mode == 0) { drawIrFrequency(canvas); return }
        if (mode == 3) { drawEnergy(canvas); return }
        val data: ConvolutionAdvancedAnalysis.Data? = shown.prepared?.advancedAnalysis
        caption(canvas, (if (mode == 1) "包裹相位 °" else "群延迟 ms") + " · " + stamp())
        val low: Double = if (mode == 1) -180.0 else -50.0
        val high: Double = if (mode == 1) 180.0 else 500.0
        grid(canvas, fmt(high, 0), fmt(low, 0), "20 Hz", "20 kHz")
        if (data == null) wait(canvas, "等待真实工作核深度分析") else {
            val rows: List<FloatArray> = if (mode == 1) data.phaseDegrees else data.groupDelayMs
            val axis: DoubleArray = data.hz.map { it.toDouble() }.toDoubleArray()
            rows.forEachIndexed { index, row -> series(canvas, axis, row.map { it.toDouble() }.toDoubleArray(), palette(index), low, high, phase = mode == 1) }
        }
        footer(canvas, "工作核起点参考 · 深凹陷留空 · 非设备总延迟")
    }
    private fun drawAudition(canvas: Canvas): Unit {
        val labels: List<String> = listOf("正常混合", "原声旁路", "仅卷积湿声", "混合输出−原声")
        caption(canvas, "当前对照：" + labels[snapshot.audition.coerceIn(0, 3)])
        for (index in 0..3) {
            val y: Float = py(.88 - index * .25)
            node(canvas, RectF(plot.left, y - d(7f), plot.right, y + d(7f)), labels[index], if (index == snapshot.audition) color else secondary, index == snapshot.audition)
        }
        footer(canvas, "原生三路表在对照前 · 实际试听PCM另标注")
    }
    private fun drawComparison(canvas: Canvas): Unit {
        caption(canvas, "B手动临时补偿 " + signed(snapshot.comparison / 10.0) + "dB")
        grid(canvas, "+12 dB", "−12", "输入幅度", "补偿幅度")
        trace(canvas, secondary, true) { x -> x }
        trace(canvas) { x -> .5 + (x - .5) * 10.0.pow(snapshot.comparison / 200.0) }
        footer(canvas, "应用A/B会写回当前参数 · 没有自动等电平测量")
    }
    private fun drawMixRoutes(canvas: Canvas): Unit {
        caption(canvas, "原声 " + fmt((1 - shown.mix) * 100) + "% + 湿声 " + fmt(shown.mix * 100) + "%")
        for (index in 0..1) {
            val y: Float = py(if (index == 0) .75 else .25)
            val coefficient: Double = if (index == 0) 1 - shown.mix else shown.mix
            val tint: Int = if (coefficient > 0) palette(index) else ColorUtils.setAlphaComponent(foreground, 0x40)
            val box: RectF = RectF(plot.left, y - d(10f), px(.74), y + d(10f))
            node(canvas, box, if (index == 0) "公共延迟匹配的原声" else "IR卷积与湿声处理", tint, coefficient > 0)
            fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x95)
            canvas.drawRoundRect(RectF(box.left, box.bottom - d(3f), box.left + box.width() * coefficient.toFloat(), box.bottom), d(2f), d(2f), fillPaint)
            linePaint.strokeWidth = d(.6f) + d(3.4f) * coefficient.toFloat()
            line(canvas, px(.74), y, px(.95), py(.5), tint, coefficient == 0.0)
            linePaint.strokeWidth = d(1.5f)
        }
        dot(canvas, px(.95), py(.5), third, d(4f))
        footer(canvas, "公共插值延迟匹配 · 不消除IR自身相位/前置静音")
    }
    private fun drawGainWave(canvas: Canvas, wet: Boolean): Unit {
        val value: Double = if (wet) shown.wetGainDb else shown.outputDb
        caption(canvas, (if (wet) "卷积分量" else "混合输出") + "幅度缩放参考")
        grid(canvas, "+1", "−1", "示意波形", "")
        trace(canvas, secondary, true) { x -> .5 + .30 * sin(6 * PI * x) }
        trace(canvas) { x -> .5 + .30 * sin(6 * PI * x) * 10.0.pow(value / 20) }
        footer(canvas, "仅解释固定增益 · 不是模拟实测音频")
    }
    private fun drawArrival(canvas: Canvas): Unit {
        caption(canvas, "湿声相对原声再延后 " + fmt(shown.predelayMs, 3) + "ms")
        val labels: List<String> = listOf("原声不补IR前置静音", "湿声：已有IR + 预延时")
        for (index in 0..1) {
            val y: Float = py(if (index == 0) .75 else .25)
            node(canvas, RectF(plot.left, y - d(10f), plot.right, y + d(10f)), labels[index], palette(index))
        }
        footer(canvas, "参数到达示意 · 不是物理距离或设备往返延迟")
    }
    private fun drawTrimPicture(canvas: Canvas): Unit {
        caption(canvas, "阈值决定保留边界 · 依真实IR峰值判断")
        val labels: List<String> = listOf("头部低于阈值", "保留工作主体", "尾部低于阈值")
        for (index in 0..2) {
            val x1: Float = px(index / 3.0 + .015); val x2: Float = px((index + 1) / 3.0 - .015)
            node(canvas, RectF(x1, py(.73), x2, py(.28)), labels[index], palette(index), index == 1)
        }
        footer(canvas, "结构示意，宽度不代表实际裁去时长")
    }
    private fun drawWindowPicture(canvas: Canvas): Unit {
        drawWindow(canvas)
        text(canvas, "淡入 " + fmt(shown.fadeInMs) + " / 淡出 " + fmt(shown.fadeOutMs) + "ms", plot.centerX(), plot.top + d(11f), plot.width(), secondary, Paint.Align.CENTER)
    }
    private fun drawNormalizeGroup(canvas: Canvas): Unit {
        val ir: ConvolutionController.PreparedIr? = shown.prepared
        caption(canvas, "整组共同归一化 · 核间比例保留")
        if (ir == null) { wait(canvas, "等待生成IR的归一化记录"); footer(canvas, "不从频响峰值反推IR样本归一化"); return }
        val labels: List<String> = listOf("源率参考归一化 " + signed(ir.normalizationGainDb, 2) + "dB", "采样率补偿 " + signed(ir.sampleRateCompensationDb, 2) + "dB", if (P.enabled(shown.values, P.NORM_ONLY_ATTENUATION)) "仅允许归一化衰减" else "允许参考归一化提升")
        labels.forEachIndexed { index, label -> val y: Float = py(.83 - index * .33); node(canvas, RectF(plot.left, y - d(9f), plot.right, y + d(9f)), label, palette(index)) }
        footer(canvas, "样本峰值目标 · 不是频响峰值或音乐限幅")
    }
    private fun drawFilterArea(canvas: Canvas): Unit {
        val index: Int = filterIndex()
        caption(canvas, if (index == 0) "只收束湿声低端" else if (index == 1) "只柔化湿声高端" else "只改变湿声音色")
        val bounds: List<Double> = listOf(20.0, 180.0, 2500.0, 20000.0)
        for (band in 0..2) { fillPaint.color = ColorUtils.setAlphaComponent(palette(band), 0x18); canvas.drawRect(fx(bounds[band]), plot.top, fx(bounds[band + 1]), plot.bottom, fillPaint) }
        series(canvas, shown.frequencies, shown.eqBandsDb[index], color, -60.0, 12.0)
        text(canvas, "低音", fx(60.0), plot.bottom + d(11f), plot.width() / 3, secondary, Paint.Align.CENTER)
        text(canvas, "中频", fx(650.0), plot.bottom + d(11f), plot.width() / 3, color, Paint.Align.CENTER)
        text(canvas, "高频", fx(7000.0), plot.bottom + d(11f), plot.width() / 3, third, Paint.Align.CENTER)
        footer(canvas, if (index < 2) "Butterworth滚降 · 原声支路仍保留" else "固定S=1搁架 · 非多频段动态压缩")
    }
    private fun drawChannelRoutes(canvas: Canvas): Unit {
        caption(canvas, if (shown.swap) "湿声左右交换 · 原声不交换" else "湿声左右输出校准")
        for (index in 0..1) {
            val y: Float = py(if (index == 0) .75 else .25)
            val target: Int = if (shown.swap) 1 - index else index
            val endY: Float = py(if (target == 0) .75 else .25)
            node(canvas, RectF(plot.left, y - d(9f), px(.30), y + d(9f)), if (index == 0) "湿L" else "湿R", palette(index))
            line(canvas, px(.30), y, px(.68), endY, palette(index))
            node(canvas, RectF(px(.68), endY - d(9f), plot.right, endY + d(9f)), (if (target == 0) "L" else "R") +
                (if (if (target == 0) shown.invertLeft else shown.invertRight) " −" else " +"), palette(index))
        }
        footer(canvas, "路线与极性示意 · 不从参数虚构相位云")
    }
    private fun drawMatrixRoutes(canvas: Canvas, shifts: Boolean): Unit {
        caption(canvas, if (shifts) "核1–4位置参数 · 非播放声道延时" else "真实可用路径依原IR通道数")
        for (index in 0..3) {
            val left: Int = if (index < 2) 0 else 1; val right: Int = if (index == 0 || index == 2) 0 else 1
            val sy: Float = py(if (left == 0) .78 else .22); val ey: Float = py(if (right == 0) .78 else .22)
            val available: Boolean = snapshot.pathAvailable(index)
            val tint: Int = if (available && !shown.pathMuted[index]) palette(index) else ColorUtils.setAlphaComponent(foreground, 0x40)
            path.rewind(); path.moveTo(px(.12), sy); path.cubicTo(px(.42), sy, px(.58), ey, px(.88), ey)
            linePaint.strokeWidth = d(1f) + if (available && !shown.pathMuted[index]) d(2f) * (10.0.pow(shown.pathGainsDb[index] / 20) / 4).toFloat() else 0f
            stroke(canvas, tint, !available || shown.pathMuted[index]); linePaint.strokeWidth = d(1.5f)
        }
        for (index in 0..1) { val y: Float = py(if (index == 0) .78 else .22); dot(canvas, px(.12), y, palette(index), d(6f)); dot(canvas, px(.88), y, palette(index), d(6f)) }
        text(canvas, "输入 L / R", plot.left, plot.bottom + d(11f), plot.width() / 2, color)
        text(canvas, "输出 L / R", plot.right, plot.bottom + d(11f), plot.width() / 2, secondary, Paint.Align.RIGHT)
        footer(canvas, "连线粗细为参数强弱 · 非实测声场或距离")
    }
    private fun drawInterpolation(canvas: Canvas): Unit {
        val selected: Int = snapshot.selectedPath(key).takeIf { it >= 0 } ?: 0
        val delay: Double = shown.pathDelayMs[selected]
        val fraction: Double = (delay * shown.rate / 1000).let { it - floor(it) }
        if (shown.fractionalMethod == 1) {
            caption(canvas, "33抽头窗化sinc · 分数采样 " + fmt(fraction, 3))
            grid(canvas, "+1", "−1", "0", "32 抽头")
            val taps: DoubleArray = DoubleArray(33) { index ->
                val x: Double = index - 16 - fraction
                (if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)) *
                    (.42 - .5 * cos(2 * PI * index / 32) + .08 * cos(4 * PI * index / 32))
            }
            val sum: Double = taps.sum()
            for (index in taps.indices) {
                val x: Float = px(index / 32.0); val y: Float = py(.5 + taps[index] / sum * .45)
                line(canvas, x, py(.5), x, y, color); dot(canvas, x, y, secondary, d(1.4f))
            }
            footer(canvas, "真实参数滤波系数 · 公共延迟16采样，非IR采样")
            return
        }
        caption(canvas, "通用插值示意 · 分数采样 " + fmt(fraction, 3))
        grid(canvas, "+1", "−1", "0", "3 采样")
        val samples: DoubleArray = doubleArrayOf(-.65, .55, .85, -.25)
        trace(canvas) { x ->
            val t: Double = 3 * x
            var value: Double = 0.0
            for (i in 0..3) { var weight: Double = 1.0; for (j in 0..3) if (j != i) weight *= (t - j) / (i - j); value += weight * samples[i] }
            .5 + value * .35
        }
        for (index in 0..3) dot(canvas, px(index / 3.0), py(.5 + samples[index] * .35), secondary)
        line(canvas, px((1 + fraction) / 3), plot.top, px((1 + fraction) / 3), plot.bottom, third, true)
        footer(canvas, "三阶多项式示意 · 不是当前IR采样点")
    }
    private fun drawShapePicture(canvas: Canvas): Unit {
        caption(canvas, "只塑形已有前声与尾声 · 不延长IR")
        val span: Double = timeSpan()
        grid(canvas, "+6 dB", "−60", "0 ms", fmt(span, 0) + " ms")
        val x: Float = px((shown.splitMs / span).coerceIn(0.0, 1.0))
        fillPaint.color = ColorUtils.setAlphaComponent(secondary, 0x1f); canvas.drawRect(plot.left, plot.top, x, plot.bottom, fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(color, 0x1f); canvas.drawRect(x, plot.top, plot.right, plot.bottom, fillPaint)
        trace(canvas) { t -> (shown.shapingDb(t * span) + 60) / 66 }
        footer(canvas, "半余弦过渡 + dB/s附加收敛 · 不等于实测RT60")
    }
    private fun drawTransitionPicture(canvas: Canvas): Unit {
        val fade: Double = A.value(shown.values, A.fade) / 1000.0
        val tail: Double = A.value(shown.values, A.tail).toDouble()
        val exit: Double = min(A.value(shown.values, A.exitFade) / 1000.0, tail)
        val end: Double = fade + tail
        val mode: Int = transitionMode()
        caption(canvas, if (mode == 1) "线性输出淡化 · 灰色为保尾参数预览" else "保尾时间上限窗 · 起点为换核开始")
        grid(canvas, "", "", "0 ms", "3500 ms")
        val starts: List<Double> = listOf(0.0, fade, end - exit)
        val ends: List<Double> = listOf(fade, end, end)
        val labels: List<String> = listOf("淡化 " + fmt(fade) + "ms", "零输入旧尾≤" + fmt(tail, 0) + "ms", "结束淡出 " + fmt(exit) + "ms")
        for (index in 0..2) {
            val y: Float = py(.84 - index * .33)
            val tint: Int = if (mode == 1 && index > 0) ColorUtils.setAlphaComponent(foreground, 0x45) else palette(index)
            line(canvas, plot.left, y, plot.right, y, ColorUtils.setAlphaComponent(foreground, 0x30), true)
            fillPaint.color = ColorUtils.setAlphaComponent(tint, 0x65)
            canvas.drawRoundRect(RectF(px(starts[index] / 3500), y - d(5f), px(ends[index] / 3500), y + d(5f)), d(3f), d(3f), fillPaint)
            text(canvas, labels[index], plot.right, y - d(6f), plot.width(), tint, Paint.Align.RIGHT)
        }
        footer(canvas, "实际保尾=min(旧核时长,上限) · 退出窗位于其末端")
    }
    private fun drawAnalysisGuide(canvas: Canvas): Unit {
        caption(canvas, "四类分析各用独立单位与纵轴")
        val labels: List<String> = listOf("幅频：dB / Hz", "相位：° / Hz", "群延迟：ms / Hz", "剩余能量：dB / ms")
        val selected: Int = A.choice(shown.values, A.GRAPH)
        labels.forEachIndexed { index, label -> val y: Float = py(.88 - index * .25); node(canvas, RectF(plot.left, y - d(7f), plot.right, y + d(7f)), label, palette(index), selected == index) }
        footer(canvas, "生成工作核分析 · 不含湿声滤波/干湿/校准")
    }
    private fun drawComparisonPicture(canvas: Canvas): Unit {
        caption(canvas, "参数快照不是私有双核试听测量")
        val labels: List<String> = listOf("记录当前参数到 A / B", "应用：写回当前参数", "B可手动临时补偿 ±12dB")
        labels.forEachIndexed { index, label -> val y: Float = py(.84 - index * .33); node(canvas, RectF(plot.left, y - d(9f), plot.right, y + d(9f)), label, palette(index)) }
        footer(canvas, "正常编辑只结束补偿 · 不回滚已应用参数")
    }
    private fun details(): String {
        val values: Map<String, *> = snapshot.values
        val ir: ConvolutionController.PreparedIr? = shown.prepared
        val state: String = if (enabled) "保存参数与生成IR缓存的只读显示。" else snapshot.reason(focus, key) + "；灰色目标不会启用声音参数。"
        val explanation: String = when (focus) {
            ConvolutionChartFocus.FILE -> "灰线仅在真实原IR分析缓存可用时显示；彩线来自生成工作IR。已包含原版优化、窗口、路径矩阵、前后段塑形、分数延时与归一化，不能再对这些曲线重复应用加工。这里不是驱动活动核样本回读。\n右图若有深度分析则画每核剩余能量；否则画真实峰值桶包络并明确它不是RMS/能量。无IR时等待，不用示例脉冲代替。"
            ConvolutionChartFocus.MIX -> "混合是线性系数，0%为原声，100%为卷积湿声。IR相位及预延时会导致中间比例的增强或抵消，不能把湿声频响dB乘mix后当最终输出。只为分数插值新增的1或16个公共采样延迟匹配干声，不补偿IR自身前置静音、原核相对移位或湿声预延时。"
            ConvolutionChartFocus.WET -> "湿声增益只缩放卷积分量，原声支路保持。与窗口前尾增益、路径增益和湿声左右校准是不同位置。图中单项幅度线不是最终频响或实际RMS；真实输入/湿声/输出由实时区读取。"
            ConvolutionChartFocus.OUTPUT -> "模块输出衰减作用于本模块正常混合输出，不能替代末端总音量或限制器。0dB为×1，最小−24dB。固定波形仅解释增益缩放，不是当前音乐或削波保证。"
            ConvolutionChartFocus.PREDELAY -> "保存为整数微秒，显示ms；播放按实际卷积采样率量化为整采样。原生±操作一次移动一个实际采样，未知实际率时不能把48kHz假定为设备采样率。图上工作率目标可供离线预览，但不等于已生效时间或设备往返延迟。湿声预延时不改原四核移位。"
            ConvolutionChartFocus.LEGACY_TRIM -> "原六字段中头部/尾部阈值为整数dB，相对整组IR峰值，仅收缩/最小相变和收缩模式使用；不是音乐噪声门或动态阈值。图只展示判据，不从已裁剪工作IR或峰值桶猜原文件裁剪边界。原始模式下灰色保留阈值，旧文本无效时原样保留。"
            ConvolutionChartFocus.LEGACY_SHIFT -> "原六字段后四项为各IR核整数采样移位，保存在同一分号字符串；它们不是独立播放左右声道的毫秒延时。范围按IR长度校验，修改单项不覆盖其它旧项。本图只列原保存采样值，不把生成工作核再移动一次，也不猜未知采样域的毫秒数。"
            ConvolutionChartFocus.WINDOW -> "在原版优化后的工作IR上保留起点、长度及淡入淡出；长度0保留起点以后全部。参数保存微秒，但编辑为0.1ms刻度，再量化到工作采样；窗口淡化使用半余弦。左/右图仅画窗口目标包络，不将其重复乘到已生成工作核上。实际边界受IR长度校验，非法保存值保留并由原状态说明错误。"
            ConvolutionChartFocus.NORMALIZATION -> "按文件原采样率完成原优化、时间窗及高级编辑后，对整组IR样本峰值只计算一次归一化增益，保留核间比例；可只允许衰减。不同工作率共用参考增益并有重采样频响补偿，所以工作核样本峰值不强行等于目标。这里不是频响最大值归一化、音乐响度匹配、压缩器或限幅器。窗口前峰值和工作核峰值属于不同处理阶段，不据二者反推目标增益。"
            ConvolutionChartFocus.HIGH_PASS, ConvolutionChartFocus.LOW_PASS -> "湿声高/低通为Butterworth 12/24/48dB每倍频程，实际频率上限为卷积处理率45%；保存频率不改。只处理卷积湿声，原声支路仍保留。灰色预览仅在显示副本启用当前滤波，其他滤波保持当前保存开关；不修改音频或原IR。"
            ConvolutionChartFocus.LOW_SHELF, ConvolutionChartFocus.HIGH_SHELF -> "湿声搁架频率/增益独立设置，RBJ S固定为1，没有隐藏Q或架式S滑杆。增益0dB时频率改变不会凭空产生作用。这不是多频段动态压缩或新增谐波层。图为湿声滤波参数模型，不含IR、干湿混合、电平校准或下游音效。"
            ConvolutionChartFocus.CHANNEL -> "左右校准、反相与交换位于卷积湿声输出，原声不受影响。左右联动仅影响后续增益编辑，会同步写入另一侧；开启联动不会抹掉已有左右保存差异，绘图必须继续使用两侧实际保存值。单项复位只恢复当前项，与四路径内部gain/mute/invert不是同一层。"
            ConvolutionChartFocus.MATRIX -> "原单/双核只有两条直通路径；原四核IR才可单独编辑两条交叉路径。输入单声道转立体声先取(L+R)/2，并不会创造真实四路径测量。路径矩阵gain、静音与极性在离线工作IR中完成，本图只解释保存参数，不再加工生成核。直通/交叉增益联动只同步之后的编辑，已有差异不自动覆盖。"
            ConvolutionChartFocus.FRACTIONAL -> "四路径追加延时保存微秒，控制刻度0.001ms；它发生在原核移位后。三阶Lagrange增加1个公共采样，33抽头Blackman窗化sinc FIR增加16个；干声同步匹配该公共延迟，原路径时间差保留，不自动对齐主峰。通用晶格图不是当前IR采样；实际高精度方法是33抽头FIR，不是把三阶曲线称为FIR。生成工作核已经包含插值，禁止重复施加。"
            ConvolutionChartFocus.SHAPE -> "分界相对第一轮窗口后的IR起点，过渡采用半余弦；前/尾段增益单位dB，附加尾衰单位dB/s，不能混称ms或实际RT60。它只收敛已有尾段，不延长原IR。参数包络与已加工工作核分开显示，不把塑形重复乘入；校正IR使用会改变其校正曲线。"
            ConvolutionChartFocus.TRANSITION -> "换核选择原方式、线性交叉淡化或旧尾自然衰减。线性方式是新旧输出的线性交叉权重；保尾方式则交叉淡化新旧输入后，让旧核零输入继续衰减。保尾段时长=min(实际旧核时长,旧尾保留上限)，位于输入淡化之后；结束淡出位于保尾段末端，其实际长度=min(结束淡出参数,保尾段时长)。\n本图没有活动旧核样本/时长回读，因此按保存上限画允许窗，不把窗权重当实际旧尾能量。当前线性方式下触摸tail/exit时只灰色预览保尾参数，不改变真实方式。淡化/尾长/退出属于换核时钟，不是IR预延时、IR长度或RT60。最多两套计划，资源过大可回退轻量过渡；实际方式/剩余/回退见实时区。"
            ConvolutionChartFocus.ANALYSIS -> "幅度dB、包裹相位°、群延迟ms共用频率轴但不共纵轴；剩余能量dB对时间ms。相位和群延迟以生成工作核起点为参考，深凹陷/静音处留空；±180°跳变处断线。倍频程平滑只用于幅度显示，不修改播放，不能对相位/群延迟套相同平滑。工作核分析不含湿声滤波、干湿、输出校准或后续音效，不是设备延迟/房间实测。"
            ConvolutionChartFocus.AUDITION -> "四路为正常混合输出、模块原声旁路、仅卷积湿声、效果差分（混合输出减原声）。原生100ms三路监测为输入/校准后湿声/正常混合输出，位于临时试听之前；实时PCM实际输出另标，不能把正常输出读数冒充当前试听。效果差分不作自动等电平，可能包含IR相位/前置静音/延时共同差异。"
            ConvolutionChartFocus.COMPARISON -> "记录A/B保存当前同IR参数；应用A/B会真实写回当前参数，包括原模式、六参数和新增设置。返回正常编辑只结束临时补偿，不回滚已经应用的参数。B补偿是手动±12dB临时量，不写预设，不是自动测量或匹配；本模块没有最近其他模块的2秒双快照等电平测量。快照按同IR指纹使用，图纸不读取私有slots，也不代替原按钮。"
        }
        val controls: List<me.timschneeberger.rootlessjamesdsp.fusion.BassParameters.Control> = when (focus) {
            ConvolutionChartFocus.MIX -> listOf(P.mix); ConvolutionChartFocus.WET -> listOf(P.wet); ConvolutionChartFocus.OUTPUT -> listOf(P.output)
            ConvolutionChartFocus.PREDELAY -> listOf(P.predelay); ConvolutionChartFocus.WINDOW -> P.window
            ConvolutionChartFocus.NORMALIZATION -> listOf(P.normTarget)
            ConvolutionChartFocus.HIGH_PASS -> listOf(A.hp); ConvolutionChartFocus.LOW_PASS -> listOf(A.lp)
            ConvolutionChartFocus.LOW_SHELF -> listOf(A.lowHz, A.lowGain); ConvolutionChartFocus.HIGH_SHELF -> listOf(A.highHz, A.highGain)
            ConvolutionChartFocus.CHANNEL -> listOf(P.left, P.right); ConvolutionChartFocus.MATRIX -> A.gain
            ConvolutionChartFocus.FRACTIONAL -> A.delay; ConvolutionChartFocus.SHAPE -> A.shapeControls
            ConvolutionChartFocus.TRANSITION -> A.transitionControls; else -> emptyList()
        }
        val saved: String = controls.joinToString("\n") { c -> c.title + "：" + c.label(P.value(values, c)) }
        val metadata: String = ir?.let { "\n\n生成IR：" + java.io.File(it.sourcePath).name + "\n源率 " + it.sourceSampleRate + "Hz / 工作率 " + it.processingSampleRate + "Hz；" + it.channels + "核 / " + it.workingFrames + "帧\nCRC " + it.crc.toUInt().toString(16) + " · " + stamp() +
            "\n峰位置（工作采样）：" + it.peakFrames.joinToString("/") + "\n原六参数文本：" + P.legacyText(values) } ?: "\n\n无完整生成IR缓存；不以示例样本替代。\n原六参数文本：" + P.legacyText(values)
        return (if (illustration) focus.rightTitle else focus.leftTitle) + "\n\n" + state + "\n\n" + explanation +
            (if (saved.isEmpty()) "" else "\n\n保存参数：\n" + saved) + metadata +
            "\n\n频率轴固定20Hz–20kHz，幅度窗口外裁绘；时间轴按原文件时长分档固定，不因实时读数跳动。模型/生成缓存不是活动核样本回读。长按只查看，不发参数或编辑IR。"
    }
    override fun onDetachedFromWindow(): Unit { generation++; ConvStaticWork.release(this); request = null; detailDialog?.dismiss(); detailDialog = null; super.onDetachedFromWindow() }
}
