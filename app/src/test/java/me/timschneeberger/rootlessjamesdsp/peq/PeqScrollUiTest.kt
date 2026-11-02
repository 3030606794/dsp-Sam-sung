package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
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
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Real Coordinator/RecyclerView touch and nested-scroll transitions, with a recycled full bank. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqScrollUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val repository get() = PeqRepository(app)
    private var controller: ActivityController<HostActivity>? = null
    private val fragment get() = controller!!.get().supportFragmentManager.findFragmentById(R.id.params) as PeqFragment
    private fun <T : View> view(id: Int): T = fragment.requireView().findViewById(id)
    private val list get() = view<RecyclerView>(R.id.peq_band_list)
    private val header get() = view<AppBarLayout>(R.id.peq_response_appbar)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun clear() {
        listOf(Constants.PREF_GEQ, PeqBandLockStore.PREFERENCES, PeqEditorPreferences.PREFERENCES,
            PeqSystemPresetOrigins.PREFERENCES).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After fun close() { controller?.pause()?.stop()?.destroy(); idle() }

    private fun launch(pinned: Boolean, count: Int = PeqModel.MAX_BANDS) {
        val state = PeqModel.defaultState(count).copy(pinResponse = pinned)
        assertTrue(repository.save(state))
        val locks = PeqBandLockStore(app)
        state.bands.forEach { locks.setBandLocked(it.id, false) }
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
    }

    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        val density = app.resources.displayMetrics.density
        content.measure(View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((820 * density).toInt(), View.MeasureSpec.EXACTLY))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        idle()
    }

    private fun togglePin() {
        val activity = controller!!.get()
        val menu = PopupMenu(activity, activity.findViewById(R.id.toolbar)).menu
        assertTrue(activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu))
        activity.onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)
        assertTrue(activity.onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, menu.findItem(R.id.peq_pin_response)))
        layout()
    }

    private fun touch(target: View, down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { target.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun swipeListUp() {
        val down = SystemClock.uptimeMillis()
        val x = 4f * app.resources.displayMetrics.density // List padding avoids parameter editing.
        val start = list.height.coerceAtMost(400).toFloat() - 20f
        touch(list, down, MotionEvent.ACTION_DOWN, x, start)
        repeat(5) { step ->
            advance(20)
            touch(list, down, MotionEvent.ACTION_MOVE, x, start - (step + 1) * 32f)
        }
        touch(list, down, MotionEvent.ACTION_UP, x, start - 160f)
        advance(100)
    }

    private fun collapseViaNestedScroll() {
        assertTrue(list.startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH))
        val consumed = IntArray(2)
        list.dispatchNestedPreScroll(0, header.height, consumed, null, ViewCompat.TYPE_TOUCH)
        assertTrue(consumed[1] > 0)
        list.stopNestedScroll(ViewCompat.TYPE_TOUCH)
        idle()
        assertTrue(header.top < 0)
    }

    @Test fun pinTransitionEndsBothNestedOwnersAndOldHeaderFlingCannotMoveThePinnedList() {
        launch(pinned = false)
        val coordinator = view<CoordinatorLayout>(R.id.peq_scroll_container)
        // A real drag on the graph starts Material's separate HeaderBehavior fling.
        val down = SystemClock.uptimeMillis()
        val x = header.width / 2f
        val start = header.height - 55f
        touch(coordinator, down, MotionEvent.ACTION_DOWN, x, start)
        advance(16)
        touch(coordinator, down, MotionEvent.ACTION_MOVE, x, start - 35f)
        advance(16)
        touch(coordinator, down, MotionEvent.ACTION_MOVE, x, start - 90f)
        advance(16)
        touch(coordinator, down, MotionEvent.ACTION_UP, x, start - 120f)
        assertTrue("The regression must begin with a moving header", header.top < 0)
        list.startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
        list.startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_NON_TOUCH)
        togglePin()
        assertFalse(list.hasNestedScrollingParent(ViewCompat.TYPE_TOUCH))
        assertFalse(list.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH))
        repeat(8) {
            advance(32)
            assertEquals(0, header.top)
            assertEquals(header.bottom, list.top)
        }
        val before = list.computeVerticalScrollOffset()
        swipeListUp()
        assertTrue("The pinned list must still accept a new drag", list.computeVerticalScrollOffset() > before)
        assertEquals(0, header.top)
    }

    @Test fun alternatingPinModesAfterRecyclingDoesNotLeaveTheListInAnOldNestedSession() {
        launch(pinned = false)
        val saved = repository.load().bands
        repeat(3) {
            collapseViaNestedScroll()
            list.scrollToPosition(PeqModel.MAX_BANDS - 1)
            layout()
            togglePin()
            assertEquals(0, header.totalScrollRange)
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
            layout()
            swipeListUp()
            assertTrue(list.computeVerticalScrollOffset() > 0)
            togglePin()
            assertTrue(header.totalScrollRange > 0)
            assertEquals(0, header.top)
        }
        assertEquals(saved, repository.load().bands)
        assertTrue(list.childCount in 1..20)
    }

    @Test fun importingAFullPresetCancelsAnActiveSliderAndRestartsAtAScrollableFirstBand() {
        launch(pinned = false)
        collapseViaNestedScroll()
        val holder = requireNotNull(list.findViewHolderForAdapterPosition(0))
        val slider = holder.itemView.findViewById<Slider>(R.id.peq_band_slider)
        var starts = 0
        var stops = 0
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { starts++ }
            override fun onStopTrackingTouch(slider: Slider) { stops++ }
        })
        val rowPosition = IntArray(2).also { slider.getLocationInWindow(it) }
        val listPosition = IntArray(2).also { list.getLocationInWindow(it) }
        val x = rowPosition[0] - listPosition[0] + slider.width / 2f
        val y = rowPosition[1] - listPosition[1] + slider.height / 2f
        val down = SystemClock.uptimeMillis()
        touch(list, down, MotionEvent.ACTION_DOWN, x, y)
        advance(20)
        touch(list, down, MotionEvent.ACTION_MOVE, x + 35f, y)
        assertTrue("Begin with an active parameter touch, not just a method call", starts > 0)

        val preset = PeqSystemPresets.load(app).maxBy { it.bands.size }
        controller!!.get().findViewById<View>(R.id.peq_system_presets).performClick()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var picker: AlertDialog? = null
        while (System.nanoTime() < deadline && picker == null) {
            idle()
            picker = (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf {
                it.isShowing && it.findViewById<View>(R.id.peq_system_presets_list) != null
            }
            if (picker == null) Thread.sleep(5)
        }
        val chosen = requireNotNull(picker)
        chosen.findViewById<EditText>(R.id.peq_system_presets_search)!!.setText(preset.name)
        val options = chosen.findViewById<ListView>(R.id.peq_system_presets_list)!!
        assertEquals(1, options.count)
        options.performItemClick(options.adapter.getView(0, null, options), 0, options.adapter.getItemId(0))
        val detail = ShadowDialog.getLatestDialog() as AlertDialog
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertTrue("The old slider receives cancellation before its row is replaced", stops > 0)
        assertEquals(preset.bands.size, list.adapter!!.itemCount)
        assertEquals(0, (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
        assertEquals(0, header.top)
        assertFalse(list.hasNestedScrollingParent(ViewCompat.TYPE_TOUCH))
        swipeListUp()
        assertTrue("The new bank must scroll without leaving/re-entering PEQ",
            header.top < 0 || list.computeVerticalScrollOffset() > 0)
        togglePin()
        val before = list.computeVerticalScrollOffset()
        swipeListUp()
        assertTrue(list.computeVerticalScrollOffset() > before)
    }

    @Test fun draggingTheListAfterLocatingCancelsOnlyThePendingExpansion() {
        launch(pinned = false)
        collapseViaNestedScroll()
        val first = requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView
        first.findViewById<View>(R.id.peq_band_locate).performClick()
        assertTrue("The marker does not wait for the layout-safe double-tap window",
            view<PeqResponseView>(R.id.peq_curve).isMarkerVisible)
        assertTrue("Expanding here would cover the original first-row button", header.top < 0)
        swipeListUp()
        val afterDrag = header.top
        assertTrue(afterDrag < 0)
        advance(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        layout()
        assertEquals("A delayed locate must not reopen the graph over the user's new drag", afterDrag, header.top)
        assertTrue(list.computeVerticalScrollOffset() > 0)
    }

    @Test fun collapsedTopCardLocatesOnFirstUpAndCopiesOnceOnSecondDownWithoutMovingTheButton() {
        launch(pinned = false, count = 16)
        collapseViaNestedScroll()
        val original = fragment.editorState
        val coordinator = view<CoordinatorLayout>(R.id.peq_scroll_container)
        val button = requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView
            .findViewById<View>(R.id.peq_band_locate)
        val parentLocation = IntArray(2).also { coordinator.getLocationInWindow(it) }
        val buttonLocation = IntArray(2).also { button.getLocationInWindow(it) }
        val x = buttonLocation[0] - parentLocation[0] + button.width / 2f
        val y = buttonLocation[1] - parentLocation[1] + button.height / 2f
        val collapsedTop = header.top
        val firstDown = SystemClock.uptimeMillis()
        touch(coordinator, firstDown, MotionEvent.ACTION_DOWN, x, y)
        advance(20)
        touch(coordinator, firstDown, MotionEvent.ACTION_UP, x, y)
        assertTrue(view<PeqResponseView>(R.id.peq_curve).isMarkerVisible)
        assertEquals(original, repository.load())
        layout()
        assertEquals(collapsedTop, header.top)
        assertArrayEquals(buttonLocation, IntArray(2).also { button.getLocationInWindow(it) })
        advance(60)
        val secondDown = SystemClock.uptimeMillis()
        // Dispatch at the same window position through the real parent, not directly to the button.
        touch(coordinator, secondDown, MotionEvent.ACTION_DOWN, x, y)
        assertEquals(original.bands.size + 1, repository.load().bands.size)
        val copy = repository.load().bands[1]
        assertNotEquals(original.bands.first().id, copy.id)
        assertEquals(original.bands.first().copy(id = copy.id, enabled = false), copy)
        val locks = PeqBandLockStore(app)
        assertFalse(locks.isBandLocked(copy.id))
        assertTrue(locks.isNew(copy.id))
        PeqEditField.entries.forEach { assertFalse(locks.isFieldLocked(copy.id, it)) }
        advance(20)
        touch(coordinator, secondDown, MotionEvent.ACTION_UP, x, y)
        advance(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        layout()
        assertEquals("Second UP/confirmed-single must not duplicate a second time", 17, repository.load().bands.size)
        assertEquals("Copy cancels the first tap's delayed expansion", collapsedTop, header.top)
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_graphic_eq)
            setSupportActionBar(findViewById(R.id.toolbar))
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .replace(R.id.params, PeqFragment()).commitNow()
            findViewById<View>(R.id.peq_system_presets).setOnClickListener {
                (supportFragmentManager.findFragmentById(R.id.params) as PeqFragment).showSystemPresets()
            }
        }
    }
}
