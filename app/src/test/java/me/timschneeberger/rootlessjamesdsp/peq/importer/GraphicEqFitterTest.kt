package me.timschneeberger.rootlessjamesdsp.peq.importer

import me.timschneeberger.rootlessjamesdsp.peq.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.*

class GraphicEqFitterTest {
    @Test fun constantAttenuationAndPreampStayAbsoluteAtEverySampleRate() {
        val curve = GraphicEqParser.parse("Preamp: -2.5 dB\nGraphicEQ: 20 -8; 20000 -8")
        val base = PeqModel.defaultState().copy(gainRange = 6.0, gainStep = 0.25, qStep = 0.25, pinResponse = false)
        for (rate in listOf(44100.0, 48000.0, 96000.0)) {
            val fit = GraphicEqFitter.fit(curve, base, GraphicEqFitOptions(sampleRate = rate))
            assertTrue(fit.targetMet)
            assertEquals(2, fit.offsetBandCount)
            assertEquals(2, fit.state.bands.size)
            assertTrue(fit.maxErrorDb < 1e-8)
            for (frequency in listOf(0.0, 1.0, 1000.0, 20000.0, rate / 2.0))
                assertEquals(-10.5, PeqResponse.responseDb(fit.state, frequency, rate), 1e-8)
            assertEquals(11.0, fit.state.gainRange, 0.0)
            assertEquals(base.gainStep, fit.state.gainStep, 0.0)
            assertEquals(base.qStep, fit.state.qStep, 0.0)
            assertEquals(base.pinResponse, fit.state.pinResponse)
            assertEquals(base.graphMaxFrequency, fit.state.graphMaxFrequency, 0.0)
        }
    }

    @Test fun neutralCurveNeedsNoSectionsAndDoesNotRetainTheOldBank() {
        val fit = GraphicEqFitter.fit(GraphicEqParser.parse("GraphicEQ: 20 0; 20000 0"),
            PeqState(listOf(PeqBand(gain = 12.0))))
        assertTrue(fit.targetMet)
        assertEquals(0, fit.offsetBandCount)
        assertTrue(fit.state.bands.isEmpty())
        assertEquals(0.0, fit.maxErrorDb, 0.0)
    }

    @Test fun reproducesAKnownOverlappingPeqCurveIncludingSchemaThreeHighQ() {
        val known = PeqState(listOf(
            PeqBand(type = PeqFilterType.LOW_SHELF, frequency = 1000.0, gain = -7.0),
            PeqBand(type = PeqFilterType.HIGH_SHELF, frequency = 1000.0, gain = -7.0),
            PeqBand(frequency = 100.0, gain = 4.5, q = 0.8),
            PeqBand(frequency = 2500.0, gain = -3.0, q = 2.5)
        ))
        val curve = GraphicEqCurve(logFrequencies(257).map { GraphicEqPoint(it, PeqResponse.responseDb(known, it)) })
        val fit = GraphicEqFitter.fit(curve, PeqModel.defaultState(), GraphicEqFitOptions(maxBands = 20, targetErrorDb = 0.5))
        assertTrue("max error=${fit.maxErrorDb}; bands=${fit.state.bands.size}", fit.targetMet)
        assertTrue(fit.state.bands.size < 20)
        // Independent dense points exercise the existing public evaluator, not the fitter grid.
        val independentWorst = logFrequencies(4099).maxOf { abs(curve.gainAt(it) - PeqResponse.responseDb(fit.state, it)) }
        assertTrue("independent max=$independentWorst", independentWorst <= 0.51)
        assertTrue(fit.state.bands.all { it.q <= 12.0 })
    }

    @Test fun screenshotStyleNegativeGraphicCurveIsNotShiftedUpToZero() {
        val curve = GraphicEqCurve(logFrequencies(81).map { frequency ->
            val gain = -8.0 + 4.0 * exp(-(ln(frequency / 80.0) / 0.7).pow(2)) -
                2.0 * exp(-(ln(frequency / 3500.0) / 0.3).pow(2))
            GraphicEqPoint(frequency, gain)
        })
        val fit = GraphicEqFitter.fit(curve, PeqModel.defaultState(), GraphicEqFitOptions(maxBands = 20))
        assertTrue("max=${fit.maxErrorDb}", fit.targetMet)
        assertTrue(fit.fittedDb.maxOrNull()!! < -3.0)
        assertEquals(curve.gainAt(20.0), PeqResponse.responseDb(fit.state, 20.0), 0.5)
        assertEquals(curve.gainAt(20000.0), PeqResponse.responseDb(fit.state, 20000.0), 0.5)
    }

    @Test fun reportsAnUnachievableSharpCurveHonestlyIncludingItsExactSourceKnot() {
        val curve = GraphicEqParser.parse("GraphicEQ: 20 -20; 999.999 -20; 1000 20; 1000.001 -20; 20000 -20")
        val fit = GraphicEqFitter.fit(curve, PeqModel.defaultState(), GraphicEqFitOptions(maxBands = 2, targetErrorDb = 0.1))
        assertFalse(fit.targetMet)
        assertEquals(40.0, fit.maxErrorDb, 1e-8)
        assertEquals(1000.0, fit.maxErrorFrequency, 0.0)
        assertTrue(fit.frequencies.any { it == 1000.0 })
        assertTrue(fit.rmsErrorDb.isFinite())
        assertTrue(fit.meanAbsoluteErrorDb > 0.0)
    }

    @Test fun clipsAtActualNyquistAndCountsExcludedControlPoints() {
        val curve = GraphicEqParser.parse("GraphicEQ: 5 -3; 20 -3; 2000 -3; 4000 -3; 6000 -3; 20000 -3")
        val fit = GraphicEqFitter.fit(curve, PeqModel.defaultState(), GraphicEqFitOptions(sampleRate = 8000.0))
        assertEquals(3, fit.clippedPointCount)
        assertEquals(20.0, fit.frequencies.first(), 1e-12)
        assertEquals(4000.0, fit.frequencies.last(), 1e-12)
        assertTrue(fit.state.bands.all { it.frequency < 4000.0 })
        assertTrue(fit.targetMet)
    }

    @Test fun rejectsInvalidBudgetsAndFrequencyBounds() {
        val curve = GraphicEqParser.parse("GraphicEQ: 20 -3; 20000 -3")
        for (options in listOf(GraphicEqFitOptions(maxBands = 1), GraphicEqFitOptions(maxBands = 131),
            GraphicEqFitOptions(sampleRate = Double.NaN), GraphicEqFitOptions(targetErrorDb = 0.0),
            GraphicEqFitOptions(sampleRate = 8000.0, minFrequency = 5000.0))) {
            try {
                GraphicEqFitter.fit(curve, PeqModel.defaultState(), options)
                fail("Expected invalid options: $options")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun cancellationIsCheckedBeforeAndDuringTheSearch() {
        val curve = GraphicEqParser.parse("GraphicEQ: 20 -8; 100 -1; 3000 -12; 20000 -4")
        for (after in listOf(0, 25)) {
            var checks = 0
            try {
                GraphicEqFitter.fit(curve, PeqModel.defaultState(), isCancelled = { ++checks > after })
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                assertTrue(checks > after)
            }
        }
    }

    @Test fun batchEvaluatorMatchesThePublicResponseForAllSupportedFilterTypes() {
        val frequencies = doubleArrayOf(0.0, 20.0, 100.0, 777.123, 1000.0, 5000.0, 20000.0, 24000.0)
        for (type in PeqFilterType.entries) {
            val state = PeqState(listOf(PeqBand(type = type, frequency = 777.1234, gain = -6.1234, q = 2.7)))
            val batch = PeqResponse.ResponseGrid(frequencies).stateDb(state)
            frequencies.forEachIndexed { index, frequency ->
                assertEquals("$type / $frequency", PeqResponse.responseDb(state, frequency), batch[index], 1e-9)
            }
        }
    }

    private fun logFrequencies(count: Int): List<Double> = List(count) { index ->
        if (index == count - 1) 20000.0 else 20.0 * exp(ln(1000.0) * index / (count - 1))
    }
}
