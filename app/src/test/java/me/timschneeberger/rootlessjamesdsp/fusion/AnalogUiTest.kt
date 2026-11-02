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
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
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

open class AnalogUiFixture {
    protected val app: Application get() = RuntimeEnvironment.getApplication()
    protected val p = AnalogParameters
    protected val c = AnalogController
    protected val prefs get() = c.preferences(app)
    protected var controller: ActivityController<Host>? = null
    protected var width = 320
    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()
    protected fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("viper-analog") as ViperEffectPreferenceFragment
    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(1f); prefs.edit().clear().commit(); c.stopAudition(app, clearSnapshots = true)
        c.publishRuntime(app, emptyList()); c.publishCapabilities(app, 134217727)
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy(); controller = null; idle(); c.stopAudition(app, clearSnapshots = true)
        c.publishRuntime(app, emptyList()); RuntimeEnvironment.setFontScale(1f)
    }
    protected fun launch(expanded: Boolean = true) {
        controller = Robolectric.buildActivity(Host::class.java).setup().visible()
        // Inspect settled preference rows, never RecyclerView removal/rebind animation frames.
        fragment().listView.itemAnimator = null
        fragment().effectHeader.setExpanded(expanded); layout()
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
        prefs.edit().putBoolean(p.CORE_ON, on).putBoolean(p.TONE_ON, on).putBoolean(p.MIX_ON, on)
            .putBoolean(p.OUTPUT_ON, on).putBoolean(p.QUALITY_ON, on).putBoolean(p.METERS, on).commit(); layout()
    }
    protected fun number(control: BassParameters.Control) = fragment().findPreference<BassNumericPreference>(control.key)!!
    protected fun reading(frames: Int, mode: Int = c.audition, stage: Int = 0, rate: Int = 48000, pro: Boolean = true, lowpass: Int = 15307) {
        val v = IntArray(40).apply {
            this[0] = 1; this[1] = 2; this[2] = rate; this[3] = 1; this[4] = if (pro) 1 else 0; this[5] = mode; this[7] = stage
            this[11] = c.epoch; this[12] = 1; this[13] = 4; this[14] = 31; this[15] = 317
            this[16] = -631; this[17] = -688; this[18] = -427; this[19] = -455
            this[20] = -1337; this[21] = -1413; this[22] = -1137; this[23] = -1213; this[24] = 3
            this[25] = 1; this[26] = lowpass; this[27] = 731; this[31] = p.baseMode(prefs.all); this[32] = 32345; this[33] = -23; this[34] = 1456; this[35] = -789; this[28] = 500; this[29] = 1000; this[30] = 2000
        }
        c.publishRuntime(app, listOf(AnalogController.Reading(this, true, frames, rate, p.decode(v)))); idle()
    }
    class Host : FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP); super.onCreate(state)
            setContentView(FragmentContainerView(this).apply { id = R.id.viper_effect_analog })
            if (state == null) supportFragmentManager.beginTransaction().add(R.id.viper_effect_analog,
                ViperEffectPreferenceFragment.newInstance("v_analog"), "viper-analog").commitNow()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class AnalogUiTest : AnalogUiFixture() {
    @Test fun originalPowerTitleAndFoldRemainIndependentWithoutInitializingNewSettings() {
        launch(false); assertEquals("模拟谐波染色", fragment().effectHeader.title); assertEquals(1, fragment().listView.adapter!!.itemCount)
        row(p.ENABLED).performClick(); layout(); assertTrue(fragment().effectHeader.isExpanded)
        assertFalse(p.moduleEnabled(prefs.all)); assertFalse(p.enabled(prefs.all))
        p.numbers.forEach { assertFalse(prefs.contains(it.key)) }; p.switches.forEach { assertFalse(p.on(prefs.all, it)) }
        professional(true); p.numbers.forEach { assertFalse(number(it).isEnabled) }
        assertTrue(fragment().findPreference<SwitchPreferenceCompat>(p.QUALITY_ON)!!.isEnabled)
        assertFalse(fragment().findPreference<FieldChoicePreference>(p.FACTOR)!!.isEnabled)
    }
    @Test fun allFifteenNumbersSupportInputOneTickButtonsAndIndependentReset() {
        launch(); professional(true); optional()
        for (control in p.numbers) {
            val candidate = control.min + 2
            assertTrue("Valid numeric input: ${control.key}", p.validValue(control, candidate))
            row(control.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<EditText>(android.R.id.text1)!!.setText(control.number(candidate))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); layout(); assertEquals("Numeric input: ${control.key}", candidate, number(control).value)
            row(control.key).findViewById<View>(R.id.bass_increment).performClick(); idle(); assertEquals("Increment: ${control.key}", candidate + 1, p.value(prefs.all, control))
            row(control.key).findViewById<View>(R.id.bass_decrement).performClick(); idle(); assertEquals("Decrement: ${control.key}", candidate, p.value(prefs.all, control))
            row(control.key).findViewById<View>(R.id.bass_reset).performClick(); idle(); assertEquals("Single reset: ${control.key}", control.default, p.value(prefs.all, control))
        }
        assertFalse(p.moduleEnabled(prefs.all)); assertEquals(0, c.audition)
    }
    @Test fun groupOffGreysControlsAndRestoresSavedValuesWithoutChangingPowerOrOtherGroups() {
        launch(); professional(true); optional(); number(p.drive).requestValue(137); number(p.hp).requestValue(317)
        for ((key, controls) in listOf(p.CORE_ON to p.coreNumbers, p.TONE_ON to p.toneNumbers,
            p.MIX_ON to listOf(p.mix), p.OUTPUT_ON to listOf(p.output))) {
            val before = p.capture(prefs.all); prefs.edit().putBoolean(key, false).commit(); layout()
            for (control in controls) {
                assertFalse(number(control).isEnabled); assertFalse(row(control.key).findViewById<Slider>(R.id.seekbar).isEnabled)
                assertEquals(0.38f, row(control.key).findViewById<TextView>(R.id.seekbar_value).alpha, 0.001f)
                assertFalse(number(control).step(1)); assertFalse(number(control).resetToDefault())
            }
            assertTrue(fragment().findPreference<SwitchPreferenceCompat>(p.QUALITY_ON)!!.isEnabled)
            assertTrue(fragment().findPreference<FieldChoicePreference>(p.FACTOR)!!.isEnabled)
            prefs.edit().putBoolean(key, true).commit(); layout(); assertEquals(before, p.capture(prefs.all))
            controls.forEach { assertTrue(number(it).isEnabled) }
        }
        optional(false); p.numbers.forEach { assertFalse(number(it).isEnabled) }; assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun staleNumericDialogCannotCommitAfterItsGroupOrProfessionalIsDisabledAndReenabled() {
        launch(); professional(true); optional(); number(p.drive).requestValue(137)
        for (key in listOf(p.CORE_ON, p.PROFESSIONAL)) {
            row(p.drive.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog; dialog.findViewById<EditText>(android.R.id.text1)!!.setText("15.1")
            if (key == p.PROFESSIONAL) professional(false) else { prefs.edit().putBoolean(key, false).commit(); layout() }
            if (key == p.PROFESSIONAL) professional(true) else { prefs.edit().putBoolean(key, true).commit(); layout() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); assertEquals(137, p.value(prefs.all, p.drive)); dialog.dismiss()
        }
    }
    @Test fun qualityChoiceIsIndependentOfCoreAndGroupToggleRestoresItsSavedFactor() {
        launch(); professional(true); prefs.edit().putBoolean(p.QUALITY_ON, true).commit(); layout()
        val choice = fragment().findPreference<FieldChoicePreference>(p.FACTOR)!!
        assertFalse(number(p.drive).isEnabled); assertTrue(choice.isEnabled); assertTrue(choice.choose(1)); assertEquals(4, p.factor(prefs.all))
        prefs.edit().putBoolean(p.QUALITY_ON, false).commit(); layout(); assertFalse(choice.isEnabled); assertFalse(choice.choose(0))
        assertEquals(4, p.factor(prefs.all)); prefs.edit().putBoolean(p.QUALITY_ON, true).commit(); layout()
        assertTrue(choice.isEnabled); assertEquals(1, choice.value)
        professional(false); assertNull(fragment().findPreference<Preference>(p.drive.key)); professional(true)
        assertEquals(4, p.factor(prefs.all)); assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun originalThreeModesStayAvailableAndFineOffsetsPersistWhileResetReturnsToCurrentBase() {
        launch(); professional(true); optional()
        val base = fragment().findPreference<ViperSliderPreference>(p.BASE)!!
        assertTrue(number(p.lpDelta).requestValue(-1000)); assertTrue(number(p.h2).requestValue(12345))
        for (mode in listOf(2, 1, 0)) {
            assertTrue(base.isEnabled); assertTrue(row(p.BASE).findViewById<Slider>(R.id.seekbar).isEnabled)
            base.value = mode; layout(); assertEquals(mode, p.baseMode(prefs.all))
            assertEquals(-1000, number(p.lpDelta).value); assertEquals(12345, number(p.h2).value)
            val hint = fragment().findPreference<Preference>("v_analog_pro_baseline")!!.summary.toString()
            assertTrue(hint.contains(p.baseNames[mode])); assertTrue(hint.contains("${p.baseLowpass[mode]} Hz"))
            assertTrue(number(p.lpDelta).resetToDefault()); assertEquals(0, p.value(prefs.all, p.lpDelta))
            assertEquals(mode, p.baseMode(prefs.all)); assertEquals(12345, p.value(prefs.all, p.h2)); number(p.lpDelta).requestValue(-1000)
        }
        professional(false); assertTrue(base.isEnabled); base.value = 2; layout(); professional(true)
        assertEquals(2, p.baseMode(prefs.all)); assertEquals(-1000, number(p.lpDelta).value); assertEquals(12345, number(p.h2).value)
        prefs.edit().putInt(p.BASE, 1).commit(); layout(); assertEquals(1, base.value)
        assertEquals("中度", row(p.BASE).findViewById<TextView>(R.id.seekbar_value).text.toString())
        val saved = p.capture(prefs.all).copy(baseMode = 0)
        p.apply(prefs, saved); layout(); assertEquals(0, base.value)
        assertEquals("轻度", row(p.BASE).findViewById<TextView>(R.id.seekbar_value).text.toString())
        assertEquals(-1000, number(p.lpDelta).value); assertEquals(12345, number(p.h2).value)
    }
    @Test fun activityFoldPauseParameterEditAndCapabilityLossClearTransientTools() {
        launch(); professional(true); optional(); prefs.edit().putBoolean(p.ENABLED, true).commit(); reading(0); reading(512); layout()
        row("v_analog_pro_save_a").performClick(); row("v_analog_pro_save_b").performClick(); idle(); assertTrue(c.hasComparison(0))
        assertTrue(fragment().findPreference<FieldChoicePreference>("v_analog_pro_audition")!!.choose(4))
        fragment().effectHeader.setExpanded(false); layout(); assertEquals(0, c.audition); assertFalse(c.hasComparison(0)); assertTrue(p.moduleEnabled(prefs.all))
        fragment().effectHeader.setExpanded(true); layout(); assertTrue(fragment().findPreference<FieldChoicePreference>("v_analog_pro_audition")!!.choose(1))
        number(p.drive).requestValue(137); idle(); assertEquals(0, c.audition)
        assertTrue(fragment().findPreference<FieldChoicePreference>("v_analog_pro_audition")!!.choose(2)); controller!!.pause(); idle(); assertEquals(0, c.audition)
        c.publishCapabilities(app, 67108863); layout(); assertFalse(fragment().findPreference<FieldChoicePreference>("v_analog_pro_audition")!!.isEnabled)
        assertTrue(fragment().findPreference<Preference>("v_analog_pro_compatibility")!!.summary.toString().contains("实际仍使用原三档染色"))
    }
    @Test fun actualFormatAndLevelsComeFromDriverAndOtherColorationModulesRemainIndependent() {
        val analog = PreferenceCache.getPreferences(app, Constants.PREF_TUBE)
        analog.edit().clear().putBoolean("tube_enable", true).putString("sentinel", "keep").commit(); val before = analog.all
        prefs.edit().putBoolean("v_tube", true).putInt("v_tube_pro_drive_tenths", 123).putInt(p.BASE, 2).commit()
        launch(); professional(true); optional(); prefs.edit().putBoolean(p.ENABLED, true).commit(); reading(0); reading(512); layout()
        val summary = fragment().findPreference<Preference>("v_analog_pro_actual")!!.summary.toString()
        assertTrue(summary.contains("48000 Hz")); assertTrue(summary.contains("4×")); assertTrue(summary.contains("31 个采样（0.65 ms）"))
        assertTrue(summary.contains("输入峰值 -6.31／-6.88")); assertTrue(summary.contains("输出峰值 -4.27／-4.55"))
        assertTrue(summary.contains("实际低切 317 Hz／高切 15307 Hz／补偿中心 731 Hz")); assertTrue(summary.contains("0.032345"))
        assertEquals(before, analog.all); assertTrue(prefs.getBoolean("v_tube", false)); assertEquals(123, prefs.getInt("v_tube_pro_drive_tenths", 0)); assertEquals(2, p.baseMode(prefs.all))
    }
    @Test fun ordinaryModeShowsActualLowRateFilterLimitsAndKeepsListeningDisabled() {
        prefs.edit().putBoolean(p.ENABLED, true).putInt(p.BASE, 0).commit(); launch()
        reading(0, rate = 22050, pro = false, lowpass = 10473); reading(512, rate = 22050, pro = false, lowpass = 10473); layout()
        val summary = fragment().findPreference<Preference>("v_analog_pro_actual")!!.summary.toString()
        assertTrue(summary.contains("原版轻度档")); assertTrue(summary.contains("22050 Hz")); assertTrue(summary.contains("高切 10473 Hz"))
        assertTrue(summary.contains("本档原高切 19650 Hz")); assertFalse(c.canListen(app))
        assertFalse(fragment().findPreference<FieldChoicePreference>("v_analog_pro_audition")!!.isEnabled)
        assertTrue(fragment().findPreference<ViperSliderPreference>(p.BASE)!!.isEnabled)
    }
    @Test fun meterVisibilityToggleLeavesActiveMatchedAuditionAndSoundControlsUntouched() {
        launch(); professional(true); optional(); prefs.edit().putBoolean(p.ENABLED, true).commit(); reading(0); reading(512); layout()
        row("v_analog_pro_save_a").performClick(); row("v_analog_pro_save_b").performClick(); idle()
        assertTrue(c.measure(app, this)); reading(1024, mode = 6, stage = 3); assertTrue(c.setMatch(app, true))
        assertTrue(fragment().findPreference<FieldChoicePreference>("v_analog_pro_audition")!!.choose(4)); layout()
        c.sync(app, 134217727, this) { _, _ -> true }; val epoch = c.epoch; val driveRow = number(p.drive)
        for (visible in listOf(false, true)) {
            fragment().findPreference<SwitchPreferenceCompat>(p.METERS)!!.isChecked = visible; layout()
            assertTrue(c.sync(app, 134217727, this) { _, _ -> true })
            assertEquals(4, c.audition); assertTrue(c.match); assertEquals(epoch, c.epoch)
            assertTrue(c.hasComparison(0)); assertTrue(c.hasComparison(1)); assertSame(driveRow, number(p.drive))
            assertEquals(4, fragment().findPreference<FieldChoicePreference>("v_analog_pro_audition")!!.value)
        }
        number(p.drive).requestValue(137); idle(); assertEquals(0, c.audition)
    }
}

open class AnalogRasterFixture : AnalogUiFixture() {
    protected fun renderAnalog(w: Int, scale: Int) {
        width = w; RuntimeEnvironment.setFontScale(scale / 100f); launch(); professional(true); optional()
        prefs.edit().putBoolean(p.ENABLED, true).putInt(p.drive.key, 137).putInt(p.h2.key, 12345).putInt(p.h3.key, -123).putInt(p.h4.key, 456).putInt(p.h5.key, -789)
            .putInt(p.BASE, 2).putInt(p.lpDelta.key, -1000).putInt(p.peakGain.key, -123)
            .putInt(p.FACTOR, 4).putInt(p.mix.key, 317).putInt(p.output.key, -29).commit(); reading(0); reading(512); layout()
        val folder = File("build/reports/analog1").apply { mkdirs() }
        fun complete(view: View, ids: List<Int>) { for (id in ids) {
            val text = view.findViewById<TextView>(id); val lines = requireNotNull(text.layout)
            repeat(lines.lineCount) { assertEquals("No ellipsis: ${text.text}", 0, lines.getEllipsisCount(it))
                assertTrue("Text width: ${text.text}", lines.getLineMax(it) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1f) }
            assertTrue("Complete text: ${text.text}", lines.getLineEnd(lines.lineCount - 1) >= text.text.length)
        } }
        for (control in p.numbers) complete(row(control.key), listOf(android.R.id.title, R.id.seekbar_value, R.id.bass_reset))
        for (key in p.switches) complete(row(key), listOf(android.R.id.title, android.R.id.summary))
        for (key in listOf("v_analog_pro_baseline", "v_analog_pro_advanced_harmonics", "v_analog_pro_help", "v_analog_pro_actual", "v_analog_pro_compatibility"))
            complete(row(key), listOf(android.R.id.title, android.R.id.summary))
        fun shot(name: String, key: String) {
            row(key); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80)); layout()
            val view = controller!!.get().findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface)); view.draw(Canvas(bitmap))
            File(folder, "${w}_${scale}_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        shot("baseline", p.BASE); shot("core", p.CORE_ON); shot("harmonics", p.h2.key); shot("tone", p.TONE_ON)
        shot("compensation", p.peak.key); shot("mix_quality", p.MIX_ON); shot("tools", "v_analog_pro_audition")
        prefs.edit().putBoolean(p.CORE_ON, false).putBoolean(p.TONE_ON, false).commit(); layout(); shot("disabled", p.CORE_ON)
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class AnalogRaster320_100Test : AnalogRasterFixture() { @Test fun render() = renderAnalog(320, 100) }
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class AnalogRaster320_150Test : AnalogRasterFixture() { @Test fun render() = renderAnalog(320, 150) }
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class AnalogRaster411_100Test : AnalogRasterFixture() { @Test fun render() = renderAnalog(411, 100) }
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class AnalogRaster411_150Test : AnalogRasterFixture() { @Test fun render() = renderAnalog(411, 150) }
