package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class SurroundPreset(val id: String, val name: String, val settings: SurroundController.Settings)

/** A portable surround-only document. No enable state or factory-slot selection is imported. */
object SurroundPresetCodec {
    const val FORMAT = "dsp-audio-surround-presets"
    const val VERSION = 1

    fun encode(presets: List<SurroundPreset>): String = encodeDocument(presets, local = false)
    fun decode(json: String): List<SurroundPreset> = decodeDocument(json, local = false)

    /** The caller owns the stream. Size is bounded before allocating or decoding the document. */
    fun readImport(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                require(output.size() < SurroundPresetStore.MAX_IMPORT_BYTES) { SIZE_ERROR }
                output.write(single)
            } else {
                require(output.size() + count <= SurroundPresetStore.MAX_IMPORT_BYTES) { SIZE_ERROR }
                output.write(buffer, 0, count)
            }
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    internal fun normalizeName(name: String): String {
        require(name.none { it.isISOControl() }) { "预设名称不能包含换行或控制字符" }
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized.length <= SurroundPresetStore.MAX_NAME_LENGTH) {
            "预设名称需要 1–40 个字符"
        }
        require(normalized.indices.all { index ->
            when {
                normalized[index].isHighSurrogate() -> index + 1 < normalized.length && normalized[index + 1].isLowSurrogate()
                normalized[index].isLowSurrogate() -> index > 0 && normalized[index - 1].isHighSurrogate()
                else -> true
            }
        }) { "预设名称包含无效字符" }
        return normalized
    }

    internal fun validateSettings(settings: SurroundController.Settings) {
        require(settings == settings.sanitized()) { "环绕参数超出范围" }
    }

    internal fun decodeDocument(json: String, local: Boolean): List<SurroundPreset> {
        require(json.length <= SurroundPresetStore.MAX_IMPORT_BYTES &&
            json.toByteArray(Charsets.UTF_8).size <= SurroundPresetStore.MAX_IMPORT_BYTES) { SIZE_ERROR }
        requireShallowDocument(json)
        val root = Json.parseToJsonElement(json) as? JsonObject
            ?: throw IllegalArgumentException("预设文件必须是 JSON 对象")
        root.requireFields(setOf("format", "version", "units", "presets"))
        require(root.string("format") == FORMAT) { "不是 dsp音效的环绕预设文件" }
        require(root.integer("version") == VERSION) { "不支持此环绕预设版本" }
        val units = root["units"] as? JsonObject ?: throw IllegalArgumentException("预设缺少参数单位")
        units.requireFields(setOf("frequency", "attenuation", "delay"))
        require(units.string("frequency") == "Hz" && units.string("attenuation") == "milli-dB" &&
            units.string("delay") == "us") { "预设单位必须为 Hz、milli-dB 和 us" }
        val entries = root["presets"] as? JsonArray ?: throw IllegalArgumentException("预设缺少 presets 列表")
        require(entries.size in (if (local) 0 else 1)..SurroundPresetStore.MAX_PRESETS) {
            "每个文件需要包含 1–20 个环绕预设"
        }
        val result = entries.map { element ->
            val entry = element as? JsonObject ?: throw IllegalArgumentException("每个预设必须是 JSON 对象")
            entry.requireFields(if (local) setOf("id", "name", "settings") else setOf("name", "settings"))
            val name = normalizeName(entry.string("name"))
            val values = entry["settings"] as? JsonObject ?: throw IllegalArgumentException("预设缺少环绕参数")
            values.requireFields(setOf("lowHz", "highHz", "attenuationMilliDb", "delayUs"))
            val settings = SurroundController.Settings(values.integer("lowHz"), values.integer("highHz"),
                values.integer("attenuationMilliDb"), values.integer("delayUs"))
            validateSettings(settings)
            val id = if (local) canonicalId(entry.string("id")) else UUID.randomUUID().toString()
            SurroundPreset(id, name, settings)
        }
        if (local) validateLocalIdentities(result)
        return result
    }

    internal fun encodeDocument(presets: List<SurroundPreset>, local: Boolean): String {
        require(presets.size in (if (local) 0 else 1)..SurroundPresetStore.MAX_PRESETS) {
            "每个文件需要包含 1–20 个环绕预设"
        }
        if (local) validateLocalIdentities(presets)
        return buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("units", buildJsonObject {
                put("frequency", "Hz"); put("attenuation", "milli-dB"); put("delay", "us")
            })
            put("presets", buildJsonArray {
                presets.forEach { preset ->
                    validateSettings(preset.settings)
                    add(buildJsonObject {
                        if (local) put("id", canonicalId(preset.id))
                        put("name", normalizeName(preset.name))
                        put("settings", buildJsonObject {
                            put("lowHz", preset.settings.lowHz)
                            put("highHz", preset.settings.highHz)
                            put("attenuationMilliDb", preset.settings.attenuationMilliDb)
                            put("delayUs", preset.settings.delayUs)
                        })
                    })
                }
            })
        }.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= SurroundPresetStore.MAX_IMPORT_BYTES) { SIZE_ERROR }
        }
    }

    private fun canonicalId(value: String): String {
        require(value.length == 36) { "本地预设标识无效" }
        val canonical = UUID.fromString(value).toString()
        require(canonical.equals(value, ignoreCase = true)) { "本地预设标识无效" }
        return canonical
    }

    private fun validateLocalIdentities(presets: List<SurroundPreset>) {
        require(presets.map { canonicalId(it.id) }.toSet().size == presets.size) { "本地预设标识重复" }
        require(presets.map { normalizeName(it.name) }.toSet().size == presets.size) { "本地预设名称重复" }
    }

    private fun JsonObject.requireFields(expected: Set<String>) {
        require(keys == expected) { "环绕预设字段缺失或包含未知字段" }
    }

    private fun JsonObject.string(key: String): String {
        val value = this[key] as? JsonPrimitive
        require(value != null && value.isString) { "预设字段 $key 必须是文字" }
        return value.content
    }

    private fun JsonObject.integer(key: String): Int {
        val value = this[key] as? JsonPrimitive
        require(value != null && !value.isString && value.intOrNull != null) { "预设字段 $key 必须是整数" }
        return value.int
    }

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
                '{', '[' -> { depth++; require(depth <= 16) { "预设文件嵌套层级过多" } }
                '}', ']' -> depth--
            }
        }
    }

    private const val SIZE_ERROR = "环绕预设文件不能超过 256 KiB"
}
