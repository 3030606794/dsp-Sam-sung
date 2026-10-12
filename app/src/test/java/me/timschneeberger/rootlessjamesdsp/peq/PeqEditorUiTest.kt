package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
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
import java.time.Duration

/** Exercises the real editor, nested Material dialogs, recycled rows and preference persistence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqEditorUiTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = application.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(application)
    private var controller: ActivityController<HostActivity>? = null

    @Before fun clear() { prefs.edit().clear().commit() }
    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        val metrics = application.resources.displayMetrics
        val config = application.resources.configuration
        val width = (config.screenWidthDp * metrics.density).toInt()
        val height = (config.screenHeightDp * metrics.density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, width, height)
        idle()
    }
    private fun launch(): PeqFragment {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
        return fragment()
    }
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun <T : View> view(id: Int): T = fragment().requireView().findViewById(id)
    private fun row(index: Int): View {
        val list = view<RecyclerView>(R.id.peq_band_list)
        list.scrollToPosition(index)
        layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }
    private fun dialog(): AlertDialog {
        idle() // Material's OnShow listener installs validation before clicking OK.
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun settings(): AlertDialog {
        view<MaterialButton>(R.id.peq_settings).performClick()
        return dialog()
    }
    private fun edit(index: Int = 0): AlertDialog {
        row(index).findViewById<MaterialButton>(R.id.peq_band_edit).performClick()
        return dialog()
    }

    @Test fun enteringEditorDoesNotSaveDefaultBandsOrEnableEffectAndHasNoLegacyEntry() {
        launch()
        assertFalse(repository.hasSavedState())
        assertFalse(prefs.getBoolean(application.getString(R.string.key_geq_enable), false))
        assertEquals(10, view<RecyclerView>(R.id.peq_band_list).adapter!!.itemCount)
        fun texts(view: View): List<String> = when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }
        assertTrue(texts(fragment().requireView()).none {
            it.contains("AutoEQ") || it.contains("旧曲线") || it.contains("48 kHz")
        })
    }

    @Test fun addedBandStartsAtZeroHzZeroGainPeakAndHalfQAndCanBeAssignedAFrequency() {
        val original = PeqModel.defaultState(1).copy(showQSlider = true, showFrequencySlider = true)
        repository.save(original)
        launch()
        view<MaterialButton>(R.id.peq_add_back).performClick()
        layout()
        val added = repository.load().bands.last()
        assertEquals(0.0, added.frequency, 0.0)
        assertEquals(0.0, added.gain, 0.0)
        assertEquals(PeqFilterType.PEAK, added.type)
        assertEquals(0.5, added.q, 0.0)
        assertEquals(original.bands.single(), repository.load().bands.first())
        val warning = row(1).findViewById<MaterialButton>(R.id.peq_band_warning)
        assertEquals(View.VISIBLE, warning.visibility)
        warning.performClick()
        assertEquals(application.getString(R.string.peq_frequency_unset_warning),
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        row(1).findViewById<MaterialButton>(R.id.peq_band_locate).performClick()
        val edit = edit(1)
        edit.findViewById<MaterialButton>(R.id.peq_quality_bw)!!.performClick()
        edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(edit.isShowing)
        assertEquals(0.0, repository.load().bands.last().frequency, 0.0)
        assertEquals(0.5, repository.load().bands.last().q, 0.0)
        row(1).findViewById<MaterialButton>(R.id.peq_frequency_plus).performClick()
        assertEquals(20.0, repository.load().bands.last().frequency, 0.0)
        assertEquals(View.INVISIBLE, row(1).findViewById<View>(R.id.peq_band_warning).visibility)
        assertFalse(prefs.getBoolean(application.getString(R.string.key_geq_enable), false))
        controller!!.recreate()
        layout()
        assertEquals(20.0, repository.load().bands.last().frequency, 0.0)
    }

    @Test fun repeatedFrequenciesCanBeEditedCopiedAndSavedWithoutChangingExistingBands() {
        val original = PeqState(listOf(PeqBand(frequency = 1000.0), PeqBand(frequency = 2000.0)))
        repository.save(original)
        launch()
        val editor = edit(1)
        editor.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("1000")
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(editor.isShowing)
        layout()
        assertEquals(listOf(1000.0, 1000.0), repository.load().bands.map { it.frequency })
        edit(0).findViewById<MaterialButton>(R.id.peq_band_copy)!!.performClick()
        layout()
        assertEquals(listOf(1000.0, 1000.0, 1000.0), repository.load().bands.map { it.frequency })
        controller!!.recreate()
        layout()
        assertEquals(listOf(1000.0, 1000.0, 1000.0), repository.load().bands.map { it.frequency })
    }

    @Test fun curveStaysPinnedAnd170BandsAreRecycledWithValuesSurvivingRotation() {
        val base = PeqModel.defaultState(170)
        repository.save(base.copy(bands = base.bands.mapIndexed { i, band ->
            if (i == 169) band.copy(gain = 3.25) else band
        }, gainStep = 0.25, gainRange = 6.0))
        launch()
        val curve = view<PeqResponseView>(R.id.peq_curve)
        val before = IntArray(2).also { curve.getLocationInWindow(it) }
        val list = view<RecyclerView>(R.id.peq_band_list)
        assertEquals(170, list.adapter!!.itemCount)
        assertTrue("Only visible bands should be inflated", list.childCount in 1..25)
        val last = row(169)
        val after = IntArray(2).also { curve.getLocationInWindow(it) }
        assertArrayEquals(before, after)
        assertEquals(3.25f, last.findViewById<Slider>(R.id.peq_band_slider).value, 0f)
        last.findViewById<MaterialButton>(R.id.peq_band_plus).performClick()
        assertEquals(3.5, repository.load().bands.last().gain, 0.0)
        controller!!.recreate()
        layout()
        assertEquals(3.5f, row(169).findViewById<Slider>(R.id.peq_band_slider).value, 0f)
        assertFalse(prefs.getBoolean(application.getString(R.string.key_geq_enable), false))
    }

    @Test fun narrowingRangeAndChangingStepPreservesOutOfRangeAndOffStepStoredGains() {
        val original = PeqState(listOf(PeqBand(gain = 20.0), PeqBand(gain = 0.1)), gainRange = 24.0)
        repository.save(original)
        launch()
        val settings = settings()
        settings.findViewById<EditText>(R.id.peq_gain_range)!!.setText("6")
        settings.findViewById<EditText>(R.id.peq_gain_step)!!.setText("0.25")
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        val saved = repository.load()
        assertEquals(original.bands, saved.bands)
        assertEquals(6.0, saved.gainRange, 0.0)
        assertEquals(0.25, saved.gainStep, 0.0)
        assertEquals(6f, row(0).findViewById<Slider>(R.id.peq_band_slider).value, 0f)
        assertTrue(row(0).findViewById<TextView>(R.id.peq_band_gain).text.contains("20.00"))
        assertEquals(0.1f, row(1).findViewById<Slider>(R.id.peq_band_slider).value, 0f)
        controller!!.recreate()
        layout()
        assertEquals(original.bands, repository.load().bands)
    }

    @Test fun reducingBandCountRequiresConfirmationAndCancelLeavesEveryBandUntouched() {
        val original = PeqModel.defaultState(3)
        repository.save(original)
        launch()
        val settings = settings()
        settings.findViewById<EditText>(R.id.peq_band_count)!!.setText("171")
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(settings.isShowing)
        assertNotNull(settings.findViewById<TextInputLayout>(R.id.peq_count_box)!!.error)
        assertEquals(original, repository.load())
        settings.findViewById<EditText>(R.id.peq_band_count)!!.setText("1")
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val confirmation = dialog()
        assertNotSame(settings, confirmation)
        assertEquals(original, repository.load())
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(original, repository.load())
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertEquals(original.bands.take(1), repository.load().bands)
        assertEquals(1, view<RecyclerView>(R.id.peq_band_list).adapter!!.itemCount)
    }

    @Test fun countChangeCanAddAndRemoveAtTheFrontWithoutChangingExistingFrequencies() {
        val original = PeqState(listOf(PeqBand(frequency = 123.0, gain = -4.5, q = 2.5),
            PeqBand(frequency = 4567.0, gain = 3.25, type = PeqFilterType.HIGH_PASS,
                qualityDisplay = PeqQualityDisplay.Q)), gainRange = 50.0)
        repository.save(original)
        launch()
        val settings = settings()
        settings.findViewById<EditText>(R.id.peq_band_count)!!.setText("5")
        settings.findViewById<EditText>(R.id.peq_frequency_step)!!.setText("125")
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val confirmation = dialog()
        assertFalse(confirmation.findViewById<MaterialSwitch>(R.id.peq_resize_redistribute)!!.isChecked)
        confirmation.findViewById<View>(R.id.peq_resize_front)!!.performClick()
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        val expanded = repository.load()
        assertEquals(original.bands, expanded.bands.takeLast(2))
        assertEquals(125.0, expanded.frequencyStep, 0.0)
        assertTrue(expanded.bands.take(3).all { it.frequency == 0.0 && it.gain == 0.0 &&
            it.q == 0.5 && it.type == PeqFilterType.PEAK })
        val reduce = settings()
        reduce.findViewById<EditText>(R.id.peq_band_count)!!.setText("2")
        reduce.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        dialog().findViewById<View>(R.id.peq_resize_front)!!.performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertEquals(expanded.copy(bands = original.bands), repository.load())
    }

    @Test fun settingSlidersAndNumberBoxesStayInSyncAtBothEndpointsAndCancelDiscardsDraft() {
        val original = PeqModel.defaultState(3)
        repository.save(original)
        launch()
        val settings = settings()
        data class Setting(val input: Int, val slider: Int, val minimum: Double, val maximum: Double)
        val controls = listOf(
            Setting(R.id.peq_band_count, R.id.peq_band_count_slider, 1.0, 170.0),
            Setting(R.id.peq_gain_step, R.id.peq_gain_step_slider, 0.01, 2.0),
            Setting(R.id.peq_gain_range, R.id.peq_gain_range_slider, 1.0, 100.0),
            Setting(R.id.peq_q_step, R.id.peq_q_step_slider, 0.01, 100.0),
            Setting(R.id.peq_frequency_step, R.id.peq_frequency_step_slider, 1.0, 1000.0)
        )
        controls.forEach { spec ->
            val input = settings.findViewById<EditText>(spec.input)!!
            val slider = settings.findViewById<Slider>(spec.slider)!!
            // Typing changes the thumb, then real key events change the number box.
            input.setText(spec.minimum.toString())
            assertEquals(slider.valueFrom, slider.value, 0f)
            assertTrue(slider.requestFocus())
            repeat(200) { slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }
            assertEquals(slider.valueTo, slider.value, 0f)
            assertEquals(spec.maximum, input.text.toString().toDouble(), 0.0)
            repeat(200) { slider.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) }
            assertEquals(slider.valueFrom, slider.value, 0f)
            assertEquals(spec.minimum, input.text.toString().toDouble(), 0.0)
            input.setText(spec.maximum.toString())
            assertEquals(slider.valueTo, slider.value, 0f)
            assertEquals(original, repository.load())
        }
        settings.findViewById<MaterialSwitch>(R.id.peq_show_q_slider)!!.performClick()
        settings.findViewById<MaterialSwitch>(R.id.peq_show_frequency_slider)!!.performClick()
        settings.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(original, repository.load())
        assertEquals(original, fragment().editorState)
        val reopened = settings()
        assertEquals(original.bands.size.toDouble(), reopened.findViewById<EditText>(R.id.peq_band_count)!!.text.toString().toDouble(), 0.0)
        assertEquals(original.qStep, reopened.findViewById<EditText>(R.id.peq_q_step)!!.text.toString().toDouble(), 0.0)
        assertEquals(original.frequencyStep, reopened.findViewById<EditText>(R.id.peq_frequency_step)!!.text.toString().toDouble(), 0.0)
        assertFalse(reopened.findViewById<MaterialSwitch>(R.id.peq_show_q_slider)!!.isChecked)
        assertFalse(reopened.findViewById<MaterialSwitch>(R.id.peq_show_frequency_slider)!!.isChecked)
    }

    @Test fun invalidSettingInputNeverClampsOrSavesAndCanBeCorrected() {
        val original = PeqModel.defaultState(3)
        repository.save(original)
        launch()
        val settings = settings()
        val cases = listOf(
            Triple(R.id.peq_band_count, R.id.peq_count_box, listOf("0", "171", "1.5", "")),
            Triple(R.id.peq_gain_step, R.id.peq_gain_step_box, listOf("0.009", "2.01", "")),
            Triple(R.id.peq_gain_range, R.id.peq_gain_range_box, listOf("0.99", "100.01", "")),
            Triple(R.id.peq_q_step, R.id.peq_q_step_box, listOf("0.009", "100.01", "")),
            Triple(R.id.peq_frequency_step, R.id.peq_frequency_step_box, listOf("0.99", "1000.01", ""))
        )
        cases.forEach { (inputId, boxId, invalidValues) ->
            val input = settings.findViewById<EditText>(inputId)!!
            val previous = input.text.toString()
            invalidValues.forEach { invalid ->
                input.setText(invalid)
                settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(settings.isShowing)
                assertNotNull(settings.findViewById<TextInputLayout>(boxId)!!.error)
                assertEquals(invalid, input.text.toString())
                assertEquals(original, repository.load())
                assertEquals(original, fragment().editorState)
            }
            input.setText(previous)
            assertNull(settings.findViewById<TextInputLayout>(boxId)!!.error)
        }
        settings.findViewById<EditText>(R.id.peq_q_step)!!.setText("0.37")
        settings.findViewById<EditText>(R.id.peq_frequency_step)!!.setText("37.5")
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(settings.isShowing)
        assertEquals(original.copy(qStep = 0.37, frequencyStep = 37.5), repository.load())
    }

    @Test fun manualSettingsPreserveBandsAndStepsSurviveRecreationAtBothRangeEndpoints() {
        val original = PeqState(listOf(PeqBand(frequency = 777.25, q = 3.25, gain = 37.125)))
        repository.save(original)
        launch()
        listOf(true, false).forEach { maximum ->
            val settings = settings()
            val expected = original.copy(gainStep = if (maximum) 2.0 else 0.01,
                gainRange = if (maximum) 100.0 else 1.0,
                qStep = if (maximum) 100.0 else 0.01,
                frequencyStep = if (maximum) 1000.0 else 1.0)
            settings.findViewById<EditText>(R.id.peq_gain_step)!!.setText(expected.gainStep.toString())
            settings.findViewById<EditText>(R.id.peq_gain_range)!!.setText(expected.gainRange.toString())
            settings.findViewById<EditText>(R.id.peq_q_step)!!.setText(expected.qStep.toString())
            settings.findViewById<EditText>(R.id.peq_frequency_step)!!.setText(expected.frequencyStep.toString())
            settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(settings.isShowing)
            assertEquals(expected, repository.load())
            controller!!.recreate()
            layout()
            assertEquals(expected, fragment().editorState)
            assertEquals(original.bands, repository.load().bands)
        }
        val settings = settings()
        settings.findViewById<EditText>(R.id.peq_gain_step)!!.setText("0.37")
        settings.findViewById<EditText>(R.id.peq_gain_range)!!.setText("17.25")
        settings.findViewById<EditText>(R.id.peq_q_step)!!.setText("0.125")
        settings.findViewById<EditText>(R.id.peq_frequency_step)!!.setText("12.5")
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val expected = original.copy(gainStep = 0.37, gainRange = 17.25, qStep = 0.125, frequencyStep = 12.5)
        assertEquals(expected, repository.load())
        controller!!.recreate()
        layout()
        assertEquals(expected, fragment().editorState)
        assertFalse(prefs.getBoolean(application.getString(R.string.key_geq_enable), false))
    }

    @Test fun everyFilterTypeExposesGainAndSavesItAlongsideFrequencyAndQ() {
        repository.save(PeqModel.defaultState(1))
        launch()
        PeqFilterType.entries.forEach { type ->
            val dialog = edit()
            val selector = dialog.findViewById<MaterialAutoCompleteTextView>(R.id.peq_band_type)!!
            selector.setText(PeqBandAdapter.typeLabel(application, type), false)
            selector.onItemClickListener!!.onItemClick(null, null, type.ordinal, type.ordinal.toLong())
            dialog.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("1234.5")
            dialog.findViewById<EditText>(R.id.peq_quality_input)!!.setText("3.25")
            dialog.findViewById<EditText>(R.id.peq_gain_input)!!.setText("-2.75")
            assertEquals(View.VISIBLE,
                dialog.findViewById<View>(R.id.peq_gain_box)!!.visibility)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            layout()
            val saved = repository.load().bands.single()
            assertEquals(type, saved.type)
            assertEquals(1234.5, saved.frequency, 0.0)
            assertEquals(3.25, saved.q, 0.0)
            assertEquals(-2.75, saved.gain, 0.0)
            assertEquals(View.VISIBLE,
                row(0).findViewById<View>(R.id.peq_gain_controls).visibility)
            row(0).findViewById<MaterialButton>(R.id.peq_band_plus).performClick()
            assertEquals(-2.65, repository.load().bands.single().gain, 1e-12)
        }
    }

    @Test fun preciseDialogRejectsInvalidFrequencyAndQAndSupportsBandwidthConversion() {
        repository.save(PeqModel.defaultState(1))
        launch()
        val dialog = edit()
        dialog.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("20001")
        dialog.findViewById<EditText>(R.id.peq_quality_input)!!.setText("101")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertNotNull(dialog.findViewById<TextInputLayout>(R.id.peq_frequency_box)!!.error)
        assertNotNull(dialog.findViewById<TextInputLayout>(R.id.peq_quality_box)!!.error)
        dialog.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("1000")
        dialog.findViewById<EditText>(R.id.peq_quality_input)!!.setText("2")
        dialog.findViewById<MaterialButton>(R.id.peq_quality_bw)!!.performClick()
        idle()
        val bandwidth = dialog.findViewById<EditText>(R.id.peq_quality_input)!!.text.toString().toDouble()
        assertEquals(PeqResponse.bandwidthOctaves(2.0, 1000.0), bandwidth, 0.0005)
        assertTrue(dialog.findViewById<EditText>(R.id.peq_quality_input)!!.text.toString().matches(Regex("[0-9]+\\.[0-9]{3}")))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertFalse(dialog.isShowing)
        assertEquals(2.0, repository.load().bands.single().q, 0.00001)
    }

    @Test fun dragUpdatesPreviewThenSavesWithin50msWithoutRewritingArchivedCurveOrEnablingEffect() {
        val curve = "GraphicEQ: 50 3; 1000 -2;"
        prefs.edit().putString(application.getString(R.string.key_geq_nodes), curve)
            .putBoolean(application.getString(R.string.key_geq_enable), false).commit()
        repository.save(PeqModel.defaultState(1))
        launch()
        val slider = row(0).findViewById<Slider>(R.id.peq_band_slider)
        assertTrue(slider.requestFocus())
        assertTrue(slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue(fragment().editorState.bands.single().gain > 0.0)
        assertEquals(fragment().editorState, view<PeqResponseView>(R.id.peq_curve).requestedState)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(fragment().editorState, repository.load())
        assertEquals(curve, prefs.getString(application.getString(R.string.key_geq_nodes), null))
        assertFalse(prefs.getBoolean(application.getString(R.string.key_geq_enable), false))
    }

    @Test fun rotationFlushesTheFinalPendingSliderValueBeforeTheDebounceDeadline() {
        repository.save(PeqModel.defaultState(1))
        launch()
        val slider = row(0).findViewById<Slider>(R.id.peq_band_slider)
        slider.requestFocus()
        slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        val expected = fragment().editorState.bands.single().gain
        assertTrue(expected > 0.0)
        // No time advance: onPause must flush the queued final value before recreation.
        controller!!.recreate()
        layout()
        assertEquals(expected, repository.load().bands.single().gain, 0.0)
        assertEquals(expected.toFloat(), row(0).findViewById<Slider>(R.id.peq_band_slider).value, 0f)
    }

    @Test fun maximumQSurvivesRepeatedBandwidthSwitchesAndRememberedModeCanBeEditedBothWays() {
        repository.save(PeqState(listOf(PeqBand(frequency = 1000.0, q = 100.0))))
        launch()
        val first = edit()
        repeat(3) {
            first.findViewById<MaterialButton>(R.id.peq_quality_bw)!!.performClick()
            assertTrue(first.findViewById<EditText>(R.id.peq_quality_input)!!.text.toString()
                .matches(Regex("[0-9]+\\.[0-9]{3}")))
            assertNull(first.findViewById<TextInputLayout>(R.id.peq_quality_box)!!.error)
            first.findViewById<MaterialButton>(R.id.peq_quality_q)!!.performClick()
            assertEquals(100.0, first.findViewById<EditText>(R.id.peq_quality_input)!!.text.toString().toDouble(), 0.0)
        }
        first.findViewById<MaterialButton>(R.id.peq_quality_bw)!!.performClick()
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertFalse(first.isShowing)
        assertEquals(100.0, repository.load().bands.single().q, 0.0)
        assertEquals(PeqQualityDisplay.BW, repository.load().bands.single().qualityDisplay)
        val second = edit()
        assertTrue(second.findViewById<MaterialButton>(R.id.peq_quality_bw)!!.isChecked)
        second.findViewById<EditText>(R.id.peq_quality_input)!!.setText(
            PeqResponse.bandwidthOctaves(12.5, 1000.0).toString())
        second.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertEquals(12.5, repository.load().bands.single().q, 1e-10)
        val third = edit()
        third.findViewById<MaterialButton>(R.id.peq_quality_q)!!.performClick()
        third.findViewById<EditText>(R.id.peq_quality_input)!!.setText("30")
        third.findViewById<MaterialButton>(R.id.peq_quality_bw)!!.performClick()
        third.findViewById<MaterialButton>(R.id.peq_quality_q)!!.performClick()
        third.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertEquals(30.0, repository.load().bands.single().q, 0.0)
        assertEquals(PeqQualityDisplay.Q, repository.load().bands.single().qualityDisplay)
    }

    @Test fun optionalLogarithmicSlidersUseRealUserEventsAndPersistWithoutRedistributingSameCount() {
        val original = PeqState(listOf(PeqBand(frequency = 777.0, q = 2.0, gain = 0.1)))
        repository.save(original)
        launch()
        val settings = settings()
        settings.findViewById<MaterialSwitch>(R.id.peq_show_q_slider)!!.performClick()
        settings.findViewById<MaterialSwitch>(R.id.peq_show_frequency_slider)!!.performClick()
        settings.findViewById<EditText>(R.id.peq_gain_range)!!.setText("100")
        settings.findViewById<EditText>(R.id.peq_gain_step)!!.setText("1.5")
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertEquals(original.bands, repository.load().bands)
        assertTrue(repository.load().showQSlider)
        assertTrue(repository.load().showFrequencySlider)
        assertEquals(100.0, repository.load().gainRange, 0.0)
        assertEquals(1.5, repository.load().gainStep, 0.0)
        val currentRow = row(0)
        listOf(R.id.peq_q_slider, R.id.peq_frequency_slider).forEach { id ->
            val slider = currentRow.findViewById<Slider>(id)
            assertTrue(slider.isShown)
            assertTrue(slider.requestFocus())
            assertTrue(slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        }
        val changed = fragment().editorState
        assertTrue(changed.bands.single().q > 2.0)
        assertTrue(changed.bands.single().frequency > 777.0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(changed, repository.load())
        assertFalse(prefs.getBoolean(application.getString(R.string.key_geq_enable), false))
        controller!!.recreate()
        layout()
        assertEquals(changed, fragment().editorState)
        assertEquals(PeqBandAdapter.logarithmicPosition(changed.bands.single().q, 0.1, 100.0),
            row(0).findViewById<Slider>(R.id.peq_q_slider).value, 0f)
        assertEquals(PeqBandAdapter.logarithmicPosition(changed.bands.single().frequency, 20.0, 20000.0),
            row(0).findViewById<Slider>(R.id.peq_frequency_slider).value, 0f)
    }

    @Test fun explicitRedistributionAndCountChangePreserveOtherParameters() {
        val original = PeqState(listOf(PeqBand(frequency = 80.0, q = 7.0, gain = -3.5),
            PeqBand(frequency = 12000.0, q = 30.0, gain = 4.0, type = PeqFilterType.HIGH_SHELF, enabled = false)))
        repository.save(original)
        launch()
        view<MaterialButton>(R.id.peq_reset).performClick()
        dialog().listView.performItemClick(null, 5, 5)
        assertEquals(original, repository.load())
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        val distributed = repository.load()
        assertEquals(listOf(20.0, 20000.0), distributed.bands.map { it.frequency })
        assertEquals(original.bands, distributed.bands.mapIndexed { index, band -> band.copy(frequency = original.bands[index].frequency) })
        val settings = settings()
        settings.findViewById<EditText>(R.id.peq_band_count)!!.setText("3")
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(distributed, repository.load())
        dialog().findViewById<MaterialSwitch>(R.id.peq_resize_redistribute)!!.performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        val resized = repository.load()
        assertEquals(3, resized.bands.size)
        assertEquals(20.0, resized.bands.first().frequency, 0.0)
        assertEquals(20000.0, resized.bands.last().frequency, 0.0)
        assertEquals(distributed.bands, resized.bands.take(2).mapIndexed { index, band ->
            band.copy(frequency = distributed.bands[index].frequency)
        })
    }

    private fun assertResponsivePlotGeometry() {
        launch()
        val root = fragment().requireView()
        val card = view<View>(R.id.peq_response_card)
        val curve = view<PeqResponseView>(R.id.peq_curve)
        val margin = (2 * application.resources.displayMetrics.density + 0.5f).toInt()
        assertEquals(margin, card.left)
        assertEquals(margin, root.width - card.right)
        assertEquals(card.width, curve.width)
        assertTrue(view<RecyclerView>(R.id.peq_band_list).height > 0)
        val bitmap = Bitmap.createBitmap(curve.width, curve.height, Bitmap.Config.ARGB_8888)
        curve.draw(Canvas(bitmap))
        bitmap.recycle()
        assertTrue(curve.renderedFrequencyLabels.size >= 5)
        assertTrue(curve.renderedGainLabels.size >= 3)
        assertEquals("20", curve.renderedFrequencyLabels.first().text)
        assertEquals("20k", curve.renderedFrequencyLabels.last().text)
        curve.renderedFrequencyLabels.zipWithNext().forEach { (left, right) ->
            assertTrue(left.bounds.right < right.bounds.left)
        }
        curve.renderedGainLabels.zipWithNext().forEach { (upper, lower) ->
            assertTrue(upper.bounds.bottom < lower.bounds.top)
        }
    }

    @Test fun phonePlotUsesTwoDpEdgesAndNonoverlappingDetailedTicks() = assertResponsivePlotGeometry()

    @Test @Config(qualifiers = "w820dp-h411dp-land")
    fun landscapePlotUsesTwoDpEdgesAndLeavesScrollableBandSpace() = assertResponsivePlotGeometry()

    @Test @Config(qualifiers = "sw600dp-w800dp-h1280dp-port")
    fun tabletPlotUsesTwoDpEdgesAndNonoverlappingDetailedTicks() = assertResponsivePlotGeometry()

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = HOST_ID })
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(HOST_ID, PeqFragment(), "peq").commitNow()
        }
        companion object { const val HOST_ID = 0x6001 }
    }
}
