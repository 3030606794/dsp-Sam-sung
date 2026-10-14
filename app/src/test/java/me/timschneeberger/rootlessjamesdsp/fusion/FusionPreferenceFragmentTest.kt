package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup

import com.google.android.material.button.MaterialButtonToggleGroup
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Runs the real preference fragments and creates/binds their Android views. A plain
 * Application avoids starting the root audio service: these are UI/lifecycle tests,
 * independent of the device, a Magisk installation, or native audio libraries.
 *
 * The cold-start test fails at ViperPreferenceFragment.onCreatePreferences on the
 * original build-5 code: a Preference.apply receiver has no manager before attachment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FusionPreferenceFragmentTest {
    private var controller: ActivityController<PreferenceHostActivity>? = null
    private val application: Application get() = RuntimeEnvironment.getApplication()

    @Before fun clearProfiles() {
        arrayOf(ViperParameters.PREFS, ToneController.PREFS, ViperController.STATUS_PREFS).forEach {
            application.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After fun destroyHost() {
        controller?.pause()?.stop()?.destroy()
        idle()
    }

    private fun launch(): PreferenceHostActivity {
        val activityController = Robolectric.buildActivity(PreferenceHostActivity::class.java)
        controller = activityController
        activityController.setup().visible()
        layout(activityController.get())
        return activityController.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun layout(activity: PreferenceHostActivity) {
        idle()
        val content = activity.findViewById<View>(android.R.id.content)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        content.layout(0, 0, 1080, 1920)
        idle()
    }

    private fun viper(activity: PreferenceHostActivity) =
        activity.supportFragmentManager.findFragmentByTag("viper") as ViperPreferenceFragment

    private fun tone(activity: PreferenceHostActivity) =
        activity.supportFragmentManager.findFragmentByTag("tone") as TonePreferenceFragment

    @Test fun coldStartCreatesBothRealPreferenceViewsAndDefaultControls() {
        val activity = launch()
        val viper = viper(activity)
        val tone = tone(activity)
        assertNotNull(viper.view)
        assertNotNull(tone.view)
        assertEquals(ViperParameters.groups.size, viper.effectFragments.size)
        assertTrue(tone.listView.adapter!!.itemCount > 0)
        listOf("fusion_james_enabled", "fusion_viper_enabled", "fusion_status", "v_pan", "v_eq_0", "v_speaker").forEach {
            assertNull("Removed controls must not appear on screen", viper.findPreference<Preference>(it))
        }

        ViperParameters.groups.flatMap { it.controls }.filter { it.key !in setOf("v_bass_freq", BassParameters.LEGACY_AMOUNT) }.forEach { definition ->
            val control = viper.findPreference<ViperSliderPreference>(definition.key)!!
            assertNotNull("Preference must be attached before consulting its manager", control.preferenceManager)
            assertEquals(definition.min, control.min)
            assertEquals(definition.max, control.max)
            assertEquals(definition.default, control.value)
            assertEquals(definition.label(definition.default), control.summary.toString())
        }
        listOf(BassParameters.frequency, BassParameters.amount, BassParameters.naturalQ).forEach { definition ->
            val control = viper.findPreference<BassNumericPreference>(definition.key)!!
            assertNotNull(control.preferenceManager)
            assertEquals(definition.default, control.value)
            assertEquals(definition.label(definition.default), control.summary.toString())
        }
        ToneController.KEYS.forEach {
            val control = tone.findPreference<ToneSliderPreference>(it)!!
            assertEquals(-100, control.min)
            assertEquals(100, control.max)
            assertEquals(0, control.value)
            assertFalse("Tone controls start collapsed", control.isVisible)
        }
    }

    @Test fun persistedProfileAndNegativeValuesSurviveActivityRecreation() {
        application.getSharedPreferences(ViperParameters.PREFS, Context.MODE_PRIVATE).edit()
            .putInt("v_pan", -62)
            .putBoolean("v_reverb", true)
            .putInt("v_reverb_size", 73)
            .putInt("v_eq_0", -9)
            .putInt("v_analog_mode", 2)
            .commit()
        ToneController.preferences(application).edit()
            .putBoolean(ToneController.ENABLED, true)
            .putString(ToneController.RANGE, "40")
            .putInt(ToneController.KEYS[0], -37)
            .putInt(ToneController.KEYS[9], 29)
            .commit()
        launch()
        controller!!.recreate()
        val activity = controller!!.get()
        layout(activity)
        val viper = viper(activity)
        val tone = tone(activity)
        assertNull(viper.findPreference<Preference>("v_pan"))
        assertNull(viper.findPreference<Preference>("v_eq_0"))
        assertEquals(73, viper.findPreference<ViperSliderPreference>("v_reverb_size")!!.value)
        assertFalse(viper.findPreference<ViperSliderPreference>("v_reverb_size")!!.isVisible)
        assertEquals("强", viper.findPreference<ViperSliderPreference>("v_analog_mode")!!.summary.toString())
        assertEquals(-37, tone.findPreference<ToneSliderPreference>(ToneController.KEYS[0])!!.value)
        assertEquals(-40, tone.findPreference<ToneSliderPreference>(ToneController.KEYS[0])!!.min)
        assertEquals(40, tone.findPreference<ToneSliderPreference>(ToneController.KEYS[0])!!.max)
        assertEquals(29, ToneController.payload(application)[10])
    }

    @Test fun toneRangeChangeAndResetRebuildAttachedViewsWithCorrectLimits() {
        ToneController.preferences(application).edit()
            .putBoolean(ToneController.ENABLED, true)
            .putInt(ToneController.KEYS[0], -87)
            .putInt(ToneController.KEYS[1], 93)
            .commit()
        val activity = launch()
        val fragment = tone(activity)
        val expand = fragment.findPreference<SwitchPreferenceGroup>(ToneController.ENABLED)!!
        expand.setExpanded(true)
        layout(activity)
        val range = fragment.findPreference<ToneRangePreference>(ToneController.RANGE)!!
        // Exercise the inline button view; range changes must not open a dialog.
        val buttons = fragment.view!!.findViewById<MaterialButtonToggleGroup>(R.id.fusion_tone_range_buttons)
        assertNotNull(buttons)
        buttons.check(R.id.fusion_tone_range_small)
        layout(activity)
        assertEquals(20, range.range)
        assertEquals("20", ToneController.preferences(application).getString(ToneController.RANGE, null))
        assertEquals(-20, fragment.findPreference<ToneSliderPreference>(ToneController.KEYS[0])!!.value)
        assertEquals(20, fragment.findPreference<ToneSliderPreference>(ToneController.KEYS[1])!!.value)
        ToneController.KEYS.forEach {
            val slider = fragment.findPreference<ToneSliderPreference>(it)!!
            assertEquals(-20, slider.min)
            assertEquals(20, slider.max)
        }
        val reset = fragment.findPreference<Preference>("fusion_tone_reset")!!
        assertTrue(reset.onPreferenceClickListener!!.onPreferenceClick(reset))
        layout(activity)
        ToneController.KEYS.forEach {
            val slider = fragment.findPreference<ToneSliderPreference>(it)!!
            assertEquals(-100, slider.min)
            assertEquals(100, slider.max)
            assertEquals(0, slider.value)
        }
        assertEquals(100, range.range)
    }

    @Test fun toneCanStayEnabledWhileCollapsedWithoutChangingParameters() {
        ToneController.preferences(application).edit()
            .putInt(ToneController.KEYS[0], 17).commit()
        val activity = launch()
        val fragment = tone(activity)
        val toggle = fragment.findPreference<SwitchPreferenceGroup>(ToneController.ENABLED)!!
        assertNull(toggle.summary)
        val slider = fragment.findPreference<ToneSliderPreference>(ToneController.KEYS[0])!!
        assertFalse(slider.isVisible)
        toggle.setValue(true)
        toggle.setExpanded(true)
        layout(activity)
        assertTrue(slider.isVisible)
        val before = ToneController.payload(application)
        val expand = fragment.findPreference<SwitchPreferenceGroup>(ToneController.ENABLED)!!
        expand.setExpanded(!expand.isExpanded)
        layout(activity)
        assertFalse(slider.isVisible)
        assertTrue(toggle.isChecked)
        assertArrayEquals(before, ToneController.payload(application))
        expand.setExpanded(!expand.isExpanded)
        layout(activity)
        assertTrue(slider.isVisible)
        assertEquals(17, slider.value)
    }

    @Test fun oldDisabledStagesAndRemovedSettingsCannotDisableOrReconfigureNewChain() {
        application.getSharedPreferences(ViperParameters.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("fusion_james_enabled", false)
            .putBoolean("fusion_viper_enabled", false)
            .putBoolean("v_eq", true).putInt("v_eq_0", 12)
            .putBoolean("v_speaker", true).putInt("v_volume", 0)
            .putInt("v_pan", -100).putInt("v_limit", 25).commit()
        val packets = mutableMapOf<Int, IntArray>()
        assertTrue(ViperController.sync(application) { id, values ->
            packets[id] = values.copyOf()
            true
        })
        assertArrayEquals(intArrayOf(1), packets[131072])
        assertArrayEquals(intArrayOf(1), packets[131073])
        setOf(65551, 65552, 65586, 65587, 65588, 65603).forEach { assertFalse(packets.containsKey(it)) }
    }

    @Test fun replacingFragmentsAfterProfileChangeRestoresValuesAndBindsViews() {
        val activity = launch()
        application.getSharedPreferences(ViperParameters.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("v_reverb", true).putInt("v_reverb_wet", 24).commit()
        ToneController.preferences(application).edit()
            .putString(ToneController.RANGE, "20").putInt(ToneController.KEYS[4], -18).commit()
        // Same replacement operation used by DspFragment after loading a device preset.
        activity.supportFragmentManager.beginTransaction()
            .replace(PreferenceHostActivity.VIPER_ID, ViperPreferenceFragment(), "viper")
            .replace(PreferenceHostActivity.TONE_ID, TonePreferenceFragment(), "tone")
            .commitNow()
        layout(activity)
        assertEquals(24, viper(activity).findPreference<ViperSliderPreference>("v_reverb_wet")!!.value)
        assertFalse(viper(activity).findPreference<ViperSliderPreference>("v_reverb_wet")!!.isVisible)
        assertEquals(-18, tone(activity).findPreference<ToneSliderPreference>(ToneController.KEYS[4])!!.value)
        assertNotNull(viper(activity).view)
        assertNotNull(tone(activity).view)
    }

    class PreferenceHostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(FrameLayout(this@PreferenceHostActivity).apply { id = VIPER_ID },
                    LinearLayout.LayoutParams(-1, 0, 1f))
                addView(FrameLayout(this@PreferenceHostActivity).apply { id = TONE_ID },
                    LinearLayout.LayoutParams(-1, 0, 1f))
            })
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .add(VIPER_ID, ViperPreferenceFragment(), "viper")
                    .add(TONE_ID, TonePreferenceFragment(), "tone")
                    .commitNow()
            }
        }

        companion object {
            const val VIPER_ID = 0x1001
            const val TONE_ID = 0x1002
        }
    }
}
