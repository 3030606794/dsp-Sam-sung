package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

/** App text size is a multiplier of the unmodified system setting, never of an activity override. */
object AppFontScale {
    const val KEY = "appearance_font_scale"
    const val DEFAULT_PERCENT = 100
    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 150

    fun percent(context: Context): Int {
        val stored = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
            .all[KEY] as? Number
        val value = stored?.toFloat()?.takeIf { it.isFinite() } ?: DEFAULT_PERCENT.toFloat()
        return value.coerceIn(MIN_PERCENT.toFloat(), MAX_PERCENT.toFloat()).roundToInt()
    }

    fun wrap(base: Context): Context {
        val factor = percent(base) / 100f
        // The application resources stay at the system scale. In particular, opening a
        // second activity from a scaled activity must not multiply its override again.
        val systemScale = (base.applicationContext ?: base).resources.configuration.fontScale
            .takeIf { it.isFinite() && it > 0f } ?: 1f
        val configuration = Configuration(base.resources.configuration).apply {
            fontScale = systemScale * factor
        }
        return base.createConfigurationContext(configuration)
    }
}
