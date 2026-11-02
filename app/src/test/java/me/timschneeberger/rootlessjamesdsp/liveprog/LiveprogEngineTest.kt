package me.timschneeberger.rootlessjamesdsp.liveprog

import android.app.Application
import android.content.Context
import java.io.File
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LiveprogEngineTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var script: File
    private lateinit var engine: TestEngine
    private val source = "desc:Fine\ngain:0<-1,1,0.001>Gain\n@init\ngain=0;\n@slider\nscale=gain;\n@sample\nspl0*=scale; spl1*=scale;\n"

    @Before fun setup() {
        script = File(context.getExternalFilesDir(null), "Liveprog/engine-test.eel").apply { parentFile!!.mkdirs(); writeText(source) }
        context.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE).edit().clear()
            .putString(context.getString(R.string.key_liveprog_file), "Liveprog/${script.name}")
            .putBoolean(context.getString(R.string.key_liveprog_enable), true).commit()
        engine = TestEngine(context)
    }
    @After fun cleanup() { engine.close(); script.delete() }
    private fun save(value: Float) {
        val parser = EelParser().apply { load(script.absolutePath) }
        @Suppress("UNCHECKED_CAST")
        val property = parser.properties.single() as EelNumberRangeProperty<Float>
        property.value = value
        assertTrue(LiveprogParameterStore(context).save(parser, listOf(property)))
    }

    @Test fun parameterEditsOnlyUseSetterAndSameSourceRecoveryReappliesSavedValue() {
        save(0.001f)
        assertTrue(engine.setLiveprog(true, script.absolutePath))
        assertEquals(listOf("gain" to 0.001f), engine.parameters)
        save(0.002f)
        engine.updateLiveprogParametersNow(script.absolutePath)
        assertEquals(1, engine.loads)
        assertEquals("gain" to 0.002f, engine.parameters.last())
        assertEquals(source, script.readText())
        engine.resetRuntime()
        assertTrue(engine.setLiveprog(true, script.absolutePath))
        assertEquals(3, engine.parameters.size)
        assertEquals("gain" to 0.002f, engine.parameters.last())
    }

    @Test fun inactiveSelectionChangedSourceAndRejectedCompileCannotWriteIntoPreviousVm() {
        assertTrue(engine.setLiveprog(true, script.absolutePath))
        save(0.001f)
        context.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE).edit()
            .putString(context.getString(R.string.key_liveprog_file), "Liveprog/other.eel").commit()
        engine.updateLiveprogParametersNow(script.absolutePath)
        assertTrue(engine.parameters.isEmpty())
        context.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE).edit()
            .putString(context.getString(R.string.key_liveprog_file), "Liveprog/${script.name}").commit()
        script.appendText("\n// changed revision\n")
        save(0.002f)
        engine.updateLiveprogParametersNow(script.absolutePath)
        assertTrue(engine.parameters.isEmpty())
        engine.acceptCompile = false
        assertFalse(engine.setLiveprog(true, script.absolutePath))
        engine.updateLiveprogParametersNow(script.absolutePath)
        assertTrue(engine.parameters.isEmpty())
    }

    @Test fun unsupportedDriverKeepsSavedChoiceAndNeverFallsBackToSourceReloadOnEdit() {
        engine.supported = false
        save(0.001f)
        assertTrue(engine.setLiveprog(true, script.absolutePath))
        engine.updateLiveprogParametersNow(script.absolutePath)
        assertEquals(1, engine.loads)
        assertTrue(engine.parameters.isEmpty())
        assertEquals(source, script.readText())
    }

    @Test fun sourceSavedDuringCompileCannotApplyNewRevisionChoicesToOldVm() {
        save(0.001f)
        engine.onCompile = {
            script.appendText("\n// editor saved while upload was running\n")
            save(0.002f)
        }
        assertTrue(engine.setLiveprog(true, script.absolutePath))
        assertEquals(listOf("gain" to 0.001f), engine.parameters)
        engine.updateLiveprogParametersNow(script.absolutePath)
        assertEquals(1, engine.parameters.size)
    }

    private class TestEngine(context: Context) : JamesDspBaseEngine(context) {
        override var enabled = true
        var supported = true
        var acceptCompile = true
        var loads = 0
        var onCompile: (() -> Unit)? = null
        val parameters = mutableListOf<Pair<String, Float>>()
        fun resetRuntime() = invalidateLiveprogParameters()
        override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean { loads++; onCompile?.invoke(); return acceptCompile }
        override fun supportsEelVmAccess() = supported
        override fun supportsCustomCrossfeed() = false
        override fun enumerateEelVariables() = arrayListOf<EelVmVariable>()
        override fun manipulateEelVariable(name: String, value: Float): Boolean { parameters.add(name to value); return true }
        override fun freezeLiveprogExecution(freeze: Boolean) {}
        override fun setOutputControl(threshold: Float, release: Float, postGain: Float) = true
        override fun setReverb(enable: Boolean, preset: Int) = true
        override fun setCrossfeed(enable: Boolean, mode: Int) = true
        override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int) = true
        override fun setBassBoost(enable: Boolean, maxGain: Float) = true
        override fun setStereoEnhancement(enable: Boolean, level: Float) = true
        override fun setVacuumTube(enable: Boolean, level: Float) = true
        override fun setMultiEqualizerInternal(enable: Boolean, filterType: Int, interpolationMode: Int, bands: DoubleArray) = true
        override fun setCompanderInternal(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: DoubleArray) = true
        override fun setVdcInternal(enable: Boolean, vdc: String) = true
        override fun setConvolverInternal(enable: Boolean, impulseResponse: FloatArray, irChannels: Int, irFrames: Int, irCrc: Int) = true
        override fun setGraphicEqInternal(enable: Boolean, bands: String) = true
    }
}
