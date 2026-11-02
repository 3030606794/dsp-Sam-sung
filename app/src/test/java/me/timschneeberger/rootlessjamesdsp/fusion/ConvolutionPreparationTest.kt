package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import me.timschneeberger.rootlessjamesdsp.convolver.ConvolverLibrary
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspBaseEngine
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ConvolutionPreparationTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=ConvolutionController.preferences(app)
    private lateinit var engine: FakeEngine
    @Before fun prepare() {
        prefs.edit().clear().putBoolean(ConvolutionParameters.ENABLED,true).commit();ConvolverLibrary.install(app)
        ConvolutionController.publishPrepared(app,null);engine=FakeEngine(app).apply {sampleRate=48000f}
    }
    @After fun cleanup(){engine.close();ConvolutionController.publishPrepared(app,null)}
    private fun apply()=engine.setConvolver(prefs.getBoolean(ConvolutionParameters.ENABLED,false),prefs.getString(ConvolutionParameters.FILE,"")!!,0,ConvolutionParameters.LEGACY_DEFAULT)
    @Test fun runtimeAndRepeatedSynchronizationReuseDecodedAndAnalyzedKernel() {
        assertTrue(apply());val original=engine.bound
        assertEquals(1,engine.decodes);assertEquals(ConvolutionController.Preparation.READY,ConvolutionController.prepared!!.status)
        prefs.edit().putInt(ConvolutionParameters.mix.key,500).putInt(ConvolutionParameters.wet.key,30).commit()
        assertTrue(apply());assertTrue(apply());assertEquals(1,engine.decodes);assertSame(original,engine.bound)
        prefs.edit().putInt("conv1_norm_mode",1).putBoolean("conv1_norm_attenuate_only",false).commit()
        assertTrue(apply());assertEquals(2,engine.decodes);assertNotSame(original,engine.bound)
    }
    @Test fun aNewPreferenceDuringDecodeNeverCommitsSupersededKernel() {
        engine.onDecode={prefs.edit().putInt("conv1_norm_mode",1).commit()}
        assertTrue(apply());assertEquals(0,engine.binds)
        engine.onDecode={};assertTrue(apply());assertEquals(1,engine.binds)
    }
    @Test fun invalidWindowRetainsLastConfirmedKernelAndShowsPreparationFailure() {
        assertTrue(apply());val original=engine.bound;val binds=engine.binds
        prefs.edit().putBoolean("conv1_window_enabled",true).putInt("conv1_start_us",200000).commit()
        assertFalse(apply());assertEquals(binds,engine.binds);assertSame(original,engine.bound)
        assertEquals(ConvolutionController.Preparation.FAILED,ConvolutionController.prepared!!.status)
    }
    @Test fun cancelledCachedBindCannotReplaceANewerPreparationStatusWithOldReady() {
        assertTrue(apply())
        engine.onBind={prefs.edit().putInt("conv1_norm_mode",1).commit()
            ConvolutionController.publishPrepared(app,ConvolutionController.prepared!!.copy(status=ConvolutionController.Preparation.PROCESSING))}
        assertTrue(apply());assertEquals(ConvolutionController.Preparation.PROCESSING,ConvolutionController.prepared!!.status)
    }
    @Test fun moduleOffDoesNotDecodeAndCloseCannotUploadAnotherKernel() {
        prefs.edit().putBoolean(ConvolutionParameters.ENABLED,false).commit()
        assertTrue(apply());assertEquals(0,engine.decodes);assertFalse(engine.lastEnabled)
        engine.close();prefs.edit().putBoolean(ConvolutionParameters.ENABLED,true).commit()
        assertFalse(apply());assertEquals(0,engine.decodes)
    }
    @Test fun changedProcessingRateRebuildsKernelAndSourceBytesStayUnchanged() {
        val source=java.io.File(app.getExternalFilesDir(null),ConvolverLibrary.DEFAULT_PATH);val hash=ConvolverLibrary.sha256(source)
        assertTrue(apply());engine.sampleRate=44100f;assertTrue(apply());assertEquals(2,engine.decodes)
        assertEquals(44100,ConvolutionController.prepared!!.processingSampleRate)
        assertEquals(hash,ConvolverLibrary.sha256(source))
    }
    @Test fun secondRoundMatrixAndNormalizationUseWorkCopiesAndKeepOriginalFile() {
        ConvolutionController.publishCapabilities(app,16383)
        val p=ConvolutionAdvancedParameters
        val source=java.io.File(app.getExternalFilesDir(null),ConvolverLibrary.DEFAULT_PATH);val hash=ConvolverLibrary.sha256(source)
        prefs.edit().putBoolean(p.MATRIX,true).putInt(p.gain[0].key,60).putInt(ConvolutionParameters.NORM_MODE,1).commit()
        assertTrue(apply());assertEquals(Math.pow(10.0,-1.0/20),engine.bound.max().toDouble(),1e-7)
        assertEquals(hash,ConvolverLibrary.sha256(source));assertEquals(2,ConvolutionController.prepared!!.sourceChannels)
    }
    @Test fun secondRoundFractionalTimingIsPreparedBeforeBindingAndOldDriverIgnoresSavedExtensions() {
        val p=ConvolutionAdvancedParameters
        ConvolutionController.publishCapabilities(app,16383)
        prefs.edit().putBoolean(p.FRACTIONAL,true).putInt(p.METHOD,1).putInt(p.delay[0].key,10).commit()
        assertTrue(apply());assertEquals(16,ConvolutionController.prepared!!.commonDelaySamples);assertTrue(engine.bound.size>128)
        ConvolutionController.publishCapabilities(app,8191);assertTrue(apply());assertEquals(0,ConvolutionController.prepared!!.commonDelaySamples)
        assertEquals(128,engine.bound.size);assertTrue(prefs.getBoolean(p.FRACTIONAL,false))
    }
    @Test fun deepAnalysisAndRealtimeFilterEditsDoNotRepeatedlyDecodeOrRebuildTheKernel() {
        val p=ConvolutionAdvancedParameters
        ConvolutionController.publishCapabilities(app,16383);prefs.edit().putBoolean(p.ANALYSIS,true).commit()
        assertTrue(apply());val samples=engine.bound;val data=ConvolutionController.prepared!!.advancedAnalysis
        assertNotNull(data);assertEquals(1,engine.decodes)
        prefs.edit().putInt(p.hp.key,500).putBoolean(p.enabledFilter(0),true).putInt(p.GRAPH,2).commit()
        assertTrue(apply());assertSame(samples,engine.bound);assertSame(data,ConvolutionController.prepared!!.advancedAnalysis);assertEquals(1,engine.decodes)
    }
    @Test fun rejectedDriverConfigurationRetainsLastKernelAndReportsFailureInsteadOfStayingProcessing() {
        ConvolutionController.publishCapabilities(app,16383);assertTrue(apply());val original=engine.bound;val binds=engine.binds
        engine.runtimeConfigured=false;prefs.edit().putBoolean(ConvolutionAdvancedParameters.MATRIX,true).putInt(ConvolutionAdvancedParameters.gain[0].key,30).commit()
        assertFalse(apply());assertSame(original,engine.bound);assertEquals(binds,engine.binds)
        assertEquals(ConvolutionController.Preparation.FAILED,ConvolutionController.prepared!!.status)
        engine.runtimeConfigured=true;assertTrue(apply());assertNotSame(original,engine.bound);assertEquals(ConvolutionController.Preparation.READY,ConvolutionController.prepared!!.status)
    }
    @Test fun noProcessingRateWaitsWithoutDecodeOrPowerWriteThenActivatesAtTheActualRate() {
        engine.sampleRate=0f
        assertTrue(apply());assertEquals(0,engine.decodes);assertEquals(0,engine.binds)
        assertTrue(prefs.getBoolean(ConvolutionParameters.ENABLED,false))
        assertEquals(ConvolutionController.Preparation.WAITING,ConvolutionController.prepared!!.status)
        engine.sampleRate=48000f
        assertTrue(apply());assertEquals(1,engine.binds)
        assertEquals(ConvolutionController.Preparation.READY,ConvolutionController.prepared!!.status)
    }
    @Test fun stagedKernelKeepsItsAnalysisAndLaterConfirmsFromTheSameCachedWorkCopy() {
        engine.transferPending=true
        assertTrue(apply());val original=engine.bound;val staged=ConvolutionController.prepared!!
        assertEquals(ConvolutionController.Preparation.STAGED,staged.status);assertTrue(staged.hasWorkingKernel)
        assertTrue(staged.frequencyHz.isNotEmpty())
        assertTrue(apply());assertSame(original,engine.bound);assertEquals(1,engine.decodes)
        engine.transferPending=false
        assertTrue(apply());assertSame(original,engine.bound);assertEquals(1,engine.decodes)
        assertEquals(ConvolutionController.Preparation.READY,ConvolutionController.prepared!!.status)
    }
    @Test fun cachedAndNewTransferFailuresPublishTheSameFailureState() {
        assertTrue(apply());engine.bindSucceeded=false
        assertFalse(apply());assertEquals(ConvolutionController.Preparation.FAILED,ConvolutionController.prepared!!.status)
        prefs.edit().putInt(ConvolutionParameters.NORM_MODE,1).commit()
        assertFalse(apply());assertEquals(ConvolutionController.Preparation.FAILED,ConvolutionController.prepared!!.status)
        assertEquals(2,engine.decodes)
    }
    @Test fun closingDuringTheNativeBindCannotPublishAnObsoleteReadyState() {
        engine.onBind={engine.close()}
        assertTrue(apply())
        assertEquals(ConvolutionController.Preparation.PROCESSING,ConvolutionController.prepared!!.status)
    }
    @Test fun rejectedSupersededBindCannotPublishAnErrorForTheNewSelection() {
        engine.bindSucceeded=false
        engine.onBind={prefs.edit().putInt(ConvolutionParameters.NORM_MODE,1).commit()}
        assertTrue(apply())
        assertEquals(ConvolutionController.Preparation.PROCESSING,ConvolutionController.prepared!!.status)
    }
    @Test fun terminalIsolatedTransferFailureReportsIrFailureWithoutFailingTheWholePreferenceSync() {
        engine.bindSucceeded=false;engine.failureIsolated=true
        assertTrue(apply());assertEquals(ConvolutionController.Preparation.FAILED,ConvolutionController.prepared!!.status)
        assertTrue(prefs.getBoolean(ConvolutionParameters.ENABLED,false))
        // A later explicit retry can still confirm the cached work copy.
        engine.bindSucceeded=true;engine.failureIsolated=false
        assertTrue(apply());assertEquals(ConvolutionController.Preparation.READY,ConvolutionController.prepared!!.status)
        assertEquals(1,engine.decodes)
    }
    @Test fun referenceNormalizationIsReusedAcrossRateChangesAndFinalCrcMatchesTheBoundKernel() {
        prefs.edit().putInt(ConvolutionParameters.NORM_MODE,1).putBoolean(ConvolutionParameters.NORM_ONLY_ATTENUATION,false).commit()
        engine.decodeSamples={rate->
            val repeat=rate/48000
            FloatArray(64*repeat*2) {i->if(i/2/repeat==3)if(i%2==0).5f else .25f else 0f}
        }
        assertTrue(apply());val reference=engine.bound.copyOf();assertEquals(1,engine.decodes)
        engine.sampleRate=192000f;assertTrue(apply())
        assertEquals(2,engine.decodes) // The original-rate reference gain is cached, not decoded again.
        assertEquals(reference.filterIndexed {i,_->i%2==0}.sum(),engine.bound.filterIndexed {i,_->i%2==0}.sum(),1e-6f)
        assertEquals(me.timschneeberger.rootlessjamesdsp.interop.ConvolutionIrPreprocessor.crc(engine.bound),engine.boundCrc)
        assertEquals(engine.boundCrc,ConvolutionController.prepared!!.crc)
        assertEquals(-12.041199826559248,ConvolutionController.prepared!!.sampleRateCompensationDb,1e-12)
        assertTrue(apply());assertEquals(2,engine.decodes)
    }
    @Test fun cancellationDuringReferenceDecodeCannotBindAnOldSelection() {
        engine.sampleRate=192000f
        prefs.edit().putInt(ConvolutionParameters.NORM_MODE,1).commit()
        engine.onDecode={if(engine.decodes==2)prefs.edit().putInt(ConvolutionParameters.mix.key,500).putInt(ConvolutionParameters.normTarget.key,-20).commit()}
        assertTrue(apply());assertEquals(2,engine.decodes);assertEquals(0,engine.binds)
    }
    private class FakeEngine(app: Application): JamesDspBaseEngine(app) {
        override var enabled=false
        var runtimeConfigured=true
        var transferPending=false
        var bindSucceeded=true
        var failureIsolated=false
        override fun convolutionTransferPending()=transferPending
        override fun convolutionFailureIsolated()=failureIsolated
        override fun configureConvolutionRuntime(prepared: ConvolutionController.PreparedIr)=runtimeConfigured
        var decodes=0;var binds=0;var bound=FloatArray(0);var boundCrc=0;var lastEnabled=false;var onDecode: ()->Unit={};var onBind: ()->Unit={}
        var decodeSamples: ((Int)->FloatArray)?=null
        override fun decodeConvolutionIr(path: String,rate: Int,info: IntArray,mode: Int,legacy: IntArray): FloatArray {
            val samples=decodeSamples?.invoke(rate) ?: FloatArray(128).apply {this[6]=.5f;this[7]=.25f}
            decodes++;info[0]=2;info[1]=samples.size/2;info[2]=123;info[3]=1;if(info.size>4)info[4]=48000;onDecode()
            return samples
        }
        override fun setConvolverInternal(enable: Boolean,impulseResponse: FloatArray,irChannels: Int,irFrames: Int,irCrc: Int): Boolean {binds++;bound=impulseResponse;boundCrc=irCrc;lastEnabled=enable;onBind();return bindSucceeded}
        override fun setOutputControl(threshold: Float,release: Float,postGain: Float)=true
        override fun setReverb(enable: Boolean,preset: Int)=true
        override fun setCrossfeed(enable: Boolean,mode: Int)=true
        override fun setCrossfeedCustom(enable: Boolean,fcut: Int,feed: Int)=true
        override fun setBassBoost(enable: Boolean,maxGain: Float)=true
        override fun setStereoEnhancement(enable: Boolean,level: Float)=true
        override fun setVacuumTube(enable: Boolean,level: Float)=true
        override fun setMultiEqualizerInternal(enable: Boolean,filterType: Int,interpolationMode: Int,bands: DoubleArray)=true
        override fun setCompanderInternal(enable: Boolean,timeConstant: Float,granularity: Int,tfTransforms: Int,bands: DoubleArray)=true
        override fun setVdcInternal(enable: Boolean,vdc: String)=true
        override fun setGraphicEqInternal(enable: Boolean,bands: String)=true
        override fun setLiveprogInternal(enable: Boolean,name: String,script: String)=true
        override fun supportsEelVmAccess()=false
        override fun supportsCustomCrossfeed()=false
        override fun enumerateEelVariables()=arrayListOf<EelVmVariable>()
        override fun manipulateEelVariable(name: String,value: Float)=false
        override fun freezeLiveprogExecution(freeze: Boolean){}
    }
}
