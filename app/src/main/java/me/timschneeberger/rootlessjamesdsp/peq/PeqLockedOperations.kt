package me.timschneeberger.rootlessjamesdsp.peq

/** All bulk parameter paths share the same field-level protection as direct card edits. */
class PeqLockedOperations(private val canEdit: (String, PeqEditField?) -> Boolean) {
    fun eligible(state: PeqState, ids: Set<String>, field: PeqEditField? = null): Set<String> =
        state.bands.filter { it.id in ids && canEdit(it.id, field) }.mapTo(linkedSetOf()) { it.id }

    data class ResetResult(val state: PeqState, val editableCount: Int, val protectedCount: Int)

    data class ResetCounts(val editableCount: Int, val protectedCount: Int)

    private fun fields(target: PeqResetTarget) = when (target) {
        PeqResetTarget.Q -> listOf(PeqEditField.QUALITY)
        PeqResetTarget.FREQUENCY -> listOf(PeqEditField.FREQUENCY)
        PeqResetTarget.GAIN -> listOf(PeqEditField.GAIN)
        PeqResetTarget.FILTER_TYPE -> listOf(PeqEditField.FILTER_TYPE)
        PeqResetTarget.ALL -> PeqEditField.entries
    }

    /** Partially locked cards can still reset their unlocked fields; only wholly blocked cards skip. */
    fun resetCounts(state: PeqState, ids: Set<String>, target: PeqResetTarget): ResetCounts {
        val targets = state.bands.filter { it.id in ids }
        val fields = fields(target)
        val editable = targets.count { band -> fields.any { canEdit(band.id, it) } }
        return ResetCounts(editable, targets.size - editable)
    }

    fun reset(state: PeqState, ids: Set<String>, target: PeqResetTarget): ResetResult {
        val fields = fields(target)
        val frequencies = if (PeqEditField.FREQUENCY in fields) {
            val unlocked = eligible(state, ids, PeqEditField.FREQUENCY)
            if (unlocked.isEmpty()) emptyMap() else PeqSelection.redistribute(state, unlocked).bands.associate { it.id to it.frequency }
        } else emptyMap()
        var editable = 0
        var protected = 0
        val next = state.copy(bands = state.bands.map { band ->
            if (band.id !in ids) return@map band
            val allowed = fields.filter { canEdit(band.id, it) }
            if (allowed.isNotEmpty()) editable++
            if (allowed.isEmpty()) protected++
            band.copy(
                q = if (PeqEditField.QUALITY in allowed) PeqModel.DEFAULT_Q.coerceAtMost(state.qRange) else band.q,
                gain = if (PeqEditField.GAIN in allowed) 0.0 else band.gain,
                frequency = if (PeqEditField.FREQUENCY in allowed) frequencies.getValue(band.id) else band.frequency,
                type = if (PeqEditField.FILTER_TYPE in allowed) PeqFilterType.PEAK else band.type
            )
        })
        return ResetResult(PeqModel.validate(next), editable, protected)
    }
}
