package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassControlUi
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
internal fun Context.primaryColor() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.MAGENTA)
internal fun Context.textColor() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY)
internal fun Context.mutedColor() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
internal fun Context.text(value: String, size: Float = 13f, bold: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(textColor()); gravity = Gravity.CENTER_VERTICAL
    if (bold) setTypeface(typeface, Typeface.BOLD)
}
internal fun Context.action(label: String, clicked: (() -> Unit)? = null) =
    MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = label; textSize = 13f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        insetTop = 0; insetBottom = 0; setPadding(0, 0, 0, 0); maxLines = 1
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 13, 1,
            android.util.TypedValue.COMPLEX_UNIT_SP)
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        if (clicked != null) setOnClickListener { clicked() }
        GlassControlUi.decorate(this)
    }
internal fun Context.card() = MaterialCardView(this).apply {
    preventCornerOverlap = false; useCompatPadding = false
    setContentPadding(0, 0, 0, 0)
}
internal fun LinearLayout.addWeighted(view: View, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
    addView(view, LinearLayout.LayoutParams(0, height, 1f))
internal fun LinearLayout.addFull(view: View, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
    addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))

private val dynamicNumberFormats = ThreadLocal.withInitial { mutableMapOf<Int, DecimalFormat>() }

internal fun dynamicNumber(value: Double, decimals: Int = 2): String {
    val digits = decimals.coerceIn(0, 8)
    val format = dynamicNumberFormats.get().getOrPut(digits) {
        DecimalFormat("0" + if (digits > 0) "." + "#".repeat(digits) else "",
            DecimalFormatSymbols.getInstance(Locale.US))
    }
    return format.format(if (abs(value) < 0.000001) 0.0 else value)
}

internal fun dynamicFrequency(value: Double): String =
    if (value >= 1000.0) "${dynamicNumber(value / 1000.0)} kHz" else "${dynamicNumber(value, 1)} Hz"

internal fun dynamicType(type: Int) = when (type) { 1 -> "低架"; 2 -> "高架"; else -> "峰值" }
