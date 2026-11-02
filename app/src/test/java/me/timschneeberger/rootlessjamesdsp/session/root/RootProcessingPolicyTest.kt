package me.timschneeberger.rootlessjamesdsp.session.root

import org.junit.Assert.*
import org.junit.Test

class RootProcessingPolicyTest {
    @Test fun explicitGlobalAlwaysAcceptsOnlySessionZeroEvenWhenOldPlayerFlagsAreTrue() {
        for (hd in listOf(false, true)) for (enhanced in listOf(false, true)) {
            assertFalse(RootProcessingPolicy.playerSessions(global = true, hd = hd, enhanced = enhanced))
            assertTrue(RootProcessingPolicy.acceptsSession(false, 0))
            assertFalse(RootProcessingPolicy.acceptsSession(false, 353))
            assertEquals(hd || enhanced, RootProcessingPolicy.playerSessions(global = false, hd = hd, enhanced = enhanced))
        }
    }
    @Test fun globalSuspensionOrAnotherOutputIsDiagnosticEvidenceAndNeverAuthorizesPlayerSids() {
        val suspended = GlobalPlaybackEvidence(listOf(GlobalPlaybackEvidence.Output("mix48", setOf(353),
            listOf(GlobalPlaybackEvidence.GlobalEffect(27, true, true)))))
        assertEquals(GlobalPlaybackEvidence.Reason.SUSPENDED, suspended.processingIssue(setOf(353), 27))
        val separate = GlobalPlaybackEvidence(listOf(
            GlobalPlaybackEvidence.Output("mix48", emptySet(), listOf(GlobalPlaybackEvidence.GlobalEffect(27, false, false, true))),
            GlobalPlaybackEvidence.Output("uhq192", setOf(353), emptyList())))
        assertEquals(GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT, separate.processingIssue(setOf(353), 27))
        assertTrue(RootProcessingPolicy.acceptsSession(false, 0))
        assertFalse(RootProcessingPolicy.acceptsSession(false, 353))
    }
    @Test fun hdOrEnhancedAlwaysRejectsGlobalAndOnlyBothOffRestoresGlobal() {
        for(hd in listOf(false,true))for(enhanced in listOf(false,true)) {
            val dynamic=RootProcessingPolicy.playerSessions(hd,enhanced)
            assertEquals(hd||enhanced,dynamic)
            assertEquals(!dynamic,RootProcessingPolicy.acceptsSession(dynamic,0))
            assertEquals(dynamic,RootProcessingPolicy.acceptsSession(dynamic,42))
            assertFalse(RootProcessingPolicy.acceptsSession(dynamic,-1))
        }
    }
    @Test fun modeTransitionsNeverAuthorizeBothGlobalAndPlayerSessions() {
        for(dynamic in listOf(false,true,true,false,true,false)) {
            val accepted=listOf(-1,0,101,203).filter {RootProcessingPolicy.acceptsSession(dynamic,it)}
            if(dynamic)assertEquals(listOf(101,203),accepted) else assertEquals(listOf(0),accepted)
            assertFalse(0 in accepted && accepted.any {it>0})
        }
    }
    @Test fun failedAttachmentRetriesBackOffIndependentlyAndRecoveredOrGoneSessionsReset() {
        val backoff=RootSessionAttachBackoff()
        assertTrue(backoff.permits(41,0))
        backoff.failed(41,0)
        assertFalse(backoff.permits(41,999));assertTrue(backoff.permits(41,1000))
        backoff.failed(41,1000)
        assertFalse(backoff.permits(41,2999));assertTrue(backoff.permits(41,3000))
        assertTrue(backoff.permits(42,0))
        backoff.succeeded(41);assertTrue(backoff.permits(41,0))
        backoff.failed(41,0);backoff.retain(emptySet());assertTrue(backoff.permits(41,0))
        repeat(12){backoff.failed(41,10000)}
        assertFalse(backoff.permits(41,39999));assertTrue(backoff.permits(41,40000))
        backoff.clear();assertTrue(backoff.permits(41,0))
    }
}
