package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test

class PeqProtocolTest {
    @Test fun packetPreservesEveryFilterAndFixedPointUnit() {
        val types = PeqFilterType.entries
        val bands = List(170) { index -> PeqBand("band-$index", types[index % types.size],
            index % 2 == 0, 1234.567, -12.345, 1.234) }
        val payload = PeqProtocol.payload(PeqState(bands), true)
        assertEquals(1250, PeqProtocol.PARAMETER)
        assertEquals(853, payload.size)
        assertArrayEquals(intArrayOf(1, 1, 170), payload.take(3).toIntArray())
        repeat(170) { index ->
            assertArrayEquals(intArrayOf(index % 7, if (index % 2 == 0) 1 else 0,
                1234567, -12345, 1234), payload.copyOfRange(3 + 5 * index, 8 + 5 * index))
        }
        val disabled = PeqProtocol.payload(PeqState(bands), false)
        assertEquals(0, disabled[1])
        assertArrayEquals(payload.copyOfRange(2, payload.size), disabled.copyOfRange(2, disabled.size))
    }

    @Test fun limitsRoundWithoutOverflowAndInvalidInputDoesNotBecomeAPacket() {
        val extremes = PeqState(listOf(
            PeqBand("minimum", frequency = 20.0, gain = -24.0, q = 0.1),
            PeqBand("maximum", frequency = 20000.0, gain = 24.0, q = 30.0)
        ))
        assertArrayEquals(intArrayOf(1, 1, 2, 0, 1, 20000, -24000, 100,
            0, 1, 20000000, 24000, 30000), PeqProtocol.payload(extremes, true))
        assertArrayEquals(intArrayOf(1, 0, 0), PeqProtocol.payload(PeqState(emptyList()), false))
        assertThrows(IllegalArgumentException::class.java) {
            PeqProtocol.payload(PeqState(listOf(PeqBand(q = Double.NaN))), true)
        }
    }
}
