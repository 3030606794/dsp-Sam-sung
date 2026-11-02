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
class DynamicPresetsUiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = DynamicController.preferences(app)
    private lateinit var store: DynamicPresetStore
    private var host: ActivityController<HostActivity>? = null
    private var manager: DynamicPresetsDialog? = null
    private var applies = 0
    private var imports = 0
    private val exports = mutableListOf<String?>()
    @Before fun prepare() {
        prefs.edit().clear().putBoolean(DynamicParameters.ENABLED, false).commit()
        DynamicParameters.apply(prefs, DynamicParameters.defaults())
        store = DynamicPresetStore(File(temporary.newFolder(), DynamicPresetStore.FILE_NAME))
    }
    @After fun close() {
        manager?.dismiss(); host?.pause()?.stop()?.destroy(); idle()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun latest(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }
    private fun launch(): AlertDialog {
        host = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        manager = DynamicPresetsDialog(host!!.get(), store,
            { DynamicParameters.capture(prefs.all) }, { settings -> applies++; DynamicParameters.apply(prefs, settings) },
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
        DynamicParameters.apply(prefs, DynamicParameters.Settings(professional = true, base = 4, strength = 73,
            coreOn = true, injectDelta = 317, toneOn = true, deepFreqDelta = 73, hp = 37, lp = 17317,
            adaptiveOn = true, detector = 0, mixOn = true, mix = 417, meters = true, response = true))
        val main = launch(); button(main, "系统声音方案").performClick()
        val defaults = latest(); assertEquals(4, defaults.listView.adapter.count)
        assertTrue(defaults.listView.adapter.getItem(0).toString().contains("专业调节关闭"))
        item(defaults, 0)
        assertEquals(1, applies); assertFalse(main.isShowing)
        assertEquals(DynamicParameters.defaults(), DynamicParameters.capture(prefs.all))
        assertFalse(prefs.getBoolean(DynamicParameters.ENABLED, true))
    }

    @Test fun applyingDeepSchemeLoadsFoundationStrengthAndItsFullParametersWithoutSwitchingPower() {
        prefs.edit().putString(DynamicParameters.BASE, "5").putInt(DynamicParameters.LEGACY, 73)
            .putBoolean(TubeParameters.ENABLED, true).putInt(TubeParameters.drive.key, 73).commit()
        val otherBefore = prefs.all.filterKeys { it !in DynamicParameters.keys }
        val main = launch(); button(main, "系统声音方案").performClick()
        val schemes = latest(); assertTrue(schemes.listView.adapter.getItem(2).toString().contains("紧致鼓点"))
        item(schemes, 2)
        assertEquals(DynamicSystemPresets.entries[2].second, DynamicParameters.capture(prefs.all))
        assertEquals(1, applies); assertFalse(main.isShowing)
        assertFalse(prefs.getBoolean(DynamicParameters.ENABLED, true))
        assertEquals("4", prefs.getString(DynamicParameters.BASE, null)); assertEquals(18, prefs.getInt(DynamicParameters.LEGACY, 0))
        assertEquals(otherBefore, prefs.all.filterKeys { it !in DynamicParameters.keys })
    }

    @Test fun invalidSaveKeepsInputOpenThenPersonalApplyRestoresCompleteSettingsWithoutPowerChange() {
        DynamicParameters.apply(prefs, DynamicSystemPresets.entries[2].second.copy(toneOn = false))
        val main = launch(); button(main, "保存当前参数").performClick()
        val saving = latest(); val input = descendants(saving.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText(" "); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(saving.isShowing); assertNotNull(input.error); assertTrue(store.load().isEmpty())
        input.setText("个人收藏"); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertFalse(saving.isShowing); val saved = store.load().single()
        val changed = DynamicParameters.Settings(professional = true, base = 0, strength = 73,
            coreOn = true, bassDelta = 73, adaptiveOn = true, detector = 0, threshold = -317)
        DynamicParameters.apply(prefs, changed); prefs.edit().putBoolean(DynamicParameters.ENABLED, true).commit()
        button(main, "个人收藏").performClick(); item(latest(), 0)
        assertEquals(saved.settings, DynamicParameters.capture(prefs.all))
        assertTrue(prefs.getBoolean(DynamicParameters.ENABLED, false)); assertEquals(1, applies)
    }

    @Test fun importAndExportCallbacksLeaveAudioStateUntouchedAndDismissClosesNestedDialog() {
        val saved = store.save("导出这份", DynamicParameters.capture(prefs.all))
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
