package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test

class PeqProtocolTest {
    @Test fun unsetFrequencyIsKeptInSavedParametersButCannotActivateADriverFilter() {
        for (type in PeqFilterType.entries) {
            val pending = PeqBand("pending", type = type, frequency = 0.0, gain = 12.0, q = 0.5)
            val state = PeqState(listOf(pending))
            assertSame(state, PeqModel.validate(state))
            val payload = PeqProtocol.payload(state, true)
            assertArrayEquals(intArrayOf(type.wireId, 0, 20000, 12000, 500), payload.drop(3).toIntArray())
            assertEquals(0.0, state.bands.single().frequency, 0.0)
            assertTrue(state.bands.single().enabled)
            val assigned = PeqProtocol.payload(state.copy(bands = listOf(pending.copy(frequency = 20.0))), true)
            assertEquals(1, assigned[4])
        }
    }

    @Test fun packetPreservesEveryFilterAndFixedPointUnit() {
        val types = PeqFilterType.entries
        val bands = List(130) { index -> PeqBand("band-$index", types[index % types.size],
            index % 2 == 0, 1234.567, -12.345, 1.234) }
        val payload = PeqProtocol.payload(PeqState(bands), true)
        assertEquals(1250, PeqProtocol.PARAMETER)
        assertEquals(653, payload.size)
        assertArrayEquals(intArrayOf(1, 1, 130), payload.take(3).toIntArray())
        repeat(130) { index ->
            assertArrayEquals(intArrayOf(index % 7, if (index % 2 == 0) 1 else 0,
                1234567, -12345, 1234), payload.copyOfRange(3 + 5 * index, 8 + 5 * index))
        }
        val disabled = PeqProtocol.payload(PeqState(bands), false)
        assertEquals(0, disabled[1])
        assertArrayEquals(payload.copyOfRange(2, payload.size), disabled.copyOfRange(2, disabled.size))
    }

    @Test fun limitsRoundWithoutOverflowAndInvalidInputDoesNotBecomeAPacket() {
        val extremes = PeqState(listOf(
            PeqBand("minimum", frequency = 20.0, gain = -100.0, q = 0.1),
            PeqBand("maximum", frequency = 20000.0, gain = 100.0, q = 100.0)
        ))
        assertArrayEquals(intArrayOf(1, 1, 2, 0, 1, 20000, -100000, 100,
            0, 1, 20000000, 100000, 100000), PeqProtocol.payload(extremes, true))
        assertArrayEquals(intArrayOf(1, 0, 0), PeqProtocol.payload(PeqState(emptyList()), false))
        assertThrows(IllegalArgumentException::class.java) {
            PeqProtocol.payload(PeqState(listOf(PeqBand(q = Double.NaN))), true)
        }
    }

    @Test fun displayModeAndSliderVisibilityCannotChangeSound() {
        val state = PeqModel.defaultState().let { it.copy(bands = it.bands.map { band ->
            band.copy(gain = 100.0, q = 100.0) }) }
        val displayed = state.copy(showFrequencySlider = true, showQSlider = true, gainRange = 100.0,
            gainStep = 2.0, bands = state.bands.map { it.copy(qualityDisplay = PeqQualityDisplay.BW) })
        assertArrayEquals(PeqProtocol.payload(state, true), PeqProtocol.payload(displayed, true))
    }
}
