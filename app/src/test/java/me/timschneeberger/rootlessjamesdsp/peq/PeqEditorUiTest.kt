package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
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
        content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 1080, 2200)
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

    @Test fun enteringEditorDoesNotSaveDefaultBandsOrEnableEffectAndLegacyImportRemainsAvailable() {
        launch()
        assertFalse(repository.hasSavedState())
        assertFalse(prefs.getBoolean(application.getString(R.string.key_geq_enable), false))
        assertEquals(10, view<RecyclerView>(R.id.peq_band_list).adapter!!.itemCount)
        assertEquals(View.VISIBLE, view<View>(R.id.peq_legacy).visibility)
        view<MaterialButton>(R.id.peq_legacy).performClick()
        assertTrue(shadowOf(controller!!.get()).nextStartedActivity.getBooleanExtra("legacy_curve", false))
        assertFalse(repository.hasSavedState())
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
        settings.findViewById<MaterialAutoCompleteTextView>(R.id.peq_gain_range)!!.setText("±6 dB", false)
        settings.findViewById<MaterialAutoCompleteTextView>(R.id.peq_gain_step)!!.setText("0.25 dB", false)
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
        assertEquals(listOf(original.bands.first()), repository.load().bands)
        assertEquals(1, view<RecyclerView>(R.id.peq_band_list).adapter!!.itemCount)
    }

    @Test fun allFilterTypesCanBeEditedAndNonGainTypesHideInactiveControls() {
        repository.save(PeqModel.defaultState(1))
        launch()
        PeqFilterType.entries.forEach { type ->
            val dialog = edit()
            val selector = dialog.findViewById<MaterialAutoCompleteTextView>(R.id.peq_band_type)!!
            selector.setText(PeqBandAdapter.typeLabel(application, type), false)
            selector.onItemClickListener!!.onItemClick(null, null, type.ordinal, type.ordinal.toLong())
            dialog.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("1234.5")
            dialog.findViewById<EditText>(R.id.peq_quality_input)!!.setText("3.25")
            if (type.usesGain) dialog.findViewById<EditText>(R.id.peq_gain_input)!!.setText("-2.75")
            assertEquals(if (type.usesGain) View.VISIBLE else View.GONE,
                dialog.findViewById<View>(R.id.peq_gain_box)!!.visibility)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            layout()
            val saved = repository.load().bands.single()
            assertEquals(type, saved.type)
            assertEquals(1234.5, saved.frequency, 0.0)
            assertEquals(3.25, saved.q, 0.0)
            assertEquals(-2.75, saved.gain, 0.0)
            assertEquals(if (type.usesGain) View.VISIBLE else View.GONE,
                row(0).findViewById<View>(R.id.peq_gain_controls).visibility)
        }
    }

    @Test fun preciseDialogRejectsInvalidFrequencyAndQAndSupportsBandwidthConversion() {
        repository.save(PeqModel.defaultState(1))
        launch()
        val dialog = edit()
        dialog.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("20001")
        dialog.findViewById<EditText>(R.id.peq_quality_input)!!.setText("31")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertNotNull(dialog.findViewById<TextInputLayout>(R.id.peq_frequency_box)!!.error)
        assertNotNull(dialog.findViewById<TextInputLayout>(R.id.peq_quality_box)!!.error)
        dialog.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("1000")
        dialog.findViewById<EditText>(R.id.peq_quality_input)!!.setText("2")
        dialog.findViewById<MaterialButton>(R.id.peq_quality_bw)!!.performClick()
        idle()
        val bandwidth = dialog.findViewById<EditText>(R.id.peq_quality_input)!!.text.toString().toDouble()
        assertEquals(PeqResponse.bandwidthOctaves(2.0, 1000.0), bandwidth, 0.000001)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertFalse(dialog.isShowing)
        assertEquals(2.0, repository.load().bands.single().q, 0.00001)
    }

    @Test fun dragUpdatesPreviewThenSavesWithin50msWithoutTakingOverLegacyOrEnablingEffect() {
        val curve = "GraphicEQ: 50 3; 1000 -2;"
        prefs.edit().putString(application.getString(R.string.key_geq_nodes), curve)
            .putBoolean(application.getString(R.string.key_geq_enable), false).commit()
        repository.save(PeqModel.defaultState(1))
        launch()
        assertFalse(PeqController.isParametric(application))
        val slider = row(0).findViewById<Slider>(R.id.peq_band_slider)
        assertTrue(slider.requestFocus())
        assertTrue(slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue(fragment().editorState.bands.single().gain > 0.0)
        assertEquals(fragment().editorState, view<PeqResponseView>(R.id.peq_curve).requestedState)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(fragment().editorState, repository.load())
        assertFalse(PeqController.isParametric(application))
        assertEquals(curve, prefs.getString(application.getString(R.string.key_geq_nodes), null))
        view<MaterialButton>(R.id.peq_activate).performClick()
        idle()
        assertTrue(PeqController.isParametric(application))
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
