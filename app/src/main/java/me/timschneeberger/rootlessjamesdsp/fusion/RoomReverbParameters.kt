package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/** Room types remain the original SF reverb; additional groups are independent and opt in. */
object RoomReverbParameters {
    const val NAMESPACE = Constants.PREF_REVERB
    const val CAPABILITY = 16777216
    const val PACKET = 1424
    const val TOOLS = 1425
    const val RUNTIME = 131224
    const val ENABLED = "reverb_enable"
    const val BASE = "reverb_preset"
    const val PROFESSIONAL = "room_reverb_pro_on"
    const val TIME_ON = "room_reverb_time_on"
    const val PREDELAY_ON = "room_reverb_predelay_on"
    const val LEVELS_ON = "room_reverb_levels_on"
    const val LINKED = "room_reverb_linked_on"
    const val WIDTHS_ON = "room_reverb_widths_on"
    const val TONE_ON = "room_reverb_tone_on"
    const val OUTPUT_ON = "room_reverb_output_on"
    const val METERS = "room_reverb_meters_on"
    val baseIds = listOf(0, 1, 2, 4, 5, 7, 8, 9, 11, 15, 16, 17, 18)
    val bases get() = baseIds
    private val names = listOf("默认", "小厅一", "小厅二", "中厅", "大厅", "小型房间一", "小型房间二", "中型房间", "大型房间", "板式高频", "板式低频", "长混响一", "长混响二")
    fun baseName(base: Int) = names.getOrElse(baseIds.indexOf(base)) { names[9] }
    val rt60 = BassParameters.Control("room_reverb_rt60_cs", "尾音参考时间 RT60", 10, 3000, 180, 100, "s", 2)
    val earlyScale = BassParameters.Control("room_reverb_early_scale_pct", "早反射时间尺度", 50, 250, 100, 100, "×", 2)
    val predelay = BassParameters.Control("room_reverb_predelay_tenths_ms", "附加湿声预延迟", 0, 2000, 0, 10, "ms", 1)
    val dryTrim = BassParameters.Control("room_reverb_dry_trim_tenths_db", "干声相对房型增益", -240, 120, 0, 10, "dB", 1)
    val earlyTrim = BassParameters.Control("room_reverb_early_trim_tenths_db", "早反射相对房型增益", -240, 120, 0, 10, "dB", 1)
    val lateTrim = BassParameters.Control("room_reverb_late_trim_tenths_db", "尾音相对房型增益", -240, 120, 0, 10, "dB", 1)
    val mix = BassParameters.Control("room_reverb_mix_tenths_pct", "联动干湿比例", 0, 1000, 0, 10, "%", 1)
    val earlyWidth = BassParameters.Control("room_reverb_early_width_thousandths", "早反射立体声宽度", -1000, 1000, 1000, 1000, "", 3)
    val lateWidth = BassParameters.Control("room_reverb_late_width_thousandths", "尾音立体声宽度", 0, 1000, 1000, 1000, "", 3)
    val lowCut = BassParameters.Control("room_reverb_low_cut_hz", "总湿声低切", 0, 1000, 0, unit = "Hz")
    val highCut = BassParameters.Control("room_reverb_high_cut_hz", "总湿声高切", 0, 20000, 0, unit = "Hz")
    val damp = BassParameters.Control("room_reverb_damp_hz", "尾音反馈阻尼频率", 100, 20000, 16000, unit = "Hz")
    val output = BassParameters.Control("room_reverb_output_tenths_db", "场景混响输出增益", -120, 0, 0, 10, "dB", 1)
    val timeNumbers = listOf(rt60, earlyScale)
    val levelNumbers = listOf(dryTrim, earlyTrim, lateTrim)
    val widthNumbers = listOf(earlyWidth, lateWidth)
    val toneNumbers = listOf(lowCut, highCut, damp)
    val numbers = timeNumbers + predelay + levelNumbers + mix + widthNumbers + toneNumbers + output
    val switches = setOf(PROFESSIONAL, TIME_ON, PREDELAY_ON, LEVELS_ON, LINKED, WIDTHS_ON, TONE_ON, OUTPUT_ON, METERS)
    val newKeys = numbers.map { it.key }.toSet() + switches
    val keys = newKeys + BASE
    fun base(v: Map<String, *>) = (v[BASE] as? String)?.toIntOrNull()?.takeIf { it in baseIds } ?: 15
    fun on(v: Map<String, *>, key: String) = v[key] as? Boolean ?: false
    fun enabled(v: Map<String, *>) = on(v, PROFESSIONAL)
    fun moduleEnabled(v: Map<String, *>) = on(v, ENABLED)

    data class Settings(
        val base: Int = 15, val professional: Boolean = false,
        val timeOn: Boolean = false, val rt60: Int = 180, val earlyScale: Int = 100,
        val predelayOn: Boolean = false, val predelay: Int = 0,
        val levelsOn: Boolean = false, val dryTrim: Int = 0, val earlyTrim: Int = 0, val lateTrim: Int = 0,
        val linked: Boolean = false, val mix: Int = 0,
        val widthsOn: Boolean = false, val earlyWidth: Int = 1000, val lateWidth: Int = 1000,
        val toneOn: Boolean = false, val lowCut: Int = 0, val highCut: Int = 0, val damp: Int = 16000,
        val outputOn: Boolean = false, val output: Int = 0, val meters: Boolean = false,
    ) {
        private fun numericValues() = listOf(rt60, earlyScale, predelay, dryTrim, earlyTrim, lateTrim, mix, earlyWidth, lateWidth, lowCut, highCut, damp, output)
        fun valid() = base in baseIds && numericValues().zip(numbers).all { (v, c) -> v in c.min..c.max } &&
            (lowCut == 0 || highCut == 0 || lowCut < highCut)
        fun packet() = intArrayOf(1, flag(professional), base, flag(timeOn), rt60, earlyScale,
            flag(predelayOn), predelay, flag(levelsOn), dryTrim, earlyTrim, lateTrim, flag(linked), mix,
            flag(widthsOn), earlyWidth, lateWidth, flag(toneOn), lowCut, highCut, damp, flag(outputOn), output, flag(meters))
        fun values(): Map<String, Any> = buildMap {
            put(BASE, base.toString())
            numbers.zip(numericValues()).forEach { (c, v) -> put(c.key, v) }
            switches.zip(listOf(professional, timeOn, predelayOn, levelsOn, linked, widthsOn, toneOn, outputOn, meters)).forEach { (k, v) -> put(k, v) }
        }
    }
    private fun flag(value: Boolean) = if (value) 1 else 0
    /** These values come directly from the existing 19-entry SF table, restricted to the 13 UI types. */
    fun defaults(base: Int = 15, professional: Boolean = false): Settings {
        val safe = base.takeIf { it in baseIds } ?: 15
        val table = mapOf(
            0 to intArrayOf(320, 160, 700, 1000, 7000),
            1 to intArrayOf(210, 100, 700, 1000, 9000),
            2 to intArrayOf(230, 100, 700, 1000, 7000),
            4 to intArrayOf(290, 120, 700, 1000, 6000),
            5 to intArrayOf(380, 140, 700, 1000, 9000),
            7 to intArrayOf(50, 70, -400, 800, 18000),
            8 to intArrayOf(50, 80, 600, 900, 10000),
            9 to intArrayOf(80, 120, -400, 800, 18000),
            11 to intArrayOf(180, 220, -400, 900, 16000),
            15 to intArrayOf(180, 100, 1000, 1000, 16000),
            16 to intArrayOf(190, 100, 1000, 1000, 9000),
            17 to intArrayOf(1200, 100, 100, 1000, 10000),
            18 to intArrayOf(3000, 100, 100, 1000, 9000),
        ).getValue(safe)
        return Settings(base = safe, professional = professional, rt60 = table[0], earlyScale = table[1], earlyWidth = table[2], lateWidth = table[3], damp = table[4])
    }
    fun defaultValue(v: Map<String, *>, c: BassParameters.Control) = defaults(base(v)).values().getValue(c.key) as Int
    fun value(v: Map<String, *>, c: BassParameters.Control) = (v[c.key] as? Int)?.takeIf { it in c.min..c.max } ?: defaultValue(v, c)
    fun capture(v: Map<String, *>): Settings {
        val low = value(v, lowCut)
        val high = value(v, highCut).takeIf { it == 0 || low == 0 || low < it } ?: 0
        return Settings(base(v), enabled(v), on(v, TIME_ON), value(v, rt60), value(v, earlyScale),
        on(v, PREDELAY_ON), value(v, predelay), on(v, LEVELS_ON), value(v, dryTrim), value(v, earlyTrim), value(v, lateTrim),
        on(v, LINKED), value(v, mix), on(v, WIDTHS_ON), value(v, earlyWidth), value(v, lateWidth),
        on(v, TONE_ON), low, high, value(v, damp), on(v, OUTPUT_ON), value(v, output), on(v, METERS))
    }
    fun packet(v: Map<String, *>) = capture(v).packet()
    fun apply(p: SharedPreferences, s: Settings) {
        require(s.valid())
        val edit = p.edit()
        s.values().forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is String -> edit.putString(key, value)
        } }
        edit.apply()
    }
    fun selectBase(p: SharedPreferences, base: Int) {
        require(base in baseIds)
        apply(p, defaults(base, enabled(p.all)))
    }
    fun setProfessional(p: SharedPreferences, on: Boolean) {
        val v = p.all
        if (on && numbers.none { v.containsKey(it.key) }) apply(p, defaults(base(v), true))
        else p.edit().putBoolean(PROFESSIONAL, on).apply()
    }
    fun normalize(p: SharedPreferences) {
        val v = p.all
        val changes = capture(v).values().filter { (key, value) -> v.containsKey(key) && v[key] != value }
        if (changes.isEmpty()) return
        val edit = p.edit()
        changes.forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is String -> edit.putString(key, value)
        } }
        edit.apply()
    }
    data class Runtime(val data: List<Int>) {
        val mode get() = data[5]
        val measurement get() = data[7]
        val epoch get() = data[11]
        val enabled get() = data[3] == 1
        val professional get() = data[4] == 1
        val ready get() = data[21] == 1
        val stabilityProtected get() = data[26] == 1
        val bassFeedbackMillionths get() = data[27]
    }
    fun decode(v: IntArray): Runtime? = v.takeIf {
        it.size == 32 && it[0] == 1 && it[1] >= 0 && it[1] and 1 == 0 && it[2] in 8000..768000 &&
            listOf(3, 4, 6, 12, 21, 26).all { i -> it[i] in 0..1 } && it[5] in 0..7 && it[7] in 0..4 &&
            it[8] in 0..2000 && it[9] in -18000..0 && it[10] in -18000..0 && it[11] >= 0 && it[13] in 0..2000 &&
            it[14] in 0..1000 && it[15] in 0..20000 && (16..19).all { i -> it[i] == Int.MIN_VALUE || it[i] in -20000..20000 } &&
            it[20] >= 0 && it[22] in baseIds && it[23] in 100..20000 && it[24] in 0..72000 && it[25] == 2000 &&
            it[27] in 0..1000000 && (28..31).all { i -> it[i] == 0 }
    }?.let { Runtime(it.toList()) }
}
