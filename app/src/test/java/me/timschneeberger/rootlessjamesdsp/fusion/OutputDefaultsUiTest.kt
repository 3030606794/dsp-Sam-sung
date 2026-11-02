package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.OutputControlFragment
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h820dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class OutputDefaultsUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_OUTPUT, Context.MODE_PRIVATE)
    private var controller: ActivityController<OutputHostActivity>? = null
    private var updates = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updates++ }
    }
    private data class Control(val tile: Int, val key: String, val default: Float, val saved: Float, val integral: Boolean = false)
    private val controls get() = listOf(
        Control(R.id.output_threshold, app.getString(R.string.key_limiter_threshold), -0.1f, -6.25f),
        Control(R.id.output_release, app.getString(R.string.key_limiter_release), 1.5f, 350.5f),
        Control(R.id.output_postgain, app.getString(R.string.key_output_postgain), 0f, 1.3f),
        Control(R.id.output_balance, OutputControlFragment.BALANCE_KEY, 0f, -31f, true))

    @Before fun setup() {
        stopKoin()
        prefs.edit().clear().commit()
        app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = Preferences(app)
        val routing = RoutingObserver(app)
        routing.currentDevice = routing.Device("speaker", "", RoutingObserver.DeviceGroup.SPEAKER)
        startKoin { modules(module { single { preferences.App() }; single { routing } }) }
        app.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        idle()
        app.unregisterLocalReceiver(receiver)
        stopKoin()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun launch(): OutputHostActivity {
        controller = Robolectric.buildActivity(OutputHostActivity::class.java).setup().visible()
        return controller!!.get().also { layout(it) }
    }
    private fun layout(activity: OutputHostActivity) {
        idle()
        activity.findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(820, View.MeasureSpec.EXACTLY))
            layout(0, 0, 411, 820)
        }
        idle()
    }
    private fun slider(activity: OutputHostActivity, control: Control): Slider =
        activity.findViewById<View>(control.tile).findViewById(R.id.output_slider)
    private fun stored(control: Control): Float = if (control.integral)
        prefs.getInt(control.key, Int.MIN_VALUE).toFloat() else prefs.getFloat(control.key, Float.NaN)
    private fun saveExistingValues() {
        prefs.edit().apply { controls.forEach {
            if (it.integral) putInt(it.key, it.saved.toInt()) else putFloat(it.key, it.saved)
        } }.commit()
    }

    @Test fun freshOutputCardUsesAllFourDefaultsWithoutWritingOrEnablingAnyEffect() {
        val activity = launch()
        controls.forEach { assertEquals(it.default, slider(activity, it).value, 0f) }
        assertEquals("-0.10 dB", activity.findViewById<View>(R.id.output_threshold)
            .findViewById<TextView>(R.id.output_value).text.toString())
        assertEquals("1.50 ms", activity.findViewById<View>(R.id.output_release)
            .findViewById<TextView>(R.id.output_value).text.toString())
        assertEquals("0.00 dB", activity.findViewById<View>(R.id.output_postgain)
            .findViewById<TextView>(R.id.output_value).text.toString())
        assertEquals(app.getString(R.string.fusion_output_balance_center), activity.findViewById<View>(R.id.output_balance)
            .findViewById<TextView>(R.id.output_value).text.toString())
        assertTrue("Rendering defaults must preserve an empty output store", prefs.all.isEmpty())
        assertEquals(0, updates)
    }

    @Test fun savedSixtyAndThreeHundredFiftyPointFiveMsRemainUnchangedThroughReloadAndRecreation() {
        saveExistingValues()
        val release = controls[1]
        prefs.edit().putFloat(release.key, 60f).commit()
        var before = prefs.all.toMap()
        val activity = launch()
        assertEquals(60f, slider(activity, release).value, 0f)
        assertEquals(before, prefs.all)
        prefs.edit().putFloat(release.key, 350.5f).commit()
        idle()
        before = prefs.all.toMap()
        assertEquals(350.5f, slider(activity, release).value, 0f)
        controller!!.recreate()
        layout(controller!!.get())
        controls.forEach { assertEquals(it.saved, slider(controller!!.get(), it).value, 0f) }
        assertEquals(before, prefs.all)
        assertEquals(0, updates)
    }

    private fun touch(slider: Slider, action: Int, down: Long, at: Long, x: Float) {
        val event = MotionEvent.obtain(down, at, action, x, slider.height / 2f, 0)
        try { assertTrue(slider.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun doubleTapThumb(slider: Slider, control: Control, time: Long) {
        val fraction = (slider.value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)
        val thumb = slider.trackSidePadding + slider.trackWidth * fraction
        touch(slider, MotionEvent.ACTION_DOWN, time, time, thumb)
        touch(slider, MotionEvent.ACTION_UP, time, time + 20, thumb)
        touch(slider, MotionEvent.ACTION_DOWN, time + 100, time + 100, thumb)
        assertEquals("Reset happens on the second thumb tap", control.default, slider.value, 0f)
        assertEquals(control.default, stored(control), 0f)
        touch(slider, MotionEvent.ACTION_UP, time + 100, time + 120, thumb)
        idle()
        assertEquals("Finger up at the old thumb position must not move the default", control.default, slider.value, 0f)
        assertEquals(control.default, stored(control), 0f)
    }

    @Test fun doubleTappingEachActualThumbPersistsItsOwnDefaultAndFingerUpCannotMoveIt() {
        saveExistingValues()
        val activity = launch()
        controls.forEachIndexed { index, control ->
            val beforeUpdates = updates
            val slider = slider(activity, control)
            val actionLabels = slider.createAccessibilityNodeInfo().actionList.mapNotNull { it.label?.toString() }
            assertTrue(actionLabels.contains("恢复默认值"))
            assertFalse(actionLabels.contains(app.getString(R.string.slider_reset_zero)))
            doubleTapThumb(slider, control, SystemClock.uptimeMillis() + index * 1000L)
            assertTrue("A reset updates the audio processor", updates > beforeUpdates)
        }
        controller!!.recreate()
        layout(controller!!.get())
        controls.forEach {
            assertEquals(it.default, slider(controller!!.get(), it).value, 0f)
            assertEquals(it.default, stored(it), 0f)
        }
    }

    @Test fun aNewDragAfterEachResetStillPersistsContinuouslyBeforeFingerRelease() {
        saveExistingValues()
        val activity = launch()
        controls.forEachIndexed { index, control ->
            val slider = slider(activity, control)
            val time = SystemClock.uptimeMillis() + index * 2000L
            doubleTapThumb(slider, control, time)
            val beforeUpdates = updates
            val down = time + 600
            touch(slider, MotionEvent.ACTION_DOWN, down, down, slider.trackSidePadding + slider.trackWidth * 0.2f)
            touch(slider, MotionEvent.ACTION_MOVE, down, down + 30, slider.trackSidePadding + slider.trackWidth * 0.4f)
            idle()
            val first = stored(control)
            assertEquals(slider.value, first, 0.001f)
            touch(slider, MotionEvent.ACTION_MOVE, down, down + 60, slider.trackSidePadding + slider.trackWidth * 0.65f)
            idle()
            val second = stored(control)
            assertNotEquals("A second move commits before release", first, second)
            assertEquals(slider.value, second, 0.001f)
            assertTrue(updates > beforeUpdates)
            touch(slider, MotionEvent.ACTION_UP, down, down + 80, slider.trackSidePadding + slider.trackWidth * 0.65f)
            idle()
            assertEquals(second, stored(control), 0.001f)
        }
    }

    class OutputHostActivity : FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(state)
            setContentView(FrameLayout(this).apply { id = R.id.card_output_control })
            if (state == null) supportFragmentManager.beginTransaction()
                .add(R.id.card_output_control, OutputControlFragment()).commitNow()
        }
    }
}
