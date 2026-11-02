package me.timschneeberger.rootlessjamesdsp.creative

import me.timschneeberger.rootlessjamesdsp.effectvisuals.*
import org.junit.Assert.*
import org.junit.Test

class CreativeEffectsVisualsTest {
    @Test fun everyControlHasFiniteProfessionalRelationshipsAndAnIllustrationAtBothEnds() {
        CreativeEffectsKind.values().forEach { kind ->
            CreativeEffectsParameters.controls(kind).forEach { control ->
                listOf(control.min, control.max).forEach { value ->
                    val pair = CreativeEffectsVisuals.pair(kind, mapOf(kind.enableKey to true, control.key to value), control.key)
                    val plot = requireNotNull(pair.professional.plot)
                    assertTrue("${kind.name}/${control.key}", plot.curves.isNotEmpty() || plot.bars.isNotEmpty())
                    assertTrue(plot.xMaximum > plot.xMinimum)
                    assertTrue(plot.yMaximum > plot.yMinimum)
                    assertTrue(plot.curves.flatMap { it.points }.all { it.x.isFinite() && it.y.isFinite() })
                    assertTrue(plot.bars.all { it.value.isFinite() })
                    assertTrue(requireNotNull(pair.illustration.scene).elements.isNotEmpty())
                    assertFalse(pair.professional.title.contains("调节范围"))
                }
            }
        }
    }

    @Test fun spectralBlurAndShimmerFeedbackRespectNativeWeights() {
        val p = CreativeEffectsParameters
        val blur = CreativeEffectsVisuals.pair(CreativeEffectsKind.FREEZE, mapOf(p.freezeBlur.key to 1000), p.freezeBlur.key)
        assertEquals(listOf(25.0, 50.0, 25.0), requireNotNull(blur.professional.plot).bars.map { it.value })
        val shimmer = CreativeEffectsVisuals.pair(CreativeEffectsKind.SHIMMER, mapOf(p.shimmerAmount.key to 1000), p.shimmerAmount.key)
        val bars = requireNotNull(shimmer.professional.plot).bars
        assertEquals(35.0, bars[0].value, 1e-8)
        assertEquals(65.0, bars[1].value, 1e-8)
    }

    @Test fun reverbTimingShowsPreDelayAndARealSixtyDbDecayReference() {
        val p = CreativeEffectsParameters
        val pair = CreativeEffectsVisuals.pair(CreativeEffectsKind.SHIMMER,
            mapOf(p.shimmerPredelay.key to 250, p.shimmerDecay.key to 6500), p.shimmerDecay.key)
        val points = requireNotNull(pair.professional.plot).curves.single().points
        assertEquals(250.0, points.first { it.y == 0.0 }.x, 0.0)
        assertEquals(6750.0, points.last().x, 0.0)
        assertEquals(-60.0, points.last().y, 0.0)
    }

    @Test fun eachEffectUsesItsOwnNativeActivityRatherThanInputAndOutputPeakCurves() {
        val granular = runtime(CreativeEffectsKind.GRANULAR, activity = 7, phase = .4f, aux = 55555)
        val g = live(granular)
        assertTrue(requireNotNull(g.scene).elements.filterIsInstance<FigureLabel>().any { it.text == "7 粒" })
        assertTrue(g.scene!!.elements.filterIsInstance<FigureArc>().any { kotlin.math.abs(it.sweepDegrees - 144) < .01 })
        assertEquals(g.scene, live(granular.copy(inputPeakDb = -5f, outputPeakDb = -7f)).scene)

        val frozen = runtime(CreativeEffectsKind.FREEZE, activity = 1).copy(spectrumDb = List(16) { if (it == 8) -12f else -90f })
        val bars = requireNotNull(live(frozen).scene).elements.filterIsInstance<FigureBox>()
        assertEquals(16, bars.size)
        assertTrue("louder spectrum must rise higher from the floor", bars[8].top < bars[0].top)
        assertEquals(bars[8].bottom, bars[0].bottom, 0.0)
        assertTrue(requireNotNull(live(frozen.copy(activity = 0)).scene).elements.none { it is FigureBox })

        val shimmer = runtime(CreativeEffectsKind.SHIMMER, activity = 8, phase = .2f, aux = 1200)
        val a = requireNotNull(live(shimmer).scene).elements.filterIsInstance<FigureCircle>()
        val b = requireNotNull(live(shimmer.copy(phase = .4f)).scene).elements.filterIsInstance<FigureCircle>()
        assertNotEquals(a, b)
        assertEquals(live(shimmer).scene, live(shimmer.copy(inputPeakDb = -3f, outputPeakDb = -1f)).scene)
    }

    @Test fun stereoReferencesKeepMidAndReduceSideInsteadOfInventingSourcePositions() {
        val p = CreativeEffectsParameters
        val plot = requireNotNull(CreativeEffectsVisuals.pair(CreativeEffectsKind.FREEZE,
            mapOf(p.freezeWidth.key to 1000), p.freezeWidth.key).professional.plot)
        assertEquals(.5, plot.curves[0].points.first().y, 0.0)
        assertEquals(.5, plot.curves[1].points.first().y, 0.0)
        assertEquals(1.0, plot.curves[0].points.last().y, 0.0)
        assertEquals(0.0, plot.curves[1].points.last().y, 0.0)
    }

    @Test fun zeroShimmerAmountKeepsReverbWithoutPretendingTheSavedPitchIsAudible() {
        val p = CreativeEffectsParameters
        val pair = CreativeEffectsVisuals.pair(CreativeEffectsKind.SHIMMER,
            mapOf(p.shimmerAmount.key to 0, p.shimmerPitch.key to 1200), p.shimmerPitch.key)
        val scene = requireNotNull(pair.illustration.scene)
        assertTrue(pair.illustration.title.contains("无闪烁"))
        assertTrue(scene.elements.filterIsInstance<FigureLabel>().any { it.text == "常规混响尾音" })
        assertTrue(scene.elements.filterIsInstance<FigureLine>().none { it.arrow })
        assertTrue("regular reverb reflections remain", scene.elements.any { it is FigureArc })
        assertEquals(2000.0, requireNotNull(pair.professional.plot).curves.first().points.last().y, .0001)
    }

    @Test fun zeroMixShowsOriginalSignalAndZeroEffectWeight() {
        val p = CreativeEffectsParameters
        CreativeEffectsKind.values().forEach { kind ->
            val control = p.controls(kind).first { it.key.endsWith("_mix") }
            val scene = requireNotNull(CreativeEffectsVisuals.pair(kind, mapOf(control.key to 0), control.key).illustration.scene)
            val labels = scene.elements.filterIsInstance<FigureLabel>()
            assertTrue(labels.any { it.text == "100%" && it.colorIndex == 2 })
            assertTrue(labels.any { it.text == "0%" && it.colorIndex == 0 })
        }
    }

    private fun live(runtime: CreativeEffectsRuntime) = CreativeEffectsVisuals.live(runtime.kind,
        listOf(CreativeEffectsVisuals.Sample(20000, runtime)))
    private fun runtime(kind: CreativeEffectsKind, activity: Int, phase: Float = .5f, aux: Int = 1) =
        CreativeEffectsRuntime(kind, true, 48000, 512, 123, -12f, -14f, -18f,
            activity, aux, phase, List(16) { -60f }, engineEnabled = true, isProcessing = true)
}
