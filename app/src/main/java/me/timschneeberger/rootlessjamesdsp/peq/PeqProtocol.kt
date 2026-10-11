package me.timschneeberger.rootlessjamesdsp.peq

import kotlin.math.roundToInt

/** Fusion driver protocol 3, SET 1250. Values use Hz/dB/Q multiplied by 1000. */
object PeqProtocol {
    const val PARAMETER = 1250
    const val SCHEMA = 1
    const val HEADER_INTS = 3
    const val BAND_INTS = 5

    fun payload(state: PeqState, enabled: Boolean): IntArray {
        PeqModel.validate(state)
        return IntArray(HEADER_INTS + BAND_INTS * state.bands.size).apply {
            this[0] = SCHEMA
            this[1] = if (enabled) 1 else 0
            this[2] = state.bands.size
            state.bands.forEachIndexed { index, band ->
                val offset = HEADER_INTS + index * BAND_INTS
                this[offset] = band.type.wireId
                this[offset + 1] = if (band.enabled) 1 else 0
                this[offset + 2] = milli(band.frequency)
                this[offset + 3] = milli(band.gain)
                this[offset + 4] = milli(band.q)
            }
        }
    }

    internal fun quantized(band: PeqBand): PeqBand = band.copy(
        frequency = milli(band.frequency) / 1000.0,
        gain = milli(band.gain) / 1000.0,
        q = milli(band.q) / 1000.0
    )

    private fun milli(value: Double): Int = (value * 1000.0).roundToInt()
}
