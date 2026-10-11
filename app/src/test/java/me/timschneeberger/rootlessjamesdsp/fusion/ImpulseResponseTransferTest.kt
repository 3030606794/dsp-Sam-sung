package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.interop.ImpulseResponseTransfer
import org.junit.Assert.*
import org.junit.Test

class ImpulseResponseTransferTest {
    @Test fun allocationBoundaryMatchesNativeForEverySupportedChannelCount() {
        val maximumSamples = 32 * 1024 * 1024 / 4
        for (channels in listOf(1, 2, 4)) {
            assertArrayEquals(intArrayOf(maximumSamples, channels, 0, 2048),
                ImpulseResponseTransfer.header(maximumSamples, channels))
            assertNull(ImpulseResponseTransfer.header(maximumSamples + channels, channels))
            assertArrayEquals(intArrayOf(4096 + channels, channels, 0, 2),
                ImpulseResponseTransfer.header(4096 + channels, channels))
        }
        assertNull(ImpulseResponseTransfer.header(Int.MAX_VALUE, 1))
    }

    @Test fun preservesStereoSamplesAcrossPaddedPartitionBoundary() {
        val samples = FloatArray(4098) { it / 4098f }
        val sent = mutableListOf<FloatArray>()
        var committed = false
        val status = ImpulseResponseTransfer.send(samples, 2, {
            assertArrayEquals(intArrayOf(4098, 2, 0, 2), it); 0
        }, { sent += it.copyOf(); 0 }, { committed = true; 0 })
        assertEquals(0, status)
        assertTrue(committed)
        assertEquals(2, sent.size)
        assertArrayEquals(samples.copyOfRange(0, 4096), sent[0], 0f)
        assertEquals(samples[4096], sent[1][0], 0f)
        assertEquals(samples[4097], sent[1][1], 0f)
        assertTrue(sent[1].drop(2).all { it == 0f })
    }

    @Test fun rejectionStopsUploadWithoutCommittingPartialData() {
        val samples = FloatArray(8192) { 0.1f }
        var chunks = 0
        var committed = false
        val status = ImpulseResponseTransfer.send(samples, 2, { 0 }, {
            chunks++; if (chunks == 2) -5 else 0
        }, { committed = true; 0 })
        assertEquals(-5, status)
        assertEquals(2, chunks)
        assertFalse(committed)
        chunks = 0
        assertEquals(-3, ImpulseResponseTransfer.send(samples, 2, { -3 }, {
            chunks++; 0
        }, { committed = true; 0 }))
        assertEquals(0, chunks)
        assertFalse(committed)
    }

    @Test fun malformedAudioDoesNotReachTheDriver() {
        var called = false
        listOf(FloatArray(0) to 1, floatArrayOf(1f) to 2,
            floatArrayOf(Float.NaN) to 1, floatArrayOf(1f) to 3).forEach { (samples, channels) ->
            assertEquals(-4, ImpulseResponseTransfer.send(samples, channels,
                { called = true; 0 }, { called = true; 0 }, { called = true; 0 }))
        }
        assertFalse(called)
    }

    @Test fun failedNativeCommitIsReturnedToCaller() {
        assertEquals(-5, ImpulseResponseTransfer.send(floatArrayOf(1f, 1f), 2,
            { 0 }, { 0 }, { -5 }))
    }
}
