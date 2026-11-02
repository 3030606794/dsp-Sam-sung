package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class PresetCrossfeedControllerTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=PresetCrossfeedParameters
    private val c=PresetCrossfeedController
    private val prefs get()=c.preferences(app)
    private val identity=Any()
    private val owner=Any()
    @Before fun prepare(){c.stopAudition(app,clearSnapshots=true);c.publishRuntime(app,emptyList());prefs.edit().clear().commit();c.publishCapabilities(app,2097151)
        p.apply(prefs,p.baseSettings(0).copy(professional=true));prefs.edit().putBoolean(p.ENABLED,true).commit()}
    @After fun cleanup(){c.sessionClosed(app,identity);c.stopAudition(app,clearSnapshots=true)}
    private fun reading(frames:Int,mode:Int=c.audition,measurement:Int=0,epoch:Int=c.epoch):PresetCrossfeedController.Reading{
        val v=IntArray(24);v[0]=1;v[1]=2;v[2]=48000;v[3]=1;v[4]=1;v[5]=mode;v[6]=measurement;v[10]=epoch;v[12]=p.base(prefs.all);v[23]=1
        return PresetCrossfeedController.Reading(identity,true,frames,48000,p.decode(v))
    }
    private fun active(){c.publishRuntime(app,listOf(reading(0)));c.publishRuntime(app,listOf(reading(512)))}
    @Test fun packetsCarryBaseAndFineParametersAndOldDriverReceivesNothingNew(){
        val packets=linkedMapOf<Int,IntArray>();assertTrue(c.sync(app,2097151,identity){id,v->packets[id]=v;true})
        assertEquals(setOf(1420,1421),packets.keys);assertEquals(9,packets[1420]!!.size);assertEquals(21,packets[1421]!!.size)
        packets.clear();assertTrue(c.sync(app,524287,identity){id,v->packets[id]=v;true});assertTrue(packets.isEmpty());assertTrue(p.enabled(prefs.all))
    }
    @Test fun auditionNeedsAnActiveSessionAndDoesNotEnablePower(){
        assertFalse(c.startAudition(app,owner,1));active();assertTrue(c.startAudition(app,owner,1));assertTrue(prefs.getBoolean(p.ENABLED,false))
        prefs.edit().putBoolean(p.ENABLED,false).commit();c.sync(app,2097151,identity){_,_->true};assertEquals(0,c.audition);assertFalse(prefs.getBoolean(p.ENABLED,true))
    }
    @Test fun auditionWaitsForTheActualProfessionalModelToBeReady(){
        val first=reading(0);val data=first.snapshot!!.data.toIntArray();data[4]=0
        c.publishRuntime(app,listOf(first.copy(snapshot=p.decode(data))));c.publishRuntime(app,listOf(reading(512).copy(snapshot=p.decode(data))))
        assertFalse(c.canListen(app));data[4]=1;data[23]=0;c.publishRuntime(app,listOf(reading(1024).copy(snapshot=p.decode(data))))
        assertFalse(c.canListen(app));data[23]=1;c.publishRuntime(app,listOf(reading(1536).copy(snapshot=p.decode(data))));assertTrue(c.canListen(app))
    }
    @Test fun crossPresetSnapshotsStayInMemoryAndMatchingRequiresMeasurement(){
        active();assertTrue(c.saveComparison(app,owner,0));p.apply(prefs,p.baseSettings(3).copy(professional=true,spatial=-31));assertTrue(c.saveComparison(app,owner,1))
        assertFalse(c.setMatch(app,true));assertTrue(c.measure(app,owner));val packets=linkedMapOf<Int,IntArray>();assertTrue(c.sync(app,2097151,identity){id,v->packets[id]=v;true})
        val v=packets[1421]!!;assertEquals(0,v[3+2]);assertEquals(3,v[12+2]);assertEquals(-31,v[12+8]);assertEquals(6,v[0])
        c.publishRuntime(app,listOf(reading(1024,6,2)));assertTrue(c.setMatch(app,true));assertTrue(c.startAudition(app,owner,4))
        assertFalse(prefs.all.keys.any {it.contains("audition") || it.contains("snapshot") || it.contains("match")})
    }
    @Test fun newBridgeCarriesIndependentAdvancedSnapshotsBeforeAudition(){
        val caps=p.CAPABILITY or p.ADVANCED_CAPABILITY;c.publishCapabilities(app,caps);active()
        val first=p.capture(prefs.all).copy(advanced=p.captureAdvanced(prefs.all).copy(pathOn=true,linked=false,leftToRight=-31,rightToLeft=27))
        p.apply(prefs,first);assertTrue(c.saveComparison(app,owner,0))
        val second=first.copy(advanced=first.advanced.copy(filterOn=true,filterHz=1234,slope=2,centerOn=true,center=-17))
        p.apply(prefs,second);assertTrue(c.saveComparison(app,owner,1));assertTrue(c.startAudition(app,owner,5))
        val packets=linkedMapOf<Int,IntArray>();assertTrue(c.sync(app,caps,identity){id,v->packets[id]=v;true})
        assertEquals(listOf(1420,1422,1423,1421),packets.keys.toList());assertArrayEquals(second.advanced.packet(),packets[1422])
        assertArrayEquals(first.advanced.packet(),packets[1423]!!.copyOfRange(1,11));assertArrayEquals(second.advanced.packet(),packets[1423]!!.copyOfRange(11,21))
        assertEquals(c.epoch,packets[1423]!![0]);assertEquals(5,packets[1421]!![0])
        c.publishCapabilities(app,p.CAPABILITY);assertFalse(c.hasComparison(0));assertFalse(c.hasComparison(1));assertEquals(0,c.audition)
        packets.clear();assertTrue(c.sync(app,p.CAPABILITY,identity){id,v->packets[id]=v;true});assertEquals(listOf(1420,1421),packets.keys.toList())
    }
    @Test fun staleRuntimeCannotRearmAndSessionClosureStopsAudition(){
        active();assertTrue(c.startAudition(app,owner,3));val poll=c.pollState();c.sessionClosed(app,identity)
        c.publishRuntime(app,listOf(reading(1024,3)),poll);assertEquals(0,c.audition);assertFalse(c.canListen(app))
    }
    @Test fun capabilityLossAndOtherAudioEditsCancelTemporaryState(){
        active();c.sync(app,2097151,identity){_,_->true};assertTrue(c.startAudition(app,owner,1))
        CrossfeedController.preferences(app).edit().putInt("v_field_widen",37).commit();c.sync(app,2097151,identity){_,_->true};assertEquals(0,c.audition)
        assertTrue(c.saveComparison(app,owner,0));c.publishCapabilities(app,524287);assertFalse(c.hasComparison(0));assertFalse(c.supported(app))
    }
}
