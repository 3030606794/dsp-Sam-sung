package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.serialization.json.*
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
@Config(sdk = [35], application = Application::class)
class PeqPresetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var file: File
    private lateinit var store: PeqPresetStore
    private val state = PeqState(listOf(PeqBand("test-band", gain = 3.125, q = 2.3456789,
        frequency = 777.123456, qualityDisplay = PeqQualityDisplay.BW)), gainRange = 50.0,
        gainStep = 0.25, qStep = 0.02, frequencyStep = 31.0, pinResponse = false)

    @Before fun setup() {
        file = File(temporary.newFolder(), PeqPresetStore.FILE_NAME)
        store = PeqPresetStore(file)
    }

    private fun portable(vararg names: String): String = PeqPresetCodec.encode(names.map {
        PeqPreset(UUID.randomUUID().toString(), it, state)
    })

    @Test fun saveRenameOverwriteAndDeletePreserveStableIdsAndExactParameters() {
        assertTrue(store.load().isEmpty())
        assertFalse(file.exists())
        val saved = store.save("  我的耳机  ", state)
        assertEquals("我的耳机", saved.name)
        assertEquals(saved.id, UUID.fromString(saved.id).toString())
        assertEquals(listOf(saved), PeqPresetStore(file).load())
        val renamed = store.rename(saved.id, "低频")
        assertEquals(saved.id, renamed.id)
        assertEquals(state, renamed.state)
        val next = state.copy(bands = state.bands.map { it.copy(gain = -7.75) })
        val updated = store.overwrite(saved.id, next)
        assertEquals(renamed.id, updated.id)
        assertEquals(renamed.name, updated.name)
        assertEquals(next, updated.state)
        val second = store.save("人声", state)
        store.delete(second.id)
        assertEquals(listOf(updated), store.load())
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.delete("missing") }
        assertArrayEquals(before, file.readBytes())
        store.delete(updated.id)
        assertTrue(PeqPresetStore(file).load().isEmpty())
    }

    @Test fun twentyEntriesRoundTripAsOneBundleAndSingleExportsHaveNoLocalIds() {
        val bank = PeqModel.defaultState(170).copy(qStep = 37.125, frequencyStep = 123.5)
        val saved = (1..20).map { store.save("预设 $it", if (it == 1) bank else state) }
        val bundle = store.exportJson()
        assertEquals(saved.map { it.name }, PeqPresetCodec.decode(bundle).map { it.name })
        assertEquals(saved.map { it.state }, PeqPresetCodec.decode(bundle).map { it.state })
        val single = store.exportJson(saved.first().id)
        val document = Json.parseToJsonElement(single).jsonObject
        assertEquals(PeqPresetCodec.FORMAT, document["format"]!!.jsonPrimitive.content)
        val entries = document["presets"]!!.jsonArray
        assertEquals(1, entries.size)
        assertFalse(entries.single().jsonObject.containsKey("id"))
        assertEquals(PeqRepository.SCHEMA, entries.single().jsonObject["state"]!!.jsonObject["schema"]!!.jsonPrimitive.int)
        val importedStore = PeqPresetStore(File(temporary.newFolder(), "import.json"))
        val imported = importedStore.importJson(bundle)
        assertEquals(saved.map { it.name }, imported.map { it.name })
        assertEquals(saved.map { it.state }, imported.map { it.state })
        assertTrue(saved.map { it.id }.toSet().intersect(imported.map { it.id }.toSet()).isEmpty())
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save("第 21 个", state) }
        assertThrows(IllegalArgumentException::class.java) { store.importJson(single) }
        assertArrayEquals(before, file.readBytes())
        store.rename(saved.last().id, "二十")
        store.overwrite(saved.last().id, bank)
        assertEquals(20, store.load().size)
    }

    @Test fun duplicateImportNamesAreUniqueAndLengthLimitedWithoutOverwritingExistingEntries() {
        val first = store.save("耳机", state)
        val added = store.importJson(portable("耳机", "耳机", "耳机"))
        assertEquals(listOf("耳机 (2)", "耳机 (3)", "耳机 (4)"), added.map { it.name })
        assertEquals(first, store.load().first())
        val longName = "长".repeat(40)
        val long = store.save(longName, state)
        val longImports = store.importJson(portable(longName, longName))
        assertEquals(listOf("长".repeat(36) + " (2)", "长".repeat(36) + " (3)"), longImports.map { it.name })
        assertTrue(longImports.all { it.name.length == 40 })
        assertEquals(long, store.load().first { it.id == long.id })
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.rename(first.id, longName) }
        assertThrows(IllegalArgumentException::class.java) { store.save(" 耳机 ", state) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun oneBadEntryOrInsufficientCapacityRejectsTheWholeImport() {
        store.save("保留", state)
        val valid = Json.parseToJsonElement(portable("第一项", "第二项")).jsonObject
        val entries = valid["presets"]!!.jsonArray
        val bad = entries[1].jsonObject.toMutableMap()
        bad["state"] = JsonObject(bad["state"]!!.jsonObject + ("schema" to JsonPrimitive(999)))
        val badBundle = JsonObject(valid + ("presets" to JsonArray(listOf(entries[0], JsonObject(bad))))).toString()
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.importJson(badBundle) }
        assertArrayEquals(before, file.readBytes())
        (2..19).forEach { store.save("保留 $it", state) }
        val atNineteen = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.importJson(portable("新一", "新二")) }
        assertArrayEquals(atNineteen, file.readBytes())
        assertEquals(1, store.importJson(portable("新一")).size)
        assertEquals(20, store.load().size)
    }

    @Test fun atomicWriteFailuresLeaveEveryExistingEntryAndFileByteIntact() {
        store.save("原始一", state)
        store.save("原始二", state)
        val original = store.load()
        val bytes = file.readBytes()
        val failing = PeqPresetStore(file) { stream, content ->
            stream.write(content, 0, minOf(20, content.size))
            throw IOException("Simulated disk write failure")
        }
        val operations = listOf<() -> Unit>(
            { failing.save("新预设", state) },
            { failing.rename(original[0].id, "新名称") },
            { failing.overwrite(original[0].id, PeqModel.defaultState(170)) },
            { failing.delete(original[0].id) },
            { failing.importJson(portable("导入预设")) }
        )
        operations.forEach { operation ->
            assertThrows(IOException::class.java) { operation() }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(original, PeqPresetStore(file).load())
            assertFalse(File(file.path + ".new").exists())
        }
    }

    @Test fun corruptLocalDataIsReportedAndCannotBeSilentlyReplacedBySavingOrImporting() {
        file.writeText("{broken json", Charsets.UTF_8)
        val original = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.load() }
        assertThrows(IllegalArgumentException::class.java) { store.save("新预设", state) }
        assertThrows(IllegalArgumentException::class.java) { store.importJson(portable("导入预设")) }
        assertArrayEquals(original, file.readBytes())
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(IOException::class.java) { store.load() }
        assertArrayEquals(byteArrayOf(0xc3.toByte(), 0x28), file.readBytes())
    }

    @Test fun legacyStateImportMigratesOnlyOldInactiveGainsAndExportsLatestSchema() {
        val legacyState = state.copy(bands = PeqFilterType.entries.mapIndexed { index, type ->
            PeqBand(type.name, type = type, frequency = 1000.0 + index, gain = 37.125, q = 3.5)
        })
        val old = PeqPresetCodec.encode(listOf(PeqPreset(UUID.randomUUID().toString(), "旧版", legacyState)))
            .replace("\"schema\":2", "\"schema\":1")
        val imported = store.importJson(old).single()
        assertEquals(legacyState.copy(bands = legacyState.bands.map {
            if (it.type.isUnityAtZeroGain) it else it.copy(gain = 0.0)
        }), imported.state)
        assertTrue(store.exportJson(imported.id).contains("\"schema\":2"))
        assertEquals(imported.state, PeqPresetCodec.decode(store.exportJson(imported.id)).single().state)
    }

    @Test fun duplicateFrequencyImportsNeverPartiallyWriteAndOldLocalFavoritesRemainUntouchedUntilSaved() {
        val bank = state.copy(bands = state.bands + PeqBand("other", frequency = 1234.0, gain = -2.34, q = 5.678))
        val first = store.save("原始", bank)
        val bytes = file.readBytes()
        val duplicatePortable = store.exportJson().replace("\"frequency\":1234.0", "\"frequency\":777.1234")
        assertThrows(IllegalArgumentException::class.java) { store.importJson(duplicatePortable) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf(first), store.load())
        val oldLocal = file.readText(Charsets.UTF_8).replace("\"frequency\":1234.0", "\"frequency\":777.1234")
        file.writeText(oldLocal, Charsets.UTF_8)
        val expected = first.copy(state = bank.copy(bands = bank.bands.map {
            if (it.id == "other") it.copy(frequency = 0.0) else it
        }))
        assertEquals(listOf(expected), store.load())
        assertEquals(oldLocal, file.readText(Charsets.UTF_8))
        assertEquals(expected.state, PeqPresetCodec.decode(store.exportJson(first.id)).single().state)
        assertEquals(oldLocal, file.readText(Charsets.UTF_8))
    }

    @Test fun personalFavoritesSurviveDevicePreferenceReplacementAndNeverChangeEffectSwitches() {
        val application: Context = RuntimeEnvironment.getApplication()
        val isolatedFiles = temporary.newFolder()
        val wrapped = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolatedFiles
        }
        val favorites = PeqPresetStore(wrapped)
        val preferences = application.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        preferences.edit().clear().putBoolean("geq_enable", false).putString("device", "wired").commit()
        val repository = PeqRepository(preferences)
        assertTrue(repository.save(state))
        val before = preferences.all.toMap()
        val favorite = favorites.save("个人收藏", state)
        favorites.rename(favorite.id, "我的收藏")
        favorites.overwrite(favorite.id, state)
        favorites.importJson(favorites.exportJson(favorite.id))
        assertEquals(before, preferences.all)
        assertTrue(File(isolatedFiles, PeqPresetStore.FILE_NAME).exists())
        preferences.edit().clear().putBoolean("geq_enable", true).putString("device", "bluetooth").commit()
        assertTrue(repository.save(PeqModel.defaultState(3)))
        val afterDeviceChange = preferences.all.toMap()
        assertEquals(listOf(state, state), favorites.load().map { it.state })
        assertEquals(afterDeviceChange, preferences.all)
        favorites.delete(favorites.load().last().id)
        assertEquals(afterDeviceChange, preferences.all)
    }
}
