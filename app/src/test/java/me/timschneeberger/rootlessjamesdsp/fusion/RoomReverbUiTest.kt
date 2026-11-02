package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.PreferenceGroupFragment
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

open class RoomReverbUiFixture {
    protected val app: Application get() = RuntimeEnvironment.getApplication()
    protected val p = RoomReverbParameters
    protected val c = RoomReverbController
    protected val prefs get() = c.preferences(app)
    protected var controller: ActivityController<Host>? = null
    protected var width = 320
    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()
    protected fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("room-reverb") as PreferenceGroupFragment
    protected fun group() = fragment().findPreference<SwitchPreferenceGroup>(p.ENABLED)!!
    @Before fun prepare() {
        stopKoin(); val preferences = Preferences(app); startKoin { modules(module { single { preferences.App() } }) }
        RuntimeEnvironment.setFontScale(1f); prefs.edit().clear().commit(); ReverbController.preferences(app).edit().clear().commit()
        c.stopAudition(app, clearSnapshots = true); c.publishRuntime(app, emptyList()); c.publishCapabilities(app, 33554431)
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy(); controller = null; idle()
        c.stopAudition(app, clearSnapshots = true); c.publishRuntime(app, emptyList()); RuntimeEnvironment.setFontScale(1f); stopKoin()
    }
    protected fun launch(expanded: Boolean = true) {
        controller = Robolectric.buildActivity(Host::class.java).setup().visible(); group().setExpanded(expanded); layout()
    }
    protected fun layout() {
        repeat(2) { idle(); controller!!.get().findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            layout(0, 0, width, 2400)
        } }; idle()
    }
    @Suppress("RestrictedApi") protected fun row(key: String): View {
        val list = fragment().listView; val adapter = list.adapter as PreferenceGroupAdapter
        val index = adapter.getPreferenceAdapterPosition(key); assertTrue("Visible row $key", index >= 0)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0); layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }
    protected fun professional(on: Boolean) {
        val pref = fragment().findPreference<SwitchPreferenceCompat>(p.PROFESSIONAL)!!
        assertTrue(pref.callChangeListener(on)); pref.isChecked = on; layout()
    }
    protected fun base(id: Int) { fragment().findPreference<ListPreference>(p.BASE)!!.callChangeListener(id.toString()); layout() }
    protected fun optional() {
        prefs.edit().putBoolean(p.TIME_ON, true).putBoolean(p.PREDELAY_ON, true).putBoolean(p.LEVELS_ON, true)
            .putBoolean(p.WIDTHS_ON, true).putBoolean(p.TONE_ON, true).putBoolean(p.OUTPUT_ON, true).putBoolean(p.METERS, true).commit(); layout()
    }
    protected fun number(control: BassParameters.Control) = fragment().findPreference<BassNumericPreference>(control.key)!!
    protected fun reading(frames: Int, mode: Int = c.audition, stage: Int = 0, stabilityProtected: Boolean = false) {
        val v = IntArray(32).apply {
            this[0] = 1; this[1] = 2; this[2] = 48000; this[3] = 1; this[4] = 1; this[5] = mode; this[7] = stage
            this[11] = c.epoch; this[12] = 1; this[13] = 173; this[14] = 137; this[15] = 8371
            this[16] = -427; this[17] = -455; this[18] = -1337; this[19] = -1413; this[20] = 3
            this[21] = 1; this[22] = p.base(prefs.all); this[23] = 6713; this[24] = 6000; this[25] = 2000
            this[26] = if (stabilityProtected) 1 else 0; this[27] = 123456
        }
        c.publishRuntime(app, listOf(RoomReverbController.Reading(this, true, frames, 48000, p.decode(v)))); idle()
    }
    class Host : FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP); super.onCreate(state)
            setContentView(FragmentContainerView(this).apply { id = R.id.card_reverb })
            if (state == null) supportFragmentManager.beginTransaction().add(R.id.card_reverb,
                PreferenceGroupFragment.newInstance(RoomReverbParameters.NAMESPACE, R.xml.dsp_reverb_preferences), "room-reverb").commitNow()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class RoomReverbUiTest : RoomReverbUiFixture() {
    @Test fun originalThirteenBasesPowerAndFoldingStayIndependentWithoutInitializingFineValues() {
        launch(false); assertEquals("房间混响", group().title); assertEquals(1, fragment().listView.adapter!!.itemCount)
        row(p.ENABLED).performClick(); layout(); assertTrue(group().isExpanded); assertFalse(group().isChecked)
        assertFalse(p.enabled(prefs.all)); assertEquals(15, p.base(prefs.all)); p.numbers.forEach { assertFalse(prefs.contains(it.key)) }
        val baseChoice = fragment().findPreference<ListPreference>(p.BASE)!!
        assertEquals(p.baseIds.map { it.toString() }, baseChoice.entryValues.toList()); assertEquals(13, baseChoice.entries.size)
        professional(true)
        for (id in p.baseIds) {
            base(id); assertTrue(baseChoice.isEnabled); assertEquals(p.defaults(id, true), p.capture(prefs.all)); assertFalse(group().isChecked)
            p.numbers.forEach { assertFalse(number(it).isEnabled) }
        }
    }
    @Test fun allNumbersSupportNumericInputOneTickButtonsAndCurrentBaseSingleReset() {
        launch(); professional(true)
        for (id in listOf(7, 18)) {
            base(id); optional()
            for (control in p.numbers) {
                if (control == p.mix) { prefs.edit().putBoolean(p.LINKED, true).commit(); layout() }
                row(control.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                dialog.findViewById<EditText>(android.R.id.text1)!!.setText(control.number(control.min + 2))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); layout(); assertEquals(control.min + 2, number(control).value)
                row(control.key).findViewById<View>(R.id.bass_increment).performClick(); idle(); assertEquals(control.min + 3, p.value(prefs.all, control))
                row(control.key).findViewById<View>(R.id.bass_decrement).performClick(); idle(); assertEquals(control.min + 2, p.value(prefs.all, control))
                row(control.key).findViewById<View>(R.id.bass_reset).performClick(); idle(); assertEquals(p.defaultValue(prefs.all, control), p.value(prefs.all, control))
            }
        }
        assertFalse(p.moduleEnabled(prefs.all)); assertTrue(ReverbController.preferences(app).all.isEmpty())
    }
    @Test fun linkedModeGreysThreeIndependentLevelsAndItsSwitchThenRestoresSavedValues() {
        launch(); professional(true); optional()
        val values = listOf(-13, 27, -37)
        p.levelNumbers.zip(values).forEach { (control, value) -> assertTrue(number(control).requestValue(value)) }
        row(p.dryTrim.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog; dialog.findViewById<EditText>(android.R.id.text1)!!.setText("-2.3")
        prefs.edit().putBoolean(p.LINKED, true).commit(); layout()
        assertFalse(fragment().findPreference<SwitchPreferenceCompat>(p.LEVELS_ON)!!.isEnabled); assertTrue(number(p.mix).isEnabled)
        for (control in p.levelNumbers) {
            assertFalse(number(control).isEnabled); assertFalse(row(control.key).findViewById<Slider>(R.id.seekbar).isEnabled)
            assertEquals(0.38f, row(control.key).findViewById<TextView>(R.id.seekbar_value).alpha, 0.001f)
            assertFalse(number(control).step(1)); assertFalse(number(control).resetToDefault())
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); assertEquals(-13, p.value(prefs.all, p.dryTrim)); dialog.dismiss()
        prefs.edit().putBoolean(p.LINKED, false).commit(); layout()
        assertTrue(fragment().findPreference<SwitchPreferenceCompat>(p.LEVELS_ON)!!.isEnabled)
        p.levelNumbers.zip(values).forEach { (control, value) -> assertTrue(number(control).isEnabled); assertEquals(value, number(control).value) }
    }
    @Test fun professionalOffRestoresOriginalPathAndLateNumericDialogsCannotCommit() {
        launch(); professional(true); optional(); number(p.rt60).requestValue(237)
        row(p.rt60.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog; dialog.findViewById<EditText>(android.R.id.text1)!!.setText("4.17")
        professional(false); assertNull(fragment().findPreference<Preference>(p.rt60.key)); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(237, p.value(prefs.all, p.rt60)); dialog.dismiss(); professional(true)
        assertEquals(237, number(p.rt60).value); assertTrue(number(p.rt60).isEnabled); assertEquals(15, p.base(prefs.all)); assertFalse(group().isChecked)
    }
    @Test fun baseSwitchRestoresRoomDefaultsAndRejectsAnyAlreadyOpenedOldRoomNumericDialog() {
        launch(); professional(true); optional(); number(p.rt60).requestValue(237)
        row(p.rt60.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog; dialog.findViewById<EditText>(android.R.id.text1)!!.setText("4.17")
        base(18); assertEquals(p.defaults(18, true), p.capture(prefs.all)); optional()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); assertEquals(3000, p.value(prefs.all, p.rt60)); dialog.dismiss()
        number(p.rt60).requestValue(237); number(p.rt60).resetToDefault(); assertEquals(3000, p.value(prefs.all, p.rt60))
    }
    @Test fun activityFoldPauseParameterEditsAndOldDriverHaveExplicitListeningRules() {
        launch(); professional(true); optional(); prefs.edit().putBoolean(p.ENABLED, true).commit(); reading(0); reading(512); layout()
        assertTrue(fragment().findPreference<FieldChoicePreference>("room_reverb_audition")!!.choose(2))
        row("room_reverb_save_a").performClick(); row("room_reverb_save_b").performClick(); idle()
        assertTrue(c.hasComparison(0)); group().setExpanded(false); layout(); assertEquals(0, c.audition); assertFalse(c.hasComparison(0))
        group().setExpanded(true); layout(); assertTrue(fragment().findPreference<FieldChoicePreference>("room_reverb_audition")!!.choose(3))
        number(p.rt60).requestValue(237); idle(); assertEquals(0, c.audition)
        assertTrue(fragment().findPreference<FieldChoicePreference>("room_reverb_audition")!!.choose(4)); controller!!.pause(); idle(); assertEquals(0, c.audition)
        c.publishCapabilities(app, 16777215); layout()
        assertFalse(fragment().findPreference<FieldChoicePreference>("room_reverb_audition")!!.isEnabled)
        assertTrue(fragment().findPreference<Preference>("room_reverb_compatibility")!!.summary.toString().contains("实际仍使用原房型"))
    }
    @Test fun roomAndViperRemainIndependentAndOverlapNoticeFollowsOnlyBothPowerSwitches() {
        launch(); professional(true); optional(); assertNull(fragment().findPreference<Preference>("room_reverb_overlap"))
        val viper = ReverbController.preferences(app); viper.edit().putBoolean(ReverbParameters.ENABLED, true).putInt(ReverbParameters.size.key, 713).commit()
        prefs.edit().putBoolean(p.ENABLED, true).commit(); layout(); assertNotNull(fragment().findPreference<Preference>("room_reverb_overlap"))
        base(7); assertEquals(713, viper.getInt(ReverbParameters.size.key, 0)); assertTrue(viper.getBoolean(ReverbParameters.ENABLED, false))
        viper.edit().putBoolean(ReverbParameters.ENABLED, false).commit(); layout(); assertNull(fragment().findPreference<Preference>("room_reverb_overlap"))
    }
    @Test fun wetFilterCutoffsRejectInvertedRangesAndAllowIndependentBypass() {
        launch(); professional(true); optional()
        assertTrue(number(p.highCut).requestValue(500)); assertTrue(number(p.lowCut).requestValue(300))
        assertFalse(number(p.lowCut).requestValue(500)); assertFalse(number(p.highCut).requestValue(300))
        assertEquals(300, p.value(prefs.all, p.lowCut)); assertEquals(500, p.value(prefs.all, p.highCut))
        assertTrue(number(p.highCut).requestValue(0)); assertTrue(number(p.lowCut).requestValue(1000))
        assertTrue(number(p.lowCut).resetToDefault()); assertEquals(0, p.value(prefs.all, p.lowCut))
    }
    @Test fun longTailProtectionExplainsTheActualAdjustmentOnlyForAnActiveReading() {
        launch(); professional(true); optional(); prefs.edit().putBoolean(p.ENABLED, true).commit()
        reading(0, stabilityProtected = true); layout()
        val state = fragment().findPreference<Preference>("room_reverb_actual")!!
        assertFalse(state.summary.toString().contains("长尾稳定保护已介入"))
        reading(512, stabilityProtected = true); layout()
        assertTrue(state.summary.toString().contains("长尾稳定保护已介入"))
        assertTrue(state.summary.toString().contains("减小 RT60 可保留更多原房型低频尾音"))
        reading(1024, stabilityProtected = false); layout()
        assertFalse(state.summary.toString().contains("长尾稳定保护已介入"))
    }
}

open class RoomReverbRasterFixture : RoomReverbUiFixture() {
    protected fun renderRoom(w: Int, scale: Int) {
        width = w; RuntimeEnvironment.setFontScale(scale / 100f); launch(); professional(true); base(7); optional()
        prefs.edit().putBoolean(p.LINKED, true).putBoolean(p.ENABLED, true).commit(); reading(0, stabilityProtected = true); reading(512, stabilityProtected = true); layout()
        val folder = File("build/reports/room-reverb1").apply { mkdirs() }
        fun complete(view: View, ids: List<Int>) { for (id in ids) {
            val text = view.findViewById<TextView>(id); val lines = requireNotNull(text.layout)
            repeat(lines.lineCount) { assertEquals("No ellipsis: ${text.text}", 0, lines.getEllipsisCount(it))
                assertTrue("Text width: ${text.text}", lines.getLineMax(it) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1f) }
            assertTrue("Complete text: ${text.text}", lines.getLineEnd(lines.lineCount - 1) >= text.text.length)
        } }
        for (control in p.numbers) complete(row(control.key), listOf(android.R.id.title, R.id.seekbar_value, R.id.bass_reset))
        for (key in p.switches) complete(row(key), listOf(android.R.id.title, android.R.id.summary))
        for (key in listOf("room_reverb_help", "room_reverb_actual", "room_reverb_compatibility"))
            complete(row(key), listOf(android.R.id.title, android.R.id.summary))
        fun shot(name: String, key: String) {
            row(key); val view = controller!!.get().findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface)); view.draw(Canvas(bitmap))
            File(folder, "${w}_${scale}_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        shot("time", p.TIME_ON); shot("linked", p.LEVELS_ON); shot("width_tone", p.WIDTHS_ON); shot("tools", "room_reverb_audition")
        shot("stability_protection", "room_reverb_actual")
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class RoomReverbRaster320_100Test : RoomReverbRasterFixture() { @Test fun render() = renderRoom(320, 100) }
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class RoomReverbRaster320_150Test : RoomReverbRasterFixture() { @Test fun render() = renderRoom(320, 150) }
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class RoomReverbRaster411_100Test : RoomReverbRasterFixture() { @Test fun render() = renderRoom(411, 100) }
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class RoomReverbRaster411_150Test : RoomReverbRasterFixture() { @Test fun render() = renderRoom(411, 150) }
