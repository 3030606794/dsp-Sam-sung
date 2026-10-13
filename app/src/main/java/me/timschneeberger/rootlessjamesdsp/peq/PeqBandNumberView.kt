package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.text.TextPaint
import android.util.AttributeSet
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.drawable.DrawableCompat
import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.roundToInt

/** Keep the visible number and settings glyph together inside the full header touch target. */
class PeqBandNumberView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatTextView(context, attrs) {
    private val settingsIcon = requireNotNull(AppCompatResources.getDrawable(context,
        R.drawable.ic_twotone_settings_24dp)).mutate()
    private val textBounds = Rect()
    private val numberPaint = TextPaint()

    override fun onDraw(canvas: Canvas) {
        val number = text.toString()
        if (number.isEmpty()) return
        val density = resources.displayMetrics.density
        val iconSize = (12 * density).roundToInt()
        val gap = 5 * density
        // The gear's path spans y=2..22 inside its 24-unit viewport.
        val iconVisibleInset = iconSize * 2f / 24f
        val iconVisibleHeight = iconSize - 2 * iconVisibleInset
        numberPaint.set(paint)
        numberPaint.color = currentTextColor
        numberPaint.getTextBounds(number, 0, number.length, textBounds)
        val maxTextHeight = (28 * density - iconVisibleHeight - gap).coerceAtLeast(density)
        if (textBounds.height() > maxTextHeight) {
            numberPaint.textSize *= maxTextHeight / textBounds.height()
            numberPaint.getTextBounds(number, 0, number.length, textBounds)
        }
        val contentHeight = textBounds.height() + gap + iconVisibleHeight
        val top = (height - contentHeight) / 2f
        // Center the actual glyph bounds rather than a font line box with hidden leading.
        canvas.drawText(number, (width - textBounds.width()) / 2f - textBounds.left,
            top - textBounds.top, numberPaint)
        val iconLeft = ((width - iconSize) / 2f).roundToInt()
        val iconTop = (top + textBounds.height() + gap - iconVisibleInset).roundToInt()
        DrawableCompat.setTint(settingsIcon, currentTextColor)
        settingsIcon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        settingsIcon.draw(canvas)
    }
}
