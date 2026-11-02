package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqNyquistUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<HostActivity>
    private val host get() = controller.get()
    private val fragment get() = host.supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private val repository get() = PeqRepository(app)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun prepare() {
        app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        val band = PeqBand("rate", frequency = 1000.0, q = 2.0, qualityDisplay = PeqQualityDisplay.BW)
        PeqBandLockStore(app).setBandLocked(band.id, false)
        assertTrue(repository.save(PeqState(listOf(band), graphMaxFrequency = 768000.0)))
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        host.supportFragmentManager.beginTransaction().add(HostActivity.CONTENT, PeqFragment(), "peq").commitNow()
        layout()
    }
    @After fun close() { controller.pause().stop().destroy(); idle() }
    private fun layout() {
        idle()
        val view = host.findViewById<View>(android.R.id.content)
        val density = app.resources.displayMetrics.density
        val width = (411 * density).toInt(); val height = (820 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        idle()
    }
    private fun rate(rate: Double) {
        PeqFragment::class.java.getDeclaredMethod("applyProcessingRate", Double::class.javaPrimitiveType).apply {
            isAccessible = true; invoke(fragment, rate)
        }
        layout()
    }
    private fun row(): View = requireNotNull(fragment.requireView().findViewById<RecyclerView>(R.id.peq_band_list)
        .findViewHolderForAdapterPosition(0)).itemView
    private fun graphMaximum(): Double {
        val curve = fragment.requireView().findViewById<PeqResponseView>(R.id.peq_curve)
        return PeqResponseView::class.java.getDeclaredField("graphMaximum").apply { isAccessible = true }.getDouble(curve)
    }
    private fun settings(): AlertDialog {
        fragment.requireView().findViewById<View>(R.id.peq_settings).performClick(); idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun dragRight(slider: Slider) {
        val now = SystemClock.uptimeMillis()
        val start = (slider.value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)
        // In the last down-rate case the thumb is already at 1 while the saved center
        // remains above Nyquist. Explicitly touching that same endpoint must commit it.
        listOf(MotionEvent.ACTION_DOWN to start, MotionEvent.ACTION_MOVE to 1f,
            MotionEvent.ACTION_UP to 1f).forEachIndexed { index, (action, position) ->
            val event = MotionEvent.obtain(now, now + index * 20, action,
                slider.trackSidePadding + position * slider.trackWidth, slider.height / 2f, 0)
            try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        layout()
    }

    @Test fun realFrequencySliderAndButtonsFollow48000384000768000WithoutChangingBandwidth() {
        val q = fragment.editorState.bands.single().q
        for (rate in listOf(48000.0, 384000.0, 768000.0, 48000.0)) {
            val savedFrequency = fragment.editorState.bands.single().frequency
            rate(rate)
            assertEquals(savedFrequency, fragment.editorState.bands.single().frequency, 0.0)
            assertEquals(rate / 2, graphMaximum(), 0.0)
            dragRight(row().findViewById(R.id.peq_frequency_slider))
            assertEquals(rate / 2, fragment.editorState.bands.single().frequency, 0.0)
            assertFalse(row().findViewById<View>(R.id.peq_frequency_plus).isEnabled)
            assertEquals(q, fragment.editorState.bands.single().q, 0.0)
            row().findViewById<View>(R.id.peq_frequency_minus).performClick(); layout()
            assertEquals(rate / 2 - 1, fragment.editorState.bands.single().frequency, 0.0)
            row().findViewById<View>(R.id.peq_frequency_plus).performClick(); layout()
            assertEquals(rate / 2, fragment.editorState.bands.single().frequency, 0.0)
        }
    }

    @Test fun openSettingsTracksRateAndUneditedMaximumPreservesFutureHighRatePreference() {
        rate(384000.0)
        val dialog = settings()
        val input = requireNotNull(dialog.findViewById<EditText>(R.id.peq_graph_max))
        assertEquals("192000", input.text.toString())
        rate(48000.0)
        assertEquals("24000", input.text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); layout()
        assertFalse(dialog.isShowing)
        assertEquals(768000.0, repository.load().graphMaxFrequency, 0.0)
        rate(384000.0)
        assertEquals(192000.0, graphMaximum(), 0.0)
        val edited = settings()
        requireNotNull(edited.findViewById<EditText>(R.id.peq_graph_max)).setText("192000")
        rate(48000.0)
        edited.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(edited.isShowing)
        assertNotNull(requireNotNull(edited.findViewById<TextInputLayout>(R.id.peq_graph_max_box)).error)
        edited.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
    }

    @Test fun manualFrequencyInputRevalidatesAtTheCurrentRateAndAcceptsExactNyquist() {
        rate(384000.0)
        row().findViewById<View>(R.id.peq_frequency_value).performClick(); idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val input = requireNotNull(dialog.findViewById<EditText>(R.id.peq_frequency_input))
        input.setText("192000")
        rate(48000.0)
        assertTrue(requireNotNull(dialog.findViewById<TextView>(R.id.peq_frequency_label)).text.contains("24000"))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(dialog.isShowing)
        assertNotNull(requireNotNull(dialog.findViewById<TextInputLayout>(R.id.peq_frequency_box)).error)
        input.setText("24000")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); layout()
        assertFalse(dialog.isShowing)
        assertEquals(24000.0, repository.load().bands.single().frequency, 0.0)
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = CONTENT })
        }
        companion object { const val CONTENT = 0x6f47 }
    }
}
