package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import kotlinx.serialization.json.*
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class SurroundListeningStateTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=SurroundProfessionalParameters
    private val identity=Any()
    @Before fun setup(){SurroundProfessionalRuntime.clearComparisons(app);SurroundProfessionalRuntime.publish(app,emptyList());SurroundController.preferences(app).edit().clear().commit();SurroundController.publishCapabilities(app,262143)}
    @After fun finish(){SurroundProfessionalRuntime.clearComparisons(app);SurroundProfessionalRuntime.publish(app,emptyList())}
    private fun active(frames:Int,listening:List<Int>?=null):SurroundProfessionalRuntime.Reading {
        val v=IntArray(32);v[0]=1;v[2]=48000;v[3]=1;v[16]=200;v[17]=9000;v[18]=350;v[19]=350;v[20]=5500;v[21]=5500
        return SurroundProfessionalRuntime.Reading(identity,true,frames,48000,SurroundProfessionalRuntime.decode(v),listening=listening)
    }
    private fun playback(){assertTrue(SurroundController.setEnabled(app,true));SurroundProfessionalRuntime.publish(app,listOf(active(0)));SurroundProfessionalRuntime.publish(app,listOf(active(512)))}
    @Test fun allEightSlotsRetainOldPrecisionAndV1V2V3RemainReadable(){
        for(slot in 0..7){SurroundController.select(app,slot);SurroundController.save(app,p.with(SurroundController.settings(app).copy(attenuationMilliDb=8753),"match_window",1000+slot))}
        for(slot in 0..7){assertEquals(1000+slot,p.value(SurroundController.settings(app,slot),"match_window"));assertEquals(8753,SurroundController.settings(app,slot).attenuationMilliDb)}
        val preset=SurroundPreset(java.util.UUID.randomUUID().toString(),"校准",SurroundController.settings(app));val encoded=SurroundPresetCodec.encode(listOf(preset));assertEquals(3,Json.parseToJsonElement(encoded).jsonObject["version"]!!.jsonPrimitive.int);assertEquals(preset.settings,SurroundPresetCodec.decode(encoded).single().settings);assertEquals("1039",Preset.minimumVersionCode(app))
        assertThrows(IllegalArgumentException::class.java){SurroundPresetCodec.decode(encoded.replace("\"version\":3","\"version\":2"))}
        SurroundController.save(app,SurroundController.defaults[7]);assertFalse(p.on(SurroundController.settings(app),"match_on"));assertFalse(p.on(SurroundController.settings(app),"cal_on"));assertFalse(p.on(SurroundController.settings(app),"diagnostics_on"));assertEquals(1006,p.value(SurroundController.settings(app,6),"match_window"))
    }
    @Test fun calibrationNeedsManualStartAndCancelsOnEditSlotPowerAndCapabilityLoss(){
        SurroundController.save(app,p.with(SurroundController.settings(app),"cal_on",1));assertEquals(0,SurroundProfessionalRuntime.calibration);assertFalse(SurroundProfessionalRuntime.startCalibration(app,this));playback();assertTrue(SurroundProfessionalRuntime.startCalibration(app,this));assertEquals(3,SurroundProfessionalRuntime.calibration)
        SurroundController.save(app,p.with(SurroundController.settings(app),"diagnostics_view",1));assertEquals(3,SurroundProfessionalRuntime.calibration)
        SurroundController.save(app,p.with(SurroundController.settings(app),"cal_frequency",1001));assertEquals(0,SurroundProfessionalRuntime.calibration)
        assertTrue(SurroundProfessionalRuntime.startCalibration(app,this));SurroundController.select(app,1);assertEquals(0,SurroundProfessionalRuntime.calibration)
        SurroundController.select(app,0);assertTrue(SurroundProfessionalRuntime.startCalibration(app,this));SurroundController.publishCapabilities(app,131071);assertEquals(0,SurroundProfessionalRuntime.calibration)
        assertTrue(SurroundController.sync(app,131071){id,_->assertNotEquals(SurroundListeningParameters.PACKET,id);true});assertTrue(p.on(SurroundController.settings(app),"cal_on"))
    }
    @Test fun timeoutAndStalePollingCannotRestartTestOrWriteMeasurements(){
        playback();SurroundController.save(app,p.with(SurroundController.settings(app),"cal_on",1));assertTrue(SurroundProfessionalRuntime.startCalibration(app,this));var packet=intArrayOf()
        assertTrue(SurroundController.sync(app,262143,identity){id,v->if(id==SurroundListeningParameters.PACKET)packet=v;true});assertEquals(13,packet.size)
        val v=IntArray(24);v[0]=1;v[2]=48000;v[3]=1;v[17]=200;v[18]=8000;v[19]=packet[4];v[22]=1000;v[23]=packet[12];v[8]=Int.MIN_VALUE;v[9]=Int.MIN_VALUE
        val decoded=SurroundListeningParameters.decode(v)!!;SurroundProfessionalRuntime.publish(app,listOf(active(1024,decoded)));assertEquals(0,SurroundProfessionalRuntime.calibration)
        val stale=SurroundProfessionalRuntime.pollState();assertTrue(SurroundProfessionalRuntime.startCalibration(app,this));SurroundProfessionalRuntime.sessionEnabled(app,identity,false);SurroundProfessionalRuntime.publish(app,listOf(active(1536,decoded)),stale);assertEquals(0,SurroundProfessionalRuntime.calibration)
        assertTrue(SurroundController.preferences(app).all.keys.none {it.contains("token") || it.contains("measurement") || it.contains("comparison")})
        assertNull(SurroundListeningParameters.decode(v.copyOf(23)));v[1]=1;assertNull(SurroundListeningParameters.decode(v))
    }
    @Test fun packetFailureRestoresNormalParametersImmediately(){
        playback();SurroundProfessionalRuntime.capture(app,3);SurroundController.save(app,p.with(SurroundController.settings(app),"mix",700));SurroundProfessionalRuntime.capture(app,4);assertTrue(SurroundProfessionalRuntime.start(app,this,3))
        val sent=mutableListOf<Pair<Int,List<Int>>>();assertFalse(SurroundController.sync(app,262143,identity){id,v->sent+=id to v.toList();id!=SurroundListeningParameters.PACKET})
        assertEquals(0,SurroundProfessionalRuntime.audition);assertEquals(700,sent.last {it.first==p.PACKET}.second[8]);assertEquals(0,sent.last {it.first==p.AUDITION}.second[0])
    }
}
