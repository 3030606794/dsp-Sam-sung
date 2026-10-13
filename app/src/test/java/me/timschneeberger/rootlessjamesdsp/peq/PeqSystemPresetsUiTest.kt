package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port",
    shadows = [ShadowPosixAtomicFile::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PeqSystemPresetsUiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private lateinit var presets: List<PeqSystemPreset>
    private lateinit var current: PeqState
    private lateinit var personalFile: File
    private lateinit var personalBytes: ByteArray
    private var controller: ActivityController<HostActivity>? = null
    private var manager: PeqSystemPresetsDialog? = null
    private lateinit var main: AlertDialog
    private var applyCount = 0
    private var allowSave = true

    @Before fun prepare() {
        prefs.edit().clear().putBoolean(app.getString(R.string.key_geq_enable), false)
            .putInt("system_output_volume", 37).commit()
        current = PeqModel.defaultState(3).copy(gainStep = 0.13, qStep = 0.07,
            frequencyStep = 17.0, pinResponse = false)
        assertTrue(repository.save(current))
        presets = PeqSystemPresets.load(app)
        personalFile = File(temporary.newFolder(), "peq-presets.json")
        PeqPresetStore(personalFile).save("保持我的预设", current)
        personalBytes = personalFile.readBytes()
    }

    @After fun close() {
        manager?.dismissAll()
        controller?.pause()?.stop()?.destroy()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun latest(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }
    private fun list() = main.findViewById<ListView>(R.id.peq_system_presets_list)!!
    private fun search(query: String) {
        main.findViewById<EditText>(R.id.peq_system_presets_search)!!.setText(query)
        idle()
    }
    private fun launch() {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        manager = PeqSystemPresetsDialog(controller!!.get(), presets) { preset ->
            applyCount++
            if (!allowSave) false else {
                val applied = preset.applyTo(current)
                repository.save(applied).also { if (it) current = applied }
            }
        }
        manager!!.show()
        main = latest()
    }
    private fun details(position: Int = 0): AlertDialog {
        val list = list()
        list.performItemClick(list.adapter.getView(position, null, list), position,
            list.adapter.getItemId(position))
        return latest()
    }

    @Test fun searchingByNameAndCountIsCaseInsensitiveAndNeverWritesSettings() {
        val before = prefs.all.toMap()
        launch()
        assertEquals(74, list().count)
        assertTrue(main.findViewById<TextView>(R.id.peq_system_presets_count)!!.text.contains("74"))
        search("  fLaT 16K (130-  ")
        assertEquals(1, list().count)
        assertEquals("Flat 16k (130-Band Graphic)", (list().adapter.getItem(0) as PeqSystemPreset).name)
        search("没有这种预设")
        assertEquals(0, list().count)
        assertEquals(View.VISIBLE, main.findViewById<View>(R.id.peq_system_presets_empty)!!.visibility)
        search("")
        assertEquals(74, list().count)
        assertEquals(0, applyCount)
        assertEquals(before, prefs.all)
        assertArrayEquals(personalBytes, personalFile.readBytes())
    }

    @Test fun inspectingAdaptationWarningAndCancellingBothDialogsLeavesCurrentAndPersonalPresetsUntouched() {
        val original = current
        val before = prefs.all.toMap()
        launch()
        search("Flat 16k (130-")
        val selected = list().adapter.getItem(0) as PeqSystemPreset
        val detail = details()
        val message = detail.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message.contains(app.getString(R.string.peq_system_presets_compat, selected.adaptedFrequencyCount)))
        assertTrue(message.contains(app.getString(R.string.peq_system_presets_quality)))
        assertTrue(message.contains("20 Hz"))
        assertEquals(0, applyCount)
        detail.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(detail.isShowing)
        assertTrue(main.isShowing)
        main.cancel()
        idle()
        assertEquals(original, repository.load())
        assertEquals(before, prefs.all)
        assertArrayEquals(personalBytes, personalFile.readBytes())
    }

    @Test fun confirmedApplyClosesThePickerAndKeepsOriginalPreampAsReferenceWithoutChangingOutputVolume() {
        val original = current
        launch()
        search("Dance (")
        val selected = list().adapter.getItem(0) as PeqSystemPreset
        val detail = details()
        val message = detail.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message.contains(app.getString(R.string.peq_system_presets_preamp,
            PeqBandAdapter.number(selected.sourcePreampDb))))
        assertEquals(0, applyCount)
        assertEquals(original, repository.load())
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, applyCount)
        assertFalse(detail.isShowing)
        assertFalse(main.isShowing)
        assertEquals(selected.bands.map { it.gain }, current.bands.map { it.gain })
        assertEquals(10, current.bands.size)
        assertEquals(current, repository.load())
        assertEquals(original.gainStep, current.gainStep, 0.0)
        assertEquals(original.qStep, current.qStep, 0.0)
        assertEquals(original.frequencyStep, current.frequencyStep, 0.0)
        assertEquals(original.pinResponse, current.pinResponse)
        assertFalse(prefs.getBoolean(app.getString(R.string.key_geq_enable), true))
        assertEquals(37, prefs.getInt("system_output_volume", -1))
        assertArrayEquals(personalBytes, personalFile.readBytes())
    }

    @Test fun rejectedApplyKeepsTheSelectionOpenForRetryAndCannotChangeSavedParameters() {
        val original = current
        launch()
        val detail = details()
        allowSave = false
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, applyCount)
        assertTrue(main.isShowing)
        assertTrue(detail.isShowing)
        assertEquals(original, repository.load())
        assertArrayEquals(personalBytes, personalFile.readBytes())
        allowSave = true
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(2, applyCount)
        assertFalse(main.isShowing)
        assertFalse(detail.isShowing)
        assertEquals(listOf(250.0, 500.0, 3000.0, 6000.0), repository.load().bands.map { it.frequency })
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }
}