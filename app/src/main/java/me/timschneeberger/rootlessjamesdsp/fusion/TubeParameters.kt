package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Original ViPER tube softening is the bypass baseline for every new processing group. */
object TubeParameters {
    const val NAMESPACE = ViperParameters.PREFS
    const val CAPABILITY = 33554432
    const val PACKET = 65649
    const val TOOLS = 65650
    const val CLEAR = 65651
    const val RUNTIME = 131225
    const val ENABLED = "v_tube"
    const val PROFESSIONAL = "v_tube_pro_on"
    const val SAT_ON = "v_tube_pro_sat_on"
    const val TONE_ON = "v_tube_pro_tone_on"
    const val MIX_ON = "v_tube_pro_mix_on"
    const val OUTPUT_ON = "v_tube_pro_output_on"
    const val QUALITY_ON = "v_tube_pro_quality_on"
    const val METERS = "v_tube_pro_meters_on"
    const val FACTOR = "v_tube_pro_factor"
    val drive = BassParameters.Control("v_tube_pro_drive_tenths", "饱和驱动", 0, 180, 0, 10, "dB", 1)
    val softness = BassParameters.Control("v_tube_pro_softness_tenths", "饱和软硬度", 0, 1000, 500, 10, "%", 1)
    val asym = BassParameters.Control("v_tube_pro_asym_tenths", "饱和非对称度", -1000, 1000, 0, 10, "%", 1)
    val softenMix = BassParameters.Control("v_tube_pro_soften_mix_tenths", "原版柔化比例", 0, 1000, 1000, 10, "%", 1)
    val softenCut = BassParameters.Control("v_tube_pro_soften_cut_hz", "柔化低通频率", 0, 20000, 0, unit = "Hz")
    val preHP = BassParameters.Control("v_tube_pro_pre_hp_hz", "饱和前低切", 0, 1000, 0, unit = "Hz")
    val postLP = BassParameters.Control("v_tube_pro_post_lp_hz", "处理后高切", 0, 20000, 0, unit = "Hz")
    val mix = BassParameters.Control("v_tube_pro_mix_tenths", "干湿混合比例", 0, 1000, 1000, 10, "%", 1)
    val output = BassParameters.Control("v_tube_pro_output_tenths_db", "电子管输出增益", -240, 0, 0, 10, "dB", 1)
    val satNumbers = listOf(drive, softness, asym)
    val toneNumbers = listOf(softenMix, softenCut, preHP, postLP)
    val numbers = satNumbers + toneNumbers + mix + output
    val switches = setOf(PROFESSIONAL, SAT_ON, TONE_ON, MIX_ON, OUTPUT_ON, QUALITY_ON, METERS)
    val newKeys = numbers.map { it.key }.toSet() + switches + FACTOR
    val keys get() = newKeys
    fun on(v: Map<String, *>, key: String) = v[key] as? Boolean ?: false
    fun enabled(v: Map<String, *>) = on(v, PROFESSIONAL)
    fun moduleEnabled(v: Map<String, *>) = on(v, ENABLED)
    fun minimumFrequency(c: BassParameters.Control) = when (c.key) { softenCut.key, preHP.key -> 20; postLP.key -> 1000; else -> 0 }
    fun validValue(c: BassParameters.Control, value: Int) = value in c.min..c.max &&
        (minimumFrequency(c) == 0 || value == 0 || value >= minimumFrequency(c))
    fun value(v: Map<String, *>, c: BassParameters.Control) = (v[c.key] as? Int)?.takeIf { validValue(c, it) } ?: c.default
    fun factor(v: Map<String, *>) = (v[FACTOR] as? Int)?.takeIf { it == 2 || it == 4 } ?: 2
    data class Settings(
        val professional: Boolean = false, val satOn: Boolean = false, val drive: Int = 0,
        val softness: Int = 500, val asym: Int = 0, val toneOn: Boolean = false,
        val softenMix: Int = 1000, val softenCut: Int = 0, val preHP: Int = 0, val postLP: Int = 0,
        val mixOn: Boolean = false, val mix: Int = 1000, val outputOn: Boolean = false, val output: Int = 0,
        val qualityOn: Boolean = false, val factor: Int = 2, val meters: Boolean = false,
    ) {
        private fun numericValues() = listOf(drive, softness, asym, softenMix, softenCut, preHP, postLP, mix, output)
        fun valid() = numericValues().zip(numbers).all { (v, c) -> validValue(c, v) } && factor in listOf(2, 4)
        fun packet() = intArrayOf(1, flag(professional), flag(satOn), drive, softness, asym, flag(toneOn), softenMix,
            softenCut, preHP, postLP, flag(mixOn), mix, flag(outputOn), output, flag(qualityOn), factor, flag(meters), 0, 0)
        fun values(): Map<String, Any> = buildMap {
            numbers.zip(numericValues()).forEach { (c, v) -> put(c.key, v) }
            put(PROFESSIONAL, professional); put(SAT_ON, satOn); put(TONE_ON, toneOn); put(MIX_ON, mixOn)
            put(OUTPUT_ON, outputOn); put(QUALITY_ON, qualityOn); put(METERS, meters); put(FACTOR, factor)
        }
    }
    private fun flag(value: Boolean) = if (value) 1 else 0
    fun defaults() = Settings()
    fun capture(v: Map<String, *>) = Settings(enabled(v), on(v, SAT_ON), value(v, drive), value(v, softness), value(v, asym),
        on(v, TONE_ON), value(v, softenMix), value(v, softenCut), value(v, preHP), value(v, postLP),
        on(v, MIX_ON), value(v, mix), on(v, OUTPUT_ON), value(v, output), on(v, QUALITY_ON), factor(v), on(v, METERS))
    fun packet(v: Map<String, *>) = capture(v).packet()
    fun apply(p: SharedPreferences, s: Settings) {
        require(s.valid()); val edit = p.edit()
        s.values().forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
        } }; edit.apply()
    }
    fun setProfessional(p: SharedPreferences, on: Boolean) { p.edit().putBoolean(PROFESSIONAL, on).apply() }
    fun normalize(p: SharedPreferences) {
        val v = p.all; val changes = capture(v).values().filter { (key, value) -> v.containsKey(key) && v[key] != value }
        if (changes.isEmpty()) return
        val edit = p.edit(); changes.forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
        } }; edit.apply()
    }
    data class Runtime(val data: List<Int>) {
        val mode get() = data[5]
        val measurement get() = data[7]
        val epoch get() = data[11]
        val enabled get() = data[3] == 1
        val professional get() = data[4] == 1
        val ready get() = data[25] == 1
    }
    fun decode(v: IntArray): Runtime? = v.takeIf {
        it.size == 32 && it[0] == 1 && it[1] >= 0 && it[1] and 1 == 0 && (it[2] == 0 || it[2] in 8000..768000) &&
            listOf(3, 4, 6, 12, 25).all { i -> it[i] in 0..1 } && it[5] in 0..6 && it[7] in 0..4 &&
            it[8] in 0..2000 && it[9] in -18000..0 && it[10] in -18000..0 && it[11] >= 0 && it[13] in listOf(1, 2, 4) &&
            it[14] in 0..64 && it[15] in 0..(it[2] / 2) && (16..23).all { i -> it[i] == Int.MIN_VALUE || it[i] in -20000..20000 } &&
            it[24] >= 0 && it[26] in 0..1000 && it[27] in 0..(it[2] / 2) &&
            it[28] in 0..60000 && it[29] in 0..60000 && it[30] == 2000 && it[31] == 0
    }?.let { Runtime(it.toList()) }
}
