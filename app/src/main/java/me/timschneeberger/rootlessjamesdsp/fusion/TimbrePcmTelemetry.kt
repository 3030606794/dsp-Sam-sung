package me.timschneeberger.rootlessjamesdsp.fusion

/** Driver1076 GET-only leased PCM. Normal configuration processed sound including dry/wet mixing; never separated new harmonics. */
object TimbrePcmTelemetry {
    const val CAPABILITY = 512 // Extended capability query 131234.
    const val QUERY = 131248
    const val HEADER_WORDS = 32
    const val CAPACITY = 1024
    const val CHANNELS = 8
    const val WORDS = HEADER_WORDS + CHANNELS * CAPACITY
    private const val MAGIC = 0x54495043

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
        val alignedDryLeft: FloatArray,
        val alignedDryRight: FloatArray,
        val wetValid: Boolean,
        val professional: Boolean,
        val fading: Boolean,
        val wetIsNormalConfiguration: Boolean,
        val effectiveFactor: Int,
        val selectedFactor: Int,
        val oldFactor: Int,
        val commonDelayFrames: Int,
        val commonDelayEndFrames: Int,
        val normalKernelDelayFrames: Int,
        val selectedKernelDelayFrames: Int,
        val commonDelayChanged: Boolean,
        val routeFading: Boolean,
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
        // A changing alignment delay does not provide a stationary dry reference
        // for an overlaid FFT, even though its raw captured samples remain real.
        val alignedDryValid: Boolean get() = inputValid && !commonDelayChanged
        val windowMs: Double get() = frames * 1000.0 / rate
        val binWidthHz: Double get() = if (frames > 0) rate.toDouble() / frames else 0.0
    }

    fun decode(words: IntArray, expectedRate: Int): Snapshot? {
        val expectedInternal = if (expectedRate >= 44100) expectedRate else if (expectedRate > 0 && 48000 % expectedRate == 0) 48000 else if (expectedRate > 0 && 44100 % expectedRate == 0) 44100 else 48000
        val factors = listOf(1, 2, 3, 4, 5)
        if (words.size != WORDS || words[0] != 1 || words[1] != MAGIC ||
            words[2] < 0 || words[3] < 0 || expectedRate !in 8000..768000 ||
            (words[4] != expectedInternal && !(words[5] == 0 && words[4] == 0)) ||
            words[5] !in listOf(0, CAPACITY) ||
            words[6] !in 0..8191 || words[7] != CHANNELS || words[8] != CAPACITY ||
            words[9] != 1 || words[10] !in listOf(0, 1, 2, 3, 4, 5) || words[11] !in 0..11 ||
            words[12] < 0 || words[13] < 0 || words[18] !in 0..1500 ||
            words[19] !in 0..512 || words[20] != WORDS || words[21] != 1 ||
            words[22] !in 0..512 || words[23] !in 0..2 ||
            words[25] !in listOf(0, 1, 2, 3, 4, 5) || words[26] !in listOf(0, 1, 2, 3, 4, 5) ||
            words[27] !in 0..2 || words[28] !in 0..1 || words[29] !in 0..512 ||
            words[30] !in 0..512 || (words[31] != expectedRate && !(words[5] == 0 && words[31] == 0))) return null
        val flags = words[6]
        val frames = words[5]
        val ready = flags and 1 != 0
        val wetValid = flags and 2 != 0
        if (frames > 0 && flags and 4096 == 0) return null
        if (ready != (frames == CAPACITY) ||
            (frames > 0 && (words[24] != 255 || !wetValid || words[23] == 0 ||
                words[10] !in factors || words[25] !in factors || words[27] != 2)) ||
            (frames == 0 && words[24] !in listOf(0, 255))) return null
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
        // Keep the native linear scale, including real above-full-scale values.
        // Never normalize or silently fabricate missing non-finite samples.
        if (planes.any { channel -> channel.any { !it.isFinite() } }) return null
        return Snapshot(words[4].takeIf { it > 0 } ?: expectedRate, frames, words[2], words[3], first, last,
            planes[0], planes[1], planes[2], planes[3], planes[4], planes[5], planes[6], planes[7],
            wetValid, flags and 4 != 0, flags and 16 != 0, flags and 256 != 0,
            words[10], words[25], words[26], words[19], words[22], words[29], words[30],
            flags and 512 != 0, flags and 1024 != 0, words[11], words[12], words[13], words[23],
            flags and 8 != 0, flags and 128 != 0, flags and 64 != 0, ready)
    }
}
