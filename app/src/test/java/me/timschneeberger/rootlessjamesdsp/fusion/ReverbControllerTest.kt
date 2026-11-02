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
class ReverbControllerTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=ReverbParameters
    private val c=ReverbController
    private val prefs get()=c.preferences(app)
    private val identity=Any();private val owner=Any();private val caps=4194303
    @Before fun prepare(){
        c.stopAudition(app,clearSnapshots=true);c.publishRuntime(app,emptyList());prefs.edit().clear().commit()
        c.publishCapabilities(app,caps);p.apply(prefs,ReverbParameters.Settings(professional=true));prefs.edit().putBoolean(p.ENABLED,true).commit()
    }
    @After fun cleanup(){c.sessionClosed(app,identity);c.stopAudition(app,clearSnapshots=true)}
    private fun reading(frames:Int,mode:Int=c.audition,measurement:Int=0,epoch:Int=c.epoch,ready:Boolean=true):ReverbController.Reading {
        val v=IntArray(32);v[0]=1;v[1]=2;v[2]=48000;v[3]=1;v[4]=1;v[5]=mode;v[7]=measurement;v[11]=epoch
        v[26]=2000;v[30]=if(ready)1 else 0
        return ReverbController.Reading(identity,true,frames,48000,p.decode(v))
    }
    private fun active(){c.publishRuntime(app,listOf(reading(0)));c.publishRuntime(app,listOf(reading(512)))}
    @Test fun viperIntegrationSendsLegacyBeforeProfessionalEnableAndTools(){
        val packets=mutableListOf<Pair<Int,IntArray>>()
        assertTrue(ViperController.sync(app,caps,identity){id,v->packets+=id to v;true})
        val ids=packets.map {it.first};val first=ids.indexOf(65560)
        assertEquals(listOf(65560,65561,65562,65563,65564,65645,65559,65646),ids.subList(first,first+8))
        assertEquals(1,ids.count {it==65559});assertEquals(18,packets.first {it.first==65645}.second.size)
        assertEquals(39,packets.first {it.first==65646}.second.size)
    }
    @Test fun oldDriverReceivesOriginalFiveAndPowerButNoProfessionalCommands(){
        val ids=mutableListOf<Int>();assertTrue(ViperController.sync(app,2097151,identity){id,_->ids+=id;true})
        assertFalse(ids.contains(65645));assertFalse(ids.contains(65646));assertEquals(1,ids.count {it==65559})
        for(id in 65560..65564)assertEquals(1,ids.count {it==id})
        assertTrue(p.enabled(prefs.all))
    }
    @Test fun readinessAndActualProfessionalStateGateAuditionWithoutEnablingPower(){
        assertFalse(c.startAudition(app,owner,1));active();assertTrue(c.startAudition(app,owner,1))
        c.publishRuntime(app,listOf(reading(1024,ready=false)));assertEquals(0,c.audition);assertFalse(c.canListen(app))
        val next=reading(1536);val data=next.snapshot!!.data.toIntArray();data[4]=0
        c.publishRuntime(app,listOf(next.copy(snapshot=p.decode(data))));assertFalse(c.canListen(app))
        prefs.edit().putBoolean(p.ENABLED,false).commit();c.sync(app,caps,identity){_,_->true};assertEquals(0,c.audition)
        assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun savedLegacySnapshotUsesLegacySoundRatherThanIndependentSavedFineValues(){
        p.apply(prefs,ReverbParameters.Settings(legacy=listOf(11,22,33,44,55),professional=false,size=997,width=998,damp=999,wet=996,dry=995))
        assertTrue(c.saveComparison(app,owner,0));p.setProfessional(prefs,true);assertTrue(c.saveComparison(app,owner,1))
        val packets=mutableMapOf<Int,IntArray>();assertTrue(c.sync(app,caps,identity){id,v->packets[id]=v;true})
        val tools=packets[p.TOOLS]!!
        assertArrayEquals(intArrayOf(1,0,110,220,330,440,550),tools.copyOfRange(3,10))
        assertArrayEquals(intArrayOf(1,1,997,998,999,996,995),tools.copyOfRange(21,28))
    }
    @Test fun comparisonNeedsWarmThenMeasureReadyAndNeverPersistsTemporaryState(){
        active();assertTrue(c.saveComparison(app,owner,0));prefs.edit().putInt(p.size.key,713).commit();assertTrue(c.saveComparison(app,owner,1))
        assertTrue(c.measure(app,owner));assertFalse(c.setMatch(app,true))
        c.publishRuntime(app,listOf(reading(1024,6,1)));assertFalse(c.matchingReady())
        c.publishRuntime(app,listOf(reading(1536,6,2)));assertFalse(c.setMatch(app,true))
        c.publishRuntime(app,listOf(reading(2048,6,3)));assertTrue(c.setMatch(app,true));assertTrue(c.startAudition(app,owner,4))
        assertFalse(prefs.all.keys.any {it.contains("audition") || it.contains("snapshot") || it.contains("match")})
    }
    @Test fun silenceCannotEstablishMatchingAndSnapshotEditsInvalidateOldMeasurement(){
        active();c.saveComparison(app,owner,0);c.saveComparison(app,owner,1);assertTrue(c.measure(app,owner))
        c.publishRuntime(app,listOf(reading(1024,6,4)));assertFalse(c.setMatch(app,true))
        c.publishRuntime(app,listOf(reading(1536,6,3)));assertTrue(c.setMatch(app,true))
        c.saveComparison(app,owner,1);assertFalse(c.matchingReady());assertFalse(c.match)
    }
    @Test fun choosingMeasurementFromAuditionRestartsTheEpochAndClearsPreviousMatch(){
        active();c.saveComparison(app,owner,0);c.saveComparison(app,owner,1);assertTrue(c.measure(app,owner))
        c.publishRuntime(app,listOf(reading(1024,6,3)));assertTrue(c.setMatch(app,true));val before=c.epoch
        assertTrue(c.startAudition(app,owner,6));assertFalse(c.match);assertTrue(c.epoch!=before);assertFalse(c.matchingReady())
    }
    @Test fun stalePollingCannotRearmAClosedSession(){
        active();assertTrue(c.startAudition(app,owner,3));val poll=c.pollState();c.sessionClosed(app,identity)
        c.publishRuntime(app,listOf(reading(1024,3)),poll);assertEquals(0,c.audition);assertFalse(c.canListen(app))
    }
    @Test fun otherAudioEditsAndSendFailuresStopAuditionCapabilityLossClearsSnapshots(){
        active();c.sync(app,caps,identity){_,_->true};c.saveComparison(app,owner,0);assertTrue(c.startAudition(app,owner,1))
        prefs.edit().putInt("v_field_widen",37).commit();c.sync(app,caps,identity){_,_->true};assertEquals(0,c.audition)
        assertTrue(c.startAudition(app,owner,3));assertFalse(c.sync(app,caps,identity){id,_->id!=p.TOOLS});assertEquals(0,c.audition)
        c.publishCapabilities(app,2097151);assertFalse(c.hasComparison(0));assertFalse(c.supported(app))
    }
}
