package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HighFrequencyParametersTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = HighFrequencyController.preferences(app)
    private val p = HighFrequencyParameters
    @Before fun clear() {
        preferences.edit().clear().commit()
        HighFrequencyController.stopAudition(app)
        HighFrequencyController.publishRuntime(app, emptyList())
        HighFrequencyController.publishCapabilities(app, 127)
    }
    @Test fun defaultsAndFineStepsMatchBothNativePacketLayouts() {
        assertArrayEquals(intArrayOf(0,8250,100,120,1200,0,0,0,0,5000,2500), p.clarityPacket(emptyMap<String, Any>()))
        assertArrayEquals(intArrayOf(560,7600,717,0,717,0,0), p.spectrumPacket(emptyMap<String, Any>()))
        assertEquals(173, p.clarityAmount.parse("1.73"))
        assertEquals(561, p.spectrumAmount.parse("56.1"))
        assertEquals(718, p.highpassQ.parse("0.718"))
        assertEquals(-119, p.spectrumOutput.parse("-11.9"))
        listOf("NaN","Infinity","1.731","-0.01","4.51","").forEach { assertNull(p.clarityAmount.parse(it)) }
        assertNull(p.spectrumAmount.parse("56.11"))
    }
    @Test fun repeatedOldPresetImportPreservesEveryOldAmountWithoutReinterpretingItsKeys() {
        for (old in 0..9) {
            preferences.edit().clear().putInt("v_clarity_amount", old).putInt("v_spectrum_amount", old * 56).commit()
            p.migrate(preferences)
            assertEquals(old * 50, preferences.getInt(p.CLARITY_AMOUNT, -1))
            assertEquals(old * 560, preferences.getInt(p.SPECTRUM_AMOUNT, -1))
            assertEquals(old, preferences.getInt("v_clarity_amount", -1))
            assertEquals(old * 56, preferences.getInt("v_spectrum_amount", -1))
        }
        preferences.edit().putInt(p.CLARITY_AMOUNT, 173).commit()
        p.migrate(preferences)
        assertEquals(173, preferences.getInt(p.CLARITY_AMOUNT, -1))
        preferences.edit().clear().putInt("v_clarity_amount", 2).commit()
        p.migrate(preferences)
        assertEquals(100, preferences.getInt(p.CLARITY_AMOUNT, -1))
        assertEquals(560, p.spectrumPacket(preferences.all)[0])
    }
    @Test fun malformedImportedTypesBecomeSafeIntegersBeforePreferenceBinding() {
        preferences.edit().putFloat(p.CLARITY_AMOUNT, Float.NaN).putFloat(p.shelfSlope.key, 53.9f)
            .putString(p.highpassQ.key, "717").putLong(p.lowDelay.key, 5000L)
            .putInt(p.highFrequency.key, Int.MAX_VALUE).putString(p.LP_AUTO, "false").commit()
        p.migrate(preferences)
        assertEquals(0, preferences.getInt(p.CLARITY_AMOUNT, -1))
        assertEquals(100, preferences.getInt(p.shelfSlope.key, -1))
        assertEquals(717, preferences.getInt(p.highpassQ.key, -1))
        assertEquals(5000, preferences.getInt(p.lowDelay.key, -1))
        assertEquals(5000, preferences.getInt(p.highFrequency.key, -1))
        assertTrue(preferences.getBoolean(p.LP_AUTO, false))
    }
    @Test fun modernPacketsPrecedeIndependentSwitchesAndLegacyWritesCannotOverrideThem() {
        preferences.edit().putInt(p.CLARITY_AMOUNT, 173).putInt(p.SPECTRUM_AMOUNT, 437).commit()
        val modern = mutableListOf<Pair<Int, IntArray>>()
        assertTrue(ViperController.sync(app, 127) { id, data -> modern += id to data; true })
        assertEquals(173, modern.single { it.first == 65600 }.second[0])
        assertEquals(437, modern.single { it.first == 65601 }.second[0])
        assertTrue(modern.none { it.first in setOf(65580,65549,65550) })
        assertTrue(modern.indexOfFirst { it.first == 65600 } < modern.indexOfFirst { it.first == 65578 })
        assertTrue(modern.indexOfFirst { it.first == 65601 } < modern.indexOfFirst { it.first == 65548 })
        val legacy = mutableListOf<Pair<Int, IntArray>>()
        assertTrue(ViperController.sync(app, 63) { id, data -> legacy += id to data; true })
        assertTrue(legacy.none { it.first in 65600..65601 })
        assertEquals(173, legacy.single { it.first == 65580 }.second[0])
        assertEquals(44, legacy.single { it.first == 65550 }.second[0])
        assertEquals(437, preferences.getInt(p.SPECTRUM_AMOUNT, -1))
    }
    @Test fun sampleEditsRoundTripExactlyWhileOriginalDelayDefaultsRemainRateDependent() {
        listOf(8000,11025,22050,44100,48000,88200,96000,192000).forEach { rate ->
            for (sample in 0..p.delaySamples(10000, rate)) {
                val us = p.delayMicroseconds(sample, rate)
                assertTrue(us <= 10000)
                assertEquals(sample, p.delaySamples(us, rate))
            }
        }
        assertEquals(220, p.delaySamples(5000,44100))
        assertEquals(240, p.delaySamples(5000,48000))
        assertEquals(110, p.delaySamples(2500,44100))
        assertEquals(120, p.delaySamples(2500,48000))
    }
    @Test fun presetsRequireTheNewAppOnlyWhenProfessionalKeysArePresent() {
        preferences.edit().putInt("v_clarity_amount", 4).commit()
        assertFalse(p.requiresNewApp(preferences.all))
        p.migrate(preferences)
        assertEquals("1028", Preset.minimumVersionCode(app))
        preferences.edit().clear().putBoolean(p.LP_AUTO, true).commit()
        assertEquals("1028", Preset.minimumVersionCode(app))
    }
    private fun reading(audition: Int, rate: Int = 48000, enabled: Boolean = true) =
        HighFrequencyController.Reading("stream", enabled, rate, listOf(8250,120,1200,240,120,7600,22000), audition)
    @Test fun auditionNeverEnablesAnEffectAndIsClearedAfterDriverResetOrHeadlessDisable() {
        val owner = Any()
        assertFalse(HighFrequencyController.startAudition(app, owner))
        HighFrequencyController.publishRuntime(app, listOf(reading(0)))
        assertFalse(HighFrequencyController.startAudition(app, owner))
        preferences.edit().putBoolean("v_spectrum", true).commit()
        val oldRevision = HighFrequencyController.auditionRevision
        assertTrue(HighFrequencyController.startAudition(app, owner))
        HighFrequencyController.publishRuntime(app, listOf(reading(0)), oldRevision)
        assertTrue(HighFrequencyController.audition)
        HighFrequencyController.publishRuntime(app, listOf(reading(1), reading(0,enabled = false).copy(identity = "disabled")))
        assertTrue("Disabled sessions do not cancel the active stream's audition",HighFrequencyController.audition)
        assertTrue(preferences.all.keys.none { it.contains("audition") })
        HighFrequencyController.publishRuntime(app, listOf(reading(0)))
        assertFalse(HighFrequencyController.audition)
        assertTrue(HighFrequencyController.startAudition(app, owner))
        preferences.edit().putBoolean("v_spectrum", false).commit()
        HighFrequencyController.sync(app, 127) { id, values ->
            if (id == 65601) assertEquals(0, values[6]); true
        }
        assertFalse(HighFrequencyController.audition)
        assertFalse(preferences.getBoolean("v_spectrum", true))
    }
    @Test fun disablingOneSessionImmediatelyBlocksStalePollsWithoutDisablingAnotherActiveSession() {
        val first = Any()
        val other = Any()
        val owner = Any()
        val firstReading = reading(1).copy(identity = first)
        val otherReading = reading(1).copy(identity = other)
        preferences.edit().putBoolean("v_spectrum",true).commit()
        try {
            HighFrequencyController.publishRuntime(app,listOf(firstReading))
            assertTrue(HighFrequencyController.startAudition(app,owner))
            val beforeOff = HighFrequencyController.runtimeRevision
            HighFrequencyController.sessionEnabled(app,first,false)
            assertFalse(HighFrequencyController.audition)
            assertFalse("No one-second window after master off",HighFrequencyController.startAudition(app,owner))
            HighFrequencyController.publishRuntime(app,listOf(firstReading),HighFrequencyController.auditionRevision,beforeOff)
            assertFalse(HighFrequencyController.masterEnabled)
            HighFrequencyController.publishRuntime(app,listOf(firstReading))
            assertFalse("A disabled identity overrides stale native enabled flags",HighFrequencyController.masterEnabled)
            val beforeOn = HighFrequencyController.runtimeRevision
            HighFrequencyController.sessionEnabled(app,first,true)
            assertFalse(HighFrequencyController.startAudition(app,owner))
            HighFrequencyController.publishRuntime(app,listOf(firstReading),HighFrequencyController.auditionRevision,beforeOn)
            assertFalse(HighFrequencyController.startAudition(app,owner))
            HighFrequencyController.publishRuntime(app,listOf(firstReading,otherReading))
            assertTrue(HighFrequencyController.startAudition(app,owner))
            HighFrequencyController.sessionEnabled(app,first,false)
            assertTrue("The other active stream remains available",HighFrequencyController.masterEnabled)
            assertTrue(HighFrequencyController.audition)
            HighFrequencyController.publishRuntime(app,listOf(firstReading.copy(audition=0),otherReading))
            assertTrue(HighFrequencyController.audition)
            HighFrequencyController.sessionClosed(app,first)
            assertTrue(HighFrequencyController.audition)
            val beforeClose = HighFrequencyController.runtimeRevision
            HighFrequencyController.sessionClosed(app,other)
            HighFrequencyController.publishRuntime(app,listOf(otherReading),HighFrequencyController.auditionRevision,beforeClose)
            assertTrue(HighFrequencyController.runtime.isEmpty())
            assertFalse(HighFrequencyController.audition)
        } finally {
            HighFrequencyController.sessionClosed(app,first)
            HighFrequencyController.sessionClosed(app,other)
        }
    }
}
