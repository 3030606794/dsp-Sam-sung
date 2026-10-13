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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.OutputControlFragment
import me.timschneeberger.rootlessjamesdsp.fragment.DeviceProfilesCardFragment
import me.timschneeberger.rootlessjamesdsp.preference.DropDownPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
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
import org.robolectric.shadows.ShadowDialog
import kotlin.math.roundToInt

/** Real output views, real main-page layout, and real preference/broadcast delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PinnedOutputControlTest {
    private var controller: ActivityController<OutputHostActivity>? = null
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_OUTPUT, Context.MODE_PRIVATE)
    private var broadcasts = 0
    private lateinit var routingObserver: RoutingObserver
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { broadcasts++ }
    }

    @Before fun prepare() {
        prefs.edit().clear().commit()
        stopKoin()
        val preferences = Preferences(app)
        routingObserver = RoutingObserver(app)
        routingObserver.currentDevice = routingObserver.Device("speaker", "", RoutingObserver.DeviceGroup.SPEAKER)
        startKoin { modules(module {
            single { preferences.App() }
            single { routingObserver }
        }) }
        app.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))
    }

    @After fun cleanUp() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller?.pause()?.stop()?.destroy()
        app.unregisterLocalReceiver(receiver)
        idle()
        stopKoin()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): OutputHostActivity {
        val next = Robolectric.buildActivity(OutputHostActivity::class.java)
        controller = next
        next.setup().visible()
        layout(next.get())
        return next.get()
    }

    private fun layout(activity: OutputHostActivity) {
        idle()
        val density = activity.resources.displayMetrics.density
        val width = (411 * density).roundToInt()
        val height = (720 * density).roundToInt()
        activity.findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            layout(0, 0, width, height)
        }
        idle()
    }

    private fun slider(activity: OutputHostActivity, tile: Int): Slider =
        activity.findViewById<View>(tile).findViewById(R.id.output_slider)

    @Test fun allOutputControlsStayFixedWhileEffectParametersScroll() {
        val activity = launch()
        val card = activity.findViewById<View>(R.id.pinned_output_card)
        val scroll = activity.findViewById<NestedScrollView>(R.id.dsp_scrollview)
        val before = IntArray(2).also(card::getLocationOnScreen)
        assertTrue("Pinned card leaves most of the screen for effects", card.height < scroll.height)
        scroll.scrollTo(0, 500)
        idle()
        assertTrue(scroll.scrollY > 0)
        assertArrayEquals(before, IntArray(2).also(card::getLocationOnScreen))
        val profile = activity.findViewById<View>(R.id.card_device_profiles)
        val output = activity.findViewById<View>(R.id.card_output_control)
        assertSame("Device profile and output share the same card content", profile.parent, output.parent)
        assertSame(card, (profile.parent as View).parent)
        arrayOf(R.id.output_threshold, R.id.output_release, R.id.output_postgain, R.id.output_balance).forEach {
            assertTrue(slider(activity, it).isShown)
            assertTrue(slider(activity, it).isEnabled)
            assertTrue(slider(activity, it).width > 0)
        }
    }

    @Test fun compactDeviceProfileKeepsCurrentRouteIconAndMenuWithoutHidingOutput() {
        val activity = launch()
        val container = activity.findViewById<View>(R.id.card_device_profiles)
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.card_device_profiles)
            as DeviceProfilesCardFragment
        val summary = container.findViewById<TextView>(android.R.id.summary)
        assertEquals(routingObserver.currentDevice!!.name, summary.text.toString())
        assertNotNull(container.findViewById<ImageView>(android.R.id.icon).drawable)
        val profile = fragment.findPreference<DropDownPreference>(app.getString(R.string.key_profile_active))!!
        assertNotNull(profile.onMenuItemClick)
        assertArrayEquals(app.resources.getTextArray(R.array.manange_profiles_menu), profile.entries)
        val nextDevice = routingObserver.Device("USB DAC", "usb", RoutingObserver.DeviceGroup.USB)
        routingObserver.currentDevice = nextDevice
        layout(activity)
        assertEquals(nextDevice.name, container.findViewById<TextView>(android.R.id.summary).text.toString())
        assertTrue("The route is a compact single row", container.height <= 64 * activity.resources.displayMetrics.density)
        container.visibility = View.GONE
        layout(activity)
        assertTrue(activity.findViewById<View>(R.id.pinned_output_card).isShown)
        assertTrue(slider(activity, R.id.output_balance).isShown)
    }

    @Test fun existingFloatValuesAndBalanceRestoreAcrossRecreationAndProfileReload() {
        prefs.edit()
            .putFloat(app.getString(R.string.key_limiter_threshold), -6.25f)
            .putFloat(app.getString(R.string.key_limiter_release), 350.5f)
            .putFloat(app.getString(R.string.key_output_postgain), 1.3f)
            .putInt(OutputControlFragment.BALANCE_KEY, -31).commit()
        launch()
        controller!!.recreate()
        val activity = controller!!.get()
        layout(activity)
        assertEquals(-6.25f, slider(activity, R.id.output_threshold).value, 0f)
        assertEquals(350.5f, slider(activity, R.id.output_release).value, 0f)
        assertEquals(1.3f, slider(activity, R.id.output_postgain).value, 0f)
        assertEquals(-31f, slider(activity, R.id.output_balance).value, 0f)
        assertEquals(-60f, slider(activity, R.id.output_threshold).valueFrom, 0f)
        assertEquals(-0.1f, slider(activity, R.id.output_threshold).valueTo, 0f)
        assertEquals(1.5f, slider(activity, R.id.output_release).valueFrom, 0f)
        assertEquals(500f, slider(activity, R.id.output_release).valueTo, 0f)
        prefs.edit().clear().putInt(OutputControlFragment.BALANCE_KEY, 64).commit()
        app.sendLocalBroadcast(Intent(Constants.ACTION_PRESET_LOADED))
        idle()
        assertEquals(-0.1f, slider(activity, R.id.output_threshold).value, 0f)
        assertEquals(60f, slider(activity, R.id.output_release).value, 0f)
        assertEquals(0f, slider(activity, R.id.output_postgain).value, 0f)
        assertEquals(64f, slider(activity, R.id.output_balance).value, 0f)
    }

    @Test fun draggingBalancePersistsAndBroadcastsBeforeFingerRelease() {
        val activity = launch()
        val balance = slider(activity, R.id.output_balance)
        val target = balance.width * .85f
        val y = balance.height * .5f
        val down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, target, y, 0)
        val move = MotionEvent.obtain(100, 120, MotionEvent.ACTION_MOVE, target, y, 0)
        try {
            balance.dispatchTouchEvent(down)
            balance.dispatchTouchEvent(move)
            idle()
            assertTrue("Continuous output update arrives before ACTION_UP",
                prefs.getInt(OutputControlFragment.BALANCE_KEY, 0) > 0)
            assertTrue(broadcasts > 0)
            assertTrue(prefs.all[OutputControlFragment.BALANCE_KEY] is Int)
        } finally {
            val up = MotionEvent.obtain(100, 140, MotionEvent.ACTION_UP, target, y, 0)
            balance.dispatchTouchEvent(up)
            up.recycle()
            down.recycle()
            move.recycle()
        }
    }

    @Test fun doubleTapBalanceResetsPersistsAndKeepsZeroAfterFingerRelease() {
        prefs.edit().putInt(OutputControlFragment.BALANCE_KEY, 60).commit()
        val activity = launch()
        val balance = slider(activity, R.id.output_balance)
        val x = balance.width * .78f
        val y = balance.height * .5f
        val start = SystemClock.uptimeMillis() + 10
        fun touch(action: Int, down: Long, at: Long) {
            val event = MotionEvent.obtain(down, at, action, x, y, 0)
            try { balance.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, start, start)
        touch(MotionEvent.ACTION_UP, start, start + 20)
        touch(MotionEvent.ACTION_DOWN, start + 100, start + 100)
        touch(MotionEvent.ACTION_UP, start + 100, start + 120)
        idle()
        assertEquals(0f, balance.value, 0f)
        assertEquals(0, prefs.getInt(OutputControlFragment.BALANCE_KEY, -1))
        assertTrue(broadcasts > 0)
        controller!!.recreate()
        layout(controller!!.get())
        assertEquals(0f, slider(controller!!.get(), R.id.output_balance).value, 0f)
    }

    @Test fun numericInputPreservesSignedFloatPrecisionAndRejectsOutOfRangeValues() {
        val activity = launch()
        activity.findViewById<View>(R.id.output_threshold)
            .findViewById<View>(R.id.output_value_row).performClick()
        // OnShow installs the validation click handler through the main looper.
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val input = dialog.findViewById<EditText>(android.R.id.edit)!!
        input.setText("-1000")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertNotNull(input.error)
        input.setText("-3.25")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(-3.25f, prefs.getFloat(app.getString(R.string.key_limiter_threshold), 0f), 0f)
        assertEquals(-3.25f, slider(activity, R.id.output_threshold).value, 0f)
        assertTrue(broadcasts > 0)
    }

    @Test fun runtimeHeaderUsesHostRateAndClearsItForIdleOrUnsupportedDriver() {
        val activity = launch()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.card_output_control) as OutputControlFragment
        val rate = activity.findViewById<TextView>(R.id.output_sample_rate)
        val driver = activity.findViewById<TextView>(R.id.output_driver_status)
        val tracker = OutputRuntimeStatus.Tracker()
        val sample = OutputRuntimeStatus.Session("real-session", true, 96000, 100)
        fragment.renderRuntimeStatus(tracker.update(listOf(sample)))
        assertEquals(app.getString(R.string.output_rate_unknown), rate.text.toString())
        fragment.renderRuntimeStatus(tracker.update(listOf(sample.copy(processedFrames = 612))))
        assertEquals(app.getString(R.string.output_rate_value, "96"), rate.text.toString())
        assertEquals(app.getString(R.string.output_driver_connected), driver.text.toString())
        fragment.renderRuntimeStatus(tracker.update(listOf(sample.copy(processedFrames = 612))))
        assertEquals(app.getString(R.string.output_rate_idle), rate.text.toString())
        fragment.renderRuntimeStatus(tracker.update(listOf(sample.copy(hostRate = null, processedFrames = null))))
        assertEquals(app.getString(R.string.output_rate_unknown), rate.text.toString())
        fragment.renderRuntimeStatus(tracker.update(emptyList()))
        assertEquals(app.getString(R.string.output_driver_disconnected), driver.text.toString())
        assertEquals(app.getString(R.string.output_rate_idle), rate.text.toString())
    }

    @Test fun currentOutputRemainsVisibleWhenDeviceProfileControlsAreHidden() {
        val activity = launch()
        val settings = app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
        settings.edit().putBoolean(app.getString(R.string.key_device_profiles_enable), false).commit()
        routingObserver.currentDevice = routingObserver.Device("FiiO KA17", "usb", RoutingObserver.DeviceGroup.USB)
        layout(activity)
        val route = activity.findViewById<TextView>(R.id.output_route)
        assertTrue(route.isShown)
        assertTrue(route.text.toString().contains("FiiO KA17"))
        routingObserver.currentDevice = null
        idle()
        assertEquals(app.getString(R.string.output_route_value, app.getString(R.string.output_route_unknown)), route.text.toString())
        settings.edit().putBoolean(app.getString(R.string.key_device_profiles_enable), true).commit()
        idle()
        assertEquals(View.GONE, route.visibility)
    }

    class OutputHostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            // Inflate the shipping main-page layout without starting the root audio service.
            setContentView(R.layout.fragment_dsp)
            findViewById<LinearLayout>(R.id.card_container).addView(View(this),
                LinearLayout.LayoutParams(-1, (1800 * resources.displayMetrics.density).roundToInt()))
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(R.id.card_device_profiles, DeviceProfilesCardFragment.newInstance())
                .add(R.id.card_output_control, OutputControlFragment()).commitNow()
        }
    }
}
