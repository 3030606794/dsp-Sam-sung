package me.timschneeberger.rootlessjamesdsp.utils

/** Evidence for output-specific processing; a friendly route name is not proof of a single sink. */
enum class MediaOutputEvidence {
    UNKNOWN, SINGLE_SPEAKER, SINGLE_OTHER, MULTIPLE;

    companion object {
        /** Caller deduplicates by endpoint ID; two built-in speakers are still multiple outputs. */
        fun fromOutputTypes(types: List<Int>, builtInSpeakerType: Int): MediaOutputEvidence = when {
            types.size > 1 -> MULTIPLE
            types.singleOrNull() == builtInSpeakerType -> SINGLE_SPEAKER
            types.size == 1 -> SINGLE_OTHER
            else -> UNKNOWN
        }
    }
}
