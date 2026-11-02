package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class)
class CompanderParametersTest {
    private val p=CompanderParameters
    private val app: Application get()=RuntimeEnvironment.getApplication()
    @Test fun defaultGroupsOffAndMissingValuesDoNotInitializeOrChangeTheFoundation() {
        val prefs=CompanderController.preferences(app);prefs.edit().clear().putFloat(p.BASE_TIME,.17317f).putFloat(p.BASE_GRANULARITY,1.7317f).putString(p.BASE_TRANSFORM,"2").putString(p.BASE_CURVE,p.DEFAULT_CURVE+";").commit()
        val before=prefs.all;p.normalize(prefs);assertEquals(before,prefs.all)
        val s=p.capture(prefs.all);assertEquals(.17317f,s.legacyTime);assertEquals(1.7317f,s.legacyGranularity);assertEquals(p.DEFAULT_CURVE+";",s.legacyCurve)
        assertTrue(p.switches.none {p.on(s.values(),it)});assertEquals(0,s.engine);assertEquals(36,p.numbers.size)
        assertFalse(p.keys.contains(p.ENABLED));assertEquals(72,s.packet().size);assertEquals(1,s.packet()[4]);assertEquals(.17317f.toBits(),s.packet()[3])
    }
    @Test fun exactLegacyTextAndTypesSurviveAllSettingsRoundTrip() {
        val raw="95.12345;200;400;800;1600;3400;7500;0.123456;0;0;-1.2;0;1.2;0;"
        val s=p.defaults().copy(legacyCurve=raw,professional=true,engine=1,coreOn=true,threshold=-237,ratio=431,lookaheadOn=true,lookahead=137,freqDeltas=listOf(1,2,3,4,5,6,7),coefficientDeltas=listOf(7,6,5,4,3,2,1))
        assertTrue(s.valid());assertEquals(s,p.capture(s.values()));assertEquals(raw,s.values()[p.BASE_CURVE])
        val v=s.packet();assertEquals(1,v[1]);assertEquals(1,v[2]);assertEquals(95.12345f.toBits(),v[6]);assertEquals(.123456f.toBits(),v[13]);assertEquals(431,v[57]);assertEquals(137,v[65]);assertEquals(0,v[70]);assertEquals(0,v[71])
    }
    @Test fun strictCurveParserRejectsBadCountBlankNonFiniteOrderAndRange() {
        assertNotNull(p.parseCurve(p.DEFAULT_CURVE));assertNotNull(p.parseCurve(p.DEFAULT_CURVE+";"))
        for(raw in listOf(p.DEFAULT_CURVE+";;",p.DEFAULT_CURVE+";0",p.DEFAULT_CURVE.replaceFirst("95.0","NaN"),p.DEFAULT_CURVE.replaceFirst("95.0","Infinity"),p.DEFAULT_CURVE.replaceFirst("95.0","0"),p.DEFAULT_CURVE.replaceFirst("95.0","200"),p.DEFAULT_CURVE.replaceFirst("7500.0","24000"),p.DEFAULT_CURVE.replaceFirst(";0;0;0;0;0;0;0",";1.201;0;0;0;0;0;0"))) assertNull(raw,p.parseCurve(raw))
        assertNull(p.parseCurve(null));assertNull(p.parseCurve(";"));assertNull(p.parseCurve("0;".repeat(2048)))
    }
    @Test fun newValueNormalizationNeverRewritesInvalidOldFoundationOrPower() {
        val prefs=CompanderController.preferences(app);prefs.edit().clear().putString(p.BASE_CURVE,"malformed").putFloat(p.BASE_TIME,Float.NaN).putBoolean(p.ENABLED,true).putString(p.PROFESSIONAL,"true").putInt(p.ratio.key,99).putInt(p.sideHP.key,1).commit()
        p.normalize(prefs);assertEquals("malformed",prefs.getString(p.BASE_CURVE,null));assertTrue(prefs.getFloat(p.BASE_TIME,0f).isNaN());assertTrue(p.moduleEnabled(prefs.all))
        assertFalse(p.enabled(prefs.all));assertEquals(200,p.value(prefs.all,p.ratio));assertEquals(0,p.value(prefs.all,p.sideHP));assertFalse(prefs.contains(p.output.key))
    }
    @Test fun manualFrequencyAndRangeEditsRejectCrossingButNewFoundationRetainsCorrections() {
        val v=p.defaults().values();assertTrue(p.candidateValid(v,p.frequencies[0],104999));assertFalse(p.candidateValid(v,p.frequencies[0],105000));assertFalse(p.candidateValid(v,p.frequencies[0],-95000))
        assertTrue(p.defaults().copy(freqDeltas=List(7){20000000}).valid())
        val range=requireNotNull(p.frequencySliderRange(v,0));assertTrue(range.first>=-23750);assertTrue(range.last<=23750);assertTrue(0 in range)
        val moved=v+mapOf(p.frequencies[0].key to -7317);assertTrue(-7317 in requireNotNull(p.frequencySliderRange(moved,0)))
        assertFalse(p.candidateValid(v+mapOf(p.rangeLow.key to 500),p.rangeHigh,500));assertTrue(p.candidateValid(v+mapOf(p.rangeLow.key to 500),p.rangeHigh,0))
        assertFalse(p.candidateValid(v+mapOf(p.sideHP.key to 1000),p.sideLP,500));assertTrue(p.candidateValid(v,p.sideLP,500))
    }
    @Test fun exactMappingAndRelativeControlsOnlyOpenForEffectiveLegacyGroup() {
        val base=p.defaults().copy(professional=true,coreOn=true,timeOn=true,detectOn=true,limitsOn=true,mixOn=true,outputOn=true,sidechainOn=true,lookaheadOn=true)
        assertTrue(p.coreNumbers.all {p.numberEnabled(it.key,base.values())});assertTrue(p.numberEnabled(p.backgroundDelta.key,base.values()));assertTrue(p.numberEnabled(p.relative.key,base.values()))
        val standard=base.copy(engine=1);assertFalse(p.numberEnabled(p.amount.key,standard.values()));assertFalse(p.numberEnabled(p.backgroundDelta.key,standard.values()));assertFalse(p.numberEnabled(p.relative.key,standard.values()))
        assertFalse(p.numberEnabled(p.weakFloor.key,standard.values()));assertTrue(p.numberEnabled(p.weakFloor.key,standard.copy(type=1).values()));assertTrue(p.numberEnabled(p.threshold.key,standard.values()));assertTrue(p.numberEnabled(p.ratio.key,standard.values()));assertTrue(p.numberEnabled(p.rmsWindow.key,standard.values()))
        assertFalse(p.numberEnabled(p.maxBoost.key,standard.values()));assertTrue(p.numberEnabled(p.maxCut.key,standard.values()));assertTrue(p.numberEnabled(p.maxBoost.key,standard.copy(type=1).values()));assertFalse(p.numberEnabled(p.maxCut.key,standard.copy(type=1).values()));assertFalse(p.numberEnabled(p.ratio.key,standard.copy(type=3).values()));assertTrue(p.numberEnabled(p.hysteresis.key,standard.copy(type=3).values()));assertFalse(p.numberEnabled(p.rmsWindow.key,standard.copy(detector=0).values()))
        assertTrue(p.numbers.none {p.numberEnabled(it.key,standard.copy(professional=false).values())})
    }
    @Test fun takeoverDisablesOnlyOwnedLegacyFieldsAndProfessionalOffRestores() {
        val s=p.defaults().copy(professional=true);assertFalse(p.curveTakenOver(s.values()));assertFalse(p.timeTakenOver(s.values()));assertFalse(p.transformTakenOver(s.values()))
        assertTrue(p.curveTakenOver(s.copy(coreOn=true).values()));assertFalse(p.timeTakenOver(s.copy(coreOn=true).values()))
        assertTrue(p.timeTakenOver(s.copy(timeOn=true).values()));assertFalse(p.curveTakenOver(s.copy(timeOn=true).values()))
        val standard=s.copy(engine=1);assertTrue(p.curveTakenOver(standard.values()));assertTrue(p.timeTakenOver(standard.values()));assertTrue(p.transformTakenOver(standard.values()))
        assertFalse(p.transformTakenOver(standard.copy(professional=false).values()))
    }
    @Test fun everyNumericStepIsRepresentableAndSingleResetMeansCurrentFoundation() {
        for(c in p.numbers) for(n in listOf(c.min,c.default,c.max)) {assertTrue(c.key,p.validValue(c,n));assertEquals(n,c.parse(c.number(n)))}
        assertTrue((p.frequencies+p.coefficients+listOf(p.backgroundDelta)).all {it.default==0});assertEquals(1000,p.amount.default)
        assertEquals(431,p.ratio.parse("4.31"));assertNull(p.ratio.parse("4.311"));assertEquals(-173,p.frequencies[0].parse("-0.173"))
        assertFalse(p.validValue(p.sideLP,499));assertFalse(p.validValue(p.rangeLow,19));assertTrue(p.validValue(p.sideLP,0))
    }
    @Test fun applyingAndOffOnRetainsAllValuesWithoutChangingSavedPower() {
        val prefs=CompanderController.preferences(app);prefs.edit().clear().putBoolean(p.ENABLED,false).putInt("sentinel",73).commit()
        val s=p.defaults().copy(professional=true,coreOn=true,coefficientDeltas=listOf(317,0,0,0,0,0,0),attack=137)
        p.apply(prefs,s);p.setProfessional(prefs,false);assertEquals(s.copy(professional=false),p.capture(prefs.all));p.setProfessional(prefs,true);assertEquals(s,p.capture(prefs.all));assertFalse(p.moduleEnabled(prefs.all));assertEquals(73,prefs.getInt("sentinel",0))
    }
    private fun runtime()=IntArray(80).apply {this[0]=1;this[1]=2;this[2]=48000;this[26]=96000;this[31]=2000;this[39]=-800;this[45]=200;this[48]=20}
    @Test fun runtimeAcceptsSafetyGainAndRejectsBadLengthEpochEnumsAndReservedWords() {
        val v=runtime();v[27]=6000;v[28]=12000;v[2]=44100;v[64]=23000;v[65]=24000;assertNotNull(p.decode(v));assertNull(p.decode(v.copyOf(79)))
        for((i,bad) in listOf(0 to 2,1 to 3,2 to 7999,5 to 7,13 to 2,45 to 99,48 to 0,63 to 1,79 to 1)) {val copy=v.copyOf();copy[i]=bad;assertNull("$i",p.decode(copy))}
    }
    @Test fun responseValidatesRealHertzGainUnitsAndStateWithoutPretendingAFilterTransfer() {
        val v=IntArray(200).apply {this[0]=1;this[1]=2;this[2]=48000;this[4]=1;this[5]=32;for(i in 0 until 32){this[8+i*6]=20+i*500;this[9+i*6]=-12000;this[10+i*6]=-317;this[11+i*6]=Int.MIN_VALUE;this[12+i*6]=-8000;this[13+i*6]=1}}
        assertNotNull(p.decodeResponse(v));assertNull(p.decodeResponse(v.copyOf(199)))
        for((i,bad) in listOf(1 to 3,3 to 2,5 to 31,7 to 1,8 to 24001,9 to -20001,10 to 2401,13 to 2,14 to 20)) {val copy=v.copyOf();copy[i]=bad;assertNull("$i",p.decodeResponse(copy))}
    }
}
