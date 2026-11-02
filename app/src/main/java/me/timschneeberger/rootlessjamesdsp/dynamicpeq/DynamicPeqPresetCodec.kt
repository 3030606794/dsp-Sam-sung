package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import kotlinx.serialization.json.*
import java.util.UUID
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqRepository.Companion.string
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqRepository.Companion.number

data class DynamicPeqPreset(val id: String, val name: String, val state: DynamicPeqState)

/** Separate portable format: static PEQ and other effects cannot be imported accidentally. */
object DynamicPeqPresetCodec {
    const val FORMAT = "dsp-audio-dynamic-peq-presets"
    const val VERSION = 1

    fun encode(presets: List<DynamicPeqPreset>): String = encodeDocument(presets, false)
    fun decode(json: String): List<DynamicPeqPreset> = decodeDocument(json, false)

    internal fun normalizeName(name: String): String {
        require(name.none { it.isISOControl() }) { "预设名称不能包含换行或控制字符" }
        val value = name.trim()
        require(value.length in 1..DynamicPeqPresetStore.MAX_NAME_LENGTH) { "预设名称需要 1–40 个字符" }
        require(value.indices.all { i -> when {
            value[i].isHighSurrogate() -> i + 1 < value.length && value[i + 1].isLowSurrogate()
            value[i].isLowSurrogate() -> i > 0 && value[i - 1].isHighSurrogate()
            else -> true
        } }) { "预设名称包含无效字符" }
        return value
    }

    internal fun encodeDocument(presets: List<DynamicPeqPreset>, local: Boolean): String {
        require(presets.size in (if (local) 0 else 1)..DynamicPeqPresetStore.MAX_PRESETS) { "每个文件需要包含 1–20 个预设" }
        require(!local || presets.map { it.id }.toSet().size == presets.size) { "本地预设标识重复" }
        require(!local || presets.map { normalizeName(it.name) }.toSet().size == presets.size) { "本地预设名称重复" }
        return buildJsonObject {
            put("format", FORMAT); put("version", VERSION)
            put("presets", buildJsonArray {
                presets.forEach { preset -> add(buildJsonObject {
                    if (local) put("id", canonicalId(preset.id))
                    put("name", normalizeName(preset.name))
                    put("state", Json.parseToJsonElement(DynamicPeqRepository.encode(preset.state)))
                }) }
            })
        }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= DynamicPeqPresetStore.MAX_IMPORT_BYTES) { "预设文件不能超过 2 MiB" } }
    }

    internal fun decodeDocument(json: String, local: Boolean): List<DynamicPeqPreset> {
        require(json.length <= DynamicPeqPresetStore.MAX_IMPORT_BYTES &&
            json.toByteArray(Charsets.UTF_8).size <= DynamicPeqPresetStore.MAX_IMPORT_BYTES) { "预设文件不能超过 2 MiB" }
        DynamicPeqRepository.requireShallowJson(json)
        val root = Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("预设文件需要是 JSON 对象")
        require(root.string("format") == FORMAT) { "不是动态 PEQ 预设文件" }
        require(root.number("version") == VERSION.toDouble()) { "不支持此预设版本" }
        val array = root["presets"] as? JsonArray ?: throw IllegalArgumentException("缺少预设列表")
        require(array.size in (if (local) 0 else 1)..DynamicPeqPresetStore.MAX_PRESETS) { "每个文件需要包含 1–20 个预设" }
        val result = array.map { item ->
            val entry = item as? JsonObject ?: throw IllegalArgumentException("预设格式无效")
            val state = entry["state"] as? JsonObject ?: throw IllegalArgumentException("缺少动态 PEQ 参数")
            DynamicPeqPreset(if (local) canonicalId(entry.string("id")) else UUID.randomUUID().toString(),
                normalizeName(entry.string("name")), DynamicPeqRepository.decode(state.toString()))
        }
        require(!local || result.map { it.id }.toSet().size == result.size) { "本地预设标识重复" }
        require(!local || result.map { it.name }.toSet().size == result.size) { "本地预设名称重复" }
        return result
    }

    private fun canonicalId(id: String): String {
        require(id.length == 36) { "本地预设标识无效" }
        return UUID.fromString(id).toString().also { require(it.equals(id, true)) { "本地预设标识无效" } }
    }
}
