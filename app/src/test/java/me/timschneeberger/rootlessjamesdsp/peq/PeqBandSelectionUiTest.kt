package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqBandSelectionUiTest {
    private var controller: ActivityController<HostActivity>? = null
    private lateinit var adapter: PeqBandAdapter
    private lateinit var state: PeqState
    private val selectedClicks = mutableListOf<String>()
    private val edits = mutableListOf<String>()
    private val types = mutableListOf<String>()
    private val locations = mutableListOf<String>()
    private val fields = mutableListOf<PeqEditField>()
    private val changes = mutableListOf<PeqBand>()
    private val list get() = controller!!.get().list

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun launch(initial: PeqState) {
        state = initial
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        adapter = PeqBandAdapter(onBandChanged = { band ->
            changes += band
            state = state.copy(bands = state.bands.map { if (it.id == band.id) band else it })
            adapter.submitState(state, band.id)
            true
        }, onEdit = { edits += it }, onLocate = { locations += it },
            onEditField = { _, field -> fields += field }, onEditType = { types += it },
            onToggleSelection = { selectedClicks += it })
        list.adapter = adapter
        adapter.submitState(state)
        layout()
    }

    private fun layout(widthDp: Int = 411) {
        shadowOf(Looper.getMainLooper()).idle()
        val density = list.resources.displayMetrics.density
        val root = controller!!.get().findViewById<View>(android.R.id.content)
        val width = (widthDp * density).toInt()
        val height = (700 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun row(index: Int = 0): MaterialCardView {
        list.scrollToPosition(index)
        layout()
        return list.findViewHolderForAdapterPosition(index)!!.itemView as MaterialCardView
    }

    private fun bounds(card: MaterialCardView, id: Int): Rect = Rect().also { rectangle ->
        val child = card.findViewById<View>(id)
        child.getDrawingRect(rectangle)
        card.offsetDescendantRectToMyCoords(child, rectangle)
    }

    private fun tap(card: MaterialCardView, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(now, now + index * 20, action, x, y, 0)
            try { card.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tap(card: MaterialCardView, id: Int) {
        val rectangle = bounds(card, id)
        assertFalse("Control must have a hit area: $id", rectangle.isEmpty)
        tap(card, rectangle.exactCenterX(), rectangle.exactCenterY())
    }

    @Test fun locatorStaysAtCardCenterAndWarningHasAFixedSlotAcrossValuesAndWidths() {
        launch(PeqState(listOf(PeqBand("center")), showQSlider = true, showFrequencySlider = true))
        for (width in listOf(320, 411, 600)) {
            var stableGainBounds: Rect? = null
            var stableWarningBounds: Rect? = null
            for ((frequency, gain) in listOf(0.0 to 0.0, 1000.0 to 100.0, 0.0 to -100.0, 20000.0 to 0.1)) {
                state = state.copy(bands = listOf(state.bands.single().copy(frequency = frequency, gain = gain)))
                adapter.submitState(state)
                layout(width)
                val card = list.findViewHolderForAdapterPosition(0)!!.itemView as MaterialCardView
                val locator = bounds(card, R.id.peq_band_locate)
                assertEquals("Locator center at width $width, $frequency Hz, $gain dB",
                    card.width / 2f, locator.exactCenterX(), 1f)
                val warning = bounds(card, R.id.peq_band_warning)
                val gainBounds = bounds(card, R.id.peq_band_gain)
                assertEquals(if (frequency == 0.0) View.VISIBLE else View.INVISIBLE,
                    card.findViewById<View>(R.id.peq_band_warning).visibility)
                assertEquals(locator.right, warning.left)
                assertTrue(warning.width() > 0)
                if (stableWarningBounds != null) assertEquals(stableWarningBounds, warning)
                if (stableGainBounds != null) assertEquals(stableGainBounds, gainBounds)
                stableWarningBounds = warning
                stableGainBounds = gainBounds
                assertTrue(gainBounds.width() > 0)
            }
        }
    }

    @Test fun selectionPayloadPreservesControlsAndRecycledCardsRestoreTheirBackground() {
        launch(PeqModel.defaultState(170).copy(showQSlider = true))
        val first = row()
        val normalBackground = first.cardBackgroundColor.defaultColor
        val normalStroke = first.strokeColorStateList?.defaultColor
        val normalWidth = first.strokeWidth
        val slider = first.findViewById<Slider>(R.id.peq_band_slider)
        var fullChanges = 0
        val changedPositions = mutableListOf<Int>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { fullChanges++ }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                assertNotNull(payload)
                changedPositions += positionStart
            }
        })
        adapter.submitSelection(true, setOf(state.bands.first().id))
        layout()
        assertSame(first, row())
        assertSame(slider, first.findViewById<Slider>(R.id.peq_band_slider))
        assertTrue(first.isSelected)
        assertNotEquals(normalBackground, first.cardBackgroundColor.defaultColor)
        assertTrue(first.strokeWidth > normalWidth)
        assertEquals(0, fullChanges)
        changedPositions.clear()
        adapter.submitSelection(true, setOf(state.bands.first().id, state.bands.last().id))
        assertEquals(listOf(169), changedPositions)
        assertTrue(row(169).isSelected)
        assertFalse(row(168).isSelected)
        assertEquals(normalBackground, row(168).cardBackgroundColor.defaultColor)
        assertEquals(normalStroke, row(168).strokeColorStateList?.defaultColor)
        assertEquals(normalWidth, row(168).strokeWidth)
        assertTrue(row(0).isSelected)
        adapter.submitSelection(false, setOf(state.bands.first().id))
        val restored = row(0)
        assertFalse(restored.isSelected)
        assertEquals(normalBackground, restored.cardBackgroundColor.defaultColor)
        assertEquals(normalStroke, restored.strokeColorStateList?.defaultColor)
        assertEquals(normalWidth, restored.strokeWidth)
        assertFalse(restored.isClickable)
        assertTrue(changes.isEmpty())
    }

    @Test fun onlyBlankCardSpaceSelectsAndControlsKeepTheirOriginalActions() {
        launch(PeqState(listOf(PeqBand("clicks", frequency = 0.0)),
            showQSlider = true, showFrequencySlider = true, qRange = PeqModel.MIN_Q))
        val card = row()
        card.performClick()
        assertTrue(selectedClicks.isEmpty())
        adapter.submitSelection(true, emptySet())
        layout()
        // The outer card padding is blank, below every child control.
        tap(card, card.width / 2f, card.height - 1f)
        assertEquals(listOf("clicks"), selectedClicks)
        selectedClicks.clear()
        tap(card, R.id.peq_band_title)
        tap(card, R.id.peq_band_gain)
        tap(card, R.id.peq_q_value)
        tap(card, R.id.peq_frequency_value)
        tap(card, R.id.peq_band_locate)
        tap(card, R.id.peq_band_warning)
        tap(card, R.id.peq_band_edit)
        assertEquals(listOf("clicks"), types)
        assertEquals(listOf(PeqEditField.GAIN, PeqEditField.QUALITY, PeqEditField.FREQUENCY), fields)
        assertEquals(listOf("clicks"), locations)
        assertEquals(listOf("clicks"), edits)
        assertTrue(selectedClicks.isEmpty())
        tap(card, R.id.peq_band_plus)
        assertTrue(changes.isNotEmpty())
        tap(card, R.id.peq_band_slider)
        tap(card, R.id.peq_frequency_slider)
        assertTrue(selectedClicks.isEmpty())
        // A disabled quality slider/button still belongs to the control, not blank card space.
        assertFalse(card.findViewById<Slider>(R.id.peq_q_slider).isEnabled)
        tap(card, R.id.peq_q_slider)
        tap(card, R.id.peq_q_plus)
        assertTrue(selectedClicks.isEmpty())
        adapter.submitSelection(false, emptySet())
        layout()
        card.performClick()
        assertTrue(selectedClicks.isEmpty())
        card.findViewById<View>(R.id.peq_band_header).performClick()
        assertEquals(listOf("clicks", "clicks"), edits)
    }

    class HostActivity : FragmentActivity() {
        lateinit var list: RecyclerView
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            list = RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@HostActivity)
                itemAnimator = null
            }
            setContentView(list)
        }
    }
}
