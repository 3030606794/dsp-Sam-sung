package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class PeqModelTest {
    @Test fun resizingRedistributesFrequencyButKeepsOtherExistingParameters() {
        val original = PeqState(listOf(PeqBand("custom", PeqFilterType.HIGH_SHELF,
            false, 1234.567, 23.75, 12.34)), gainRange = 6.0, gainStep = 0.25)
        val expanded = PeqModel.resize(original, 170)
        assertEquals(170, expanded.bands.size)
        assertEquals(original.bands.single().copy(frequency = 20.0), expanded.bands.first())
        assertEquals(170, expanded.bands.map { it.id }.toSet().size)
        assertTrue(expanded.bands.drop(1).all { it.type == PeqFilterType.PEAK && it.gain == 0.0 })
        assertEquals(original.copy(bands = listOf(original.bands.single().copy(frequency = sqrt(400000.0)))),
            PeqModel.resize(expanded, 1))
        assertSame(original, PeqModel.resize(original, 1))
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
            valid.copy(gain = -100.001), valid.copy(q = 0.099), valid.copy(q = 100.001),
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

    @Test fun everyBandCountUsesAnEvenLogarithmicFrequencyDistribution() {
        for (count in listOf(2, 10, 35, 65, 170)) {
            val state = PeqModel.defaultState(count)
            assertEquals(20.0, state.bands.first().frequency, 0.0)
            assertEquals(20000.0, state.bands.last().frequency, 0.0)
            val ratio = 1000.0.pow(1.0 / (count - 1))
            state.bands.zipWithNext().forEach { (first, second) ->
                assertEquals(ratio, second.frequency / first.frequency, 1e-12)
            }
        }
        assertEquals(sqrt(400000.0), PeqModel.defaultState(1).bands.single().frequency, 1e-12)
        assertTrue(PeqModel.defaultState(0).bands.isEmpty())
    }

    @Test fun explicitRedistributionOnlyChangesFrequencyAndSameCountDoesNothing() {
        val state = PeqState(List(170) { index -> PeqBand("custom-$index",
            PeqFilterType.entries[index % 7], index % 2 == 0, 1234.567,
            99.125, 100.0, PeqQualityDisplay.BW) }, 100.0, 1.5, true, true)
        assertSame(state, PeqModel.resize(state, 170))
        val next = PeqModel.redistribute(state)
        assertEquals(state, next.copy(bands = next.bands.map { it.copy(frequency = 1234.567) }))
        assertEquals(20.0, next.bands.first().frequency, 0.0)
        assertEquals(20000.0, next.bands.last().frequency, 0.0)
        PeqModel.validate(next.copy(gainStep = 2.0))
    }

    @Test fun oldDistributionDetectionDoesNotGuessAfterAnyManualFrequencyEdit() {
        val oldFrequencies = listOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        val old = PeqState(oldFrequencies.mapIndexed { index, frequency ->
            PeqBand("default-${index + 1}", frequency = frequency, gain = index.toDouble()) })
        assertTrue(PeqModel.isOldGeneratedDistribution(old))
        assertTrue(PeqModel.isOldGeneratedDistribution(old.copy(bands = old.bands.take(4))))
        val expanded = old.copy(bands = old.bands + PeqModel.defaultState(170).bands.drop(10))
        assertTrue(PeqModel.isOldGeneratedDistribution(expanded))
        assertFalse(PeqModel.isOldGeneratedDistribution(expanded.copy(bands = expanded.bands.mapIndexed { index, band ->
            if (index == 128) band.copy(frequency = band.frequency + 0.001) else band })))
        assertFalse(PeqModel.isOldGeneratedDistribution(old.copy(bands = old.bands.map {
            it.copy(id = "user-${it.id}") })))
        assertFalse(PeqModel.isOldGeneratedDistribution(PeqModel.redistribute(expanded)))
    }
}
