package me.timschneeberger.rootlessjamesdsp.liveprog

import org.junit.Assert.*
import org.junit.Test

class LiveprogPrecisionTest {
    @Test fun thousandthsNegativeStepsAndFractionalOffsetsRemainExact() {
        val property = EelNumberRangeProperty("gain", "Gain", 0f, 0.001f, -1f, 1f, 0.001f)
        assertEquals(3, property.precision)
        assertEquals("0.001", property.valueAsString())
        assertEquals(-0.001f, property.normalize(-0.0006f), 0f)
        assertEquals(0.001f, property.normalize(0.0014f), 0f)
        val offset = EelNumberRangeProperty("gain", "Gain", 0.5f, 0.5f, 0.5f, 4.5f, 1f)
        assertFalse(offset.handleAsInt())
        assertEquals("0.5", offset.valueAsString())
        assertEquals(1.5f, offset.normalize(1.4f), 0f)
        val oddRange = EelNumberRangeProperty("delay", "Delay", 300f, 300f, 1f, 4000f, 20f)
        assertEquals(3981f, oddRange.normalize(4000f), 0f)
    }

    @Test fun numericLiteralsSupportScientificAndLeadingDecimalWithoutSuffixOrCommentMatches() {
        val source = "// gain=9;\n/* gain=8; */\nother_gain=7; text=\"gain=6;\";\ngain=1e-3;\n"
        assertEquals(0.001f, EelNumberRangeProperty.findVariable("gain", source)!!, 0f)
        assertEquals(source.replace("gain=1e-3", "gain=-.002"), EelNumberRangeProperty.replaceVariable("gain", "-.002", source))
        val parameter = EelNumberRangeProperty.parse("gain:0<-1,1,1e-3>Gain", source) as EelNumberRangeProperty<*>
        assertEquals(0.001f, parameter.step.toFloat(), 0f)
        assertEquals(3, parameter.precision)
    }

    @Test fun invalidNumbersNeverBecomeParametersOrProtocolPackets() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { step ->
            assertThrows(NumberFormatException::class.java) { EelNumberRangeProperty("x", "X", 0f, 0f, 0f, 1f, step) }
        }
        assertThrows(NumberFormatException::class.java) { EelNumberRangeProperty("x", "X", 0f, Float.NaN, 0f, 1f, 0.001f) }
        assertNull(LiveprogProtocol.parameter("bad.name", 1.0))
        assertNull(LiveprogProtocol.parameter("x", Double.NaN))
        assertNull(LiveprogProtocol.parameter("x".repeat(64), 1.0))
    }
}
