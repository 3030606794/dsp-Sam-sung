package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class CrossfeedAdvancedTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val a=CrossfeedAdvancedParameters
    @Test fun defaultsDisableEveryNewAudioAndDiagnosticFeature(){
        val words=a.packet(emptyMap<String,Any>());assertEquals(11,words.size)
        for(i in listOf(1,3,8,10))assertEquals(0,words[i]);assertEquals(2,words[9]);assertTrue(CrossfeedAdvancedParameters.Settings(words.toList()).valid())
    }
    @Test fun allAdvancedValuesRoundTripWithCorrectUnits(){
        val v=mapOf(a.COMPENSATION to true,a.amount.key to 47,a.SEPARATE to true,a.left.key to 43,a.right.key to 107,
            a.leftDelay.key to 71,a.rightDelay.key to 311,a.FILTER to true,a.SLOPE to 4,a.DIAGNOSTICS to true)
        val s=a.capture(v);assertEquals(v,s.values());assertArrayEquals(intArrayOf(1,1,47,1,43,107,71,311,1,4,1),s.words.toIntArray())
        assertEquals(43,a.left.parse("4.3"));assertNull(a.left.parse("4.31"));assertEquals(311,a.rightDelay.parse("311"))
    }
    @Test fun presetVersionTwoCarriesEnhancementsAndVersionOneStillImports(){
        val settings=CrossfeedParameters.capture(mapOf(a.COMPENSATION to true,a.SEPARATE to true,a.leftDelay.key to 137,a.DIAGNOSTICS to true))
        val preset=CrossfeedPreset(UUID.randomUUID().toString(),"增强",settings);val json=CrossfeedPresetCodec.encode(listOf(preset))
        assertEquals(preset,CrossfeedPresetCodec.decode(json).single());val root=Json.parseToJsonElement(json).jsonObject
        assertEquals(2,root["version"]!!.jsonPrimitive.int)
        val old=buildJsonObject {root.forEach {(k,v)->put(k,if(k=="version")JsonPrimitive(1)else if(k=="presets")buildJsonArray {
            root["presets"]!!.jsonArray.forEach {entry->add(buildJsonObject {entry.jsonObject.forEach {(ek,ev)->put(ek,if(ek=="settings")JsonObject(ev.jsonObject.filterKeys {it!="advanced"})else ev)}})}
        }else v)}}.toString()
        assertEquals(CrossfeedAdvancedParameters.Settings(),CrossfeedPresetCodec.decode(old).single().settings.advanced)
    }
    @Test fun snapshotsIncludeIndependentDirectionsWithoutPersistingAudition(){
        val prefs=CrossfeedController.preferences(app);CrossfeedController.stopAudition(app,clearSnapshots=true);CrossfeedController.publishCapabilities(app,2097151)
        prefs.edit().clear().putBoolean("v_cure",true).putBoolean(CrossfeedParameters.ENABLED,true).putBoolean(a.SEPARATE,true).putInt(a.leftDelay.key,137).commit()
        assertTrue(CrossfeedController.saveComparison(app,this,0));assertTrue(CrossfeedController.saveComparison(app,this,1))
        val packets=linkedMapOf<Int,IntArray>();assertTrue(CrossfeedController.sync(app,2097151,this){id,v->packets[id]=v;true})
        assertEquals(11,packets[a.PACKET]!!.size);assertEquals(41,packets[a.TOOLS]!!.size);assertEquals(137,packets[a.TOOLS]!![3+8+6])
        assertFalse(prefs.all.keys.any {it.contains("audition") || it.contains("snapshot")})
        CrossfeedController.publishCapabilities(app,524287);assertFalse(CrossfeedController.hasComparison(0))
    }
}
