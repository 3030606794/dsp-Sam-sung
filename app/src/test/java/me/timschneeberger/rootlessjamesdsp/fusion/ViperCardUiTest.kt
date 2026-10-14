package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.fragment.app.FragmentActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
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

/** Real nested fragments and Material views, with no audio service or phone required. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ViperCardUiTest {
    private var controller: ActivityController<HostActivity>? = null
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = application.getSharedPreferences(ViperParameters.PREFS, Context.MODE_PRIVATE)

    @Before fun clearProfile() { preferences.edit().clear().commit() }

    @After fun destroyHost() {
        controller?.pause()?.stop()?.destroy()
        idle()
    }

    private fun launch(): ViperPreferenceFragment {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
        return host()
    }

    private fun host() = controller!!.get().supportFragmentManager
        .findFragmentByTag("viper") as ViperPreferenceFragment

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(12000, View.MeasureSpec.EXACTLY),
        )
        content.layout(0, 0, 1080, 12000)
        idle()
    }

    @Suppress("RestrictedApi")
    private fun row(fragment: ViperEffectPreferenceFragment, key: String): View {
        val adapter = fragment.listView.adapter as PreferenceGroupAdapter
        val position = adapter.getPreferenceAdapterPosition(key)
        assertTrue("Expected visible row $key", position >= 0)
        fragment.listView.scrollToPosition(position)
        layout()
        return requireNotNull(fragment.listView.findViewHolderForAdapterPosition(position)).itemView
    }

    private fun slider(fragment: ViperEffectPreferenceFragment, key: String) =
        row(fragment, key).findViewById<Slider>(R.id.seekbar)

    @Test fun eachEffectHasOneRoundedCardOneIconHeaderAndStartsCollapsedEvenWhenEnabled() {
        preferences.edit().putBoolean("v_reverb", true).commit()
        val host = launch()
        val cards = (host.requireView() as LinearLayout).children.toList()
        assertEquals(ViperParameters.groups.size, cards.size)
        assertEquals(ViperParameters.groups.size, host.effectFragments.size)
        ViperParameters.groups.forEachIndexed { index, definition ->
            assertTrue(cards[index] is MaterialCardView)
            val fragment = requireNotNull(host.effectFragment(definition.key))
            val header = fragment.effectHeader
            assertEquals(definition.title, header.title)
            assertNotNull(header.icon)
            assertFalse(header.isExpanded)
            assertEquals("Only one header, no category plus enable row", 1, fragment.listView.adapter!!.itemCount)
            val headerView = row(fragment, definition.key)
            assertEquals(View.VISIBLE, headerView.findViewById<View>(R.id.icon_frame).visibility)
            assertEquals(definition.title, headerView.findViewById<TextView>(android.R.id.title).text)
            assertNotNull(headerView.findViewById<MaterialSwitch>(R.id.switchWidget))
            definition.controls.forEach {
                val key = if (it.key == BassParameters.LEGACY_AMOUNT) BassParameters.AMOUNT else it.key
                assertFalse(host.findPreference<Preference>(key)!!.isVisible)
            }
        }
        assertTrue(host.effectFragment("v_reverb")!!.effectHeader.isChecked)
    }

    @Test fun titleOnlyExpandsAndSwitchOnlyEnablesWhileDisabledControlsRemainEditable() {
        val host = launch()
        val fragment = host.effectFragment("v_reverb")!!
        val header = fragment.effectHeader
        row(fragment, "v_reverb").performClick()
        layout()
        assertTrue(header.isExpanded)
        assertFalse(header.isChecked)
        assertFalse(preferences.getBoolean("v_reverb", false))
        assertTrue(slider(fragment, "v_reverb_wet").isEnabled)

        row(fragment, "v_reverb").findViewById<MaterialSwitch>(R.id.switchWidget).performClick()
        layout()
        assertTrue(header.isChecked)
        assertTrue(header.isExpanded)
        assertTrue(preferences.getBoolean("v_reverb", false))

        row(fragment, "v_reverb").performClick()
        layout()
        assertFalse(header.isExpanded)
        assertTrue(header.isChecked)
        row(fragment, "v_reverb").findViewById<MaterialSwitch>(R.id.switchWidget).performClick()
        layout()
        assertFalse(header.isChecked)
        assertFalse(header.isExpanded)
    }

    @Test fun realMaterialSliderKeyboardChangePersistsIntAndBroadcastsWithoutEnablingEffect() {
        preferences.edit().putInt("v_reverb_size", 73).commit()
        val host = launch()
        val fragment = host.effectFragment("v_reverb")!!
        fragment.effectHeader.setExpanded(true)
        layout()
        var notifications = 0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { notifications++ }
        }
        application.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))
        try {
            val actualSlider = slider(fragment, "v_reverb_size")
            assertEquals(1f, actualSlider.stepSize, 0f)
            assertTrue(actualSlider.requestFocus())
            assertTrue(actualSlider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
            layout()
            assertEquals(74, preferences.getInt("v_reverb_size", -1))
            assertTrue(preferences.all["v_reverb_size"] is Int)
            assertEquals(74, host.findPreference<ViperSliderPreference>("v_reverb_size")!!.value)
            assertTrue("User changes still reach the existing audio service", notifications > 0)
            assertFalse(fragment.effectHeader.isChecked)
        } finally {
            application.unregisterLocalReceiver(receiver)
        }
    }

    @Test fun recreationKeepsIndependentExpansionAndActualSliderValuesAcrossRepeatedViewIds() {
        preferences.edit().putBoolean("v_reverb", true)
            .putInt("v_reverb_size", 73).putInt("v_reverb_width", 12)
            .putInt("v_reverb_wet", 24).putInt("v_reverb_dry", 91)
            .putInt("v_bass_freq", 117).putString("v_dynamic_preset", "4").commit()
        var host = launch()
        host.effectFragment("v_reverb")!!.effectHeader.setExpanded(true)
        host.effectFragment("v_bass")!!.effectHeader.setExpanded(true)
        layout()
        controller!!.recreate()
        layout()
        host = host()
        assertEquals(10, host.childFragmentManager.fragments.size)
        assertTrue(host.effectFragment("v_reverb")!!.effectHeader.isExpanded)
        assertTrue(host.effectFragment("v_bass")!!.effectHeader.isExpanded)
        assertFalse(host.effectFragment("v_dynamic")!!.effectHeader.isExpanded)
        assertTrue(host.effectFragment("v_reverb")!!.effectHeader.isChecked)
        assertFalse(host.effectFragment("v_bass")!!.effectHeader.isChecked)
        mapOf("v_reverb_size" to 73, "v_reverb_width" to 12,
            "v_reverb_wet" to 24, "v_reverb_dry" to 91).forEach { (key, expected) ->
            val pref = host.findPreference<ViperSliderPreference>(key)!!
            assertEquals(expected, pref.value)
            assertEquals(expected.toFloat(), slider(host.effectFragment("v_reverb")!!, key).value, 0f)
        }
        assertEquals(117f, slider(host.effectFragment("v_bass")!!, "v_bass_freq").value, 0f)
        assertEquals("4", host.findPreference<ListPreference>("v_dynamic_preset")!!.value)
        assertEquals("深低音", host.findPreference<ListPreference>("v_dynamic_preset")!!.summary)
    }

    @Test fun loadingProfileRebindsEveryCardWithoutDuplicatingCardsOrChangingStoredTypes() {
        var host = launch()
        host.effectFragment("v_reverb")!!.effectHeader.setExpanded(true)
        layout()
        preferences.edit().putBoolean("v_bass", true).putInt("v_bass_mode", 2)
            .putInt("v_bass_freq", 38).putInt("v_reverb_wet", 41)
            .putString("v_dynamic_preset", "3").commit()
        application.sendLocalBroadcast(Intent(Constants.ACTION_PRESET_LOADED))
        layout()
        host = host()
        assertEquals(10, host.effectFragments.size)
        assertEquals(10, host.childFragmentManager.fragments.size)
        assertFalse(host.effectFragment("v_reverb")!!.effectHeader.isExpanded)
        assertTrue(host.effectFragment("v_bass")!!.effectHeader.isChecked)
        assertNull("The frequency is inactive in subwoofer mode", host.findPreference<Preference>("v_bass_freq"))
        assertEquals(38, preferences.getInt("v_bass_freq", -1))
        assertEquals("低音炮", host.findPreference<ViperSliderPreference>("v_bass_mode")!!.summary)
        assertEquals(41, host.findPreference<ViperSliderPreference>("v_reverb_wet")!!.value)
        assertEquals("3", host.findPreference<ListPreference>("v_dynamic_preset")!!.value)
        assertTrue(preferences.all["v_bass_mode"] is Int)
        assertTrue(preferences.all["v_dynamic_preset"] is String)
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = HOST_ID })
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .add(HOST_ID, ViperPreferenceFragment(), "viper").commitNow()
            }
        }

        companion object { const val HOST_ID = 0x2001 }
    }
}
