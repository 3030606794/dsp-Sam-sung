package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.view.FloatingToggleButton
import me.timschneeberger.rootlessjamesdsp.ui.power.PowerMotionSettings
import me.timschneeberger.rootlessjamesdsp.ui.power.PowerMotionStyle
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    qualifiers = "w411dp-h891dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MainPowerInteractionTest {
    @Before fun resetMotionSettings() {
        // Robolectric otherwise advances virtual time automatically for every
        // requested vsync. Use one controlled16ms clock for duration assertions.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        PowerMotionSettings.preferences(RuntimeEnvironment.getApplication())
            .edit().remove(PowerMotionSettings.KEY_ENABLED).remove(PowerMotionSettings.KEY_STYLE).commit()
    }

    @Test fun clickingMainPowerWithMotionDisabledChangesStateAndReturnsToTheSameFrame() = withPower { host, power ->
        val states = mutableListOf<Boolean>()
        power.setOnToggledListener(object : FloatingToggleButton.OnToggledListener {
            override fun onToggled(enabled: Boolean) { states += enabled }
        })
        val before = pixels(host.window.decorView)

        assertTrue(power.performClick())
        assertTrue(power.isToggled)
        assertTrue(power.isSelected)
        assertTrue(power.performClick())
        assertFalse(power.isToggled)
        assertFalse(power.isSelected)
        assertEquals(listOf(true, false), states)

        // With presentation animations disabled, two accepted toggles restore
        // the original page without starting a full-screen transition.
        assertArrayEquals("Disabled presentation motion must not paint a transition over the page",
            before, pixels(host.window.decorView))
        assertNull(power.stateListAnimator)
        assertEquals(Color.TRANSPARENT, power.rippleColor)
        assertEquals(0f, power.elevation, 0f)
        assertEquals(0f, power.translationZ, 0f)
        assertEquals(0f, power.rotation, 0f)
        assertEquals(1f, power.scaleX, 0f)
        assertEquals(1f, power.scaleY, 0f)
        assertEquals(48, power.customSize)
    }

    @Test fun mainPowerStillLetsTheServiceAcceptOrRejectAClick() = withPower { host, power ->
        power.toggleOnClick = false
        var accepted = false
        var clicks = 0
        val states = mutableListOf<Boolean>()
        power.setOnToggledListener(object : FloatingToggleButton.OnToggledListener {
            override fun onToggled(enabled: Boolean) { states += enabled }
        })
        power.setOnToggleClickListener(object : FloatingToggleButton.OnToggleClickListener {
            override fun onClick() {
                clicks++
                if (accepted) power.isToggled = !power.isToggled
            }
        })
        val before = pixels(host.window.decorView)
        power.performClick()
        assertFalse(power.isToggled)
        assertTrue(states.isEmpty())
        assertArrayEquals(before, pixels(host.window.decorView))

        accepted = true
        power.performClick()
        assertTrue(power.isToggled)
        assertEquals(listOf(true), states)
        assertEquals(2, clicks)
        val colors = host.getColorStateList(R.color.selector_floating_toggle_foreground_tint)
        assertEquals(Color.rgb(46, 173, 85), colors.getColorForState(power.drawableState, 0))
    }

    @Test fun presentationPreferencesUpdateTheExistingAttachedButtonWithoutChangingPower() =
        withAnimatedPower { host, power, _ ->
            power.isToggled = true
            val prefs = PowerMotionSettings.preferences(host)
            for (style in PowerMotionStyle.entries) {
                prefs.edit().putString(PowerMotionSettings.KEY_STYLE, style.key).commit()
                idle()
                assertSame(power, host.findViewById<FloatingToggleButton>(R.id.power_toggle))
                assertEquals(style, power.motionSnapshot().style)
                assertTrue(power.isToggled)
                assertFalse("Preference refresh is not a user click", power.motionSnapshot().transientRunning)
                assertFalse(power.motionSnapshot().pendingHaptic)
            }
            prefs.edit().putBoolean(PowerMotionSettings.KEY_ENABLED, false).commit()
            idle()
            assertFalse(power.motionSnapshot().enabled)
            assertQuiescent(power)
            assertTrue(power.isToggled)
            assertEquals(PowerMotionStyle.DOME, PowerMotionSettings.read(host).style)
            prefs.edit().putBoolean(PowerMotionSettings.KEY_ENABLED, true).commit()
            idle()
            assertTrue(power.motionSnapshot().enabled)
            assertEquals(PowerMotionStyle.DOME, power.motionSnapshot().style)
        }

    @Test fun rejectedClicksAndExternalServiceRefreshesNeverStartACommitAnimationOrHaptic() =
        withAnimatedPower { _, power, _ ->
            power.toggleOnClick = false
            var accept = false
            var clicks = 0
            val states = mutableListOf<Boolean>()
            power.setOnToggledListener(object : FloatingToggleButton.OnToggledListener {
                override fun onToggled(enabled: Boolean) { states += enabled }
            })
            power.setOnToggleClickListener(object : FloatingToggleButton.OnToggleClickListener {
                override fun onClick() { clicks++; if (accept) power.isToggled = !power.isToggled }
            })
            val haptic = shadowOf(power).lastHapticFeedbackPerformed()
            assertTrue(power.performClick())
            assertFalse(power.isToggled)
            assertTrue(states.isEmpty())
            assertFalse(power.motionSnapshot().transientRunning)
            assertFalse(power.motionSnapshot().pendingHaptic)
            assertEquals(haptic, shadowOf(power).lastHapticFeedbackPerformed())

            power.isToggled = true // Same setter used by service / saved-state refresh.
            assertTrue(power.isSelected)
            assertFalse(power.motionSnapshot().transientRunning)
            assertFalse(power.motionSnapshot().pendingHaptic)
            assertEquals(1, clicks)
            assertEquals(haptic, shadowOf(power).lastHapticFeedbackPerformed())
            accept = true
            assertTrue(power.performClick())
            assertFalse(power.isToggled)
            assertFalse(power.isSelected)
            assertEquals(listOf(true, false), states)
            assertEquals(2, clicks)
            idleFor(2)
            assertQuiescent(power)
        }

    @Test fun fastAlternatingClicksKeepEveryAcceptedStateAndCanBeStoppedImmediately() =
        withAnimatedPower { _, power, _ ->
            val states = mutableListOf<Boolean>()
            power.setOnToggledListener(object : FloatingToggleButton.OnToggledListener {
                override fun onToggled(enabled: Boolean) { states += enabled }
            })
            repeat(20) { assertTrue(power.performClick()) }
            assertEquals(List(20) { it % 2 == 0 }, states)
            assertFalse(power.isToggled)
            power.setMotionResumed(false)
            assertQuiescent(power)
            idleFor(2)
            assertQuiescent(power)
            assertEquals(20, states.size)
        }

    @Test fun cancelingAPressDoesNotToggleOrLeaveAReleaseHapticQueued() =
        withAnimatedPower { _, power, _ ->
            var clicks = 0
            power.setOnToggleClickListener(object : FloatingToggleButton.OnToggleClickListener {
                override fun onClick() { clicks++ }
            })
            val time = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int) {
                MotionEvent.obtain(time, time + 20, action, power.width / 2f, power.height / 2f, 0)
                    .also { event -> try { power.dispatchTouchEvent(event) } finally { event.recycle() } }
            }
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_CANCEL)
            idleFor(2)
            assertEquals(0, clicks)
            assertFalse(power.isToggled)
            assertFalse(power.motionSnapshot().pressed)
            assertFalse(power.motionSnapshot().pendingHaptic)
            assertQuiescent(power)
        }

    @Test fun detachingDuringATransitionStopsAllMotionAndReattachingKeepsTheSameToggleContract() =
        withAnimatedPower { _, power, _ ->
            assertTrue(power.performClick())
            assertTrue(power.isToggled)
            val parent = power.parent as ViewGroup
            val params = power.layoutParams
            parent.removeView(power)
            assertFalse(power.isAttachedToWindow)
            assertQuiescent(power)
            idleFor(2)
            assertQuiescent(power)
            parent.addView(power, params)
            idle()
            assertTrue(power.isAttachedToWindow)
            assertTrue(power.isToggled)
            assertTrue(power.performClick())
            assertFalse(power.isToggled)
            idleFor(2)
            assertQuiescent(power)
        }

    @Test fun pausingThePageStopsMotionAndResumingDoesNotReplayTheClick() =
        withAnimatedPower { host, power, controller ->
            power.isToggled = true
            idleFor(1)
            val haptic = shadowOf(power).lastHapticFeedbackPerformed()
            controller.pause().stop()
            assertFalse(power.motionSnapshot().resumed)
            assertQuiescent(power)
            PowerMotionSettings.preferences(host).edit()
                .putString(PowerMotionSettings.KEY_STYLE, PowerMotionStyle.PHASE.key).commit()
            idle()
            assertQuiescent(power)
            controller.start().resume().visible()
            idle()
            assertTrue(power.motionSnapshot().resumed)
            assertEquals(PowerMotionStyle.PHASE, power.motionSnapshot().style)
            assertTrue(power.isToggled)
            assertFalse(power.motionSnapshot().transientRunning)
            assertFalse(power.motionSnapshot().pendingHaptic)
            assertEquals(haptic, shadowOf(power).lastHapticFeedbackPerformed())
        }

    @Test fun everyStyleRunsAFiniteAcceptedTransitionWithoutResidentAnimation() =
        withAnimatedPower { host, power, _ ->
            val root = host.window.decorView
            for (style in PowerMotionStyle.entries) {
                power.setMotionResumed(false)
                power.isToggled = false
                PowerMotionSettings.preferences(host).edit()
                    .putString(PowerMotionSettings.KEY_STYLE, style.key).commit()
                idle()
                power.setMotionResumed(true)
                assertTrue(power.performClick())
                assertTrue("$style must animate an accepted state change", power.motionSnapshot().transientRunning)
                assertTrue(power.motionSnapshot().activeAnimatorCount > 0)
                advanceFrames(120)
                val intermediate = power.motionSnapshot()
                assertTrue("$style must advance a real transition", intermediate.haloProgress > 0f)
                assertTrue(intermediate.haloProgress < 1f)
                assertTrue("$style stopped before its midpoint: $intermediate", intermediate.transientRunning)
                assertEquals("Power tint follows the accepted state immediately",
                    Color.rgb(46, 173, 85), intermediate.tint)
                savePreview(root, style.key)
                idleFor(2)
                assertEquals(Color.rgb(46, 173, 85), power.motionSnapshot().tint)
                assertQuiescent(power)
            }
        }

    private fun assertQuiescent(power: FloatingToggleButton) {
        val state = power.motionSnapshot()
        assertEquals(0, state.activeAnimatorCount)
        assertFalse(state.breathing)
        assertFalse(state.transientRunning)
        assertFalse(state.pendingHaptic)
        assertFalse(state.pendingRelease)
        assertEquals(1f, power.scaleX, 0f)
        assertEquals(1f, power.scaleY, 0f)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun idleFor(seconds: Long) = advanceFrames(seconds * 1000L)

    private fun advanceFrames(milliseconds: Long) {
        var remaining = milliseconds
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }

    private fun savePreview(view: View, style: String) {
        val file = File("diagnostics/UI54-power-motion-20261017/power-$style.png")
        file.parentFile.mkdirs()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun withPower(block: (HostActivity, FloatingToggleButton) -> Unit) {
        withPowerController(false) { host, power, _ -> block(host, power) }
    }

    private fun withAnimatedPower(block: (HostActivity, FloatingToggleButton, ActivityController<HostActivity>) -> Unit) {
        withPowerController(true, block)
    }

    private fun withPowerController(enabled: Boolean,
        block: (HostActivity, FloatingToggleButton, ActivityController<HostActivity>) -> Unit) {
        PowerMotionSettings.preferences(RuntimeEnvironment.getApplication()).edit()
            .putBoolean(PowerMotionSettings.KEY_ENABLED, enabled).commit()
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        try {
            val host = controller.get()
            // With controlled vsync, the window's first traversal/attach is
            // pending until frames are pumped; visible() alone cannot attach it.
            advanceFrames(32)
            shadowOf(Looper.getMainLooper()).idle()
            val root = host.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 411, 891)
            val power = host.findViewById<FloatingToggleButton>(R.id.power_toggle)
            assertTrue("Exercise the actual attached homepage power button", power.isAttachedToWindow)
            block(host, power, controller)
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            return IntArray(view.width * view.height).also {
                bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height)
            }
        } finally { bitmap.recycle() }
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            val content = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
            // Inflate the homepage button from its real XML tag, avoiding the page's
            // navigation host and audio-service dependencies in this interaction test.
            val power = resources.getLayout(R.layout.activity_dsp_main).use { parser ->
                while (parser.eventType != XmlPullParser.START_TAG ||
                    parser.name != FloatingToggleButton::class.java.name) {
                    check(parser.next() != XmlPullParser.END_DOCUMENT)
                }
                layoutInflater.createView(FloatingToggleButton::class.java.name, null,
                    Xml.asAttributeSet(parser)) as FloatingToggleButton
            }
            content.addView(power, FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            setContentView(content)
        }

        override fun onResume() {
            super.onResume()
            findViewById<FloatingToggleButton>(R.id.power_toggle)?.setMotionResumed(true)
        }

        override fun onPause() {
            findViewById<FloatingToggleButton>(R.id.power_toggle)?.setMotionResumed(false)
            super.onPause()
        }
    }
}
