package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
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
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port", shadows = [ShadowPosixAtomicFile::class])
@LooperMode(LooperMode.Mode.PAUSED)
class DdcPresetsUiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(DdcParameters.NAMESPACE, Application.MODE_PRIVATE)
    private lateinit var store: DdcPresetStore
    private lateinit var source: File
    private var host: ActivityController<HostActivity>? = null
    private var manager: DdcPresetsDialog? = null
    private var applies = 0
    private var imports = 0
    private val exports = mutableListOf<String?>()
    @Before fun prepare() {
        source = File(temporary.newFolder(), "原校正.vdc"); source.writeText(DdcTestFixture.TEXT)
        prefs.edit().clear().putBoolean(DdcParameters.ENABLED, false).putString(DdcParameters.FILE, source.absolutePath).commit()
        DdcParameters.apply(prefs, DdcParameters.defaults())
        store = DdcPresetStore(File(temporary.newFolder(), DdcPresetStore.FILE_NAME))
    }
    @After fun close() { manager?.dismiss(); host?.pause()?.stop()?.destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun latest(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }
    private fun foundation() = DdcFoundation.from(source.absolutePath, "原校正", DdcVdc.read(source))
    private fun launch(canSave: () -> Boolean = { true }): AlertDialog {
        host = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        manager = DdcPresetsDialog(host!!.get(), store, { foundation() }, { DdcParameters.capture(prefs.all) }, { foundation, settings ->
            val path = foundation.install(host!!.get()); val edit = prefs.edit().putString(DdcParameters.FILE, path)
            DdcParameters.put(edit, settings.values()); edit.commit(); applies++
        }, { imports++ }, { exports += it }, canSave)
        manager!!.show(); return latest()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun button(dialog: AlertDialog, label: String): TextView = descendants(dialog.window!!.decorView)
        .filterIsInstance<TextView>().first { it.isClickable && it.text.toString().startsWith(label) }
    private fun item(dialog: AlertDialog, index: Int) {
        val list = dialog.listView; list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index)); idle()
    }

    @Test fun originalFileChoiceKeepsSourceAndPowerAndClosesProfessionalLayer() {
        DdcParameters.apply(prefs, DdcTestFixture.settings)
        val before = source.readBytes(); val main = launch(); button(main, "系统声音方案").performClick()
        val options = latest(); assertEquals(6, options.listView.adapter.count)
        assertTrue(options.listView.adapter.getItem(0).toString().contains("专业附加层关闭")); item(options, 0)
        assertEquals(1, applies); assertFalse(main.isShowing); assertEquals(DdcParameters.defaults(), DdcParameters.capture(prefs.all))
        assertFalse(prefs.getBoolean(DdcParameters.ENABLED, true)); assertEquals(source.absolutePath, prefs.getString(DdcParameters.FILE, ""))
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun systemDirectionChangesOnlyExtrasAndPreservesSourceOtherModulesAndPower() {
        DdcParameters.apply(prefs, DdcTestFixture.settings)
        prefs.edit().putInt("unrelated", 73).commit()
        val other = ToneController.preferences(app); other.edit().clear().putBoolean(ToneController.ENABLED, true).commit()
        val before = other.all.toMap(); val bytes = source.readBytes()
        val expected = DdcSystemPresets.entries(DdcTestFixture.settings)[3].second
        val main = launch(); button(main, "系统声音方案").performClick(); item(latest(), 3)
        assertEquals(expected, DdcParameters.capture(prefs.all)); assertEquals(1, applies); assertFalse(main.isShowing)
        assertFalse(prefs.getBoolean(DdcParameters.ENABLED, true)); assertEquals(source.absolutePath, prefs.getString(DdcParameters.FILE, ""))
        assertEquals(73, prefs.getInt("unrelated", 0)); assertEquals(before, other.all); assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun invalidNameKeepsDialogOpenAndFavoriteRestoresMissingSourceWithPowerPreserved() {
        DdcParameters.apply(prefs, DdcTestFixture.settings)
        val main = launch(); button(main, "保存当前参数").performClick()
        val saving = latest(); val input = descendants(saving.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText(" "); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(saving.isShowing); assertNotNull(input.error); assertTrue(store.load().isEmpty())
        input.setText("个人收藏"); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val saved = store.load().single(); assertEquals(foundation(), saved.foundation)
        DdcParameters.apply(prefs, DdcParameters.defaults()); prefs.edit().putBoolean(DdcParameters.ENABLED, true).commit()
        source.writeText("corrupt source")
        button(main, "个人收藏").performClick(); item(latest(), 0)
        assertEquals(saved.settings, DdcParameters.capture(prefs.all)); assertTrue(prefs.getBoolean(DdcParameters.ENABLED, false)); assertEquals(1, applies)
        val restored = prefs.getString(DdcParameters.FILE, "")!!
        assertNotEquals(source.absolutePath, restored); assertEquals("corrupt source", source.readText())
        assertEquals(saved.foundation.sha256, DdcFoundation.selected(app, restored).sha256)
    }

    @Test fun importAndExportCallbacksLeaveAudioUntouchedAndDismissClosesNestedDialogs() {
        val saved = store.save("导出这份", foundation(), DdcParameters.capture(prefs.all))
        val before = prefs.all.toMap(); val main = launch()
        button(main, "导入预设文件").performClick(); assertEquals(1, imports)
        button(main, "导出全部个人预设").performClick(); assertEquals(listOf<String?>(null), exports)
        button(main, "导出这份").performClick(); item(latest(), 3)
        assertEquals(listOf(null, saved.id), exports); assertEquals(before, prefs.all); assertEquals(0, applies)
        button(main, "保存当前参数").performClick(); val nested = latest(); assertTrue(nested.isShowing)
        manager!!.dismiss(); idle(); assertFalse(nested.isShowing); assertFalse(main.isShowing)
        assertEquals(listOf(saved), store.load()); assertEquals(before, prefs.all)
    }

    @Test fun invalidSourceCannotBeSavedEvenWhenItChangesAfterInputOpened() {
        val saved = store.save("已有收藏", foundation(), DdcParameters.defaults())
        source.writeText("invalid")
        val main = launch(); button(main, "保存当前参数").performClick(); idle()
        assertTrue(main.isShowing); assertEquals(listOf(saved), store.load())
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("DDC"))
        source.writeText(DdcTestFixture.TEXT); button(main, "保存当前参数").performClick()
        val saving = latest(); val input = descendants(saving.window!!.decorView).filterIsInstance<EditText>().single(); input.setText("不该写入")
        source.writeText("invalid again"); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(saving.isShowing); assertNotNull(input.error); assertEquals(listOf(saved), store.load()); assertEquals(0, applies)
    }

    @Test fun overwriteConfirmationRechecksFoundationAndProtectsSavedEmbeddedBytes() {
        val saved = store.save("保留收藏", foundation(), DdcTestFixture.settings)
        val main = launch(); button(main, "保留收藏").performClick(); item(latest(), 2); val confirmation = latest()
        source.writeText("invalid after confirmation"); confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(listOf(saved), store.load()); assertEquals(0, applies); assertTrue(main.isShowing)
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("DDC"))
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP); super.onCreate(savedInstanceState); setContentView(FrameLayout(this))
        }
    }
}
