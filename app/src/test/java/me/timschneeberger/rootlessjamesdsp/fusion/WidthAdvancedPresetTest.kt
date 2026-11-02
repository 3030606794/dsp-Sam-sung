package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
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
class WidthAdvancedPresetTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val p=WidthParameters
    private val prefs get()=WidthController.preferences(app)
    @Before fun prepare(){stopKoin();startKoin {modules(module {single<Context> {app}})};prefs.edit().clear().commit();WidthController.stopAudition(app);WidthController.publishRuntime(app,emptyList());WidthController.publishCapabilities(app,65535)}
    @After fun cleanup(){WidthController.stopAudition(app);WidthController.publishRuntime(app,emptyList());stopKoin()}
    private fun save(name: String)=Preset(name,temporary.newFolder()).also {assertTrue(it.save())}
    @Test fun secondRoundPresetRoundTripsAndOldImportClearsOnlyRoundTwo() {
        prefs.edit().putFloat(p.LEGACY,58.5f).putInt(p.MODE,2).putInt(p.master.key,1300).putBoolean(p.ENABLED,true).commit();val firstValues=prefs.all.toMap();val first=save("first.tar")
        val edit=prefs.edit();WidthAdvancedParameters.entries.forEach {e->if(e.kind=="switch")edit.putBoolean(e.control.key,true)else edit.putInt(e.control.key,e.control.min)};edit.commit();val expected=prefs.all.toMap();val full=save("second.tar");val folder=temporary.newFolder()
        val meta=Tar.Reader(full.file().inputStream()).extract(folder)!!;assertEquals("1037",meta[Preset.META_MIN_VERSION_CODE]);val xml=File(folder,"${p.NAMESPACE}.xml").readText();assertFalse(xml.contains("compare"));assertFalse(xml.contains("audition"))
        repeat(2){Preset.load(app,first.file().inputStream());WidthAdvancedParameters.normalize(prefs);assertEquals(firstValues,prefs.all);assertTrue(WidthAdvancedParameters.keys.none(prefs.all::containsKey));assertEquals(58.5f,prefs.getFloat(p.LEGACY,0f))
            Preset.load(app,full.file().inputStream());WidthAdvancedParameters.normalize(prefs);assertEquals(expected,prefs.all)}
    }
    @Test fun presetWithoutWidthNamespaceClearsStaleWidthSettings() {
        val archive=temporary.newFile("without-width.tar");val xml=File(temporary.newFolder(),"dsp_convolver.xml").apply {writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?><map />")}
        Tar.Composer(archive).use {it.metadata[Preset.META_VERSION]="3";it.metadata[Preset.META_MIN_VERSION_CODE]="1010";assertTrue(it.add(xml))}
        prefs.edit().putFloat(p.LEGACY,70f).putBoolean(p.ENABLED,true).putInt(p.MODE,2).putBoolean(p.MONO,true).commit();Preset.load(app,archive.inputStream());assertTrue(prefs.all.isEmpty())
    }
}
