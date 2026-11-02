package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
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

    @Test fun touchSingleUsesTheShortConfirmationWindowAndAddsExactlyOnce() {
        tap()
        assertTrue(actions.isEmpty())
        assertFalse(button.isPressed)
        val delay = PeqAddButtonGestures.confirmationDelayMillis()
        assertTrue(delay <= 220L && delay <= ViewConfiguration.getDoubleTapTimeout())
        advance(delay - 1)
        assertTrue(actions.isEmpty())
        advance(1)
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

    @Test fun secondTapJustInsideTheShortWindowJumpsButNeverSendsASpeculativeAdd() {
        tap()
        advance(PeqAddButtonGestures.confirmationDelayMillis() - 1)
        event(MotionEvent.ACTION_DOWN)
        assertEquals(listOf("jump"), actions)
        event(MotionEvent.ACTION_UP)
        awaitConfirmation()
        assertEquals(listOf("jump"), actions)
    }

    @Test fun tapsAfterTheShortWindowAreTwoSinglesAndCannotTurnIntoALateDoubleJump() {
        tap()
        advance(PeqAddButtonGestures.confirmationDelayMillis() + 1)
        assertEquals(listOf("add"), actions)
        tap()
        advance(PeqAddButtonGestures.confirmationDelayMillis() + 1)
        assertEquals(listOf("add", "add"), actions)
        awaitConfirmation()
        assertEquals(listOf("add", "add"), actions)
    }

    @Test fun aLongerFirstTouchStillAllowsASecondTapWithinTheSameReleaseWindow() {
        event(MotionEvent.ACTION_DOWN)
        advance(250)
        event(MotionEvent.ACTION_UP)
        advance(150)
        event(MotionEvent.ACTION_DOWN)
        assertEquals(listOf("jump"), actions)
        event(MotionEvent.ACTION_UP)
        awaitConfirmation()
        assertEquals(listOf("jump"), actions)
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

    @Test fun disabledButtonRejectsAccessibilityClicksLongPressAndPendingTouchCallbacks() {
        tap()
        button.isEnabled = false
        button.performClick()
        button.performLongClick()
        awaitConfirmation()
        assertTrue(actions.isEmpty())
        button.isEnabled = true
        button.performClick()
        assertEquals(listOf("add"), actions)
    }

    @Test fun detachingTheButtonCancelsPendingConfirmationWithoutNeedingAFragmentCallback() {
        tap()
        val parent = button.parent as ViewGroup
        val params = button.layoutParams
        parent.removeView(button)
        awaitConfirmation()
        assertTrue(actions.isEmpty())
        parent.addView(button, params)
        tap()
        awaitConfirmation()
        assertEquals(listOf("add"), actions)
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
