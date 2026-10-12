package me.timschneeberger.rootlessjamesdsp.peq

/** Validates the whole bank before constructing a replacement. No partial updates or clamping. */
object PeqBulkParameters {
    fun bandwidthRange(state: PeqState): ClosedFloatingPointRange<Double> {
        require(state.bands.isNotEmpty())
        return state.bands.maxOf { PeqResponse.bandwidthOctaves(state.qRange, it.frequency) }..
            state.bands.minOf { PeqResponse.bandwidthOctaves(PeqModel.MIN_Q, it.frequency) }
    }

    fun quality(state: PeqState, mode: PeqQualityDisplay, value: Double): PeqState {
        require(value.isFinite())
        val bands = state.bands.map { band ->
            val q = if (mode == PeqQualityDisplay.Q) value else PeqResponse.qFromBandwidth(value, band.frequency)
            require(q.isFinite() && q >= PeqModel.MIN_Q - 1e-9 && q <= state.qRange + 1e-9)
            band.copy(q = q.coerceIn(PeqModel.MIN_Q, state.qRange), qualityDisplay = mode)
        }
        return state.copy(bands = bands)
    }

    fun filterType(state: PeqState, type: PeqFilterType): PeqState =
        state.copy(bands = state.bands.map { it.copy(type = type) })
}
