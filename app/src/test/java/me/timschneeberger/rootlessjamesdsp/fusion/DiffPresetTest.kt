package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.storage.Tar
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class DiffPresetTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=DiffController.preferences(app)
    private val p=DiffParameters
    @Before fun prepare() {
        stopKoin(); startKoin { modules(module { single<Context> { app } }) }
        prefs.edit().clear().commit(); DiffController.stopAudition(app)
        DiffController.publishRuntime(app,emptyList()); DiffController.publishCapabilities(app,2047)
    }
    @After fun cleanup() { DiffController.stopAudition(app); DiffController.publishRuntime(app,emptyList()); stopKoin() }
    private fun save(name: String)=Preset(name,temporary.newFolder()).also { assertTrue(it.save()) }
    @Test fun secondRoundPresetRoundTripsAndOldPresetRemovesEveryExtensionKey() {
        val a=DiffAdvancedParameters
        prefs.edit().putInt(p.legacy.key,17).putInt(p.MODE,1).putInt(p.delay.key,125).commit()
        val first=save("first-round.tar")
        val editor=prefs.edit().putInt(a.QUALITY,1).putInt(a.GUARD_MODE,1)
        a.all.forEach { editor.putInt(it.key,if(it==a.window)80 else it.min) }
        a.switches.forEach { editor.putBoolean(it,true) };editor.commit()
        val expected=a.packet(prefs.all);val second=save("second-round.tar");val folder=temporary.newFolder()
        val meta=Tar.Reader(second.file().inputStream()).extract(folder)!!
        assertEquals("1033",meta[Preset.META_MIN_VERSION_CODE])
        assertFalse(File(folder,"dsp_fusion_viper.xml").readText().contains("audition"))
        prefs.edit().clear().commit();Preset.load(app,second.file().inputStream())
        assertArrayEquals(expected,a.packet(prefs.all));assertEquals(125,prefs.getInt(p.delay.key,-1))
        repeat(2) {
            Preset.load(app,first.file().inputStream());a.migrate(prefs)
            assertFalse(a.requiresNewApp(prefs.all));assertEquals(1,p.mode(prefs.all))
            assertEquals(1250,a.value(prefs.all,a.fine));assertFalse(a.enabled(prefs.all,a.FINE_ENABLED))
        }
    }
    @Test fun professionalPresetRestoresBothDelayScalesAndDoesNotPersistTemporaryListening() {
        prefs.edit().putInt(p.legacy.key,17).putInt(p.MODE,1).putInt(p.delay.key,125).putInt(p.mix.key,725)
            .putInt(p.CHANNEL,1).putBoolean(p.BAND_ENABLED,true).putBoolean("v_diff",true).commit()
        DiffController.publishRuntime(app,listOf(DiffController.Reading("stream",true,48000,0,null)))
        assertTrue(DiffController.startAudition(app,this,2))
        val preset=save("professional-diff.tar"); val folder=temporary.newFolder()
        val meta=Tar.Reader(preset.file().inputStream()).extract(folder)!!
        assertEquals("1032",meta[Preset.META_MIN_VERSION_CODE])
        val xml=File(folder,"dsp_fusion_viper.xml").readText()
        assertTrue(xml.contains(p.delay.key)); assertFalse(xml.contains("audition"))
        assertTrue(folder.listFiles()!!.none { it.name.contains("diff_driver_status") })
        prefs.edit().clear().putInt(p.delay.key,0).commit(); Preset.load(app,preset.file().inputStream())
        assertEquals(0,DiffController.audition); assertEquals(17,prefs.getInt(p.legacy.key,-1))
        assertEquals(125,prefs.getInt(p.delay.key,-1)); assertEquals(725,prefs.getInt(p.mix.key,-1))
        assertEquals(1,p.channel(prefs.all)); assertTrue(p.bandEnabled(prefs.all))
    }
    @Test fun repeatedOldPresetImportReturnsToCompatibilityAndRemovesAllProfessionalKeys() {
        prefs.edit().putInt(p.legacy.key,17).putBoolean("v_diff",true).commit()
        val old=save("legacy-diff.tar")
        repeat(2) {
            prefs.edit().putInt(p.MODE,1).putInt(p.delay.key,125).putInt(p.CHANNEL,1).putInt(p.mix.key,500)
                .putBoolean(p.BAND_ENABLED,true).putInt(p.low.key,700).putInt(p.high.key,9000).commit()
            Preset.load(app,old.file().inputStream()); p.migrate(prefs)
            assertFalse(p.requiresNewApp(prefs.all)); assertEquals(0,p.mode(prefs.all))
            assertEquals(17,p.value(prefs.all,p.legacy)); assertTrue(prefs.getBoolean("v_diff",false))
            assertArrayEquals(intArrayOf(0,500,0,1000,0,150,12000),p.packet(prefs.all))
        }
    }
    @Test fun missingViperNamespaceClearsProfessionalStateInsteadOfLeakingAcrossProfiles() {
        val file=temporary.newFile("no-viper.tar")
        val xml=File(temporary.newFolder(),"${Constants.PREF_GEQ}.xml").apply { writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?><map />") }
        Tar.Composer(file).use { it.metadata[Preset.META_VERSION]="3"; it.metadata[Preset.META_MIN_VERSION_CODE]="1010"; assertTrue(it.add(xml)) }
        prefs.edit().putInt(p.MODE,1).putInt(p.delay.key,2000).putBoolean(p.BAND_ENABLED,true).commit()
        Preset.load(app,file.inputStream()); assertTrue(prefs.all.isEmpty())
    }
}
