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
@Config(sdk=[35],application=Application::class)
class ConvolutionPresetTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=ConvolutionController.preferences(app)
    private val p=ConvolutionParameters
    @Before fun prepare() {
        stopKoin();startKoin {modules(module {single<Context> {app}})}
        prefs.edit().clear().commit();ConvolutionController.stopAudition(app);ConvolutionController.publishRuntime(app,emptyList());ConvolutionController.publishPrepared(app,null);ConvolutionController.publishCapabilities(app,8191)
    }
    @After fun cleanup(){ConvolutionController.stopAudition(app);ConvolutionController.publishRuntime(app,emptyList());ConvolutionController.publishPrepared(app,null);stopKoin()}
    private fun save(name: String)=Preset(name,temporary.newFolder()).also {assertTrue(it.save())}
    @Test fun fullProfileRoundTripsAndRepeatedOldImportRemovesExtensionsWithoutManualCacheClear() {
        val legacy="-72;-96;13;7;0;2"
        prefs.edit().putBoolean(p.ENABLED,true).putString(p.FILE,"Convolver/user-measurement.wav").putString(p.LEGACY_MODE,"1").putString(p.LEGACY_ADVANCED,legacy).commit()
        val old=save("old-convolution.tar")
        val edit=prefs.edit().putInt(p.NORM_MODE,1)
        p.all.forEach {edit.putInt(it.key,when(it){p.mix->725;p.predelay->499;p.start,p.length,p.fadeIn,p.fadeOut->100;else->it.min})}
        p.switches.forEach {edit.putBoolean(it,true)};edit.commit()
        val expected=prefs.all.toMap();val id=Any()
        ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading(id,true,48000,0,ConvolutionRuntimeReader.decode(ConvolutionParametersTest.bulk()))))
        try {
            assertTrue(ConvolutionController.startAudition(app,this,2));val full=save("full-convolution.tar");val extracted=temporary.newFolder()
            val metadata=Tar.Reader(full.file().inputStream()).extract(extracted)!!
            assertEquals("1034",metadata[Preset.META_MIN_VERSION_CODE])
            val xml=File(extracted,"${p.NAMESPACE}.xml").readText();assertTrue(xml.contains(p.mix.key));assertFalse(xml.contains("audition"));assertFalse(xml.contains("conv1_legacy_field_"))
            assertTrue(extracted.listFiles()!!.none {it.name=="convolution_driver_status.xml"})
            repeat(2) {
                // Load straight over the live SharedPreferences object; clearing it would hide a cache regression.
                Preset.load(app,old.file().inputStream());p.normalize(prefs)
                assertEquals(0,ConvolutionController.audition);assertFalse(p.requiresNewApp(prefs.all));assertEquals(legacy,p.legacyText(prefs.all))
                assertEquals("Convolver/user-measurement.wav",prefs.getString(p.FILE,null));assertEquals("1",prefs.getString(p.LEGACY_MODE,null));assertTrue(prefs.getBoolean(p.ENABLED,false))
                assertArrayEquals(intArrayOf(1000,0,0,0,0,0,0,0,0,0),p.runtimePacket(prefs.all))
                Preset.load(app,full.file().inputStream());p.normalize(prefs);assertEquals(expected,prefs.all);assertEquals(0,ConvolutionController.audition)
            }
        }finally{ConvolutionController.sessionClosed(app,id)}
    }
    @Test fun secondRoundProfileRequires1035AndFirstRoundImportClearsOnlyNewExtensions() {
        val advanced=ConvolutionAdvancedParameters
        prefs.edit().putInt(p.mix.key,725).putString(p.LEGACY_ADVANCED,"-72;-96;13;7;0;2").putString(p.FILE,"Convolver/user.irs").commit()
        val first=save("first-round.tar")
        val edit=prefs.edit();advanced.all.forEach {edit.putInt(it.key,it.min)};advanced.switches.forEach {edit.putBoolean(it,true)}
        advanced.choices.forEach {(key,max)->edit.putInt(key,max)};edit.commit()
        val expected=prefs.all.toMap();val second=save("second-round.tar");val extracted=temporary.newFolder()
        val metadata=Tar.Reader(second.file().inputStream()).extract(extracted)!!;assertEquals("1035",metadata[Preset.META_MIN_VERSION_CODE])
        assertFalse(java.io.File(extracted,"${p.NAMESPACE}.xml").readText().contains(advanced.comparison.key))
        repeat(2) {
            Preset.load(app,first.file().inputStream());p.normalize(prefs)
            assertTrue(advanced.newKeys.none(prefs.all::containsKey));assertEquals(725,prefs.getInt(p.mix.key,0))
            assertEquals("Convolver/user.irs",prefs.getString(p.FILE,null));assertEquals("-72;-96;13;7;0;2",p.legacyText(prefs.all))
            Preset.load(app,second.file().inputStream());p.normalize(prefs);assertEquals(expected,prefs.all)
        }
    }
    @Test fun profileWithoutConvolutionNamespaceClearsEverySettingAndStopsTemporaryListening() {
        val archive=temporary.newFile("without-convolution.tar")
        val xml=File(temporary.newFolder(),"${Constants.PREF_GEQ}.xml").apply {writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?><map />")}
        Tar.Composer(archive).use {it.metadata[Preset.META_VERSION]="3";it.metadata[Preset.META_MIN_VERSION_CODE]="1010";assertTrue(it.add(xml))}
        val id=Any();prefs.edit().putBoolean(ConvolutionAdvancedParameters.MATRIX,true).putInt(ConvolutionAdvancedParameters.ROUTE,1).putBoolean(p.ENABLED,true).putInt(p.mix.key,500).putBoolean(p.WINDOW_ENABLED,true).putString(p.FILE,"Convolver/custom.wav").commit()
        ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading(id,true,48000,0,ConvolutionRuntimeReader.decode(ConvolutionParametersTest.bulk()))))
        try {
            assertTrue(ConvolutionController.startAudition(app,this,3));Preset.load(app,archive.inputStream())
            assertTrue(prefs.all.isEmpty());assertEquals(0,ConvolutionController.audition)
            assertArrayEquals(intArrayOf(1000,0,0,0,0,0,0,0,0,0),p.runtimePacket(prefs.all))
        }finally{ConvolutionController.sessionClosed(app,id)}
    }
}
