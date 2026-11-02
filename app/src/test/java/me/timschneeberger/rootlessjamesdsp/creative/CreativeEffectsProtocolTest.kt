package me.timschneeberger.rootlessjamesdsp.creative

import org.junit.Assert.*
import org.junit.Test

class CreativeEffectsProtocolTest {
    @Test fun threeIndependentNativePacketsHaveExactUnitsAndStartOff() {
        assertArrayEquals(intArrayOf(1, 0, 350, 300, 80, 18, 120, 700, 250, 300, 700, 10000),
            CreativeEffectsParameters.packet(CreativeEffectsKind.GRANULAR, emptyMap<String, Any>()))
        assertArrayEquals(intArrayOf(1, 0, 500, 0, 0, 12000, 120, 600, 0, 120, 700, 12000),
            CreativeEffectsParameters.packet(CreativeEffectsKind.FREEZE, emptyMap<String, Any>()))
        assertArrayEquals(intArrayOf(1, 0, 300, 650, 6500, 30, 1200, 500, 7000, 800, 700, 120),
            CreativeEffectsParameters.packet(CreativeEffectsKind.SHIMMER, emptyMap<String, Any>()))
        assertEquals(3, CreativeEffectsKind.values().map { it.prefsName }.toSet().size)
        assertEquals(3, CreativeEffectsKind.values().map { it.parameter }.toSet().size)
    }
    @Test fun storedValuesCannotReplayAMomentaryCaptureAndDisablingAlwaysReleases() {
        val kind = CreativeEffectsKind.FREEZE
        val stored = mapOf(kind.enableKey to true, "captureToken" to 55, "hold" to true, "fr_mix" to 750)
        val default = CreativeEffectsParameters.packet(kind, stored)
        assertEquals(0, default[3]); assertEquals(0, default[4])
        val captured = CreativeEffectsParameters.packet(kind, stored, 56, true)
        assertEquals(56, captured[3]); assertEquals(1, captured[4]); assertEquals(750, captured[2])
        val off = CreativeEffectsParameters.packet(kind, stored + (kind.enableKey to false), 56, true)
        assertEquals(0, off[1]); assertEquals(0, off[4])
        val edited = CreativeEffectsParameters.packet(kind, stored + ("fr_mix" to 333), 56, true)
        assertEquals(captured[3], edited[3]); assertEquals(captured[4], edited[4])
        assertNotEquals(captured.contentHashCode(), edited.contentHashCode())
    }
    @Test fun corruptNumericValuesAreBoundedBeforeAudioTransport() {
        val packet = CreativeEffectsParameters.packet(CreativeEffectsKind.GRANULAR,
            mapOf("gr_pitch" to -999999, "gr_feedback" to 999999, "gr_delay" to Float.NaN))
        assertEquals(-2400, packet[7]); assertEquals(850, packet[9]); assertEquals(300, packet[3])
    }
    @Test fun runtimeNeverPretendsToBeActiveWithoutEngineProgressEvidence() {
        val words = IntArray(32).apply {
            this[0] = 1; this[1] = 1; this[2] = 48000; this[3] = 512
            this[4] = 123; this[5] = -12345; this[6] = -6789; this[7] = -24000
            this[8] = 1; this[9] = 19; this[10] = 750
            for (i in 12..27) this[i] = -45000
        }
        val runtime = requireNotNull(CreativeEffectsParameters.decode(CreativeEffectsKind.FREEZE, words))
        assertEquals(-12.345f, runtime.inputPeakDb, .0001f)
        assertEquals(.75f, runtime.phase, 0f); assertEquals(19, runtime.aux)
        assertEquals(List(16) { -45f }, runtime.spectrumDb)
        assertFalse(runtime.isProcessing); assertFalse(runtime.engineEnabled)
        assertNull(CreativeEffectsParameters.decode(CreativeEffectsKind.FREEZE, words.copyOf(31)))
        words[2] = 0
        assertNull(CreativeEffectsParameters.decode(CreativeEffectsKind.FREEZE, words))
    }
}
