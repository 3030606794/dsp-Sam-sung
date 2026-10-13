package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.card.MaterialCardView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.DspFragment
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GroupedDspUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<Host>? = null
    private val namespaces = listOf(ViperParameters.PREFS, ToneController.PREFS,
        Constants.PREF_APP, Constants.PREF_COMPANDER, Constants.PREF_BASS, Constants.PREF_EQ,
        Constants.PREF_GEQ, Constants.PREF_DDC, Constants.PREF_CONVOLVER, Constants.PREF_LIVEPROG,
        Constants.PREF_TUBE, Constants.PREF_STEREOWIDE, Constants.PREF_CROSSFEED, Constants.PREF_REVERB)

    @Before fun prepare() {
        stopKoin()
        namespaces.forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        val preferences = Preferences(app)
        startKoin { modules(module {
            single { preferences.App() }
            single { preferences.Var() }
            single { RoutingObserver(app) }
        }) }
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        idle()
        stopKoin()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun launch(): Host {
        controller = Robolectric.buildActivity(Host::class.java).setup().visible()
        idle()
        return controller!!.get()
    }
    private fun dsp(host: Host) = host.supportFragmentManager.findFragmentByTag("dsp") as DspFragment
    private fun snapshot() = namespaces.associateWith { app.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }

    @Test fun tenCardsContainAllTwentyTwoIndependentEffects() {
        val host = launch()
        val cards = host.findViewById<LinearLayout>(R.id.card_container)
        assertEquals(10, cards.childCount)
        (0 until cards.childCount).forEach { assertTrue(cards.getChildAt(it) is MaterialCardView) }
        assertEquals(24, dsp(host).childFragmentManager.fragments.size)
        val bass = host.findViewById<View>(R.id.viper_effect_bass)
        val dynamicBass = host.findViewById<View>(R.id.card_bass)
        assertSame(bass.parent, dynamicBass.parent)
        assertSame(host.findViewById<View>(R.id.viper_effect_reverb).parent,
            host.findViewById<View>(R.id.card_reverb).parent)
        assertSame(host.findViewById<View>(R.id.card_eq).parent,
            host.findViewById<View>(R.id.card_geq).parent)
        val children = dsp(host).childFragmentManager
        assertEquals(ViperParameters.PREFS,
            (children.findFragmentById(R.id.viper_effect_bass) as PreferenceFragmentCompat).preferenceManager.sharedPreferencesName)
        assertEquals(Constants.PREF_BASS,
            (children.findFragmentById(R.id.card_bass) as PreferenceFragmentCompat).preferenceManager.sharedPreferencesName)
    }

    @Test fun categoryExpansionChangesNoSavedAudioSettingsAndSurvivesRecreation() {
        val host = launch()
        val before = snapshot()
        val content = host.findViewById<View>(R.id.group_bass_content)
        assertEquals(View.GONE, content.visibility)
        host.findViewById<View>(R.id.group_bass_header).performClick()
        idle()
        assertEquals(View.VISIBLE, content.visibility)
        assertEquals(before, snapshot())
        controller!!.recreate()
        idle()
        assertEquals(View.VISIBLE, controller!!.get().findViewById<View>(R.id.group_bass_content).visibility)
        assertEquals(before, snapshot())
        assertEquals(24, dsp(controller!!.get()).childFragmentManager.fragments.size)
    }

    @Test fun independentSwitchesStillPersistAndPresetReloadRebindsBothEngines() {
        val host = launch()
        val children = dsp(host).childFragmentManager
        val viper = children.findFragmentById(R.id.viper_effect_bass) as ViperEffectPreferenceFragment
        val james = children.findFragmentById(R.id.card_bass) as PreferenceFragmentCompat
        val jamesKey = app.getString(R.string.key_bass_enable)
        viper.effectHeader.setValue(true)
        idle()
        assertTrue(app.getSharedPreferences(ViperParameters.PREFS, Context.MODE_PRIVATE).getBoolean("v_bass", false))
        assertFalse(app.getSharedPreferences(Constants.PREF_BASS, Context.MODE_PRIVATE).getBoolean(jamesKey, false))
        james.findPreference<SwitchPreferenceGroup>(jamesKey)!!.setValue(true)
        idle()
        assertTrue(app.getSharedPreferences(Constants.PREF_BASS, Context.MODE_PRIVATE).getBoolean(jamesKey, false))
        app.getSharedPreferences(ViperParameters.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("v_bass", false).putInt("v_bass_freq", 93).commit()
        app.getSharedPreferences(Constants.PREF_BASS, Context.MODE_PRIVATE).edit().putBoolean(jamesKey, false).commit()
        app.sendLocalBroadcast(Intent(Constants.ACTION_PRESET_LOADED))
        idle()
        children.executePendingTransactions()
        idle()
        assertFalse((children.findFragmentById(R.id.viper_effect_bass) as ViperEffectPreferenceFragment).effectHeader.isChecked)
        assertFalse((children.findFragmentById(R.id.card_bass) as PreferenceFragmentCompat)
            .findPreference<SwitchPreferenceGroup>(jamesKey)!!.isChecked)
        assertEquals(93, app.getSharedPreferences(ViperParameters.PREFS, Context.MODE_PRIVATE).getInt("v_bass_freq", 0))
        assertEquals(24, children.fragments.size)
    }

    class Host : FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(state)
            setContentView(FrameLayout(this).apply { id = HOST })
            if (state == null) supportFragmentManager.beginTransaction()
                .add(HOST, DspFragment(), "dsp").commitNow()
        }
        companion object { const val HOST = 0x245017 }
    }
}
