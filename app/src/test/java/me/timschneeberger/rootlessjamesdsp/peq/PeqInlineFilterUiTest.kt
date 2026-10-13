package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqInlineFilterUiTest {
    private val host = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
    private val list get() = host.get().list
    private val locks get() = PeqBandLockStore(host.get())
    private val actions = mutableListOf<Pair<String, PeqInlineFilterAction>>()
    private val selections = mutableListOf<String>()
    private var mutations = 0
    private var state = PeqModel.defaultState(30)
    private val adapter = PeqBandAdapter(onBandChanged = { mutations++; true }, onEdit = {},
        onToggleSelection = selections::add, onInlineFilterAction = { id, action -> actions += id to action })

    @After fun close() { host.pause().stop().destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun launch() {
        host.get().getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        state.bands.forEach { locks.setBandLocked(it.id, false) }
        list.adapter = adapter
        adapter.submitState(state)
        layout()
    }
    private fun layout(width: Int = 411) {
        idle()
        val root = host.get().findViewById<View>(android.R.id.content)
        val density = root.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((700 * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        idle()
    }
    private fun card(index: Int = 0) = list.findViewHolderForAdapterPosition(index)!!.itemView as ViewGroup
    private fun row(index: Int = 0) = card(index).findViewById<PeqInlineFilterRow>(R.id.peq_inline_filter)
    private fun cell(id: Int, index: Int = 0) = row(index).findViewById<View>(id)
    private fun type(type: PeqFilterType) = cell(PeqFilterTypeDialog.buttonId(type))
    private fun color(view: View) = (view.background as ColorDrawable).color
    private fun show(draft: PeqInlineFilterDraft? = null) { adapter.submitInlineFilter(true, draft); layout() }
    private fun draft(type: PeqFilterType = PeqFilterType.HIGH_PASS, monitoring: Boolean = false) =
        PeqInlineFilterDraft(state.bands.first().id, type, monitoring)

    @Test fun optionalStripHasElevenStableEqualTextSlotsAndCardsUseCompactPlainNumbers() {
        launch()
        assertEquals(View.GONE, row().visibility)
        show()
        val ids = listOf(R.id.peq_inline_cancel) + PeqFilterType.entries.map(PeqFilterTypeDialog::buttonId) +
            listOf(R.id.peq_inline_monitor, R.id.peq_inline_confirm, R.id.peq_inline_lock)
        for (width in listOf(320, 411, 600)) {
            layout(width)
            val choices = row().findViewById<ViewGroup>(R.id.peq_inline_choices)
            assertEquals(11, choices.childCount)
            val cells = ids.map { row().findViewById<View>(it) }
            assertEquals(ids, (0 until choices.childCount).map { choices.getChildAt(it).id })
            assertTrue(cells.maxOf { it.width } - cells.minOf { it.width } <= 1)
            assertTrue(cells.all { it.visibility == View.VISIBLE && it.right <= choices.width })
            cells.zipWithNext().forEach { (a, b) -> assertEquals(a.right, b.left) }
            PeqFilterType.entries.forEach {
                val label = type(it) as TextView
                assertTrue(label.paint.measureText(label.text.toString()) <= label.width + 1)
                assertTrue(label.compoundDrawables.all { icon -> icon == null })
            }
            val density = choices.resources.displayMetrics.density
            assertEquals(40f, choices.height / density, 1f)
            assertEquals(40f, card().findViewById<View>(R.id.peq_band_header).height / density, 1f)
            val gain = card().findViewById<TextView>(R.id.peq_band_gain)
            val frequency = card().findViewById<TextView>(R.id.peq_frequency_value)
            assertNotNull(gain.text.toString().toDoubleOrNull())
            assertNotNull(frequency.text.toString().toDoubleOrNull())
            assertEquals(Color.TRANSPARENT, color(gain))
            assertTrue(gain.isClickable && frequency.isClickable)
        }
    }

    @Test fun draftAndAuditionKeepCommittedTypePurpleAndHeaderUnchangedUntilHostConfirms() {
        launch()
        show()
        val savedColor = color(type(PeqFilterType.PEAK))
        type(PeqFilterType.HIGH_PASS).performClick()
        assertEquals(PeqInlineFilterAction.Select(PeqFilterType.HIGH_PASS), actions.last().second)
        show(draft())
        assertTrue(type(PeqFilterType.HIGH_PASS).background is GradientDrawable)
        assertEquals(savedColor, color(type(PeqFilterType.PEAK)))
        cell(R.id.peq_inline_monitor).performClick()
        assertEquals(PeqInlineFilterAction.Monitor(true), actions.last().second)
        show(draft(monitoring = true))
        assertEquals(Color.rgb(46, 125, 50), color(type(PeqFilterType.HIGH_PASS)))
        assertEquals(Color.rgb(46, 125, 50), color(cell(R.id.peq_inline_monitor)))
        assertEquals(savedColor, color(type(PeqFilterType.PEAK)))
        assertEquals("峰值", card().findViewById<TextView>(R.id.peq_band_title).text.toString())
        assertEquals(0, mutations)
        cell(R.id.peq_inline_confirm).performClick()
        assertEquals(PeqInlineFilterAction.Confirm, actions.last().second)
        state = state.copy(bands = state.bands.mapIndexed { index, band ->
            if (index == 0) band.copy(type = PeqFilterType.HIGH_PASS) else band })
        adapter.submitInlineFilter(true, null)
        adapter.submitState(state)
        layout()
        assertEquals(savedColor, color(type(PeqFilterType.HIGH_PASS)))
        assertEquals(Color.TRANSPARENT, color(type(PeqFilterType.PEAK)))
        assertEquals("高通", card().findViewById<TextView>(R.id.peq_band_title).text.toString())
        assertFalse(cell(R.id.peq_inline_confirm).isEnabled)
    }

    @Test fun filterLockStopsPreviewButKeepsItsUnlockAndOtherParametersUsable() {
        launch()
        show(draft(monitoring = true))
        cell(R.id.peq_inline_lock).performClick()
        assertTrue(locks.isFieldLocked(state.bands.first().id, PeqEditField.FILTER_TYPE))
        assertEquals(listOf(state.bands.first().id to PeqInlineFilterAction.Unavailable), actions)
        assertFalse(type(PeqFilterType.NOTCH).isEnabled)
        assertFalse(card().findViewById<View>(R.id.peq_band_title).isEnabled)
        assertTrue(cell(R.id.peq_inline_lock).isEnabled)
        assertTrue(card().findViewById<View>(R.id.peq_band_plus).isEnabled)
        type(PeqFilterType.NOTCH).performClick()
        assertEquals(1, actions.size)
        cell(R.id.peq_inline_lock).performClick()
        assertFalse(locks.isFieldLocked(state.bands.first().id, PeqEditField.FILTER_TYPE))
        assertTrue(type(PeqFilterType.NOTCH).isEnabled)
        assertTrue(card().findViewById<View>(R.id.peq_band_title).isEnabled)
    }

    @Test fun enteringSelectionModeStopsPreviewAndEveryInlineTouchOnlySelectsTheCard() {
        launch()
        show(draft(monitoring = true))
        adapter.submitSelection(true, emptySet())
        layout()
        assertEquals(PeqInlineFilterAction.Unavailable, actions.single().second)
        actions.clear()
        val card = card()
        val choices = row().findViewById<ViewGroup>(R.id.peq_inline_choices)
        for (index in 0 until choices.childCount) {
            val child = choices.getChildAt(index)
            val rect = Rect().also { child.getDrawingRect(it); card.offsetDescendantRectToMyCoords(child, it) }
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { step, action ->
                val event = MotionEvent.obtain(now, now + step * 20, action, rect.exactCenterX(), rect.exactCenterY(), 0)
                try { card.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        assertEquals(List(11) { state.bands.first().id }, selections)
        assertTrue(actions.isEmpty())
        assertEquals(0, mutations)
        assertFalse(locks.isFieldLocked(state.bands.first().id, PeqEditField.FILTER_TYPE))
    }

    @Test fun scrollingThePreviewCardAwayRequestsCleanupOnceAndRecycledRowsHaveNoDraft() {
        launch()
        show(draft(monitoring = true))
        list.scrollToPosition(29)
        layout()
        assertEquals(listOf(state.bands.first().id to PeqInlineFilterAction.Unavailable), actions)
        val last = list.findViewHolderForAdapterPosition(29)!!.itemView
        assertFalse(last.findViewById<View>(R.id.peq_inline_monitor).isSelected)
        assertFalse(last.findViewById<View>(R.id.peq_inline_confirm).isEnabled)
        adapter.submitInlineFilter(false)
        assertEquals(1, actions.size)
    }

    @Test fun auditioningSavedTypeKeepsElevenPositionsAndNoConfirmUntilChanged() {
        launch()
        show(draft(PeqFilterType.PEAK, true))
        assertFalse(cell(R.id.peq_inline_confirm).isEnabled)
        assertTrue(cell(R.id.peq_inline_cancel).isEnabled)
        cell(R.id.peq_inline_confirm).performClick()
        assertTrue(actions.isEmpty())
        cell(R.id.peq_inline_cancel).performClick()
        assertEquals(PeqInlineFilterAction.Cancel, actions.single().second)
        adapter.submitInlineFilter(false)
        assertEquals(PeqInlineFilterAction.Unavailable, actions.last().second)
        assertEquals(View.GONE, row().visibility)
    }

    class HostActivity : FragmentActivity() {
        lateinit var list: RecyclerView
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@HostActivity); itemAnimator = null }
            setContentView(list)
        }
    }
}
