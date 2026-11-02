package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspBaseEngine
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class, shadows=[ShadowPosixAtomicFile::class])
class CompanderIntegrationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val p=CompanderParameters
    private val prefs get()=CompanderController.preferences(app)
    private val dynamic get()=DynamicController.preferences(app)
    private val basisCurve="95.00;200;4e2;800;1600;3400;7500;0.1;-0.2;0.3;-0.4;0.5;-0.6;0.7;"
    private fun settings()=p.defaults().copy(legacyTime=.173713f,legacyGranularity=1.7317f,
        legacyTransform="2",legacyCurve=basisCurve,professional=true,coreOn=true,
        freqDeltas=listOf(0,0,0,0,0,17000,0),coefficientDeltas=listOf(73,0,0,0,0,0,-173),
        limitsOn=true,maxBoost=73,maxCut=173,timeOn=true,backgroundDelta=173,attack=137,
        release=317,detectOn=true,relative=17,knee=37,mixOn=true,mix=731,
        outputOn=true,output=-17,meters=true,response=true)
    @Before fun prepare() {
        stopKoin();startKoin {modules(module {single<Context> {app}})}
        prefs.edit().clear().commit();dynamic.edit().clear().commit()
        CompanderController.stopAudition(app,clearSnapshots=true)
        DynamicController.stopAudition(app,clearSnapshots=true)
    }
    @After fun cleanup() {
        CompanderController.stopAudition(app,clearSnapshots=true)
        DynamicController.stopAudition(app,clearSnapshots=true)
        prefs.edit().clear().commit();dynamic.edit().clear().commit();stopKoin()
    }

    @Test fun everyInactiveProfessionalFieldRequires1049WhileTheFourOldFoundationsStayCompatible() {
        prefs.edit().putBoolean(p.ENABLED,false).putFloat(p.BASE_TIME,.173713f)
            .putFloat(p.BASE_GRANULARITY,1.7317f).putString(p.BASE_TRANSFORM,"2")
            .putString(p.BASE_CURVE,basisCurve).commit()
        val old=Preset.minimumVersionCode(app);assertTrue(old.toInt()<1049)
        val defaults=p.defaults().values()
        p.newKeys.forEach {key ->
            val e=prefs.edit();when(val value=defaults.getValue(key)) {
                is Boolean -> e.putBoolean(key,value)
                is Int -> e.putInt(key,value)
                else -> fail("Professional key unexpectedly changed storage type: $key")
            };e.commit()
            assertEquals("1049",Preset.minimumVersionCode(app));prefs.edit().remove(key).commit()
        }
        assertEquals(old,Preset.minimumVersionCode(app))
        assertEquals(.173713f,prefs.all[p.BASE_TIME]);assertEquals(1.7317f,prefs.all[p.BASE_GRANULARITY])
        assertEquals("2",prefs.all[p.BASE_TRANSFORM]);assertEquals(basisCurve,prefs.all[p.BASE_CURVE])
        assertFalse(p.moduleEnabled(prefs.all))
    }

    @Test fun wholeProfilePreservesFloatFloatStringStringFoundationAndFineSettingsButExcludesLibrariesAndTools() {
        val value=settings();p.apply(prefs,value);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val store=CompanderPresetStore(app);val saved=store.save("设备配置之外的压扩收藏",value)
        CompanderController.publishCapabilities(app,Int.MAX_VALUE)
        assertTrue(CompanderController.saveComparison(app,this,0))
        val profile=Preset("compander.tar",temporary.newFolder());assertTrue(profile.save())
        val extracted=temporary.newFolder();val metadata=Tar.Reader(profile.file().inputStream()).extract(extracted)!!
        assertEquals("1049",metadata[Preset.META_MIN_VERSION_CODE])
        val file=File(extracted,"${Constants.PREF_COMPANDER}.xml")
        val document=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        fun element(key:String)=document.documentElement.childNodes.let {nodes ->
            (0 until nodes.length).map {nodes.item(it)}.single {it.attributes?.getNamedItem("name")?.nodeValue==key}
        }
        assertEquals("float",element(p.BASE_TIME).nodeName)
        assertEquals(.173713f,element(p.BASE_TIME).attributes.getNamedItem("value").nodeValue.toFloat(),0f)
        assertEquals("float",element(p.BASE_GRANULARITY).nodeName)
        assertEquals(1.7317f,element(p.BASE_GRANULARITY).attributes.getNamedItem("value").nodeValue.toFloat(),0f)
        assertEquals("string",element(p.BASE_TRANSFORM).nodeName);assertEquals("2",element(p.BASE_TRANSFORM).textContent)
        assertEquals("string",element(p.BASE_CURVE).nodeName);assertEquals(basisCurve,element(p.BASE_CURVE).textContent)
        val xml=file.readText()
        listOf(p.ENABLED,p.frequencies[5].key,p.coefficients[6].key,p.backgroundDelta.key,p.mix.key).forEach {assertTrue(xml.contains(it))}
        listOf("audition","snapshot","measurement_result","capabilities","session").forEach {assertFalse(xml.contains(it))}
        assertTrue(extracted.listFiles()!!.none {it.name.contains("favorite") || it.name.contains("driver_status")})
        assertEquals(listOf(saved),store.load());assertTrue(CompanderController.hasComparison(0))
    }

    @Test fun oldProfileMissingCompanderClearsAllItsParametersAndTemporaryComparisonsButPreservesFavorites() {
        val output=app.getSharedPreferences(Constants.PREF_OUTPUT,Context.MODE_PRIVATE)
        output.edit().putFloat("output_postgain",-1f).commit()
        val archive=Preset("old.tar",temporary.newFolder())
        Tar.Composer(archive.file()).use {tar ->
            tar.metadata[Preset.META_VERSION]="3";tar.metadata[Preset.META_MIN_VERSION_CODE]="1010"
            assertTrue(tar.add(File(app.applicationInfo.dataDir,"shared_prefs/${Constants.PREF_OUTPUT}.xml")))
        }
        val value=settings();p.apply(prefs,value);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val store=CompanderPresetStore(app);val saved=store.save("保留压扩收藏",value)
        CompanderController.publishCapabilities(app,Int.MAX_VALUE)
        assertTrue(CompanderController.saveComparison(app,this,0))
        Preset.load(app,archive.file().inputStream())
        assertTrue(p.keys.none(prefs.all::containsKey));assertFalse(p.moduleEnabled(prefs.all))
        assertEquals(p.defaults(),p.capture(prefs.all));assertFalse(CompanderController.hasComparison(0))
        assertEquals(listOf(saved),store.load())
    }

    @Test fun atomicConfigurationThenPowerThenToolsKeepsDynamicToneIndependentInBothDirections() {
        val value=settings();p.apply(prefs,value);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val tone=DynamicParameters.defaults().copy(base=4,strength=31,professional=true,coreOn=true,
            injectDelta=317,adaptiveOn=true,threshold=-237)
        DynamicParameters.apply(dynamic,tone);dynamic.edit().putBoolean(DynamicParameters.ENABLED,true).commit()
        val beforeDynamic=dynamic.all.toMap();val beforeCompander=prefs.all.toMap()
        val packets=mutableListOf<Pair<Int,IntArray>>()
        assertTrue(CompanderController.sync(app,Int.MAX_VALUE,Any()){id,v->packets+=id to v;true})
        assertEquals(listOf(p.PACKET,1200,p.TOOLS),packets.map {it.first})
        assertArrayEquals(value.packet(),packets[0].second);assertArrayEquals(intArrayOf(1),packets[1].second)
        assertEquals(148,packets[2].second.size);assertEquals(0,packets[2].second[0])
        assertEquals(beforeDynamic,dynamic.all);assertEquals(beforeCompander,prefs.all)
        packets.clear()
        assertTrue(DynamicController.sync(app,Int.MAX_VALUE,Any()){id,v->packets+=id to v;true})
        assertArrayEquals(tone.packet(),packets.single {it.first==DynamicParameters.PACKET}.second)
        assertEquals(beforeCompander,prefs.all);assertEquals(beforeDynamic,dynamic.all)
    }

    @Test fun anOldDriverReceivesNoNewPacketsAndTheProfessionalRequestsRemainStored() {
        val value=settings().copy(engine=1,type=3,lookaheadOn=true,lookahead=137)
        p.apply(prefs,value);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val before=prefs.all.toMap();val packets=mutableListOf<Pair<Int,IntArray>>()
        assertTrue(CompanderController.sync(app,1073741823,Any()){id,v->packets+=id to v;true})
        assertTrue(packets.isEmpty());assertEquals(before,prefs.all);assertEquals(value,p.capture(prefs.all))
        assertEquals("1049",Preset.minimumVersionCode(app))
    }

    @Test fun invalidSavedCurvesClearOnlyTemporaryToolsWithoutSendingAFallbackOrChangingTheSavedRequest() {
        val value=settings();p.apply(prefs,value);prefs.edit().putBoolean(p.ENABLED,true).commit()
        val packets=mutableListOf<Pair<Int,IntArray>>()
        assertTrue(CompanderController.sync(app,Int.MAX_VALUE,Any()){id,v->packets+=id to v;true})
        val original=packets.first().second.copyOf()
        CompanderController.publishCapabilities(app,Int.MAX_VALUE)
        assertTrue(CompanderController.saveComparison(app,this,0))
        listOf("", "95;200;400;800;1600;3400;7500;0;0;0;0;0;0",basisCurve+";",
            "95;200;200;800;1600;3400;7500;0;0;0;0;0;0;0",
            "NaN;200;400;800;1600;3400;7500;0;0;0;0;0;0;0",
            "95;200;400;800;1600;3400;7500;0;0;0;0;0;0;1.201").forEach {invalid ->
            prefs.edit().putString(p.BASE_CURVE,invalid).commit();packets.clear()
            assertTrue(CompanderController.sync(app,Int.MAX_VALUE,Any()){id,v->packets+=id to v;true})
            assertEquals(listOf(p.CLEAR),packets.map {it.first});assertArrayEquals(intArrayOf(0),packets.single().second)
            assertEquals(invalid,prefs.all[p.BASE_CURVE]);assertFalse(CompanderController.hasComparison(0))
        }
        prefs.edit().putInt(p.BASE_CURVE,1).commit();packets.clear()
        assertTrue(CompanderController.sync(app,Int.MAX_VALUE,Any()){id,v->packets+=id to v;true})
        assertEquals(listOf(p.CLEAR),packets.map {it.first});assertArrayEquals(intArrayOf(0),packets.single().second)
        assertEquals(1,prefs.all[p.BASE_CURVE])
        p.apply(prefs,value);packets.clear()
        assertTrue(CompanderController.sync(app,Int.MAX_VALUE,Any()){id,v->packets+=id to v;true})
        assertArrayEquals(original,packets.first().second)
    }

    @Test fun thePublicLegacyEntryRejectsMalformedCurvesBeforeEnteringEitherNativeEngine() {
        val engine=Probe(app)
        try {
            assertTrue(engine.setCompander(true,.173f,3,2,basisCurve))
            val original=engine.curve!!;assertEquals(1,engine.calls)
            assertArrayEquals(doubleArrayOf(95.0,200.0,400.0,800.0,1600.0,3400.0,7500.0,.1,-.2,.3,-.4,.5,-.6,.7),original,0.0)
            listOf("",p.DEFAULT_CURVE+";0",basisCurve+";",
                "95;200;400;800;1600;3400;7500;0;0;0;0;0;0;NaN",
                "95;200;200;800;1600;3400;7500;0;0;0;0;0;0;0").forEach {invalid ->
                assertFalse(engine.setCompander(true,.173f,3,2,invalid))
                assertFalse(engine.setCompander(false,.173f,3,2,invalid))
            }
            assertFalse(engine.setCompander(true,Float.NaN,3,2,basisCurve))
            assertFalse(engine.setCompander(true,.301f,3,2,basisCurve))
            assertFalse(engine.setCompander(true,.173f,5,2,basisCurve))
            assertFalse(engine.setCompander(true,.173f,3,4,basisCurve))
            assertEquals(1,engine.calls);assertSame(original,engine.curve)
        } finally {engine.close()}
    }

    @Test fun rejectedLegacyCurveStillAllowsModuleShutdownAndRetriesARealShutdownFailure() {
        prefs.edit().clear().putString(p.BASE_CURVE,"malformed").putBoolean(p.ENABLED,false).commit()
        val engine=Probe(app)
        try {
            var completed=CountDownLatch(1);engine.completed={completed.countDown()}
            engine.syncWithPreferences(arrayOf(Constants.PREF_COMPANDER));assertTrue(completed.await(5,TimeUnit.SECONDS))
            assertEquals(listOf(false),engine.rejected);assertEquals(0,engine.calls)
            prefs.edit().putBoolean(p.ENABLED,true).commit();completed=CountDownLatch(1);engine.completed={completed.countDown()}
            engine.syncWithPreferences(arrayOf(Constants.PREF_COMPANDER));assertTrue(completed.await(5,TimeUnit.SECONDS))
            assertEquals(listOf(false,true),engine.rejected);assertEquals(0,engine.calls)
            prefs.edit().putBoolean(p.ENABLED,false).commit();engine.rejectionAccepted=false
            val failed=CountDownLatch(1);engine.failed={failed.countDown()};engine.syncWithPreferences(arrayOf(Constants.PREF_COMPANDER));assertTrue(failed.await(5,TimeUnit.SECONDS))
            engine.rejectionAccepted=true;completed=CountDownLatch(1);engine.completed={completed.countDown()};engine.syncWithPreferences();assertTrue(completed.await(5,TimeUnit.SECONDS))
            assertEquals(listOf(false,true,false,false),engine.rejected);assertEquals(0,engine.calls);assertEquals("malformed",prefs.getString(p.BASE_CURVE,null))
            prefs.edit().putInt(p.BASE_CURVE,1).commit();completed=CountDownLatch(1);engine.completed={completed.countDown()}
            engine.syncWithPreferences(arrayOf(Constants.PREF_COMPANDER));assertTrue(completed.await(5,TimeUnit.SECONDS))
            assertEquals(listOf(false,true,false,false,false),engine.rejected);assertEquals(0,engine.calls);assertEquals(1,prefs.all[p.BASE_CURVE])
            val invalidFoundations=listOf<Pair<String,Any>>(p.BASE_TIME to Float.NaN,p.BASE_TIME to Float.POSITIVE_INFINITY,p.BASE_TIME to .301f,p.BASE_TIME to .059f,p.BASE_TIME to "0.22",p.BASE_GRANULARITY to Float.NaN,p.BASE_GRANULARITY to 4.01f,p.BASE_GRANULARITY to -.01f,p.BASE_GRANULARITY to "2",p.BASE_TRANSFORM to "oops",p.BASE_TRANSFORM to "4",p.BASE_TRANSFORM to 1)
            for((key,value) in invalidFoundations) {
                p.apply(prefs,p.defaults());val e=prefs.edit();when(value){is Float->e.putFloat(key,value);is String->e.putString(key,value);is Int->e.putInt(key,value)};e.commit()
                completed=CountDownLatch(1);engine.completed={completed.countDown()};engine.syncWithPreferences(arrayOf(Constants.PREF_COMPANDER));assertTrue("$key=$value",completed.await(5,TimeUnit.SECONDS))
                assertEquals(false,engine.rejected.last());assertEquals(0,engine.calls);assertEquals(value,prefs.all[key])
                val packets=mutableListOf<Pair<Int,IntArray>>();assertTrue(CompanderController.sync(app,Int.MAX_VALUE,Any()){id,v->packets+=id to v;true});assertEquals(listOf(p.CLEAR,1200),packets.map {it.first});assertFalse(packets.any {it.first==p.PACKET})
            }
        } finally {engine.close()}
    }

    /** Exercises public parsing without loading an Android native audio effect. */
    private class Probe(app:Application):JamesDspBaseEngine(app) {
        override var enabled=false
        var calls=0;var curve:DoubleArray?=null
        val rejected=mutableListOf<Boolean>()
        @Volatile var rejectionAccepted=true
        @Volatile var completed:()->Unit={}
        @Volatile var failed:()->Unit={}
        override fun onRejectedCompander(enable:Boolean):Boolean {rejected+=enable;return rejectionAccepted}
        override fun afterPreferenceSync(){completed()}
        override fun preferenceSyncFailed(){failed()}
        override fun setCompanderInternal(enable:Boolean,timeConstant:Float,granularity:Int,tfTransforms:Int,bands:DoubleArray):Boolean {calls++;curve=bands;return true}
        override fun setConvolverInternal(enable:Boolean,impulseResponse:FloatArray,irChannels:Int,irFrames:Int,irCrc:Int)=true
        override fun setOutputControl(threshold:Float,release:Float,postGain:Float)=true
        override fun setReverb(enable:Boolean,preset:Int)=true
        override fun setCrossfeed(enable:Boolean,mode:Int)=true
        override fun setCrossfeedCustom(enable:Boolean,fcut:Int,feed:Int)=true
        override fun setBassBoost(enable:Boolean,maxGain:Float)=true
        override fun setStereoEnhancement(enable:Boolean,level:Float)=true
        override fun setVacuumTube(enable:Boolean,level:Float)=true
        override fun setMultiEqualizerInternal(enable:Boolean,filterType:Int,interpolationMode:Int,bands:DoubleArray)=true
        override fun setVdcInternal(enable:Boolean,vdc:String)=true
        override fun setGraphicEqInternal(enable:Boolean,bands:String)=true
        override fun setLiveprogInternal(enable:Boolean,name:String,script:String)=true
        override fun supportsEelVmAccess()=false
        override fun supportsCustomCrossfeed()=false
        override fun enumerateEelVariables()=arrayListOf<EelVmVariable>()
        override fun manipulateEelVariable(name:String,value:Float)=false
        override fun freezeLiveprogExecution(freeze:Boolean){}
    }
}
