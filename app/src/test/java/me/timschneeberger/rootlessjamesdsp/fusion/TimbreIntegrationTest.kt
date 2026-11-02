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
class TimbreIntegrationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=TimbreParameters
    private val prefs get()=TimbreController.preferences(app)
    @Before fun prepare() {
        stopKoin();startKoin {modules(module {single<Context> {app}})}
        prefs.edit().clear().commit()
        TimbreController.stopAudition(app,clearSnapshots=true)
    }
    @After fun cleanup() {prefs.edit().clear().commit();stopKoin()}

    @Test fun inactiveNewKeysRequire1047WhileTheOriginalFloatAndPowerStayCompatible() {
        prefs.edit().putBoolean(p.ENABLED,false).putFloat(p.LEGACY,7.3f).commit()
        val old=Preset.minimumVersionCode(app);assertTrue(old.toInt()<1047)
        val defaults=p.defaults().values()
        p.newKeys.forEach {key ->
            val edit=prefs.edit();when(val value=defaults.getValue(key)) {
                is Boolean -> edit.putBoolean(key,value)
                is Int -> edit.putInt(key,value)
            };edit.commit()
            assertEquals("1047",Preset.minimumVersionCode(app))
            prefs.edit().remove(key).commit()
        }
        assertEquals(old,Preset.minimumVersionCode(app));assertEquals(7.3f,p.legacy(prefs.all))
        assertFalse(p.moduleEnabled(prefs.all))
    }

    @Test fun wholeProfileExportsFineSoundAndLegacyFloatButExcludesIndependentFavoritesAndTools() {
        val settings=p.defaults().copy(professional=true,coreOn=true,drive=83,band2=1371,
            toneOn=true,cut2=2111,qualityOn=true,factor=4,meters=true)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).putFloat(p.LEGACY,6.2f).commit()
        val store=TimbrePresetStore(app);val saved=store.save("配置之外的音色收藏",settings)
        val profile=Preset("timbre.tar",temporary.newFolder());assertTrue(profile.save())
        val extracted=temporary.newFolder()
        val metadata=Tar.Reader(profile.file().inputStream()).extract(extracted)!!
        assertEquals("1047",metadata[Preset.META_MIN_VERSION_CODE])
        val xml=File(extracted,"${Constants.PREF_TUBE}.xml").readText()
        for(key in listOf(p.LEGACY,p.ENABLED,p.band2.key,p.cut2.key))assertTrue(xml.contains(key))
        for(value in listOf("6.2","1371","2111"))assertTrue(xml.contains(value))
        for(transient in listOf("audition","snapshot","measurement","capabilities","session"))assertFalse(xml.contains(transient))
        assertTrue(extracted.listFiles()!!.none {it.name.contains("favorite") || it.name.contains("driver_status")})
        assertEquals(listOf(saved),store.load())
    }

    @Test fun olderProfileClearsNewSettingsWithoutReplacingIndependentFavorites() {
        val output=app.getSharedPreferences(Constants.PREF_OUTPUT,Context.MODE_PRIVATE)
        output.edit().putFloat("output_postgain",-1f).commit()
        val archive=Preset("old.tar",temporary.newFolder())
        Tar.Composer(archive.file()).use {tar ->
            tar.metadata[Preset.META_VERSION]="3";tar.metadata[Preset.META_MIN_VERSION_CODE]="1010"
            assertTrue(tar.add(File(app.applicationInfo.dataDir,"shared_prefs/${Constants.PREF_OUTPUT}.xml")))
        }
        val settings=p.defaults().copy(professional=true,coreOn=true,band4=812)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).putFloat(p.LEGACY,7f).commit()
        val store=TimbrePresetStore(app);val saved=store.save("保留音色收藏",settings)
        Preset.load(app,archive.file().inputStream())
        assertTrue(p.newKeys.none(prefs.all::containsKey));assertFalse(p.moduleEnabled(prefs.all))
        assertEquals(p.defaults(),p.capture(prefs.all));assertEquals(listOf(saved),store.load())
    }

    @Test fun configurationThenShortPowerThenToolsPreserveOtherProcessorsAndOriginalFloat() {
        val settings=p.defaults().copy(professional=true,coreOn=true,band1=1234,qualityOn=true,factor=4)
        val analog=AnalogParameters.defaults(2).copy(professional=true,coreOn=true,h2=1234)
        val viper=AnalogController.preferences(app);AnalogParameters.apply(viper,analog)
        p.apply(prefs,settings);prefs.edit().putBoolean(p.ENABLED,true).putFloat(p.LEGACY,7f).commit()
        val packets=mutableListOf<Pair<Int,IntArray>>()
        assertTrue(TimbreController.sync(app,268435455,Any()){id,v->packets+=id to v;true})
        assertEquals(listOf(p.PACKET,1206,p.TOOLS),packets.map{it.first})
        assertArrayEquals(settings.packet(),packets[0].second)
        assertArrayEquals(intArrayOf(1),packets[1].second)
        assertEquals(68,packets[2].second.size)
        assertEquals(7f,p.legacy(prefs.all));assertEquals(analog,AnalogParameters.capture(viper.all))
        packets.clear();assertTrue(TimbreController.sync(app,134217727,Any()){id,v->packets+=id to v;true})
        assertTrue(packets.isEmpty());assertEquals(settings,p.capture(prefs.all))
    }

    @Test fun extendedAmountRequires1048EvenWhenTheProfessionalGroupIsOff() {
        prefs.edit().putInt(p.amount.key,2000).commit()
        assertEquals("1047",Preset.minimumVersionCode(app))
        prefs.edit().putInt(p.amount.key,2001).putBoolean(p.PROFESSIONAL,false).commit()
        assertEquals("1048",Preset.minimumVersionCode(app))
        prefs.edit().putInt(p.amount.key,10000).commit()
        assertEquals("1048",Preset.minimumVersionCode(app));assertEquals(10000,p.capture(prefs.all).amount)
    }
}
