package me.timschneeberger.rootlessjamesdsp.fusion

/** Runtime-only values: never cache these in profiles or substitute a device's advertised rates. */
object OutputRuntimeStatus {
    data class Session(val identity: Any, val enabled: Boolean, val hostRate: Int?, val processedFrames: Int?,
                       val sessionId: Int? = null)
    enum class Processing { ACTIVE, IDLE, UNKNOWN }
    enum class Mode { GLOBAL, PLAYER_SESSION }
    enum class GlobalIssue { SUSPENDED, DIFFERENT_OUTPUT, NO_PROCESSING }
    data class ActiveSession(val sessionId: Int?, val rate: Int)
    data class Snapshot(val connected: Boolean, val processing: Processing, val rates: List<Int> = emptyList(),
                        val activeSessions: List<ActiveSession> = emptyList(), val mode: Mode? = null,
                        val globalIssue: GlobalIssue? = null)

    class Tracker {
        private var previous = emptyMap<Any, Session>()

        fun update(sessions: List<Session>): Snapshot {
            val active = mutableListOf<ActiveSession>()
            var unknown = false
            sessions.filter { it.enabled }.forEach { session ->
                val before = previous[session.identity]
                val rate = session.hostRate?.takeIf { it in 8000..768000 }
                val frames = session.processedFrames?.takeIf { it >= 0 }
                val oldFrames = before?.processedFrames?.takeIf { it >= 0 }
                when {
                    rate == null || frames == null || oldFrames == null -> unknown = true
                    !before.enabled || before.hostRate != rate || before.sessionId != session.sessionId -> unknown = true
                    frames > oldFrames || (oldFrames >= 0x70000000 && frames <= 0x0fffffff) ->
                        active += ActiveSession(session.sessionId?.takeIf { it >= 0 }, rate)
                    frames < oldFrames -> unknown = true // counter reset is not proof of processing
                }
            }
            previous = sessions.associateBy { it.identity }
            return Snapshot(sessions.isNotEmpty(), when {
                active.isNotEmpty() -> Processing.ACTIVE
                unknown -> Processing.UNKNOWN
                else -> Processing.IDLE
            }, active.map { it.rate }.distinct().sorted(),
                active.distinct().sortedWith(compareBy({ it.sessionId ?: Int.MAX_VALUE }, { it.rate })))
        }

        fun clear() { previous = emptyMap() }
    }
}
