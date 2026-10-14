package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.roundToInt

/** Persist scaled integers so the value shown is exactly the value sent to the driver. */
object BassParameters {
    const val AMOUNT = "v_bass_amount_hundredths"
    const val MAX_GAIN = "bass_max_gain_tenths"
    const val LEGACY_AMOUNT = "v_bass_amount"
    const val LEGACY_MAX_GAIN = "bass_max_gain"
    const val DYNAMIC_PARAMETER = 1300

    data class Control(
        val key: String, val title: String, val min: Int, val max: Int, val default: Int,
        val divisor: Int = 1, val unit: String = "", val decimals: Int = 0,
        val parameter: Int? = null, val advanced: Boolean = false,
    ) {
        fun number(value: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value.toDouble() / divisor)
        fun label(value: Int): String = number(value) + if (unit.isEmpty()) "" else " $unit"
        fun parse(text: String): Int? = runCatching {
            val scaled = BigDecimal(text.trim().replace(',', '.')).multiply(BigDecimal(divisor))
                .intValueExact()
            scaled.takeIf { it in min..max }
        }.getOrNull()
    }

    val frequency = Control("v_bass_freq", "低音频率", 15, 150, 70, unit = "Hz", parameter = 65576)
    val amount = Control(AMOUNT, "增强系数", 50, 600, 50, 100, decimals = 2, parameter = 65577)
    val naturalQ = Control("v_bass_q_hundredths", "低通 Q 值", 30, 200, 53, 100, decimals = 2, parameter = 65590, advanced = true)
    val deepFrequency = Control("v_bass_deep_hz", "深潜频率", 20, 80, 44, unit = "Hz", parameter = 65591, advanced = true)
    val punchFrequency = Control("v_bass_punch_hz", "冲击频率", 60, 160, 80, unit = "Hz", parameter = 65592, advanced = true)
    val deepGain = Control("v_bass_deep_gain_tenths", "深潜增益修正", -120, 120, 0, 10, "dB", 1, 65593, true)
    val punchGain = Control("v_bass_punch_gain_tenths", "冲击增益修正", -120, 120, 0, 10, "dB", 1, 65594, true)
    val deepWidth = Control("v_bass_deep_bw_hundredths", "深潜带宽", 10, 200, 75, 100, "oct", 2, 65595, true)
    val punchWidth = Control("v_bass_punch_bw_hundredths", "冲击带宽", 10, 200, 20, 100, "oct", 2, 65596, true)
    val viperAdvanced = listOf(naturalQ, deepFrequency, punchFrequency, deepGain, punchGain, deepWidth, punchWidth)
    val viper = listOf(frequency, amount) + viperAdvanced

    val maxGain = Control(MAX_GAIN, "最大增益", 0, 150, 50, 10, "dB", 1)
    val trackLow = Control("bass_track_low_hz", "跟踪下限", 0, 249, 0, unit = "Hz", advanced = true)
    val trackHigh = Control("bass_track_high_hz", "跟踪上限", 1, 250, 250, unit = "Hz", advanced = true)
    val dynamicQ = Control("bass_q_hundredths", "增强 Q 值", 50, 400, 200, 100, decimals = 2, advanced = true)
    val tracking = Control("bass_tracking_ms", "频率跟随时间", 5, 1000, 48, unit = "ms", advanced = true)
    val rise = Control("bass_rise_ms", "增益上升时间", 5, 2000, 192, unit = "ms", advanced = true)
    val fall = Control("bass_fall_ms", "增益回落时间", 5, 5000, 192, unit = "ms", advanced = true)
    val dynamic = listOf(maxGain, trackLow, trackHigh, dynamicQ, tracking, rise, fall)

    private fun integer(values: Map<String, *>, key: String): Int? =
        (values[key] as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 &&
            it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()

    fun value(values: Map<String, *>, control: Control): Int {
        val fallback = when (control.key) {
            AMOUNT -> integer(values, LEGACY_AMOUNT)?.coerceIn(0, 11)?.let { it * 50 + 50 }
            MAX_GAIN -> (values[LEGACY_MAX_GAIN] as? Number)?.toDouble()?.takeIf { it.isFinite() }
                ?.coerceIn(0.0, 15.0)?.roundToInt()?.times(10)
            else -> null
        } ?: control.default
        return (integer(values, control.key) ?: fallback).coerceIn(control.min, control.max)
    }

    /** Missing new keys deliberately fall back again when an old preset is reimported. */
    fun migrate(preferences: SharedPreferences, viper: Boolean) {
        val values = preferences.all
        val control = if (viper) amount else maxGain
        val legacy = if (viper) LEGACY_AMOUNT else LEGACY_MAX_GAIN
        if (!values.containsKey(control.key) && values.containsKey(legacy))
            preferences.edit().putInt(control.key, value(values, control)).apply()
        if (viper) normalizeViper(preferences) else normalizeDynamic(preferences)
    }

    private fun normalizeViper(preferences: SharedPreferences) {
        val values = preferences.all
        val editor = preferences.edit()
        var changed = false
        viper.forEach { control ->
            if (values.containsKey(control.key)) {
                val normalized = value(values, control)
                if (values[control.key] != normalized) { editor.putInt(control.key, normalized); changed = true }
            }
        }
        if (changed) editor.apply()
    }

    fun normalizeDynamic(preferences: SharedPreferences) {
        val values = preferences.all
        val packet = dynamicPacket(values)
        val editor = preferences.edit()
        var changed = false
        dynamic.forEachIndexed { index, control ->
            if (values.containsKey(control.key) && values[control.key] != packet[index]) {
                editor.putInt(control.key, packet[index]); changed = true
            }
        }
        if (changed) editor.apply()
    }

    fun requiresNewApp(viperValues: Map<String, *>, dynamicValues: Map<String, *>): Boolean =
        (listOf(amount) + viperAdvanced).any { viperValues.containsKey(it.key) } || dynamic.any { dynamicValues.containsKey(it.key) }

    fun dynamicPacket(values: Map<String, *>): IntArray = dynamic.map { value(values, it) }.toIntArray().also {
        // Rejecting corrupt profile state at the driver is too late: normalize the two bounds together.
        if (it[1] >= it[2]) { it[1] = trackLow.default; it[2] = trackHigh.default }
    }

    fun acceptsDynamic(values: Map<String, *>, control: Control, candidate: Int): Boolean {
        if (candidate !in control.min..control.max) return false
        val packet = dynamicPacket(values)
        return when (control.key) {
            trackLow.key -> candidate < packet[2]
            trackHigh.key -> candidate > packet[1]
            else -> true
        }
    }
}
