package me.timschneeberger.rootlessjamesdsp.peq

import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

enum class PeqFilterType(val wireId: Int, val usesGain: Boolean) {
    PEAK(0, true), LOW_SHELF(1, true), HIGH_SHELF(2, true),
    LOW_PASS(3, true), HIGH_PASS(4, true), NOTCH(5, true), BAND_PASS(6, true);

    /** Zero gain bypasses only peak/shelf filters; pass/notch filters keep their shape. */
    val isUnityAtZeroGain: Boolean get() = this == PEAK || this == LOW_SHELF || this == HIGH_SHELF
}

enum class PeqQualityDisplay { Q, BW }
enum class PeqResetTarget { Q, FREQUENCY, GAIN, ALL }
enum class PeqResizeEdge { FRONT, BACK }

data class PeqBand(
    val id: String = UUID.randomUUID().toString(),
    val type: PeqFilterType = PeqFilterType.PEAK,
    val enabled: Boolean = true,
    val frequency: Double = 1000.0,
    val gain: Double = 0.0,
    val q: Double = 0.7071067811865476,
    val qualityDisplay: PeqQualityDisplay = PeqQualityDisplay.Q
)

/** The effect's enable switch stays in the existing key_geq_enable preference. */
data class PeqState(
    val bands: List<PeqBand>,
    val gainRange: Double = 18.0,
    val gainStep: Double = 0.1,
    val showQSlider: Boolean = false,
    val showFrequencySlider: Boolean = false,
    val qStep: Double = 0.01,
    val frequencyStep: Double = 1.0,
    val pinResponse: Boolean = true
)

object PeqModel {
    const val MAX_BANDS = 170
    const val MIN_FREQUENCY = 20.0
    const val MAX_FREQUENCY = 20000.0
    const val MAX_GAIN = 100.0
    const val MIN_Q = 0.1
    const val MAX_Q = 100.0
    const val DEFAULT_Q = 0.7071067811865476

    /** A newly added 0 Hz band is an unset placeholder, not a DC filter. */
    fun validFrequency(frequency: Double): Boolean = frequency.isFinite() &&
        (frequency == 0.0 || frequency in MIN_FREQUENCY..MAX_FREQUENCY)

    /** Match the driver's millihertz precision, including its positive half-step rounding. */
    internal fun frequencyKey(frequency: Double): Long = (frequency * 1000.0).roundToLong()

    fun frequencyConflict(state: PeqState, frequency: Double, excludingBandId: String? = null): PeqBand? {
        require(validFrequency(frequency)) { "Frequency must be 0 (unset), or between 20 and 20000 Hz" }
        if (frequency == 0.0) return null
        val key = frequencyKey(frequency)
        return state.bands.firstOrNull { it.id != excludingBandId && it.frequency > 0.0 &&
            frequencyKey(it.frequency) == key }
    }

    /** Preserve the first assigned frequency and clear later duplicates without changing other parameters. */
    fun normalizeDuplicateFrequencies(state: PeqState): PeqState {
        state.bands.forEach(::validateBand)
        val seen = HashSet<Long>()
        var changed = false
        val bands = state.bands.map { band ->
            if (band.frequency > 0.0 && !seen.add(frequencyKey(band.frequency))) {
                changed = true
                band.copy(frequency = 0.0)
            } else band
        }
        return validate(if (changed) state.copy(bands = bands) else state)
    }

    /** Reset only requested parameters, preserving the bank and editor preferences. */
    fun reset(state: PeqState, target: PeqResetTarget): PeqState {
        validate(state)
        val next = state.copy(bands = state.bands.map { band ->
            band.copy(q = if (target == PeqResetTarget.Q || target == PeqResetTarget.ALL) DEFAULT_Q else band.q,
                gain = if (target == PeqResetTarget.GAIN || target == PeqResetTarget.ALL) 0.0 else band.gain)
        })
        return if (target == PeqResetTarget.FREQUENCY || target == PeqResetTarget.ALL) redistribute(next) else next
    }

    fun validateBand(band: PeqBand): PeqBand {
        require(band.id.isNotBlank() && band.id.length <= 128 && band.id.none { it.isISOControl() }) {
            "Band id must be a non-empty, printable string of at most 128 characters"
        }
        require(validFrequency(band.frequency)) {
            "Frequency must be 0 (unset), or between 20 and 20000 Hz"
        }
        require(band.gain.isFinite() && band.gain in -MAX_GAIN..MAX_GAIN) {
            "Gain must be between -100 and 100 dB"
        }
        require(band.q.isFinite() && band.q in MIN_Q..MAX_Q) { "Q must be between 0.1 and 100" }
        return band
    }

    fun validate(state: PeqState): PeqState {
        require(state.bands.size <= MAX_BANDS) { "At most 170 bands are supported" }
        require(state.gainRange.isFinite() && state.gainRange in 1.0..100.0) { "Gain range must be between 1 and 100 dB" }
        require(state.gainStep.isFinite() && state.gainStep in 0.01..2.0) { "Gain step must be between 0.01 and 2 dB" }
        require(state.qStep.isFinite() && state.qStep in 0.01..100.0) { "Q/BW step must be between 0.01 and 100" }
        require(state.frequencyStep.isFinite() && state.frequencyStep in 1.0..1000.0) { "Frequency step must be between 1 and 1000 Hz" }
        state.bands.forEach(::validateBand)
        require(state.bands.map { it.id }.toSet().size == state.bands.size) { "Band ids must be unique" }
        val frequencies = HashSet<Long>()
        require(state.bands.all { it.frequency == 0.0 || frequencies.add(frequencyKey(it.frequency)) }) {
            "非零频率不能重复（精度为 0.001 Hz）"
        }
        return state
    }

    fun defaultState(count: Int = 10): PeqState {
        require(count in 0..MAX_BANDS) { "Band count must be between 0 and 170" }
        return PeqState(List(count) { index ->
            PeqBand(id = "default-${index + 1}", frequency = distributedFrequency(index, count))
        })
    }

    /** Count changes redistribute frequencies; all retained bands keep their other parameters. */
    fun resize(state: PeqState, count: Int): PeqState {
        validate(state)
        require(count in 0..MAX_BANDS) { "Band count must be between 0 and 170" }
        if (count == state.bands.size) return state
        val retained = state.bands.take(count)
        val bands = retained + List((count - retained.size).coerceAtLeast(0)) { PeqBand(frequency = 0.0) }
        return redistribute(state.copy(bands = bands))
    }

    /** New editor bands start unset; their parameters become audible only after setting a frequency. */
    fun blankBand(): PeqBand = PeqBand(frequency = 0.0, gain = 0.0, q = 0.5)

    /** Change one end of the bank, retaining every surviving band's identity and parameters. */
    fun resize(state: PeqState, count: Int, edge: PeqResizeEdge, redistribute: Boolean): PeqState {
        validate(state)
        require(count in 0..MAX_BANDS) { "Band count must be between 0 and 170" }
        val difference = count - state.bands.size
        val resized = if (difference == 0) state else state.copy(bands = when {
            difference > 0 -> {
                val added = List(difference) { blankBand() }
                if (edge == PeqResizeEdge.FRONT) added + state.bands else state.bands + added
            }
            edge == PeqResizeEdge.FRONT -> state.bands.takeLast(count)
            else -> state.bands.take(count)
        })
        return if (redistribute) redistribute(resized) else resized
    }

    /** Explicitly requested operation: overwrite only the frequencies, including manually set ones. */
    fun redistribute(state: PeqState): PeqState {
        validate(state)
        return state.copy(bands = state.bands.mapIndexed { index, band ->
            band.copy(frequency = distributedFrequency(index, state.bands.size))
        })
    }

    /**
     * Conservative alpha6 detection. There was no stored provenance, so recognize only a
     * complete untouched default prefix, optionally followed by one generated expansion.
     * Complex edit/resize histories remain unchanged and can be redistributed explicitly.
     */
    fun isOldGeneratedDistribution(state: PeqState): Boolean {
        val count = state.bands.size
        if (count !in 1..MAX_BANDS) return false
        val oldTen = listOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0,
            2000.0, 4000.0, 8000.0, 16000.0)
        return state.bands.withIndex().all { (index, band) ->
            if (index < 10) band.id == "default-${index + 1}" &&
                frequencyMatches(band.frequency, oldTen[index])
            else frequencyMatches(band.frequency, distributedFrequency(index, count))
        }
    }

    private fun distributedFrequency(index: Int, count: Int): Double =
        if (count <= 1) (MIN_FREQUENCY * MAX_FREQUENCY).pow(0.5)
        else if (index == count - 1) MAX_FREQUENCY
        else MIN_FREQUENCY * (MAX_FREQUENCY / MIN_FREQUENCY).pow(index.toDouble() / (count - 1))

    private fun frequencyMatches(actual: Double, expected: Double): Boolean =
        actual.isFinite() && abs(actual - expected) <= maxOf(1e-10, abs(expected) * 1e-12)
}
