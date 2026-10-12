package me.timschneeberger.rootlessjamesdsp.fusion

import org.junit.Assert.*
import org.junit.Test

class OutputRuntimeStatusTest {
    @Test fun requiresTwoLiveSamplesAndAFrameAdvanceBeforeReportingAHostRate() {
        val tracker = OutputRuntimeStatus.Tracker()
        val initial = OutputRuntimeStatus.Session("live", true, 96000, 0)
        assertEquals(OutputRuntimeStatus.Processing.UNKNOWN, tracker.update(listOf(initial)).processing)
        assertEquals(OutputRuntimeStatus.Processing.IDLE, tracker.update(listOf(initial)).processing)
        val playing = tracker.update(listOf(initial.copy(processedFrames = 512)))
        assertTrue(playing.connected)
        assertEquals(OutputRuntimeStatus.Processing.ACTIVE, playing.processing)
        assertEquals(listOf(96000), playing.rates)
        val paused = tracker.update(listOf(initial.copy(processedFrames = 512)))
        assertEquals(OutputRuntimeStatus.Processing.IDLE, paused.processing)
        assertTrue(paused.rates.isEmpty())
    }

    @Test fun oldDriversAndUnconfiguredSessionsNeverFallBackToAnInventedRate() {
        val tracker = OutputRuntimeStatus.Tracker()
        for (rate in listOf(null, 0, -1, 48000)) {
            val olderDriver = OutputRuntimeStatus.Session("old", true, rate, null)
            repeat(2) {
                val status = tracker.update(listOf(olderDriver))
                assertTrue(status.connected)
                assertEquals(OutputRuntimeStatus.Processing.UNKNOWN, status.processing)
                assertTrue(status.rates.isEmpty())
            }
        }
    }

    @Test fun disableDisconnectAndAChangedHostConfigurationDiscardTheOldDisplay() {
        val tracker = OutputRuntimeStatus.Tracker()
        val live = OutputRuntimeStatus.Session("live", true, 48000, 0)
        tracker.update(listOf(live))
        assertEquals(listOf(48000), tracker.update(listOf(live.copy(processedFrames = 512))).rates)
        val disabled = tracker.update(listOf(live.copy(enabled = false, processedFrames = 1024)))
        assertEquals(OutputRuntimeStatus.Processing.IDLE, disabled.processing)
        assertTrue(disabled.rates.isEmpty())
        val changed = live.copy(hostRate = 192000, processedFrames = 1536)
        assertEquals(OutputRuntimeStatus.Processing.UNKNOWN, tracker.update(listOf(changed)).processing)
        assertEquals(listOf(192000), tracker.update(listOf(changed.copy(processedFrames = 2048))).rates)
        assertFalse(tracker.update(emptyList()).connected)
        assertEquals(OutputRuntimeStatus.Processing.UNKNOWN, tracker.update(listOf(changed)).processing)
        tracker.clear()
        assertEquals(OutputRuntimeStatus.Processing.UNKNOWN, tracker.update(listOf(changed)).processing)
    }

    @Test fun multipleSessionsAndCounterWrapDoNotSelectAnArbitraryOrStaleRate() {
        val tracker = OutputRuntimeStatus.Tracker()
        val first = OutputRuntimeStatus.Session("a", true, 44100, Int.MAX_VALUE - 2)
        val second = OutputRuntimeStatus.Session("b", true, 96000, 100)
        tracker.update(listOf(first, second))
        assertEquals(listOf(44100, 96000), tracker.update(listOf(
            first.copy(processedFrames = 3), second.copy(processedFrames = 612))).rates)
        val replacement = first.copy(identity = "new-driver", processedFrames = 4)
        assertEquals(OutputRuntimeStatus.Processing.UNKNOWN, tracker.update(listOf(replacement)).processing)
    }
}
