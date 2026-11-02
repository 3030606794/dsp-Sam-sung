package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import me.timschneeberger.rootlessjamesdsp.utils.storage.Tar
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,shadows=[ShadowPosixAtomicFile::class])
class CrossfeedPresetTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=CrossfeedParameters
    private val prefs get()=CrossfeedController.preferences(app)
    private fun settings()=p.capture(emptyMap<String,Any>()).copy(professional=true,attenuation=46,delay=137,filter=1)
    private fun preset(name:String="自然")=CrossfeedPreset(UUID.randomUUID().toString(),name,settings())
    private fun rejects(block:()->Unit){try{block();fail("expected rejection")}catch(expected:IllegalArgumentException){}}
    @Before fun prepare(){stopKoin();startKoin{modules(module{single<Context>{app}})};prefs.edit().clear().commit();CrossfeedController.stopAudition(app,clearSnapshots=true)}
    @After fun cleanup(){stopKoin()}
    @Test fun documentRoundTripKeepsExactUnitsAndRejectsWrongFormat(){
        val entry=preset();val json=CrossfeedPresetCodec.encode(listOf(entry))
        assertEquals(entry,CrossfeedPresetCodec.decode(json).single())
        rejects{CrossfeedPresetCodec.decode(json.replace("tenth-dB","dB"))}
        rejects{CrossfeedPresetCodec.decode(json.replace("\"attenuation\":46","\"attenuation\":4.6"))}
        rejects{CrossfeedPresetCodec.decode(json.replace("\"filter\":1","\"filter\":9"))}
        rejects{CrossfeedPresetCodec.decode(json.replace("\"settings\":{","\"settings\":{\"v_cure\":true,"))}
    }
    @Test fun invalidSizeEncodingAndDepthAreRejected(){
        rejects{CrossfeedPresetCodec.read(ByteArrayInputStream(ByteArray(65537)))}
        rejects{CrossfeedPresetCodec.decode("[".repeat(20)+"]".repeat(20))}
        try{CrossfeedPresetCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28)));fail()}catch(expected:java.nio.charset.CharacterCodingException){}
        rejects{CrossfeedPresetCodec.name("bad\nname")};rejects{CrossfeedPresetCodec.name(" ")}
    }
    @Test fun libraryIsSeparateAndImportRenamesWithoutOverwriting(){
        val store=CrossfeedPresetStore(File(temporary.newFolder(),"favorites.json"))
        val saved=store.save("自然",settings());assertEquals(1,store.importJson(store.export()))
        assertEquals(listOf("自然","自然 (2)"),store.load().map {it.name});assertNotEquals(saved.id,store.load()[1].id)
        prefs.edit().clear().putInt(p.LEGACY,2).commit();assertEquals(2,store.load().size)
        val before=prefs.all.toMap();store.rename(saved.id,"重命名");assertEquals(before,prefs.all)
    }
    @Test fun capacityAndInvalidImportLeaveLibraryUntouched(){
        val file=File(temporary.newFolder(),"favorites.json");val store=CrossfeedPresetStore(file)
        repeat(20){store.save("方案 $it",settings())};val before=file.readBytes()
        rejects{store.save("第21项",settings())};rejects{store.importJson(CrossfeedPresetCodec.encode(listOf(preset())))}
        assertArrayEquals(before,file.readBytes())
        rejects{store.importJson("{broken}")};assertArrayEquals(before,file.readBytes())
    }
    @Test fun applyKeepsPowerAndAllOtherEffectsAndHasSingleModuleScope(){
        prefs.edit().putBoolean("v_cure",false).putInt("v_field_widen",37).commit()
        p.apply(prefs,settings());assertFalse(prefs.getBoolean("v_cure",true));assertEquals(37,prefs.getInt("v_field_widen",0))
        assertEquals(46,prefs.getInt(p.attenuation.key,0));assertEquals(137,prefs.getInt(p.delay.key,0))
    }
    @Test fun archiveMinimumVersionAndOldReimportRestoreCompatibility(){
        prefs.edit().putInt(p.LEGACY,2).commit();val old=Preset("old.tar",temporary.newFolder());assertTrue(old.save())
        p.apply(prefs,settings());assertEquals("1041",Preset.minimumVersionCode(app))
        val fresh=Preset("new.tar",temporary.newFolder());assertTrue(fresh.save())
        val extracted=temporary.newFolder();Tar.Reader(fresh.file().inputStream()).extract(extracted)
        assertFalse(File(extracted,"dsp_fusion_viper.xml").readText().contains("audition"))
        assertTrue(extracted.listFiles()!!.none {it.name.contains("favorites") || it.name.contains("crossfeed_driver")})
        repeat(2){p.apply(prefs,settings());Preset.load(app,old.file().inputStream());p.normalize(prefs)
            assertEquals(2,p.legacy(prefs.all));assertFalse(p.enabled(prefs.all));assertFalse(p.requiresNewApp(prefs.all))}
    }
}
