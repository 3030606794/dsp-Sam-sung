package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences
import kotlin.math.roundToInt

/** Versioned integer units: legacy keys retain their original meanings on preset import. */
object HighFrequencyParameters {
    const val CLARITY_PACKET = 65600
    const val SPECTRUM_PACKET = 65601
    const val CLARITY_AMOUNT = "v_clarity_amount_hundredths"
    const val SPECTRUM_AMOUNT = "v_spectrum_amount_tenths"
    const val LP_AUTO = "v_spectrum_lowpass_auto"
    val clarityAmount = BassParameters.Control(CLARITY_AMOUNT, "增强系数", 0, 450, 0, 100, decimals = 2)
    val shelfFrequency = BassParameters.Control("v_clarity_shelf_hz", "高频转折频率", 2000, 16000, 8250, unit = "Hz", advanced = true)
    val shelfSlope = BassParameters.Control("v_clarity_shelf_slope", "过渡斜率 S", 25, 100, 100, 100, decimals = 2, advanced = true)
    val lowFrequency = BassParameters.Control("v_clarity_low_hz", "低／中分界频率", 60, 400, 120, unit = "Hz", advanced = true)
    val highFrequency = BassParameters.Control("v_clarity_high_hz", "中／高分界频率", 500, 5000, 1200, unit = "Hz", advanced = true)
    val lowTrim = BassParameters.Control("v_clarity_low_trim", "低频增益修正", -120, 60, 0, 10, "dB", 1, advanced = true)
    val midTrim = BassParameters.Control("v_clarity_mid_trim", "中频增益修正", -120, 60, 0, 10, "dB", 1, advanced = true)
    val highTrim = BassParameters.Control("v_clarity_high_trim", "高频增益修正", -120, 60, 0, 10, "dB", 1, advanced = true)
    val clarityOutput = BassParameters.Control("v_clarity_output_trim", "输出衰减", -120, 0, 0, 10, "dB", 1, advanced = true)
    val lowDelay = BassParameters.Control("v_clarity_low_delay_us", "低频延时", 0, 10000, 5000, 1000, "ms", 3, advanced = true)
    val midDelay = BassParameters.Control("v_clarity_mid_delay_us", "中频延时", 0, 5000, 2500, 1000, "ms", 3, advanced = true)
    val clarity = listOf(clarityAmount, shelfFrequency, shelfSlope, lowFrequency, highFrequency,
        lowTrim, midTrim, highTrim, clarityOutput, lowDelay, midDelay)

    val spectrumAmount = BassParameters.Control(SPECTRUM_AMOUNT, "激励支路强度", 0, 5600, 560, 10, "%", 1)
    val spectrumFrequency = BassParameters.Control("v_spectrum_freq", "激励起始频率", 1000, 16000, 7600, unit = "Hz")
    val highpassQ = BassParameters.Control("v_spectrum_highpass_q", "输入高通 Q", 300, 1500, 717, 1000, decimals = 3, advanced = true)
    val lowpassFrequency = BassParameters.Control("v_spectrum_lowpass_hz", "输出高频截止", 8000, 24000, 22000, unit = "Hz", advanced = true)
    val lowpassQ = BassParameters.Control("v_spectrum_lowpass_q", "输出低通 Q", 300, 1500, 717, 1000, decimals = 3, advanced = true)
    val spectrumOutput = BassParameters.Control("v_spectrum_output_trim", "输出衰减", -120, 0, 0, 10, "dB", 1, advanced = true)
    val spectrum = listOf(spectrumAmount, spectrumFrequency, highpassQ, lowpassFrequency, lowpassQ, spectrumOutput)
    val all = clarity + spectrum
    val legacyHandledKeys = setOf("v_clarity_amount", "v_spectrum_amount", "v_spectrum_freq")

    private fun integer(values: Map<String, *>, key: String): Int? =
        (values[key] as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 &&
            it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()

    fun value(values: Map<String, *>, control: BassParameters.Control): Int {
        val fallback = when (control.key) {
            CLARITY_AMOUNT -> integer(values, "v_clarity_amount")?.coerceIn(0, 9)?.times(50)
            SPECTRUM_AMOUNT -> integer(values, "v_spectrum_amount")?.coerceIn(0, 560)?.times(10)
            else -> null
        } ?: control.default
        return (integer(values, control.key) ?: fallback).coerceIn(control.min, control.max)
    }

    fun migrate(preferences: SharedPreferences) {
        val values = preferences.all
        val editor = preferences.edit()
        var changed = false
        all.forEach { control ->
            val hasLegacy = (control.key == CLARITY_AMOUNT && values.containsKey("v_clarity_amount")) ||
                (control.key == SPECTRUM_AMOUNT && values.containsKey("v_spectrum_amount"))
            if (values.containsKey(control.key) || hasLegacy) {
                val normalized = value(values, control)
                if (values[control.key] != normalized) { editor.putInt(control.key, normalized); changed = true }
            }
        }
        if (values.containsKey(LP_AUTO) && values[LP_AUTO] !is Boolean) {
            editor.putBoolean(LP_AUTO, true); changed = true
        }
        if (values.containsKey("v_clarity_mode")) {
            val mode = (integer(values, "v_clarity_mode") ?: 0).coerceIn(0, 2)
            if (values["v_clarity_mode"] != mode) { editor.putInt("v_clarity_mode", mode); changed = true }
        }
        if (changed) editor.apply()
        HighFrequencyAdvancedParameters.migrate(preferences)
    }

    fun clarityPacket(values: Map<String, *>): IntArray = clarity.map { value(values, it) }.toIntArray()
    fun automaticLowpass(values: Map<String, *>): Boolean = values[LP_AUTO] as? Boolean ?: true
    fun spectrumPacket(values: Map<String, *>, audition: Boolean = false): IntArray =
        (spectrum.map { if (it == lowpassFrequency && automaticLowpass(values)) 0 else value(values, it) } +
            if (audition) 1 else 0).toIntArray()
    fun legacySpectrumAmount(values: Map<String, *>): Int = (value(values, spectrumAmount) / 10.0).roundToInt()
    fun requiresNewApp(values: Map<String, *>): Boolean =
        all.any { it.key != spectrumFrequency.key && values.containsKey(it.key) } || values.containsKey(LP_AUTO) ||
            (integer(values, spectrumFrequency.key)?.let { it !in 3000..12000 } == true)

    /** The driver's truncation preserves the original fs/200 and fs/400 defaults. */
    fun delaySamples(microseconds: Int, sampleRate: Int): Int = (microseconds.toLong() * sampleRate / 1_000_000L).toInt()
    fun delayMicroseconds(samples: Int, sampleRate: Int): Int =
        ((samples.toLong() * 1_000_000L + sampleRate - 1) / sampleRate).toInt()
}
