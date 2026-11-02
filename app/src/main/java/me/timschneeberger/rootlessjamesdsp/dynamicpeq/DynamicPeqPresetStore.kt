package me.timschneeberger.rootlessjamesdsp.dynamicpeq

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

/** Personal favorites live outside dsp_* device/preset replacement. */
class DynamicPeqPresetStore internal constructor(
    private val file: File,
    private val writePayload: (FileOutputStream, ByteArray) -> Unit = { stream, bytes -> stream.write(bytes) }
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))

    fun load(): List<DynamicPeqPreset> = synchronized(lock) { read() }

    fun save(name: String, state: DynamicPeqState): DynamicPeqPreset = synchronized(lock) {
        val normalized = DynamicPeqPresetCodec.normalizeName(name)
        DynamicPeqModel.validate(state)
        val current = read()
        require(current.size < MAX_PRESETS) { "最多保存 20 个预设，请先删除部分预设" }
        require(current.none { it.name == normalized }) { "预设名称已存在" }
        val preset = DynamicPeqPreset(UUID.randomUUID().toString(), normalized, state)
        write(current + preset)
        preset
    }

    fun rename(id: String, name: String): DynamicPeqPreset = synchronized(lock) {
        val normalized = DynamicPeqPresetCodec.normalizeName(name)
        val current = read()
        require(current.none { it.id != id && it.name == normalized }) { "预设名称已存在" }
        val result = current.find(id).copy(name = normalized)
        write(current.map { if (it.id == id) result else it })
        result
    }

    fun overwrite(id: String, state: DynamicPeqState): DynamicPeqPreset = synchronized(lock) {
        DynamicPeqModel.validate(state)
        val current = read()
        val result = current.find(id).copy(state = state)
        write(current.map { if (it.id == id) result else it })
        result
    }

    fun delete(id: String) = synchronized(lock) {
        val current = read()
        current.find(id)
        write(current.filterNot { it.id == id })
    }

    fun importJson(json: String): List<DynamicPeqPreset> = synchronized(lock) {
        val incoming = DynamicPeqPresetCodec.decode(json)
        val current = read()
        require(current.size + incoming.size <= MAX_PRESETS) { "导入后将超过 20 个预设" }
        val names = current.map { it.name }.toMutableSet()
        val added = incoming.map { preset ->
            var name = preset.name
            var index = 2
            while (name in names) {
                val suffix = " ($index)"
                var prefix = preset.name.take(MAX_NAME_LENGTH - suffix.length).trimEnd()
                if (prefix.lastOrNull()?.isHighSurrogate() == true) prefix = prefix.dropLast(1)
                name = prefix + suffix
                index++
            }
            names += name
            preset.copy(name = name)
        }
        write(current + added)
        added
    }

    fun exportJson(id: String? = null): String = synchronized(lock) {
        val current = read()
        DynamicPeqPresetCodec.encode(if (id == null) current else listOf(current.find(id)))
    }

    private fun List<DynamicPeqPreset>.find(id: String): DynamicPeqPreset = firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("预设已不存在，请刷新列表")

    private fun read(): List<DynamicPeqPreset> {
        val input = try { AtomicFile(file).openRead() } catch (error: FileNotFoundException) {
            if (!file.exists() && !File(file.path + ".bak").exists()) return emptyList()
            throw error
        }
        val bytes = input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_IMPORT_BYTES) { "本地预设文件不能超过 2 MiB" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return DynamicPeqPresetCodec.decodeDocument(decoder.decode(ByteBuffer.wrap(bytes)).toString(), true)
    }

    private fun write(presets: List<DynamicPeqPreset>) {
        val bytes = DynamicPeqPresetCodec.encodeDocument(presets, true).toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            writePayload(output, bytes)
            output.fd.sync()
            atomic.finishWrite(output)
            if (!file.isFile || !file.readBytes().contentEquals(bytes)) throw IOException("动态 PEQ 预设保存失败")
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    companion object {
        const val FILE_NAME = "dynamic-peq-presets.json"
        const val MAX_PRESETS = 20
        const val MAX_NAME_LENGTH = 40
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
        private val lock = Any()
    }
}
