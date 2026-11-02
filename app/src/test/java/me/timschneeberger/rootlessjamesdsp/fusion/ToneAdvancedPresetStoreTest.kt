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
class ToneAdvancedPresetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var file: File
    private lateinit var store: ToneAdvancedPresetStore
    private val settings get() = ToneAdvancedParameters.capture(ToneAdvancedParameters.defaults().values() +
        ToneController.KEYS.mapIndexed { i, key -> key to (i * 7 - 31) }.toMap() +
        (ToneController.RANGE to "100"))
    @Before fun setup() {
        file = File(temporary.newFolder(), ToneAdvancedPresetStore.FILE_NAME)
        store = ToneAdvancedPresetStore(file)
    }
    private fun document(vararg names: String) = ToneAdvancedPresetCodec.encode(names.map {
        ToneAdvancedPreset(UUID.randomUUID().toString(), it, settings)
    })

    @Test fun saveRenameOverwriteDeleteAndReopenRetainIdentityAndCompleteParameters() {
        assertTrue(store.load().isEmpty()); assertFalse(file.exists())
        val saved = store.save("  温暖方案  ", settings)
        assertEquals("温暖方案", saved.name)
        assertEquals(listOf(saved), ToneAdvancedPresetStore(file).load())
        val renamed = store.rename(saved.id, "夜间")
        assertEquals(saved.id, renamed.id); assertEquals(settings, renamed.settings)
        val changed = ToneAdvancedParameters.capture(settings.values() + (ToneController.KEYS[0] to 73))
        val overwritten = store.overwrite(saved.id, changed)
        assertEquals(renamed.id, overwritten.id); assertEquals(renamed.name, overwritten.name)
        assertEquals(changed, overwritten.settings)
        val second = store.save("另一份", settings); store.delete(second.id)
        assertEquals(listOf(overwritten), ToneAdvancedPresetStore(file).load())
        val before = file.readBytes()
        listOf<() -> Unit>({ store.delete("missing") }, { store.rename("missing", "名称") },
            { store.overwrite("missing", settings) }, { store.export("missing") }).forEach {
            assertThrows(IllegalArgumentException::class.java) { it() }
            assertArrayEquals(before, file.readBytes())
        }
        store.delete(saved.id); assertTrue(ToneAdvancedPresetStore(file).load().isEmpty())
    }

    @Test fun capacityExportImportAndMutationsAtTwentyEntriesPreserveEveryOriginalBasis() {
        val saved = (1..20).map { index -> store.save("收藏 $index",
            ToneAdvancedParameters.capture(settings.values() + (ToneController.KEYS[0] to index))) }
        val target = ToneAdvancedPresetStore(File(temporary.newFolder(), ToneAdvancedPresetStore.FILE_NAME))
        assertEquals(20, target.importJson(store.export()))
        assertEquals(saved.map { it.name }, target.load().map { it.name })
        assertEquals(saved.map { it.settings }, target.load().map { it.settings })
        assertTrue(saved.map { it.id }.toSet().intersect(target.load().map { it.id }.toSet()).isEmpty())
        assertEquals(saved[3], ToneAdvancedPresetCodec.decode(store.export(saved[3].id)).single())
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save("第二十一份", settings) }
        assertThrows(IllegalArgumentException::class.java) { store.importJson(document("新方案")) }
        assertArrayEquals(before, file.readBytes())
        store.rename(saved.last().id, "第二十份"); store.overwrite(saved.last().id, settings)
        assertEquals(20, store.load().size)
    }

    @Test fun duplicateImportNamesGenerateNewIdentityWithoutReplacingFavoritesOrBreakingUnicode() {
        val original = store.save("温暖方案", settings)
        assertEquals(1, store.importJson(store.export()))
        assertEquals(1, store.importJson(document("温暖方案")))
        assertEquals(listOf("温暖方案", "温暖方案 (2)", "温暖方案 (3)"), store.load().map { it.name })
        assertEquals(original, store.load().first()); assertEquals(3, store.load().map { it.id }.toSet().size)
        val long = "名".repeat(35) + "🎵" + "余".repeat(3)
        assertEquals(40, long.length)
        store.save(long, settings); store.importJson(document(long))
        assertEquals("名".repeat(35) + " (2)", store.load().last().name)
        assertEquals(store.load().last().name, ToneAdvancedPresetCodec.name(store.load().last().name))
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.rename(original.id, long) }
        assertThrows(IllegalArgumentException::class.java) { store.save(" 温暖方案 ", settings) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun invalidSecondEntryRejectsWholeImportAndCapacityFailureNeverPartlyAddsEntries() {
        store.save("保留", settings)
        val root = Json.parseToJsonElement(document("一", "二")).jsonObject
        val entries = root["presets"]!!.jsonArray
        val second = entries[1].jsonObject
        val before = file.readBytes()
        listOf(mapOf(ToneController.KEYS[1] to JsonPrimitive(101)),
            mapOf(ToneController.KEYS[2] to JsonPrimitive("1")),
            mapOf(ToneController.RANGE to JsonPrimitive(100)),
            mapOf(ToneController.RANGE to JsonPrimitive("20")),
            mapOf("tone_pro_professional" to JsonPrimitive(1)),
            mapOf("tone_pro_unknown" to JsonPrimitive(0))).forEach { updates ->
            val invalidEntry = JsonObject(second + ("settings" to JsonObject(second["settings"]!!.jsonObject + updates)))
            val invalid = JsonObject(root + ("presets" to JsonArray(listOf(entries[0], invalidEntry)))).toString()
            assertThrows(IllegalArgumentException::class.java) { store.importJson(invalid) }
            assertArrayEquals(before, file.readBytes())
        }
        assertThrows(IllegalArgumentException::class.java) { store.importJson(document("重复", "重复")) }
        (2..19).forEach { store.save("保留 $it", settings) }
        val nineteen = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.importJson(document("新一", "新二")) }
        assertArrayEquals(nineteen, file.readBytes())
        assertEquals(1, store.importJson(document("新一"))); assertEquals(20, store.load().size)
    }

    @Test fun everyMutationRollsBackPartialAtomicWriteFailureToTheLastCommittedBytes() {
        store.save("原始一", settings); store.save("原始二", settings)
        val original = store.load(); val before = file.readBytes()
        val failing = ToneAdvancedPresetStore(file) { stream, bytes ->
            stream.write(bytes, 0, minOf(31, bytes.size)); throw IOException("Simulated partial disk write failure")
        }
        listOf<() -> Unit>({ failing.save("新", settings) }, { failing.rename(original.first().id, "改名") },
            { failing.overwrite(original.first().id, ToneAdvancedParameters.defaults()) },
            { failing.delete(original.first().id) }, { failing.importJson(document("导入")) }).forEach {
            assertThrows(IOException::class.java) { it() }
            assertArrayEquals(before, file.readBytes())
            assertEquals(original, ToneAdvancedPresetStore(file).load())
            assertFalse(File(file.path + ".new").exists())
        }
    }

    @Test fun corruptExistingFilesAreReportedAndCannotBeSilentlyReplacedBySaveOrImport() {
        val valid = document("已保存")
        listOf("{broken", valid.dropLast(1), valid.replace("\"version\":1", "\"version\":2")).forEach {
            file.writeText(it, Charsets.UTF_8); val before = file.readBytes()
            assertThrows(IllegalArgumentException::class.java) { store.load() }
            assertThrows(IllegalArgumentException::class.java) { store.save("新", settings) }
            assertThrows(IllegalArgumentException::class.java) { store.importJson(document("导入")) }
            assertArrayEquals(before, file.readBytes())
        }
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(IOException::class.java) { store.load() }
        assertThrows(IOException::class.java) { store.save("新", settings) }
    }

    @Test fun independentLibraryAndApplyingFavoritesPreserveEffectPowerOtherModulesAndUiWindow() {
        val app: Context = RuntimeEnvironment.getApplication()
        val directory = temporary.newFolder()
        val wrapped = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val favorites = ToneAdvancedPresetStore(wrapped)
        val timbreFavorites = TimbrePresetStore(wrapped)
        val otherFavorite = timbreFavorites.save("模拟音色收藏", TimbreParameters.defaults())
        val prefs = ToneController.preferences(app)
        val other = app.getSharedPreferences(TimbreParameters.NAMESPACE, Context.MODE_PRIVATE)
        other.edit().clear().putBoolean(TimbreParameters.ENABLED, true).commit()
        val otherBefore = other.all.toMap()
        prefs.edit().clear().putBoolean(ToneController.ENABLED, false).putInt("unrelated", 17)
            .putInt("local_window_scale", 20).commit()
        val before = prefs.all.toMap()
        val saved = favorites.save("独立收藏", settings)
        assertEquals(before, prefs.all); assertEquals(otherBefore, other.all)
        prefs.edit().clear().putBoolean(ToneController.ENABLED, true).putInt("unrelated", 73)
            .putInt("local_window_scale", 40).commit()
        assertEquals(listOf(saved), ToneAdvancedPresetStore(wrapped).load())
        ToneAdvancedParameters.apply(prefs, saved.settings)
        assertEquals(saved.settings, ToneAdvancedParameters.capture(prefs.all))
        assertTrue(prefs.getBoolean(ToneController.ENABLED, false)); assertEquals(73, prefs.getInt("unrelated", 0))
        assertEquals(40, prefs.getInt("local_window_scale", 0)); assertEquals(otherBefore, other.all)
        prefs.edit().putBoolean(ToneController.ENABLED, false).commit()
        ToneAdvancedParameters.apply(prefs, saved.settings)
        assertFalse(prefs.getBoolean(ToneController.ENABLED, true)); assertEquals(otherBefore, other.all)
        assertNotEquals(ToneAdvancedPresetStore.FILE_NAME, TimbrePresetStore.FILE_NAME)
        assertNotEquals(ToneAdvancedPresetStore.FILE_NAME, CompanderPresetStore.FILE_NAME)
        assertEquals(listOf(saved), ToneAdvancedPresetStore(wrapped).load())
        assertEquals(listOf(otherFavorite), TimbrePresetStore(wrapped).load())
    }
}
