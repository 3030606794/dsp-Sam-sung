package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** The original three analog modes remain the baseline; harmonic offsets are stored in ppm. */
object AnalogParameters {
    const val NAMESPACE = ViperParameters.PREFS
    const val CAPABILITY = 67108864
    const val PACKET = 65652
    const val TOOLS = 65653
    const val CLEAR = 65654
    const val RUNTIME = 131226
    const val ENABLED = "v_analog"
    const val BASE = "v_analog_mode"
    const val PROFESSIONAL = "v_analog_pro_on"
    const val CORE_ON = "v_analog_pro_core_on"
    const val TONE_ON = "v_analog_pro_tone_on"
    const val MIX_ON = "v_analog_pro_mix_on"
    const val OUTPUT_ON = "v_analog_pro_output_on"
    const val QUALITY_ON = "v_analog_pro_quality_on"
    const val METERS = "v_analog_pro_meters_on"
    const val FACTOR = "v_analog_pro_factor"
    val drive = BassParameters.Control("v_analog_pro_drive_tenths", "输入驱动修正", -120, 180, 0, 10, "dB", 1)
    val amount = BassParameters.Control("v_analog_pro_amount_tenths", "染色注入倍率", 0, 2000, 1000, 10, "%", 1)
    val h2 = BassParameters.Control("v_analog_pro_h2_delta_ppm", "二次谐波系数偏移", -50000, 50000, 0, 1000000, decimals = 6)
    val h3 = BassParameters.Control("v_analog_pro_h3_delta_ppm", "三次谐波系数偏移", -50000, 50000, 0, 1000000, decimals = 6)
    val h4 = BassParameters.Control("v_analog_pro_h4_delta_ppm", "四次谐波系数偏移", -50000, 50000, 0, 1000000, decimals = 6)
    val h5 = BassParameters.Control("v_analog_pro_h5_delta_ppm", "五次谐波系数偏移", -50000, 50000, 0, 1000000, decimals = 6)
    val hp = BassParameters.Control("v_analog_pro_hp_hz", "染色前低切", 20, 4000, 240, unit = "Hz")
    val hpQ = BassParameters.Control("v_analog_pro_hp_q_milli", "低切 Q 值", 300, 2000, 717, 1000, decimals = 3)
    val lpDelta = BassParameters.Control("v_analog_pro_lp_delta_hz", "高切频率偏移", -15000, 4000, 0, unit = "Hz")
    val lpQ = BassParameters.Control("v_analog_pro_lp_q_milli", "高切 Q 值", 300, 2000, 717, 1000, decimals = 3)
    val peak = BassParameters.Control("v_analog_pro_peak_hz", "音色补偿中心频率", 20, 4000, 633, unit = "Hz")
    val peakGain = BassParameters.Control("v_analog_pro_peak_gain_centi_db", "音色补偿增益", -600, 600, 58, 100, "dB", 2)
    val peakBW = BassParameters.Control("v_analog_pro_peak_bw_milli_oct", "音色补偿带宽", 100, 8000, 6280, 1000, "oct", 3)
    val mix = BassParameters.Control("v_analog_pro_mix_tenths", "干湿混合比例", 0, 1000, 1000, 10, "%", 1)
    val output = BassParameters.Control("v_analog_pro_output_tenths_db", "染色输出增益", -240, 0, 0, 10, "dB", 1)
    val coreNumbers = listOf(drive, amount, h2, h3, h4, h5)
    val toneNumbers = listOf(hp, hpQ, lpDelta, lpQ, peak, peakGain, peakBW)
    val numbers = coreNumbers + toneNumbers + mix + output
    val switches = setOf(PROFESSIONAL, CORE_ON, TONE_ON, MIX_ON, OUTPUT_ON, QUALITY_ON, METERS)
    val newKeys = numbers.map { it.key }.toSet() + switches + FACTOR
    val keys get() = newKeys + BASE
    val baseNames = listOf("轻度", "中度", "强")
    val baseLowpass = listOf(19650, 18233, 16307)
    val baseHarmonics = listOf(20000, 100, 1000, 0)
    fun on(v: Map<String, *>, key: String) = v[key] as? Boolean ?: false
    fun enabled(v: Map<String, *>) = on(v, PROFESSIONAL)
    fun moduleEnabled(v: Map<String, *>) = on(v, ENABLED)
    fun baseMode(v: Map<String, *>) = (v[BASE] as? Int)?.takeIf { it in 0..2 } ?: 0
    fun validValue(c: BassParameters.Control, value: Int) = value in c.min..c.max
    fun value(v: Map<String, *>, c: BassParameters.Control) = (v[c.key] as? Int)?.takeIf { validValue(c, it) } ?: c.default
    fun factor(v: Map<String, *>) = (v[FACTOR] as? Int)?.takeIf { it == 2 || it == 4 } ?: 2
    data class Settings(
        val professional: Boolean = false, val baseMode: Int = 0, val coreOn: Boolean = false,
        val drive: Int = 0, val amount: Int = 1000, val h2: Int = 0, val h3: Int = 0, val h4: Int = 0, val h5: Int = 0,
        val toneOn: Boolean = false, val hp: Int = 240, val hpQ: Int = 717, val lpDelta: Int = 0, val lpQ: Int = 717,
        val peak: Int = 633, val peakGain: Int = 58, val peakBW: Int = 6280,
        val mixOn: Boolean = false, val mix: Int = 1000, val outputOn: Boolean = false, val output: Int = 0,
        val qualityOn: Boolean = false, val factor: Int = 2, val meters: Boolean = false,
    ) {
        private fun numericValues() = listOf(drive, amount, h2, h3, h4, h5, hp, hpQ, lpDelta, lpQ, peak, peakGain, peakBW, mix, output)
        fun valid() = baseMode in 0..2 && numericValues().zip(numbers).all { (v, c) -> validValue(c, v) } && factor in listOf(2, 4)
        fun packet() = intArrayOf(1, flag(professional), baseMode, flag(coreOn), drive, amount, h2, h3, h4, h5,
            flag(toneOn), hp, hpQ, lpDelta, lpQ, peak, peakGain, peakBW, flag(mixOn), mix, flag(outputOn), output,
            flag(qualityOn), factor, flag(meters), 0, 0, 0)
        fun values(): Map<String, Any> = buildMap {
            put(BASE, baseMode); numbers.zip(numericValues()).forEach { (c, v) -> put(c.key, v) }
            put(PROFESSIONAL, professional); put(CORE_ON, coreOn); put(TONE_ON, toneOn); put(MIX_ON, mixOn)
            put(OUTPUT_ON, outputOn); put(QUALITY_ON, qualityOn); put(METERS, meters); put(FACTOR, factor)
        }
    }
    private fun flag(value: Boolean) = if (value) 1 else 0
    fun defaults(baseMode: Int = 0) = Settings(baseMode = baseMode)
    fun capture(v: Map<String, *>) = Settings(enabled(v), baseMode(v), on(v, CORE_ON), value(v, drive), value(v, amount),
        value(v, h2), value(v, h3), value(v, h4), value(v, h5), on(v, TONE_ON), value(v, hp), value(v, hpQ),
        value(v, lpDelta), value(v, lpQ), value(v, peak), value(v, peakGain), value(v, peakBW), on(v, MIX_ON), value(v, mix),
        on(v, OUTPUT_ON), value(v, output), on(v, QUALITY_ON), factor(v), on(v, METERS))
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
        val baseMode get() = data[31]
    }
    fun decode(v: IntArray): Runtime? = v.takeIf {
        it.size == 40 && it[0] == 1 && it[1] >= 0 && it[1] and 1 == 0 && (it[2] == 0 || it[2] in 8000..768000) &&
            listOf(3, 4, 6, 12, 25).all { i -> it[i] in 0..1 } && it[5] in 0..6 && it[7] in 0..4 &&
            it[8] in 0..2000 && it[9] in -18000..0 && it[10] in -18000..0 && it[11] >= 0 && it[13] in listOf(1, 2, 4) &&
            it[14] in 0..64 && listOf(15, 26, 27).all { i -> it[i] in 0..(it[2] / 2) } &&
            (16..23).all { i -> it[i] == Int.MIN_VALUE || it[i] in -20000..20000 } && it[24] >= 0 &&
            it[28] in 0..60000 && it[29] in 0..60000 && it[30] == 2000 && it[31] in 0..2 &&
            (32..35).all { i -> it[i] in -100000..100000 } && (36..39).all { i -> it[i] == 0 }
    }?.let { Runtime(it.toList()) }
}
