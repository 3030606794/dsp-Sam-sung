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
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class DiffAdvancedParametersTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=DiffController.preferences(app)
    private val p=DiffAdvancedParameters
    @Before fun prepare() {
        prefs.edit().clear().commit();DiffController.stopAudition(app)
        DiffController.publishRuntime(app,emptyList());DiffController.publishCapabilities(app,4095)
    }
    @Test fun missingKeysKeepFirstRoundAndFineInheritsWithoutEnabling() {
        prefs.edit().putInt(DiffParameters.delay.key,123).commit();p.migrate(prefs)
        assertFalse(p.requiresNewApp(prefs.all));assertEquals(1230,p.value(prefs.all,p.fine))
        assertArrayEquals(intArrayOf(0,0,1230,40,0,0,0,2,0,30,5,1000,20,400,0,500,60,20,80,200,2000),p.packet(prefs.all))
        assertEquals(1,p.fine.parse("0.001"));assertNull(p.fine.parse("0.0001"))
        assertFalse(p.enabled(prefs.all,p.FINE_ENABLED));assertFalse(prefs.getBoolean("v_diff",false))
    }
    @Test fun corruptedAdvancedStorageNormalizesAtomicallyWithoutTouchingOldValues() {
        prefs.edit().putInt(DiffParameters.legacy.key,17).putFloat(p.fine.key,1.5f).putInt(p.window.key,60)
            .putLong(p.wet.key,-25).putString(p.GUARD_ENABLED,"true").putInt(p.QUALITY,3).putInt(p.GUARD_MODE,-1).commit()
        p.migrate(prefs)
        assertEquals(5000,prefs.getInt(p.fine.key,-1));assertEquals(40,prefs.getInt(p.window.key,-1))
        assertEquals(-25,prefs.getInt(p.wet.key,0));assertFalse(prefs.getBoolean(p.GUARD_ENABLED,true))
        assertEquals(0,p.quality(prefs.all));assertEquals(2,p.detector(prefs.all));assertEquals(17,prefs.getInt(DiffParameters.legacy.key,-1))
        assertEquals("1033",Preset.minimumVersionCode(app))
        p.all.forEach { c->assertEquals(c.min,p.value(mapOf(c.key to c.min),c));assertEquals(c.max,p.value(mapOf(c.key to c.max),c)) }
    }
    @Test fun newSyncIsCapabilityGatedAndConfiguresBeforePowerAndExtendedAudition() {
        val id=Any();prefs.edit().putBoolean("v_diff",true).putBoolean(p.FINE_ENABLED,true).putInt(p.fine.key,1234).commit()
        DiffController.publishRuntime(app,listOf(DiffController.Reading(id,true,48000,0,null)))
        try {
            assertTrue(DiffController.startAudition(app,this,4))
            val sent=mutableListOf<Pair<Int,IntArray>>()
            assertTrue(DiffController.sync(app,4095,id){ k,v->sent+=k to v.copyOf();true })
            assertEquals(21,sent.single {it.first==p.PACKET}.second.size)
            assertEquals(1234,sent.single {it.first==p.PACKET}.second[2])
            assertTrue(sent.indexOfFirst {it.first==p.PACKET}<sent.indexOfFirst {it.first==65557})
            assertEquals(listOf(0,4),sent.filter {it.first==p.AUDITION_PACKET}.map {it.second.single()})
            val oldPoll=DiffController.pollState()
            DiffController.publishCapabilities(app,2047);assertEquals(0,DiffController.audition)
            assertFalse(DiffController.startAudition(app,this,3));sent.clear()
            assertTrue(DiffController.sync(app,2047,id){k,v->sent+=k to v;true})
            assertTrue(sent.none {it.first==p.PACKET || it.first==p.AUDITION_PACKET})
            DiffController.publishRuntime(app,listOf(DiffController.Reading(id,true,48000,64,null)),oldPoll.auditionRevision,oldPoll.runtimeRevision,oldPoll.sentRevisionToken)
            assertEquals(0,DiffController.audition);assertEquals(1234,prefs.getInt(p.fine.key,-1))
        } finally {DiffController.sessionClosed(app,id)}
    }
    @Test fun failedSecondPacketCancelsTemporaryAudition() {
        val id=Any();prefs.edit().putBoolean("v_diff",true).commit()
        DiffController.publishRuntime(app,listOf(DiffController.Reading(id,true,48000,0,null)))
        try {
            assertTrue(DiffController.startAudition(app,this,3))
            assertFalse(DiffController.sync(app,4095,id){k,_->k!=p.PACKET});assertEquals(0,DiffController.audition)
        } finally {DiffController.sessionClosed(app,id)}
    }
    @Test fun bulkSnapshotRejectsPartialPublishingAndInvalidFields() {
        val data=bulk()
        val result=DiffRuntimeReader.decodeBulk(data)!!
        assertEquals(4,result.audition);assertEquals(80,result.advanced!!.window)
        assertEquals(200,result.advanced!!.low);assertEquals(-300,result.advanced!!.band(2,2).mid)
        assertNull(DiffRuntimeReader.decodeBulk(data.copyOf(83)))
        for((index,value) in listOf(20 to 1,18 to 5,24 to 2,26 to 60,30 to 1001,35 to 10001,46 to -10001,82 to 7000)) {
            assertNull("Invalid bulk field $index",DiffRuntimeReader.decodeBulk(data.copyOf().apply {this[index]=value}))
        }
    }
    @Test fun responsePredictsLinearLossMonoCombAndOutputTrimWithoutApplyingDynamicProtection() {
        val c=DiffResponse.Configuration(48000,.5,1.0,false,150,12000,0,40,0.0,0.0)
        val points=DiffResponse.calculate(c)
        for(point in points) {
            val w=2*PI*point.frequency/48000
            val real=(1+cos(w))*.5;val imag=-sin(w)*.5
            assertEquals(20*log10(hypot(real,imag)),point.selected,1e-9)
            assertEquals(20*log10(hypot(1+real,imag)*.5),point.mono,1e-9)
            assertEquals(0.0,point.other,1e-9)
        }
        val cubic=DiffResponse.calculate(c.copy(quality=1))
        assertTrue(cubic.last().selected>points.last().selected)
        val zero=DiffResponse.calculate(c.copy(delaySamples=0.0,quality=1,band=true,window=80,outputDb=-6.0))
        zero.forEach {assertEquals(-6.0,it.selected,1e-10);assertEquals(-6.0,it.other,1e-10);assertEquals(-6.0,it.mono,1e-10)}
        val legacy=DiffResponse.configuration(44100,mapOf(DiffParameters.legacy.key to 5,p.QUALITY to 1,p.FINE_ENABLED to true,p.fine.key to 1))
        assertEquals(220.0,legacy.delaySamples,1e-12);assertEquals(0,legacy.quality)
    }
    companion object {
        fun bulk()=IntArray(84).apply {
            val basic=intArrayOf(5001,240048,0,1,21,0,0,10000,9000,0,-10,-300,-300,-300,Int.MIN_VALUE,-300,-1000,1,4,0,2,1,1000,0)
            basic.copyInto(this)
            val second=intArrayOf(1,1,80,0,0,1000,500,500,0,1,0,10000,9000,0,-10,-300,Int.MIN_VALUE,-300,-1000,-300,-300,1)
            second.copyInto(this,24)
            for(b in 0..2)for(s in 0..2){val i=46+b*12+s*4;this[i]=10000;this[i+1]=0;this[i+2]=-300;this[i+3]=Int.MIN_VALUE}
            this[82]=200;this[83]=2000
        }
    }
}
