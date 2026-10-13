package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.SurroundActivity
import me.timschneeberger.rootlessjamesdsp.utils.Constants
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SurroundUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        stopKoin()
        listOf(SurroundController.PREFS, SurroundController.STATUS_PREFS, Constants.PREF_APP,
            Constants.PREF_EQ, ViperParameters.PREFS).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val prefs = Preferences(app)
        startKoin { modules(module { single { prefs.App() }; single { prefs.Var() } }) }
    }

    @After fun cleanup() { idle(); stopKoin() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun payloadIsVersionedBoundedAndDoesNotTouchOtherEffects() {
        val eq = app.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
        val viper = app.getSharedPreferences(ViperParameters.PREFS, Context.MODE_PRIVATE)
        eq.edit().putString("marker", "eq-original").commit()
        viper.edit().putBoolean("v_bass", true).commit()
        val beforeEq = eq.all.toMap()
        val beforeViper = viper.all.toMap()
        assertArrayEquals(intArrayOf(1, 0, 200, 9000, 5500, 35), SurroundController.payload(app))
        SurroundController.preferences(app).edit().putInt(SurroundController.SELECTED, -900)
            .putInt("slot_0_low", Int.MAX_VALUE).putInt("slot_0_high", -1)
            .putInt("slot_0_attenuation", 800000).putInt("slot_0_delay", 0).commit()
        assertArrayEquals(intArrayOf(1, 0, 800, 4000, 10000, 20), SurroundController.payload(app))
        var sent = false
        assertTrue(SurroundController.sync(app, 0) { _, _ -> sent = true; true })
        assertFalse(sent)
        assertFalse(SurroundController.setEnabled(app, true))
        SurroundController.publishCapabilities(app, SurroundController.CAPABILITY)
        assertTrue(SurroundController.setEnabled(app, true))
        assertTrue(SurroundController.sync(app, SurroundController.CAPABILITY) { id, payload ->
            assertEquals(SurroundController.PARAMETER, id)
            assertArrayEquals(intArrayOf(1, 1, 800, 4000, 10000, 20), payload)
            sent = true
            true
        })
        assertTrue(sent)
        assertFalse(SurroundController.sync(app, 0) { _, _ -> fail("Unsupported parameter must not be sent"); true })
        assertFalse(SurroundController.supported(app))
        assertTrue(SurroundController.setEnabled(app, false))
        assertEquals(beforeEq, eq.all)
        assertEquals(beforeViper, viper.all)
    }

    @Test fun eightSlotsRetainEditsAndResetOnlyTheSelectedSlot() {
        val modified = SurroundController.Settings(310, 12000, 8750, 91)
        SurroundController.save(app, modified)
        for (slot in 1..7) {
            SurroundController.select(app, slot)
            assertEquals(SurroundController.defaults[slot], SurroundController.settings(app))
            SurroundController.save(app, SurroundController.settings(app).copy(delayUs = 50 + slot))
            assertFalse(SurroundController.enabled(app))
        }
        SurroundController.select(app, 0)
        assertEquals(modified, SurroundController.settings(app))
        SurroundController.publishCapabilities(app, 16)
        SurroundController.setEnabled(app, true)
        SurroundController.reset(app)
        assertEquals(SurroundController.defaults[0], SurroundController.settings(app))
        assertTrue(SurroundController.enabled(app))
        for (slot in 1..7) assertEquals(50 + slot, SurroundController.settings(app, slot).delayUs)
        // A persisted capability from another process is not sufficient to show an active switch.
        SurroundController.statusPreferences(app).edit().putString("session", "old-process").commit()
        assertFalse(SurroundController.supported(app))
    }

    @Test fun homepageRequiresCapabilitiesAllowsDisableAndOpensFullScreenEditor() {
        val controller = Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host = controller.get()
            idle()
            val power = host.findViewById<View>(R.id.surround_power)
            power.performClick()
            idle()
            assertFalse(SurroundController.enabled(app))
            assertFalse(power.isSelected)
            SurroundController.publishCapabilities(app, 16)
            idle()
            power.performClick()
            idle()
            assertTrue(power.isSelected)
            SurroundController.publishCapabilities(app, 0)
            idle()
            assertFalse(power.isSelected)
            power.performClick()
            assertFalse(SurroundController.enabled(app))
            SurroundController.select(app, 5)
            app.sendLocalBroadcast(Intent(Constants.ACTION_PRESET_LOADED))
            idle()
            assertTrue(host.findViewById<TextView>(R.id.surround_editor_entry).text.contains("6"))
            host.findViewById<View>(R.id.surround_editor_entry).performClick()
            assertEquals(SurroundActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun editorRetainsParametersAndRejectsNonFiniteInput() {
        val controller = Robolectric.buildActivity(SurroundActivity::class.java).setup().visible()
        try {
            val editor = controller.get()
            idle()
            assertNotNull(editor.findViewById<View>(R.id.surround_chart))
            assertFalse(editor.updateValue(SurroundActivity.Parameter.ATTENUATION, Double.NaN))
            assertFalse(editor.updateValue(SurroundActivity.Parameter.HIGH, Double.POSITIVE_INFINITY))
            assertFalse(editor.updateValue(SurroundActivity.Parameter.DELAY, 211.0))
            assertTrue(editor.updateValue(SurroundActivity.Parameter.ATTENUATION, 8.75))
            assertTrue(editor.updateValue(SurroundActivity.Parameter.LOW, 300.0))
            editor.setPinned(true)
            idle()
            controller.recreate()
            idle()
            assertEquals(8750, SurroundController.settings(app).attenuationMilliDb)
            assertEquals(300, SurroundController.settings(app).lowHz)
            assertFalse(SurroundController.enabled(app))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun frequencyPreviewMatchesBandEdgesAndExcludesAttenuationAndDelay() {
        val settings = SurroundController.Settings(300, 9000, 10000, 20)
        val response = SurroundResponse.calculate(settings)
        assertTrue(response.decibels.all { it.isFinite() && it <= 0.001 })
        assertTrue(response.decibels.first() < -40.0)
        assertTrue(response.decibels.last() < -20.0)
        assertTrue(response.decibels.maxOrNull()!! > -0.1)
        val second = SurroundResponse.calculate(settings.copy(attenuationMilliDb = 500, delayUs = 210))
        assertArrayEquals(response.decibels, second.decibels, 0.0)
    }

    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = android.R.id.content })
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(android.R.id.content, SurroundPreferenceFragment()).commitNow()
        }
    }
}
