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
class PresetCrossfeedParametersTest {
    private val p=PresetCrossfeedParameters
    private val app:Application get()=RuntimeEnvironment.getApplication()
    @Test fun originalSixBasesAndNeutralParametersArePreserved(){
        assertFalse(p.enabled(emptyMap<String,Any>()));assertEquals(5,p.base(emptyMap<String,Any>()))
        for(mode in 0..5){val s=p.baseSettings(mode);assertTrue(s.valid());assertFalse(s.professional)
            assertEquals(s,p.capture(s.values()));assertArrayEquals(intArrayOf(1,0,mode,if(mode==1)650 else 700,if(mode==1)95 else 60,100,0,0,0),s.packet())}
    }
    @Test fun baseSelectionResetsFineValuesAndRetainsProfessionalAndModulePower(){
        val prefs=PresetCrossfeedController.preferences(app);prefs.edit().clear().putBoolean(p.ENABLED,false).putString("unrelated","keep").commit()
        p.apply(prefs,p.baseSettings(4).copy(professional=true,mix=43,delay=137,spatial=-31))
        p.selectBase(prefs,1);assertEquals(p.baseSettings(1).copy(professional=true),p.capture(prefs.all))
        assertFalse(prefs.getBoolean(p.ENABLED,true));assertEquals("keep",prefs.getString("unrelated",null))
    }
    @Test fun oldEnabledProfilesWithoutAModeKeepTheLegacyServiceFallback(){
        val old=mapOf(p.ENABLED to true)
        assertEquals(0,p.capture(old).base);assertFalse(p.capture(old).professional)
        assertEquals(5,p.capture(old+mapOf(p.BASE to "5")).base)
        assertEquals(5,p.base(emptyMap<String,Any>()))
    }
    @Test fun numericUnitsAndIndependentNamespacesRemainExact(){
        assertEquals(46,p.attenuation.parse("4.6"));assertNull(p.attenuation.parse("4.61"));assertNull(p.delay.parse("1.1"))
        assertEquals(-31,p.spatial.parse("-3.1"));assertEquals(137,p.delay.parse("137"))
        assertTrue(p.newKeys.intersect(CrossfeedParameters.newKeys+CrossfeedAdvancedParameters.keys).isEmpty())
        assertNotEquals(p.NAMESPACE,ViperParameters.PREFS)
    }
    @Test fun malformedValuesNormalizeWithoutSwitchingOnOrChangingOtherModules(){
        val prefs=PresetCrossfeedController.preferences(app);prefs.edit().clear().putString(p.BASE,"bad").putString(p.PROFESSIONAL,"true")
            .putInt(p.mix.key,1000).putFloat(p.delay.key,137.5f).putBoolean(p.ENABLED,true).commit()
        p.normalize(prefs);assertEquals(5,p.base(prefs.all));assertFalse(p.enabled(prefs.all));assertEquals(100,p.value(prefs.all,p.mix));assertEquals(0,p.value(prefs.all,p.delay))
        assertTrue(prefs.getBoolean(p.ENABLED,false))
    }
    @Test fun advancedDefaultsRoundTripAndBaseResetRemainIndependent(){
        val base=p.baseSettings(3);val neutral=base.advanced
        assertFalse(neutral.pathOn);assertTrue(neutral.linked);assertFalse(neutral.filterOn);assertFalse(neutral.centerOn)
        assertArrayEquals(intArrayOf(1,0,1,0,0,0,3000,1,0,0),neutral.packet())
        val custom=base.copy(professional=true,advanced=neutral.copy(pathOn=true,linked=false,leftToRight=-31,rightToLeft=27,filterOn=true,filterHz=1234,slope=2,centerOn=true,center=-17))
        assertEquals(custom,p.capture(custom.values()));assertTrue(custom.valid());assertFalse(custom.copy(advanced=neutral.copy(filterHz=20001)).valid())
        assertEquals(-31,p.leftToRight.parse("-3.1"));assertNull(p.center.parse("1.01"))
        val prefs=PresetCrossfeedController.preferences(app);prefs.edit().clear().putBoolean(p.ENABLED,false).commit();p.apply(prefs,custom)
        p.selectBase(prefs,1);assertEquals(p.baseSettings(1).copy(professional=true),p.capture(prefs.all));assertFalse(prefs.getBoolean(p.ENABLED,true))
    }
    @Test fun runtimeRejectsMalformedAndStaleEpochPackets(){
        val v=IntArray(24);v[0]=1;v[1]=2;v[2]=48000;v[3]=1;v[4]=1;v[12]=5;v[23]=1
        assertNotNull(p.decode(v));assertNull(p.decode(v.copyOf(23)));v[1]=3;assertNull(p.decode(v));v[1]=2;v[12]=6;assertNull(p.decode(v))
    }
}
