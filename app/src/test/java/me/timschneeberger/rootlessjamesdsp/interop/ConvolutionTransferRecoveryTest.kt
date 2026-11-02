package me.timschneeberger.rootlessjamesdsp.interop

import org.junit.Assert.*
import org.junit.Test

class ConvolutionTransferRecoveryTest {
    private val target = ConvolutionTransferRecovery.Target("stereo.irs", 345625875, 18824, 2, 48000)
    private val attempt = ConvolutionTransferRecovery.Action.ATTEMPT
    private val wait = ConvolutionTransferRecovery.Action.WAIT
    private val failed = ConvolutionTransferRecovery.Action.FAILED
    private fun policy() = ConvolutionTransferRecovery().apply { select(target) }
    private fun ack(rate: Int = 48000, flags: Int = 1, sequence: Int = 2) = IntArray(32).apply {
        this[0] = 1; this[1] = sequence; this[2] = rate; this[3] = flags
        this[4] = 2; this[5] = 18824; this[6] = 345625875
    }

    @Test fun activeSessionGetsInitialAttemptAndExactlyThreeQuietRetriesBeforeFailure() {
        val recovery = policy()
        var now = 0L
        for (delay in listOf(150L, 500L, 1000L)) {
            assertEquals(attempt, recovery.action(now, true))
            assertEquals(wait, recovery.failed(now, true))
            assertEquals(wait, recovery.action(now + delay - 1, true))
            now += delay
        }
        assertEquals(attempt, recovery.action(now, true))
        assertEquals(failed, recovery.failed(now, true))
        assertEquals(failed, recovery.action(now + 10000, true))
    }

    @Test fun idleFailuresWaitForStreamInsteadOfReportingFatalAndWakeRestoresFullBudget() {
        val recovery = policy()
        var now = 0L
        repeat(4) {
            assertEquals(attempt, recovery.action(now, false))
            assertEquals(wait, recovery.failed(now, false))
            now += 2000L
        }
        assertEquals(wait, recovery.action(now + 100000, false))
        assertEquals(attempt, recovery.action(now, true))
        repeat(3) {
            assertEquals(wait, recovery.failed(now, true))
            now += 2000L
            assertEquals(attempt, recovery.action(now, true))
        }
        assertEquals(failed, recovery.failed(now, true))
    }

    @Test fun newTargetCancelsOldRetryDeadlineAndTerminalBudgetButSameTargetDoesNot() {
        val recovery = policy()
        recovery.failed(0, true)
        recovery.select(target)
        assertEquals(wait, recovery.action(1, true))
        repeat(3) { recovery.failed(2000L * (it + 1), true) }
        assertEquals(failed, recovery.action(10000, true))
        recovery.select(target.copy(identity = "new.irs", crc = 9))
        assertEquals(attempt, recovery.action(0, true))
        repeat(3) { assertEquals(wait, recovery.failed(it * 2000L, true)) }
        assertEquals(failed, recovery.failed(6000, true))
    }

    @Test fun failureNoticeIsOncePerFailureEpisodeAndSuccessOrNewTargetResetsIt() {
        val recovery = policy()
        repeat(4) { recovery.failed(it * 2000L, true) }
        assertTrue(recovery.takeFailureNotice())
        assertFalse(recovery.takeFailureNotice())
        recovery.succeeded()
        assertEquals(attempt, recovery.action(0, true))
        repeat(4) { recovery.failed(it * 2000L, true) }
        assertTrue(recovery.takeFailureNotice())
        recovery.select(target.copy(identity = "another.irs"))
        repeat(4) { recovery.failed(it * 2000L, true) }
        assertTrue(recovery.takeFailureNotice())
        assertFalse(recovery.takeFailureNotice())
        recovery.reset()
        assertEquals(wait, recovery.action(Long.MAX_VALUE, true))
    }

    @Test fun loadedAckBeforeAudioRateIsKnownIsAcceptedWithoutReadyFlag() {
        val dormant = ConvolutionTransferRecovery.decodeAck(ack(rate = 0))
        assertNotNull(dormant)
        assertTrue(dormant!!.matches(target))
        assertTrue(ConvolutionTransferRecovery.decodeAck(ack())!!.matches(target))
    }

    @Test fun unavailableKernelMetadataDoesNotReuploadAPreviouslyConfirmedIr() {
        assertTrue(ConvolutionTransferRecovery.waitForAcknowledgement(null, true))
        assertFalse(ConvolutionTransferRecovery.waitForAcknowledgement(null, false))
        assertFalse(ConvolutionTransferRecovery.waitForAcknowledgement(false, true))
        assertFalse(ConvolutionTransferRecovery.waitForAcknowledgement(true, true))
    }

    @Test fun mismatchedCrcFramesChannelsRateAndMissingLoadedFlagCannotConfirmTarget() {
        val variants = listOf(
            ack().apply { this[6]++ }, ack().apply { this[5]-- },
            ack().apply { this[4] = 1 }, ack(rate = 44100), ack(flags = 0)
        )
        variants.forEach { assertFalse(ConvolutionTransferRecovery.decodeAck(it)!!.matches(target)) }
    }

    @Test fun malformedOrUnstableMetadataNeverProducesAcknowledgement() {
        val malformed = listOf(
            IntArray(31), IntArray(33), ack(sequence = 3),
            ack().apply { this[0] = 2 }, ack(rate = 7999), ack(rate = 768001),
            ack(flags = 16), ack().apply { this[4] = 3 }, ack().apply { this[5] = -1 },
            ack(rate = 0, flags = 5), ack(rate = 0, flags = 9)
        )
        assertNull(ConvolutionTransferRecovery.decodeAck(null))
        malformed.forEach { assertNull(ConvolutionTransferRecovery.decodeAck(it)) }
        for(rate in listOf(192001,384000,768000))assertNotNull(ConvolutionTransferRecovery.decodeAck(ack(rate=rate)))
    }
}
