package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.serialization.json.*
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
import me.timschneeberger.rootlessjamesdsp.utils.Constants
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
class CompanderPresetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var file: File
    private lateinit var store: CompanderPresetStore
    private val settings = CompanderParameters.capture(CompanderParameters.defaults().values() + mapOf(
        "compander_timeconstant" to 0.173f, "compander_granularity" to 3f,
        "compander_tftransforms" to "2", "compander_response" to
            "95;200;400;800;1600;3400;7500;-0.2;0.3;-0.4;0.5;-0.6;0.7;-0.8"
    )).copy(professional = true)
    @Before fun setup() {
        file = File(temporary.newFolder(), CompanderPresetStore.FILE_NAME)
        store = CompanderPresetStore(file)
    }
    private fun document(vararg names: String) = CompanderPresetCodec.encode(names.map {
        CompanderPreset(UUID.randomUUID().toString(), it, settings)
    })

    @Test fun saveRenameOverwriteDeleteAndReopenPreserveIdentityAndAllSettings() {
        assertTrue(store.load().isEmpty()); assertFalse(file.exists())
        val saved = store.save("  低频方案  ", settings)
        assertEquals("低频方案", saved.name)
        assertEquals(listOf(saved), CompanderPresetStore(file).load())
        val renamed = store.rename(saved.id, "晚间")
        assertEquals(saved.id, renamed.id); assertEquals(settings, renamed.settings)
        val changed = CompanderParameters.capture(settings.values() + mapOf("compander_timeconstant" to 0.271f, "compander_granularity" to 4f)).copy(professional = false)
        val overwritten = store.overwrite(saved.id, changed)
        assertEquals(renamed.id, overwritten.id); assertEquals(renamed.name, overwritten.name)
        assertEquals(changed, overwritten.settings)
        val second = store.save("另一份", settings); store.delete(second.id)
        assertEquals(listOf(overwritten), CompanderPresetStore(file).load())
        val before = file.readBytes()
        listOf<() -> Unit>({ store.delete("missing") }, { store.rename("missing", "新名称") },
            { store.overwrite("missing", settings) }, { store.export("missing") }).forEach {
            assertThrows(IllegalArgumentException::class.java) { it() }
            assertArrayEquals(before, file.readBytes())
        }
        store.delete(saved.id); assertTrue(CompanderPresetStore(file).load().isEmpty())
    }

    @Test fun twentyItemLibraryExportsImportsAndStillAllowsRenameAndOverwriteAtCapacity() {
        val saved = (1..20).map { store.save("收藏 $it", CompanderParameters.capture(settings.values() + mapOf("compander_timeconstant" to (0.06f + it * 0.01f)))) }
        val target = CompanderPresetStore(File(temporary.newFolder(), CompanderPresetStore.FILE_NAME))
        assertEquals(20, target.importJson(store.export()))
        val imported = target.load()
        assertEquals(saved.map { it.name }, imported.map { it.name })
        assertEquals(saved.map { it.settings }, imported.map { it.settings })
        assertTrue(saved.map { it.id }.toSet().intersect(imported.map { it.id }.toSet()).isEmpty())
        assertEquals(saved[3], CompanderPresetCodec.decode(store.export(saved[3].id)).single())
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save("第21份", settings) }
        assertThrows(IllegalArgumentException::class.java) { store.importJson(document("新方案")) }
        assertArrayEquals(before, file.readBytes())
        store.rename(saved.last().id, "二十"); store.overwrite(saved.last().id, settings)
        assertEquals(20, store.load().size)
    }

    @Test fun duplicateImportNamesAreRenamedWithoutReplacingIdsOrSplittingSurrogates() {
        val saved = store.save("低频方案", settings)
        assertEquals(1, store.importJson(store.export()))
        assertEquals(1, store.importJson(document("低频方案")))
        assertEquals(listOf("低频方案", "低频方案 (2)", "低频方案 (3)"), store.load().map { it.name })
        assertEquals(saved, store.load().first())
        assertEquals(3, store.load().map { it.id }.toSet().size)
        val longName = "名".repeat(35) + "🎵" + "余".repeat(3)
        assertEquals(40, longName.length)
        store.save(longName, settings); store.importJson(document(longName))
        val imported = store.load().last()
        assertEquals("名".repeat(35) + " (2)", imported.name)
        assertEquals(imported.name, CompanderPresetCodec.name(imported.name))
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.rename(saved.id, longName) }
        assertThrows(IllegalArgumentException::class.java) { store.save(" 低频方案 ", settings) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun invalidEntryRelationsAndInsufficientCapacityRejectWholeImportBeforeWriting() {
        store.save("保留", settings)
        val root = Json.parseToJsonElement(document("一", "二")).jsonObject
        val entries = root["presets"]!!.jsonArray
        val second = entries[1].jsonObject
        val before = file.readBytes()
        listOf(
            mapOf("compander_timeconstant" to JsonPrimitive(0.031f)),
            mapOf("compander_timeconstant" to JsonPrimitive("0.173")),
            mapOf("compander_granularity" to JsonPrimitive(4.01f)),
            mapOf("compander_tftransforms" to JsonPrimitive(2)),
            mapOf("compander_response" to JsonPrimitive("95;200;400;800;1600;3400;7500;0;0;0;0;0;0")),
            mapOf("compander_response" to JsonPrimitive("95;200;200;800;1600;3400;7500;0;0;0;0;0;0;0")),
            mapOf(CompanderParameters.rangeLow.key to JsonPrimitive(20000), CompanderParameters.rangeHigh.key to JsonPrimitive(20)),
            mapOf(CompanderParameters.sideHP.key to JsonPrimitive(500), CompanderParameters.sideLP.key to JsonPrimitive(499)),
        ).forEach { updates ->
            val invalidEntry = JsonObject(second + ("settings" to JsonObject(second["settings"]!!.jsonObject + updates)))
            val invalidFile = JsonObject(root + ("presets" to JsonArray(listOf(entries[0], invalidEntry)))).toString()
            assertThrows(IllegalArgumentException::class.java) { store.importJson(invalidFile) }
            assertArrayEquals(before, file.readBytes())
        }
        assertThrows(IllegalArgumentException::class.java) { store.importJson(document("重复", "重复")) }
        assertArrayEquals(before, file.readBytes())
        (2..19).forEach { store.save("保留 $it", settings) }
        val nineteen = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.importJson(document("新一", "新二")) }
        assertArrayEquals(nineteen, file.readBytes())
        assertEquals(1, store.importJson(document("新一"))); assertEquals(20, store.load().size)
    }

    @Test fun partialAtomicWriteFailureRollsBackEveryMutationAndKeepsPreviousBytes() {
        store.save("原始一", settings); store.save("原始二", settings)
        val original = store.load(); val before = file.readBytes()
        val failing = CompanderPresetStore(file) { stream, payload ->
            stream.write(payload, 0, minOf(31, payload.size))
            throw IOException("Simulated partial disk write failure")
        }
        listOf<() -> Unit>(
            { failing.save("新", settings) }, { failing.rename(original.first().id, "新名称") },
            { failing.overwrite(original.first().id, settings.copy(professional = false)) },
            { failing.delete(original.first().id) }, { failing.importJson(document("导入")) }
        ).forEach { mutation ->
            assertThrows(IOException::class.java) { mutation() }
            assertArrayEquals(before, file.readBytes())
            assertEquals(original, CompanderPresetStore(file).load())
            assertFalse(File(file.path + ".new").exists())
        }
    }

    @Test fun corruptAndTruncatedExistingFilesAreReportedAndNeverReplacedBySaveOrImport() {
        val valid = document("已保存")
        listOf("{broken", valid.dropLast(1), valid.replace("\"version\":1", "\"version\":2")).forEach { broken ->
            file.writeText(broken, Charsets.UTF_8); val before = file.readBytes()
            assertThrows(IllegalArgumentException::class.java) { store.load() }
            assertThrows(IllegalArgumentException::class.java) { store.save("新", settings) }
            assertThrows(IllegalArgumentException::class.java) { store.importJson(document("导入")) }
            assertArrayEquals(before, file.readBytes())
        }
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(IOException::class.java) { store.load() }
        assertThrows(IOException::class.java) { store.save("新", settings) }
    }

    @Test fun favoritesSurviveProfileReplacementAndApplyingDoesNotTouchPowerOrDynamicTone() {
        val app: Context = RuntimeEnvironment.getApplication()
        val directory = temporary.newFolder()
        val wrapped = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val favorites = CompanderPresetStore(wrapped)
        val dynamicFavorites = DynamicPresetStore(wrapped)
        val dynamicFavorite = dynamicFavorites.save("动态音色收藏", DynamicParameters.defaults())
        val compander = app.getSharedPreferences(Constants.PREF_COMPANDER, Context.MODE_PRIVATE)
        val dynamic = app.getSharedPreferences(DynamicParameters.NAMESPACE, Context.MODE_PRIVATE)
        dynamic.edit().clear().putBoolean(DynamicParameters.ENABLED, true)
            .putString(DynamicParameters.BASE, "4").putInt(DynamicParameters.LEGACY, 37).commit()
        val otherBefore = dynamic.all.toMap()
        compander.edit().clear().putBoolean(CompanderParameters.ENABLED, false).putInt("unrelated", 17).commit()
        val before = compander.all.toMap()
        val favorite = favorites.save("独立收藏", settings)
        assertEquals(before, compander.all); assertEquals(otherBefore, dynamic.all)
        compander.edit().clear().putBoolean(CompanderParameters.ENABLED, true).putInt("unrelated", 73).commit()
        assertEquals(listOf(favorite), CompanderPresetStore(wrapped).load())
        CompanderParameters.apply(compander, favorite.settings)
        assertEquals(settings, CompanderParameters.capture(compander.all))
        assertTrue(compander.getBoolean(CompanderParameters.ENABLED, false)); assertEquals(73, compander.getInt("unrelated", 0))
        assertEquals(otherBefore, dynamic.all)
        compander.edit().putBoolean(CompanderParameters.ENABLED, false).commit()
        CompanderParameters.apply(compander, favorite.settings)
        assertFalse(compander.getBoolean(CompanderParameters.ENABLED, true)); assertEquals(otherBefore, dynamic.all)
        assertNotEquals(CompanderPresetStore.FILE_NAME, DynamicPresetStore.FILE_NAME)
        assertEquals(listOf(favorite), CompanderPresetStore(wrapped).load())
        assertEquals(listOf(dynamicFavorite), DynamicPresetStore(wrapped).load())
    }
}
