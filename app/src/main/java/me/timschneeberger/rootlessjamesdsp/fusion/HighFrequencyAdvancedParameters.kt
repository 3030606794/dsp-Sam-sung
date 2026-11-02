package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Second-generation controls are separate from the first-generation packets and capability. */
object HighFrequencyAdvancedParameters {
    const val CAPABILITY = 128
    const val NATURAL_PACKET = 65604
    const val NONLINEAR_PACKET = 65605
    const val DYNAMIC_PACKET = 65606
    const val DEESS_PACKET = 65607
    const val NATURAL_ENABLED = "v_clarity_selective_enabled"
    const val DYNAMIC_ENABLED = "v_clarity_dynamic_enabled"
    const val DEESS_ENABLED = "v_clarity_deess_enabled"
    const val OVERSAMPLING = "v_spectrum_oversampling"

    val naturalLow = hz("v_clarity_selective_low_hz", "锐化频带下限", 1000, 12000, 3000)
    val naturalHigh = hz("v_clarity_selective_high_hz", "锐化频带上限", 2000, 20000, 12000)
    val naturalQ = q("v_clarity_selective_q", "选频滤波 Q", 707)
    val natural = listOf(naturalLow, naturalHigh, naturalQ)

    val drive = db("v_spectrum_drive", "非线性输入驱动", -120, 180, 0)
    val evenMix = BassParameters.Control("v_spectrum_even_mix", "偶次谐波混合", 0, 100, 0, unit = "%", advanced = true)
    val highOrder = BassParameters.Control("v_spectrum_high_order", "高阶谐波比例", 0, 100, 100, unit = "%", advanced = true)
    val nonlinear = listOf(drive, evenMix, highOrder)

    val dynamicFrequency = hz("v_clarity_dynamic_hz", "抑制频段起点", 1000, 16000, 6000)
    val dynamicQ = q("v_clarity_dynamic_q", "高频检测滤波 Q", 707)
    val dynamicThreshold = db("v_clarity_dynamic_threshold", "高频峰值阈值", -600, 0, -180).copy(unit = "dBFS")
    val dynamicRatio = ratio("v_clarity_dynamic_ratio", 40)
    val dynamicMaximum = db("v_clarity_dynamic_maximum", "最大高频平台衰减", 0, 180, 60)
    val dynamicAttack = BassParameters.Control("v_clarity_dynamic_attack", "高频抑制启动", 1, 1000, 50, 10, "ms", 1, advanced = true)
    val dynamicRelease = BassParameters.Control("v_clarity_dynamic_release", "高频抑制恢复", 5, 2000, 120, unit = "ms", advanced = true)
    val dynamic = listOf(dynamicFrequency, dynamicQ, dynamicThreshold, dynamicRatio, dynamicMaximum, dynamicAttack, dynamicRelease)

    val deessLow = hz("v_clarity_deess_low_hz", "齿音频带下限", 2000, 12000, 4500)
    val deessHigh = hz("v_clarity_deess_high_hz", "齿音频带上限", 3000, 20000, 10000)
    val deessQ = q("v_clarity_deess_q", "齿音检测滤波 Q", 707)
    val deessThreshold = db("v_clarity_deess_threshold", "齿音峰值阈值", -600, 0, -240).copy(unit = "dBFS")
    val deessRatio = ratio("v_clarity_deess_ratio", 60)
    val deessMaximum = db("v_clarity_deess_maximum", "最大齿音中心衰减", 0, 180, 60)
    val deessAttack = BassParameters.Control("v_clarity_deess_attack", "去齿音启动", 1, 100, 10, 10, "ms", 1, advanced = true)
    val deessRelease = BassParameters.Control("v_clarity_deess_release", "去齿音恢复", 5, 500, 80, unit = "ms", advanced = true)
    val deess = listOf(deessLow, deessHigh, deessQ, deessThreshold, deessRatio, deessMaximum, deessAttack, deessRelease)
    val clarity = natural + dynamic + deess
    val all = clarity + nonlinear
    val switches = listOf(NATURAL_ENABLED, DYNAMIC_ENABLED, DEESS_ENABLED)
    val allKeys = all.map { it.key }.toSet() + switches + OVERSAMPLING
    val frequencyPairs = listOf(naturalLow to naturalHigh, deessLow to deessHigh)

    private fun hz(key: String, title: String, min: Int, max: Int, default: Int) =
        BassParameters.Control(key, title, min, max, default, unit = "Hz", advanced = true)
    private fun q(key: String, title: String, default: Int) =
        BassParameters.Control(key, title, 300, 1500, default, 1000, decimals = 3, advanced = true)
    private fun db(key: String, title: String, min: Int, max: Int, default: Int) =
        BassParameters.Control(key, title, min, max, default, 10, "dB", 1, advanced = true)
    private fun ratio(key: String, default: Int) =
        BassParameters.Control(key, "压缩比", 10, 200, default, 10, ": 1", 1, advanced = true)

    private fun integer(values: Map<String, *>, key: String): Int? =
        (values[key] as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 &&
            it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
    private fun rawValue(values: Map<String, *>, control: BassParameters.Control): Int =
        (integer(values, control.key) ?: control.default).coerceIn(control.min, control.max)
    fun value(values: Map<String, *>, control: BassParameters.Control): Int {
        val pair = frequencyPairs.firstOrNull { control == it.first || control == it.second }
        // Imported invalid pairs recover together. The UI and packets use the same canonical pair.
        if (pair != null && rawValue(values, pair.first) >= rawValue(values, pair.second)) return control.default
        return rawValue(values, control)
    }
    fun enabled(values: Map<String, *>, key: String) = values[key] as? Boolean ?: false
    fun oversampling(values: Map<String, *>) = integer(values, OVERSAMPLING)?.takeIf { it in listOf(1, 2, 4) } ?: 1
    fun acceptsFrequency(values: Map<String, *>, key: String, candidate: Int): Boolean = frequencyPairs.all { (low, high) ->
        when (key) { low.key -> candidate < value(values, high); high.key -> candidate > value(values, low); else -> true }
    }
    fun migrate(preferences: SharedPreferences) {
        val values = preferences.all
        val editor = preferences.edit()
        var changed = false
        all.forEach { control ->
            if (values.containsKey(control.key)) {
                val normalized = value(values, control)
                if (values[control.key] != normalized) { editor.putInt(control.key, normalized); changed = true }
            }
        }
        // If only one member was imported and it crosses the other's default, persist both repaired bounds.
        frequencyPairs.forEach { (low, high) ->
            if ((values.containsKey(low.key) || values.containsKey(high.key)) && rawValue(values, low) >= rawValue(values, high)) {
                editor.putInt(low.key, low.default).putInt(high.key, high.default); changed = true
            }
        }
        switches.forEach { key -> if (values.containsKey(key) && values[key] !is Boolean) {
            editor.putBoolean(key, false); changed = true
        } }
        if (values.containsKey(OVERSAMPLING) && values[OVERSAMPLING] != oversampling(values)) {
            editor.putInt(OVERSAMPLING, oversampling(values)); changed = true
        }
        if (changed) editor.apply()
    }
    private fun packet(values: Map<String, *>, key: String, controls: List<BassParameters.Control>) =
        (listOf(if (enabled(values, key)) 1 else 0) + controls.map { value(values, it) }).toIntArray()
    fun naturalPacket(values: Map<String, *>) = packet(values, NATURAL_ENABLED, natural)
    fun nonlinearPacket(values: Map<String, *>) = (nonlinear.map { value(values, it) } + oversampling(values)).toIntArray()
    fun dynamicPacket(values: Map<String, *>) = packet(values, DYNAMIC_ENABLED, dynamic)
    fun deessPacket(values: Map<String, *>) = packet(values, DEESS_ENABLED, deess)
    fun requiresNewApp(values: Map<String, *>) = allKeys.any { values.containsKey(it) }
}
