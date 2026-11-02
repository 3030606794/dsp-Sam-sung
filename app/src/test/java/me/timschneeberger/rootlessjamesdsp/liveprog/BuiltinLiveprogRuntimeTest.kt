package me.timschneeberger.rootlessjamesdsp.liveprog

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BuiltinLiveprogRuntimeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun source(name: String) = context.assets.open("Liveprog/$name").bufferedReader().use { it.readText() }

    @Test fun everyParameterizedStockScriptHasSeparateSliderRuntimeWithoutChangingSource() {
        var adapted = 0
        context.assets.list("Liveprog")!!.filter { it.endsWith(".eel") }.forEach { name ->
            val original = source(name)
            val runtime = BuiltinLiveprogRuntime.prepare(context, name, original)
            if (original.lineSequence().any(BuiltinLiveprogMetadata::isParameterLine)) {
                adapted++
                assertTrue(name, Regex("(?m)^@slider\\b").containsMatchIn(runtime))
                assertTrue(name, Regex("(?m)^@init\\b").containsMatchIn(runtime))
                assertTrue(name, Regex("(?m)^@sample\\b").containsMatchIn(runtime))
            } else assertEquals(name, original, runtime)
            assertEquals(original, source(name))
        }
        assertEquals(22, adapted)
    }

    @Test fun fineLiteralsAreCopiedExactlyWhileEditedCustomCodeIsLeftAlone() {
        val name = "gainControl.eel"
        val original = source(name)
        val fine = EelNumberRangeProperty.replaceVariable("dB", "1e-3", original)!!
        val runtime = BuiltinLiveprogRuntime.prepare(context, name, fine)
        assertTrue(runtime.contains("1e-3"))
        assertEquals(0.001f, EelNumberRangeProperty.findVariable("dB", runtime)!!, 0f)
        val edited = fine + "\n// custom implementation\n"
        assertEquals(edited, BuiltinLiveprogRuntime.prepare(context, name, edited))
    }
}
