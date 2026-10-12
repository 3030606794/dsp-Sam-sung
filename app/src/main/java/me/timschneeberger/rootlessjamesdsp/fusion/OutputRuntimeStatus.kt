package me.timschneeberger.rootlessjamesdsp.fusion

/** Runtime-only values: never cache these in profiles or substitute a device's advertised rates. */
object OutputRuntimeStatus {
    data class Session(val identity: Any, val enabled: Boolean, val hostRate: Int?, val processedFrames: Int?)
    enum class Processing { ACTIVE, IDLE, UNKNOWN }
    data class Snapshot(val connected: Boolean, val processing: Processing, val rates: List<Int> = emptyList())

    class Tracker {
        private var previous = emptyMap<Any, Session>()

        fun update(sessions: List<Session>): Snapshot {
            val active = mutableListOf<Int>()
            var unknown = false
            sessions.filter { it.enabled }.forEach { session ->
                val before = previous[session.identity]
                val rate = session.hostRate?.takeIf { it in 8000..192000 }
                when {
                    rate == null || session.processedFrames == null || before?.processedFrames == null -> unknown = true
                    before.hostRate != rate -> unknown = true
                    before.processedFrames != session.processedFrames -> active += rate
                }
            }
            previous = sessions.associateBy { it.identity }
            return Snapshot(sessions.isNotEmpty(), when {
                active.isNotEmpty() -> Processing.ACTIVE
                unknown -> Processing.UNKNOWN
                else -> Processing.IDLE
            }, active.distinct().sorted())
        }

        fun clear() { previous = emptyMap() }
    }
}
