package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionAdvancedIr
import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionIrPreprocessor
import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionTransferRecovery
import me.timschneeberger.rootlessjamesdsp.interop.EngineHealth
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

/** High-rate support must preserve runtime meaning, not infer processing from advertised capabilities. */
class SampleRateRuntimeTest {
    private fun metadata(rate: Int, flags: Int = 3, delaySamples: Int = 0, delayUs: Int = 0) = IntArray(32).apply {
        this[0] = 1; this[1] = 2; this[2] = rate; this[3] = flags
        this[4] = 2; this[5] = 18824; this[6] = 345625875
        this[7] = delaySamples; this[8] = delayUs; this[9] = 16; this[11] = 1000
        for (offset in listOf(19, 23, 27)) {
            this[offset] = Int.MIN_VALUE; this[offset + 1] = Int.MIN_VALUE
            this[offset + 2] = Int.MIN_VALUE; this[offset + 3] = Int.MIN_VALUE
        }
        this[31] = if (rate > 0) rate / 50 else 0
    }

    @Test fun healthyHighRatesAndLegalIdleDoNotTriggerEffectRecovery() {
        val health = EngineHealth()
        for ((index, rate) in listOf(0, 384000, 768000, 0).withIndex()) {
            assertEquals(EngineHealth.Action.APPLY,
                health.observe(index * 500L, EngineHealth.Reading(1234, 4, rate, 4, true)))
        }
        assertEquals(EngineHealth.Action.RETRY,
            health.observe(3000, EngineHealth.Reading(1234, 4, 768001, 4, true)))
    }

    @Test fun onlySameSessionActualHostRateAndFrameProgressCanDeclareActiveProcessing() {
        val tracker = OutputRuntimeStatus.Tracker()
        fun session(id: String = "live", rate: Int? = 768000, frames: Int? = 0, enabled: Boolean = true) =
            OutputRuntimeStatus.Session(id, enabled, rate, frames)
        assertNotEquals(OutputRuntimeStatus.Processing.ACTIVE,
            tracker.update(listOf(session(rate = null, frames = 1000))).processing)
        assertNotEquals(OutputRuntimeStatus.Processing.ACTIVE,
            tracker.update(listOf(session(rate = null, frames = 2000))).processing)
        assertNotEquals(OutputRuntimeStatus.Processing.ACTIVE, tracker.update(listOf(session())).processing)
        assertEquals(OutputRuntimeStatus.Processing.IDLE, tracker.update(listOf(session())).processing)
        val active = tracker.update(listOf(session(frames = 1024)))
        assertEquals(OutputRuntimeStatus.Processing.ACTIVE, active.processing)
        assertEquals(listOf(768000), active.rates)
        assertNotEquals(OutputRuntimeStatus.Processing.ACTIVE,
            tracker.update(listOf(session("new", 384000, 2048))).processing)
        assertEquals(listOf(384000), tracker.update(listOf(session("new", 384000, 3072))).rates)
        assertEquals(OutputRuntimeStatus.Processing.IDLE,
            tracker.update(listOf(session("new", 384000, 4096, false))).processing)
    }

    @Test fun highRateIrWindowAndFractionalDelayUseRealRateWithoutChangingInputOrUnits() {
        for (rate in listOf(384000, 768000)) {
            val source = FloatArray(rate / 1000 * 2) { if (it % 2 == 0) .5f else .25f }
            val copy = source.copyOf()
            val edited = ConvolutionIrPreprocessor.edit(source, 2, rate,
                ConvolutionIrPreprocessor.Options(window = true, startUs = 250, lengthUs = 500))
            assertEquals(rate / 4000, edited.startFrame)
            assertEquals(rate / 2000, edited.frames)
            assertArrayEquals(copy, source, 0f)
            val impulse = floatArrayOf(.5f, .25f)
            val delayed = ConvolutionAdvancedIr.edit(impulse, 2, rate,
                ConvolutionAdvancedParameters.IrOptions(fractional = true, delays = listOf(1000, 0, 0, 0)))
            assertEquals(1, delayed.commonDelay)
            assertEquals(.5f, delayed.samples[(rate / 1000 + 1) * 2], 0f)
            assertEquals(.25f, delayed.samples[3], 0f)
            assertArrayEquals(floatArrayOf(.5f, .25f), impulse, 0f)
        }
    }

    @Test fun highRateKernelAcknowledgementAndIdleMetadataKeepTheExactAcceptedIdentity() {
        for (rate in listOf(384000, 768000)) {
            val target = ConvolutionTransferRecovery.Target("stereo.irs", 345625875, 18824, 2, rate)
            val active = requireNotNull(ConvolutionTransferRecovery.decodeAck(metadata(rate)))
            assertTrue(active.matches(target))
            val idle = requireNotNull(ConvolutionTransferRecovery.decodeAck(metadata(0)))
            assertTrue(idle.matches(target))
            val reading = requireNotNull(ConvolutionRuntimeReader.decode(metadata(0)))
            assertEquals(345625875, reading.crc)
            assertEquals(18824, reading.frames)
            assertTrue(reading.loaded)
            assertFalse(reading.available)
            assertNull(ConvolutionTransferRecovery.decodeAck(metadata(0, 15)))
            assertNull(ConvolutionRuntimeReader.decode(metadata(0, 15)))
        }
    }

    @Test fun fullTwoHundredMillisecondDelaySurvivesHighRatePlaybackAndIdleWithoutWrongRejection() {
        for (rate in listOf(384000, 768000)) {
            val samples = (rate * .2).roundToInt()
            val active = requireNotNull(ConvolutionRuntimeReader.decode(metadata(rate, 15, samples, 200000)))
            assertEquals(samples, active.delaySamples)
            assertEquals(200000, active.delayUs)
            assertEquals(rate / 50, active.windowFrames)
            assertNotNull(ConvolutionRuntimeReader.decode(metadata(0, 3, samples, 200000)))
            assertNull(ConvolutionRuntimeReader.decode(metadata(rate, 15, samples + 1, 200000)))
            assertNull(ConvolutionRuntimeReader.decode(metadata(rate, 15, samples, 199999)))
        }
    }
}
