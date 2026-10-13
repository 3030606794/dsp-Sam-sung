package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
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

/** Real Material dialogs; document pickers remain owned by the activity callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port",
    shadows = [ShadowPosixAtomicFile::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SurroundPresetsUiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = SurroundController.preferences(app)
    private lateinit var store: SurroundPresetStore
    private lateinit var current: SurroundController.Settings
    private var controller: ActivityController<HostActivity>? = null
    private var manager: SurroundPresetsDialog? = null
    private var mainDialog: AlertDialog? = null
    private var applyCount = 0
    private var importCount = 0
    private val exports = mutableListOf<String?>()

    @Before fun prepare() {
        prefs.edit().clear().commit()
        SurroundController.statusPreferences(app).edit().clear().commit()
        store = SurroundPresetStore(File(temporary.newFolder(), SurroundPresetStore.FILE_NAME))
        current = SurroundController.Settings(317, 13851, 8753, 97)
        SurroundController.save(app, current)
    }

    @After fun close() {
        manager?.dismiss()
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
        manager = SurroundPresetsDialog(activity, store,
            readCurrent = { current },
            applyState = { selected ->
                applyCount++
                SurroundController.save(app, selected)
                current = selected
            },
            requestImport = { importCount++ },
            requestExport = { exports.add(it) })
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

    private fun action(preset: SurroundPreset, index: Int): AlertDialog {
        mainDialog!!.findViewById<LinearLayout>(R.id.surround_presets_list)!!
            .findViewWithTag<View>(preset.id)!!.performClick()
        val actions = latest()
        val list = actions.listView
        list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index))
        return latest()
    }

    @Test fun saveTakesSnapshotWhileCancelAndInvalidNamesKeepTheLibraryUnchanged() {
        val originalPrefs = prefs.all.toMap()
        launch()
        val cancelled = clickMain(R.id.surround_presets_save)
        cancelled.findViewById<EditText>(R.id.peq_preset_name)!!.setText("取消这份")
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(store.load().isEmpty())
        val saving = clickMain(R.id.surround_presets_save)
        inputName(saving, "   ")
        assertTrue(saving.isShowing)
        assertNotNull(saving.findViewById<TextInputLayout>(R.id.peq_preset_name_box)!!.error)
        assertTrue(store.load().isEmpty())
        inputName(saving, "名".repeat(41))
        assertTrue(saving.isShowing)
        assertTrue(store.load().isEmpty())
        val snapshot = current
        inputName(saving, "桌面扬声器")
        assertFalse(saving.isShowing)
        assertEquals("桌面扬声器", store.load().single().name)
        assertEquals(snapshot, store.load().single().settings)
        current = SurroundController.defaults[0]
        assertEquals(snapshot, store.load().single().settings)
        assertEquals(originalPrefs, prefs.all)
        assertEquals(0, applyCount)
    }

    @Test fun applyingOnlyReplacesTheCurrentSlotWithoutEnablingOrChangingOtherSlots() {
        val saved = store.save("近场环绕", current)
        val preserved = SurroundController.Settings(411, 15000, 6300, 54)
        SurroundController.save(app, preserved, slot = 1)
        SurroundController.select(app, 4)
        current = SurroundController.defaults[4]
        SurroundController.save(app, current)
        prefs.edit().putBoolean(SurroundController.ENABLED, false).putString("unrelated", "keep").commit()
        val main = launch()
        assertTrue(main.findViewById<TextView>(R.id.surround_presets_scope)!!.text.contains("5"))
        val summary = main.findViewById<LinearLayout>(R.id.surround_presets_list)!!
            .findViewWithTag<View>(saved.id)!!.findViewById<TextView>(R.id.peq_preset_row_count).text.toString()
        listOf("317", "13851", "8.753", "97").forEach { assertTrue(summary.contains(it)) }
        action(saved, 0)
        assertEquals(1, applyCount)
        assertEquals(saved.settings, SurroundController.settings(app))
        assertEquals(4, SurroundController.selected(app))
        assertFalse(SurroundController.enabled(app))
        assertEquals(preserved, SurroundController.settings(app, 1))
        assertEquals("keep", prefs.getString("unrelated", null))
        assertEquals(listOf(saved), store.load())
        assertFalse(main.isShowing)
    }

    @Test fun renamingPreservesParametersAndDuplicateNamesLeaveInputOpenForCorrection() {
        val first = store.save("原名称", current)
        val second = store.save("另一个", SurroundController.defaults[0])
        launch()
        val rename = action(first, 1)
        inputName(rename, second.name)
        assertTrue(rename.isShowing)
        assertEquals(second.name, rename.findViewById<EditText>(R.id.peq_preset_name)!!.text.toString())
        assertNotNull(rename.findViewById<TextInputLayout>(R.id.peq_preset_name_box)!!.error)
        assertEquals(listOf(first, second), store.load())
        inputName(rename, "新名称")
        val result = store.load().first { it.id == first.id }
        assertEquals("新名称", result.name)
        assertEquals(first.settings, result.settings)
        assertEquals(second, store.load().first { it.id == second.id })
        assertEquals(0, applyCount)
    }

    @Test fun fullLibraryPreventsAdditionalSaveOrImportButAllowsConfirmedOverwrite() {
        repeat(SurroundPresetStore.MAX_PRESETS) { store.save("预设 ${it + 1}", SurroundController.defaults[0]) }
        val target = store.load().first()
        val originalPrefs = prefs.all.toMap()
        val main = launch()
        assertFalse(main.findViewById<View>(R.id.surround_presets_save)!!.isEnabled)
        assertFalse(main.findViewById<View>(R.id.surround_presets_import)!!.isEnabled)
        assertTrue(main.findViewById<View>(R.id.surround_presets_export_all)!!.isEnabled)
        val cancelled = action(target, 2)
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(target, store.load().first())
        val confirmation = action(target, 2)
        assertEquals(target, store.load().first())
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val overwritten = store.load().first { it.id == target.id }
        assertEquals(target.name, overwritten.name)
        assertEquals(current, overwritten.settings)
        assertEquals(20, store.load().size)
        assertEquals(originalPrefs, prefs.all)
        assertEquals(0, applyCount)
    }

    @Test fun deleteRequiresConfirmationAndClosingManagerKeepsTheCurrentAudioParameters() {
        val saved = store.save("待删除", current)
        val original = prefs.all.toMap()
        val main = launch()
        val cancelled = action(saved, 4)
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(listOf(saved), store.load())
        val confirmation = action(saved, 4)
        assertEquals(listOf(saved), store.load())
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(store.load().isEmpty())
        assertFalse(main.findViewById<View>(R.id.surround_presets_export_all)!!.isEnabled)
        main.cancel()
        assertEquals(original, prefs.all)
        assertEquals(0, applyCount)
    }

    @Test fun importAndSingleOrAllExportCallbacksDoNotApplyOrMutateParameters() {
        val first = store.save("第一份", current)
        store.save("第二份", SurroundController.defaults[0])
        val original = prefs.all.toMap()
        launch()
        action(first, 3)
        assertEquals(listOf(first.id), exports)
        clickMain(R.id.surround_presets_export_all)
        assertEquals(listOf(first.id, null), exports)
        clickMain(R.id.surround_presets_import)
        assertEquals(1, importCount)
        assertEquals(original, prefs.all)
        assertEquals(0, applyCount)
        store.save("导入结果", current)
        manager!!.refreshStatus()
        assertEquals(3, mainDialog!!.findViewById<LinearLayout>(R.id.surround_presets_list)!!.childCount)
        manager!!.refreshStatus("导入文件无效")
        assertEquals("导入文件无效", mainDialog!!.findViewById<TextView>(R.id.surround_presets_status)!!.text.toString())
        assertEquals(View.VISIBLE, mainDialog!!.findViewById<TextView>(R.id.surround_presets_status)!!.visibility)
        assertEquals(3, mainDialog!!.findViewById<LinearLayout>(R.id.surround_presets_list)!!.childCount)
    }

    @Test fun lifecycleDismissClosesNestedDialogAndReopeningHasOneFreshMainDialog() {
        val main = launch()
        val editing = clickMain(R.id.surround_presets_save)
        assertTrue(editing.isShowing)
        manager!!.dismiss()
        idle()
        assertFalse(editing.isShowing)
        assertFalse(main.isShowing)
        manager!!.refreshStatus("不会重新打开对话框")
        assertFalse(latest().isShowing)
        manager!!.show()
        val reopened = latest()
        assertTrue(reopened.isShowing)
        manager!!.show()
        assertSame(reopened, latest())
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }
}
