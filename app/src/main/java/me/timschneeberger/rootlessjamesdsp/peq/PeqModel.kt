package me.timschneeberger.rootlessjamesdsp.peq

import java.util.UUID
import kotlin.math.pow

enum class PeqFilterType(val wireId: Int, val usesGain: Boolean) {
    PEAK(0, true), LOW_SHELF(1, true), HIGH_SHELF(2, true),
    LOW_PASS(3, false), HIGH_PASS(4, false), NOTCH(5, false), BAND_PASS(6, false)
}

data class PeqBand(
    val id: String = UUID.randomUUID().toString(),
    val type: PeqFilterType = PeqFilterType.PEAK,
    val enabled: Boolean = true,
    val frequency: Double = 1000.0,
    val gain: Double = 0.0,
    val q: Double = 0.7071067811865476
)

/** The effect's enable switch stays in the existing key_geq_enable preference. */
data class PeqState(
    val bands: List<PeqBand>,
    val gainRange: Double = 18.0,
    val gainStep: Double = 0.1
)

object PeqModel {
    const val MAX_BANDS = 170
    const val MIN_FREQUENCY = 20.0
    const val MAX_FREQUENCY = 20000.0
    const val MAX_GAIN = 24.0
    const val MIN_Q = 0.1
    const val MAX_Q = 30.0
    val GAIN_RANGES = listOf(6.0, 12.0, 18.0, 24.0)
    val GAIN_STEPS = listOf(0.1, 0.25, 0.5, 1.0)

    fun validateBand(band: PeqBand): PeqBand {
        require(band.id.isNotBlank() && band.id.length <= 128 && band.id.none { it.isISOControl() }) {
            "Band id must be a non-empty, printable string of at most 128 characters"
        }
        require(band.frequency.isFinite() && band.frequency in MIN_FREQUENCY..MAX_FREQUENCY) {
            "Frequency must be between 20 and 20000 Hz"
        }
        require(band.gain.isFinite() && band.gain in -MAX_GAIN..MAX_GAIN) {
            "Gain must be between -24 and 24 dB"
        }
        require(band.q.isFinite() && band.q in MIN_Q..MAX_Q) { "Q must be between 0.1 and 30" }
        return band
    }

    fun validate(state: PeqState): PeqState {
        require(state.bands.size <= MAX_BANDS) { "At most 170 bands are supported" }
        require(state.gainRange in GAIN_RANGES) { "Unsupported gain display range" }
        require(state.gainStep in GAIN_STEPS) { "Unsupported gain adjustment step" }
        state.bands.forEach(::validateBand)
        require(state.bands.map { it.id }.toSet().size == state.bands.size) { "Band ids must be unique" }
        return state
    }

    fun defaultState(count: Int = 10): PeqState {
        require(count in 0..MAX_BANDS) { "Band count must be between 0 and 170" }
        val tenBandFrequencies = listOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0,
            2000.0, 4000.0, 8000.0, 16000.0)
        return PeqState(List(count) { index ->
            PeqBand(id = "default-${index + 1}", frequency =
                if (count == 10) tenBandFrequencies[index] else distributedFrequency(index, count))
        })
    }

    /** Existing bands, ids and gains are retained exactly; shrinking is confirmed by the UI. */
    fun resize(state: PeqState, count: Int): PeqState {
        validate(state)
        require(count in 0..MAX_BANDS) { "Band count must be between 0 and 170" }
        if (count <= state.bands.size) return state.copy(bands = state.bands.take(count))
        return state.copy(bands = state.bands + List(count - state.bands.size) { offset ->
            PeqBand(frequency = distributedFrequency(state.bands.size + offset, count))
        })
    }

    private fun distributedFrequency(index: Int, count: Int): Double =
        if (count <= 1) 1000.0 else MIN_FREQUENCY *
            (MAX_FREQUENCY / MIN_FREQUENCY).pow(index.toDouble() / (count - 1))
}
