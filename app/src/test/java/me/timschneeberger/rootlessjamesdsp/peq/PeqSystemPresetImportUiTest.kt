package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import java.util.concurrent.TimeUnit

/** Exercises the real fragment's save ordering, rather than a dialog-only save callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqSystemPresetImportUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val lockPrefs get() = app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE)
    private val originPrefs get() = app.getSharedPreferences(PeqSystemPresetOrigins.PREFERENCES, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private var controller: ActivityController<HostActivity>? = null
    private val fragment get() = controller!!.get().supportFragmentManager.findFragmentById(R.id.params) as PeqFragment
    private val original = PeqState(listOf(PeqBand("old", frequency = 650.0, gain = -2.5, q = 1.25)))

    @Before fun clear() {
        prefs.edit().clear().commit()
        lockPrefs.edit().clear().commit()
        originPrefs.edit().clear().commit()
        app.getSharedPreferences(PeqEditorPreferences.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
        assertTrue(repository.save(original))
        assertTrue(PeqSystemPresetOrigins(app).recordImportedBands(original.bands))
        PeqBandLockStore(app).applySystemPresetDefaults(original.bands.map { it.id })
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        idle()
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch() {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        idle()
    }

    private fun picker(): AlertDialog {
        assertTrue(controller!!.get().findViewById<View>(R.id.peq_system_presets).performClick())
        // The real entry loads its packaged asset on Dispatchers.IO and posts back to Main.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            idle()
            val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
            if (dialog?.isShowing == true && dialog.findViewById<View>(R.id.peq_system_presets_list) != null)
                return dialog
            Thread.sleep(5)
        }
        throw AssertionError("The real system-preset entry did not finish loading")
    }

    private fun details(picker: AlertDialog): AlertDialog {
        picker.findViewById<EditText>(R.id.peq_system_presets_search)!!.setText("4-Band Parametric (Default)")
        idle()
        val list = picker.findViewById<ListView>(R.id.peq_system_presets_list)!!
        assertEquals(1, list.count)
        assertTrue(list.performItemClick(list.adapter.getView(0, null, list), 0, list.adapter.getItemId(0)))
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun assertImportedProtectionAndOrigins(state: PeqState) {
        assertEquals(4, state.bands.size)
        assertTrue(state.bands.none { it.id == "old" })
        val locks = PeqBandLockStore(app)
        val origins = PeqSystemPresetOrigins(app)
        assertNull(origins.find("old"))
        state.bands.forEach { band ->
            assertFalse(locks.isBandLocked(band.id))
            assertFalse(locks.isFieldLocked(band.id, PeqEditField.GAIN))
            assertFalse(locks.isFieldLocked(band.id, PeqEditField.FILTER_TYPE))
            assertTrue(locks.isFieldLocked(band.id, PeqEditField.QUALITY))
            assertTrue(locks.isFieldLocked(band.id, PeqEditField.FREQUENCY))
            assertEquals(PeqSystemPresetOrigin(band.frequency, band.gain, band.q, band.qualityDisplay), origins.find(band.id))
        }
        assertFalse(prefs.getBoolean(app.getString(R.string.key_geq_enable), false))
        assertNull(PeqPreviewSession.current())
    }

    @Test fun realEntryAppliesImportedLocksAndPersistsOriginsAcrossEditorReopening() {
        launch()
        val picker = picker()
        val detail = details(picker)
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(detail.isShowing)
        assertFalse(picker.isShowing)
        val imported = fragment.editorState
        assertEquals(imported, repository.load())
        assertImportedProtectionAndOrigins(imported)

        controller!!.pause().stop().destroy()
        idle()
        controller = null
        launch()
        assertEquals(imported, fragment.editorState)
        assertImportedProtectionAndOrigins(fragment.editorState)
    }

    @Test fun rejectedBankCommitLeavesPreviousOriginsAndLocksUntouchedAndAllowsRetry() {
        launch()
        val soundsBefore = prefs.all.toMap()
        val locksBefore = lockPrefs.all.toMap()
        val originsBefore = originPrefs.all.toMap()
        val picker = picker()
        val detail = details(picker)
        val repositoryField = PeqFragment::class.java.getDeclaredField("repository").apply { isAccessible = true }
        repositoryField.set(fragment, PeqRepository(RejectingWrites(prefs)))
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(detail.isShowing)
        assertTrue(picker.isShowing)
        assertEquals(original, fragment.editorState)
        assertEquals(original, repository.load())
        assertEquals(soundsBefore, prefs.all)
        assertEquals(locksBefore, lockPrefs.all)
        assertEquals(originsBefore, originPrefs.all)

        repositoryField.set(fragment, repository)
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(detail.isShowing)
        assertFalse(picker.isShowing)
        assertEquals(fragment.editorState, repository.load())
        assertImportedProtectionAndOrigins(fragment.editorState)
    }

    private class RejectingWrites(private val actual: SharedPreferences) : SharedPreferences by actual {
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by actual.edit() {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun commit() = false
        }
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_graphic_eq)
            setSupportActionBar(findViewById(R.id.toolbar))
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .replace(R.id.params, PeqFragment()).commitNow()
            findViewById<View>(R.id.peq_system_presets).setOnClickListener {
                (supportFragmentManager.findFragmentById(R.id.params) as PeqFragment).showSystemPresets()
            }
        }
    }
}
