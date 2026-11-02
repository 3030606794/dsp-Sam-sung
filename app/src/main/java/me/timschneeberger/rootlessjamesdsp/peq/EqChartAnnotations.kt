package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Read-only listening-region legend and control-frequency markers on an existing EQ curve. */
class EqChartAnnotations(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity
    private val accent = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary,
        Color.rgb(105, 80, 160))
    private val foregroundColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface,
        Color.DKGRAY)
    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface,
        Color.WHITE)
    private val regionEdges = doubleArrayOf(20.0, 60.0, 250.0, 500.0, 2000.0, 6000.0, 24000.0)
    private val regionNames = arrayOf("极低", "低频", "中低", "中频", "中高", "高频")
    private val listeningNames = arrayOf("下潜", "鼓点", "醇厚", "人声", "清脆", "空气感")
    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0x30)
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 4f * density), 0f)
    }
    private val legendBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(surface, 0x78)
    }
    private val activeBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(surface, 0xe8)
    }
    private val legendText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0xd4)
        textSize = min(8.5f * scaledDensity, 10f * density)
        textAlign = Paint.Align.CENTER
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = min(10f * scaledDensity, 12f * density)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val statusText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0x76)
        textSize = min(12f * scaledDensity, 14f * density)
        textAlign = Paint.Align.CENTER
    }
    private val labelBounds = RectF()
    private var frequencies = doubleArrayOf()
    private var gains = doubleArrayOf()
    private var activeIndex = -1
    private var effectEnabled = true
    private var activeFrequencyLabel = ""

    val contentDescription: String
        get() = "听感参考：20 至 60 Hz 极低频下潜；60 至 250 Hz 低频鼓点；" +
            "250 至 500 Hz 中低频醇厚；500 至 2000 Hz 中频人声；" +
            "2000 至 6000 Hz 中高频清脆；6000 至 24000 Hz 高频空气感。" +
            "节点表示控制频率处的总响应。" +
            if (effectEnabled) "" else "曲线均衡器已关闭，当前为预览模式。"

    fun setState(frequencies: DoubleArray, gains: DoubleArray, activeIndex: Int, effectEnabled: Boolean) {
        this.frequencies = frequencies.copyOf()
        this.gains = gains.copyOf()
        this.activeIndex = activeIndex.takeIf { it >= 0 && it < min(frequencies.size, gains.size) } ?: -1
        this.effectEnabled = effectEnabled
        activeFrequencyLabel = this.frequencies.getOrNull(this.activeIndex)?.takeIf { it.isFinite() && it > 0.0 }
            ?.let(::frequencyLabel) ?: ""
    }

    fun clear() {
        frequencies = doubleArrayOf()
        gains = doubleArrayOf()
        activeIndex = -1
        effectEnabled = true
        activeFrequencyLabel = ""
    }

    fun draw(canvas: Canvas, bounds: RectF, minimumHz: Double, maximumHz: Double,
        minimumGain: Double, maximumGain: Double, responseFrequencies: DoubleArray, responseValues: DoubleArray) {
        if (!bounds.left.isFinite() || !bounds.top.isFinite() || !bounds.right.isFinite() || !bounds.bottom.isFinite() ||
            bounds.width() <= 0f || bounds.height() <= 0f || !minimumHz.isFinite() || !maximumHz.isFinite() ||
            minimumHz <= 0.0 || maximumHz <= minimumHz || !minimumGain.isFinite() || !maximumGain.isFinite() ||
            maximumGain <= minimumGain) return
        val logMinimum = ln(minimumHz)
        val logSpan = ln(maximumHz) - logMinimum
        val gainSpan = maximumGain - minimumGain
        if (!logSpan.isFinite() || logSpan <= 0.0 || !gainSpan.isFinite()) return
        canvas.save()
        canvas.clipRect(bounds)
        for (index in listeningNames.indices) {
            val low = max(minimumHz, regionEdges[index])
            val high = min(maximumHz, regionEdges[index + 1])
            if (high > low) {
                regionPaint.color = ColorUtils.setAlphaComponent(accent, if (index % 2 == 0) 0x09 else 0x04)
                canvas.drawRect(frequencyX(low, bounds, logMinimum, logSpan), bounds.top,
                    frequencyX(high, bounds, logMinimum, logSpan), bounds.bottom, regionPaint)
            }
        }
        for (edge in regionEdges) {
            if (edge > minimumHz && edge < maximumHz) {
                val x = frequencyX(edge, bounds, logMinimum, logSpan)
                canvas.drawLine(x, bounds.top, x, bounds.bottom, boundaryPaint)
            }
        }
        val legendBottom = drawLegend(canvas, bounds)
        if (!effectEnabled) drawDisabledStatus(canvas, bounds, legendBottom)
        val count = min(frequencies.size, gains.size)
        for (index in 0 until count) {
            if (index == activeIndex) continue
            drawNode(canvas, bounds, index, minimumHz, maximumHz, minimumGain, gainSpan,
                logMinimum, logSpan, responseFrequencies, responseValues, legendBottom)
        }
        if (activeIndex >= 0 && activeIndex < count) {
            drawNode(canvas, bounds, activeIndex, minimumHz, maximumHz, minimumGain, gainSpan,
                logMinimum, logSpan, responseFrequencies, responseValues, legendBottom)
        }
        canvas.restore()
    }

    private fun drawLegend(canvas: Canvas, bounds: RectF): Float {
        val cellWidth = bounds.width() / listeningNames.size
        if (cellWidth < 8f * density || bounds.height() < 12f * density) return bounds.top
        val twoRows = bounds.height() >= 54f * density
        val rowHeight = min(12f * density, if (twoRows) bounds.height() / 6f else bounds.height() / 3f)
        val top = bounds.top + 2f * density
        val bottom = min(bounds.bottom, top + rowHeight * if (twoRows) 2f else 1f)
        // An equal-width legend keeps all six names readable even when log-frequency regions are narrow.
        canvas.drawRect(bounds.left, top, bounds.right, bottom, legendBackground)
        for (index in listeningNames.indices) {
            val x = bounds.left + (index + .5f) * cellWidth
            if (twoRows) {
                drawCenteredText(canvas, regionNames[index], x, top + rowHeight * .5f,
                    cellWidth - 4f * density, legendText)
                drawCenteredText(canvas, listeningNames[index], x, top + rowHeight * 1.5f,
                    cellWidth - 4f * density, legendText)
            } else {
                drawCenteredText(canvas, listeningNames[index], x, (top + bottom) * .5f,
                    cellWidth - 4f * density, legendText)
            }
        }
        return bottom
    }

    private fun drawDisabledStatus(canvas: Canvas, bounds: RectF, legendBottom: Float) {
        val regionTop = max(bounds.top, legendBottom)
        val centerY = (regionTop + bounds.bottom) * .5f
        val available = bounds.width() - 12f * density
        val lineHeight = min(17f * density, (bounds.bottom - regionTop) * .4f)
        if (available <= 0f || lineHeight <= 0f) return
        drawCenteredText(canvas, "曲线均衡器已关闭", bounds.centerX(), centerY - lineHeight * .5f,
            available, statusText)
        drawCenteredText(canvas, "（预览模式）", bounds.centerX(), centerY + lineHeight * .5f,
            available, statusText)
    }

    private fun drawNode(canvas: Canvas, bounds: RectF, index: Int, minimumHz: Double, maximumHz: Double,
        minimumGain: Double, gainSpan: Double, logMinimum: Double, logSpan: Double,
        responseFrequencies: DoubleArray, responseValues: DoubleArray, legendBottom: Float) {
        val frequency = frequencies[index]
        if (!frequency.isFinite() || frequency < minimumHz || frequency > maximumHz || !gains[index].isFinite()) return
        val response = responseAt(frequency, responseFrequencies, responseValues) ?: return
        val x = frequencyX(frequency, bounds, logMinimum, logSpan).coerceIn(bounds.left, bounds.right)
        val fraction = ((response - minimumGain) / gainSpan).coerceIn(0.0, 1.0)
        if (!fraction.isFinite()) return
        val y = bounds.bottom - bounds.height() * fraction.toFloat()
        val active = index == activeIndex
        val color = if (effectEnabled) accent else ColorUtils.blendARGB(foregroundColor, surface, .4f)
        nodePaint.color = if (active) color else surface
        nodeOutline.color = color
        if (active) {
            haloPaint.color = ColorUtils.setAlphaComponent(color, 0x25)
            canvas.drawCircle(x, y, 8f * density, haloPaint)
        }
        val radius = (if (active) 4.7f else 3.2f) * density
        canvas.drawCircle(x, y, radius, nodePaint)
        canvas.drawCircle(x, y, radius, nodeOutline)
        if (active && activeFrequencyLabel.isNotEmpty()) {
            val padding = 4f * density
            val availableWidth = bounds.width() - padding * 2f
            val labelHeight = min(18f * density, bounds.height() - padding * 2f)
            if (availableWidth <= 0f || labelHeight <= 0f) return
            val labelWidth = min(availableWidth, activeText.measureText(activeFrequencyLabel) + padding * 2f)
            val minimumLeft = bounds.left + padding
            val maximumLeft = max(minimumLeft, bounds.right - padding - labelWidth)
            val left = (x - labelWidth * .5f).coerceIn(minimumLeft, maximumLeft)
            val minimumTop = min(bounds.bottom - labelHeight, legendBottom + 2f * density)
            val preferredTop = if (y - labelHeight - 8f * density >= minimumTop)
                y - labelHeight - 8f * density else y + 8f * density
            val top = preferredTop.coerceIn(minimumTop, max(minimumTop, bounds.bottom - labelHeight))
            labelBounds.set(left, top, left + labelWidth, top + labelHeight)
            canvas.drawRoundRect(labelBounds, 3f * density, 3f * density, activeBackground)
            activeText.color = color
            drawCenteredText(canvas, activeFrequencyLabel, labelBounds.centerX(), labelBounds.centerY(),
                labelWidth - padding * 2f, activeText)
        }
    }

    /** Interpolate adjacent displayed response samples on their logarithmic frequency axis. */
    private fun responseAt(frequency: Double, xs: DoubleArray, ys: DoubleArray): Double? {
        val count = min(xs.size, ys.size)
        if (count == 0) return null
        var left = 0
        var right = count - 1
        val first = xs[left]
        val last = xs[right]
        if (!first.isFinite() || !last.isFinite() || first <= 0.0 || last < first ||
            frequency < first || frequency > last) return null
        while (left <= right) {
            val middle = (left + right).ushr(1)
            val current = xs[middle]
            if (!current.isFinite() || current <= 0.0) return null
            when {
                frequency == current -> return ys[middle].takeIf { it.isFinite() }
                frequency < current -> right = middle - 1
                else -> left = middle + 1
            }
        }
        if (right < 0 || left >= count || left != right + 1) return null
        val low = xs[right]
        val high = xs[left]
        val lowGain = ys[right]
        val highGain = ys[left]
        if (!low.isFinite() || !high.isFinite() || low <= 0.0 || high <= low ||
            frequency < low || frequency > high || !lowGain.isFinite() || !highGain.isFinite()) return null
        val fraction = (ln(frequency) - ln(low)) / (ln(high) - ln(low))
        return (lowGain * (1.0 - fraction) + highGain * fraction).takeIf { it.isFinite() }
    }

    private fun frequencyX(frequency: Double, bounds: RectF, logMinimum: Double, logSpan: Double) =
        bounds.left + bounds.width() * ((ln(frequency) - logMinimum) / logSpan).toFloat()

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, centerY: Float,
        availableWidth: Float, paint: Paint) {
        if (availableWidth <= 0f) return
        val originalSize = paint.textSize
        val measuredWidth = paint.measureText(text)
        if (measuredWidth > availableWidth) paint.textSize *= availableWidth / measuredWidth
        val baseline = centerY - (paint.fontMetrics.ascent + paint.fontMetrics.descent) * .5f
        canvas.drawText(text, x, baseline, paint)
        paint.textSize = originalSize
    }

    private fun frequencyLabel(value: Double): String = if (value >= 1000.0)
        String.format(Locale.ROOT, "%.4g kHz", value / 1000.0) else String.format(Locale.ROOT, "%.4g Hz", value)
}
