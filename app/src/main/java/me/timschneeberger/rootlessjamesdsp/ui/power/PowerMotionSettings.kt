package me.timschneeberger.rootlessjamesdsp.ui.power

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants

enum class PowerMotionStyle(val key: String) {
    WAVE("wave"),
    SPECTRUM("spectrum"),
    PHASE("phase"),
    MATRIX("matrix"),
    REFRACTION("refraction"),
    DOME("dome");

    @get:StringRes
    val summaryResId: Int
        get() = when (this) {
            WAVE -> R.string.power_motion_style_wave_summary
            SPECTRUM -> R.string.power_motion_style_spectrum_summary
            PHASE -> R.string.power_motion_style_phase_summary
            MATRIX -> R.string.power_motion_style_matrix_summary
            REFRACTION -> R.string.power_motion_style_refraction_summary
            DOME -> R.string.power_motion_style_dome_summary
        }

    companion object {
        fun fromKey(key: String?): PowerMotionStyle = entries.firstOrNull { it.key == key } ?: WAVE
    }
}

data class PowerMotionConfig(val enabled: Boolean = true, val style: PowerMotionStyle = PowerMotionStyle.WAVE)

/** Presentation preferences only; power state and audio parameter transport are independent. */
object PowerMotionSettings {
    const val KEY_ENABLED = "appearance_power_motion_enabled"
    const val KEY_STYLE = "appearance_power_motion_style"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)

    fun read(context: Context): PowerMotionConfig {
        val values = preferences(context).all
        return PowerMotionConfig(
            enabled = values[KEY_ENABLED] as? Boolean ?: true,
            style = PowerMotionStyle.fromKey(values[KEY_STYLE] as? String),
        )
    }
}
