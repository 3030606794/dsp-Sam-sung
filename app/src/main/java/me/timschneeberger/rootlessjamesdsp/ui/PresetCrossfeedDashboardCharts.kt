package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.*
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedDashboardRuntimeMonitor
import java.util.Locale
import kotlin.math.*

/** The host explicitly selects one chart; invisible pages own no computation or sampling loop. */
internal abstract class PresetCrossfeedDashboardChart(context: Context) : View(context) {
    init { PresetCrossfeedDashboardModel.initialize(context) }
    var onStatusChanged: (() -> Unit)? = null
    protected val density = resources.displayMetrics.density
    protected val foregroundColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    protected val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    protected val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    protected val colors = intArrayOf(accent,
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary),
        if (dark) Color.rgb(108, 210, 241) else Color.rgb(15, 126, 164),
        if (dark) Color.rgb(255, 193, 110) else Color.rgb(177, 100, 12))
    protected val warningColor = if (dark) Color.rgb(255, 131, 131) else Color.rgb(184, 46, 46)
    protected val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foregroundColor; textSize = min(9.5f * resources.displayMetrics.scaledDensity, 11.5f * density)
    }
    protected val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0x30); strokeWidth = density; style = Paint.Style.STROKE
    }
    protected val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 1.5f * density; style = Paint.Style.STROKE }
    protected val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val path = Path()
    protected val dash = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    protected var configuration: PresetCrossfeedDashboardModel? = null
        private set
    protected var liveSample: PresetCrossfeedDashboardRuntimeMonitor.Sample? = null
        private set
    protected var pauseLabel = "监视已关闭"
        private set
    private var selected = false
    private var attached = false
    protected val canCompute get() = selected && attached && visibility == VISIBLE && windowVisibility == VISIBLE
    fun setActive(active: Boolean) {
        if (selected == active) return
        selected = active; onActivationChanged(); if (active) invalidate()
    }
    fun setModel(model: PresetCrossfeedDashboardModel) {
        if (configuration === model) return
        configuration = model
        if (!model.effectEnabled) liveSample = null
        onModelChanged(); if (canCompute) invalidate()
    }
    fun showSample(sample: PresetCrossfeedDashboardRuntimeMonitor.Sample) {
        liveSample = sample; onSampleChanged(sample); if (canCompute) invalidate()
    }
    fun pause(label: String) { liveSample = null; pauseLabel = label; onPaused(); if (canCompute) invalidate() }
    protected open fun onModelChanged() = Unit
    protected open fun onActivationChanged() = Unit
    protected open fun onSampleChanged(sample: PresetCrossfeedDashboardRuntimeMonitor.Sample) = Unit
    protected open fun onPaused() = Unit
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; onActivationChanged() }
    override fun onDetachedFromWindow() { attached = false; onActivationChanged(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); onActivationChanged() }
    protected fun data(sample: PresetCrossfeedDashboardRuntimeMonitor.Sample? = liveSample): List<Int>? {
        val value = sample ?: return null
        val reading = value.reading ?: return null
        val basic = reading.snapshot ?: return null
        val state = basic.data
        if (configuration?.effectEnabled == false || !value.output.connected || value.output.globalIssue != null ||
            value.output.processing != OutputRuntimeStatus.Processing.ACTIVE || !reading.enabled || !reading.active ||
            !basic.enabled || !basic.professional || state[3] != 1 || state[4] != 1 || state[23] != 1) return null
        if ((20..21).all { state[it] == Int.MIN_VALUE }) return null
        return state
    }
    protected fun number(values: List<Int>?, index: Int, divisor: Double = 100.0) =
        values?.getOrNull(index)?.takeUnless { it == Int.MIN_VALUE }?.toDouble()?.div(divisor)?.takeIf { it.isFinite() }
    protected fun fmt(value: Double?, digits: Int = 1) = value?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: "—"
    protected fun label(canvas: Canvas, text: String, x: Float, y: Float, available: Float,
        color: Int = foregroundColor, align: Paint.Align = Paint.Align.LEFT) {
        if (available <= 0f) return
        val original = textPaint.textSize
        textPaint.color = color; textPaint.textAlign = align
        val measured = textPaint.measureText(text)
        if (measured > available) textPaint.textSize *= available / measured
        canvas.drawText(text, x, y, textPaint); textPaint.textSize = original
    }
    protected fun dot(canvas: Canvas, x: Float, y: Float, color: Int, r: Float = 3f * density) {
        fillPaint.color = color; canvas.drawCircle(x, y, r, fillPaint)
    }
    protected fun tooltip(canvas: Canvas, rows: List<String>, desiredX: Float, desiredY: Float) {
        if (rows.isEmpty()) return
        val pad = 5f * density; val line = textPaint.textSize * 1.3f
        val w = min(width - 4f * density, rows.maxOf { textPaint.measureText(it) } + pad * 2f)
        val h = rows.size * line + pad * 2f
        val x = desiredX.coerceIn(2f * density, max(2f * density, width - w - 2f * density))
        val y = desiredY.coerceIn(2f * density, max(2f * density, height - h - 2f * density))
        val rect = RectF(x, y, x + w, y + h)
        fillPaint.color = ColorUtils.setAlphaComponent(surface, 0xf4)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, fillPaint)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, gridPaint)
        rows.forEachIndexed { index, row -> label(canvas, row, x + pad, y + pad + line * (index + .8f), w - pad * 2f) }
    }
}

internal data class PresetCrossfeedPlotData(val x: DoubleArray, val values: List<DoubleArray>, val labels: List<String>,
    val unit: String, val logarithmic: Boolean, val minimum: Double, val maximum: Double, val title: String,
    val impulse: Boolean = false, val minima: List<DoubleArray>? = null, val maxima: List<DoubleArray>? = null)

/** One running calculation plus one replaceable request; slider motion never restarts the running calculation. */
internal abstract class PresetCrossfeedSeriesChart(context: Context) : PresetCrossfeedDashboardChart(context) {
    protected abstract fun cached(model: PresetCrossfeedDashboardModel): PresetCrossfeedPlotData?
    protected abstract fun calculate(model: PresetCrossfeedDashboardModel, cancel: () -> Unit): PresetCrossfeedPlotData
    protected abstract fun sameView(a: PresetCrossfeedDashboardModel, b: PresetCrossfeedDashboardModel): Boolean
    private data class Frame(val model: PresetCrossfeedDashboardModel, val plot: PresetCrossfeedPlotData)
    private var frame: Frame? = null
    private var pending: PresetCrossfeedDashboardModel? = null
    private var scope: CoroutineScope? = null
    private var worker: Job? = null
    private var calculationError = false
    private val plotBounds = RectF()
    private var held: Frame? = null
    private var cursorX: Double? = null
    private val gesture = PresetCrossfeedPagerGesture(this, { x, y ->
        val current = frame
        if (current == null || !plotBounds.contains(x, y)) false else { held = current; true }
    }, { x ->
        val plot = held?.plot
        if (plot != null) { cursorX = valueAt(x, plot); invalidate() }
    }, { held = null; cursorX = null; invalidate() })

    override fun onModelChanged() {
        gesture.clear(); calculationError = false
        val model = configuration ?: return
        val fromCache = cached(model)
        if (fromCache != null) frame = Frame(model, fromCache)
        else if (frame?.model?.let { it.presetSlot != model.presetSlot || !sameView(it, model) } != false) frame = null
        pending = if (fromCache == null) model else null
        startWorker()
    }
    override fun onActivationChanged() {
        if (!canCompute) {
            gesture.clear(); worker?.cancel(); worker = null; scope?.cancel(); scope = null
            pending = configuration
        } else {
            val model = configuration ?: return
            cached(model)?.let { frame = Frame(model, it); pending = null; invalidate(); return }
            pending = model; startWorker()
        }
    }
    override fun onPaused() { gesture.clear() }
    private fun startWorker() {
        if (!canCompute || worker?.isActive == true || pending == null) return
        val owner = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        worker = owner.launch {
            while (canCompute) {
                val requested = pending ?: break
                pending = null
                val result = try {
                    withContext(Dispatchers.Default) {
                        val context = currentCoroutineContext()
                        calculate(requested) { context.ensureActive() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                val current = configuration
                if (canCompute && current != null && current.presetSlot == requested.presetSlot && sameView(current, requested) &&
                    (current === requested || frame?.model !== current)) {
                    if (result != null) { frame = Frame(requested, result); calculationError = false }
                    else calculationError = true
                    invalidate(); onStatusChanged?.invoke()
                }
            }
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); gesture.clear()
        plotBounds.set(38f * density, 40f * density, w - 9f * density, h - 25f * density)
    }
    private fun x(value: Double, plot: PresetCrossfeedPlotData): Float {
        val fraction = if (plot.logarithmic) ln(value / plot.x.first()) / ln(plot.x.last() / plot.x.first())
            else (value - plot.x.first()) / (plot.x.last() - plot.x.first())
        return plotBounds.left + fraction.toFloat() * plotBounds.width()
    }
    private fun valueAt(position: Float, plot: PresetCrossfeedPlotData): Double {
        val fraction = ((position - plotBounds.left) / plotBounds.width()).coerceIn(0f, 1f).toDouble()
        return if (plot.logarithmic) plot.x.first() * (plot.x.last() / plot.x.first()).pow(fraction)
            else plot.x.first() + (plot.x.last() - plot.x.first()) * fraction
    }
    private fun y(value: Double, plot: PresetCrossfeedPlotData) = plotBounds.bottom -
        ((value - plot.minimum) / (plot.maximum - plot.minimum)).toFloat() * plotBounds.height()
    private fun nearest(plot: PresetCrossfeedPlotData, value: Double): Int {
        val found = plot.x.binarySearch(value)
        if (found >= 0) return found
        val next = (-found - 1).coerceIn(0, plot.x.lastIndex)
        return if (next > 0 && abs(plot.x[next - 1] - value) < abs(plot.x[next] - value)) next - 1 else next
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (plotBounds.width() <= 0f || plotBounds.height() <= 0f) return
        val target = configuration ?: return
        val displayed = held ?: frame
        val plot = displayed?.plot
        if (plot == null) {
            for (tick in 0..4) {
                val y = plotBounds.top + plotBounds.height() * tick / 4f
                canvas.drawLine(plotBounds.left, y, plotBounds.right, y, gridPaint)
            }
            label(canvas, "预设 ${target.presetSlot + 1} · 参数模型", 3f * density, 12f * density, width - 6f * density)
            label(canvas, if (calculationError) "当前参数图暂不可用" else "正在生成当前参数图", plotBounds.centerX(),
                plotBounds.centerY(), plotBounds.width(), foregroundColor, Paint.Align.CENTER)
            return
        }
        val oldTarget = displayed.model !== target
        label(canvas, plot.title + if (oldTarget) " · 更新中（上一参数）" else " · 参数模型",
            3f * density, 12f * density, width - 6f * density)
        plot.labels.forEachIndexed { index, name ->
            label(canvas, name, width * (index + .5f) / plot.labels.size, 27f * density,
                width / plot.labels.size - 5f * density, colors[index % colors.size], Paint.Align.CENTER)
        }
        for (tick in 0..4) {
            val value = plot.minimum + (plot.maximum - plot.minimum) * tick / 4.0
            canvas.drawLine(plotBounds.left, y(value, plot), plotBounds.right, y(value, plot), gridPaint)
            label(canvas, fmt(value, if (abs(value) >= 10) 0 else 2), plotBounds.left - 4f * density,
                y(value, plot) + 3f * density, 32f * density, foregroundColor, Paint.Align.RIGHT)
        }
        for (tick in 0..4) {
            val value = valueAt(plotBounds.left + plotBounds.width() * tick / 4f, plot)
            val xx = x(value, plot)
            canvas.drawLine(xx, plotBounds.top, xx, plotBounds.bottom, gridPaint)
            label(canvas, if (plot.logarithmic && value >= 1000.0) "${fmt(value / 1000.0, 1)}k" else
                fmt(value, if (plot.logarithmic) 0 else 1), xx, plotBounds.bottom + 12f * density,
                plotBounds.width() / 4f, foregroundColor, Paint.Align.CENTER)
        }
        label(canvas, plot.unit, plotBounds.left, 38f * density, plotBounds.width())
        canvas.save(); canvas.clipRect(plotBounds)
        plot.values.forEachIndexed { series, values ->
            linePaint.color = if (target.effectEnabled) colors[series % colors.size] else ColorUtils.setAlphaComponent(colors[series % colors.size], 0x88)
            linePaint.pathEffect = if (target.effectEnabled && !oldTarget) null else dash
            if (plot.impulse && plot.minima != null && plot.maxima != null) {
                val lo = plot.minima[series]; val hi = plot.maxima[series]
                for (index in lo.indices) {
                    if (!lo[index].isFinite() || !hi[index].isFinite()) continue
                    val xx = plotBounds.left + (index + .5f) / lo.size * plotBounds.width()
                    canvas.drawLine(xx, y(lo[index], plot), xx, y(hi[index], plot), linePaint)
                }
            } else {
                path.rewind(); var connected = false
                values.forEachIndexed { index, value ->
                    if (!value.isFinite()) { connected = false; return@forEachIndexed }
                    val xx = x(plot.x[index], plot); val yy = y(value, plot)
                    if (connected) path.lineTo(xx, yy) else path.moveTo(xx, yy)
                    connected = true
                }
                canvas.drawPath(path, linePaint)
            }
            linePaint.pathEffect = null
        }
        cursorX?.let { value ->
            val index = nearest(plot, value); val xx = x(plot.x[index], plot)
            canvas.drawLine(xx, plotBounds.top, xx, plotBounds.bottom, gridPaint)
            plot.values.forEachIndexed { series, values ->
                values[index].takeIf { it.isFinite() }?.let { dot(canvas, xx, y(it, plot), colors[series % colors.size]) }
            }
        }
        canvas.restore()
        label(canvas, if (plot.logarithmic) "频率 / Hz · 长按读取模型网格" else "时间 / ms · 单位脉冲原始采样值",
            plotBounds.centerX(), height - 2f * density, plotBounds.width(), foregroundColor, Paint.Align.CENTER)
        cursorX?.let { value ->
            val index = nearest(plot, value)
            val title = if (plot.impulse) "${fmt(plot.x[index], 4)} ms · 样本 $index" else "${fmt(plot.x[index], 2)} Hz"
            val rows = listOf(title) + plot.values.mapIndexed { series, values ->
                "${plot.labels[series]} ${fmt(values[index], if (plot.impulse) 6 else 2)} ${plot.unit}"
            }
            tooltip(canvas, rows, if (x(value, plot) > plotBounds.centerX()) plotBounds.left else plotBounds.centerX(), plotBounds.top + 3f * density)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); gesture.clear() }
}

internal class PresetCrossfeedFrequencyView(context: Context) : PresetCrossfeedSeriesChart(context) {
    private fun convert(model: PresetCrossfeedDashboardModel, curve: me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalResponse.Curve): PresetCrossfeedPlotData {
        val values = curve.decibels.take(6)
        var maximum = 6.0
        values.forEach { row -> row.forEach { if (it.isFinite()) maximum = max(maximum, ceil(it / 6.0) * 6.0) } }
        return PresetCrossfeedPlotData(curve.frequencies, values, curve.labels.take(6), "dB（下界 −60）", true,
            -60.0, maximum, "${me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedParameters.baseNames[model.presetSlot]} · 附加低通 ${model.stages * 6} dB/oct · 补偿 ${fmt(model.compensation)} dB")
    }
    override fun cached(model: PresetCrossfeedDashboardModel) = model.cachedFrequency()?.let { convert(model, it) }
    override fun calculate(model: PresetCrossfeedDashboardModel, cancel: () -> Unit) = convert(model, model.frequency(cancel))
    override fun sameView(a: PresetCrossfeedDashboardModel, b: PresetCrossfeedDashboardModel) = true
}

/** Path thickness and ripple position express saved gain and delay; no physical propagation is claimed. */
internal class PresetCrossfeedSpaceView(context: Context) : PresetCrossfeedDashboardChart(context) {
    private val oval = RectF()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val model = configuration ?: return
        label(canvas, "${me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedParameters.baseNames[model.presetSlot]} · 头部与交叉路径示意", 3f * density, 12f * density, width - 6f * density)
        val cx = width * .5f; val cy = height * .48f
        val radius = min(width * .24f, (height - 58f * density) * .44f).coerceAtLeast(0f)
        if (radius <= 0f) return
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        linePaint.color = ColorUtils.setAlphaComponent(foregroundColor, 0x80); linePaint.strokeWidth = 1.5f * density
        canvas.drawOval(oval, linePaint)
        // Nose and loudspeakers establish a top-down listening-room orientation.
        path.rewind(); path.moveTo(cx - radius * .12f, cy - radius)
        path.lineTo(cx, cy - radius * 1.14f); path.lineTo(cx + radius * .12f, cy - radius)
        canvas.drawPath(path, linePaint)
        for (side in 0..1) {
            val x = cx + (if (side == 0) -1f else 1f) * (radius * 1.8f + 7f * density)
            fillPaint.color = if (model.effectEnabled && model.professional) colors[side * 2] else ColorUtils.setAlphaComponent(foregroundColor, 0x70)
            canvas.drawRoundRect(RectF(x - 6f * density, cy - 10f * density, x + 6f * density, cy + 10f * density),
                3f * density, 3f * density, fillPaint)
            label(canvas, if (side == 0) "L" else "R", x, cy + 24f * density, 25f * density, colors[side * 2], Paint.Align.CENTER)
        }
        for (direction in 0..1) {
            val attenuation = if (direction == 0) model.lrAttenuationDb else model.rlAttenuationDb
            val delay = if (direction == 0) model.lrDelayUs else model.rlDelayUs
            val factor = (10.0.pow(-attenuation / 20.0) * model.mix).coerceIn(0.0, 2.0)
            val sign = if (direction == 0) 1f else -1f
            val start = cx - sign * radius; val end = cx + sign * radius
            val yy = cy + sign * radius * .22f
            linePaint.strokeWidth = (1.0 + factor * 4.0).toFloat() * density
            linePaint.color = ColorUtils.setAlphaComponent(colors[direction * 2], (45 + factor * 210).roundToInt().coerceIn(45, 255))
            path.rewind(); path.moveTo(start, yy); path.quadTo(cx, yy - sign * radius * .65f, end, yy)
            canvas.drawPath(path, linePaint)
            canvas.drawLine(end, yy, end - sign * 5f * density, yy - 4f * density, linePaint)
            canvas.drawLine(end, yy, end - sign * 5f * density, yy + 4f * density, linePaint)
            val fraction = (delay / 500.0).toFloat()
            val waveX = start + (end - start) * (.15f + .70f * fraction)
            val waveY = yy - sign * radius * .28f
            linePaint.strokeWidth = density
            for (ring in 1..3) {
                val r = (2f + ring * 2.5f) * density
                oval.set(waveX - r, waveY - r, waveX + r, waveY + r)
                canvas.drawArc(oval, if (direction == 0) 270f else 90f, 180f, false, linePaint)
            }
        }
        linePaint.strokeWidth = 1.5f * density
        label(canvas, "中央补偿 ${fmt(model.compensation)} dB", cx, cy + 4f * density,
            radius * 1.4f, foregroundColor, Paint.Align.CENTER)
        label(canvas, "L→R 参数衰减 ${fmt(model.lrAttenuationDb)} dB / 附加 ${fmt(model.lrDelayUs, 0)} μs", 3f * density,
            height - 29f * density, width - 6f * density, colors[0])
        label(canvas, "R→L 参数衰减 ${fmt(model.rlAttenuationDb)} dB / 附加 ${fmt(model.rlDelayUs, 0)} μs", 3f * density,
            height - 16f * density, width - 6f * density, colors[2])
        label(canvas, "线宽为交叉衰减示意，波纹为延时参数示意", 3f * density, height - 3f * density,
            width - 6f * density, foregroundColor)
    }
}

/** Original FIR kernels / BS2B complex response, with conjugate-symmetric IFFT impulse preview. */
internal class PresetCrossfeedDiagnosticView(context: Context) : PresetCrossfeedDashboardChart(context) {
    private data class Frame(val model: PresetCrossfeedDashboardModel,
        val frequency: me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalResponse.Curve,
        val impulse: me.timschneeberger.rootlessjamesdsp.fusion.SurroundDiagnostics.Result,
        val minima: List<DoubleArray>, val maxima: List<DoubleArray>, val phases: List<DoubleArray>)
    private var frame: Frame? = null
    private var pending: PresetCrossfeedDashboardModel? = null
    private var scope: CoroutineScope? = null
    private var worker: Job? = null
    private val cache = LinkedHashMap<me.timschneeberger.rootlessjamesdsp.fusion.SurroundDiagnostics.Result, Frame>(3, .75f, true)
    private val frequencyPlot = RectF()
    private val impulsePlot = RectF()
    private var held: Frame? = null
    private var cursorIndex: Int? = null
    private var inspectingImpulse = false
    private val gesture = PresetCrossfeedPagerGesture(this, { x, y ->
        val current = frame
        if (current == null || (!frequencyPlot.contains(x, y) && !impulsePlot.contains(x, y))) false
        else { held = current; inspectingImpulse = impulsePlot.contains(x, y); true }
    }, { x ->
        val heldFrame = held
        if (heldFrame != null) {
            val plot = if (inspectingImpulse) impulsePlot else frequencyPlot
            val values = if (inspectingImpulse) heldFrame.impulse.x else heldFrame.frequency.frequencies
            val ratio = ((x - plot.left) / plot.width()).coerceIn(0f, 1f)
            val target = if (inspectingImpulse) values.last() * ratio else values.first() * (values.last() / values.first()).pow(ratio.toDouble())
            val found = values.binarySearch(target)
            val next = if (found >= 0) found else (-found - 1).coerceIn(0, values.lastIndex)
            cursorIndex = if (next > 0 && abs(values[next - 1] - target) < abs(values[next] - target)) next - 1 else next
            invalidate()
        }
    }, { held = null; cursorIndex = null; invalidate() })
    private fun cached(model: PresetCrossfeedDashboardModel): Frame? = model.cachedDiagnostic()?.let { result ->
        synchronized(cache) { cache[result]?.copy(model = model) }
    }
    override fun onModelChanged() {
        gesture.clear()
        val model = configuration ?: return
        val previous = cached(model)
        if (previous != null) frame = previous
        pending = if (previous == null) model else null
        start()
    }
    override fun onActivationChanged() {
        if (!canCompute) {
            gesture.clear(); worker?.cancel(); worker = null; scope?.cancel(); scope = null; pending = configuration
        } else {
            val model = configuration ?: return
            cached(model)?.let { frame = it; pending = null; invalidate(); return }
            pending = model; start()
        }
    }
    override fun onPaused() { gesture.clear() }
    private fun start() {
        if (!canCompute || worker?.isActive == true || pending == null) return
        val owner = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        worker = owner.launch {
            while (canCompute) {
                val requested = pending ?: break
                pending = null
                val result = try {
                    withContext(Dispatchers.Default) {
                        val context = currentCoroutineContext()
                        val impulse = requested.diagnostic { context.ensureActive() }
                        val frequency = requested.frequency { context.ensureActive() }
                        val buckets = min(768, impulse.x.size)
                        fun extrema(minimum: Boolean) = impulse.values.map { values -> DoubleArray(buckets) { bucket ->
                            val start = bucket * values.size / buckets; val end = max(start + 1, (bucket + 1) * values.size / buckets)
                            var result = if (minimum) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
                            for (index in start until end) result = if (minimum) min(result, values[index]) else max(result, values[index])
                            result
                        } }
                        val result = Frame(requested, frequency, impulse, extrema(true), extrema(false), requested.coherentPhase { context.ensureActive() })
                        context.ensureActive()
                        synchronized(cache) { cache[impulse] = result; while (cache.size > 3) cache.remove(cache.keys.first()) }
                        result
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (canCompute && result != null && (configuration === requested || frame?.model !== configuration)) {
                    frame = result; invalidate(); onStatusChanged?.invoke()
                }
            }
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); gesture.clear()
        frequencyPlot.set(36f * density, 28f * density, w - 30f * density, h * .48f)
        impulsePlot.set(36f * density, h * .48f + 24f * density, w - 8f * density, h - 19f * density)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frequencyPlot.height() <= 0 || impulsePlot.height() <= 0) return
        val snapshot = held ?: frame
        label(canvas, "同 / 反相复响与单位脉冲 · 参数模型", 3f * density, 12f * density, width - 6f * density)
        val stale = snapshot != null && snapshot.model !== configuration
        label(canvas, if (stale) "更新中：当前为上一参数图" else "实线同相 / 虚线反相 dB · 橙色相位 °（右轴）",
            frequencyPlot.left, 25f * density, frequencyPlot.width())
        for ((panel, plot) in listOf(frequencyPlot, impulsePlot).withIndex()) {
            val impulseLimit = max(.01, snapshot?.maxima?.maxOfOrNull { row -> row.maxOf { abs(it) } } ?: 1.25)
            val frequencyHigh = max(6.0, snapshot?.frequency?.decibels?.drop(4)?.maxOfOrNull { row -> row.maxOrNull() ?: 6.0 } ?: 6.0)
            val lo = if (panel == 0) -60.0 else -impulseLimit; val hi = if (panel == 0) frequencyHigh else impulseLimit
            fun y(value: Double) = plot.bottom - ((value - lo) / (hi - lo)).toFloat() * plot.height()
            for (tick in 0..2) {
                val value = lo + (hi - lo) * tick / 2.0
                canvas.drawLine(plot.left, y(value), plot.right, y(value), gridPaint)
                label(canvas, fmt(value, if (panel == 0) 0 else 2), plot.left - 4f * density,
                    y(value) + 3f * density, 30f * density, foregroundColor, Paint.Align.RIGHT)
            }
            if (snapshot == null) continue
            canvas.save(); canvas.clipRect(plot)
            if (panel == 0) {
                val frequency = snapshot.frequency
                for (series in 0..3) {
                    path.rewind(); var started = false
                    val values = frequency.decibels[series + 4]
                    values.forEachIndexed { index, value ->
                        if (!value.isFinite()) { started = false; return@forEachIndexed }
                        val x = plot.left + index.toFloat() / values.lastIndex * plot.width()
                        if (started) path.lineTo(x, y(value)) else path.moveTo(x, y(value))
                        started = true
                    }
                    linePaint.color = colors[series]; linePaint.pathEffect = if (series % 2 == 1 || stale) dash else null
                    canvas.drawPath(path, linePaint); linePaint.pathEffect = null
                }
                for (channel in 0..1) {
                    path.rewind(); var previous: Double? = null
                    snapshot.phases[channel].forEachIndexed { index, value ->
                        if (!value.isFinite()) { previous = null; return@forEachIndexed }
                        val x = plot.left + index.toFloat() / snapshot.phases[channel].lastIndex * plot.width()
                        val yy = plot.bottom - ((value + 180) / 360).toFloat() * plot.height()
                        if (previous == null || abs(value - previous!!) > 180) path.moveTo(x, yy) else path.lineTo(x, yy)
                        previous = value
                    }
                    linePaint.color = ColorUtils.setAlphaComponent(colors[3], if (channel == 0) 220 else 130)
                    linePaint.pathEffect = if (channel == 1) dash else null
                    canvas.drawPath(path, linePaint); linePaint.pathEffect = null
                }
            } else {
                snapshot.minima.forEachIndexed { series, lows ->
                    val highs = snapshot.maxima[series]
                    linePaint.color = colors[series]
                    for (index in lows.indices) {
                        val x = plot.left + (index + .5f) / lows.size * plot.width()
                        canvas.drawLine(x, y(lows[index]), x, y(highs[index]), linePaint)
                    }
                }
            }
            if ((panel == 1) == inspectingImpulse) cursorIndex?.let { index ->
                val size = if (panel == 1) snapshot.impulse.x.size else snapshot.frequency.frequencies.size
                val x = plot.left + index.toFloat() / (size - 1) * plot.width()
                canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
                for (series in 0..3) {
                    val value = if (panel == 1) snapshot.impulse.values[series][index] else snapshot.frequency.decibels[series + 4][index]
                    if (value.isFinite()) dot(canvas, x, y(value), colors[series])
                }
            }
            canvas.restore()
            if (panel == 0) for (tick in 0..2) {
                label(canvas, (180 - tick * 180).toString(), plot.right + 3f * density,
                    plot.top + plot.height() * tick / 2f + 3f * density, 25f * density, colors[3])
            }
        }
        label(canvas, "20 Hz", frequencyPlot.left, frequencyPlot.bottom + 11f * density, 45f * density)
        label(canvas, "${fmt(min(20000.0, (snapshot?.model?.rate ?: 48000) * .45) / 1000.0)} kHz", frequencyPlot.right,
            frequencyPlot.bottom + 11f * density, 60f * density, foregroundColor, Paint.Align.RIGHT)
        label(canvas, "单位脉冲 · IFFT 线性幅度 · ${if ((snapshot?.model?.rate ?: 48000) > 48000) "48k 带限窗口" else "有限窗口"}", impulsePlot.left, impulsePlot.top - 4f * density, impulsePlot.width())
        label(canvas, "0", impulsePlot.left, height - 3f * density, 25f * density)
        label(canvas, "${fmt(snapshot?.impulse?.x?.lastOrNull(), 1)} ms · 长按读取样本", impulsePlot.right, height - 3f * density,
            impulsePlot.width(), foregroundColor, Paint.Align.RIGHT)
        if (snapshot == null) label(canvas, "正在生成当前诊断图", frequencyPlot.centerX(), frequencyPlot.centerY(),
            frequencyPlot.width(), foregroundColor, Paint.Align.CENTER)
        if (snapshot != null) cursorIndex?.let { index ->
            val title = if (inspectingImpulse) "${fmt(snapshot.impulse.x[index], 4)} ms · 样本 $index" else
                "${fmt(snapshot.frequency.frequencies[index], 2)} Hz"
            val rows = listOf(title) + (0..3).map { series ->
                val name = if (inspectingImpulse) snapshot.impulse.labels[series] else snapshot.frequency.labels[series + 4]
                val value = if (inspectingImpulse) snapshot.impulse.values[series][index] else snapshot.frequency.decibels[series + 4][index]
                "$name ${fmt(value, if (inspectingImpulse) 6 else 2)} ${if (inspectingImpulse) "" else "dB"}"
            } + if (inspectingImpulse) emptyList() else (0..1).map { "同相${if (it == 0) "L" else "R"} 相位 ${fmt(snapshot.phases[it][index], 2)} °" }
            tooltip(canvas, rows, frequencyPlot.centerX(), if (inspectingImpulse) frequencyPlot.top else impulsePlot.top)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); gesture.clear() }
}

/** The protocol exports output-channel peaks only; no direct/cross branch energy is invented. */
internal class PresetCrossfeedEnergyView(context: Context) : PresetCrossfeedDashboardChart(context) {
    private data class Point(val time: Long, val left: Double?, val right: Double?, val mode: Int, val gap: Boolean)
    private val history = ArrayList<Point>(420)
    private val plot = RectF()
    private var now = 0L; private var identity: Any? = null; private var rate = 0; private var sequence: Int? = null
    private var lastMode: Int? = null; private var split = true
    private var held: List<Point>? = null; private var heldTime = 0L; private var selected: Point? = null
    private val gesture = PresetCrossfeedPagerGesture(this, { x, y ->
        if (!plot.contains(x, y) || history.isEmpty()) false else { held = history.toList(); heldTime = now; true }
    }, { x ->
        val target = heldTime - ((plot.right - x.coerceIn(plot.left, plot.right)) / plot.width() * 20000.0).toLong()
        selected = held?.minByOrNull { abs(it.time - target) }; invalidate()
    }, { held = null; selected = null; invalidate() })
    override fun onModelChanged() { gesture.clear(); split = true }
    override fun onPaused() { gesture.clear(); split = true }
    override fun onActivationChanged() { if (!canCompute) { gesture.clear(); split = true } }
    override fun onSampleChanged(sample: PresetCrossfeedDashboardRuntimeMonitor.Sample) {
        now = max(now, sample.elapsedRealtimeMs)
        while (history.isNotEmpty() && (history.first().time < now - 20000L || history.size >= 420)) history.removeAt(0)
        val values = data(sample) ?: run { split = true; return }
        val mode = sample.reading?.snapshot?.mode ?: return
        if (identity !== sample.streamIdentity || rate != values[2] || mode != lastMode) { split = true; sequence = null }
        identity = sample.streamIdentity; rate = values[2]; lastMode = mode
        val frames = sample.reading?.frames ?: return
        if (sequence == frames) return
        sequence = frames
        history += Point(sample.elapsedRealtimeMs, number(values, 20), number(values, 21), mode, split); split = false
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); gesture.clear()
        plot.set(39f * density, 45f * density, w - 9f * density, h - 29f * density)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (plot.width() <= 0f || plot.height() <= 0f) return
        val points = held ?: history; val end = if (held != null) heldTime else now
        var low = -90.0; var high = 6.0
        points.forEach { p -> listOfNotNull(p.left, p.right).forEach { low = min(low, floor(it / 12) * 12); high = max(high, ceil(it / 6) * 6) } }
        fun x(time: Long) = plot.right - ((end - time) / 20000.0).toFloat() * plot.width()
        fun y(value: Double) = plot.bottom - ((value - low) / (high - low)).toFloat() * plot.height()
        label(canvas, "20 秒真实输出峰值 · dBFS", 3f * density, 12f * density, width - 6f * density)
        val current = data()
        for (channel in 0..1) {
            val value = number(current, 20 + channel)
            val left = plot.left + channel * plot.width() * .52f
            val available = plot.width() * .48f
            label(canvas, "${if (channel == 0) "L" else "R"} ${fmt(value, 1)} dBFS", left, 25f * density, available, colors[channel * 2])
            fillPaint.color = ColorUtils.setAlphaComponent(foregroundColor, 0x18)
            val bar = RectF(left, 29f * density, left + available, 36f * density)
            canvas.drawRoundRect(bar, 2f * density, 2f * density, fillPaint)
            value?.let {
                bar.right = left + available * ((it + 90) / 96).coerceIn(0.0, 1.0).toFloat()
                fillPaint.color = if (it > 0) warningColor else colors[channel * 2]
                canvas.drawRoundRect(bar, 2f * density, 2f * density, fillPaint)
            }
        }
        for (tick in 0..4) {
            val value = low + (high - low) * tick / 4.0
            canvas.drawLine(plot.left, y(value), plot.right, y(value), gridPaint)
            label(canvas, fmt(value, 0), plot.left - 4f * density, y(value) + 3f * density,
                32f * density, foregroundColor, Paint.Align.RIGHT)
            val x = plot.left + plot.width() * tick / 4f
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
        }
        canvas.save(); canvas.clipRect(plot)
        for (channel in 0..1) {
            path.rewind(); var started = false; var previous = 0L
            for (point in points) {
                val value = if (channel == 0) point.left else point.right
                if (value == null) { started = false; continue }
                if (started && !point.gap && point.time - previous <= 1500L) path.lineTo(x(point.time), y(value)) else path.moveTo(x(point.time), y(value))
                started = true; previous = point.time
            }
            linePaint.color = colors[channel * 2]; canvas.drawPath(path, linePaint)
        }
        selected?.let { point ->
            canvas.drawLine(x(point.time), plot.top, x(point.time), plot.bottom, gridPaint)
            point.left?.let { dot(canvas, x(point.time), y(it), colors[0]) }
            point.right?.let { dot(canvas, x(point.time), y(it), colors[2]) }
        }
        canvas.restore()
        label(canvas, "−20 s", plot.left, height - 15f * density, 40f * density)
        label(canvas, if (data() == null) "末次" else "当前", plot.right, height - 15f * density, 35f * density, foregroundColor, Paint.Align.RIGHT)
        label(canvas, "峰值随当前试听模式输出 · 分支 RMS 驱动未提供", width * .5f, height - 2f * density,
            width - 6f * density, foregroundColor, Paint.Align.CENTER)
        if (data() == null) label(canvas, if (configuration?.professional == false) "原预设仍处理 · 精调关闭时驱动不提供峰值" else pauseLabel,
            plot.centerX(), plot.centerY(), plot.width(), foregroundColor, Paint.Align.CENTER)
        selected?.let { point -> tooltip(canvas,
            listOf("样本 −${fmt((heldTime - point.time) / 1000.0, 2)} s · 试听模式 ${point.mode}",
                "左峰值 ${fmt(point.left, 2)} dBFS", "右峰值 ${fmt(point.right, 2)} dBFS"),
            if (x(point.time) > plot.centerX()) plot.left else plot.centerX(), plot.top + 3f * density) }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); gesture.clear() }
}

private class PresetCrossfeedPagerGesture(private val view: View, private val begin: (Float, Float) -> Boolean,
    private val move: (Float) -> Unit, private val end: () -> Unit) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f; private var downY = 0f; private var currentX = 0f
    private var pending = false; private var inspecting = false
    private val longPress = Runnable {
        if (pending && pointer != MotionEvent.INVALID_POINTER_ID && view.isAttachedToWindow && view.isShown) {
            pending = false
            if (begin(downX, downY)) { inspecting = true; view.parent?.requestDisallowInterceptTouchEvent(true); move(currentX) }
        }
    }
    fun clear() {
        view.removeCallbacks(longPress); pending = false; pointer = MotionEvent.INVALID_POINTER_ID
        if (inspecting) { inspecting = false; view.parent?.requestDisallowInterceptTouchEvent(false); end() }
    }
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clear(); pointer = event.getPointerId(0); downX = event.x; downY = event.y; currentX = downX
                pending = true; view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0 || event.pointerCount != 1) { clear(); return false }
                currentX = event.getX(index)
                if (inspecting) { move(currentX); return true }
                if (pending && (abs(currentX - downX) > slop || abs(event.getY(index) - downY) > slop)) { view.removeCallbacks(longPress); pending = false }
                return true
            }
            MotionEvent.ACTION_UP -> { val click = pending && !inspecting; clear(); if (click) view.performClick(); return true }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { clear(); return false }
        }
        return pending || inspecting
    }
}

