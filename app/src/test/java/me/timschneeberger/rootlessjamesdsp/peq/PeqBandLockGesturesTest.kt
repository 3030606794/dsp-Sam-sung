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

/** Physical double taps must never briefly toggle the master lock as a first single tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PeqBandLockGesturesTest {
    private lateinit var activity: ActivityController<HostActivity>
    private lateinit var gestures: PeqBandLockGestures
    private val actions = mutableListOf<String>()
    private val button get() = activity.get().button
    private var downTime = 0L

    @Before fun setup() {
        activity = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        button.layout(0, 0, 160, 120)
        gestures = PeqBandLockGestures(button, { actions += "master" }, { actions += "fields" })
    }

    @After fun close() {
        gestures.cancel()
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    private fun event(action: Int, x: Float = 80f, y: Float = 60f) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        try { button.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun tap() {
        event(MotionEvent.ACTION_DOWN)
        advance(20)
        event(MotionEvent.ACTION_UP)
    }
    private fun awaitConfirmation() = advance(ViewConfiguration.getDoubleTapTimeout().toLong() + 1)

    @Test fun physicalSingleTapWaitsThenTogglesTheMasterExactlyOnce() {
        tap()
        assertTrue(actions.isEmpty())
        assertFalse(button.isPressed)
        awaitConfirmation()
        assertEquals(listOf("master"), actions)
        awaitConfirmation()
        assertEquals(listOf("master"), actions)
    }

    @Test fun physicalDoubleTapActsOnSecondDownWithoutAnySingleCallbackOrConfirmationDelay() {
        tap()
        advance(80)
        event(MotionEvent.ACTION_DOWN)
        assertEquals(listOf("fields"), actions)
        assertTrue(button.isPressed)
        advance(20)
        event(MotionEvent.ACTION_UP)
        assertEquals(listOf("fields"), actions)
        assertFalse(button.isPressed)
        awaitConfirmation()
        assertEquals(listOf("fields"), actions)
    }

    @Test fun tapsSeparatedByTheConfirmationWindowAreTwoMasterActions() {
        tap()
        awaitConfirmation()
        tap()
        awaitConfirmation()
        assertEquals(listOf("master", "master"), actions)
    }

    @Test fun directClickIsImmediateAndDiscardsThePendingPhysicalSingle() {
        tap()
        assertTrue(button.performClick())
        assertEquals(listOf("master"), actions)
        awaitConfirmation()
        assertEquals(listOf("master"), actions)
    }

    @Test fun cancelDiscardsThePendingSingleAndClearsTheDoubleTapCandidateForReuse() {
        tap()
        gestures.cancel()
        advance(80)
        tap()
        assertTrue(actions.isEmpty())
        awaitConfirmation()
        assertEquals(listOf("master"), actions)
        tap()
        gestures.cancel()
        awaitConfirmation()
        assertEquals(listOf("master"), actions)
    }

    @Test fun cancellingAfterSecondDownDoesNotRepeatTheAlreadyCompletedDoubleAction() {
        tap()
        advance(80)
        event(MotionEvent.ACTION_DOWN)
        assertEquals(listOf("fields"), actions)
        event(MotionEvent.ACTION_CANCEL)
        event(MotionEvent.ACTION_UP)
        awaitConfirmation()
        assertEquals(listOf("fields"), actions)
        assertFalse(button.isPressed)
    }

    @Test fun movingOffTheButtonCancelsItsPendingLockAction() {
        event(MotionEvent.ACTION_DOWN)
        assertTrue("The first down has immediate visual feedback even while confirmation waits", button.isPressed)
        event(MotionEvent.ACTION_MOVE, 700f, 200f)
        event(MotionEvent.ACTION_UP, 700f, 200f)
        awaitConfirmation()
        assertTrue(actions.isEmpty())
        assertFalse(button.isPressed)
    }

    @Test fun becomingDisabledBeforeConfirmationCannotToggleAnyLock() {
        tap()
        button.isEnabled = false
        awaitConfirmation()
        button.performClick()
        assertTrue(actions.isEmpty())
        button.isEnabled = true
        button.performClick()
        assertEquals(listOf("master"), actions)
    }

    @Test fun accessibilityFieldUnlockCancelsPendingSingleAndUsesOnlyTheDoubleAction() {
        val action = button.createAccessibilityNodeInfo().actionList.single {
            it.label?.toString() == button.context.getString(R.string.peq_unlock_all_field_locks)
        }
        tap()
        assertTrue(button.performAccessibilityAction(action.id, null))
        assertEquals(listOf("fields"), actions)
        awaitConfirmation()
        assertEquals(listOf("fields"), actions)
        button.isEnabled = false
        assertFalse(button.performAccessibilityAction(action.id, null))
        assertEquals(listOf("fields"), actions)
    }

    @Test fun doubleCallbackCanCancelAndDisableItsViewWithoutLeavingASecondActionPending() {
        gestures.cancel()
        gestures = PeqBandLockGestures(button, { actions += "single" }, {
            actions += "double"
            gestures.cancel()
            button.isEnabled = false
        }, doubleTapActionLabel = "复制当前频段")
        tap()
        advance(80)
        event(MotionEvent.ACTION_DOWN)
        assertEquals(listOf("double"), actions)
        assertFalse(button.isPressed)
        event(MotionEvent.ACTION_UP)
        awaitConfirmation()
        assertEquals(listOf("double"), actions)
    }

    class HostActivity : FragmentActivity() {
        lateinit var button: MaterialButton
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            button = MaterialButton(this)
            setContentView(button)
        }
    }
}
