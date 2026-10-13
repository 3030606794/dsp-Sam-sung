package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PeqResetMenuAdapterTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val locks get() = PeqBandLockStore(app)
    private val lockPrefs get() = app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE)
    private val context get() = ContextThemeWrapper(app, R.style.Theme_RootlessJamesDSP)
    private var bank = PeqState(listOf(PeqBand("q-first"), PeqBand("bw", qualityDisplay = PeqQualityDisplay.BW),
        PeqBand("q-last"), PeqBand("outside", qualityDisplay = PeqQualityDisplay.BW)))
    private val selected = setOf("q-first", "bw", "q-last")
    private val labels = listOf("reset", "band", "gain", "Q", "BW", "frequency", "type", "other action")
    private val kinds = mapOf(1 to PeqBulkLockKind.BAND, 2 to PeqBulkLockKind.GAIN,
        3 to PeqBulkLockKind.Q, 4 to PeqBulkLockKind.BW, 5 to PeqBulkLockKind.FREQUENCY,
        6 to PeqBulkLockKind.FILTER_TYPE)
    private var changes = 0
    private fun adapter(ids: Set<String> = selected) = PeqResetMenuAdapter(context, labels, kinds, ids,
        { bank }, locks) { changes++ }
    private fun row(adapter: PeqResetMenuAdapter, index: Int): View = adapter.getView(index, null, FrameLayout(context))

    @Before fun clear() {
        lockPrefs.edit().clear().commit()
        bank.bands.forEach { locks.setBandLocked(it.id, false) }
        changes = 0
    }

    @Test fun originalLabelsIndicesAndPlainRowsArePreserved() {
        val adapter = adapter()
        assertEquals(labels.size, adapter.count)
        labels.forEachIndexed { index, label ->
            assertEquals(label, adapter.getItem(index))
            assertEquals(index.toLong(), adapter.getItemId(index))
        }
        assertNull(adapter.counts(0))
        assertNull(adapter.counts(7))
        assertNull(row(adapter, 0).findViewById<View>(R.id.peq_bulk_lock_button))
        assertNull(row(adapter, 7).findViewById<View>(R.id.peq_bulk_unlock_button))
    }

    @Test fun qAndBwButtonsCountAndChangeOnlyTheirCurrentModeInsideTheScope() {
        locks.setFieldLocked("q-first", PeqEditField.QUALITY, true)
        locks.setFieldLocked("outside", PeqEditField.QUALITY, true)
        val before = bank
        val adapter = adapter()
        assertEquals(PeqResetMenuAdapter.Counts(1, 1), adapter.counts(3))
        assertEquals(PeqResetMenuAdapter.Counts(1, 0), adapter.counts(4))
        row(adapter, 3).findViewById<View>(R.id.peq_bulk_lock_button).performClick()
        assertEquals(PeqResetMenuAdapter.Counts(0, 2), adapter.counts(3))
        val lockedRow = row(adapter, 3)
        assertEquals(app.getString(R.string.peq_bulk_lock_counts, 0, 2),
            lockedRow.findViewById<TextView>(R.id.peq_bulk_lock_counts).text.toString())
        assertFalse(lockedRow.findViewById<View>(R.id.peq_bulk_lock_button).isEnabled)
        assertFalse(locks.isFieldLocked("bw", PeqEditField.QUALITY))
        assertTrue(locks.isFieldLocked("outside", PeqEditField.QUALITY))
        lockedRow.findViewById<View>(R.id.peq_bulk_unlock_button).performClick()
        assertEquals(PeqResetMenuAdapter.Counts(2, 0), adapter.counts(3))
        row(adapter, 4).findViewById<View>(R.id.peq_bulk_lock_button).performClick()
        assertTrue(locks.isFieldLocked("bw", PeqEditField.QUALITY))
        assertFalse(locks.isFieldLocked("q-first", PeqEditField.QUALITY))
        assertFalse(locks.isFieldLocked("q-last", PeqEditField.QUALITY))
        assertTrue(locks.isFieldLocked("outside", PeqEditField.QUALITY))
        assertEquals(3, changes)
        assertEquals(before, bank)
    }

    @Test fun everyLockButtonTouchesOnlyItsOwnFieldAndNeverReleasesOtherLocks() {
        val beforeBank = bank
        for ((position, kind) in kinds) {
            lockPrefs.edit().clear().commit()
            bank.bands.forEach { band ->
                locks.setBandLocked(band.id, true)
                PeqEditField.entries.forEach { locks.setFieldLocked(band.id, it, true) }
            }
            val before = lockPrefs.all.toMap()
            val adapter = adapter()
            row(adapter, position).findViewById<View>(R.id.peq_bulk_unlock_button).performClick()
            val targeted = bank.bands.filter {
                it.id in selected && (kind.mode == null || it.qualityDisplay == kind.mode)
            }.map { it.id }
            val expected = before.toMutableMap()
            targeted.forEach { expected["$it.${kind.field?.name ?: "band"}"] = false }
            assertEquals("Only ${kind.name} in the requested scope may unlock", expected, lockPrefs.all)
            row(adapter, position).findViewById<View>(R.id.peq_bulk_lock_button).performClick()
            assertEquals(before, lockPrefs.all)
            assertEquals(beforeBank, bank)
        }
    }

    @Test fun aBoundButtonRechecksCurrentModesAndMissingIdsAtClickTime() {
        val adapter = adapter()
        val oldQRow = row(adapter, 3)
        bank = bank.copy(bands = listOf(bank.bands[0].copy(qualityDisplay = PeqQualityDisplay.BW), bank.bands[1], bank.bands[3]))
        val before = lockPrefs.all.toMap()
        oldQRow.findViewById<View>(R.id.peq_bulk_lock_button).performClick()
        assertEquals(before, lockPrefs.all)
        assertEquals(PeqResetMenuAdapter.Counts(0, 0), adapter.counts(3))
        val newQRow = row(adapter, 3)
        assertFalse(newQRow.findViewById<View>(R.id.peq_bulk_lock_button).isEnabled)
        assertFalse(newQRow.findViewById<View>(R.id.peq_bulk_unlock_button).isEnabled)
        assertEquals(PeqResetMenuAdapter.Counts(2, 0), adapter.counts(4))
    }

    @Test fun resetRowsShowFieldSpecificCountsAndAllCountsOnlyCompletelyBlockedCardsAsSkipped() {
        locks.setBandLocked("q-first", true)
        locks.setFieldLocked("bw", PeqEditField.QUALITY, true)
        locks.setFieldLocked("bw", PeqEditField.GAIN, true)
        PeqEditField.entries.forEach { locks.setFieldLocked("q-last", it, true) }
        val before = bank
        val resetTargets = mapOf(0 to PeqResetTarget.Q, 1 to PeqResetTarget.FREQUENCY,
            2 to PeqResetTarget.GAIN, 3 to PeqResetTarget.FILTER_TYPE,
            4 to PeqResetTarget.ALL, 5 to PeqResetTarget.FREQUENCY)
        val adapter = PeqResetMenuAdapter(context, listOf("Q", "frequency", "gain", "type", "all", "redistribute", "band lock"),
            mapOf(6 to PeqBulkLockKind.BAND), selected + "missing", { bank }, locks,
            resetTargets, setOf(5)) { changes++ }
        val expected = listOf(0 to 3, 1 to 2, 0 to 3, 1 to 2, 1 to 2, 1 to 2)
        expected.forEachIndexed { index, (editable, protected) ->
            assertEquals(PeqLockedOperations.ResetCounts(editable, protected), adapter.resetCounts(index))
            val resetRow = row(adapter, index)
            val resource = if (index == 5) R.string.peq_bulk_redistribute_counts else R.string.peq_bulk_reset_counts
            val suffix = if (index == 4) "\n" + app.getString(R.string.peq_bulk_reset_unlocked_only) else ""
            assertEquals(app.getString(resource, editable, protected) + suffix,
                resetRow.findViewById<TextView>(R.id.peq_bulk_reset_counts).text.toString())
            assertNull(resetRow.findViewById<View>(R.id.peq_bulk_lock_button))
            assertNull(resetRow.findViewById<View>(R.id.peq_bulk_unlock_button))
        }
        assertNull(adapter.resetCounts(6))
        row(adapter, 6).findViewById<View>(R.id.peq_bulk_unlock_button).performClick()
        assertEquals(PeqLockedOperations.ResetCounts(1, 2), adapter.resetCounts(0))
        assertEquals(PeqLockedOperations.ResetCounts(2, 1), adapter.resetCounts(4))
        assertEquals(app.getString(R.string.peq_bulk_redistribute_counts, 2, 1),
            row(adapter, 5).findViewById<TextView>(R.id.peq_bulk_reset_counts).text.toString())
        assertEquals(1, changes)
        assertEquals(before, bank)
    }
}
