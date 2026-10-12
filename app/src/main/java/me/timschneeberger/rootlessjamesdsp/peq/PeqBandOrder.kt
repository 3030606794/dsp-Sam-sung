package me.timschneeberger.rootlessjamesdsp.peq

/** Changes display/processing order without changing any band's identity or parameters. */
object PeqBandOrder {
    /** [to] is the moved band's final index, including when moving toward the end. */
    fun move(state: PeqState, from: Int, to: Int): PeqState {
        PeqModel.validate(state)
        require(from in state.bands.indices && to in state.bands.indices) {
            "Both band positions must exist in the equalizer"
        }
        if (from == to) return state
        val reordered = state.bands.toMutableList()
        val moved = reordered.removeAt(from)
        reordered.add(to, moved)
        return state.copy(bands = reordered)
    }
}
