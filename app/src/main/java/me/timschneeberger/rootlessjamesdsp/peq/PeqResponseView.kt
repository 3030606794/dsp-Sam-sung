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
    private var responseValues = DoubleArray(POINTS)
    private var gainRange = 18.0
    private var animator: ValueAnimator? = null
    private var worker: ExecutorService? = null
    private val generation = AtomicInteger()
    var requestedState: PeqState? = null
        private set

    init {
        isSaveEnabled = false
        contentDescription = context.getString(R.string.peq_response_title)
    }

    fun setState(state: PeqState, animate: Boolean = true) {
        if (state == requestedState && worker?.isShutdown == false) return
        requestedState = state
        gainRange = state.gainRange
        invalidate()
        val revision = generation.incrementAndGet()
        val executor = worker?.takeUnless { it.isShutdown } ?: Executors.newSingleThreadExecutor { task ->
            Thread(task, "peq-response-preview").apply { isDaemon = true }
        }.also { worker = it }
        executor.execute {
            if (revision != generation.get()) return@execute
            val next = PeqResponse.curve(state, points = POINTS).map { it.db }.toDoubleArray()
            post {
                if (revision == generation.get()) updateCurve(next, animate)
            }
        }
    }

    private fun updateCurve(next: DoubleArray, animate: Boolean) {
        animator?.cancel()
        if (!animate || !isAttachedToWindow || responseValues.size != next.size) {
            responseValues = next
            invalidate()
            return
        }
        val previous = responseValues.copyOf()
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

    override fun onDetachedFromWindow() {
        generation.incrementAndGet()
        animator?.cancel()
        worker?.shutdownNow()
        worker = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        graphBounds.set(34f * density, 9f * density, width - 13f * density, height - 23f * density)
        if (graphBounds.width() <= 0 || graphBounds.height() <= 0) return
        val frequencies = listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10000.0 to "10k", 20000.0 to "20k")
        textPaint.textAlign = Paint.Align.CENTER
        frequencies.forEach { (frequency, label) ->
            val x = graphBounds.left + (log10(frequency / 20.0) / 3.0 * graphBounds.width()).toFloat()
            canvas.drawLine(x, graphBounds.top, x, graphBounds.bottom, gridPaint)
            canvas.drawText(label, x, height - 5f * density, textPaint)
        }
        textPaint.textAlign = Paint.Align.RIGHT
        for (fraction in listOf(1.0, 0.5, 0.0, -0.5, -1.0)) {
            val y = y(fraction * gainRange)
            canvas.drawLine(graphBounds.left, y, graphBounds.right, y, gridPaint)
            canvas.drawText(PeqBandAdapter.number(fraction * gainRange), graphBounds.left - 5f * density,
                y + textPaint.textSize * 0.35f, textPaint)
        }
        line.rewind()
        responseValues.forEachIndexed { index, db ->
            val x = graphBounds.left + index.toFloat() / (responseValues.size - 1) * graphBounds.width()
            if (index == 0) line.moveTo(x, y(db)) else line.lineTo(x, y(db))
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

    companion object { private const val POINTS = 256 }
}
