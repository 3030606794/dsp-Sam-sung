package me.timschneeberger.rootlessjamesdsp.fusion

/** Runtime readings are never saved in device profiles. Each stream must advance before it is displayed. */
object BassRuntimeStatus {
    enum class State { ACTIVE, IDLE, OFF, UNSUPPORTED, UNKNOWN, MULTIPLE }
    data class Reading(val identity: Any, val enabled: Boolean, val supported: Boolean,
        val frames: Int?, val frequencyHundredths: Int?, val gainHundredths: Int?)
    data class Snapshot(val state: State, val frequencyHundredths: Int? = null, val gainHundredths: Int? = null)
    class Tracker {
        private var previous = emptyMap<Any, Int?>()
        fun update(readings: List<Reading>, bassEnabled: Boolean): Snapshot {
            val active = readings.filter { it.enabled && it.supported && it.frames != null &&
                previous[it.identity] != null && previous[it.identity] != it.frames }
            val result = when {
                !bassEnabled -> Snapshot(State.OFF)
                readings.isEmpty() -> Snapshot(State.UNKNOWN)
                readings.none { it.supported } -> Snapshot(State.UNSUPPORTED)
                active.size > 1 -> Snapshot(State.MULTIPLE)
                active.size == 1 -> active.single().let {
                    if (it.frequencyHundredths != null && it.gainHundredths != null &&
                        it.frequencyHundredths in 0..25000 && it.gainHundredths in 0..1500)
                        Snapshot(State.ACTIVE, it.frequencyHundredths, it.gainHundredths) else Snapshot(State.UNKNOWN)
                }
                readings.any { it.enabled && it.supported && (it.frames == null || previous[it.identity] == null) } -> Snapshot(State.UNKNOWN)
                else -> Snapshot(State.IDLE)
            }
            previous = readings.associate { it.identity to it.frames }
            return result
        }
        fun clear() { previous = emptyMap() }
    }
}
