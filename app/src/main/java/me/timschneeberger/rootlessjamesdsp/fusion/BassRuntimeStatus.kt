package me.timschneeberger.rootlessjamesdsp.fusion

/** Runtime readings are never saved in device profiles. Each stream must advance before it is displayed. */
object BassRuntimeStatus {
    enum class State { ACTIVE, IDLE, OFF, UNSUPPORTED, UNKNOWN, MULTIPLE }
    data class Reading(val identity: Any, val enabled: Boolean, val supported: Boolean,
        val frames: Int?, val frequencyHundredths: Int?, val gainHundredths: Int?,
        val hostRate: Int? = null, val sessionId: Int? = null)
    data class Snapshot(val state: State, val frequencyHundredths: Int? = null, val gainHundredths: Int? = null)
    class Tracker {
        private var previous = emptyMap<Any, Reading>()
        var activeStreamIdentity: Any? = null
            private set
        fun update(readings: List<Reading>, bassEnabled: Boolean): Snapshot {
            fun comparable(reading: Reading, before: Reading?) = before != null && before.enabled &&
                before.supported && before.hostRate == reading.hostRate && before.sessionId == reading.sessionId
            fun advanced(frames: Int, oldFrames: Int) = frames > oldFrames ||
                (oldFrames >= 0x70000000 && frames <= 0x0fffffff)
            val active = readings.filter { reading ->
                val before = previous[reading.identity]
                val frames = reading.frames?.takeIf { it >= 0 }
                val oldFrames = before?.frames?.takeIf { it >= 0 }
                reading.enabled && reading.supported && comparable(reading, before) &&
                    frames != null && oldFrames != null && advanced(frames, oldFrames)
            }
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
                readings.any { reading ->
                    val before = previous[reading.identity]
                    val frames = reading.frames?.takeIf { it >= 0 }
                    val oldFrames = before?.frames?.takeIf { it >= 0 }
                    reading.enabled && reading.supported && (frames == null || oldFrames == null ||
                        !comparable(reading, before) || (frames < oldFrames && !advanced(frames, oldFrames)))
                } -> Snapshot(State.UNKNOWN)
                else -> Snapshot(State.IDLE)
            }
            activeStreamIdentity = if (result.state == State.ACTIVE) active.single().identity else null
            previous = readings.associateBy { it.identity }
            return result
        }
        fun clear() { previous = emptyMap(); activeStreamIdentity = null }
    }
}
