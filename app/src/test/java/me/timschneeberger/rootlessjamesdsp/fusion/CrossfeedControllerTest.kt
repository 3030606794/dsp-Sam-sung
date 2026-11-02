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
class CrossfeedControllerTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val c=CrossfeedController
    private val p=CrossfeedParameters
    private val owner=Any()
    private val stream=Any()
    private val prefs get()=c.preferences(app)
    @Before fun setup(){
        c.stopAudition(app,clearSnapshots=true);c.publishRuntime(app,emptyList())
        prefs.edit().clear().commit();c.publishCapabilities(app,524287)
        prefs.edit().putBoolean("v_cure",true).putBoolean(p.ENABLED,true).commit()
        c.sync(app,524287,stream){_,_->true};publish(0);publish(100)
    }
    @After fun cleanup(){c.stopAudition(app,clearSnapshots=true);c.sessionClosed(app,stream)}
    private fun snapshot(mode:Int=c.audition,measurement:Int=0,epoch:Int=c.epoch)=p.decode(intArrayOf(1,2,48000,1,1,mode,measurement,
        if(measurement==2)2100 else 0,-120,0,epoch,0,700,60,100,0,0,0,0,0))!!
    private fun publish(frames:Int,mode:Int=c.audition,measurement:Int=0,epoch:Int=c.epoch)=c.publishRuntime(app,
        listOf(CrossfeedController.Reading(stream,true,frames,48000,snapshot(mode,measurement,epoch))))
    @Test fun oldDriverReceivesOnlyLegacyTierAndNewStateIsRetained(){
        prefs.edit().putInt(p.LEGACY,2).putInt(p.delay.key,137).commit()
        val calls=mutableListOf<Pair<Int,List<Int>>>()
        assertTrue(c.sync(app,262143,stream){id,v->calls+=id to v.toList();true})
        assertEquals(listOf(65582 to listOf(2)),calls);assertEquals(137,prefs.getInt(p.delay.key,0));assertEquals(0,c.audition)
    }
    @Test fun configAndPowerAreSentBeforeTemporaryAudition(){
        assertTrue(c.startAudition(app,owner,1));val calls=mutableListOf<Int>()
        assertTrue(c.sync(app,524287,stream){id,_->calls+=id;true})
        assertEquals(listOf(65582,65640,65581,65641),calls)
        assertTrue(prefs.getBoolean("v_cure",false));assertFalse(prefs.all.keys.any {it.contains("audition")})
    }
    @Test fun snapshotsStayInMemoryAndRequireBothForComparison(){
        assertFalse(c.startAudition(app,owner,4));assertTrue(c.saveComparison(app,owner,0))
        assertFalse(c.startAudition(app,owner,4));assertTrue(c.saveComparison(app,owner,1))
        assertTrue(c.startAudition(app,owner,4));assertTrue(c.hasComparison(0));assertTrue(c.hasComparison(1))
        assertFalse(prefs.all.keys.any {it.contains("snapshot") || it.contains("match")})
    }
    @Test fun editsStopAuditionAndDoNotChangePersistentPower(){
        assertTrue(c.startAudition(app,owner,3));prefs.edit().putInt(p.delay.key,100).commit()
        c.sync(app,524287,stream){_,_->true};assertEquals(0,c.audition)
        assertTrue(prefs.getBoolean("v_cure",false))
    }
    @Test fun matchingNeedsCurrentCompletedMeasurementsAndFixedAttenuation(){
        assertTrue(c.saveComparison(app,owner,0));assertTrue(c.saveComparison(app,owner,1))
        assertFalse(c.setMatch(app,true));assertTrue(c.startAudition(app,owner,6))
        c.sync(app,524287,stream){_,_->true};publish(200,6,2)
        assertTrue(c.setMatch(app,true));assertTrue(c.startAudition(app,owner,4))
        var packet=IntArray(0);c.sync(app,524287,stream){id,v->if(id==p.TOOLS)packet=v;true}
        assertEquals(19,packet.size);assertEquals(1,packet[1]);assertEquals(c.epoch,packet[2])
        c.stopAudition(app);assertFalse(c.match)
    }
    @Test fun idleStreamAndMasterOffExitAudition(){
        assertTrue(c.startAudition(app,owner,2));c.sync(app,524287,stream){_,_->true};publish(100)
        assertEquals(0,c.audition)
        publish(200);assertTrue(c.startAudition(app,owner,1));c.sessionEnabled(app,stream,false)
        assertEquals(0,c.audition);assertFalse(c.canListen(app))
    }
    @Test fun stalePollCannotCancelANewerAudition(){
        val old=c.pollState();assertTrue(c.startAudition(app,owner,1));c.sync(app,524287,stream){_,_->true}
        c.publishRuntime(app,listOf(CrossfeedController.Reading(stream,true,200,48000,snapshot(0))),old)
        assertEquals(1,c.audition)
    }
    @Test fun failedWireSendCancelsAndSnapshotCleanupDoesNotTouchSettings(){
        assertTrue(c.saveComparison(app,owner,0));assertTrue(c.saveComparison(app,owner,1))
        assertTrue(c.startAudition(app,owner,5));val before=prefs.all.toMap()
        assertFalse(c.sync(app,524287,stream){id,_->id!=p.TOOLS});assertEquals(0,c.audition)
        c.stopAudition(app,owner,true);assertFalse(c.hasComparison(0));assertEquals(before,prefs.all)
    }
    @Test fun repeatMeasurementGetsFreshEpochAndOldEditorCannotClearNewSnapshots(){
        val oldOwner=Any();assertTrue(c.saveComparison(app,owner,0));assertTrue(c.saveComparison(app,owner,1))
        c.stopAudition(app,oldOwner,true);assertTrue(c.hasComparison(0))
        assertTrue(c.measure(app,owner));val first=c.epoch;assertTrue(c.measure(app,owner));assertTrue(c.epoch>first)
        assertFalse(c.match);assertEquals(6,c.audition)
    }
    @Test fun upstreamOutputEditInvalidatesTemporaryListeningWithoutChangingSavedSnapshots(){
        assertTrue(c.saveComparison(app,owner,0));assertTrue(c.saveComparison(app,owner,1))
        assertTrue(c.startAudition(app,owner,4))
        val output=me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache.getPreferences(app,
            me.timschneeberger.rootlessjamesdsp.utils.Constants.PREF_OUTPUT)
        output.edit().putInt("balance",17).commit();c.sync(app,524287,stream){_,_->true}
        assertEquals(0,c.audition);assertTrue(c.hasComparison(0));output.edit().clear().commit()
    }
}
