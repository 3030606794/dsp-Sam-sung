package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.drawable.DrawableCompat
import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.roundToInt

/** Positions the visible glyphs, avoiding transparent font/vector margins. */
class PeqFilterLabelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatTextView(context, attrs) {
    private var curve: Drawable? = null
    private val labelPaint = TextPaint()
    private val glyphBounds = Rect()

    /** Footer cells use 5 dp outer insets and a 3 dp text/curve gap; headers keep a 5 dp gap. */
    var inlineMode: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    internal fun inlineHeightWithCurve(): Int {
        val label = text.toString()
        paint.getTextBounds(label, 0, label.length, glyphBounds)
        // Font fallback can initially report empty glyph bounds. Reserve at least
        // the requested text size so turning on curves never shrinks the row.
        return (maxOf(glyphBounds.height().toFloat(), paint.textSize) +
            25 * resources.displayMetrics.density).roundToInt()
    }

    var filterIcon: PeqFilterType? = null
        set(value) {
            if (field == value) return
            field = value
            curve = value?.let { AppCompatResources.getDrawable(context, iconId(it))?.mutate() }
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val type = filterIcon
        val icon = curve
        if (text.isNullOrEmpty() || (!inlineMode && (type == null || icon == null))) {
            super.onDraw(canvas)
            return
        }
        val density = resources.displayMetrics.density
        val iconWidth = (22 * density).roundToInt()
        val iconHeight = (16 * density).roundToInt()
        labelPaint.set(paint)
        labelPaint.color = currentTextColor
        val label = TextUtils.ellipsize(text, labelPaint,
            (width - paddingLeft - paddingRight).coerceAtLeast(0).toFloat(), TextUtils.TruncateAt.END).toString()
        if (label.isEmpty()) return
        labelPaint.getTextBounds(label, 0, label.length, glyphBounds)
        if (type == null || icon == null) {
            // Chinese fallback and Latin action labels have different font line
            // boxes. Center their visible glyphs on the same horizontal axis.
            canvas.drawText(label, (width - glyphBounds.width()) / 2f - glyphBounds.left,
                (height - glyphBounds.height()) / 2f - glyphBounds.top, labelPaint)
            return
        }

        // The shared 32x20 vectors include transparent space above and below their
        // 1.8-unit strokes. Position their visible edges, not those viewport margins.
        val visibleTop = (if (type == PeqFilterType.BAND_PASS) 3.35f else 3.1f) / 20f * iconHeight
        val visibleBottom = when (type) {
            PeqFilterType.PEAK, PeqFilterType.LOW_SHELF, PeqFilterType.HIGH_SHELF -> 15.9f
            PeqFilterType.NOTCH -> 17.9f
            else -> 18.9f
        } / 20f * iconHeight
        val gap = (if (inlineMode) 3 else 5) * density
        val contentHeight = glyphBounds.height() + gap + visibleBottom - visibleTop
        val top = if (inlineMode) 5 * density else (height - contentHeight) / 2f
        canvas.drawText(label, (width - glyphBounds.width()) / 2f - glyphBounds.left,
            top - glyphBounds.top, labelPaint)
        DrawableCompat.setTint(icon, currentTextColor)
        icon.setBounds(0, 0, iconWidth, iconHeight)
        val save = canvas.save()
        try {
            val curveTop = top + glyphBounds.height() + gap
            canvas.translate((width - iconWidth) / 2f, curveTop)
            if (inlineMode) {
                // All seven curves end 5 dp above the lower border. A shared row
                // height accommodates slightly different glyph bounds without
                // changing the 3 dp gap or adding invisible viewport whitespace.
                val visibleHeight = (height - 5 * density - curveTop).coerceAtLeast(density)
                canvas.scale(1f, visibleHeight / (visibleBottom - visibleTop))
            }
            canvas.translate(0f, -visibleTop)
            icon.draw(canvas)
        } finally { canvas.restoreToCount(save) }
    }

    private fun iconId(type: PeqFilterType): Int = when (type) {
        PeqFilterType.PEAK -> R.drawable.ic_peq_filter_peak
        PeqFilterType.LOW_SHELF -> R.drawable.ic_peq_filter_low_shelf
        PeqFilterType.HIGH_SHELF -> R.drawable.ic_peq_filter_high_shelf
        PeqFilterType.LOW_PASS -> R.drawable.ic_peq_filter_low_pass
        PeqFilterType.HIGH_PASS -> R.drawable.ic_peq_filter_high_pass
        PeqFilterType.NOTCH -> R.drawable.ic_peq_filter_notch
        PeqFilterType.BAND_PASS -> R.drawable.ic_peq_filter_band_pass
    }
}
