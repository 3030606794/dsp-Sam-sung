package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomReverbParametersTest {
    private val p = RoomReverbParameters
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Test fun originalDefaultAndEveryNewFeatureRemainOffWithoutInitializingValues() {
        val prefs = RoomReverbController.preferences(app); prefs.edit().clear().commit()
        assertEquals(p.defaults(15), p.capture(prefs.all)); p.normalize(prefs); assertTrue(prefs.all.isEmpty())
        val s = p.capture(emptyMap<String, Any>())
        assertTrue(s.valid()); assertEquals(24, s.packet().size)
        assertArrayEquals(intArrayOf(1, 0, 15, 0, 180, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1000, 1000, 0, 0, 0, 16000, 0, 0, 0), s.packet())
        assertTrue(p.switches.none { p.on(s.values(), it) }); assertFalse(s.values().containsKey(p.ENABLED))
    }
    @Test fun allThirteenDefaultsMatchExistingSfRoomTable() {
        val expected = listOf(
            listOf(0, 320, 160, 700, 1000, 7000), listOf(1, 210, 100, 700, 1000, 9000),
            listOf(2, 230, 100, 700, 1000, 7000), listOf(4, 290, 120, 700, 1000, 6000),
            listOf(5, 380, 140, 700, 1000, 9000), listOf(7, 50, 70, -400, 800, 18000),
            listOf(8, 50, 80, 600, 900, 10000), listOf(9, 80, 120, -400, 800, 18000),
            listOf(11, 180, 220, -400, 900, 16000), listOf(15, 180, 100, 1000, 1000, 16000),
            listOf(16, 190, 100, 1000, 1000, 9000), listOf(17, 1200, 100, 100, 1000, 10000), listOf(18, 3000, 100, 100, 1000, 9000))
        assertEquals(expected.map { it[0] }, p.baseIds)
        for (row in expected) {
            val s = p.defaults(row[0]); assertTrue(s.valid())
            assertEquals(row, listOf(s.base, s.rt60, s.earlyScale, s.earlyWidth, s.lateWidth, s.damp))
            assertEquals(s, p.capture(s.values())); assertTrue(p.baseName(s.base).isNotBlank())
        }
    }
    @Test fun decimalUnitsAndPacketOrderAreExact() {
        assertEquals(237, p.rt60.parse("2.37")); assertNull(p.rt60.parse("2.371"))
        assertEquals(173, p.predelay.parse("17.3")); assertEquals(-13, p.earlyTrim.parse("-1.3"))
        assertEquals(-417, p.earlyWidth.parse("-0.417")); assertNull(p.damp.parse("8371.1"))
        val s = p.defaults(7, true).copy(timeOn = true, rt60 = 237, earlyScale = 173, predelayOn = true, predelay = 417,
            levelsOn = true, dryTrim = -13, earlyTrim = 27, lateTrim = -37, linked = true, mix = 314,
            widthsOn = true, earlyWidth = -271, lateWidth = 617, toneOn = true, lowCut = 173, highCut = 8371,
            damp = 6713, outputOn = true, output = -31, meters = true)
        assertEquals(s, p.capture(s.values()))
        assertArrayEquals(intArrayOf(1, 1, 7, 1, 237, 173, 1, 417, 1, -13, 27, -37, 1, 314, 1, -271, 617, 1, 173, 8371, 6713, 1, -31, 1), s.packet())
        assertFalse(s.copy(base = 3).valid()); assertFalse(s.copy(rt60 = 3001).valid()); assertFalse(s.copy(earlyWidth = -1001).valid())
        assertFalse(s.copy(lowCut = 500, highCut = 500).valid()); assertTrue(s.copy(lowCut = 1000, highCut = 0).valid())
    }
    @Test fun firstActivationUsesCurrentRoomAndClosingPreservesFineSettings() {
        val prefs = RoomReverbController.preferences(app); prefs.edit().clear().putString(p.BASE, "7").commit()
        p.setProfessional(prefs, true); assertEquals(p.defaults(7, true), p.capture(prefs.all))
        prefs.edit().putInt(p.rt60.key, 237).putBoolean(p.TIME_ON, true).commit()
        p.setProfessional(prefs, false); assertEquals(237, p.value(prefs.all, p.rt60)); assertTrue(p.on(prefs.all, p.TIME_ON))
        p.setProfessional(prefs, true); assertEquals(237, p.capture(prefs.all).rt60); assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun selectingBaseResetsFineValuesAndGroupsWhilePreservingProfessionalAndPower() {
        val prefs = RoomReverbController.preferences(app); prefs.edit().clear().putBoolean(p.ENABLED, true).commit()
        p.apply(prefs, p.defaults(7, true).copy(timeOn = true, rt60 = 237, linked = true, mix = 417, meters = true))
        for (base in p.baseIds) {
            p.selectBase(prefs, base); assertEquals(p.defaults(base, true), p.capture(prefs.all)); assertTrue(p.moduleEnabled(prefs.all))
            assertEquals(p.defaults(base).rt60, p.defaultValue(prefs.all, p.rt60))
        }
        p.setProfessional(prefs, false); p.selectBase(prefs, 0)
        assertEquals(p.defaults(0), p.capture(prefs.all)); assertTrue(p.moduleEnabled(prefs.all))
    }
    @Test fun applyingRoomSettingsPreservesViperAndUnrelatedValues() {
        val prefs = RoomReverbController.preferences(app); prefs.edit().clear().putBoolean(p.ENABLED, false).putString("sentinel", "keep").commit()
        val viper = ReverbController.preferences(app); viper.edit().clear().putBoolean(ReverbParameters.ENABLED, true).putInt(ReverbParameters.size.key, 713).commit()
        val before = viper.all
        p.apply(prefs, p.defaults(4, true).copy(timeOn = true, rt60 = 237, toneOn = true, highCut = 8371))
        assertEquals(before, viper.all); assertFalse(p.moduleEnabled(prefs.all)); assertEquals("keep", prefs.getString("sentinel", null))
    }
    @Test fun malformedPresentKeysNormalizeWithoutCreatingAbsentValues() {
        val prefs = RoomReverbController.preferences(app)
        prefs.edit().clear().putString(p.BASE, "bad").putString(p.PROFESSIONAL, "true")
            .putFloat(p.rt60.key, 2.37f).putInt(p.lowCut.key, -1).putBoolean(p.ENABLED, true).commit()
        p.normalize(prefs); assertEquals(15, p.base(prefs.all)); assertFalse(p.enabled(prefs.all))
        assertEquals(180, p.value(prefs.all, p.rt60)); assertEquals(0, p.value(prefs.all, p.lowCut))
        assertFalse(prefs.contains(p.earlyScale.key)); assertTrue(p.moduleEnabled(prefs.all))
    }
    @Test fun runtimeRejectsTruncatedOddMalformedAndNonFiniteRepresentations() {
        val v = IntArray(32).apply { this[0] = 1; this[1] = 2; this[2] = 48000; this[21] = 1; this[22] = 15; this[23] = 16000; this[25] = 2000 }
        assertNotNull(p.decode(v)); assertNull(p.decode(v.copyOf(31))); v[1] = 3; assertNull(p.decode(v)); v[1] = 2
        v[5] = 7; v[16] = Int.MIN_VALUE; v[19] = -20000; assertNotNull(p.decode(v))
        v[28] = 1; assertNull(p.decode(v)); v[28] = 0; v[22] = 3; assertNull(p.decode(v)); v[22] = 15
        v[23] = 99; assertNull(p.decode(v)); v[23] = 16000; v[7] = 5; assertNull(p.decode(v))
    }
    @Test fun stabilityReportAcceptsBothProtectionStatesAndBoundsActualBassFeedback() {
        val v = IntArray(32).apply { this[0] = 1; this[1] = 2; this[2] = 48000; this[21] = 1; this[22] = 7; this[23] = 18000; this[25] = 2000 }
        assertFalse(p.decode(v)!!.stabilityProtected)
        v[26] = 1
        for (coefficient in listOf(0, 123456, 1000000)) {
            v[27] = coefficient
            val decoded = p.decode(v)!!; assertTrue(decoded.stabilityProtected); assertEquals(coefficient, decoded.bassFeedbackMillionths)
        }
        v[27] = -1; assertNull(p.decode(v)); v[27] = 1000001; assertNull(p.decode(v)); v[27] = 100000
        v[26] = 2; assertNull(p.decode(v)); v[26] = -1; assertNull(p.decode(v))
    }
}
