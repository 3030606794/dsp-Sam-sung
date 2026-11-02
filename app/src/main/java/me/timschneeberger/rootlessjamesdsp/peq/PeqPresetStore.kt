package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.security.MessageDigest

/** Personal PEQ favorites live outside device/effect preferences and global preset replacement. */
class PeqPresetStore internal constructor(
    private val file: File,
    private val writePayload: (FileOutputStream, ByteArray) -> Unit = { stream, bytes -> stream.write(bytes) }
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))

    /** Filled by load; opening the library never rewrites its original file. */
    var lastLoadedLegacyPresetCount: Int = 0
        private set
    private var legacyBytesToBackup: ByteArray? = null

    fun load(): List<PeqPreset> = synchronized(fileLock) { read() }

    fun save(name: String, state: PeqState): PeqPreset = synchronized(fileLock) {
        val normalized = PeqPresetCodec.normalizeName(name)
        PeqModel.validate(state)
        val current = read()
        require(current.size < MAX_PRESETS) { "最多保存 20 个预设，请先删除一个预设" }
        require(current.none { it.name == normalized }) { "预设名称已存在，请换一个名称" }
        val preset = PeqPreset(UUID.randomUUID().toString(), normalized, state)
        write(current + preset)
        preset
    }

    fun rename(id: String, name: String): PeqPreset = synchronized(fileLock) {
        val normalized = PeqPresetCodec.normalizeName(name)
        val current = read()
        val existing = current.find(id)
        require(current.none { it.id != id && it.name == normalized }) { "预设名称已存在，请换一个名称" }
        val renamed = existing.copy(name = normalized)
        write(current.map { if (it.id == id) renamed else it })
        renamed
    }

    fun overwrite(id: String, state: PeqState): PeqPreset = synchronized(fileLock) {
        PeqModel.validate(state)
        val current = read()
        val updated = current.find(id).copy(state = state)
        write(current.map { if (it.id == id) updated else it })
        updated
    }

    fun delete(id: String) = synchronized(fileLock) {
        val current = read()
        current.find(id)
        write(current.filterNot { it.id == id })
    }

    /** Validate the entire file and capacity before a single atomic write; never overwrite names. */
    fun importJson(json: String): List<PeqPreset> = synchronized(fileLock) {
        val incoming = PeqPresetCodec.decode(json)
        val current = read()
        require(current.size + incoming.size <= MAX_PRESETS) { "导入后将超过 20 个预设，请先删除部分预设" }
        val names = current.map { it.name }.toMutableSet()
        val added = incoming.map { entry ->
            var name = entry.name
            var suffix = 2
            while (name in names) {
                val ending = " ($suffix)"
                var prefix = entry.name.take(MAX_NAME_LENGTH - ending.length).trimEnd()
                // Do not split a surrogate pair when a long name needs room for a suffix.
                if (prefix.lastOrNull()?.isHighSurrogate() == true) prefix = prefix.dropLast(1)
                name = prefix + ending
                suffix++
            }
            names.add(name)
            entry.copy(name = name)
        }
        write(current + added)
        added
    }

    /** Both single-item and complete exports use the same portable array format. */
    fun exportJson(id: String? = null): String = synchronized(fileLock) {
        val current = read()
        PeqPresetCodec.encode(if (id == null) current else listOf(current.find(id)))
    }

    private fun List<PeqPreset>.find(id: String): PeqPreset = firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")

    private fun read(): List<PeqPreset> {
        legacyBytesToBackup = null
        lastLoadedLegacyPresetCount = 0
        val atomic = AtomicFile(file)
        val stream = try { atomic.openRead() } catch (missing: FileNotFoundException) {
            if (!file.exists() && !File(file.path + ".bak").exists()) return emptyList()
            throw missing
        }
        val bytes = stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_IMPORT_BYTES) { "本地预设文件不能超过 4 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        var legacyCount = 0
        val result = PeqPresetCodec.decodeDocument(decoder.decode(ByteBuffer.wrap(bytes)).toString(), local = true) {
            legacyCount++
        }
        lastLoadedLegacyPresetCount = legacyCount
        if (legacyCount > 0) legacyBytesToBackup = bytes
        return result
    }

    private fun write(presets: List<PeqPreset>) {
        val bytes = PeqPresetCodec.encodeDocument(presets, local = true).toByteArray(Charsets.UTF_8)
        legacyBytesToBackup?.let { original ->
            // Content-addressed backups preserve every distinct old library before a user writes it.
            val hash = MessageDigest.getInstance("SHA-256").digest(original)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val backup = File(file.path + ".before-130-$hash.json")
            if (!backup.isFile || !backup.readBytes().contentEquals(original)) {
                val atomicBackup = AtomicFile(backup)
                val output = atomicBackup.startWrite()
                try {
                    output.write(original)
                    output.fd.sync()
                    atomicBackup.finishWrite(output)
                    // AtomicFile may log a failed rename rather than throw. Do not replace the
                    // original library unless its complete recovery copy can actually be read.
                    if (!backup.isFile || !backup.readBytes().contentEquals(original)) {
                        throw IOException("旧预设备份保存失败，原预设文件未修改")
                    }
                } catch (failure: Exception) {
                    atomicBackup.failWrite(output)
                    throw failure
                }
            }
        }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            writePayload(stream, bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
            legacyBytesToBackup = null
            lastLoadedLegacyPresetCount = 0
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    companion object {
        const val FILE_NAME = "peq-presets.json"
        const val MAX_PRESETS = 20
        const val MAX_NAME_LENGTH = 40
        const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
        private val fileLock = Any()
    }
}
