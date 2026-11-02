package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
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
import java.time.Duration

/** Real card/dialog thumb gestures, rather than invoking the reset callback directly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqSystemPresetResetUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val repository get() = PeqRepository(app)
    private val locks get() = PeqBandLockStore(app)
    private val origins get() = PeqSystemPresetOrigins(app)
    private var controller: ActivityController<HostActivity>? = null

    private fun original(id: String = "imported") = PeqBand(id, frequency = 2178.8125,
        gain = -3.751234567, q = 6.695873046875)

    @Before fun clear() {
        listOf(Constants.PREF_GEQ, PeqBandLockStore.PREFERENCES, PeqEditorPreferences.PREFERENCES,
            PeqSystemPresetOrigins.PREFERENCES).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }
    @After fun close() { controller?.pause()?.stop()?.destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun layout(view: View = controller!!.get().findViewById(android.R.id.content),
                       heightMode: Int = View.MeasureSpec.EXACTLY) {
        idle()
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((820 * density).toInt(), heightMode))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        idle()
    }
    private fun launch(imported: List<PeqBand>, edited: List<PeqBand>) {
        assertTrue(origins.recordImportedBands(imported))
        assertTrue(repository.save(PeqState(edited, showQSlider = true, showFrequencySlider = true,
            gainStep = 0.5, gainRange = 18.0, qRange = 30.0)))
        edited.forEach { locks.setBandLocked(it.id, false) }
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
    }
    private fun list() = fragment().requireView().findViewById<RecyclerView>(R.id.peq_band_list)
    private fun row(index: Int = 0): View {
        list().scrollToPosition(index)
        layout()
        return requireNotNull(list().findViewHolderForAdapterPosition(index)).itemView
    }
    private fun saved(index: Int = 0) = repository.load().bands[index]

    private fun tap(view: View, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        advance(20)
        send(MotionEvent.ACTION_UP)
    }
    private fun doubleTapThumb(slider: Slider) {
        assertTrue("The actual slider track is laid out", slider.trackWidth > 0)
        val position = (slider.value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)
        val x = slider.trackSidePadding + position * slider.trackWidth
        tap(slider, x, slider.height / 2f)
        advance(80)
        tap(slider, x, slider.height / 2f)
        advance(350)
    }
    private fun doubleTapButton(button: View) {
        tap(button, button.width / 2f, button.height / 2f)
        advance(80)
        tap(button, button.width / 2f, button.height / 2f)
        advance(350)
    }
    private fun resetCard(sliderId: Int, index: Int = 0) = doubleTapThumb(row(index).findViewById(sliderId))
    private fun precise(): AlertDialog {
        row().findViewById<View>(R.id.peq_band_number).performClick()
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also {
            assertTrue(it.isShowing)
            layout(it.window!!.decorView, View.MeasureSpec.AT_MOST)
        }
    }
    private fun resetFine(dialog: AlertDialog, sliderId: Int) =
        doubleTapThumb(dialog.findViewById<Slider>(sliderId)!!)
    private fun input(dialog: AlertDialog, id: Int) = dialog.findViewById<EditText>(id)!!.text.toString()
    private fun confirm(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse("A valid reset draft confirms successfully", dialog.isShowing)
        assertEquals(repository.load(), fragment().editorState)
    }

    @Test fun exactNonzeroImportedGainSurvivesRepeatedCardRebindingAndBothLockGuards() {
        val origin = original()
        launch(listOf(origin), listOf(origin.copy(gain = 8.0)))
        repeat(3) {
            list().adapter!!.notifyDataSetChanged()
            resetCard(R.id.peq_band_slider)
            assertEquals(origin.gain, saved().gain, 0.0)
            assertEquals(origin.frequency, saved().frequency, 0.0)
            assertEquals(origin.q, saved().q, 0.0)
            row().findViewById<View>(R.id.peq_band_plus).performClick()
            idle()
            assertEquals(origin.gain + 0.5, saved().gain, 0.0)
        }
        val adjusted = saved()
        row().findViewById<View>(R.id.peq_gain_lock).performClick()
        resetCard(R.id.peq_band_slider)
        assertEquals(adjusted, saved())
        row().findViewById<View>(R.id.peq_gain_lock).performClick()
        row().findViewById<View>(R.id.peq_band_lock).performClick()
        resetCard(R.id.peq_band_slider)
        assertEquals(adjusted, saved())
        row().findViewById<View>(R.id.peq_band_lock).performClick()
        resetCard(R.id.peq_band_slider)
        assertEquals(origin, saved())
        assertEquals(origin.gain, origins.find(origin.id)!!.gain, 0.0)
    }

    @Test fun frequencyResetWaitsForUnlockAndHonorsQualityLockThenPreservesCurrentBandwidth() {
        val origin = original().copy(qualityDisplay = PeqQualityDisplay.BW)
        val adjusted = origin.copy(frequency = 7000.0, q = 4.047)
        launch(listOf(origin), listOf(adjusted))
        locks.applySystemPresetDefaults(listOf(origin.id))
        resetCard(R.id.peq_frequency_slider)
        assertEquals(adjusted, saved())
        row().findViewById<View>(R.id.peq_frequency_lock).performClick()
        resetCard(R.id.peq_frequency_slider)
        assertEquals(origin.frequency, saved().frequency, 0.0)
        assertEquals(adjusted.q, saved().q, 0.0)
        assertTrue(locks.isFieldLocked(origin.id, PeqEditField.QUALITY))

        row().findViewById<View>(R.id.peq_q_lock).performClick()
        row().findViewById<View>(R.id.peq_frequency_plus).performClick()
        idle()
        val moved = saved()
        assertNotEquals(origin.frequency, moved.frequency, 0.0)
        val bandwidth = PeqResponse.bandwidthOctaves(moved.q, moved.frequency)
        resetCard(R.id.peq_frequency_slider)
        assertEquals(origin.frequency, saved().frequency, 0.0)
        assertEquals(bandwidth, PeqResponse.bandwidthOctaves(saved().q, saved().frequency), 1e-10)
        assertEquals(PeqQualityDisplay.BW, saved().qualityDisplay)
        assertEquals(origin.q, origins.find(origin.id)!!.q, 0.0)
    }

    @Test fun qualityResetUsesTheCurrentQOrBwModeAndDoesNotResetFrequency() {
        val qOrigin = original("q").copy(qualityDisplay = PeqQualityDisplay.BW)
        val bwOrigin = original("bw")
        val qEdited = qOrigin.copy(frequency = 5000.0, q = 2.5, qualityDisplay = PeqQualityDisplay.Q)
        val bwEdited = bwOrigin.copy(frequency = 8000.0, q = 4.0, qualityDisplay = PeqQualityDisplay.BW)
        launch(listOf(qOrigin, bwOrigin), listOf(qEdited, bwEdited))
        resetCard(R.id.peq_q_slider)
        assertEquals(qOrigin.q, saved().q, 0.0)
        assertEquals(qEdited.frequency, saved().frequency, 0.0)
        assertEquals(PeqQualityDisplay.Q, saved().qualityDisplay)
        resetCard(R.id.peq_q_slider, 1)
        assertEquals(bwEdited.frequency, saved(1).frequency, 0.0)
        assertEquals(PeqQualityDisplay.BW, saved(1).qualityDisplay)
        assertEquals(PeqResponse.bandwidthOctaves(bwOrigin.q, bwOrigin.frequency),
            PeqResponse.bandwidthOctaves(saved(1).q, saved(1).frequency), 1e-10)
        assertNotEquals("BW reset adapts Q to the current frequency", bwOrigin.q, saved(1).q, 1e-6)
    }

    @Test fun aRealCopyAndAnOrdinaryBandHaveNoImportedQualityOrFrequencyReset() {
        val origin = original()
        val edited = origin.copy(frequency = 5000.0, gain = 7.0, q = 4.0)
        val ordinary = PeqBand("ordinary", frequency = 1000.0, gain = 2.0, q = 2.0)
        launch(listOf(origin), listOf(edited, ordinary))
        doubleTapButton(row().findViewById(R.id.peq_band_locate))
        assertEquals(3, repository.load().bands.size)
        val copy = saved(1)
        assertEquals(edited.copy(id = copy.id, enabled = false), copy)
        assertNull(origins.find(copy.id))
        assertNull(origins.find(ordinary.id))
        for (index in listOf(1, 2)) {
            val before = saved(index)
            resetCard(R.id.peq_q_slider, index)
            resetCard(R.id.peq_frequency_slider, index)
            assertEquals(before, saved(index))
        }
        assertEquals(edited, saved())
        assertNotNull(origins.find(origin.id))
    }

    @Test fun fineResetsKeepExactDraftValuesUntilConfirmAndAreBlockedByMasterAndFieldLocks() {
        val origin = original()
        val edited = origin.copy(gain = 8.0, frequency = 7000.0, q = 2.5)
        launch(listOf(origin), listOf(edited))
        var dialog = precise()
        dialog.findViewById<View>(R.id.peq_gain_lock)!!.performClick()
        val protectedGain = input(dialog, R.id.peq_gain_input)
        resetFine(dialog, R.id.peq_gain_slider)
        assertEquals(protectedGain, input(dialog, R.id.peq_gain_input))
        dialog.findViewById<View>(R.id.peq_gain_lock)!!.performClick()
        dialog.findViewById<View>(R.id.peq_band_dialog_lock)!!.performClick()
        val protectedFrequency = input(dialog, R.id.peq_frequency_input)
        resetFine(dialog, R.id.peq_frequency_slider)
        assertEquals(protectedFrequency, input(dialog, R.id.peq_frequency_input))
        assertEquals(edited, saved())
        dialog.findViewById<View>(R.id.peq_band_dialog_lock)!!.performClick()

        fun resetDraft(editor: AlertDialog) {
            resetFine(editor, R.id.peq_gain_slider)
            resetFine(editor, R.id.peq_frequency_slider)
            resetFine(editor, R.id.peq_quality_slider)
            assertEquals(origin.gain, input(editor, R.id.peq_gain_input).toDouble(), 0.0)
            assertEquals(origin.frequency, input(editor, R.id.peq_frequency_input).toDouble(), 0.0)
            assertEquals(edited, saved())
        }
        resetDraft(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(edited, saved())
        dialog = precise()
        resetDraft(dialog)
        confirm(dialog)
        assertEquals(origin, saved())
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
