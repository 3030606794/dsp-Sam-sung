package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
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
import org.robolectric.shadows.ShadowToast

/** The real Fragment owns drafts: card labels and saved audio only change after confirmation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqUi5IntegrationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private val locks get() = PeqBandLockStore(app)
    private var controller: ActivityController<HostActivity>? = null

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
        val config = app.resources.configuration
        val width = (config.screenWidthDp * metrics.density).toInt()
        val height = (config.screenHeightDp * metrics.density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, width, height)
        idle()
    }

    private fun original() = PeqState(listOf(
        PeqBand("first", frequency = 1000.0, gain = 3.0, q = 2.0),
        PeqBand("second", type = PeqFilterType.HIGH_SHELF, frequency = 5000.0, gain = -2.0, q = 3.0),
        PeqBand("third", type = PeqFilterType.NOTCH, enabled = false, frequency = 12000.0, gain = 1.0, q = 4.0)),
        showQSlider = false, showFrequencySlider = false)

    private fun launch(settings: PeqEditorSettings = PeqEditorSettings(showInlineFilters = true)): PeqState {
        val state = original()
        assertTrue(repository.save(state))
        assertTrue(PeqEditorPreferences(app).save(settings))
        state.bands.forEach { locks.setBandLocked(it.id, false) }
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

    private fun inlineClick(id: Int, index: Int = 0) {
        row(index).findViewById<View>(id).performClick()
        idle()
    }
    private fun choose(type: PeqFilterType, index: Int = 0) = inlineClick(PeqFilterTypeDialog.buttonId(type), index)
    private fun title(index: Int = 0) = row(index).findViewById<TextView>(R.id.peq_band_title).text.toString()
    private fun withFirstType(state: PeqState, type: PeqFilterType) =
        state.copy(bands = state.bands.map { if (it.id == "first") it.copy(type = type) else it })

    private fun precise(index: Int = 0): AlertDialog {
        row(index).findViewById<View>(R.id.peq_band_number).performClick()
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun assertSaved(expected: PeqState) {
        assertEquals(expected, repository.load())
        assertEquals(expected, fragment().editorState)
        assertFalse(prefs.getBoolean(app.getString(R.string.key_geq_enable), false))
    }

    @Test fun elevenCellInlineAuditionKeepsTheCardTitleUntilConfirmation() {
        val original = launch()
        val beforePrefs = prefs.all.toMap()
        assertEquals(11, row().findViewById<ViewGroup>(R.id.peq_inline_choices).childCount)
        val originalTitle = title()
        choose(PeqFilterType.LOW_PASS)
        assertNull(PeqPreviewSession.current())
        assertEquals(originalTitle, title())
        inlineClick(R.id.peq_inline_monitor)
        assertEquals(withFirstType(original, PeqFilterType.LOW_PASS), PeqPreviewSession.current())
        choose(PeqFilterType.HIGH_PASS)
        assertEquals(withFirstType(original, PeqFilterType.HIGH_PASS), PeqPreviewSession.current())
        assertEquals(originalTitle, title())
        assertEquals(beforePrefs, prefs.all)
        assertSaved(original)
        inlineClick(R.id.peq_inline_confirm)
        assertNull(PeqPreviewSession.current())
        assertSaved(withFirstType(original, PeqFilterType.HIGH_PASS))
        assertEquals(PeqBandAdapter.typeLabel(app, PeqFilterType.HIGH_PASS), title())
        assertFalse(row().findViewById<View>(R.id.peq_inline_monitor).isSelected)
    }

    @Test fun inlineCancelDropsTheAuditionAndLeavesSavedValuesAndTitleUnchanged() {
        val original = launch()
        val before = prefs.all.toMap()
        val originalTitle = title()
        inlineClick(R.id.peq_inline_monitor)
        choose(PeqFilterType.BAND_PASS)
        assertNotNull(PeqPreviewSession.current())
        inlineClick(R.id.peq_inline_cancel)
        assertNull(PeqPreviewSession.current())
        assertEquals(before, prefs.all)
        assertEquals(originalTitle, title())
        assertSaved(original)
        assertFalse(row().findViewById<View>(R.id.peq_inline_confirm).isEnabled)
    }

    @Test fun leavingInlineForPreciseEditorEditModeOrActivityPauseEndsThePreview() {
        val original = launch()
        fun audition() {
            choose(PeqFilterType.LOW_PASS)
            inlineClick(R.id.peq_inline_monitor)
            assertNotNull(PeqPreviewSession.current())
        }
        audition()
        val editor = precise()
        assertNull(PeqPreviewSession.current())
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        audition()
        view<View>(R.id.peq_edit_selection).performClick()
        layout()
        assertNull(PeqPreviewSession.current())
        view<View>(R.id.peq_selection_done).performClick()
        layout()
        audition()
        controller!!.pause()
        idle()
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        controller!!.resume()
        layout()
        assertFalse(row().findViewById<View>(R.id.peq_inline_monitor).isSelected)
    }

    @Test fun inlineTypeLockEndsThePreviewAndCannotBeBypassedByDisabledControls() {
        val original = launch()
        choose(PeqFilterType.LOW_PASS)
        inlineClick(R.id.peq_inline_monitor)
        assertNotNull(PeqPreviewSession.current())
        inlineClick(R.id.peq_inline_lock)
        assertTrue(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        assertNull(PeqPreviewSession.current())
        listOf(PeqFilterTypeDialog.buttonId(PeqFilterType.HIGH_PASS), R.id.peq_inline_monitor,
            R.id.peq_inline_confirm).forEach { id ->
            assertFalse(row().findViewById<View>(id).isEnabled)
            inlineClick(id)
        }
        assertSaved(original)
        assertNull(PeqPreviewSession.current())
        inlineClick(R.id.peq_inline_lock)
        assertFalse(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        choose(PeqFilterType.HIGH_PASS)
        inlineClick(R.id.peq_inline_confirm)
        assertSaved(withFirstType(original, PeqFilterType.HIGH_PASS))
    }

    @Test fun preciseSlidersUpdateDraftInputsAndOnlySaveOnConfirmation() {
        val original = launch()
        val editor = precise()
        val fields = listOf(R.id.peq_frequency_slider to R.id.peq_frequency_input,
            R.id.peq_gain_slider to R.id.peq_gain_input, R.id.peq_quality_slider to R.id.peq_quality_input)
        fields.forEach { (sliderId, inputId) ->
            val slider = editor.findViewById<Slider>(sliderId)!!
            assertTrue(slider.isEnabled)
            assertTrue(slider.requestFocus())
            repeat(200) { slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }
            assertEquals(1f, slider.value, 0f)
            assertTrue(editor.findViewById<EditText>(inputId)!!.text.isNotBlank())
        }
        assertEquals(20000.0, editor.findViewById<EditText>(R.id.peq_frequency_input)!!.text.toString().toDouble(), 0.0)
        assertEquals(original.gainRange, editor.findViewById<EditText>(R.id.peq_gain_input)!!.text.toString().toDouble(), 0.0)
        assertEquals(original.qRange, editor.findViewById<EditText>(R.id.peq_quality_input)!!.text.toString().toDouble(), 0.0)
        assertSaved(original)
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(editor.isShowing)
        assertSaved(original.copy(bands = original.bands.map {
            if (it.id == "first") it.copy(frequency = 20000.0, gain = original.gainRange, q = original.qRange) else it
        }))
    }

    @Test fun preciseLocksCanBeChangedAndLockedDraftValuesNeverOverrideSavedParameters() {
        PeqEditField.entries.forEach { locks.setFieldLocked("first", it, true) }
        val original = launch()
        val editor = precise()
        val fields = listOf(Triple(R.id.peq_frequency_lock, R.id.peq_frequency_slider, R.id.peq_frequency_input),
            Triple(R.id.peq_gain_lock, R.id.peq_gain_slider, R.id.peq_gain_input),
            Triple(R.id.peq_quality_lock, R.id.peq_quality_slider, R.id.peq_quality_input))
        fields.forEach { (lockId, sliderId, inputId) ->
            assertFalse(editor.findViewById<Slider>(sliderId)!!.isEnabled)
            assertFalse(editor.findViewById<EditText>(inputId)!!.isEnabled)
            editor.findViewById<View>(lockId)!!.performClick()
            assertTrue(editor.findViewById<Slider>(sliderId)!!.isEnabled)
            assertTrue(editor.findViewById<EditText>(inputId)!!.isEnabled)
        }
        editor.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("1234")
        editor.findViewById<EditText>(R.id.peq_gain_input)!!.setText("5")
        editor.findViewById<EditText>(R.id.peq_quality_input)!!.setText("1.5")
        val types = editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!
        assertFalse(types.isEnabled)
        editor.findViewById<View>(R.id.peq_type_lock)!!.performClick()
        assertTrue(types.isEnabled)
        types.setSelection(PeqFilterType.NOTCH, notify = true)
        editor.findViewById<View>(R.id.peq_type_lock)!!.performClick()
        editor.findViewById<View>(R.id.peq_gain_lock)!!.performClick()
        assertFalse(types.isEnabled)
        assertTrue(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        assertEquals(original.bands.first().type, types.selectedType)
        // Restoration or a stale callback must also obey the locks when the draft is committed.
        types.setSelection(PeqFilterType.LOW_PASS, notify = true)
        editor.findViewById<EditText>(R.id.peq_gain_input)!!.setText("99999")
        assertSaved(original)
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(editor.isShowing)
        assertSaved(original.copy(bands = original.bands.map {
            if (it.id == "first") it.copy(frequency = 1234.0, q = 1.5) else it
        }))
        assertTrue(locks.isFieldLocked("first", PeqEditField.GAIN))
        assertTrue(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        assertFalse(locks.isFieldLocked("first", PeqEditField.FREQUENCY))
        assertFalse(locks.isFieldLocked("first", PeqEditField.QUALITY))
    }

    @Test fun preciseTitleFollowsBandNumberVisibilitySettingAndUsesCurrentRowNumber() {
        val original = launch()
        val withoutNumber = precise(1)
        assertEquals(app.getString(R.string.peq_edit_band),
            withoutNumber.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
        withoutNumber.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(PeqEditorPreferences(app).save(PeqEditorSettings(showInlineFilters = true, showMarkerBandNumber = true)))
        controller!!.recreate()
        layout()
        val withNumber = precise(1)
        assertEquals(app.getString(R.string.peq_edit_band_number, 2),
            withNumber.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
        withNumber.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertSaved(original)
    }

    @Test fun preciseAndCardDeletionToastUseTheRankBeforeEachDeletion() {
        val original = launch()
        val editor = precise(1)
        editor.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        layout()
        assertFalse(editor.isShowing)
        assertEquals(app.getString(R.string.peq_deleted_band_number, 2), ShadowToast.getTextOfLatestToast())
        assertSaved(original.copy(bands = listOf(original.bands[0], original.bands[2])))
        row(1).findViewById<View>(R.id.peq_band_delete).performClick()
        layout()
        assertEquals(app.getString(R.string.peq_deleted_band_number, 2), ShadowToast.getTextOfLatestToast())
        assertSaved(original.copy(bands = listOf(original.bands[0])))
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
