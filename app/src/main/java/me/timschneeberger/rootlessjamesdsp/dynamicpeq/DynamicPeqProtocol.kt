package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import kotlin.math.roundToInt

/** Dedicated schema: never send this payload through the static PEQ parameter. */
object DynamicPeqProtocol {
    const val PARAMETER = 196720
    const val CAPABILITY_QUERY = 132000
    const val RUNTIME_QUERY = 132001
    const val SCHEMA = 1
    const val HEADER_INTS = 3
    const val BAND_INTS = 12
    const val RUNTIME_WORDS = 40

    fun payload(state: DynamicPeqState): IntArray {
        DynamicPeqModel.validate(state)
        return IntArray(HEADER_INTS + BAND_INTS * state.bands.size).apply {
            this[0] = SCHEMA
            this[1] = if (state.enabled) 1 else 0
            this[2] = state.bands.size
            state.bands.forEachIndexed { index, b ->
                val o = HEADER_INTS + index * BAND_INTS
                this[o] = b.type
                this[o + 1] = if (b.enabled) 1 else 0
                this[o + 2] = milli(b.frequency)
                this[o + 3] = milli(b.gain)
                this[o + 4] = milli(b.q)
                this[o + 5] = if (b.dynamicEnabled) 1 else 0
                this[o + 6] = milli(b.threshold)
                this[o + 7] = milli(b.ratio)
                this[o + 8] = milli(b.range)
                this[o + 9] = milli(b.attack)
                this[o + 10] = milli(b.release)
                this[o + 11] = milli(b.knee)
            }
        }
    }

    fun decodeRuntime(words: IntArray): DynamicPeqRuntime? {
        if (words.size != RUNTIME_WORDS || words[0] != SCHEMA || words[1] !in 0..1 ||
            words[2] !in 0..DynamicPeqModel.MAX_BANDS || words[3] !in 8000..768000 || words[4] < 0) return null
        fun db(index: Int): Double = words[index] / 1000.0
        if (words[5] !in -240000..48000 || words[6] !in -240000..48000) return null
        if ((0 until words[2]).any { words[8 + it] !in -240000..48000 || words[24 + it] !in 0..24000 }) return null
        return DynamicPeqRuntime(words[1] == 1, words[2], words[3], words[4], db(5), db(6),
            List(words[2]) { db(8 + it) }, List(words[2]) { db(24 + it) }, parameterHash = words[7])
    }

    private fun milli(value: Double): Int = (value * 1000.0).roundToInt()
}

data class DynamicPeqRuntime(
    val enabled: Boolean,
    val bandCount: Int,
    val sampleRate: Int,
    val processedFrames: Int,
    val inputPeak: Double,
    val outputPeak: Double,
    val detectorRms: List<Double>,
    val reductions: List<Double>,
    val isProcessing: Boolean = false,
    val engineEnabled: Boolean = false,
    val parameterHash: Int = 0
)
