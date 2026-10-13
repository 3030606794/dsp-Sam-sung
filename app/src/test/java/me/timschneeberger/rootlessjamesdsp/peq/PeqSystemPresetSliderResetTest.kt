package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
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
class PeqSystemPresetSliderResetTest {
    private lateinit var activity: ActivityController<HostActivity>
    private val slider get() = activity.get().slider
    private var resets = 0
    private var cancels = 0
    private var userChanges = 0
    private var downTime = 0L

    @Before fun setup() {
        activity = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
        assertTrue(slider.trackWidth > 0)
        slider.addOnChangeListener { _, _, fromUser -> if (fromUser) userChanges++ }
        PeqSystemPresetSliderReset.install(slider, { cancels++ }) { resets++ }
    }

    private fun layout() {
        // Resolve requested traversals before taking coordinates for a physical gesture.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        val root = activity.get().findViewById<View>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 600, 300)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
    }

    @After fun close() {
        PeqSystemPresetSliderReset.clear(slider)
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    private fun x(value: Float): Float {
        var fraction = (value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)
        if (slider.layoutDirection == View.LAYOUT_DIRECTION_RTL) fraction = 1f - fraction
        return slider.trackSidePadding + fraction * slider.trackWidth
    }
    private fun touch(action: Int, x: Float = x(slider.value), y: Float = slider.height / 2f) {
        val time = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = time
        val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
        try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun tap(at: Float = x(slider.value)) {
        touch(MotionEvent.ACTION_DOWN, at)
        advance(20)
        touch(MotionEvent.ACTION_UP, at)
    }

    @Test fun thumbDoubleTapResetsOnSecondDownAndConsumesTheWholeSecondTouch() {
        PeqSystemPresetSliderReset.install(slider) { resets++; slider.value = 0.15f }
        val oldThumb = x(slider.value)
        tap(oldThumb)
        assertEquals(0, resets)
        advance(80)
        touch(MotionEvent.ACTION_DOWN, oldThumb)
        assertEquals(1, resets)
        assertEquals(0.15f, slider.value, 0f)
        touch(MotionEvent.ACTION_MOVE, x(0.9f))
        advance(20)
        touch(MotionEvent.ACTION_UP, oldThumb)
        assertEquals(1, resets)
        assertEquals(0.15f, slider.value, 0f)
    }

    @Test fun helperDoesNotAssignAnArbitraryValueWhenTheResetCallbackOnlyNotifies() {
        tap()
        val firstValue = slider.value
        advance(80)
        tap()
        assertEquals(1, resets)
        assertEquals(firstValue, slider.value, 0f)
    }

    @Test fun trackDoubleTapStillEditsNormallyEvenWhenFirstTapMovesThumbUnderSecondTap() {
        val track = x(0.85f)
        tap(track)
        assertEquals(0.85f, slider.value, 0.002f)
        assertTrue(userChanges > 0)
        advance(80)
        tap(track)
        assertEquals(0, resets)
        assertEquals(0.85f, slider.value, 0.002f)
    }

    @Test fun disabledThumbAndAccessibilityActionCannotResetOrChangeValue() {
        val action = slider.createAccessibilityNodeInfo().actionList.single {
            it.label?.toString() == slider.context.getString(R.string.peq_system_preset_restore_value)
        }
        slider.isEnabled = false
        val before = slider.value
        tap()
        advance(80)
        tap()
        assertFalse(slider.performAccessibilityAction(action.id, null))
        assertEquals(0, resets)
        assertEquals(before, slider.value, 0f)
        assertEquals(0, userChanges)
    }

    @Test fun dragAndTheNextThumbTapAreNotMistakenForADoubleTap() {
        touch(MotionEvent.ACTION_DOWN)
        advance(20)
        touch(MotionEvent.ACTION_MOVE, x(0.8f))
        advance(20)
        touch(MotionEvent.ACTION_UP, x(0.8f))
        assertEquals(0.8f, slider.value, 0.002f)
        assertTrue(userChanges > 0)
        advance(80)
        tap()
        assertEquals(0, resets)
    }

    @Test fun cancellingInteractionNotifiesAndPreventsTheNextTapFromCompletingADouble() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_CANCEL)
        assertEquals(1, cancels)
        advance(80)
        tap()
        assertEquals(0, resets)
    }

    @Test fun rebindingBetweenTapsKeepsTheCandidateButUsesTheNewestResetCallback() {
        var newest = 0
        tap()
        PeqSystemPresetSliderReset.install(slider, { cancels++ }) { newest++; slider.value = 0.2f }
        advance(80)
        val thumb = x(slider.value)
        touch(MotionEvent.ACTION_DOWN, thumb)
        assertEquals(0, resets)
        assertEquals(1, newest)
        // Reset-driven rendering can bind the holder again before this tap is released.
        PeqSystemPresetSliderReset.install(slider) { newest++ }
        touch(MotionEvent.ACTION_UP, thumb)
        assertEquals(1, newest)
        assertEquals(0.2f, slider.value, 0f)
    }

    @Test fun clearRemovesPendingGestureAndAccessibilityActionForAnOrdinaryRecycledCard() {
        val action = slider.createAccessibilityNodeInfo().actionList.single {
            it.label?.toString() == slider.context.getString(R.string.peq_system_preset_restore_value)
        }
        tap()
        PeqSystemPresetSliderReset.clear(slider)
        assertFalse(slider.createAccessibilityNodeInfo().actionList.any { it.id == action.id })
        assertFalse(slider.performAccessibilityAction(action.id, null))
        advance(80)
        tap()
        advance(80)
        tap()
        assertEquals(0, resets)
        tap(x(0.7f))
        assertEquals(0.7f, slider.value, 0.002f)
        PeqSystemPresetSliderReset.install(slider) { resets++ }
        tap()
        assertEquals(0, resets)
    }

    @Test fun clearDuringAnActiveDragNotifiesCancelAndDoesNotLeaveTheOldCallbackAttached() {
        touch(MotionEvent.ACTION_DOWN)
        PeqSystemPresetSliderReset.clear(slider)
        assertEquals(1, cancels)
        touch(MotionEvent.ACTION_CANCEL)
        assertEquals(1, cancels)
        PeqSystemPresetSliderReset.install(slider) { resets += 10 }
        tap()
        advance(80)
        tap()
        assertEquals(10, resets)
    }

    @Test fun thumbGeometryTracksRightToLeftLayoutAndNonZeroSliderRanges() {
        slider.layoutDirection = View.LAYOUT_DIRECTION_RTL
        slider.valueTo = 30f
        slider.valueFrom = 10f
        slider.value = 15f
        // Changing direction requests layout. Without a traversal, the first tap's advance()
        // can resize the track between DOWN and UP, turning the intended thumb tap into a move.
        layout()
        assertEquals(View.LAYOUT_DIRECTION_RTL, slider.layoutDirection)
        val width = slider.trackWidth
        val thumb = x(15f)
        assertTrue("The lower-quarter value must render on the right in RTL", thumb > slider.width / 2f)
        tap(thumb)
        assertEquals("A thumb tap must not move the value", 15f, slider.value, 0.02f)
        assertEquals("The touch track must stay laid out through the gesture", width, slider.trackWidth)
        advance(80)
        touch(MotionEvent.ACTION_DOWN, thumb)
        assertEquals(1, resets)
        touch(MotionEvent.ACTION_UP, thumb)
        assertEquals(15f, slider.value, 0.02f)
    }

    class HostActivity : FragmentActivity() {
        lateinit var slider: Slider
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            slider = Slider(this).apply {
                valueFrom = 0f
                valueTo = 1f
                value = 0.35f
                labelBehavior = LabelFormatter.LABEL_GONE
            }
            val frame = FrameLayout(this)
            frame.addView(slider, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 64))
            setContentView(frame)
        }
    }
}
