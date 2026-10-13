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

/** A filter label with an optional curve, separated by 3 dp of visible whitespace. */
class PeqFilterLabelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatTextView(context, attrs) {
    private var curve: Drawable? = null
    private val labelPaint = TextPaint()
    private val glyphBounds = Rect()

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
        if (type == null || icon == null || text.isNullOrEmpty()) {
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

        // The shared 32x20 vectors include transparent space above and below their
        // 1.8-unit strokes. Position their visible edges, not those viewport margins.
        val visibleTop = (if (type == PeqFilterType.BAND_PASS) 3.35f else 3.1f) / 20f * iconHeight
        val visibleBottom = when (type) {
            PeqFilterType.PEAK, PeqFilterType.LOW_SHELF, PeqFilterType.HIGH_SHELF -> 15.9f
            PeqFilterType.NOTCH -> 17.9f
            else -> 18.9f
        } / 20f * iconHeight
        val gap = 3 * density
        val contentHeight = glyphBounds.height() + gap + visibleBottom - visibleTop
        val top = (height - contentHeight) / 2f
        canvas.drawText(label, (width - glyphBounds.width()) / 2f - glyphBounds.left,
            top - glyphBounds.top, labelPaint)
        DrawableCompat.setTint(icon, currentTextColor)
        icon.setBounds(0, 0, iconWidth, iconHeight)
        val save = canvas.save()
        try {
            canvas.translate((width - iconWidth) / 2f, top + glyphBounds.height() + gap - visibleTop)
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
