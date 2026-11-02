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
class AnalogParametersTest {
    private val p = AnalogParameters
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Test fun allOptionalGroupsStayOffWithoutInitializingPreferencesAndOriginalThreeModesRemain() {
        val prefs = AnalogController.preferences(app); prefs.edit().clear().commit()
        assertEquals(p.defaults(), p.capture(prefs.all)); p.normalize(prefs); assertTrue(prefs.all.isEmpty())
        assertEquals(15, p.numbers.size); assertEquals(p.newKeys + p.BASE, p.keys)
        assertFalse(p.newKeys.contains(p.BASE)); assertFalse(p.keys.contains(p.ENABLED))
        for (base in 0..2) {
            val settings = p.defaults(base); assertTrue(settings.valid()); assertEquals(settings, p.capture(settings.values()))
            assertArrayEquals(intArrayOf(1, 0, base, 0, 0, 1000, 0, 0, 0, 0, 0, 240, 717, 0, 717,
                633, 58, 6280, 0, 1000, 0, 0, 0, 2, 0, 0, 0, 0), settings.packet())
            assertTrue(p.switches.none { p.on(settings.values(), it) })
        }
        p.setProfessional(prefs, true); assertTrue(p.enabled(prefs.all)); p.numbers.forEach { assertFalse(prefs.contains(it.key)) }
    }
    @Test fun fullSettingsRoundTripAndScaledPacketOrderAreExact() {
        val s = AnalogParameters.Settings(true, 2, true, -53, 1371, 12345, -123, 456, -789,
            true, 317, 873, -4321, 967, 731, -123, 2345, true, 317, true, -29, true, 4, true)
        assertTrue(s.valid()); assertEquals(s, p.capture(s.values()))
        assertArrayEquals(intArrayOf(1, 1, 2, 1, -53, 1371, 12345, -123, 456, -789, 1, 317, 873, -4321,
            967, 731, -123, 2345, 1, 317, 1, -29, 1, 4, 1, 0, 0, 0), s.packet())
        assertFalse(s.copy(baseMode = 3).valid()); assertFalse(s.copy(drive = -121).valid())
        assertFalse(s.copy(h2 = 50001).valid()); assertFalse(s.copy(factor = 3).valid())
    }
    @Test fun preciseCoefficientOffsetsAndSignedToneValuesAcceptOnlyRepresentableSteps() {
        assertEquals(-53, p.drive.parse("-5.3")); assertNull(p.drive.parse("-5.31"))
        assertEquals(1, p.h2.parse("0.000001")); assertEquals(-50000, p.h5.parse("-0.050000"))
        assertNull(p.h3.parse("0.0000001")); assertNull(p.h4.parse("0.050001"))
        assertEquals(873, p.hpQ.parse("0.873")); assertEquals(-4321, p.lpDelta.parse("-4321"))
        assertEquals(-123, p.peakGain.parse("-1.23")); assertEquals(2345, p.peakBW.parse("2.345"))
        assertNull(p.peakBW.parse("2.3451")); assertNull(p.hp.parse("19")); assertNull(p.output.parse("0.1"))
    }
    @Test fun professionalAndGroupsRetainRelativeFineSettingsAcrossBaseChangesAndDoNotPowerOtherModules() {
        val prefs = AnalogController.preferences(app)
        prefs.edit().clear().putBoolean(p.ENABLED, false).putBoolean("v_tube", true).putInt("v_tube_pro_drive_tenths", 123).commit()
        val tube = PreferenceCache.getPreferences(app, Constants.PREF_TUBE)
        tube.edit().clear().putBoolean("tube_enable", true).putString("sentinel", "keep").commit(); val before = tube.all
        val saved = p.defaults(1).copy(professional = true, coreOn = true, drive = 137, h2 = 123, toneOn = true, lpDelta = -1000, factor = 4)
        p.apply(prefs, saved); p.setProfessional(prefs, false); assertEquals(saved.copy(professional = false), p.capture(prefs.all))
        prefs.edit().putInt(p.BASE, 2).commit(); p.setProfessional(prefs, true)
        assertEquals(saved.copy(baseMode = 2), p.capture(prefs.all)); assertFalse(p.moduleEnabled(prefs.all))
        assertTrue(prefs.getBoolean("v_tube", false)); assertEquals(123, prefs.getInt("v_tube_pro_drive_tenths", 0)); assertEquals(before, tube.all)
    }
    @Test fun malformedPresentKeysNormalizeWithoutInitializingAbsentControlsOrChangingPower() {
        val prefs = AnalogController.preferences(app)
        prefs.edit().clear().putString(p.PROFESSIONAL, "true").putFloat(p.drive.key, 13.7f).putInt(p.BASE, -1)
            .putInt(p.hpQ.key, 299).putInt(p.FACTOR, 3).putBoolean(p.ENABLED, true).commit()
        p.normalize(prefs); assertFalse(p.enabled(prefs.all)); assertEquals(0, p.value(prefs.all, p.drive))
        assertEquals(0, p.baseMode(prefs.all)); assertEquals(717, p.value(prefs.all, p.hpQ)); assertEquals(2, p.factor(prefs.all))
        assertFalse(prefs.contains(p.h2.key)); assertTrue(p.moduleEnabled(prefs.all))
    }
    @Test fun everyNumericRangeEndpointRoundTripsAndRejectsValuesOutsideItsOwnBounds() {
        for (control in p.numbers) {
            for (value in listOf(control.min, control.default, control.max)) {
                assertTrue(p.validValue(control, value)); assertEquals(value, p.value(mapOf(control.key to value), control))
                assertEquals(value, control.parse(control.number(value)))
            }
            assertFalse(p.validValue(control, control.min - 1)); assertFalse(p.validValue(control, control.max + 1))
            assertEquals(control.default, p.value(mapOf(control.key to "invalid"), control))
        }
    }
    @Test fun runtimeAcceptsSafeLowRateFiltersAndAllBaseModesWhileRejectingMalformedReports() {
        val v = IntArray(40).apply { this[0] = 1; this[1] = 2; this[13] = 1; this[30] = 2000 }
        assertNotNull(p.decode(v)); v[2] = 8000; v[15] = 240; v[26] = 3800; v[27] = 633; v[14] = 31; v[13] = 4
        v[16] = Int.MIN_VALUE; v[23] = -20000; v[31] = 2; v[32] = 70000; v[35] = -50000
        assertEquals(2, p.decode(v)!!.baseMode); assertNull(p.decode(v.copyOf(39)))
        for ((index, wrong) in listOf(1 to 3, 13 to 3, 15 to 4001, 26 to 4001, 27 to 4001, 31 to 3, 32 to 100001, 39 to 1)) {
            val bad = v.copyOf(); bad[index] = wrong; assertNull("Reject index $index", p.decode(bad))
        }
    }
}
