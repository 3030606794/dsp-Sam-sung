package me.timschneeberger.rootlessjamesdsp.creative

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Apply each device profile through SharedPreferences, after earlier asynchronous slider writes. */
object CreativeEffectsRepository {
    fun flush(context: Context): Boolean = CreativeEffectsKind.values().all {
        CreativeEffectsController.preferences(context, it).edit().commit()
    }
    fun replace(context: Context, kind: CreativeEffectsKind, values: Map<String, Any>?): Boolean {
        val edit = CreativeEffectsController.preferences(context, kind).edit().clear()
        values?.forEach { (key, value) ->
            when (value) { is Boolean -> edit.putBoolean(key, value); is Int -> edit.putInt(key, value) }
        }
        return edit.commit()
    }
    fun read(kind: CreativeEffectsKind, file: File): Map<String, Any> {
        require(file.length() in 1..65536) { "${kind.title}配置文件大小无效" }
        val controls = CreativeEffectsParameters.controls(kind).associateBy { it.key }
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
                        result[key] = if (key == kind.enableKey) {
                            require(parser.name == "boolean" && attribute in listOf("true", "false")) { "音效开关无效" }
                            attribute == "true"
                        } else {
                            val control = requireNotNull(controls[key]) { "未知音效参数" }
                            val number = attribute?.toIntOrNull()
                            require(parser.name == "int" && number != null && number in control.min..control.max) { "音效参数超出范围" }
                            number
                        }
                        require(parser.nextText().isBlank()) { "配置参数不能包含文字" }
                    }
                }
                token = parser.nextToken()
            }
        }
        require(root) { "配置缺少根节点" }
        return result
    }
}
