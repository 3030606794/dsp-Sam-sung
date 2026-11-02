package me.timschneeberger.rootlessjamesdsp.liveprog

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.io.File
import java.security.MessageDigest

/** Parameter choices are backed up with DSP preferences, independently of editable EEL files. */
class LiveprogParameterStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE)
    private class CachedSource(val path: String, val source: String, val exactPrefix: String) {
        var compatibilityResolved = false
        var compatibilityPrefix: String? = null
    }
    private val sourceLock = Any()
    private var cachedSource: CachedSource? = null

    fun applyTo(parser: EelParser) {
        values(parser).forEach { (key, value) ->
            when (val property = parser.properties.find { it.key == key }) {
                is EelListProperty -> property.value = property.validateRange(value.toInt())
                is EelNumberRangeProperty<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (property as EelNumberRangeProperty<Float>).value = property.validateRange(value.toFloat())
                }
            }
        }
    }

    fun save(parser: EelParser, properties: Collection<EelBaseProperty>): Boolean {
        val prefix = prefix(parser) ?: return false
        val edit = preferences.edit()
        properties.forEach { edit.putString(prefix + it.key, it.valueAsString()) }
        // The parameter receiver reads the same preferences memory immediately. Avoid waiting
        // for a disk fsync on every 40 ms slider update; Android queues persistence in order.
        edit.apply()
        return true
    }

    fun values(parser: EelParser): Map<String, Double> {
        val source = sourceIdentity(parser) ?: return emptyMap()
        val prefix = source.exactPrefix
        val saved = preferences.all
        // Older resource restoration could change only a stock script's parameter literals.
        // Keep exact-file values authoritative; recover missing values only from matching stock code.
        val stockPrefix by lazy(LazyThreadSafetyMode.NONE) {
            compatibleStockPrefix(source)?.takeUnless { it == prefix }
        }
        return parser.properties.mapNotNull { property ->
            val text = (saved[prefix + property.key] as? String)
                ?: stockPrefix?.let { saved[it + property.key] as? String }
                ?: return@mapNotNull null
            val value = text.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return@mapNotNull null
            property.key to value
        }.toMap()
    }

    private fun compatibleStockPrefix(source: CachedSource): String? = synchronized(sourceLock) {
        if (!source.compatibilityResolved) {
            source.compatibilityPrefix = resolveCompatibleStockPrefix(source.path, source.source)
            source.compatibilityResolved = true
        }
        source.compatibilityPrefix
    }

    private fun resolveCompatibleStockPrefix(path: String, source: String): String? {
        val name = File(path).name
        val title = BuiltinLiveprogMetadata.identifiedTitle(name, source) ?: return null
        val stock = runCatching { appContext.assets.open("Liveprog/$name").bufferedReader(Charsets.UTF_8).use { it.readText() } }
            .getOrNull() ?: return null
        if (BuiltinLiveprogMetadata.identifiedTitle(name, stock) != title) return null
        return "liveprog_parameter_v1:${identity(path, stock)}:"
    }

    /** Only explicit exports carry parameter literals into a copy of the source. */
    fun exportSource(path: String): String {
        val parser = EelParser().apply { load(path) }
        applyTo(parser)
        var source = parser.contents ?: return ""
        val selected = values(parser).keys
        parser.properties.filter { it.key in selected }.forEach { property ->
            source = property.manipulateProperty(source) ?: source
        }
        return source
    }

    fun relocate(oldPath: String, newPath: String, source: String) {
        val oldPrefix = "liveprog_parameter_v1:${identity(oldPath, source)}:"
        val newPrefix = "liveprog_parameter_v1:${identity(newPath, source)}:"
        val edit = preferences.edit()
        preferences.all.forEach { (key, value) ->
            if (key.startsWith(oldPrefix) && value is String) {
                edit.putString(newPrefix + key.removePrefix(oldPrefix), value).remove(key)
            }
        }
        val previous = EelParser().apply { loadSource(oldPath, source) }
        val recovered = values(previous).keys
        applyTo(previous)
        previous.properties.filter { it.key in recovered }.forEach { property ->
            edit.putString(newPrefix + property.key, property.valueAsString())
        }
        edit.commit()
    }

    private fun prefix(parser: EelParser): String? = sourceIdentity(parser)?.exactPrefix

    private fun sourceIdentity(parser: EelParser): CachedSource? {
        val path = parser.path ?: return null
        val source = parser.contents ?: return null
        return synchronized(sourceLock) {
            cachedSource?.takeIf { it.path == path && it.source === source }
                ?: CachedSource(path, source, "liveprog_parameter_v1:${identity(path, source)}:")
                    .also { cachedSource = it }
        }
    }

    companion object {
        fun identity(path: String, source: String): String = MessageDigest.getInstance("SHA-256")
            .digest((File(path).absolutePath + '\u0000' + source).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
