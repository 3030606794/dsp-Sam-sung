package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.effectvisuals.FigureLabel
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SpeakerOptimizationVisualsTest {
    private val kind get() = ExpansionEffectsKind.SPEAKER_OPTIMIZATION
    private fun settings(vararg values: Pair<String, Double>): Map<String, Any> =
        values.associate { (name, value) -> ExpansionEffectsParameters.control(kind, name).key to (value * 1000).roundToInt() }
    private fun key(logical: String) = ExpansionEffectsParameters.control(kind, logical).key

    @Test fun classicCenterRetainsUpstreamThreePointEightEightBandpassPeak() {
        val r = SpeakerOptimizationVisuals.response(emptyMap<String, Any>(), 420.0)
        val ratio = r.total / (r.highpass * r.lowpass)
        assertEquals(.5 * (1 + 3.88), ratio.re, 1e-7)
        assertEquals(0.0, ratio.im, 1e-7)
        // The compensation takes the already high/low-passed, base-scaled signal.
        val branch = r.body / (r.highpass * r.lowpass)
        assertEquals(.5 * 3.88, branch.re, 1e-7)
    }

    @Test fun changingBandpassQDoesNotSilentlyChangeTheIndependentPeakStrength() {
        listOf(.3, 1.0, 3.88, 10.0).forEach { q ->
            val r = SpeakerOptimizationVisuals.response(settings("body_q" to q, "body_amount" to .7, "base_level" to .4), 420.0)
            val branch = r.body / (r.highpass * r.lowpass)
            assertEquals(.4 * 3.88 * .7, branch.magnitude(), 1e-7)
        }
    }

    @Test fun mixingKeepsPhaseAndOutputGainIsAfterTheWholeMix() {
        val values = settings("mix" to .5, "base_level" to 1.0, "body_amount" to 0.0,
            "low_cut" to 80.0, "low_q" to 1.0, "output_gain" to 0.0)
        val r = SpeakerOptimizationVisuals.response(values, 80.0)
        val actualDb = 20 * log10(r.total.magnitude())
        assertTrue("quadrature HP/dry mix must lose approximately 3 dB", actualDb in -3.5..-2.5)
        val wrongMagnitudeMix = .5 + .5 * r.wet.magnitude()
        assertTrue(abs(r.total.magnitude() - wrongMagnitudeMix) > .2)
        listOf(20.0, 420.0, 18000.0).forEach { hz ->
            val dry = SpeakerOptimizationVisuals.response(settings("mix" to 0.0, "output_gain" to -6.0), hz)
            assertEquals(10.0.pow(-6.0 / 20), dry.total.magnitude(), 1e-12)
        }
    }

    @Test fun zeroBaseAlsoSilencesBodyAndZeroBodyPreservesBaseFilters() {
        val zero = SpeakerOptimizationVisuals.response(settings("base_level" to 0.0, "mix" to 1.0, "body_amount" to 2.0), 420.0)
        assertEquals(0.0, zero.body.magnitude(), 0.0)
        assertEquals(0.0, zero.wet.magnitude(), 0.0)
        assertEquals(0.0, zero.total.magnitude(), 0.0)
        val partial = SpeakerOptimizationVisuals.response(settings("base_level" to 0.0, "mix" to .25), 420.0)
        assertEquals(.75, partial.total.magnitude(), 1e-12)
        val plain = SpeakerOptimizationVisuals.response(settings("body_amount" to 0.0), 420.0)
        assertEquals((plain.highpass * plain.lowpass * .5).magnitude(), plain.total.magnitude(), 1e-12)
        val scene = SpeakerOptimizationVisuals.static(settings("mix" to 1.0, "base_level" to 0.0), key("base_level")).illustration.scene!!
        assertTrue(scene.elements.filterIsInstance<FigureLabel>().any { it.text.contains("无输出") })
    }

    @Test fun allUserControlLimitsHaveFiniteAndVisibleProfessionalGraphs() {
        ExpansionEffectsParameters.controls(kind).forEach { c ->
            listOf(c.min, c.max).forEach { limit ->
                val visual = SpeakerOptimizationVisuals.static(mapOf(c.key to limit), c.key)
                val plot = visual.professional.plot
                if (plot != null) {
                    assertTrue(plot.yMaximum > plot.yMinimum)
                    assertTrue(plot.xMaximum > plot.xMinimum)
                    plot.curves.flatMap { it.points }.forEach {
                        assertTrue(it.x.isFinite() && it.y.isFinite())
                        assertTrue(it.y >= plot.yMinimum - .0001 && it.y <= plot.yMaximum + .0001)
                    }
                } else assertNotNull(visual.professional.scene)
                assertNotNull(visual.illustration.scene)
            }
        }
    }

    @Test fun actualSpectraUseRounded2048BinsAndDeduplicateAtEverySampleRate() {
        listOf(8000, 16000, 44100, 48000, 96000, 192000, 768000).forEach { rate ->
            val source = runtime(rate)
            val result = SpeakerOptimizationVisuals.live(emptyMap<String, Any>(), null, source, emptyList())
            val plot = requireNotNull(result.plot)
            val f = SpeakerOptimizationVisuals.spectrumFrequencies(rate)
            val points = plot.curves.first().points
            assertEquals(f.distinct(), points.map { it.x })
            assertTrue(points.zipWithNext().all { (a, b) -> a.x < b.x })
            assertTrue(points.all { it.x < rate / 2.0 })
            points.forEach { point ->
                val bin = point.x * 2048 / rate
                assertEquals(bin.roundToInt().toDouble(), bin, .00001)
            }
            assertEquals(f.first(), plot.xMinimum, 0.0)
            assertEquals(f.last(), plot.xMaximum, 0.0)
            assertEquals(-30.0, plot.curves.first().points.first().y, 0.0)
            assertEquals(-24.0, plot.curves.last().points.first().y, 0.0)
        }
    }

    @Test fun bypassOrIncompleteWindowNeverProducesFakeSpectrum() {
        val ready = runtime(48000)
        listOf(ready.copy(meters = ready.meters.toMutableList().apply { this[4] = 0f }),
            ready.copy(meters = ready.meters.toMutableList().apply { this[7] = 0f }),
            ready.copy(isProcessing = false)).forEach { sample ->
            val visual = SpeakerOptimizationVisuals.live(emptyMap<String, Any>(), null, sample, emptyList())
            assertNull(visual.plot)
            assertTrue(visual.scene!!.elements.filterIsInstance<FigureLabel>().any { it.text.contains("等待") || it.text.contains("原声") })
        }
        val zeroMix = ready.copy(meters = ready.meters.toMutableList().apply { this[3] = 0f; this[12] = -6f })
        assertNotNull(SpeakerOptimizationVisuals.live(emptyMap<String, Any>(), null, zeroMix, emptyList()).plot)
    }

    private fun runtime(rate: Int): ExpansionEffectsRuntime {
        val meters = MutableList(116) { 0f }
        meters[3] = 1f; meters[4] = 1f; meters[5] = 1f; meters[7] = 2f
        meters[8] = 80f; meters[9] = min(13500f, rate * .48f); meters[10] = 420f
        repeat(48) { meters[20 + it] = -30f - it; meters[68 + it] = -24f - it }
        return ExpansionEffectsRuntime(kind, true, rate, 48000, 1, -12f, -14f, -18f, meters,
            engineEnabled = true, isProcessing = true)
    }
}
