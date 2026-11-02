package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ConvolutionAdvancedParametersTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=ConvolutionController.preferences(app)
    private val p=ConvolutionAdvancedParameters
    @Before fun prepare(){prefs.edit().clear().commit();ConvolutionController.stopAudition(app);ConvolutionController.publishRuntime(app,emptyList());ConvolutionController.publishPrepared(app,null);ConvolutionController.publishCapabilities(app,16383)}
    @After fun cleanup(){ConvolutionController.stopAudition(app);ConvolutionController.publishRuntime(app,emptyList());ConvolutionController.publishPrepared(app,null)}
    @Test fun defaultPacketAndEveryNumericBoundUseExactStoredIntegers() {
        assertArrayEquals(intArrayOf(1,0,60,0,0,12000,0,0,150,0,0,6000,0,0,50000,1000,50000,0,0),p.packet(emptyMap<String,Any>()))
        for(c in p.all){assertEquals(c.min,c.parse(c.number(c.min)));assertEquals(c.max,c.parse(c.number(c.max)));assertNull(c.parse("NaN"));assertNull(c.parse("Infinity"))}
        val packet=p.packet(mapOf(p.enabledFilter(0) to true,p.hp.key to 321,p.lowGain.key to -37),16,37)
        assertEquals(1,packet[1]);assertEquals(321,packet[2]);assertEquals(-37,packet[9]);assertEquals(37,packet[17]);assertEquals(16,packet[18])
    }
    @Test fun migrationPreservesLegacyFieldsAndDoesNotEnableOrOverwriteUserIr() {
        val old="-73;-91;7;3;0;1";prefs.edit().putString(ConvolutionParameters.LEGACY_ADVANCED,old).putString(ConvolutionParameters.FILE,"Convolver/user.irs").putInt(p.hp.key,Int.MAX_VALUE).putString(p.MATRIX,"bad").commit()
        ConvolutionParameters.normalize(prefs)
        assertEquals(old,prefs.getString(ConvolutionParameters.LEGACY_ADVANCED,null));assertEquals("Convolver/user.irs",prefs.getString(ConvolutionParameters.FILE,null))
        assertEquals(p.hp.default,prefs.getInt(p.hp.key,-1));assertFalse(prefs.getBoolean(p.MATRIX,true));assertFalse(prefs.contains(ConvolutionParameters.ENABLED))
    }
    @Test fun capabilityGateSendsNoSecondRoundPacketToFirstRoundDriver() {
        val sent=mutableListOf<Int>();ConvolutionController.sync(app,8191,Any()){id,_->sent+=id;true}
        assertFalse(sent.contains(1402));sent.clear();ConvolutionController.sync(app,16383,Any()){id,_->sent+=id;true};assertTrue(sent.contains(1402))
    }
    @Test fun comparisonCompensationIsTemporaryOwnedAndClearsWithModuleSession() {
        val id=Any();val owner=Any();prefs.edit().putBoolean(ConvolutionParameters.ENABLED,true).commit()
        ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading(id,true,48000,0,ConvolutionRuntimeReader.decode(ConvolutionParametersTest.bulk()))))
        ConvolutionController.setComparison(app,owner,47);assertEquals(47,ConvolutionController.comparisonTenths)
        val packets=mutableMapOf<Int,IntArray>();ConvolutionController.sync(app,16383,id,16){key,v->packets[key]=v;true}
        assertEquals(47,packets[1402]!![17]);assertEquals(16,packets[1402]!![18]);assertFalse(prefs.contains(p.comparison.key))
        ConvolutionController.stopAudition(app,Any());assertEquals(47,ConvolutionController.comparisonTenths)
        ConvolutionController.sessionClosed(app,id);assertEquals(0,ConvolutionController.comparisonTenths)
    }
    @Test fun advancedSnapshotRejectsTornInvalidOrMissingFieldsAndMatchesCommonLatency() {
        val values=IntArray(20).apply{this[0]=1;this[1]=2;this[2]=44100;this[3]=2;this[4]=2;this[5]=2;this[7]=100;this[8]=4410;this[9]=4096;this[14]=16;this[15]=-37;this[18]=1234;this[19]=5000}
        val decoded=ConvolutionAdvancedRuntime.decode(values)!!;assertEquals(16,decoded.commonDelay);assertEquals(-37,decoded.comparison);assertEquals(1234,decoded.crc)
        assertNull(ConvolutionAdvancedRuntime.decode(values.copyOf(19)))
        for((i,v) in listOf(0 to 2,1 to 3,2 to 0,3 to 3,4 to 3,5 to 4,6 to 3,7 to -1,10 to 30000,14 to 17,15 to 121))assertNull(ConvolutionAdvancedRuntime.decode(values.copyOf().apply{this[i]=v}))
        assertEquals(16,ConvolutionRuntimeReader.decode(ConvolutionParametersTest.bulk().apply{this[9]=16})!!.algorithmSamples)
    }
}
