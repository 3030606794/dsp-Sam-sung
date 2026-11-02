package me.timschneeberger.rootlessjamesdsp.liveprog

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.io.File
import java.security.MessageDigest

/** Removes redundant legacy editor copies, never originals, selected files, or different edits. */
object LiveprogDuplicateCleaner {
    private val copySuffix = Regex("-编辑副本(?:-\\d+)?$")
    private const val MAX_SCRIPT_BYTES = 512L * 1024
    private data class Signature(val bytesHash: String, val parameters: Map<String, Any?>)

    fun clean(context: Context, directory: File, currentSelection: String? = null): Int {
        val external = runCatching { context.getExternalFilesDir(null)?.canonicalFile }.getOrNull() ?: return 0
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return 0
        if (root.parentFile != external || !directory.name.equals("Liveprog", ignoreCase = true) ||
            !root.name.equals("Liveprog", ignoreCase = true)) return 0
        val preferences = context.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE)
        val stored = preferences.all
        val selected = listOfNotNull(currentSelection,
            stored[context.getString(R.string.key_liveprog_file)] as? String).mapNotNull { path ->
            if (path.isBlank()) null else runCatching {
                (if (File(path).isAbsolute) File(path) else File(external, path)).canonicalFile
            }.getOrNull()
        }.toSet()
        // Keep the editor's original absolute path for its path-bound parameter identity.
        val files = directory.listFiles().orEmpty().filter { file ->
            file.isFile && file.extension == "eel" && file.length() <= MAX_SCRIPT_BYTES &&
                runCatching { file.canonicalFile.parentFile == root }.getOrDefault(false)
        }
        val copies = files.mapNotNull { file -> baseName(file.nameWithoutExtension)?.let { it to file } }
            .groupBy({ it.first }, { it.second })
        val signatures = mutableMapOf<File, Signature?>()
        fun signature(file: File): Signature? = signatures.getOrPut(file) {
            runCatching {
                val bytes = file.readBytes()
                val source = bytes.toString(Charsets.UTF_8)
                // A malformed/non-text file is not safe to classify as a disposable editor copy.
                if (!source.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return@getOrPut null
                val prefix = "liveprog_parameter_v1:${LiveprogParameterStore.identity(file.absolutePath, source)}:"
                val parameters = stored.filterKeys { it.startsWith(prefix) }
                    .mapKeys { it.key.removePrefix(prefix) }
                Signature(MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }, parameters)
            }.getOrNull()
        }
        var removed = 0
        copies.forEach { (base, candidates) ->
            val keepers = mutableSetOf<Signature>()
            // Originals are always retained, even if an active copy happens to contain the same bytes.
            files.filter { it.nameWithoutExtension == base && baseName(it.nameWithoutExtension) == null }
                .mapNotNull(::signature).forEach(keepers::add)
            candidates.filter { runCatching { it.canonicalFile in selected }.getOrDefault(false) }
                .mapNotNull(::signature).forEach(keepers::add)
            candidates.sortedWith(compareBy<File> { copySuffix.find(it.nameWithoutExtension)?.value?.substringAfterLast('-', "0")?.toIntOrNull() ?: 0 }
                .thenBy { it.name }).forEach next@ { file ->
                if (runCatching { file.canonicalFile in selected }.getOrDefault(true)) return@next
                val snapshot = signature(file) ?: return@next
                if (!keepers.add(snapshot)) {
                    // Recheck selection, bytes and saved parameters immediately before deleting.
                    val unchanged = runCatching {
                        val latest = preferences.all
                        val active = latest[context.getString(R.string.key_liveprog_file)] as? String
                        if (!active.isNullOrBlank()) {
                            val activeFile = if (File(active).isAbsolute) File(active) else File(external, active)
                            if (activeFile.canonicalFile == file.canonicalFile) return@runCatching false
                        }
                        val bytes = file.readBytes()
                        val prefix = "liveprog_parameter_v1:${LiveprogParameterStore.identity(file.absolutePath, bytes.toString(Charsets.UTF_8))}:"
                        val parameters = latest.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
                        parameters == snapshot.parameters && MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it.toInt() and 0xff) } == snapshot.bytesHash
                    }.getOrDefault(false)
                    if (unchanged && file.delete()) removed++
                }
            }
        }
        return removed
    }

    private fun baseName(name: String): String? {
        var base = name
        while (copySuffix.containsMatchIn(base)) base = copySuffix.replace(base, "")
        return base.takeIf { it.isNotBlank() && it != name }
    }
}
