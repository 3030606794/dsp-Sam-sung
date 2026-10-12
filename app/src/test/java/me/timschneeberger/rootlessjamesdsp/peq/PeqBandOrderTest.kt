package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test

class PeqBandOrderTest {
    private val state = PeqState(listOf(
        PeqBand("a", frequency = 1000.0, gain = 35.0, q = 40.0),
        PeqBand("b", type = PeqFilterType.HIGH_PASS, frequency = 0.0, gain = -4.0, q = 2.0),
        PeqBand("c", type = PeqFilterType.NOTCH, enabled = false, frequency = 1000.0,
            gain = 8.0, q = 5.0, qualityDisplay = PeqQualityDisplay.BW),
        PeqBand("d", type = PeqFilterType.LOW_SHELF, frequency = 0.0, gain = -12.0, q = 10.0)),
        gainRange = 18.0, gainStep = 0.3, showQSlider = true, showFrequencySlider = true,
        qStep = 0.02, frequencyStep = 50.0, pinResponse = false, qRange = 30.0)

    @Test fun firstToLastAndLastToFirstUseTheRequestedFinalIndex() {
        val firstToLast = PeqBandOrder.move(state, 0, 3)
        assertEquals(listOf("b", "c", "d", "a"), firstToLast.bands.map { it.id })
        val lastToFirst = PeqBandOrder.move(state, 3, 0)
        assertEquals(listOf("d", "a", "b", "c"), lastToFirst.bands.map { it.id })
        assertEquals(state, PeqBandOrder.move(firstToLast, 3, 0))
        assertEquals(state, PeqBandOrder.move(lastToFirst, 0, 3))
    }

    @Test fun middleMovesShiftOnlyTheBandsBetweenTheTwoPositions() {
        assertEquals(listOf("a", "c", "b", "d"), PeqBandOrder.move(state, 1, 2).bands.map { it.id })
        assertEquals(listOf("a", "c", "b", "d"), PeqBandOrder.move(state, 2, 1).bands.map { it.id })
        assertEquals(listOf("b", "c", "a", "d"), PeqBandOrder.move(state, 0, 2).bands.map { it.id })
        assertEquals(listOf("a", "d", "b", "c"), PeqBandOrder.move(state, 3, 1).bands.map { it.id })
    }

    @Test fun everyMovePreservesAllSettingsParametersAndOriginalBank() {
        val before = state.copy(bands = state.bands.toList())
        for (from in state.bands.indices) for (to in state.bands.indices) {
            val result = PeqBandOrder.move(state, from, to)
            assertEquals(state.copy(bands = result.bands), result)
            assertSame(state.bands[from], result.bands[to])
            assertEquals(state.bands.filterIndexed { index, _ -> index != from },
                result.bands.filterIndexed { index, _ -> index != to })
            result.bands.forEach { band -> assertSame(state.bands.single { it.id == band.id }, band) }
            assertEquals(before, state)
        }
    }

    @Test fun repeatedFrequenciesAndUnsetBandsRemainSeparateAndAreNotSorted() {
        val result = PeqBandOrder.move(state, 3, 1)
        assertEquals(listOf("a", "d", "b", "c"), result.bands.map { it.id })
        assertEquals(listOf(1000.0, 0.0, 0.0, 1000.0), result.bands.map { it.frequency })
        assertEquals(4, result.bands.size)
        // Values already beyond the current editor ranges are preserved, not clamped by reordering.
        assertEquals(35.0, result.bands[0].gain, 0.0)
        assertEquals(40.0, result.bands[0].q, 0.0)
        assertEquals(result, PeqModel.validate(result))
    }

    @Test fun samePositionReturnsTheOriginalStateIncludingASingleBandBank() {
        for (index in state.bands.indices) assertSame(state, PeqBandOrder.move(state, index, index))
        val single = state.copy(bands = listOf(state.bands[2]))
        assertSame(single, PeqBandOrder.move(single, 0, 0))
    }

    @Test fun invalidPositionsAndInvalidBanksAreRejectedBeforeMutation() {
        val before = state.copy(bands = state.bands.toList())
        for ((from, to) in listOf(-1 to 0, 0 to -1, 4 to 0, 0 to 4, -1 to -1, 4 to 4,
            Int.MIN_VALUE to 0, 0 to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { PeqBandOrder.move(state, from, to) }
            assertEquals(before, state)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeqBandOrder.move(state.copy(bands = emptyList()), 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeqBandOrder.move(state.copy(bands = listOf(state.bands[0], state.bands[0])), 0, 0)
        }
    }

    @Test fun fullBankCanMoveAcrossBothBoundariesWithoutLosingAnyIds() {
        val full = PeqModel.defaultState(PeqModel.MAX_BANDS)
        val moved = PeqBandOrder.move(full, 0, full.bands.lastIndex)
        assertEquals(full.bands.drop(1) + full.bands.first(), moved.bands)
        assertEquals(full, PeqBandOrder.move(moved, moved.bands.lastIndex, 0))
    }
}
