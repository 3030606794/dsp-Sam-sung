package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class)
class TimbreParametersTest {
    private val p = TimbreParameters
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Test fun defaultsDoNotInitializeNewPreferencesOrImportTheIneffectiveLegacyDrive() {
        val prefs = TimbreController.preferences(app); prefs.edit().clear().putFloat(p.LEGACY, 2f).commit()
        assertEquals(p.defaults(), p.capture(prefs.all)); p.normalize(prefs)
        assertEquals(mapOf(p.LEGACY to 2f), prefs.all); assertEquals(17, p.numbers.size)
        assertFalse(p.keys.contains(p.ENABLED)); assertFalse(p.keys.contains(p.LEGACY))
        assertTrue(p.switches.none { p.on(p.defaults().values(), it) })
        assertArrayEquals(intArrayOf(1, 0, 0, 0, 1000, 1000, 1000, 1000, 1000, 0, 600, 1900, 4400, 8000, 12000,
            0, 0, 0, 50, 0, 1000, 0, 0, 0, 1000, 0, 2, 0, 0, 0, 0, 0), p.defaults().packet())
    }
    @Test fun completeSettingsRoundTripAndProtocolOrderAreExact() {
        val s = TimbreParameters.Settings(true, true, 53, 1371, 317, 456, 789, 1234, true, 617, 1917, 4417, 8017, 12017,
            137, 15317, true, 73, true, 317, true, -29, true, 687, true, 4, true)
        assertTrue(s.valid()); assertEquals(s, p.capture(s.values()))
        assertArrayEquals(intArrayOf(1, 1, 1, 53, 1371, 317, 456, 789, 1234, 1, 617, 1917, 4417, 8017, 12017,
            137, 15317, 1, 73, 1, 317, 1, -29, 1, 687, 1, 4, 1, 0, 0, 0, 0), s.packet())
        assertFalse(s.copy(factor = 3).valid()); assertFalse(s.copy(cut2 = 666).valid())
        assertFalse(s.copy(colorHP = 999, colorLP = 1000).copy(colorLP = 999).valid())
    }
    @Test fun decimalPrecisionSignedOutputAndBypassGapsRejectUnrepresentableValues() {
        assertEquals(-23, p.drive.parse("-2.3")); assertNull(p.drive.parse("-2.31"))
        assertEquals(73, p.dcHz.parse("7.3")); assertNull(p.dcHz.parse("7.31")); assertNull(p.output.parse("0.1"))
        assertTrue(p.validValue(p.colorHP, 0)); assertFalse(p.validValue(p.colorHP, 19)); assertTrue(p.validValue(p.colorHP, 20))
        assertTrue(p.validValue(p.colorLP, 0)); assertFalse(p.validValue(p.colorLP, 999)); assertTrue(p.validValue(p.colorLP, 1000))
    }
    @Test fun orderedCrossoversAndFilterPairsRejectWithoutChangingOtherValues() {
        val v = p.defaults().values()
        assertTrue(p.candidateValid(v, p.cut1, 1850)); assertFalse(p.candidateValid(v, p.cut1, 1851))
        assertFalse(p.candidateValid(v, p.cut3, 1850)); assertTrue(p.candidateValid(v, p.cut3, 1950))
        val filters = v + mapOf(p.colorHP.key to 1000, p.colorLP.key to 1001)
        assertFalse(p.candidateValid(filters, p.colorLP, 1000)); assertTrue(p.candidateValid(filters, p.colorLP, 0))
        assertEquals(600, v[p.cut1.key]); assertEquals(1900, v[p.cut2.key])
    }
    @Test fun optionalGroupsPreserveLegacyFloatPowerAndOtherEffects() {
        val prefs = TimbreController.preferences(app)
        prefs.edit().clear().putBoolean(p.ENABLED, false).putFloat(p.LEGACY, 8.7f).commit()
        val other = PreferenceCache.getPreferences(app, ViperParameters.PREFS)
        other.edit().clear().putBoolean("v_tube", true).putBoolean("v_analog", true).putString("sentinel", "keep").commit()
        val before = other.all; val settings = p.defaults().copy(professional = true, coreOn = true, drive = 73,
            toneOn = true, dcOn = true, dcHz = 73, qualityOn = true, factor = 4)
        p.apply(prefs, settings); p.setProfessional(prefs, false); assertEquals(settings.copy(professional = false), p.capture(prefs.all))
        p.setProfessional(prefs, true); assertEquals(settings, p.capture(prefs.all)); assertFalse(p.moduleEnabled(prefs.all))
        assertEquals(8.7f, prefs.getFloat(p.LEGACY, 0f)); assertEquals(before, other.all)
    }
    @Test fun malformedPresentValuesNormalizeSafelyWithoutAddingAbsentFieldsOrChangingLegacy() {
        val prefs = TimbreController.preferences(app)
        prefs.edit().clear().putFloat(p.LEGACY, 7f).putBoolean(p.ENABLED, true).putString(p.PROFESSIONAL, "true")
            .putFloat(p.drive.key, 7.3f).putInt(p.cut1.key, 1900).putInt(p.colorHP.key, 19).putInt(p.FACTOR, 3).commit()
        p.normalize(prefs); assertFalse(p.enabled(prefs.all)); assertEquals(0, p.value(prefs.all, p.drive))
        assertEquals(600, p.value(prefs.all, p.cut1)); assertEquals(0, p.value(prefs.all, p.colorHP)); assertEquals(2, p.factor(prefs.all))
        assertFalse(prefs.contains(p.cut2.key)); assertTrue(p.moduleEnabled(prefs.all)); assertEquals(7f, prefs.getFloat(p.LEGACY, 0f))
    }
    @Test fun numericEndpointsRoundTripWhileOwnBoundsAreStrict() {
        for (control in p.numbers) {
            for (value in listOf(control.min, control.default, control.max)) {
                assertTrue(p.validValue(control, value)); assertEquals(value, p.value(mapOf(control.key to value), control))
                assertEquals(value, control.parse(control.number(value)))
            }
            assertFalse(p.validValue(control, control.min - 1)); assertFalse(p.validValue(control, control.max + 1))
        }
    }
    @Test fun runtimeAllowsActualAutomaticFactorsAndLowRateLimitsButRejectsMalformedData() {
        val v = IntArray(48).apply { this[0] = 1; this[1] = 2; this[13] = 1; this[31] = 2000 }
        assertNotNull(p.decode(v)); v[2] = 8000; v[26] = 192000; v[13] = 5; v[14] = 31; v[15] = -30
        for (i in 32..38) v[i] = 3800
        v[27] = Int.MIN_VALUE; v[28] = -137; v[40] = Int.MIN_VALUE; v[43] = -20000; v[45] = 1; v[44] = 1000
        assertNotNull(p.decode(v)); assertNull(p.decode(v.copyOf(47)))
        for ((index, wrong) in listOf(1 to 3, 2 to 7999, 13 to 6, 14 to 129, 15 to 121, 26 to 192001, 32 to 4001,
            38 to 4001, 39 to 9, 40 to 20001, 44 to 1001, 45 to 2, 47 to 1)) {
            val bad = v.copyOf(); bad[index] = wrong; assertNull("Reject $index", p.decode(bad))
        }
    }
    @Test fun expandedTotalColorKeepsOldDefaultsAndIndividualBandBoundsWhileSoloRuntimeStaysScoped() {
        assertEquals(1000, p.amount.default); assertEquals(10000, p.amount.max)
        assertTrue(p.defaults().copy(amount = 10000).valid()); assertFalse(p.defaults().copy(amount = 10001).valid())
        assertTrue(listOf(p.band1, p.band2, p.band3, p.band4).all { it.max == 2000 })
        assertEquals(17, p.numbers.size); assertFalse(p.keys.contains("timbre_pro_solo_gain"))
        val v = IntArray(48).apply { this[0] = 1; this[1] = 2; this[5] = 11; this[13] = 1; this[31] = 2000; this[46] = 180 }
        assertNotNull(p.decode(v)); v[46] = 181; assertNull(p.decode(v)); v[46] = 60; v[5] = 2; assertNull(p.decode(v))
        v[46] = 0; assertNotNull(p.decode(v)); v[5] = 12; assertNull(p.decode(v))
    }
}
