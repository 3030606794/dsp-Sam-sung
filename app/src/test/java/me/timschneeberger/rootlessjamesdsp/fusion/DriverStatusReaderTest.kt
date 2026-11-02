package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DriverStatusReaderTest {
    private class FakeProbe(var frames: Int? = 100, var rate: Int? = 48000) : DriverStatusReader.Probe {
        val queried = mutableListOf<Int>()
        var readable = true
        override fun integer(parameter: Int): Int? {
            queried += parameter
            if (!readable) throw IllegalStateException("Session was released")
            return when (parameter) {
                DriverStatusReader.PROCESSED_FRAMES -> frames
                DriverStatusReader.HOST_RATE -> rate
                DriverStatusReader.INTERNAL_RATE -> 48000
                DriverStatusReader.PROTOCOL -> 5
                DriverStatusReader.CAPABILITIES -> -1
                DriverStatusReader.PID -> 321
                else -> null
            }
        }
        override fun enabled(): Boolean? = if (readable) true else null
        override fun hasControl(): Boolean? = if (readable) false else null
        override fun descriptor() = DriverStatusReader.Descriptor("Existing DSP effect", "Example vendor")
    }
    private fun source(probe: DriverStatusReader.Probe?) = DriverStatusReader.Source(42, "example.music", 10042, probe)

    @Test fun configuredRateAloneDoesNotImplyActiveProcessingAndReadsExcludeStateChangingParameter() = runBlocking {
        val probe = FakeProbe()
        val snapshot = DriverStatusReader { }.read("扬声器", true, listOf(source(probe)))
        assertEquals(DriverStatusReader.Processing.IDLE, snapshot.processing)
        assertTrue(snapshot.sessions!!.single().connected == true)
        val text = DriverStatusText.format(snapshot)
        assertTrue(text.contains("处理采样率：未处理"))
        assertTrue(text.contains("宿主采样率：48 kHz"))
        assertTrue(text.contains("基础能力：0xFFFFFFFF"))
        assertFalse("Initialization query has a native write side effect", probe.queried.contains(19998))
        assertEquals(DriverStatusReader.READ_ONLY_PARAMETERS, probe.queried.toSet())
    }

    @Test fun advancingFramesReportOnlyTheHostRateAsTheActiveProcessingRate() = runBlocking {
        val probe = FakeProbe(rate = 44100)
        val snapshot = DriverStatusReader { probe.frames = 300 }
            .read("USB DAC", true, listOf(source(probe)))
        assertEquals(DriverStatusReader.Processing.ACTIVE, snapshot.processing)
        val text = DriverStatusText.format(snapshot)
        assertTrue(text.contains("处理采样率：44.1 kHz"))
        assertTrue(text.contains("引擎内部采样率：48 kHz"))
        assertTrue(text.contains("控制权：未获得"))
        assertTrue(text.contains("来源应用：example.music"))
    }

    @Test fun unsupportedRuntimeFieldsRemainUnknownWhileAReadableEffectRemainsConnected() = runBlocking {
        val probe = FakeProbe(frames = null, rate = null)
        val snapshot = DriverStatusReader { }.read(null, null, listOf(source(probe)))
        assertEquals(DriverStatusReader.Processing.UNKNOWN, snapshot.processing)
        assertEquals(true, snapshot.sessions!!.single().connected)
        assertTrue(DriverStatusText.format(snapshot).contains("处理采样率：未知 / 未提供"))
    }

    @Test fun releasedHandleDoesNotClaimConnectionFromACachedDescriptor() = runBlocking {
        val probe = FakeProbe().apply { readable = false }
        val snapshot = DriverStatusReader { }.read(null, false, listOf(source(probe)))
        assertNull(snapshot.sessions!!.single().connected)
        assertTrue(DriverStatusText.format(snapshot).contains("驱动连接：未知 / 未提供"))
    }

    @Test fun counterResetAndRateChangeRemainUnknownRatherThanInventingActiveAudio() = runBlocking {
        val reset = FakeProbe()
        assertEquals(DriverStatusReader.Processing.UNKNOWN,
            DriverStatusReader { reset.frames = 1 }.read(null, true, listOf(source(reset))).processing)
        val switched = FakeProbe()
        assertEquals(DriverStatusReader.Processing.UNKNOWN,
            DriverStatusReader { switched.rate = 96000; switched.frames = 300 }
                .read(null, true, listOf(source(switched))).processing)
    }

    @Test fun unavailableSessionDatabaseAndAnEmptyDatabaseHaveDifferentConnectionStates() = runBlocking {
        val unknown = DriverStatusText.format(DriverStatusReader { }.read(null, null, null))
        val empty = DriverStatusText.format(DriverStatusReader { }.read("扬声器", false, emptyList()))
        assertTrue(unknown.contains("驱动连接：未知 / 未提供"))
        assertTrue(empty.contains("驱动连接：未连接"))
        assertFalse(empty.contains("已安装"))
        assertFalse(empty.contains("驱动版本"))
    }

    @Test fun closingDuringTheObservationWindowCancelsBeforeTheSecondNativeRead() = runBlocking {
        val windowStarted = CompletableDeferred<Unit>()
        val probe = FakeProbe()
        val reading = async {
            DriverStatusReader { windowStarted.complete(Unit); awaitCancellation() }
                .read(null, true, listOf(source(probe)))
        }
        windowStarted.await()
        reading.cancelAndJoin()
        assertTrue(reading.isCancelled)
        assertEquals(DriverStatusReader.READ_ONLY_PARAMETERS.size, probe.queried.size)
    }
}
