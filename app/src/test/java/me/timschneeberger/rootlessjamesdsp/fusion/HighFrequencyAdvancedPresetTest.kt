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
class HighFrequencyAdvancedPresetTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = HighFrequencyController.preferences(app)
    private val p = HighFrequencyAdvancedParameters
    @Before fun prepare() {
        stopKoin(); startKoin { modules(module { single<Context> { app } }) }
        preferences.edit().clear().commit()
        HighFrequencyController.stopAudition(app)
        HighFrequencyController.publishRuntime(app,emptyList())
        HighFrequencyController.publishCapabilities(app,255)
    }
    @After fun cleanup() { HighFrequencyController.stopAudition(app); stopKoin() }
    private fun save(name: String) = Preset(name,temporary.newFolder()).also { assertTrue(it.save()) }
    @Test fun realArchiveRequires1029AndRestoresBothGenerationsWithoutPersistingAudition() {
        preferences.edit().putInt(HighFrequencyParameters.CLARITY_AMOUNT,173).putInt(p.drive.key,31)
            .putInt(p.OVERSAMPLING,4).putBoolean(p.DEESS_ENABLED,true).putBoolean("v_spectrum",true).commit()
        HighFrequencyController.publishRuntime(app,listOf(HighFrequencyController.Reading("stream",true,48000,
            listOf(8250,120,1200,240,120,7600,22000),0)))
        assertTrue(HighFrequencyController.startAudition(app,Any()))
        val preset = save("professional.tar")
        val extracted = temporary.newFolder()
        val metadata = Tar.Reader(preset.file().inputStream()).extract(extracted)!!
        assertEquals("1029",metadata[Preset.META_MIN_VERSION_CODE])
        val xml = File(extracted,"dsp_fusion_viper.xml").readText()
        assertTrue(xml.contains(p.OVERSAMPLING)); assertFalse(xml.contains("audition"))
        assertTrue(extracted.listFiles()!!.none { it.name.contains("high_frequency_driver_status") })
        preferences.edit().clear().putInt(p.drive.key,-120).commit()
        Preset.load(app,preset.file().inputStream())
        assertFalse(HighFrequencyController.audition)
        assertEquals(31,preferences.getInt(p.drive.key,-1))
        assertEquals(4,preferences.getInt(p.OVERSAMPLING,-1))
        assertEquals(173,preferences.getInt(HighFrequencyParameters.CLARITY_AMOUNT,-1))
    }
    @Test fun reimportingAnOldArchiveRemovesSecondGenerationFieldsEveryTime() {
        preferences.edit().putInt("v_clarity_amount",4).putInt("v_spectrum_amount",56).commit()
        val old = save("old.tar")
        repeat(2) {
            preferences.edit().putBoolean(p.DYNAMIC_ENABLED,true).putBoolean(p.DEESS_ENABLED,true)
                .putInt(p.drive.key,180).putInt(p.OVERSAMPLING,4).commit()
            Preset.load(app,old.file().inputStream())
            HighFrequencyParameters.migrate(preferences)
            assertFalse(p.requiresNewApp(preferences.all))
            assertArrayEquals(intArrayOf(0,0,100,1),p.nonlinearPacket(preferences.all))
            assertEquals(0,p.dynamicPacket(preferences.all)[0]); assertEquals(0,p.deessPacket(preferences.all)[0])
            assertEquals(200,preferences.getInt(HighFrequencyParameters.CLARITY_AMOUNT,-1))
        }
    }
    @Test fun oldArchiveWithoutViperNamespaceClearsAllProfessionalProcessing() {
        val file = temporary.newFile("without-viper.tar")
        val xml = File(temporary.newFolder(),"${Constants.PREF_GEQ}.xml").apply { writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?><map />") }
        Tar.Composer(file).use {
            it.metadata[Preset.META_VERSION] = "3"; it.metadata[Preset.META_MIN_VERSION_CODE] = "1010"
            assertTrue(it.add(xml))
        }
        preferences.edit().putBoolean(p.DYNAMIC_ENABLED,true).putInt(p.OVERSAMPLING,4)
            .putInt(HighFrequencyParameters.CLARITY_AMOUNT,173).commit()
        Preset.load(app,file.inputStream())
        assertTrue(preferences.all.isEmpty())
        assertArrayEquals(intArrayOf(0,0,100,1),p.nonlinearPacket(preferences.all))
    }
}
