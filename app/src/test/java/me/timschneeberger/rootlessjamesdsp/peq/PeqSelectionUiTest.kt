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
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
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

/** Exercises selection through real rows, dialogs and persisted editor state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqSelectionUiTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = application.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(application)
    private val presets get() = PeqPresetStore(application)
    private var controller: ActivityController<HostActivity>? = null

    @Before fun clear() {
        prefs.edit().clear().commit()
        presets.load().forEach { presets.delete(it.id) }
    }
    @After fun close() { controller?.pause()?.stop()?.destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun <T : View> view(id: Int): T = fragment().requireView().findViewById(id)
    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        val density = application.resources.displayMetrics.density
        content.measure(View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((820 * density).toInt(), View.MeasureSpec.EXACTLY))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        idle()
    }
    private fun initial() = PeqState(listOf(
        PeqBand("a", type = PeqFilterType.HIGH_SHELF, frequency = 100.0, gain = 3.25, q = 2.5),
        PeqBand("b", type = PeqFilterType.NOTCH, enabled = false, frequency = 1000.0, gain = -2.75, q = 3.5),
        PeqBand("c", type = PeqFilterType.LOW_PASS, frequency = 10000.0, gain = 7.5, q = 4.5,
            qualityDisplay = PeqQualityDisplay.BW)), gainRange = 50.0, qRange = 10.0,
        showQSlider = false, showFrequencySlider = false, frequencyStep = 37.0)
    private fun launch(state: PeqState = initial()) {
        assertTrue(repository.save(state))
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
    private fun edit(vararg positions: Int) {
        click(R.id.peq_edit_selection)
        assertEquals(View.VISIBLE, view<View>(R.id.peq_selection_controls).visibility)
        positions.forEach { row(it).performClick(); layout() }
    }
    private fun dialog(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }
    private fun choose(dialog: AlertDialog, index: Int) {
        dialog.listView.performItemClick(null, index, index.toLong())
        idle()
    }
    private fun parameters(index: Int): AlertDialog {
        click(R.id.peq_parameters)
        choose(dialog(), index)
        return dialog()
    }
    private fun quality(index: Int, value: String) {
        val editor = parameters(index)
        editor.findViewById<EditText>(R.id.peq_bulk_quality_input)!!.setText(value)
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertFalse(editor.isShowing)
    }
    private fun scopeExcluded() {
        view<MaterialButtonToggleGroup>(R.id.peq_selection_mode).check(R.id.peq_scope_excluded)
        layout()
    }
    private fun assertSaved(expected: PeqState) {
        assertEquals(expected, repository.load())
        assertEquals(expected, fragment().editorState)
        assertFalse(prefs.getBoolean(application.getString(R.string.key_geq_enable), false))
    }
    private fun settingCount(): Int {
        if (view<View>(R.id.peq_selection_controls).visibility == View.VISIBLE) {
            assertFalse(view<View>(R.id.peq_settings).isEnabled)
            click(R.id.peq_selection_done)
        }
        click(R.id.peq_settings)
        val settings = dialog()
        val count = settings.findViewById<EditText>(R.id.peq_band_count)!!.text.toString().toInt()
        settings.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        layout()
        return count
    }

    @Test fun selectingTwoRowsRestrictsBulkQAndBandwidthWithoutTouchingTheOtherBand() {
        val original = initial()
        launch(original)
        edit(0, 2)
        assertTrue(row(0).isSelected)
        assertFalse(row(1).isSelected)
        assertTrue(row(2).isSelected)
        assertSaved(original)
        quality(0, "1.25")
        val afterQ = original.copy(bands = original.bands.mapIndexed { index, band ->
            if (index != 1) band.copy(q = 1.25, qualityDisplay = PeqQualityDisplay.Q) else band
        })
        assertSaved(afterQ)
        quality(1, "0.5")
        assertSaved(afterQ.copy(bands = afterQ.bands.mapIndexed { index, band ->
            if (index != 1) band.copy(q = PeqResponse.qFromBandwidth(0.5, band.frequency),
                qualityDisplay = PeqQualityDisplay.BW) else band
        }))
        assertTrue(row(0).isSelected)
        assertTrue(row(2).isSelected)
    }

    @Test fun excludedScopeResetsChangesTypeAndDeletesOnlyUnselectedRowsAfterConfirmation() {
        val original = initial()
        launch(original)
        edit(1)
        scopeExcluded()
        click(R.id.peq_reset)
        choose(dialog(), PeqResetTarget.GAIN.ordinal)
        layout()
        val reset = original.copy(bands = original.bands.mapIndexed { index, band ->
            if (index != 1) band.copy(gain = 0.0) else band
        })
        assertSaved(reset)
        val picker = parameters(2)
        choose(picker, PeqFilterType.PEAK.ordinal)
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        val afterType = reset.copy(bands = reset.bands.mapIndexed { index, band ->
            if (index != 1) band.copy(type = PeqFilterType.PEAK) else band
        })
        assertSaved(afterType)
        click(R.id.peq_edit_selection)
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertSaved(afterType)
        click(R.id.peq_edit_selection)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertSaved(original.copy(bands = listOf(original.bands[1])))
        assertTrue(row(0).isSelected)
        assertEquals(1, view<RecyclerView>(R.id.peq_band_list).adapter!!.itemCount)
        assertTrue(view<TextView>(R.id.peq_summary).text.startsWith("1 "))
        assertEquals(1, settingCount())
        assertTrue(view<View>(R.id.peq_add_front).isEnabled)
        assertTrue(view<View>(R.id.peq_add_back).isEnabled)
    }

    @Test fun presetSaveCapturesOnlySelectionAndDoesNotReplaceTheCurrentBank() {
        val original = initial()
        launch(original)
        edit(0, 2)
        click(R.id.peq_presets)
        dialog().findViewById<View>(R.id.peq_presets_save)!!.performClick()
        val name = dialog()
        name.findViewById<EditText>(R.id.peq_preset_name)!!.setText("两段预设")
        name.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertEquals(original.copy(bands = listOf(original.bands[0], original.bands[2])), presets.load().single().state)
        assertSaved(original)
    }

    @Test fun selectionSurvivesRecreationAndFrontInsertionByIdAndDoneRestoresWholeBankOperations() {
        val original = initial()
        launch(original)
        edit(0, 2)
        scopeExcluded()
        controller!!.recreate()
        layout()
        assertEquals(View.VISIBLE, view<View>(R.id.peq_selection_controls).visibility)
        assertEquals(R.id.peq_scope_excluded,
            view<MaterialButtonToggleGroup>(R.id.peq_selection_mode).checkedButtonId)
        assertTrue(row(0).isSelected)
        assertFalse(row(1).isSelected)
        assertTrue(row(2).isSelected)
        click(R.id.peq_add_front)
        assertFalse(row(0).isSelected)
        assertTrue(row(1).isSelected)
        assertFalse(row(2).isSelected)
        assertTrue(row(3).isSelected)
        assertEquals(original.bands, repository.load().bands.drop(1))
        click(R.id.peq_selection_all)
        repeat(4) { assertTrue(row(it).isSelected) }
        click(R.id.peq_selection_none)
        repeat(4) { assertFalse(row(it).isSelected) }
        click(R.id.peq_selection_done)
        assertEquals(View.GONE, view<View>(R.id.peq_selection_controls).visibility)
        quality(0, "1.75")
        assertTrue(repository.load().bands.all { it.q == 1.75 })
        assertEquals(repository.load(), fragment().editorState)
    }

    @Test fun redistributingSelectionChangesOnlyItsFrequenciesAndRequiresConfirmation() {
        val original = initial()
        launch(original)
        edit(0, 2)
        click(R.id.peq_reset)
        choose(dialog(), 5)
        val confirmation = dialog()
        assertSaved(original)
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertSaved(original.copy(bands = listOf(original.bands[0].copy(frequency = 20.0),
            original.bands[1], original.bands[2].copy(frequency = 20000.0))))
    }

    @Test fun emptySelectionDisablesActionsAndDeletingAllIsRejectedUntilOneBandWillRemain() {
        val original = initial()
        launch(original)
        edit()
        listOf(R.id.peq_parameters, R.id.peq_reset, R.id.peq_presets, R.id.peq_edit_selection).forEach {
            assertFalse(view<View>(it).isEnabled)
        }
        assertSaved(original)
        click(R.id.peq_selection_all)
        click(R.id.peq_edit_selection)
        assertSaved(original)
        assertEquals(application.getString(R.string.peq_delete_last_band), ShadowToast.getTextOfLatestToast())
        row(1).performClick() // Leave the middle band unselected, so it survives the deletion.
        layout()
        click(R.id.peq_edit_selection)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertSaved(original.copy(bands = listOf(original.bands[1])))
        assertEquals(1, view<RecyclerView>(R.id.peq_band_list).adapter!!.itemCount)
        assertTrue(view<TextView>(R.id.peq_summary).text.startsWith("1 "))
        assertEquals(1, settingCount())
        click(R.id.peq_add_back)
        assertEquals(2, repository.load().bands.size)
        assertEquals(0.0, repository.load().bands.last().frequency, 0.0)
        assertFalse(row(1).isSelected)
        assertEquals(2, settingCount())
    }

    @Test fun editButtonBecomesDeleteAndSelectionControlsActionAvailabilityAndColorsUntilDoneOrBack() {
        val original = initial()
        launch(original)
        val actionIds = listOf(R.id.peq_reset, R.id.peq_presets, R.id.peq_parameters)
        val originalColors = actionIds.associateWith { view<MaterialButton>(it).currentTextColor }
        val settingsColor = view<MaterialButton>(R.id.peq_settings).currentTextColor
        assertEquals(application.getString(R.string.peq_edit_selection), view<MaterialButton>(R.id.peq_edit_selection).text)
        edit()
        assertEquals(application.getString(R.string.peq_selection_delete), view<MaterialButton>(R.id.peq_edit_selection).text)
        assertFalse(view<View>(R.id.peq_edit_selection).isEnabled)
        assertFalse(view<View>(R.id.peq_settings).isEnabled)
        assertNotEquals(settingsColor, view<MaterialButton>(R.id.peq_settings).currentTextColor)
        val disabledColors = actionIds.associateWith { id ->
            val button = view<MaterialButton>(id)
            assertFalse(button.isEnabled)
            assertNotEquals(originalColors.getValue(id), button.currentTextColor)
            button.currentTextColor
        }
        row(0).performClick()
        layout()
        assertTrue(view<View>(R.id.peq_edit_selection).isEnabled)
        actionIds.forEach { id ->
            val button = view<MaterialButton>(id)
            assertTrue(button.isEnabled)
            assertEquals(MaterialColors.getColor(button, com.google.android.material.R.attr.colorTertiary), button.currentTextColor)
            assertNotEquals(disabledColors.getValue(id), button.currentTextColor)
        }
        click(R.id.peq_selection_none)
        actionIds.forEach { id ->
            assertFalse(view<View>(id).isEnabled)
            assertEquals(disabledColors.getValue(id), view<MaterialButton>(id).currentTextColor)
        }
        click(R.id.peq_selection_done)
        assertEquals(View.GONE, view<View>(R.id.peq_selection_controls).visibility)
        assertTrue(view<View>(R.id.peq_settings).isEnabled)
        assertEquals(settingsColor, view<MaterialButton>(R.id.peq_settings).currentTextColor)
        assertEquals(application.getString(R.string.peq_edit_selection), view<MaterialButton>(R.id.peq_edit_selection).text)
        actionIds.forEach { id ->
            assertTrue(view<View>(id).isEnabled)
            assertEquals(originalColors.getValue(id), view<MaterialButton>(id).currentTextColor)
        }
        edit(1)
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        layout()
        assertEquals(View.GONE, view<View>(R.id.peq_selection_controls).visibility)
        assertFalse(controller!!.get().isFinishing)
        assertSaved(original)
    }

    @Test fun resumingWithAReplacedBankDismissesOldDeleteConfirmationBeforeItCanDeleteNewBands() {
        launch()
        edit(0, 2)
        click(R.id.peq_edit_selection)
        val oldConfirmation = dialog()
        assertTrue(oldConfirmation.isShowing)
        controller!!.pause()
        // Keep one old id to ensure dismissing is based on the bank change, not just missing ids.
        val replacement = initial().copy(bands = listOf(
            PeqBand("a", frequency = 2500.0, gain = 4.5, q = 1.5),
            PeqBand("replacement", frequency = 3500.0, gain = 2.5, q = 2.25)))
        assertTrue(repository.save(replacement))
        controller!!.resume()
        layout()
        assertFalse(oldConfirmation.isShowing)
        assertSaved(replacement)
        assertEquals(2, view<RecyclerView>(R.id.peq_band_list).adapter!!.itemCount)
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
