package me.timschneeberger.rootlessjamesdsp.eq

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.view.EqPreview
import java.util.UUID
import kotlin.math.abs

/** Personal presets and home shortcuts are separate from all DSP/profile preference namespaces. */
class EqPresetStore(private val context: Context) {
    val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    enum class ShortcutResult { ADDED, ALREADY_ADDED, FULL, NOT_FOUND }
    private data class Library(
        val users: List<EqPreset> = emptyList(),
        val shortcuts: List<String> = emptyList(),
        val lastApplied: String? = null,
        val overrides: List<EqPreset> = emptyList(),
        val hiddenBuiltIns: List<String> = emptyList(),
    )
    private data class Audio(val gains: List<Double>, val filterType: String, val interpolation: String)
    @Suppress("DEPRECATION")
    private val audioPreferences get() = context.getSharedPreferences(Constants.PREF_EQ, Context.MODE_MULTI_PROCESS)
    private val bandKey get() = context.getString(R.string.key_eq_bands)
    private val filterKey get() = context.getString(R.string.key_eq_filter_type)
    private val interpolationKey get() = context.getString(R.string.key_eq_interpolation)

    private fun builtIns(): List<EqPreset> {
        val names = context.resources.getStringArray(R.array.equalizer_preset_modes)
        val gains = context.resources.getStringArray(R.array.equalizer_preset_values)
        return names.mapIndexed { index, name ->
            EqPresetCodec.validate(EqPreset("builtin-$index", name,
                gains[index].split(';').map(String::toDouble), builtIn = true))
        }
    }

    private fun visiblePresets(library: Library): List<EqPreset> {
        val overrides = library.overrides.associateBy { it.id }
        return builtIns().filterNot { it.id in library.hiddenBuiltIns }
            .map { overrides[it.id] ?: it } + library.users
    }

    fun all(): List<EqPreset> = synchronized(lock) { visiblePresets(readLibrary()) }
    fun find(id: String): EqPreset? = all().firstOrNull { it.id == id }

    fun capture(name: String): EqPreset = synchronized(lock) {
        val audio = readAudio()
        EqPresetCodec.validate(EqPreset(newId(), name.trim(), audio.gains, audio.filterType, audio.interpolation))
    }

    fun saveCurrent(name: String): EqPreset = synchronized(lock) {
        val preset = capture(name)
        append(preset)
        preset
    }

    fun rename(id: String, name: String): EqPreset = synchronized(lock) {
        val library = readLibrary()
        val original = requireNotNull(visiblePresets(library).firstOrNull { it.id == id }) { "预设不存在" }
        val renamed = EqPresetCodec.validate(original.copy(name = name.trim()))
        replace(library, renamed)
        renamed
    }

    fun update(id: String, gains: List<Double>, filterType: String?, interpolation: String?): EqPreset = synchronized(lock) {
        val library = readLibrary()
        val original = requireNotNull(visiblePresets(library).firstOrNull { it.id == id }) { "预设不存在" }
        val updated = EqPresetCodec.validate(original.copy(gains = gains.toList(),
            filterType = filterType, interpolation = interpolation))
        replace(library, updated)
        updated
    }

    fun duplicate(id: String): EqPreset = synchronized(lock) {
        val library = readLibrary()
        val presets = visiblePresets(library)
        val original = requireNotNull(presets.firstOrNull { it.id == id }) { "预设不存在" }
        require(library.users.size < MAX_PERSONAL_PRESETS) { "个人预设已达到 128 个上限" }
        val names = presets.map { it.name }.toSet()
        var index = 1
        var name: String
        do {
            val suffix = if (index == 1) " 副本" else " 副本$index"
            var stem = original.name.take(EqPresetCodec.MAX_NAME_LENGTH - suffix.length)
            if (stem.isNotEmpty() && Character.isHighSurrogate(stem.last())) stem = stem.dropLast(1)
            name = stem.trimEnd() + suffix
            index++
        } while (name in names)
        val copied = EqPresetCodec.validate(original.copy(id = newId(), name = name, builtIn = false))
        writeLibrary(library.copy(users = library.users + copied))
        copied
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val library = readLibrary()
        val preset = visiblePresets(library).firstOrNull { it.id == id } ?: return@synchronized false
        writeLibrary(library.copy(
            users = library.users.filterNot { it.id == id },
            overrides = library.overrides.filterNot { it.id == id },
            hiddenBuiltIns = if (preset.builtIn) library.hiddenBuiltIns + id else library.hiddenBuiltIns,
            shortcuts = library.shortcuts.filterNot { it == id },
            lastApplied = library.lastApplied.takeUnless { it == id },
        ))
        true
    }

    private fun replace(library: Library, preset: EqPreset) {
        val updated = if (preset.builtIn)
            library.copy(overrides = library.overrides.filterNot { it.id == preset.id } + preset)
        else library.copy(users = library.users.map { if (it.id == preset.id) preset else it })
        writeLibrary(updated)
    }

    fun shortcuts(): List<EqPreset> = synchronized(lock) {
        val library = readLibrary()
        val byId = visiblePresets(library).associateBy { it.id }
        library.shortcuts.mapNotNull(byId::get)
    }

    fun addShortcut(id: String): ShortcutResult = synchronized(lock) {
        val library = readLibrary()
        when {
            visiblePresets(library).none { it.id == id } -> ShortcutResult.NOT_FOUND
            id in library.shortcuts -> ShortcutResult.ALREADY_ADDED
            library.shortcuts.size >= MAX_SHORTCUTS -> ShortcutResult.FULL
            else -> { writeLibrary(library.copy(shortcuts = library.shortcuts + id)); ShortcutResult.ADDED }
        }
    }

    fun removeShortcut(id: String): Boolean = synchronized(lock) {
        val library = readLibrary()
        if (id !in library.shortcuts) false else {
            writeLibrary(library.copy(shortcuts = library.shortcuts - id))
            true
        }
    }

    fun apply(id: String): Boolean = synchronized(lock) {
        val library = readLibrary()
        val preset = visiblePresets(library).firstOrNull { it.id == id } ?: return@synchronized false
        val edit = audioPreferences.edit().putString(bandKey, EqPreview.encode(preset.gains.toDoubleArray()))
        // Original built-ins have null options. Edited built-ins restore explicitly saved options.
        preset.filterType?.let { edit.putString(filterKey, it) }
        preset.interpolation?.let { edit.putString(interpolationKey, it) }
        edit.apply()
        writeLibrary(library.copy(lastApplied = id))
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        true
    }

    fun selectedShortcutId(): String? = synchronized(lock) {
        val library = readLibrary()
        val presets = visiblePresets(library).associateBy { it.id }
        val audio = runCatching { readAudio() }.getOrNull() ?: return@synchronized null
        fun matches(preset: EqPreset) = preset.gains.zip(audio.gains).all { abs(it.first - it.second) <= 1e-6 } &&
            (preset.filterType == null || preset.filterType == audio.filterType) &&
            (preset.interpolation == null || preset.interpolation == audio.interpolation)
        library.lastApplied?.takeIf { id -> id in library.shortcuts && presets[id]?.let(::matches) == true }
            ?: library.shortcuts.mapNotNull(presets::get).filter(::matches).singleOrNull()?.id
    }

    fun exportJson(id: String? = null): String = synchronized(lock) {
        val preset = if (id == null) capture("当前设置") else requireNotNull(find(id)) { "预设不存在" }
        EqPresetCodec.encode(preset)
    }

    fun importJson(text: String): EqPreset = synchronized(lock) {
        // Validate every field before touching either the preset library or audio preferences.
        val preset = EqPresetCodec.decode(text).copy(id = newId(), builtIn = false)
        append(preset)
        preset
    }

    private fun append(preset: EqPreset) {
        val library = readLibrary()
        require(library.users.size < MAX_PERSONAL_PRESETS) { "个人预设已达到 128 个上限" }
        writeLibrary(library.copy(users = library.users + preset))
    }

    private fun readAudio(): Audio {
        val values = audioPreferences.all
        fun string(key: String, fallback: String): String {
            val value = values[key] ?: return fallback
            require(value is String) { "当前均衡器参数格式无效" }
            return value
        }
        val raw = string(bandKey, EqPreview.defaultValue).split(';')
        require(raw.size == 30) { "当前均衡器频段格式无效" }
        val gains = raw.drop(15).map { requireNotNull(it.toDoubleOrNull()) { "当前均衡器增益格式无效" } }
        require(gains.all { it.isFinite() && it in -12.0..12.0 }) { "当前均衡器增益超出范围" }
        return Audio(gains, string(filterKey, "0"), string(interpolationKey, "0"))
    }

    private fun readLibrary(): Library {
        val text = preferences.getString(LIBRARY_KEY, null) ?: return Library()
        try {
            val root = Json.parseToJsonElement(text) as JsonObject
            val version = (root["version"] as? JsonPrimitive)?.also { require(!it.isString) }?.intOrNull
            require(version == 1 || version == 2)
            val baseKeys = setOf("version", "users", "shortcuts", "lastAppliedId")
            require(root.keys == (if (version == 1) baseKeys else baseKeys + setOf("overrides", "hiddenBuiltInIds")))
            val builtInIds = builtIns().map { it.id }.toSet()
            fun entries(array: JsonArray, builtIn: Boolean): List<EqPreset> = array.map { value ->
                val entry = value as JsonObject
                require(entry.keys == setOf("id", "preset"))
                val id = (entry["id"] as JsonPrimitive).also { require(it.isString) }.content
                require(if (builtIn) id in builtInIds else id.startsWith("user-") && id.length <= 64)
                EqPresetCodec.decode(entry.getValue("preset").toString()).copy(id = id, builtIn = builtIn)
            }
            val users = entries(root["users"] as JsonArray, false)
            require(users.size <= MAX_PERSONAL_PRESETS && users.map { it.id }.distinct().size == users.size)
            val shortcuts = (root["shortcuts"] as JsonArray).map {
                (it as JsonPrimitive).also { value -> require(value.isString) }.content
            }
            require(shortcuts.size <= MAX_SHORTCUTS && shortcuts.distinct().size == shortcuts.size)
            val last = root["lastAppliedId"].let {
                if (it == JsonNull) null else (it as JsonPrimitive).also { value -> require(value.isString) }.content
            }
            val overrides = if (version == 2) entries(root["overrides"] as JsonArray, true) else emptyList()
            require(overrides.size <= builtInIds.size && overrides.map { it.id }.distinct().size == overrides.size)
            val hidden = if (version == 2) (root["hiddenBuiltInIds"] as JsonArray).map {
                (it as JsonPrimitive).also { value -> require(value.isString && value.content in builtInIds) }.content
            } else emptyList()
            require(hidden.distinct().size == hidden.size)
            return Library(users, shortcuts, last, overrides, hidden)
        } catch (invalid: Exception) {
            throw IllegalArgumentException("个人预设库格式无效，已保留原内容", invalid)
        }
    }

    private fun writeLibrary(library: Library) {
        val json = buildJsonObject {
            put("version", 2)
            put("users", buildJsonArray { library.users.forEach { preset -> add(buildJsonObject {
                put("id", preset.id)
                put("preset", Json.parseToJsonElement(EqPresetCodec.encode(preset)))
            }) } })
            put("shortcuts", buildJsonArray { library.shortcuts.forEach { add(JsonPrimitive(it)) } })
            put("lastAppliedId", library.lastApplied?.let(::JsonPrimitive) ?: JsonNull)
            put("overrides", buildJsonArray { library.overrides.forEach { preset -> add(buildJsonObject {
                put("id", preset.id)
                put("preset", Json.parseToJsonElement(EqPresetCodec.encode(preset)))
            }) } })
            put("hiddenBuiltInIds", buildJsonArray { library.hiddenBuiltIns.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        preferences.edit().putString(LIBRARY_KEY, json).apply()
    }

    companion object {
        const val PREFERENCES = "eq_preset_library"
        const val MAX_SHORTCUTS = 5
        const val MAX_PERSONAL_PRESETS = 128
        private const val LIBRARY_KEY = "library_json"
        private val lock = Any()
        private fun newId() = "user-${UUID.randomUUID()}"
    }
}
