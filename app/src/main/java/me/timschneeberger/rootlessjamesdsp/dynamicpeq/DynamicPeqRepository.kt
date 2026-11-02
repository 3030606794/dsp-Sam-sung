package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import kotlinx.serialization.json.*
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.xmlpull.v1.XmlPullParser
import java.io.File

data class DynamicPeqLoadResult(val state: DynamicPeqState, val hasSavedState: Boolean, val corrupt: Boolean, val error: String? = null)

/** dsp_ namespace participates in device/global presets; static PEQ storage is never accessed. */
class DynamicPeqRepository(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(PreferenceCache.getPreferences(context, Constants.PREF_DYNAMIC_PEQ))

    fun load(): DynamicPeqState = loadResult().state

    fun loadResult(): DynamicPeqLoadResult = synchronized(lock) {
        val values = preferences.all
        if (!values.containsKey(KEY_STATE) && !values.containsKey(KEY_ENABLED))
            return@synchronized DynamicPeqLoadResult(DynamicPeqModel.defaultState(), false, false)
        try {
            DynamicPeqLoadResult(stateFromPreferences(values), true, false)
        } catch (error: RuntimeException) {
            // Preserve damaged data and bypass this module rather than modifying another effect.
            DynamicPeqLoadResult(DynamicPeqModel.defaultState(), true, true, error.message)
        }
    }

    fun save(state: DynamicPeqState, synchronous: Boolean = true): Boolean = synchronized(lock) {
        val edit = preferences.edit().putString(KEY_STATE, encode(state)).putBoolean(KEY_ENABLED, state.enabled)
        if (synchronous) edit.commit() else { edit.apply(); true }
    }

    fun hasSavedState(): Boolean = preferences.contains(KEY_STATE)
    fun importJson(json: String): DynamicPeqState = decode(json)
    fun exportJson(state: DynamicPeqState): String = encode(state)

    /**
     * commit waits for this SharedPreferences' earlier apply writes before returning.
     * Preset loading must use this API instead of copying XML over a pending apply:
     * both the in-memory cache and the final ordered disk write then belong to the new route.
     * null represents an older preset that does not include this independent module.
     */
    fun replaceFromPreset(state: DynamicPeqState?): Boolean = synchronized(lock) {
        if (state != null) return@synchronized save(state, synchronous = true)
        preferences.edit().clear().commit()
    }

    /** Complete only this namespace's pending writes before a device/global archive reads XML. */
    fun flush(): Boolean = synchronized(lock) { preferences.edit().commit() }

    companion object {
        const val KEY_STATE = "dynamic_peq_state"
        const val KEY_ENABLED = "dynamic_peq_enable"
        const val SCHEMA = 1
        const val MAX_JSON_LENGTH = 64 * 1024
        private val lock = Any()

        private fun stateFromPreferences(values: Map<String, *>): DynamicPeqState {
            require(!values.containsKey(KEY_ENABLED) || values[KEY_ENABLED] is Boolean) { "动态 PEQ 开关格式无效" }
            val state = if (values.containsKey(KEY_STATE)) {
                val text = values[KEY_STATE] as? String ?: throw IllegalArgumentException("动态 PEQ 参数格式无效")
                decode(text)
            } else DynamicPeqModel.defaultState()
            return state.copy(enabled = values[KEY_ENABLED] as? Boolean ?: state.enabled)
        }

        /** Validate extracted XML without modifying current storage or accepting external entities. */
        fun readPresetPreferences(file: File): DynamicPeqState {
            require(file.length() in 1..(512L * 1024)) { "动态 PEQ 配置文件过大或为空" }
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            val values = linkedMapOf<String, Any>()
            var foundRoot = false
            file.inputStream().use { input ->
                parser.setInput(input, null)
                var token = parser.nextToken()
                while (token != XmlPullParser.END_DOCUMENT) {
                    require(token != XmlPullParser.DOCDECL && token != XmlPullParser.ENTITY_REF) { "配置文件不能包含文档声明或实体引用" }
                    if (token == XmlPullParser.START_TAG) {
                        if (parser.depth == 1) {
                            require(!foundRoot && parser.name == "map") { "动态 PEQ 配置根节点无效" }
                            foundRoot = true
                        } else {
                            require(parser.depth == 2 && foundRoot) { "动态 PEQ 配置嵌套格式无效" }
                            val key = parser.getAttributeValue(null, "name")
                            require(key in listOf(KEY_STATE, KEY_ENABLED) && !values.containsKey(key)) { "动态 PEQ 配置字段无效或重复" }
                            val value: Any = when (parser.name) {
                                "string" -> parser.nextText()
                                "boolean" -> {
                                    val attribute = parser.getAttributeValue(null, "value")
                                    require(attribute == "true" || attribute == "false") { "动态 PEQ 开关格式无效" }
                                    require(parser.nextText().isBlank()) { "动态 PEQ 开关不能包含文字" }
                                    attribute == "true"
                                }
                                else -> throw IllegalArgumentException("动态 PEQ 配置类型无效")
                            }
                            values[requireNotNull(key)] = value
                        }
                    }
                    token = parser.nextToken()
                }
            }
            require(foundRoot) { "动态 PEQ 配置缺少根节点" }
            return stateFromPreferences(values)
        }

        fun encode(state: DynamicPeqState): String {
            DynamicPeqModel.validate(state)
            return buildJsonObject {
                put("schema", SCHEMA)
                put("enabled", state.enabled)
                put("bands", buildJsonArray {
                    state.bands.forEach { b -> add(buildJsonObject {
                        put("id", b.id); put("type", b.type); put("enabled", b.enabled)
                        put("frequency", b.frequency); put("gain", b.gain); put("q", b.q)
                        put("dynamicEnabled", b.dynamicEnabled); put("threshold", b.threshold)
                        put("ratio", b.ratio); put("range", b.range); put("attack", b.attack)
                        put("release", b.release); put("knee", b.knee)
                    }) }
                })
            }.toString()
        }

        fun decode(json: String): DynamicPeqState {
            require(json.length <= MAX_JSON_LENGTH && json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_LENGTH) { "动态 PEQ 参数文件过大" }
            requireShallowJson(json)
            val root = Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("动态 PEQ 参数需要是 JSON 对象")
            require(root.number("schema") == SCHEMA.toDouble()) { "不支持此动态 PEQ 参数版本" }
            val bands = root["bands"] as? JsonArray ?: throw IllegalArgumentException("缺少动态频段列表")
            require(bands.size <= DynamicPeqModel.MAX_BANDS) { "最多支持 ${DynamicPeqModel.MAX_BANDS} 个动态频段" }
            return DynamicPeqModel.validate(DynamicPeqState(root.boolean("enabled"), bands.map { element ->
                val b = element as? JsonObject ?: throw IllegalArgumentException("频段格式无效")
                val type = b.number("type")
                require(type == type.toInt().toDouble()) { "滤波类型格式无效" }
                DynamicPeqBand(b.string("id"), type.toInt(), b.boolean("enabled"), b.number("frequency"),
                    b.number("gain"), b.number("q"), b.boolean("dynamicEnabled"), b.number("threshold"),
                    b.number("ratio"), b.number("range"), b.number("attack"), b.number("release"), b.number("knee"))
            }))
        }

        internal fun JsonObject.string(key: String): String {
            val v = this[key] as? JsonPrimitive
            require(v != null && v.isString) { "$key 需要是文字" }
            return v.content
        }
        internal fun JsonObject.number(key: String): Double {
            val v = this[key] as? JsonPrimitive
            require(v != null && !v.isString) { "$key 需要是数字" }
            return requireNotNull(v.doubleOrNull?.takeIf { it.isFinite() }) { "$key 需要是有限数字" }
        }
        internal fun JsonObject.boolean(key: String): Boolean {
            val v = this[key] as? JsonPrimitive
            require(v != null && !v.isString && v.booleanOrNull != null) { "$key 需要是布尔值" }
            return v.boolean
        }
        internal fun requireShallowJson(json: String) {
            var string = false; var escaped = false; var depth = 0
            json.forEach { char ->
                if (string) {
                    if (escaped) escaped = false else when (char) { '\\' -> escaped = true; '"' -> string = false }
                } else when (char) {
                    '"' -> string = true
                    '{', '[' -> { depth++; require(depth <= 16) { "JSON 嵌套层级过多" } }
                    '}', ']' -> depth--
                }
            }
        }
    }
}
