package me.timschneeberger.rootlessjamesdsp.peq

enum class PeqSelectionScope { SELECTED, EXCLUDED }

/** Selection belongs to the editor, not to the saved equalizer or its presets. */
object PeqSelection {
    /** Keep surviving stable ids in bank order. Newly inserted bands remain unselected. */
    fun prune(state: PeqState, selectedIds: Set<String>): Set<String> =
        state.bands.mapNotNullTo(linkedSetOf()) { it.id.takeIf(selectedIds::contains) }

    /** Empty targets are useful for disabling actions; actions themselves reject them. */
    fun targets(state: PeqState, selectedIds: Set<String>, scope: PeqSelectionScope): Set<String> =
        state.bands.mapNotNullTo(linkedSetOf()) { band ->
            band.id.takeIf { (it in selectedIds) == (scope == PeqSelectionScope.SELECTED) }
        }

    /** An independent preset bank with the original settings and only the requested bands. */
    fun snapshot(state: PeqState, ids: Set<String>): PeqState {
        PeqModel.validate(state)
        require(ids.isNotEmpty()) { "Select at least one target band" }
        val bands = state.bands.filter { it.id in ids }
        require(bands.size == ids.size) { "Target bands must still exist in the equalizer" }
        return state.copy(bands = bands)
    }

    /**
     * Apply an existing whole-bank operation to a subset, then merge by stable id.
     * Transformations cannot insert/remove bands, change editor settings, or reorder the bank.
     */
    fun update(state: PeqState, ids: Set<String>, transform: (PeqState) -> PeqState): PeqState {
        val subset = snapshot(state, ids)
        val changed = transform(subset)
        require(changed.bands.size == subset.bands.size && changed.bands.map { it.id }.toSet() == ids) {
            "An update must preserve every target band id exactly once"
        }
        val replacements = changed.bands.associateBy { it.id }
        val result = state.copy(bands = state.bands.map { replacements[it.id] ?: it })
        return PeqModel.validate(result)
    }

    /** Frequency resets distribute only the target bands, in their original order. */
    fun reset(state: PeqState, ids: Set<String>, target: PeqResetTarget): PeqState =
        update(state, ids) { PeqModel.reset(it, target) }

    fun redistribute(state: PeqState, ids: Set<String>): PeqState =
        update(state, ids, PeqModel::redistribute)

    /** Batch deletion must leave at least one band available in the editor. */
    fun delete(state: PeqState, ids: Set<String>): PeqState {
        snapshot(state, ids)
        require(ids.size < state.bands.size) { "At least one band must remain" }
        return state.copy(bands = state.bands.filterNot { it.id in ids })
    }
}
