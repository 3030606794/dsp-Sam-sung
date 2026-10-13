package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
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

/** Exercises the real precise editor, including user slider events and its confirmation path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqFineSliderUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val repository get() = PeqRepository(app)
    private val locks get() = PeqBandLockStore(app)
    private var controller: ActivityController<HostActivity>? = null

    @Before fun clear() {
        listOf(Constants.PREF_GEQ, PeqBandLockStore.PREFERENCES, PeqEditorPreferences.PREFERENCES).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }
    @After fun close() { controller?.pause()?.stop()?.destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun layout(view: View, heightMode: Int = View.MeasureSpec.EXACTLY) {
        idle()
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((820 * density).toInt(), heightMode))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        idle()
    }
    private fun launch(state: PeqState) {
        assertTrue(repository.save(state))
        state.bands.forEach { locks.setBandLocked(it.id, false) }
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout(controller!!.get().findViewById(android.R.id.content))
    }
    private fun precise(): AlertDialog {
        layout(controller!!.get().findViewById(android.R.id.content))
        val list = fragment().requireView().findViewById<RecyclerView>(R.id.peq_band_list)
        list.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.peq_band_number).performClick()
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also {
            assertTrue(it.isShowing)
            layout(it.window!!.decorView, View.MeasureSpec.AT_MOST)
        }
    }
    private fun dragToEndpoint(slider: Slider, right: Boolean) {
        assertTrue(slider.isEnabled)
        assertTrue("The real slider must have a measured touch track", slider.trackWidth > 0)
        val start = slider.trackSidePadding + slider.trackWidth * slider.value
        val target = slider.trackSidePadding + if (right) slider.trackWidth.toFloat() else 0f
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to start, MotionEvent.ACTION_MOVE to target,
            MotionEvent.ACTION_UP to target).forEachIndexed { index, (action, x) ->
            val event = MotionEvent.obtain(now, now + index * 30, action, x, slider.height / 2f, 0)
            try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        idle()
    }
    private fun confirm(dialog: AlertDialog, expected: PeqState) {
        val listening = dialog.findViewById<View>(R.id.peq_band_monitor)!!.isSelected
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertNull(dialog.findViewById<TextInputLayout>(R.id.peq_quality_box)!!.error)
        assertFalse("A valid endpoint must confirm, rather than leave an error dialog", dialog.isShowing)
        assertEquals(expected, repository.load())
        assertEquals(expected, fragment().editorState)
        assertEquals(if (listening) expected else null, PeqPreviewSession.current())
    }

    @Test fun bandwidthSliderAtOneKhzConfirmsBothExactQBoundsDespiteRoundedDisplay() {
        val original = PeqState(listOf(PeqBand("boundary", frequency = 1000.0, q = 30.0,
            qualityDisplay = PeqQualityDisplay.BW)), qRange = 30.0)
        launch(original)
        // First go to the widest bandwidth / minimum Q, then the narrowest / maximum Q.
        listOf(true to PeqModel.MIN_Q, false to original.qRange).forEach { (right, expectedQ) ->
            val before = repository.load()
            val editor = precise()
            dragToEndpoint(editor.findViewById<Slider>(R.id.peq_quality_slider)!!, right)
            assertEquals(PeqQualityEditor.format(PeqResponse.bandwidthOctaves(expectedQ, 1000.0)),
                editor.findViewById<EditText>(R.id.peq_quality_input)!!.text.toString())
            assertEquals(before, repository.load())
            val expected = original.copy(bands = listOf(original.bands.single().copy(q = expectedQ)))
            confirm(editor, expected)
            assertEquals(expectedQ, repository.load().bands.single().q, 0.0)
        }
    }

    @Test fun qualityStepLargerThanRangeStillReachesAndConfirmsTheRightEndpoint() {
        val original = PeqState(listOf(PeqBand("large-step", frequency = 1000.0, q = 1.0)),
            qStep = 100.0, qRange = 30.0)
        launch(original)
        val editor = precise()
        val slider = editor.findViewById<Slider>(R.id.peq_quality_slider)!!
        val beforeKey = slider.value
        assertTrue(slider.requestFocus())
        assertTrue(slider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue(slider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue("Fine adjustments must advance even when the card step exceeds the whole range",
            slider.value > beforeKey && slider.value < 1f)
        val input = editor.findViewById<EditText>(R.id.peq_quality_input)!!
        assertEquals(1.001, input.text.toString().toDouble(), 0.0)
        slider.layoutDirection = View.LAYOUT_DIRECTION_RTL
        slider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        slider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(1.002, input.text.toString().toDouble(), 0.0)
        slider.isEnabled = false
        slider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        slider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(1.002, input.text.toString().toDouble(), 0.0)
        slider.isEnabled = true
        slider.layoutDirection = View.LAYOUT_DIRECTION_LTR
        dragToEndpoint(slider, right = true)
        assertEquals(1f, slider.value, 0f)
        assertEquals(30.0, editor.findViewById<EditText>(R.id.peq_quality_input)!!.text.toString().toDouble(), 0.0)
        assertEquals(original, repository.load())
        confirm(editor, original.copy(bands = listOf(original.bands.single().copy(q = 30.0))))
    }

    @Test fun changingFrequencyWhileBandwidthIsLockedThenUnlockingDoesNotDriftStoredQ() {
        val original = PeqState(listOf(PeqBand("locked-bw", frequency = 1000.0, q = 7.123456789,
            qualityDisplay = PeqQualityDisplay.BW)), qRange = 30.0)
        launch(original)
        val editor = precise()
        val input = editor.findViewById<EditText>(R.id.peq_quality_input)!!
        val firstBandwidth = input.text.toString()
        editor.findViewById<View>(R.id.peq_quality_lock)!!.performClick()
        assertFalse(input.isEnabled)
        assertTrue(locks.isFieldLocked("locked-bw", PeqEditField.QUALITY))
        editor.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("16000")
        idle()
        assertNotEquals(firstBandwidth, input.text.toString())
        assertEquals(PeqQualityEditor.format(PeqResponse.bandwidthOctaves(original.bands.single().q, 16000.0)),
            input.text.toString())
        editor.findViewById<View>(R.id.peq_quality_lock)!!.performClick()
        assertTrue(input.isEnabled)
        assertFalse(locks.isFieldLocked("locked-bw", PeqEditField.QUALITY))
        editor.findViewById<View>(R.id.peq_band_monitor)!!.performClick()
        assertEquals(original.bands.single().q, PeqPreviewSession.current()!!.bands.single().q, 0.0)
        assertEquals(original, repository.load())
        confirm(editor, original.copy(bands = listOf(original.bands.single().copy(frequency = 16000.0))))
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_graphic_eq)
            setSupportActionBar(findViewById(R.id.toolbar))
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .replace(R.id.params, PeqFragment(), "peq").commitNow()
        }
    }
}
