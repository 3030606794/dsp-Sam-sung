package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class)
class DynamicParametersTest {
    private val p=DynamicParameters
    private val app: Application get()=RuntimeEnvironment.getApplication()
    @Test fun allNewGroupsStartOffAndMissingPreferencesAreNotInitialized() {
        val prefs=DynamicController.preferences(app); prefs.edit().clear().putString(p.BASE,"4").putInt(p.LEGACY,71).commit()
        p.normalize(prefs); assertEquals(mapOf(p.BASE to "4",p.LEGACY to 71),prefs.all)
        val s=p.capture(prefs.all); assertEquals(p.defaults().copy(base=4,strength=71),s)
        assertTrue(p.switches.none { p.on(s.values(),it) }); assertEquals(20,p.numbers.size)
        assertFalse(p.newKeys.contains(p.BASE)); assertFalse(p.newKeys.contains(p.LEGACY)); assertFalse(p.keys.contains(p.ENABLED))
        assertEquals(36,s.packet().size); assertArrayEquals(intArrayOf(0,0,0,0),s.packet().copyOfRange(32,36))
    }
    @Test fun completeSettingsRoundTripAndPacketOrderAreExact() {
        val s=DynamicParameters.Settings(true,4,37,true,23,117,-137,317,43,true,-73,7,13,17,27,18017,true,0,73,317,-237,43,73,137,317,true,317,true,-29,true,true)
        assertTrue(s.valid()); assertEquals(s,p.capture(s.values()))
        assertArrayEquals(intArrayOf(1,1,4,37,1,23,117,-137,317,43,1,-73,7,13,17,27,18017,1,0,73,317,-237,43,73,137,317,1,317,1,-29,1,1,0,0,0,0),s.packet())
        assertFalse(s.copy(detector=2).valid()); assertFalse(s.copy(base=6).valid()); assertFalse(s.copy(strength=101).valid())
    }
    @Test fun signedDecimalPrecisionAndBypassGapsRejectUnrepresentableValues() {
        assertEquals(-23,p.bassDelta.parse("-2.3")); assertNull(p.bassDelta.parse("-2.31"))
        assertEquals(43,p.qDelta.parse("0.43")); assertNull(p.qDelta.parse("0.431")); assertNull(p.output.parse("0.1"))
        assertTrue(p.validValue(p.hp,0)); assertFalse(p.validValue(p.hp,9)); assertTrue(p.validValue(p.hp,10))
        assertTrue(p.validValue(p.lp,0)); assertFalse(p.validValue(p.lp,99)); assertTrue(p.validValue(p.lp,100))
    }
    @Test fun eachModeOnlyOpensEffectiveCoreAndFrequencyControls() {
        val v=p.defaults().copy(professional=true,coreOn=true,toneOn=true,adaptiveOn=true,mixOn=true,outputOn=true).values()
        for(base in 0..5) {
            val current=v+mapOf(p.BASE to base.toString()); val deep=base==4
            for(c in listOf(p.bassDelta,p.lowGainDelta,p.highGainDelta,p.xDelta,p.lowFreqDelta,p.highFreqDelta)) assertEquals(!deep,p.numberEnabled(c.key,current))
            for(c in listOf(p.injectDelta,p.qDelta,p.deepFreqDelta)) assertEquals(deep,p.numberEnabled(c.key,current))
            for(c in p.adaptiveNumbers+listOf(p.hp,p.lp,p.mix,p.output)) assertTrue(p.numberEnabled(c.key,current))
        }
        assertTrue(p.numbers.none { p.numberEnabled(it.key,v+mapOf(p.PROFESSIONAL to false)) })
    }
    @Test fun boundaryEditsRejectCrossingWithoutChangingAnotherNumberButPresetSwitchKeepsOffsets() {
        val s=p.defaults().copy(toneOn=true,lowFreqDelta=19); val v=s.values()
        assertTrue(p.candidateValid(v,p.lowFreqDelta,19)); assertFalse(p.candidateValid(v,p.lowFreqDelta,20))
        assertFalse(p.candidateValid(v,p.highFreqDelta,-1)); assertTrue(p.candidateValid(v,p.highFreqDelta,1))
        // Current base has 40 / 60; switching retains offset even when a different foundation crosses.
        val shifted=s.copy(lowFreqDelta=30,base=3); assertTrue(shifted.valid()); assertEquals(30,p.capture(shifted.values()).lowFreqDelta)
        assertFalse(p.candidateValid(v+mapOf(p.hp.key to 100),p.lp,100)); assertTrue(p.candidateValid(v+mapOf(p.hp.key to 100),p.lp,0))
        assertFalse(p.candidateValid(v,p.detectorHP,250)); assertTrue(p.candidateValid(v,p.detectorHP,249))
        assertEquals(19,v[p.lowFreqDelta.key]); assertEquals(0,v[p.highFreqDelta.key])
    }
    @Test fun invalidPresentValuesNormalizeOnlyNewKeysPreservingPowerAndOtherModules() {
        val prefs=DynamicController.preferences(app)
        prefs.edit().clear().putString(p.BASE,"4").putInt(p.LEGACY,71).putBoolean(p.ENABLED,false).putBoolean("v_tube",true)
            .putString(p.PROFESSIONAL,"true").putFloat(p.bassDelta.key,2.3f).putInt(p.hp.key,9).putInt(p.DETECTOR,3).commit()
        p.normalize(prefs); assertFalse(p.enabled(prefs.all)); assertEquals(0,p.value(prefs.all,p.bassDelta)); assertEquals(0,p.value(prefs.all,p.hp))
        assertEquals(1,p.detector(prefs.all)); assertFalse(prefs.contains(p.lp.key)); assertEquals("4",prefs.getString(p.BASE,null))
        assertEquals(71,prefs.getInt(p.LEGACY,0)); assertTrue(prefs.getBoolean("v_tube",false)); assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun singleControlDefaultsAreRelativeOffsetsAndRangesRoundTrip() {
        assertTrue((p.coreNumbers+p.toneNumbers.take(4)).all { it.default==0 })
        for(control in p.numbers) for(v in listOf(control.min,control.default,control.max)) {
            assertTrue(p.validValue(control,v)); assertEquals(v,p.value(mapOf(control.key to v),control)); assertEquals(v,control.parse(control.number(v)))
        }
        p.numbers.forEach { assertFalse(p.validValue(it,it.min-1)); assertFalse(p.validValue(it,it.max+1)) }
    }
    @Test fun applyPreservesPowerAndUnrelatedModulesAndOffOnRestoresFineValues() {
        val prefs=DynamicController.preferences(app); prefs.edit().clear().putBoolean(p.ENABLED,false).putBoolean("v_tube",true).putBoolean("v_analog",true).commit()
        val s=p.defaults().copy(professional=true,base=4,strength=37,coreOn=true,injectDelta=317,qDelta=43,toneOn=true,deepFreqDelta=17)
        p.apply(prefs,s); p.setProfessional(prefs,false); assertEquals(s.copy(professional=false),p.capture(prefs.all))
        p.setProfessional(prefs,true); assertEquals(s,p.capture(prefs.all)); assertFalse(p.moduleEnabled(prefs.all)); assertTrue(prefs.getBoolean("v_tube",false)); assertTrue(prefs.getBoolean("v_analog",false))
    }
    @Test fun runtimeStrictlyRejectsBadRangesAndAcceptsSafeLowRateAndLongWarmup() {
        val v=IntArray(48).apply { this[0]=1;this[1]=2;this[2]=8000;this[26]=192000;this[31]=2000;this[30]=6000;this[29]=5999;this[28]=Int.MIN_VALUE;this[45]=Int.MIN_VALUE }
        assertNotNull(p.decode(v)); assertNull(p.decode(v.copyOf(47)))
        v[47]=3;assertNotNull(p.decode(v))
        for((i,bad) in listOf(1 to 3,2 to 7999,5 to 9,13 to 6,14 to 101,27 to 1801,30 to 60001,31 to 1999,32 to 4001,42 to 99,43 to 4001,46 to 2,47 to 4)) {
            val n=v.copyOf(); n[i]=bad; assertNull("Reject runtime $i",p.decode(n))
        }
    }
    @Test fun responseStrictlyChecksHeaderActualRatesAndIncreasingFrequencies() {
        val v=IntArray(200).apply { this[0]=1;this[1]=2;this[2]=48000;this[5]=1;this[6]=32
            for(i in 0 until 32) { this[8+i*6]=20+i*500; this[9+i*6]=-i*100;this[10+i*6]=-137;this[11+i*6]=0;this[12+i*6]=-17;this[13+i*6]=-i*200 } }
        assertNotNull(p.decodeResponse(v)); assertNull(p.decodeResponse(v.copyOf(199)))
        for((i,bad) in listOf(1 to 3,2 to 7999,3 to 2,4 to 1801,5 to 2,6 to 31,7 to 1,8 to 24001,10 to -180001,14 to 20)) {
            val n=v.copyOf(); n[i]=bad; assertNull("Reject response $i",p.decodeResponse(n))
        }
    }
}
