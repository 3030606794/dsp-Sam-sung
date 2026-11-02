package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** Native readout text stays complete; summary updates retain RuntimeStatusPreference's no-rebind path. */
internal class StableRuntimeReadoutPreference(context: Context) : RuntimeStatusPreference(context) {
    init { isSelectable = false; isIconSpaceReserved = false }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.summary) as? TextView)?.apply {
            typeface = Typeface.MONOSPACE
            fontFeatureSettings = "tnum"
            textDirection = View.TEXT_DIRECTION_LTR
            setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            val fixedLineHeight = ceil(max(paint.fontSpacing, textSize * 1.4f)).toInt()
            if (Build.VERSION.SDK_INT >= 28) {
                setFallbackLineSpacing(false)
                setLineHeight(fixedLineHeight)
            } else setLineSpacing(max(0f, fixedLineHeight - paint.fontSpacing), 1f)
        }
    }

    companion object {
        /** Preserve the current reading exactly; padding stabilizes columns and missing values never reuse old data. */
        fun fixedNumber(raw: Int?, divisor: Double = 10.0, decimals: Int = 1,
            signed: Boolean = false, width: Int = 8): String {
            if (raw == null || raw == Int.MIN_VALUE) return "‒".padStart(width)
            return String.format(Locale.ROOT, "%${if (signed) "+" else ""}$width.${decimals}f", raw / divisor)
        }
    }
}
