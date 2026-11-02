package me.timschneeberger.rootlessjamesdsp.fusion

/**
 * Driver 1076's optional, GET-only analog-colour PCM observation protocol.
 *
 * Each GET requests one contiguous full-rate window and renews a 1500 ms lease.
 * The raw input, normal configuration's full pre-mix processed branch, actual
 * selected output and delay-aligned dry reference are synchronous real samples.
 * The processed branch includes high-pass filtering, the harmonic colour model,
 * low-pass filtering, the original gain, peak EQ and output trim. It is not a
 * separated newly generated harmonic signal.
 * No quality, factor, METERS, audition or persistent DSP parameter is written.
 */
object AnalogPcmTelemetry {
    const val CAPABILITY = 128 // Extended capability query 131234.
    const val QUERY = 131246
    const val HEADER_WORDS = 32
    const val CAPACITY = 1024
    const val CHANNELS = 8
    const val WORDS = HEADER_WORDS + CHANNELS * CAPACITY
    private const val MAGIC = 0x414E5043

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
        val baseMode: Int,
        val selectedBaseMode: Int,
        val pendingWarming: Boolean,
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
        val factors = listOf(1, 2, 4)
        if (words.size != WORDS || words[0] != 1 || words[1] != MAGIC ||
            words[2] < 0 || words[3] < 0 || expectedRate !in 8000..768000 ||
            (words[4] != expectedRate && !(words[5] == 0 && words[4] == 0)) ||
            words[5] !in listOf(0, CAPACITY) ||
            words[6] !in 0..4095 || words[7] != CHANNELS || words[8] != CAPACITY ||
            words[9] != 1 || words[10] !in listOf(0, 1, 2, 4) || words[11] !in 0..6 ||
            words[12] < 0 || words[13] < 0 || words[18] !in 0..1500 ||
            words[19] !in 0..64 || words[20] != WORDS || words[21] != 1 ||
            words[22] !in 0..64 || words[23] !in 0..2 ||
            words[25] !in listOf(0, 1, 2, 4) || words[26] !in listOf(0, 1, 2, 4) ||
            words[27] !in 0..1 || words[28] !in 0..2 || words[29] !in 0..64 ||
            words[30] !in 0..64 || words[31] !in 0..2) return null
        val flags = words[6]
        val frames = words[5]
        val ready = flags and 1 != 0
        val wetValid = flags and 2 != 0
        if (ready != (frames == CAPACITY) ||
            (frames > 0 && (words[24] != 255 || !wetValid || words[23] == 0 ||
                words[10] !in factors || words[25] !in factors || words[27] != 1)) ||
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
        return Snapshot(expectedRate, frames, words[2], words[3], first, last,
            planes[0], planes[1], planes[2], planes[3], planes[4], planes[5], planes[6], planes[7],
            wetValid, flags and 4 != 0, flags and 16 != 0, flags and 256 != 0,
            words[10], words[25], words[26], words[19], words[22], words[29], words[30],
            words[28], words[31], flags and 2048 != 0,
            flags and 512 != 0, flags and 1024 != 0, words[11], words[12], words[13], words[23],
            flags and 8 != 0, flags and 128 != 0, flags and 64 != 0, ready)
    }
}
