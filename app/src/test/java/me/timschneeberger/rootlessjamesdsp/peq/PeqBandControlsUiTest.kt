package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
        application.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
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
        state.bands.forEach { PeqBandLockStore(application).setBandLocked(it.id, false) }
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
    private fun press(row: View, id: Int) = row.findViewById<View>(id).performClick()
    private fun pressRight(slider: Slider) {
        assertTrue(slider.requestFocus())
        assertTrue(slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
    }
    private fun dragTo(slider: Slider, position: Float, whileDragging: () -> Unit = {}) {
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
        try { whileDragging() } finally { dispatch(MotionEvent.ACTION_UP, downTime + 40, targetX) }
    }
    private fun curve() = fragment().requireView().findViewById<PeqResponseView>(R.id.peq_curve)
    private fun drawMarkerOrNull(): PeqResponseView.BandMarker? {
        val plot = curve()
        if (plot.width <= 0 || plot.height <= 0) return null
        val bitmap = Bitmap.createBitmap(plot.width, plot.height, Bitmap.Config.ARGB_8888)
        plot.draw(Canvas(bitmap))
        bitmap.recycle()
        return plot.renderedMarker
    }
    private fun drawMarker(): PeqResponseView.BandMarker = requireNotNull(drawMarkerOrNull())

    @Test fun allFourSliderCombinationsShowEachParameterOnceAndTypeUsesItsOwnMenuCallback() {
        var state = PeqState(listOf(PeqBand("adaptive", type = PeqFilterType.HIGH_SHELF,
            frequency = 1234.567, q = 2.345678, gain = 6.25, qualityDisplay = PeqQualityDisplay.BW)))
        launch(state)
        val fields = mutableListOf<Pair<String, PeqEditField>>()
        val types = mutableListOf<String>()
        var fullEdits = 0
        var mutations = 0
        val adapter = PeqBandAdapter(onBandChanged = { mutations++; true }, onEdit = { fullEdits++ },
            onEditField = { id, field -> fields.add(id to field) }, onEditType = { types.add(it) })
        fragment().requireView().findViewById<RecyclerView>(R.id.peq_band_list).adapter = adapter
        val headerHeights = mutableMapOf<Pair<Boolean, Boolean>, Int>()
        for ((showQ, showFrequency) in listOf(false to false, true to false, false to true, true to true)) {
            state = state.copy(showQSlider = showQ, showFrequencySlider = showFrequency)
            adapter.submitState(state)
            val current = row()
            val title = current.findViewById<TextView>(R.id.peq_band_title)
            assertEquals(PeqBandAdapter.typeLabel(application, PeqFilterType.HIGH_SHELF), title.text.toString())
            assertEquals(if (showQ && showFrequency) View.GONE else View.VISIBLE,
                current.findViewById<View>(R.id.peq_band_details).visibility)
            assertEquals(if (showFrequency) View.GONE else View.VISIBLE,
                current.findViewById<View>(R.id.peq_band_header_frequency).visibility)
            assertEquals(if (showQ) View.GONE else View.VISIBLE,
                current.findViewById<View>(R.id.peq_band_summary).visibility)
            assertEquals(if (!showQ && !showFrequency) View.VISIBLE else View.GONE,
                current.findViewById<View>(R.id.peq_band_detail_separator).visibility)
            assertEquals(if (showQ) View.VISIBLE else View.GONE, current.findViewById<View>(R.id.peq_q_controls).visibility)
            assertEquals(if (showFrequency) View.VISIBLE else View.GONE,
                current.findViewById<View>(R.id.peq_frequency_controls).visibility)
            val quality = current.findViewById<TextView>(if (showQ) R.id.peq_q_value else R.id.peq_band_summary)
            assertTrue(quality.isShown)
            assertTrue(quality.text.startsWith("BW"))
            val frequency = current.findViewById<TextView>(if (showFrequency) R.id.peq_frequency_value else R.id.peq_band_header_frequency)
            assertTrue(frequency.isShown)
            assertTrue(frequency.text.contains("1234.567"))
            fields.clear()
            types.clear()
            title.performClick()
            assertEquals(listOf("adaptive"), types)
            assertTrue(fields.isEmpty())
            assertEquals(0, fullEdits)
            frequency.performClick()
            quality.performClick()
            current.findViewById<View>(R.id.peq_band_gain).performClick()
            assertEquals(listOf("adaptive" to PeqEditField.FREQUENCY, "adaptive" to PeqEditField.QUALITY,
                "adaptive" to PeqEditField.GAIN), fields)
            assertEquals(listOf("adaptive"), types)
            assertEquals(0, fullEdits)
            assertEquals(0, mutations)
            headerHeights[showQ to showFrequency] = current.findViewById<View>(R.id.peq_band_header).height
        }
        assertTrue("Hiding the second header row must reclaim height",
            headerHeights.getValue(true to true) < headerHeights.getValue(false to false))
        // Reusing the same holder after both hidden and visible states restores the missing values.
        state = state.copy(showQSlider = false, showFrequencySlider = false,
            bands = listOf(state.bands.single().copy(type = PeqFilterType.LOW_PASS, qualityDisplay = PeqQualityDisplay.Q)))
        adapter.submitState(state)
        val reused = row()
        assertEquals(PeqBandAdapter.typeLabel(application, PeqFilterType.LOW_PASS),
            reused.findViewById<TextView>(R.id.peq_band_title).text.toString())
        assertEquals(application.getString(R.string.peq_q_value, "2.346"),
            reused.findViewById<TextView>(R.id.peq_band_summary).text.toString())
        assertEquals(View.VISIBLE, reused.findViewById<View>(R.id.peq_band_header_frequency).visibility)
        assertEquals(0, mutations)
    }

    @Test fun onlyGainSliderChangesRedBlueAndRestoresThemeAtZeroForButtonsAndDragging() {
        launch(PeqState(listOf(PeqBand()), showQSlider = true, showFrequencySlider = true))
        val current = row()
        val gain = current.findViewById<Slider>(R.id.peq_band_slider)
        val q = current.findViewById<Slider>(R.id.peq_q_slider)
        val frequency = current.findViewById<Slider>(R.id.peq_frequency_slider)
        val defaultThumb = gain.thumbTintList
        val defaultTrack = gain.trackActiveTintList
        val originalQ = q.thumbTintList
        val originalFrequency = frequency.thumbTintList
        fun assertColor(positive: Boolean) {
            val color = gain.thumbTintList.defaultColor
            assertEquals(color, gain.trackActiveTintList.defaultColor)
            assertTrue(if (positive) Color.red(color) > Color.blue(color) else Color.blue(color) > Color.red(color))
            assertEquals(originalQ, q.thumbTintList)
            assertEquals(originalFrequency, frequency.thumbTintList)
        }
        press(current, R.id.peq_band_plus)
        assertColor(true)
        press(current, R.id.peq_band_minus)
        assertEquals(defaultThumb, gain.thumbTintList)
        assertEquals(defaultTrack, gain.trackActiveTintList)
        press(current, R.id.peq_band_minus)
        assertColor(false)
        press(current, R.id.peq_band_plus)
        assertEquals(defaultThumb, gain.thumbTintList)
        dragTo(gain, 0.7f)
        assertColor(true)
        dragTo(gain, 0.3f)
        assertColor(false)
        dragTo(gain, 0.5f)
        assertEquals(0.0, repository.load().bands.single().gain, 0.0)
        assertEquals(defaultThumb, gain.thumbTintList)
        assertEquals(defaultTrack, gain.trackActiveTintList)
    }

    @Test fun choosingBandwidthPersistsBothModeAndTheActualRowSliderWithoutChangingQ() {
        val original = PeqBand(q = 2.345678901, frequency = 7000.0, gain = 6.0)
        launch(PeqState(listOf(original), qStep = 0.01, qRange = PeqModel.MAX_Q, showQSlider = true, showFrequencySlider = true))
        press(row(), R.id.peq_band_number)
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
        launch(PeqState(listOf(original), qRange = PeqModel.MAX_Q, qStep = 100.0, frequencyStep = 1000.0,
            showQSlider = true, showFrequencySlider = true))
        val current = row()
        val slider = current.findViewById<Slider>(R.id.peq_q_slider)
        val originalBounds = PeqBandAdapter.qualityBounds(original)
        dragTo(slider, PeqBandAdapter.logarithmicPosition(1.253, originalBounds.start, originalBounds.endInclusive))
        val wider = fragment().editorState.bands.single()
        assertEquals(1.253, PeqBandAdapter.displayedQuality(wider), 1e-12)
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
        dragTo(slider, PeqBandAdapter.logarithmicPosition(2.007, bounds.start, bounds.endInclusive))
        assertEquals(2.007, PeqBandAdapter.displayedQuality(fragment().editorState.bands.single()), 1e-12)
        assertTrue(current.findViewById<TextView>(R.id.peq_q_value).text.endsWith("2.007"))
        assertFalse(current.findViewById<TextView>(R.id.peq_band_summary).text.contains("倍频程"))
        assertTrue(PeqBandAdapter.displayedQuality(fragment().editorState.bands.single()) > width)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(fragment().editorState, repository.load())
    }

    @Test fun largeButtonStepsStillAllowFineDraggingAndBindingKeepsStoredPrecision() {
        val original = PeqBand(q = 2.033456789, frequency = 777.123456, gain = 12.000123)
        launch(PeqState(listOf(original), qRange = PeqModel.MAX_Q, gainStep = 2.0, qStep = 100.0, frequencyStep = 1000.0,
            showQSlider = true, showFrequencySlider = true))
        assertEquals(original, repository.load().bands.single())
        val current = row()
        assertTrue(current.findViewById<TextView>(R.id.peq_q_value).text.endsWith("2.033"))
        assertEquals("+12.00 dB", current.findViewById<TextView>(R.id.peq_band_gain).text.toString())
        dragTo(current.findViewById(R.id.peq_q_slider),
            PeqBandAdapter.logarithmicPosition(4.321, PeqModel.MIN_Q, PeqModel.MAX_Q))
        val narrower = fragment().editorState.bands.single()
        assertEquals(4.321, narrower.q, 1e-12)
        assertTrue(narrower.q > original.q)
        assertTrue(current.findViewById<TextView>(R.id.peq_q_value).text.endsWith("4.321"))
        assertTrue(PeqResponse.responseDb(PeqState(listOf(narrower)), 1300.0) <
            PeqResponse.responseDb(PeqState(listOf(original)), 1300.0))
        dragTo(current.findViewById(R.id.peq_frequency_slider),
            PeqBandAdapter.logarithmicPosition(2123.0, PeqModel.MIN_FREQUENCY, PeqModel.MAX_FREQUENCY))
        val changed = fragment().editorState.bands.single()
        assertEquals(2123.0, changed.frequency, 0.0)
        assertEquals(narrower.q, changed.q, 0.0)
        dragTo(current.findViewById(R.id.peq_band_slider), ((0.37 + 18.0) / 36.0).toFloat())
        assertEquals(0.37, repository.load().bands.single().gain, 0.0)
        assertEquals("+0.37 dB", current.findViewById<TextView>(R.id.peq_band_gain).text.toString())
        // Button steps remain independent from drag precision.
        press(current, R.id.peq_band_plus)
        assertEquals(2.37, repository.load().bands.single().gain, 1e-12)
        press(current, R.id.peq_frequency_plus)
        assertEquals(3123.0, repository.load().bands.single().frequency, 0.0)
        press(current, R.id.peq_q_plus)
        assertEquals(PeqModel.MAX_Q, repository.load().bands.single().q, 0.0)
    }

    @Test fun reusedRowsRestoreQualityModeAndBandwidthPlusAlwaysMovesTowardWiderFilters() {
        val bands = PeqModel.defaultState(170).bands.mapIndexed { index, band ->
            band.copy(q = 10.0, qualityDisplay = if (index == 0 || index == 169) PeqQualityDisplay.BW else PeqQualityDisplay.Q,
                type = if (index == 169) PeqFilterType.BAND_PASS else PeqFilterType.PEAK)
        }
        launch(PeqState(bands, qStep = 0.01, showQSlider = true))
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
            assertTrue(PeqBandAdapter.usesBandwidth(band))
        }
        listOf(20.0, 1000.0, 20000.0).forEach { frequency ->
            val band = PeqBand(frequency = frequency, qualityDisplay = PeqQualityDisplay.BW)
            val bounds = PeqBandAdapter.qualityBounds(band)
            assertEquals(PeqModel.MAX_Q, PeqResponse.qFromBandwidth(bounds.start, frequency), 1e-11)
            assertEquals(PeqModel.MIN_Q, PeqResponse.qFromBandwidth(bounds.endInclusive, frequency), 1e-14)
            assertEquals(bounds.start, PeqBandAdapter.dragQuality(0f,
                bounds.start, bounds.endInclusive), 0.0)
            assertEquals(bounds.endInclusive, PeqBandAdapter.dragQuality(1f,
                bounds.start, bounds.endInclusive), 0.0)
        }
        val tooWide = PeqBand(q = PeqModel.MIN_Q, frequency = 20.0, qualityDisplay = PeqQualityDisplay.BW)
        val moved = PeqBandAdapter.withFrequency(tooWide, 20000.0)
        assertEquals(PeqModel.MIN_Q, moved.q, 0.0)
        assertEquals(moved, PeqModel.validateBand(moved))
        assertEquals(20000.0, PeqBandAdapter.dragFrequency(1f), 0.0)
        assertEquals(20.0, PeqBandAdapter.dragFrequency(0f), 0.0)
        assertEquals("1000", PeqBandAdapter.number(1000.0, 0))
    }

    @Test fun configuredQualityRangePreservesStoredValuesUntilEditedAndConstrainsQAndBandwidth() {
        val original = PeqState(listOf(PeqBand("q", q = 20.123456789),
            PeqBand("bw", type = PeqFilterType.LOW_SHELF, q = 20.123456789, qualityDisplay = PeqQualityDisplay.BW)),
            showQSlider = true, showFrequencySlider = true, qRange = 3.25, qStep = 0.01)
        launch(original)
        val qRow = row(0)
        assertEquals(1f, qRow.findViewById<Slider>(R.id.peq_q_slider).value, 0f)
        assertTrue(qRow.findViewById<TextView>(R.id.peq_q_value).text.endsWith("20.123"))
        val bwRow = row(1)
        assertEquals(0f, bwRow.findViewById<Slider>(R.id.peq_q_slider).value, 0f)
        assertTrue(bwRow.findViewById<TextView>(R.id.peq_q_value).text.startsWith("BW"))
        assertEquals(original, repository.load())
        press(row(0), R.id.peq_q_minus)
        assertEquals(3.25, repository.load().bands[0].q, 0.0)
        press(row(1), R.id.peq_q_plus)
        assertEquals(3.25, repository.load().bands[1].q, 0.0)
        dragTo(row(0).findViewById(R.id.peq_q_slider), 0f)
        assertEquals(0.1, repository.load().bands[0].q, 0.0)
        dragTo(row(0).findViewById(R.id.peq_q_slider), 1f)
        assertEquals(3.25, repository.load().bands[0].q, 0.0)
        dragTo(row(1).findViewById(R.id.peq_q_slider), 1f)
        assertEquals(0.1, repository.load().bands[1].q, 0.0)
        dragTo(row(1).findViewById(R.id.peq_q_slider), 0f)
        assertEquals(3.25, repository.load().bands[1].q, 0.0)
    }

    @Test fun everyFilterSupportsBandwidthWithExactConfiguredBoundsAndSingleValueRangesStayFinite() {
        PeqFilterType.entries.forEach { type ->
            for (frequency in listOf(0.0, 20.0, 1000.0, 20000.0)) {
                for (qRange in listOf(0.1, 0.5, 3.25, 100.0)) {
                    val band = PeqBand(type = type, frequency = frequency, q = 20.123456789, qualityDisplay = PeqQualityDisplay.BW)
                    val bounds = PeqBandAdapter.qualityBounds(band, qRange)
                    assertEquals(qRange, PeqResponse.qFromBandwidth(bounds.start, frequency), 1e-11)
                    assertEquals(0.1, PeqResponse.qFromBandwidth(bounds.endInclusive, frequency), 1e-14)
                    assertEquals(qRange, PeqBandAdapter.withQuality(band, bounds.start, qRange).q, 0.0)
                    assertEquals(0.1, PeqBandAdapter.withQuality(band, bounds.endInclusive, qRange).q, 0.0)
                    assertTrue(PeqBandAdapter.logarithmicPosition(PeqBandAdapter.displayedQuality(band),
                        bounds.start, bounds.endInclusive).isFinite())
                    assertTrue(PeqBandAdapter.withFrequency(band, if (frequency == 20000.0) 20.0 else 20000.0,
                        qRange).q in 0.1..qRange)
                }
            }
        }
        launch(PeqState(listOf(PeqBand("q", q = 5.123),
            PeqBand("bw", q = 5.123, qualityDisplay = PeqQualityDisplay.BW)), qRange = 0.1, showQSlider = true))
        for (index in 0..1) {
            val current = row(index)
            val slider = current.findViewById<Slider>(R.id.peq_q_slider)
            assertEquals(0f, slider.value, 0f)
            assertFalse(slider.isEnabled)
            assertEquals(5.123, repository.load().bands[index].q, 0.0)
            press(current, if (index == 0) R.id.peq_q_minus else R.id.peq_q_plus)
            assertEquals(0.1, repository.load().bands[index].q, 0.0)
            assertFalse(current.findViewById<MaterialButton>(R.id.peq_q_minus).isEnabled)
            assertFalse(current.findViewById<MaterialButton>(R.id.peq_q_plus).isEnabled)
        }
    }

    @Test fun gainStepLargerThanDisplayRangeCannotSaveAnOutOfRangeGain() {
        launch(PeqState(listOf(PeqBand()), gainRange = 1.0, gainStep = 2.0))
        val slider = row().findViewById<Slider>(R.id.peq_band_slider)
        pressRight(slider)
        assertEquals(1.0, fragment().editorState.bands.single().gain, 0.0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(1.0, repository.load().bands.single().gain, 0.0)
        listOf(1.0, 1.3, 7.0).forEach { range ->
            assertEquals(range, PeqBandAdapter.dragGain(range.toFloat(), range), 0.0)
            assertEquals(-range, PeqBandAdapter.dragGain(-range.toFloat(), range), 0.0)
            for (part in -100..100) {
                val saved = PeqBandAdapter.dragGain((range * part / 100.0).toFloat(), range)
                assertTrue(saved in -range..range)
            }
        }
    }

    @Test fun firstGainButtonEditBringsPreservedOutOfRangeValuesInsideTheCurrentRange() {
        val original = PeqState(listOf(PeqBand(gain = 25.123456), PeqBand(gain = -25.123456)),
            gainRange = 6.0, gainStep = 0.25)
        launch(original)
        assertEquals(original, repository.load())
        press(row(0), R.id.peq_band_minus)
        assertEquals(6.0, repository.load().bands[0].gain, 0.0)
        assertEquals(original.bands[1], repository.load().bands[1])
        press(row(1), R.id.peq_band_plus)
        assertEquals(-6.0, repository.load().bands[1].gain, 0.0)
    }

    @Test fun cancellingAnySliderEndsTrackingAndHidesTheMarkerAfterTwoSeconds() {
        launch(PeqState(listOf(PeqBand(q = 2.0, frequency = 1000.0, gain = 1.0)),
            showQSlider = true, showFrequencySlider = true))
        val current = row()
        listOf(R.id.peq_band_slider, R.id.peq_q_slider, R.id.peq_frequency_slider).forEach { id ->
            val slider = current.findViewById<Slider>(id)
            val original = (slider.value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)
            val downTime = SystemClock.uptimeMillis()
            val target = slider.trackSidePadding + 0.8f * slider.trackWidth
            fun dispatch(action: Int, x: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    x, slider.height / 2f, 0)
                try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            dispatch(MotionEvent.ACTION_DOWN, slider.trackSidePadding + original * slider.trackWidth)
            dispatch(MotionEvent.ACTION_MOVE, target)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2100))
            assertNotNull("A held slider must keep its marker visible", drawMarkerOrNull())
            dispatch(MotionEvent.ACTION_CANCEL, target)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1990))
            assertNotNull(drawMarkerOrNull())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            assertNull("A cancelled gesture must not leave tracking enabled", drawMarkerOrNull())
            assertEquals(fragment().editorState, repository.load())
        }
    }

    @Test fun everyFilterTypeHasWorkingGainButtonsAndFineDragging() {
        val original = PeqState(PeqFilterType.entries.map { PeqBand(type = it, gain = 2.0) }, gainStep = 0.25)
        launch(original)
        PeqFilterType.entries.forEachIndexed { index, type ->
            val current = row(index)
            assertEquals(View.VISIBLE, current.findViewById<View>(R.id.peq_gain_controls).visibility)
            press(current, R.id.peq_band_plus)
            assertEquals(type, repository.load().bands[index].type)
            assertEquals(2.25, repository.load().bands[index].gain, 0.0)
            dragTo(current.findViewById(R.id.peq_band_slider), ((0.37 + 18.0) / 36.0).toFloat())
            assertEquals(0.37, repository.load().bands[index].gain, 0.0)
            assertEquals("+0.37 dB", current.findViewById<TextView>(R.id.peq_band_gain).text.toString())
        }
    }

    @Test fun locatorHasItsOwnCallbackAndDraggingSignalsStartAndFinish() {
        var state = PeqState(listOf(PeqBand(q = 2.0)), qRange = PeqModel.MAX_Q, showQSlider = true, showFrequencySlider = true)
        launch(state)
        val located = mutableListOf<String>()
        val selected = mutableListOf<String>()
        val started = mutableListOf<String>()
        val editFields = mutableListOf<Pair<String, PeqEditField>>()
        var finished = 0
        var edited = 0
        lateinit var adapter: PeqBandAdapter
        adapter = PeqBandAdapter(onBandChanged = { band ->
            state = state.copy(bands = listOf(band))
            adapter.submitState(state, band.id)
            true
        }, onEdit = { edited++ }, onBandSelected = { selected.add(it) },
            onLocate = { located.add(it) }, onInteractionStarted = { started.add(it) },
            onInteractionFinished = { finished++ }, onEditField = { id, field -> editFields.add(id to field) })
        val list = fragment().requireView().findViewById<RecyclerView>(R.id.peq_band_list)
        list.adapter = adapter
        adapter.submitState(state)
        val current = row()
        val button = current.findViewById<MaterialButton>(R.id.peq_band_locate)
        assertEquals("定位频段", button.contentDescription.toString())
        assertTrue(button.text.isEmpty())
        assertNotNull(button.icon)
        assertSame(current.findViewById<View>(R.id.peq_band_header), button.parent)
        button.performClick()
        assertEquals(listOf(state.bands.single().id), located)
        assertTrue(selected.isEmpty())
        assertEquals(0, edited)
        assertEquals(0, finished)
        assertTrue(started.isEmpty())
        dragTo(current.findViewById(R.id.peq_q_slider),
            PeqBandAdapter.logarithmicPosition(4.321, PeqModel.MIN_Q, PeqModel.MAX_Q)) {
            assertTrue(started.isNotEmpty())
            assertTrue(started.all { it == state.bands.single().id })
            assertEquals(0, finished)
        }
        assertEquals(1, finished)
        assertEquals(4.321, state.bands.single().q, 1e-12)
        assertTrue(selected.isNotEmpty())
        assertEquals(listOf(state.bands.single().id), located)
        assertEquals(0, edited)
        val fields = listOf(R.id.peq_band_gain to PeqEditField.GAIN,
            R.id.peq_q_value to PeqEditField.QUALITY, R.id.peq_frequency_value to PeqEditField.FREQUENCY)
        fields.forEach { (id, _) -> current.findViewById<View>(id).performClick() }
        assertEquals(fields.map { state.bands.single().id to it.second }, editFields)
        assertEquals(0, edited)
        press(current, R.id.peq_band_number)
        assertEquals(1, edited)
        state = state.copy(qRange = 0.5)
        adapter.submitState(state)
        val rebound = row()
        assertEquals(4.321, state.bands.single().q, 1e-12)
        assertEquals(1f, rebound.findViewById<Slider>(R.id.peq_q_slider).value, 0f)
        assertTrue(rebound.findViewById<TextView>(R.id.peq_q_value).text.endsWith("4.321"))
    }

    @Test fun editingAnotherBandMovesTheMarkerAndLocatorRestoresVisibilityAfterRotation() {
        val bands = listOf(PeqBand(frequency = 200.0), PeqBand(frequency = 1000.0), PeqBand(frequency = 5000.0))
        launch(PeqState(bands, gainStep = 0.1, frequencyStep = 125.0, showFrequencySlider = true))
        assertNull(drawMarkerOrNull())
        val second = row(1)
        press(second, R.id.peq_band_plus)
        val afterGain = drawMarker()
        assertEquals(bands[1].id, afterGain.bandId)
        assertEquals(fragment().editorState, curve().requestedState)
        assertEquals(0.1, afterGain.gain, 0.0)
        assertEquals("+0.10 dB", afterGain.gainText)
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
        assertNull(drawMarkerOrNull())
        press(row(1), R.id.peq_band_locate)
        layout()
        val restored = drawMarker()
        assertEquals(bands[1].id, restored.bandId)
        assertEquals(afterFrequency.frequency, restored.frequency, 0.0)
        assertEquals(afterFrequency.gain, restored.gain, 0.0)
    }

    @Test fun preciseFrequencyAndGainDraftUpdatesTheMarkerButCancelRestoresSavedParameters() {
        val original = PeqState(listOf(PeqBand(frequency = 200.0), PeqBand(frequency = 1000.0, gain = -3.0)))
        launch(original)
        press(row(1), R.id.peq_band_number)
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
        assertEquals(7.25, drawMarker().gain, 0.0)
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.peq_gain_box)!!.visibility)
        assertEquals(original, fragment().editorState)
        assertEquals(original, repository.load())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(original, curve().requestedState)
        assertEquals(original, repository.load())
        assertNull(drawMarkerOrNull())
        press(row(1), R.id.peq_band_locate)
        layout()
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
