package me.timschneeberger.rootlessjamesdsp.peq

import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import org.junit.Assert.*
import org.junit.Test

class PeqFrequencyLimitsTest {
    private fun session(id: String, rate: Int?, frames: Int?, enabled: Boolean = true) =
        OutputRuntimeStatus.Session(id, enabled, rate, frames)

    @Test fun controlsUseExactNyquistIndependentlyOfGraphZoom() {
        for (rate in listOf(48000.0, 384000.0, 768000.0, 47999.0)) {
            val range = PeqFrequencyLimits.editorRange(rate)
            assertEquals(1.0, range.start, 0.0)
            assertEquals(rate / 2.0, range.endInclusive, 0.0)
            assertEquals(200.0, PeqFrequencyLimits.graphRange(
                PeqState(emptyList(), graphMaxFrequency = 200.0), rate).endInclusive, 0.0)
        }
        assertEquals(24000.0, PeqFrequencyLimits.maximum(null), 0.0)
        assertEquals(24000.0, PeqFrequencyLimits.maximum(Double.NaN), 0.0)
    }

    @Test fun graphTruncatesAndRestoresStoredPreferenceWithoutChangingAnyDspWord() {
        val state = PeqState(listOf(PeqBand("saved", frequency = 100000.0)), graphMaxFrequency = 768000.0)
        val stored = PeqRepository.encode(state)
        val payload = PeqProtocol.payload(state, true)
        val cache = PeqResponse.PreviewCache()
        for (rate in listOf(48000.0, 384000.0, 768000.0, 48000.0, 384000.0)) {
            val range = PeqFrequencyLimits.graphRange(state, rate)
            assertEquals(rate / 2.0, range.endInclusive, 0.0)
            for (curve in listOf(PeqResponse.curve(state, 32, rate),
                PeqResponse.adaptiveCurve(state, 32, rate), requireNotNull(cache.curve(state, 32, rate)))) {
                assertEquals(rate / 2.0, curve.last().frequency, 0.0)
                assertTrue(curve.all { it.frequency in 1.0..rate / 2.0 && it.db.isFinite() })
            }
            assertEquals(768000.0, PeqFrequencyLimits.preferredGraphMaximum(
                state.graphMaxFrequency, range.endInclusive, false), 0.0)
            assertEquals(stored, PeqRepository.encode(state))
            assertArrayEquals(payload, PeqProtocol.payload(state, true))
        }
        assertEquals(24000.0, PeqFrequencyLimits.preferredGraphMaximum(768000.0, 24000.0, true), 0.0)
        assertEquals(24000.0, PeqFrequencyLimits.graphRange(PeqState(emptyList()), 48000.0).endInclusive, 0.0)
    }

    @Test fun wideningRequiresSameSessionFrameProgressAndIdleRetainsTheSafeRate() {
        val tracker = PeqFrequencyLimits.RuntimeTracker()
        assertEquals(48000.0, tracker.update(listOf(session("a", 384000, 100))), 0.0)
        assertEquals(48000.0, tracker.update(listOf(session("a", 384000, 100))), 0.0)
        assertEquals(384000.0, tracker.update(listOf(session("a", 384000, 101))), 0.0)
        assertEquals(384000.0, tracker.update(listOf(session("a", 0, null))), 0.0)
        assertEquals(384000.0, tracker.update(emptyList()), 0.0)
        assertEquals(384000.0, tracker.update(listOf(session("b", 768000, 100000))), 0.0)
        assertEquals(768000.0, tracker.update(listOf(session("b", 768000, 100001))), 0.0)
        tracker.suspend()
        assertEquals(768000.0, tracker.update(listOf(session("b", 768000, 900000))), 0.0)
        assertEquals(768000.0, tracker.update(listOf(session("disabled", 8000, 1, false))), 0.0)
    }

    @Test fun lowerConfiguredRateImmediatelyTightensBeforeNewStreamProgress() {
        val tracker = PeqFrequencyLimits.RuntimeTracker()
        tracker.update(listOf(session("a", 384000, 10)))
        assertEquals(384000.0, tracker.update(listOf(session("a", 384000, 11))), 0.0)
        assertEquals(48000.0, tracker.update(listOf(session("a", 48000, 11))), 0.0)
        assertEquals(48000.0, tracker.update(listOf(session("a", 768000, 12))), 0.0)
        assertEquals(768000.0, tracker.update(listOf(session("a", 768000, 13))), 0.0)
        assertEquals(16000.0, tracker.update(listOf(session("a", 768000, 14), session("b", 16000, 1))), 0.0)
        assertEquals(16000.0, tracker.update(listOf(session("a", 768000, 15), session("b", 16000, 2))), 0.0)
    }

    @Test fun rateChangesKeepTheFixedBandwidthUnitAndTheSavedBandUntouched() {
        val q = PeqResponse.qFromBandwidth(.014, 1000.0)
        val state = PeqState(listOf(PeqBand("bw", frequency = 100000.0, q = q, qualityDisplay = PeqQualityDisplay.BW)))
        for (rate in listOf(48000.0, 384000.0, 768000.0)) {
            PeqFrequencyLimits.graphRange(state, rate)
            assertEquals(.014, PeqResponse.bandwidthOctaves(q, 100000.0, rate), 1e-15)
            assertEquals(q, state.bands.single().q, 0.0)
            assertEquals(100000.0, state.bands.single().frequency, 0.0)
        }
    }

    @Test fun exactNyquistUsesTheNativeLeftLimitRatherThanBypassingTheBand() {
        for (rate in listOf(48000.0, 384000.0, 768000.0)) for (type in PeqFilterType.entries) {
            val requested = PeqBand("edge", type, frequency = rate / 2.0, gain = 12.0, q = 100.0)
            val exact = PeqState(listOf(requested))
            val effective = exact.copy(bands = listOf(requested.copy(frequency = rate / 2.0 - .001)))
            for (frequency in listOf(1.0, rate / 4.0, rate / 2.0 - .001, rate / 2.0)) {
                assertEquals("$type fs=$rate f=$frequency", PeqResponse.responseDb(effective, frequency, rate),
                    PeqResponse.responseDb(exact, frequency, rate), 0.0)
            }
            val curve = PeqResponse.adaptiveCurve(exact, 32, rate)
            assertTrue(curve.any { it.frequency == rate / 2.0 - .001 })
            assertTrue(curve.all { it.db.isFinite() })
            assertTrue(PeqResponse.curve(exact.copy(bands = listOf(requested.copy(frequency = rate / 2.0 + .001))),
                32, rate).all { it.db == 0.0 })
        }
    }
}
