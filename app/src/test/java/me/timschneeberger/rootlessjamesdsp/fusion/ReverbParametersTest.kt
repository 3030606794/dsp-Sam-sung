package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ReverbParametersTest {
    private val p=ReverbParameters
    private val app:Application get()=RuntimeEnvironment.getApplication()
    @Test fun originalDefaultsAndEveryOptionalFeatureRemainOff(){
        val s=p.capture(emptyMap<String,Any>())
        assertEquals(ReverbParameters.Settings(),s);assertTrue(s.valid());assertFalse(s.professional)
        assertFalse(s.predelayOn || s.lowCutOn || s.highCutOn || s.linked || s.outputOn || s.meters)
        assertArrayEquals(intArrayOf(1,0,0,0,0,0,500,0,0,0,80,0,12000,0,0,0,0,0),s.packet())
        assertFalse(s.values().containsKey(p.ENABLED));assertFalse(p.newKeys.contains(p.ENABLED))
    }
    @Test fun firstActivationCopiesAllFiveLegacyValuesAndLaterActivationsRetainFineValues(){
        val prefs=ReverbController.preferences(app);prefs.edit().clear().commit()
        val values=listOf(61,82,23,19,47)
        val edit=prefs.edit();p.legacyControls.zip(values).forEach {(c,v)->edit.putInt(c.key,v)};edit.commit()
        val before=prefs.all;p.capture(before);p.normalize(prefs);assertEquals(before,prefs.all)
        p.setProfessional(prefs,true)
        p.core.zip(values).forEach {(c,v)->assertEquals(v*10,p.value(prefs.all,c))}
        prefs.edit().putInt(p.size.key,617).commit();p.setProfessional(prefs,false)
        prefs.edit().putInt(p.legacyControls[0].key,30).commit();p.setProfessional(prefs,true)
        assertEquals(617,p.value(prefs.all,p.size));assertEquals(30,p.legacy(prefs.all)[0]);assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun decimalUnitsRangesAndSettingsRoundTripAreExact(){
        assertEquals(137,p.size.parse("13.7"));assertNull(p.size.parse("13.71"))
        assertEquals(137,p.predelay.parse("13.7"));assertNull(p.lowCut.parse("80.1"));assertEquals(-31,p.output.parse("-3.1"))
        val s=ReverbParameters.Settings(listOf(3,14,28,39,57),true,321,654,123,98,456,true,137,true,173,true,9123,true,267,true,-31,true)
        assertTrue(s.valid());assertEquals(s,p.capture(s.values()));assertEquals(18,s.packet().size)
        assertFalse(s.copy(lowCut=1001).valid());assertFalse(s.copy(highCut=999).valid())
    }
    @Test fun professionalRoomSizeIsSavedExactlyAndNeverUsesTheMapEntryCount(){
        val settings=ReverbParameters.Settings(professional=true,size=317)
        assertEquals(317,settings.values()[p.size.key])
        val prefs=ReverbController.preferences(app);prefs.edit().clear().commit();p.apply(prefs,settings)
        assertEquals(317,prefs.getInt(p.size.key,-1));assertEquals(317,p.capture(prefs.all).size)
    }
    @Test fun malformedPresentKeysNormalizeWithoutInitializingAbsentOrTouchingOtherEffects(){
        val prefs=ReverbController.preferences(app);prefs.edit().clear().putString(p.PROFESSIONAL,"true")
            .putInt(p.lowCut.key,-1).putFloat(p.predelay.key,1.7f).putInt("v_reverb_dry",47)
            .putBoolean("v_cure",true).putBoolean(p.ENABLED,true).commit()
        p.normalize(prefs);assertFalse(p.enabled(prefs.all));assertEquals(80,p.value(prefs.all,p.lowCut))
        assertEquals(0,p.value(prefs.all,p.predelay));assertFalse(prefs.contains(p.size.key))
        assertTrue(prefs.getBoolean("v_cure",false));assertTrue(p.moduleEnabled(prefs.all))
    }
    @Test fun applyingIndependentPresetsPreservesModulePowerRoomReverbAndOtherViperValues(){
        val prefs=ReverbController.preferences(app);prefs.edit().clear().putBoolean(p.ENABLED,false).putInt("v_field_widen",37).commit()
        val room=me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache.getPreferences(app,
            me.timschneeberger.rootlessjamesdsp.utils.Constants.PREF_REVERB)
        room.edit().clear().putBoolean("reverb_enable",true).putString("sentinel","keep").commit();val before=room.all
        p.apply(prefs,ReverbParameters.Settings(professional=true,size=731,predelayOn=true,predelay=237))
        assertFalse(p.moduleEnabled(prefs.all));assertEquals(37,prefs.getInt("v_field_widen",0));assertEquals(before,room.all)
    }
    @Test fun runtimeRejectsTruncationOddPublicationAndInvalidMeterState(){
        val v=IntArray(32);v[0]=1;v[1]=2;v[2]=48000;v[3]=1;v[4]=1;v[26]=2000;v[30]=1
        assertNotNull(p.decode(v));assertNull(p.decode(v.copyOf(31)));v[1]=3;assertNull(p.decode(v));v[1]=2
        v[7]=5;assertNull(p.decode(v));v[7]=1;v[25]=72000;v[9]=-18000;assertNotNull(p.decode(v))
        v[23]=2;assertNull(p.decode(v));v[23]=1;v[16]=Int.MIN_VALUE;assertNotNull(p.decode(v))
    }
}
