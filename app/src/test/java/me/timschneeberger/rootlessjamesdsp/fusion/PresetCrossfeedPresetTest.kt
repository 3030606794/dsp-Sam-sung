package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,shadows=[ShadowPosixAtomicFile::class])
class PresetCrossfeedPresetTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=PresetCrossfeedParameters
    private fun settings()=p.baseSettings(3).copy(professional=true,mix=71,delay=137,output=-12,spatial=-31)
    private fun entry()=PresetCrossfeedPreset(UUID.randomUUID().toString(),"空间微调",settings())
    private fun rejects(block:()->Unit){try{block();fail("expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun exactSettingsRoundTripAndForeignModuleFilesAreRejected(){
        val e=entry();val json=PresetCrossfeedPresetCodec.encode(listOf(e));assertEquals(e,PresetCrossfeedPresetCodec.decode(json).single())
        rejects{PresetCrossfeedPresetCodec.decode(json.replace("tenth-dB","dB"))}
        rejects{PresetCrossfeedPresetCodec.decode(json.replace("\"spatial\":-31","\"spatial\":-3.1"))}
        rejects{PresetCrossfeedPresetCodec.decode(json.replace("\"delay\":137","\"delay\":501"))}
        val other=CrossfeedPreset(UUID.randomUUID().toString(),"上方方案",CrossfeedParameters.capture(emptyMap<String,Any>()))
        rejects{PresetCrossfeedPresetCodec.decode(CrossfeedPresetCodec.encode(listOf(other)))}
        rejects{CrossfeedPresetCodec.decode(json)}
    }
    @Test fun versionOneImportsNeutralAdvancedAndVersionTwoPreservesEveryField(){
        val old="""{"format":"dsp-audio-preset-crossfeed-presets","version":1,"units":"Hz,tenth-dB,percent,us","presets":[{"id":"12345678-1234-1234-1234-123456789abc","name":"旧预设","settings":{"base":3,"professional":true,"cutoff":700,"attenuation":60,"mix":71,"delay":137,"output":-12,"spatial":-31}}]}"""
        val legacy=PresetCrossfeedPresetCodec.decode(old).single();assertEquals(settings(),legacy.settings)
        val custom=legacy.copy(settings=legacy.settings.copy(advanced=p.captureAdvanced(emptyMap<String,Any>()).copy(pathOn=true,linked=false,leftToRight=-37,rightToLeft=29,filterOn=true,filterHz=1257,slope=2,centerOn=true,center=-17)))
        val encoded=PresetCrossfeedPresetCodec.encode(listOf(custom));assertTrue(encoded.contains("\"version\":2"));assertEquals(custom,PresetCrossfeedPresetCodec.decode(encoded).single())
        rejects{PresetCrossfeedPresetCodec.decode(encoded.replace("\"slope\":2","\"slope\":3"))}
        rejects{PresetCrossfeedPresetCodec.decode(encoded.replace("\"filterHz\":1257","\"filterHz\":20001"))}
        rejects{PresetCrossfeedPresetCodec.decode(encoded.replace("\"pathOn\":true","\"pathOn\":\"true\""))}
    }
    @Test fun libraryImportRenamesAndRemainsOutsideBothDeviceProfiles(){
        val file=File(temporary.newFolder(),"favorites.json");val store=PresetCrossfeedPresetStore(file);val saved=store.save("空间微调",settings())
        assertEquals(1,store.importJson(store.export()));assertEquals(listOf("空间微调","空间微调 (2)"),store.load().map {it.name})
        val lower=PresetCrossfeedController.preferences(app);val upper=CrossfeedController.preferences(app)
        lower.edit().clear().commit();upper.edit().clear().commit();assertEquals(2,store.load().size)
        store.rename(saved.id,"重命名");store.overwrite(saved.id,p.baseSettings(1));assertEquals(1,store.load().first().settings.base)
        assertTrue(lower.all.isEmpty());assertTrue(upper.all.isEmpty());store.delete(saved.id);assertEquals(1,store.load().size)
    }
    @Test fun invalidAndOverCapacityImportsAreAtomic(){
        val file=File(temporary.newFolder(),"favorites.json");val store=PresetCrossfeedPresetStore(file)
        repeat(20){store.save("方案 $it",settings())};val before=file.readBytes()
        rejects{store.save("第21项",settings())};rejects{store.importJson(PresetCrossfeedPresetCodec.encode(listOf(entry())))}
        rejects{store.importJson("{broken}")};assertArrayEquals(before,file.readBytes())
    }
    @Test fun importBoundsAndUtf8AreStrict(){
        rejects{PresetCrossfeedPresetCodec.read(ByteArrayInputStream(ByteArray(65537)))}
        rejects{PresetCrossfeedPresetCodec.decode("[".repeat(20)+"]".repeat(20))}
        try{PresetCrossfeedPresetCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28)));fail()}catch(_:java.nio.charset.CharacterCodingException){}
        rejects{PresetCrossfeedPresetCodec.name("bad\nname")}
    }
    @Test fun applyingPersonalPresetKeepsBothPowerSwitchesAndUpperSettings(){
        val lower=PresetCrossfeedController.preferences(app);val upper=CrossfeedController.preferences(app)
        lower.edit().clear().putBoolean(p.ENABLED,false).commit();upper.edit().clear().putBoolean("v_cure",true).putInt(CrossfeedParameters.LEGACY,2).commit();val before=upper.all.toMap()
        p.apply(lower,settings());assertFalse(lower.getBoolean(p.ENABLED,true));assertEquals(settings(),p.capture(lower.all));assertEquals(before,upper.all)
        assertFalse(PresetCrossfeedPresetCodec.encode(listOf(entry())).contains("audition"))
    }
}
