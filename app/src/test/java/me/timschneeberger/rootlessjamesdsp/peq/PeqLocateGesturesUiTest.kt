package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Exercises the actual adapter holder, its touch listener and RecyclerView lifecycle callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqLocateGesturesUiTest {
    private lateinit var host: ActivityController<HostActivity>
    private lateinit var adapter: PeqBandAdapter
    private lateinit var holder: PeqBandAdapter.Holder
    private var state = PeqState(listOf(PeqBand("first", frequency = 1000.0, gain = 3.0)))
    private val located = mutableListOf<String>()
    private val duplicated = mutableListOf<String>()
    private var refreshOnLocate = false
    private var downTime = 0L
    private val frame get() = host.get().frame
    private val plus get() = holder.itemView.findViewById<View>(R.id.peq_band_locate)

    @Before fun setup() {
        host = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        host.get().getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        PeqBandLockStore(host.get()).setBandLocked("first", false)
        adapter = PeqBandAdapter(onBandChanged = { fail("Locating/copy request must not edit source parameters"); false },
            onEdit = {}, onLocate = { id ->
                located += id
                if (refreshOnLocate) {
                    // Locating can refresh the same card while the first UP is still dispatching.
                    adapter.onBindViewHolder(holder, 0)
                    layout()
                }
            }, onDuplicate = { duplicated += it })
        adapter.submitState(state)
        holder = adapter.onCreateViewHolder(frame, 0)
        adapter.onBindViewHolder(holder, 0)
        frame.addView(holder.itemView)
        adapter.onViewAttachedToWindow(holder)
        layout()
    }
    @After fun close() {
        adapter.onViewDetachedFromWindow(holder)
        host.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    private fun awaitWindow() = advance(ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
    private fun layout() {
        val density = frame.resources.displayMetrics.density
        frame.measure(View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((820 * density).toInt(), View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
    }
    private fun event(action: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, plus.width / 2f, plus.height / 2f, 0)
        try { plus.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun tap() {
        event(MotionEvent.ACTION_DOWN)
        advance(20)
        event(MotionEvent.ACTION_UP)
    }

    @Test fun firstReleaseLocatesImmediatelyAndSameCardRefreshKeepsTheSecondDownCopy() {
        refreshOnLocate = true
        tap()
        assertEquals("No timeout must be needed for the first locate", listOf("first"), located)
        assertTrue(duplicated.isEmpty())
        advance(80)
        event(MotionEvent.ACTION_DOWN)
        assertEquals("Copy runs immediately on the second down after the synchronous rebind", listOf("first"), duplicated)
        advance(20)
        event(MotionEvent.ACTION_UP)
        awaitWindow()
        assertEquals(listOf("first"), located)
        assertEquals(listOf("first"), duplicated)
        assertEquals(3.0, state.bands.single().gain, 0.0)
    }

    @Test fun changingTheReusedHoldersIdentityCannotTurnItsFirstTapIntoACopy() {
        tap()
        assertEquals(listOf("first"), located)
        state = state.copy(bands = listOf(state.bands.single().copy(id = "replacement")))
        PeqBandLockStore(host.get()).setBandLocked("replacement", false)
        adapter.submitState(state)
        adapter.onBindViewHolder(holder, 0)
        layout()
        advance(80)
        tap()
        assertEquals(listOf("first", "replacement"), located)
        assertTrue(duplicated.isEmpty())
        advance(80)
        event(MotionEvent.ACTION_DOWN)
        assertEquals(listOf("replacement"), duplicated)
        event(MotionEvent.ACTION_UP)
        awaitWindow()
        assertEquals(listOf("first", "replacement"), located)
    }

    @Test fun lockedCardsStillLocateImmediatelyButTheSecondTapCannotCopyThem() {
        PeqBandLockStore(host.get()).setBandLocked("first", true)
        adapter.refreshLocks("first")
        tap()
        assertEquals(listOf("first"), located)
        advance(80)
        tap()
        awaitWindow()
        assertTrue(duplicated.isEmpty())
        assertEquals(listOf("first"), located)
    }

    @Test fun selectionModeAndDetachmentEachDiscardAnEarlierDoubleCandidate() {
        tap()
        adapter.submitSelection(true, emptySet())
        adapter.onBindViewHolder(holder, 0)
        tap()
        assertEquals(listOf("first"), located)
        assertTrue(duplicated.isEmpty())
        adapter.submitSelection(false, emptySet())
        adapter.onBindViewHolder(holder, 0)
        advance(80)
        tap()
        assertEquals(listOf("first", "first"), located)
        assertTrue(duplicated.isEmpty())

        adapter.onViewDetachedFromWindow(holder)
        frame.removeView(holder.itemView)
        frame.addView(holder.itemView)
        adapter.onViewAttachedToWindow(holder)
        layout()
        advance(80)
        tap()
        assertEquals(listOf("first", "first", "first"), located)
        awaitWindow()
        assertTrue(duplicated.isEmpty())
    }

    class HostActivity : FragmentActivity() {
        lateinit var frame: FrameLayout
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            frame = FrameLayout(this)
            setContentView(frame)
        }
    }
}
