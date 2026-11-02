package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test

class PeqSelectionTest {
    private val state = PeqState(listOf(
        PeqBand("a", frequency = 0.0, gain = 3.0, q = 0.5),
        PeqBand("b", type = PeqFilterType.HIGH_PASS, frequency = 20.0, gain = -4.0, q = 2.0),
        PeqBand("c", type = PeqFilterType.NOTCH, enabled = false, frequency = 1000.0,
            gain = 8.0, q = 5.0, qualityDisplay = PeqQualityDisplay.BW),
        PeqBand("d", type = PeqFilterType.LOW_SHELF, frequency = 20000.0, gain = -12.0, q = 10.0)),
        gainRange = 50.0, gainStep = 0.3, showQSlider = true, showFrequencySlider = true,
        qStep = 0.02, frequencyStep = 50.0, pinResponse = false, qRange = 30.0)

    @Test fun selectedAndExcludedAreDisjointAndFollowBankOrder() {
        val selected = linkedSetOf("d", "b", "deleted")
        val included = PeqSelection.targets(state, selected, PeqSelectionScope.SELECTED)
        val excluded = PeqSelection.targets(state, selected, PeqSelectionScope.EXCLUDED)
        assertEquals(listOf("b", "d"), included.toList())
        assertEquals(listOf("a", "c"), excluded.toList())
        assertTrue(included.intersect(excluded).isEmpty())
        assertEquals(state.bands.map { it.id }.toSet(), included + excluded)
    }

    @Test fun noSelectionAndAllSelectionHaveExplicitOppositeScopes() {
        val all = state.bands.map { it.id }.toSet()
        assertTrue(PeqSelection.targets(state, emptySet(), PeqSelectionScope.SELECTED).isEmpty())
        assertEquals(all, PeqSelection.targets(state, emptySet(), PeqSelectionScope.EXCLUDED))
        assertEquals(all, PeqSelection.targets(state, all, PeqSelectionScope.SELECTED))
        assertTrue(PeqSelection.targets(state, all, PeqSelectionScope.EXCLUDED).isEmpty())
        for (scope in PeqSelectionScope.entries) {
            assertTrue(PeqSelection.targets(state.copy(bands = emptyList()), all, scope).isEmpty())
        }
    }

    @Test fun pruneRetainsStableIdsAfterReorderDeletionAndInsertion() {
        val selected = setOf("b", "d", "deleted")
        val newBand = PeqModel.blankBand()
        val reordered = state.copy(bands = listOf(newBand, state.bands[3], state.bands[0]))
        val retained = PeqSelection.prune(reordered, selected)
        assertEquals(listOf("d"), retained.toList())
        assertFalse(newBand.id in retained)
        assertEquals(setOf("b", "d", "deleted"), selected)
    }

    @Test fun snapshotIsAnIndependentSubsetWithAllSettingsPreserved() {
        val snapshot = PeqSelection.snapshot(state, linkedSetOf("d", "b"))
        assertEquals(state.copy(bands = listOf(state.bands[1], state.bands[3])), snapshot)
        assertEquals(snapshot, PeqModel.validate(snapshot))
        val loadedAndEdited = PeqBulkParameters.filterType(snapshot, PeqFilterType.BAND_PASS)
        assertEquals(listOf("b", "d"), loadedAndEdited.bands.map { it.id })
        assertEquals(PeqFilterType.HIGH_PASS, state.bands[1].type)
        assertEquals(PeqFilterType.LOW_SHELF, state.bands[3].type)
        assertEquals(4, state.bands.size)
    }

    @Test fun emptyOrStaleTargetActionsFailBeforeCallingTheTransform() {
        for (ids in listOf(emptySet(), setOf("missing"), setOf("a", "missing"))) {
            assertThrows(IllegalArgumentException::class.java) { PeqSelection.snapshot(state, ids) }
            var called = false
            assertThrows(IllegalArgumentException::class.java) {
                PeqSelection.update(state, ids) { called = true; it }
            }
            assertFalse(called)
            assertThrows(IllegalArgumentException::class.java) {
                PeqSelection.reset(state, ids, PeqResetTarget.GAIN)
            }
            assertThrows(IllegalArgumentException::class.java) { PeqSelection.redistribute(state, ids) }
            assertThrows(IllegalArgumentException::class.java) { PeqSelection.delete(state, ids) }
        }
    }

    @Test fun updateMergesByIdAndPreservesBankOrderSettingsAndUntouchedBands() {
        val ids = setOf("b", "d")
        val result = PeqSelection.update(state, ids) { subset ->
            assertEquals(listOf("b", "d"), subset.bands.map { it.id })
            subset.copy(gainRange = 1.0, qRange = 0.1, showQSlider = false,
                bands = subset.bands.reversed().map { it.copy(gain = 1.5) })
        }
        assertEquals(state.copy(bands = state.bands.map {
            if (it.id in ids) it.copy(gain = 1.5) else it
        }), result)
        assertSame(state.bands[0], result.bands[0])
        assertSame(state.bands[2], result.bands[2])
    }

    @Test fun transformsCannotInsertRemoveDuplicateOrReplaceTargetIds() {
        val ids = setOf("b", "d")
        val invalidBands = listOf(
            listOf(state.bands[1]),
            listOf(state.bands[1], state.bands[3], state.bands[0]),
            listOf(state.bands[1], state.bands[1]),
            listOf(state.bands[1], state.bands[3].copy(id = "new")),
            listOf(state.bands[1], state.bands[0]))
        invalidBands.forEach { bands ->
            assertThrows(IllegalArgumentException::class.java) {
                PeqSelection.update(state, ids) { it.copy(bands = bands) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeqSelection.update(state, setOf("a")) { subset ->
                subset.copy(bands = subset.bands.map { it.copy(q = Double.NaN) })
            }
        }
    }

    @Test fun selectingAllMatchesExistingWholeBankOperations() {
        val all = state.bands.map { it.id }.toSet()
        for (target in PeqResetTarget.entries) {
            assertEquals(PeqModel.reset(state, target), PeqSelection.reset(state, all, target))
        }
        assertEquals(PeqModel.redistribute(state), PeqSelection.redistribute(state, all))
        assertEquals(state, PeqSelection.snapshot(state, all))
    }

    @Test fun resetsAndRedistributionChangeOnlyTheRequestedSubsetParameters() {
        val ids = setOf("b", "d")
        for (target in PeqResetTarget.entries) {
            val result = PeqSelection.reset(state, ids, target)
            val expectedSubset = PeqModel.reset(PeqSelection.snapshot(state, ids), target)
            assertEquals(state.copy(bands = listOf(state.bands[0], expectedSubset.bands[0],
                state.bands[2], expectedSubset.bands[1])), result)
        }
        val result = PeqSelection.redistribute(state, ids)
        assertEquals(20.0, result.bands[1].frequency, 0.0)
        assertEquals(20000.0, result.bands[3].frequency, 0.0)
        assertEquals(state.bands[0], result.bands[0])
        assertEquals(state.bands[2], result.bands[2])
        val single = PeqSelection.redistribute(state, setOf("a"))
        assertEquals(PeqModel.defaultState(1).bands.single().frequency, single.bands[0].frequency, 0.0)
        assertEquals(state.bands.drop(1), single.bands.drop(1))
    }

    @Test fun qResetRespectsRangeWithoutClampingAnyOtherBand() {
        val narrow = state.copy(qRange = 0.2)
        val result = PeqSelection.reset(narrow, setOf("c"), PeqResetTarget.Q)
        assertEquals(narrow.copy(bands = narrow.bands.map {
            if (it.id == "c") it.copy(q = 0.2) else it
        }), result)
    }

    @Test fun deletingSelectedOrExcludedPreservesRemainingOrderParametersAndSettings() {
        val selected = setOf("b", "d")
        for (scope in PeqSelectionScope.entries) {
            val ids = PeqSelection.targets(state, selected, scope)
            val result = PeqSelection.delete(state, ids)
            assertEquals(state.copy(bands = state.bands.filterNot { it.id in ids }), result)
            result.bands.forEach { band -> assertSame(state.bands.first { it.id == band.id }, band) }
            assertEquals(2, result.bands.size)
            assertEquals(result, PeqModel.validate(result))
        }
        val remaining = PeqSelection.delete(state, selected)
        assertFalse(remaining.bands.single { it.id == "c" }.enabled)
        assertEquals(PeqQualityDisplay.BW, remaining.bands.single { it.id == "c" }.qualityDisplay)
        assertTrue(PeqSelection.prune(remaining, selected).isEmpty())
    }

    @Test fun deletingAllOrTheFinalBandIsRejectedWithoutChangingTheBank() {
        val selected = state.bands.map { it.id }.toSet()
        val before = state.copy(bands = state.bands.toList())
        assertThrows(IllegalArgumentException::class.java) { PeqSelection.delete(state, selected) }
        assertEquals(before, state)
        val remaining = PeqSelection.delete(state, selected - "c")
        assertEquals(listOf(state.bands[2]), remaining.bands)
        assertThrows(IllegalArgumentException::class.java) { PeqSelection.delete(remaining, setOf("c")) }
        assertEquals(state.copy(bands = listOf(state.bands[2])), remaining)
        val added = PeqModel.resize(remaining, 3, PeqResizeEdge.BACK, redistribute = false)
        assertEquals(3, added.bands.size)
        assertEquals(state.bands[2], added.bands.first())
        val pruned = PeqSelection.prune(added, selected)
        assertEquals(setOf("c"), pruned)
        assertTrue(added.bands.drop(1).none { it.id in pruned })
        assertEquals(remaining.copy(bands = added.bands), added)
        assertTrue(added.bands.drop(1).all { it.frequency == 0.0 && it.gain == 0.0 && it.q == 0.5 })
    }

    @Test fun selectedQualityUpdatesUseTheExistingQRangeAndRejectOutOfRangeValues() {
        val before = state.copy(bands = state.bands.toList())
        for (scope in PeqSelectionScope.entries) {
            val ids = PeqSelection.targets(state, setOf("b", "c"), scope)
            val result = PeqSelection.update(state, ids) {
                PeqBulkParameters.quality(it, PeqQualityDisplay.Q, state.qRange)
            }
            assertEquals(state.copy(bands = state.bands.map {
                if (it.id in ids && it.qualityDisplay == PeqQualityDisplay.Q) it.copy(q = state.qRange) else it
            }), result)
            // The selected BW card and every card outside this scope remain completely unchanged.
            state.bands.forEachIndexed { index, band ->
                if (band.id !in ids || band.qualityDisplay != PeqQualityDisplay.Q)
                    assertEquals(band, result.bands[index])
            }
            var committed = state
            assertThrows(IllegalArgumentException::class.java) {
                committed = PeqSelection.update(state, ids) {
                    PeqBulkParameters.quality(it, PeqQualityDisplay.Q, state.qRange + 0.001)
                }
            }
            assertSame(state, committed)
            assertEquals(before, state)
        }
    }

    @Test fun selectedBandwidthRangeUsesOnlyTargetFrequenciesAndFailureIsAtomic() {
        // BW 2 is valid for low-frequency targets but below minimum Q at 20 kHz.
        val mixed = state.copy(bands = state.bands.map {
            it.copy(qualityDisplay = if (it.id == "c") PeqQualityDisplay.Q else PeqQualityDisplay.BW)
        })
        val ids = PeqSelection.targets(mixed, setOf("d"), PeqSelectionScope.EXCLUDED)
        val bandwidthIds = mixed.bands.filter { it.id in ids && it.qualityDisplay == PeqQualityDisplay.BW }
            .mapTo(hashSetOf()) { it.id }
        assertEquals(setOf("a", "b"), bandwidthIds)
        val range = PeqBulkParameters.bandwidthRange(PeqSelection.snapshot(mixed, bandwidthIds))
        assertTrue(2.0 in range)
        assertFalse(2.0 in PeqBulkParameters.bandwidthRange(mixed))
        val result = PeqSelection.update(mixed, ids) {
            PeqBulkParameters.quality(it, PeqQualityDisplay.BW, 2.0)
        }
        for (index in 0..1) {
            assertEquals(PeqResponse.qFromBandwidth(2.0, mixed.bands[index].frequency), result.bands[index].q, 0.0)
            assertEquals(PeqQualityDisplay.BW, result.bands[index].qualityDisplay)
        }
        assertEquals(mixed.copy(bands = mixed.bands.map {
            if (it.id in bandwidthIds) it.copy(q = PeqResponse.qFromBandwidth(2.0, it.frequency)) else it
        }), result)
        // The Q card is inside the scope, while the high-frequency BW card is outside it.
        assertEquals(mixed.bands.drop(2), result.bands.drop(2))
        val before = mixed.copy(bands = mixed.bands.toList())
        var committed = mixed
        assertThrows(IllegalArgumentException::class.java) {
            committed = PeqSelection.update(mixed, setOf("b", "c", "d")) {
                PeqBulkParameters.quality(it, PeqQualityDisplay.BW, 2.0)
            }
        }
        assertSame(mixed, committed)
        assertEquals(before, mixed)
    }
}
