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
        qStep = 45.678, frequencyStep = 987.654, pinResponse = false)

    @Test fun assignedFrequencyConflictsUseDriverPrecisionAndAllowAnyNumberOfUnsetBands() {
        val first = PeqBand("first", enabled = false, frequency = 1000.0001)
        val state = PeqState(listOf(first, PeqBand("second", frequency = 1000.001),
            PeqModel.blankBand(), PeqModel.blankBand()))
        assertSame(state, PeqModel.validate(state))
        assertSame(first, PeqModel.frequencyConflict(state, 1000.0004))
        assertNull(PeqModel.frequencyConflict(state, 1000.0004, excludingBandId = first.id))
        assertEquals("second", PeqModel.frequencyConflict(state, 1000.0005)?.id)
        assertNull(PeqModel.frequencyConflict(state, 1000.0015))
        assertNull(PeqModel.frequencyConflict(state, 0.0))
        assertNull(PeqModel.frequencyConflict(state, -0.0))
        assertEquals(1000001L, PeqModel.frequencyKey(1000.0005))
        assertThrows(IllegalArgumentException::class.java) {
            PeqModel.validate(state.copy(bands = state.bands + PeqBand("duplicate", frequency = 1000.0004)))
        }
        assertThrows(IllegalArgumentException::class.java) { PeqModel.frequencyConflict(state, Double.NaN) }
    }

    @Test fun duplicateMigrationPreservesFirstFrequencyAndAllOtherBandAndEditorFields() {
        val first = customBank().bands.first().copy(frequency = 777.1231, enabled = false)
        val second = customBank().bands[1].copy(frequency = 777.1234)
        val third = customBank().bands[2].copy(frequency = 777.1232)
        val zero = PeqModel.blankBand()
        val distinct = customBank().bands[3].copy(frequency = 777.1236)
        val state = customBank().copy(bands = listOf(first, second, zero, third, distinct, PeqModel.blankBand()))
        val normalized = PeqModel.normalizeDuplicateFrequencies(state)
        assertEquals(state.copy(bands = listOf(first, second.copy(frequency = 0.0), zero,
            third.copy(frequency = 0.0), distinct, state.bands.last())), normalized)
        assertSame(first, normalized.bands.first())
        assertSame(zero, normalized.bands[2])
        assertSame(distinct, normalized.bands[4])
        assertSame(normalized, PeqModel.normalizeDuplicateFrequencies(normalized))
        assertEquals(777.1234, state.bands[1].frequency, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            PeqModel.normalizeDuplicateFrequencies(state.copy(bands = state.bands + second.copy(q = Double.NaN)))
        }
    }

    @Test fun everyDistributionAndResetCountProducesUniqueWireFrequencies() {
        for (count in 0..PeqModel.MAX_BANDS) {
            val blank = PeqState(List(count) { PeqModel.blankBand() })
            PeqModel.validate(blank)
            val distributed = PeqModel.redistribute(blank)
            assertEquals(count, distributed.bands.map { PeqModel.frequencyKey(it.frequency) }.toSet().size)
            PeqModel.validate(distributed)
            assertEquals(distributed, PeqModel.reset(blank, PeqResetTarget.FREQUENCY))
            PeqModel.validate(PeqModel.defaultState(count))
        }
    }

    @Test fun addingAtEitherEndKeepsExistingBandObjectsAndEveryEditorSetting() {
        val original = customBank()
        PeqResizeEdge.entries.forEach { edge ->
            val expanded = PeqModel.resize(original, 170, edge, redistribute = false)
            val retained = if (edge == PeqResizeEdge.FRONT) expanded.bands.takeLast(original.bands.size)
                else expanded.bands.take(original.bands.size)
            assertEquals(original.bands, retained)
            original.bands.zip(retained).forEach { (before, after) -> assertSame(before, after) }
            assertEquals(original, expanded.copy(bands = original.bands))
            val added = if (edge == PeqResizeEdge.FRONT) expanded.bands.dropLast(original.bands.size)
                else expanded.bands.drop(original.bands.size)
            assertEquals(170 - original.bands.size, added.size)
            added.forEach { band ->
                assertEquals(PeqBand(id = band.id, frequency = 0.0, gain = 0.0, q = 0.5), band)
                assertFalse(original.bands.any { it.id == band.id })
            }
            assertEquals(170, expanded.bands.map { it.id }.toSet().size)
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
            for (count in listOf(0, 1, 3, original.bands.size, 170)) {
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
            val maximum = PeqModel.resize(empty, 170, edge, redistribute = false)
            assertTrue(maximum.bands.all { it.frequency == 0.0 && it.gain == 0.0 && it.q == 0.5 })
            assertSame(maximum, PeqModel.resize(maximum, 170, edge, redistribute = false))
            assertEquals(empty, PeqModel.resize(maximum, 0, edge, redistribute = false))
            listOf(-1, 171).forEach { count ->
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
            PeqFilterType.entries[index % 7], index % 2 == 0, 1234.567 + index,
            99.125, 100.0, PeqQualityDisplay.BW) }, 100.0, 1.5, true, true)
        assertSame(state, PeqModel.resize(state, 170))
        val next = PeqModel.redistribute(state)
        assertEquals(state, next.copy(bands = next.bands.mapIndexed { index, band ->
            band.copy(frequency = 1234.567 + index) }))
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
