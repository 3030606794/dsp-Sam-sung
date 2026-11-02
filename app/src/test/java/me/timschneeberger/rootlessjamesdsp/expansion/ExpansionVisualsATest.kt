package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.effectvisuals.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ExpansionVisualsATest {
    private fun settings(kind: ExpansionEffectsKind, vararg values: Pair<String, Int>) = values.associate { (key, value) ->
        ExpansionEffectsParameters.control(kind, key).key to value
    }
    private fun runtime(kind: ExpansionEffectsKind, vararg changed: Pair<Int, Float>): ExpansionEffectsRuntime {
        val meters = MutableList(116) { if (it == 3) 1f else 0f }; changed.forEach { meters[it.first] = it.second }
        return ExpansionEffectsRuntime(kind, true, 48000, 8192, 7, -10f, -12f, -16f, meters, true, true)
    }
    @Test fun everyControlHasFiniteProfessionalModelAndDistinctExplanatoryScene() {
        val scenes = mutableSetOf<EffectScene>()
        ExpansionEffectsKind.values().filter { it.id < 10 }.forEach { kind ->
            scenes += requireNotNull(ExpansionVisualsA.static(kind, emptyMap<String, Any>(), null).illustration.scene)
            ExpansionEffectsParameters.controls(kind).forEach { control ->
                listOf(control.min, control.default, control.max).forEach { value ->
                    val pair = ExpansionVisualsA.static(kind, mapOf(control.key to value), control.key)
                    val p = requireNotNull(pair.professional.plot)
                    assertTrue("$kind ${control.key} x domain", p.xMaximum > p.xMinimum)
                    assertTrue("$kind ${control.key} y domain", p.yMaximum > p.yMinimum)
                    (p.curves.flatMap { it.points }.map { it.x to it.y } + p.marks.map { it.x to it.y }).forEach {
                        assertTrue("$kind ${control.key} finite x", it.first.isFinite())
                        assertTrue("$kind ${control.key} finite y", it.second.isFinite())
                    }
                    assertNotNull(pair.illustration.scene)
                    assertFalse(pair.professional.plot!!.bars.any { it.label == "默认" || it.label == "当前" })
                }
            }
        }
        assertEquals("Ten effects need ten different explanatory figures", 10, scenes.size)
    }
    @Test fun selectedParameterChangesTheProfessionalQuestionBeingAnswered() {
        val kindsAndGroups = listOf(
            ExpansionEffectsKind.MICROSHIFT to listOf("detune", "delay", "tone", "width"),
            ExpansionEffectsKind.FORMANT to listOf("shift", "smoothing", "low"),
            ExpansionEffectsKind.VOCODER to listOf("carrier", "attack", "bands", "unvoiced"),
            ExpansionEffectsKind.GATED_REVERB to listOf("hold", "threshold", "damping", "size"),
            ExpansionEffectsKind.AUTO_WAH to listOf("q", "sensitivity", "attack"),
            ExpansionEffectsKind.TAPE to listOf("drive", "wow", "flutter", "tone", "hf_compression", "noise")
        )
        kindsAndGroups.forEach { (kind, groups) ->
            val plots = groups.map { key -> ExpansionVisualsA.static(kind, emptyMap<String, Any>(),
                ExpansionEffectsParameters.control(kind, key).key).professional.title }
            assertEquals("$kind cannot recycle one generic plot for unrelated controls", groups.size, plots.distinct().size)
        }
    }
    @Test fun frequencyShiftUsesEqualHzOffsetsInsteadOfPitchRatios() {
        val kind = ExpansionEffectsKind.FREQUENCY_SHIFT
        val p = ExpansionVisualsA.static(kind, settings(kind, "shift" to 40000, "stereo" to 20000), "p1").professional.plot!!
        assertTrue(p.curves[0].points.all { abs((it.y - it.x) - 50) < 1e-8 })
        assertTrue(p.curves[1].points.all { abs((it.y - it.x) - 30) < 1e-8 })
    }
    @Test fun pureAllpassKeepsMagnitudeButHalfMixHasCancellation() {
        val kind = ExpansionEffectsKind.LOW_PHASE
        val pure = ExpansionVisualsA.static(kind, settings(kind, "mix" to 1000, "amount" to 1000, "stages" to 1000), "p2").professional.plot!!
        assertTrue(pure.curves.all { c -> c.points.all { abs(it.y) < 1e-8 } })
        val mixed = ExpansionVisualsA.static(kind, settings(kind, "mix" to 1000, "amount" to 500, "stages" to 1000), "p2").professional.plot!!
        assertTrue(mixed.curves.first().points.any { it.y < -20 })
        val live = ExpansionVisualsA.live(kind, emptyMap<String, Any>(), null, runtime(kind, 4 to 100f, 5 to 2f, 6 to 1f, 3 to 1f), emptyList())
        assertTrue(live.subtitle.contains("非音频示波"))
    }
    @Test fun realtimeGeometryFollowsPublishedGateAndPanRatherThanClock() {
        val gate = ExpansionEffectsKind.GATED_REVERB
        val a = ExpansionVisualsA.live(gate, emptyMap<String, Any>(), "p3", runtime(gate, 4 to 0f), emptyList())
        val b = ExpansionVisualsA.live(gate, emptyMap<String, Any>(), "p3", runtime(gate, 4 to 1f), emptyList())
        assertNotEquals(a.scene, b.scene)
        val pan = ExpansionEffectsKind.AUTOPAN
        val left = ExpansionVisualsA.live(pan, emptyMap<String, Any>(), "p2", runtime(pan, 5 to 1.4142135f, 6 to 0f), emptyList())
        val right = ExpansionVisualsA.live(pan, emptyMap<String, Any>(), "p2", runtime(pan, 5 to 0f, 6 to 1.4142135f), emptyList())
        assertNotEquals(left.scene, right.scene)
        assertEquals(right, ExpansionVisualsA.live(pan, emptyMap<String, Any>(), "p2", runtime(pan, 5 to 0f, 6 to 1.4142135f), emptyList()))
    }
    @Test fun publishedVocoderBandsAndHistoryGapsArePreserved() {
        val kind = ExpansionEffectsKind.VOCODER
        val quiet = ExpansionVisualsA.live(kind, emptyMap<String, Any>(), null, runtime(kind, *(4..19).map { it to -90f }.toTypedArray()), emptyList())
        val loud = ExpansionVisualsA.live(kind, emptyMap<String, Any>(), null, runtime(kind, *(4..19).map { it to -18f }.toTypedArray()), emptyList())
        assertNotEquals(quiet.scene, loud.scene)
        assertTrue(loud.subtitle.contains("-18.0"))
        val frames = listOf(0L, 1000L, 2000L, 3000L).mapIndexed { i, t ->
            EffectVisualFrame(t, MutableList(116) { if (it == 4) i / 4f else 0f }, -10f, -12f, -16f, 48000, i == 2)
        }
        val live = ExpansionVisualsA.live(ExpansionEffectsKind.GATED_REVERB, emptyMap<String, Any>(), "p1", runtime(ExpansionEffectsKind.GATED_REVERB), frames)
        assertEquals("A stale gap must break the gate trace", 2, live.plot!!.curves.size)
        assertTrue(live.plot!!.curves.all { it.points.size == 2 })
    }
    @Test fun livePanIncludesTheActualDryWetBlend() {
        val kind = ExpansionEffectsKind.AUTOPAN
        val dry = ExpansionVisualsA.live(kind, emptyMap<String, Any>(), "p2", runtime(kind, 3 to 0f, 5 to 0f, 6 to 1.414f), emptyList())
        val labels = dry.scene!!.elements.filterIsInstance<FigureLabel>().map { it.text }
        assertTrue(labels.contains("左 100%")); assertTrue(labels.contains("右 100%"))
    }
    @Test fun noCrossFeedbackDrawsOnlySameSideReturnsAndNegativeFrequenciesMirror() {
        val ping = ExpansionEffectsKind.PINGPONG
        val visual = ExpansionVisualsA.live(ping, settings(ping, "cross" to 0), "p4", runtime(ping, 4 to 250f, 6 to .5f), emptyList())
        assertTrue(visual.scene!!.elements.filterIsInstance<FigureLine>().all { it.x1 == it.x2 })
        val shift = ExpansionEffectsKind.FREQUENCY_SHIFT
        val plot = ExpansionVisualsA.static(shift, settings(shift, "shift" to -2000000), "p0").professional.plot!!
        assertEquals(1560.0, plot.marks.last().x, 1e-8)
    }
    @Test fun flutterReferenceWrapsLikeTheNativeOscillator() {
        val kind = ExpansionEffectsKind.TAPE
        val plot = ExpansionVisualsA.static(kind, settings(kind, "flutter_rate" to 6000), "p4").professional.plot!!
        val points = plot.curves.single().points
        assertEquals(points.first().y, points[48].y, 1e-8)
        assertEquals(points.first().y, points.last().y, 1e-8)
    }
    @Test fun wahUsesAppliedMixAndTheNativeSampleRateCeiling() {
        val kind = ExpansionEffectsKind.AUTO_WAH
        val sample = runtime(kind, 3 to 0f, 4 to 12000f, 6 to 6f).copy(sampleRate = 8000)
        val dry = ExpansionVisualsA.live(kind, emptyMap<String, Any>(), "p0", sample, emptyList())
        assertTrue(dry.plot!!.curves.single().points.all { abs(it.y) < 1e-8 })
        assertEquals(3360.0, dry.plot!!.marks.single().x, 1e-8)
        val history = listOf(EffectVisualFrame(1000, sample.meters, -10f, -12f, -16f, 8000))
        val tracking = ExpansionVisualsA.live(kind, emptyMap<String, Any>(), "p5", sample, history)
        assertEquals(3360.0, tracking.plot!!.curves.single().points.single().y, 1e-8)
    }
    @Test fun zeroAutopanMixHasNoIllustratedPanRangeOrTremoloDepth() {
        val kind = ExpansionEffectsKind.AUTOPAN
        val pan = ExpansionVisualsA.static(kind, settings(kind, "mix" to 0, "bias" to 900, "depth" to 1000), "p0")
        val endpoints = pan.illustration.scene!!.elements.filterIsInstance<FigureCircle>().take(2)
        assertTrue(endpoints.all { abs(it.x - .5) < 1e-8 })
        val tremolo = ExpansionVisualsA.static(kind, settings(kind, "mix" to 0, "mode" to 1000), "p0")
        assertTrue(tremolo.illustration.scene!!.elements.filterIsInstance<FigureArc>().all { it.alpha == 0.0 })
    }
}
