package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test

class PeqModelTest {
    @Test fun resizingKeepsEveryExistingIdAndParameter() {
        val original = PeqState(listOf(PeqBand("custom", PeqFilterType.HIGH_SHELF,
            false, 1234.567, 23.75, 12.34)), gainRange = 6.0, gainStep = 0.25)
        val expanded = PeqModel.resize(original, 170)
        assertEquals(170, expanded.bands.size)
        assertEquals(original.bands.single(), expanded.bands.first())
        assertEquals(170, expanded.bands.map { it.id }.toSet().size)
        assertTrue(expanded.bands.drop(1).all { it.type == PeqFilterType.PEAK && it.gain == 0.0 })
        assertEquals(original, PeqModel.resize(expanded, 1))
        assertEquals(0, PeqModel.resize(expanded, 0).bands.size)
    }

    @Test fun displayRangeAndStepDoNotModifyPreviouslySavedGains() {
        val state = PeqState(listOf(PeqBand(gain = 23.127)), gainRange = 6.0, gainStep = 1.0)
        assertEquals(23.127, PeqModel.validate(state).bands.single().gain, 0.0)
        assertEquals(23.127, PeqModel.resize(state, 170).bands.first().gain, 0.0)
    }

    @Test fun invalidAndNonFiniteParametersAreRejectedInsteadOfClipped() {
        val valid = PeqBand()
        listOf(valid.copy(frequency = Double.NaN), valid.copy(frequency = 19.999),
            valid.copy(frequency = 20000.001), valid.copy(gain = Double.POSITIVE_INFINITY),
            valid.copy(gain = -24.001), valid.copy(q = 0.099), valid.copy(q = 30.001),
            valid.copy(id = " ")).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { PeqModel.validate(PeqState(listOf(bad))) }
        }
        assertThrows(IllegalArgumentException::class.java) { PeqModel.resize(PeqModel.defaultState(), 171) }
        assertThrows(IllegalArgumentException::class.java) { PeqModel.validate(PeqState(listOf(valid, valid))) }
        assertThrows(IllegalArgumentException::class.java) {
            PeqModel.validate(PeqState(listOf(valid), gainRange = Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeqModel.validate(PeqState(listOf(valid), gainStep = 0.0))
        }
    }

    @Test fun defaultBandsAreNeutralAndValid() {
        val state = PeqModel.defaultState()
        assertEquals(10, state.bands.size)
        assertTrue(state.bands.all { it.enabled && it.gain == 0.0 && it.type == PeqFilterType.PEAK })
        PeqModel.validate(state)
        PeqModel.validate(PeqModel.defaultState(170))
    }
}
