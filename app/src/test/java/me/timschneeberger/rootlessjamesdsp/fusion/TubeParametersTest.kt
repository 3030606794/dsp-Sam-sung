package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class)
class TubeParametersTest {
    private val p = TubeParameters
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Test fun originalBaselineAndAllOptionalGroupsStayOffWithoutInitializingPreferences() {
        val prefs = TubeController.preferences(app); prefs.edit().clear().commit()
        assertEquals(p.defaults(), p.capture(prefs.all)); p.normalize(prefs); assertTrue(prefs.all.isEmpty())
        val settings = p.capture(emptyMap<String, Any>()); assertTrue(settings.valid())
        assertArrayEquals(intArrayOf(1, 0, 0, 0, 500, 0, 0, 1000, 0, 0, 0, 0, 1000, 0, 0, 0, 2, 0, 0, 0), settings.packet())
        assertEquals(9, p.numbers.size); assertTrue(p.switches.none { p.on(settings.values(), it) })
        assertFalse(settings.values().containsKey(p.ENABLED)); assertFalse(p.newKeys.contains(p.ENABLED))
        p.setProfessional(prefs, true); assertTrue(p.enabled(prefs.all)); p.numbers.forEach { assertFalse(prefs.contains(it.key)) }
    }
    @Test fun fullSettingsRoundTripAndScaledPacketOrderAreExact() {
        assertEquals(137, p.drive.parse("13.7")); assertNull(p.drive.parse("13.71"))
        assertEquals(-213, p.asym.parse("-21.3")); assertEquals(-29, p.output.parse("-2.9"))
        val s = TubeParameters.Settings(true, true, 137, 617, -213, true, 731, 8431, 137, 11753,
            true, 317, true, -29, true, 4, true)
        assertTrue(s.valid()); assertEquals(s, p.capture(s.values()))
        assertArrayEquals(intArrayOf(1, 1, 1, 137, 617, -213, 1, 731, 8431, 137, 11753, 1, 317, 1, -29, 1, 4, 1, 0, 0), s.packet())
        assertFalse(s.copy(drive = 181).valid()); assertFalse(s.copy(asym = -1001).valid()); assertFalse(s.copy(factor = 3).valid())
    }
    @Test fun filterBypassAndValidFrequencyBandsExcludeTheUnusedIntegerGaps() {
        for ((control, minimum) in listOf(p.softenCut to 20, p.preHP to 20, p.postLP to 1000)) {
            assertTrue(p.validValue(control, 0)); assertTrue(p.validValue(control, minimum)); assertTrue(p.validValue(control, control.max))
            assertFalse(p.validValue(control, 1)); assertFalse(p.validValue(control, minimum - 1))
            assertEquals(0, p.value(mapOf(control.key to minimum - 1), control))
        }
        assertFalse(p.defaults().copy(softenCut = 19).valid()); assertFalse(p.defaults().copy(preHP = 1).valid())
        assertFalse(p.defaults().copy(postLP = 999).valid())
    }
    @Test fun professionalAndGroupOffKeepFineSettingsAndDoNotChangeOtherToneModules() {
        val prefs = TubeController.preferences(app)
        prefs.edit().clear().putBoolean(p.ENABLED, false).putBoolean("v_analog", true).putInt("v_analog_mode", 2).commit()
        val tube = PreferenceCache.getPreferences(app, Constants.PREF_TUBE)
        tube.edit().clear().putBoolean("tube_enable", true).putString("sentinel", "keep").commit(); val before = tube.all
        val saved = p.defaults().copy(professional = true, satOn = true, drive = 137, toneOn = true, preHP = 137, factor = 4)
        p.apply(prefs, saved); p.setProfessional(prefs, false)
        assertEquals(saved.copy(professional = false), p.capture(prefs.all)); p.setProfessional(prefs, true)
        assertEquals(saved, p.capture(prefs.all)); assertFalse(p.moduleEnabled(prefs.all))
        assertTrue(prefs.getBoolean("v_analog", false)); assertEquals(2, prefs.getInt("v_analog_mode", 0)); assertEquals(before, tube.all)
    }
    @Test fun malformedPresentKeysNormalizeWithoutInitializingAbsentControlsOrPower() {
        val prefs = TubeController.preferences(app)
        prefs.edit().clear().putString(p.PROFESSIONAL, "true").putFloat(p.drive.key, 13.7f)
            .putInt(p.softenCut.key, 19).putInt(p.FACTOR, 3).putBoolean(p.ENABLED, true).commit()
        p.normalize(prefs); assertFalse(p.enabled(prefs.all)); assertEquals(0, p.value(prefs.all, p.drive))
        assertEquals(0, p.value(prefs.all, p.softenCut)); assertEquals(2, p.factor(prefs.all))
        assertFalse(prefs.contains(p.softness.key)); assertTrue(p.moduleEnabled(prefs.all))
    }
    @Test fun runtimeAllowsUnconfiguredAndHighRateOriginalCutoffWhileRejectingMalformedReports() {
        val v = IntArray(32).apply { this[0] = 1; this[1] = 2; this[13] = 1; this[30] = 2000 }
        assertNotNull(p.decode(v)); v[2] = 192000; v[15] = 22085; v[14] = 31; v[13] = 4; v[25] = 1
        v[16] = Int.MIN_VALUE; v[23] = -20000; assertNotNull(p.decode(v)); assertNull(p.decode(v.copyOf(31)))
        v[1] = 3; assertNull(p.decode(v)); v[1] = 2; v[13] = 3; assertNull(p.decode(v)); v[13] = 2
        v[15] = 96001; assertNull(p.decode(v)); v[15] = 22085; v[14] = 65; assertNull(p.decode(v)); v[14] = 31
        v[31] = 1; assertNull(p.decode(v)); v[31] = 0; v[7] = 5; assertNull(p.decode(v))
    }
}
