package me.timschneeberger.rootlessjamesdsp.peq

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.floor

/** Fixed, logarithmic-frequency magnitude preview using the same coefficients as the engine. */
class PeqResponseView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primary
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(primary, 26) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foreground, 35)
        strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foreground, 180)
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val graphBounds = RectF()
    private val line = Path()
    private val fill = Path()
    private var responseFrequencies = DoubleArray(BASE_POINTS) { 20.0 * 1000.0.pow(it.toDouble() / (BASE_POINTS - 1)) }
    private var responseValues = DoubleArray(BASE_POINTS)
    private var gainRange = 18.0
    private var requestedWidth = -1
    private var animator: ValueAnimator? = null
    private var worker: ExecutorService? = null
    private val generation = AtomicInteger()
    var requestedState: PeqState? = null
        private set
    data class AxisLabel(val text: String, val position: Float, val bounds: RectF)
    internal var renderedFrequencyLabels: List<AxisLabel> = emptyList()
        private set
    internal var renderedGainLabels: List<AxisLabel> = emptyList()
        private set

    init {
        isSaveEnabled = false
        contentDescription = context.getString(R.string.peq_response_title)
    }

    fun setState(state: PeqState, animate: Boolean = true) {
        if (state == requestedState && requestedWidth == width && worker?.isShutdown == false) return
        requestedState = state
        requestedWidth = width
        gainRange = state.gainRange
        invalidate()
        val revision = generation.incrementAndGet()
        val points = (requestedWidth / density * 4).roundToInt().coerceIn(BASE_POINTS, 4096)
        val executor = worker?.takeUnless { it.isShutdown } ?: Executors.newSingleThreadExecutor { task ->
            Thread(task, "peq-response-preview").apply { isDaemon = true }
        }.also { worker = it }
        executor.execute {
            if (revision != generation.get()) return@execute
            val next = PeqResponse.adaptiveCurve(state, points = points)
            post {
                if (revision == generation.get()) updateCurve(next, animate)
            }
        }
    }

    private fun updateCurve(points: List<PeqResponse.Point>, animate: Boolean) {
        animator?.cancel()
        val nextFrequencies = points.map { it.frequency }.toDoubleArray()
        val next = points.map { it.db }.toDoubleArray()
        if (!animate || !isAttachedToWindow) {
            responseFrequencies = nextFrequencies
            responseValues = next
            invalidate()
            return
        }
        // Adaptive sampling moves frequency points as Q/frequency changes. Resample the old
        // drawn curve on the new log-frequency grid before interpolating its gains.
        var leftIndex = 0
        val previous = DoubleArray(next.size) { index ->
            val frequency = nextFrequencies[index]
            while (leftIndex < responseFrequencies.lastIndex - 1 && responseFrequencies[leftIndex + 1] < frequency) leftIndex++
            val rightIndex = (leftIndex + 1).coerceAtMost(responseFrequencies.lastIndex)
            val low = responseFrequencies[leftIndex]
            val high = responseFrequencies[rightIndex]
            val fraction = if (low == high) 0.0 else (ln(frequency / low) / ln(high / low)).coerceIn(0.0, 1.0)
            responseValues[leftIndex] + (responseValues[rightIndex] - responseValues[leftIndex]) * fraction
        }
        responseFrequencies = nextFrequencies
        responseValues = previous
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            addUpdateListener {
                val fraction = it.animatedValue as Float
                responseValues = DoubleArray(next.size) { index -> previous[index] + (next[index] - previous[index]) * fraction }
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestedState?.takeIf { worker == null || worker!!.isShutdown }?.let { setState(it, false) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) requestedState?.let { setState(it, false) }
    }

    override fun onDetachedFromWindow() {
        generation.incrementAndGet()
        animator?.cancel()
        worker?.shutdownNow()
        worker = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val labelWidth = maxOf(textPaint.measureText("+${PeqBandAdapter.number(gainRange)}"),
            textPaint.measureText("−${PeqBandAdapter.number(gainRange)}"))
        graphBounds.set(labelWidth + 8f * density, textHeight / 2 + 5f * density,
            width - 15f * density, height - textHeight - 9f * density)
        if (graphBounds.width() <= 0 || graphBounds.height() <= 0) return
        val frequencies = listOf(20.0, 30.0, 40.0, 50.0, 60.0, 80.0, 100.0, 150.0, 200.0,
            300.0, 400.0, 500.0, 600.0, 800.0, 1000.0, 1500.0, 2000.0, 3000.0, 4000.0,
            5000.0, 6000.0, 8000.0, 10000.0, 12000.0, 16000.0, 20000.0)
        textPaint.textAlign = Paint.Align.CENTER
        frequencies.forEach { frequency ->
            val position = x(frequency)
            gridPaint.alpha = if (frequency in listOf(20.0, 100.0, 1000.0, 10000.0, 20000.0)) 60 else 25
            canvas.drawLine(position, graphBounds.top, position, graphBounds.bottom, gridPaint)
        }
        val xLabels = mutableListOf<AxisLabel>()
        val priorityFrequencies = (listOf(20.0, 20000.0, 1000.0, 100.0, 10000.0) + frequencies).distinct()
        val baseline = height - 4f * density - metrics.descent
        priorityFrequencies.forEach { frequency ->
            val text = if (frequency >= 1000) "${PeqBandAdapter.number(frequency / 1000)}k" else PeqBandAdapter.number(frequency)
            val position = x(frequency)
            val halfWidth = textPaint.measureText(text) / 2
            val bounds = RectF(position - halfWidth, baseline + metrics.ascent, position + halfWidth, baseline + metrics.descent)
            if (xLabels.none { bounds.left < it.bounds.right + 7f * density && bounds.right + 7f * density > it.bounds.left })
                xLabels.add(AxisLabel(text, position, bounds))
        }
        renderedFrequencyLabels = xLabels.sortedBy { it.position }
        renderedFrequencyLabels.forEach { canvas.drawText(it.text, it.position, baseline, textPaint) }
        textPaint.textAlign = Paint.Align.RIGHT
        val intervals = (graphBounds.height() / (textHeight + 5f * density)).coerceAtLeast(2f)
        val minimumStep = gainRange * 2 / intervals
        val gainStep = listOf(0.5, 1.0, 2.0, 2.5, 3.0, 5.0, 6.0, 10.0, 12.0, 20.0, 25.0, 50.0, 100.0)
            .firstOrNull { it >= minimumStep } ?: 100.0
        val gains = (-floor(gainRange / gainStep).toInt()..floor(gainRange / gainStep).toInt()).map { it * gainStep }
        (gains + listOf(-gainRange, gainRange)).distinct().forEach { gain ->
            gridPaint.alpha = if (gain == 0.0) 90 else 35
            canvas.drawLine(graphBounds.left, y(gain), graphBounds.right, y(gain), gridPaint)
        }
        val yLabels = mutableListOf<AxisLabel>()
        (listOf(gainRange, -gainRange, 0.0) + gains.sortedBy { abs(it) }).distinct().forEach { gain ->
            val text = (if (gain > 0.0) "+" else "") + PeqBandAdapter.number(gain)
            val position = y(gain)
            val base = position - (metrics.ascent + metrics.descent) / 2
            val bounds = RectF(graphBounds.left - 5f * density - textPaint.measureText(text),
                base + metrics.ascent, graphBounds.left - 5f * density, base + metrics.descent)
            if (yLabels.none { bounds.top < it.bounds.bottom + 3f * density && bounds.bottom + 3f * density > it.bounds.top })
                yLabels.add(AxisLabel(text, base, bounds))
        }
        renderedGainLabels = yLabels.sortedBy { it.position }
        renderedGainLabels.forEach { canvas.drawText(it.text, graphBounds.left - 5f * density, it.position, textPaint) }
        line.rewind()
        responseValues.forEachIndexed { index, db ->
            val position = x(responseFrequencies[index])
            if (index == 0) line.moveTo(position, y(db)) else line.lineTo(position, y(db))
        }
        fill.set(line)
        fill.lineTo(graphBounds.right, y(0.0))
        fill.lineTo(graphBounds.left, y(0.0))
        fill.close()
        canvas.save()
        canvas.clipRect(graphBounds)
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, curvePaint)
        canvas.restore()
    }

    private fun y(db: Double): Float = graphBounds.centerY() -
        (db.coerceIn(-gainRange * 2, gainRange * 2) / (2 * gainRange) * graphBounds.height()).toFloat()

    private fun x(frequency: Double): Float = graphBounds.left +
        (log10(frequency / PeqModel.MIN_FREQUENCY) / 3.0 * graphBounds.width()).toFloat()

    companion object { private const val BASE_POINTS = 1024 }
}
