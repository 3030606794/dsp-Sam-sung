package me.timschneeberger.rootlessjamesdsp.fusion

/** Optional feedback and detector controls. The original 18-word reverb packet stays unchanged. */
object ReverbAdvancedParameters {
    const val CAPABILITY = 4194304
    const val PACKET = 65648
    const val RUNTIME = 131223
    const val RT_ON = "v_reverb_pro_rt60_on"
    const val BANDS_ON = "v_reverb_pro_decay_bands_on"
    const val DUCK_ON = "v_reverb_pro_duck_on"
    const val GRAPH_ON = "v_reverb_pro_decay_graph_on"
    val rt60 = BassParameters.Control("v_reverb_pro_rt60_centiseconds", "尾音时间 RT60", 20, 1000, 150, 100, "s", 2)
    val bassRatio = BassParameters.Control("v_reverb_pro_decay_bass_percent", "低频衰减时间倍率", 25, 400, 100, 100, "倍", 2)
    val trebleRatio = BassParameters.Control("v_reverb_pro_decay_treble_percent", "高频衰减时间倍率", 25, 200, 100, 100, "倍", 2)
    val lowCrossover = BassParameters.Control("v_reverb_pro_decay_low_hz", "低频衰减分界", 80, 1000, 250, unit = "Hz")
    val highCrossover = BassParameters.Control("v_reverb_pro_decay_high_hz", "高频衰减分界", 1000, 16000, 4000, unit = "Hz")
    val threshold = BassParameters.Control("v_reverb_pro_duck_threshold_tenths_db", "避让触发电平", -600, 0, -240, 10, "dBFS", 1)
    val depth = BassParameters.Control("v_reverb_pro_duck_depth_tenths_db", "最大避让量", 0, 240, 90, 10, "dB", 1)
    val attack = BassParameters.Control("v_reverb_pro_duck_attack_ms", "避让攻击时间", 1, 200, 20, unit = "ms")
    val release = BassParameters.Control("v_reverb_pro_duck_release_ms", "避让释放时间", 50, 3000, 350, unit = "ms")
    val bandNumbers = listOf(bassRatio, trebleRatio, lowCrossover, highCrossover)
    val duckNumbers = listOf(threshold, depth, attack, release)
    val numbers = listOf(rt60) + bandNumbers + duckNumbers
    val switches = setOf(RT_ON, BANDS_ON, DUCK_ON, GRAPH_ON)
    val keys = numbers.map { it.key }.toSet() + switches
    data class Settings(
        val rtOn: Boolean = false, val rt60: Int = 150,
        val bandsOn: Boolean = false, val bassRatio: Int = 100, val trebleRatio: Int = 100,
        val lowCrossover: Int = 250, val highCrossover: Int = 4000,
        val duckOn: Boolean = false, val threshold: Int = -240, val depth: Int = 90,
        val attack: Int = 20, val release: Int = 350, val graphOn: Boolean = false,
    ) {
        private fun integers() = listOf(rt60, bassRatio, trebleRatio, lowCrossover, highCrossover, threshold, depth, attack, release)
        fun valid() = integers().zip(numbers).all { (value, control) -> value in control.min..control.max } && lowCrossover <= highCrossover
        fun packet() = intArrayOf(1, if (rtOn) 1 else 0, rt60, if (bandsOn) 1 else 0, bassRatio, trebleRatio,
            lowCrossover, highCrossover, if (duckOn) 1 else 0, threshold, depth, attack, release, if (graphOn) 1 else 0, 0)
        fun values(): Map<String, Any> = buildMap {
            numbers.zip(integers()).forEach { (control, value) -> put(control.key, value) }
            put(RT_ON, rtOn); put(BANDS_ON, bandsOn); put(DUCK_ON, duckOn); put(GRAPH_ON, graphOn)
        }
    }
    fun capture(values: Map<String, *>) = Settings(
        ReverbParameters.on(values, RT_ON), ReverbParameters.value(values, rt60),
        ReverbParameters.on(values, BANDS_ON), ReverbParameters.value(values, bassRatio), ReverbParameters.value(values, trebleRatio),
        ReverbParameters.value(values, lowCrossover), ReverbParameters.value(values, highCrossover),
        ReverbParameters.on(values, DUCK_ON), ReverbParameters.value(values, threshold), ReverbParameters.value(values, depth),
        ReverbParameters.value(values, attack), ReverbParameters.value(values, release), ReverbParameters.on(values, GRAPH_ON),
    )
    /** Loop-decay prediction and applied envelope attenuation, never acoustic room measurements. */
    data class Runtime(val data: List<Int>) {
        val sampleRate get() = data[2]
        val ready get() = data[3] == 1 && data[4] == 1 && data[8] == 1
        val referenceMs get() = data[9]
        val decayMs get() = data.subList(10, 13)
        val duckCentiDb get() = data[13]
    }
    fun decode(packet: IntArray): Runtime? = packet.takeIf {
        it.size == 16 && it[0] == 1 && it[1] >= 0 && it[1] and 1 == 0 && it[2] in 8000..768000 &&
            (3..8).all { index -> it[index] in 0..1 } && (9..12).all { index -> it[index] in 1..60000 } &&
            it[13] in 0..2400 && it[14] in 80..1000 && it[15] in 1000..16000 && it[14] <= it[15]
    }?.let { Runtime(it.toList()) }
}
