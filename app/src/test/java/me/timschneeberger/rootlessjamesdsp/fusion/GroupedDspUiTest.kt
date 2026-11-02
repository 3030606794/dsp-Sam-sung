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
import com.google.android.material.materialswitch.MaterialSwitch
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
    private val namespaces = listOf(ViperParameters.PREFS, ToneController.PREFS, SurroundController.PREFS,
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

    @Test fun elevenCardsContainAllTwentyThreeIndependentEffects() {
        val host = launch()
        val cards = host.findViewById<LinearLayout>(R.id.card_container)
        assertEquals(11, cards.childCount)
        (0 until cards.childCount).forEach { assertTrue(cards.getChildAt(it) is MaterialCardView) }
        assertEquals(25, dsp(host).childFragmentManager.fragments.size)
        val bass = host.findViewById<View>(R.id.viper_effect_bass)
        val dynamicBass = host.findViewById<View>(R.id.card_bass)
        assertSame(bass.parent, dynamicBass.parent)
        assertSame(host.findViewById<View>(R.id.viper_effect_reverb).parent,
            host.findViewById<View>(R.id.card_reverb).parent)
        assertNotSame(host.findViewById<View>(R.id.card_eq).parent,
            host.findViewById<View>(R.id.card_geq).parent)
        val children = dsp(host).childFragmentManager
        assertEquals(ViperParameters.PREFS,
            (children.findFragmentById(R.id.viper_effect_bass) as PreferenceFragmentCompat).preferenceManager.sharedPreferencesName)
        assertEquals(Constants.PREF_BASS,
            (children.findFragmentById(R.id.card_bass) as PreferenceFragmentCompat).preferenceManager.sharedPreferencesName)
    }

    private fun groups(host: Host) = dsp(host).childFragmentManager.fragments
        .filterIsInstance<PreferenceFragmentCompat>().mapNotNull {
            it.preferenceScreen?.getPreference(0) as? SwitchPreferenceGroup
        }

    private fun layout(host: Host) {
        idle()
        host.findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            layout(0, 0, measuredWidth, measuredHeight)
        }
        idle()
    }

    private fun header(host: Host, container: Int): View {
        layout(host)
        val fragment = dsp(host).childFragmentManager.findFragmentById(container) as PreferenceFragmentCompat
        return requireNotNull(fragment.listView.findViewHolderForAdapterPosition(0)).itemView
    }

    @Test fun everyEffectStartsCollapsedWithoutChangingItsSavedEnableState() {
        val host = launch()
        val before = snapshot()
        assertEquals(23, groups(host).size)
        groups(host).forEach { group ->
            assertFalse(group.isExpanded)
            (0 until group.preferenceCount).forEach { assertFalse(group.getPreference(it).isVisible) }
        }
        controller!!.recreate()
        idle()
        groups(controller!!.get()).forEach { assertFalse(it.isExpanded) }
        assertEquals(before, snapshot())
        assertEquals(25, dsp(controller!!.get()).childFragmentManager.fragments.size)
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
        assertEquals(25, children.fragments.size)
    }

    @Test fun equalizerHeadersExpandIndependentlyAndOnlyTheirRightSwitchesEnableSound() {
        val host = launch()
        val eq = app.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
        val peq = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        val eqKey = app.getString(R.string.key_eq_enable)
        val peqKey = app.getString(R.string.key_geq_enable)
        val eqGroup = (dsp(host).childFragmentManager.findFragmentById(R.id.card_eq)
            as PreferenceFragmentCompat).findPreference<SwitchPreferenceGroup>(eqKey)!!
        val peqGroup = (dsp(host).childFragmentManager.findFragmentById(R.id.card_geq)
            as PreferenceFragmentCompat).findPreference<SwitchPreferenceGroup>(peqKey)!!
        assertEquals(app.getString(R.string.fusion_eq_title), eqGroup.title)
        assertEquals(app.getString(R.string.fusion_peq_title), peqGroup.title)
        val before = snapshot()
        header(host, R.id.card_eq).performClick()
        layout(host)
        assertTrue(eqGroup.isExpanded)
        assertFalse(peqGroup.isExpanded)
        assertEquals(before, snapshot())
        header(host, R.id.card_geq).performClick()
        layout(host)
        assertTrue(eqGroup.isExpanded)
        assertTrue(peqGroup.isExpanded)
        assertEquals(before, snapshot())

        header(host, R.id.card_geq).findViewById<MaterialSwitch>(R.id.switchWidget).performClick()
        layout(host)
        assertTrue(peq.getBoolean(peqKey, false))
        assertFalse(eq.getBoolean(eqKey, false))
        header(host, R.id.card_geq).performClick()
        layout(host)
        assertFalse(peqGroup.isExpanded)
        assertTrue(peq.getBoolean(peqKey, false))
        header(host, R.id.card_eq).findViewById<MaterialSwitch>(R.id.switchWidget).performClick()
        layout(host)
        assertTrue(eq.getBoolean(eqKey, false))
        assertTrue(peq.getBoolean(peqKey, false))
        assertTrue(eqGroup.isExpanded)
        header(host, R.id.card_eq).findViewById<MaterialSwitch>(R.id.switchWidget).performClick()
        layout(host)
        assertFalse(eq.getBoolean(eqKey, true))
        assertTrue(peq.getBoolean(peqKey, false))
    }

    @Test fun ordinaryAndAdvancedHeadersOnlyChangePresentationWhenClicked() {
        val host = launch()
        val before = snapshot()
        listOf(R.id.viper_effect_bass, R.id.card_bass, R.id.card_liveprog, R.id.card_fusion_tone).forEach { id ->
            val fragment = dsp(host).childFragmentManager.findFragmentById(id) as PreferenceFragmentCompat
            val group = fragment.preferenceScreen.getPreference(0) as SwitchPreferenceGroup
            assertFalse(group.isExpanded)
            header(host, id).performClick()
            layout(host)
            assertTrue(group.isExpanded)
            assertEquals(before, snapshot())
            header(host, id).performClick()
            layout(host)
            assertFalse(group.isExpanded)
            assertEquals(before, snapshot())
        }
    }

    @Test fun mainPowerIconHasTransparentBackgroundAndGreenGrayStates() {
        val host = launch()
        val main = host.layoutInflater.inflate(R.layout.activity_dsp_main, null)
        val power = main.findViewById<me.timschneeberger.rootlessjamesdsp.view.FloatingToggleButton>(R.id.power_toggle)
        assertEquals(android.graphics.Color.TRANSPARENT, power.supportBackgroundTintList!!.defaultColor)
        val colors = host.getColorStateList(R.color.selector_floating_toggle_foreground_tint)
        power.isToggled = true
        assertEquals(android.graphics.Color.rgb(46, 173, 85), colors.getColorForState(power.drawableState, 0))
        power.isToggled = false
        assertEquals(android.graphics.Color.rgb(128, 128, 128), colors.getColorForState(power.drawableState, 0))
    }

    @Test fun eqPreviewOpensDedicatedActivityInsteadOfDialog() {
        val host = launch()
        val fragment = dsp(host).childFragmentManager.findFragmentById(R.id.card_eq)
            as me.timschneeberger.rootlessjamesdsp.fragment.PreferenceGroupFragment
        val preference = fragment.findPreference<me.timschneeberger.rootlessjamesdsp.preference.EqualizerPreference>(app.getString(R.string.key_eq_bands))!!
        assertNull(fragment.findPreference<androidx.preference.Preference>(app.getString(R.string.key_eq_filter_type)))
        assertNull(fragment.findPreference<androidx.preference.Preference>(app.getString(R.string.key_eq_interpolation)))
        fragment.onDisplayPreferenceDialog(preference)
        val intent = shadowOf(host).nextStartedActivity
        assertEquals(me.timschneeberger.rootlessjamesdsp.activity.EqualizerActivity::class.java.name, intent.component!!.className)
        assertTrue(dsp(host).childFragmentManager.fragments.none { it is androidx.fragment.app.DialogFragment })
    }

    @Test fun fullScreenEqEditsOnlyEqAndPreservesGainAcrossRecreation() {
        val beforePeq = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE).all.toMap()
        val next = Robolectric.buildActivity(me.timschneeberger.rootlessjamesdsp.activity.EqualizerActivity::class.java).setup().visible()
        try {
            next.get().setBandGain(4, 3.5)
            idle()
            val value = app.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
                .getString(app.getString(R.string.key_eq_bands), "")!!
            assertEquals(30, value.split(';').size)
            assertEquals(3.5, me.timschneeberger.rootlessjamesdsp.view.EqPreview.parse(value)[4], 0.0)
            assertFalse(app.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
                .getBoolean(app.getString(R.string.key_eq_enable), false))
            assertEquals(beforePeq, app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE).all)
            val chart = next.get().findViewById<View>(R.id.layout_peq)
            val scrollingParent = chart.parent
            assertEquals(0, (scrollingParent as View).paddingLeft)
            next.get().setPinned(true)
            assertNotSame(scrollingParent, chart.parent)
            assertEquals(value, app.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
                .getString(app.getString(R.string.key_eq_bands), ""))
            next.recreate()
            idle()
            assertTrue(app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).getBoolean("eq_pin_response", false))
            val reloaded = app.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
                .getString(app.getString(R.string.key_eq_bands), "")!!
            assertEquals(value, reloaded)
        } finally { next.pause().stop().destroy(); idle() }
    }

    @Test fun powerWaveExpandsFromTouchAndClosingWaveStartsAtScreenEdges() {
        val host = launch()
        val canvasHost = FrameLayout(host).apply { layout(0, 0, 1000, 2000) }
        val opening = me.timschneeberger.rootlessjamesdsp.view.PowerTransition(canvasHost, 120f, 1800f, true)
        val closing = me.timschneeberger.rootlessjamesdsp.view.PowerTransition(canvasHost, 120f, 1800f, false)
        assertEquals(0f, opening.radius, 0f)
        assertEquals(closing.maximumRadius, closing.radius, 0f)
        assertTrue(opening.maximumRadius >= kotlin.math.hypot(880f, 1800f))
        val before = snapshot()
        opening.start()
        opening.cancel()
        closing.start()
        closing.cancel()
        assertEquals(before, snapshot())
        assertFalse(canvasHost.isClickable)
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
