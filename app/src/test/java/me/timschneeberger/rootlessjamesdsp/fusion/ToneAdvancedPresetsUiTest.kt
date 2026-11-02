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
class ToneAdvancedPresetsUiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = ToneController.preferences(app)
    private lateinit var store: ToneAdvancedPresetStore
    private var host: ActivityController<HostActivity>? = null
    private var manager: ToneAdvancedPresetsDialog? = null
    private var applies = 0
    private var imports = 0
    private val exports = mutableListOf<String?>()
    @Before fun prepare() {
        prefs.edit().clear().putBoolean(ToneAdvancedParameters.ENABLED, false).commit()
        ToneAdvancedParameters.apply(prefs, ToneAdvancedParameters.defaults())
        store = ToneAdvancedPresetStore(File(temporary.newFolder(), ToneAdvancedPresetStore.FILE_NAME))
    }
    @After fun close() {
        manager?.dismiss(); host?.pause()?.stop()?.destroy(); idle()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun latest(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }
    private fun launch(canSave: () -> Boolean = { true }): AlertDialog {
        host = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        manager = ToneAdvancedPresetsDialog(host!!.get(), store,
            { ToneAdvancedParameters.capture(prefs.all) }, { settings -> applies++; ToneAdvancedParameters.apply(prefs, settings) },
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

    private fun foundation() = ToneAdvancedParameters.capture(ToneAdvancedParameters.defaults().values() +
        ToneController.KEYS.mapIndexed { i, key -> key to (i * 7 - 31) }.toMap() +
        (ToneController.RANGE to "100"))

    @Test fun originalChoiceKeepsAllTenValuesRangeAndPowerWhileClosingNewGroups() {
        val original = foundation().copy(professional = true, coreOn = true, delta = List(10) { 73 })
        ToneAdvancedParameters.apply(prefs, original)
        val main = launch(); button(main, "系统声音方案").performClick()
        val defaults = latest(); assertEquals(6, defaults.listView.adapter.count)
        assertTrue(defaults.listView.adapter.getItem(0).toString().contains("专业调节关闭"))
        item(defaults, 0)
        assertEquals(1, applies); assertFalse(main.isShowing)
        val restored = ToneAdvancedParameters.capture(prefs.all)
        assertEquals(ToneAdvancedSystemPresets.foundation(original), restored)
        assertFalse(prefs.getBoolean(ToneAdvancedParameters.ENABLED, true))
        (ToneController.KEYS.toList() + ToneController.RANGE).forEach {
            assertEquals(original.values()[it], restored.values()[it])
        }
        assertTrue(restored.values().values.filterIsInstance<Boolean>().all { !it })
    }

    @Test fun systemDirectionAddsProfessionalCorrectionsWithoutTouchingFoundationOrOtherModules() {
        val original = foundation()
        ToneAdvancedParameters.apply(prefs, original)
        prefs.edit().putInt("unrelated", 73).commit()
        val other = app.getSharedPreferences(TimbreParameters.NAMESPACE, Application.MODE_PRIVATE)
        other.edit().clear().putBoolean(TimbreParameters.ENABLED, true).commit()
        val before = other.all.toMap()
        val expected = ToneAdvancedSystemPresets.entries(original)[3].second
        val main = launch(); button(main, "系统声音方案").performClick(); item(latest(), 3)
        assertEquals(expected, ToneAdvancedParameters.capture(prefs.all))
        assertEquals(1, applies); assertFalse(main.isShowing)
        assertFalse(prefs.getBoolean(ToneAdvancedParameters.ENABLED, true)); assertEquals(73, prefs.getInt("unrelated", 0))
        assertEquals(before, other.all)
        assertEquals(original.base, ToneAdvancedParameters.capture(prefs.all).base)
        assertEquals(original.baseRange, ToneAdvancedParameters.capture(prefs.all).baseRange)
    }

    @Test fun invalidNameKeepsInputOpenThenPersonalApplyRestoresCompleteSettingsWithoutPowerChange() {
        ToneAdvancedParameters.apply(prefs, ToneAdvancedSystemPresets.entries(foundation())[2].second.copy(professional = false))
        val main = launch(); button(main, "保存当前参数").performClick()
        val saving = latest(); val input = descendants(saving.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText(" "); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(saving.isShowing); assertNotNull(input.error); assertTrue(store.load().isEmpty())
        input.setText("个人收藏"); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertFalse(saving.isShowing); val saved = store.load().single()
        ToneAdvancedParameters.apply(prefs, ToneAdvancedParameters.defaults())
        prefs.edit().putBoolean(ToneAdvancedParameters.ENABLED, true).commit()
        button(main, "个人收藏").performClick(); item(latest(), 0)
        assertEquals(saved.settings, ToneAdvancedParameters.capture(prefs.all))
        assertTrue(prefs.getBoolean(ToneAdvancedParameters.ENABLED, false)); assertEquals(1, applies)
    }

    @Test fun importExportCallbacksLeaveAudioUntouchedAndDismissClosesNestedDialogs() {
        val saved = store.save("导出这份", ToneAdvancedParameters.capture(prefs.all))
        val before = prefs.all.toMap(); val main = launch()
        button(main, "导入预设文件").performClick(); assertEquals(1, imports)
        button(main, "导出全部个人预设").performClick(); assertEquals(listOf<String?>(null), exports)
        button(main, "导出这份").performClick(); item(latest(), 3)
        assertEquals(listOf(null, saved.id), exports); assertEquals(before, prefs.all); assertEquals(0, applies)
        button(main, "保存当前参数").performClick(); val nested = latest(); assertTrue(nested.isShowing)
        manager!!.dismiss(); idle(); assertFalse(nested.isShowing); assertFalse(main.isShowing)
        assertEquals(listOf(saved), store.load()); assertEquals(before, prefs.all)
    }

    @Test fun invalidOriginalValueOrRangeCannotBeSavedEvenWhenCorruptionArrivesAfterOpeningInput() {
        val saved = store.save("已有收藏", ToneAdvancedParameters.defaults())
        val before = store.load()
        prefs.edit().putInt(ToneController.KEYS[0], 101).commit()
        val main = launch { ToneAdvancedParameters.validSavedFoundation(prefs.all) }
        button(main, "保存当前参数").performClick(); idle()
        assertTrue(main.isShowing); assertEquals(before, store.load())
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("原十项或范围无效"))
        button(main, "已有收藏").performClick(); item(latest(), 2)
        assertEquals(saved, store.load().single())
        prefs.edit().putInt(ToneController.KEYS[0], 0).commit()
        button(main, "保存当前参数").performClick()
        val saving = latest(); val input = descendants(saving.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText("不该写入")
        prefs.edit().putString(ToneController.RANGE, "broken").commit()
        saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(saving.isShowing); assertTrue(input.error.toString().contains("原十项或范围无效"))
        assertEquals(before, store.load()); saving.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        button(main, "系统声音方案").performClick(); item(latest(), 0)
        assertEquals(1, applies); assertFalse(main.isShowing)
        assertTrue(ToneAdvancedParameters.validSavedFoundation(prefs.all))
        assertEquals(before, store.load()); assertFalse(prefs.getBoolean(ToneAdvancedParameters.ENABLED, true))
    }

    @Test fun overwriteConfirmationRechecksFoundationAtCommitAndProtectsTheExistingFavorite() {
        val saved = store.save("保留收藏", foundation())
        val main = launch { ToneAdvancedParameters.validSavedFoundation(prefs.all) }
        button(main, "保留收藏").performClick(); item(latest(), 2)
        val confirm = latest()
        prefs.edit().putString(ToneController.KEYS[1], "bad-type").commit()
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(listOf(saved), store.load())
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("原十项或范围无效"))
        assertEquals(0, applies); assertTrue(main.isShowing)
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP); super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }
}
