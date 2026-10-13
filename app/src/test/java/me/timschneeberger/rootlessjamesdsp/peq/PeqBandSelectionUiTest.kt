package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
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
import java.time.Duration

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
    private val deleted = mutableListOf<String>()
    private val draggedHolders = mutableListOf<RecyclerView.ViewHolder>()
    private val list get() = controller!!.get().list

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun launch(initial: PeqState, unlock: Boolean = true) {
        state = initial
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        controller!!.get().getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        if (unlock) initial.bands.forEach { PeqBandLockStore(controller!!.get()).setBandLocked(it.id, false) }
        adapter = PeqBandAdapter(onBandChanged = { band ->
            changes += band
            state = state.copy(bands = state.bands.map { if (it.id == band.id) band else it })
            adapter.submitState(state, band.id)
            true
        }, onEdit = { edits += it }, onLocate = { locations += it },
            onEditField = { _, field -> fields += field }, onEditType = { types += it },
            onToggleSelection = { selectedClicks += it }, onStartDrag = { draggedHolders += it },
            onDelete = { deleted += it })
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

    @Test fun nineEqualHeaderSlotsStayFixedWhenWarningAndNewBadgeVisibilityChanges() {
        launch(PeqState(listOf(PeqBand("center")), showQSlider = true, showFrequencySlider = true))
        val slots = listOf(R.id.peq_band_number, R.id.peq_band_delete, R.id.peq_band_title, R.id.peq_band_warning,
            R.id.peq_band_power, R.id.peq_band_drag_handle, R.id.peq_band_locate, R.id.peq_band_new, R.id.peq_band_lock)
        val locks = PeqBandLockStore(controller!!.get())
        for (width in listOf(320, 411, 600)) {
            var stableBounds: List<Rect>? = null
            for ((frequency, gain) in listOf(0.0 to 0.0, 1000.0 to 100.0, 0.0 to -100.0, 20000.0 to 0.1)) {
                if (frequency == 0.0) locks.markNew("center") else locks.markUsed("center")
                state = state.copy(bands = listOf(state.bands.single().copy(frequency = frequency, gain = gain)))
                adapter.submitState(state)
                layout(width)
                val card = list.findViewHolderForAdapterPosition(0)!!.itemView as MaterialCardView
                val header = bounds(card, R.id.peq_band_header)
                val rectangles = slots.map { bounds(card, it) }
                assertTrue("Nine slot widths differ by at most a rounding pixel at $width dp",
                    rectangles.maxOf { it.width() } - rectangles.minOf { it.width() } <= 1)
                rectangles.forEachIndexed { index, rectangle ->
                    assertEquals("Slot ${index + 1} at width $width", header.left + header.width() * (index + 0.5f) / 9f,
                        rectangle.exactCenterX(), 1f)
                    assertEquals(header.top, rectangle.top)
                    assertEquals(header.bottom, rectangle.bottom)
                }
                rectangles.zipWithNext().forEach { (left, right) -> assertEquals(left.right, right.left) }
                if (stableBounds != null) assertEquals("Hidden badges must reserve their complete slots", stableBounds, rectangles)
                stableBounds = rectangles
                assertEquals(card.width / 2f, bounds(card, R.id.peq_band_power).exactCenterX(), 1f)
                assertEquals(0, rectangles.first().left)
                assertEquals(0, rectangles.first().top)
                assertNotNull(card.findViewById<View>(R.id.peq_band_number).background)
                val divider = bounds(card, R.id.peq_band_divider)
                assertEquals(0, divider.left)
                assertEquals(card.width, divider.right)
                assertEquals(header.bottom, divider.top)
                assertEquals(if (frequency == 0.0) View.VISIBLE else View.INVISIBLE,
                    card.findViewById<View>(R.id.peq_band_warning).visibility)
                assertEquals(if (frequency == 0.0) View.VISIBLE else View.INVISIBLE,
                    card.findViewById<View>(R.id.peq_band_new).visibility)
                assertEquals(Color.rgb(211, 47, 47), card.findViewById<MaterialButton>(R.id.peq_band_delete).iconTint!!.defaultColor)
            }
        }
    }

    @Test fun compactRowsKeepLabelsBeforeMinusAndNumericBoxesBetweenSliderAndPlus() {
        launch(PeqState(listOf(PeqBand("compact", qualityDisplay = PeqQualityDisplay.BW))))
        val rows = listOf(
            listOf(R.id.peq_gain_label, R.id.peq_band_minus, R.id.peq_band_slider, R.id.peq_band_gain, R.id.peq_band_plus, R.id.peq_gain_lock),
            listOf(R.id.peq_q_label, R.id.peq_q_minus, R.id.peq_q_slider, R.id.peq_q_value, R.id.peq_q_plus, R.id.peq_q_lock),
            listOf(R.id.peq_frequency_label, R.id.peq_frequency_minus, R.id.peq_frequency_slider, R.id.peq_frequency_value, R.id.peq_frequency_plus, R.id.peq_frequency_lock)
        )
        for (width in listOf(320, 411)) {
            layout(width)
            val card = list.findViewHolderForAdapterPosition(0)!!.itemView as MaterialCardView
            val density = card.resources.displayMetrics.density
            assertTrue("Three rows and header stay compact at width $width", card.height / density in 180f..205f)
            rows.forEach { ids ->
                val rectangles = ids.map { bounds(card, it) }
                rectangles.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
                rectangles.forEach { assertEquals(rectangles.first().exactCenterY(), it.exactCenterY(), 1f) }
                assertNotNull(card.findViewById<View>(ids[3]).background)
                assertTrue("Compact layout must keep a usable slider track", card.findViewById<Slider>(ids[2]).trackWidth / density >= 50f)
            }
            assertEquals("BW 值", card.findViewById<TextView>(R.id.peq_q_label).text.toString())
            assertFalse(card.findViewById<TextView>(R.id.peq_q_value).text.contains("BW"))
        }
        val card = row()
        tap(card, R.id.peq_band_gain)
        tap(card, R.id.peq_q_value)
        tap(card, R.id.peq_frequency_value)
        assertEquals(listOf(PeqEditField.GAIN, PeqEditField.QUALITY, PeqEditField.FREQUENCY), fields)
        assertTrue(changes.isEmpty())
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

    @Test fun editingMakesEveryCardAreaSelectWithoutRunningAnyControlAction() {
        launch(PeqState(listOf(PeqBand("clicks", frequency = 0.0)),
            showQSlider = true, showFrequencySlider = true, qRange = PeqModel.MIN_Q))
        val card = row()
        val original = state
        card.performClick()
        assertTrue(selectedClicks.isEmpty())
        adapter.submitSelection(true, emptySet())
        layout()
        tap(card, card.width / 2f, card.height - 1f)
        val controls = listOf(R.id.peq_band_number, R.id.peq_band_delete, R.id.peq_band_title, R.id.peq_band_gain,
            R.id.peq_q_value, R.id.peq_frequency_value, R.id.peq_band_locate, R.id.peq_band_warning,
            R.id.peq_band_lock, R.id.peq_band_power, R.id.peq_gain_lock, R.id.peq_q_lock,
            R.id.peq_frequency_lock, R.id.peq_band_plus, R.id.peq_band_slider, R.id.peq_frequency_slider,
            R.id.peq_q_slider, R.id.peq_q_plus, R.id.peq_band_drag_handle)
        controls.forEach { id ->
            val before = selectedClicks.size
            tap(card, id)
            assertEquals("A tap on $id selects the card", before + 1, selectedClicks.size)
        }
        assertEquals(List(controls.size + 1) { "clicks" }, selectedClicks)
        assertTrue(types.isEmpty())
        assertTrue(fields.isEmpty())
        assertTrue(locations.isEmpty())
        assertTrue(edits.isEmpty())
        assertTrue(changes.isEmpty())
        assertTrue(deleted.isEmpty())
        assertEquals(original, state)
        val locks = PeqBandLockStore(controller!!.get())
        assertFalse(locks.isBandLocked("clicks"))
        assertFalse(locks.isFieldLocked("clicks", PeqEditField.GAIN))
        adapter.submitSelection(false, emptySet())
        layout()
        val count = selectedClicks.size
        card.performClick()
        assertEquals(count, selectedClicks.size)
        tap(card, R.id.peq_band_number)
        assertEquals(listOf("clicks"), edits)
    }

    @Test fun scrollingAndCancelledGesturesNeverSelectOrChangeParameters() {
        launch(PeqModel.defaultState(3))
        adapter.submitSelection(true, emptySet())
        val card = row()
        val original = state
        val area = bounds(card, R.id.peq_band_slider)
        val now = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            MotionEvent.obtain(now, now + 20, action, area.exactCenterX(), y, 0).also {
                card.dispatchTouchEvent(it); it.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, area.exactCenterY())
        event(MotionEvent.ACTION_MOVE, area.exactCenterY() + 100)
        event(MotionEvent.ACTION_UP, area.exactCenterY() + 100)
        event(MotionEvent.ACTION_DOWN, area.exactCenterY())
        event(MotionEvent.ACTION_CANCEL, area.exactCenterY())
        event(MotionEvent.ACTION_UP, area.exactCenterY())
        assertTrue(selectedClicks.isEmpty())
        assertEquals(original, state)
    }

    @Test fun mainLockDefaultsToRedAndProtectsParametersWhilePowerStillBypassesBand() {
        launch(PeqState(listOf(PeqBand("locked", gain = 3.0, q = 2.0, frequency = 1234.0))), unlock = false)
        val card = row()
        val locks = PeqBandLockStore(controller!!.get())
        val lock = card.findViewById<MaterialButton>(R.id.peq_band_lock)
        assertTrue(locks.isBandLocked("locked"))
        assertEquals(Color.rgb(211, 47, 47), lock.iconTint!!.defaultColor)
        assertFalse(card.findViewById<Slider>(R.id.peq_band_slider).isEnabled)
        listOf(R.id.peq_band_number, R.id.peq_band_title, R.id.peq_band_gain, R.id.peq_band_plus,
            R.id.peq_q_plus, R.id.peq_frequency_plus, R.id.peq_gain_lock).forEach { card.findViewById<View>(it).performClick() }
        assertTrue(edits.isEmpty()); assertTrue(types.isEmpty()); assertTrue(fields.isEmpty()); assertTrue(changes.isEmpty())
        val before = state.bands.single()
        tap(card, R.id.peq_band_power)
        assertEquals(before.copy(enabled = false), state.bands.single())
        tap(card, R.id.peq_band_lock)
        assertFalse(locks.isBandLocked("locked"))
        assertEquals(Color.GRAY, lock.iconTint!!.defaultColor)
        assertTrue(card.findViewById<Slider>(R.id.peq_band_slider).isEnabled)
        tap(card, R.id.peq_band_number)
        tap(card, R.id.peq_band_title)
        assertEquals(listOf("locked"), edits)
        assertEquals(listOf("locked"), types)
        tap(card, R.id.peq_band_power)
        assertEquals(before, state.bands.single())
    }

    @Test fun deletingUsesItsOwnCallbackAndRespectsMasterLockAndSelectionMode() {
        launch(PeqModel.defaultState(2), unlock = false)
        val card = row()
        val firstId = state.bands.first().id
        tap(card, R.id.peq_band_delete)
        assertTrue(deleted.isEmpty())
        tap(card, R.id.peq_band_lock)
        tap(card, R.id.peq_band_delete)
        assertEquals(listOf(firstId), deleted)
        assertTrue(edits.isEmpty())
        assertTrue(changes.isEmpty())
        adapter.submitSelection(true, emptySet())
        layout()
        tap(card, R.id.peq_band_delete)
        assertEquals(listOf(firstId), deleted)
        assertEquals(listOf(firstId), selectedClicks)
    }

    @Test fun newBadgeSurvivesLocatingAndLockingButAnyParameterEntryClearsIt() {
        launch(PeqState(listOf(PeqBand("fresh"))))
        val locks = PeqBandLockStore(controller!!.get())
        locks.setBandLocked("fresh", true)
        PeqEditField.entries.forEach { locks.setFieldLocked("fresh", it, true) }
        locks.markNew("fresh")
        adapter.notifyDataSetChanged()
        val card = row()
        assertFalse(locks.isBandLocked("fresh"))
        PeqEditField.entries.forEach { assertFalse(locks.isFieldLocked("fresh", it)) }
        assertEquals(View.VISIBLE, card.findViewById<View>(R.id.peq_band_new).visibility)
        tap(card, R.id.peq_band_locate)
        tap(card, R.id.peq_band_lock)
        tap(card, R.id.peq_band_lock)
        tap(card, R.id.peq_gain_lock)
        tap(card, R.id.peq_gain_lock)
        assertTrue(locks.isNew("fresh"))
        assertEquals(View.VISIBLE, card.findViewById<View>(R.id.peq_band_new).visibility)
        tap(card, R.id.peq_band_title)
        assertFalse(locks.isNew("fresh"))
        assertEquals(View.INVISIBLE, card.findViewById<View>(R.id.peq_band_new).visibility)
        assertTrue(changes.isEmpty())
        // Rebinding a recycled card must read the persisted status rather than its old badge.
        adapter.notifyDataSetChanged()
        assertEquals(View.INVISIBLE, row().findViewById<View>(R.id.peq_band_new).visibility)
    }

    @Test fun touchingUnchangedGainOrPowerClearsNewBadgeWithoutMutatingOtherParameters() {
        launch(PeqState(listOf(PeqBand("fresh", gain = 0.0))))
        val locks = PeqBandLockStore(controller!!.get())
        locks.markNew("fresh")
        adapter.notifyDataSetChanged()
        val card = row()
        val original = state
        tap(card, R.id.peq_band_slider)
        assertFalse(locks.isNew("fresh"))
        assertEquals(original, state)
        assertEquals(View.INVISIBLE, card.findViewById<View>(R.id.peq_band_new).visibility)
        locks.markNew("fresh")
        adapter.notifyDataSetChanged()
        val rebound = row()
        tap(rebound, R.id.peq_band_power)
        assertFalse(locks.isNew("fresh"))
        assertEquals(original.copy(bands = listOf(original.bands.single().copy(enabled = false))), state)
    }

    @Test fun editingAndLockedParameterAttemptsPreserveNewBadgeAndDisabledBandStaysDisabledWhenAdjusted() {
        launch(PeqState(listOf(PeqBand("fresh", enabled = false))))
        val locks = PeqBandLockStore(controller!!.get())
        locks.markNew("fresh")
        locks.setFieldLocked("fresh", PeqEditField.GAIN, true)
        adapter.notifyDataSetChanged()
        val card = row()
        card.findViewById<View>(R.id.peq_band_plus).performClick()
        assertTrue(locks.isNew("fresh"))
        adapter.submitSelection(true, emptySet())
        layout()
        tap(card, R.id.peq_band_power)
        tap(card, R.id.peq_band_title)
        assertTrue(locks.isNew("fresh"))
        assertFalse(state.bands.single().enabled)
        adapter.submitSelection(false, emptySet())
        layout()
        tap(card, R.id.peq_gain_lock)
        tap(card, R.id.peq_band_plus)
        assertFalse(locks.isNew("fresh"))
        assertEquals(0.5, state.bands.single().gain, 0.0)
        assertFalse(state.bands.single().enabled)
    }

    @Test fun individualLocksProtectEachFieldAndSurviveRebindingAndReordering() {
        launch(PeqState(listOf(PeqBand("one", gain = 3.0, q = 2.0), PeqBand("two"))))
        val first = row()
        tap(first, R.id.peq_gain_lock)
        val before = state
        first.findViewById<View>(R.id.peq_band_plus).performClick()
        first.findViewById<View>(R.id.peq_band_gain).performClick()
        assertEquals(before, state)
        assertTrue(fields.isEmpty())
        tap(first, R.id.peq_q_plus)
        assertEquals(2.5, state.bands.first().q, 0.0)
        tap(first, R.id.peq_q_lock)
        tap(first, R.id.peq_frequency_lock)
        val saved = state
        listOf(R.id.peq_q_plus, R.id.peq_q_value, R.id.peq_frequency_plus, R.id.peq_frequency_value)
            .forEach { first.findViewById<View>(it).performClick() }
        assertEquals(saved, state)
        assertTrue(fields.isEmpty())
        state = state.copy(bands = state.bands.reversed())
        adapter.submitReorderedState(state, 0, 1)
        val reordered = row(1)
        assertFalse(reordered.findViewById<Slider>(R.id.peq_band_slider).isEnabled)
        assertFalse(reordered.findViewById<Slider>(R.id.peq_q_slider).isEnabled)
        assertFalse(reordered.findViewById<Slider>(R.id.peq_frequency_slider).isEnabled)
        val reloaded = PeqBandLockStore(controller!!.get())
        assertFalse(reloaded.isBandLocked("one"))
        PeqEditField.entries.forEach { assertTrue(reloaded.isFieldLocked("one", it)) }
        assertFalse(reloaded.isFieldLocked("two", PeqEditField.GAIN))
        tap(reordered, R.id.peq_gain_lock)
        tap(reordered, R.id.peq_band_plus)
        assertEquals(3.5, state.bands[1].gain, 0.0)
    }

    @Test fun lockedQualityDoesNotChangeIndirectlyWhenMovingBandwidthFrequency() {
        launch(PeqState(listOf(PeqBand("bw", frequency = 1000.0, q = 2.0, qualityDisplay = PeqQualityDisplay.BW))))
        val card = row()
        tap(card, R.id.peq_q_lock)
        tap(card, R.id.peq_frequency_plus)
        assertEquals(1001.0, state.bands.single().frequency, 0.0)
        assertEquals(2.0, state.bands.single().q, 0.0)
    }

    @Test fun onlyHandleLongPressStartsDragAndTitleClickAreaStaysAroundText() {
        launch(PeqModel.defaultState(2).copy(showQSlider = true, showFrequencySlider = true))
        val card = row()
        val title = card.findViewById<TextView>(R.id.peq_band_title)
        val header = bounds(card, R.id.peq_band_header)
        val titleBounds = bounds(card, R.id.peq_band_title)
        assertTrue("Title stays left of the central power", titleBounds.right <= bounds(card, R.id.peq_band_power).left)
        card.findViewById<View>(R.id.peq_band_header).performClick()
        assertTrue(edits.isEmpty())
        assertTrue(types.isEmpty())
        tap(card, R.id.peq_band_title)
        assertEquals(listOf(state.bands.first().id), types)
        val handle = card.findViewById<View>(R.id.peq_band_drag_handle)
        assertTrue(handle.isEnabled)
        assertTrue(handle.performLongClick())
        assertSame(list.findViewHolderForAdapterPosition(0), draggedHolders.single())
        draggedHolders.clear()
        adapter.submitSelection(true, emptySet())
        layout()
        assertFalse(handle.isEnabled)
        handle.performLongClick()
        tap(card, R.id.peq_band_drag_handle)
        assertTrue(draggedHolders.isEmpty())
        assertEquals(listOf(state.bands.first().id), selectedClicks)
        adapter.submitSelection(false, emptySet())
        state = state.copy(bands = state.bands.take(1))
        adapter.submitState(state)
        assertFalse(row().findViewById<View>(R.id.peq_band_drag_handle).isEnabled)
    }

    @Test fun reorderPayloadUpdatesEveryRankWithoutReplacingHoldersOrSliderCallbacks() {
        launch(PeqModel.defaultState(3).copy(showQSlider = false, showFrequencySlider = false))
        val firstId = state.bands.first().id
        val moving = list.findViewHolderForAdapterPosition(0)!!
        val movingSlider = moving.itemView.findViewById<Slider>(R.id.peq_band_slider)
        val ids = state.bands.map { it.id }
        var fullChanges = 0
        val moves = mutableListOf<Pair<Int, Int>>()
        val ranges = mutableListOf<Pair<Int, Int>>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { fullChanges++ }
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                moves += fromPosition to toPosition
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                assertNotNull(payload)
                ranges += positionStart to itemCount
            }
        })
        state = state.copy(bands = listOf(state.bands[1], state.bands[2], state.bands[0]))
        adapter.submitReorderedState(state, 0, 2)
        layout()
        assertEquals(listOf(0 to 2), moves)
        assertEquals(listOf(0 to 3), ranges)
        assertEquals(0, fullChanges)
        assertSame(moving, list.findViewHolderForAdapterPosition(2))
        assertSame(movingSlider, moving.itemView.findViewById<Slider>(R.id.peq_band_slider))
        for (index in 0..2) {
            assertEquals((index + 1).toString(), row(index).findViewById<TextView>(R.id.peq_band_number).text.toString())
        }
        row(2).findViewById<View>(R.id.peq_band_plus).performClick()
        assertEquals(firstId, changes.single().id)
        assertEquals("3", row(2).findViewById<TextView>(R.id.peq_band_number).text.toString())
        assertEquals(listOf(ids[1], ids[2], ids[0]), state.bands.map { it.id })
    }

    @Test fun repeatedGainStepsSurviveSelectionRefreshAndStopWhenEditingModeChanges() {
        launch(PeqState(listOf(PeqBand("repeat")), gainStep = 0.25))
        val plus = row().findViewById<View>(R.id.peq_band_plus)
        val down = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, plus.width / 2f, plus.height / 2f, 0)
        try { plus.dispatchTouchEvent(event) } finally { event.recycle() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))
        assertEquals(0.25, state.bands.single().gain, 0.0)
        for (index in 2..4) {
            adapter.submitSelection(false, emptySet())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(index * 0.25, state.bands.single().gain, 0.0)
        }
        adapter.submitSelection(true, emptySet())
        layout()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(1.0, state.bands.single().gain, 0.0)
        val up = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
            plus.width / 2f, plus.height / 2f, 0)
        try { plus.dispatchTouchEvent(up) } finally { up.recycle() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1.0, state.bands.single().gain, 0.0)
        assertTrue(selectedClicks.isEmpty())
        adapter.cancelPendingRepeats()
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
