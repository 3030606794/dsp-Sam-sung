package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.utils.MediaOutputEvidence

/** Dedicated extension: the original 21 effects and Match EQ wire addresses remain stable. */
object SpeakerOptimizationProtocol {
    const val PARAMETER = 196762
    const val RUNTIME_QUERY = 132043
    const val CAPABILITY_QUERY = 132044
    const val SCHEMA = 1
    const val SCOPE_INDEX = 12
    const val ROUTE_INDEX = 13

    fun supported(kind: ExpansionEffectsKind, legacy: Boolean?, speaker: Boolean?, smart: Boolean? = false): Boolean? =
        when (kind) {
            ExpansionEffectsKind.SPEAKER_OPTIMIZATION -> speaker
            ExpansionEffectsKind.SMART_ADAPT -> smart
            else -> legacy
        }

    fun routeActive(scope: Int, evidence: MediaOutputEvidence): Boolean =
        scope == 1000 || evidence == MediaOutputEvidence.SINGLE_SPEAKER

    fun bypassReason(scope: Int, evidence: MediaOutputEvidence): String? {
        if (routeActive(scope, evidence)) return null
        return when (evidence) {
            MediaOutputEvidence.MULTIPLE -> "检测到多个媒体输出 · 扬声器优化已旁路"
            MediaOutputEvidence.SINGLE_OTHER -> "当前不是内置扬声器 · 扬声器优化已旁路"
            else -> "尚未确认单路内置扬声器 · 扬声器优化已旁路"
        }
    }

    /** Only algorithm controls reset; enable, scope and route state are never overwritten. */
    fun classicValues(): Map<String, Int> = ExpansionEffectsParameters.metadata(ExpansionEffectsKind.SPEAKER_OPTIMIZATION)
        .take(10).associate { it.control.key to it.control.default }
}
