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
            val originalState = state.copy(bands = state.bands.map { it.copy(qualityDisplay = mode) })
            val changed = PeqBulkParameters.quality(originalState, mode, 0.5)
            assertEquals(originalState.copy(bands = changed.bands), changed)
            originalState.bands.forEachIndexed { index, original ->
                val q = if (mode == PeqQualityDisplay.Q) 0.5 else PeqResponse.qFromBandwidth(0.5, original.frequency)
                assertEquals(original.copy(q = q), changed.bands[index])
            }
        }
    }

    @Test fun bulkQualityOnlyUpdatesCardsAlreadyDisplayingTheRequestedMode() {
        for (mode in PeqQualityDisplay.entries) {
            val changed = PeqBulkParameters.quality(mixedState, mode, 0.5)
            mixedState.bands.forEachIndexed { index, original ->
                if (original.qualityDisplay == mode) {
                    val q = if (mode == PeqQualityDisplay.Q) 0.5 else PeqResponse.qFromBandwidth(0.5, original.frequency)
                    assertEquals(original.copy(q = q), changed.bands[index])
                } else {
                    assertSame(original, changed.bands[index])
                }
            }
            assertEquals(mixedState.copy(bands = changed.bands), changed)
        }
    }

    @Test fun failingOneBandwidthRejectsTheWholeBankAndRangeCoversEveryBand() {
        val originalState = state.copy(bands = state.bands.map { it.copy(qualityDisplay = PeqQualityDisplay.BW) })
        val range = PeqBulkParameters.bandwidthRange(originalState)
        assertEquals(PeqResponse.bandwidthOctaves(30.0, 0.0), range.start, 0.0)
        assertEquals(PeqResponse.bandwidthOctaves(0.1, 20000.0), range.endInclusive, 0.0)
        for (bound in listOf(range.start, range.endInclusive)) {
            val result = PeqBulkParameters.quality(originalState, PeqQualityDisplay.BW, bound)
            assertTrue(result.bands.all { it.q in 0.1..30.0 })
        }
        val before = originalState.copy(bands = originalState.bands.toList())
        for (value in listOf(range.start * 0.99, range.endInclusive * 1.01, Double.NaN, 0.0)) {
            assertThrows(IllegalArgumentException::class.java) { PeqBulkParameters.quality(originalState, PeqQualityDisplay.BW, value) }
            assertEquals(before, originalState)
        }
        assertThrows(IllegalArgumentException::class.java) { PeqBulkParameters.quality(state, PeqQualityDisplay.Q, 30.001) }
    }

    @Test fun tooSmallQRangeCanHaveNoCommonBandwidthAndTypeOnlyChangesType() {
        assertTrue(PeqBulkParameters.bandwidthRange(state.copy(qRange = 0.1)).isEmpty())
        for (type in PeqFilterType.entries) {
            assertEquals(state.copy(bands = state.bands.map { it.copy(type = type) }), PeqBulkParameters.filterType(state, type))
        }
    }

    @Test fun draftCanUpdateBothQualityModesTogetherWithoutConvertingEitherMode() {
        val changed = PeqBulkParameters.applyDraft(mixedState, allIds(mixedState), allowAll,
            q = 1.5, bw = 0.5, type = PeqFilterType.LOW_SHELF)
        val expected = mixedState.copy(bands = mixedState.bands.map { original ->
            original.copy(q = if (original.qualityDisplay == PeqQualityDisplay.Q) 1.5
                else PeqResponse.qFromBandwidth(0.5, original.frequency), type = PeqFilterType.LOW_SHELF)
        })
        assertEquals(expected, changed)
        assertNotEquals(changed.bands[1].q, changed.bands[2].q, 1e-6)
    }

    @Test fun draftRespectsSelectedAndExcludedScopesForEveryParameter() {
        val selected = setOf("q-low", "bw-mid")
        for (scope in PeqSelectionScope.entries) {
            val ids = PeqSelection.targets(mixedState, selected, scope)
            val changed = PeqBulkParameters.applyDraft(mixedState, ids, allowAll,
                q = 1.5, bw = 0.5, type = PeqFilterType.BAND_PASS)
            mixedState.bands.forEachIndexed { index, original ->
                if (original.id in ids) {
                    val q = if (original.qualityDisplay == PeqQualityDisplay.Q) 1.5
                        else PeqResponse.qFromBandwidth(0.5, original.frequency)
                    assertEquals(original.copy(q = q, type = PeqFilterType.BAND_PASS), changed.bands[index])
                } else {
                    assertSame(original, changed.bands[index])
                }
            }
        }
    }

    @Test fun draftProtectsMainLocksAndQualityLocksButOtherFieldLocksDoNotBlockQualityOrType() {
        val protectedState = state.copy(bands = listOf(
            PeqBand("q-main", q = 2.0),
            PeqBand("bw-main", q = 3.0, qualityDisplay = PeqQualityDisplay.BW),
            PeqBand("q-quality", q = 4.0),
            PeqBand("bw-quality", q = 5.0, qualityDisplay = PeqQualityDisplay.BW),
            PeqBand("q-gain", q = 6.0, gain = 7.0),
            PeqBand("bw-frequency", q = 7.0, frequency = 5000.0, qualityDisplay = PeqQualityDisplay.BW)))
        val mainLocks = setOf("q-main", "bw-main")
        val fieldLocks = setOf("q-quality" to PeqEditField.QUALITY, "bw-quality" to PeqEditField.QUALITY,
            "q-gain" to PeqEditField.GAIN, "bw-frequency" to PeqEditField.FREQUENCY)
        val canEdit: (String, PeqEditField?) -> Boolean = { id, field ->
            id !in mainLocks && (field == null || (id to field) !in fieldLocks)
        }
        val changed = PeqBulkParameters.applyDraft(protectedState, allIds(protectedState), canEdit,
            q = 1.5, bw = 0.5, type = PeqFilterType.HIGH_PASS)
        protectedState.bands.forEachIndexed { index, original ->
            val expected = when (original.id) {
                "q-main", "bw-main" -> original
                "q-quality", "bw-quality" -> original.copy(type = PeqFilterType.HIGH_PASS)
                "q-gain" -> original.copy(q = 1.5, type = PeqFilterType.HIGH_PASS)
                else -> original.copy(q = PeqResponse.qFromBandwidth(0.5, original.frequency), type = PeqFilterType.HIGH_PASS)
            }
            assertEquals(expected, changed.bands[index])
        }
        assertEquals(protectedState.copy(bands = changed.bands), changed)
    }

    @Test fun typeOnlyDraftProtectsMainLocksAcrossAllFilterTypesAndPreservesQuality() {
        val everyType = state.copy(bands = PeqFilterType.entries.mapIndexed { index, type ->
            PeqBand(id = type.name, type = type, enabled = index % 2 == 0, gain = index.toDouble(),
                q = index + 1.0, qualityDisplay = if (index % 2 == 0) PeqQualityDisplay.Q else PeqQualityDisplay.BW)
        })
        val ids = allIds(everyType) - PeqFilterType.LOW_SHELF.name
        for (target in PeqFilterType.entries) {
            val changed = PeqBulkParameters.applyDraft(everyType, ids,
                canEdit = { id, _ -> id != PeqFilterType.NOTCH.name }, type = target)
            everyType.bands.forEachIndexed { index, original ->
                val expected = if (original.id in ids && original.type != PeqFilterType.NOTCH)
                    original.copy(type = target) else original
                assertEquals(expected, changed.bands[index])
            }
        }
    }

    @Test fun draftWithNoMatchingOrEditableTargetsDoesNothing() {
        assertSame(mixedState, PeqBulkParameters.applyDraft(mixedState, emptySet(), allowAll,
            q = 1.5, bw = 0.5, type = PeqFilterType.HIGH_PASS))
        assertSame(mixedState, PeqBulkParameters.applyDraft(mixedState, allIds(mixedState), { _, _ -> false },
            q = 1.5, bw = 0.5, type = PeqFilterType.HIGH_PASS))
        assertSame(mixedState, PeqBulkParameters.applyDraft(mixedState, setOf("q-low"), allowAll, bw = 0.5))
        assertSame(mixedState, PeqBulkParameters.applyDraft(mixedState, setOf("bw-mid", "bw-high"), allowAll, q = 1.5))
        assertSame(mixedState, PeqBulkParameters.applyDraft(mixedState, allIds(mixedState), allowAll))
        assertEquals(state, PeqBulkParameters.quality(state, PeqQualityDisplay.BW, 0.5))
    }

    @Test fun irrelevantModesAndProtectedFieldsDoNotValidateUnappliedDraftValues() {
        val changed = PeqBulkParameters.applyDraft(mixedState, setOf("q-low"), allowAll, q = 1.5, bw = Double.NaN)
        assertEquals(mixedState.copy(bands = mixedState.bands.map {
            if (it.id == "q-low") it.copy(q = 1.5) else it
        }), changed)
        assertSame(mixedState, PeqBulkParameters.applyDraft(mixedState, allIds(mixedState), { _, _ -> false },
            q = Double.NaN, bw = Double.POSITIVE_INFINITY))
    }

    @Test fun bandwidthRangeUsesOnlyTheCallersEligibleBandwidthTargets() {
        val ids = setOf("bw-mid")
        val eligible = PeqSelection.snapshot(mixedState, ids)
        val range = PeqBulkParameters.bandwidthRange(eligible)
        assertEquals(PeqResponse.bandwidthOctaves(mixedState.qRange, 1000.0), range.start, 0.0)
        assertEquals(PeqResponse.bandwidthOctaves(PeqModel.MIN_Q, 1000.0), range.endInclusive, 0.0)
        // This valid width for the selected card would be invalid for an unselected high-frequency card.
        assertTrue(range.endInclusive > PeqBulkParameters.bandwidthRange(mixedState).endInclusive)
        val changed = PeqBulkParameters.applyDraft(mixedState, ids, allowAll, bw = range.endInclusive)
        assertEquals(PeqModel.MIN_Q, changed.bands[1].q, 1e-12)
        mixedState.bands.forEachIndexed { index, original ->
            if (original.id !in ids) assertSame(original, changed.bands[index])
        }
    }

    @Test fun invalidQRejectsTheWholeDraftWithoutApplyingBandwidthOrType() {
        val before = mixedState.copy(bands = mixedState.bands.toList())
        for (q in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0, 0.099, 30.001)) {
            var committed = mixedState
            assertThrows(IllegalArgumentException::class.java) {
                committed = PeqBulkParameters.applyDraft(mixedState, allIds(mixedState), allowAll,
                    q = q, bw = 0.5, type = PeqFilterType.HIGH_PASS)
            }
            assertSame(mixedState, committed)
            assertEquals(before, mixedState)
        }
    }

    @Test fun invalidBandwidthRejectsTheWholeDraftWithoutCommittingEarlierQualityChanges() {
        val before = mixedState.copy(bands = mixedState.bands.toList())
        val bandwidthTargets = mixedState.copy(bands = mixedState.bands.filter { it.qualityDisplay == PeqQualityDisplay.BW })
        val range = PeqBulkParameters.bandwidthRange(bandwidthTargets)
        val invalidForHighBand = range.endInclusive * 1.01
        assertTrue(PeqResponse.qFromBandwidth(invalidForHighBand, 1000.0) in PeqModel.MIN_Q..mixedState.qRange)
        assertTrue(PeqResponse.qFromBandwidth(invalidForHighBand, 10000.0) < PeqModel.MIN_Q)
        for (bw in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -0.5, range.start * 0.99, invalidForHighBand)) {
            var committed = mixedState
            assertThrows(IllegalArgumentException::class.java) {
                committed = PeqBulkParameters.applyDraft(mixedState, allIds(mixedState), allowAll,
                    q = 1.5, bw = bw, type = PeqFilterType.HIGH_PASS)
            }
            assertSame(mixedState, committed)
            assertEquals(before, mixedState)
        }
    }

    private val mixedState = state.copy(bands = listOf(
        PeqBand("q-low", type = PeqFilterType.PEAK, frequency = 100.0, gain = 3.0, q = 2.0),
        PeqBand("bw-mid", type = PeqFilterType.HIGH_SHELF, frequency = 1000.0, gain = -4.0, q = 5.0,
            qualityDisplay = PeqQualityDisplay.BW),
        PeqBand("bw-high", type = PeqFilterType.NOTCH, enabled = false, frequency = 10000.0, gain = 7.0, q = 3.0,
            qualityDisplay = PeqQualityDisplay.BW),
        PeqBand("q-high", type = PeqFilterType.HIGH_PASS, frequency = 20000.0, gain = -12.0, q = 5.0)))

    private val allowAll: (String, PeqEditField?) -> Boolean = { _, _ -> true }
    private fun allIds(bank: PeqState) = bank.bands.mapTo(hashSetOf()) { it.id }
}
