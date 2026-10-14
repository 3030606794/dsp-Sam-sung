package me.timschneeberger.rootlessjamesdsp.fusion

import org.junit.Assert.*
import org.junit.Test

class BassRuntimeStatusTest {
    private fun reading(frames: Int?, identity: Any = "a", enabled: Boolean = true, supported: Boolean = true,
        hz: Int? = 7000, gain: Int? = 230) = BassRuntimeStatus.Reading(identity, enabled, supported, frames, hz, gain)

    @Test fun onlyAdvancingEnabledStreamsProduceReadingsAndIdleNeverLooksLikeZeroGain() {
        val tracker = BassRuntimeStatus.Tracker()
        assertEquals(BassRuntimeStatus.State.UNKNOWN, tracker.update(listOf(reading(0)), true).state)
        assertEquals(BassRuntimeStatus.Snapshot(BassRuntimeStatus.State.ACTIVE, 7000, 230), tracker.update(listOf(reading(1024)), true))
        assertEquals(BassRuntimeStatus.State.IDLE, tracker.update(listOf(reading(1024)), true).state)
        assertEquals(BassRuntimeStatus.State.IDLE, tracker.update(listOf(reading(2048, enabled = false)), true).state)
        assertEquals(BassRuntimeStatus.State.OFF, tracker.update(listOf(reading(4096)), false).state)
    }

    @Test fun reconnectMissingQueriesAndMultipleSessionsDoNotCreateMisleadingMeasurements() {
        val tracker = BassRuntimeStatus.Tracker()
        tracker.update(listOf(reading(1)), true)
        assertEquals(BassRuntimeStatus.State.UNKNOWN, tracker.update(listOf(reading(2, hz = null)), true).state)
        assertEquals(BassRuntimeStatus.State.UNKNOWN, tracker.update(listOf(reading(3, hz = 25001)), true).state)
        assertEquals(BassRuntimeStatus.State.UNKNOWN, tracker.update(listOf(reading(4, identity = "b")), true).state)
        tracker.update(listOf(reading(10), reading(10, "b")), true)
        assertEquals(BassRuntimeStatus.State.MULTIPLE, tracker.update(listOf(reading(20), reading(20, "b")), true).state)
        assertEquals(BassRuntimeStatus.State.UNSUPPORTED, tracker.update(listOf(reading(30, supported = false)), true).state)
        tracker.clear()
        assertEquals(BassRuntimeStatus.State.UNKNOWN, tracker.update(listOf(reading(50)), true).state)
    }
}
