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
@Config(sdk = [35], application = Application::class)
class FieldPresetTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = FieldController.preferences(app)
    private val p = FieldParameters
    @Before fun prepare() {
        stopKoin(); startKoin { modules(module { single<Context> { app } }) }
        preferences.edit().clear().commit(); FieldController.stopAudition(app)
        FieldController.publishRuntime(app,emptyList()); FieldController.publishCapabilities(app,511)
    }
    @After fun cleanup() { FieldController.stopAudition(app); stopKoin() }
    private fun save(name: String) = Preset(name,temporary.newFolder()).also { assertTrue(it.save()) }
    @Test fun archiveRestoresLegacyAndProfessionalValuesAndIndependentDepthWithoutAudition() {
        preferences.edit().putInt(p.legacyWidth.key,37).putInt(p.MODE,1).putInt(p.width.key,1234)
            .putBoolean(p.LEGACY_DEPTH_ENABLED,false).putBoolean(p.MS_DEPTH_ENABLED,true).putBoolean("v_field",true).commit()
        FieldController.publishRuntime(app,listOf(FieldController.Reading("stream",true,48000,0,listOf(150,16000,120),
            null,null,null,null,0,false)))
        assertTrue(FieldController.startAudition(app,Any(),3))
        val preset = save("field.tar"); val folder = temporary.newFolder()
        val metadata = Tar.Reader(preset.file().inputStream()).extract(folder)!!
        assertEquals("1030",metadata[Preset.META_MIN_VERSION_CODE])
        val xml = File(folder,"dsp_fusion_viper.xml").readText()
        assertTrue(xml.contains(p.MS_DEPTH_ENABLED)); assertFalse(xml.contains("audition"))
        assertTrue(folder.listFiles()!!.none { it.name.contains("field_driver_status") })
        preferences.edit().clear().putInt(p.width.key,0).commit()
        Preset.load(app,preset.file().inputStream())
        assertEquals(0,FieldController.audition)
        assertEquals(1234,preferences.getInt(p.width.key,-1)); assertEquals(37,preferences.getInt(p.legacyWidth.key,-1))
        assertFalse(preferences.getBoolean(p.LEGACY_DEPTH_ENABLED,true)); assertTrue(preferences.getBoolean(p.MS_DEPTH_ENABLED,false))
    }
    @Test fun oldPresetReimportRemovesNewKeysRepeatedlyAndRetainsAllThreeOriginalScales() {
        preferences.edit().putInt(p.legacyWidth.key,37).putInt(p.legacyCenter.key,163).putInt(p.depth.key,555).commit()
        val old = save("old-field.tar")
        repeat(2) {
            preferences.edit().putInt(p.MODE,1).putInt(p.width.key,2000).putBoolean(p.MS_DEPTH_ENABLED,true)
                .putBoolean(p.BAND_ENABLED,true).putBoolean(p.FOCUS_ENABLED,true).commit()
            Preset.load(app,old.file().inputStream()); p.migrate(preferences)
            assertFalse(p.requiresNewApp(preferences.all))
            assertEquals(37,p.value(preferences.all,p.legacyWidth)); assertEquals(163,p.value(preferences.all,p.legacyCenter))
            assertEquals(555,p.value(preferences.all,p.depth))
            assertArrayEquals(intArrayOf(0,1000,0,0,150,16000,0,120,1000,1,1000,0,0),p.packet(preferences.all))
        }
    }
    @Test fun presetWithoutViperNamespaceCannotLeaveProfessionalFieldStateBehind() {
        val file = temporary.newFile("without-field.tar")
        val xml = File(temporary.newFolder(),"${Constants.PREF_GEQ}.xml").apply { writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?><map />") }
        Tar.Composer(file).use {
            it.metadata[Preset.META_VERSION] = "3"; it.metadata[Preset.META_MIN_VERSION_CODE] = "1010"; assertTrue(it.add(xml))
        }
        preferences.edit().putInt(p.MODE,1).putBoolean(p.BAND_ENABLED,true).putInt(p.width.key,0).commit()
        Preset.load(app,file.inputStream())
        assertTrue(preferences.all.isEmpty()); assertFalse(p.requiresNewApp(preferences.all))
    }
}
