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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port",
    shadows = [ShadowPosixAtomicFile::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AnalogPresetsUiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = AnalogController.preferences(app)
    private lateinit var store: AnalogPresetStore
    private var host: ActivityController<HostActivity>? = null
    private var manager: AnalogPresetsDialog? = null
    private var applies = 0
    private var imports = 0
    private val exports = mutableListOf<String?>()
    @Before fun prepare() {
        prefs.edit().clear().putBoolean(AnalogParameters.ENABLED, false).commit()
        AnalogParameters.apply(prefs, AnalogParameters.defaults())
        store = AnalogPresetStore(File(temporary.newFolder(), AnalogPresetStore.FILE_NAME))
    }
    @After fun close() {
        manager?.dismiss(); host?.pause()?.stop()?.destroy(); idle()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun latest(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }
    private fun launch(): AlertDialog {
        host = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        manager = AnalogPresetsDialog(host!!.get(), store,
            { AnalogParameters.capture(prefs.all) }, { settings -> applies++; AnalogParameters.apply(prefs, settings) },
            { imports++ }, { id -> exports.add(id) })
        manager!!.show(); return latest()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun button(dialog: AlertDialog, label: String): TextView = descendants(dialog.window!!.decorView)
        .filterIsInstance<TextView>().first { it.isClickable && it.text.toString().startsWith(label) }
    private fun item(dialog: AlertDialog, index: Int) {
        val list = dialog.listView
        list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index)); idle()
    }

    @Test fun originalDefaultChoiceRestoresOriginalSoundAndRetainsModulePower() {
        AnalogParameters.apply(prefs, AnalogParameters.Settings(professional = true, coreOn = true, drive = 137,
            toneOn = true, hp = 137, mixOn = true, mix = 417, qualityOn = true, factor = 4, meters = true))
        val main = launch(); button(main, "系统声音方案").performClick()
        val defaults = latest(); assertEquals(6, defaults.listView.adapter.count)
        assertTrue(defaults.listView.adapter.getItem(0).toString().contains("专业调节关闭"))
        item(defaults, 1)
        assertEquals(1, applies); assertFalse(main.isShowing)
        assertEquals(AnalogParameters.defaults(1), AnalogParameters.capture(prefs.all))
        assertFalse(prefs.getBoolean(AnalogParameters.ENABLED, true))
    }

    @Test fun applyingWarmSchemeLoadsItsFullParametersWithoutSwitchingModulePower() {
        prefs.edit().putBoolean(TubeParameters.ENABLED, true).putInt(TubeParameters.drive.key, 73).commit()
        val main = launch(); button(main, "系统声音方案").performClick()
        val schemes = latest(); assertTrue(schemes.listView.adapter.getItem(3).toString().contains("轻暖"))
        item(schemes, 3)
        assertEquals(AnalogSystemPresets.entries[3].second, AnalogParameters.capture(prefs.all))
        assertEquals(1, applies); assertFalse(main.isShowing)
        assertFalse(prefs.getBoolean(AnalogParameters.ENABLED, true))
        assertTrue(prefs.getBoolean(TubeParameters.ENABLED, false)); assertEquals(73, prefs.getInt(TubeParameters.drive.key, 0))
    }

    @Test fun invalidSaveKeepsInputOpenThenPersonalApplyRestoresCompleteSettingsWithoutPowerChange() {
        AnalogParameters.apply(prefs, AnalogSystemPresets.entries[4].second.copy(toneOn = false))
        val main = launch(); button(main, "保存当前参数").performClick()
        val saving = latest(); val input = descendants(saving.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText(" "); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(saving.isShowing); assertNotNull(input.error); assertTrue(store.load().isEmpty())
        input.setText("个人收藏"); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertFalse(saving.isShowing); val saved = store.load().single()
        val changed = AnalogParameters.Settings(professional = true, coreOn = true, baseMode = 2, drive = 137, h2 = 731, qualityOn = true, factor = 4)
        AnalogParameters.apply(prefs, changed); prefs.edit().putBoolean(AnalogParameters.ENABLED, true).commit()
        button(main, "个人收藏").performClick(); item(latest(), 0)
        assertEquals(saved.settings, AnalogParameters.capture(prefs.all))
        assertTrue(prefs.getBoolean(AnalogParameters.ENABLED, false)); assertEquals(1, applies)
    }

    @Test fun importAndExportCallbacksLeaveAudioStateUntouchedAndDismissClosesNestedDialog() {
        val saved = store.save("导出这份", AnalogParameters.capture(prefs.all))
        val before = prefs.all.toMap(); val main = launch()
        button(main, "导入预设文件").performClick(); assertEquals(1, imports)
        button(main, "导出全部个人预设").performClick(); assertEquals(listOf<String?>(null), exports)
        button(main, "导出这份").performClick(); item(latest(), 3)
        assertEquals(listOf(null, saved.id), exports); assertEquals(before, prefs.all); assertEquals(0, applies)
        button(main, "保存当前参数").performClick(); val nested = latest(); assertTrue(nested.isShowing)
        manager!!.dismiss(); idle(); assertFalse(nested.isShowing); assertFalse(main.isShowing)
        assertEquals(listOf(saved), store.load()); assertEquals(before, prefs.all)
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP); super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }
}
