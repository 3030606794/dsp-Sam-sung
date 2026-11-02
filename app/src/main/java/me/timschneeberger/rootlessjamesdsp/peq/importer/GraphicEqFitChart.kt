package me.timschneeberger.rootlessjamesdsp.peq.importer

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/** Draws fitter samples directly; no secondary response calculation or decorative motion. */
class GraphicEqFitChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        fontFeatureSettings = "tnum"
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val graph = RectF()
    private var frequencies = doubleArrayOf()
    private var target = doubleArrayOf()
    private var fitted = doubleArrayOf()
    private val originalPath = Path()
    private val fittedPath = Path()
    private var pathsDirty = true

    fun setCurves(frequencies: DoubleArray, targetDb: DoubleArray, fittedDb: DoubleArray, description: String) {
        require(frequencies.size == targetDb.size && frequencies.size == fittedDb.size)
        this.frequencies = frequencies.copyOf()
        target = targetDb.copyOf()
        fitted = fittedDb.copyOf()
        contentDescription = description
        pathsDirty = true
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Axes follow the user's font scale; extra height keeps large labels out of the plot.
        val desiredHeight = (190 * density + 4 * label.textSize).roundToInt()
        setMeasuredDimension(resolveSize((280 * density).roundToInt(), widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pathsDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frequencies.size < 2 || width <= 0 || height <= 0) return
        val low = frequencies.first()
        val high = frequencies.last()
        if (low <= 0 || high <= low || !low.isFinite() || !high.isFinite()) return
        val minimum = minOf(0.0, target.minOrNull() ?: 0.0, fitted.minOrNull() ?: 0.0)
        val maximum = maxOf(0.0, target.maxOrNull() ?: 0.0, fitted.maxOrNull() ?: 0.0)
        val desiredStep = (maximum - minimum).coerceAtLeast(2.0) / 4.0
        val step = listOf(.5, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0, 50.0, 100.0).firstOrNull { it >= desiredStep } ?: ceil(desiredStep)
        val bottomDb = floor((minimum - step * .15) / step) * step
        val topDb = ceil((maximum + step * .15) / step) * step
        val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val muted = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val yLabels = generateSequence(bottomDb) { it + step }.takeWhile { it <= topDb + step * .01 }.toList()
        val left = yLabels.maxOf { label.measureText(db(it)) } + 10 * density
        graph.set(left, label.textSize + 8 * density, width - 8 * density, height - 2.8f * label.textSize - 4 * density)
        if (graph.width() < 24 * density || graph.height() <= 0) return
        fun x(value: Double): Float = graph.left + (ln(value / low) / ln(high / low) * graph.width()).toFloat()
        fun y(value: Double): Float = graph.bottom - ((value - bottomDb) / (topDb - bottomDb) * graph.height()).toFloat()
        label.color = ink
        label.textAlign = Paint.Align.LEFT
        canvas.drawText("dB", graph.left, label.textSize, label)
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText("Hz", graph.right, height - 2 * density, label)
        stroke.pathEffect = null
        stroke.strokeWidth = density
        for (value in yLabels) {
            stroke.color = ColorUtils.setAlphaComponent(ink, if (value == 0.0) 85 else 35)
            canvas.drawLine(graph.left, y(value), graph.right, y(value), stroke)
            label.color = muted
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(db(value), graph.left - 6 * density, y(value) - (label.ascent() + label.descent()) / 2, label)
        }
        val candidates = (listOf(low) + listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
            .filter { it > low * 1.01 && it < high / 1.01 } + high).distinct().sorted()
        var lastEnd = Float.NEGATIVE_INFINITY
        val finalLabelStart = graph.right - label.measureText(hz(high)) / 2
        for (frequency in candidates) {
            val text = hz(frequency)
            val center = x(frequency).coerceIn(label.measureText(text) / 2, width - label.measureText(text) / 2)
            val start = center - label.measureText(text) / 2
            val end = center + label.measureText(text) / 2
            if (start < lastEnd + 10 * density || (frequency != high && frequency != low && end > finalLabelStart - 10 * density)) continue
            stroke.color = ColorUtils.setAlphaComponent(ink, 35)
            canvas.drawLine(x(frequency), graph.top, x(frequency), graph.bottom, stroke)
            label.textAlign = Paint.Align.CENTER
            canvas.drawText(text, center, graph.bottom + 1.4f * label.textSize, label)
            lastEnd = end
        }
        if (pathsDirty) {
            fun makePath(path: Path, values: DoubleArray) {
                path.reset()
                var started = false
                for (i in frequencies.indices) {
                    if (!frequencies[i].isFinite() || frequencies[i] <= 0 || !values[i].isFinite()) { started = false; continue }
                    if (started) path.lineTo(x(frequencies[i]), y(values[i]))
                    else { path.moveTo(x(frequencies[i]), y(values[i])); started = true }
                }
            }
            makePath(originalPath, target)
            makePath(fittedPath, fitted)
            pathsDirty = false
        }
        canvas.save()
        canvas.clipRect(graph)
        stroke.color = muted
        stroke.strokeWidth = 2f * density
        stroke.pathEffect = DashPathEffect(floatArrayOf(5 * density, 4 * density), 0f)
        canvas.drawPath(originalPath, stroke)
        stroke.color = primary
        stroke.strokeWidth = 2.25f * density
        stroke.pathEffect = null
        canvas.drawPath(fittedPath, stroke)
        canvas.restore()
    }

    private fun db(value: Double) = if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.getDefault(), "%.1f", value)
    private fun hz(value: Double) = if (value >= 1000.0) "${db(value / 1000.0)}k" else db(value)
}
