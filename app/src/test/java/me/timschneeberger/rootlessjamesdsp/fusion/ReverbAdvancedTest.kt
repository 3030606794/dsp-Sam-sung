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
@Config(sdk = [35], application = Application::class)
class ReverbAdvancedTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val p = ReverbParameters
    private val a = ReverbAdvancedParameters
    private val enhanced = ReverbAdvancedParameters.Settings(true, 237, true, 153, 67, 321, 6321, true, -287, 113, 37, 728, true)
    @Test fun everyAdvancedFeatureDefaultsOffAndUnitsHaveExactSingleTickPrecision() {
        val defaults = p.capture(emptyMap<String, Any>()).advanced
        assertFalse(defaults.rtOn || defaults.bandsOn || defaults.duckOn || defaults.graphOn)
        assertArrayEquals(intArrayOf(1,0,150,0,100,100,250,4000,0,-240,90,20,350,0,0),defaults.packet())
        assertEquals(237, a.rt60.parse("2.37")); assertNull(a.rt60.parse("2.371"))
        assertEquals(153, a.bassRatio.parse("1.53")); assertEquals(-287,a.threshold.parse("-28.7"))
        assertEquals(enhanced, a.capture(enhanced.values())); assertTrue(enhanced.valid())
        assertFalse(enhanced.copy(rt60=19).valid()); assertFalse(enhanced.copy(bassRatio=401).valid())
        assertFalse(enhanced.copy(trebleRatio=201).valid()); assertFalse(enhanced.copy(attack=0).valid())
    }
    @Test fun savingAndReopeningIncludesInactiveAdvancedValuesWithoutChangingLegacyOrPower() {
        val prefs=ReverbController.preferences(app); prefs.edit().clear().putBoolean(p.ENABLED,false).putInt("v_field_widen",73).commit()
        val settings=ReverbParameters.Settings(legacy=listOf(19,28,37,46,55), professional=false, size=317,
            advanced=enhanced.copy(rtOn=false,bandsOn=false,duckOn=false,graphOn=false))
        p.apply(prefs,settings); assertEquals(settings,p.capture(prefs.all)); assertFalse(p.moduleEnabled(prefs.all))
        assertEquals(73,prefs.getInt("v_field_widen",0)); assertEquals(listOf(19,28,37,46,55),p.legacy(prefs.all))
        val favorite=ReverbPreset(UUID.randomUUID().toString(),"新混响",settings)
        assertEquals(favorite,ReverbPresetCodec.decode(ReverbPresetCodec.encode(listOf(favorite))).single())
    }
    @Test fun versionOneFilesImportWithAllNewFeaturesOffAndUnchangedOriginalNumbers() {
        val settings=ReverbParameters.Settings(legacy=listOf(19,28,37,46,55),professional=true,size=317,predelayOn=true,predelay=147)
        val favorite=ReverbPreset(UUID.randomUUID().toString(),"旧混响",settings)
        val root=Json.parseToJsonElement(ReverbPresetCodec.encode(listOf(favorite))).jsonObject
        val entry=root["presets"]!!.jsonArray.single().jsonObject
        val oldSettings=JsonObject(entry["settings"]!!.jsonObject.filterKeys {it !in a.keys})
        val old=JsonObject(root+mapOf("version" to JsonPrimitive(1),
            "units" to JsonPrimitive("percent,tenth-percent,tenth-ms,Hz,tenth-dB"),
            "presets" to JsonArray(listOf(JsonObject(entry+("settings" to oldSettings)))))).toString()
        assertEquals(favorite,ReverbPresetCodec.decode(old).single())
        val reexported=ReverbPresetCodec.encode(ReverbPresetCodec.decode(old))
        assertEquals(2,Json.parseToJsonElement(reexported).jsonObject["version"]!!.jsonPrimitive.int)
        val malformed=old.replace("\"v_reverb_pro_size_tenths\":317,", "")
        assertThrows(IllegalArgumentException::class.java){ReverbPresetCodec.decode(malformed)}
    }
    @Test fun snapshotsIncludeTheirOwnAdvancedSettingsWhileOldDriversReceiveOnlyKnownCommands() {
        val controller=ReverbController;val prefs=controller.preferences(app);val identity=Any();val owner=Any()
        controller.stopAudition(app,clearSnapshots=true);controller.publishRuntime(app,emptyList())
        controller.publishCapabilities(app,8388607);prefs.edit().clear().commit()
        val first=ReverbParameters.Settings(professional=true,advanced=enhanced)
        val second=first.copy(advanced=enhanced.copy(rt60=413,depth=47,bassRatio=88))
        p.apply(prefs,first);assertTrue(controller.saveComparison(app,owner,0))
        p.apply(prefs,second);assertTrue(controller.saveComparison(app,owner,1))
        val packets=mutableMapOf<Int,IntArray>()
        assertTrue(controller.sync(app,8388607,identity){id,v->packets[id]=v;true})
        assertArrayEquals(second.advanced.packet(),packets[a.PACKET]);val tools=packets[p.TOOLS]!!
        assertEquals(69,tools.size);assertArrayEquals(first.advanced.packet(),tools.copyOfRange(21,36))
        assertArrayEquals(second.advanced.packet(),tools.copyOfRange(54,69))
        packets.clear();assertTrue(controller.sync(app,4194303,identity){id,v->packets[id]=v;true})
        assertFalse(packets.containsKey(a.PACKET));assertEquals(39,packets[p.TOOLS]!!.size)
        assertEquals(second,p.capture(prefs.all));controller.sessionClosed(app,identity);controller.stopAudition(app,clearSnapshots=true)
    }
    @Test fun runtimeRequiresCompleteStablePacketAndBoundedPhysicalValues() {
        val values=intArrayOf(1,2,48000,1,1,1,1,1,1,2370,3540,2290,1610,1130,321,6321)
        val report=a.decode(values)!!;assertTrue(report.ready);assertEquals(listOf(3540,2290,1610),report.decayMs)
        assertNull(a.decode(values.copyOf(15)));values[1]=3;assertNull(a.decode(values));values[1]=2
        values[13]=-1;assertNull(a.decode(values));values[13]=0;values[10]=60001;assertNull(a.decode(values))
    }
}
