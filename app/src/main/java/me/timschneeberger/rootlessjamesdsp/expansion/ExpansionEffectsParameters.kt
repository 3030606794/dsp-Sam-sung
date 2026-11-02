package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters

internal data class ExpansionControl(val control: BassParameters.Control, val logicalKey: String,
    val step: Int, val options: Map<Int, String>, val section: String, val basic: Boolean, val transient: Boolean)

object ExpansionEffectsParameters {
    const val CAPABILITY_QUERY = 132020
    const val SCHEMA = 1
    const val RUNTIME_WORDS = 128
    internal fun metadata(kind: ExpansionEffectsKind) = ExpansionEffectsCatalog.entries.getValue(kind)
    fun allControls(kind: ExpansionEffectsKind) = metadata(kind).map { it.control }
    private fun displayed(kind: ExpansionEffectsKind): List<ExpansionControl> = metadata(kind).let { rows ->
        if (kind != ExpansionEffectsKind.SMART_ADAPT) rows else rows.sortedBy {
            when (it.control.key) { "p0" -> 0; "p16" -> 1; "p1" -> 2; else -> 3 + it.control.key.removePrefix("p").toInt() }
        }
    }
    fun controls(kind: ExpansionEffectsKind) = displayed(kind).filterNot { it.transient }.map { it.control }
    fun basic(kind: ExpansionEffectsKind) = displayed(kind).filter { it.basic && !it.transient }.map { it.control }
    fun value(values: Map<String, *>, control: BassParameters.Control) = BassParameters.value(values, control)
    fun control(kind: ExpansionEffectsKind, logicalKey: String) = metadata(kind).first { it.logicalKey == logicalKey }.control
    fun raw(kind: ExpansionEffectsKind, values: Map<String, *>, logicalKey: String): Double =
        metadata(kind).firstOrNull { it.logicalKey == logicalKey }?.control?.let { value(values, it) / 1000.0 } ?: 0.0
    fun options(kind: ExpansionEffectsKind, key: String) = metadata(kind).firstOrNull { it.control.key == key }?.options.orEmpty()
    fun section(kind: ExpansionEffectsKind, key: String) = metadata(kind).firstOrNull { it.control.key == key }?.section.orEmpty()
    fun description(kind: ExpansionEffectsKind) = ExpansionEffectsCatalog.descriptions.getValue(kind)
    fun help(kind: ExpansionEffectsKind, key: String): String {
        val meta = metadata(kind).first { it.control.key == key }
        return ExpansionEffectsHelp.control(kind, meta.logicalKey)
    }
    fun packet(kind: ExpansionEffectsKind, values: Map<String, *>): IntArray = IntArray(128).apply {
        this[0] = SCHEMA; this[1] = if (values[kind.enableKey] == true) 1 else 0
        metadata(kind).forEachIndexed { index, metadata ->
            val raw = value(values, metadata.control)
            // An unknown enum is not a position beyond the last option: recover its documented default.
            // Continuous values keep the usual bounded numeric recovery.
            val storedOption = values[metadata.control.key] as? Int ?: metadata.control.default
            this[index + 2] = if (metadata.options.isNotEmpty()) storedOption.takeIf { it in metadata.options } ?: metadata.control.default else raw
        }
        // Imported/stale settings cannot authorize an output route. Only the live bridge may do so.
        if (kind == ExpansionEffectsKind.SPEAKER_OPTIMIZATION) this[SpeakerOptimizationProtocol.ROUTE_INDEX] = 0
        if (kind == ExpansionEffectsKind.SMART_ADAPT) SmartAdaptState.patch(values, this)
        // Profile data can originate from older versions; preserve a valid native packet even then.
        linkedRange(kind)?.let { relation ->
            val low = control(kind, relation.first).key.removePrefix("p").toInt() + 2
            val high = control(kind, relation.second).key.removePrefix("p").toInt() + 2
            val gap = relation.third
            if (this[high] < this[low] + gap) this[low] = this[high] - gap
        }
    }
    internal fun linkedRange(kind: ExpansionEffectsKind): Triple<String, String, Int>? = when(kind) {
        ExpansionEffectsKind.FORMANT, ExpansionEffectsKind.AUTO_WAH -> Triple("low", "high", 1000)
        ExpansionEffectsKind.FREQUENCY_SHIFT -> Triple("lowcut", "highcut", 1000)
        ExpansionEffectsKind.MATCH_EQ, ExpansionEffectsKind.RESONANCE -> Triple("low", "high", 100000)
        ExpansionEffectsKind.TRANSIENT -> Triple("low_split", "high_split", 100000)
        ExpansionEffectsKind.SUB_BASS -> Triple("input_low", "input_high", 10000)
        else -> null
    }
    fun hash(kind: ExpansionEffectsKind, words: IntArray): Int = when(kind) {
        ExpansionEffectsKind.HEADTRACK -> words.copyOf().apply { for (i in 7..11) this[i] = 0 }.contentHashCode()
        ExpansionEffectsKind.SPEAKER_OPTIMIZATION -> words.copyOf().apply { this[SpeakerOptimizationProtocol.ROUTE_INDEX] = 0 }.contentHashCode()
        ExpansionEffectsKind.SMART_ADAPT -> words.copyOf().apply { this[SmartAdaptProtocol.ROUTE_INDEX] = 0 }.contentHashCode()
        else -> words.contentHashCode()
    }
    fun decode(kind: ExpansionEffectsKind, words: IntArray): ExpansionEffectsRuntime? {
        if (words.size != RUNTIME_WORDS || words[0] != SCHEMA || words[1] !in 0..1 || words[2] !in 8000..768000 || words[3] < 0) return null
        return ExpansionEffectsRuntime(kind, words[1] == 1, words[2], words[3], words[4],
            words[5] / 1000f, words[6] / 1000f, words[7] / 1000f,
            words.slice(12..127).map { it / 1000f })
    }
}

data class ExpansionEffectsRuntime(val kind: ExpansionEffectsKind, val enabled: Boolean, val sampleRate: Int,
    val processedFrames: Int, val parameterHash: Int, val inputPeakDb: Float, val outputPeakDb: Float,
    val wetPeakDb: Float, val meters: List<Float>, val engineEnabled: Boolean = false, val isProcessing: Boolean = false) {
    fun meter(index: Int) = meters.getOrElse(index) { 0f }
    val spectrumDb get() = meters.drop(4).take(16)
}
