package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil

/** Fixed-size status captions: changing telemetry cannot request a layout or change chart geometry. */
internal class StableDashboardReadouts(
    context: Context,
    primaryTextSizeSp: Float = 11f,
    secondaryTextSizeSp: Float = 10f,
    statusTextSizeSp: Float = 10f
) : View(context) {
    private val density = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val customTextSizes = primaryTextSizeSp != 11f || secondaryTextSizeSp != 10f || statusTextSizeSp != 10f
    private val paints: List<TextPaint> = listOf(primaryTextSizeSp, secondaryTextSizeSp, statusTextSizeSp).mapIndexed { index, size ->
        TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * resources.displayMetrics.scaledDensity
            typeface = Typeface.create("sans-serif", if (index == 0) Typeface.BOLD else Typeface.NORMAL)
            color = if (index == 0) accent else ColorUtils.setAlphaComponent(ink, 190)
            fontFeatureSettings = "tnum"
        }
    }
    private val heights: List<Int> = paints.map {
        val height = if (customTextSizes) maxOf(it.fontSpacing, it.fontMetrics.bottom - it.fontMetrics.top) else it.fontSpacing
        ceil(height.toDouble()).toInt() + ceil(2 * density).toInt()
    }
    private var lines: List<String> = listOf("", "", "")
    val fixedContentHeight: Int get() = heights.sum() + paddingTop + paddingBottom

    fun setLines(values: List<String>) {
        val next = List(3) { values.getOrNull(it).orEmpty() }
        if (next == lines) return
        lines = next
        contentDescription = next.joinToString("\n")
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(fixedContentHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0).toFloat()
        var top = paddingTop.toFloat()
        paints.forEachIndexed { index, paint ->
            val value = TextUtils.ellipsize(lines[index], paint, available, TextUtils.TruncateAt.END).toString()
            val topMetric = if (customTextSizes) paint.fontMetrics.top else paint.fontMetrics.ascent
            canvas.drawText(value, paddingLeft.toFloat(), top + density - topMetric, paint)
            top += heights[index]
        }
    }
}
