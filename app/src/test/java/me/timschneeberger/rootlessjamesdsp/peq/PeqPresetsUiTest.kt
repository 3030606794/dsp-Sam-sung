package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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

/** Real dialogs exercise the library workflow; document-picker handoff stays outside this test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port",
    shadows = [ShadowPosixAtomicFile::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PeqPresetsUiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private lateinit var store: PeqPresetStore
    private lateinit var current: PeqState
    private var controller: ActivityController<HostActivity>? = null
    private var manager: PeqPresetsDialog? = null
    private var mainDialog: AlertDialog? = null
    private var applyCount = 0
    private var importCount = 0
    private val exports = mutableListOf<Pair<String, String>>()

    @Before fun prepare() {
        prefs.edit().clear().commit()
        store = PeqPresetStore(File(temporary.newFolder(), "peq-presets.json"))
        current = PeqModel.defaultState(3).let { state -> state.copy(
            bands = state.bands.mapIndexed { index, band -> band.copy(gain = index + 0.25, q = 1.23456) },
            gainRange = 23.0, gainStep = 0.17, qStep = 0.125, frequencyStep = 13.5, pinResponse = false) }
        assertTrue(repository.save(current))
    }

    @After fun close() {
        manager?.dismissAll()
        controller?.pause()?.stop()?.destroy()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun latest(): AlertDialog {
        idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun launch(): AlertDialog {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        val activity = controller!!.get()
        manager = PeqPresetsDialog(activity, LayoutInflater.from(activity),
            readCurrent = { current },
            applyState = { selected ->
                applyCount++
                repository.save(selected).also { saved -> if (saved) current = selected }
            },
            onImportRequested = { importCount++ },
            onExportRequested = { name, json -> exports.add(name to json) },
            store = store)
        manager!!.show()
        return latest().also { mainDialog = it }
    }

    private fun clickMain(id: Int): AlertDialog {
        mainDialog!!.findViewById<View>(id)!!.performClick()
        return latest()
    }

    private fun inputName(dialog: AlertDialog, name: String) {
        dialog.findViewById<EditText>(R.id.peq_preset_name)!!.setText(name)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    private fun action(preset: PeqPreset, index: Int): AlertDialog {
        mainDialog!!.findViewById<LinearLayout>(R.id.peq_presets_list)!!
            .findViewWithTag<View>(preset.id)!!.performClick()
        val actions = latest()
        val list = actions.listView
        val item = list.adapter.getView(index, null, list)
        list.performItemClick(item, index, list.adapter.getItemId(index))
        return latest()
    }

    @Test fun savingCurrentStoresASnapshotAndCancelOrInvalidNameLeavesLibraryUntouched() {
        launch()
        val cancelled = clickMain(R.id.peq_presets_save)
        cancelled.findViewById<EditText>(R.id.peq_preset_name)!!.setText("不保存")
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(store.load().isEmpty())
        val save = clickMain(R.id.peq_presets_save)
        inputName(save, "   ")
        assertTrue(save.isShowing)
        assertTrue(store.load().isEmpty())
        val original = current
        inputName(save, "通勤耳机")
        assertFalse(save.isShowing)
        val saved = store.load().single()
        assertEquals("通勤耳机", saved.name)
        assertEquals(original, saved.state)
        current = PeqModel.defaultState(1)
        assertEquals(original, store.load().single().state)
        assertEquals(0, applyCount)
        assertFalse(prefs.getBoolean(app.getString(R.string.key_geq_enable), false))
    }

    @Test fun applyingSavedPresetReplacesOnlyThePeqStateWithoutEnablingEffect() {
        val savedState = current
        val saved = store.save("已保存调音", savedState)
        current = PeqModel.defaultState(1)
        repository.save(current)
        val enabledKey = app.getString(R.string.key_geq_enable)
        prefs.edit().putBoolean(enabledKey, false).putString("unrelated_setting", "keep").commit()
        launch()
        action(saved, 0)
        assertEquals(1, applyCount)
        assertEquals(savedState, repository.load())
        assertFalse(prefs.getBoolean(enabledKey, true))
        assertEquals("keep", prefs.getString("unrelated_setting", null))
        assertEquals(savedState, store.load().single().state)
    }

    @Test fun renameKeepsTheSavedParametersAndDuplicateNameDoesNotOverwriteAnotherPreset() {
        val first = store.save("原名称", current)
        val second = store.save("另一个", PeqModel.defaultState(1))
        launch()
        val rename = action(first, 1)
        inputName(rename, second.name)
        assertTrue(rename.isShowing)
        assertEquals(listOf(first, second), store.load())
        inputName(rename, "新名称")
        val renamed = store.load().first { it.id == first.id }
        assertEquals("新名称", renamed.name)
        assertEquals(first.state, renamed.state)
        assertEquals(second, store.load().first { it.id == second.id })
        assertEquals(0, applyCount)
    }

    @Test fun fullLibraryDisablesNewSaveButAllowsConfirmedOverwriteAndCancelPreservesSnapshot() {
        repeat(PeqPresetStore.MAX_PRESETS) { store.save("预设 ${it + 1}", PeqModel.defaultState(1)) }
        val target = store.load().first()
        val main = launch()
        assertFalse(main.findViewById<View>(R.id.peq_presets_save)!!.isEnabled)
        val cancelled = action(target, 2)
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(target, store.load().first())
        val confirmation = action(target, 2)
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(PeqPresetStore.MAX_PRESETS, store.load().size)
        val overwritten = store.load().first { it.id == target.id }
        assertEquals(target.name, overwritten.name)
        assertEquals(current, overwritten.state)
        assertEquals(0, applyCount)
    }

    @Test fun deletionNeedsConfirmationAndCancellingTheManagerDoesNotChangeCurrentParameters() {
        val saved = store.save("待删除", current)
        val original = repository.load()
        val main = launch()
        val cancelDelete = action(saved, 4)
        cancelDelete.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(listOf(saved), store.load())
        val delete = action(saved, 4)
        delete.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(store.load().isEmpty())
        main.cancel()
        assertEquals(original, repository.load())
        assertEquals(0, applyCount)
    }

    @Test fun importAndExportsHandOffTheRequestedLibrarySnapshotWithoutApplyingIt() {
        val first = store.save("第一份", current)
        store.save("第二份", PeqModel.defaultState(1))
        val original = repository.load()
        launch()
        action(first, 3)
        assertEquals(1, exports.size)
        assertTrue(exports.single().first.endsWith(".json"))
        assertEquals(store.exportJson(first.id), exports.single().second)
        clickMain(R.id.peq_presets_export_all)
        assertEquals(2, exports.size)
        assertEquals(store.exportJson(), exports.last().second)
        clickMain(R.id.peq_presets_import)
        assertEquals(1, importCount)
        assertEquals(original, repository.load())
        assertEquals(0, applyCount)
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }
}
