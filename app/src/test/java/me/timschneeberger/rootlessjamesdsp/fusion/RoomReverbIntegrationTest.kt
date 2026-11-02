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
@Config(sdk=[35],application=Application::class,shadows=[ShadowPosixAtomicFile::class])
class RoomReverbIntegrationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=RoomReverbParameters
    private val prefs get()=RoomReverbController.preferences(app)
    @Before fun prepare(){
        stopKoin();startKoin {modules(module {single<Context>{app}})}
        prefs.edit().clear().commit()
        app.getSharedPreferences("dsp_fusion_viper",Context.MODE_PRIVATE).edit().clear().commit()
        RoomReverbController.stopAudition(app,clearSnapshots=true)
    }
    @After fun cleanup(){prefs.edit().clear().commit();stopKoin()}
    @Test fun inactiveProfessionalKeysStillRequireNewAppButLegacyRoomDoesNot(){
        prefs.edit().putBoolean(p.ENABLED,false).putString(p.BASE,"7").commit()
        val old=Preset.minimumVersionCode(app);assertTrue(old.toInt()<1044)
        val defaults=p.defaults(7).values()
        p.newKeys.forEach {key->
            val edit=prefs.edit();when(val value=defaults.getValue(key)){
                is Boolean->edit.putBoolean(key,value)
                is Int->edit.putInt(key,value)
            };edit.commit()
            assertEquals("1044",Preset.minimumVersionCode(app))
            prefs.edit().remove(key).commit()
        }
        assertEquals(old,Preset.minimumVersionCode(app));assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun overallProfileExportsRoomSettingsAndRetainsIndependentFavorites(){
        val settings=p.defaults(7,true).copy(timeOn=true,rt60=213,predelayOn=true,predelay=217,
            levelsOn=true,earlyTrim=-33,toneOn=true,lowCut=117,meters=true)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val store=RoomReverbPresetStore(app);val saved=store.save("设备配置之外",settings)
        val profile=Preset("room.tar",temporary.newFolder());assertTrue(profile.save())
        val extracted=temporary.newFolder()
        val metadata=Tar.Reader(profile.file().inputStream()).extract(extracted)!!
        assertEquals("1044",metadata[Preset.META_MIN_VERSION_CODE])
        val xml=File(extracted,"${Constants.PREF_REVERB}.xml").readText()
        assertTrue(xml.contains(p.rt60.key));assertTrue(xml.contains("213"));assertTrue(xml.contains("217"))
        listOf("audition","snapshot","measurement","capabilities","session","peak").forEach {assertFalse(xml.contains(it))}
        assertTrue(extracted.listFiles()!!.none {it.name.contains("favorite") || it.name.contains("driver_status")})
        assertEquals(listOf(saved),store.load())
    }
    @Test fun profileWithoutRoomNamespaceCannotRetainProfessionalRoomUnderneath(){
        val unrelated=app.getSharedPreferences(Constants.PREF_OUTPUT,Context.MODE_PRIVATE)
        unrelated.edit().putFloat("output_postgain",-1f).commit()
        val old=Preset("without-room.tar",temporary.newFolder())
        Tar.Composer(old.file()).use {archive->
            archive.metadata[Preset.META_VERSION]="3"
            archive.metadata[Preset.META_MIN_VERSION_CODE]="1010"
            assertTrue(archive.add(File(app.applicationInfo.dataDir,"shared_prefs/${Constants.PREF_OUTPUT}.xml")))
        }
        val settings=p.defaults(18,true).copy(timeOn=true,rt60=1500)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val store=RoomReverbPresetStore(app);val saved=store.save("保留收藏",settings)
        Preset.load(app,old.file().inputStream())
        assertTrue(p.newKeys.none(prefs.all::containsKey));assertFalse(p.moduleEnabled(prefs.all))
        assertEquals(15,p.capture(prefs.all).base);assertEquals(listOf(saved),store.load())
    }
}
