package me.timschneeberger.rootlessjamesdsp.fusion

import android.animation.LayoutTransition
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
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
import me.timschneeberger.rootlessjamesdsp.fragment.DriverStatusDialogFragment
import me.timschneeberger.rootlessjamesdsp.preference.DropDownPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.view.OutputCardPinController
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import kotlin.math.roundToInt
import java.io.File

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
        app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit().clear().commit()
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

    @Test fun driverStatusSharesTheThresholdTextStartAndGainUsesTheShortTitle() {
        val activity = launch()
        val driver = activity.findViewById<TextView>(R.id.output_driver_status)
        val rate = activity.findViewById<TextView>(R.id.output_sample_rate)
        val threshold = activity.findViewById<View>(R.id.output_threshold)
            .findViewById<TextView>(R.id.output_label)
        val gain = activity.findViewById<View>(R.id.output_postgain)
            .findViewById<TextView>(R.id.output_label)
        // GridLayout reparses only individual left/top/right/bottom margins, so the
        // include's layout_marginHorizontal is not applied here. Compare real text
        // positions rather than adding that nominal 6 dp to the value row's 2 dp.
        val driverPosition = IntArray(2).also(driver::getLocationOnScreen)
        val thresholdPosition = IntArray(2).also(threshold::getLocationOnScreen)
        assertEquals("The status starts directly above the threshold label",
            thresholdPosition[0] + threshold.paddingLeft,
            driverPosition[0] + driver.paddingLeft)
        assertSame("Driver and processing rate remain on one row", driver.parent, rate.parent)
        assertEquals("增益", gain.text.toString())
    }

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
        val icon = container.findViewById<ImageView>(android.R.id.icon)
        assertNotNull(icon.drawable)
        assertEquals("Header speaker crops transparent sides without shrinking its visible ink",
            (14 * activity.resources.displayMetrics.density).roundToInt(), icon.drawable.intrinsicWidth)
        val driver = activity.findViewById<TextView>(R.id.output_driver_status)
        val iconPosition = IntArray(2).also(icon::getLocationOnScreen)
        val driverPosition = IntArray(2).also(driver::getLocationOnScreen)
        val summaryPosition = IntArray(2).also(summary::getLocationOnScreen)
        assertEquals("Route icon shares the driver's text-start vertical axis",
            driverPosition[0] + driver.compoundPaddingLeft, iconPosition[0])
        assertEquals("The name has no hidden start padding", 0, summary.compoundPaddingLeft)
        assertEquals("Name starts exactly 3 mm after the route icon",
            iconPosition[0] + icon.width + activity.resources.getDimensionPixelSize(R.dimen.output_route_icon_title_gap),
            summaryPosition[0] + summary.compoundPaddingLeft)
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
        assertEquals(1.5f, slider(activity, R.id.output_release).value, 0f)
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
        val connectedColor = activity.getColor(R.color.output_status_connected)
        val disconnectedColor = activity.getColor(R.color.output_status_disconnected)
        val tracker = OutputRuntimeStatus.Tracker()
        val sample = OutputRuntimeStatus.Session("real-session", true, 96000, 100)
        fragment.renderRuntimeStatus(tracker.update(listOf(sample)))
        assertEquals(app.getString(R.string.output_rate_unknown), rate.text.toString())
        assertEquals(connectedColor, driver.currentTextColor)
        assertEquals(disconnectedColor, rate.currentTextColor)
        fragment.renderRuntimeStatus(tracker.update(listOf(sample.copy(processedFrames = 612))))
        assertEquals(app.getString(R.string.output_rate_value, "96"), rate.text.toString())
        assertEquals(app.getString(R.string.output_driver_connected), driver.text.toString())
        assertEquals(connectedColor, rate.currentTextColor)
        fragment.renderRuntimeStatus(tracker.update(listOf(sample.copy(processedFrames = 612))))
        assertEquals(app.getString(R.string.output_rate_idle), rate.text.toString())
        assertEquals(disconnectedColor, rate.currentTextColor)
        fragment.renderRuntimeStatus(tracker.update(listOf(sample.copy(hostRate = null, processedFrames = null))))
        assertEquals(app.getString(R.string.output_rate_unknown), rate.text.toString())
        fragment.renderRuntimeStatus(tracker.update(emptyList()))
        assertEquals(app.getString(R.string.output_driver_disconnected), driver.text.toString())
        assertEquals(disconnectedColor, driver.currentTextColor)
        assertEquals(app.getString(R.string.output_rate_idle), rate.text.toString())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun pinMovesTheSameOutputCardIntoAndOutOfTheScrollingListAndRestoresOnRecreation() {
        prefs.edit().putFloat(app.getString(R.string.key_output_postgain), 2.7f).commit()
        val activity = launch()
        val card = activity.findViewById<View>(R.id.pinned_output_card)
        val originalParent = card.parent
        val scrollingCards = activity.findViewById<LinearLayout>(R.id.card_container)
        val pin = activity.findViewById<View>(R.id.output_card_pin)
        val gain = slider(activity, R.id.output_postgain)
        val outputFragment = activity.supportFragmentManager.findFragmentById(R.id.card_output_control)
        assertTrue(pin.isSelected)
        assertEquals(app.getString(R.string.output_card_unpin), pin.contentDescription)
        saveScreen(activity, "home-output-pinned")
        broadcasts = 0
        pin.performClick()
        layout(activity)
        assertSame(scrollingCards, card.parent)
        assertSame(card, scrollingCards.getChildAt(0))
        assertSame(gain, slider(activity, R.id.output_postgain))
        assertSame(outputFragment, activity.supportFragmentManager.findFragmentById(R.id.card_output_control))
        assertEquals(2.7f, gain.value, 0f)
        assertFalse(pin.isSelected)
        assertEquals(app.getString(R.string.output_card_pin), pin.contentDescription)
        assertEquals("Pinning does not change audio parameters", 0, broadcasts)
        saveScreen(activity, "home-output-unpinned")
        val scroll = activity.findViewById<NestedScrollView>(R.id.dsp_scrollview)
        val before = IntArray(2).also(card::getLocationOnScreen)
        scroll.scrollTo(0, 500)
        idle()
        val after = IntArray(2).also(card::getLocationOnScreen)
        assertTrue("Unpinned output now scrolls with the effects", after[1] < before[1])

        controller!!.recreate()
        val recreated = controller!!.get()
        layout(recreated)
        val restoredCard = recreated.findViewById<View>(R.id.pinned_output_card)
        assertSame(recreated.findViewById<View>(R.id.card_container), restoredCard.parent)
        assertEquals(2.7f, slider(recreated, R.id.output_postgain).value, 0f)
        recreated.findViewById<View>(R.id.output_card_pin).performClick()
        layout(recreated)
        assertNotSame(recreated.findViewById<View>(R.id.card_container), restoredCard.parent)
        assertTrue(recreated.findViewById<View>(R.id.output_card_pin).isSelected)
        assertNotSame(originalParent, restoredCard.parent)
        val restoredScrollingCards = recreated.findViewById<LinearLayout>(R.id.card_container)
        val transition = restoredScrollingCards.layoutTransition
        assertNotNull("The host uses the production card-container transition", transition)
        repeat(3) {
            val restoredPin = recreated.findViewById<View>(R.id.output_card_pin)
            restoredPin.performClick()
            layout(recreated)
            assertSame(restoredScrollingCards, restoredCard.parent)
            // An in-flight disappearing transition must not retain the source parent.
            restoredScrollingCards.startViewTransition(restoredCard)
            restoredPin.performClick()
            layout(recreated)
            assertNotSame(restoredScrollingCards, restoredCard.parent)
            assertSame("Moving the card restores the original transition", transition, restoredScrollingCards.layoutTransition)
            assertTrue(transition!!.isTransitionTypeEnabled(LayoutTransition.DISAPPEARING))
            assertTrue(transition.isTransitionTypeEnabled(LayoutTransition.CHANGING))
            assertEquals(2.7f, slider(recreated, R.id.output_postgain).value, 0f)
        }
    }

    private fun saveScreen(activity: OutputHostActivity, name: String) {
        val root = activity.findViewById<View>(android.R.id.content)
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(image))
            val directory = File("build/reports/ui38-fix2").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun driverInformationIsIndependentOfTheRouteMenuAndPinAndCanRefreshAndClose() {
        val activity = launch()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.card_device_profiles) as DeviceProfilesCardFragment
        val profile = fragment.findPreference<DropDownPreference>(app.getString(R.string.key_profile_active))!!
        var routeMenuClicks = 0
        profile.setOnPreferenceClickListener { routeMenuClicks++; false }
        val parametersBefore = prefs.all.toMap()
        val pin = activity.findViewById<View>(R.id.output_card_pin)
        val info = activity.findViewById<View>(R.id.output_driver_info)
        val density = activity.resources.displayMetrics.density
        assertEquals((40 * density).roundToInt(), info.width)
        assertEquals(pin.height, info.height)
        assertTrue(info.right <= pin.left)
        broadcasts = 0
        info.performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertNotNull(activity.supportFragmentManager.findFragmentByTag(DriverStatusDialogFragment.TAG))
        fun awaitDetails() {
            repeat(200) {
                idle()
                if (dialog.findViewById<TextView>(R.id.driver_status_detail)?.text
                    ?.contains("当前输出：") == true) return
                Thread.sleep(10)
            }
            fail("Read-only driver details did not finish")
        }
        awaitDetails()
        assertEquals(0, routeMenuClicks)
        assertTrue(pin.isSelected)
        assertEquals(parametersBefore, prefs.all)
        assertEquals(0, broadcasts)
        assertTrue(dialog.findViewById<TextView>(R.id.driver_status_detail)!!.text
            .contains("驱动连接：未知 / 未提供"))
        val decor = dialog.window!!.decorView
        val width = (360 * density).roundToInt()
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((660 * density).roundToInt(), View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, width, decor.measuredHeight)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(com.google.android.material.color.MaterialColors.getColor(decor, android.R.attr.colorBackground))
            decor.draw(Canvas(bitmap))
            val directory = File("build/reports/ui38-fix3").apply { mkdirs() }
            File(directory, "driver-status-details.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        awaitDetails()
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(0, broadcasts)
        assertEquals(parametersBefore, prefs.all)
        assertTrue(pin.isSelected)
        // The original title-row menu still owns its separate action.
        fragment.listView.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
        assertEquals(1, routeMenuClicks)
    }

    class OutputHostActivity : FragmentActivity() {
        private var pinController: OutputCardPinController? = null
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            // Inflate the shipping main-page layout without starting the root audio service.
            setContentView(R.layout.fragment_dsp)
            pinController = OutputCardPinController(
                findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout,
                getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE))
            findViewById<LinearLayout>(R.id.card_container).layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
            }
            findViewById<LinearLayout>(R.id.card_container).addView(View(this),
                LinearLayout.LayoutParams(-1, (1800 * resources.displayMetrics.density).roundToInt()))
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(R.id.card_device_profiles, DeviceProfilesCardFragment.newInstance())
                .add(R.id.card_output_control, OutputControlFragment()).commitNow()
        }

        override fun onDestroy() {
            pinController?.close()
            super.onDestroy()
        }
    }
}
