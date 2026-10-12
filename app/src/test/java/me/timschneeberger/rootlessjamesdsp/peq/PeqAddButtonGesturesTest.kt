package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PeqAddButtonGesturesTest {
    private lateinit var activity: ActivityController<HostActivity>
    private lateinit var gestures: PeqAddButtonGestures
    private val actions = mutableListOf<String>()
    private val button get() = activity.get().button
    private var downTime = 0L

    @Before fun setup() {
        activity = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        button.layout(0, 0, 240, 120)
        gestures = PeqAddButtonGestures(button, { actions += "add" }, { actions += "jump" }, { actions += "menu" })
    }

    @After fun close() {
        gestures.cancel()
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    private fun event(action: Int, x: Float = 120f, y: Float = 60f) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        try { assertTrue(button.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    private fun tap() {
        event(MotionEvent.ACTION_DOWN)
        advance(20)
        event(MotionEvent.ACTION_UP)
    }
    private fun awaitConfirmation() = advance(ViewConfiguration.getDoubleTapTimeout().toLong() + 1)

    @Test fun touchSingleWaitsForDoubleTapWindowThenAddsExactlyOnce() {
        tap()
        assertTrue(actions.isEmpty())
        assertFalse(button.isPressed)
        awaitConfirmation()
        assertEquals(listOf("add"), actions)
        awaitConfirmation()
        assertEquals(listOf("add"), actions)
    }

    @Test fun doubleTapJumpsWithoutAddingAnyBand() {
        tap()
        advance(80)
        tap()
        assertEquals(listOf("jump"), actions)
        awaitConfirmation()
        assertEquals(listOf("jump"), actions)
        assertFalse(button.isPressed)
    }

    @Test fun longPressOpensMenuWithoutASingleTapAfterRelease() {
        event(MotionEvent.ACTION_DOWN)
        assertTrue(button.isPressed)
        advance(ViewConfiguration.getLongPressTimeout().toLong() + ViewConfiguration.getTapTimeout() + 1)
        assertEquals(listOf("menu"), actions)
        event(MotionEvent.ACTION_UP)
        awaitConfirmation()
        assertEquals(listOf("menu"), actions)
        assertFalse(button.isPressed)
    }

    @Test fun directClicksRemainImmediateForAccessibilityAndCancelPendingTouchClicks() {
        tap()
        assertTrue(button.performClick())
        assertEquals(listOf("add"), actions)
        awaitConfirmation()
        assertEquals(listOf("add"), actions)
        tap()
        assertTrue(button.performLongClick())
        assertEquals(listOf("add", "menu"), actions)
        awaitConfirmation()
        assertEquals(listOf("add", "menu"), actions)
    }

    @Test fun lifecycleCancelDiscardsSingleTapAndLongPressAndAllowsLaterReuse() {
        tap()
        gestures.cancel()
        awaitConfirmation()
        assertTrue(actions.isEmpty())
        event(MotionEvent.ACTION_DOWN)
        gestures.cancel()
        advance(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        assertFalse(button.isPressed)
        assertTrue(actions.isEmpty())
        event(MotionEvent.ACTION_CANCEL)
        tap()
        awaitConfirmation()
        assertEquals(listOf("add"), actions)
    }

    @Test fun draggingOffTheButtonAndTouchCancellationDoNotAdd() {
        event(MotionEvent.ACTION_DOWN)
        advance(20)
        event(MotionEvent.ACTION_MOVE, 800f, 300f)
        event(MotionEvent.ACTION_UP, 800f, 300f)
        awaitConfirmation()
        assertTrue(actions.isEmpty())
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_CANCEL)
        awaitConfirmation()
        assertTrue(actions.isEmpty())
    }

    class HostActivity : FragmentActivity() {
        lateinit var button: MaterialButton
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            button = MaterialButton(this).apply { text = "+频段 • 前" }
            setContentView(button)
        }
    }
}
