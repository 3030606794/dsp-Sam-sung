package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Personal favorites are deliberately outside the per-device/global DSP namespaces. */
class SurroundPresetStore internal constructor(
    private val file: File,
    private val writePayload: (FileOutputStream, ByteArray) -> Unit = { stream, bytes -> stream.write(bytes) }
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))

    fun load(): List<SurroundPreset> = synchronized(fileLock) { read() }

    fun save(name: String, settings: SurroundController.Settings): SurroundPreset = synchronized(fileLock) {
        val normalized = SurroundPresetCodec.normalizeName(name)
        SurroundPresetCodec.validateSettings(settings)
        val current = read()
        require(current.size < MAX_PRESETS) { "最多保存 20 个环绕预设，请先删除一个预设" }
        require(current.none { it.name == normalized }) { "预设名称已存在，请换一个名称" }
        val preset = SurroundPreset(UUID.randomUUID().toString(), normalized, settings)
        write(current + preset)
        preset
    }

    fun rename(id: String, name: String): SurroundPreset = synchronized(fileLock) {
        val normalized = SurroundPresetCodec.normalizeName(name)
        val current = read()
        val existing = current.find(id)
        require(current.none { it.id != id && it.name == normalized }) { "预设名称已存在，请换一个名称" }
        val renamed = existing.copy(name = normalized)
        write(current.map { if (it.id == id) renamed else it })
        renamed
    }

    fun overwrite(id: String, settings: SurroundController.Settings): SurroundPreset = synchronized(fileLock) {
        SurroundPresetCodec.validateSettings(settings)
        val current = read()
        val updated = current.find(id).copy(settings = settings)
        write(current.map { if (it.id == id) updated else it })
        updated
    }

    fun delete(id: String) = synchronized(fileLock) {
        val current = read()
        current.find(id)
        write(current.filterNot { it.id == id })
    }

    fun applyToCurrentSlot(context: Context, id: String): SurroundPreset {
        val preset = synchronized(fileLock) { read().find(id) }
        SurroundController.save(context, preset.settings)
        return preset
    }

    /** The entire document and capacity are validated before the single atomic replacement. */
    fun importJson(json: String): List<SurroundPreset> = synchronized(fileLock) {
        val incoming = SurroundPresetCodec.decode(json)
        val current = read()
        require(current.size + incoming.size <= MAX_PRESETS) { "导入后将超过 20 个预设，请先删除部分预设" }
        val names = current.map { it.name }.toMutableSet()
        val added = incoming.map { entry ->
            var name = entry.name
            var suffix = 2
            while (name in names) {
                val ending = " ($suffix)"
                var prefix = entry.name.take(MAX_NAME_LENGTH - ending.length).trimEnd()
                if (prefix.lastOrNull()?.isHighSurrogate() == true) prefix = prefix.dropLast(1)
                name = prefix + ending
                suffix++
            }
            names += name
            entry.copy(name = name)
        }
        write(current + added)
        added
    }

    fun exportJson(id: String? = null): String = synchronized(fileLock) {
        val current = read()
        SurroundPresetCodec.encode(if (id == null) current else listOf(current.find(id)))
    }

    private fun List<SurroundPreset>.find(id: String): SurroundPreset = firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")

    private fun read(): List<SurroundPreset> {
        val atomic = AtomicFile(file)
        val input = try { atomic.openRead() } catch (missing: FileNotFoundException) {
            if (!file.exists() && !File(file.path + ".bak").exists()) return emptyList()
            throw missing
        }
        return input.use { SurroundPresetCodec.decodeDocument(SurroundPresetCodec.readImport(it), local = true) }
    }

    private fun write(presets: List<SurroundPreset>) {
        val bytes = SurroundPresetCodec.encodeDocument(presets, local = true).toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            writePayload(output, bytes)
            output.fd.sync()
            atomic.finishWrite(output)
            // AtomicFile logs rename failures instead of throwing them on newer Android releases.
            if (!file.isFile || File(file.path + ".new").exists())
                throw IOException("无法提交环绕预设，请检查存储空间后重试")
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }

    companion object {
        const val FILE_NAME = "surround-presets.json"
        const val MAX_PRESETS = 20
        const val MAX_NAME_LENGTH = 40
        const val MAX_IMPORT_BYTES = 256 * 1024
        private val fileLock = Any()
    }
}
