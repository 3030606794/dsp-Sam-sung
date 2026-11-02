package me.timschneeberger.rootlessjamesdsp.convolver

import android.content.Context
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarInputStream
import org.kamranzafar.jtar.TarOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Restore only into new IR files; mappings belong to this incoming backup, never existing profiles. */
internal object ConvolverBackupRestore {
    fun restoreFiles(context: Context, sourceDirectory: File): Map<String, String> {
        if (!sourceDirectory.isDirectory) return emptyMap()
        val root = context.getExternalFilesDir(null) ?: throw IOException("IR library unavailable")
        val directory = File(root, "Convolver").apply { mkdirs() }
        val mapping = linkedMapOf<String, String>()
        sourceDirectory.walkTopDown().filter { it.isFile }.forEach { source ->
            val relative = source.relativeTo(sourceDirectory).path.replace(File.separatorChar, '/')
            val originalPath = "Convolver/$relative"
            var destination = File(directory, relative)
            if (!destination.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                throw IOException("IR backup path escapes library")
            destination.parentFile!!.mkdirs()
            if (ConvolverLibrary.isRetiredBuiltin(source)) {
                mapping[originalPath] = ConvolverLibrary.DEFAULT_PATH
                return@forEach
            }
            val sourceHash = ConvolverLibrary.sha256(source)
            if (destination.isFile && ConvolverLibrary.sha256(destination) == sourceHash) {
                mapping[originalPath] = destination.relativeTo(root).path.replace(File.separatorChar, '/')
                return@forEach
            }
            if (destination.exists() || ConvolverLibrary.isProtected(context, destination)) {
                destination = ConvolverLibrary.uniqueCopy(destination.parentFile!!, destination.nameWithoutExtension + "_恢复", destination.extension)
            }
            while (!ConvolverLibrary.copy(context, source, destination)) {
                if (!destination.exists()) throw IOException("Could not restore IR copy")
                destination = ConvolverLibrary.uniqueCopy(destination.parentFile!!, source.nameWithoutExtension + "_恢复", source.extension)
            }
            destination.setLastModified(source.lastModified())
            mapping[originalPath] = destination.relativeTo(root).path.replace(File.separatorChar, '/')
        }
        return mapping
    }

    private fun remap(path: String, mapping: Map<String, String>): String {
        val normalized = path.replace('\\', '/')
        val relative = if (normalized.startsWith("Convolver/")) normalized else {
            val index = normalized.lastIndexOf("/Convolver/")
            if (index < 0) return path
            normalized.substring(index + 1)
        }
        return mapping[relative] ?: path
    }

    private fun rewriteXml(bytes: ByteArray, mapping: Map<String, String>): ByteArray? {
        if (bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true))
            throw IOException("DOCTYPE is not supported in preference backups")
        val document = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
            .newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val strings = document.getElementsByTagName("string")
        var changed = false
        for (index in 0 until strings.length) {
            val node = strings.item(index)
            if (node.attributes?.getNamedItem("name")?.nodeValue == "convolver_file") {
                val before = node.textContent
                val after = remap(before, mapping)
                if (before != after) { node.textContent = after; changed = true }
            }
        }
        if (!changed) return null
        return ByteArrayOutputStream().use { output ->
            TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.ENCODING, "UTF-8") }
                .transform(DOMSource(document), StreamResult(output))
            output.toByteArray()
        }
    }

    fun rewriteSelections(extractedBackup: File, mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        extractedBackup.walkTopDown().filter { it.isFile && it.name == "dsp_convolver.xml" }.forEach { file ->
            rewriteXml(file.readBytes(), mapping)?.let(file::writeBytes)
        }
        val profiles = File(extractedBackup, "profiles")
        if (profiles.isDirectory) profiles.walkTopDown().filter { it.isFile && it.name == "profile.tar" }.forEach { file ->
            rewriteProfile(file, mapping)
        }
    }

    /** Other profile entries, header attributes and metadata payloads pass through unchanged. */
    private fun rewriteProfile(file: File, mapping: Map<String, String>) {
        val pending = File.createTempFile(".ir-profile-", ".tar", file.parentFile)
        var changed = false
        try {
            TarInputStream(file.inputStream().buffered()).use { input ->
                TarOutputStream(pending.outputStream().buffered()).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (entry.name.substringAfterLast('/') == "dsp_convolver.xml") {
                            val before = input.readBytes()
                            val replacement = rewriteXml(before, mapping)
                            val bytes = replacement ?: before
                            if (replacement != null) changed = true
                            val header = ByteArray(512).also(entry::writeEntryHeader)
                            val outgoing = TarEntry(header).apply { size = bytes.size.toLong() }
                            output.putNextEntry(outgoing); output.write(bytes)
                        } else {
                            output.putNextEntry(entry); input.copyTo(output)
                        }
                    }
                }
            }
            if (changed) pending.copyTo(file, overwrite = true) // Only the extracted cache copy is modified.
        } finally { pending.delete() }
    }
}
