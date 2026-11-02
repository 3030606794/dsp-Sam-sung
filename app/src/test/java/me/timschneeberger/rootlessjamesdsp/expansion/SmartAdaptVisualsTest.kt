package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.effectvisuals.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class SmartAdaptVisualsTest {
    private val kind = ExpansionEffectsKind.SMART_ADAPT
    private fun key(name: String) = ExpansionEffectsParameters.control(kind, name).key
    private fun settings(vararg pairs: Pair<String, Double>): Map<String, Any> =
        pairs.associate { key(it.first) to (it.second * 1000).roundToInt() } + (kind.enableKey to true)
    private fun runtime(phase: Int = 3): ExpansionEffectsRuntime {
        val meters = MutableList(116) { 0f }.apply {
            this[0] = phase.toFloat(); this[1] = 2500f
            this[2] = 1.25f; this[3] = -.75f; this[4] = .4f
            this[5] = .08f; this[6] = -1.4f
            this[7] = 4f; this[8] = -5f; this[9] = 3f; this[10] = .3f
            this[11] = .72f; this[12] = -25f; this[13] = -26f; this[14] = -33f
            this[15] = .35f; this[22] = 2f; this[24] = 2.5f; this[25] = 3f
            this[28] = 3f; this[29] = 4f
        }
        return ExpansionEffectsRuntime(kind, true, 48000, 48000, 1, -12f, -14f, -18f, meters, true, true)
    }
    private fun frame(time: Long, sample: ExpansionEffectsRuntime, split: Boolean = false) =
        EffectVisualFrame(time, sample.meters, sample.inputPeakDb, sample.outputPeakDb, sample.wetPeakDb, sample.sampleRate, split)

    @Test fun middlePeakMatchesNativeQAndShelvesHaveCorrectAsymptotes() {
        assertEquals(6.0, SmartAdaptVisuals.filterDb(1, 6.0, 1800.0), 1e-8)
        assertEquals(-6.0, SmartAdaptVisuals.filterDb(1, -6.0, 1800.0), 1e-8)
        assertEquals(6.0, SmartAdaptVisuals.filterDb(0, 6.0, 0.0), 1e-6)
        assertEquals(0.0, SmartAdaptVisuals.filterDb(2, 6.0, 0.0), 1e-6)
        assertEquals(6.0, SmartAdaptVisuals.filterDb(2, 6.0, 24000.0), 1e-6)
        assertEquals(0.0, SmartAdaptVisuals.filterDb(0, 6.0, 24000.0), 1e-6)
    }

    @Test fun sideBranchRetainsOriginalLowFrequencyStereo() {
        assertEquals(0.0, SmartAdaptVisuals.sideDb(0.0, 180.0, .6), 1e-9)
        assertEquals(0.0, SmartAdaptVisuals.sideDb(1000.0, 180.0, 0.0), 1e-9)
        assertTrue(SmartAdaptVisuals.sideDb(10000.0, 180.0, .6) > 3.5)
        assertTrue(SmartAdaptVisuals.sideDb(20.0, 180.0, .6) < .2)
        val nonSpatial = SmartAdaptVisuals.static(settings("target" to 0.0, "width" to .6), key("width"))
        assertTrue(nonSpatial.professional.plot!!.curves.single().points.all { it.y == 0.0 })
    }

    @Test fun zeroStrengthBypassesBothToneAndManualTrimReferences() {
        listOf("strength", "trim", "width", "intensity").forEach { focus ->
            val pair = SmartAdaptVisuals.static(settings("strength" to 0.0, "trim" to -12.0, "target" to 3.0), key(focus))
            assertTrue(pair.professional.plot!!.curves.flatMap { it.points }.all { it.y == 0.0 })
        }
    }

    @Test fun independentFrequencyParticipationDoesNotBecomeSixIndependentFftControls() {
        val pair = SmartAdaptVisuals.static(settings("intensity" to 0.0, "strength" to 1.0, "max_boost" to 6.0,
            "low_amount" to 0.0, "mid_amount" to 1.0, "high_amount" to 0.0), key("mid_amount"))
        pair.professional.plot!!.curves.first().points.forEach {
            assertEquals(SmartAdaptVisuals.filterDb(1, 6.0, it.x), it.y, 1e-9)
        }
        assertEquals(2, pair.professional.plot!!.curves.size)
    }

    @Test fun everyControlHasFiniteBoundedReferencesAndAppropriateFocus() {
        ExpansionEffectsParameters.controls(kind).forEach { control ->
            listOf(control.min, control.max).forEach { bound ->
                val pair = ExpansionEffectsVisuals.static(kind, mapOf(control.key to bound), control.key)
                assertTrue(pair.professional.inactive)
                assertNotNull(pair.illustration.scene)
                pair.professional.plot?.let { p ->
                    assertTrue(p.xMinimum.isFinite() && p.xMaximum > p.xMinimum)
                    assertTrue(p.yMinimum.isFinite() && p.yMaximum > p.yMinimum)
                    p.curves.flatMap { it.points }.forEach {
                        assertTrue(it.x.isFinite() && it.y.isFinite())
                        assertTrue(it.y >= p.yMinimum - 1e-6 && it.y <= p.yMaximum + 1e-6)
                    }
                } ?: assertNotNull(pair.professional.scene)
            }
        }
        val titles = listOf("strength", "analysis", "width", "trim", "transient", "device", "intensity").map {
            SmartAdaptVisuals.static(settings(), key(it)).professional.title
        }
        assertEquals(7, titles.distinct().size)
        assertTrue(SmartAdaptVisuals.static(settings("target" to 3.0), key("target")).professional.title.contains("侧声道"))
    }

    @Test fun illustrativePanelsAreLinkedToParticipationAndTimingSettings() {
        val weak = SmartAdaptVisuals.static(settings("strength" to .2), key("strength")).illustration
        val strong = SmartAdaptVisuals.static(settings("strength" to .8), key("strength")).illustration
        assertNotEquals(weak.scene, strong.scene)
        val short = SmartAdaptVisuals.static(settings("follow" to 200.0), key("follow")).illustration
        val long = SmartAdaptVisuals.static(settings("follow" to 8000.0), key("follow")).illustration
        assertNotEquals(short.scene, long.scene)
        assertTrue(long.scene!!.elements.filterIsInstance<FigureBox>().any { it.label == "跟随 8.0 s" })
    }

    @Test fun liveUsesSmoothedThreeGainsAndNeverFullStrengthFrozenTargets() {
        val s = runtime(4)
        val result = ExpansionEffectsVisuals.live(kind, settings(), key("strength"), s, listOf(frame(1000, s)))
        assertTrue(result.subtitle.contains("已锁定"))
        assertEquals(listOf(1.25, -.75, .4f.toDouble()), result.plot!!.curves.map { it.points.single().y })
        assertFalse(result.plot!!.curves.flatMap { it.points }.any { it.y == 4.0 || it.y == -5.0 })
    }

    @Test fun realMetersSelectSpaceProtectionHeadroomAndRouteGraphs() {
        val s = runtime()
        val expected = mapOf("width" to 8.0, "transient" to 35.0, "headroom" to -1.4, "device" to 1.0)
        expected.forEach { (focus, value) ->
            val plot = SmartAdaptVisuals.live(settings(), key(focus), s, listOf(frame(1000, s))).plot!!
            assertEquals(value, plot.curves.first().points.single().y, 1e-5)
        }
        val cuts = SmartAdaptVisuals.live(settings(), key("device"), s, listOf(frame(1000, s))).plot!!.curves.last()
        assertEquals(-1.2, cuts.points.single().y, 1e-6)
        val space = SmartAdaptVisuals.live(settings(), key("width"), s, listOf(frame(1000, s)))
        assertEquals("自动跟随", space.subtitle)
        assertTrue(space.notes.any { it.contains("相关度0.72") })
        val scene = SmartAdaptVisuals.static(settings("target" to 3.0), key("strength")).illustration.scene!!
        assertTrue(scene.elements.filterIsInstance<FigureLabel>().any { it.text == "空间目标" })
    }

    @Test fun warmingDisplaysObservedDurationInSecondsWithoutPercentageConfidence() {
        val s = runtime(2)
        val graph = SmartAdaptVisuals.live(settings(), key("strength"), s, listOf(frame(1000, s)))
        assertEquals("有效音频积累", graph.title)
        assertEquals("秒", graph.plot!!.yLabel)
        assertEquals(2.5, graph.plot!!.curves.single().points.single().y, 0.0)
    }

    @Test fun disabledWaitingAndMalformedSamplesNeverFabricateActivity() {
        val s = runtime()
        listOf(s.copy(enabled = false), s.copy(isProcessing = false), runtime(0), runtime(1),
            s.copy(meters = listOf(3f)), s.copy(meters = s.meters.toMutableList().apply { this[2] = Float.NaN })).forEach {
            val graph = SmartAdaptVisuals.live(settings(), null, it, listOf(frame(1000, s)))
            assertNull(graph.plot)
            assertNotNull(graph.scene)
        }
        val empty = SmartAdaptVisuals.live(settings(), null, s, emptyList())
        assertTrue(empty.plot!!.curves.isEmpty())
    }

    @Test fun missingFramesAndExplicitBreaksAreNotConnectedAcrossHistory() {
        val s = runtime()
        val bad = s.copy(meters = emptyList())
        val history = listOf(frame(0, s), frame(15000, s), frame(16000, bad), frame(17000, s),
            frame(18000, s, true), frame(19000, s), frame(25000, s))
        val graph = SmartAdaptVisuals.live(settings(), null, s, history).plot!!
        val low = graph.curves.filter { it.colorIndex == 0 }
        assertEquals(listOf(1, 1, 3), low.map { it.points.size })
        assertTrue(low.flatMap { it.points }.all { it.x in -20.0..0.0 })
        assertEquals(listOf(-10.0, -8.0, -7.0, -6.0, 0.0), low.flatMap { it.points }.map { it.x })
    }

    @Test fun telemetryDoesNotInterpretPhaseAsAnAudioLevelOrAProbability() {
        val s = runtime(6)
        assertEquals(1.25f, ExpansionEffectsTelemetry.first(s), 0f)
        assertEquals(.4f, ExpansionEffectsTelemetry.second(s), 0f)
        assertTrue(ExpansionEffectsTelemetry.readout(s).contains("低 1.3 / 中 -0.8 / 高 0.4"))
        assertTrue(ExpansionEffectsTelemetry.unavailable(s)!!.contains("设备策略适配"))
        assertFalse(ExpansionEffectsTelemetry.legend(kind).contains("dBFS"))
        assertTrue(ExpansionEffectsTelemetry.readout(s.copy(meters = emptyList())).contains("—"))
    }

    @Test fun fiveLevelReferenceScalesBeforeTwelveDbClampStrengthAndParticipation() {
        val values = settings("intensity" to 3.0, "strength" to .4,
            "max_boost" to 2.5, "max_cut" to 3.0,
            "low_amount" to .5, "mid_amount" to .25, "high_amount" to 0.0)
        val result = SmartAdaptVisuals.static(values, key("intensity"))
        val plot = result.professional.plot!!
        val expectedBoost = listOf(.5, .875, 1.375, 2.0, 2.4)
        plot.curves[0].points.forEachIndexed { i, p -> assertEquals(expectedBoost[i], p.y, 1e-8) }
        val expectedCut = listOf(-.6, -1.05, -1.65, -2.4, -2.4)
        plot.curves[1].points.forEachIndexed { i, p -> assertEquals(expectedCut[i], p.y, 1e-8) }
        assertEquals(listOf(4.0, 4.0), plot.marks.map { it.x })
        assertTrue(result.professional.notes.any { it.contains("低 −2.4～+2.0") && it.contains("高 −0.0～+0.0") })
        assertTrue(result.professional.notes.any { it.contains("静图不知道实际输出") })
        val scenes = (0..4).map { index -> SmartAdaptVisuals.static(values + (key("intensity") to index * 1000), key("intensity")).illustration.scene }
        assertEquals(5, scenes.distinct().size)
    }

    @Test fun staticToneAndSpeakerPolicyUseLevelAndScaledPolicyLimits() {
        val values = settings("intensity" to 3.0, "strength" to .4, "device" to 2.0,
            "max_boost" to 6.0, "max_cut" to 6.0,
            "low_amount" to 1.0, "mid_amount" to 0.0, "high_amount" to 0.0)
        val curves = SmartAdaptVisuals.static(values, key("max_boost")).professional.plot!!.curves
        curves[0].points.forEach { assertEquals(SmartAdaptVisuals.filterDb(0, 1.6, it.x), it.y, 1e-9) }
        curves[1].points.forEach { assertEquals(SmartAdaptVisuals.filterDb(0, -4.8, it.x), it.y, 1e-8) }
        val side = SmartAdaptVisuals.static(values + (key("target") to 3000), key("width"))
        assertTrue(side.professional.plot!!.curves.single().points.all { it.y == 0.0 })
        val headphone = SmartAdaptVisuals.static(settings("target" to 3.0, "intensity" to 4.0,
            "width" to .2, "strength" to .5), key("width"))
        headphone.professional.plot!!.curves.single().points.forEach {
            assertEquals(SmartAdaptVisuals.sideDb(it.x, 180.0, .3), it.y, 1e-9)
        }
    }

    @Test fun liveIntensityUsesReportedCapsOnceAndKeepsActualCorrectionsSeparate() {
        val values = settings("intensity" to 4.0, "strength" to .4,
            "low_amount" to .5, "mid_amount" to .25, "high_amount" to 0.0)
        val sample = runtime().let { it.copy(meters = it.meters.toMutableList().apply {
            this[24] = 6f; this[25] = 12f; this[28] = 4f; this[29] = 6f
        }) }
        val visual = SmartAdaptVisuals.live(values, key("intensity"), sample, listOf(frame(1000, sample)))
        val curves = visual.plot!!.curves
        assertEquals(listOf(1.25, -.75, .4f.toDouble()), curves.take(3).map { it.points.single().y })
        assertEquals(1.2, curves[3].points.single().y, 1e-8)
        assertEquals(-2.4, curves[4].points.single().y, 1e-8)
        assertTrue(curves.drop(3).all { it.dashed })
        assertTrue(visual.notes.any { it.contains("极强") && it.contains("不再重复乘档位倍率") })
        assertTrue(visual.notes.any { it.contains("中 −1.2～+0.6") && it.contains("高 −0.0～+0.0") })
        val noParticipation = values + listOf("low_amount", "mid_amount", "high_amount").associate { key(it) to 0 }
        val zero = SmartAdaptVisuals.live(noParticipation, key("intensity"), sample, listOf(frame(1000, sample)))
        assertTrue(zero.plot!!.curves.takeLast(2).flatMap { it.points }.all { it.y == 0.0 })
        assertEquals(1.25, zero.plot!!.curves.first().points.single().y, 0.0) // Do not rewrite a real in-flight gain.
    }

    @Test fun intensityRangeHistoryBreaksAtMissingReportedCapsAndNeverSynthesizesThem() {
        val s = runtime()
        val bad = s.copy(meters = s.meters.toMutableList().apply { this[24] = Float.NaN })
        val history = listOf(frame(0, s), frame(1000, bad), frame(2000, s))
        val plot = SmartAdaptVisuals.live(settings(), key("intensity"), s, history).plot!!
        assertEquals(listOf(1, 1), plot.curves.filter { it.dashed && it.colorIndex == 0 }.map { it.points.size })
        assertTrue(SmartAdaptVisuals.live(settings(), key("intensity"), s, emptyList()).plot!!.curves.isEmpty())
        val missingLevel = s.copy(meters = s.meters.take(28))
        assertNull(SmartAdaptVisuals.live(settings(), key("intensity"), missingLevel, history).plot)
    }
}
