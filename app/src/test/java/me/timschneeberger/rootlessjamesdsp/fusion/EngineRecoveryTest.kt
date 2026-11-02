package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.interop.EngineHealth
import me.timschneeberger.rootlessjamesdsp.interop.EngineHealth.Action.*
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceSyncQueue
import me.timschneeberger.rootlessjamesdsp.interop.EnginePower
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EngineRecoveryTest {
    private fun healthy(rate: Int = 48000) = EngineHealth.Reading(949, 4, rate, 4, true)
    private fun failed(status: Int = -3) = EngineHealth.Reading(null, status, null, status, true)

    @Test fun incompleteIntegerRepliesNeverBecomeZeroOrPartialPid() {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(949).array()
        for (status in listOf(-7, -5, -3, -1, 0, 1, 2, 3, 5, 8)) {
            val result = AudioEffectExtensions.decodeIntRead(status, bytes)
            assertEquals(status, result.status); assertNull(result.value)
        }
        assertEquals(949, AudioEffectExtensions.decodeIntRead(4, bytes).value)
        assertNull(AudioEffectExtensions.decodeIntRead(4, byteArrayOf(1,2)).value)
    }
    @Test fun oneTemporaryReadFailureAfterPresetDoesNotRecreateEngine() {
        val health=EngineHealth()
        assertEquals(APPLY,health.observe(0,healthy()))
        assertEquals(RETRY,health.observe(10,failed()))
        assertEquals(APPLY,health.observe(260,healthy()))
        assertEquals(APPLY,health.observe(510,healthy()))
    }
    @Test fun presetBurstCannotCountAsThreeIndependentFailures() {
        val health=EngineHealth()
        repeat(200) { assertEquals(RETRY,health.observe(100,failed())) }
        assertEquals(RETRY,health.observe(350,failed()))
        assertEquals(RECOVER,health.observe(600,failed()))
    }
    @Test fun idleRateIsValidAndControlLossNeverRestarts() {
        val health=EngineHealth()
        assertEquals(APPLY,health.observe(0,healthy(0)))
        repeat(20) { assertEquals(WAIT_CONTROL,health.observe(it*1000L,failed().copy(control=false))) }
        assertEquals(APPLY,health.observe(21000,healthy(96000)))
    }
    @Test fun deadObjectRecoversOnceThenCooldownAndRetryLimitApply() {
        val health=EngineHealth()
        assertEquals(RECOVER,health.observe(0,failed(-7)))
        assertEquals(RETRY,health.observe(250,failed(-7)))
        assertEquals(RETRY,health.observe(4999,failed(-7)))
        assertEquals(RECOVER,health.observe(5000,failed(-7)))
        assertEquals(STOP,health.observe(5250,failed(-7)))
        assertEquals(STOP,health.observe(29999,failed(-7)))
    }
    @Test fun healthyTenSecondsRearmsRecoveryAndShortRateDoesNotPass() {
        val health=EngineHealth()
        assertEquals(RECOVER,health.observe(0,failed(-7)))
        assertEquals(APPLY,health.observe(100,healthy()))
        assertEquals(APPLY,health.observe(10100,healthy()))
        assertEquals(RETRY,health.observe(10200,healthy().copy(rate=null,rateStatus=0)))
        assertEquals(RECOVER,health.observe(10300,failed(-7)))
    }
    @Test fun presetKeyBurstHasOneWorkerAndKeepsAllForcedNamespaces() {
        val queue=PreferenceSyncQueue()
        assertTrue(queue.request(arrayOf("crossfeed")))
        repeat(40) { assertFalse(queue.request(null)) }
        assertFalse(queue.request(arrayOf("viper", "crossfeed")))
        assertArrayEquals(arrayOf("crossfeed","viper"),queue.take())
        assertNull(queue.take())
        assertTrue(queue.request(null));assertArrayEquals(emptyArray<String>(),queue.take())
    }
    @Test fun requestDuringApplyIsConsumedBySameWorkerAndCloseDropsPending() {
        val queue=PreferenceSyncQueue()
        assertTrue(queue.request(null));assertNotNull(queue.take())
        assertFalse(queue.request(arrayOf("latest")));assertArrayEquals(arrayOf("latest"),queue.take())
        queue.request(arrayOf("stale"));queue.close()
        assertNull(queue.take());assertFalse(queue.request(arrayOf("after-release")))
    }
    @Test fun failedEnableReturnAndExceptionPreserveIntentUntilSuccessfulRetry() {
        for(desired in listOf(false,true)) {
            val power=EnginePower();power.request(desired)
            assertEquals(EnginePower.Result.RETRY,power.apply { assertEquals(desired,it);-7 })
            assertTrue(power.pending);assertEquals(desired,power.desired)
            assertEquals(EnginePower.Result.RETRY,power.apply { throw IllegalStateException() })
            power.replaced()
            assertEquals(EnginePower.Result.DONE,power.apply { assertEquals(desired,it);0 })
            assertFalse(power.pending)
        }
    }
    @Test fun powerChangedDuringNativeWriteIsNotLostAndFailuresAreBounded() {
        val power=EnginePower();power.request(true)
        assertEquals(EnginePower.Result.RETRY,power.apply { power.request(false);0 })
        assertTrue(power.pending)
        repeat(3){assertEquals(EnginePower.Result.RETRY,power.apply {-5})}
        assertEquals(EnginePower.Result.STOP,power.apply {-5})
        var writesAfterStop=0
        repeat(20){assertEquals(EnginePower.Result.STOP,power.apply { writesAfterStop++;-5 })}
        assertEquals(0,writesAfterStop)
        assertFalse(power.desired);assertTrue(power.pending)
        power.request(true)
        assertEquals(EnginePower.Result.DONE,power.apply { assertTrue(it);0 })
    }
}
