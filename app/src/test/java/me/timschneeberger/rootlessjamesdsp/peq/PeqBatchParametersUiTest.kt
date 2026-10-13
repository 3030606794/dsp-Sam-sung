package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

/** Real Fragment entry point: one cancellable, protected batch and no live audio side effects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqBatchParametersUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private val locks get() = PeqBandLockStore(app)
    private var controller: ActivityController<HostActivity>? = null

    @Before fun clear() {
        prefs.edit().clear().commit()
        app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(PeqEditorPreferences.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }

    @After fun close() { controller?.pause()?.stop()?.destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun original() = PeqState(listOf(
        PeqBand("q-open", frequency = 777.125, gain = 1.125, q = 2.25),
        PeqBand("bw-open", frequency = 3456.75, gain = -2.125, q = 1.75, qualityDisplay = PeqQualityDisplay.BW),
        PeqBand("q-locked", frequency = 2000.0, gain = 7.0, q = 4.0),
        PeqBand("bw-locked", frequency = 200.0, gain = -0.4, q = 3.0, qualityDisplay = PeqQualityDisplay.BW),
        PeqBand("band-locked", enabled = false, frequency = 10000.0, gain = 1.0, q = 5.0)))

    private fun launch(state: PeqState = original()): PeqState {
        assertTrue(repository.save(state))
        state.bands.forEach { locks.setBandLocked(it.id, false) }
        locks.setBandLocked("band-locked", true)
        locks.setFieldLocked("q-locked", PeqEditField.QUALITY, true)
        locks.setFieldLocked("bw-locked", PeqEditField.QUALITY, true)
        prefs.edit().putBoolean(app.getString(R.string.key_geq_enable), true).commit()
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        idle()
        return state
    }

    private fun open(): AlertDialog {
        fragment().requireView().findViewById<View>(R.id.peq_parameters).performClick()
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also {
            assertTrue(it.isShowing)
            assertNotNull(it.findViewById<EditText>(R.id.peq_batch_q_input))
            assertNotNull(it.findViewById<EditText>(R.id.peq_batch_bw_input))
            assertNotNull(it.findViewById<PeqFilterSelector>(R.id.peq_batch_filter))
            assertNull(it.findViewById<View>(R.id.peq_filter_monitor))
        }
    }

    private fun draft(dialog: AlertDialog, q: String = "1.25", bw: String = "0.5") {
        dialog.findViewById<EditText>(R.id.peq_batch_q_input)!!.setText(q)
        dialog.findViewById<EditText>(R.id.peq_batch_bw_input)!!.setText(bw)
        dialog.findViewById<PeqFilterSelector>(R.id.peq_batch_filter)!!
            .setSelection(PeqFilterType.HIGH_PASS, notify = true)
        idle()
    }

    private fun packet(): IntArray {
        var result = intArrayOf()
        assertTrue(PeqController.sync(app) { _, values -> result = values; true })
        return result
    }

    private fun expected(original: PeqState) = original.copy(bands = original.bands.map { band ->
        when (band.id) {
            "band-locked" -> band
            "q-open" -> band.copy(type = PeqFilterType.HIGH_PASS, q = 1.25)
            "bw-open" -> band.copy(type = PeqFilterType.HIGH_PASS,
                q = PeqResponse.qFromBandwidth(0.5, band.frequency))
            else -> band.copy(type = PeqFilterType.HIGH_PASS)
        }
    })

    @Test fun cancellingMixedDraftKeepsPreferencesAndDriverPacketExactlyUnchanged() {
        val original = launch()
        val beforePrefs = prefs.all.toMap()
        val beforePacket = packet()
        val dialog = open()
        draft(dialog)
        assertEquals(original, fragment().editorState)
        assertEquals(beforePrefs, prefs.all)
        assertArrayEquals(beforePacket, packet())
        assertNull(PeqPreviewSession.current())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(beforePrefs, prefs.all)
        assertArrayEquals(beforePacket, packet())
    }

    @Test fun confirmationCommitsOneBankAndKeepsModesAndProtectedParameters() {
        val original = launch()
        var savedBanks = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PeqRepository.KEY_STATE) savedBanks++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            val dialog = open()
            draft(dialog)
            assertEquals(0, savedBanks)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            assertFalse(dialog.isShowing)
            assertEquals(1, savedBanks)
            assertEquals(expected(original), repository.load())
            assertEquals(repository.load(), fragment().editorState)
            assertEquals(original.bands.map { it.qualityDisplay }, repository.load().bands.map { it.qualityDisplay })
            assertArrayEquals(PeqProtocol.payload(expected(original), true), packet())
            assertTrue(prefs.getBoolean(app.getString(R.string.key_geq_enable), false))
            assertNull(PeqPreviewSession.current())
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    @Test fun invalidBandwidthDoesNotPartiallySaveValidQOrFilterType() {
        val original = launch()
        val dialog = open()
        draft(dialog, bw = "NaN")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(dialog.isShowing)
        assertNotNull(dialog.findViewById<TextInputLayout>(R.id.peq_batch_bw_box)!!.error)
        assertEquals(original, repository.load())
        assertEquals(original, fragment().editorState)
        dialog.findViewById<EditText>(R.id.peq_batch_bw_input)!!.setText("0.5")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(expected(original), repository.load())
        assertFalse(dialog.isShowing)
    }

    @Test fun confirmingUntouchedControlsPreservesOutOfRangeAndMixedOriginalValues() {
        val original = launch(original().copy(qRange = 2.0))
        val before = packet()
        val dialog = open()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(original, repository.load())
        assertArrayEquals(before, packet())
    }

    @Test fun confirmationRescansLocksAcquiredAfterOpeningTheDraft() {
        val original = launch()
        val dialog = open()
        draft(dialog)
        locks.setBandLocked("q-open", true)
        locks.setFieldLocked("bw-open", PeqEditField.QUALITY, true)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val expected = original.copy(bands = original.bands.map {
            if (it.id == "q-open" || it.id == "band-locked") it else it.copy(type = PeqFilterType.HIGH_PASS)
        })
        assertEquals(expected, repository.load())
        assertFalse(dialog.isShowing)
    }

    @Test fun aBankWithoutBwCardsDisablesOnlyBwAndKeepsQAndTypeUsable() {
        val original = PeqModel.defaultState(2)
        launch(original)
        val dialog = open()
        assertFalse(dialog.findViewById<EditText>(R.id.peq_batch_bw_input)!!.isEnabled)
        assertTrue(dialog.findViewById<EditText>(R.id.peq_batch_q_input)!!.isEnabled)
        assertTrue(dialog.findViewById<PeqFilterSelector>(R.id.peq_batch_filter)!!.isEnabled)
        dialog.findViewById<EditText>(R.id.peq_batch_q_input)!!.setText("1.25")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(original.copy(bands = original.bands.map { it.copy(q = 1.25) }), repository.load())
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
