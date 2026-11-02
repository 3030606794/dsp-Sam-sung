package me.timschneeberger.rootlessjamesdsp.fusion

import org.junit.Assert.*
import org.junit.Test

/** A mounted/player/DAC rate is not evidence that a particular DSP session is processing. */
class OutputSessionRuntimeTest {
    private fun session(id: Int, rate: Int? = 192000, frames: Int? = 100,
                        identity: Any = "effect-$id", enabled: Boolean = true) =
        OutputRuntimeStatus.Session(identity, enabled, rate, frames, id)

    @Test fun nonzeroSidAndRateArePairedOnlyAfterThatEffectActuallyAdvancesFrames() {
        val tracker = OutputRuntimeStatus.Tracker()
        val mounted = session(125)
        assertTrue(tracker.update(listOf(mounted)).activeSessions.isEmpty())
        assertEquals(OutputRuntimeStatus.Processing.IDLE, tracker.update(listOf(mounted)).processing)
        val playing = tracker.update(listOf(mounted.copy(processedFrames = 612)))
        assertEquals(OutputRuntimeStatus.Processing.ACTIVE, playing.processing)
        assertEquals(listOf(OutputRuntimeStatus.ActiveSession(125, 192000)), playing.activeSessions)
        val paused = tracker.update(listOf(mounted.copy(processedFrames = 612)))
        assertTrue(paused.connected)
        assertEquals(OutputRuntimeStatus.Processing.IDLE, paused.processing)
        assertTrue(paused.activeSessions.isEmpty())
        assertTrue(paused.rates.isEmpty())
    }

    @Test fun globalZeroSidRemainsAnActualSessionAndUnknownSidIsNeverInventedAsZero() {
        val tracker = OutputRuntimeStatus.Tracker()
        val global = session(0, 48000)
        tracker.update(listOf(global))
        assertEquals(listOf(OutputRuntimeStatus.ActiveSession(0, 48000)),
            tracker.update(listOf(global.copy(processedFrames = 612))).activeSessions)
        val unidentified = global.copy(identity = "legacy", sessionId = null)
        tracker.update(listOf(unidentified))
        assertEquals(listOf(OutputRuntimeStatus.ActiveSession(null, 48000)),
            tracker.update(listOf(unidentified.copy(processedFrames = 612))).activeSessions)
    }

    @Test fun multiplePlayersKeepTheirOwnRatesAndIdlePlayersNeverBorrowAnActivePlayersRate() {
        val tracker = OutputRuntimeStatus.Tracker()
        val first = session(125, 192000)
        val second = session(200, 48000)
        val paused = session(300, 768000)
        tracker.update(listOf(paused, second, first))
        val status = tracker.update(listOf(first.copy(processedFrames = 200), paused,
            second.copy(processedFrames = 200)))
        assertEquals(listOf(OutputRuntimeStatus.ActiveSession(125, 192000),
            OutputRuntimeStatus.ActiveSession(200, 48000)), status.activeSessions)
        assertEquals(listOf(48000, 192000), status.rates)
        assertFalse(status.activeSessions.any { it.sessionId == 300 })
    }

    @Test fun replacementIdentityChangedSidAndChangedRateEachNeedFreshProgress() {
        val tracker = OutputRuntimeStatus.Tracker()
        var current = session(125)
        tracker.update(listOf(current))
        current = current.copy(processedFrames = 200)
        assertEquals(1, tracker.update(listOf(current)).activeSessions.size)
        for (next in listOf(current.copy(identity = "replacement", processedFrames = 300),
            current.copy(identity = "replacement", sessionId = 126, processedFrames = 500),
            current.copy(identity = "replacement", sessionId = 126, hostRate = 48000, processedFrames = 700))) {
            assertTrue(tracker.update(listOf(next)).activeSessions.isEmpty())
            assertEquals(listOf(OutputRuntimeStatus.ActiveSession(next.sessionId, requireNotNull(next.hostRate))),
                tracker.update(listOf(next.copy(processedFrames = next.processedFrames!! + 100))).activeSessions)
        }
    }

    @Test fun disableCounterResetAndInvalidReadsDoNotReportStaleActiveSessions() {
        val tracker = OutputRuntimeStatus.Tracker()
        val mounted = session(125, frames = 1000)
        tracker.update(listOf(mounted))
        val disabled = mounted.copy(enabled = false, processedFrames = 2000)
        assertTrue(tracker.update(listOf(disabled)).activeSessions.isEmpty())
        val enabled = disabled.copy(enabled = true, processedFrames = 3000)
        assertTrue(tracker.update(listOf(enabled)).activeSessions.isEmpty())
        assertEquals(1, tracker.update(listOf(enabled.copy(processedFrames = 4000))).activeSessions.size)
        val reset = enabled.copy(processedFrames = 0)
        assertEquals(OutputRuntimeStatus.Processing.UNKNOWN, tracker.update(listOf(reset)).processing)
        assertEquals(OutputRuntimeStatus.Processing.IDLE, tracker.update(listOf(reset)).processing)
        for (bad in listOf(reset.copy(processedFrames = -1), reset.copy(processedFrames = null),
            reset.copy(hostRate = null), reset.copy(hostRate = 0), reset.copy(hostRate = 768001))) {
            assertTrue(tracker.update(listOf(bad)).activeSessions.isEmpty())
        }
        assertFalse(tracker.update(emptyList()).connected)
        assertTrue(tracker.update(listOf(mounted)).activeSessions.isEmpty())
    }

    @Test fun legalCounterWrapIsActualProgressButResumingTheScreenNeedsANewBaseline() {
        val tracker = OutputRuntimeStatus.Tracker()
        val old = session(125, frames = Int.MAX_VALUE - 2)
        tracker.update(listOf(old))
        assertEquals(listOf(OutputRuntimeStatus.ActiveSession(125, 192000)),
            tracker.update(listOf(old.copy(processedFrames = 3))).activeSessions)
        tracker.clear()
        assertTrue(tracker.update(listOf(old.copy(processedFrames = 10000))).activeSessions.isEmpty())
    }
}
