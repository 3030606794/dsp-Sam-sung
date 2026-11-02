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
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundDashboardRuntimeMonitor
import java.util.Locale
import kotlin.math.*

/** The host explicitly selects one chart; invisible pages own no computation or sampling loop. */
internal abstract class SurroundDashboardChart(context: Context) : View(context) {
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
    protected var configuration: SurroundDashboardModel? = null
        private set
    protected var liveSample: SurroundDashboardRuntimeMonitor.Sample? = null
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
    fun setModel(model: SurroundDashboardModel) {
        if (configuration === model) return
        configuration = model
        if (!model.effectEnabled) liveSample = null
        onModelChanged(); if (canCompute) invalidate()
    }
    fun showSample(sample: SurroundDashboardRuntimeMonitor.Sample) {
        liveSample = sample; onSampleChanged(sample); if (canCompute) invalidate()
    }
    fun pause(label: String) { liveSample = null; pauseLabel = label; onPaused(); if (canCompute) invalidate() }
    protected open fun onModelChanged() = Unit
    protected open fun onActivationChanged() = Unit
    protected open fun onSampleChanged(sample: SurroundDashboardRuntimeMonitor.Sample) = Unit
    protected open fun onPaused() = Unit
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; onActivationChanged() }
    override fun onDetachedFromWindow() { attached = false; onActivationChanged(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); onActivationChanged() }
    protected fun data(sample: SurroundDashboardRuntimeMonitor.Sample? = liveSample): List<Int>? {
        val value = sample ?: return null
        val reading = value.reading ?: return null
        val state = reading.snapshot?.data ?: return null
        if (configuration?.effectEnabled == false || !value.output.connected || value.output.globalIssue != null ||
            value.output.processing != OutputRuntimeStatus.Processing.ACTIVE || !reading.enabled || !reading.active ||
            state[3] != 1 || state[5] != 1 || state[29] != 1) return null
        if ((7..10).all { state[it] == Int.MIN_VALUE }) return null
        return state
    }
    protected fun number(values: List<Int>?, index: Int, divisor: Double = 10.0) =
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

internal data class SurroundPlotData(val x: DoubleArray, val values: List<DoubleArray>, val labels: List<String>,
    val unit: String, val logarithmic: Boolean, val minimum: Double, val maximum: Double, val title: String,
    val impulse: Boolean = false, val minima: List<DoubleArray>? = null, val maxima: List<DoubleArray>? = null)

/** One running calculation plus one replaceable request; slider motion never restarts the running calculation. */
internal abstract class SurroundSeriesChart(context: Context) : SurroundDashboardChart(context) {
    protected abstract fun cached(model: SurroundDashboardModel): SurroundPlotData?
    protected abstract fun calculate(model: SurroundDashboardModel, cancel: () -> Unit): SurroundPlotData
    protected abstract fun sameView(a: SurroundDashboardModel, b: SurroundDashboardModel): Boolean
    private data class Frame(val model: SurroundDashboardModel, val plot: SurroundPlotData)
    private var frame: Frame? = null
    private var pending: SurroundDashboardModel? = null
    private var scope: CoroutineScope? = null
    private var worker: Job? = null
    private var calculationError = false
    private val plotBounds = RectF()
    private var held: Frame? = null
    private var cursorX: Double? = null
    private val gesture = SurroundPagerGesture(this, { x, y ->
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
    private fun x(value: Double, plot: SurroundPlotData): Float {
        val fraction = if (plot.logarithmic) ln(value / plot.x.first()) / ln(plot.x.last() / plot.x.first())
            else (value - plot.x.first()) / (plot.x.last() - plot.x.first())
        return plotBounds.left + fraction.toFloat() * plotBounds.width()
    }
    private fun valueAt(position: Float, plot: SurroundPlotData): Double {
        val fraction = ((position - plotBounds.left) / plotBounds.width()).coerceIn(0f, 1f).toDouble()
        return if (plot.logarithmic) plot.x.first() * (plot.x.last() / plot.x.first()).pow(fraction)
            else plot.x.first() + (plot.x.last() - plot.x.first()) * fraction
    }
    private fun y(value: Double, plot: SurroundPlotData) = plotBounds.bottom -
        ((value - plot.minimum) / (plot.maximum - plot.minimum)).toFloat() * plotBounds.height()
    private fun nearest(plot: SurroundPlotData, value: Double): Int {
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

internal class SurroundFrequencyView(context: Context) : SurroundSeriesChart(context) {
    private fun convert(model: SurroundDashboardModel, curve: me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalResponse.Curve): SurroundPlotData {
        val maximum = max(12.0, ceil((curve.decibels.flatMap { it.asIterable() }.filter { it.isFinite() }.maxOrNull() ?: 0.0) / 6.0) * 6.0)
        return SurroundPlotData(curve.frequencies, curve.decibels, curve.labels, "dB（下界 −60）", true, -60.0, maximum,
            listOf("处理频段 ${model.hpSlopeDb}/${model.lpSlopeDb} dB/oct", "直达与交叉响应", "同相与反相响应")[model.responseView])
    }
    override fun cached(model: SurroundDashboardModel) = model.cachedFrequency()?.let { convert(model, it) }
    override fun calculate(model: SurroundDashboardModel, cancel: () -> Unit) = convert(model, model.frequency(cancel))
    override fun sameView(a: SurroundDashboardModel, b: SurroundDashboardModel) = a.responseView == b.responseView
}

internal class SurroundDiagnosticView(context: Context) : SurroundSeriesChart(context) {
    private fun convert(model: SurroundDashboardModel, result: me.timschneeberger.rootlessjamesdsp.fusion.SurroundDiagnostics.Result): SurroundPlotData {
        var low = 0.0; var high = 0.0
        result.values.forEach { values -> values.forEach { if (it.isFinite()) { low = min(low, it); high = max(high, it) } } }
        val impulse = !result.logarithmic
        if (impulse) {
            val amplitude = max(1.25, 2.0.pow(ceil(log2(max(abs(low), abs(high)).coerceAtLeast(1e-12)))))
            low = -amplitude; high = amplitude
        } else {
            if (high - low < 1e-9) { low -= 1.0; high += 1.0 }
            val pad = (high - low) * .05; low -= pad; high += pad
        }
        val buckets = if (impulse) min(1024, result.x.size) else 0
        val minima = if (impulse) result.values.map { values -> DoubleArray(buckets) { bucket ->
            val start = bucket * values.size / buckets; val end = max(start + 1, (bucket + 1) * values.size / buckets)
            var v = Double.POSITIVE_INFINITY
            for (i in start until end) if (values[i].isFinite()) v = min(v, values[i])
            if (v.isFinite()) v else Double.NaN
        } } else null
        val maxima = if (impulse) result.values.map { values -> DoubleArray(buckets) { bucket ->
            val start = bucket * values.size / buckets; val end = max(start + 1, (bucket + 1) * values.size / buckets)
            var v = Double.NEGATIVE_INFINITY
            for (i in start until end) if (values[i].isFinite()) v = max(v, values[i])
            if (v.isFinite()) v else Double.NaN
        } } else null
        return SurroundPlotData(result.x, result.values, result.labels, result.unit, result.logarithmic, low, high,
            listOf("展开相位", "群延迟", "单位脉冲 · 截至 ${model.impulseWindowMs} ms")[model.diagnosticView], impulse, minima, maxima)
    }
    private val plotCache = LinkedHashMap<me.timschneeberger.rootlessjamesdsp.fusion.SurroundDiagnostics.Result, SurroundPlotData>(3, .75f, true)
    override fun cached(model: SurroundDashboardModel): SurroundPlotData? = model.cachedDiagnostic()?.let { result ->
        synchronized(plotCache) { plotCache[result] }
    }
    override fun calculate(model: SurroundDashboardModel, cancel: () -> Unit): SurroundPlotData {
        val result = model.diagnostic(cancel)
        val plot = synchronized(plotCache) { plotCache[result] } ?: convert(model, result)
        cancel()
        synchronized(plotCache) {
            plotCache[result] = plot
            while (plotCache.size > 3) plotCache.remove(plotCache.keys.first())
        }
        return plot
    }
    override fun sameView(a: SurroundDashboardModel, b: SurroundDashboardModel) =
        a.diagnosticView == b.diagnosticView && a.impulseWindowMs == b.impulseWindowMs
}

/** Geometry uses real dimensions; the static ripple marker is an explicitly labelled delay diagram. */
internal class SurroundSpeakerView(context: Context) : SurroundDashboardChart(context) {
    private val oval = RectF()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val model = configuration ?: return
        label(canvas, "扬声器 / 双耳俯视 · 静态几何", 3f * density, 12f * density, width - 6f * density)
        val top = 33f * density; val bottom = height - 30f * density
        if (bottom <= top) return
        val scale = min((width - 66f * density) / max(model.speakerSpacingMm, model.earSpacingMm * 2).toFloat(),
            (bottom - top) / model.listeningDistanceMm.toFloat())
        val cx = width * .5f
        val sy = top; val ey = sy + model.listeningDistanceMm.toFloat() * scale
        val speakers = floatArrayOf(cx - model.speakerSpacingMm.toFloat() * scale / 2f,
            cx + model.speakerSpacingMm.toFloat() * scale / 2f)
        val ears = floatArrayOf(cx - model.earSpacingMm.toFloat() * scale / 2f,
            cx + model.earSpacingMm.toFloat() * scale / 2f)
        linePaint.pathEffect = null
        for (index in 0..1) {
            linePaint.color = ColorUtils.setAlphaComponent(foregroundColor, 0x40)
            canvas.drawLine(speakers[index], sy, ears[index], ey, linePaint)
            val other = 1 - index
            linePaint.color = colors[index * 2]; linePaint.pathEffect = dash
            canvas.drawLine(speakers[index], sy, ears[other], ey, linePaint); linePaint.pathEffect = null
            val delay = if (index == 0) model.lrDelayUs else model.rlDelayUs
            val position = .24f + ((delay - 20.0) / 190.0).coerceIn(0.0, 1.0).toFloat() * .52f
            val waveX = speakers[index] + (ears[other] - speakers[index]) * position
            val waveY = sy + (ey - sy) * position
            for (ring in 1..3) {
                val r = (3f + ring * 3f) * density
                linePaint.color = ColorUtils.setAlphaComponent(colors[index * 2], 0xb0 - ring * 0x20)
                oval.set(waveX - r, waveY - r, waveX + r, waveY + r)
                canvas.drawArc(oval, 20f, 140f, false, linePaint)
            }
            fillPaint.color = if (model.effectEnabled) colors[index * 2] else ColorUtils.setAlphaComponent(foregroundColor, 0x88)
            canvas.drawRoundRect(RectF(speakers[index] - 7f * density, sy - 5f * density,
                speakers[index] + 7f * density, sy + 6f * density), 2f * density, 2f * density, fillPaint)
            dot(canvas, ears[index], ey, foregroundColor, 3f * density)
            label(canvas, if (index == 0) "L" else "R", speakers[index], sy - 8f * density,
                25f * density, colors[index * 2], Paint.Align.CENTER)
        }
        val headWidth = max(8f * density, (ears[1] - ears[0]) * .8f)
        oval.set(cx - headWidth / 2f, ey - headWidth * .55f, cx + headWidth / 2f, ey + headWidth * .55f)
        canvas.drawOval(oval, gridPaint)
        label(canvas, "${fmt(model.geometry.angleDegrees)}°", cx, sy + 16f * density, width * .3f, foregroundColor, Paint.Align.CENTER)
        label(canvas, "L→R ${fmt(model.lrDelayUs)} μs", 3f * density, height - 17f * density, width * .49f, colors[0])
        label(canvas, "R→L ${fmt(model.rlDelayUs)} μs", width - 3f * density, height - 17f * density,
            width * .49f, colors[2], Paint.Align.RIGHT)
        label(canvas, "波纹位置表示支路延迟 · 非房间传播仿真", width * .5f, height - 3f * density,
            width - 6f * density, foregroundColor, Paint.Align.CENTER)
    }
}

/** Overall stereo RMS/peaks are never relabelled as separate left/right measurements. */
internal class SurroundEnergyView(context: Context) : SurroundDashboardChart(context) {
    private data class Point(val time: Long, val values: List<Double?>, val gap: Boolean)
    private val history = ArrayList<Point>(420)
    private val levels = RectF()
    private val gains = RectF()
    private var now = 0L
    private var identity: Any? = null
    private var rate = 0
    private var revision: Int? = null
    private var sequence: Int? = null
    private var split = true
    private var held: List<Point>? = null
    private var heldTime = 0L
    private var selected: Point? = null
    private val gesture = SurroundPagerGesture(this, { x, y ->
        if (history.isEmpty() || (!levels.contains(x, y) && !gains.contains(x, y))) false
        else { held = history.toList(); heldTime = now; true }
    }, { x ->
        val target = heldTime - ((levels.right - x.coerceIn(levels.left, levels.right)) / levels.width() * 20000.0).toLong()
        selected = held?.minByOrNull { abs(it.time - target) }; invalidate()
    }, { held = null; selected = null; invalidate() })
    override fun onModelChanged() { gesture.clear(); split = true }
    override fun onPaused() { gesture.clear(); split = true }
    override fun onActivationChanged() { if (!canCompute) { gesture.clear(); split = true } }
    override fun onSampleChanged(sample: SurroundDashboardRuntimeMonitor.Sample) {
        now = max(now, sample.elapsedRealtimeMs)
        while (history.isNotEmpty() && (history.first().time < now - 20000L || history.size >= 420)) history.removeAt(0)
        val values = data(sample) ?: run { split = true; return }
        if (identity !== sample.streamIdentity || rate != values[2] || revision != values[30]) { split = true; sequence = null }
        identity = sample.streamIdentity; rate = values[2]; revision = values[30]
        if (sequence == values[1]) return
        sequence = values[1]
        val readings = listOf(number(values, 7), number(values, 8), number(values, 9), number(values, 10),
            number(values, 13), number(values, 14))
        history += Point(sample.elapsedRealtimeMs, readings, split); split = false
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); gesture.clear()
        levels.set(38f * density, 31f * density, w - 9f * density, h * .61f)
        gains.set(38f * density, h * .61f + 17f * density, w - 9f * density, h - 20f * density)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (levels.width() <= 0f || gains.height() <= 0f) return
        val points = held ?: history
        val end = if (held != null) heldTime else now
        val currentData = data()
        val currentPeakGain = number(currentData, 14)
        label(canvas, "20 秒真实整体电平 · 非独立 L/R", 3f * density, 12f * density, width - 6f * density)
        val names = listOf("输入 RMS", "输出 RMS", "输入峰值", "输出峰值")
        for (index in 0..3) label(canvas, names[index], width * (index + .5f) / 4f, 26f * density,
            width / 4f - 3f * density, colors[index], Paint.Align.CENTER)
        var minimumLevel = -90.0; var maximumLevel = 6.0; var minimumGain = -12.0
        points.forEach { point -> point.values.take(4).filterNotNull().forEach {
            minimumLevel = min(minimumLevel, floor(it / 12.0) * 12.0)
            maximumLevel = max(maximumLevel, ceil(it / 6.0) * 6.0)
        }; point.values.getOrNull(5)?.let { minimumGain = min(minimumGain, floor(it / 6.0) * 6.0) } }
        fun xx(time: Long, plot: RectF) = plot.right - ((end - time) / 20000.0).toFloat() * plot.width()
        fun yy(value: Double, plot: RectF, low: Double, high: Double) = plot.bottom - ((value - low) / (high - low)).toFloat() * plot.height()
        for ((panel, plot) in listOf(levels, gains).withIndex()) {
            val low = if (panel == 0) minimumLevel else minimumGain
            val high = if (panel == 0) maximumLevel else 6.0
            for (tick in 0..2) {
                val value = low + (high - low) * tick / 2.0
                val y = yy(value, plot, low, high)
                canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
                label(canvas, fmt(value, 0), plot.left - 4f * density, y + 3f * density, 31f * density, foregroundColor, Paint.Align.RIGHT)
            }
            for (tick in 0..4) {
                val x = plot.left + plot.width() * tick / 4f
                canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            }
            canvas.save(); canvas.clipRect(plot)
            val indices = if (panel == 0) 0..3 else 4..5
            for (series in indices) {
                path.rewind(); var connected = false; var previous = 0L
                for (point in points) {
                    val value = point.values.getOrNull(series)
                    if (value == null) { connected = false; continue }
                    val x = xx(point.time, plot); val y = yy(value, plot, low, high)
                    if (connected && !point.gap && point.time - previous <= 1500L) path.lineTo(x, y) else path.moveTo(x, y)
                    connected = true; previous = point.time
                }
                linePaint.color = if (series == 5) warningColor else colors[series % colors.size]
                linePaint.pathEffect = if (series in 2..3) dash else null
                canvas.drawPath(path, linePaint); linePaint.pathEffect = null
            }
            selected?.let { point ->
                val x = xx(point.time, plot); canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
                for (series in indices) point.values.getOrNull(series)?.let { value ->
                    dot(canvas, x, yy(value, plot, low, high), if (series == 5) warningColor else colors[series % colors.size])
                }
            }
            canvas.restore()
        }
        label(canvas, "RMS 补偿 / 峰值保护实际增益 · dB", gains.left, gains.top - 5f * density, gains.width())
        label(canvas, "dBFS", levels.left + 3f * density, levels.top + 10f * density, 45f * density)
        if (configuration?.peakEnabled == true && currentPeakGain != null && currentPeakGain < 0.0) {
            linePaint.color = warningColor
            canvas.drawLine(levels.left, levels.top, levels.right, levels.top, linePaint)
        }
        if (points.isEmpty()) label(canvas, if (configuration?.metersEnabled == false) "请在原控件开启「启用本模块监测」" else pauseLabel,
            levels.centerX(), levels.centerY(), levels.width(), foregroundColor, Paint.Align.CENTER)
        label(canvas, "−20 s", levels.left, height - 3f * density, 40f * density)
        label(canvas, "红线：实际峰值保护 · 非硬件削波", width * .55f, height - 3f * density,
            width * .50f, foregroundColor, Paint.Align.CENTER)
        label(canvas, if (currentData == null) "末次" else "当前", levels.right, height - 3f * density,
            32f * density, foregroundColor, Paint.Align.RIGHT)
        selected?.let { point ->
            val rows = listOf("样本 −${fmt((heldTime - point.time) / 1000.0, 2)} s",
                "RMS 入/出 ${fmt(point.values[0])} / ${fmt(point.values[1])} dBFS",
                "峰值 入/出 ${fmt(point.values[2])} / ${fmt(point.values[3])} dBFS",
                "补偿 ${fmt(point.values[4])} dB · 峰值增益 ${fmt(point.values[5])} dB")
            tooltip(canvas, rows, if (xx(point.time, levels) > levels.centerX()) levels.left else levels.centerX(), levels.top + 3f * density)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); gesture.clear() }
}

/** Ordinary horizontal dragging changes page. Inspection starts only after a stationary long press. */
private class SurroundPagerGesture(private val view: View, private val begin: (Float, Float) -> Boolean,
    private val move: (Float) -> Unit, private val end: () -> Unit) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f; private var downY = 0f; private var currentX = 0f
    private var pending = false; private var inspecting = false
    private val longPress = Runnable {
        if (pending && pointer != MotionEvent.INVALID_POINTER_ID && view.isAttachedToWindow && view.isShown) {
            pending = false
            if (begin(downX, downY)) {
                inspecting = true; view.parent?.requestDisallowInterceptTouchEvent(true); move(currentX)
            }
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
                if (pending && (abs(currentX - downX) > slop || abs(event.getY(index) - downY) > slop)) {
                    view.removeCallbacks(longPress); pending = false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val click = pending && !inspecting; clear(); if (click) view.performClick(); return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { clear(); return false }
        }
        return pending || inspecting
    }
}
