package me.timschneeberger.rootlessjamesdsp.liveprog

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LiveprogDuplicateCleanerTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = app.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE)
    private lateinit var directory: File
    private val created = mutableListOf<File>()
    private val source = "desc: test\ngain:1<0,10,0.1>Gain\n@init\ngain=1;\n@sample\nspl0*=gain;\nspl1*=gain;\n"

    @Before fun prepare() {
        preferences.edit().clear().commit()
        directory = File(app.getExternalFilesDir(null), "Liveprog").apply { mkdirs() }
    }

    @After fun close() {
        created.forEach { it.delete() }
        directory.delete()
        preferences.edit().clear().commit()
    }

    private fun script(name: String, content: String = source, folder: File = directory): File =
        File(folder, name).also { it.writeText(content); created += it }

    @Test fun redundantNumberedCopiesDisappearWhileOriginalBytesAndPreferencesStayUntouched() {
        val original = script("8bandSplitting.eel")
        repeat(7) { script("8bandSplitting-编辑副本-${it + 1}.eel") }
        val before = preferences.all
        assertEquals(7, LiveprogDuplicateCleaner.clean(app, directory))
        assertEquals(listOf(original.name), directory.list()!!.toList())
        assertEquals(source, original.readText())
        assertEquals(before, preferences.all)
        assertEquals(0, LiveprogDuplicateCleaner.clean(app, directory))
    }

    @Test fun theSelectedCopyAndItsOriginalAreRetainedWithoutChangingSelection() {
        val original = script("8bandSplitting.eel")
        val active = script("8bandSplitting-编辑副本-2.eel")
        script("8bandSplitting-编辑副本-3.eel")
        preferences.edit().putString(app.getString(R.string.key_liveprog_file), "Liveprog/${active.name}").commit()
        val before = preferences.all
        assertEquals(1, LiveprogDuplicateCleaner.clean(app, directory))
        assertTrue(active.isFile)
        assertTrue(original.isFile)
        assertEquals(before, preferences.all)
    }

    @Test fun differentEditsSurviveAndOnlyTheRepeatedEditedContentIsRemoved() {
        script("8bandSplitting.eel")
        val first = script("8bandSplitting-编辑副本-1.eel", source + "// first edit\n")
        val second = script("8bandSplitting-编辑副本-2.eel", source + "// second edit\n")
        val repeated = script("8bandSplitting-编辑副本-3.eel", first.readText())
        assertEquals(1, LiveprogDuplicateCleaner.clean(app, directory))
        assertTrue(first.isFile)
        assertTrue(second.isFile)
        assertFalse(repeated.exists())
    }

    @Test fun identicalFilesWithDifferentSavedParameterChoicesAreNotDiscarded() {
        script("8bandSplitting.eel")
        val first = script("8bandSplitting-编辑副本-1.eel")
        val second = script("8bandSplitting-编辑副本-2.eel")
        val repeated = script("8bandSplitting-编辑副本-3.eel")
        listOf(first to "3", second to "4", repeated to "3").forEach { (file, value) ->
            val prefix = "liveprog_parameter_v1:${LiveprogParameterStore.identity(file.absolutePath, source)}:"
            preferences.edit().putString(prefix + "gain", value).commit()
        }
        val before = preferences.all
        assertEquals(1, LiveprogDuplicateCleaner.clean(app, directory))
        assertTrue(first.isFile)
        assertTrue(second.isFile)
        assertFalse(repeated.exists())
        assertEquals(before, preferences.all)
    }

    @Test fun unrelatedUserNamesAreNeverTreatedAsLegacyEditorCopies() {
        script("8bandSplitting.eel")
        val unrelated = script("My personal backup.eel")
        val other = script("another-script-2.eel")
        assertEquals(0, LiveprogDuplicateCleaner.clean(app, directory))
        assertTrue(unrelated.isFile)
        assertTrue(other.isFile)
    }

    @Test fun aGroupWithoutAnOriginalKeepsOneCompleteWorkingCopy() {
        val first = script("custom-编辑副本.eel", source + "// edited\n")
        script("custom-编辑副本-2.eel", first.readText())
        assertEquals(1, LiveprogDuplicateCleaner.clean(app, directory))
        assertTrue(first.isFile)
    }

    @Test fun cleanupCannotDeleteFilesOutsideTheAppsScriptLibrary() {
        val other = File(app.getExternalFilesDir(null), "OtherLibrary").apply { mkdirs() }
        try {
            val original = script("8bandSplitting.eel", folder = other)
            val copy = script("8bandSplitting-编辑副本-2.eel", folder = other)
            assertEquals(0, LiveprogDuplicateCleaner.clean(app, other))
            assertTrue(original.isFile)
            assertTrue(copy.isFile)
        } finally { created.filter { it.parentFile == other }.forEach { it.delete() }; other.delete() }
    }

    @Test fun repeatedEditorSavesKeepTheSameFileAndNeverGenerateNumberedCopies() {
        val edited = script("custom-编辑副本-2.eel")
        repeat(10) { index ->
            val parser = EelParser().apply { load(edited.absolutePath, skipParse = true) }
            parser.contents = source + "// saved $index\n"
            assertTrue(parser.save())
            assertEquals(edited.absolutePath, parser.path)
            assertEquals(listOf(edited.name), directory.list()!!.toList())
        }
        assertTrue(edited.readText().endsWith("// saved 9\n"))
    }
}
