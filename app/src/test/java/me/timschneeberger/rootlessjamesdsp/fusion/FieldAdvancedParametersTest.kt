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
class FieldAdvancedParametersTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=FieldController.preferences(app)
    private val p=FieldAdvancedParameters
    @Before fun prepare() {
        prefs.edit().clear().commit(); FieldController.stopAudition(app)
        FieldController.publishRuntime(app,emptyList()); FieldController.publishCapabilities(app,1023)
    }
    private fun reading(identity: Any, actual: Int=0)=FieldController.Reading(identity,true,44100,0,listOf(150,16000,120),
        10000,-600,Int.MIN_VALUE,-300,actual,true)
    private fun sync(identity: Any)=mutableListOf<Pair<Int,IntArray>>().also { output ->
        assertTrue(FieldController.sync(app,1023,identity) { id,data -> output += id to data.copyOf(); true })
    }
    @Test fun allNewProcessingDefaultsOffAndPacketsHaveTheFrozenUnits() {
        val packets=p.packets(emptyMap<String,Any>()).toMap()
        assertArrayEquals(intArrayOf(0,-180,20,30,10,250),packets[p.DYNAMIC_PACKET])
        assertArrayEquals(intArrayOf(0,0,5,30,600,1000),packets[p.GUARD_PACKET])
        assertArrayEquals(intArrayOf(0,500,60,80),packets[p.TRANSIENT_PACKET])
        assertArrayEquals(intArrayOf(0,30,150,0,2,1200,10,150),packets[p.SPACE_PACKET])
        assertEquals(31,p.delayTime.parse("0.31")); assertNull(p.delayTime.parse("0.301"))
        assertEquals(-29,p.guardThreshold.parse("-0.29")); assertEquals(11,p.dynamicRatio.parse("1.1"))
    }
    @Test fun damagedImportsAreCanonicalInStorageControlsAndPackets() {
        prefs.edit().putFloat(p.delayTime.key,30.5f).putLong(p.dynamicRatio.key,21)
            .putInt(p.diffusionStages.key,3).putString(p.DELAY_ENABLED,"true").putInt(p.guardMaximum.key,1001).commit()
        p.migrate(prefs)
        assertEquals(30,prefs.getInt(p.delayTime.key,-1)); assertEquals(21,prefs.getInt(p.dynamicRatio.key,-1))
        assertEquals(2,prefs.getInt(p.diffusionStages.key,-1)); assertFalse(prefs.getBoolean(p.DELAY_ENABLED,true))
        p.all.forEach { control ->
            if (prefs.contains(control.key)) assertEquals(p.value(prefs.all,control),prefs.getInt(control.key,-1))
        }
        assertEquals(1000,p.packets(prefs.all).toMap().getValue(p.GUARD_PACKET)[5])
    }
    @Test fun olderDriverNeverReceivesSecondGenerationAndRetainsItsSavedValues() {
        prefs.edit().putInt(p.delayTime.key,123).putBoolean(p.DYNAMIC_ENABLED,true).commit()
        for (capability in listOf(255,511,1023)) {
            val ids=mutableListOf<Int>()
            assertTrue(FieldController.sync(app,capability) { id,_ -> ids+=id; true })
            assertTrue(ids.containsAll(listOf(65554,65555,65556)))
            assertEquals(capability==1023,ids.contains(p.DYNAMIC_PACKET))
            assertEquals(capability==1023,ids.contains(p.DIFFERENCE_PACKET))
        }
        assertEquals(123,prefs.getInt(p.delayTime.key,-1)); assertTrue(prefs.getBoolean(p.DYNAMIC_ENABLED,false))
        assertEquals("1031",Preset.minimumVersionCode(app))
    }
    @Test fun differenceAuditionUsesIndependentEdgesAndNormalClearsBothProtocols() {
        val identity=Any(); val owner=Any(); prefs.edit().putBoolean("v_field",true).commit()
        FieldController.publishRuntime(app,listOf(reading(identity)))
        try {
            assertTrue(FieldController.startAudition(app,owner,4))
            fun difference(sent: List<Pair<Int,IntArray>>)=sent.filter { it.first==p.DIFFERENCE_PACKET }.map { it.second.single() }
            val first=sync(identity)
            assertEquals(listOf(0,1),difference(first))
            assertEquals(0,first.single { it.first==FieldParameters.PACKET }.second[12])
            assertEquals(listOf(1),difference(sync(identity)))
            assertTrue(FieldController.startAudition(app,owner,3))
            val side=sync(identity)
            assertEquals(listOf(0),difference(side))
            assertEquals(listOf(0,3),side.filter { it.first==FieldParameters.PACKET }.map { it.second[12] })
            assertTrue(FieldController.startAudition(app,owner,4)); assertEquals(listOf(0,1),difference(sync(identity)))
            FieldController.stopAudition(app,owner)
            val normal=sync(identity); assertEquals(listOf(0),difference(normal))
            assertEquals(0,normal.single { it.first==FieldParameters.PACKET }.second[12])
            val attempted=mutableListOf<Int>()
            assertFalse(FieldController.sync(app,1023,identity) { id,_ -> attempted+=id; id!=p.DIFFERENCE_PACKET })
            assertTrue("Normal attempts both clears even if one transport write fails",attempted.containsAll(listOf(p.DIFFERENCE_PACKET,FieldParameters.PACKET)))
        } finally { FieldController.sessionClosed(app,identity) }
    }
    @Test fun differencePollRaceNativeResetAndDeliveryFailuresCannotLeaveStaleAudition() {
        val identity=Any(); prefs.edit().putBoolean("v_field",true).commit()
        FieldController.publishRuntime(app,listOf(reading(identity)))
        try {
            assertTrue(FieldController.startAudition(app,Any(),4)); val poll=FieldController.pollState()
            sync(identity)
            FieldController.publishRuntime(app,listOf(reading(identity)),poll.auditionRevision,poll.runtimeRevision,poll.sentRevisionToken)
            assertEquals(4,FieldController.audition)
            FieldController.publishRuntime(app,listOf(reading(identity,4))); assertEquals(4,FieldController.audition)
            FieldController.publishRuntime(app,listOf(reading(identity))); assertEquals(0,FieldController.audition)
            for (failedValue in listOf(0,1)) {
                assertTrue(FieldController.startAudition(app,Any(),4))
                assertFalse(FieldController.sync(app,1023,identity) { id,data -> id!=p.DIFFERENCE_PACKET || data.single()!=failedValue })
                assertEquals(0,FieldController.audition)
            }
            assertTrue(FieldController.startAudition(app,Any(),4)); FieldController.publishCapabilities(app,511)
            assertEquals(0,FieldController.audition); assertFalse(FieldController.startAudition(app,Any(),4))
            assertTrue(FieldController.startAudition(app,Any(),1))
        } finally { FieldController.sessionClosed(app,identity) }
    }
    private fun values()=mutableMapOf<Int,Int>().apply {
        listOf(150,16000,120,10000,-600,Int.MIN_VALUE,300,0,1).forEachIndexed { i,v -> put(131103+i,v) }
        listOf(10000,100,250,500,300,13230,1200,600,2400,-600,Int.MIN_VALUE,0,10000,1)
            .forEachIndexed { i,v -> put(131112+i,v) }
        put(FieldRuntimeReader.EPOCH,2)
    }
    @Test fun coherentSnapshotRetriesOddOrChangedEpochAndNeverMixesMeterWindows() {
        val data=values(); val epochs=ArrayDeque(listOf(1,2,4,6,6)); val visited=mutableListOf<Int>()
        val snapshot=FieldRuntimeReader.read(1023) { id -> visited+=id; if (id==FieldRuntimeReader.EPOCH) epochs.removeFirst() else data[id] }
        assertNotNull(snapshot); assertEquals(5,visited.count { it==FieldRuntimeReader.EPOCH })
        assertEquals(13230,snapshot!!.advanced!!.delaySamplesMilli)
        assertEquals(300,snapshot.basic[6]) // Positive float headroom is a valid sample peak.
        var calls=0
        assertNull(FieldRuntimeReader.read(1023) { id -> calls++; if (id==FieldRuntimeReader.EPOCH) 1 else data[id] })
        assertEquals(3,calls)
    }
    @Test fun oldDriverReadsOnlyItsGetIdsAndNewInvalidMetersStayUnavailable() {
        val data=values(); val ids=mutableListOf<Int>()
        assertNotNull(FieldRuntimeReader.read(511) { id -> ids+=id; data[id] })
        assertEquals((131103..131111).toList(),ids)
        data[131110]=4
        assertNull(FieldRuntimeReader.read(511) { data[it] })
        assertNotNull(FieldRuntimeReader.read(1023) { data[it] })
        data[131114]=1001; assertNull(FieldRuntimeReader.read(1023) { data[it] })
        data[131114]=0; data.remove(131122); assertNull(FieldRuntimeReader.read(1023) { data[it] })
    }
    @Test fun incompleteWindowRetainsLiveAuditionButDisabledSessionStillClearsIt() {
        val identity=Any(); prefs.edit().putBoolean("v_field",true).commit()
        FieldController.publishRuntime(app,listOf(reading(identity)))
        try {
            assertTrue(FieldController.startAudition(app,Any(),4)); sync(identity)
            var attempts=0
            fun unavailable(enabled: Boolean, frames: Int)=FieldRuntimeReader.reading(1023,identity,enabled,44100,frames) { id ->
                assertEquals(FieldRuntimeReader.EPOCH,id); attempts++; 1
            }
            val waiting=unavailable(true,64)
            assertNotNull(waiting); assertEquals(3,attempts)
            assertTrue(waiting!!.targets.isEmpty()); assertNull(waiting.correlation)
            assertNull(waiting.midRms); assertNull(waiting.sideRms); assertNull(waiting.peak)
            assertNull(waiting.actualAudition); assertFalse(waiting.meterReady); assertNull(waiting.advanced)
            FieldController.publishRuntime(app,listOf(waiting))
            assertEquals(4,FieldController.audition); assertTrue(FieldController.masterEnabled)
            assertEquals(64,FieldController.runtime.single().frames)
            assertEquals(OutputRuntimeStatus.Processing.ACTIVE,FieldController.runtime.single().processing)
            assertNull(FieldController.runtime.single().correlation)
            assertNull("Older drivers retain their existing unavailable-reading behavior",
                FieldRuntimeReader.reading(511,identity,true,44100,64) { null })
            FieldController.publishRuntime(app,listOf(unavailable(false,128)!!))
            assertEquals(0,FieldController.audition); assertFalse(FieldController.masterEnabled)
        } finally { FieldController.sessionClosed(app,identity) }
    }
}
