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
class RoomReverbPresetsUiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = RoomReverbController.preferences(app)
    private lateinit var store: RoomReverbPresetStore
    private var host: ActivityController<HostActivity>? = null
    private var manager: RoomReverbPresetsDialog? = null
    private var applies = 0
    private var imports = 0
    private val exports = mutableListOf<String?>()
    @Before fun prepare() {
        prefs.edit().clear().putBoolean(RoomReverbParameters.ENABLED, false).commit()
        RoomReverbParameters.apply(prefs, RoomReverbParameters.defaults(15))
        store = RoomReverbPresetStore(File(temporary.newFolder(), RoomReverbPresetStore.FILE_NAME))
    }
    @After fun close() {
        manager?.dismiss(); host?.pause()?.stop()?.destroy(); idle()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun latest(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }
    private fun launch(): AlertDialog {
        host = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        manager = RoomReverbPresetsDialog(host!!.get(), store,
            { RoomReverbParameters.capture(prefs.all) }, { settings -> applies++; RoomReverbParameters.apply(prefs, settings) },
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

    @Test fun selectingOriginalBaseRestoresItsDefaultsAndRetainsProfessionalAndModulePower() {
        RoomReverbParameters.apply(prefs, RoomReverbParameters.defaults(4, true).copy(
            timeOn = true, rt60 = 237, levelsOn = true, earlyTrim = -13, linked = true, mix = 417,
            widthsOn = true, earlyWidth = -100, toneOn = true, highCut = 8371, meters = true))
        val main = launch(); button(main, "原 13 个基础房型").performClick()
        val bases = latest(); assertEquals(13, bases.listView.adapter.count)
        assertTrue(bases.listView.adapter.getItem(1).toString().contains(RoomReverbParameters.baseName(1)))
        item(bases, 1)
        assertEquals(1, applies); assertFalse(main.isShowing)
        assertEquals(RoomReverbParameters.defaults(1, true), RoomReverbParameters.capture(prefs.all))
        assertFalse(prefs.getBoolean(RoomReverbParameters.ENABLED, true))
    }

    @Test fun selectingOriginalBaseWithProfessionalOffLeavesAllNewFlagsOff() {
        val main = launch(); button(main, "原 13 个基础房型").performClick()
        item(latest(), 12)
        assertEquals(RoomReverbParameters.defaults(18), RoomReverbParameters.capture(prefs.all))
        assertFalse(prefs.getBoolean(RoomReverbParameters.ENABLED, true))
    }

    @Test fun invalidSaveKeepsInputOpenThenPersonalApplyRestoresCompleteSettingsWithoutPowerChange() {
        val main = launch(); button(main, "保存当前参数").performClick()
        val saving = latest(); val input = descendants(saving.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText(" "); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(saving.isShowing); assertNotNull(input.error); assertTrue(store.load().isEmpty())
        input.setText("个人收藏"); saving.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertFalse(saving.isShowing); val saved = store.load().single()
        val changed = RoomReverbParameters.defaults(8, true).copy(timeOn = true, rt60 = 731, predelayOn = true, predelay = 173)
        RoomReverbParameters.apply(prefs, changed); prefs.edit().putBoolean(RoomReverbParameters.ENABLED, true).commit()
        button(main, "个人收藏").performClick(); item(latest(), 0)
        assertEquals(saved.settings, RoomReverbParameters.capture(prefs.all))
        assertTrue(prefs.getBoolean(RoomReverbParameters.ENABLED, false)); assertEquals(1, applies)
    }

    @Test fun importAndExportCallbacksLeaveAudioStateUntouchedAndDismissClosesNestedDialog() {
        val saved = store.save("导出这份", RoomReverbParameters.capture(prefs.all))
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
