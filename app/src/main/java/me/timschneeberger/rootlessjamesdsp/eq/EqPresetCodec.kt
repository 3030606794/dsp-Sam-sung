package me.timschneeberger.rootlessjamesdsp.eq

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class EqPreset(
    val id: String,
    val name: String,
    val gains: List<Double>,
    val filterType: String? = null,
    val interpolation: String? = null,
    val builtIn: Boolean = false,
)

/** Portable single-preset files contain audio parameters, never library IDs or shortcut metadata. */
object EqPresetCodec {
    const val KIND = "dsp-audio-eq-preset"
    const val VERSION = 1
    const val MAX_JSON_BYTES = 256 * 1024
    const val MAX_NAME_LENGTH = 40
    private val fields = setOf("kind", "version", "name", "gains", "filterType", "interpolation")
    private val filters = setOf("0", "1", "2", "3", "4", "5")
    private val interpolators = setOf("0", "1")

    fun validate(preset: EqPreset): EqPreset {
        require(preset.name.isNotBlank() && preset.name.length <= MAX_NAME_LENGTH &&
            preset.name.none { it.code < 32 || it.code == 127 }) { "预设名称需为 1～40 个字符" }
        require(preset.gains.size == 15 && preset.gains.all { it.isFinite() && it in -12.0..12.0 }) {
            "预设必须包含 15 个 −12～+12 dB 的有限增益值"
        }
        require(preset.filterType == null || preset.filterType in filters) { "不支持的滤波器类型" }
        require(preset.interpolation == null || preset.interpolation in interpolators) { "不支持的插值器" }
        return preset
    }

    fun encode(preset: EqPreset): String {
        validate(preset)
        return buildJsonObject {
            put("kind", KIND)
            put("version", VERSION)
            put("name", preset.name.trim())
            put("gains", buildJsonArray { preset.gains.forEach { add(JsonPrimitive(it)) } })
            put("filterType", preset.filterType?.let(::JsonPrimitive) ?: JsonNull)
            put("interpolation", preset.interpolation?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
    }

    fun decode(text: String): EqPreset {
        require(text.length <= MAX_JSON_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
            "预设文件超过 256 KiB"
        }
        // Reject excessive nesting before the JSON parser can recurse into an invalid file.
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) { "预设文件结构无效" } }
                '}', ']' -> { depth--; require(depth >= 0) { "预设文件结构无效" } }
            }
        }
        val root = try { Json.parseToJsonElement(text) as? JsonObject }
            catch (invalid: Exception) { throw IllegalArgumentException("预设文件不是有效的 JSON", invalid) }
        requireNotNull(root) { "预设文件必须是单个 JSON 对象" }
        require(root.keys == fields) { "预设文件字段缺失或包含未知字段" }
        fun string(key: String): String {
            val value = root[key] as? JsonPrimitive
            require(value != null && value.isString) { "预设字段 $key 必须是文字" }
            return value.content
        }
        require(string("kind") == KIND) { "不是曲线均衡器预设文件" }
        val version = root["version"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == VERSION) { "不支持的预设文件版本" }
        val values = root["gains"] as? JsonArray
        require(values != null && values.size == 15) { "预设必须包含 15 个频段" }
        val gains = values.map { entry ->
            val value = entry as? JsonPrimitive
            require(value != null && !value.isString) { "频段增益必须是数值" }
            requireNotNull(value.doubleOrNull) { "频段增益必须是数值" }
        }
        fun optional(key: String): String? = if (root[key] == JsonNull) null else string(key)
        return validate(EqPreset("", string("name").trim(), gains, optional("filterType"), optional("interpolation")))
    }
}
