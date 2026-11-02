package me.timschneeberger.rootlessjamesdsp.eq

import android.content.Context
import android.content.SharedPreferences

/** Local analyser presentation, independent of every audio parameter and preset bank. */
data class SpectrumDisplayOptions(
    val peakHold: Boolean = true,
    val linearFrequency: Boolean = true,
    val flatten: Boolean = false,
    val sensitivity: Float = .25f,
    val responseMs: Int = 80,
    val scale: Float = 1f,
    val followSampleRate: Boolean = true
) {
    fun normalized(): SpectrumDisplayOptions = copy(
        sensitivity = if (sensitivity.isFinite()) sensitivity.coerceIn(.05f, 1f) else .25f,
        responseMs = responseMs.coerceIn(50, 500),
        scale = if (scale.isFinite()) scale.coerceIn(.5f, 2f) else 1f
    )
}

/** The EQ and PEQ analysers share a file, with independent namespace-prefixed display keys. */
class SpectrumDisplayPreferences(context: Context, namespace: String) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "eq_spectrum_appearance", Context.MODE_PRIVATE)
    private val prefix = "$namespace."
    private fun key(name: String) = prefix + name

    fun load(): SpectrumDisplayOptions {
        val values = preferences.all
        val defaults = SpectrumDisplayOptions()
        fun boolean(name: String, fallback: Boolean) = values[key(name)] as? Boolean ?: fallback
        fun number(name: String, fallback: Float) = (values[key(name)] as? Number)?.toFloat() ?: fallback
        return SpectrumDisplayOptions(
            peakHold = boolean("peakHold", defaults.peakHold),
            linearFrequency = boolean("linearFrequency", defaults.linearFrequency),
            flatten = boolean("flatten", defaults.flatten),
            sensitivity = number("sensitivity", defaults.sensitivity),
            responseMs = values[key("responseMs")] as? Int ?: defaults.responseMs,
            scale = number("scale", defaults.scale),
            followSampleRate = boolean("followSampleRate", defaults.followSampleRate)
        ).normalized()
    }

    fun save(options: SpectrumDisplayOptions): Boolean {
        val value = options.normalized()
        return preferences.edit()
            .putBoolean(key("peakHold"), value.peakHold)
            .putBoolean(key("linearFrequency"), value.linearFrequency)
            .putBoolean(key("flatten"), value.flatten)
            .putFloat(key("sensitivity"), value.sensitivity)
            .putInt(key("responseMs"), value.responseMs)
            .putFloat(key("scale"), value.scale)
            .putBoolean(key("followSampleRate"), value.followSampleRate)
            .commit()
    }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
}
