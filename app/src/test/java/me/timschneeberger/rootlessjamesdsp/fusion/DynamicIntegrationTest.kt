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
class DynamicIntegrationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=DynamicParameters
    private val prefs get()=DynamicController.preferences(app)
    @Before fun prepare() {
        stopKoin();startKoin {modules(module {single<Context> {app}})}
        prefs.edit().clear().commit();DynamicController.stopAudition(app,clearSnapshots=true)
    }
    @After fun cleanup() {prefs.edit().clear().commit();stopKoin()}

    @Test fun inactiveProfessionalFieldsRequire1048WhileSixBasesAndStrengthStayCompatible() {
        prefs.edit().putBoolean(p.ENABLED,false).putString(p.BASE,"4").putInt(p.LEGACY,73).commit()
        val old=Preset.minimumVersionCode(app);assertTrue(old.toInt()<1048)
        val defaults=p.defaults().values()
        p.newKeys.forEach {key ->
            val e=prefs.edit();when(val value=defaults.getValue(key)) {
                is Boolean -> e.putBoolean(key,value)
                is Int -> e.putInt(key,value)
            };e.commit();assertEquals("1048",Preset.minimumVersionCode(app));prefs.edit().remove(key).commit()
        }
        assertEquals(old,Preset.minimumVersionCode(app));assertEquals(4,p.base(prefs.all));assertEquals(73,p.legacy(prefs.all))
        assertFalse(p.moduleEnabled(prefs.all))
    }

    @Test fun wholeProfileExportsFoundationAndFineValuesWithoutPersonalFavoritesOrToolState() {
        val settings=p.defaults().copy(base=4,strength=37,professional=true,coreOn=true,injectDelta=317,qDelta=123,
            toneOn=true,deepFreqDelta=17,adaptiveOn=true,threshold=-237,mixOn=true,mix=731,meters=true,response=true)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val store=DynamicPresetStore(app);val saved=store.save("设备配置之外的收藏",settings)
        val profile=Preset("dynamic.tar",temporary.newFolder());assertTrue(profile.save())
        val extracted=temporary.newFolder();val metadata=Tar.Reader(profile.file().inputStream()).extract(extracted)!!
        assertEquals("1048",metadata[Preset.META_MIN_VERSION_CODE])
        val xml=File(extracted,"${ViperParameters.PREFS}.xml").readText()
        for(key in listOf(p.BASE,p.LEGACY,p.ENABLED,p.injectDelta.key,p.qDelta.key,p.threshold.key))assertTrue(xml.contains(key))
        for(value in listOf("317","123","-237"))assertTrue(xml.contains(value))
        for(transient in listOf("audition","snapshot","measurement","capabilities","session"))assertFalse(xml.contains(transient))
        assertTrue(extracted.listFiles()!!.none {it.name.contains("favorite") || it.name.contains("driver_status")})
        assertEquals(listOf(saved),store.load())
    }

    @Test fun oldProfileMissingViperResetsTheModuleButPreservesItsIndependentLibrary() {
        val output=app.getSharedPreferences(Constants.PREF_OUTPUT,Context.MODE_PRIVATE)
        output.edit().putFloat("output_postgain",-1f).commit()
        val archive=Preset("old.tar",temporary.newFolder())
        Tar.Composer(archive.file()).use {tar ->
            tar.metadata[Preset.META_VERSION]="3";tar.metadata[Preset.META_MIN_VERSION_CODE]="1010"
            assertTrue(tar.add(File(app.applicationInfo.dataDir,"shared_prefs/${Constants.PREF_OUTPUT}.xml")))
        }
        val settings=p.defaults().copy(base=2,strength=68,professional=true,coreOn=true,bassDelta=73,adaptiveOn=true)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val store=DynamicPresetStore(app);val saved=store.save("保留动态音色收藏",settings)
        Preset.load(app,archive.file().inputStream())
        assertTrue(p.keys.none(prefs.all::containsKey));assertFalse(p.moduleEnabled(prefs.all))
        assertEquals(p.defaults(),p.capture(prefs.all));assertEquals(listOf(saved),store.load())
    }

    @Test fun atomicConfigurationPowerAndToolsDoNotChangeCompanderOrOtherColoration() {
        val settings=p.defaults().copy(base=4,strength=31,professional=true,coreOn=true,injectDelta=531,adaptiveOn=true)
        val analog=AnalogParameters.defaults(2).copy(professional=true,coreOn=true,h2=1234)
        AnalogParameters.apply(prefs,analog);p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val compander=app.getSharedPreferences(Constants.PREF_COMPANDER,Context.MODE_PRIVATE)
        compander.edit().putBoolean("compander_enable",true).putFloat("compander_timeconstant",0.37f).commit()
        val before=compander.all.toMap();val packets=mutableListOf<Pair<Int,IntArray>>()
        assertTrue(DynamicController.sync(app,536870911,Any()){id,v->packets+=id to v;true})
        assertEquals(listOf(p.PACKET,65569,p.TOOLS),packets.map{it.first})
        assertArrayEquals(settings.packet(),packets[0].second);assertArrayEquals(intArrayOf(1),packets[1].second)
        assertEquals(76,packets[2].second.size);assertEquals(analog,AnalogParameters.capture(prefs.all));assertEquals(before,compander.all)
        packets.clear();assertTrue(DynamicController.sync(app,268435455,Any()){id,v->packets+=id to v;true})
        assertTrue(packets.isEmpty());assertEquals(settings,p.capture(prefs.all))
    }

    @Test fun viperSynchronizationUsesNewAtomicPacketOrTheOriginalLegacyCoefficientsAccordingToCapability() {
        val settings=p.defaults().copy(base=4,strength=37,professional=true,coreOn=true,injectDelta=317)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val packets=mutableListOf<Pair<Int,IntArray>>()
        assertTrue(ViperController.sync(app,536870911,Any()){id,v->packets+=id to v;true})
        assertArrayEquals(settings.packet(),packets.single{it.first==p.PACKET}.second)
        assertEquals(1,packets.count{it.first==65569});assertFalse(packets.any{it.first in 65570..65573})
        packets.clear();assertTrue(ViperController.sync(app,268435455,Any()){id,v->packets+=id to v;true})
        assertFalse(packets.any{it.first in p.PACKET..p.CLEAR})
        assertArrayEquals(intArrayOf(840),packets.single{it.first==65573}.second)
        assertArrayEquals(intArrayOf(100,5600),packets.single{it.first==65570}.second)
        assertArrayEquals(intArrayOf(40,80),packets.single{it.first==65571}.second)
        assertArrayEquals(intArrayOf(50,50),packets.single{it.first==65572}.second)
        assertEquals(1,packets.count{it.first==65569})
    }
}
