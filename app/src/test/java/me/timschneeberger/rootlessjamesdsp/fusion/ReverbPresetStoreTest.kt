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
class ReverbPresetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var file: File
    private lateinit var store: ReverbPresetStore
    private val settings = ReverbParameters.Settings(
        legacy = listOf(31, 76, 48, 19, 50), professional = true,
        size = 317, width = 763, damp = 489, wet = 197, dry = 500,
        predelayOn = true, predelay = 173, lowCutOn = true, lowCut = 131,
        highCutOn = true, highCut = 11753, linked = false, mix = 147,
        outputOn = true, output = -18, meters = true,
    )
    @Before fun setup() {
        file = File(temporary.newFolder(), ReverbPresetStore.FILE_NAME)
        store = ReverbPresetStore(file)
    }
    private fun document(vararg names: String) = ReverbPresetCodec.encode(names.map {
        ReverbPreset(UUID.randomUUID().toString(), it, settings)
    })

    @Test fun saveRenameOverwriteDeleteAndReopenPreserveIdentityAndAllSettings() {
        assertTrue(store.load().isEmpty()); assertFalse(file.exists())
        val saved = store.save("  人声空间  ", settings)
        assertEquals("人声空间", saved.name)
        assertEquals(listOf(saved), ReverbPresetStore(file).load())
        val renamed = store.rename(saved.id, "晚间")
        assertEquals(saved.id, renamed.id); assertEquals(settings, renamed.settings)
        val changed = settings.copy(professional = false, wet = 143, predelay = 217,
            legacy = listOf(8, 7, 6, 5, 4), meters = false)
        val overwritten = store.overwrite(saved.id, changed)
        assertEquals(renamed.id, overwritten.id); assertEquals(renamed.name, overwritten.name)
        assertEquals(changed, overwritten.settings)
        val second = store.save("另一份", settings); store.delete(second.id)
        assertEquals(listOf(overwritten), ReverbPresetStore(file).load())
        val before = file.readBytes()
        listOf<() -> Unit>({ store.delete("missing") }, { store.rename("missing", "新名称") },
            { store.overwrite("missing", settings) }, { store.export("missing") }).forEach {
            assertThrows(IllegalArgumentException::class.java) { it() }
            assertArrayEquals(before, file.readBytes())
        }
        store.delete(saved.id); assertTrue(ReverbPresetStore(file).load().isEmpty())
    }

    @Test fun twentyItemLibraryExportsImportsAndStillAllowsRenameAndOverwriteAtCapacity() {
        val saved = (1..20).map { store.save("收藏 $it", settings.copy(predelay = 100 + it)) }
        val target = ReverbPresetStore(File(temporary.newFolder(), ReverbPresetStore.FILE_NAME))
        assertEquals(20, target.importJson(store.export()))
        val imported = target.load()
        assertEquals(saved.map { it.name }, imported.map { it.name })
        assertEquals(saved.map { it.settings }, imported.map { it.settings })
        assertTrue(saved.map { it.id }.toSet().intersect(imported.map { it.id }.toSet()).isEmpty())
        assertEquals(saved[3], ReverbPresetCodec.decode(store.export(saved[3].id)).single())
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
        assertEquals(imported.name, ReverbPresetCodec.name(imported.name))
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
        val badValues = JsonObject(second["settings"]!!.jsonObject + (ReverbParameters.predelay.key to JsonPrimitive(2001)))
        val badSecond = JsonObject(second + ("settings" to badValues))
        val broken = JsonObject(root + ("presets" to JsonArray(listOf(entries[0], badSecond)))).toString()
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.importJson(broken) }
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
        val failing = ReverbPresetStore(file) { stream, payload ->
            stream.write(payload, 0, minOf(31, payload.size))
            throw IOException("Simulated partial disk write failure")
        }
        listOf<() -> Unit>(
            { failing.save("新", settings) }, { failing.rename(original.first().id, "新名称") },
            { failing.overwrite(original.first().id, settings.copy(predelay = 0)) },
            { failing.delete(original.first().id) }, { failing.importJson(document("导入")) }
        ).forEach { mutation ->
            assertThrows(IOException::class.java) { mutation() }
            assertArrayEquals(before, file.readBytes())
            assertEquals(original, ReverbPresetStore(file).load())
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

    @Test fun favoritesSurviveProfileReplacementAndApplyPreservesBothModulePowersAndRoomSettings() {
        val app: Context = RuntimeEnvironment.getApplication()
        val directory = temporary.newFolder()
        val wrapped = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val favorites = ReverbPresetStore(wrapped)
        val viper = app.getSharedPreferences(ReverbParameters.NAMESPACE, Context.MODE_PRIVATE)
        val room = app.getSharedPreferences(Constants.PREF_REVERB, Context.MODE_PRIVATE)
        viper.edit().clear().putBoolean(ReverbParameters.ENABLED, false).putString("other", "keep").commit()
        room.edit().clear().putBoolean("reverb_enable", true).putString("reverb_preset", "4").commit()
        val original = viper.all.toMap(); val roomOriginal = room.all.toMap()
        val favorite = favorites.save("独立收藏", settings)
        assertEquals(original, viper.all); assertEquals(roomOriginal, room.all)
        viper.edit().clear().putBoolean(ReverbParameters.ENABLED, true).putInt("unrelated", 7).commit()
        assertEquals(listOf(favorite), ReverbPresetStore(wrapped).load())
        ReverbParameters.apply(viper, favorite.settings)
        assertEquals(settings, ReverbParameters.capture(viper.all))
        assertTrue(viper.getBoolean(ReverbParameters.ENABLED, false)); assertEquals(7, viper.getInt("unrelated", 0))
        assertEquals(roomOriginal, room.all)
        viper.edit().putBoolean(ReverbParameters.ENABLED, false).commit()
        ReverbParameters.apply(viper, favorite.settings)
        assertFalse(viper.getBoolean(ReverbParameters.ENABLED, true)); assertEquals(roomOriginal, room.all)
        assertTrue(File(directory, ReverbPresetStore.FILE_NAME).isFile)
    }
}
