package me.timschneeberger.rootlessjamesdsp.peq

import java.util.Collections

/**
 * A temporary audition bank, shared with the in-process audio service.
 * It never writes preferences or changes the effect/band enable switches. A card and its filter
 * dialog can hand off the same session, including after confirmation. The host ends it when
 * listening stops, a draft is cancelled/protected, or the host pauses. Process death also clears it.
 */
object PeqPreviewSession {
    class Token internal constructor()
    private data class Preview(val owner: Token, val state: PeqState)
    private var preview: Preview? = null

    /** Starting another dialog's audition supersedes the previous owner. */
    @Synchronized
    fun begin(state: PeqState): Token {
        val snapshot = snapshot(state)
        return Token().also { preview = Preview(it, snapshot) }
    }

    /** A stale dialog cannot overwrite a newer audition. */
    @Synchronized
    fun update(owner: Token, state: PeqState): Boolean {
        if (preview?.owner !== owner) return false
        preview = Preview(owner, snapshot(state))
        return true
    }

    /** Returns true only when this call actually removes the active override. */
    @Synchronized
    fun end(owner: Token): Boolean {
        if (preview?.owner !== owner) return false
        preview = null
        return true
    }

    @Synchronized
    fun current(): PeqState? = preview?.state

    private fun snapshot(state: PeqState): PeqState = PeqModel.validate(
        state.copy(bands = Collections.unmodifiableList(ArrayList(state.bands))))
}
