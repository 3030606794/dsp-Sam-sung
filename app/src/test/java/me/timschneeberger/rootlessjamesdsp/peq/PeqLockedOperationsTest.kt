package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/** Bulk reset must obey the same protection as the card, including mixed selections. */
class PeqLockedOperationsTest {
    private fun operations(
        masterLocked: Set<String> = emptySet(),
        fieldLocked: Map<String, Set<PeqEditField>> = emptyMap()
    ) = PeqLockedOperations { id, field ->
        id !in masterLocked && (field == null || field !in fieldLocked[id].orEmpty())
    }

    private fun band(id: String, frequency: Double = 1234.5) = PeqBand(
        id = id, type = PeqFilterType.HIGH_PASS, enabled = false, frequency = frequency,
        gain = 7.25, q = 4.56789, qualityDisplay = PeqQualityDisplay.BW
    )

    @Test fun masterLockProtectsTheWholeBandForEveryResetTarget() {
        val locked = band("locked")
        val editable = band("editable", 3456.7)
        val unselected = band("unselected", 9876.5)
        val original = PeqState(listOf(locked, editable, unselected), gainStep = 1.25, pinResponse = false)
        val operation = operations(masterLocked = setOf(locked.id))

        PeqResetTarget.entries.forEach { target ->
            val result = operation.reset(original, setOf(locked.id, editable.id), target)
            assertEquals("Master lock must cover $target", locked, result.state.bands[0])
            assertEquals("Reset must not touch unselected cards", unselected, result.state.bands[2])
            assertEquals("Exactly one selected band is editable for $target", 1, result.editableCount)
            assertEquals("Exactly one selected band is protected for $target", 1, result.protectedCount)
            assertFalse("Reset must not enable a disabled band", result.state.bands[1].enabled)
            assertEquals(original.copy(bands = result.state.bands), result.state)
        }
    }

    @Test fun eachFieldLockProtectsItsOwnResetWithoutBlockingOtherFields() {
        val original = PeqState(listOf(band("gain"), band("quality", 2345.6), band("frequency", 3456.7)))
        val ids = original.bands.mapTo(linkedSetOf()) { it.id }
        val operation = operations(fieldLocked = mapOf(
            "gain" to setOf(PeqEditField.GAIN),
            "quality" to setOf(PeqEditField.QUALITY),
            "frequency" to setOf(PeqEditField.FREQUENCY)
        ))

        val gain = operation.reset(original, ids, PeqResetTarget.GAIN)
        assertEquals(original.copy(bands = listOf(original.bands[0],
            original.bands[1].copy(gain = 0.0), original.bands[2].copy(gain = 0.0))), gain.state)
        val quality = operation.reset(original, ids, PeqResetTarget.Q)
        assertEquals(original.copy(bands = listOf(original.bands[0].copy(q = PeqModel.DEFAULT_Q),
            original.bands[1], original.bands[2].copy(q = PeqModel.DEFAULT_Q))), quality.state)
        val frequency = operation.reset(original, ids, PeqResetTarget.FREQUENCY)
        assertEquals(original.copy(bands = listOf(original.bands[0].copy(frequency = 20.0),
            original.bands[1].copy(frequency = 20000.0), original.bands[2])), frequency.state)
        listOf(gain, quality, frequency).forEach {
            assertEquals(2, it.editableCount)
            assertEquals(1, it.protectedCount)
        }
        // Per-slider locks do not prevent the independent filter-type reset.
        val types = operation.reset(original, ids, PeqResetTarget.FILTER_TYPE)
        assertEquals(original.copy(bands = original.bands.map { it.copy(type = PeqFilterType.PEAK) }), types.state)
        assertEquals(3, types.editableCount)
        assertEquals(0, types.protectedCount)
    }

    @Test fun allResetChangesOnlyAllowedFieldsAndCountsPartiallyProtectedCardsInBothTotals() {
        val original = PeqState(listOf(band("master"), band("gain"), band("quality"), band("frequency"),
            band("free"), band("unselected")), qRange = 0.25, gainRange = 12.0, gainStep = 1.5,
            qStep = 0.25, frequencyStep = 123.0, showQSlider = false, pinResponse = false)
        val operation = operations(masterLocked = setOf("master"), fieldLocked = mapOf(
            "gain" to setOf(PeqEditField.GAIN), "quality" to setOf(PeqEditField.QUALITY),
            "frequency" to setOf(PeqEditField.FREQUENCY)
        ))
        val selected = setOf("master", "gain", "quality", "frequency", "free", "no-longer-present")

        val result = operation.reset(original, selected, PeqResetTarget.ALL)

        assertEquals(4, result.editableCount)
        assertEquals(4, result.protectedCount)
        assertEquals(sqrt(20.0 * 20000.0), result.state.bands[2].frequency, 1e-9)
        assertEquals(original.copy(bands = listOf(
            original.bands[0],
            original.bands[1].copy(type = PeqFilterType.PEAK, q = 0.25, frequency = 20.0),
            original.bands[2].copy(type = PeqFilterType.PEAK, gain = 0.0, frequency = result.state.bands[2].frequency),
            original.bands[3].copy(type = PeqFilterType.PEAK, gain = 0.0, q = 0.25),
            original.bands[4].copy(type = PeqFilterType.PEAK, gain = 0.0, q = 0.25, frequency = 20000.0),
            original.bands[5]
        )), result.state)
    }

    @Test fun frequencyDistributionUsesOnlyEditableSelectedCardsInBankOrder() {
        val original = PeqState(listOf(band("last-in-selection", 4567.0), band("master", 3456.0),
            band("unselected", 2345.0), band("frequency-locked", 1234.0), band("first-in-selection", 5678.0)))
        val operation = operations(masterLocked = setOf("master"),
            fieldLocked = mapOf("frequency-locked" to setOf(PeqEditField.FREQUENCY)))
        val selected = linkedSetOf("first-in-selection", "frequency-locked", "master", "missing", "last-in-selection")

        val result = operation.reset(original, selected, PeqResetTarget.FREQUENCY)

        assertEquals(original.copy(bands = original.bands.map {
            when (it.id) {
                "last-in-selection" -> it.copy(frequency = 20.0)
                "first-in-selection" -> it.copy(frequency = 20000.0)
                else -> it
            }
        }), result.state)
        assertEquals(2, result.editableCount)
        assertEquals(2, result.protectedCount)
        assertEquals(linkedSetOf("last-in-selection", "first-in-selection"),
            operation.eligible(original, selected, PeqEditField.FREQUENCY))
    }

    @Test fun aSingleEditableFrequencyGetsTheCenterWithoutChangingItsLockedBandwidthQuality() {
        val original = PeqState(listOf(band("protected", 7654.0), band("only-editable", 0.0)))
        val operation = operations(fieldLocked = mapOf("protected" to setOf(PeqEditField.FREQUENCY),
            "only-editable" to setOf(PeqEditField.QUALITY)))

        val result = operation.reset(original, setOf("protected", "only-editable"), PeqResetTarget.FREQUENCY)

        assertEquals(sqrt(20.0 * 20000.0), result.state.bands[1].frequency, 1e-9)
        assertEquals(original.copy(bands = listOf(original.bands[0],
            original.bands[1].copy(frequency = result.state.bands[1].frequency))), result.state)
        assertEquals(1, result.editableCount)
        assertEquals(1, result.protectedCount)
        assertEquals(PeqQualityDisplay.BW, result.state.bands[1].qualityDisplay)
        assertEquals(original.bands[1].q, result.state.bands[1].q, 0.0)
    }

    @Test fun noEditableFrequencyAndEmptyOrStaleSelectionsAreSafeNoOps() {
        val original = PeqState(listOf(band("main"), band("frequency")))
        val operation = operations(masterLocked = setOf("main"),
            fieldLocked = mapOf("frequency" to setOf(PeqEditField.FREQUENCY)))
        val protected = operation.reset(original, setOf("main", "frequency"), PeqResetTarget.FREQUENCY)
        assertEquals(original, protected.state)
        assertEquals(0, protected.editableCount)
        assertEquals(2, protected.protectedCount)
        listOf(emptySet(), setOf("removed")).forEach { selection ->
            val result = operation.reset(original, selection, PeqResetTarget.ALL)
            assertEquals(original, result.state)
            assertEquals(0, result.editableCount)
            assertEquals(0, result.protectedCount)
        }
    }
}
