package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.SharedPreferences

/** One private DSP namespace makes these values part of normal presets/device profiles. */
object ToneController {
    const val PREFS = "dsp_fusion_tone"
    const val PARAMETER = 196608
    const val ENABLED = "fusion_tone_enabled"
    const val RANGE = "fusion_tone_range"
    val RANGES = listOf(20, 40, 100)
    val KEYS = arrayOf(
        "fusion_tone_temperature", "fusion_tone_bass_depth", "fusion_tone_bass_impact",
        "fusion_tone_thickness", "fusion_tone_vocal", "fusion_tone_female",
        "fusion_tone_sibilance_low", "fusion_tone_sibilance_high", "fusion_tone_transient",
        "fusion_tone_air"
    )

    @Suppress("DEPRECATION")
    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)

    fun range(preferences: SharedPreferences): Int =
        (preferences.all[RANGE] as? String)?.toIntOrNull()?.takeIf { it in RANGES } ?: 100

    fun payload(context: Context): IntArray {
        val prefs = preferences(context)
        val limit = range(prefs)
        return IntArray(11).apply {
            this[0] = if (prefs.all[ENABLED] as? Boolean == true) 1 else 0
            KEYS.forEachIndexed { index, key -> this[index + 1] = (prefs.all[key] as? Int ?: 0).coerceIn(-limit, limit) }
        }
    }

    fun sync(context: Context, sender: (Int, IntArray) -> Boolean): Boolean {
        val prefs=preferences(context)
        if(!ToneAdvancedParameters.validSavedFoundation(prefs.all)) {
            // Validation failures retain the last valid sound; they are not transport failures.
            return if(prefs.all[ENABLED]==false) sender(PARAMETER,IntArray(11)) else true
        }
        return sender(PARAMETER, payload(context))
    }
}
