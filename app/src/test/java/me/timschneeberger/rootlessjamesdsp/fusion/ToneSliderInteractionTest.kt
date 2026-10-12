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
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceGroupAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ToneSliderInteractionTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = ToneController.preferences(application)
    private var controller: ActivityController<ToneHostActivity>? = null
    private var broadcasts = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { broadcasts++ }
    }

    @Before fun setup() {
        prefs.edit().clear().commit()
        application.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        idle()
        application.unregisterLocalReceiver(receiver)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    private fun touch(button: View, action: Int, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            button.width / 2f, button.height / 2f, 0)
        try { button.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun startHold(button: View): Long = SystemClock.uptimeMillis().also {
        touch(button, MotionEvent.ACTION_DOWN, it)
        advance(ViewConfiguration.getLongPressTimeout().toLong() + 1)
    }
    private fun layout() {
        idle()
        val view = controller!!.get().findViewById<View>(android.R.id.content)
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 2400)
        idle()
    }

    private fun launch(): TonePreferenceFragment {
        controller = Robolectric.buildActivity(ToneHostActivity::class.java).setup().visible()
        layout()
        return controller!!.get().supportFragmentManager.findFragmentByTag("tone") as TonePreferenceFragment
    }

    private fun row(fragment: TonePreferenceFragment, key: String): View {
        // Expansion posts PreferenceGroupAdapter's visible-list rebuild to the main looper.
        // Resolve the position only after that real update, otherwise a newly shown row is -1.
        layout()
        val index = (fragment.listView.adapter as PreferenceGroupAdapter).getPreferenceAdapterPosition(key)
        assertTrue("Expanded preference must appear in the adapter: $key", index >= 0)
        fragment.listView.scrollToPosition(index)
        layout()
        return requireNotNull(fragment.listView.findViewHolderForAdapterPosition(index)) {
            "Preference $key at position $index must have a visible, bound row"
        }.itemView
    }

    @Test fun buttonsEditDisabledEffectByExactlyOneAndStopAtEveryRangeBoundary() {
        val fragment = launch()
        val group = fragment.findPreference<SwitchPreferenceGroup>(ToneController.ENABLED)!!
        assertEquals("MSEB", group.title.toString())
        assertFalse(group.isChecked)
        assertFalse(group.isExpanded)
        group.setExpanded(true)
        layout()
        val key = ToneController.KEYS[0]
        val preference = fragment.findPreference<ToneSliderPreference>(key)!!
        val range = fragment.findPreference<ToneRangePreference>(ToneController.RANGE)!!
        for (limit in listOf(100, 40, 20)) {
            if (range.range != limit) assertTrue(range.selectRange(limit))
            preference.value = limit - 1
            val bound = row(fragment, key)
            val minus = bound.findViewById<MaterialButton>(R.id.fusion_tone_decrease)
            val plus = bound.findViewById<MaterialButton>(R.id.fusion_tone_increase)
            assertTrue(plus.left > minus.left)
            assertTrue(bound.findViewById<Slider>(R.id.fusion_tone_slider).isEnabled)
            val before = broadcasts
            plus.performClick()
            idle()
            assertEquals(limit, prefs.getInt(key, 0))
            assertFalse(plus.isEnabled)
            assertTrue(broadcasts > before)
            assertTrue(prefs.all[key] is Int)
            minus.performClick()
            idle()
            assertEquals(limit - 1, prefs.getInt(key, 0))
            preference.value = -limit + 1
            val rebound = row(fragment, key)
            rebound.findViewById<MaterialButton>(R.id.fusion_tone_decrease).performClick()
            idle()
            assertEquals(-limit, prefs.getInt(key, 0))
            assertFalse(rebound.findViewById<MaterialButton>(R.id.fusion_tone_decrease).isEnabled)
            assertFalse("Editing must not activate MSEB", prefs.getBoolean(ToneController.ENABLED, false))
            assertEquals("总温度：${-limit}", rebound.findViewById<TextView>(R.id.fusion_tone_slider_title).text.toString())
            assertEquals("冷 / 清脆", rebound.findViewById<TextView>(R.id.fusion_tone_keyword_left).text.toString())
            assertEquals("暖 / 暗", rebound.findViewById<TextView>(R.id.fusion_tone_keyword_right).text.toString())
        }
    }

    @Test fun draggingPersistsAndBroadcastsBeforeReleasingFinger() {
        val fragment = launch()
        fragment.findPreference<SwitchPreferenceGroup>(ToneController.ENABLED)!!.setExpanded(true)
        val key = ToneController.KEYS[0]
        val slider = row(fragment, key).findViewById<Slider>(R.id.fusion_tone_slider)
        val x = slider.width * .8f
        val y = slider.height * .5f
        val down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, x, y, 0)
        val move = MotionEvent.obtain(100, 120, MotionEvent.ACTION_MOVE, x, y, 0)
        val up = MotionEvent.obtain(100, 140, MotionEvent.ACTION_UP, x, y, 0)
        try {
            slider.dispatchTouchEvent(down)
            slider.dispatchTouchEvent(move)
            idle()
            assertTrue(prefs.getInt(key, 0) > 0)
            assertTrue(broadcasts > 0)
        } finally {
            slider.dispatchTouchEvent(up)
            down.recycle(); move.recycle(); up.recycle()
        }
    }

    @Test fun doubleTapOnTrackResetsDisplayedAndPersistedValueToZero() {
        prefs.edit().putInt(ToneController.KEYS[0], 37).commit()
        val fragment = launch()
        fragment.findPreference<SwitchPreferenceGroup>(ToneController.ENABLED)!!.setExpanded(true)
        val key = ToneController.KEYS[0]
        val bound = row(fragment, key)
        val slider = bound.findViewById<Slider>(R.id.fusion_tone_slider)
        val x = slider.width * .8f
        val y = slider.height * .5f
        fun event(downTime: Long, time: Long, action: Int) {
            MotionEvent.obtain(downTime, time, action, x, y, 0).also {
                slider.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        event(1000, 1000, MotionEvent.ACTION_DOWN)
        event(1000, 1020, MotionEvent.ACTION_UP)
        event(1100, 1100, MotionEvent.ACTION_DOWN)
        event(1100, 1120, MotionEvent.ACTION_UP)
        idle()
        assertEquals(0, prefs.getInt(key, -1))
        assertEquals(0f, slider.value, 0f)
        assertEquals("总温度：0", bound.findViewById<TextView>(R.id.fusion_tone_slider_title).text.toString())
        assertTrue(broadcasts > 0)
        assertFalse(prefs.getBoolean(ToneController.ENABLED, false))
    }

    @Test fun holdingPlusAndMinusRepeatsOneUnitAtFixedIntervalsAndStopsOnReleaseOrCancel() {
        val fragment = launch()
        fragment.findPreference<SwitchPreferenceGroup>(ToneController.ENABLED)!!.setExpanded(true)
        val key = ToneController.KEYS[0]
        val bound = row(fragment, key)
        val plus = bound.findViewById<MaterialButton>(R.id.fusion_tone_increase)
        val minus = bound.findViewById<MaterialButton>(R.id.fusion_tone_decrease)
        val plusDown = startHold(plus)
        assertEquals(1, prefs.getInt(key, 0))
        repeat(4) { index ->
            advance(100)
            assertEquals(index + 2, prefs.getInt(key, 0))
        }
        touch(plus, MotionEvent.ACTION_UP, plusDown)
        val afterRelease = prefs.getInt(key, 0)
        advance(700)
        assertEquals(5, afterRelease)
        assertEquals(afterRelease, prefs.getInt(key, 0))
        val minusDown = startHold(minus)
        assertEquals(4, prefs.getInt(key, 0))
        repeat(3) { index ->
            advance(100)
            assertEquals(3 - index, prefs.getInt(key, 0))
        }
        touch(minus, MotionEvent.ACTION_CANCEL, minusDown)
        advance(700)
        assertEquals(1, prefs.getInt(key, 0))
        assertEquals(1f, bound.findViewById<Slider>(R.id.fusion_tone_slider).value, 0f)
        assertEquals("总温度：1", bound.findViewById<TextView>(R.id.fusion_tone_slider_title).text.toString())
        assertTrue(broadcasts > 0)
        assertFalse("Repeating an edit must not enable MSEB", prefs.getBoolean(ToneController.ENABLED, false))
    }

    @Test fun holdingStopsAtTheRangeLimitAndIsCancelledByRebindingPausingAndCollapsingMseb() {
        val fragment = launch()
        val group = fragment.findPreference<SwitchPreferenceGroup>(ToneController.ENABLED)!!
        group.setExpanded(true)
        val key = ToneController.KEYS[0]
        val preference = fragment.findPreference<ToneSliderPreference>(key)!!
        assertTrue(fragment.findPreference<ToneRangePreference>(ToneController.RANGE)!!.selectRange(20))
        preference.value = 19
        var plus = row(fragment, key).findViewById<MaterialButton>(R.id.fusion_tone_increase)
        val limitDown = startHold(plus)
        assertEquals(20, prefs.getInt(key, 0))
        assertFalse(plus.isEnabled)
        advance(1000)
        assertEquals(20, prefs.getInt(key, 0))
        touch(plus, MotionEvent.ACTION_UP, limitDown)

        preference.value = 0
        plus = row(fragment, key).findViewById(R.id.fusion_tone_increase)
        startHold(plus)
        assertEquals(1, prefs.getInt(key, 0))
        preference.value = 7 // A preference rebind must invalidate the old held button callback.
        row(fragment, key)
        advance(700)
        assertEquals(7, prefs.getInt(key, 0))

        plus = row(fragment, key).findViewById(R.id.fusion_tone_increase)
        startHold(plus)
        assertEquals(8, prefs.getInt(key, 0))
        controller!!.pause()
        advance(700)
        assertEquals(8, prefs.getInt(key, 0))
        controller!!.resume()
        val minus = row(fragment, key).findViewById<MaterialButton>(R.id.fusion_tone_decrease)
        startHold(minus)
        assertEquals(7, prefs.getInt(key, 0))
        group.setExpanded(false)
        layout()
        advance(700)
        assertEquals(7, prefs.getInt(key, 0))
        assertFalse(prefs.getBoolean(ToneController.ENABLED, false))
    }

    class ToneHostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = HOST })
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(HOST, TonePreferenceFragment(), "tone").commitNow()
        }
        companion object { const val HOST = 0x6011 }
    }
}
