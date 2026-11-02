package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Parameter canvases invalidate only for data, layout or touch changes; no drawing timer. */
abstract class InspectablePlotView(context: Context) : View(context) {
    protected val d = resources.displayMetrics.density
    protected val fg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    protected val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    protected val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    protected val orange = if (ColorUtils.calculateLuminance(surface) > .5) Color.rgb(174, 80, 20) else Color.rgb(255, 179, 93)
    protected val teal = if (ColorUtils.calculateLuminance(surface) > .5) Color.rgb(0, 125, 125) else Color.rgb(89, 220, 205)
    protected val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; textSize = min(9f * resources.displayMetrics.scaledDensity, 11f * d) }
    protected val smallPaint = Paint(textPaint).apply { textSize *= .9f; alpha = 180 }
    protected val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(fg, 35); strokeWidth = d }
    protected val outline = Paint(gridPaint).apply { style = Paint.Style.STROKE; color = ColorUtils.setAlphaComponent(fg, 65) }
    protected val dash = DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fillPath = Path()
    protected val panes = ArrayList<RectF>()
    protected var heldPane = -1
        private set
    protected var heldUnit = 0f
        private set
    val inspecting get() = heldPane >= 0
    private var pendingPane = -1
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable { if (pendingPane >= 0 && pointer != MotionEvent.INVALID_POINTER_ID) begin(lastX) }

    protected abstract fun drawPlots(canvas: Canvas)
    protected abstract fun drawInspection(canvas: Canvas, pane: Int, unit: Float)
    protected open fun inspectionStarted() {}
    protected open fun inspectionFinished() {}
    final override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        panes.clear()
        drawPlots(canvas)
        if (heldPane in panes.indices) drawInspection(canvas, heldPane, heldUnit)
    }

    protected fun title(c: Canvas, value: String, x: Float, y: Float, color: Int = fg) {
        textPaint.color = color; textPaint.textAlign = Paint.Align.LEFT
        c.drawText(value, x, y, textPaint)
    }
    protected fun number(n: Double, decimals: Int = 1) = String.format(Locale.ROOT, "%.${decimals}f", n)
    protected fun hz(n: Double) = if (n >= 1000) "${number(n / 1000, if (n < 10000) 1 else 0)}k" else number(n, 0)
    protected fun logUnit(f: Double, low: Double, high: Double) = (ln(f.coerceIn(low, high) / low) / ln(high / low)).toFloat()
    protected fun logValue(unit: Float, low: Double, high: Double) = low * (high / low).pow(unit.coerceIn(0f, 1f).toDouble())
    protected fun timeValue(unit: Float, maximum: Double = 5000.0) = 2.0 * (kotlin.math.exp(unit.toDouble() * ln(1 + maximum / 2)) - 1)
    protected fun timeUnit(ms: Double, maximum: Double = 5000.0) = (ln(1 + ms.coerceAtLeast(0.0) / 2) / ln(1 + maximum / 2)).toFloat()
    protected fun y(rect: RectF, value: Double, low: Double, high: Double) = rect.bottom - ((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat() * rect.height()

    protected fun axes(c: Canvas, r: RectF, xTicks: List<Pair<Float, String>>, low: Double, high: Double, labels: Int = 2) {
        repeat(5) { i -> val yy = r.top + r.height() * i / 4; c.drawLine(r.left, yy, r.right, yy, gridPaint) }
        xTicks.forEach { (u, label) ->
            val xx = r.left + r.width() * u
            c.drawLine(xx, r.top, xx, r.bottom, gridPaint)
            smallPaint.textAlign = when { u < .01f -> Paint.Align.LEFT; u > .99f -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            c.drawText(label, xx, r.bottom + 11 * d, smallPaint)
        }
        smallPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..labels) {
            val value = high + (low - high) * i / labels
            c.drawText(number(value, if (abs(value) < 10 && value != 0.0) 1 else 0), r.left - 3 * d, r.top + r.height() * i / labels + 3 * d, smallPaint)
        }
        c.drawRect(r, outline)
    }

    protected fun curve(c: Canvas, r: RectF, color: Int, low: Double, high: Double,
        dashed: Boolean = false, shade: Boolean = false, baseline: Double = 0.0, fn: (Float) -> Double) {
        path.reset(); var started = false
        for (i in 0..192) {
            val u = i / 192f; val value = fn(u)
            if (!value.isFinite()) { started = false; continue }
            val xx = r.left + r.width() * u; val yy = y(r, value, low, high)
            if (started) path.lineTo(xx, yy) else { path.moveTo(xx, yy); started = true }
        }
        if (shade) {
            fillPath.set(path); fillPath.lineTo(r.right, y(r, baseline, low, high)); fillPath.lineTo(r.left, y(r, baseline, low, high)); fillPath.close()
            fill.color = ColorUtils.setAlphaComponent(color, 24); c.drawPath(fillPath, fill)
        }
        stroke.color = color; stroke.pathEffect = if (dashed) dash else null
        val save = c.save(); c.clipRect(r); c.drawPath(path, stroke); c.restoreToCount(save)
    }

    protected fun vertical(c: Canvas, r: RectF, unit: Float, color: Int, dashed: Boolean = true) {
        stroke.color = ColorUtils.setAlphaComponent(color, 160); stroke.pathEffect = if (dashed) dash else null; stroke.strokeWidth = d
        val xx = r.left + unit.coerceIn(0f, 1f) * r.width()
        c.drawLine(xx, r.top, xx, r.bottom, stroke); stroke.strokeWidth = 1.5f * d
    }
    protected fun band(c: Canvas, r: RectF, from: Float, to: Float, color: Int) {
        fill.color = ColorUtils.setAlphaComponent(color, 22)
        c.drawRect(r.left + from.coerceIn(0f, 1f) * r.width(), r.top, r.left + to.coerceIn(0f, 1f) * r.width(), r.bottom, fill)
        vertical(c, r, from, color); vertical(c, r, to, color)
    }
    protected fun dot(c: Canvas, r: RectF, unit: Float, value: Double, low: Double, high: Double, color: Int) {
        if (!value.isFinite()) return
        fill.color = color; c.drawCircle(r.left + unit * r.width(), y(r, value, low, high), 2.7f * d, fill)
    }
    protected fun tooltip(c: Canvas, r: RectF, lines: List<String>) {
        if (lines.isEmpty()) return
        textPaint.textAlign = Paint.Align.LEFT; textPaint.color = fg
        val w = min(width - 4 * d, lines.maxOf { textPaint.measureText(it) } + 12 * d)
        val h = lines.size * 12 * d + 8 * d
        val box = RectF((r.centerX() - w / 2).coerceIn(2 * d, max(2 * d, width - w - 2 * d)), (r.top + 5 * d).coerceAtMost(max(0f, height - h)), 0f, 0f)
        box.right = box.left + w; box.bottom = box.top + h
        fill.color = ColorUtils.blendARGB(surface, primary, .06f); c.drawRoundRect(box, 4 * d, 4 * d, fill); c.drawRoundRect(box, 4 * d, 4 * d, outline)
        lines.forEachIndexed { i, s -> c.drawText(s, box.left + 6 * d, box.top + (i + 1) * 12 * d, textPaint) }
    }

    protected fun cancelInspection() {
        removeCallbacks(longPress)
        val had = inspecting
        pendingPane = -1; heldPane = -1; pointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        if (had) { inspectionFinished(); invalidate() }
    }
    private fun begin(x: Float) {
        if (pendingPane !in panes.indices) return
        removeCallbacks(longPress); heldPane = pendingPane
        inspectionStarted(); move(x); parent?.requestDisallowInterceptTouchEvent(true)
    }
    private fun move(x: Float) {
        val r = panes.getOrNull(heldPane) ?: return
        heldUnit = ((x - r.left) / r.width()).coerceIn(0f, 1f); invalidate()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelInspection()
                pendingPane = panes.indexOfFirst { it.contains(e.x, e.y) }
                if (pendingPane < 0) return false
                pointer = e.getPointerId(0); startX = e.x; startY = e.y; lastX = e.x
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = e.findPointerIndex(pointer)
                if (index < 0) { cancelInspection(); return false }
                val x = e.getX(index); val y = e.getY(index); lastX = x
                if (inspecting) move(x)
                else if (abs(x - startX) > slop && abs(x - startX) > abs(y - startY)) begin(x)
                else if (abs(y - startY) > slop) { removeCallbacks(longPress); pendingPane = -1 }
                return true
            }
            MotionEvent.ACTION_UP -> { val click = !inspecting; cancelInspection(); if (click) performClick(); return true }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { cancelInspection(); return true }
        }
        return pointer != MotionEvent.INVALID_POINTER_ID
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { cancelInspection(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { cancelInspection(); super.onSizeChanged(w, h, oldw, oldh) }
}

/** Bounded driver telemetry history; repainting is driven by new samples, never a frame coroutine. */
class HighFrequencyTrendView(context: Context, private val spectrum: Boolean) : InspectablePlotView(context) {
    private data class Point(val ms: Long, val enhancement: Double?, val dynamic: Double?, val deess: Double?)
    private val points = ArrayList<Point>(48)
    private var held = emptyList<Point>()
    private var message = "监视已关闭"
    var onCursor: ((String?) -> Unit)? = null
    private val low get() = if (spectrum) -120.0 else -24.0
    private val high get() = if (spectrum) 12.0 else 24.0
    fun reset() { cancelInspection(); points.clear(); message = "等待音频处理数据"; invalidate() }
    fun pause(label: String) {
        if (message == label && points.isEmpty()) return
        cancelInspection(); points.clear(); message = label; invalidate()
    }
    fun add(ms: Long, enhancement: Double?, dynamic: Double?, deess: Double?) {
        if (points.lastOrNull()?.ms?.let { ms <= it } == true) return
        points.add(Point(ms, enhancement?.takeIf { it.isFinite() }, dynamic?.takeIf { it.isFinite() }, deess?.takeIf { it.isFinite() }))
        while (points.isNotEmpty() && (ms - points[0].ms > 20000 || points.size > 100)) points.removeAt(0)
        message = ""; if (!inspecting) invalidate()
    }
    override fun inspectionStarted() { held = points.toList() }
    override fun inspectionFinished() { held = emptyList(); onCursor?.invoke(null) }
    override fun drawPlots(canvas: Canvas) {
        if (spectrum) title(canvas, "— 纯激励支路 RMS · dBFS", 0f, 11 * d, primary)
        else {
            title(canvas, "— 增强 RMS 差", 0f, 11 * d, primary)
            title(canvas, "— 高频抑制", width * .4f, 11 * d, orange)
            title(canvas, "— 去齿音", width * .73f, 11 * d, teal)
        }
        val r = RectF(28 * d, 19 * d, width - 3 * d, height - 17 * d); panes.add(r)
        axes(canvas, r, listOf(0f to "−20 s", .25f to "−15", .5f to "−10", .75f to "−5", 1f to "现在"), low, high, 4)
        val samples = if (inspecting) held else points
        if (samples.isEmpty()) {
            smallPaint.textAlign = Paint.Align.CENTER; canvas.drawText(message, r.centerX(), r.centerY() + 3 * d, smallPaint); return
        }
        val now = samples.last().ms
        fun draw(which: (Point) -> Double?, color: Int) {
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 1.7f * d; style = Paint.Style.STROKE }
            val line = Path(); var previous: Point? = null; var drawing = false
            samples.forEach { p ->
                val value = which(p)
                if (value == null) { drawing = false; previous = p; return@forEach }
                val xx = r.right - (now - p.ms).toFloat() / 20000 * r.width(); val yy = y(r, value, low, high)
                if (!drawing || previous?.let { p.ms - it.ms > 1600 } == true) line.moveTo(xx, yy) else line.lineTo(xx, yy)
                drawing = true; previous = p
            }
            val save = canvas.save(); canvas.clipRect(r); canvas.drawPath(line, pen); canvas.restoreToCount(save)
        }
        draw({ it.enhancement }, primary)
        if (!spectrum) { draw({ it.dynamic }, orange); draw({ it.deess }, teal) }
    }
    override fun drawInspection(canvas: Canvas, pane: Int, unit: Float) {
        val r = panes[pane]; val samples = held.ifEmpty { points }; val now = samples.lastOrNull()?.ms ?: return
        val target = now - ((1 - unit) * 20000).toLong()
        val p = samples.minByOrNull { abs(it.ms - target) } ?: return
        val u = 1 - (now - p.ms).toFloat() / 20000
        vertical(canvas, r, u, primary)
        val lines = if (spectrum) listOf("${number((p.ms - now) / 1000.0, 2)} s · 泛音支路 ${p.enhancement?.let { number(it, 2) } ?: "—"} dBFS") else listOf(
            "${number((p.ms - now) / 1000.0, 2)} s · 增强 ${p.enhancement?.let { number(it, 2) } ?: "—"} dB",
            "高频 ${p.dynamic?.let { number(it, 2) } ?: "—"} dB · 齿音 ${p.deess?.let { number(it, 2) } ?: "—"} dB")
        tooltip(canvas, r, lines); onCursor?.invoke(lines.joinToString(" · "))
    }
}
