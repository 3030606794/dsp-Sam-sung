package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test

class PeqQualityEditorTest {
    @Test fun presentationRoundingNeverFeedsBackIntoCanonicalQWhenSwitching() {
        for (frequency in listOf(0.0, 20.0, 1000.0, 20000.0)) {
            for (q in listOf(0.0001, 0.1, 0.5, PeqModel.DEFAULT_Q, 30.0, 100.0)) {
                val editor = PeqQualityEditor(q, frequency, PeqQualityDisplay.Q)
                repeat(40) {
                    for (mode in listOf(PeqQualityDisplay.BW, PeqQualityDisplay.Q)) {
                        val text = requireNotNull(editor.switchMode(mode, editor.renderedText, frequency))
                        assertTrue(text.toDouble().isFinite())
                        assertTrue(PeqQualityEditor.format(text.toDouble()).matches(Regex("[0-9]+\\.[0-9]{3}")))
                        assertEquals(q, requireNotNull(editor.readQ(text, frequency)), 0.0)
                    }
                }
            }
        }
    }

    @Test fun aRealBandwidthEditChangesQAndSurvivesFollowingSwitches() {
        val editor = PeqQualityEditor(2.0, 1000.0, PeqQualityDisplay.BW)
        val q = PeqResponse.qFromBandwidth(1.123, 1000.0)
        assertTrue(q < 2.0)
        assertEquals(q, requireNotNull(editor.readQ("1.123", 1000.0)), 0.0)
        requireNotNull(editor.switchMode(PeqQualityDisplay.Q, "1.123", 1000.0))
        requireNotNull(editor.switchMode(PeqQualityDisplay.BW, editor.renderedText, 1000.0))
        assertEquals(1.123, editor.renderedText.toDouble(), 1e-14)
        assertEquals(q, requireNotNull(editor.readQ(editor.renderedText, 1000.0)), 0.0)
    }

    @Test fun editingFrequencyPreservesUnroundedBandwidth() {
        val editor = PeqQualityEditor(2.456789, 1000.0, PeqQualityDisplay.BW)
        val fullBw = PeqResponse.bandwidthOctaves(2.456789, 1000.0)
        val expected = 2.456789
        assertEquals(expected, requireNotNull(editor.readQ(editor.renderedText, 12000.0)), 0.0)
        requireNotNull(editor.switchMode(PeqQualityDisplay.Q, editor.renderedText, 12000.0))
        assertEquals(expected, requireNotNull(editor.readQ(editor.renderedText, 12000.0)), 0.0)
    }

    @Test fun invalidEditsDoNotReplaceTheRetainedValue() {
        val editor = PeqQualityEditor(100.0, 20000.0, PeqQualityDisplay.Q)
        for (invalid in listOf("", "NaN", "Infinity", "30001", "-1")) {
            assertNull(editor.switchMode(PeqQualityDisplay.BW, invalid, 20000.0))
            assertEquals(PeqQualityDisplay.Q, editor.mode)
            assertEquals(100.0, requireNotNull(editor.readQ(editor.renderedText, 20000.0)), 0.0)
        }
    }

    @Test fun preciseBandwidthBoundsAreEditableAndRoundedLabelsCannotAliasOutOfRangeEdits() {
        for (frequency in listOf(0.0, 20.0, 1000.0, 20000.0)) {
            for (maximum in listOf(0.1, 30.0, 100.0)) {
                val editor = PeqQualityEditor(0.5, frequency, PeqQualityDisplay.BW)
                val minBw = PeqQualityEditor.formatInput(PeqResponse.bandwidthOctaves(maximum, frequency))
                assertEquals(maximum, requireNotNull(editor.readQ(minBw, frequency, maximum)), 1e-12)
                val qText = requireNotNull(editor.switchMode(PeqQualityDisplay.Q, minBw, frequency, maximum))
                assertEquals(maximum, requireNotNull(editor.readQ(qText, frequency, maximum)), 1e-12)
                requireNotNull(editor.switchMode(PeqQualityDisplay.BW, qText, frequency, maximum))
                assertEquals(maximum, requireNotNull(editor.readQ(editor.renderedText, frequency, maximum)), 1e-12)
            }
        }
        val editor = PeqQualityEditor(0.707, 1000.0, PeqQualityDisplay.BW)
        assertTrue(requireNotNull(editor.readQ("0.014", 1000.0)) > 100.0)
        assertTrue(requireNotNull(editor.readQ("0.014000001", 1000.0)) > 100.0)
        assertNotNull(editor.switchMode(PeqQualityDisplay.Q, "0.014", 1000.0, 100.0))
    }
}
