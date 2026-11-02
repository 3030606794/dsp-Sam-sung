package me.timschneeberger.rootlessjamesdsp.session.root

import org.junit.Assert.*
import org.junit.Test

class RootSessionContinuityTest {
    private val first = ActivePlaybackSession(585, 10450, 2100, "com.player")
    private val process = ActivePlaybackSessionParser.ProcessIdentity(first.pid, first.uid, first.packageName)
    private val live = mapOf(process.pid to process)

    @Test fun pauseAndSongGapKeepTheInitializedInstanceUntilThePlayerProcessExits() {
        val continuity = RootSessionContinuity().apply { attached(first) }
        repeat(100) { assertTrue(continuity.sessionsToRelease(emptyMap(), live).isEmpty()) }
        assertTrue(continuity.sessionsToRelease(mapOf(first.sessionId to first), live).isEmpty())
        assertEquals(setOf(first.sessionId), continuity.sessionsToRelease(emptyMap(), emptyMap()))
    }

    @Test fun unavailableDiscoveryKeepsExistingConnectionAndDoesNotInventAnySession() {
        val continuity = RootSessionContinuity()
        assertTrue(continuity.sessionsToRelease(mapOf(first.sessionId to first), null).isEmpty())
        continuity.attached(first)
        repeat(10) { assertTrue(continuity.sessionsToRelease(emptyMap(), null).isEmpty()) }
    }

    @Test fun newSidRetiresOldIdleSidOnlyAfterReplacementIsAttached() {
        val continuity = RootSessionContinuity().apply { attached(first) }
        val next = first.copy(sessionId = 593)
        val active = mapOf(next.sessionId to next)
        assertTrue(continuity.sessionsToRelease(active, live).isEmpty())
        continuity.attached(next)
        assertEquals(setOf(first.sessionId), continuity.sessionsToRelease(active, live))
        continuity.removed(first.sessionId)
        assertTrue(continuity.sessionsToRelease(emptyMap(), live).isEmpty())
    }

    @Test fun concurrentTracksAndOtherPlayersDoNotEvictEachOther() {
        val continuity = RootSessionContinuity().apply { attached(first) }
        val second = first.copy(sessionId = 593)
        continuity.attached(second)
        assertTrue(continuity.sessionsToRelease(mapOf(first.sessionId to first, second.sessionId to second), live).isEmpty())
        val other = ActivePlaybackSession(601, 10500, 2200, "other.player")
        continuity.attached(other)
        assertTrue(continuity.sessionsToRelease(mapOf(other.sessionId to other), null).isEmpty())
    }

    @Test fun reusedSidPidAndChangedUidNeverInheritAnOldEffect() {
        val continuity = RootSessionContinuity().apply { attached(first) }
        for (changed in listOf(first.copy(uid = 10451), first.copy(packageName = "other.player"), first.copy(pid = 2200))) {
            assertEquals(setOf(first.sessionId), continuity.sessionsToRelease(mapOf(changed.sessionId to changed), null))
        }
        assertEquals(setOf(first.sessionId), continuity.sessionsToRelease(emptyMap(), mapOf(process.pid to process.copy(uid = 10451))))
        assertTrue(continuity.sessionsToRelease(emptyMap(), mapOf(process.pid to process.copy(processName = "custom.audio.process"))).isEmpty())
    }

    @Test fun unrelatedPlayerReleaseNeverBlocksANewTrackButModeAndSidReleasesDo() {
        assertTrue(RootSessionReleaseBarrier.permits(593, setOf(585)))
        assertFalse(RootSessionReleaseBarrier.permits(585, setOf(585)))
        assertFalse(RootSessionReleaseBarrier.permits(593, setOf(0)))
        assertFalse(RootSessionReleaseBarrier.permits(0, setOf(585)))
        assertTrue(RootSessionReleaseBarrier.permits(0, emptySet()))
    }
}
