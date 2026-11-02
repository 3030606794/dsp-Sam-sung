package me.timschneeberger.rootlessjamesdsp.peq.importer

import org.junit.Assert.*
import org.junit.Test

class GraphicEqParserTest {
    @Test fun acceptsBomCommentsWrappedPairsTabsAndPreampWithoutNormalizing() {
        val curve = GraphicEqParser.parse("\uFEFF# headphone profile\nPreamp: -2.5 dB\nGraphicEQ: 20\t-1; 200 -5; # comment\n2000 -9\n20000 -3;\n")
        assertEquals(4, curve.points.size)
        assertEquals(-2.5, curve.preampDb, 0.0)
        assertEquals(-1.0, curve.points.first().gainDb, 0.0)
        assertEquals(-3.5, curve.gainAt(20.0), 0.0)
        assertEquals(-11.5, curve.gainAt(2000.0), 0.0)
    }

    @Test fun interpolationUsesLogHzAndKeepsEndpointLevels() {
        val curve = GraphicEqParser.parse("GraphicEQ: 100 -2; 10000 -10\nPreamp: -3 dB")
        assertEquals(-9.0, curve.gainAt(1000.0), 1e-12)
        assertEquals(-5.0, curve.gainAt(1.0), 0.0)
        assertEquals(-13.0, curve.gainAt(50000.0), 0.0)
        val extreme = GraphicEqParser.parse("GraphicEQ: 1e-310 -2; 20000 -10")
        assertTrue(extreme.gainAt(1000.0).isFinite())
    }

    @Test fun sortsUnorderedPointsAndOnlyDeduplicatesIdenticalValues() {
        val curve = GraphicEqParser.parse("graphiceq: 20000 -3; 20 -6; 20 -6; 1000 -4")
        assertEquals(listOf(20.0, 1000.0, 20000.0), curve.points.map { it.frequency })
        invalid("GraphicEQ: 20 -6; 20 -5; 20000 -3")
    }

    @Test fun rejectsMalformedNumbersMissingPairsAndUnsupportedProcessing() {
        for (text in listOf(
            "20 -3; 20000 -6", "GraphicEQ: 20 -3; 20000", "GraphicEQ: 20 -3",
            "GraphicEQ: NaN -3; 20000 -6", "GraphicEQ: 20 Infinity; 20000 -6",
            "GraphicEQ: 0 -3; 20000 -6", "GraphicEQ: -20 -3; 20000 -6",
            "GraphicEQ: 20 -101; 20000 -6", "GraphicEQ: 20 -3; 1e309 -6",
            "GraphicEQ: 20 -3; 20000 -6\nFilter: ON PK Fc 100 Hz Gain 3 dB Q 1",
            "GraphicEQ: 20 -3; 20000 -6\nInclude: another.txt",
            "GraphicEQ: 20 -3; 20000 -6\nGraphicEQ: 20 -1; 20000 -2",
            "Preamp: -3\nGraphicEQ: 20 -3; 20000 -6",
            "Preamp: -3 dB\nPreamp: -2 dB\nGraphicEQ: 20 -3; 20000 -6",
            "Preamp: -5 dB\nGraphicEQ: 20 -99; 20000 -6"
        )) invalid(text)
    }

    @Test fun inputAndPointLimitsApplyBeforeLargeParsingWork() {
        invalid(" ".repeat(GraphicEqParser.MAX_INPUT_BYTES + 1))
        invalid("# 中文\n".repeat(40000))
        invalid("GraphicEQ: " + (1..GraphicEqParser.MAX_POINTS + 1).joinToString("; ") { "$it -3" })
    }

    private fun invalid(text: String) {
        try {
            GraphicEqParser.parse(text)
            fail("Expected invalid input: ${text.take(100)}")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.isNullOrBlank())
        }
    }
}
