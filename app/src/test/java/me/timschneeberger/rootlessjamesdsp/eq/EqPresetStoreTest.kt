package me.timschneeberger.rootlessjamesdsp.eq

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.view.EqPreview
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class EqPresetStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val eq get() = app.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
    private val peq get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val global get() = app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
    private val bandKey get() = app.getString(R.string.key_eq_bands)
    private val filterKey get() = app.getString(R.string.key_eq_filter_type)
    private val interpolationKey get() = app.getString(R.string.key_eq_interpolation)
    private lateinit var store: EqPresetStore
    private var broadcasts = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { broadcasts++ }
    }

    @Before fun setup() {
        listOf(Constants.PREF_EQ, Constants.PREF_GEQ, Constants.PREF_APP, EqPresetStore.PREFERENCES).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        store = EqPresetStore(app)
        app.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))
    }
    @After fun cleanup() { app.unregisterLocalReceiver(receiver) }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun setCurrent(gains: List<Double>, filter: String = "2", interpolation: String = "1") {
        eq.edit().putString(bandKey, EqPreview.encode(gains.toDoubleArray()))
            .putString(filterKey, filter).putString(interpolationKey, interpolation)
            .putBoolean(app.getString(R.string.key_eq_enable), true).commit()
    }
    private fun currentGains() = EqPreview.parse(eq.getString(bandKey, EqPreview.defaultValue)!!).toList()

    @Test fun allEighteenBuiltInsKeepStableIdsAndOriginalValuesWithNoInitialShortcuts() {
        val presets = store.all()
        assertEquals(18, presets.size)
        assertEquals((0..17).map { "builtin-$it" }, presets.map { it.id })
        val names = app.resources.getStringArray(R.array.equalizer_preset_modes)
        val values = app.resources.getStringArray(R.array.equalizer_preset_values)
        presets.forEachIndexed { index, preset ->
            assertTrue(preset.builtIn)
            assertEquals(names[index], preset.name)
            assertEquals(values[index].split(';').map(String::toDouble), preset.gains)
            assertNull(preset.filterType)
            assertNull(preset.interpolation)
        }
        assertTrue(store.shortcuts().isEmpty())
        assertNull(store.selectedShortcutId())
        assertTrue(store.preferences.all.isEmpty())
        assertTrue(eq.all.isEmpty())
    }

    @Test fun captureDoesNotSaveAndEverySaveGetsAnIndependentPersistentId() {
        val gains = List(15) { (it - 7) * 0.173 }
        setCurrent(gains)
        val before = eq.all.toMap()
        val capture = store.capture("  我的声音  ")
        assertEquals("我的声音", capture.name)
        assertEquals(gains, capture.gains)
        assertEquals("2", capture.filterType)
        assertEquals("1", capture.interpolation)
        assertTrue(store.preferences.all.isEmpty())
        val first = store.saveCurrent("我的声音")
        val second = store.saveCurrent("我的声音")
        assertNotEquals(first.id, second.id)
        assertEquals(20, EqPresetStore(app).all().size)
        assertEquals(first, EqPresetStore(app).find(first.id))
        assertEquals(before, eq.all)
        idle()
        assertEquals(0, broadcasts)
    }

    @Test fun fiveShortcutsAreUniqueOrderedPersistentAndRemovableWithoutChangingAudio() {
        val before = eq.all.toMap()
        (0..4).forEach { assertEquals(EqPresetStore.ShortcutResult.ADDED, store.addShortcut("builtin-$it")) }
        assertEquals(EqPresetStore.ShortcutResult.ALREADY_ADDED, store.addShortcut("builtin-0"))
        assertEquals(EqPresetStore.ShortcutResult.NOT_FOUND, store.addShortcut("missing"))
        assertEquals(EqPresetStore.ShortcutResult.FULL, store.addShortcut("builtin-5"))
        assertEquals((0..4).map { "builtin-$it" }, EqPresetStore(app).shortcuts().map { it.id })
        assertTrue(store.removeShortcut("builtin-2"))
        assertFalse(store.removeShortcut("builtin-2"))
        assertEquals(EqPresetStore.ShortcutResult.ADDED, store.addShortcut("builtin-5"))
        assertEquals(listOf("builtin-0", "builtin-1", "builtin-3", "builtin-4", "builtin-5"),
            EqPresetStore(app).shortcuts().map { it.id })
        assertEquals(before, eq.all)
        idle()
        assertEquals(0, broadcasts)
    }

    @Test fun applyChangesOnlyEqWithOneBroadcastBuiltInRetainsOptionsPersonalRestoresThem() {
        peq.edit().putString("marker", "PEQ").commit()
        global.edit().putBoolean("global_enable", false).commit()
        val beforePeq = peq.all.toMap()
        val beforeGlobal = global.all.toMap()
        val customGains = List(15) { (it - 7) * 0.21 }
        setCurrent(customGains, "5", "1")
        val personal = store.saveCurrent("保存")
        eq.edit().putString(filterKey, "0").putString(interpolationKey, "0").commit()
        assertTrue(store.apply("builtin-3"))
        idle()
        assertEquals(1, broadcasts)
        assertEquals(store.find("builtin-3")!!.gains, currentGains())
        assertEquals("0", eq.getString(filterKey, null))
        assertEquals("0", eq.getString(interpolationKey, null))
        assertTrue(store.apply(personal.id))
        idle()
        assertEquals(2, broadcasts)
        assertEquals(customGains, currentGains())
        assertEquals("5", eq.getString(filterKey, null))
        assertEquals("1", eq.getString(interpolationKey, null))
        assertTrue(eq.getBoolean(app.getString(R.string.key_eq_enable), false))
        assertEquals(beforePeq, peq.all)
        assertEquals(beforeGlobal, global.all)
        val before = eq.all.toMap()
        assertFalse(store.apply("missing"))
        idle()
        assertEquals(before, eq.all)
        assertEquals(2, broadcasts)
    }

    @Test fun selectedShortcutTracksRealParametersAndAmbiguousMatchesDoNotGuess() {
        setCurrent(List(15) { 0.37 })
        val first = store.saveCurrent("一")
        val second = store.saveCurrent("二")
        store.addShortcut(first.id)
        store.addShortcut(second.id)
        assertNull("Equal matching shortcuts need an explicit last applied choice", store.selectedShortcutId())
        assertTrue(store.apply(second.id))
        assertEquals(second.id, store.selectedShortcutId())
        assertEquals(second.id, EqPresetStore(app).selectedShortcutId())
        eq.edit().putString(bandKey, EqPreview.encode(DoubleArray(15) { 0.38 })).commit()
        assertNull(store.selectedShortcutId())
        eq.edit().putString(bandKey, EqPreview.encode(first.gains.toDoubleArray())).putString(filterKey, "4").commit()
        assertNull("Personal filter choice is part of the match", store.selectedShortcutId())
        eq.edit().putString(filterKey, "2").commit()
        assertEquals(second.id, store.selectedShortcutId())
        assertTrue(store.removeShortcut(second.id))
        assertEquals("A unique matching shortcut can be inferred", first.id, store.selectedShortcutId())
        store.addShortcut("builtin-8")
        assertTrue(store.apply("builtin-8"))
        eq.edit().putString(filterKey, "5").putString(interpolationKey, "0").commit()
        assertEquals("Built-ins depend only on gains", "builtin-8", store.selectedShortcutId())
    }

    @Test fun exportImportRoundTripCreatesNewPersonalEntriesWithoutApplyingOrBroadcasting() {
        setCurrent(List(15) { (it - 7) * 0.131 }, "3", "1")
        val before = eq.all.toMap()
        val current = store.capture("当前设置")
        val exported = store.exportJson()
        val first = store.importJson(exported)
        val second = store.importJson(exported)
        assertNotEquals(first.id, second.id)
        assertEquals(current.gains, first.gains)
        assertEquals("3", first.filterType)
        assertEquals("1", first.interpolation)
        assertFalse(first.builtIn)
        assertEquals(before, eq.all)
        assertEquals(20, store.all().size)
        val builtInCopy = store.importJson(store.exportJson("builtin-0"))
        assertFalse(builtInCopy.builtIn)
        assertEquals(store.find("builtin-0")!!.gains, builtInCopy.gains)
        assertNull(builtInCopy.filterType)
        assertNull(builtInCopy.interpolation)
        idle()
        assertEquals(0, broadcasts)
    }

    @Test fun invalidImportAndFullLibraryLeaveAudioShortcutsAndExistingPresetsUntouched() {
        setCurrent(List(15) { 0.3 })
        val saved = store.saveCurrent("已有")
        store.addShortcut(saved.id)
        val beforeAudio = eq.all.toMap()
        val beforeLibrary = store.preferences.all.toMap()
        val valid = store.exportJson()
        listOf("{}", valid.replace("\"version\":1", "\"version\":99"),
            valid.replace("\"filterType\":\"2\"", "\"filterType\":\"9\""),
            " ".repeat(EqPresetCodec.MAX_JSON_BYTES) + valid).forEach { invalid ->
            try { store.importJson(invalid); fail("Invalid preset must not be imported") }
            catch (_: IllegalArgumentException) { }
            assertEquals(beforeAudio, eq.all)
            assertEquals(beforeLibrary, store.preferences.all)
        }
        repeat(127) { store.importJson(valid) }
        assertEquals(146, store.all().size)
        val full = store.preferences.all.toMap()
        try { store.importJson(valid); fail("Full library must reject another entry") }
        catch (_: IllegalArgumentException) { }
        try { store.duplicate("builtin-0"); fail("Full library must reject another duplicate") }
        catch (_: IllegalArgumentException) { }
        assertEquals(full, store.preferences.all)
        assertEquals(beforeAudio, eq.all)
        idle()
        assertEquals(0, broadcasts)
    }

    @Test fun v1PersonalPresetsShortcutsAndSelectionMigrateWithoutChangingIdsOrAudio() {
        val gains = List(15) { (it - 7) * 0.17 }
        setCurrent(gains, "3", "1")
        val existing = EqPreset("user-existing-v1", "旧个人预设", gains, "3", "1")
        val v1 = buildJsonObject {
            put("version", 1)
            put("users", buildJsonArray { add(buildJsonObject {
                put("id", existing.id)
                put("preset", Json.parseToJsonElement(EqPresetCodec.encode(existing)))
            }) })
            put("shortcuts", buildJsonArray { add(JsonPrimitive("builtin-3")); add(JsonPrimitive(existing.id)) })
            put("lastAppliedId", existing.id)
        }.toString()
        store.preferences.edit().putString("library_json", v1).commit()
        val beforeAudio = eq.all.toMap()
        assertEquals(existing, store.find(existing.id))
        assertEquals(listOf("builtin-3", existing.id), store.shortcuts().map { it.id })
        assertEquals(existing.id, store.selectedShortcutId())
        assertEquals("Reading v1 must not rewrite its metadata", v1, store.preferences.getString("library_json", null))
        val renamed = store.rename(existing.id, "旧预设改名")
        assertEquals(existing.copy(name = "旧预设改名"), renamed)
        val rebuilt = EqPresetStore(app)
        assertEquals(renamed, rebuilt.find(existing.id))
        assertEquals(listOf("builtin-3", existing.id), rebuilt.shortcuts().map { it.id })
        assertEquals(existing.id, rebuilt.selectedShortcutId())
        assertEquals("2", Json.parseToJsonElement(store.preferences.getString("library_json", "")!!)
            .jsonObject.getValue("version").toString())
        assertEquals(beforeAudio, eq.all)
        idle()
        assertEquals(0, broadcasts)
    }

    @Test fun builtInRenameAndEditKeepItsIdUpdateTheShortcutAndApplyExplicitOptions() {
        val originalBuiltIns = store.all()
        val original = originalBuiltIns.first()
        setCurrent(List(15) { 0.9 }, "0", "0")
        store.addShortcut(original.id)
        assertTrue(store.apply(original.id))
        idle()
        assertEquals(1, broadcasts)
        val beforeAudio = eq.all.toMap()
        val renamed = store.rename(original.id, "新名字")
        assertEquals(original.id, renamed.id)
        assertTrue(renamed.builtIn)
        assertEquals(original.id, store.selectedShortcutId())
        val gains = List(15) { (it - 7) * 0.41 }
        val edited = store.update(original.id, gains, "5", "1")
        assertEquals(original.id, edited.id)
        assertEquals("新名字", edited.name)
        assertTrue(edited.builtIn)
        assertEquals(edited, store.shortcuts().single())
        assertEquals(edited, EqPresetStore(app).find(original.id))
        assertNull("Editing metadata must clear a now-mismatching selected shortcut", store.selectedShortcutId())
        assertEquals(beforeAudio, eq.all)
        idle()
        assertEquals(1, broadcasts)
        val exported = EqPresetCodec.decode(store.exportJson(original.id))
        assertEquals(gains, exported.gains)
        assertEquals("5", exported.filterType)
        assertEquals("1", exported.interpolation)
        assertTrue(store.apply(original.id))
        idle()
        assertEquals(2, broadcasts)
        assertEquals(gains, currentGains())
        assertEquals("5", eq.getString(filterKey, null))
        assertEquals("1", eq.getString(interpolationKey, null))
        assertEquals(original.id, store.selectedShortcutId())
        assertEquals(originalBuiltIns.drop(1), store.all().drop(1))
        assertEquals("Resource gains remain intact", original.gains,
            app.resources.getStringArray(R.array.equalizer_preset_values)[0].split(';').map(String::toDouble))
    }

    @Test fun personalManagementKeepsStableIdsMakesUniqueCopiesAndNeverChangesAudio() {
        setCurrent(List(15) { 0.4 }, "2", "1")
        val original = store.saveCurrent("长".repeat(40))
        store.addShortcut(original.id)
        assertTrue(store.apply(original.id))
        idle()
        val beforeAudio = eq.all.toMap()
        val renamed = store.rename(original.id, "重".repeat(40))
        assertEquals(original.id, renamed.id)
        assertEquals(renamed, store.shortcuts().single())
        val edited = store.update(original.id, List(15) { -0.6 }, "4", "0")
        assertEquals(original.id, edited.id)
        assertEquals(edited, store.shortcuts().single())
        assertNull(store.selectedShortcutId())
        val first = store.duplicate(original.id)
        val second = store.duplicate(original.id)
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.id, original.id)
        assertNotEquals(first.name, second.name)
        assertTrue(first.name.length <= 40 && second.name.length <= 40)
        assertEquals(edited.gains, first.gains)
        assertEquals(edited.filterType, first.filterType)
        assertEquals(edited.interpolation, first.interpolation)
        assertFalse(first.builtIn)
        assertTrue(store.delete(original.id))
        assertFalse(store.delete(original.id))
        assertNull(store.find(original.id))
        assertTrue(store.shortcuts().isEmpty())
        assertNull(store.selectedShortcutId())
        assertEquals(JsonNull, Json.parseToJsonElement(store.preferences.getString("library_json", "")!!)
            .jsonObject["lastAppliedId"])
        val rebuilt = EqPresetStore(app)
        assertEquals(first, rebuilt.find(first.id))
        assertEquals(second, rebuilt.find(second.id))
        val emoji = store.rename(second.id, "🎵".repeat(20))
        val emojiCopy = store.duplicate(emoji.id)
        assertTrue(emojiCopy.name.length <= 40)
        assertEquals("Truncated duplicate names retain complete Unicode characters", emojiCopy.name,
            emojiCopy.name.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertEquals(beforeAudio, eq.all)
        idle()
        assertEquals(1, broadcasts)
    }

    @Test fun deletingABuiltInOnlyHidesItsStableIdAndCleansItsShortcutAndLastApplied() {
        val original = store.find("builtin-3")!!
        val copy = store.duplicate(original.id)
        assertFalse(copy.builtIn)
        assertEquals(original.gains, copy.gains)
        store.addShortcut(original.id)
        assertTrue(store.apply(original.id))
        idle()
        val beforeAudio = eq.all.toMap()
        assertTrue(store.delete(original.id))
        assertFalse(store.delete(original.id))
        val rebuilt = EqPresetStore(app)
        assertNull(rebuilt.find(original.id))
        assertEquals(17, rebuilt.all().count { it.builtIn })
        assertEquals(copy, rebuilt.find(copy.id))
        assertTrue(rebuilt.shortcuts().isEmpty())
        assertNull(rebuilt.selectedShortcutId())
        assertFalse(rebuilt.apply(original.id))
        assertEquals(EqPresetStore.ShortcutResult.NOT_FOUND, rebuilt.addShortcut(original.id))
        val metadata = Json.parseToJsonElement(store.preferences.getString("library_json", "")!!).jsonObject
        assertEquals(JsonNull, metadata["lastAppliedId"])
        assertTrue(metadata.getValue("hiddenBuiltInIds").toString().contains(original.id))
        assertEquals(18, app.resources.getStringArray(R.array.equalizer_preset_modes).size)
        assertEquals(beforeAudio, eq.all)
        idle()
        assertEquals(1, broadcasts)
    }

    @Test fun invalidManagementRequestsLeaveTheEntireLibraryAndAudioUntouched() {
        setCurrent(List(15) { 0.3 })
        val saved = store.saveCurrent("有效预设")
        store.addShortcut(saved.id)
        val beforeAudio = eq.all.toMap()
        val beforeLibrary = store.preferences.all.toMap()
        val invalid = listOf<() -> Unit>(
            { store.rename(saved.id, "   ") },
            { store.rename(saved.id, "名".repeat(41)) },
            { store.rename("missing", "名称") },
            { store.update(saved.id, List(14) { 0.0 }, "0", "0") },
            { store.update(saved.id, List(15) { Double.NaN }, "0", "0") },
            { store.update(saved.id, List(15) { 12.1 }, "0", "0") },
            { store.update(saved.id, List(15) { 0.0 }, "9", "0") },
            { store.update(saved.id, List(15) { 0.0 }, "0", "9") },
            { store.update("missing", List(15) { 0.0 }, "0", "0") },
            { store.duplicate("missing") },
        )
        invalid.forEach { action ->
            try { action(); fail("Invalid management operation must fail") }
            catch (_: IllegalArgumentException) { }
            assertEquals(beforeLibrary, store.preferences.all)
            assertEquals(beforeAudio, eq.all)
        }
        assertFalse(store.delete("missing"))
        idle()
        assertEquals(0, broadcasts)
    }
}
