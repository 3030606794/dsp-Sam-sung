package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.OutputControlFragment
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
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
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { broadcasts++ }
    }

    @Before fun prepare() {
        prefs.edit().clear().commit()
        app.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))
    }

    @After fun cleanUp() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller?.pause()?.stop()?.destroy()
        app.unregisterLocalReceiver(receiver)
        idle()
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
        arrayOf(R.id.output_threshold, R.id.output_release, R.id.output_postgain, R.id.output_balance).forEach {
            assertTrue(slider(activity, it).isShown)
            assertTrue(slider(activity, it).isEnabled)
            assertTrue(slider(activity, it).width > 0)
        }
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

    @Test fun numericInputPreservesSignedFloatPrecisionAndRejectsOutOfRangeValues() {
        val activity = launch()
        activity.findViewById<View>(R.id.output_threshold)
            .findViewById<View>(R.id.output_value_row).performClick()
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

    class OutputHostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            // Inflate the shipping main-page layout without starting the root audio service.
            setContentView(R.layout.fragment_dsp)
            findViewById<View>(R.id.translation_notice).visibility = View.GONE
            findViewById<View>(R.id.update_notice).visibility = View.GONE
            findViewById<LinearLayout>(R.id.card_container).addView(View(this),
                LinearLayout.LayoutParams(-1, (1800 * resources.displayMetrics.density).roundToInt()))
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(R.id.card_output_control, OutputControlFragment()).commitNow()
        }
    }
}
