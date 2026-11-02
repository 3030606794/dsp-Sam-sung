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
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port",
    shadows = [ShadowPosixAtomicFile::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CompanderPresetsUiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = CompanderController.preferences(app)
    private lateinit var store: CompanderPresetStore
    private var host: ActivityController<HostActivity>? = null
    private var manager: CompanderPresetsDialog? = null
    private var applies = 0
    private var imports = 0
    private val exports = mutableListOf<String?>()
    @Before fun prepare() {
        prefs.edit().clear().putBoolean(CompanderParameters.ENABLED, false).commit()
        CompanderParameters.apply(prefs, CompanderParameters.defaults())
        store = CompanderPresetStore(File(temporary.newFolder(), CompanderPresetStore.FILE_NAME))
    }
    @After fun close() {
        manager?.dismiss(); host?.pause()?.stop()?.destroy(); idle()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun latest(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }
    private fun launch(canSave: () -> Boolean = { true }): AlertDialog {
        host = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        manager = CompanderPresetsDialog(host!!.get(), store,
            { CompanderParameters.capture(prefs.all) }, { settings -> applies++; CompanderParameters.apply(prefs, settings) },
            { imports++ }, { id -> exports.add(id) }, canSave = canSave)
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

    @Test fun originalChoiceRetainsCurrentFoundationAndPowerWhileClosingAllProfessionalGroups() {
        val original = CompanderParameters.capture(CompanderParameters.defaults().values() + mapOf(
            "compander_timeconstant" to 0.173f, "compander_granularity" to 3f,
            "compander_tftransforms" to "2", "compander_response" to
                "95;200;400;800;1600;3400;7500;-0.2;0.3;-0.4;0.5;-0.6;0.7;-0.8"
        )).copy(professional = true)
        CompanderParameters.apply(prefs, original)
        val main = launch(); button(main, "系统声音方案").performClick()
        val defaults = latest(); assertEquals(CompanderSystemPresets.entries(original).size, defaults.listView.adapter.count)
        assertTrue(defaults.listView.adapter.getItem(0).toString().contains("专业调节关闭"))
        item(defaults, 0)
        assertEquals(1, applies); assertFalse(main.isShowing)
        val restored = CompanderParameters.capture(prefs.all)
        assertEquals(CompanderSystemPresets.foundation(original), restored)
        assertFalse(prefs.getBoolean(CompanderParameters.ENABLED, true))
        listOf("compander_timeconstant", "compander_granularity", "compander_tftransforms", "compander_response")
            .forEach { assertEquals(original.values()[it], restored.values()[it]) }
    }

    @Test fun applyingSystemDirectionKeepsOriginalCurveAndDoesNotTouchDynamicTone() {
        val original = CompanderParameters.capture(CompanderParameters.defaults().values() + mapOf(
            "compander_timeconstant" to 0.193f, "compander_granularity" to 4f, "compander_tftransforms" to "3"))
        CompanderParameters.apply(prefs, original)
        prefs.edit().putInt("unrelated", 73).commit()
        val dynamic = app.getSharedPreferences(DynamicParameters.NAMESPACE, Application.MODE_PRIVATE)
        dynamic.edit().clear().putBoolean(DynamicParameters.ENABLED, true).putInt(DynamicParameters.LEGACY, 43).commit()
        val before = dynamic.all.toMap()
        val expected = CompanderSystemPresets.entries(original)[2].second
        val main = launch(); button(main, "系统声音方案").performClick(); item(latest(), 2)
        assertEquals(expected, CompanderParameters.capture(prefs.all))
        assertEquals(1, applies); assertFalse(main.isShowing)
        assertFalse(prefs.getBoolean(CompanderParameters.ENABLED, true)); assertEquals(73, prefs.getInt("unrelated", 0))
        assertEquals(before, dynamic.all)
    }

    @Test fun invalidSaveKeepsInputOpenThenPersonalApplyRestoresCompleteSettingsWithoutPowerChange() {
        CompanderParameters.apply(prefs, CompanderSystemPresets.entries(CompanderParameters.capture(prefs.all))[2].second.copy(professional = false))
        val main = launch(); button(main, "保存当前参数").performClick()
        val saving = latest(); val input = descendants(saving.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText(" "); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(saving.isShowing); assertNotNull(input.error); assertTrue(store.load().isEmpty())
        input.setText("个人收藏"); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertFalse(saving.isShowing); val saved = store.load().single()
        val changed = CompanderParameters.capture(CompanderParameters.defaults().values() + mapOf(
            "compander_timeconstant" to 0.273f, "compander_granularity" to 4f, "compander_tftransforms" to "3"))
        CompanderParameters.apply(prefs, changed); prefs.edit().putBoolean(CompanderParameters.ENABLED, true).commit()
        button(main, "个人收藏").performClick(); item(latest(), 0)
        assertEquals(saved.settings, CompanderParameters.capture(prefs.all))
        assertTrue(prefs.getBoolean(CompanderParameters.ENABLED, false)); assertEquals(1, applies)
    }

    @Test fun importAndExportCallbacksLeaveAudioStateUntouchedAndDismissClosesNestedDialog() {
        val saved = store.save("导出这份", CompanderParameters.capture(prefs.all))
        val before = prefs.all.toMap(); val main = launch()
        button(main, "导入预设文件").performClick(); assertEquals(1, imports)
        button(main, "导出全部个人预设").performClick(); assertEquals(listOf<String?>(null), exports)
        button(main, "导出这份").performClick(); item(latest(), 3)
        assertEquals(listOf(null, saved.id), exports); assertEquals(before, prefs.all); assertEquals(0, applies)
        button(main, "保存当前参数").performClick(); val nested = latest(); assertTrue(nested.isShowing)
        manager!!.dismiss(); idle(); assertFalse(nested.isShowing); assertFalse(main.isShowing)
        assertEquals(listOf(saved), store.load()); assertEquals(before, prefs.all)
    }

    @Test fun invalidCurrentCurveCannotBeSavedOrOverwrittenButValidSchemesCanStillRepairIt() {
        val saved = store.save("已有收藏", CompanderParameters.defaults())
        val before = store.load()
        prefs.edit().putString(CompanderParameters.BASE_CURVE, "broken").commit()
        val main = launch { CompanderParameters.validSavedCurve(prefs.all) }
        button(main, "保存当前参数").performClick(); idle()
        assertTrue(main.isShowing); assertEquals(before, store.load())
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("原曲线无效"))
        button(main, "已有收藏").performClick(); item(latest(), 2)
        assertTrue(main.isShowing); assertEquals(saved, store.load().single())
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("原曲线无效"))

        prefs.edit().putString(CompanderParameters.BASE_CURVE, CompanderParameters.DEFAULT_CURVE).commit()
        button(main, "保存当前参数").performClick()
        val saving = latest(); val input = descendants(saving.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText("不该写入")
        prefs.edit().putString(CompanderParameters.BASE_CURVE, "broken again").commit()
        saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(saving.isShowing); assertTrue(input.error.toString().contains("原曲线无效"))
        assertEquals(before, store.load()); saving.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()

        button(main, "系统声音方案").performClick(); item(latest(), 0)
        assertEquals(1, applies); assertFalse(main.isShowing)
        assertTrue(CompanderParameters.validSavedCurve(prefs.all)); assertEquals(before, store.load())
        assertFalse(prefs.getBoolean(CompanderParameters.ENABLED, true))
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP); super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }
}
