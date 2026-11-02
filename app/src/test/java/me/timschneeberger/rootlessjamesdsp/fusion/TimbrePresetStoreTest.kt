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
class TimbrePresetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var file: File
    private lateinit var store: TimbrePresetStore
    private val settings = TimbreParameters.Settings(
        professional = true, coreOn = true, drive = 73, amount = 617,
        band1 = 431, band2 = 937, band3 = 1237, band4 = 1731,
        toneOn = true, cut1 = 431, cut2 = 1373, cut3 = 3171, cut4 = 7317, cut5 = 13731,
        colorHP = 137, colorLP = 17317, dcOn = true, dcHz = 73,
        mixOn = true, mix = 317, outputOn = true, output = -29,
        compOn = true, comp = 731, qualityOn = true, factor = 4, meters = true,
    )
    @Before fun setup() {
        file = File(temporary.newFolder(), TimbrePresetStore.FILE_NAME)
        store = TimbrePresetStore(file)
    }
    private fun document(vararg names: String) = TimbrePresetCodec.encode(names.map {
        TimbrePreset(UUID.randomUUID().toString(), it, settings)
    })

    @Test fun saveRenameOverwriteDeleteAndReopenPreserveIdentityAndAllSettings() {
        assertTrue(store.load().isEmpty()); assertFalse(file.exists())
        val saved = store.save("  人声空间  ", settings)
        assertEquals("人声空间", saved.name)
        assertEquals(listOf(saved), TimbrePresetStore(file).load())
        val renamed = store.rename(saved.id, "晚间")
        assertEquals(saved.id, renamed.id); assertEquals(settings, renamed.settings)
        val changed = settings.copy(professional = false, drive = 83, band1 = 217, factor = 2, meters = false)
        val overwritten = store.overwrite(saved.id, changed)
        assertEquals(renamed.id, overwritten.id); assertEquals(renamed.name, overwritten.name)
        assertEquals(changed, overwritten.settings)
        val second = store.save("另一份", settings); store.delete(second.id)
        assertEquals(listOf(overwritten), TimbrePresetStore(file).load())
        val before = file.readBytes()
        listOf<() -> Unit>({ store.delete("missing") }, { store.rename("missing", "新名称") },
            { store.overwrite("missing", settings) }, { store.export("missing") }).forEach {
            assertThrows(IllegalArgumentException::class.java) { it() }
            assertArrayEquals(before, file.readBytes())
        }
        store.delete(saved.id); assertTrue(TimbrePresetStore(file).load().isEmpty())
    }

    @Test fun twentyItemLibraryExportsImportsAndStillAllowsRenameAndOverwriteAtCapacity() {
        val saved = (1..20).map { store.save("收藏 $it", settings.copy(drive = 100 + it)) }
        val target = TimbrePresetStore(File(temporary.newFolder(), TimbrePresetStore.FILE_NAME))
        assertEquals(20, target.importJson(store.export()))
        val imported = target.load()
        assertEquals(saved.map { it.name }, imported.map { it.name })
        assertEquals(saved.map { it.settings }, imported.map { it.settings })
        assertTrue(saved.map { it.id }.toSet().intersect(imported.map { it.id }.toSet()).isEmpty())
        assertEquals(saved[3], TimbrePresetCodec.decode(store.export(saved[3].id)).single())
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save("第21份", settings) }
        assertThrows(IllegalArgumentException::class.java) { store.importJson(document("新方案")) }
        assertArrayEquals(before, file.readBytes())
        store.rename(saved.last().id, "二十"); store.overwrite(saved.last().id, settings)
        assertEquals(20, store.load().size)
    }

    @Test fun duplicateImportNamesAreRenamedWithoutReplacingIdsOrSplittingSurrogates() {
        val saved = store.save("人声空间", settings)
        assertEquals(1, store.importJson(store.export()))
        assertEquals(1, store.importJson(document("人声空间")))
        assertEquals(listOf("人声空间", "人声空间 (2)", "人声空间 (3)"), store.load().map { it.name })
        assertEquals(saved, store.load().first())
        assertEquals(3, store.load().map { it.id }.toSet().size)
        val longName = "名".repeat(35) + "🎵" + "余".repeat(3)
        assertEquals(40, longName.length)
        store.save(longName, settings); store.importJson(document(longName))
        val imported = store.load().last()
        assertEquals("名".repeat(35) + " (2)", imported.name)
        assertEquals(imported.name, TimbrePresetCodec.name(imported.name))
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.rename(saved.id, longName) }
        assertThrows(IllegalArgumentException::class.java) { store.save(" 人声空间 ", settings) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun anyInvalidEntryAndInsufficientCapacityRejectTheWholeImportBeforeWriting() {
        store.save("保留", settings)
        val root = Json.parseToJsonElement(document("一", "二")).jsonObject
        val entries = root["presets"]!!.jsonArray
        val second = entries[1].jsonObject
        val badValues = JsonObject(second["settings"]!!.jsonObject + (TimbreParameters.drive.key to JsonPrimitive(121)))
        val badSecond = JsonObject(second + ("settings" to badValues))
        val broken = JsonObject(root + ("presets" to JsonArray(listOf(entries[0], badSecond)))).toString()
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.importJson(broken) }
        assertThrows(IllegalArgumentException::class.java) { store.importJson(document("重复", "重复")) }
        listOf(
            mapOf(TimbreParameters.cut2.key to JsonPrimitive(settings.cut1 + 49)),
            mapOf(TimbreParameters.cut3.key to JsonPrimitive(settings.cut4)),
            mapOf(TimbreParameters.colorHP.key to JsonPrimitive(1)),
            mapOf(TimbreParameters.colorHP.key to JsonPrimitive(1000), TimbreParameters.colorLP.key to JsonPrimitive(1000)),
        ).forEach { updates ->
            val invalidEntry = JsonObject(second + ("settings" to JsonObject(second["settings"]!!.jsonObject + updates)))
            val invalidFile = JsonObject(root + ("presets" to JsonArray(listOf(entries[0], invalidEntry)))).toString()
            assertThrows(IllegalArgumentException::class.java) { store.importJson(invalidFile) }
            assertArrayEquals(before, file.readBytes())
        }
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
        val failing = TimbrePresetStore(file) { stream, payload ->
            stream.write(payload, 0, minOf(31, payload.size))
            throw IOException("Simulated partial disk write failure")
        }
        listOf<() -> Unit>(
            { failing.save("新", settings) }, { failing.rename(original.first().id, "新名称") },
            { failing.overwrite(original.first().id, settings.copy(drive = 0)) },
            { failing.delete(original.first().id) }, { failing.importJson(document("导入")) }
        ).forEach { mutation ->
            assertThrows(IOException::class.java) { mutation() }
            assertArrayEquals(before, file.readBytes())
            assertEquals(original, TimbrePresetStore(file).load())
            assertFalse(File(file.path + ".new").exists())
        }
    }

    @Test fun corruptAndTruncatedExistingFilesAreReportedAndNeverReplacedBySaveOrImport() {
        val valid = document("已保存")
        listOf("{broken", valid.dropLast(1), valid.replace("\"version\":2", "\"version\":3")).forEach { broken ->
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

    @Test fun existingVersionOneLibraryKeepsIdsAndBytesOnReadThenUpgradesOnlyAfterSuccessfulMutation() {
        val legacy = document("旧收藏").replace("\"version\":2", "\"version\":1")
        file.writeText(legacy, Charsets.UTF_8)
        val bytes = file.readBytes(); val original = store.load().single()
        assertEquals("timbre-favorites-v1.json", TimbrePresetStore.FILE_NAME)
        assertArrayEquals(bytes, file.readBytes()); assertEquals(settings, original.settings)
        val renamed = store.rename(original.id, "旧收藏改名")
        assertEquals(original.id, renamed.id); assertEquals(original.settings, renamed.settings)
        assertEquals(2, Json.parseToJsonElement(file.readText()).jsonObject["version"]!!.jsonPrimitive.int)
        val added = store.save("明显染色", settings.copy(amount = 6000))
        assertEquals(listOf(renamed, added), TimbrePresetStore(file).load())
        assertEquals(6000, TimbrePresetCodec.decode(store.export(added.id)).single().settings.amount)
    }

    @Test fun failedVersionTwoWriteKeepsOriginalVersionOneLibraryForEveryMutation() {
        file.writeText(document("旧一", "旧二").replace("\"version\":2", "\"version\":1"), Charsets.UTF_8)
        val original = store.load(); val before = file.readBytes()
        val failing = TimbrePresetStore(file) { stream, payload ->
            stream.write(payload, 0, minOf(31, payload.size)); throw IOException("Simulated upgrade disk failure")
        }
        val strong = settings.copy(amount = 10000)
        val incoming = TimbrePresetCodec.encode(listOf(TimbrePreset(UUID.randomUUID().toString(), "强染色导入", strong)))
        listOf<() -> Unit>(
            { failing.save("新强染色", strong) }, { failing.rename(original.first().id, "旧名变更") },
            { failing.overwrite(original.first().id, strong) }, { failing.delete(original.first().id) },
            { failing.importJson(incoming) },
        ).forEach { mutation ->
            assertThrows(IOException::class.java) { mutation() }
            assertArrayEquals(before, file.readBytes()); assertEquals(original, TimbrePresetStore(file).load())
            assertEquals(1, Json.parseToJsonElement(file.readText()).jsonObject["version"]!!.jsonPrimitive.int)
            assertFalse(File(file.path + ".new").exists())
        }
    }

    @Test fun mixedVersionOneHighAmountImportRejectsWholeFileWithoutTouchingAnExistingVersionTwoLibrary() {
        val saved = store.save("新库保留", settings.copy(amount = 6000)); val before = file.readBytes()
        val old = TimbrePresetCodec.encode(listOf(
            TimbrePreset(UUID.randomUUID().toString(), "旧合法", settings),
            TimbrePreset(UUID.randomUUID().toString(), "旧格式高值", settings.copy(amount = 2001)),
        )).replace("\"version\":2", "\"version\":1")
        assertThrows(IllegalArgumentException::class.java) { store.importJson(old) }
        assertArrayEquals(before, file.readBytes()); assertEquals(listOf(saved), store.load())
        assertEquals(1, store.importJson(document("有效旧收藏").replace("\"version\":2", "\"version\":1")))
        assertEquals(6000, store.load().first().settings.amount); assertEquals(2, store.load().size)
    }

    @Test fun favoritesSurviveProfileReplacementAndApplyPreservesLegacyPowerAndOtherModules() {
        val app: Context = RuntimeEnvironment.getApplication()
        val directory = temporary.newFolder()
        val wrapped = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val favorites = TimbrePresetStore(wrapped)
        val analogFavorites = AnalogPresetStore(wrapped)
        val analogFavorite = analogFavorites.save("谐波染色收藏", AnalogParameters.defaults(2))
        val tube = app.getSharedPreferences(TimbreParameters.NAMESPACE, Context.MODE_PRIVATE)
        val viper = app.getSharedPreferences(ViperParameters.PREFS, Context.MODE_PRIVATE)
        tube.edit().clear().putBoolean(TimbreParameters.ENABLED, false).putFloat(TimbreParameters.LEGACY, 7.3f)
            .putString("other", "keep").commit()
        viper.edit().clear().putBoolean(TubeParameters.ENABLED, true).putBoolean(TubeParameters.PROFESSIONAL, true)
            .putInt(TubeParameters.drive.key, 73).putBoolean(AnalogParameters.ENABLED, true)
            .putInt(AnalogParameters.BASE, 2).commit()
        val original = tube.all.toMap(); val viperOriginal = viper.all.toMap()
        val favorite = favorites.save("独立收藏", settings)
        assertEquals(original, tube.all); assertEquals(viperOriginal, viper.all)
        tube.edit().clear().putBoolean(TimbreParameters.ENABLED, true).putFloat(TimbreParameters.LEGACY, -1.7f)
            .putInt("unrelated", 7).commit()
        assertEquals(listOf(favorite), TimbrePresetStore(wrapped).load())
        TimbreParameters.apply(tube, favorite.settings)
        assertEquals(settings, TimbreParameters.capture(tube.all))
        assertTrue(tube.getBoolean(TimbreParameters.ENABLED, false)); assertEquals(7, tube.getInt("unrelated", 0))
        assertEquals(-1.7f, tube.getFloat(TimbreParameters.LEGACY, 0f), 0f)
        assertEquals(viperOriginal, viper.all)
        tube.edit().putBoolean(TimbreParameters.ENABLED, false).commit()
        TimbreParameters.apply(tube, favorite.settings)
        assertFalse(tube.getBoolean(TimbreParameters.ENABLED, true))
        assertEquals(-1.7f, tube.getFloat(TimbreParameters.LEGACY, 0f), 0f)
        assertEquals(viperOriginal, viper.all)
        assertTrue(File(directory, TimbrePresetStore.FILE_NAME).isFile)
        assertTrue(File(directory, AnalogPresetStore.FILE_NAME).isFile)
        assertNotEquals(TimbrePresetStore.FILE_NAME, AnalogPresetStore.FILE_NAME)
        assertEquals(listOf(analogFavorite), AnalogPresetStore(wrapped).load())
        assertEquals(listOf(favorite), TimbrePresetStore(wrapped).load())
    }
}
