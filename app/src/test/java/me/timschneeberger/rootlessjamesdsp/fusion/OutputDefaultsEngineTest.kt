package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspBaseEngine
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real asynchronous preference sync, without loading an Android native effect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OutputDefaultsEngineTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_OUTPUT, Context.MODE_PRIVATE)

    @Before fun setup() { prefs.edit().clear().commit() }
    @After fun cleanup() { prefs.edit().clear().commit() }

    @Test fun firstSyncActuallySendsOnePointFiveMsReleaseAndTheOtherDefaultsToTheProcessor() {
        assertProcessorValues(-0.1f, 1.5f, 0f)
        assertTrue("Fallback defaults do not overwrite profiles", prefs.all.isEmpty())
    }

    @Test fun firstSyncKeepsPreviouslySavedReleaseValuesInsteadOfMigratingThem() {
        listOf(60f, 350.5f).forEach { release ->
            prefs.edit().putFloat(app.getString(R.string.key_limiter_threshold), -6.25f)
                .putFloat(app.getString(R.string.key_limiter_release), release)
                .putFloat(app.getString(R.string.key_output_postgain), 1.3f).commit()
            val before = prefs.all.toMap()
            assertProcessorValues(-6.25f, release, 1.3f)
            assertEquals(before, prefs.all)
        }
    }

    private fun assertProcessorValues(threshold: Float, release: Float, gain: Float) {
        val engine = Probe(app)
        try {
            engine.syncWithPreferences()
            assertTrue("The real worker must call setOutputControl", engine.output.await(5, TimeUnit.SECONDS))
            assertArrayEquals(floatArrayOf(threshold, release, gain), engine.received.get(), 0f)
        } finally { engine.close() }
    }

    private class Probe(app: Application) : JamesDspBaseEngine(app) {
        val output = CountDownLatch(1)
        val received = AtomicReference<FloatArray>()
        override var enabled = false
        override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
            received.set(floatArrayOf(threshold, release, postGain))
            output.countDown()
            return true
        }
        override fun setConvolverInternal(enable: Boolean, impulseResponse: FloatArray, irChannels: Int, irFrames: Int, irCrc: Int) = true
        override fun setReverb(enable: Boolean, preset: Int) = true
        override fun setCrossfeed(enable: Boolean, mode: Int) = true
        override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int) = true
        override fun setBassBoost(enable: Boolean, maxGain: Float) = true
        override fun setStereoEnhancement(enable: Boolean, level: Float) = true
        override fun setVacuumTube(enable: Boolean, level: Float) = true
        override fun setMultiEqualizerInternal(enable: Boolean, filterType: Int, interpolationMode: Int, bands: DoubleArray) = true
        override fun setCompanderInternal(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: DoubleArray) = true
        override fun setVdcInternal(enable: Boolean, vdc: String) = true
        override fun setGraphicEqInternal(enable: Boolean, bands: String) = true
        override fun setLiveprogInternal(enable: Boolean, name: String, script: String) = true
        override fun supportsEelVmAccess() = false
        override fun supportsCustomCrossfeed() = false
        override fun enumerateEelVariables() = arrayListOf<EelVmVariable>()
        override fun manipulateEelVariable(name: String, value: Float) = false
        override fun freezeLiveprogExecution(freeze: Boolean) {}
    }
}
