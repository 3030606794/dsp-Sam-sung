package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test

class PeqBulkParametersTest {
    private val state = PeqState(listOf(
        PeqBand("blank", frequency = 0.0, q = 0.5),
        PeqBand("low", type = PeqFilterType.HIGH_PASS, frequency = 20.0, gain = 3.0, q = 2.0),
        PeqBand("high", type = PeqFilterType.NOTCH, enabled = false, frequency = 20000.0, gain = -12.0, q = 5.0)),
        qRange = 30.0, gainRange = 50.0, showQSlider = true, showFrequencySlider = true)

    @Test fun bulkQualityPreservesAllOtherFieldsAndUsesEachBandsFrequency() {
        for (mode in PeqQualityDisplay.entries) {
            val changed = PeqBulkParameters.quality(state, mode, 0.5)
            assertEquals(state.copy(bands = changed.bands), changed)
            state.bands.forEachIndexed { index, original ->
                val q = if (mode == PeqQualityDisplay.Q) 0.5 else PeqResponse.qFromBandwidth(0.5, original.frequency)
                assertEquals(original.copy(q = q, qualityDisplay = mode), changed.bands[index])
            }
        }
    }

    @Test fun failingOneBandwidthRejectsTheWholeBankAndRangeCoversEveryBand() {
        val range = PeqBulkParameters.bandwidthRange(state)
        assertEquals(PeqResponse.bandwidthOctaves(30.0, 0.0), range.start, 0.0)
        assertEquals(PeqResponse.bandwidthOctaves(0.1, 20000.0), range.endInclusive, 0.0)
        for (bound in listOf(range.start, range.endInclusive)) {
            val result = PeqBulkParameters.quality(state, PeqQualityDisplay.BW, bound)
            assertTrue(result.bands.all { it.q in 0.1..30.0 })
        }
        val before = state.copy(bands = state.bands.toList())
        for (value in listOf(range.start * 0.99, range.endInclusive * 1.01, Double.NaN, 0.0)) {
            assertThrows(IllegalArgumentException::class.java) { PeqBulkParameters.quality(state, PeqQualityDisplay.BW, value) }
            assertEquals(before, state)
        }
        assertThrows(IllegalArgumentException::class.java) { PeqBulkParameters.quality(state, PeqQualityDisplay.Q, 30.001) }
    }

    @Test fun tooSmallQRangeCanHaveNoCommonBandwidthAndTypeOnlyChangesType() {
        assertTrue(PeqBulkParameters.bandwidthRange(state.copy(qRange = 0.1)).isEmpty())
        for (type in PeqFilterType.entries) {
            assertEquals(state.copy(bands = state.bands.map { it.copy(type = type) }), PeqBulkParameters.filterType(state, type))
        }
    }
}
