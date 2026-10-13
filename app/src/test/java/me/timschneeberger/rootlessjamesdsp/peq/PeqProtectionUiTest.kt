package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/** Exercises protection through the actual card actions and batch menus, not private callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqProtectionUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val lockPrefs get() = app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE)
    private val locks get() = PeqBandLockStore(app)
    private val repository get() = PeqRepository(app)
    private var controller: ActivityController<HostActivity>? = null

    @Before fun clear() {
        prefs.edit().clear().commit()
        lockPrefs.edit().clear().commit()
        app.getSharedPreferences(PeqEditorPreferences.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        val token = PeqPreviewSession.begin(PeqModel.defaultState())
        PeqPreviewSession.end(token)
    }

    @After fun close() { controller?.pause()?.stop()?.destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun <T : View> view(id: Int): T = fragment().requireView().findViewById(id)

    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        val density = app.resources.displayMetrics.density
        content.measure(View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((820 * density).toInt(), View.MeasureSpec.EXACTLY))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        idle()
    }

    private fun launch(state: PeqState, unlocked: Set<String> = emptySet()) {
        assertTrue(repository.save(state))
        unlocked.forEach { locks.setBandLocked(it, false) }
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
    }

    private fun row(index: Int): View {
        val list = view<RecyclerView>(R.id.peq_band_list)
        list.scrollToPosition(index)
        layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }

    private fun click(id: Int) { view<View>(id).performClick(); layout() }
    private fun dialog(): AlertDialog {
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun choose(menu: AlertDialog, label: Int) {
        val expected = app.getString(label)
        val index = (0 until menu.listView.adapter.count).firstOrNull {
            menu.listView.adapter.getItem(it).toString() == expected
        }
        assertNotNull("Menu must contain $expected", index)
        val position = requireNotNull(index)
        menu.listView.performItemClick(null, position, position.toLong())
        layout()
    }

    private fun edit(vararg indices: Int) {
        click(R.id.peq_edit_selection)
        indices.forEach { row(it).performClick(); layout() }
    }

    private fun resetAction(label: Int) {
        click(R.id.peq_reset)
        choose(dialog(), label)
    }

    private fun assertNoDialog() = assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
    private fun assertSaved(state: PeqState) {
        assertEquals(state, repository.load())
        assertEquals(state, fragment().editorState)
        assertFalse(prefs.getBoolean(app.getString(R.string.key_geq_enable), false))
    }

    private fun initial() = PeqState(listOf(
        PeqBand("first", type = PeqFilterType.HIGH_SHELF, gain = 3.25, q = 2.5, frequency = 250.0),
        PeqBand("second", type = PeqFilterType.LOW_SHELF, enabled = false, gain = -4.5,
            q = 3.75, frequency = 1500.0, qualityDisplay = PeqQualityDisplay.BW),
        PeqBand("third", type = PeqFilterType.NOTCH, gain = 1.5, q = 4.25, frequency = 8000.0)),
        gainRange = 18.0, qRange = 30.0)

    @Test fun addingCreatesAnEnabledUnlockedNewCardWithoutChangingExistingLocks() {
        val original = initial()
        launch(original)
        val previousLocks = lockPrefs.all.toMap()
        click(R.id.peq_add_back)
        val saved = repository.load()
        val added = saved.bands.last()
        assertEquals(original.bands, saved.bands.dropLast(1))
        assertTrue(added.enabled)
        assertEquals(0.0, added.frequency, 0.0)
        assertFalse(locks.isBandLocked(added.id))
        assertTrue(locks.isNew(added.id))
        PeqEditField.entries.forEach { assertFalse(locks.isFieldLocked(added.id, it)) }
        previousLocks.forEach { (key, value) -> assertEquals(value, lockPrefs.all[key]) }
        original.bands.forEach { assertTrue(locks.isBandLocked(it.id)) }
        assertEquals(View.VISIBLE, row(3).findViewById<View>(R.id.peq_band_new).visibility)
        row(3).findViewById<View>(R.id.peq_band_number).performClick()
        val editor = dialog()
        assertFalse(locks.isNew(added.id))
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        assertEquals(View.INVISIBLE, row(3).findViewById<View>(R.id.peq_band_new).visibility)
        assertEquals(saved, repository.load())
    }

    @Test fun copyingCreatesAnOffUnlockedNewCardWithTheExactSourceParameters() {
        val original = initial()
        val source = original.bands.first()
        PeqEditField.entries.forEach { locks.setFieldLocked(source.id, it, true) }
        launch(original, unlocked = setOf(source.id))
        row(0).findViewById<View>(R.id.peq_band_number).performClick()
        val editor = dialog()
        editor.findViewById<View>(R.id.peq_band_copy)!!.performClick()
        layout()
        assertFalse(editor.isShowing)
        val result = repository.load()
        val copied = result.bands[1]
        assertNotEquals(source.id, copied.id)
        assertEquals(source.copy(id = copied.id, enabled = false), copied)
        assertEquals(original.bands, result.bands.filterNot { it.id == copied.id })
        assertFalse(locks.isBandLocked(copied.id))
        assertTrue(locks.isNew(copied.id))
        PeqEditField.entries.forEach {
            assertFalse(locks.isFieldLocked(copied.id, it))
            assertTrue(locks.isFieldLocked(source.id, it))
        }
        assertEquals(View.VISIBLE, row(1).findViewById<View>(R.id.peq_band_new).visibility)
        assertFalse(prefs.getBoolean(app.getString(R.string.key_geq_enable), false))
    }

    @Test fun defaultDeleteIsImmediateButNeitherTheCardNorBatchDeleteCanRemoveLockedBands() {
        val original = initial()
        launch(original, unlocked = setOf("second", "third"))
        assertFalse(PeqEditorPreferences(app).load().confirmDelete)
        val protectedDelete = row(0).findViewById<View>(R.id.peq_band_delete)
        assertFalse(protectedDelete.isEnabled)
        protectedDelete.performClick()
        assertSaved(original)
        row(1).findViewById<View>(R.id.peq_band_delete).performClick()
        layout()
        assertNoDialog()
        val reduced = original.copy(bands = listOf(original.bands[0], original.bands[2]))
        assertSaved(reduced)
        edit(0, 1)
        click(R.id.peq_edit_selection)
        assertNoDialog()
        assertSaved(original.copy(bands = listOf(original.bands.first())))
        val deletionMessage = ShadowToast.getTextOfLatestToast().toString()
        assertTrue(deletionMessage.contains(app.getString(R.string.peq_deleted_band_number, 2)))
        assertTrue(deletionMessage.contains(app.getString(R.string.peq_delete_skipped_locked, 1)))
        assertTrue(locks.isBandLocked("first"))
    }

    @Test fun deleteConfirmationCanBeEnabledAndCancelPreservesTheCard() {
        val original = initial()
        assertTrue(PeqEditorPreferences(app).save(PeqEditorSettings(confirmDelete = true)))
        launch(original, unlocked = setOf("first"))
        row(0).findViewById<View>(R.id.peq_band_delete).performClick()
        val confirmation = dialog()
        assertSaved(original)
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        assertSaved(original)
        row(0).findViewById<View>(R.id.peq_band_delete).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertSaved(original.copy(bands = original.bands.drop(1)))
    }

    @Test fun batchPowerChangesOnlySelectedEnableFlagsAndPreservesAllParameterLocks() {
        val original = initial()
        locks.setFieldLocked("first", PeqEditField.GAIN, true)
        locks.setFieldLocked("second", PeqEditField.QUALITY, true)
        locks.setFieldLocked("third", PeqEditField.FREQUENCY, true)
        launch(original, unlocked = setOf("second"))
        val beforeLocks = lockPrefs.all.toMap()
        edit(0, 1)
        resetAction(R.string.peq_bulk_disable)
        assertSaved(original.copy(bands = original.bands.mapIndexed { index, band ->
            if (index < 2) band.copy(enabled = false) else band }))
        assertEquals(beforeLocks, lockPrefs.all)
        resetAction(R.string.peq_bulk_enable)
        assertSaved(original.copy(bands = original.bands.mapIndexed { index, band ->
            if (index < 2) band.copy(enabled = true) else band }))
        assertEquals(beforeLocks, lockPrefs.all)
    }

    @Test fun batchQualityLocksAffectOnlyTheCorrespondingDisplayModeWithinTheSelection() {
        val original = initial()
        launch(original, unlocked = original.bands.map { it.id }.toSet())
        edit(0, 1)
        resetAction(R.string.peq_bulk_lock_q)
        assertTrue(locks.isFieldLocked("first", PeqEditField.QUALITY))
        assertFalse(locks.isFieldLocked("second", PeqEditField.QUALITY))
        assertFalse(locks.isFieldLocked("third", PeqEditField.QUALITY))
        assertEquals(app.getString(R.string.peq_bulk_lock_result, 1), ShadowToast.getTextOfLatestToast())
        resetAction(R.string.peq_bulk_lock_bw)
        assertTrue(locks.isFieldLocked("first", PeqEditField.QUALITY))
        assertTrue(locks.isFieldLocked("second", PeqEditField.QUALITY))
        assertFalse(locks.isFieldLocked("third", PeqEditField.QUALITY))
        resetAction(R.string.peq_bulk_lock_band)
        assertTrue(locks.isBandLocked("first"))
        assertTrue(locks.isBandLocked("second"))
        assertFalse(locks.isBandLocked("third"))
        resetAction(R.string.peq_bulk_unlock)
        for (id in listOf("first", "second")) {
            assertFalse(locks.isBandLocked(id))
            PeqEditField.entries.forEach { assertFalse(locks.isFieldLocked(id, it)) }
        }
        assertSaved(original)
    }

    @Test fun bulkParametersAndResetReportSkippedLocksAndPreserveThoseValues() {
        val original = initial()
        locks.setFieldLocked("second", PeqEditField.QUALITY, true)
        locks.setFieldLocked("second", PeqEditField.GAIN, true)
        launch(original, unlocked = setOf("second", "third"))
        edit(0, 1, 2)
        click(R.id.peq_parameters)
        val quality = dialog()
        val scan = app.getString(R.string.peq_bulk_scan_hint, 3, 1, 1, 0, 1, 0) + "\n" +
            app.getString(R.string.peq_bulk_filter_scan_hint, 0)
        assertEquals(scan, quality.findViewById<TextView>(R.id.peq_batch_scan)!!.text.toString())
        val input = quality.findViewById<EditText>(R.id.peq_batch_q_input)!!
        assertEquals(app.getString(R.string.peq_bulk_mode_hint, 1, 1),
            quality.findViewById<TextView>(R.id.peq_batch_q_hint)!!.text.toString())
        assertEquals(app.getString(R.string.peq_bulk_mode_hint, 0, 1),
            quality.findViewById<TextView>(R.id.peq_batch_bw_hint)!!.text.toString())
        assertFalse(quality.findViewById<View>(R.id.peq_batch_bw_input)!!.isEnabled)
        input.setText("5.5")
        quality.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        val afterQuality = original.copy(bands = original.bands.map {
            if (it.id == "third") it.copy(q = 5.5) else it })
        assertSaved(afterQuality)
        assertEquals(app.getString(R.string.peq_bulk_applied), ShadowToast.getTextOfLatestToast())
        resetAction(R.string.peq_reset_gain)
        val afterGain = afterQuality.copy(bands = afterQuality.bands.map {
            if (it.id == "third") it.copy(gain = 0.0) else it })
        assertSaved(afterGain)
        assertEquals(app.getString(R.string.peq_reset_protected_result, 1, 2), ShadowToast.getTextOfLatestToast())
        resetAction(R.string.peq_reset_all)
        val final = repository.load()
        assertEquals(original.bands.first(), final.bands.first())
        assertEquals(original.bands[1].q, final.bands[1].q, 0.0)
        assertEquals(original.bands[1].gain, final.bands[1].gain, 0.0)
        assertEquals(20.0, final.bands[1].frequency, 0.0)
        assertEquals(PeqFilterType.PEAK, final.bands[1].type)
        assertEquals(PeqModel.DEFAULT_Q, final.bands[2].q, 0.0)
        assertEquals(20000.0, final.bands[2].frequency, 0.0)
        assertEquals(app.getString(R.string.peq_reset_protected_result, 2, 2), ShadowToast.getTextOfLatestToast())
    }

    @Test fun unifiedBatchDraftCancelsAllFieldsAndConfirmingUntouchedFieldsPreservesEveryBand() {
        val original = initial()
        launch(original, unlocked = original.bands.map { it.id }.toSet())
        click(R.id.peq_parameters)
        val draft = dialog()
        draft.findViewById<EditText>(R.id.peq_batch_q_input)!!.setText("1.5")
        draft.findViewById<EditText>(R.id.peq_batch_bw_input)!!.setText("0.5")
        draft.findViewById<View>(PeqFilterTypeDialog.buttonId(PeqFilterType.HIGH_PASS))!!.performClick()
        assertSaved(original)
        assertNull(PeqPreviewSession.current())
        draft.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        assertSaved(original)
        click(R.id.peq_parameters)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertSaved(original)
    }

    @Test fun unifiedBatchDraftRechecksLocksAtConfirmationAndKeepsTheSelectionScope() {
        val original = initial()
        launch(original, unlocked = original.bands.map { it.id }.toSet())
        edit(0, 1)
        click(R.id.peq_parameters)
        val draft = dialog()
        draft.findViewById<EditText>(R.id.peq_batch_q_input)!!.setText("1.5")
        draft.findViewById<EditText>(R.id.peq_batch_bw_input)!!.setText("0.5")
        draft.findViewById<View>(PeqFilterTypeDialog.buttonId(PeqFilterType.HIGH_PASS))!!.performClick()
        locks.setBandLocked("first", true)
        locks.setFieldLocked("second", PeqEditField.QUALITY, true)
        draft.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertFalse(draft.isShowing)
        assertSaved(original.copy(bands = original.bands.map {
            if (it.id == "second") it.copy(type = PeqFilterType.HIGH_PASS) else it
        }))
        assertTrue(locks.isBandLocked("first"))
        assertTrue(locks.isFieldLocked("second", PeqEditField.QUALITY))
        assertNull(PeqPreviewSession.current())
    }

    @Test fun batchFilterLockAndUnlockRespectSelectionAndNeverReleaseTheMasterOrOtherFieldLocks() {
        val original = initial()
        locks.setFieldLocked("first", PeqEditField.GAIN, true)
        locks.setFieldLocked("second", PeqEditField.QUALITY, true)
        launch(original, unlocked = setOf("second", "third"))
        edit(0, 1)
        resetAction(R.string.peq_bulk_lock_filter_type)
        assertTrue(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        assertTrue(locks.isFieldLocked("second", PeqEditField.FILTER_TYPE))
        assertFalse(locks.isFieldLocked("third", PeqEditField.FILTER_TYPE))
        assertTrue(locks.isBandLocked("first"))
        assertTrue(locks.isFieldLocked("first", PeqEditField.GAIN))
        assertTrue(locks.isFieldLocked("second", PeqEditField.QUALITY))
        resetAction(R.string.peq_reset_filter_type)
        assertSaved(original)
        assertEquals(app.getString(R.string.peq_reset_protected_result, 0, 2), ShadowToast.getTextOfLatestToast())

        resetAction(R.string.peq_bulk_unlock_filter_type)
        assertFalse(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        assertFalse(locks.isFieldLocked("second", PeqEditField.FILTER_TYPE))
        assertTrue(locks.isBandLocked("first"))
        assertTrue(locks.isFieldLocked("first", PeqEditField.GAIN))
        assertTrue(locks.isFieldLocked("second", PeqEditField.QUALITY))
        resetAction(R.string.peq_reset_filter_type)
        assertSaved(original.copy(bands = original.bands.map {
            if (it.id == "second") it.copy(type = PeqFilterType.PEAK) else it
        }))
        assertEquals(app.getString(R.string.peq_reset_protected_result, 1, 1), ShadowToast.getTextOfLatestToast())
    }

    @Test fun menuLockButtonsStayOpenAndRefreshModeCountsScanAndOnlyTheSelectedLocks() {
        val original = initial()
        locks.setFieldLocked("first", PeqEditField.QUALITY, true)
        locks.setFieldLocked("third", PeqEditField.QUALITY, true)
        launch(original, unlocked = original.bands.map { it.id }.toSet())
        edit(0, 1)
        click(R.id.peq_reset)
        val menu = dialog()
        val adapter = menu.listView.adapter
        fun lockRow(label: Int): View {
            val index = (0 until adapter.count).first { adapter.getItem(it).toString() == app.getString(label) }
            return adapter.getView(index, null, menu.listView)
        }
        fun counts(label: Int) = lockRow(label).findViewById<TextView>(R.id.peq_bulk_lock_counts).text.toString()
        assertEquals(app.getString(R.string.peq_bulk_lock_counts, 0, 1), counts(R.string.peq_bulk_lock_q))
        assertEquals(app.getString(R.string.peq_bulk_lock_counts, 1, 0), counts(R.string.peq_bulk_lock_bw))
        lockRow(R.string.peq_bulk_lock_q).findViewById<View>(R.id.peq_bulk_unlock_button).performClick()
        layout()
        assertTrue(menu.isShowing)
        assertFalse(locks.isFieldLocked("first", PeqEditField.QUALITY))
        assertFalse(locks.isFieldLocked("second", PeqEditField.QUALITY))
        assertTrue(locks.isFieldLocked("third", PeqEditField.QUALITY))
        assertEquals(app.getString(R.string.peq_bulk_lock_counts, 1, 0), counts(R.string.peq_bulk_lock_q))
        lockRow(R.string.peq_bulk_lock_bw).findViewById<View>(R.id.peq_bulk_lock_button).performClick()
        layout()
        assertTrue(menu.isShowing)
        assertTrue(locks.isFieldLocked("second", PeqEditField.QUALITY))
        assertFalse(locks.isFieldLocked("first", PeqEditField.QUALITY))
        assertEquals(app.getString(R.string.peq_bulk_lock_counts, 0, 1), counts(R.string.peq_bulk_lock_bw))
        val scan = app.getString(R.string.peq_bulk_scan_hint, 2, 0, 0, 0, 1, 0) + "\n" +
            app.getString(R.string.peq_bulk_filter_scan_hint, 0)
        assertEquals(scan, menu.findViewById<TextView>(R.id.peq_bulk_lock_scan)!!.text.toString())
        assertSaved(original)
        menu.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        click(R.id.peq_selection_done)
        assertTrue(row(0).findViewById<View>(R.id.peq_q_slider).isEnabled)
        assertFalse(row(1).findViewById<View>(R.id.peq_q_slider).isEnabled)
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_graphic_eq)
            setSupportActionBar(findViewById(R.id.toolbar))
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .replace(R.id.params, PeqFragment(), "peq").commitNow()
        }
    }
}
