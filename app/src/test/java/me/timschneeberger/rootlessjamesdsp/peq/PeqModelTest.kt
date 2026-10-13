package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class PeqModelTest {
    private fun customBank() = PeqState(PeqFilterType.entries.mapIndexed { index, type ->
        PeqBand("custom-$index", type, index % 2 == 0, 345.678 + index * 1234.567,
            -88.123 + index * 24.567, 0.1 + index * 11.123456,
            if (index % 2 == 0) PeqQualityDisplay.BW else PeqQualityDisplay.Q)
    }, gainRange = 3.25, gainStep = 1.23, showQSlider = true, showFrequencySlider = true,
        qStep = 45.678, frequencyStep = 987.654, pinResponse = false, qRange = 2.345)

    @Test fun qRangeValidatesItsOwnLimitsWithoutClippingSavedBandsOrForbiddingRepeatedFrequencies() {
        val bands = listOf(PeqBand("first", q = 100.0), PeqBand("second", q = 23.456789))
        val state = PeqState(bands, qRange = 0.1)
        assertSame(state, PeqModel.validate(state))
        assertEquals(bands, state.bands)
        assertEquals(30.0, PeqState(emptyList()).qRange, 0.0)
        assertSame(state, PeqModel.resize(state, 2, PeqResizeEdge.BACK, false))
        listOf(0.0, 0.099, 100.001, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { PeqModel.validate(state.copy(qRange = invalid)) }
        }
        PeqModel.validate(state.copy(qRange = 100.0))
        assertThrows(IllegalArgumentException::class.java) {
            PeqModel.validate(state.copy(bands = listOf(bands.first().copy(q = 100.001))))
        }
    }

    @Test fun filterTypeResetOnlyChangesTypesAndAllResetAlsoHonorsTheConfiguredQRange() {
        val original = customBank().copy(qRange = 0.25)
        val typesReset = PeqModel.reset(original, PeqResetTarget.FILTER_TYPE)
        assertEquals(original.copy(bands = original.bands.map { it.copy(type = PeqFilterType.PEAK) }), typesReset)
        val qReset = PeqModel.reset(original, PeqResetTarget.Q)
        assertEquals(original.copy(bands = original.bands.map { it.copy(q = 0.25) }), qReset)
        val allReset = PeqModel.reset(original, PeqResetTarget.ALL)
        val frequencies = PeqModel.defaultState(original.bands.size).bands.map { it.frequency }
        assertEquals(original.copy(bands = original.bands.mapIndexed { index, band ->
            band.copy(type = PeqFilterType.PEAK, frequency = frequencies[index], gain = 0.0, q = 0.25)
        }), allReset)
        assertEquals(original.bands.map { it.id }, allReset.bands.map { it.id })
        assertEquals(original.bands.map { it.enabled }, allReset.bands.map { it.enabled })
        assertEquals(original.bands.map { it.qualityDisplay }, allReset.bands.map { it.qualityDisplay })
        assertEquals(PeqModel.DEFAULT_Q, PeqModel.reset(original.copy(qRange = 100.0),
            PeqResetTarget.Q).bands.first().q, 0.0)
    }

    @Test fun addingAtEitherEndKeepsExistingBandObjectsAndEveryEditorSetting() {
        val original = customBank()
        PeqResizeEdge.entries.forEach { edge ->
            val expanded = PeqModel.resize(original, 130, edge, redistribute = false)
            val retained = if (edge == PeqResizeEdge.FRONT) expanded.bands.takeLast(original.bands.size)
                else expanded.bands.take(original.bands.size)
            assertEquals(original.bands, retained)
            original.bands.zip(retained).forEach { (before, after) -> assertSame(before, after) }
            assertEquals(original, expanded.copy(bands = original.bands))
            val added = if (edge == PeqResizeEdge.FRONT) expanded.bands.dropLast(original.bands.size)
                else expanded.bands.drop(original.bands.size)
            assertEquals(130 - original.bands.size, added.size)
            added.forEach { band ->
                assertEquals(PeqBand(id = band.id, frequency = 0.0, gain = 0.0, q = 0.5), band)
                assertFalse(original.bands.any { it.id == band.id })
            }
            assertEquals(130, expanded.bands.map { it.id }.toSet().size)
            assertEquals(expanded, PeqModel.validate(expanded))
        }
    }

    @Test fun deletingFromEitherEndPreservesSurvivingBandIdentityOrderAndSettings() {
        val original = customBank()
        PeqResizeEdge.entries.forEach { edge ->
            val reduced = PeqModel.resize(original, 3, edge, redistribute = false)
            val expected = if (edge == PeqResizeEdge.FRONT) original.bands.takeLast(3)
                else original.bands.take(3)
            assertEquals(original.copy(bands = expected), reduced)
            expected.zip(reduced.bands).forEach { (before, after) -> assertSame(before, after) }
            assertEquals(original.copy(bands = emptyList()),
                PeqModel.resize(original, 0, edge, redistribute = false))
        }
        assertEquals(7, original.bands.size)
        assertEquals(345.678, original.bands.first().frequency, 0.0)
    }

    @Test fun explicitRedistributionAfterFrontOrBackResizeChangesOnlyFrequencies() {
        val original = customBank()
        PeqResizeEdge.entries.forEach { edge ->
            for (count in listOf(0, 1, 3, original.bands.size, 130)) {
                val result = PeqModel.resize(original, count, edge, redistribute = true)
                assertEquals(original, result.copy(bands = original.bands))
                assertEquals(PeqModel.defaultState(count).bands.map { it.frequency }, result.bands.map { it.frequency })
                val retainedBefore = if (edge == PeqResizeEdge.FRONT) original.bands.takeLast(count)
                    else original.bands.take(count)
                val retainedAfter = if (edge == PeqResizeEdge.FRONT) result.bands.takeLast(retainedBefore.size)
                    else result.bands.take(retainedBefore.size)
                retainedBefore.zip(retainedAfter).forEach { (before, after) ->
                    assertEquals(before, after.copy(frequency = before.frequency))
                }
                result.bands.filter { band -> original.bands.none { it.id == band.id } }.forEach { band ->
                    assertEquals(PeqBand(id = band.id, frequency = band.frequency, q = 0.5), band)
                }
                assertEquals(count, result.bands.map { it.id }.toSet().size)
                PeqModel.validate(result)
            }
        }
    }

    @Test fun unchangedCountAndZeroToMaximumBoundariesHonorTheFrequencyPolicy() {
        val original = customBank()
        val empty = original.copy(bands = emptyList())
        PeqResizeEdge.entries.forEach { edge ->
            assertSame(original, PeqModel.resize(original, original.bands.size, edge, redistribute = false))
            assertEquals(PeqModel.redistribute(original),
                PeqModel.resize(original, original.bands.size, edge, redistribute = true))
            assertSame(empty, PeqModel.resize(empty, 0, edge, redistribute = false))
            assertEquals(empty, PeqModel.resize(empty, 0, edge, redistribute = true))
            val maximum = PeqModel.resize(empty, 130, edge, redistribute = false)
            assertTrue(maximum.bands.all { it.frequency == 0.0 && it.gain == 0.0 && it.q == 0.5 })
            assertSame(maximum, PeqModel.resize(maximum, 130, edge, redistribute = false))
            assertEquals(empty, PeqModel.resize(maximum, 0, edge, redistribute = false))
            listOf(-1, 131).forEach { count ->
                listOf(false, true).forEach { redistribute ->
                    assertThrows(IllegalArgumentException::class.java) {
                        PeqModel.resize(original, count, edge, redistribute)
                    }
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                PeqModel.resize(original.copy(bands = original.bands + original.bands.first()),
                    0, edge, redistribute = false)
            }
        }
    }

    @Test fun resizingRedistributesFrequencyButKeepsOtherExistingParameters() {
        val original = PeqState(listOf(PeqBand("custom", PeqFilterType.HIGH_SHELF,
            false, 1234.567, 23.75, 12.34)), gainRange = 6.0, gainStep = 0.25)
        val expanded = PeqModel.resize(original, 130)
        assertEquals(130, expanded.bands.size)
        assertEquals(original.bands.single().copy(frequency = 20.0), expanded.bands.first())
        assertEquals(130, expanded.bands.map { it.id }.toSet().size)
        assertTrue(expanded.bands.drop(1).all { it.type == PeqFilterType.PEAK && it.gain == 0.0 })
        assertEquals(original.copy(bands = listOf(original.bands.single().copy(frequency = sqrt(400000.0)))),
            PeqModel.resize(expanded, 1))
        assertSame(original, PeqModel.resize(original, 1))
        assertEquals(0, PeqModel.resize(expanded, 0).bands.size)
    }

    @Test fun displayRangeAndStepDoNotModifyPreviouslySavedGains() {
        val state = PeqState(listOf(PeqBand(gain = 23.127)), gainRange = 6.0, gainStep = 1.0)
        assertEquals(23.127, PeqModel.validate(state).bands.single().gain, 0.0)
        assertEquals(23.127, PeqModel.resize(state, 130).bands.first().gain, 0.0)
    }

    @Test fun invalidAndNonFiniteParametersAreRejectedInsteadOfClipped() {
        val valid = PeqBand()
        listOf(valid.copy(frequency = Double.NaN), valid.copy(frequency = 19.999),
            valid.copy(frequency = 20000.001), valid.copy(gain = Double.POSITIVE_INFINITY),
            valid.copy(gain = -100.001), valid.copy(q = 0.099), valid.copy(q = 100.001),
            valid.copy(id = " ")).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { PeqModel.validate(PeqState(listOf(bad))) }
        }
        assertThrows(IllegalArgumentException::class.java) { PeqModel.resize(PeqModel.defaultState(), 131) }
        assertThrows(IllegalArgumentException::class.java) { PeqModel.validate(PeqState(listOf(valid, valid))) }
        assertThrows(IllegalArgumentException::class.java) {
            PeqModel.validate(PeqState(listOf(valid), gainRange = Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeqModel.validate(PeqState(listOf(valid), gainStep = 0.0))
        }
    }

    @Test fun freshDefaultsUseFourNeutralBandsAndTheRequestedEditorSettings() {
        val state = PeqModel.defaultState()
        assertEquals(4, state.bands.size)
        assertEquals(0.5, state.gainStep, 0.0)
        assertEquals(18.0, state.gainRange, 0.0)
        assertEquals(0.5, state.qStep, 0.0)
        assertEquals(30.0, state.qRange, 0.0)
        assertEquals(1.0, state.frequencyStep, 0.0)
        assertTrue(state.showQSlider)
        assertTrue(state.showFrequencySlider)
        assertTrue(state.bands.all { it.enabled && it.gain == 0.0 && it.type == PeqFilterType.PEAK })
        PeqModel.validate(state)
        PeqModel.validate(PeqModel.defaultState(130))
    }

    @Test fun everyBandCountUsesAnEvenLogarithmicFrequencyDistribution() {
        for (count in listOf(2, 10, 35, 65, 130)) {
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
        val state = PeqState(List(130) { index -> PeqBand("custom-$index",
            PeqFilterType.entries[index % 7], index % 2 == 0, 1234.567,
            99.125, 100.0, PeqQualityDisplay.BW) }, 100.0, 1.5, true, true)
        assertSame(state, PeqModel.resize(state, 130))
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
        val expanded = old.copy(bands = old.bands + PeqModel.defaultState(130).bands.drop(10))
        assertTrue(PeqModel.isOldGeneratedDistribution(expanded))
        assertFalse(PeqModel.isOldGeneratedDistribution(expanded.copy(bands = expanded.bands.mapIndexed { index, band ->
            if (index == 128) band.copy(frequency = band.frequency + 0.001) else band })))
        assertFalse(PeqModel.isOldGeneratedDistribution(old.copy(bands = old.bands.map {
            it.copy(id = "user-${it.id}") })))
        assertFalse(PeqModel.isOldGeneratedDistribution(PeqModel.redistribute(expanded)))
    }
}
