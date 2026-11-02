package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/** Fine controls for the four coloring branches, separate from the saved legacy preamplifier. */
object TimbreParameters {
    const val NAMESPACE = Constants.PREF_TUBE
    const val CAPABILITY = 134217728
    const val EXTENDED_CAPABILITY = 536870912
    const val PACKET = 1427
    const val TOOLS = 1428
    const val CLEAR = 1429
    const val RUNTIME = 131227
    const val ENABLED = "tube_enable"
    const val LEGACY = "tube_drive"
    const val PROFESSIONAL = "timbre_pro_on"
    const val CORE_ON = "timbre_pro_core_on"
    const val TONE_ON = "timbre_pro_tone_on"
    const val DC_ON = "timbre_pro_dc_on"
    const val MIX_ON = "timbre_pro_mix_on"
    const val OUTPUT_ON = "timbre_pro_output_on"
    const val COMP_ON = "timbre_pro_comp_on"
    const val QUALITY_ON = "timbre_pro_quality_on"
    const val METERS = "timbre_pro_meters_on"
    const val FACTOR = "timbre_pro_factor"
    val drive = BassParameters.Control("timbre_pro_drive_tenths", "染色驱动", -30, 120, 0, 10, "dB", 1)
    val amount = BassParameters.Control("timbre_pro_amount_tenths", "总染色强度", 0, 10000, 1000, 10, "%", 1)
    val band1 = BassParameters.Control("timbre_pro_band1_tenths", "第一路染色强度", 0, 2000, 1000, 10, "%", 1)
    val band2 = BassParameters.Control("timbre_pro_band2_tenths", "第二路染色强度", 0, 2000, 1000, 10, "%", 1)
    val band3 = BassParameters.Control("timbre_pro_band3_tenths", "第三路染色强度", 0, 2000, 1000, 10, "%", 1)
    val band4 = BassParameters.Control("timbre_pro_band4_tenths", "第四路染色强度", 0, 2000, 1000, 10, "%", 1)
    val cut1 = BassParameters.Control("timbre_pro_cut1_hz", "第一分频点", 50, 20000, 600, unit = "Hz")
    val cut2 = BassParameters.Control("timbre_pro_cut2_hz", "第二分频点", 50, 20000, 1900, unit = "Hz")
    val cut3 = BassParameters.Control("timbre_pro_cut3_hz", "第三分频点", 50, 20000, 4400, unit = "Hz")
    val cut4 = BassParameters.Control("timbre_pro_cut4_hz", "第四分频点", 50, 20000, 8000, unit = "Hz")
    val cut5 = BassParameters.Control("timbre_pro_cut5_hz", "第五分频点", 50, 20000, 12000, unit = "Hz")
    val colorHP = BassParameters.Control("timbre_pro_color_hp_hz", "染色支路低切", 0, 1000, 0, unit = "Hz")
    val colorLP = BassParameters.Control("timbre_pro_color_lp_hz", "染色支路高切", 0, 20000, 0, unit = "Hz")
    val dcHz = BassParameters.Control("timbre_pro_dc_hz_tenths", "直流消除截止频率", 10, 300, 50, 10, "Hz", 1)
    val mix = BassParameters.Control("timbre_pro_mix_tenths", "干湿混合比例", 0, 1000, 1000, 10, "%", 1)
    val output = BassParameters.Control("timbre_pro_output_tenths_db", "模块输出增益", -240, 0, 0, 10, "dB", 1)
    val comp = BassParameters.Control("timbre_pro_comp_tenths", "驱动增益补偿比例", 0, 1000, 1000, 10, "%", 1)
    val coreNumbers = listOf(drive, amount, band1, band2, band3, band4)
    val cuts = listOf(cut1, cut2, cut3, cut4, cut5)
    val toneNumbers = cuts + colorHP + colorLP + dcHz
    val numbers = coreNumbers + toneNumbers + mix + output + comp
    val switches = linkedSetOf(PROFESSIONAL, CORE_ON, TONE_ON, DC_ON, MIX_ON, OUTPUT_ON, COMP_ON, QUALITY_ON, METERS)
    val newKeys = numbers.map { it.key }.toSet() + switches + FACTOR
    val keys get() = newKeys
    fun on(v: Map<String, *>, key: String) = v[key] as? Boolean ?: false
    fun enabled(v: Map<String, *>) = on(v, PROFESSIONAL)
    fun moduleEnabled(v: Map<String, *>) = on(v, ENABLED)
    fun validValue(c: BassParameters.Control, v: Int) = v in c.min..c.max && when (c.key) {
        colorHP.key -> v == 0 || v >= 20
        colorLP.key -> v == 0 || v >= 1000
        else -> true
    }
    fun value(v: Map<String, *>, c: BassParameters.Control) = (v[c.key] as? Int)?.takeIf { validValue(c, it) } ?: c.default
    fun factor(v: Map<String, *>) = (v[FACTOR] as? Int)?.takeIf { it == 2 || it == 4 } ?: 2
    fun legacy(v: Map<String, *>) = (v[LEGACY] as? Float)?.takeIf { it.isFinite() && it in -3f..12f } ?: 2f
    data class Settings(
        val professional: Boolean = false, val coreOn: Boolean = false, val drive: Int = 0, val amount: Int = 1000,
        val band1: Int = 1000, val band2: Int = 1000, val band3: Int = 1000, val band4: Int = 1000,
        val toneOn: Boolean = false, val cut1: Int = 600, val cut2: Int = 1900, val cut3: Int = 4400,
        val cut4: Int = 8000, val cut5: Int = 12000, val colorHP: Int = 0, val colorLP: Int = 0,
        val dcOn: Boolean = false, val dcHz: Int = 50, val mixOn: Boolean = false, val mix: Int = 1000,
        val outputOn: Boolean = false, val output: Int = 0, val compOn: Boolean = false, val comp: Int = 1000,
        val qualityOn: Boolean = false, val factor: Int = 2, val meters: Boolean = false,
    ) {
        private fun numericValues() = listOf(drive, amount, band1, band2, band3, band4, cut1, cut2, cut3, cut4, cut5,
            colorHP, colorLP, dcHz, mix, output, comp)
        fun valid() = numericValues().zip(numbers).all { (v, c) -> validValue(c, v) } &&
            listOf(cut1, cut2, cut3, cut4, cut5).zipWithNext().all { (a, b) -> b - a >= 50 } &&
            (colorHP == 0 || colorLP == 0 || colorHP < colorLP) && factor in listOf(2, 4)
        fun packet() = intArrayOf(1, flag(professional), flag(coreOn), drive, amount, band1, band2, band3, band4,
            flag(toneOn), cut1, cut2, cut3, cut4, cut5, colorHP, colorLP, flag(dcOn), dcHz, flag(mixOn), mix,
            flag(outputOn), output, flag(compOn), comp, flag(qualityOn), factor, flag(meters), 0, 0, 0, 0)
        fun values(): Map<String, Any> = buildMap {
            numbers.zip(numericValues()).forEach { (c, v) -> put(c.key, v) }
            put(PROFESSIONAL, professional); put(CORE_ON, coreOn); put(TONE_ON, toneOn); put(DC_ON, dcOn)
            put(MIX_ON, mixOn); put(OUTPUT_ON, outputOn); put(COMP_ON, compOn); put(QUALITY_ON, qualityOn)
            put(METERS, meters); put(FACTOR, factor)
        }
    }
    private fun flag(value: Boolean) = if (value) 1 else 0
    fun defaults() = Settings()
    fun capture(v: Map<String, *>) : Settings {
        var s = Settings(enabled(v), on(v, CORE_ON), value(v, drive), value(v, amount), value(v, band1), value(v, band2),
            value(v, band3), value(v, band4), on(v, TONE_ON), value(v, cut1), value(v, cut2), value(v, cut3), value(v, cut4),
            value(v, cut5), value(v, colorHP), value(v, colorLP), on(v, DC_ON), value(v, dcHz), on(v, MIX_ON), value(v, mix),
            on(v, OUTPUT_ON), value(v, output), on(v, COMP_ON), value(v, comp), on(v, QUALITY_ON), factor(v), on(v, METERS))
        if (!listOf(s.cut1, s.cut2, s.cut3, s.cut4, s.cut5).zipWithNext().all { (a, b) -> b - a >= 50 })
            s = s.copy(cut1 = cut1.default, cut2 = cut2.default, cut3 = cut3.default, cut4 = cut4.default, cut5 = cut5.default)
        if (s.colorHP != 0 && s.colorLP != 0 && s.colorHP >= s.colorLP) s = s.copy(colorHP = 0, colorLP = 0)
        return s
    }
    fun candidateValid(values: Map<String, *>, control: BassParameters.Control, next: Int): Boolean {
        if (!validValue(control, next)) return false
        val merged = buildMap<String, Any?> { values.forEach { (key, value) -> put(key, value) }; put(control.key, next) }
        val points = cuts.map { value(merged, it) }
        val lo = value(merged, colorHP); val hi = value(merged, colorLP)
        return points.zipWithNext().all { (a, b) -> b - a >= 50 } && (lo == 0 || hi == 0 || lo < hi)
    }
    fun packet(v: Map<String, *>) = capture(v).packet()
    fun apply(p: SharedPreferences, s: Settings) {
        require(s.valid()); val edit = p.edit()
        s.values().forEach { (key, value) -> when (value) { is Boolean -> edit.putBoolean(key, value); is Int -> edit.putInt(key, value) } }
        edit.apply()
    }
    fun setProfessional(p: SharedPreferences, on: Boolean) { p.edit().putBoolean(PROFESSIONAL, on).apply() }
    fun normalize(p: SharedPreferences) {
        val v = p.all; val changes = capture(v).values().filter { (key, value) -> v.containsKey(key) && v[key] != value }
        if (changes.isEmpty()) return
        val edit = p.edit(); changes.forEach { (key, value) -> when (value) { is Boolean -> edit.putBoolean(key, value); is Int -> edit.putInt(key, value) } }; edit.apply()
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
        it.size == 48 && it[0] == 1 && it[1] >= 0 && it[1] and 1 == 0 && (it[2] == 0 || it[2] in 8000..768000) &&
            listOf(3, 4, 6, 12, 25, 45).all { i -> it[i] in 0..1 } && it[5] in 0..11 && it[7] in 0..4 &&
            it[8] in 0..2000 && it[9] in -18000..0 && it[10] in -18000..0 && it[11] >= 0 && it[13] in 1..5 &&
            it[14] in 0..128 && it[15] in -30..120 && (16..23).all { i -> it[i] == Int.MIN_VALUE || it[i] in -20000..20000 } &&
            it[24] >= 0 && (it[26] == 0 || it[26] in 8000..768000) &&
            (27..28).all { i -> it[i] == Int.MIN_VALUE || it[i] in -1000000000..1000000000 } &&
            it[29] in 0..60000 && it[30] in 0..60000 && it[31] == 2000 &&
            (32..38).all { i -> it[i] in 0..(it[2] / 2) } && (it[39] == 0 || it[39] in 10..300) &&
            (40..43).all { i -> it[i] == Int.MIN_VALUE || it[i] in -20000..20000 } &&
            it[44] in 0..1000 && it[46] in 0..180 && (it[5] in 7..11 || it[46] == 0) && it[47] == 0
    }?.let { Runtime(it.toList()) }
}
