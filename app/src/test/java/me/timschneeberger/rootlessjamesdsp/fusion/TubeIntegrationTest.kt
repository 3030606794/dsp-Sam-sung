package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
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
@Config(sdk=[35], application=Application::class, shadows=[ShadowPosixAtomicFile::class])
class TubeIntegrationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=TubeParameters
    private val prefs get()=TubeController.preferences(app)
    @Before fun prepare() {
        stopKoin();startKoin {modules(module {single<Context> {app}})}
        prefs.edit().clear().commit()
        RoomReverbController.preferences(app).edit().clear().commit()
        TubeController.stopAudition(app,clearSnapshots=true)
    }
    @After fun cleanup() {prefs.edit().clear().commit();stopKoin()}

    @Test fun inactiveNewKeysStillRequire1045ButTheOldPowerSwitchDoesNot() {
        prefs.edit().putBoolean(p.ENABLED,false).commit()
        val old=Preset.minimumVersionCode(app);assertTrue(old.toInt()<1045)
        val defaults=p.defaults().values()
        p.newKeys.forEach {key ->
            val edit=prefs.edit();when(val value=defaults.getValue(key)) {
                is Boolean -> edit.putBoolean(key,value)
                is Int -> edit.putInt(key,value)
            };edit.commit()
            assertEquals("1045",Preset.minimumVersionCode(app))
            prefs.edit().remove(key).commit()
        }
        assertEquals(old,Preset.minimumVersionCode(app));assertFalse(p.moduleEnabled(prefs.all))
    }

    @Test fun wholeProfileExportsTheProfessionalSoundWithoutFavoritesOrTransientTools() {
        val settings=p.defaults().copy(professional=true,satOn=true,drive=83,asym=-117,
            toneOn=true,preHP=137,qualityOn=true,factor=4,meters=true)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val store=TubePresetStore(app);val saved=store.save("配置之外的收藏",settings)
        val profile=Preset("tube.tar",temporary.newFolder());assertTrue(profile.save())
        val extracted=temporary.newFolder()
        val metadata=Tar.Reader(profile.file().inputStream()).extract(extracted)!!
        assertEquals("1045",metadata[Preset.META_MIN_VERSION_CODE])
        val xml=File(extracted,"${ViperParameters.PREFS}.xml").readText()
        assertTrue(xml.contains(p.drive.key));assertTrue(xml.contains("83"));assertTrue(xml.contains("137"))
        listOf("audition","snapshot","measurement","capabilities","session","peak").forEach {assertFalse(xml.contains(it))}
        assertTrue(extracted.listFiles()!!.none {it.name.contains("favorite") || it.name.contains("driver_status")})
        assertEquals(listOf(saved),store.load())
    }

    @Test fun olderProfileCannotLeaveTubeProfessionalSettingsUnderItsOriginalPowerSwitch() {
        val output=app.getSharedPreferences(Constants.PREF_OUTPUT,Context.MODE_PRIVATE)
        output.edit().putFloat("output_postgain",-1f).commit()
        val archive=Preset("old.tar",temporary.newFolder())
        Tar.Composer(archive.file()).use {tar ->
            tar.metadata[Preset.META_VERSION]="3";tar.metadata[Preset.META_MIN_VERSION_CODE]="1010"
            assertTrue(tar.add(File(app.applicationInfo.dataDir,"shared_prefs/${Constants.PREF_OUTPUT}.xml")))
        }
        val settings=p.defaults().copy(professional=true,satOn=true,drive=127)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val store=TubePresetStore(app);val saved=store.save("保留电子管收藏",settings)
        Preset.load(app,archive.file().inputStream())
        assertTrue(p.newKeys.none(prefs.all::containsKey));assertFalse(p.moduleEnabled(prefs.all))
        assertEquals(p.defaults(),p.capture(prefs.all));assertEquals(listOf(saved),store.load())
    }

}
