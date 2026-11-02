package me.timschneeberger.rootlessjamesdsp.peq

import kotlin.math.roundToInt

/** Fusion SET 1250 schema 3: milli-Hz, milli-dB, and IEEE754 double Q as low/high int32 words. */
object PeqProtocol {
    const val PARAMETER = 1250
    const val SCHEMA = 3
    const val PRECISE_Q_CAPABILITY = 8
    const val HEADER_INTS = 3
    const val BAND_INTS = 6

    fun payload(state: PeqState, enabled: Boolean): IntArray {
        PeqModel.validate(state)
        return IntArray(HEADER_INTS + BAND_INTS * state.bands.size).apply {
            this[0] = SCHEMA
            this[1] = if (enabled) 1 else 0
            this[2] = state.bands.size
            state.bands.forEachIndexed { index, band ->
                val offset = HEADER_INTS + index * BAND_INTS
                this[offset] = band.type.wireId
                // The driver only accepts real filter frequencies. Keep unset bands
                // in the saved bank but send a disabled legal placeholder on the wire.
                this[offset + 1] = if (band.enabled && band.frequency > 0.0) 1 else 0
                this[offset + 2] = milli(band.frequency.coerceAtLeast(PeqModel.MIN_FREQUENCY))
                this[offset + 3] = milli(band.gain)
                val qualityBits = band.q.toRawBits()
                this[offset + 4] = qualityBits.toInt()
                this[offset + 5] = (qualityBits ushr 32).toInt()
            }
        }
    }

    internal fun quantized(band: PeqBand): PeqBand = band.copy(
        frequency = milli(band.frequency) / 1000.0,
        gain = milli(band.gain) / 1000.0,
        q = band.q
    )

    private fun milli(value: Double): Int = (value * 1000.0).roundToInt()
}
