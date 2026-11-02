package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.peq.PeqModel
import me.timschneeberger.rootlessjamesdsp.peq.PeqRepository
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class DynamicPeqEditorUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val repository get() = DynamicPeqRepository(app)
    private var controller: ActivityController<HostActivity>? = null
    private val fragment get() = controller!!.get().supportFragmentManager.findFragmentByTag("dynamic-peq") as DynamicPeqFragment
    private val staticPreferences get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)

    @Before fun clear() {
        app.getSharedPreferences(Constants.PREF_DYNAMIC_PEQ, Context.MODE_PRIVATE).edit().clear().commit()
        staticPreferences.edit().clear().commit()
        assertTrue(PeqRepository(app).save(PeqModel.defaultState(5)))
        staticPreferences.edit().putBoolean(app.getString(R.string.key_geq_enable), true).putString("ui-sentinel", "keep").commit()
    }
    @After fun close() { controller?.pause()?.stop()?.destroy(); shadowOf(Looper.getMainLooper()).idle() }

    private fun launch(state: DynamicPeqState = DynamicPeqState()) {
        assertTrue(repository.save(state))
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
    }
    private fun layout() {
        shadowOf(Looper.getMainLooper()).idle()
        val view = controller!!.get().findViewById<View>(android.R.id.content)
        view.measure(View.MeasureSpec.makeMeasureSpec(app.dp(360), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(app.dp(800), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, app.dp(360), app.dp(800))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun firstCard(): View = fragment.requireView().findViewById<ViewGroup>(R.id.dynamic_peq_band_list).getChildAt(0)
    private fun plus(parameter: Int) = firstCard().findViewById<View>(parameter)
        .findViewById<View>(R.id.dynamic_peq_parameter_plus).performClick()
    private fun latestDialog(): AlertDialog {
        // Dialog show listeners and standard button callbacks are posted to the main queue.
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun clickDialog(dialog: AlertDialog, button: Int) {
        dialog.getButton(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun dynamicEditsAndPowerNeverModifyTheStaticEqualizer() {
        val unchanged = staticPreferences.all.toMap()
        launch()
        plus(R.id.dynamic_peq_gain)
        plus(R.id.dynamic_peq_threshold)
        firstCard().findViewById<View>(R.id.dynamic_peq_band_dynamic).performClick()
        assertFalse(fragment.editorState.enabled)
        assertEquals(.1, fragment.editorState.bands.first().gain, .00001)
        assertEquals(-23.5, fragment.editorState.bands.first().threshold, .00001)
        assertFalse(fragment.editorState.bands.first().dynamicEnabled)
        fragment.requireView().findViewById<View>(R.id.dynamic_peq_power).performClick()
        assertTrue(repository.load().enabled)
        assertEquals(fragment.editorState, repository.load())
        assertEquals(unchanged, staticPreferences.all)
    }

    @Test fun directInputRejectsOutOfBoundsAndPersistsValidFractionalGain() {
        launch()
        firstCard().findViewById<View>(R.id.dynamic_peq_gain)
            .findViewById<View>(R.id.dynamic_peq_parameter_value).performClick()
        val dialog = latestDialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText("25")
        clickDialog(dialog, AlertDialog.BUTTON_POSITIVE)
        assertTrue(dialog.isShowing)
        assertNotNull(descendants(dialog.window!!.decorView).filterIsInstance<TextInputLayout>().single().error)
        assertEquals(0.0, fragment.editorState.bands.first().gain, .000001)
        input.setText("-3.75")
        clickDialog(dialog, AlertDialog.BUTTON_POSITIVE)
        assertFalse(dialog.isShowing)
        controller!!.pause()
        assertEquals(-3.75, repository.load().bands.first().gain, .000001)
        assertFalse(repository.load().enabled)
    }

    @Test fun resetRequiresConfirmationAndPreservesBothModuleSwitchAndStaticPeq() {
        val unchanged = staticPreferences.all.toMap()
        val initial = DynamicPeqState(true, listOf(DynamicPeqBand(frequency = 250.0, gain = 8.0)))
        launch(initial)
        fragment.requireView().findViewById<View>(R.id.dynamic_peq_reset).performClick()
        clickDialog(latestDialog(), AlertDialog.BUTTON_NEGATIVE)
        assertEquals(initial, repository.load())
        fragment.requireView().findViewById<View>(R.id.dynamic_peq_reset).performClick()
        clickDialog(latestDialog(), AlertDialog.BUTTON_POSITIVE)
        assertEquals(DynamicPeqState(enabled = true), repository.load())
        assertEquals(unchanged, staticPreferences.all)
    }

    @Test fun deletingAndAddingHonorSixteenBandLimit() {
        launch(DynamicPeqState(bands = List(16) { DynamicPeqBand(frequency = 100.0 + it * 200) }))
        val add = fragment.requireView().findViewById<View>(R.id.dynamic_peq_add)
        assertFalse(add.isEnabled)
        firstCard().findViewById<View>(R.id.dynamic_peq_band_delete).performClick()
        clickDialog(latestDialog(), AlertDialog.BUTTON_NEGATIVE)
        assertEquals(16, fragment.editorState.bands.size)
        firstCard().findViewById<View>(R.id.dynamic_peq_band_delete).performClick()
        clickDialog(latestDialog(), AlertDialog.BUTTON_POSITIVE)
        assertEquals(15, fragment.editorState.bands.size)
        assertTrue(add.isEnabled)
        add.performClick()
        assertEquals(16, fragment.editorState.bands.size)
        assertFalse(add.isEnabled)
    }

    @Test fun recreationFlushesPendingEditsAndRestoresOwnBank() {
        launch()
        plus(R.id.dynamic_peq_frequency)
        plus(R.id.dynamic_peq_range)
        val expected = fragment.editorState
        controller!!.recreate()
        layout()
        assertEquals(expected, fragment.editorState)
        assertEquals(expected, repository.load())
        assertEquals("keep", staticPreferences.getString("ui-sentinel", null))
    }

    @Test fun merelyOpeningDamagedStateDoesNotOverwriteThePreservedData() {
        val prefs = app.getSharedPreferences(Constants.PREF_DYNAMIC_PEQ, Context.MODE_PRIVATE)
        prefs.edit().putString(DynamicPeqRepository.KEY_STATE, "damaged-source").commit()
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout(); controller!!.pause()
        assertEquals("damaged-source", prefs.getString(DynamicPeqRepository.KEY_STATE, null))
    }

    @Test fun profileReplacementDiscardsQueuedOldEditsAndClosesStalePreciseDialog() {
        launch()
        plus(R.id.dynamic_peq_gain)
        firstCard().findViewById<View>(R.id.dynamic_peq_gain)
            .findViewById<View>(R.id.dynamic_peq_parameter_value).performClick()
        val oldDialog = latestDialog()
        val input = descendants(oldDialog.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText("11")
        val replacement = DynamicPeqState(true, listOf(DynamicPeqBand(frequency = 2700.0, gain = -4.0, threshold = -36.0)))
        assertTrue(repository.save(replacement))
        LocalBroadcastManager.getInstance(app).sendBroadcastSync(Intent(Constants.ACTION_PRESET_LOADED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertFalse(oldDialog.isShowing)
        assertEquals(replacement, fragment.editorState)
        controller!!.pause()
        assertEquals(replacement, repository.load())
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_graphic_eq)
            setSupportActionBar(findViewById(R.id.toolbar))
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .replace(R.id.params, DynamicPeqFragment(), "dynamic-peq").commitNow()
        }
    }
}
