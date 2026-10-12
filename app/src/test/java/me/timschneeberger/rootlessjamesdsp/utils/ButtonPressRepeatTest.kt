package me.timschneeberger.rootlessjamesdsp.utils

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
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
class ButtonPressRepeatTest {
    private lateinit var activity: ActivityController<HostActivity>
    private lateinit var repeat: ButtonPressRepeat
    private val button get() = activity.get().button
    private var clicks = 0
    private var step = 1.0
    private var value = 0.0
    private var maximum = 1000.0
    private var downTime = 0L

    @Before fun setup() {
        activity = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        button.layout(0, 0, 240, 120)
        button.setOnClickListener {
            clicks++
            value = (value + step).coerceAtMost(maximum)
            button.isEnabled = value < maximum
        }
        repeat = ButtonPressRepeat(button)
        assertTrue(button.isAttachedToWindow)
    }

    @After fun close() {
        repeat.dispose()
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    private fun event(action: Int, x: Float = 120f, y: Float = 60f) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        try { button.dispatchTouchEvent(event) } finally { event.recycle() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun hold() {
        event(MotionEvent.ACTION_DOWN)
        advance(ViewConfiguration.getLongPressTimeout().toLong() + 1)
        assertEquals(1, clicks)
    }

    @Test fun ordinaryTouchAndAccessibilityClicksKeepTheOriginalSingleStep() {
        event(MotionEvent.ACTION_DOWN)
        advance(20)
        assertEquals(0, clicks)
        event(MotionEvent.ACTION_UP)
        assertEquals(1, clicks)
        advance(1000)
        assertEquals(1, clicks)
        assertTrue(button.performClick())
        assertEquals(2, clicks)
        assertFalse(button.performLongClick()) // No touch to release: never start an endless repeat.
        advance(1000)
        assertEquals(2, clicks)
    }

    @Test fun everyHundredMillisecondsAddsExactlyTheCurrentStepWithoutAccelerationOrReleaseStep() {
        step = 0.25
        hold()
        for (index in 1..8) {
            advance(100)
            assertEquals(index + 1, clicks)
            assertEquals((index + 1) * 0.25, value, 0.0)
        }
        step = 2.0
        val before = value
        advance(300)
        assertEquals(12, clicks)
        assertEquals(before + 6.0, value, 0.0)
        event(MotionEvent.ACTION_UP)
        advance(1000)
        assertEquals(12, clicks)
        assertFalse(button.isPressed)
    }

    @Test fun cancellationOrMovingOutsideStopsImmediately() {
        hold()
        event(MotionEvent.ACTION_CANCEL)
        advance(1000)
        assertEquals(1, clicks)
        clicks = 0
        hold()
        event(MotionEvent.ACTION_MOVE, 800f, 300f)
        advance(1000)
        event(MotionEvent.ACTION_UP, 800f, 300f)
        assertEquals(1, clicks)
        assertFalse(button.isPressed)
    }

    @Test fun aDisabledBoundaryStopsInsteadOfSendingRepeatedNoOpClicks() {
        maximum = 3.0
        hold()
        advance(1000)
        assertEquals(3, clicks)
        assertEquals(3.0, value, 0.0)
        assertFalse(button.isEnabled)
        event(MotionEvent.ACTION_UP)
        advance(1000)
        assertEquals(3, clicks)
    }

    @Test fun explicitLifecycleCancelAndDetachmentDiscardQueuedSteps() {
        hold()
        repeat.cancel()
        advance(1000)
        event(MotionEvent.ACTION_UP)
        assertEquals(1, clicks)
        clicks = 0
        hold()
        activity.get().container.removeView(button)
        advance(1000)
        assertEquals(1, clicks)
        assertFalse(button.isPressed)
    }

    @Test fun replacingABindingAndDisposingTheOldOwnerDoesNotRemoveNewListeners() {
        hold()
        val previous = repeat
        repeat = ButtonPressRepeat(button)
        previous.dispose()
        advance(1000)
        assertEquals(1, clicks)
        clicks = 0
        hold()
        advance(200)
        assertEquals(3, clicks)
        event(MotionEvent.ACTION_UP)
        advance(1000)
        assertEquals(3, clicks)
    }

    class HostActivity : FragmentActivity() {
        lateinit var button: MaterialButton
        lateinit var container: FrameLayout
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            button = MaterialButton(this).apply { text = "+" }
            container = FrameLayout(this).apply { addView(button, FrameLayout.LayoutParams(240, 120)) }
            setContentView(container)
        }
    }
}
