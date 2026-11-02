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
class DdcPresetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var file: File
    private lateinit var store: DdcPresetStore
    private val settings get() = DdcTestFixture.settings
    private val foundation get() = DdcTestFixture.foundation
    @Before fun setup() { file = File(temporary.newFolder(), DdcPresetStore.FILE_NAME); store = DdcPresetStore(file) }
    private fun document(vararg names: String) = DdcPresetCodec.encode(names.map {
        DdcPreset(UUID.randomUUID().toString(), it, foundation, settings)
    })

    @Test fun saveRenameOverwriteDeleteAndReopenRetainIdentityEmbeddedFileAndParameters() {
        assertTrue(store.load().isEmpty()); assertFalse(file.exists())
        val saved = store.save("  温暖方案  ", foundation, settings)
        assertEquals("温暖方案", saved.name); assertEquals(listOf(saved), DdcPresetStore(file).load())
        val renamed = store.rename(saved.id, "夜间")
        assertEquals(saved.id, renamed.id); assertEquals(foundation, renamed.foundation); assertEquals(settings, renamed.settings)
        val changed = settings.copy(ratio = 317)
        val changedFoundation = DdcFoundation.from("DDC/new.vdc", "新校正", DdcVdc.parse(DdcTestFixture.TEXT.replace("1,0,0", "0.5,0,0")))
        val overwritten = store.overwrite(saved.id, changedFoundation, changed)
        assertEquals(renamed.id, overwritten.id); assertEquals(renamed.name, overwritten.name)
        assertEquals(changed, overwritten.settings); assertEquals(changedFoundation, overwritten.foundation)
        val second = store.save("另一份", foundation, settings); store.delete(second.id)
        assertEquals(listOf(overwritten), DdcPresetStore(file).load())
        val before = file.readBytes()
        listOf<() -> Unit>({ store.delete("missing") }, { store.rename("missing", "名称") },
            { store.overwrite("missing", foundation, settings) }, { store.export("missing") }).forEach {
            assertThrows(IllegalArgumentException::class.java) { it() }; assertArrayEquals(before, file.readBytes())
        }
        store.delete(saved.id); assertTrue(DdcPresetStore(file).load().isEmpty())
    }

    @Test fun twentyEntryCapacityExportImportAndMutationsKeepEveryEmbeddedFoundation() {
        val saved = (1..20).map { index -> store.save("收藏 $index", foundation, settings.copy(ratio = index * 31)) }
        val target = DdcPresetStore(File(temporary.newFolder(), DdcPresetStore.FILE_NAME))
        assertEquals(20, target.importJson(store.export()))
        assertEquals(saved.map { it.name }, target.load().map { it.name })
        assertEquals(saved.map { it.settings }, target.load().map { it.settings })
        assertEquals(saved.map { it.foundation }, target.load().map { it.foundation })
        assertTrue(saved.map { it.id }.toSet().intersect(target.load().map { it.id }.toSet()).isEmpty())
        assertEquals(saved[3], DdcPresetCodec.decode(store.export(saved[3].id)).single())
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save("第二十一份", foundation, settings) }
        assertThrows(IllegalArgumentException::class.java) { store.importJson(document("新方案")) }
        assertArrayEquals(before, file.readBytes())
        store.rename(saved.last().id, "第二十份"); store.overwrite(saved.last().id, foundation, settings)
        assertEquals(20, store.load().size)
    }

    @Test fun duplicateImportNamesReceiveNewIdsWithoutReplacingFavoritesOrBreakingUnicode() {
        val original = store.save("温暖方案", foundation, settings)
        assertEquals(1, store.importJson(store.export())); assertEquals(1, store.importJson(document("温暖方案")))
        assertEquals(listOf("温暖方案", "温暖方案 (2)", "温暖方案 (3)"), store.load().map { it.name })
        assertEquals(original, store.load().first()); assertEquals(3, store.load().map { it.id }.toSet().size)
        val long = "名".repeat(35) + "🎵" + "余".repeat(3)
        store.save(long, foundation, settings); store.importJson(document(long))
        assertEquals("名".repeat(35) + " (2)", store.load().last().name)
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.rename(original.id, long) }
        assertThrows(IllegalArgumentException::class.java) { store.save(" 温暖方案 ", foundation, settings) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun invalidSecondEntryRejectsWholeImportAndCapacityNeverAddsPartialData() {
        store.save("保留", foundation, settings)
        val root = Json.parseToJsonElement(document("一", "二")).jsonObject
        val entries = root["presets"]!!.jsonArray; val second = entries[1].jsonObject; val before = file.readBytes()
        listOf(mapOf(DdcParameters.ratio.key to JsonPrimitive(1001)), mapOf(DdcParameters.lowGain.key to JsonPrimitive("1")),
            mapOf(DdcParameters.PROFESSIONAL to JsonPrimitive(1)), mapOf("ddc_pro_unknown" to JsonPrimitive(0))).forEach { updates ->
            val invalidEntry = JsonObject(second + ("settings" to JsonObject(second["settings"]!!.jsonObject + updates)))
            val invalid = JsonObject(root + ("presets" to JsonArray(listOf(entries[0], invalidEntry)))).toString()
            assertThrows(IllegalArgumentException::class.java) { store.importJson(invalid) }; assertArrayEquals(before, file.readBytes())
        }
        val wrongFoundation = JsonObject(second + ("foundation" to JsonObject(second["foundation"]!!.jsonObject + ("sha256" to JsonPrimitive("0".repeat(64))))))
        assertThrows(IllegalArgumentException::class.java) { store.importJson(JsonObject(root + ("presets" to JsonArray(listOf(entries[0], wrongFoundation)))).toString()) }
        assertArrayEquals(before, file.readBytes())
        (2..19).forEach { store.save("保留 $it", foundation, settings) }; val nineteen = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.importJson(document("新一", "新二")) }
        assertArrayEquals(nineteen, file.readBytes()); assertEquals(1, store.importJson(document("新一"))); assertEquals(20, store.load().size)
    }

    @Test fun everyMutationRollsBackPartialAtomicWriteToLastCommittedBytes() {
        store.save("原始一", foundation, settings); store.save("原始二", foundation, settings)
        val original = store.load(); val before = file.readBytes()
        val failing = DdcPresetStore(file) { stream, bytes -> stream.write(bytes, 0, minOf(31, bytes.size)); throw IOException("partial disk write") }
        listOf<() -> Unit>({ failing.save("新", foundation, settings) }, { failing.rename(original.first().id, "改名") },
            { failing.overwrite(original.first().id, foundation, DdcParameters.defaults()) },
            { failing.delete(original.first().id) }, { failing.importJson(document("导入")) }).forEach {
            assertThrows(IOException::class.java) { it() }; assertArrayEquals(before, file.readBytes())
            assertEquals(original, DdcPresetStore(file).load()); assertFalse(File(file.path + ".new").exists())
        }
    }

    @Test fun corruptExistingLibraryIsReportedAndCannotBeSilentlyReplaced() {
        val valid = document("已保存")
        listOf("{broken", valid.dropLast(1), valid.replace("\"version\":1", "\"version\":2")).forEach {
            file.writeText(it, Charsets.UTF_8); val before = file.readBytes()
            assertThrows(IllegalArgumentException::class.java) { store.load() }
            assertThrows(IllegalArgumentException::class.java) { store.save("新", foundation, settings) }
            assertThrows(IllegalArgumentException::class.java) { store.importJson(document("导入")) }; assertArrayEquals(before, file.readBytes())
        }
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(IOException::class.java) { store.load() }; assertThrows(IOException::class.java) { store.save("新", foundation, settings) }
    }

    @Test fun independentLibraryAndParameterApplyPreservePowerFileSelectionAndOtherModules() {
        val app: Context = RuntimeEnvironment.getApplication(); val directory = temporary.newFolder()
        val wrapped = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val favorites = DdcPresetStore(wrapped); val otherFavorites = ToneAdvancedPresetStore(wrapped)
        val otherFavorite = otherFavorites.save("音色收藏", ToneAdvancedParameters.defaults())
        val prefs = app.getSharedPreferences(DdcParameters.NAMESPACE, Context.MODE_PRIVATE)
        val other = ToneController.preferences(app); other.edit().clear().putBoolean(ToneController.ENABLED, true).commit()
        val otherBefore = other.all.toMap()
        prefs.edit().clear().putBoolean(DdcParameters.ENABLED, false).putString(DdcParameters.FILE, "DDC/existing.vdc").putInt("unrelated", 17).commit()
        val before = prefs.all.toMap(); val saved = favorites.save("独立收藏", foundation, settings)
        assertEquals(before, prefs.all); assertEquals(otherBefore, other.all)
        prefs.edit().putBoolean(DdcParameters.ENABLED, true).putInt("unrelated", 73).commit()
        DdcParameters.apply(prefs, saved.settings)
        assertEquals(saved.settings, DdcParameters.capture(prefs.all)); assertTrue(prefs.getBoolean(DdcParameters.ENABLED, false))
        assertEquals("DDC/existing.vdc", prefs.getString(DdcParameters.FILE, "")); assertEquals(73, prefs.getInt("unrelated", 0)); assertEquals(otherBefore, other.all)
        prefs.edit().putBoolean(DdcParameters.ENABLED, false).commit(); DdcParameters.apply(prefs, saved.settings)
        assertFalse(prefs.getBoolean(DdcParameters.ENABLED, true)); assertEquals(listOf(saved), DdcPresetStore(wrapped).load())
        assertNotEquals(DdcPresetStore.FILE_NAME, ToneAdvancedPresetStore.FILE_NAME); assertNotEquals(DdcPresetStore.FILE_NAME, CompanderPresetStore.FILE_NAME)
        assertEquals(listOf(otherFavorite), ToneAdvancedPresetStore(wrapped).load()); assertEquals(otherBefore, other.all)
    }
}
