package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.utils.MediaOutputEvidence
import org.junit.Assert.*
import org.junit.Test

class SpeakerOptimizationProtocolTest {
    private val kind = ExpansionEffectsKind.SPEAKER_OPTIMIZATION

    @Test fun classicDefaultsMatchTheNativeContractWithoutAuthorizingAnyRoute() {
        val expected = intArrayOf(1,0,1000,80000,1000,13500000,1000,420000,3880,1000,500,0,0,0)
        val packet = ExpansionEffectsParameters.packet(kind, emptyMap<String, Any>())
        assertArrayEquals(expected, packet.take(expected.size).toIntArray())
        assertTrue(packet.drop(expected.size).all { it == 0 })
        assertEquals(12, ExpansionEffectsParameters.allControls(kind).size)
        assertEquals(11, ExpansionEffectsParameters.controls(kind).size)
        assertEquals(listOf("p0", "p1", "p7"), ExpansionEffectsParameters.basic(kind).map { it.key })
    }

    @Test fun oldDriverRetainsAllTwentyOneEffectsWhileOnlyTheNewOneIsUnsupported() {
        val supported = ExpansionEffectsKind.values().filter { SpeakerOptimizationProtocol.supported(it, true, false) == true }
        assertEquals((0..20).toList(), supported.map { it.id })
        assertEquals((196740..196760).toSet(), supported.map { it.parameter }.toSet())
        assertEquals((132021..132041).toSet(), supported.map { it.query }.toSet())
        assertFalse(SpeakerOptimizationProtocol.supported(kind, true, false)!!)
        assertTrue(SpeakerOptimizationProtocol.supported(kind, false, true)!!)
        assertNull(SpeakerOptimizationProtocol.supported(kind, true, null))
        assertEquals(196762, kind.parameter); assertEquals(132043, kind.query)
        assertEquals(132044, SpeakerOptimizationProtocol.CAPABILITY_QUERY)
        assertTrue(ExpansionEffectsKind.values().none { it.parameter == 196761 || it.query == 132042 })
    }

    @Test fun unknownMultipleAndNonSpeakerRoutesBypassUntilUserExplicitlyChoosesAllOutputs() {
        val speakerType = 2
        assertEquals(MediaOutputEvidence.UNKNOWN, MediaOutputEvidence.fromOutputTypes(emptyList(), speakerType))
        assertEquals(MediaOutputEvidence.SINGLE_SPEAKER, MediaOutputEvidence.fromOutputTypes(listOf(2), speakerType))
        assertEquals(MediaOutputEvidence.SINGLE_OTHER, MediaOutputEvidence.fromOutputTypes(listOf(22), speakerType))
        assertEquals(MediaOutputEvidence.MULTIPLE, MediaOutputEvidence.fromOutputTypes(listOf(2, 2), speakerType))
        assertEquals(MediaOutputEvidence.MULTIPLE, MediaOutputEvidence.fromOutputTypes(listOf(2, 22), speakerType))
        MediaOutputEvidence.values().forEach { evidence ->
            assertEquals(evidence == MediaOutputEvidence.SINGLE_SPEAKER, SpeakerOptimizationProtocol.routeActive(0, evidence))
            assertTrue(SpeakerOptimizationProtocol.routeActive(1000, evidence))
            if (evidence != MediaOutputEvidence.SINGLE_SPEAKER) assertTrue(SpeakerOptimizationProtocol.bypassReason(0, evidence)!!.contains("旁路"))
        }
    }

    @Test fun storedRouteCannotOverrideLiveEvidenceAndOnlyRouteIsExcludedFromTheHash() {
        val packet = ExpansionEffectsParameters.packet(kind, mapOf(kind.enableKey to true, "p11" to 1000))
        assertEquals(1, packet[1]); assertEquals(0, packet[13])
        val routed = packet.copyOf().apply { this[13] = 1000 }
        assertEquals(ExpansionEffectsParameters.hash(kind, packet), ExpansionEffectsParameters.hash(kind, routed))
        for (index in 1..12) {
            val changed = packet.copyOf().apply { this[index]++ }
            assertNotEquals("User field $index must be confirmed", ExpansionEffectsParameters.hash(kind, packet), ExpansionEffectsParameters.hash(kind, changed))
        }
    }

    @Test fun preciseAdjustmentsRemainDistinctAndOutOfRangeValuesAreBounded() {
        val packet = ExpansionEffectsParameters.packet(kind, mapOf("p2" to 1010, "p4" to 990, "p6" to 3890, "p9" to -100, "p10" to 500))
        assertEquals(1010, packet[4]); assertEquals(990, packet[6]); assertEquals(3890, packet[8])
        assertEquals(-100, packet[11]); assertEquals(0, packet[12])
        val bounded = ExpansionEffectsParameters.packet(kind, mapOf("p1" to Int.MAX_VALUE, "p7" to Int.MAX_VALUE, "p9" to Int.MIN_VALUE))
        assertEquals(1000000, bounded[3]); assertEquals(2000, bounded[9]); assertEquals(-24000, bounded[11])
        assertEquals((0..9).map { "p$it" }.toSet(), SpeakerOptimizationProtocol.classicValues().keys)
    }
}
