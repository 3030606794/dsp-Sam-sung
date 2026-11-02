package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspBaseEngine
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class EngineSyncIntegrationTest {
    @Test fun requestsWhileBusyProduceOnlyOneFollowupWithLatestCompletePreset() {
        val app=RuntimeEnvironment.getApplication()
        val prefs=PresetCrossfeedController.preferences(app)
        prefs.edit().clear().commit()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val followed=CountDownLatch(1)
        val calls=AtomicInteger();var finalSettings:PresetCrossfeedParameters.Settings?=null
        val engine=Probe(app) {
            if(calls.incrementAndGet()==1){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            else{finalSettings=PresetCrossfeedParameters.capture(prefs.all);followed.countDown()}
        }
        try {
            engine.syncWithPreferences()
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            repeat(50) { index ->
                val next=PresetCrossfeedParameters.baseSettings(index%6).copy(professional=true,delay=index*5)
                PresetCrossfeedParameters.apply(prefs,next)
                repeat(10){engine.syncWithPreferences()}
            }
            release.countDown()
            assertTrue(followed.await(5,TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(2,calls.get());assertEquals(1,finalSettings!!.base);assertEquals(245,finalSettings!!.delay)
        } finally {release.countDown();engine.close();prefs.edit().clear().commit()}
    }
    @Test fun closingBeforeQueuedSyncCannotEnterReleasedEngine() {
        val calls=AtomicInteger()
        val engine=Probe(RuntimeEnvironment.getApplication()) {calls.incrementAndGet()}
        engine.syncWithPreferences();engine.close();engine.syncWithPreferences()
        Thread.sleep(120)
        assertEquals(0,calls.get())
    }
    @Test fun forcedNamespaceSurvivesFailedConnectionGateUntilPlainRetrySucceeds() {
        val engine=Probe(RuntimeEnvironment.getApplication()) {}
        try {
            engine.allow=true
            var completed=CountDownLatch(1);engine.completed={completed.countDown()}
            engine.syncWithPreferences()
            assertTrue(completed.await(5,TimeUnit.SECONDS))
            val before=engine.outputs.get()
            assertEquals(1,before)
            val rejected=CountDownLatch(1)
            engine.allow=false;engine.observe={rejected.countDown()}
            engine.syncWithPreferences(arrayOf(Constants.PREF_OUTPUT))
            assertTrue(rejected.await(5,TimeUnit.SECONDS))
            Thread.sleep(60)
            assertEquals(before,engine.outputs.get())
            engine.allow=true;engine.observe={};completed=CountDownLatch(1)
            engine.syncWithPreferences()
            assertTrue(completed.await(5,TimeUnit.SECONDS))
            assertEquals(before+1,engine.outputs.get())
        } finally {engine.close()}
    }
    @Test fun rejectedParameterWriteIsRetriedWithoutAnotherPreferenceChange() {
        val engine=Probe(RuntimeEnvironment.getApplication()) {}
        try {
            engine.allow=true;engine.outputAccepted=false
            val failed=CountDownLatch(1);engine.failed={failed.countDown()}
            engine.syncWithPreferences()
            assertTrue(failed.await(5,TimeUnit.SECONDS))
            assertEquals(1,engine.outputs.get())
            engine.outputAccepted=true
            val completed=CountDownLatch(1);engine.completed={completed.countDown()}
            engine.syncWithPreferences()
            assertTrue(completed.await(5,TimeUnit.SECONDS))
            assertEquals(2,engine.outputs.get())
        } finally {engine.close()}
    }
    /** Exercises the real worker without Android native effects. */
    private class Probe(app:Application,var observe:()->Unit):JamesDspBaseEngine(app) {
        @Volatile var allow=false
        @Volatile var outputAccepted=true
        var completed:()->Unit={}
        var failed:()->Unit={}
        val outputs=AtomicInteger()
        override var enabled=false
        override fun beforePreferenceSync():Boolean {observe();return allow}
        override fun afterPreferenceSync(){completed()}
        override fun preferenceSyncFailed(){failed()}
        override fun setConvolverInternal(enable:Boolean,impulseResponse:FloatArray,irChannels:Int,irFrames:Int,irCrc:Int)=true
        override fun setOutputControl(threshold:Float,release:Float,postGain:Float):Boolean {outputs.incrementAndGet();return outputAccepted}
        override fun setReverb(enable:Boolean,preset:Int)=true
        override fun setCrossfeed(enable:Boolean,mode:Int)=true
        override fun setCrossfeedCustom(enable:Boolean,fcut:Int,feed:Int)=true
        override fun setBassBoost(enable:Boolean,maxGain:Float)=true
        override fun setStereoEnhancement(enable:Boolean,level:Float)=true
        override fun setVacuumTube(enable:Boolean,level:Float)=true
        override fun setMultiEqualizerInternal(enable:Boolean,filterType:Int,interpolationMode:Int,bands:DoubleArray)=true
        override fun setCompanderInternal(enable:Boolean,timeConstant:Float,granularity:Int,tfTransforms:Int,bands:DoubleArray)=true
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
