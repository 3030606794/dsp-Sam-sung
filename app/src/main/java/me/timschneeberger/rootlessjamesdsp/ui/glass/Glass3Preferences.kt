package me.timschneeberger.rootlessjamesdsp.ui.glass

import android.content.Context
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/** Visual preferences only. No effect values or driver interfaces are stored here. */
object Glass3Preferences {
    const val PREFIX = "glass3_"
    const val MOTION_ENABLED = "glass3_motion_enabled"
    const val STRENGTH = "glass3_strength"
    const val SPEED = "glass3_speed"
    const val OPACITY = "glass3_opacity"
    const val REFRACTION = "glass3_refraction"
    const val HIGHLIGHT = "glass3_highlight"
    const val LIQUID_TOOLTIP = "glass3_liquid_tooltip"
    const val COLORFUL_BACKGROUND = "glass3_colorful_background"

    data class Snapshot(
        val motionEnabled: Boolean = true,
        val strength: Float = .70f,
        val speed: Float = 1f,
        val opacity: Float = .72f,
        val refraction: Float = .80f,
        val highlight: Float = .85f,
        val liquidTooltip: Boolean = true,
        val colorfulBackground: Boolean = false,
    )

    @Volatile private var cached = Snapshot()
    @Volatile private var storage: SharedPreferences? = null
    // Hold the listener strongly: SharedPreferences retains its listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key.startsWith(PREFIX)) cached = read(prefs)
    }

    fun snapshot(context: Context): Snapshot {
        if (storage == null) preferences(context)
        return cached
    }

    fun setBoolean(context: Context, key: String, value: Boolean) {
        require(key == MOTION_ENABLED || key == LIQUID_TOOLTIP || key == COLORFUL_BACKGROUND)
        val prefs = preferences(context)
        cached = when (key) {
            MOTION_ENABLED -> cached.copy(motionEnabled = value)
            LIQUID_TOOLTIP -> cached.copy(liquidTooltip = value)
            else -> cached.copy(colorfulBackground = value)
        }
        prefs.edit().putBoolean(key, value).apply()
    }

    fun setPercent(context: Context, key: String, value: Int) {
        val range = when (key) {
            SPEED -> 50..150
            OPACITY -> 30..100
            STRENGTH, REFRACTION, HIGHLIGHT -> 0..150
            else -> error("Unknown glass appearance setting")
        }
        val prefs = preferences(context)
        val bounded = value.coerceIn(range)
        val fraction = bounded / 100f
        cached = when (key) {
            SPEED -> cached.copy(speed = fraction)
            OPACITY -> cached.copy(opacity = fraction)
            STRENGTH -> cached.copy(strength = fraction)
            REFRACTION -> cached.copy(refraction = fraction)
            else -> cached.copy(highlight = fraction)
        }
        prefs.edit().putInt(key, bounded).apply()
    }

    fun reset(context: Context) {
        val prefs = preferences(context)
        cached = Snapshot()
        prefs.edit()
            .putBoolean(MOTION_ENABLED, true)
            .putInt(STRENGTH, 70).putInt(SPEED, 100).putInt(OPACITY, 72)
            .putInt(REFRACTION, 80).putInt(HIGHLIGHT, 85)
            .putBoolean(LIQUID_TOOLTIP, true).putBoolean(COLORFUL_BACKGROUND, false)
            .apply()
        cached = read(preferences(context))
    }

    @Synchronized private fun preferences(context: Context): SharedPreferences {
        storage?.let { return it }
        return context.applicationContext.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).also {
            cached = read(it)
            it.registerOnSharedPreferenceChangeListener(listener)
            storage = it
        }
    }

    private fun read(prefs: SharedPreferences) = Snapshot(
        motionEnabled = prefs.getBoolean(MOTION_ENABLED, true),
        strength = prefs.getInt(STRENGTH, 70).coerceIn(0, 150) / 100f,
        speed = prefs.getInt(SPEED, 100).coerceIn(50, 150) / 100f,
        opacity = prefs.getInt(OPACITY, 72).coerceIn(30, 100) / 100f,
        refraction = prefs.getInt(REFRACTION, 80).coerceIn(0, 150) / 100f,
        highlight = prefs.getInt(HIGHLIGHT, 85).coerceIn(0, 150) / 100f,
        liquidTooltip = prefs.getBoolean(LIQUID_TOOLTIP, true),
        colorfulBackground = prefs.getBoolean(COLORFUL_BACKGROUND, false),
    )
}
