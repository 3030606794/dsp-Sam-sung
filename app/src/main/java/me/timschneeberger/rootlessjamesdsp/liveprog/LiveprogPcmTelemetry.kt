package me.timschneeberger.rootlessjamesdsp.liveprog

/** Leased GET-only samples from immediately before/after the active EEL @sample. */
internal object LiveprogPcmTelemetry {
    const val CAPABILITY = 16384
    const val QUERY = 131254
    const val HEADER_WORDS = 32
    const val CAPACITY = 1024
    const val CHANNELS = 4
    const val WORDS = HEADER_WORDS + CAPACITY * CHANNELS
    private const val MAGIC = 0x4c505043

    data class Snapshot(
        val rate: Int, val hostRate: Int, val frames: Int, val sequence: Int,
        val generation: Int, val configurationEpoch: Int, val compileRevision: Int,
        val scriptHash: Int, val compileResult: Int,
        val firstFramePosition: Long, val lastFramePosition: Long,
        val inputLeft: FloatArray, val inputRight: FloatArray,
        val outputLeft: FloatArray, val outputRight: FloatArray,
        val ready: Boolean, val moduleEnabled: Boolean, val compiled: Boolean,
        val executing: Boolean, val leaseActive: Boolean, val invalidSamples: Boolean,
        val rateMismatch: Boolean
    )

    fun internalRate(host: Int): Int = if (host >= 44100) host else when {
        host > 0 && 48000 % host == 0 -> 48000
        host > 0 && 44100 % host == 0 -> 44100
        else -> 48000
    }

    fun sourceHash(source: String): Int {
        var hash = 0x811c9dc5.toInt()
        source.toByteArray(Charsets.UTF_8).forEach { hash = (hash xor (it.toInt() and 255)) * 16777619 }
        return hash
    }

    fun decode(words: IntArray, expectedHostRate: Int): Snapshot? {
        if (words.size != WORDS || words[0] != 1 || words[1] != MAGIC ||
            expectedHostRate !in 8000..768000 || words[5] !in listOf(0, CAPACITY) ||
            words[7] != CHANNELS || words[8] != CAPACITY || words[9] != 1 ||
            words[18] !in 0..1500 || words[19] != 0 || words[20] != WORDS ||
            words[21] != 1 || words[22] != 1 || words[23] !in listOf(0, 15) ||
            words[24] != 0 || words[25] !in 0..1 || words[26] !in 0..1 ||
            words[27] !in 0..1 || words[28] !in 0..1 || words[29] != 0 || words[30] != 0 ||
            (words[4] != internalRate(expectedHostRate) && !(words[5] == 0 && words[4] == 0)) ||
            (words[31] != expectedHostRate && !(words[5] == 0 && words[31] == 0))) return null
        val flags = words[6]
        if (flags and 0xff.inv() != 0) return null
        val ready = flags and 1 != 0
        if (ready != (words[5] == CAPACITY) ||
            (ready && (words[23] != 15 || flags and 2 == 0 || flags and 4 == 0 || flags and 8 == 0 || flags and 16 == 0))) return null
        fun position(low: Int, high: Int) = (high.toLong() shl 32) or (low.toLong() and 0xffffffffL)
        val first = position(words[14], words[15]); val last = position(words[16], words[17])
        if (first < 0 || last < 0 || (ready && last - first != CAPACITY - 1L)) return null
        val planes = Array(CHANNELS) { channel -> FloatArray(words[5]) { index ->
            Float.fromBits(words[HEADER_WORDS + channel * CAPACITY + index])
        } }
        if (planes.any { plane -> plane.any { !it.isFinite() } }) return null
        return Snapshot(words[4].takeIf { it > 0 } ?: internalRate(expectedHostRate), expectedHostRate,
            words[5], words[2], words[3], words[13], words[10], words[11], words[12], first, last,
            planes[0], planes[1], planes[2], planes[3], ready, flags and 4 != 0,
            flags and 8 != 0, flags and 2 != 0 && flags and 16 != 0,
            flags and 128 != 0, flags and 64 != 0, words[28] != 0)
    }
}
