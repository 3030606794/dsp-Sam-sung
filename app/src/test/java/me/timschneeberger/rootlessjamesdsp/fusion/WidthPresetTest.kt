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
class WidthPresetTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val p=WidthParameters
    private val prefs get()=WidthController.preferences(app)
    @Before fun prepare(){stopKoin();startKoin {modules(module {single<Context> {app}})};prefs.edit().clear().commit();WidthController.stopAudition(app);WidthController.publishRuntime(app,emptyList());WidthController.publishCapabilities(app,32767)}
    @After fun cleanup(){WidthController.stopAudition(app);WidthController.publishRuntime(app,emptyList());stopKoin()}
    private fun save(name: String)=Preset(name,temporary.newFolder()).also {assertTrue(it.save())}
    @Test fun fullPresetRequiresNewAppAndOldImportClearsOnlyExtensionsWhileRetainingOldFloat() {
        prefs.edit().putFloat(p.LEGACY,58.5f).putBoolean(p.ENABLED,true).commit();val old=save("old-width.tar")
        val edit=prefs.edit();p.all.forEach {edit.putInt(it.key,it.min)};p.switches.forEach {edit.putBoolean(it,true)};p.choices.forEach {(k,r)->edit.putInt(k,r.last)};edit.commit();val expected=prefs.all.toMap()
        val id=Any();WidthController.publishRuntime(app,listOf(WidthController.Reading(id,true,0,WidthRuntime.decode(WidthParametersTest.bulk()))))
        try {
            assertTrue(WidthController.startAudition(app,this,3));val full=save("full-width.tar");val extracted=temporary.newFolder()
            val meta=Tar.Reader(full.file().inputStream()).extract(extracted)!!;assertEquals("1036",meta[Preset.META_MIN_VERSION_CODE]);assertFalse(File(extracted,"${p.NAMESPACE}.xml").readText().contains("audition"));assertFalse(File(extracted,"width_driver_status.xml").exists())
            repeat(2){Preset.load(app,old.file().inputStream());p.normalize(prefs);assertEquals(58.5f,prefs.getFloat(p.LEGACY,0f));assertTrue(prefs.getBoolean(p.ENABLED,false));assertTrue(p.newKeys.none(prefs.all::containsKey));assertEquals(0,WidthController.audition)
                Preset.load(app,full.file().inputStream());p.normalize(prefs);assertEquals(expected,prefs.all)}
        }finally{WidthController.sessionClosed(app,id)}
    }
    @Test fun presetWithoutWidthNamespaceClearsStaleWidthSettings() {
        val archive=temporary.newFile("without-width.tar");val xml=File(temporary.newFolder(),"dsp_convolver.xml").apply {writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?><map />")}
        Tar.Composer(archive).use {it.metadata[Preset.META_VERSION]="3";it.metadata[Preset.META_MIN_VERSION_CODE]="1010";assertTrue(it.add(xml))}
        prefs.edit().putFloat(p.LEGACY,70f).putBoolean(p.ENABLED,true).putInt(p.MODE,2).putBoolean(p.MONO,true).commit();Preset.load(app,archive.inputStream());assertTrue(prefs.all.isEmpty())
    }
}
