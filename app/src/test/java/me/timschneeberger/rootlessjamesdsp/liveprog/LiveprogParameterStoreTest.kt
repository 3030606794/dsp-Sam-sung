package me.timschneeberger.rootlessjamesdsp.liveprog

import android.app.Application
import android.content.Context
import java.io.File
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.storage.Tar
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LiveprogParameterStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var script: File
    private val original = "desc:Portable\ngain:0<-1,1,0.001>Gain\n@init\ngain=0;\n@sample\nspl0*=gain; spl1*=gain;\n"
    @Before fun setup() {
        stopKoin()
        startKoin { modules(module { single<Context> { context } }) }
        context.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE).edit().clear().commit()
        script = File(context.getExternalFilesDir(null), "Liveprog/portable.eel").apply { parentFile!!.mkdirs(); writeText(original) }
    }
    @After fun cleanup() { script.delete(); stopKoin() }
    private fun save(value: Float) {
        val parser = EelParser().apply { load(script.absolutePath) }
        @Suppress("UNCHECKED_CAST")
        val property = parser.properties.single() as EelNumberRangeProperty<Float>
        property.value = value
        assertTrue(LiveprogParameterStore(context).save(parser, listOf(property)))
    }

    @Test fun exportedPresetEmbedsFineChoiceAndLeavesEditableSourceUnchanged() {
        context.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE).edit()
            .putString(context.getString(R.string.key_liveprog_file), "Liveprog/${script.name}").commit()
        save(0.001f)
        assertEquals("1052", Preset.minimumVersionCode(context))
        val preset = Preset("liveprog-fine.tar")
        assertTrue(preset.save())
        val folder = File(context.cacheDir, "exported-liveprog").apply { mkdirs() }
        assertNotNull(Tar.Reader(preset.file().inputStream()).extract(folder))
        val exported = File(folder, Preset.FILE_LIVEPROG)
        assertEquals(0.001f, EelNumberRangeProperty.findVariable("gain", exported.readText())!!, 0f)
        assertEquals(original, script.readText())
        // A receiving device has a different path and no matching overlay; the exported EEL carries the value.
        context.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE).edit().clear().commit()
        val received = EelParser().apply { load(exported.absolutePath); LiveprogParameterStore(context).applyTo(this) }
        assertEquals(0.001f, (received.properties.single() as EelNumberRangeProperty<*>).value.toFloat(), 0f)
    }

    @Test fun renamePreservesChoiceAndDifferentRevisionCannotInheritIt() {
        save(0.001f)
        val renamed = File(script.parentFile, "renamed.eel")
        assertTrue(script.renameTo(renamed))
        LiveprogParameterStore(context).relocate(script.absolutePath, renamed.absolutePath, original)
        script = renamed
        val parser = EelParser().apply { load(script.absolutePath); LiveprogParameterStore(context).applyTo(this) }
        assertEquals(0.001f, (parser.properties.single() as EelNumberRangeProperty<*>).value.toFloat(), 0f)
        script.appendText("\n// new revision\n")
        val edited = EelParser().apply { load(script.absolutePath); LiveprogParameterStore(context).applyTo(this) }
        assertEquals(0f, (edited.properties.single() as EelNumberRangeProperty<*>).value.toFloat(), 0f)
    }
}
