package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.slider.Slider
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

/** Filter drafts preview visually; monitoring additionally auditions audio, and confirmation persists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqUi6InteractionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private val locks get() = PeqBandLockStore(app)
    private var controller: ActivityController<HostActivity>? = null
    private val graph get() = fragment().requireView().findViewById<PeqResponseView>(R.id.peq_curve)

    @Before fun clear() {
        prefs.edit().clear().commit()
        app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(PeqEditorPreferences.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
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
        val metrics = app.resources.displayMetrics
        val width = (411 * metrics.density).toInt()
        val height = (820 * metrics.density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, width, height)
        idle()
    }

    private fun original() = PeqState(listOf(PeqBand("first", frequency = 1000.0, gain = 3.0, q = 2.0),
        PeqBand("second", type = PeqFilterType.HIGH_SHELF, frequency = 5000.0, gain = -2.0, q = 3.0)),
        gainRange = 12.0, showQSlider = false, showFrequencySlider = false)

    private fun launch(state: PeqState = original(), unlocked: Boolean = true,
                       settings: PeqEditorSettings = PeqEditorSettings()): PeqState {
        assertTrue(repository.save(state))
        assertTrue(PeqEditorPreferences(app).save(settings))
        if (unlocked) state.bands.forEach { locks.setBandLocked(it.id, false) }
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

    private fun dialog(): AlertDialog {
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }
    private fun precise(index: Int = 0): AlertDialog {
        row(index).findViewById<View>(R.id.peq_band_number).performClick()
        return dialog()
    }
    private fun choose(editor: AlertDialog, type: PeqFilterType) {
        editor.findViewById<View>(PeqFilterTypeDialog.buttonId(type))!!.performClick()
        idle()
    }
    private fun click(editor: AlertDialog, id: Int) { editor.findViewById<View>(id)!!.performClick(); idle() }
    private fun withType(state: PeqState, type: PeqFilterType) =
        state.copy(bands = state.bands.map { if (it.id == "first") it.copy(type = type) else it })
    private fun assertSaved(expected: PeqState) {
        assertEquals(expected, repository.load())
        assertEquals(expected, fragment().editorState)
        assertFalse(prefs.getBoolean(app.getString(R.string.key_geq_enable), false))
    }

    private fun assertPreviewMarker(listening: Boolean) {
        assertTrue(graph.isMarkerVisible)
        val expected = app.getString(if (listening) R.string.peq_marker_listening else R.string.peq_marker_preview)
        val other = app.getString(if (listening) R.string.peq_marker_preview else R.string.peq_marker_listening)
        assertTrue(graph.contentDescription.toString().contains(expected))
        assertFalse(graph.contentDescription.toString().contains(other))
    }

    private fun startInlineListening(type: PeqFilterType): View {
        val card = row()
        card.findViewById<View>(PeqFilterTypeDialog.buttonId(type)).performClick()
        idle()
        card.findViewById<View>(R.id.peq_inline_monitor).performClick()
        idle()
        assertNotNull(PeqPreviewSession.current())
        assertTrue(card.findViewById<View>(R.id.peq_inline_monitor).isSelected)
        return card
    }

    private fun togglePin() {
        val activity = controller!!.get()
        val menu = PopupMenu(activity, activity.findViewById<MaterialToolbar>(R.id.toolbar)).menu
        assertTrue(activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu))
        activity.onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)
        assertTrue(activity.onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, menu.findItem(R.id.peq_pin_response)))
        layout()
    }

    @Test fun lockedNumberOpensReadOnlyAndTheTitleLockUnlocksOnlyTheSharedMasterLock() {
        locks.setFieldLocked("first", PeqEditField.GAIN, true)
        val original = launch(unlocked = false)
        val editor = precise()
        assertEquals(app.getString(R.string.peq_fine_band_title, 1),
            editor.findViewById<TextView>(R.id.peq_band_dialog_title)!!.text.toString())
        listOf(R.id.peq_frequency_slider, R.id.peq_gain_slider, R.id.peq_quality_slider,
            R.id.peq_band_type, R.id.peq_band_monitor, R.id.peq_band_copy).forEach {
            assertFalse(editor.findViewById<View>(it)!!.isEnabled)
        }
        assertFalse(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertFalse(editor.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
        click(editor, R.id.peq_band_monitor)
        assertNull(PeqPreviewSession.current())
        click(editor, R.id.peq_band_dialog_lock)
        assertFalse(locks.isBandLocked("first"))
        assertTrue(locks.isFieldLocked("first", PeqEditField.GAIN))
        assertTrue(editor.findViewById<Slider>(R.id.peq_frequency_slider)!!.isEnabled)
        assertTrue(editor.findViewById<Slider>(R.id.peq_quality_slider)!!.isEnabled)
        assertFalse(editor.findViewById<Slider>(R.id.peq_gain_slider)!!.isEnabled)
        assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        editor.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("1200")
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertSaved(original.copy(bands = listOf(original.bands[0].copy(frequency = 1200.0), original.bands[1])))
        assertFalse(locks.isBandLocked("first"))
        assertFalse(row().findViewById<Slider>(R.id.peq_band_slider).isEnabled)
    }

    @Test fun typeLockBlocksListeningAndAcquiringItImmediatelyRevokesAnActiveAudition() {
        locks.setFieldLocked("first", PeqEditField.FILTER_TYPE, true)
        val original = launch()
        val savedPreferences = prefs.all.toMap()
        val editor = precise()
        val monitor = editor.findViewById<View>(R.id.peq_band_monitor)!!
        assertFalse(monitor.isEnabled)
        monitor.performClick()
        assertFalse(monitor.isSelected)
        assertNull(PeqPreviewSession.current())
        click(editor, R.id.peq_type_lock)
        assertTrue(monitor.isEnabled)
        choose(editor, PeqFilterType.LOW_PASS)
        click(editor, R.id.peq_band_monitor)
        assertEquals(withType(original, PeqFilterType.LOW_PASS), PeqPreviewSession.current())
        click(editor, R.id.peq_type_lock)
        assertFalse(monitor.isEnabled)
        assertFalse(monitor.isSelected)
        assertNull(PeqPreviewSession.current())
        assertEquals(original, graph.requestedState)
        assertEquals(savedPreferences, prefs.all)
        editor.findViewById<EditText>(R.id.peq_gain_input)!!.setText("4")
        val gainOnly = original.copy(bands = listOf(original.bands[0].copy(gain = 4.0), original.bands[1]))
        assertEquals(gainOnly, graph.requestedState)
        assertPreviewMarker(listening = false)
        assertNull(PeqPreviewSession.current())
        assertEquals(savedPreferences, prefs.all)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(original, graph.requestedState)
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        assertEquals(savedPreferences, prefs.all)
        assertTrue(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        val reopened = precise()
        reopened.findViewById<EditText>(R.id.peq_gain_input)!!.setText("4")
        reopened.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertSaved(gainOnly)
    }

    @Test fun titleMasterLockDiscardsTheWholeDraftAndStopsListeningBeforeCancel() {
        val original = launch()
        val savedPreferences = prefs.all.toMap()
        val editor = precise()
        choose(editor, PeqFilterType.NOTCH)
        editor.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("1200")
        click(editor, R.id.peq_band_monitor)
        assertNotNull(PeqPreviewSession.current())
        click(editor, R.id.peq_band_dialog_lock)
        assertTrue(locks.isBandLocked("first"))
        assertNull(PeqPreviewSession.current())
        assertEquals(original, graph.requestedState)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isEnabled)
        assertFalse(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertFalse(editor.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
        assertFalse(editor.findViewById<View>(R.id.peq_band_copy)!!.isEnabled)
        assertSaved(original)
        assertEquals(savedPreferences, prefs.all)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        assertTrue(locks.isBandLocked("first"))
        assertFalse(row().findViewById<View>(R.id.peq_band_delete).isEnabled)
        assertSaved(original)
        assertEquals(savedPreferences, prefs.all)
        assertEquals(original, graph.requestedState)
        assertNull(PeqPreviewSession.current())
    }

    @Test fun preciseDraftRemainsVisibleWhenMonitoringStopsAndOnlyConfirmPersistsIt() {
        val original = launch()
        val editor = precise()
        assertPreviewMarker(listening = false)
        choose(editor, PeqFilterType.LOW_PASS)
        val draft = withType(original, PeqFilterType.LOW_PASS)
        assertEquals(draft, graph.requestedState)
        assertPreviewMarker(listening = false)
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        click(editor, R.id.peq_band_monitor)
        assertEquals(draft, PeqPreviewSession.current())
        assertEquals(draft, graph.requestedState)
        assertPreviewMarker(listening = true)
        click(editor, R.id.peq_band_monitor)
        assertNull(PeqPreviewSession.current())
        assertEquals(draft, graph.requestedState)
        assertPreviewMarker(listening = false)
        assertEquals(PeqFilterType.LOW_PASS, editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!.selectedType)
        assertSaved(original)
        click(editor, R.id.peq_band_monitor)
        assertEquals(draft, PeqPreviewSession.current())
        assertEquals(draft, graph.requestedState)
        assertPreviewMarker(listening = true)
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(draft, graph.requestedState)
        assertEquals(draft, PeqPreviewSession.current())
        assertPreviewMarker(listening = true)
        assertSaved(draft)
        val cancelled = precise()
        assertTrue(cancelled.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        choose(cancelled, PeqFilterType.NOTCH)
        assertEquals(withType(draft, PeqFilterType.NOTCH), graph.requestedState)
        assertPreviewMarker(listening = true)
        assertEquals(withType(draft, PeqFilterType.NOTCH), PeqPreviewSession.current())
        click(cancelled, R.id.peq_band_monitor)
        assertNull(PeqPreviewSession.current())
        assertEquals(withType(draft, PeqFilterType.NOTCH), graph.requestedState)
        assertPreviewMarker(listening = false)
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(draft, graph.requestedState)
        assertNull(PeqPreviewSession.current())
        assertFalse(graph.isMarkerVisible)
        assertSaved(draft)
    }

    @Test fun disabledBandDraftAlwaysPreviewsVisuallyAndNeverEnablesTheBand() {
        val original = original().let { it.copy(bands = listOf(it.bands[0].copy(enabled = false), it.bands[1])) }
        launch(original)
        val editor = precise()
        choose(editor, PeqFilterType.NOTCH)
        val draft = withType(original, PeqFilterType.NOTCH)
        assertEquals(draft, graph.requestedState)
        assertTrue(requireNotNull(graph.requestedPlotState).bands.first().enabled)
        assertPreviewMarker(listening = false)
        assertNull(PeqPreviewSession.current())
        click(editor, R.id.peq_band_monitor)
        assertEquals(draft, graph.requestedState)
        assertTrue(requireNotNull(graph.requestedPlotState).bands.first().enabled)
        assertFalse(graph.requestedState!!.bands.first().enabled)
        assertEquals(draft, PeqPreviewSession.current())
        assertFalse(requireNotNull(PeqPreviewSession.current()).bands.first().enabled)
        assertPreviewMarker(listening = true)
        click(editor, R.id.peq_band_monitor)
        assertEquals(draft, graph.requestedState)
        assertTrue(requireNotNull(graph.requestedPlotState).bands.first().enabled)
        assertPreviewMarker(listening = false)
        assertSaved(original)
        click(editor, R.id.peq_band_monitor)
        assertEquals(draft, graph.requestedState)
        assertTrue(requireNotNull(graph.requestedPlotState).bands.first().enabled)
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertSaved(draft)
        assertEquals(draft, PeqPreviewSession.current())
        assertPreviewMarker(listening = true)
        assertFalse(repository.load().bands.first().enabled)
        assertTrue(requireNotNull(graph.requestedPlotState).bands.first().enabled)
    }

    @Test fun standaloneDraftRemainsVisibleWhenMonitoringStopsAndOnlyConfirmPersistsIt() {
        val original = launch()
        row().findViewById<View>(R.id.peq_band_title).performClick()
        val picker = dialog()
        assertPreviewMarker(listening = false)
        choose(picker, PeqFilterType.HIGH_PASS)
        val draft = withType(original, PeqFilterType.HIGH_PASS)
        assertEquals(draft, graph.requestedState)
        assertPreviewMarker(listening = false)
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        click(picker, R.id.peq_filter_monitor)
        assertEquals(draft, PeqPreviewSession.current())
        assertEquals(draft, graph.requestedState)
        assertPreviewMarker(listening = true)
        click(picker, R.id.peq_filter_monitor)
        assertNull(PeqPreviewSession.current())
        assertEquals(draft, graph.requestedState)
        assertPreviewMarker(listening = false)
        assertEquals(PeqFilterType.HIGH_PASS, picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!.selectedType)
        assertSaved(original)
        click(picker, R.id.peq_filter_monitor)
        assertEquals(draft, PeqPreviewSession.current())
        assertEquals(draft, graph.requestedState)
        assertPreviewMarker(listening = true)
        click(picker, R.id.peq_filter_confirm)
        assertFalse(picker.isShowing)
        assertEquals(draft, graph.requestedState)
        assertEquals(draft, PeqPreviewSession.current())
        assertPreviewMarker(listening = true)
        assertSaved(draft)
        row().findViewById<View>(R.id.peq_band_title).performClick()
        val cancelled = dialog()
        assertTrue(cancelled.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        choose(cancelled, PeqFilterType.NOTCH)
        assertEquals(withType(draft, PeqFilterType.NOTCH), graph.requestedState)
        assertPreviewMarker(listening = true)
        assertEquals(withType(draft, PeqFilterType.NOTCH), PeqPreviewSession.current())
        click(cancelled, R.id.peq_filter_monitor)
        assertNull(PeqPreviewSession.current())
        assertEquals(withType(draft, PeqFilterType.NOTCH), graph.requestedState)
        assertPreviewMarker(listening = false)
        click(cancelled, R.id.peq_filter_cancel)
        assertEquals(draft, graph.requestedState)
        assertNull(PeqPreviewSession.current())
        assertFalse(graph.isMarkerVisible)
        assertSaved(draft)
    }

    @Test fun inlineDraftRemainsVisibleWhenMonitoringStopsAndOnlyConfirmPersistsIt() {
        val original = launch(settings = PeqEditorSettings(showInlineFilters = true))
        val card = row()
        card.findViewById<View>(PeqFilterTypeDialog.buttonId(PeqFilterType.BAND_PASS)).performClick()
        idle()
        val draft = withType(original, PeqFilterType.BAND_PASS)
        assertEquals(draft, graph.requestedState)
        assertPreviewMarker(listening = false)
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        card.findViewById<View>(R.id.peq_inline_monitor).performClick()
        idle()
        assertEquals(draft, graph.requestedState)
        assertEquals(draft, PeqPreviewSession.current())
        assertPreviewMarker(listening = true)
        card.findViewById<View>(R.id.peq_inline_monitor).performClick()
        idle()
        assertEquals(draft, graph.requestedState)
        assertPreviewMarker(listening = false)
        assertTrue(card.findViewById<View>(PeqFilterTypeDialog.buttonId(PeqFilterType.BAND_PASS)).isSelected)
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        card.findViewById<View>(R.id.peq_inline_monitor).performClick()
        idle()
        assertEquals(draft, graph.requestedState)
        assertEquals(draft, PeqPreviewSession.current())
        assertPreviewMarker(listening = true)
        card.findViewById<View>(R.id.peq_inline_confirm).performClick()
        idle()
        assertEquals(draft, graph.requestedState)
        assertEquals(draft, PeqPreviewSession.current())
        assertPreviewMarker(listening = true)
        assertSaved(draft)
        card.findViewById<View>(PeqFilterTypeDialog.buttonId(PeqFilterType.NOTCH)).performClick()
        idle()
        assertEquals(withType(draft, PeqFilterType.NOTCH), graph.requestedState)
        assertPreviewMarker(listening = true)
        assertEquals(withType(draft, PeqFilterType.NOTCH), PeqPreviewSession.current())
        card.findViewById<View>(R.id.peq_inline_monitor).performClick()
        idle()
        assertNull(PeqPreviewSession.current())
        assertEquals(withType(draft, PeqFilterType.NOTCH), graph.requestedState)
        assertPreviewMarker(listening = false)
        card.findViewById<View>(R.id.peq_inline_cancel).performClick()
        idle()
        assertEquals(draft, graph.requestedState)
        assertNull(PeqPreviewSession.current())
        assertFalse(graph.isMarkerVisible)
        assertSaved(draft)
    }

    @Test fun batchParameterDraftDoesNotChangeGraphOrAudioUntilConfirmed() {
        val original = launch()
        view<View>(R.id.peq_parameters).performClick()
        val editor = dialog()
        assertNull(editor.findViewById<View>(R.id.peq_filter_monitor))
        assertNull(editor.findViewById<View>(R.id.peq_band_monitor))
        val before = graph.requestedState
        editor.findViewById<EditText>(R.id.peq_batch_q_input)!!.setText("1.5")
        choose(editor, PeqFilterType.LOW_PASS)
        assertEquals(before, graph.requestedState)
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(before, graph.requestedState)
        assertSaved(original)
    }

    @Test fun hidingPreviewStatusTextDoesNotHideTheDraftCurveOrMarkerAndDoesNotStartAudio() {
        val original = launch(settings = PeqEditorSettings(showMarkerPreviewStatus = false))
        val editor = precise()
        val draft = withType(original, PeqFilterType.NOTCH)
        choose(editor, PeqFilterType.NOTCH)
        assertFalse(PeqEditorPreferences(app).load().showMarkerPreviewStatus)
        assertEquals(draft, graph.requestedState)
        assertTrue(graph.isMarkerVisible)
        assertFalse(graph.contentDescription.toString().contains(app.getString(R.string.peq_marker_preview)))
        assertNull(PeqPreviewSession.current())
        click(editor, R.id.peq_band_monitor)
        assertEquals(draft, graph.requestedState)
        assertEquals(draft, PeqPreviewSession.current())
        assertTrue(graph.isMarkerVisible)
        assertFalse(graph.contentDescription.toString().contains(app.getString(R.string.peq_marker_listening)))
        click(editor, R.id.peq_band_monitor)
        assertEquals(draft, graph.requestedState)
        assertTrue(graph.isMarkerVisible)
        assertFalse(graph.contentDescription.toString().contains(app.getString(R.string.peq_marker_preview)))
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(PeqEditorPreferences(app).load().showMarkerPreviewStatus)
        assertEquals(original, graph.requestedState)
        assertFalse(graph.isMarkerVisible)
        assertSaved(original)
    }

    @Test fun pausingPreciseAuditionRestoresGraphAndAudioAndDoesNotResumeListening() {
        val original = launch()
        val editor = precise()
        choose(editor, PeqFilterType.HIGH_PASS)
        click(editor, R.id.peq_band_monitor)
        assertNotNull(PeqPreviewSession.current())
        controller!!.pause()
        idle()
        assertNull(PeqPreviewSession.current())
        assertEquals(original, graph.requestedState)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        controller!!.resume()
        layout()
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
    }

    @Test fun preciseLabelsExposeCurrentRangesAndValueBoxesUseTheCompactWidth() {
        launch()
        val editor = precise(1)
        assertEquals(app.getString(R.string.peq_fine_band_title, 2),
            editor.findViewById<TextView>(R.id.peq_band_dialog_title)!!.text.toString())
        val frequency = editor.findViewById<TextView>(R.id.peq_frequency_label)!!
        val gain = editor.findViewById<TextView>(R.id.peq_gain_label)!!
        val quality = editor.findViewById<TextView>(R.id.peq_quality_range_hint)!!
        assertTrue(frequency.isShown && frequency.text.toString().contains("20") && frequency.text.toString().contains("Hz"))
        assertTrue(gain.isShown && gain.text.toString().contains("12") && gain.text.toString().contains("dB"))
        assertTrue(quality.isShown && quality.text.toString().contains("30"))
        listOf(R.id.peq_frequency_box, R.id.peq_gain_box, R.id.peq_quality_box).forEach { id ->
            val box = editor.findViewById<View>(id)!!
            val widthDp = box.layoutParams.width / app.resources.displayMetrics.density
            assertTrue("The numeric field must be narrower than its previous 92 dp width", widthDp > 0 && widthDp < 92)
        }
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
    }

    @Test fun locatingAnotherVisibleCardStopsInlineListeningAndDiscardsItsTypeDraft() {
        val original = launch(settings = PeqEditorSettings(showInlineFilters = true))
        val savedPreferences = prefs.all.toMap()
        val first = startInlineListening(PeqFilterType.LOW_PASS)
        val second = requireNotNull(view<RecyclerView>(R.id.peq_band_list).findViewHolderForAdapterPosition(1)).itemView
        // Do not scroll or recycle the auditioning row: the locate action itself must clear the draft.
        assertEquals(withType(original, PeqFilterType.LOW_PASS), PeqPreviewSession.current())
        second.findViewById<View>(R.id.peq_band_locate).performClick()
        idle()
        assertNull(PeqPreviewSession.current())
        assertEquals(original, graph.requestedState)
        assertFalse(first.findViewById<View>(R.id.peq_inline_monitor).isSelected)
        assertFalse(first.findViewById<View>(R.id.peq_inline_confirm).isEnabled)
        assertSaved(original)
        assertEquals(savedPreferences, prefs.all)
    }

    @Test fun toolbarPinChangeStopsInlineListeningAndPersistsOnlyThePinSetting() {
        val original = launch(settings = PeqEditorSettings(showInlineFilters = true))
        startInlineListening(PeqFilterType.NOTCH)
        togglePin()
        val expected = original.copy(pinResponse = !original.pinResponse)
        assertNull(PeqPreviewSession.current())
        assertEquals(expected, graph.requestedState)
        assertSaved(expected)
        assertFalse(row().findViewById<View>(R.id.peq_inline_monitor).isSelected)
        assertFalse(row().findViewById<View>(R.id.peq_inline_confirm).isEnabled)
    }

    @Test fun movingABandStopsInlineListeningBeforePersistingItsNewOrder() {
        val original = launch(settings = PeqEditorSettings(showInlineFilters = true))
        startInlineListening(PeqFilterType.HIGH_PASS)
        assertTrue(fragment().moveBand(0, 1))
        assertNull(PeqPreviewSession.current())
        assertEquals(original.bands.associateBy { it.id }, graph.requestedState!!.bands.associateBy { it.id })
        val moved = original.copy(bands = original.bands.reversed())
        assertEquals(moved, fragment().editorState)
        // A completed drag/lifecycle flush saves ordering, never its abandoned audition draft.
        controller!!.pause()
        idle()
        assertSaved(moved)
        assertEquals(moved, graph.requestedState)
        controller!!.resume()
        layout()
        assertNull(PeqPreviewSession.current())
        assertSaved(moved)
    }

    @Test fun standaloneTitleTypeLockAllowsReadOnlyEntryAndRevokesListeningWithoutSavingDrafts() {
        locks.setFieldLocked("first", PeqEditField.FILTER_TYPE, true)
        locks.setFieldLocked("first", PeqEditField.GAIN, true)
        val original = launch()
        val savedPreferences = prefs.all.toMap()
        val label = row().findViewById<View>(R.id.peq_band_title)
        assertTrue(label.isEnabled)
        label.performClick()
        val picker = dialog()
        val selector = picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!
        val monitor = picker.findViewById<View>(R.id.peq_filter_monitor)!!
        val confirm = picker.findViewById<View>(R.id.peq_filter_confirm)!!
        val typeLock = picker.findViewById<View>(R.id.peq_filter_dialog_lock)!!
        assertFalse(selector.isEnabled)
        assertFalse(monitor.isEnabled)
        assertFalse(confirm.isEnabled)
        assertTrue(typeLock.isEnabled)
        choose(picker, PeqFilterType.NOTCH)
        assertEquals(original.bands.first().type, selector.selectedType)
        assertNull(PeqPreviewSession.current())
        click(picker, R.id.peq_filter_dialog_lock)
        assertFalse(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        assertTrue(selector.isEnabled)
        assertTrue(monitor.isEnabled)
        choose(picker, PeqFilterType.LOW_PASS)
        assertEquals(withType(original, PeqFilterType.LOW_PASS), graph.requestedState)
        assertPreviewMarker(listening = false)
        assertNull(PeqPreviewSession.current())
        assertTrue(confirm.isEnabled)
        click(picker, R.id.peq_filter_monitor)
        assertEquals(withType(original, PeqFilterType.LOW_PASS), PeqPreviewSession.current())
        click(picker, R.id.peq_filter_dialog_lock)
        assertTrue(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        assertTrue(locks.isFieldLocked("first", PeqEditField.GAIN))
        assertFalse(locks.isBandLocked("first"))
        assertFalse(selector.isEnabled)
        assertFalse(monitor.isEnabled)
        assertFalse(monitor.isSelected)
        assertFalse(confirm.isEnabled)
        assertEquals(original.bands.first().type, selector.selectedType)
        assertEquals(original, graph.requestedState)
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        assertEquals(savedPreferences, prefs.all)
        click(picker, R.id.peq_filter_cancel)
        assertFalse(picker.isShowing)
        assertEquals(original, graph.requestedState)
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        assertEquals(savedPreferences, prefs.all)
    }

    @Test fun masterLockedCardDisablesTheTypeTitleButItsNumberStillOpensTheReadOnlyFineEditor() {
        val original = launch(unlocked = false)
        val beforeLocks = app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).all.toMap()
        val savedPreferences = prefs.all.toMap()
        val label = row().findViewById<TextView>(R.id.peq_band_title)
        assertFalse(label.isEnabled)
        assertEquals(Color.GRAY, label.currentTextColor)
        label.performClick()
        idle()
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(beforeLocks, app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).all)
        assertTrue(locks.isBandLocked("first"))
        assertFalse(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        assertNull(PeqPreviewSession.current())
        assertEquals(original, graph.requestedState)
        val editor = precise()
        assertEquals(app.getString(R.string.peq_fine_band_title, 1),
            editor.findViewById<TextView>(R.id.peq_band_dialog_title)!!.text.toString())
        assertTrue(editor.findViewById<View>(R.id.peq_band_dialog_lock)!!.isEnabled)
        listOf(R.id.peq_frequency_slider, R.id.peq_gain_slider, R.id.peq_quality_slider,
            R.id.peq_band_type, R.id.peq_band_monitor, R.id.peq_type_lock).forEach {
            assertFalse(editor.findViewById<View>(it)!!.isEnabled)
        }
        assertFalse(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertSaved(original)
        assertEquals(savedPreferences, prefs.all)
        assertEquals(beforeLocks, app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).all)
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
