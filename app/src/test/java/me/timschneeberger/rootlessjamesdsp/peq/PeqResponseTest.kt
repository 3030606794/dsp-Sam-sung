package me.timschneeberger.rootlessjamesdsp.peq

import kotlin.math.abs
import kotlin.math.log10
import org.junit.Assert.*
import org.junit.Test

class PeqResponseTest {
    private fun state(type: PeqFilterType, gain: Double = 6.0, q: Double = 0.707,
                      frequency: Double = 1000.0) = PeqState(listOf(
        PeqBand("test", type, true, frequency, gain, q)))

    @Test fun neutralAndDisabledBandsHaveAnExactlyFlatResponse() {
        assertTrue(PeqResponse.curve(PeqModel.defaultState(130)).all { it.db == 0.0 })
        val disabled = state(PeqFilterType.LOW_PASS).let {
            it.copy(bands = it.bands.map { band -> band.copy(enabled = false) })
        }
        assertTrue(PeqResponse.curve(disabled).all { it.db == 0.0 })
    }

    @Test fun peakCenterGainBandwidthAndInverseAreCorrectAcrossSampleRates() {
        for (rate in listOf(44100.0, 48000.0, 96000.0)) {
            val peak = state(PeqFilterType.PEAK, 12.0, 2.0)
            assertEquals(12.0, PeqResponse.responseDb(peak, 1000.0, rate), 1e-8)
            assertTrue(abs(PeqResponse.responseDb(peak, 100.0, rate)) < 0.2)
            val wide = state(PeqFilterType.PEAK, 12.0, 0.5)
            assertTrue(PeqResponse.responseDb(wide, 1500.0, rate) >
                PeqResponse.responseDb(peak, 1500.0, rate))
            val inverse = PeqState(peak.bands + peak.bands.single().copy(id = "inverse", gain = -12.0))
            assertTrue(PeqResponse.curve(inverse, sampleRate = rate).all { abs(it.db) < 1e-7 })
        }
    }

    @Test fun shelvesReachTheSpecifiedEndpointsAndUseQResonance() {
        val low = state(PeqFilterType.LOW_SHELF)
        val high = state(PeqFilterType.HIGH_SHELF)
        assertEquals(6.0, PeqResponse.responseDb(low, 0.0), 1e-8)
        assertEquals(0.0, PeqResponse.responseDb(low, 24000.0), 1e-8)
        assertEquals(0.0, PeqResponse.responseDb(high, 0.0), 1e-8)
        assertEquals(6.0, PeqResponse.responseDb(high, 24000.0), 1e-8)
        assertEquals(3.0, PeqResponse.responseDb(low, 1000.0), 1e-8)
        assertEquals(3.0, PeqResponse.responseDb(high, 1000.0), 1e-8)
        assertTrue(PeqResponse.responseDb(state(PeqFilterType.LOW_SHELF, q = 4.0), 500.0) >
            PeqResponse.responseDb(low, 500.0))
    }

    @Test fun passNotchAndBandPassShapesRemainActiveAtZeroGain() {
        val low = state(PeqFilterType.LOW_PASS, gain = 0.0)
        val high = state(PeqFilterType.HIGH_PASS, gain = 0.0)
        val notch = state(PeqFilterType.NOTCH, gain = 0.0)
        val band = state(PeqFilterType.BAND_PASS, gain = 0.0, q = 3.0)
        assertEquals(20.0 * log10(0.707), PeqResponse.responseDb(low, 1000.0), 1e-8)
        assertEquals(20.0 * log10(0.707), PeqResponse.responseDb(high, 1000.0), 1e-8)
        assertTrue(PeqResponse.responseDb(low, 100.0) > PeqResponse.responseDb(low, 10000.0) + 30.0)
        assertTrue(PeqResponse.responseDb(high, 10000.0) > PeqResponse.responseDb(high, 100.0) + 30.0)
        assertTrue(PeqResponse.responseDb(notch, 1000.0) < -180.0)
        assertEquals(0.0, PeqResponse.responseDb(band, 1000.0), 1e-8)
        assertTrue(PeqResponse.responseDb(band, 100.0) < -20.0)
    }

    @Test fun passNotchAndBandPassGainScalesTheWholeUnclippedResponse() {
        for (type in PeqFilterType.entries.filterNot { it.isUnityAtZeroGain }) {
            for (sampleRate in listOf(44100.0, 48000.0, 96000.0)) {
                for (gain in listOf(-100.0, -6.25, 6.25, 100.0)) {
                    for (q in listOf(0.1, 100.0)) {
                        val neutral = state(type, gain = 0.0, q = q)
                        val adjusted = state(type, gain = gain, q = q)
                        for (frequency in listOf(20.0, 300.0, 900.0, 1500.0, 12000.0, 20000.0)) {
                            val reference = PeqResponse.responseDb(neutral, frequency, sampleRate)
                            assertEquals("$type gain=$gain Q=$q f=$frequency rate=$sampleRate",
                                (reference + gain).coerceAtLeast(PeqResponse.FLOOR_DB),
                                PeqResponse.responseDb(adjusted, frequency, sampleRate), 1e-8)
                        }
                    }
                }
            }
        }
    }

    @Test fun passFilterGainIsNotFlooredBeforeOtherBandsAreSummed() {
        val cut = PeqBand(id = "cut", type = PeqFilterType.LOW_PASS, frequency = 20.0, gain = -100.0)
        val boost = PeqBand(id = "boost", type = PeqFilterType.HIGH_PASS, frequency = 20.0, gain = 100.0)
        val neutralCut = cut.copy(gain = 0.0)
        val neutralBoost = boost.copy(gain = 0.0)
        val combined = PeqResponse.responseDb(PeqState(listOf(cut, boost)), 23000.0)
        val neutral = PeqResponse.responseDb(PeqState(listOf(neutralCut, neutralBoost)), 23000.0)
        assertTrue(neutral > PeqResponse.FLOOR_DB)
        assertEquals(neutral, combined, 1e-8)
    }

    @Test fun sampledCurveMatchesIndividualEvaluationAndBypassesUnavailableFrequencies() {
        val requested = state(PeqFilterType.PEAK, frequency = 20000.0)
        assertTrue(PeqResponse.curve(requested, sampleRate = 32000.0).all { it.db == 0.0 })
        assertTrue(PeqResponse.curve(state(PeqFilterType.LOW_PASS, frequency = 16000.0),
            sampleRate = 32000.0).all { it.db == 0.0 })
        val state = PeqState(listOf(PeqBand("peak", gain = 7.125, q = 0.71314),
            PeqBand("shelf", PeqFilterType.HIGH_SHELF, true, 3456.7894, -3.45123, 1.56789)))
        val curve = PeqResponse.curve(state, points = 101)
        assertEquals(20.0, curve.first().frequency, 0.0)
        assertEquals(20000.0, curve.last().frequency, 0.0)
        assertTrue(curve.zipWithNext().all { (a, b) -> a.frequency < b.frequency })
        curve.forEach { assertEquals(PeqResponse.responseDb(state, it.frequency), it.db, 0.0) }
    }

    @Test fun unsetFrequencyBandsNeverAffectResponseOrAdaptiveSamplingAndCanBecomeActive() {
        val baseline = state(PeqFilterType.PEAK, frequency = 1234.0, gain = 6.0)
        val baselineCurve = PeqResponse.adaptiveCurve(baseline, points = 64)
        for (type in PeqFilterType.entries) {
            val cache = PeqResponse.PreviewCache()
            val unset = PeqBand(id = "unset", type = type, frequency = 0.0, gain = 100.0, q = 0.5)
            val withUnset = baseline.copy(bands = baseline.bands + unset)
            assertEquals(baselineCurve, PeqResponse.adaptiveCurve(withUnset, points = 64))
            assertEquals(baselineCurve, cache.curve(withUnset, points = 64))
            for (frequency in listOf(0.0, 20.0, 1000.0, 20000.0, 24000.0)) {
                assertEquals(0.0, PeqResponse.responseDb(PeqState(listOf(unset)), frequency), 0.0)
            }
            val active = withUnset.copy(bands = baseline.bands + unset.copy(frequency = 500.0))
            val activeCurve = PeqResponse.adaptiveCurve(active, points = 64)
            cache.curve(active, points = 64)!!.zip(activeCurve).forEach { (actual, expected) ->
                assertEquals(expected.frequency, actual.frequency, 0.0)
                assertEquals(expected.db, actual.db, 1e-8)
            }
            cache.curve(withUnset, points = 64)!!.zip(baselineCurve).forEach { (actual, expected) ->
                assertEquals(expected.frequency, actual.frequency, 0.0)
                assertEquals(expected.db, actual.db, 1e-8)
            }
        }
    }

    @Test fun zeroFrequencyBandwidthUsesExactDcLimitWithoutSampleRateApproximation() {
        for (rate in listOf(44100.0, 48000.0, 96000.0)) {
            for (q in listOf(0.01, 0.5, 1.0, 100.0)) {
                val expected = 2.0 * kotlin.math.asinh(1.0 / (2.0 * q)) / kotlin.math.ln(2.0)
                val bandwidth = PeqResponse.bandwidthOctaves(q, 0.0, rate)
                assertEquals(expected, bandwidth, 1e-12)
                assertEquals(q, PeqResponse.qFromBandwidth(bandwidth, 0.0, rate), 1e-10)
                assertTrue(bandwidth > PeqResponse.bandwidthOctaves(q, 20.0, rate))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { PeqResponse.bandwidthOctaves(0.5, -1.0) }
    }

    @Test fun extreme130BandResponsesRemainFiniteWithoutProductOverflow() {
        for (type in PeqFilterType.entries) {
            for (rate in listOf(44100.0, 48000.0, 96000.0)) {
                val state = PeqState(List(130) { index -> PeqBand("band-$index", type, true,
                    if (index % 2 == 0) 20.0 else 20000.0,
                    if (index % 2 == 0) 100.0 else -100.0,
                    if (index % 2 == 0) 0.1 else 100.0) })
                assertTrue(PeqResponse.curve(state, sampleRate = rate).all { it.db.isFinite() })
            }
        }
    }

    @Test fun bandwidthConversionIncludesDigitalWarpingAndRoundTrips() {
        for (rate in listOf(44100.0, 48000.0, 96000.0)) {
            for (frequency in listOf(20.0, 1000.0, 20000.0)) {
                for (q in listOf(0.1, 0.707, 3.0, 30.0, 100.0)) {
                    val bandwidth = PeqResponse.bandwidthOctaves(q, frequency, rate)
                    assertEquals(q, PeqResponse.qFromBandwidth(bandwidth, frequency, rate), 1e-10)
                }
            }
        }
        assertTrue(PeqResponse.bandwidthOctaves(1.0, 20000.0) <
            PeqResponse.bandwidthOctaves(1.0, 1000.0) / 2.0)
        assertThrows(IllegalArgumentException::class.java) { PeqResponse.qFromBandwidth(1.0, 24000.0) }
        assertThrows(IllegalArgumentException::class.java) { PeqResponse.bandwidthOctaves(Double.NaN, 1000.0) }
    }

    @Test fun bandwidthIsStrictlyMonotonicAndBoundaryRoundTripsStayEditable() {
        for (frequency in listOf(0.0, 20.0, 1000.0, 20000.0)) {
            var previousBandwidth = Double.POSITIVE_INFINITY
            var previousQ = 0.0
            for (index in 0..10000) {
                val q = 0.1 + 99.9 * index / 10000.0
                val bandwidth = PeqResponse.bandwidthOctaves(q, frequency)
                val restored = PeqResponse.qFromBandwidth(bandwidth, frequency)
                assertTrue(bandwidth < previousBandwidth)
                assertTrue(restored > previousQ)
                assertTrue("Q=$q at $frequency Hz restored as $restored", restored in PeqModel.MIN_Q..PeqModel.MAX_Q)
                assertEquals(q, restored, 1e-10)
                previousBandwidth = bandwidth
                previousQ = restored
            }
            // A real edit past the allowed boundary must remain invalid, rather than being clipped.
            val minimumBandwidth = PeqResponse.bandwidthOctaves(100.0, frequency)
            assertTrue(PeqResponse.qFromBandwidth(minimumBandwidth * 0.9999, frequency) > 100.0)
        }
    }

    @Test fun adaptiveCurveIncludesNarrowPeakAndNotchCentersExactly() {
        for (type in listOf(PeqFilterType.PEAK, PeqFilterType.NOTCH, PeqFilterType.BAND_PASS)) {
            val state = state(type, gain = 100.0, q = 100.0, frequency = 1388.8894)
            val curve = PeqResponse.adaptiveCurve(state)
            val center = curve.single { it.frequency == 1388.889 }
            val expected = when (type) {
                PeqFilterType.PEAK -> 100.0
                PeqFilterType.NOTCH -> PeqResponse.FLOOR_DB
                else -> 100.0
            }
            assertEquals(expected, center.db, 1e-8)
            assertTrue(curve.size >= PeqResponse.DEFAULT_CURVE_POINTS)
            assertTrue(curve.zipWithNext().all { (a, b) -> a.frequency < b.frequency })
            curve.forEach { assertEquals(PeqResponse.responseDb(state, it.frequency), it.db, 0.0) }
        }
    }

    @Test fun adaptive130BandCurveIsBoundedAndDoesNotDropAnyActiveCenter() {
        val state = PeqModel.defaultState(130).let { original -> original.copy(
            bands = original.bands.mapIndexed { index, band -> band.copy(q = 100.0,
                gain = if (index % 2 == 0) 100.0 else -100.0,
                type = PeqFilterType.entries[index % 7]) }) }
        val curve = PeqResponse.adaptiveCurve(state, points = 8192)
        val frequencies = curve.map { it.frequency }.toSet()
        state.bands.forEach { assertTrue(frequencies.contains(PeqProtocol.quantized(it).frequency)) }
        assertTrue(curve.size <= PeqResponse.MAX_ADAPTIVE_POINTS)
        assertTrue(curve.all { it.db.isFinite() })
    }

    @Test fun extremeShelvesRemainCorrectAtDcAndNearNyquistWithoutCoefficientCancellation() {
        for (sampleRate in listOf(40001.0, 44100.0, 48000.0, 96000.0, 192000.0)) {
            for (frequency in listOf(20.0, 20000.0)) {
                for (gain in listOf(-100.0, 100.0)) {
                    for (q in listOf(0.1, 100.0)) {
                        val low = state(PeqFilterType.LOW_SHELF, gain, q, frequency)
                        val high = state(PeqFilterType.HIGH_SHELF, gain, q, frequency)
                        assertEquals(gain, PeqResponse.responseDb(low, 0.0, sampleRate), 1e-8)
                        assertEquals(0.0, PeqResponse.responseDb(high, 0.0, sampleRate), 1e-8)
                        assertEquals(gain / 2.0, PeqResponse.responseDb(low, frequency, sampleRate), 1e-8)
                        assertEquals(gain / 2.0, PeqResponse.responseDb(high, frequency, sampleRate), 1e-8)
                        assertEquals(0.0, PeqResponse.responseDb(low, sampleRate / 2.0, sampleRate), 1e-8)
                        assertEquals(gain, PeqResponse.responseDb(high, sampleRate / 2.0, sampleRate), 1e-8)
                    }
                }
            }
        }
    }
}
