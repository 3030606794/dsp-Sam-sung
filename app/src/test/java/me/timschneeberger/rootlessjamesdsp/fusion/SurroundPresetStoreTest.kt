package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.serialization.json.*
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowPosixAtomicFile::class])
class SurroundPresetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var file: File
    private lateinit var store: SurroundPresetStore
    private val settings = SurroundController.Settings(317, 13851, 8753, 97)

    @Before fun setup() {
        file = File(temporary.newFolder(), SurroundPresetStore.FILE_NAME)
        store = SurroundPresetStore(file)
    }

    private fun portable(vararg names: String) = SurroundPresetCodec.encode(names.map {
        SurroundPreset(UUID.randomUUID().toString(), it, settings)
    })

    @Test fun saveRenameOverwriteDeleteAndReopenPreserveIdentityAndAllFourParameters() {
        assertTrue(store.load().isEmpty())
        assertFalse(file.exists())
        val saved = store.save("  我的扬声器  ", settings)
        assertEquals("我的扬声器", saved.name)
        assertEquals(listOf(saved), SurroundPresetStore(file).load())
        val renamed = store.rename(saved.id, "近场")
        assertEquals(saved.id, renamed.id)
        assertEquals(settings, renamed.settings)
        val next = settings.copy(attenuationMilliDb = 5225, delayUs = 49)
        val updated = store.overwrite(saved.id, next)
        assertEquals(renamed.id, updated.id)
        assertEquals(renamed.name, updated.name)
        assertEquals(next, updated.settings)
        val second = store.save("桌面", settings)
        store.delete(second.id)
        assertEquals(listOf(updated), store.load())
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.delete("missing") }
        assertArrayEquals(before, file.readBytes())
        store.delete(updated.id)
        assertTrue(SurroundPresetStore(file).load().isEmpty())
    }

    @Test fun twentyEntriesExportAndImportAndCapacityDoesNotBlockRenamingOrOverwriting() {
        val saved = (1..20).map { store.save("收藏 $it", settings.copy(delayUs = 20 + it)) }
        val bundle = store.exportJson()
        val target = SurroundPresetStore(File(temporary.newFolder(), "import.json"))
        val added = target.importJson(bundle)
        assertEquals(saved.map { it.settings }, added.map { it.settings })
        assertEquals(saved.map { it.name }, added.map { it.name })
        assertTrue(saved.map { it.id }.toSet().intersect(added.map { it.id }.toSet()).isEmpty())
        assertEquals(saved[3].settings, SurroundPresetCodec.decode(store.exportJson(saved[3].id)).single().settings)
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save("第21个", settings) }
        assertThrows(IllegalArgumentException::class.java) { store.importJson(portable("导入")) }
        assertArrayEquals(before, file.readBytes())
        store.rename(saved.last().id, "二十")
        store.overwrite(saved.last().id, settings)
        assertEquals(20, store.load().size)
    }

    @Test fun duplicateImportNamesGetSuffixesWithoutReplacingSavedDataOrSplittingUnicode() {
        val first = store.save("扬声器", settings)
        assertEquals(listOf("扬声器 (2)", "扬声器 (3)", "扬声器 (4)"),
            store.importJson(portable("扬声器", "扬声器", "扬声器")).map { it.name })
        assertEquals(first, store.load().first())
        val name = "名".repeat(35) + "\uD83C\uDFB5" + "余".repeat(3)
        assertEquals(40, name.length)
        store.save(name, settings)
        val imported = store.importJson(portable(name)).single()
        assertEquals("名".repeat(35) + " (2)", imported.name)
        assertEquals(imported.name, SurroundPresetCodec.normalizeName(imported.name))
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.rename(first.id, name) }
        assertThrows(IllegalArgumentException::class.java) { store.save(" 扬声器 ", settings) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun anyInvalidItemOrInsufficientCapacityRejectsWholeImportBeforeWriting() {
        store.save("保留", settings)
        val root = Json.parseToJsonElement(portable("一", "二")).jsonObject
        val entries = root["presets"]!!.jsonArray
        val second = entries[1].jsonObject
        val invalidSettings = JsonObject(second["settings"]!!.jsonObject + ("delayUs" to JsonPrimitive(999)))
        val invalidSecond = JsonObject(second + ("settings" to invalidSettings))
        val broken = JsonObject(root + ("presets" to JsonArray(listOf(entries[0], invalidSecond)))).toString()
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.importJson(broken) }
        assertArrayEquals(before, file.readBytes())
        (2..19).forEach { store.save("保留 $it", settings) }
        val nineteen = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.importJson(portable("新一", "新二")) }
        assertArrayEquals(nineteen, file.readBytes())
        assertEquals(1, store.importJson(portable("新一")).size)
        assertEquals(20, store.load().size)
    }

    @Test fun atomicWriteFailureRollsBackEveryMutationAndKeepsPreviousBytes() {
        store.save("原始一", settings)
        store.save("原始二", settings)
        val original = store.load()
        val bytes = file.readBytes()
        val failing = SurroundPresetStore(file) { stream, content ->
            stream.write(content, 0, minOf(31, content.size))
            throw IOException("Simulated partial disk write failure")
        }
        listOf<() -> Unit>(
            { failing.save("新", settings) },
            { failing.rename(original[0].id, "新名称") },
            { failing.overwrite(original[0].id, settings.copy(delayUs = 140)) },
            { failing.delete(original[0].id) },
            { failing.importJson(portable("导入")) }
        ).forEach {
            assertThrows(IOException::class.java) { it() }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(original, SurroundPresetStore(file).load())
            assertFalse(File(file.path + ".new").exists())
        }
    }

    @Test fun corruptLocalFileIsReportedAndNeverOverwrittenByASaveOrImport() {
        file.writeText("{broken", Charsets.UTF_8)
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.load() }
        assertThrows(IllegalArgumentException::class.java) { store.save("新", settings) }
        assertThrows(IllegalArgumentException::class.java) { store.importJson(portable("导入")) }
        assertArrayEquals(before, file.readBytes())
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(IOException::class.java) { store.load() }
    }

    @Test fun favoritesSurviveDspPreferenceReplacementAndApplyingOnlyWritesCurrentSlotsFourValues() {
        val app: Context = RuntimeEnvironment.getApplication()
        val directory = temporary.newFolder()
        val wrapped = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val favorites = SurroundPresetStore(wrapped)
        val prefs = SurroundController.preferences(app)
        prefs.edit().clear().putBoolean(SurroundController.ENABLED, false)
            .putInt(SurroundController.SELECTED, 3).commit()
        val before = prefs.all.toMap()
        val favorite = favorites.save("收藏", settings)
        assertEquals(before, prefs.all)
        prefs.edit().clear().putBoolean(SurroundController.ENABLED, true)
            .putInt(SurroundController.SELECTED, 5).putInt("slot_1_delay", 108).commit()
        assertEquals(listOf(favorite), SurroundPresetStore(wrapped).load())
        assertEquals(favorite, favorites.applyToCurrentSlot(app, favorite.id))
        assertEquals(settings, SurroundController.settings(app))
        assertEquals(5, SurroundController.selected(app))
        assertTrue(SurroundController.enabled(app))
        assertEquals(108, SurroundController.settings(app, 1).delayUs)
        val after = prefs.all.toMap()
        assertThrows(IllegalArgumentException::class.java) { favorites.applyToCurrentSlot(app, "missing") }
        assertEquals(after, prefs.all)
        assertTrue(File(directory, SurroundPresetStore.FILE_NAME).exists())
    }
}
