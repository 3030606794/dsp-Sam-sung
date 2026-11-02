package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.utils.MediaOutputEvidence

/** Independent processor; no addresses or state from the user's equalizers are reused. */
object SmartAdaptProtocol {
    const val PARAMETER = 196763
    const val RUNTIME_QUERY = 132045
    const val CAPABILITY_QUERY = 132046
    const val SCHEMA = 1
    const val CAPABILITY_REVISION = 2
    const val SNAPSHOT_SCHEMA = 2
    const val WORDS = 128
    const val LOCK_INDEX = 110
    const val ROUTE_INDEX = 126

    fun supports(words: IntArray?): Boolean = words?.contentEquals(intArrayOf(CAPABILITY_REVISION, 2, WORDS, WORDS)) == true
    fun route(evidence: MediaOutputEvidence): Int = when (evidence) {
        MediaOutputEvidence.UNKNOWN -> 0
        MediaOutputEvidence.SINGLE_SPEAKER -> 1000
        MediaOutputEvidence.SINGLE_OTHER -> 2000
        MediaOutputEvidence.MULTIPLE -> 3000
    }

    fun framesAdvanced(first: ExpansionEffectsRuntime, second: ExpansionEffectsRuntime): Boolean =
        first.sampleRate == second.sampleRate && first.engineEnabled && second.engineEnabled &&
            (second.processedFrames > first.processedFrames ||
                first.processedFrames >= 0x70000000 && second.processedFrames <= 0x0fffffff)
}
