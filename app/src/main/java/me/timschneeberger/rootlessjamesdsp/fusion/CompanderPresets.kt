package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class CompanderPreset(val id: String, val name: String, val settings: CompanderParameters.Settings)

/** Compander documents preserve the original curve and Float controls, never effect power or temporary tools. */
object CompanderPresetCodec {
    const val FORMAT = "dsp-audio-compander-presets"
    const val VERSION = 1
    const val MAX_BYTES = 65536
    private const val UNITS = "legacy-float-seconds,legacy-float-granularity,Hz,milli-coefficient,tenth-dB,tenth-ms,ms,tenth-ratio,tenth-percent"
    private const val SIZE_ERROR = "动态压扩器预设文件不能超过 64 KiB"

    fun name(value: String): String {
        require(value.none { it.isISOControl() }) { "名称不能包含换行或控制字符" }
        val normalized = value.trim()
        require(normalized.isNotEmpty() && normalized.length <= 40) { "名称需要 1–40 个字符" }
        require(normalized.indices.all { i -> when {
            normalized[i].isHighSurrogate() -> i + 1 < normalized.length && normalized[i + 1].isLowSurrogate()
            normalized[i].isLowSurrogate() -> i > 0 && normalized[i - 1].isHighSurrogate()
            else -> true
        } }) { "名称包含无效字符" }
        return normalized
    }

    /** The caller owns the input stream. Decode only after enforcing the byte limit. */
    fun read(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                require(output.size() < MAX_BYTES) { SIZE_ERROR }
                output.write(single)
            } else {
                require(output.size() + count <= MAX_BYTES) { SIZE_ERROR }
                output.write(buffer, 0, count)
            }
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    private fun JsonObject.fields(expected: Set<String>) {
        require(keys == expected) { "动态压扩器预设字段缺失或包含未知字段" }
    }
    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("字段 $key 必须为文本")
    private fun JsonObject.integer(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw IllegalArgumentException("字段 $key 必须为整数")
    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw IllegalArgumentException("字段 $key 必须为开关")

    private fun JsonObject.decimal(key: String): Float =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull?.takeIf { it.isFinite() }
            ?: throw IllegalArgumentException("字段 $key 必须为有限数值")

    fun decode(json: String, allowEmpty: Boolean = false): List<CompanderPreset> {
        require(json.length <= MAX_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { SIZE_ERROR }
        // kotlinx.serialization keeps the last duplicate key; reject ambiguous files before parsing.
        UniqueFields(json).validate()
        val root = Json.parseToJsonElement(json) as? JsonObject
            ?: throw IllegalArgumentException("预设必须为 JSON 对象")
        root.fields(setOf("format", "version", "units", "presets"))
        val version = root.integer("version")
        require(root.string("format") == FORMAT && version == VERSION) {
            "不是受支持的动态压扩器预设文件"
        }
        require(root.string("units") == UNITS) { "动态压扩器预设参数单位不受支持" }
        val entries = root["presets"] as? JsonArray ?: throw IllegalArgumentException("缺少预设列表")
        require(entries.size in (if (allowEmpty) 0 else 1)..CompanderPresetStore.MAX_PRESETS) {
            "每个文件需要包含 1–20 个预设"
        }
        val schema = CompanderParameters.defaults().values()
        val presets = entries.map { element ->
            val entry = element as? JsonObject ?: throw IllegalArgumentException("预设条目必须为对象")
            entry.fields(setOf("id", "name", "settings"))
            val id = entry.string("id")
            require(id.length == 36 && UUID.fromString(id).toString() == id) { "预设标识无效" }
            val values = entry["settings"] as? JsonObject ?: throw IllegalArgumentException("缺少动态压扩器参数")
            values.fields(schema.keys)
            val raw: Map<String, Any> = schema.mapValues { (key, default) ->
                when (default) {
                    is Boolean -> values.boolean(key)
                    is Int -> values.integer(key)
                    is Float -> values.decimal(key)
                    is String -> values.string(key)
                    else -> throw IllegalArgumentException("不受支持的参数类型")
                }
            }
            val settings = CompanderParameters.capture(raw)
            require(settings.valid() && settings.values() == raw) { "动态压扩器参数超出范围" }
            CompanderPreset(id, name(entry.string("name")), settings)
        }
        require(presets.map { it.id }.toSet().size == presets.size &&
            presets.map { it.name }.toSet().size == presets.size) { "文件内预设标识或名称重复" }
        return presets
    }

    fun encode(presets: List<CompanderPreset>, allowEmpty: Boolean = false): String {
        require(presets.all { it.settings.valid() }) { "动态压扩器参数超出范围" }
        val encoded = buildJsonObject {
            put("format", FORMAT); put("version", VERSION); put("units", UNITS)
            putJsonArray("presets") {
                presets.forEach { preset -> add(buildJsonObject {
                    put("id", preset.id); put("name", name(preset.name))
                    putJsonObject("settings") {
                        preset.settings.values().forEach { (key, value) -> when (value) {
                            is Int -> put(key, value)
                            is Float -> put(key, value)
                            is Boolean -> put(key, value)
                            is String -> put(key, value)
                            else -> throw IllegalArgumentException("不受支持的参数类型")
                        } }
                    }
                }) }
            }
        }.toString()
        decode(encoded, allowEmpty)
        return encoded
    }

    /** A bounded structural pass with decoded key identities, including escaped duplicates. */
    private class UniqueFields(private val input: String) {
        private var at = 0
        private fun whitespace() { while (at < input.length && input[at] in " \t\r\n") at++ }
        private fun expect(char: Char) {
            whitespace(); require(at < input.length && input[at] == char) { "预设 JSON 格式无效" }; at++
        }
        private fun string(): String {
            whitespace(); val start = at; expect('"')
            var escaped = false
            while (at < input.length) {
                val char = input[at++]
                if (escaped) escaped = false else when (char) {
                    '\\' -> escaped = true
                    '"' -> return Json.parseToJsonElement(input.substring(start, at)).jsonPrimitive.content
                }
            }
            throw IllegalArgumentException("预设 JSON 文本未结束")
        }
        private fun value(depth: Int) {
            whitespace(); require(depth <= 8) { "预设嵌套层级过多" }
            require(at < input.length) { "预设 JSON 被截断" }
            when (input[at]) {
                '{' -> {
                    at++; whitespace(); val keys = mutableSetOf<String>()
                    if (at < input.length && input[at] == '}') { at++; return }
                    while (true) {
                        val key = string(); require(keys.add(key)) { "预设 JSON 包含重复字段：$key" }
                        expect(':'); value(depth + 1); whitespace()
                        require(at < input.length) { "预设 JSON 被截断" }
                        if (input[at] == '}') { at++; return }
                        expect(',')
                    }
                }
                '[' -> {
                    at++; whitespace()
                    if (at < input.length && input[at] == ']') { at++; return }
                    while (true) {
                        value(depth + 1); whitespace(); require(at < input.length) { "预设 JSON 被截断" }
                        if (input[at] == ']') { at++; return }
                        expect(',')
                    }
                }
                '"' -> string()
                else -> {
                    val start = at
                    while (at < input.length && input[at] !in ",]} \t\r\n") at++
                    require(at > start) { "预设 JSON 格式无效" }
                    val token = input.substring(start, at)
                    require(token == "true" || token == "false" || token == "null" || NUMBER.matches(token)) {
                        "预设 JSON 数值或常量无效"
                    }
                }
            }
        }
        fun validate() {
            value(0); whitespace(); require(at == input.length) { "预设 JSON 末尾包含额外内容" }
        }
        companion object { private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") }
    }
}

/** Favorites preserve the complete foundation separately from profiles and temporary tools. */
class CompanderPresetStore internal constructor(
    private val file: File,
    private val writePayload: (FileOutputStream, ByteArray) -> Unit = { stream, bytes -> stream.write(bytes) },
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))
    companion object {
        const val FILE_NAME = "compander-favorites-v1.json"
        const val MAX_PRESETS = 20
        private val lock = Any()
    }
    fun load(): List<CompanderPreset> = synchronized(lock) {
        val atomic = AtomicFile(file)
        val input = try { atomic.openRead() } catch (missing: FileNotFoundException) {
            if (!file.exists() && !File(file.path + ".bak").exists()) return@synchronized emptyList()
            throw missing
        }
        input.use { CompanderPresetCodec.decode(CompanderPresetCodec.read(it), true) }
    }
    private fun write(values: List<CompanderPreset>) {
        val bytes = CompanderPresetCodec.encode(values, true).toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try {
            writePayload(output, bytes); output.fd.sync(); atomic.finishWrite(output)
            if (!file.isFile || File(file.path + ".new").exists() || !file.readBytes().contentEquals(bytes))
                throw IOException("无法提交动态压扩器预设，请检查存储空间后重试")
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    fun save(name: String, settings: CompanderParameters.Settings): CompanderPreset = synchronized(lock) {
        val normalized = CompanderPresetCodec.name(name); require(settings.valid())
        val current = load(); require(current.size < MAX_PRESETS) { "个人预设已满 20 项" }
        require(current.none { it.name == normalized }) { "名称已存在" }
        val preset = CompanderPreset(UUID.randomUUID().toString(), normalized, settings)
        write(current + preset); preset
    }
    private fun List<CompanderPreset>.find(id: String) = firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")
    fun rename(id: String, name: String): CompanderPreset = synchronized(lock) {
        val normalized = CompanderPresetCodec.name(name); val current = load()
        require(current.none { it.id != id && it.name == normalized }) { "名称已存在" }
        val updated = current.find(id).copy(name = normalized)
        write(current.map { if (it.id == id) updated else it }); updated
    }
    fun overwrite(id: String, settings: CompanderParameters.Settings): CompanderPreset = synchronized(lock) {
        require(settings.valid()); val current = load(); val updated = current.find(id).copy(settings = settings)
        write(current.map { if (it.id == id) updated else it }); updated
    }
    fun delete(id: String) = synchronized(lock) {
        val current = load(); current.find(id); write(current.filterNot { it.id == id })
    }
    fun export(id: String? = null): String = synchronized(lock) {
        val current = load(); CompanderPresetCodec.encode(if (id == null) current else listOf(current.find(id)))
    }
    fun importJson(json: String): Int = synchronized(lock) {
        val incoming = CompanderPresetCodec.decode(json); val current = load()
        require(current.size + incoming.size <= MAX_PRESETS) { "导入后将超过 20 项，请先删除部分预设" }
        val names = current.map { it.name }.toMutableSet()
        val added = incoming.map { preset ->
            var name = preset.name; var index = 2
            while (name in names) {
                val ending = " ($index)"; var prefix = preset.name.take(40 - ending.length).trimEnd()
                if (prefix.lastOrNull()?.isHighSurrogate() == true) prefix = prefix.dropLast(1)
                name = prefix + ending; index++
            }
            names += name; preset.copy(id = UUID.randomUUID().toString(), name = name)
        }
        write(current + added); added.size
    }
}
