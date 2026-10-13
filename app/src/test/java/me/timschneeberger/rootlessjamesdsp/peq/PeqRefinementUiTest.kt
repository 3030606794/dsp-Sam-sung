package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
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

/** Protects the explicit power, lock and temporary filter audition workflows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqRefinementUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private val locks get() = PeqBandLockStore(app)
    private val enableKey get() = app.getString(R.string.key_geq_enable)
    private var controller: ActivityController<HostActivity>? = null

    @Before fun clear() {
        prefs.edit().clear().commit()
        app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(PeqEditorPreferences.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        clearPreview()
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        idle()
        clearPreview()
    }

    private fun clearPreview() {
        val token = PeqPreviewSession.begin(PeqModel.defaultState())
        PeqPreviewSession.end(token)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        val metrics = app.resources.displayMetrics
        val config = app.resources.configuration
        val width = (config.screenWidthDp * metrics.density).toInt()
        val height = (config.screenHeightDp * metrics.density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, width, height)
        idle()
    }

    private fun launch(state: PeqState? = null, unlocked: Boolean = true): PeqFragment {
        if (state != null) assertTrue(repository.save(state))
        if (unlocked) (state ?: PeqModel.defaultState()).bands.forEach { locks.setBandLocked(it.id, false) }
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
        return fragment()
    }

    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun <T : View> view(id: Int): T = fragment().requireView().findViewById(id)
    private fun row(index: Int = 0): View {
        val list = view<RecyclerView>(R.id.peq_band_list)
        list.scrollToPosition(index)
        layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }

    private fun dialog(): AlertDialog {
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun filterPicker(): AlertDialog {
        row().findViewById<View>(R.id.peq_band_title).performClick()
        return dialog().also { assertEquals(PeqFilterType.entries.size,
            it.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!.childCount) }
    }

    private fun choose(dialog: AlertDialog, type: PeqFilterType) {
        dialog.findViewById<View>(PeqFilterTypeDialog.buttonId(type))!!.performClick()
        idle()
    }

    private fun bounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun original() = PeqState(listOf(
        PeqBand("audition", frequency = 777.125, gain = 3.25, q = 2.75),
        PeqBand("untouched", type = PeqFilterType.HIGH_SHELF, enabled = false,
            frequency = 3456.75, gain = -2.125, q = 0.8125)))

    private fun withType(state: PeqState, type: PeqFilterType) = state.copy(bands =
        state.bands.mapIndexed { index, band -> if (index == 0) band.copy(type = type) else band })

    @Test fun summaryAndEditAreCenteredAroundTheGraphWhilePowerStaysInTheFooter() {
        launch()
        val toolbar = controller!!.get().findViewById<MaterialToolbar>(R.id.toolbar)
        val summary = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
            .filterIsInstance<TextView>().single { it.text == toolbar.subtitle }
        val graph = bounds(view(R.id.peq_curve))
        val edit = bounds(view(R.id.peq_edit_selection))
        val power = bounds(view(R.id.peq_power))
        val root = bounds(fragment().requireView())
        assertTrue(bounds(summary).bottom <= graph.top)
        assertEquals(13f, summary.textSize / app.resources.displayMetrics.scaledDensity, 0.01f)
        assertEquals(root.exactCenterX(), bounds(summary).exactCenterX(), 1f)
        assertTrue(edit.top >= graph.bottom)
        assertEquals(root.exactCenterX(), edit.exactCenterX(), 1f)
        assertEquals(root.exactCenterX(), power.exactCenterX(), 1f)
        assertTrue(power.top > edit.bottom)
        view<RecyclerView>(R.id.peq_band_list).scrollBy(0, 300)
        layout()
        assertEquals(power, bounds(view(R.id.peq_power)))
        assertFalse(repository.hasSavedState())
    }

    @Test fun footerPowerOnlyChangesTheRealPeqSwitchAndReflectsExternalChanges() {
        val original = original()
        launch(original)
        val saved = prefs.getString(PeqRepository.KEY_STATE, null)
        val button = view<View>(R.id.peq_power)
        assertFalse(button.isSelected)
        button.performClick()
        idle()
        assertTrue(prefs.getBoolean(enableKey, false))
        assertTrue(button.isSelected)
        assertEquals(saved, prefs.getString(PeqRepository.KEY_STATE, null))
        button.performClick()
        idle()
        assertFalse(prefs.getBoolean(enableKey, true))
        assertFalse(button.isSelected)
        assertEquals(original, repository.load())
        prefs.edit().putBoolean(enableKey, true).commit()
        idle()
        assertTrue(button.isSelected)
        assertEquals(original, fragment().editorState)
    }

    @Test fun filterAuditionStartsOffAndTogglingItNeverPersistsDraftsOrEnablesAudio() {
        val original = original()
        launch(original)
        val saved = prefs.all.toMap()
        val picker = filterPicker()
        val monitor = picker.findViewById<View>(R.id.peq_filter_monitor)!!
        assertFalse(monitor.isSelected)
        assertNull(PeqPreviewSession.current())
        choose(picker, PeqFilterType.LOW_PASS)
        assertNull(PeqPreviewSession.current())
        assertEquals(saved, prefs.all)
        monitor.performClick()
        idle()
        assertTrue(picker.isShowing)
        assertTrue(monitor.isSelected)
        assertEquals(withType(original, PeqFilterType.LOW_PASS), PeqPreviewSession.current())
        choose(picker, PeqFilterType.HIGH_PASS)
        assertEquals(withType(original, PeqFilterType.HIGH_PASS), PeqPreviewSession.current())
        assertEquals(original, fragment().editorState)
        assertEquals(saved, prefs.all)
        assertFalse(prefs.getBoolean(enableKey, false))
        monitor.performClick()
        idle()
        assertFalse(monitor.isSelected)
        assertNull(PeqPreviewSession.current())
        assertEquals(original, repository.load())
        monitor.performClick()
        idle()
        assertEquals(withType(original, PeqFilterType.HIGH_PASS), PeqPreviewSession.current())
        picker.findViewById<View>(R.id.peq_filter_cancel)!!.performClick()
        idle()
        assertNull(PeqPreviewSession.current())
        assertEquals(saved, prefs.all)
    }

    @Test fun cancelBackOutsideDismissAndActivityPauseAllEndTemporaryAudition() {
        val original = original()
        launch(original)
        val saved = prefs.all.toMap()
        val exits: List<(AlertDialog) -> Unit> = listOf(
            { it.findViewById<View>(R.id.peq_filter_cancel)!!.performClick() },
            {
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            },
            { it.cancel() }
        )
        for (exit in exits) {
            val picker = filterPicker()
            picker.findViewById<View>(R.id.peq_filter_monitor)!!.performClick()
            choose(picker, PeqFilterType.NOTCH)
            assertNotNull(PeqPreviewSession.current())
            exit(picker)
            idle()
            assertFalse(picker.isShowing)
            assertNull(PeqPreviewSession.current())
            assertEquals(saved, prefs.all)
            assertEquals(original, fragment().editorState)
        }
        val paused = filterPicker()
        paused.findViewById<View>(R.id.peq_filter_monitor)!!.performClick()
        choose(paused, PeqFilterType.BAND_PASS)
        controller!!.pause()
        idle()
        assertNull(PeqPreviewSession.current())
        assertFalse(paused.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        assertEquals(saved, prefs.all)
        controller!!.resume()
        idle()
        assertNull(PeqPreviewSession.current())
        paused.dismiss()
    }

    @Test fun confirmingAnAuditionSavesOnlyTheSelectedTypeAndEndsItsOverride() {
        val original = original()
        prefs.edit().putBoolean(enableKey, true).commit()
        launch(original)
        val picker = filterPicker()
        picker.findViewById<View>(R.id.peq_filter_monitor)!!.performClick()
        choose(picker, PeqFilterType.HIGH_PASS)
        assertEquals(original, repository.load())
        picker.findViewById<View>(R.id.peq_filter_confirm)!!.performClick()
        idle()
        assertFalse(picker.isShowing)
        assertNull(PeqPreviewSession.current())
        assertEquals(withType(original, PeqFilterType.HIGH_PASS), repository.load())
        assertEquals(repository.load(), fragment().editorState)
        assertTrue(prefs.getBoolean(enableKey, false))
        val reopened = filterPicker()
        assertFalse(reopened.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        assertNull(PeqPreviewSession.current())
        reopened.findViewById<View>(R.id.peq_filter_cancel)!!.performClick()
    }

    @Test fun numberedSettingsRespectEveryIndividualParameterLockWhenSaving() {
        val original = original()
        val first = original.bands.first()
        PeqEditField.entries.forEach { locks.setFieldLocked(first.id, it, true) }
        launch(original)
        row().findViewById<View>(R.id.peq_band_number).performClick()
        val editor = dialog()
        val lockedFields = listOf(R.id.peq_frequency_input, R.id.peq_gain_input, R.id.peq_quality_input)
        lockedFields.forEach { id ->
            val input = editor.findViewById<EditText>(id)!!
            assertFalse(input.isEnabled)
            // Even an input update from restoration/autofill cannot rewrite a locked value.
            input.setText("99999")
        }
        assertFalse(editor.findViewById<MaterialButton>(R.id.peq_quality_q)!!.isEnabled)
        assertFalse(editor.findViewById<MaterialButton>(R.id.peq_quality_bw)!!.isEnabled)
        val type = editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!
        type.setSelection(PeqFilterType.LOW_SHELF, notify = true)
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(editor.isShowing)
        assertEquals(original, repository.load())
        assertNull(PeqPreviewSession.current())
    }

    @Test fun lockedBandsCannotStartReorderingAndEditModeBlocksUnlockedReordering() {
        val original = original()
        launch(original, unlocked = false)
        assertTrue(locks.isBandLocked(original.bands.first().id))
        assertFalse(fragment().moveBand(0, 1))
        assertEquals(original, fragment().editorState)
        assertEquals(original, repository.load())
        row().findViewById<View>(R.id.peq_band_lock).performClick()
        assertFalse(locks.isBandLocked(original.bands.first().id))
        assertTrue(fragment().moveBand(0, 1))
        assertEquals(original.bands.reversed(), fragment().editorState.bands)
        view<View>(R.id.peq_edit_selection).performClick()
        idle()
        assertEquals(original.bands.reversed(), repository.load().bands)
        assertFalse(fragment().moveBand(1, 0))
        assertEquals(original.bands.reversed(), fragment().editorState.bands)
    }

    @Test fun preciseFilterMonitoringIsTemporaryAndStopsOnCancelAndPause() {
        val original = original()
        launch(original)
        row().findViewById<View>(R.id.peq_band_number).performClick()
        val editor = dialog()
        val types = editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!
        val monitor = editor.findViewById<View>(R.id.peq_band_monitor)!!
        assertFalse(monitor.isSelected)
        types.setSelection(PeqFilterType.HIGH_PASS, true)
        assertNull(PeqPreviewSession.current())
        monitor.performClick()
        assertTrue(monitor.isSelected)
        assertTrue(types.monitoring)
        assertEquals(withType(original, PeqFilterType.HIGH_PASS), PeqPreviewSession.current())
        assertEquals(original, repository.load())
        assertFalse(prefs.getBoolean(enableKey, false))
        types.setSelection(PeqFilterType.NOTCH, true)
        assertEquals(withType(original, PeqFilterType.NOTCH), PeqPreviewSession.current())
        controller!!.pause()
        idle()
        assertNull(PeqPreviewSession.current())
        assertFalse(monitor.isSelected)
        assertFalse(types.monitoring)
        controller!!.resume()
        idle()
        monitor.performClick()
        assertNotNull(PeqPreviewSession.current())
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertNull(PeqPreviewSession.current())
        assertEquals(original, repository.load())
    }

    @Test fun preciseFilterMonitoringCommitsOnlyWhenConfirmedAndInvalidDraftEndsAudioPreview() {
        val original = original()
        launch(original)
        row().findViewById<View>(R.id.peq_band_number).performClick()
        val editor = dialog()
        editor.findViewById<View>(R.id.peq_band_monitor)!!.performClick()
        editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!.setSelection(PeqFilterType.LOW_PASS, true)
        assertNotNull(PeqPreviewSession.current())
        val input = editor.findViewById<EditText>(R.id.peq_frequency_input)!!
        input.setText("")
        assertNull(PeqPreviewSession.current())
        assertEquals(original, repository.load())
        input.setText("777.125")
        assertNotNull(PeqPreviewSession.current())
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertNull(PeqPreviewSession.current())
        assertEquals(withType(original, PeqFilterType.LOW_PASS), repository.load())
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
