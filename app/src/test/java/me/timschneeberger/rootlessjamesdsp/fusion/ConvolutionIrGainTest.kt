package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionIrGain as Gain
import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionIrPreprocessor as Ir
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.*

class ConvolutionIrGainTest {
    private fun pulse(rate: Int): FloatArray = FloatArray((rate * .004).roundToInt() * 2) { i ->
        val time = (i / 2).toDouble() / rate
        val amplitude = if (i % 2 == 0) .5 else -.125
        (amplitude * exp(-((time - .00112345) / .00015).pow(2))).toFloat()
    }
    private fun magnitude(samples: FloatArray, rate: Int, channel: Int, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in 0 until samples.size / 2) {
            val phase = 2 * PI * hz * i / rate
            re += samples[i * 2 + channel] * cos(phase)
            im -= samples[i * 2 + channel] * sin(phase)
        }
        return hypot(re, im)
    }

    @Test fun inverseRateCompensationPreservesPhysicalFrequencyGainWithAndWithoutNormalization() {
        val sourceRate = 44100
        val reference = pulse(sourceRate)
        for (normalize in listOf(false, true)) {
            val options = Ir.Options(normalize = normalize, attenuateOnly = false)
            val gain = Gain.normalizationGain(reference, options)
            val expected = Gain.apply(reference, sourceRate, sourceRate, gain).samples
            for (rate in listOf(32000, 44100, 48000, 88200, 96000, 176400, 192000, 384000, 768000)) {
                val result = Gain.apply(pulse(rate), sourceRate, rate, gain)
                for (channel in 0..1) for (hz in listOf(20.0, 1000.0, 3000.0)) {
                    val deltaDb = 20 * log10(magnitude(result.samples, rate, channel, hz) / magnitude(expected, sourceRate, channel, hz))
                    assertEquals("$rate Hz, channel $channel, $hz Hz", 0.0, deltaDb, .002)
                }
                assertEquals(20 * log10(gain), result.normalizationGainDb, 1e-12)
            }
        }
    }

    @Test fun originalRateMatchesHistoricalPeakNormalizationBitForBitAndNeutralKeepsTheSameBuffer() {
        val source = floatArrayOf(.5f, -.125f, .25f, -.0625f)
        val options = Ir.Options(normalize = true, attenuateOnly = false)
        val historical = Ir.edit(source, 2, 48000, options)
        val result = Gain.apply(source, 48000, 48000, Gain.normalizationGain(source, options))
        assertArrayEquals(historical.samples, result.samples, 0f)
        assertSame(source, Gain.apply(source, 48000, 48000).samples)
        assertArrayEquals(floatArrayOf(.5f, -.125f, .25f, -.0625f), source, 0f)
    }

    @Test fun attenuationOnlyUsesReferencePeakAndDoesNotCancelSrcCompensation() {
        val source = floatArrayOf(.5f, -.25f)
        val options = Ir.Options(normalize = true, attenuateOnly = true)
        val gain = Gain.normalizationGain(source, options)
        assertEquals(1.0, gain, 0.0)
        val result = Gain.apply(floatArrayOf(.6f, -.3f), 48000, 192000, gain)
        assertArrayEquals(floatArrayOf(.15f, -.075f), result.samples, 0f)
        assertEquals(-12.041199826559248, result.rateCompensationDb, 1e-12)
        assertEquals(1.0, Gain.normalizationGain(FloatArray(2), options.copy(attenuateOnly = false)), 0.0)
    }

    @Test fun invalidRatesNonfiniteValuesAndInterruptedWorkCannotProduceAWorkingKernel() {
        assertThrows(IllegalArgumentException::class.java) { Gain.apply(floatArrayOf(1f), 0, 192000) }
        assertThrows(IllegalArgumentException::class.java) { Gain.apply(floatArrayOf(1f), 48000, 768001) }
        assertThrows(IllegalArgumentException::class.java) { Gain.apply(floatArrayOf(Float.NaN), 48000, 192000) }
        assertThrows(IllegalArgumentException::class.java) { Gain.apply(floatArrayOf(Float.MAX_VALUE), 768000, 8000) }
        Thread.currentThread().interrupt()
        try { assertThrows(CancellationException::class.java) { Gain.apply(floatArrayOf(1f), 48000, 192000) } }
        finally { Thread.interrupted() }
    }
}
