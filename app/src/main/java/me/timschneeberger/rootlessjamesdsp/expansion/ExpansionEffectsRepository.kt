package me.timschneeberger.rootlessjamesdsp.expansion

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Apply each device profile through SharedPreferences, after earlier asynchronous slider writes. */
object ExpansionEffectsRepository {
    fun flush(context: Context): Boolean = ExpansionEffectsKind.values().all {
        ExpansionEffectsController.preferences(context, it).edit().commit()
    }
    fun replace(context: Context, kind: ExpansionEffectsKind, values: Map<String, Any>?): Boolean {
        val edit = ExpansionEffectsController.preferences(context, kind).edit().clear()
        values?.forEach { (key, value) ->
            when (value) { is Boolean -> edit.putBoolean(key, value); is Int -> edit.putInt(key, value); is String -> edit.putString(key, value) }
        }
        return edit.commit()
    }
    fun read(kind: ExpansionEffectsKind, file: File): Map<String, Any> {
        require(file.length() in 1..65536) { "${kind.title}配置文件大小无效" }
        val controls = ExpansionEffectsParameters.controls(kind).associateBy { it.key }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        val result = linkedMapOf<String, Any>()
        var root = false
        file.inputStream().use { input ->
            parser.setInput(input, null)
            var token = parser.nextToken()
            while (token != XmlPullParser.END_DOCUMENT) {
                require(token != XmlPullParser.DOCDECL && token != XmlPullParser.ENTITY_REF) { "配置不能包含外部实体" }
                if (token == XmlPullParser.START_TAG) {
                    if (parser.depth == 1) {
                        require(!root && parser.name == "map") { "配置根节点无效" }; root = true
                    } else {
                        require(root && parser.depth == 2) { "配置嵌套无效" }
                        val key = requireNotNull(parser.getAttributeValue(null, "name"))
                        require(!result.containsKey(key)) { "配置字段重复" }
                        val attribute = parser.getAttributeValue(null, "value")
                        if (kind == ExpansionEffectsKind.MATCH_EQ && key == ExpansionEffectsController.MATCH_PROFILE) {
                            require(parser.name == "string") { "参考频谱存储格式无效" }
                            val value = parser.nextText()
                            require(ExpansionEffectsController.matchProfile(value) != null) { "参考频谱数据无效" }
                            result[key] = value
                            token = parser.nextToken()
                            continue
                        }
                        result[key] = if (kind == ExpansionEffectsKind.SMART_ADAPT && key in SmartAdaptState.PREFERENCE_KEYS) {
                            require(parser.name == "int") { "适配锁定数据格式无效" }
                            requireNotNull(attribute?.toIntOrNull()) { "适配锁定数据无效" }
                        } else if (key == kind.enableKey) {
                            require(parser.name == "boolean" && attribute in listOf("true", "false")) { "音效开关无效" }
                            attribute == "true"
                        } else {
                            val control = requireNotNull(controls[key]) { "未知音效参数" }
                            val number = attribute?.toIntOrNull()
                            require(parser.name == "int" && number != null && number in control.min..control.max) { "音效参数超出范围" }
                            val choices = ExpansionEffectsParameters.options(kind, key)
                            require(choices.isEmpty() || number in choices) { "音效选项无效" }
                            number
                        }
                        require(parser.nextText().isBlank()) { "配置参数不能包含文字" }
                    }
                }
                token = parser.nextToken()
            }
        }
        require(root) { "配置缺少根节点" }
        val normalized = if (kind == ExpansionEffectsKind.SMART_ADAPT) SmartAdaptState.upgradeImported(result) else result
        if (kind == ExpansionEffectsKind.SMART_ADAPT && normalized.keys.any { it in SmartAdaptState.PREFERENCE_KEYS }) {
            require(SmartAdaptState.PREFERENCE_KEYS.all(normalized::containsKey) && SmartAdaptState.read(normalized) != null) {
                "适配锁定快照缺失、超出范围或与当前目标、力度不一致"
            }
        }
        ExpansionEffectsParameters.linkedRange(kind)?.let { range ->
            val low = ExpansionEffectsParameters.control(kind, range.first)
            val high = ExpansionEffectsParameters.control(kind, range.second)
            require(ExpansionEffectsParameters.value(result, high) >= ExpansionEffectsParameters.value(result, low) + range.third) { "作用频率上下限无效" }
        }
        return normalized
    }
}
