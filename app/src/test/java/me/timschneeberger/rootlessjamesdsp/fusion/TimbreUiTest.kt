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
import java.time.Duration

open class TimbreUiFixture {
    protected val app: Application get() = RuntimeEnvironment.getApplication()
    protected val p = TimbreParameters
    protected val c = TimbreController
    protected val prefs get() = c.preferences(app)
    protected var controller: ActivityController<Host>? = null
    protected var width = 320
    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()
    protected fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("timbre") as PreferenceGroupFragment
    protected fun group() = fragment().findPreference<SwitchPreferenceGroup>(p.ENABLED)!!
    @Before fun prepare() {
        stopKoin(); val preferences = Preferences(app); startKoin { modules(module { single { preferences.App() } }) }
        RuntimeEnvironment.setFontScale(1f); prefs.edit().clear().commit()
        c.stopAudition(app, clearSnapshots = true); c.publishRuntime(app, emptyList()); c.publishCapabilities(app, 1073741823)
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy(); controller = null; idle()
        c.stopAudition(app, clearSnapshots = true); c.publishRuntime(app, emptyList()); RuntimeEnvironment.setFontScale(1f); stopKoin()
    }
    protected fun launch(expanded: Boolean = true) {
        controller = Robolectric.buildActivity(Host::class.java).setup().visible(); fragment().listView.itemAnimator = null; group().setExpanded(expanded); layout()
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
    protected fun optional(on: Boolean = true) {
        prefs.edit().putBoolean(p.CORE_ON, on).putBoolean(p.TONE_ON, on).putBoolean(p.DC_ON, on).putBoolean(p.MIX_ON, on)
            .putBoolean(p.OUTPUT_ON, on).putBoolean(p.COMP_ON, on).putBoolean(p.QUALITY_ON, on).putBoolean(p.METERS, on).commit(); layout()
    }
    protected fun number(control: BassParameters.Control) = fragment().findPreference<BassNumericPreference>(control.key)!!
    protected fun reading(frames: Int, mode: Int = c.audition, stage: Int = 0, rate: Int = 48000, pro: Boolean = true) {
        val v = IntArray(48).apply {
            this[0] = 1; this[1] = 2; this[2] = rate; this[3] = 1; this[4] = if (pro) 1 else 0; this[5] = mode; this[7] = stage
            this[11] = c.epoch; this[12] = 1; this[13] = 4; this[14] = 31; this[15] = 73
            this[16] = -631; this[17] = -688; this[18] = -427; this[19] = -455
            this[20] = -1337; this[21] = -1413; this[22] = -1137; this[23] = -1213; this[24] = 3
            this[25] = 1; this[26] = 96000; this[27] = 137; this[28] = -23; this[29] = 500; this[30] = 1000; this[31] = 2000
            this[32] = 617; this[33] = 1917; this[34] = 4417; this[35] = 8017; this[36] = 12017
            this[37] = 137; this[38] = 15317; this[39] = 73; this[40] = -3471; this[41] = -4471; this[42] = -5471; this[43] = -6471; this[44] = 687; this[45] = 1
            this[46] = if (mode in 7..11) c.soloGain else 0
        }
        c.publishRuntime(app, listOf(TimbreController.Reading(this, true, frames, rate, p.decode(v)))); idle()
    }
    class Host : FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP); super.onCreate(state)
            setContentView(FragmentContainerView(this).apply { id = R.id.card_tube })
            if (state == null) supportFragmentManager.beginTransaction().add(R.id.card_tube,
                PreferenceGroupFragment.newInstance(TimbreParameters.NAMESPACE, R.xml.dsp_tube_preferences), "timbre").commitNow()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class TimbreUiTest : TimbreUiFixture() {
    @Test fun originalTitlePowerAndFoldStayIndependentAndAllNewGroupsStartOff() {
        launch(false); assertEquals("模拟音色仿真", group().title); assertEquals(1, fragment().listView.adapter!!.itemCount)
        row(p.ENABLED).performClick(); layout(); assertTrue(group().isExpanded); assertFalse(group().isChecked)
        assertFalse(p.moduleEnabled(prefs.all)); assertFalse(p.enabled(prefs.all))
        p.numbers.forEach { assertFalse(prefs.contains(it.key)) }; p.switches.forEach { assertFalse(p.on(prefs.all, it)) }
        professional(true); p.numbers.forEach { assertFalse(number(it).isEnabled) }
        assertFalse(fragment().findPreference<SwitchPreferenceCompat>(p.DC_ON)!!.isEnabled)
        assertTrue(fragment().findPreference<SwitchPreferenceCompat>(p.QUALITY_ON)!!.isEnabled)
        assertFalse(fragment().findPreference<FieldChoicePreference>(p.FACTOR)!!.isEnabled)
    }
    @Test fun seventeenNumbersSupportInputOneTickButtonsAndSingleReset() {
        launch(); professional(true); optional()
        for (control in p.numbers) {
            val candidate = when (control.key) {
                p.colorHP.key -> 137
                p.colorLP.key -> 15317
                p.output.key -> -29
                else -> control.default + if (control == p.mix || control == p.comp) -2 else 2
            }
            assertTrue("Valid input: ${control.key}", p.candidateValid(prefs.all, control, candidate))
            row(control.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<EditText>(android.R.id.text1)!!.setText(control.number(candidate))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); layout(); assertEquals("Input: ${control.key}", candidate, number(control).value)
            row(control.key).findViewById<View>(R.id.bass_increment).performClick(); idle(); assertEquals(candidate + 1, p.value(prefs.all, control))
            row(control.key).findViewById<View>(R.id.bass_decrement).performClick(); idle(); assertEquals(candidate, p.value(prefs.all, control))
            row(control.key).findViewById<View>(R.id.bass_reset).performClick(); idle(); assertEquals(control.default, p.value(prefs.all, control))
        }
        assertFalse(p.moduleEnabled(prefs.all)); assertEquals(0, c.audition)
    }
    @Test fun groupOffGreysControlsAndReopeningRestoresSavedValues() {
        launch(); professional(true); optional(); number(p.drive).requestValue(73); number(p.colorHP).requestValue(137)
        for ((key, controls) in listOf(p.CORE_ON to p.coreNumbers, p.TONE_ON to p.toneNumbers,
            p.MIX_ON to listOf(p.mix), p.OUTPUT_ON to listOf(p.output), p.COMP_ON to listOf(p.comp))) {
            val before = p.capture(prefs.all); prefs.edit().putBoolean(key, false).commit(); layout()
            for (control in controls) {
                assertFalse(number(control).isEnabled); assertFalse(row(control.key).findViewById<Slider>(R.id.seekbar).isEnabled)
                assertEquals(0.38f, row(control.key).findViewById<TextView>(R.id.seekbar_value).alpha, 0.001f)
                assertFalse(number(control).step(1)); assertFalse(number(control).resetToDefault())
            }
            prefs.edit().putBoolean(key, true).commit(); layout(); assertEquals(before, p.capture(prefs.all))
            controls.forEach { assertTrue(number(it).isEnabled) }
        }
        optional(false); p.numbers.forEach { assertFalse(number(it).isEnabled) }; assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun staleDialogCannotWriteAfterCoreProfessionalOrDcWasDisabledAndReenabled() {
        launch(); professional(true); optional(); number(p.drive).requestValue(73); number(p.dcHz).requestValue(73)
        for ((key, control) in listOf(p.CORE_ON to p.drive, p.PROFESSIONAL to p.drive, p.DC_ON to p.dcHz)) {
            row(control.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog; dialog.findViewById<EditText>(android.R.id.text1)!!.setText("8.1")
            if (key == p.PROFESSIONAL) { professional(false); professional(true) }
            else { prefs.edit().putBoolean(key, false).commit(); layout(); prefs.edit().putBoolean(key, true).commit(); layout() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); assertEquals(73, p.value(prefs.all, control)); dialog.dismiss()
        }
    }
    @Test fun orderedCrossoversFilterGapsAndSingleResetRejectWithoutMovingOtherControls() {
        launch(); professional(true); optional()
        assertTrue(number(p.cut1).requestValue(1850)); val before = p.capture(prefs.all)
        assertFalse(number(p.cut1).step(1)); assertEquals(before, p.capture(prefs.all))
        assertTrue(number(p.cut1).resetToDefault())
        // Test a reset that conflicts with the neighboring point: the neighbor must remain unchanged.
        assertTrue(number(p.cut2).requestValue(1950)); assertTrue(number(p.cut1).requestValue(1900))
        assertFalse(number(p.cut2).resetToDefault()); assertEquals(1950, number(p.cut2).value); assertEquals(1900, number(p.cut1).value)
        assertTrue(number(p.colorHP).step(1)); assertEquals(20, number(p.colorHP).value)
        assertTrue(number(p.colorHP).step(-1)); assertEquals(0, number(p.colorHP).value)
        assertFalse(number(p.colorHP).requestValue(19)); assertFalse(number(p.colorLP).requestValue(999))
        assertTrue(number(p.colorLP).step(1)); assertEquals(1000, number(p.colorLP).value)
        assertTrue(number(p.colorHP).requestValue(999)); assertFalse(number(p.colorHP).step(1))
        assertEquals(1000, number(p.colorLP).value); assertEquals(999, number(p.colorHP).value)
    }
    @Test fun dcAndQualityDependenciesGreyThenRestoreWithoutTouchingTheirSavedValues() {
        launch(); professional(true); optional(); number(p.dcHz).requestValue(73)
        val quality = fragment().findPreference<FieldChoicePreference>(p.FACTOR)!!
        assertTrue(quality.choose(1)); assertEquals(4, p.factor(prefs.all))
        prefs.edit().putBoolean(p.TONE_ON, false).putBoolean(p.QUALITY_ON, false).commit(); layout()
        assertFalse(fragment().findPreference<SwitchPreferenceCompat>(p.DC_ON)!!.isEnabled); assertFalse(number(p.dcHz).isEnabled)
        assertFalse(quality.isEnabled); assertFalse(quality.choose(0)); assertEquals(4, p.factor(prefs.all))
        prefs.edit().putBoolean(p.TONE_ON, true).putBoolean(p.QUALITY_ON, true).commit(); layout()
        assertTrue(number(p.dcHz).isEnabled); assertEquals(73, number(p.dcHz).value); assertTrue(quality.isEnabled); assertEquals(1, quality.value)
        professional(false); assertNull(fragment().findPreference<Preference>(p.drive.key)); professional(true)
        assertEquals(73, number(p.dcHz).value); assertEquals(4, p.factor(prefs.all)); assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun legacyDriveStaysHiddenAcrossModesAndExternalUpdatesWithoutRewritingItsSavedFloat() {
        prefs.edit().putFloat(p.LEGACY, 8.7f).commit(); launch()
        assertNull(fragment().findPreference<Preference>(p.LEGACY)); assertEquals(8.7f, prefs.getFloat(p.LEGACY, 0f))
        var hint = fragment().findPreference<Preference>("timbre_pro_baseline")!!.summary.toString()
        assertTrue(hint.contains("染色驱动")); assertFalse(hint.contains("8.7"))
        prefs.edit().putFloat(p.LEGACY, -2.3f).commit(); layout()
        assertNull(fragment().findPreference<Preference>(p.LEGACY))
        assertEquals(-2.3f, prefs.getFloat(p.LEGACY, 0f)); prefs.edit().putFloat(p.LEGACY, 8.7f).commit(); layout()
        professional(true); assertEquals(0, number(p.drive).value); assertNull(fragment().findPreference<Preference>(p.LEGACY))
        hint = fragment().findPreference<Preference>("timbre_pro_baseline")!!.summary.toString(); assertTrue(hint.contains("染色驱动"))
        optional(); number(p.drive).requestValue(73); professional(false); assertNull(fragment().findPreference<Preference>(p.LEGACY))
        assertEquals(8.7f, prefs.getFloat(p.LEGACY, 0f)); professional(true); assertEquals(73, number(p.drive).value)
    }
    @Test fun activityFoldPauseSoundEditAndCapabilityLossClearTransientTools() {
        launch(); professional(true); optional(); prefs.edit().putBoolean(p.ENABLED, true).commit(); reading(0); reading(512); layout()
        row("timbre_pro_save_a").performClick(); row("timbre_pro_save_b").performClick(); idle(); assertTrue(c.hasComparison(0))
        assertTrue(fragment().findPreference<FieldChoicePreference>("timbre_pro_audition")!!.choose(4))
        group().setExpanded(false); layout(); assertEquals(0, c.audition); assertFalse(c.hasComparison(0)); assertTrue(p.moduleEnabled(prefs.all))
        group().setExpanded(true); layout(); assertTrue(fragment().findPreference<FieldChoicePreference>("timbre_pro_audition")!!.choose(7))
        number(p.drive).requestValue(73); idle(); assertEquals(0, c.audition)
        assertTrue(fragment().findPreference<FieldChoicePreference>("timbre_pro_audition")!!.choose(10)); controller!!.pause(); idle(); assertEquals(0, c.audition)
        c.publishCapabilities(app, 134217727); layout(); assertFalse(fragment().findPreference<FieldChoicePreference>("timbre_pro_audition")!!.isEnabled)
        assertTrue(fragment().findPreference<Preference>("timbre_pro_compatibility")!!.summary.toString().contains("实际仍使用原版音色仿真"))
    }
    @Test fun driverReportsActualFrequencyDriveDcAndBranchLevelsAndOtherModulesAreIndependent() {
        val other = me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache.getPreferences(app, ViperParameters.PREFS)
        other.edit().clear().putBoolean("v_tube", true).putBoolean("v_analog", true).putString("sentinel", "keep").commit(); val before = other.all
        launch(); professional(true); optional(); prefs.edit().putBoolean(p.ENABLED, true).commit(); reading(0); reading(512); layout()
        val summary = fragment().findPreference<Preference>("timbre_pro_actual")!!.summary.toString()
        assertTrue(summary.contains("48000 Hz")); assertTrue(summary.contains("96000 Hz")); assertTrue(summary.contains("4×"))
        assertTrue(summary.contains("31 个采样（0.65 ms）")); assertTrue(summary.contains("输入峰值 -6.31／-6.88"))
        assertTrue(summary.contains("输出峰值 -4.27／-4.55")); assertTrue(summary.contains("617／1917／4417／8017／12017 Hz"))
        assertTrue(summary.contains("实际驱动 7.3 dB；补偿 68.7%")); assertTrue(summary.contains("137／-23 ppm"))
        assertTrue(summary.contains("-34.71／-44.71／-54.71／-64.71")); assertEquals(before, other.all)
        assertTrue(number(p.band1).title.toString().contains("实际 617–1917 Hz"))
    }
    @Test fun meterVisibilityPreservesMatchedAuditionEpochAndTheExistingNumericRow() {
        launch(); professional(true); optional(); prefs.edit().putBoolean(p.ENABLED, true).commit(); reading(0); reading(512); layout()
        row("timbre_pro_save_a").performClick(); row("timbre_pro_save_b").performClick(); idle()
        assertTrue(c.measure(app, this)); reading(1024, mode = 6, stage = 3); assertTrue(c.setMatch(app, true))
        assertTrue(fragment().findPreference<FieldChoicePreference>("timbre_pro_audition")!!.choose(4)); layout()
        c.sync(app, 268435455, this) { _, _ -> true }; val epoch = c.epoch; val drive = number(p.drive)
        for (visible in listOf(false, true)) {
            fragment().findPreference<SwitchPreferenceCompat>(p.METERS)!!.isChecked = visible; layout()
            assertTrue(c.sync(app, 268435455, this) { _, _ -> true }); assertEquals(4, c.audition); assertTrue(c.match); assertEquals(epoch, c.epoch)
            assertTrue(c.hasComparison(0)); assertTrue(c.hasComparison(1)); assertSame(drive, number(p.drive))
        }
        number(p.drive).requestValue(73); idle(); assertEquals(0, c.audition)
    }
    @Test fun extendedRangeIsEditableButOldDriverRejectsHighEditsAndClearlyPreservesImportedHighValues() {
        launch(); professional(true); optional(); assertTrue(number(p.amount).requestValue(10000))
        assertEquals("1000.0 %", number(p.amount).summary.toString()); assertTrue(number(p.amount).resetToDefault())
        c.publishCapabilities(app, 268435455); layout(); assertTrue(number(p.amount).requestValue(2000))
        assertFalse(number(p.amount).requestValue(2001)); assertEquals(2000, p.value(prefs.all, p.amount))
        p.apply(prefs, p.capture(prefs.all).copy(amount = 10000)); layout(); assertEquals(10000, number(p.amount).value)
        val compatibility = fragment().findPreference<Preference>("timbre_pro_compatibility")!!.summary.toString()
        assertTrue(compatibility.contains("旧驱动实际最高 200%")); assertTrue(compatibility.contains("保存值 1000.0 % 保留"))
        assertTrue(number(p.amount).resetToDefault()); assertEquals(1000, number(p.amount).value)
        assertNull(fragment().findPreference<Preference>(p.LEGACY))
    }
    @Test fun totalColorSoloAndTemporaryGainStayOutOfSavedSettingsAndClearOnFoldAndPause() {
        launch(); professional(true); optional(); prefs.edit().putBoolean(p.ENABLED, true).commit(); reading(0); reading(512); layout()
        val audition = fragment().findPreference<FieldChoicePreference>("timbre_pro_audition")!!
        val gain = fragment().findPreference<FieldChoicePreference>("timbre_pro_solo_gain")!!
        assertFalse(gain.isEnabled); assertTrue(audition.choose(11)); layout(); assertTrue(gain.isEnabled); assertTrue(gain.choose(3))
        reading(1024, mode = 11); layout(); assertEquals(180, c.soloGain); assertFalse(prefs.contains("timbre_pro_solo_gain"))
        val actual = fragment().findPreference<Preference>("timbre_pro_actual")!!.summary.toString()
        assertTrue(actual.contains("混合前总染色独听")); assertTrue(actual.contains("实际独听临时增益 18.0 dB")); assertTrue(actual.contains("混合前染色 RMS"))
        c.sync(app, 1073741823, this) { _, _ -> true }; prefs.edit().putBoolean(p.METERS, false).commit(); layout()
        c.sync(app, 1073741823, this) { _, _ -> true }; assertEquals(180, c.soloGain); assertEquals(11, c.audition)
        group().setExpanded(false); layout(); assertEquals(0, c.soloGain); assertEquals(0, c.audition)
        group().setExpanded(true); layout(); assertTrue(audition.choose(7)); layout(); assertTrue(gain.choose(2)); assertEquals(120, c.soloGain)
        controller!!.pause(); idle(); assertEquals(0, c.soloGain); assertEquals(0, c.audition)
    }
}

open class TimbreRasterFixture : TimbreUiFixture() {
    protected fun renderTimbre(w: Int, scale: Int) {
        width = w; RuntimeEnvironment.setFontScale(scale / 100f); launch(); professional(true); optional()
        prefs.edit().putBoolean(p.ENABLED, true).putInt(p.drive.key, 73).putInt(p.amount.key, 10000).putInt(p.band1.key, 317).putInt(p.band2.key, 456)
            .putInt(p.band3.key, 789).putInt(p.band4.key, 1234).putInt(p.colorHP.key, 137).putInt(p.colorLP.key, 15317)
            .putInt(p.dcHz.key, 73).putInt(p.comp.key, 687).putInt(p.FACTOR, 4).putInt(p.mix.key, 317).putInt(p.output.key, -29).commit()
        reading(0); reading(512); layout()
        val folder = File("build/reports/timbre1").apply { mkdirs() }
        fun complete(view: View, ids: List<Int>) { for (id in ids) {
            val text = view.findViewById<TextView>(id); val lines = requireNotNull(text.layout)
            repeat(lines.lineCount) { assertEquals("No ellipsis: ${text.text}", 0, lines.getEllipsisCount(it))
                assertTrue("Text width: ${text.text}", lines.getLineMax(it) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1f) }
            assertTrue("Complete text: ${text.text}", lines.getLineEnd(lines.lineCount - 1) >= text.text.length)
        } }
        for (control in p.numbers) complete(row(control.key), listOf(android.R.id.title, R.id.seekbar_value, R.id.bass_reset))
        assertNull(fragment().findPreference<Preference>(p.LEGACY))
        for (key in p.switches) complete(row(key), listOf(android.R.id.title, android.R.id.summary))
        for (key in listOf("timbre_pro_baseline", "timbre_pro_bands", "timbre_pro_help", "timbre_pro_actual", "timbre_pro_compatibility", "timbre_pro_solo_help"))
            complete(row(key), listOf(android.R.id.title, android.R.id.summary))
        fun shot(name: String, key: String) {
            row(key); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80)); layout()
            val view = controller!!.get().findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface)); view.draw(Canvas(bitmap))
            File(folder, "${w}_${scale}_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        shot("baseline", p.PROFESSIONAL); shot("core", p.CORE_ON); shot("bands", p.band1.key); shot("crossovers", p.TONE_ON)
        shot("filters_dc", p.colorHP.key); shot("mix_quality", p.MIX_ON)
        assertTrue(fragment().findPreference<FieldChoicePreference>("timbre_pro_audition")!!.choose(11)); layout()
        assertTrue(fragment().findPreference<FieldChoicePreference>("timbre_pro_solo_gain")!!.choose(1)); reading(1024, mode = 11); layout()
        shot("tools", "timbre_pro_audition")
        prefs.edit().putBoolean(p.CORE_ON, false).putBoolean(p.TONE_ON, false).commit(); layout(); shot("disabled", p.CORE_ON)
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class TimbreRaster320_100Test : TimbreRasterFixture() { @Test fun render() = renderTimbre(320, 100) }
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class TimbreRaster320_150Test : TimbreRasterFixture() { @Test fun render() = renderTimbre(320, 150) }
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class TimbreRaster411_100Test : TimbreRasterFixture() { @Test fun render() = renderTimbre(411, 100) }
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class TimbreRaster411_150Test : TimbreRasterFixture() { @Test fun render() = renderTimbre(411, 150) }
