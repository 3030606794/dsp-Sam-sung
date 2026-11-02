package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.utils.MediaOutputEvidence
import org.junit.Assert.*
import org.junit.Test

class SmartAdaptProtocolTest {
    private val kind = ExpansionEffectsKind.SMART_ADAPT
    private fun packet(extra: Map<String, Any> = emptyMap()) = ExpansionEffectsParameters.packet(kind, mapOf(kind.enableKey to true) + extra)
    private fun frozenValues(target: Int = 0) = mapOf("p0" to target, SmartAdaptState.SCHEMA_KEY to 2,
        SmartAdaptState.TARGET_KEY to target, SmartAdaptState.LOW_KEY to 1250, SmartAdaptState.MID_KEY to -800,
        SmartAdaptState.HIGH_KEY to 450, SmartAdaptState.WIDTH_KEY to 120,
        SmartAdaptState.INTENSITY_KEY to SmartAdaptIntensity.DEFAULT)
    private fun runtime(expected: IntArray, frames: Int = 4800) = ExpansionEffectsRuntime(kind, true, 48000, frames,
        ExpansionEffectsParameters.hash(kind, expected), -12f, -14f, -14f, MutableList(116) { 0f }.apply {
            this[0] = 3f; this[1] = 2000f; this[2] = .3f; this[3] = -.2f; this[4] = .1f
            this[7] = 1.25f; this[8] = -.8f; this[9] = .45f; this[10] = .12f
            this[22] = expected[126] / 1000f; this[23] = 1f; this[26] = 1f; this[27] = expected[2] / 1000f
            this[28] = expected[18] / 1000f; this[29] = SmartAdaptIntensity.level(expected[18]).multiplier.toFloat()
        }, engineEnabled = true)

    @Test fun defaultPacketIsIndependentDisabledAndUsesExactWireUnits() {
        val words = ExpansionEffectsParameters.packet(kind, emptyMap<String, Any>())
        assertEquals(196763, kind.parameter); assertEquals(132045, kind.query)
        assertArrayEquals(intArrayOf(1, 0, 0, 400, 2500, 3000, 1000, 1000, 1000, 2000000,
            1200000, 700, 2000000, 200, 180000, 1000, 0, 0, 3000), words.take(19).toIntArray())
        assertTrue(words.drop(19).all { it == 0 })
        assertEquals(listOf("p0", "p16", "p1"), ExpansionEffectsParameters.basic(kind).map { it.key })
        assertEquals(listOf("p0", "p16", "p1"), ExpansionEffectsParameters.controls(kind).take(3).map { it.key })
        assertEquals(17, ExpansionEffectsParameters.controls(kind).size)
    }

    @Test fun oldCapabilitiesNeverImplyNewProcessorAndExactDescriptorIsRequired() {
        assertEquals(false, SpeakerOptimizationProtocol.supported(kind, true, true))
        assertNull(SpeakerOptimizationProtocol.supported(kind, true, true, null))
        assertEquals(true, SpeakerOptimizationProtocol.supported(kind, false, false, true))
        assertTrue(SmartAdaptProtocol.supports(intArrayOf(2, 2, 128, 128)))
        listOf(null, intArrayOf(1), intArrayOf(1, 2, 128, 128), intArrayOf(2, 1, 128, 128), intArrayOf(2, 2, 127, 128))
            .forEach { assertFalse(SmartAdaptProtocol.supports(it)) }
    }

    @Test fun noPreferenceCanAuthorizeRouteAndRouteDoesNotInvalidateConfigurationHash() {
        val words = packet(mapOf("p126" to 2000, "route" to 2000, "route_active" to 1000))
        assertEquals(0, words[126])
        val hash = ExpansionEffectsParameters.hash(kind, words)
        MediaOutputEvidence.values().forEach { route ->
            val routed = words.copyOf().apply { this[126] = SmartAdaptProtocol.route(route) }
            assertEquals(hash, ExpansionEffectsParameters.hash(kind, routed))
        }
        assertEquals(listOf(0, 1000, 2000, 3000), MediaOutputEvidence.values().map(SmartAdaptProtocol::route))
    }

    @Test fun frozenVectorIsPreservedAtFullStrengthAndIncludedInHash() {
        val original = packet(frozenValues())
        assertArrayEquals(intArrayOf(1, 1250, -800, 450, 120, 2, 0, 3000), original.sliceArray(110..117))
        val scaled = packet(frozenValues() + mapOf("p1" to 100, "p4" to 0))
        assertArrayEquals(original.sliceArray(110..117), scaled.sliceArray(110..117))
        assertNotEquals(ExpansionEffectsParameters.hash(kind, original), ExpansionEffectsParameters.hash(kind, packet()))
    }

    @Test fun malformedOrDifferentTargetSnapshotNeverReachesNativeAsFrozen() {
        listOf(frozenValues() - SmartAdaptState.HIGH_KEY,
            frozenValues() + (SmartAdaptState.WIDTH_KEY to 601),
            frozenValues() + (SmartAdaptState.LOW_KEY to -12001),
            frozenValues() + (SmartAdaptState.SCHEMA_KEY to 3),
            frozenValues() + ("p16" to 4000),
            frozenValues() + ("p0" to 1000)).forEach { values ->
            assertNull(SmartAdaptState.read(values)); assertTrue(packet(values).slice(110..117).all { it == 0 })
        }
    }

    @Test fun snapshotUsesNativeTargetNotDisplaySmoothedGainAndRejectsStaleReadiness() {
        val words = packet(mapOf("p1" to 400, "p4" to 0))
        val live = runtime(words)
        assertEquals(SmartAdaptState.Snapshot(0, 1250, -800, 450, 120), SmartAdaptState.snapshot(live, words))
        assertNull(SmartAdaptState.snapshot(live.copy(parameterHash = live.parameterHash + 1), words))
        assertNull(SmartAdaptState.snapshot(live.copy(engineEnabled = false), words))
        listOf(0 to 2f, 23 to 0f, 26 to 0f, 22 to 1f, 27 to 1f, 28 to 2f, 29 to 1f, 7 to Float.NaN, 10 to .601f).forEach { (index, value) ->
            val bad = live.copy(meters = live.meters.toMutableList().apply { this[index] = value })
            assertNull(SmartAdaptState.snapshot(bad, words))
        }
    }

    @Test fun fiveLevelsOnlyChangeTheAppendedWireFieldAndKeepLegacyParameters() {
        val baseline = packet(mapOf("p1" to 400, "p2" to 1900, "p3" to 2300, "p8" to 1700000))
        assertEquals(listOf(1.0, 1.75, 2.75, 4.0, 6.0), SmartAdaptIntensity.levels.map { it.multiplier })
        SmartAdaptIntensity.levels.forEach { level ->
            val words = packet(mapOf("p1" to 400, "p2" to 1900, "p3" to 2300, "p8" to 1700000, "p16" to level.value))
            assertEquals(level.value, words[18]); assertArrayEquals(baseline.take(18).toIntArray(), words.take(18).toIntArray())
            assertTrue(words.drop(19).all { it == 0 })
        }
        listOf(-1, 1500, 5000).forEach { assertEquals(3000, packet(mapOf("p16" to it))[18]) }
    }

    @Test fun highLevelFreezeUsesAlreadyScaledTargetExactlyOnceAndBindsLevel() {
        val words = packet(mapOf("p16" to 4000, "p1" to 250, "p4" to 500))
        val live = runtime(words).let { it.copy(meters = it.meters.toMutableList().apply {
            this[7] = 11.5f; this[8] = -12f; this[9] = 8f; this[10] = .6f
        }) }
        val snapshot = requireNotNull(SmartAdaptState.snapshot(live, words))
        assertEquals(SmartAdaptState.Snapshot(0, 11500, -12000, 8000, 600, 4000), snapshot)
        val frozen = packet(frozenValues() + mapOf("p16" to 4000, SmartAdaptState.INTENSITY_KEY to 4000,
            SmartAdaptState.LOW_KEY to 11500, SmartAdaptState.MID_KEY to -12000,
            SmartAdaptState.HIGH_KEY to 8000, SmartAdaptState.WIDTH_KEY to 600))
        assertArrayEquals(intArrayOf(1, 11500, -12000, 8000, 600, 2, 0, 4000), frozen.sliceArray(110..117))
        assertNull(SmartAdaptState.snapshot(live.copy(meters = live.meters.toMutableList().apply { this[7] = 12.001f }), words))
    }

    @Test fun captureRequiresUniqueAdvancingSameIdentityAndSameAcceptedConfiguration() {
        val words = packet(); val a = Any(); val b = Any(); val start = runtime(words)
        val finish = start.copy(processedFrames = 9600)
        assertNotNull(SmartAdaptCapture.select(listOf(a to start), listOf(a to finish), words))
        assertNull(SmartAdaptCapture.select(listOf(a to start), listOf(a to start), words))
        assertNull(SmartAdaptCapture.select(listOf(a to start), listOf(b to finish), words))
        assertNull(SmartAdaptCapture.select(listOf(a to start), listOf(a to finish, b to finish), words))
        assertNull(SmartAdaptCapture.select(listOf(a to start, b to start), listOf(a to finish, b to finish), words))
        assertNull(SmartAdaptCapture.select(listOf(a to start), listOf(a to finish.copy(sampleRate = 44100)), words))
        assertNull(SmartAdaptCapture.select(listOf(a to start.copy(parameterHash = 4)), listOf(a to finish), words))
        assertNotNull(SmartAdaptCapture.select(listOf(a to start, b to start), listOf(a to finish, b to start), words))
    }

    @Test fun frameCounterWrapIsAcceptedButResetBackwardsIsNot() {
        val live = runtime(packet())
        assertTrue(SmartAdaptProtocol.framesAdvanced(live.copy(processedFrames = 0x7fffff00), live.copy(processedFrames = 512)))
        assertFalse(SmartAdaptProtocol.framesAdvanced(live.copy(processedFrames = 10000), live.copy(processedFrames = 512)))
    }
}
