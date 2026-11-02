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
class AnalogIntegrationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=AnalogParameters
    private val prefs get()=AnalogController.preferences(app)
    @Before fun prepare() {
        stopKoin();startKoin {modules(module {single<Context> {app}})}
        prefs.edit().clear().commit()
        RoomReverbController.preferences(app).edit().clear().commit()
        AnalogController.stopAudition(app,clearSnapshots=true)
    }
    @After fun cleanup() {prefs.edit().clear().commit();stopKoin()}

    @Test fun inactiveNewKeysRequire1046WhileTheOriginalPowerAndThreeModesStayCompatible() {
        prefs.edit().putBoolean(p.ENABLED,false).putInt(p.BASE,2).commit()
        val old=Preset.minimumVersionCode(app);assertTrue(old.toInt()<1046)
        val defaults=p.defaults().values()
        p.newKeys.forEach {key ->
            val edit=prefs.edit();when(val value=defaults.getValue(key)) {
                is Boolean -> edit.putBoolean(key,value)
                is Int -> edit.putInt(key,value)
            };edit.commit()
            assertEquals("1046",Preset.minimumVersionCode(app))
            prefs.edit().remove(key).commit()
        }
        assertEquals(old,Preset.minimumVersionCode(app));assertEquals(2,p.capture(prefs.all).baseMode)
        assertFalse(p.moduleEnabled(prefs.all))
    }

    @Test fun completeProfileExportsTheBaseAndFineSoundWithoutFavoritesOrTemporaryState() {
        val settings=p.defaults(2).copy(professional=true,coreOn=true,drive=83,h2=-117,h5=913,
            toneOn=true,lpDelta=-1833,qualityOn=true,factor=4,meters=true)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val store=AnalogPresetStore(app);val saved=store.save("配置之外的染色收藏",settings)
        val profile=Preset("analog.tar",temporary.newFolder());assertTrue(profile.save())
        val extracted=temporary.newFolder()
        val metadata=Tar.Reader(profile.file().inputStream()).extract(extracted)!!
        assertEquals("1046",metadata[Preset.META_MIN_VERSION_CODE])
        val xml=File(extracted,"${ViperParameters.PREFS}.xml").readText()
        for(key in listOf(p.BASE,p.h2.key,p.h5.key,p.lpDelta.key))assertTrue(xml.contains(key))
        for(value in listOf("-117","913","-1833"))assertTrue(xml.contains(value))
        for(transient in listOf("audition","snapshot","measurement","capabilities","session"))assertFalse(xml.contains(transient))
        assertTrue(extracted.listFiles()!!.none {it.name.contains("favorite") || it.name.contains("driver_status")})
        assertEquals(listOf(saved),store.load())
    }

    @Test fun olderProfileClearsNewAnalogSettingsWithoutReplacingIndependentFavorites() {
        val output=app.getSharedPreferences(Constants.PREF_OUTPUT,Context.MODE_PRIVATE)
        output.edit().putFloat("output_postgain",-1f).commit()
        val archive=Preset("old.tar",temporary.newFolder())
        Tar.Composer(archive.file()).use {tar ->
            tar.metadata[Preset.META_VERSION]="3";tar.metadata[Preset.META_MIN_VERSION_CODE]="1010"
            assertTrue(tar.add(File(app.applicationInfo.dataDir,"shared_prefs/${Constants.PREF_OUTPUT}.xml")))
        }
        val settings=p.defaults(1).copy(professional=true,coreOn=true,h5=812)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val store=AnalogPresetStore(app);val saved=store.save("保留染色收藏",settings)
        Preset.load(app,archive.file().inputStream())
        assertTrue(p.newKeys.none(prefs.all::containsKey));assertFalse(p.moduleEnabled(prefs.all))
        assertEquals(p.defaults(),p.capture(prefs.all));assertEquals(listOf(saved),store.load())
    }

    @Test fun ordinaryViperSynchronizationPreservesIndependentTubeAndAnalogSoundsAndPowers() {
        val analog=p.defaults(2).copy(professional=true,coreOn=true,h2=1234,qualityOn=true,factor=4)
        val tube=TubeParameters.defaults().copy(professional=true,satOn=true,drive=92,asym=117)
        p.apply(prefs,analog);TubeParameters.apply(prefs,tube)
        prefs.edit().putBoolean(p.ENABLED,true).putBoolean(TubeParameters.ENABLED,false).commit()
        val packets=mutableListOf<Pair<Int,IntArray>>()
        assertTrue(ViperController.sync(app,134217727,Any()){id,v->packets+=id to v;true})
        assertArrayEquals(analog.packet(),packets.single{it.first==p.PACKET}.second)
        assertArrayEquals(tube.packet(),packets.single{it.first==TubeParameters.PACKET}.second)
        assertArrayEquals(intArrayOf(1),packets.single{it.first==65584}.second)
        assertArrayEquals(intArrayOf(0),packets.single{it.first==65583}.second)
        assertFalse(packets.any{it.first==65585})
        assertEquals(analog,p.capture(prefs.all));assertEquals(tube,TubeParameters.capture(prefs.all))
    }
}
