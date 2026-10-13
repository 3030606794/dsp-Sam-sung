package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
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

/** The optional reorder permission changes ordering access, never parameter protection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqUi7InteractionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val soundPrefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val lockPrefs get() = app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE)
    private val displayPrefs get() = app.getSharedPreferences(PeqEditorPreferences.PREFERENCES, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private val locks get() = PeqBandLockStore(app)
    private val settings get() = PeqEditorPreferences(app)
    private var controller: ActivityController<HostActivity>? = null

    @Before fun clear() {
        soundPrefs.edit().clear().commit()
        lockPrefs.edit().clear().commit()
        displayPrefs.edit().clear().commit()
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        idle()
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun <T : View> view(id: Int): T = fragment().requireView().findViewById(id)
    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        val density = app.resources.displayMetrics.density
        val width = (411 * density).toInt()
        val height = (820 * density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, width, height)
        idle()
    }

    private fun original() = PeqState(listOf(
        PeqBand("locked-a", type = PeqFilterType.PEAK, frequency = 123.456, gain = 1.25, q = 2.75,
            qualityDisplay = PeqQualityDisplay.BW),
        PeqBand("locked-b", type = PeqFilterType.HIGH_SHELF, frequency = 2345.67, gain = -4.5,
            q = 3.25, enabled = false),
        PeqBand("free", type = PeqFilterType.NOTCH, frequency = 6789.1, gain = 2.5, q = 4.25)),
        showQSlider = false, showFrequencySlider = false)

    private fun launch(state: PeqState = original()): PeqState {
        assertTrue(repository.save(state))
        state.bands.forEach { locks.setBandLocked(it.id, it.id != "free") }
        locks.setFieldLocked("locked-a", PeqEditField.GAIN, true)
        locks.setFieldLocked("locked-b", PeqEditField.FILTER_TYPE, true)
        locks.setFieldLocked("free", PeqEditField.QUALITY, true)
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
        return state
    }

    private fun row(index: Int = 0): View {
        val list = view<RecyclerView>(R.id.peq_band_list)
        list.scrollToPosition(index)
        layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }

    private fun drag(index: Int = 0) = row(index).findViewById<MaterialButton>(R.id.peq_band_drag_handle)
    private fun dialog(): AlertDialog {
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun openSettings(): AlertDialog {
        view<View>(R.id.peq_settings).performClick()
        return dialog()
    }

    private fun changeReorderPermission(enabled: Boolean, confirm: Boolean = true) {
        val dialog = openSettings()
        val option = dialog.findViewById<MaterialSwitch>(R.id.peq_allow_locked_band_reorder)!!
        if (option.isChecked != enabled) option.performClick()
        assertEquals(enabled, option.isChecked)
        dialog.getButton(if (confirm) AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        assertFalse(dialog.isShowing)
    }

    private fun assertSaved(state: PeqState) {
        assertEquals(state, repository.load())
        assertEquals(state, fragment().editorState)
        assertFalse(soundPrefs.getBoolean(app.getString(R.string.key_geq_enable), false))
        assertNull(PeqPreviewSession.current())
    }

    private fun assertDisabledDrag(index: Int = 0) {
        val handle = drag(index)
        assertEquals(View.VISIBLE, handle.visibility)
        assertFalse(handle.isEnabled)
        assertEquals(Color.GRAY, handle.iconTint!!.defaultColor)
        assertFalse(handle.performLongClick())
        assertFalse(fragment().canMoveBand(index))
    }

    @Test fun defaultPermissionAndCancelledSettingsCannotMoveALockedCard() {
        val original = launch()
        val beforeSound = soundPrefs.all.toMap()
        val beforeLocks = lockPrefs.all.toMap()
        val beforeDisplay = displayPrefs.all.toMap()
        assertFalse(settings.load().allowLockedBandReorder)
        assertDisabledDrag()
        assertFalse(fragment().moveBand(0, 1))
        assertTrue(fragment().canMoveBand(2))
        assertFalse(fragment().canMoveBand(-1))
        assertFalse(fragment().canMoveBand(original.bands.size))
        val editor = openSettings()
        val option = editor.findViewById<MaterialSwitch>(R.id.peq_allow_locked_band_reorder)!!
        assertFalse(option.isChecked)
        option.performClick()
        assertTrue(option.isChecked)
        assertFalse(settings.load().allowLockedBandReorder)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        assertDisabledDrag()
        assertFalse(fragment().moveBand(0, 1))
        assertSaved(original)
        assertEquals(beforeSound, soundPrefs.all)
        assertEquals(beforeLocks, lockPrefs.all)
        assertEquals(beforeDisplay, displayPrefs.all)
    }

    @Test fun confirmingPermissionEnablesTheVisibleHandleAndMovesOnlyWholeBands() {
        val original = launch()
        val beforeLocks = lockPrefs.all.toMap()
        changeReorderPermission(true)
        assertTrue(settings.load().allowLockedBandReorder)
        val card = row()
        assertTrue(card.findViewById<View>(R.id.peq_band_drag_handle).isEnabled)
        assertTrue(fragment().canMoveBand(0))
        assertTrue(locks.isBandLocked("locked-a"))
        assertFalse(card.findViewById<View>(R.id.peq_band_slider).isEnabled)
        assertFalse(card.findViewById<View>(R.id.peq_band_title).isEnabled)
        assertEquals(Color.GRAY, card.findViewById<TextView>(R.id.peq_band_title).currentTextColor)
        assertSaved(original)
        assertTrue(fragment().moveBand(0, 2))
        val moved = original.copy(bands = listOf(original.bands[1], original.bands[2], original.bands[0]))
        assertEquals(moved, fragment().editorState)
        assertEquals(original.bands.associateBy { it.id }, fragment().editorState.bands.associateBy { it.id })
        assertEquals(beforeLocks, lockPrefs.all)
        controller!!.pause()
        idle()
        assertSaved(moved)
        assertEquals(beforeLocks, lockPrefs.all)
        controller!!.resume()
        layout()
        assertTrue(drag(2).isEnabled)
        assertTrue(locks.isBandLocked("locked-a"))
        assertFalse(row(2).findViewById<View>(R.id.peq_band_slider).isEnabled)
    }

    @Test fun cancellingDisableKeepsAccessButConfirmingItImmediatelyGreysAndDisablesTheLockedHandle() {
        val original = launch()
        val beforeLocks = lockPrefs.all.toMap()
        changeReorderPermission(true)
        assertTrue(drag().isEnabled)
        changeReorderPermission(false, confirm = false)
        assertTrue(settings.load().allowLockedBandReorder)
        assertTrue(drag().isEnabled)
        assertTrue(fragment().canMoveBand(0))
        changeReorderPermission(false)
        assertFalse(settings.load().allowLockedBandReorder)
        assertDisabledDrag()
        assertFalse(fragment().moveBand(0, 1))
        assertTrue(drag(2).isEnabled)
        assertTrue(fragment().canMoveBand(2))
        assertSaved(original)
        assertEquals(beforeLocks, lockPrefs.all)
    }

    @Test fun confirmedPermissionSurvivesActivityRecreationAndReturnsToTheSettingsDialog() {
        val original = launch()
        val beforeLocks = lockPrefs.all.toMap()
        changeReorderPermission(true)
        controller!!.recreate()
        layout()
        assertTrue(settings.load().allowLockedBandReorder)
        assertTrue(fragment().canMoveBand(0))
        assertTrue(drag().isEnabled)
        assertTrue(locks.isBandLocked("locked-a"))
        val restored = openSettings()
        assertTrue(restored.findViewById<MaterialSwitch>(R.id.peq_allow_locked_band_reorder)!!.isChecked)
        restored.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        assertSaved(original)
        assertEquals(beforeLocks, lockPrefs.all)
        changeReorderPermission(false)
        controller!!.recreate()
        layout()
        assertFalse(settings.load().allowLockedBandReorder)
        assertDisabledDrag()
        assertSaved(original)
        assertEquals(beforeLocks, lockPrefs.all)
    }

    @Test fun selectionEditingBlocksEveryDragEvenWhenLockedBandReorderingIsAllowed() {
        val original = launch()
        val beforeLocks = lockPrefs.all.toMap()
        changeReorderPermission(true)
        view<View>(R.id.peq_edit_selection).performClick()
        layout()
        assertEquals(View.VISIBLE, view<View>(R.id.peq_selection_controls).visibility)
        original.bands.indices.forEach { assertDisabledDrag(it) }
        assertFalse(fragment().moveBand(0, 1))
        assertFalse(fragment().moveBand(2, 0))
        assertSaved(original)
        assertEquals(beforeLocks, lockPrefs.all)
        assertTrue(settings.load().allowLockedBandReorder)
        view<View>(R.id.peq_selection_done).performClick()
        layout()
        assertEquals(View.GONE, view<View>(R.id.peq_selection_controls).visibility)
        assertTrue(drag().isEnabled)
        assertTrue(fragment().canMoveBand(0))
        assertSaved(original)
    }

    @Test fun permissionDoesNotMakeASingleBandReorderableOrAllowInvalidMoves() {
        val original = launch(original().copy(bands = listOf(original().bands.first())))
        val beforeLocks = lockPrefs.all.toMap()
        changeReorderPermission(true)
        assertTrue(settings.load().allowLockedBandReorder)
        assertDisabledDrag()
        assertFalse(fragment().moveBand(0, 0))
        assertFalse(fragment().moveBand(0, 1))
        assertFalse(fragment().moveBand(-1, 0))
        assertSaved(original)
        assertEquals(beforeLocks, lockPrefs.all)
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
