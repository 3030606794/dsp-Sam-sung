package me.timschneeberger.rootlessjamesdsp.peq

import kotlin.math.abs
import kotlin.math.log10

/** Leased GET-only PCM immediately before/after PEQ, before every downstream effect. */
object PeqPcmTelemetry {
    const val CAPABILITY = 32768
    const val QUERY = 131255
    const val HEADER_WORDS = 32
    const val CAPACITY = 1024
    const val CHANNELS = 4
    const val WORDS = HEADER_WORDS + CHANNELS * CAPACITY
    private const val MAGIC = 0x50455143

    data class Snapshot(
        val rate: Int,
        val hostRate: Int,
        val frames: Int,
        val sequence: Int,
        val captureId: Int,
        val generation: Int,
        val configurationEpoch: Int,
        val appliedConfigurationEpoch: Int,
        val parameterHash: Int,
        val firstFramePosition: Long,
        val lastFramePosition: Long,
        val inputLeft: FloatArray,
        val inputRight: FloatArray,
        val outputLeft: FloatArray,
        val outputRight: FloatArray,
        val ready: Boolean,
        val moduleEnabled: Boolean,
        val transitioning: Boolean,
        val configurationChanged: Boolean,
        val leaseActive: Boolean,
        val invalidSamples: Boolean
    ) {
        val valid: Boolean get() = ready && leaseActive && !invalidSamples && frames == CAPACITY
        val windowMs: Double get() = frames * 1000.0 / rate
        val binWidthHz: Double get() = if (frames > 0) rate.toDouble() / frames else 0.0

        private val inputMetrics by lazy { metrics(inputLeft, inputRight) }
        private val outputMetrics by lazy { metrics(outputLeft, outputRight) }
        val inputRmsDbfs: Double get() = inputMetrics.first
        val outputRmsDbfs: Double get() = outputMetrics.first
        val inputPeakDbfs: Double get() = inputMetrics.second
        val outputPeakDbfs: Double get() = outputMetrics.second

        /** Original linear PCM. These helpers never normalize either window. */
        fun inputInterleaved(): FloatArray = interleaved(inputLeft, inputRight)
        fun outputInterleaved(): FloatArray = interleaved(outputLeft, outputRight)

        private fun metrics(left: FloatArray, right: FloatArray): Pair<Double, Double> {
            if (!valid || left.size != frames || right.size != frames) return Double.NaN to Double.NaN
            var square = 0.0
            var peak = 0.0
            for (index in 0 until frames) {
                val l = left[index].toDouble()
                val r = right[index].toDouble()
                square += l * l + r * r
                peak = maxOf(peak, abs(l), abs(r))
            }
            val rmsDb = if (square > 0.0) 10.0 * log10(square / (frames * 2.0)) else Double.NEGATIVE_INFINITY
            val peakDb = if (peak > 0.0) 20.0 * log10(peak) else Double.NEGATIVE_INFINITY
            return rmsDb to peakDb
        }
        private fun interleaved(left: FloatArray, right: FloatArray): FloatArray =
            FloatArray(minOf(left.size, right.size) * 2) { index ->
                if (index and 1 == 0) left[index / 2] else right[index / 2]
            }
    }

    /** Exact little-endian FNV-1a of the actual schema-3 packet, including the master enable. */
    fun expectedParameterHash(state: PeqState, enabled: Boolean): Int {
        var hash = 0x811c9dc5.toInt()
        PeqProtocol.payload(state, enabled).forEach { word ->
            for (shift in 0 until 32 step 8) hash = (hash xor ((word ushr shift) and 255)) * 16777619
        }
        return hash
    }

    fun decode(words: IntArray, expectedHostRate: Int): Snapshot? {
        if (words.size != WORDS || words[0] != 1 || words[1] != MAGIC ||
            expectedHostRate !in 8000..768000 || words[2] < 0 || words[3] < 0 || words[10] < 0 ||
            words[5] !in listOf(0, CAPACITY) || words[7] != CHANNELS || words[8] != CAPACITY ||
            words[9] != 1 || words[13] < 0 || words[19] < 0 || words[18] !in 0..1500 ||
            words[20] != WORDS || words[21] != 1 || words[22] != 1 || words[24] !in 0..1 ||
            (25..30).any { words[it] != 0 } ||
            (words[4] != expectedHostRate && !(words[5] == 0 && words[4] == 0)) ||
            (words[31] != expectedHostRate && !(words[5] == 0 && words[31] == 0))) return null
        val flags = words[6]
        if (flags and 207.inv() != 0) return null
        val ready = flags and 1 != 0
        val frames = words[5]
        if (ready != (frames == CAPACITY) || flags and 2 != words[24] * 2 ||
            (ready && (words[10] == 0 || words[23] != 15 || flags and 128 == 0)) ||
            (!ready && words[23] != 0)) return null
        fun position(low: Int, high: Int): Long = (high.toLong() shl 32) or (low.toLong() and 0xffffffffL)
        val first = position(words[14], words[15])
        val last = position(words[16], words[17])
        if (first < 0 || last < 0 || (ready && last - first != CAPACITY - 1L)) return null
        val planes = Array(CHANNELS) { plane -> FloatArray(frames) { index ->
            Float.fromBits(words[HEADER_WORDS + plane * CAPACITY + index])
        } }
        // Preserve above-full-scale samples and absolute calibration. Never replace an
        // unavailable/silent signal with a normalized or synthetic comparison trace.
        if (planes.any { plane -> plane.any { !it.isFinite() } }) return null
        return Snapshot(words[4].takeIf { it > 0 } ?: expectedHostRate, expectedHostRate,
            frames, words[2], words[10], words[3], words[13], words[19], words[11], first, last,
            planes[0], planes[1], planes[2], planes[3], ready, flags and 2 != 0,
            flags and 4 != 0, flags and 8 != 0, flags and 128 != 0, flags and 64 != 0)
    }
}
