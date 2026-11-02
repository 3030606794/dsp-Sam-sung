package me.timschneeberger.rootlessjamesdsp.peq

/** Validates the whole bank before constructing a replacement. No partial updates or clamping. */
object PeqBulkParameters {
    fun bandwidthRange(state: PeqState): ClosedFloatingPointRange<Double> {
        require(state.bands.isNotEmpty())
        return PeqModel.MIN_BW..PeqModel.MAX_BW
    }

    fun quality(state: PeqState, mode: PeqQualityDisplay, value: Double): PeqState {
        require(value.isFinite())
        val bands = state.bands.map { band ->
            if (band.qualityDisplay != mode) return@map band
            val q = if (mode == PeqQualityDisplay.Q) value else PeqResponse.qFromBandwidth(value, band.frequency)
            if (mode == PeqQualityDisplay.BW) require(value in PeqModel.MIN_BW..PeqModel.MAX_BW)
            val maximum = PeqResponse.maximumQ(band.frequency,
                if (mode == PeqQualityDisplay.BW) PeqModel.MAX_Q else state.qRange)
            require(q.isFinite() && q >= PeqModel.MIN_Q - 64 * Math.ulp(PeqModel.MIN_Q) &&
                q <= maximum + 64 * Math.ulp(maximum))
            band.copy(q = q.coerceIn(PeqModel.MIN_Q, maximum))
        }
        return state.copy(bands = bands)
    }

    fun filterType(state: PeqState, type: PeqFilterType): PeqState =
        state.copy(bands = state.bands.map { it.copy(type = type) })

    /** A batch draft never converts Q cards into BW cards, or changes protected fields. */
    fun applyDraft(state: PeqState, ids: Set<String>, canEdit: (String, PeqEditField?) -> Boolean,
                   q: Double? = null, bw: Double? = null, type: PeqFilterType? = null): PeqState {
        var next = state
        listOf(PeqQualityDisplay.Q to q, PeqQualityDisplay.BW to bw).forEach { (mode, value) ->
            if (value != null) {
                val eligible = next.bands.filter { it.id in ids && it.qualityDisplay == mode &&
                    canEdit(it.id, PeqEditField.QUALITY) }.mapTo(hashSetOf()) { it.id }
                if (eligible.isNotEmpty()) next = PeqSelection.update(next, eligible) { quality(it, mode, value) }
            }
        }
        if (type != null) {
            val eligible = next.bands.filter { it.id in ids && canEdit(it.id, PeqEditField.FILTER_TYPE) }
                .mapTo(hashSetOf()) { it.id }
            if (eligible.isNotEmpty()) next = PeqSelection.update(next, eligible) { filterType(it, type) }
        }
        return next
    }
}
