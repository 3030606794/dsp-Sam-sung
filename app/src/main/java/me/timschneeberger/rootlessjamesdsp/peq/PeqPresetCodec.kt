package me.timschneeberger.rootlessjamesdsp.peq

import kotlinx.serialization.json.*
import java.util.UUID

data class PeqPreset(val id: String, val name: String, val state: PeqState)

/** Portable UTF-8 PEQ-only format; local identities are deliberately absent from exports. */
object PeqPresetCodec {
    const val FORMAT = "dsp-audio-peq-presets"
    const val VERSION = 1

    fun decode(json: String): List<PeqPreset> = decodeDocument(json, local = false)

    fun encode(presets: List<PeqPreset>): String = encodeDocument(presets, local = false)

    internal fun normalizeName(name: String): String {
        require(name.none { it.isISOControl() }) { "预设名称不能包含换行或控制字符" }
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized.length <= PeqPresetStore.MAX_NAME_LENGTH) {
            "预设名称需要 1–40 个字符"
        }
        require(normalized.indices.all { index ->
            val char = normalized[index]
            when {
                char.isHighSurrogate() -> index + 1 < normalized.length && normalized[index + 1].isLowSurrogate()
                char.isLowSurrogate() -> index > 0 && normalized[index - 1].isHighSurrogate()
                else -> true
            }
        }) { "预设名称包含无效字符" }
        return normalized
    }

    internal fun decodeDocument(json: String, local: Boolean): List<PeqPreset> {
        require(json.length <= PeqPresetStore.MAX_IMPORT_BYTES &&
            json.toByteArray(Charsets.UTF_8).size <= PeqPresetStore.MAX_IMPORT_BYTES) { "预设文件不能超过 4 MiB" }
        requireShallowDocument(json)
        val root = Json.parseToJsonElement(json) as? JsonObject
            ?: throw IllegalArgumentException("预设文件必须是 JSON 对象")
        require(root.string("format") == FORMAT) { "不是 dsp音效的 PEQ 预设文件" }
        val version = root["version"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == VERSION) { "不支持此预设文件版本" }
        val items = root["presets"] as? JsonArray ?: throw IllegalArgumentException("预设文件缺少 presets 列表")
        require(items.size in (if (local) 0 else 1)..PeqPresetStore.MAX_PRESETS) { "每个文件需要包含 1–20 个预设" }
        val result = items.map { item ->
            val entry = item as? JsonObject ?: throw IllegalArgumentException("每个预设必须是 JSON 对象")
            val name = normalizeName(entry.string("name"))
            val state = entry["state"] as? JsonObject ?: throw IllegalArgumentException("预设缺少均衡器参数")
            val id = if (local) {
                val stored = entry.string("id")
                require(stored.length == 36) { "本地预设标识无效" }
                val canonical = UUID.fromString(stored).toString()
                require(canonical.equals(stored, ignoreCase = true)) { "本地预设标识无效" }
                canonical
            } else UUID.randomUUID().toString()
            PeqPreset(id, name, PeqRepository.decode(state.toString()))
        }
        if (local) {
            require(result.map { it.id }.toSet().size == result.size) { "本地预设标识重复" }
            require(result.map { it.name }.toSet().size == result.size) { "本地预设名称重复" }
        }
        return result
    }

    internal fun encodeDocument(presets: List<PeqPreset>, local: Boolean): String {
        require(presets.size in (if (local) 0 else 1)..PeqPresetStore.MAX_PRESETS) { "每个文件需要包含 1–20 个预设" }
        return buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("presets", buildJsonArray {
                presets.forEach { preset -> add(buildJsonObject {
                    if (local) put("id", preset.id)
                    put("name", normalizeName(preset.name))
                    put("state", Json.parseToJsonElement(PeqRepository.encode(preset.state)))
                }) }
            })
        }.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= PeqPresetStore.MAX_IMPORT_BYTES) { "预设文件不能超过 4 MiB" }
        }
    }

    private fun JsonObject.string(key: String): String {
        val value = this[key] as? JsonPrimitive
        require(value != null && value.isString) { "预设字段 $key 必须是文字" }
        return value.content
    }

    /** The format needs six levels; bound hostile nesting before invoking the JSON parser. */
    private fun requireShallowDocument(json: String) {
        var inString = false
        var escaped = false
        var depth = 0
        json.forEach { char ->
            if (inString) {
                if (escaped) escaped = false else when (char) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            } else when (char) {
                '"' -> inString = true
                '{', '[' -> { depth++; require(depth <= 32) { "预设文件嵌套层级过多" } }
                '}', ']' -> depth--
            }
        }
    }
}
