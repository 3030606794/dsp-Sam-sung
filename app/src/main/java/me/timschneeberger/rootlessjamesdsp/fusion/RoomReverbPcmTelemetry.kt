package me.timschneeberger.rootlessjamesdsp.fusion

/**
 * Driver 1075's optional, GET-only room-reverb observation protocol.
 *
 * Each GET renews a 1500 ms native capture lease; no DSP parameter, original
 * METERS switch, audition or profile is written. All six planes describe the
 * same contiguous full-rate window. The wet plane is the normal configuration's
 * total wet branch, while the output plane is the actually selected audition
 * output. Old drivers are excluded by extended capability bit 32 before GET.
 */
object RoomReverbPcmTelemetry {
    const val CAPABILITY = 32 // Extended capability query 131234.
    const val QUERY = 131244
    const val HEADER_WORDS = 32
    const val CAPACITY = 1024
    const val CHANNELS = 6
    const val WORDS = HEADER_WORDS + CHANNELS * CAPACITY
    private const val MAGIC = 0x52565043

    data class Snapshot(
        val rate: Int,
        val frames: Int,
        val sequence: Int,
        val generation: Int,
        val firstFramePosition: Long,
        val lastFramePosition: Long,
        val inputLeft: FloatArray,
        val inputRight: FloatArray,
        val wetLeft: FloatArray,
        val wetRight: FloatArray,
        val outputLeft: FloatArray,
        val outputRight: FloatArray,
        val wetValid: Boolean,
        val professional: Boolean,
        val fading: Boolean,
        val wetIsNormalConfiguration: Boolean,
        val base: Int,
        val mode: Int,
        val comparisonEpoch: Int,
        val configurationEpoch: Int,
        val wetSource: Int,
        val moduleEnabled: Boolean,
        val leaseActive: Boolean,
        val invalidSamples: Boolean,
        val ready: Boolean
    ) {
        val inputValid: Boolean get() = ready && frames == CAPACITY && !invalidSamples
        val outputValid: Boolean get() = inputValid
        val windowMs: Double get() = frames * 1000.0 / rate
        val binWidthHz: Double get() = if (frames > 0) rate.toDouble() / frames else 0.0
    }

    fun decode(words: IntArray, expectedRate: Int): Snapshot? {
        if (words.size != WORDS || words[0] != 1 || words[1] != MAGIC ||
            words[2] < 0 || words[3] < 0 || expectedRate !in 8000..768000 ||
            words[4] != expectedRate || words[5] !in listOf(0, CAPACITY) ||
            words[6] !in 0..511 || words[7] != CHANNELS || words[8] != CAPACITY ||
            words[9] != 1 || words[10] !in 0..18 || words[11] !in 0..7 ||
            words[12] < 0 || words[13] < 0 || words[18] !in 0..1500 ||
            words[19] != 0 || words[20] != WORDS || words[21] != 1 ||
            words[22] != 0 || words[23] !in 0..3 || (25..31).any { words[it] != 0 })
            return null
        val flags = words[6]
        val frames = words[5]
        val ready = flags and 1 != 0
        val wetValid = flags and 2 != 0
        val channelMask = words[24]
        if (ready != (frames == CAPACITY) ||
            (frames > 0 && channelMask != if (wetValid) 63 else 51) ||
            (frames == 0 && channelMask !in listOf(0, 51, 63)) ||
            (wetValid && words[23] == 0) || (!wetValid && words[23] != 0))
            return null
        fun framePosition(low: Int, high: Int): Long =
            (high.toLong() shl 32) or (low.toLong() and 0xffffffffL)
        val first = framePosition(words[14], words[15])
        val last = framePosition(words[16], words[17])
        if (first < 0 || last < 0 || (frames > 0 && last - first != frames - 1L))
            return null
        fun plane(channel: Int) = FloatArray(frames) { index ->
            Float.fromBits(words[HEADER_WORDS + channel * CAPACITY + index])
        }
        val planes = Array(CHANNELS, ::plane)
        // Preserve natural amplitude, including legitimate above-full-scale
        // values. Reject corrupted non-finite packets rather than normalize them.
        if (planes.any { channel -> channel.any { !it.isFinite() } }) return null
        return Snapshot(expectedRate, frames, words[2], words[3], first, last,
            planes[0], planes[1], planes[2], planes[3], planes[4], planes[5],
            wetValid, flags and 4 != 0, flags and 16 != 0, flags and 256 != 0,
            words[10], words[11], words[12], words[13], words[23],
            flags and 8 != 0, flags and 128 != 0, flags and 64 != 0, ready)
    }
}
