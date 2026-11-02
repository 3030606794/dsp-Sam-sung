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
class FieldAdvancedPresetTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=FieldController.preferences(app)
    private val p=FieldAdvancedParameters
    @Before fun prepare() {
        stopKoin(); startKoin { modules(module { single<Context> { app } }) }
        prefs.edit().clear().commit(); FieldController.stopAudition(app)
        FieldController.publishRuntime(app,emptyList()); FieldController.publishCapabilities(app,1023)
    }
    @After fun cleanup() { FieldController.stopAudition(app); stopKoin() }
    private fun save(name: String)=Preset(name,temporary.newFolder()).also { assertTrue(it.save()) }
    @Test fun secondGenerationArchiveHas1031MinimumRestoresValuesAndExcludesDifferenceAudition() {
        prefs.edit().putInt(FieldParameters.MODE,1).putInt(FieldParameters.width.key,1234)
            .putBoolean(p.DYNAMIC_ENABLED,true).putBoolean(p.DELAY_ENABLED,true).putInt(p.delayTime.key,37)
            .putInt(p.diffusionStages.key,4).putBoolean("v_field",true).commit()
        FieldController.publishRuntime(app,listOf(FieldController.Reading("stream",true,44100,0,listOf(150,16000,120),null,null,null,null,0,false)))
        assertTrue(FieldController.startAudition(app,Any(),4))
        val preset=save("imaging-second.tar"); val folder=temporary.newFolder()
        val metadata=Tar.Reader(preset.file().inputStream()).extract(folder)!!
        assertEquals("1031",metadata[Preset.META_MIN_VERSION_CODE])
        val xml=File(folder,"dsp_fusion_viper.xml").readText()
        assertTrue(xml.contains(p.delayTime.key)); assertFalse(xml.contains("audition"))
        prefs.edit().clear().commit(); Preset.load(app,preset.file().inputStream())
        assertEquals(0,FieldController.audition); assertEquals(37,prefs.getInt(p.delayTime.key,-1))
        assertEquals(4,prefs.getInt(p.diffusionStages.key,-1)); assertEquals(1234,prefs.getInt(FieldParameters.width.key,-1))
        assertTrue(prefs.getBoolean(p.DYNAMIC_ENABLED,false))
    }
    @Test fun firstGenerationArchiveReimportClearsEverySecondGenerationKeyWithoutAlteringOldValues() {
        prefs.edit().putInt(FieldParameters.MODE,1).putInt(FieldParameters.width.key,1375)
            .putInt(FieldParameters.depth.key,555).putBoolean(FieldParameters.MS_DEPTH_ENABLED,true).commit()
        val old=save("imaging-first.tar")
        repeat(2) {
            prefs.edit().apply {
                p.all.forEach { putInt(it.key,it.max) }; p.switches.forEach { putBoolean(it,true) }
            }.commit()
            Preset.load(app,old.file().inputStream()); p.migrate(prefs)
            assertFalse(p.requiresNewApp(prefs.all)); assertEquals("1030",Preset.minimumVersionCode(app))
            assertEquals(1375,prefs.getInt(FieldParameters.width.key,-1)); assertEquals(555,prefs.getInt(FieldParameters.depth.key,-1))
            assertTrue(prefs.getBoolean(FieldParameters.MS_DEPTH_ENABLED,false))
            assertTrue(p.packets(prefs.all).all { it.second[0]==0 })
        }
    }
}
