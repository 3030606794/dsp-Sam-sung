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
import androidx.preference.PreferenceGroupAdapter
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class ViperSliderRealtimeUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(ViperParameters.PREFS, Context.MODE_PRIVATE)
    private var controller: ActivityController<Host>? = null
    private var broadcasts = 0
    private val packets = mutableListOf<Pair<Int, List<Int>>>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            broadcasts++
            // Like JamesDspRemoteEngine, read through the engine's owning context,
            // rather than the broadcast callback's context. LocalBroadcastManager's
            // singleton can retain an earlier Application between Robolectric cases.
            ViperController.sync(app) { id, values ->
                packets += id to values.toList()
                true
            }
        }
    }

    @Before fun setup() {
        prefs.edit().clear().commit()
        app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE).edit().clear().commit()
        app.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        idle()
        app.unregisterLocalReceiver(receiver)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(12000, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 1080, 12000)
        idle()
    }

    private fun launch(): ViperEffectPreferenceFragment {
        controller = Robolectric.buildActivity(Host::class.java).setup().visible()
        layout()
        val host = controller!!.get().supportFragmentManager.findFragmentByTag("viper") as ViperPreferenceFragment
        return host.effectFragment("v_reverb")!!.also { it.effectHeader.setExpanded(true); layout() }
    }

    @Suppress("RestrictedApi")
    private fun row(fragment: ViperEffectPreferenceFragment, key: String): View {
        layout()
        val adapter = fragment.listView.adapter as PreferenceGroupAdapter
        val position = adapter.getPreferenceAdapterPosition(key)
        assertTrue("Expected expanded slider $key", position >= 0)
        fragment.listView.scrollToPosition(position)
        layout()
        return requireNotNull(fragment.listView.findViewHolderForAdapterPosition(position)).itemView
    }

    private fun touch(slider: Slider, action: Int, downTime: Long, fraction: Float) {
        val x = slider.trackSidePadding + slider.trackWidth * fraction
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, slider.height / 2f, 0)
        try { assertTrue(slider.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun start(slider: Slider): Long = SystemClock.uptimeMillis().also {
        val fraction = (slider.value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)
        touch(slider, MotionEvent.ACTION_DOWN, it, fraction)
    }

    @Test fun everyReverbControlSendsItsUpdatedIntegerPacketBeforeFingerRelease() {
        prefs.edit().putBoolean("v_reverb", true).commit()
        val fragment = launch()
        ViperParameters.groups.single { it.key == "v_reverb" }.controls.forEach { control ->
            val bound = row(fragment, control.key)
            val slider = bound.findViewById<Slider>(R.id.seekbar)
            val downTime = start(slider)
            val before = broadcasts
            touch(slider, MotionEvent.ACTION_MOVE, downTime, 0.65f)
            idle()
            val first = slider.value.toInt()
            assertTrue(first > 50)
            assertEquals(first, prefs.getInt(control.key, -1))
            assertTrue(prefs.all[control.key] is Int)
            assertTrue("Dragging must notify the driver before ACTION_UP", broadcasts > before)
            assertTrue("Expected ${control.parameter}=${control.packet(first).toList()}; actual packets: $packets",
                packets.contains(control.parameter to control.packet(first).toList()))
            assertEquals(control.label(first), bound.findViewById<TextView>(R.id.seekbar_value).text.toString())
            touch(slider, MotionEvent.ACTION_MOVE, downTime, 0.85f)
            idle()
            val finalValue = slider.value.toInt()
            assertTrue(finalValue > first)
            assertEquals(finalValue, prefs.getInt(control.key, -1))
            assertTrue("Expected final ${control.parameter}=${control.packet(finalValue).toList()}; actual packets: $packets",
                packets.contains(control.parameter to control.packet(finalValue).toList()))
            touch(slider, MotionEvent.ACTION_UP, downTime, 0.85f)
            idle()
            assertEquals(finalValue, fragment.findPreference<ViperSliderPreference>(control.key)!!.value)
            assertEquals(finalValue, prefs.getInt(control.key, -1))
            assertTrue(fragment.effectHeader.isChecked)
        }
    }

    @Test fun realtimeEditingDisabledReverbDoesNotEnableItOrAlterPeq() {
        val peq = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        peq.edit().putString("marker", "unchanged PEQ").commit()
        val beforePeq = peq.all.toMap()
        val fragment = launch()
        val slider = row(fragment, "v_reverb_wet").findViewById<Slider>(R.id.seekbar)
        val downTime = start(slider)
        packets.clear()
        val before = broadcasts
        touch(slider, MotionEvent.ACTION_MOVE, downTime, 0.7f)
        idle()
        assertEquals(slider.value.toInt(), prefs.getInt("v_reverb_wet", -1))
        assertTrue(broadcasts > before)
        assertTrue("Expected wet signal ${slider.value.toInt()}; actual packets: $packets",
            packets.contains(65563 to listOf(slider.value.toInt())))
        assertTrue(packets.contains(65559 to listOf(0)))
        assertFalse(packets.contains(65559 to listOf(1)))
        touch(slider, MotionEvent.ACTION_UP, downTime, 0.7f)
        idle()
        assertFalse(fragment.effectHeader.isChecked)
        assertFalse(prefs.getBoolean("v_reverb", false))
        assertEquals(beforePeq, peq.all)
    }

    @Test fun repeatedMovesAndReleaseDoNotBroadcastDuplicateValueAndCancelRetainsLatestStep() {
        val fragment = launch()
        val slider = row(fragment, "v_reverb_size").findViewById<Slider>(R.id.seekbar)
        val downTime = start(slider)
        touch(slider, MotionEvent.ACTION_MOVE, downTime, 0.6f)
        idle()
        val accepted = prefs.getInt("v_reverb_size", -1)
        val afterMove = broadcasts
        repeat(5) { touch(slider, MotionEvent.ACTION_MOVE, downTime, 0.6f) }
        idle()
        assertEquals(afterMove, broadcasts)
        touch(slider, MotionEvent.ACTION_CANCEL, downTime, 0.6f)
        idle()
        assertEquals(accepted, prefs.getInt("v_reverb_size", -1))
        assertEquals(accepted, fragment.findPreference<ViperSliderPreference>("v_reverb_size")!!.value)
        assertEquals(afterMove, broadcasts)
        val secondDown = start(slider)
        touch(slider, MotionEvent.ACTION_MOVE, secondDown, 0.8f)
        idle()
        val beforeUp = broadcasts
        touch(slider, MotionEvent.ACTION_UP, secondDown, 0.8f)
        idle()
        assertEquals(beforeUp, broadcasts)
        assertEquals(slider.value.toInt(), prefs.getInt("v_reverb_size", -1))
    }

    @Test fun rejectedDragAndProgrammaticSliderChangesDoNotSaveOrNotifyDriver() {
        prefs.edit().putInt("v_reverb_size", 30).commit()
        val fragment = launch()
        val slider = row(fragment, "v_reverb_size").findViewById<Slider>(R.id.seekbar)
        val preference = fragment.findPreference<ViperSliderPreference>("v_reverb_size")!!
        preference.setOnPreferenceChangeListener { _, _ -> false }
        val before = broadcasts
        val downTime = start(slider)
        touch(slider, MotionEvent.ACTION_MOVE, downTime, 0.8f)
        idle()
        assertEquals(30, prefs.getInt("v_reverb_size", -1))
        assertEquals(30f, slider.value, 0f)
        touch(slider, MotionEvent.ACTION_UP, downTime, 0.8f)
        slider.value = 70f
        idle()
        assertEquals(30, prefs.getInt("v_reverb_size", -1))
        assertEquals(before, broadcasts)
    }

    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = HOST_ID })
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(HOST_ID, ViperPreferenceFragment(), "viper").commitNow()
        }
        companion object { const val HOST_ID = 0x250013 }
    }
}
