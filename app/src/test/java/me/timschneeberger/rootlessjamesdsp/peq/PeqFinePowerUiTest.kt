package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
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

/** The fine-editor power control commits bypass only; parameter drafts keep their own lifetime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqFinePowerUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private val locks get() = PeqBandLockStore(app)
    private var controller: ActivityController<HostActivity>? = null
    private val fragment get() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private val graph get() = fragment.requireView().findViewById<PeqResponseView>(R.id.peq_curve)

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

    private fun initial(enabled: Boolean = true) = PeqState(listOf(
        PeqBand("first", enabled = enabled, frequency = 1000.0, gain = 3.0, q = 2.0),
        PeqBand("second", type = PeqFilterType.HIGH_SHELF, frequency = 5000.0, gain = -2.0, q = 3.0)),
        gainRange = 12.0, showQSlider = false, showFrequencySlider = false)

    private fun launch(state: PeqState = initial(), unlocked: Boolean = true): PeqState {
        assertTrue(repository.save(state))
        if (unlocked) state.bands.forEach { locks.setBandLocked(it.id, false) }
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
        return state
    }

    private fun card(): View {
        layout()
        val list = fragment.requireView().findViewById<RecyclerView>(R.id.peq_band_list)
        return requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView
    }

    private fun precise(): AlertDialog {
        card().findViewById<View>(R.id.peq_band_number).performClick()
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun click(editor: AlertDialog, id: Int) {
        editor.findViewById<View>(id)!!.performClick()
        idle()
    }

    private fun withPower(state: PeqState, enabled: Boolean) = state.copy(
        bands = state.bands.map { if (it.id == "first") it.copy(enabled = enabled) else it })

    private fun changeDraft(editor: AlertDialog, base: PeqState): PeqState {
        click(editor, PeqFilterTypeDialog.buttonId(PeqFilterType.LOW_SHELF))
        editor.findViewById<EditText>(R.id.peq_gain_input)!!.setText("7")
        editor.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("1200")
        editor.findViewById<EditText>(R.id.peq_quality_input)!!.setText("3")
        idle()
        return base.copy(bands = base.bands.map {
            if (it.id == "first") it.copy(type = PeqFilterType.LOW_SHELF, gain = 7.0, frequency = 1200.0, q = 3.0)
            else it
        }).also { assertEquals(it, graph.requestedState) }
    }

    private fun assertSaved(state: PeqState) {
        assertEquals(state, repository.load())
        assertEquals(state, fragment.editorState)
        assertFalse(prefs.getBoolean(app.getString(R.string.key_geq_enable), false))
    }

    private fun assertPower(editor: AlertDialog, enabled: Boolean, locked: Boolean = false) {
        val button = editor.findViewById<MaterialButton>(R.id.peq_band_dialog_power)!!
        assertEquals(!locked, button.isEnabled)
        assertEquals(enabled, button.isSelected)
        assertEquals(if (enabled && !locked) Color.rgb(46, 170, 83) else Color.GRAY,
            button.iconTint!!.defaultColor)
        assertEquals(app.getString(if (enabled) R.string.peq_disable_band else R.string.peq_enable_band),
            button.contentDescription.toString())
    }

    private fun assertCardPower(enabled: Boolean) {
        val button = card().findViewById<MaterialButton>(R.id.peq_band_power)
        assertEquals(if (enabled) Color.rgb(46, 170, 83) else Color.GRAY, button.iconTint!!.defaultColor)
        assertEquals(app.getString(if (enabled) R.string.peq_disable_band else R.string.peq_enable_band),
            button.contentDescription.toString())
    }

    @Test fun titlePowerAndCardPowerStaySynchronizedAcrossClosingAndReopeningTheEditor() {
        val original = launch(initial(enabled = false))
        val editor = precise()
        assertPower(editor, enabled = false)
        click(editor, R.id.peq_band_dialog_power)
        assertPower(editor, enabled = true)
        assertSaved(withPower(original, true))
        assertCardPower(true)
        assertNull(PeqPreviewSession.current())
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        card().findViewById<View>(R.id.peq_band_power).performClick()
        layout()
        assertSaved(original)
        assertCardPower(false)
        val reopened = precise()
        assertPower(reopened, enabled = false)
        click(reopened, R.id.peq_band_dialog_power)
        assertSaved(withPower(original, true))
        assertCardPower(true)
    }

    @Test fun togglingPowerPersistsOnlyEnabledAndCancelDiscardsAllUnconfirmedParameterEdits() {
        val original = launch()
        val otherPreferences = prefs.all.filterKeys { it != PeqRepository.KEY_STATE }
        val editor = precise()
        val draft = changeDraft(editor, original)
        assertSaved(original)
        click(editor, R.id.peq_band_dialog_power)
        val savedPowerOnly = withPower(original, false)
        assertSaved(savedPowerOnly)
        assertEquals(otherPreferences, prefs.all.filterKeys { it != PeqRepository.KEY_STATE })
        assertEquals(withPower(draft, false), graph.requestedState)
        assertTrue(graph.requestedPlotState!!.bands.first().enabled)
        assertNull(PeqPreviewSession.current())
        assertPower(editor, enabled = false)
        assertCardPower(false)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        assertFalse(editor.isShowing)
        assertSaved(savedPowerOnly)
        assertEquals(savedPowerOnly, graph.requestedState)
        assertEquals(savedPowerOnly, graph.requestedPlotState)
        assertFalse(graph.isMarkerVisible)
        assertNull(PeqPreviewSession.current())
        val reopened = precise()
        assertEquals(PeqFilterType.PEAK,
            reopened.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!.selectedType)
        assertEquals(3.0, reopened.findViewById<EditText>(R.id.peq_gain_input)!!.text.toString().toDouble(), 0.0)
        assertPower(reopened, enabled = false)
    }

    @Test fun listeningRebuildsItsAudioDraftWithTheLatestPowerAndNeverCommitsItsParameters() {
        val original = launch()
        val editor = precise()
        val draft = changeDraft(editor, original)
        click(editor, R.id.peq_band_monitor)
        assertEquals(draft, PeqPreviewSession.current())
        val beforePower = PeqPreviewSession.current()
        click(editor, R.id.peq_band_dialog_power)
        assertTrue(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertEquals(withPower(draft, false), PeqPreviewSession.current())
        assertNotSame(beforePower, PeqPreviewSession.current())
        assertFalse(PeqPreviewSession.current()!!.bands.first().enabled)
        assertSaved(withPower(original, false))
        click(editor, R.id.peq_band_dialog_power)
        assertEquals(draft, PeqPreviewSession.current())
        assertSaved(original)
        click(editor, R.id.peq_band_dialog_power)
        assertEquals(withPower(draft, false), PeqPreviewSession.current())
        click(editor, R.id.peq_band_monitor)
        assertNull(PeqPreviewSession.current())
        assertEquals(withPower(draft, false), graph.requestedState)
        click(editor, R.id.peq_band_monitor)
        assertEquals(withPower(draft, false), PeqPreviewSession.current())
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        assertNull(PeqPreviewSession.current())
        assertSaved(withPower(original, false))
        assertEquals(withPower(original, false), graph.requestedState)
        assertFalse(graph.isMarkerVisible)
    }

    @Test fun masterLockGreysAndGuardsPowerWhileUnlockingRestoresItWithoutClearingFieldLocks() {
        locks.setFieldLocked("first", PeqEditField.GAIN, true)
        val original = launch(unlocked = false)
        val editor = precise()
        assertPower(editor, enabled = true, locked = true)
        click(editor, R.id.peq_band_dialog_power)
        assertSaved(original)
        click(editor, R.id.peq_band_dialog_lock)
        assertPower(editor, enabled = true)
        assertFalse(locks.isBandLocked("first"))
        assertTrue(locks.isFieldLocked("first", PeqEditField.GAIN))
        click(editor, R.id.peq_band_dialog_power)
        assertSaved(withPower(original, false))
        click(editor, R.id.peq_band_dialog_lock)
        assertPower(editor, enabled = false, locked = true)
        click(editor, R.id.peq_band_dialog_power)
        assertSaved(withPower(original, false))
        assertNull(PeqPreviewSession.current())
        click(editor, R.id.peq_band_dialog_lock)
        assertPower(editor, enabled = false)
        click(editor, R.id.peq_band_dialog_power)
        assertPower(editor, enabled = true)
        assertSaved(original)
        assertTrue(locks.isFieldLocked("first", PeqEditField.GAIN))
    }

    @Test fun confirmingTheParameterDraftKeepsThePowerStateThatWasExplicitlySaved() {
        assertTrue(PeqEditorPreferences(app).save(PeqEditorSettings(showInlineFilters = true)))
        val original = launch(initial(enabled = false))
        val editor = precise()
        val draft = changeDraft(editor, original)
        click(editor, R.id.peq_band_monitor)
        click(editor, R.id.peq_band_dialog_power)
        assertSaved(withPower(original, true))
        assertEquals(withPower(draft, true), PeqPreviewSession.current())
        val preview = PeqPreviewSession::class.java.getDeclaredField("preview").apply { isAccessible = true }
        val current = requireNotNull(preview.get(PeqPreviewSession))
        val ownerField = current.javaClass.getDeclaredField("owner").apply { isAccessible = true }
        val owner = ownerField.get(current)
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertFalse(editor.isShowing)
        assertSaved(withPower(draft, true))
        assertEquals(withPower(draft, true), PeqPreviewSession.current())
        assertSame(owner, ownerField.get(requireNotNull(preview.get(PeqPreviewSession))))
        assertTrue(card().findViewById<View>(R.id.peq_inline_monitor).isSelected)
        assertEquals(withPower(draft, true), graph.requestedState)
        assertCardPower(true)
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
