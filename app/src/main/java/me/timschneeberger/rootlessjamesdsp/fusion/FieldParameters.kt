package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** The legacy integer keys retain their original scale and defaults. */
object FieldParameters {
    const val PACKET = 65608
    const val MODE = "v_field_processing_mode"
    const val BAND_ENABLED = "v_field_band_enabled"
    const val FOCUS_ENABLED = "v_field_focus_enabled"
    const val LEGACY_DEPTH_ENABLED = "v_field_legacy_depth_enabled"
    const val MS_DEPTH_ENABLED = "v_field_ms_depth_enabled"
    val legacyWidth = BassParameters.Control("v_field_widen", "立体声拓展", 0, 100, 0, unit = "%")
    val legacyCenter = BassParameters.Control("v_field_center", "中央声像", 100, 200, 150, unit = "%")
    val depth = BassParameters.Control("v_field_depth", "声场深度（原单位）", 200, 800, 200)
    val legacy = listOf(legacyWidth, legacyCenter, depth)
    val legacyKeys = legacy.map { it.key }.toSet()
    val width = BassParameters.Control("v_field_ms_width_tenths", "M/S 宽度", 0, 2000, 1000, 10, "%", 1, advanced = true)
    val midTrim = BassParameters.Control("v_field_mid_trim", "Mid 增益修正", -120, 60, 0, 10, "dB", 1, advanced = true)
    val bandLow = BassParameters.Control("v_field_band_low_hz", "成像频带下限", 100, 2000, 150, unit = "Hz", advanced = true)
    val bandHigh = BassParameters.Control("v_field_band_high_hz", "成像频带上限", 2000, 20000, 16000, unit = "Hz", advanced = true)
    val focusFrequency = BassParameters.Control("v_field_focus_hz", "低频聚焦截止", 40, 500, 120, unit = "Hz", advanced = true)
    val focusWidth = BassParameters.Control("v_field_focus_width_tenths", "低频保留宽度", 0, 1000, 1000, 10, "%", 1, advanced = true)
    val depthMix = BassParameters.Control("v_field_depth_mix_tenths", "深度混合", 0, 1000, 1000, 10, "%", 1, advanced = true)
    val outputTrim = BassParameters.Control("v_field_output_trim", "输出衰减", -120, 0, 0, 10, "dB", 1, advanced = true)
    val advanced = listOf(width, midTrim, bandLow, bandHigh, focusFrequency, focusWidth, depthMix, outputTrim)
    val all = legacy + advanced
    val switches = listOf(BAND_ENABLED, FOCUS_ENABLED, LEGACY_DEPTH_ENABLED, MS_DEPTH_ENABLED)
    val newKeys = advanced.map { it.key }.toSet() + switches + MODE
    private fun integer(values: Map<String, *>, key: String) = (values[key] as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
    }?.toInt()
    private fun raw(values: Map<String, *>, control: BassParameters.Control) =
        (integer(values, control.key) ?: control.default).coerceIn(control.min, control.max)
    fun value(values: Map<String, *>, control: BassParameters.Control): Int {
        if ((control == bandLow || control == bandHigh) && raw(values, bandLow) >= raw(values, bandHigh)) return control.default
        return raw(values, control)
    }
    fun mode(values: Map<String, *>) = integer(values, MODE)?.takeIf { it in 0..1 } ?: 0
    fun enabled(values: Map<String, *>, key: String) = values[key] as? Boolean ?: (key == LEGACY_DEPTH_ENABLED)
    fun depthKey(mode: Int) = if (mode == 1) MS_DEPTH_ENABLED else LEGACY_DEPTH_ENABLED
    fun acceptsFrequency(values: Map<String, *>, key: String, candidate: Int) = when (key) {
        bandLow.key -> candidate < value(values, bandHigh)
        bandHigh.key -> candidate > value(values, bandLow)
        else -> true
    }
    fun migrate(preferences: SharedPreferences) {
        val values = preferences.all; val editor = preferences.edit(); var changed = false
        all.forEach { control -> if (values.containsKey(control.key) && values[control.key] != value(values, control)) {
            editor.putInt(control.key,value(values,control)); changed = true
        } }
        if ((values.containsKey(bandLow.key) || values.containsKey(bandHigh.key)) && raw(values,bandLow) >= raw(values,bandHigh)) {
            editor.putInt(bandLow.key,bandLow.default).putInt(bandHigh.key,bandHigh.default); changed = true
        }
        switches.forEach { key -> if (values.containsKey(key) && values[key] !is Boolean) {
            editor.putBoolean(key,key == LEGACY_DEPTH_ENABLED); changed = true
        } }
        if (values.containsKey(MODE) && values[MODE] != mode(values)) { editor.putInt(MODE,mode(values)); changed = true }
        if (changed) editor.apply()
    }
    fun packet(values: Map<String, *>, audition: Int = 0): IntArray {
        val mode = mode(values)
        fun flag(key: String) = if (enabled(values,key)) 1 else 0
        return intArrayOf(mode,value(values,width),value(values,midTrim),flag(BAND_ENABLED),value(values,bandLow),value(values,bandHigh),
            flag(FOCUS_ENABLED),value(values,focusFrequency),value(values,focusWidth),flag(depthKey(mode)),value(values,depthMix),
            value(values,outputTrim),audition.coerceIn(0,3))
    }
    fun requiresNewApp(values: Map<String, *>) = newKeys.any { values.containsKey(it) }
}
