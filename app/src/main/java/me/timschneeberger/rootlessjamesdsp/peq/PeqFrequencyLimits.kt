package me.timschneeberger.rootlessjamesdsp.peq

import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus

/** Actual processing limits are independent of a device's advertised rates and graph zoom. */
internal object PeqFrequencyLimits {
    fun sampleRate(rate: Double?): Double = rate?.takeIf {
        it.isFinite() && it in 8000.0..768000.0
    } ?: PeqResponse.DEFAULT_SAMPLE_RATE

    fun maximum(rate: Double?): Double = sampleRate(rate) / 2.0

    fun editorRange(rate: Double?): ClosedFloatingPointRange<Double> =
        PeqModel.MIN_FREQUENCY..maximum(rate)

    fun graphRange(state: PeqState, rate: Double?): ClosedFloatingPointRange<Double> =
        state.graphMinFrequency..minOf(state.graphMaxFrequency, maximum(rate))

    /** Saving unrelated settings must not turn the temporary processing cap into a saved zoom. */
    fun preferredGraphMaximum(previous: Double, displayed: Double, edited: Boolean): Double =
        if (edited) displayed else previous

    class RuntimeTracker {
        private val progress = OutputRuntimeStatus.Tracker()
        var effectiveRate: Double = PeqResponse.DEFAULT_SAMPLE_RATE
            private set

        fun update(sessions: List<OutputRuntimeStatus.Session>): Double {
            val snapshot = progress.update(sessions)
            // A config rate alone can outlive the stream. Only same-session frame progress
            // confirms it; the most restrictive simultaneous active session defines the bank.
            if (snapshot.processing == OutputRuntimeStatus.Processing.ACTIVE)
                snapshot.rates.minOrNull()?.let { effectiveRate = it.toDouble() }
            // Tighten immediately during a rate transition; widening requires frame progress.
            sessions.filter { it.enabled }.mapNotNull { it.hostRate?.takeIf { rate -> rate in 8000..768000 } }
                .minOrNull()?.let { effectiveRate = minOf(effectiveRate, it.toDouble()) }
            return effectiveRate
        }

        fun suspend() { progress.clear() }
    }
}
