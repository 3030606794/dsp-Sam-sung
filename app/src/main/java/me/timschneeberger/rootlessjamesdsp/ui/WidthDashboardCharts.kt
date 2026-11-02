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
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.WidthDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.WidthDashboardRuntimeMonitor
import java.util.Locale
import kotlin.math.*

/** No timer or capture owner lives in a chart. Its host supplies configuration and real samples. */
internal abstract class WidthDashboardChart(context: Context) : View(context) {
    protected val density = resources.displayMetrics.density
    protected val foregroundColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    protected val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    protected val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    protected val colors = intArrayOf(accent,
        if (dark) Color.rgb(108, 210, 241) else Color.rgb(15, 126, 164),
        if (dark) Color.rgb(255, 193, 110) else Color.rgb(177, 100, 12))
    protected val warningColor = if (dark) Color.rgb(255, 131, 131) else Color.rgb(184, 46, 46)
    protected val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foregroundColor
        textSize = min(10f * resources.displayMetrics.scaledDensity, 12f * density)
    }
    protected val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0x30)
        strokeWidth = density; style = Paint.Style.STROKE
    }
    protected val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; strokeWidth = 1.6f * density; style = Paint.Style.STROKE
    }
    protected val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val path = Path()
    protected val dash = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    protected var configuration: WidthDashboardModel? = null
        private set
    protected var liveSample: WidthDashboardRuntimeMonitor.Sample? = null
        private set
    protected var pauseLabel = "监视已关闭"
        private set

    fun setModel(model: WidthDashboardModel) {
        if (configuration === model) return
        configuration = model
        if (!model.effectEnabled) liveSample = null
        onModelChanged()
        redraw()
    }
    fun showSample(sample: WidthDashboardRuntimeMonitor.Sample) {
        liveSample = sample
        pauseLabel = "等待有效处理数据"
        onSampleChanged(sample)
        redraw()
    }
    fun pause(label: String) {
        liveSample = null; pauseLabel = label
        onPaused()
        redraw()
    }
    protected open fun onModelChanged() = Unit
    protected open fun onSampleChanged(sample: WidthDashboardRuntimeMonitor.Sample) = Unit
    protected open fun onPaused() = Unit
    protected fun redraw() { if (visibility == VISIBLE && isShown) invalidate() }

    protected fun data(sample: WidthDashboardRuntimeMonitor.Sample? = liveSample): List<Int>? {
        val item = sample ?: return null
        val reading = item.reading ?: return null
        val snapshot = reading.snapshot ?: return null
        if (configuration?.effectEnabled == false || !item.output.connected || item.output.globalIssue != null ||
            item.output.processing != OutputRuntimeStatus.Processing.ACTIVE || !reading.enabled ||
            !reading.active || !snapshot.enabled || !snapshot.ready) return null
        // A ready control snapshot can still contain no valid audio-level evidence.
        if (listOf(6, 11, 14).all { snapshot.data[it] == Int.MIN_VALUE }) return null
        return snapshot.data
    }
    protected fun number(values: List<Int>?, index: Int, divisor: Double = 1.0): Double? =
        values?.getOrNull(index)?.takeUnless { it == Int.MIN_VALUE }?.toDouble()?.div(divisor)?.takeIf { it.isFinite() }
    protected fun fmt(value: Double?, digits: Int = 1): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: "—"
    protected fun hz(value: Double): String = if (value >= 1000.0) "${fmt(value / 1000.0, 1)}k" else fmt(value, 0)
    protected fun label(canvas: Canvas, value: String, x: Float, y: Float, available: Float,
        color: Int = foregroundColor, align: Paint.Align = Paint.Align.LEFT) {
        if (available <= 0f) return
        val oldSize = textPaint.textSize
        textPaint.textAlign = align; textPaint.color = color
        val measured = textPaint.measureText(value)
        if (measured > available) textPaint.textSize *= available / measured
        canvas.drawText(value, x, y, textPaint)
        textPaint.textSize = oldSize
    }
    protected fun dot(canvas: Canvas, x: Float, y: Float, color: Int, radius: Float = 3f * density) {
        fillPaint.color = color; canvas.drawCircle(x, y, radius, fillPaint)
    }
    protected fun tooltip(canvas: Canvas, lines: List<String>, desiredX: Float, desiredY: Float) {
        if (lines.isEmpty() || width <= 0 || height <= 0) return
        val padding = 6f * density
        val lineHeight = textPaint.textSize * 1.3f
        val boxWidth = min(width - 4f * density, (lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f) + padding * 2f)
        val boxHeight = lines.size * lineHeight + padding * 2f
        val x = desiredX.coerceIn(2f * density, max(2f * density, width - boxWidth - 2f * density))
        val y = desiredY.coerceIn(2f * density, max(2f * density, height - boxHeight - 2f * density))
        val box = RectF(x, y, x + boxWidth, y + boxHeight)
        fillPaint.color = ColorUtils.setAlphaComponent(surface, 0xf4)
        canvas.drawRoundRect(box, 4f * density, 4f * density, fillPaint)
        canvas.drawRoundRect(box, 4f * density, 4f * density, gridPaint)
        lines.forEachIndexed { index, text ->
            label(canvas, text, x + padding, y + padding + lineHeight * (index + .8f), boxWidth - padding * 2f)
        }
    }
}

/** Radius is log frequency. Fan angle is a labelled width-ratio illustration, not a measured direction. */
internal class WidthRadarView(context: Context) : WidthDashboardChart(context) {
    private val arc = RectF()
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w * .5f; cy = h - 29f * density
        radius = min(w * .40f, max(0f, cy - 33f * density))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val model = configuration ?: return
        if (radius <= 0f) return
        label(canvas, "三频宽度雷达 · 参数比例示意", 3f * density, 12f * density, width * .72f)
        label(canvas, if (model.effectEnabled) "模型" else "音效已关闭", width - 3f * density,
            12f * density, width * .26f, if (model.effectEnabled) accent else foregroundColor, Paint.Align.RIGHT)
        val maximumHz = min(20000.0, model.rate * .49).coerceAtLeast(21.0)
        val boundaries = doubleArrayOf(20.0, model.crossLowHz.coerceIn(20.0, maximumHz),
            model.crossHighHz.coerceIn(20.0, maximumHz), maximumHz)
        fun radiusFor(f: Double) = radius * (ln(f.coerceIn(20.0, maximumHz) / 20.0) / ln(maximumHz / 20.0)).toFloat()
        fun point(f: Double, ratio: Double, sign: Double): Pair<Float, Float> {
            val theta = (90.0 + sign * atan(ratio.coerceAtLeast(0.0)) * 180.0 / PI) * PI / 180.0
            val r = radiusFor(f)
            return Pair(cx - r * cos(theta).toFloat(), cy - r * sin(theta).toFloat())
        }
        for (band in 0..2) {
            val low = boundaries[band]; val high = boundaries[band + 1]
            if (high <= low) continue
            path.rewind()
            var usable = true
            for (side in 0..1) {
                for (position in 0..40) {
                    val fraction = if (side == 0) position / 40.0 else 1.0 - position / 40.0
                    val frequency = exp(ln(low) + ln(high / low) * fraction)
                    val ratio = model.widthAt(frequency)
                    if (!ratio.isFinite()) { usable = false; break }
                    val p = point(frequency, ratio, if (side == 0) -1.0 else 1.0)
                    if (side == 0 && position == 0) path.moveTo(p.first, p.second) else path.lineTo(p.first, p.second)
                }
            }
            if (usable) {
                path.close()
                val color = if (model.effectEnabled) colors[band] else foregroundColor
                fillPaint.color = ColorUtils.setAlphaComponent(color, 0x35); canvas.drawPath(path, fillPaint)
                linePaint.color = ColorUtils.setAlphaComponent(color, 0xc0)
                linePaint.pathEffect = if (model.effectEnabled) null else dash
                canvas.drawPath(path, linePaint); linePaint.pathEffect = null
            }
        }
        for (frequency in doubleArrayOf(100.0, 1000.0, maximumHz)) {
            val r = radiusFor(frequency)
            arc.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(arc, 180f, 180f, false, gridPaint)
            label(canvas, "${hz(frequency)} Hz", cx - r - 2f * density, cy - 3f * density,
                max(25f * density, cx - r - 3f * density), foregroundColor, Paint.Align.RIGHT)
        }
        for (angle in intArrayOf(0, 45, 90, 135, 180)) {
            val theta = angle * PI / 180.0
            canvas.drawLine(cx, cy, cx - radius * cos(theta).toFloat(), cy - radius * sin(theta).toFloat(), gridPaint)
        }
        if (model.monoEnabled && model.mode != 0) {
            val r = radiusFor(model.monoHz)
            arc.set(cx - r, cy - r, cx + r, cy + r)
            linePaint.color = colors[2]; linePaint.pathEffect = dash
            canvas.drawArc(arc, 180f, 180f, false, linePaint); linePaint.pathEffect = null
            label(canvas, "收拢 ${hz(model.monoHz)} Hz · ${model.monoOrder * 6} dB/oct", width - 3f * density,
                25f * density, width * .60f, colors[2], Paint.Align.RIGHT)
        }
        val values = data()
        if (model.mode == 2 && values != null) {
            for (band in 0..2) {
                val ratio = number(values, 16 + band, 1000.0) ?: continue
                val f = sqrt(boundaries[band] * boundaries[band + 1])
                for (side in listOf(-1.0, 1.0)) {
                    val p = point(f, ratio, side); dot(canvas, p.first, p.second, colors[band])
                }
            }
        }
        if (model.mode == 0) label(canvas, "原算法旧刻度 · 不换算为 M/S 宽度", cx, cy - radius * .45f,
            width * .82f, foregroundColor, Paint.Align.CENTER)
        label(canvas, "左", cx - radius, cy + 12f * density, 30f * density, foregroundColor, Paint.Align.CENTER)
        label(canvas, "Mid", cx, cy + 12f * density, 40f * density, foregroundColor, Paint.Align.CENTER)
        label(canvas, "右", cx + radius, cy + 12f * density, 30f * density, foregroundColor, Paint.Align.CENTER)
        for (band in 0..2) {
            val name = listOf("低频", "中频", "高频")[band]
            val text = if (model.mode == 0) "$name —" else
                "$name ${fmt(model.bandWidths.getOrNull(band)?.times(100.0), 0)}%${if (model.mode == 2) "" else " 参考"}"
            label(canvas, text, (band + .5f) * width / 3f, height - 3f * density, width / 3f - 5f * density,
                if (model.effectEnabled) colors[band] else foregroundColor, Paint.Align.CENTER)
        }
    }
}

/** The saved threshold/ratio/knee model includes the cap on reclaiming only the added width. */
internal class WidthCompressionView(context: Context) : WidthDashboardChart(context) {
    private val plot = RectF()
    private var cursor: Double? = null
    private val gesture = WidthPagerChartGesture(this, { x, y -> plot.contains(x, y) }, { x ->
        cursor = inputAt(x); invalidate()
    }, { cursor = null; invalidate() })
    private fun inputAt(x: Float) = -36.0 + (x.coerceIn(plot.left, plot.right) - plot.left) / plot.width() * 48.0
    private fun x(value: Double) = plot.left + ((value + 36.0) / 48.0).toFloat() * plot.width()
    private fun y(value: Double) = plot.bottom - ((value + 60.0) / 72.0).toFloat() * plot.height()
    override fun onModelChanged() { gesture.clear() }
    override fun onPaused() { gesture.clear() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); gesture.clear()
        plot.set(34f * density, 28f * density, w - 9f * density, h - 34f * density)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val model = configuration ?: return
        if (plot.width() <= 0f || plot.height() <= 0f) return
        label(canvas, "动态回收模型 · 纵轴为回收后 Side/Mid", 3f * density, 12f * density, width - 6f * density)
        label(canvas, if (model.incrementEqEnabled) "同相基准 · 增量均衡已启用，非实测传递" else "虚线为未启用参考 · 仅回收超过 100% 的扩宽",
            plot.right, 24f * density, plot.width(), ColorUtils.setAlphaComponent(foregroundColor, 0xa8), Paint.Align.RIGHT)
        for (value in -36..12 step 12) {
            canvas.drawLine(x(value.toDouble()), plot.top, x(value.toDouble()), plot.bottom, gridPaint)
            label(canvas, "$value", x(value.toDouble()), plot.bottom + 12f * density, 24f * density,
                foregroundColor, Paint.Align.CENTER)
        }
        for (value in -60..12 step 18) {
            canvas.drawLine(plot.left, y(value.toDouble()), plot.right, y(value.toDouble()), gridPaint)
            label(canvas, "$value", plot.left - 4f * density, y(value.toDouble()) + 3f * density,
                27f * density, foregroundColor, Paint.Align.RIGHT)
        }
        label(canvas, "dB", plot.left - 4f * density, plot.top - 5f * density, 28f * density, foregroundColor, Paint.Align.RIGHT)
        canvas.save(); canvas.clipRect(plot)
        canvas.drawLine(x(-36.0), y(-36.0), x(12.0), y(12.0), gridPaint)
        model.dynamics.take(3).forEachIndexed { index, band ->
            val color = if (model.effectEnabled && band.active) colors[index] else ColorUtils.setAlphaComponent(colors[index], 0x88)
            linePaint.color = color; linePaint.pathEffect = if (model.effectEnabled && band.active) null else dash
            path.rewind()
            var connected = false
            for (point in 0..192) {
                val input = -36.0 + point * .25
                val output = band.outputDb(input)
                if (!output.isFinite()) { connected = false; continue }
                if (connected) path.lineTo(x(input), y(output)) else path.moveTo(x(input), y(output))
                connected = true
            }
            canvas.drawPath(path, linePaint)
            linePaint.pathEffect = dash
            val kneeLeft = x(band.thresholdDb - band.kneeDb / 2.0)
            val kneeRight = x(band.thresholdDb + band.kneeDb / 2.0)
            fillPaint.color = ColorUtils.setAlphaComponent(colors[index], 0x0d)
            canvas.drawRect(kneeLeft, plot.top, kneeRight, plot.bottom, fillPaint)
            canvas.drawLine(x(band.thresholdDb), plot.top, x(band.thresholdDb), plot.bottom, linePaint)
            linePaint.pathEffect = null
            cursor?.let { input ->
                val output = band.outputDb(input)
                if (output.isFinite()) dot(canvas, x(input), y(output), colors[index])
            }
        }
        cursor?.let { canvas.drawLine(x(it), plot.top, x(it), plot.bottom, gridPaint) }
        canvas.restore()
        model.dynamics.take(3).forEachIndexed { index, band ->
            val text = "${listOf("低", "中", "高")[index]} ${fmt(band.ratio)}:1${if (band.active && model.effectEnabled) "" else " · 参考"}"
            label(canvas, text, (index + .5f) * width / 3f, height - 3f * density, width / 3f - 4f * density,
                colors[index], Paint.Align.CENTER)
        }
        label(canvas, "输入 Side/Mid · dB", plot.centerX(), plot.bottom + 24f * density, plot.width(), foregroundColor, Paint.Align.CENTER)
        cursor?.let { input ->
            val rows = model.dynamics.take(3).mapIndexed { index, band ->
                "${listOf("低", "中", "高")[index]} ${fmt(band.outputDb(input))} dB · 宽 ${fmt(band.recoveredWidth(input) * 100.0, 0)}%"
            }
            tooltip(canvas, listOf("模型输入 ${fmt(input)} dB") + rows,
                if (x(input) > plot.centerX()) plot.left + 3f * density else plot.centerX(), plot.top + 3f * density)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); gesture.clear() }
}

/** The needle is read from the driver. Threshold regions remain available with monitoring off. */
internal class WidthCorrelationView(context: Context) : WidthDashboardChart(context) {
    private val arc = RectF()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val model = configuration ?: return
        val cx = width * .5f
        val cy = height - 39f * density
        val radius = min(width * .40f, max(0f, cy - 29f * density))
        if (radius <= 0f) return
        label(canvas, "相位相关度 · 真实输出", 3f * density, 12f * density, width * .70f)
        val values = data()
        val rho = number(values, 6, 10000.0)?.takeIf { it in -1.0..1.0 }
        val withdrawal = number(values, 19, 1000.0)?.takeIf { it in 0.0..1.0 }
        val advanced = liveSample?.reading?.advanced?.data?.takeIf {
            values != null && it.getOrNull(1) == values[1] && it.getOrNull(5) == 1
        }
        val actualGuard = advanced?.let { state -> (27..30).mapNotNull { number(state, it, 1000.0) }.maxOrNull() }
        val candidates = advanced?.let { state -> (35..38).map { number(state, it, 10000.0)?.takeIf { value -> value in -1.0..1.0 } } }
        val guardActive = actualGuard?.let { it > 0.0 }
        val underThreshold = rho != null && model.guardEnabled && rho < model.guardThreshold
        label(canvas, when {
            guardActive == true -> "保护正在回收"
            underThreshold -> "输出低于参考线"
            !model.guardEnabled -> "保护未启用"
            else -> "保护阈值预览"
        }, width - 3f * density, 12f * density, width * .29f,
            if (guardActive == true || underThreshold) warningColor else foregroundColor, Paint.Align.RIGHT)
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val oldStroke = linePaint.strokeWidth
        linePaint.strokeWidth = 9f * density
        linePaint.color = ColorUtils.setAlphaComponent(foregroundColor, 0x22)
        canvas.drawArc(arc, 180f, 180f, false, linePaint)
        val threshold = model.guardThreshold.coerceIn(-1.0, 1.0)
        val restore = (threshold + model.guardHysteresis).coerceIn(-1.0, 1.0)
        linePaint.color = ColorUtils.setAlphaComponent(warningColor, if (model.guardEnabled) 0x80 else 0x22)
        canvas.drawArc(arc, 180f, ((threshold + 1.0) * 90.0).toFloat(), false, linePaint)
        linePaint.color = ColorUtils.setAlphaComponent(colors[2], if (model.guardEnabled) 0x90 else 0x25)
        canvas.drawArc(arc, (180.0 + (threshold + 1.0) * 90.0).toFloat(), ((restore - threshold) * 90.0).toFloat(), false, linePaint)
        linePaint.strokeWidth = oldStroke
        fun xy(value: Double, r: Float): Pair<Float, Float> {
            val angle = (180.0 + (value + 1.0) * 90.0) * PI / 180.0
            return Pair(cx + r * cos(angle).toFloat(), cy + r * sin(angle).toFloat())
        }
        for (value in doubleArrayOf(-1.0, -.5, 0.0, .5, 1.0)) {
            val a = xy(value, radius - 6f * density); val b = xy(value, radius + 5f * density)
            canvas.drawLine(a.first, a.second, b.first, b.second, gridPaint)
            val c = xy(value, radius + 15f * density)
            label(canvas, fmt(value, 1), c.first, c.second + 3f * density, 31f * density, foregroundColor, Paint.Align.CENTER)
        }
        for ((index, marker) in listOf(threshold, restore).withIndex()) {
            val a = xy(marker, radius - 11f * density); val b = xy(marker, radius + 7f * density)
            linePaint.color = if (index == 0) warningColor else colors[2]
            canvas.drawLine(a.first, a.second, b.first, b.second, linePaint)
        }
        candidates?.forEachIndexed { index, value ->
            if (value != null && ((model.mode == 2 && index < 3) || (model.mode == 1 && index == 3))) {
                val point = xy(value, radius - 5f * density)
                dot(canvas, point.first, point.second, colors[index.coerceAtMost(2)], 2.5f * density)
            }
        }
        rho?.let {
            val end = xy(it, radius - 17f * density)
            linePaint.color = accent
            canvas.drawLine(cx, cy, end.first, end.second, linePaint)
            dot(canvas, cx, cy, accent, 4f * density)
        }
        label(canvas, fmt(rho, 3), cx, cy - 22f * density, width * .45f,
            if (underThreshold) warningColor else accent, Paint.Align.CENTER)
        label(canvas, if (rho == null) "暂无有效采样" else "输出相关度", cx, cy - 7f * density,
            width * .50f, foregroundColor, Paint.Align.CENTER)
        label(canvas, "参考阈值 ${fmt(threshold, 2)} / ${fmt(restore, 2)} · 总回收 ${fmt(withdrawal?.times(100.0), 1)}%",
            width * .5f, height - 17f * density, width - 6f * density, foregroundColor, Paint.Align.CENTER)
        label(canvas, if (candidates != null) "主针：输出 · 彩点：保护候选 · 回收只作用于新增宽度" else
            "主针为输出参考；保护状态只取实际回收读数", width * .5f, height - 3f * density,
            width - 6f * density, ColorUtils.setAlphaComponent(foregroundColor, 0xa0), Paint.Align.CENTER)
    }
}

/** Separate scales preserve the distinct units of effective width, Side/Mid and RMS compensation. */
internal class WidthHistoryView(context: Context) : WidthDashboardChart(context) {
    private data class Point(val time: Long, val widths: List<Double?>, val sideDb: Double?, val compensationDb: Double?, val gap: Boolean)
    private val history = ArrayList<Point>(420)
    private val plots = Array(3) { RectF() }
    private var now = 0L
    private var identity: Any? = null
    private var rate = 0
    private var sequence: Int? = null
    private var split = true
    private var held: List<Point>? = null
    private var heldEnd = 0L
    private var selected: Point? = null
    private var selectedPanel = 0
    private val gesture = WidthPagerChartGesture(this, { x, y ->
        val panel = plots.indexOfFirst { it.contains(x, y) }
        if (panel < 0 || history.isEmpty()) false else {
            held = history.toList(); heldEnd = now; selectedPanel = panel; true
        }
    }, { x ->
        val plot = plots[selectedPanel]
        val target = heldEnd - ((plot.right - x.coerceIn(plot.left, plot.right)) / plot.width() * 20000.0).toLong()
        selected = held?.minByOrNull { abs(it.time - target) }
        invalidate()
    }, { held = null; selected = null; invalidate() })

    override fun onModelChanged() { gesture.clear(); split = true }
    override fun onPaused() { gesture.clear(); split = true }
    override fun onSampleChanged(sample: WidthDashboardRuntimeMonitor.Sample) {
        if (sample.elapsedRealtimeMs > now) now = sample.elapsedRealtimeMs
        while (history.isNotEmpty() && (history.first().time < now - 20000L || history.size >= 420)) history.removeAt(0)
        val values = data(sample)
        if (values == null) { split = true; return }
        val nextRate = values[2]
        val changed = identity !== sample.streamIdentity || nextRate != rate
        if (changed) { split = true; sequence = null }
        identity = sample.streamIdentity; rate = nextRate
        // The even publication epoch changes only when the native snapshot is published.
        if (sequence == values[1]) return
        sequence = values[1]
        val widths = if (values[4] == 2) (16..18).map { number(values, it, 10.0) }
            else listOf(number(values, 15, 10.0), null, null)
        val side = number(values, 11, 10.0)
        val compensation = number(values, 20, 10.0)
        if (widths.all { it == null } && side == null && compensation == null) { split = true; return }
        history += Point(sample.elapsedRealtimeMs, widths, side, compensation, split)
        split = false
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); gesture.clear()
        val top = 19f * density
        val available = max(0f, h - top - 19f * density)
        val rowHeight = available / 3f
        for (index in 0..2) plots[index].set(39f * density, top + index * rowHeight + 3f * density,
            w - 9f * density, top + (index + 1) * rowHeight - 6f * density)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (plots[0].width() <= 0f || plots[0].height() <= 0f) return
        val points = held ?: history
        val end = if (held != null) heldEnd else now
        label(canvas, "最近 20 秒 · 三组独立坐标轴", 3f * density, 12f * density, width * .78f)
        label(canvas, if (data() == null) "待采样" else "真实", width - 3f * density, 12f * density,
            width * .20f, if (data() == null) foregroundColor else accent, Paint.Align.RIGHT)
        val sideValues = points.mapNotNull { it.sideDb }
        val sideMin = min(-60.0, floor((sideValues.minOrNull() ?: -60.0) / 12.0) * 12.0)
        val sideMax = max(24.0, ceil((sideValues.maxOrNull() ?: 24.0) / 12.0) * 12.0)
        val ranges = listOf(0.0 to 400.0, sideMin to sideMax, -6.0 to 6.0)
        fun xx(time: Long, plot: RectF) = plot.right - ((end - time) / 20000.0).toFloat() * plot.width()
        fun yy(value: Double, plot: RectF, range: Pair<Double, Double>) =
            plot.bottom - ((value - range.first) / (range.second - range.first)).toFloat() * plot.height()
        for (index in 0..2) {
            val plot = plots[index]; val range = ranges[index]
            val title = listOf("宽度 %", "S/M dB", "补偿 dB")[index]
            label(canvas, title, plot.left + 2f * density, plot.top + 10f * density, plot.width() * .35f)
            for (tick in 0..2) {
                val value = range.first + (range.second - range.first) * tick / 2.0
                val y = yy(value, plot, range)
                canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
                label(canvas, fmt(value, 0), plot.left - 4f * density, y + 3f * density,
                    30f * density, foregroundColor, Paint.Align.RIGHT)
            }
            for (tick in 0..4) {
                val x = plot.left + plot.width() * tick / 4f
                canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            }
            canvas.save(); canvas.clipRect(plot)
            for (series in 0 until (if (index == 0) 3 else 1)) {
                path.rewind(); var connected = false; var lastTime = 0L
                for (point in points) {
                    val value = when (index) { 0 -> point.widths.getOrNull(series); 1 -> point.sideDb; else -> point.compensationDb }
                    if (value == null || !value.isFinite()) { connected = false; continue }
                    val x = xx(point.time, plot); val y = yy(value, plot, range)
                    if (connected && !point.gap && point.time - lastTime <= 1500L) path.lineTo(x, y) else path.moveTo(x, y)
                    connected = true; lastTime = point.time
                }
                linePaint.color = if (index == 0) colors[series] else colors[index]
                canvas.drawPath(path, linePaint)
                points.lastOrNull()?.let { point ->
                    val value = when (index) { 0 -> point.widths.getOrNull(series); 1 -> point.sideDb; else -> point.compensationDb }
                    if (value != null) dot(canvas, xx(point.time, plot), yy(value, plot, range), linePaint.color, 2f * density)
                }
            }
            selected?.let { point ->
                val x = xx(point.time, plot); canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
                val value = when (index) { 0 -> point.widths.firstOrNull(); 1 -> point.sideDb; else -> point.compensationDb }
                if (value != null) dot(canvas, x, yy(value, plot, range), accent)
            }
            canvas.restore()
        }
        label(canvas, "−20 s", plots[2].left, height - 3f * density, 40f * density)
        label(canvas, "低 / 中 / 高 · 独立 dB 轴", width * .55f, height - 3f * density,
            width * .50f, foregroundColor, Paint.Align.CENTER)
        label(canvas, if (data() == null) "末次" else "当前", plots[2].right, height - 3f * density,
            35f * density, foregroundColor, Paint.Align.RIGHT)
        if (points.isEmpty()) label(canvas, "等待真实驱动样本", width * .5f, height * .52f,
            width * .8f, foregroundColor, Paint.Align.CENTER)
        selected?.let { point ->
            val rows = listOf("样本 −${fmt((heldEnd - point.time) / 1000.0, 2)} s",
                "宽度 ${point.widths.joinToString(" / ") { fmt(it, 0) }} %",
                "Side/Mid ${fmt(point.sideDb)} dB · 补偿 ${fmt(point.compensationDb)} dB")
            val x = xx(point.time, plots[selectedPanel])
            tooltip(canvas, rows, if (x > width * .5f) plots[0].left else width * .43f,
                if (selectedPanel == 0) height * .53f else 19f * density)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); gesture.clear() }
}

/** A pager keeps ordinary drags. Only a stationary long press enters chart inspection. */
private class WidthPagerChartGesture(
    private val view: View,
    private val begin: (Float, Float) -> Boolean,
    private val move: (Float) -> Unit,
    private val end: () -> Unit
) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var currentX = 0f
    private var pending = false
    private var inspecting = false
    private val longPress = Runnable {
        if (pending && pointer != MotionEvent.INVALID_POINTER_ID && view.isAttachedToWindow && view.isShown) {
            pending = false
            if (begin(downX, downY)) {
                inspecting = true
                view.parent?.requestDisallowInterceptTouchEvent(true)
                move(currentX)
            }
        }
    }

    fun clear() {
        view.removeCallbacks(longPress)
        pending = false
        pointer = MotionEvent.INVALID_POINTER_ID
        if (inspecting) {
            inspecting = false
            view.parent?.requestDisallowInterceptTouchEvent(false)
            end()
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clear()
                pointer = event.getPointerId(0)
                downX = event.x; downY = event.y; currentX = downX
                pending = true
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0 || event.pointerCount != 1) { clear(); return false }
                currentX = event.getX(index)
                if (inspecting) {
                    move(currentX)
                    return true
                }
                if (pending && (abs(currentX - downX) > slop || abs(event.getY(index) - downY) > slop)) {
                    view.removeCallbacks(longPress)
                    pending = false
                }
                // Never disallow interception here: horizontal pages and vertical scrolling keep their drag.
                return true
            }
            MotionEvent.ACTION_UP -> {
                val click = pending && !inspecting
                clear()
                if (click) view.performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                clear()
                return false
            }
        }
        return inspecting || pending
    }
}
