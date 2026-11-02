package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
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
class SurroundProfessionalStateTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=SurroundProfessionalParameters
    @Before fun before(){SurroundProfessionalRuntime.clearComparisons(app);SurroundProfessionalRuntime.publish(app,emptyList());SurroundController.preferences(app).edit().clear().commit();SurroundController.publishCapabilities(app,131071);FileHelper.clear(app)}
    @After fun after(){SurroundProfessionalRuntime.clearComparisons(app);SurroundProfessionalRuntime.publish(app,emptyList())}
    @Test fun allEightSlotsAndOldPresetApplicationAreIsolated(){
        for(i in 0..7){SurroundController.select(app,i);SurroundController.save(app,p.with(p.with(SurroundController.settings(app),"enabled",1),"lr_delay",351+i))}
        assertFalse(SurroundController.enabled(app));for(i in 0..7){assertEquals(351+i,p.value(SurroundController.settings(app,i),"lr_delay"));assertEquals(SurroundController.defaults[i].delayUs,SurroundController.settings(app,i).delayUs)}
        SurroundController.select(app,3);SurroundController.save(app,SurroundController.Settings(317,13851,8753,97));assertTrue(SurroundController.settings(app).professional.isEmpty());assertEquals(8753,SurroundController.payload(app)[4]);assertTrue(p.on(SurroundController.settings(app,2),"enabled"));assertEquals(3,SurroundController.selected(app))
        SurroundController.reset(app);assertEquals(SurroundController.defaults[3],SurroundController.settings(app));assertTrue(p.on(SurroundController.settings(app,2),"enabled"))
    }
    @Test fun newLibraryVersionRoundTripsAndOldVersionRemainsReadable(){
        val s=p.entries.fold(SurroundController.defaults[0]){v,e->p.with(v,e.control.key,e.control.default)}
        val preset=SurroundPreset(java.util.UUID.randomUUID().toString(),"专业方案",s)
        val encoded=SurroundPresetCodec.encode(listOf(preset));val root=Json.parseToJsonElement(encoded).jsonObject;assertEquals(2,root["version"]!!.jsonPrimitive.int)
        assertEquals(s,SurroundPresetCodec.decode(encoded).single().settings)
        val old=SurroundPresetCodec.encode(listOf(preset.copy(settings=s.copy(professional=emptyMap()))));assertEquals(1,Json.parseToJsonElement(old).jsonObject["version"]!!.jsonPrimitive.int);assertTrue(SurroundPresetCodec.decode(old).single().settings.professional.isEmpty())
        val bad=encoded.replace("\"lr_delay\":350","\"lr_delay\":199");assertNotEquals(encoded,bad);assertThrows(IllegalArgumentException::class.java){SurroundPresetCodec.decode(bad)}
        val wrong=encoded.replace("\"lr_delay\":350","\"unknown_control\":350");assertThrows(IllegalArgumentException::class.java){SurroundPresetCodec.decode(wrong)}
        val store=SurroundPresetStore(app);val saved=store.save("保存",s);assertEquals(s,store.load().single().settings);SurroundController.select(app,5);assertTrue(SurroundController.setEnabled(app,true));store.applyToCurrentSlot(app,saved.id);assertEquals(s,SurroundController.settings(app));assertEquals(5,SurroundController.selected(app));assertTrue(SurroundController.enabled(app))
    }
    @Test fun driverGatingAndPresetVersionDoNotOverwriteOldFields(){
        val s=p.with(SurroundController.defaults[0].copy(attenuationMilliDb=8753),"enabled",1);SurroundController.save(app,s)
        assertEquals("1038",Preset.minimumVersionCode(app));val packets=mutableListOf<Pair<Int,List<Int>>>()
        assertTrue(SurroundController.sync(app,16){id,v->packets+=id to v.toList();true});assertEquals(listOf(SurroundController.PARAMETER),packets.map {it.first});assertEquals(8753,packets[0].second[4]);assertFalse(SurroundController.professionalSupported(app))
        packets.clear();assertTrue(SurroundController.sync(app,131071){id,v->packets+=id to v.toList();true});assertEquals(27,packets.first().second.size);assertEquals(1,packets.first().second[7]);assertEquals(s,SurroundController.settings(app))
        assertThrows(IllegalArgumentException::class.java){SurroundController.save(app,s.copy(professional=mapOf("lr_delay" to 199)))}
    }
    @Test fun temporaryABNeverPersistsAndStalePollsCannotRestoreAudition(){
        val id=Any();SurroundController.setEnabled(app,true);SurroundProfessionalRuntime.capture(app,3);SurroundController.save(app,p.with(SurroundController.settings(app),"mix",750));SurroundProfessionalRuntime.capture(app,4)
        fun reading(n:Int,aud:Int=0):SurroundProfessionalRuntime.Reading {val v=IntArray(32);v[0]=1;v[2]=48000;v[3]=1;v[16]=200;v[17]=9000;v[18]=350;v[19]=350;v[20]=5500;v[21]=5500;v[24]=aud;return SurroundProfessionalRuntime.Reading(id,true,n,48000,SurroundProfessionalRuntime.decode(v))}
        SurroundProfessionalRuntime.publish(app,listOf(reading(0)));SurroundProfessionalRuntime.publish(app,listOf(reading(512)));assertTrue(SurroundProfessionalRuntime.start(app,this,3))
        var configured=emptyList<Int>();assertTrue(SurroundController.sync(app,131071,id){param,v->if(param==p.PACKET)configured=v.toList();true});assertEquals(1000,configured[8]);assertEquals(750,p.value(SurroundController.settings(app),"mix"))
        SurroundController.save(app,p.with(SurroundController.settings(app),"response_view",2));assertEquals(3,SurroundProfessionalRuntime.audition)
        val stale=SurroundProfessionalRuntime.pollState();SurroundProfessionalRuntime.sessionEnabled(app,id,false);SurroundProfessionalRuntime.publish(app,listOf(reading(1024,3)),stale);assertEquals(0,SurroundProfessionalRuntime.audition)
        assertTrue(SurroundController.preferences(app).all.keys.none {it.contains("audition") || it.contains("comparison")})
        val bad=IntArray(32);assertNull(SurroundProfessionalRuntime.decode(bad));bad[0]=1;bad[2]=48000;bad[1]=1;assertNull(SurroundProfessionalRuntime.decode(bad))
    }
    @Test fun curvesPreferAnActiveSessionAndRejectMalformedRuntime(){
        val idle=Any();val active=Any()
        SurroundProfessionalRuntime.publish(app,listOf(SurroundProfessionalRuntime.Reading(idle,false,0,48000,null),SurroundProfessionalRuntime.Reading(active,true,0,96000,null)))
        SurroundProfessionalRuntime.publish(app,listOf(SurroundProfessionalRuntime.Reading(idle,false,0,48000,null),SurroundProfessionalRuntime.Reading(active,true,512,96000,null)))
        assertEquals(96000,SurroundProfessionalRuntime.curveRate())
        val v=IntArray(32);v[0]=1;v[2]=48000;v[3]=1;assertNull(SurroundProfessionalRuntime.decode(v))
    }
    private object FileHelper {fun clear(c:Context){java.io.File(c.filesDir,SurroundPresetStore.FILE_NAME).delete()}}
}
