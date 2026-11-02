package me.timschneeberger.rootlessjamesdsp.session.root

/** Keep an initialized player effect through pauses and song gaps, without guessing new SIDs. */
class RootSessionContinuity {
    private val attached = mutableMapOf<Int, ActivePlaybackSession>()

    fun attached(player: ActivePlaybackSession) { attached[player.sessionId] = player }
    fun removed(sessionId: Int) { attached.remove(sessionId) }

    /**
     * An inactive track is not a closed player. Retire its effect only when its process exits,
     * ownership changes, or that same player has an attached replacement SID.
     * A missing discovery snapshot is not evidence that an existing connection disappeared.
     */
    fun sessionsToRelease(
        active: Map<Int, ActivePlaybackSession>,
        processes: Map<Int, ActivePlaybackSessionParser.ProcessIdentity>?,
    ): Set<Int> = attached.filter { (id, owner) ->
        val current = active[id]
        when {
            current != null -> current.uid != owner.uid || current.packageName != owner.packageName ||
                current.pid != owner.pid
            // Android applications may use a custom process name. The verified UID and PID
            // identify the existing process; a display name is not package-ownership evidence.
            processes != null && processes[owner.pid]?.uid != owner.uid -> true
            else -> active.values.any { replacement ->
                replacement.uid == owner.uid && replacement.packageName == owner.packageName &&
                    attached[replacement.sessionId] == replacement
            }
        }
    }.keys
}

/** Wait for the same SID and global/player transitions; unrelated player releases may finish later. */
object RootSessionReleaseBarrier {
    fun permits(sessionId: Int, closing: Set<Int>): Boolean =
        if (sessionId == 0) closing.isEmpty() else sessionId !in closing && 0 !in closing
}
