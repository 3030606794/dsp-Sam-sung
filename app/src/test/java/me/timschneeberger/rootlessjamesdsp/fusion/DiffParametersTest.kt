package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class DiffParametersTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=DiffController.preferences(app)
    private val p=DiffParameters
    @Before fun prepare() {
        prefs.edit().clear().commit(); DiffController.stopAudition(app)
        DiffController.publishRuntime(app,emptyList()); DiffController.publishCapabilities(app,2047)
    }
    private fun values()=mutableMapOf<Int,Int>().apply {
        listOf(5000,220500,0,0,0,0,0,10000,5000,0,-125,-300,300,-600,Int.MIN_VALUE,-700,-1300,1,0,0)
            .forEachIndexed { i,v -> put(131128+i,v) }
        put(131148,2); put(131149,1); put(131150,1000); put(131151,0)
    }
    private fun reading(id: Any,frames: Int=0,actual: Int=0,enabled: Boolean=true)=
        DiffController.Reading(id,enabled,44100,frames,DiffRuntimeReader.read(2047) { values().apply { put(131146,actual) }[it] })
    private fun sync(id: Any)=mutableListOf<Pair<Int,IntArray>>().also { sent ->
        assertTrue(DiffController.sync(app,2047,id) { key,v -> sent+=key to v.copyOf(); true })
    }
    @Test fun untouchedProfilesKeepOldWholeMillisecondsAndNewModeStartsIndependentlyAtFive() {
        prefs.edit().putInt(p.legacy.key,17).commit(); p.migrate(prefs)
        assertFalse(p.requiresNewApp(prefs.all)); assertEquals(0,p.mode(prefs.all))
        assertArrayEquals(intArrayOf(0,500,0,1000,0,150,12000),p.packet(prefs.all))
        val sent=sync(Any()); assertArrayEquals(intArrayOf(1700),sent.single { it.first==65558 }.second)
        prefs.edit().putInt(p.MODE,1).commit()
        assertEquals(500,p.packet(prefs.all)[1]); assertEquals(17,prefs.getInt(p.legacy.key,-1))
        assertEquals(1,p.delay.parse("0.01")); assertNull(p.delay.parse("0.001"))
        assertEquals("1032",Preset.minimumVersionCode(app))
    }
    @Test fun damagedStorageAndCrossedImportsNormalizeBeforeControlsAndWireReadThem() {
        prefs.edit().putFloat(p.delay.key,501.5f).putLong(p.mix.key,725).putInt(p.low.key,2000)
            .putInt(p.high.key,2000).putString(p.BAND_ENABLED,"true").putInt(p.CHANNEL,2).putFloat(p.MODE,1.5f).commit()
        p.migrate(prefs)
        assertEquals(500,prefs.getInt(p.delay.key,-1)); assertEquals(725,prefs.getInt(p.mix.key,-1))
        assertEquals(0,prefs.getInt(p.MODE,-1)); assertEquals(0,prefs.getInt(p.CHANNEL,-1))
        assertFalse(prefs.getBoolean(p.BAND_ENABLED,true))
        assertArrayEquals(intArrayOf(0,500,0,725,0,150,12000),p.packet(prefs.all))
        p.all.filter { prefs.contains(it.key) }.forEach { assertEquals(p.value(prefs.all,it),prefs.getInt(it.key,-1)) }
        prefs.edit().putInt(p.low.key,1999).putInt(p.high.key,2000).commit()
        assertFalse(p.acceptsFrequency(prefs.all,p.low.key,2000)); assertTrue(p.acceptsFrequency(prefs.all,p.low.key,1998))
    }
    @Test fun oldDriverReceivesOnlyUnchanged65558AndNeverQueriesNewIds() {
        prefs.edit().putInt(p.legacy.key,7).putInt(p.MODE,1).putInt(p.delay.key,125).putInt(p.CHANNEL,1).commit()
        val sent=mutableListOf<Pair<Int,IntArray>>()
        assertTrue(DiffController.sync(app,1023) { id,v -> sent+=id to v; true })
        assertEquals(listOf(65558),sent.map { it.first }); assertArrayEquals(intArrayOf(700),sent.single().second)
        assertEquals(125,prefs.getInt(p.delay.key,-1)); assertEquals(1,p.mode(prefs.all))
        assertNull(DiffRuntimeReader.reading(1023,Any(),true,48000,0) { fail("Old driver must not receive new GETs"); null })
    }
    @Test fun explicitAuditionChangesRearmButOrdinaryParameterSyncDoesNot() {
        val id=Any(); prefs.edit().putBoolean("v_diff",true).commit(); DiffController.publishRuntime(app,listOf(reading(id)))
        try {
            assertTrue(DiffController.startAudition(app,this,1))
            fun selections(sent: List<Pair<Int,IntArray>>)=sent.filter { it.first==p.AUDITION_PACKET }.map { it.second.single() }
            assertEquals(listOf(0,1),selections(sync(id))); assertEquals(listOf(1),selections(sync(id)))
            prefs.edit().putInt(p.delay.key,125).commit(); assertEquals(listOf(1),selections(sync(id)))
            assertTrue(DiffController.startAudition(app,this,2)); assertEquals(listOf(0,2),selections(sync(id)))
            DiffController.stopAudition(app,Any()); assertEquals(2,DiffController.audition)
            DiffController.stopAudition(app,this); assertEquals(listOf(0),selections(sync(id)))
            assertTrue(prefs.all.keys.none { it.contains("audition") })
        } finally { DiffController.sessionClosed(app,id) }
    }
    @Test fun viperSynchronizationConfiguresThenEnablesBeforeRearmingTheAudition() {
        val id=Any(); prefs.edit().putBoolean("v_diff",true).putInt(p.MODE,1).commit()
        DiffController.publishRuntime(app,listOf(reading(id)))
        try {
            assertTrue(DiffController.startAudition(app,this,2))
            val sent=mutableListOf<Pair<Int,IntArray>>()
            assertTrue(ViperController.sync(app,2047,id) { key,v -> sent+=key to v.copyOf(); true })
            assertEquals(1,sent.count { it.first==65558 }); assertEquals(1,sent.count { it.first==65557 })
            assertTrue(sent.indexOfFirst { it.first==p.PACKET } < sent.indexOfFirst { it.first==65557 })
            assertTrue(sent.indexOfFirst { it.first==65557 } < sent.indexOfFirst { it.first==p.AUDITION_PACKET })
            assertArrayEquals(intArrayOf(1),sent.single { it.first==65557 }.second)
            assertEquals(listOf(0,2),sent.filter { it.first==p.AUDITION_PACKET }.map { it.second.single() })
        } finally { DiffController.sessionClosed(app,id) }
    }
    @Test fun preSendPollCannotUndoNewAuditionButConfirmedNativeResetCan() {
        val id=Any(); prefs.edit().putBoolean("v_diff",true).commit(); DiffController.publishRuntime(app,listOf(reading(id)))
        try {
            assertTrue(DiffController.startAudition(app,this,2)); val oldPoll=DiffController.pollState()
            DiffController.publishRuntime(app,listOf(reading(id)),oldPoll.auditionRevision,oldPoll.runtimeRevision,oldPoll.sentRevisionToken)
            assertEquals(2,DiffController.audition)
            sync(id)
            DiffController.publishRuntime(app,listOf(reading(id)),oldPoll.auditionRevision,oldPoll.runtimeRevision,oldPoll.sentRevisionToken)
            assertEquals(2,DiffController.audition)
            DiffController.publishRuntime(app,listOf(reading(id,64,2))); assertEquals(2,DiffController.audition)
            DiffController.publishRuntime(app,listOf(reading(id,128,0))); assertEquals(0,DiffController.audition)
        } finally { DiffController.sessionClosed(app,id) }
    }
    @Test fun failedConfigurationOrEitherAuditionEdgeFailsClosed() {
        val id=Any(); prefs.edit().putBoolean("v_diff",true).commit(); DiffController.publishRuntime(app,listOf(reading(id)))
        try {
            for(failed in listOf(65558,p.PACKET,65557,0,2)) {
                assertTrue(DiffController.startAudition(app,Any(),2))
                assertFalse(DiffController.sync(app,2047,id) { key,v -> if(failed<3)key!=p.AUDITION_PACKET || v.single()!=failed else key!=failed })
                assertEquals(0,DiffController.audition)
            }
        } finally { DiffController.sessionClosed(app,id) }
    }
    @Test fun masterGateAndClosedSessionsCannotBeRevivedByAnInflightPoll() {
        val id=Any(); val other=Any(); prefs.edit().putBoolean("v_diff",true).commit()
        DiffController.publishRuntime(app,listOf(reading(id),reading(other)))
        try {
            assertTrue(DiffController.startAudition(app,this,1)); val poll=DiffController.pollState()
            DiffController.sessionEnabled(app,id,false); assertEquals(1,DiffController.audition)
            DiffController.sessionEnabled(app,other,false); assertEquals(0,DiffController.audition)
            assertFalse(DiffController.startAudition(app,this,1))
            DiffController.publishRuntime(app,listOf(reading(id,64)),poll.auditionRevision,poll.runtimeRevision,poll.sentRevisionToken)
            assertFalse(DiffController.masterEnabled)
            DiffController.sessionEnabled(app,id,true); assertFalse(DiffController.startAudition(app,this,1))
            DiffController.publishRuntime(app,listOf(reading(id,128))); assertTrue(DiffController.startAudition(app,this,1))
            DiffController.publishCapabilities(app,1023); assertEquals(0,DiffController.audition)
        } finally { DiffController.sessionClosed(app,id); DiffController.sessionClosed(app,other) }
    }
    @Test fun snapshotRetriesPublishingAndChangedEpochAndKeepsPositiveHeadroomPeak() {
        val data=values(); val epochs=ArrayDeque(listOf(1,2,4,6,6)); val ids=mutableListOf<Int>()
        val result=DiffRuntimeReader.read(2047) { id -> ids+=id; if(id==DiffRuntimeReader.EPOCH)epochs.removeFirst() else data[id] }
        assertNotNull(result); assertEquals(5,ids.count { it==DiffRuntimeReader.EPOCH })
        assertEquals(220500,result!!.samplesMilli); assertEquals(300,result.peak); assertEquals(1,result.mode)
        assertTrue(ids.contains(131149)); assertTrue(ids.none { it in 131103..131125 })
        data[131146]=3; assertNull(DiffRuntimeReader.read(2047) { data[it] })
        data[131146]=0; data[131135]=10001; assertNull(DiffRuntimeReader.read(2047) { data[it] })
    }
    @Test fun incompleteTelemetryRetainsConfirmedSessionAndAuditionWithoutStaleMeters() {
        val id=Any(); prefs.edit().putBoolean("v_diff",true).commit(); DiffController.publishRuntime(app,listOf(reading(id)))
        try {
            assertTrue(DiffController.startAudition(app,this,2)); sync(id)
            var count=0
            val waiting=DiffRuntimeReader.reading(2047,id,true,44100,64) { assertEquals(131148,it); count++; 1 }!!
            assertEquals(3,count); assertNull(waiting.snapshot); DiffController.publishRuntime(app,listOf(waiting))
            assertEquals(2,DiffController.audition); assertEquals(OutputRuntimeStatus.Processing.ACTIVE,DiffController.runtime.single().processing)
            assertNull(DiffController.runtime.single().snapshot)
            DiffController.publishRuntime(app,listOf(waiting.copy(enabled=false,frames=128)))
            assertEquals(0,DiffController.audition); assertFalse(DiffController.masterEnabled)
        } finally { DiffController.sessionClosed(app,id) }
    }
}
