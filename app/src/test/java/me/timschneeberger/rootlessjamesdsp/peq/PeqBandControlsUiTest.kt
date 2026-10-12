package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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

/** Regression coverage for the BW row bug, real +/- events, and dynamic BW limits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqBandControlsUiTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val repository get() = PeqRepository(application)
    private var controller: ActivityController<HostActivity>? = null

    @Before fun clear() {
        application.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun close() { controller?.pause()?.stop()?.destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        val density = application.resources.displayMetrics.density
        val width = (411 * density).toInt()
        val height = (820 * density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, width, height)
        idle()
    }
    private fun launch(state: PeqState) {
        assertTrue(repository.save(state))
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
    }
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun row(index: Int = 0): View {
        val list = fragment().requireView().findViewById<RecyclerView>(R.id.peq_band_list)
        list.scrollToPosition(index)
        layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }
    private fun press(row: View, id: Int) = row.findViewById<MaterialButton>(id).performClick()
    private fun pressRight(slider: Slider) {
        assertTrue(slider.requestFocus())
        assertTrue(slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
    }
    private fun dragTo(slider: Slider, position: Float) {
        assertTrue("A real laid-out slider track is required", slider.trackWidth > 0)
        val start = (slider.value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)
        val startX = slider.trackSidePadding + start * slider.trackWidth
        val targetX = slider.trackSidePadding + position * slider.trackWidth
        val y = slider.height / 2f
        val downTime = SystemClock.uptimeMillis()
        fun dispatch(action: Int, time: Long, x: Float) {
            val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
            try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN, downTime, startX)
        dispatch(MotionEvent.ACTION_MOVE, downTime + 20, targetX)
        dispatch(MotionEvent.ACTION_UP, downTime + 40, targetX)
    }
    private fun curve() = fragment().requireView().findViewById<PeqResponseView>(R.id.peq_curve)
    private fun drawMarker(): PeqResponseView.BandMarker {
        val plot = curve()
        val bitmap = Bitmap.createBitmap(plot.width, plot.height, Bitmap.Config.ARGB_8888)
        plot.draw(Canvas(bitmap))
        bitmap.recycle()
        return requireNotNull(plot.renderedMarker)
    }

    @Test fun choosingBandwidthPersistsBothModeAndTheActualRowSliderWithoutChangingQ() {
        val original = PeqBand(q = 2.345678901, frequency = 7000.0, gain = 6.0)
        launch(PeqState(listOf(original), showQSlider = true, showFrequencySlider = true))
        press(row(), R.id.peq_band_edit)
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<MaterialButton>(R.id.peq_quality_bw)!!.performClick()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertFalse(dialog.isShowing)
        assertEquals(original.q, repository.load().bands.single().q, 0.0)
        val current = row()
        assertTrue(current.findViewById<TextView>(R.id.peq_q_value).text.startsWith("BW"))
        val bw = PeqResponse.bandwidthOctaves(original.q, original.frequency)
        val bounds = PeqBandAdapter.qualityBounds(repository.load().bands.single())
        assertEquals(PeqBandAdapter.logarithmicPosition(bw, bounds.start, bounds.endInclusive),
            current.findViewById<Slider>(R.id.peq_q_slider).value, 0f)
        press(current, R.id.peq_q_plus)
        val wider = repository.load().bands.single()
        assertEquals(bw + 0.01, PeqBandAdapter.displayedQuality(wider), 1e-12)
        assertTrue(wider.q < original.q)
        controller!!.recreate()
        layout()
        assertEquals(wider, repository.load().bands.single())
        assertTrue(row().findViewById<TextView>(R.id.peq_q_value).text.startsWith("BW"))
    }

    @Test fun plusMinusUseConfiguredStepsAndStopAtRealParameterLimits() {
        launch(PeqState(listOf(PeqBand(q = 2.0, frequency = 1000.0)),
            qStep = 0.25, frequencyStep = 123.0, showQSlider = true, showFrequencySlider = true))
        val current = row()
        press(current, R.id.peq_q_plus)
        assertEquals(2.25, repository.load().bands.single().q, 0.0)
        press(current, R.id.peq_q_minus)
        assertEquals(2.0, repository.load().bands.single().q, 0.0)
        press(current, R.id.peq_frequency_plus)
        assertEquals(1123.0, repository.load().bands.single().frequency, 0.0)
        press(current, R.id.peq_frequency_minus)
        assertEquals(1000.0, repository.load().bands.single().frequency, 0.0)
        assertEquals(2.0, repository.load().bands.single().q, 0.0)
        repeat(8) { press(current, R.id.peq_q_minus) }
        assertEquals(PeqModel.MIN_Q, repository.load().bands.single().q, 0.0)
        assertFalse(current.findViewById<MaterialButton>(R.id.peq_q_minus).isEnabled)
        repeat(8) { press(current, R.id.peq_frequency_minus) }
        assertEquals(PeqModel.MIN_FREQUENCY, repository.load().bands.single().frequency, 0.0)
        assertFalse(current.findViewById<MaterialButton>(R.id.peq_frequency_minus).isEnabled)
    }

    @Test fun bandwidthSliderWidensCurveAndFrequencyMovementKeepsBandwidthOnTheActiveRow() {
        val original = PeqBand(q = 2.0, frequency = 1000.0, gain = 12.0, qualityDisplay = PeqQualityDisplay.BW)
        launch(PeqState(listOf(original), qStep = 0.01, frequencyStep = 1000.0,
            showQSlider = true, showFrequencySlider = true))
        val current = row()
        val slider = current.findViewById<Slider>(R.id.peq_q_slider)
        val originalBounds = PeqBandAdapter.qualityBounds(original)
        dragTo(slider, PeqBandAdapter.logarithmicPosition(1.25, originalBounds.start, originalBounds.endInclusive))
        val wider = fragment().editorState.bands.single()
        assertEquals(1.25, PeqBandAdapter.displayedQuality(wider), 1e-12)
        assertTrue(PeqBandAdapter.displayedQuality(wider) > PeqBandAdapter.displayedQuality(original))
        assertTrue(wider.q < original.q)
        assertTrue(PeqResponse.responseDb(PeqState(listOf(wider)), 1500.0) >
            PeqResponse.responseDb(PeqState(listOf(original)), 1500.0))
        val width = PeqBandAdapter.displayedQuality(wider)
        press(current, R.id.peq_frequency_plus)
        val moved = repository.load().bands.single()
        assertEquals(2000.0, moved.frequency, 0.0)
        assertEquals(width, PeqBandAdapter.displayedQuality(moved), 1e-12)
        assertNotEquals(wider.q, moved.q)
        val bounds = PeqBandAdapter.qualityBounds(moved)
        assertEquals(PeqBandAdapter.logarithmicPosition(width, bounds.start, bounds.endInclusive), slider.value, 1e-7f)
        assertEquals(PeqBandAdapter.logarithmicPosition(2000.0, 20.0, 20000.0),
            current.findViewById<Slider>(R.id.peq_frequency_slider).value, 0f)
        // A second edit uses bounds from 2000 Hz, not the captured bounds from bind time.
        dragTo(slider, PeqBandAdapter.logarithmicPosition(2.0, bounds.start, bounds.endInclusive))
        assertEquals(2.0, PeqBandAdapter.displayedQuality(fragment().editorState.bands.single()), 1e-12)
        assertTrue(PeqBandAdapter.displayedQuality(fragment().editorState.bands.single()) > width)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(fragment().editorState, repository.load())
    }

    @Test fun qSliderNarrowsCurveAndChangingStepsDoesNotRewriteStoredOffStepParameters() {
        val original = PeqBand(q = 2.033, frequency = 777.123, gain = 12.0)
        launch(PeqState(listOf(original), qStep = 0.1, frequencyStep = 100.0,
            showQSlider = true, showFrequencySlider = true))
        assertEquals(original, repository.load().bands.single())
        val current = row()
        dragTo(current.findViewById(R.id.peq_q_slider),
            PeqBandAdapter.logarithmicPosition(4.3, PeqModel.MIN_Q, PeqModel.MAX_Q))
        val narrower = fragment().editorState.bands.single()
        assertEquals(4.3, narrower.q, 1e-12)
        assertTrue(narrower.q > original.q)
        assertEquals(kotlin.math.round(narrower.q / 0.1), narrower.q / 0.1, 1e-10)
        assertTrue(PeqResponse.responseDb(PeqState(listOf(narrower)), 1300.0) <
            PeqResponse.responseDb(PeqState(listOf(original)), 1300.0))
        dragTo(current.findViewById(R.id.peq_frequency_slider),
            PeqBandAdapter.logarithmicPosition(2120.0, PeqModel.MIN_FREQUENCY, PeqModel.MAX_FREQUENCY))
        val changed = fragment().editorState.bands.single()
        assertEquals(2120.0, changed.frequency, 0.0)
        assertEquals(kotlin.math.round((changed.frequency - 20.0) / 100.0),
            (changed.frequency - 20.0) / 100.0, 1e-10)
        assertEquals(narrower.q, changed.q, 0.0)
    }

    @Test fun reusedRowsRestoreQualityModeAndBandwidthPlusAlwaysMovesTowardWiderFilters() {
        val bands = PeqModel.defaultState(170).bands.mapIndexed { index, band ->
            band.copy(q = 10.0, qualityDisplay = if (index == 0 || index == 169) PeqQualityDisplay.BW else PeqQualityDisplay.Q,
                type = if (index == 169) PeqFilterType.BAND_PASS else PeqFilterType.PEAK)
        }
        launch(PeqState(bands, showQSlider = true))
        assertTrue(row().findViewById<TextView>(R.id.peq_q_value).text.startsWith("BW"))
        assertTrue(row(80).findViewById<TextView>(R.id.peq_q_value).text.startsWith("Q"))
        val last = row(169)
        assertTrue(last.findViewById<TextView>(R.id.peq_q_value).text.startsWith("BW"))
        val before = PeqBandAdapter.displayedQuality(repository.load().bands.last())
        press(last, R.id.peq_q_plus)
        assertEquals(before + 0.01, PeqBandAdapter.displayedQuality(repository.load().bands.last()), 1e-12)
        assertEquals(bands.first(), repository.load().bands.first())
        assertTrue(row().findViewById<TextView>(R.id.peq_q_value).text.startsWith("BW"))
    }

    @Test fun bandwidthLimitsAndHugeStepsStayFiniteAndCanReachBothEnds() {
        PeqFilterType.entries.forEach { type ->
            val band = PeqBand(type = type, qualityDisplay = PeqQualityDisplay.BW)
            assertEquals(type in listOf(PeqFilterType.PEAK, PeqFilterType.NOTCH, PeqFilterType.BAND_PASS),
                PeqBandAdapter.usesBandwidth(band))
        }
        listOf(20.0, 1000.0, 20000.0).forEach { frequency ->
            val band = PeqBand(frequency = frequency, qualityDisplay = PeqQualityDisplay.BW)
            val bounds = PeqBandAdapter.qualityBounds(band)
            assertEquals(PeqModel.MAX_Q, PeqResponse.qFromBandwidth(bounds.start, frequency), 1e-11)
            assertEquals(PeqModel.MIN_Q, PeqResponse.qFromBandwidth(bounds.endInclusive, frequency), 1e-14)
            assertEquals(bounds.start, PeqBandAdapter.steppedLogarithmicValue(0f,
                bounds.start, bounds.endInclusive, 100.0), 0.0)
            assertEquals(bounds.endInclusive, PeqBandAdapter.steppedLogarithmicValue(1f,
                bounds.start, bounds.endInclusive, 100.0), 0.0)
        }
        val tooWide = PeqBand(q = PeqModel.MIN_Q, frequency = 20.0, qualityDisplay = PeqQualityDisplay.BW)
        val moved = PeqBandAdapter.withFrequency(tooWide, 20000.0)
        assertEquals(PeqModel.MIN_Q, moved.q, 0.0)
        assertEquals(moved, PeqModel.validateBand(moved))
        assertEquals(20000.0, PeqBandAdapter.steppedLogarithmicValue(1f, 20.0, 20000.0, 1000.0, 20.0), 0.0)
    }

    @Test fun gainStepLargerThanDisplayRangeCannotSaveAnOutOfRangeGain() {
        launch(PeqState(listOf(PeqBand()), gainRange = 1.0, gainStep = 2.0))
        val slider = row().findViewById<Slider>(R.id.peq_band_slider)
        pressRight(slider)
        assertEquals(1.0, fragment().editorState.bands.single().gain, 0.0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(1.0, repository.load().bands.single().gain, 0.0)
        listOf(1.0, 1.3, 7.0).forEach { range ->
            assertEquals(range, PeqBandAdapter.steppedGain(range.toFloat(), range, 2.0), 0.0)
            assertEquals(-range, PeqBandAdapter.steppedGain(-range.toFloat(), range, 2.0), 0.0)
            for (part in -100..100) {
                val saved = PeqBandAdapter.steppedGain((range * part / 100.0).toFloat(), range, 2.0)
                assertTrue(saved in -range..range)
            }
        }
    }

    @Test fun editingAnotherBandMovesTheMarkerImmediatelyAndSelectionSurvivesRotation() {
        val bands = listOf(PeqBand(frequency = 200.0), PeqBand(frequency = 1000.0), PeqBand(frequency = 5000.0))
        launch(PeqState(bands, frequencyStep = 125.0, showFrequencySlider = true))
        assertEquals(bands.first().id, drawMarker().bandId)
        val second = row(1)
        press(second, R.id.peq_band_plus)
        val afterGain = drawMarker()
        assertEquals(bands[1].id, afterGain.bandId)
        assertEquals(fragment().editorState, curve().requestedState)
        assertEquals(0.1, afterGain.gain, 0.0)
        assertEquals("+0.1 dB", afterGain.gainText)
        press(second, R.id.peq_frequency_plus)
        val afterFrequency = drawMarker()
        assertEquals(bands[1].id, afterFrequency.bandId)
        assertEquals(1125.0, afterFrequency.frequency, 0.0)
        assertEquals("1125 Hz", afterFrequency.frequencyText)
        assertTrue(afterFrequency.x > afterGain.x)
        assertEquals(fragment().editorState, curve().requestedState)
        assertEquals(fragment().editorState, repository.load())
        controller!!.recreate()
        layout()
        val restored = drawMarker()
        assertEquals(bands[1].id, restored.bandId)
        assertEquals(afterFrequency.frequency, restored.frequency, 0.0)
        assertEquals(afterFrequency.gain, restored.gain, 0.0)
    }

    @Test fun preciseFrequencyAndGainDraftUpdatesTheMarkerButCancelRestoresSavedParameters() {
        val original = PeqState(listOf(PeqBand(frequency = 200.0), PeqBand(frequency = 1000.0, gain = -3.0)))
        launch(original)
        press(row(1), R.id.peq_band_edit)
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("1234.5")
        dialog.findViewById<EditText>(R.id.peq_gain_input)!!.setText("7.25")
        val preview = requireNotNull(curve().requestedState).bands[1]
        assertEquals(1234.5, preview.frequency, 0.0)
        assertEquals(7.25, preview.gain, 0.0)
        val marker = drawMarker()
        assertEquals(original.bands[1].id, marker.bandId)
        assertEquals("1234.5 Hz", marker.frequencyText)
        assertEquals("+7.25 dB", marker.gainText)
        assertEquals(original, fragment().editorState)
        assertEquals(original, repository.load())
        val type = PeqFilterType.LOW_PASS
        val selector = dialog.findViewById<MaterialAutoCompleteTextView>(R.id.peq_band_type)!!
        selector.setText(PeqBandAdapter.typeLabel(application, type), false)
        selector.onItemClickListener!!.onItemClick(null, null, type.ordinal, type.ordinal.toLong())
        assertEquals(type, requireNotNull(curve().requestedState).bands[1].type)
        assertEquals(0.0, drawMarker().gain, 0.0)
        assertEquals(original, fragment().editorState)
        assertEquals(original, repository.load())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(original, curve().requestedState)
        assertEquals(original, repository.load())
        val restored = drawMarker()
        assertEquals(1000.0, restored.frequency, 0.0)
        assertEquals(-3.0, restored.gain, 0.0)
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = 0x6002 })
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(0x6002, PeqFragment(), "peq").commitNow()
        }
    }
}
