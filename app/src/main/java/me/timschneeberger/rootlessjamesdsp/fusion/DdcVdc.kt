package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.util.AtomicFile
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.abs

/** A VDC document is two coefficient sets, not a recoverable frequency/gain/Q description. */
object DdcVdc {
    const val MAX_BYTES = 256 * 1024
    const val MAX_SOS = 128
    const val COEFFICIENT_ABS = 1.0e12
    const val STABILITY_EPSILON = 1.0e-12
    const val MAX_TOKEN = 127
    private const val SIZE_ERROR = "DDC 文件不能超过 256 KiB"
    private val NUMBER = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val LABEL = Regex("SR_(44100|48000)[ \\t\\r\\n]*:")

    data class Parsed internal constructor(
        val text: String,
        val sha256: String,
        val section44100: List<List<Double>>,
        val section48000: List<List<Double>>,
    ) {
        val sosCount: Int get() = section44100.size
        fun bytes(): ByteArray = text.toByteArray(Charsets.UTF_8)
    }

    /** The stream belongs to the caller; bounded reading happens before any parsing. */
    fun read(input: InputStream): Parsed {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                require(output.size() < MAX_BYTES) { SIZE_ERROR }
                output.write(single)
            } else {
                require(output.size() + count <= MAX_BYTES) { SIZE_ERROR }
                output.write(buffer, 0, count)
            }
        }
        return parse(output.toByteArray())
    }

    fun read(file: File): Parsed {
        require(file.isFile) { "找不到所选 DDC 文件" }
        require(file.length() <= MAX_BYTES) { SIZE_ERROR }
        return file.inputStream().use(::read)
    }

    fun parse(text: String): Parsed {
        require(text.length <= MAX_BYTES) { SIZE_ERROR }
        require(text.removePrefix("\uFEFF").all { it.code in 1..127 }) { "DDC 正文必须为 ASCII 文本，不能包含空字符或非 ASCII 内容" }
        return parse(text.toByteArray(Charsets.UTF_8))
    }

    fun parse(bytes: ByteArray): Parsed {
        require(bytes.size in 1..MAX_BYTES) { if (bytes.isEmpty()) "DDC 文件为空" else SIZE_ERROR }
        val offset = if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) 3 else 0
        require((offset until bytes.size).all { bytes[it].toInt() in 1..127 }) { "DDC 正文必须为 ASCII 文本，不能包含空字符或非 ASCII 内容" }
        val text = bytes.toString(Charsets.UTF_8)
        val bodyText = text.removePrefix("\uFEFF")
        val labels = LABEL.findAll(bodyText).toList()
        require(labels.size == 2 && labels.map { it.groupValues[1] }.toSet() == setOf("44100", "48000")) {
            "DDC 必须且只能包含 SR_44100 与 SR_48000 两段"
        }
        require(bodyText.substring(0, labels.first().range.first).all(::whitespace)) { "DDC 包含未知字段" }
        val sections = labels.mapIndexed { i, label ->
            val end = if (i + 1 < labels.size) labels[i + 1].range.first else bodyText.length
            val body = bodyText.substring(label.range.last + 1, end).trim(' ', '\t', '\r', '\n')
            val tokens = body.split(',')
            require(tokens.size in 5..MAX_SOS * 5 && tokens.size % 5 == 0) {
                "DDC 每段需要 1–128 组完整的五系数滤波器"
            }
            val coefficients = tokens.map { token ->
                val value = token.trim(' ', '\t', '\r', '\n')
                require(value.length <= MAX_TOKEN && NUMBER.matches(value)) { "DDC 包含无效、过长或多余的系数内容" }
                value.toDoubleOrNull()?.takeIf { it.isFinite() && abs(it) <= COEFFICIENT_ABS }
                    ?: throw IllegalArgumentException("DDC 系数必须为有限数值")
            }.chunked(5)
            coefficients.forEach { coefficient ->
                // VDC stores positive feedback terms: denominator 1 - a1*z^-1 - a2*z^-2.
                val a1 = coefficient[3]; val a2 = coefficient[4]
                require(abs(a2) <= 1.0 - STABILITY_EPSILON && 1.0 - a1 - a2 >= STABILITY_EPSILON && 1.0 + a1 - a2 >= STABILITY_EPSILON) {
                    "DDC 包含不稳定的滤波器极点"
                }
            }
            label.groupValues[1] to coefficients
        }.toMap()
        require(sections.getValue("44100").size == sections.getValue("48000").size) {
            "DDC 两个采样率的滤波器数量必须相同"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Parsed(text, digest, sections.getValue("44100"), sections.getValue("48000"))
    }

    private fun whitespace(char: Char) = char == ' ' || char == '\t' || char == '\r' || char == '\n'
}

/** Embedded original bytes let an independent favorite survive a missing or replaced library file. */
data class DdcFoundation(val path: String, val label: String, val sha256: String, val text: String) {
    fun validated(): DdcVdc.Parsed {
        require(path.length <= 1024 && path.none { it.isISOControl() } && validUnicode(path)) { "DDC 原路径无效" }
        require(label.length in 1..120 && label.none { it.isISOControl() } && validUnicode(label)) { "DDC 文件显示名称无效" }
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "DDC 内容指纹无效" }
        return DdcVdc.parse(text).also { require(it.sha256 == sha256) { "DDC 内容与保存指纹不一致" } }
    }

    /** The imported path is display metadata; only the content-addressed file is ever written. */
    fun install(context: Context): String {
        validated()
        // A matching existing selection retains its original path and filename.
        if (path.isNotEmpty()) {
            val existing = resolve(context, path)
            if (runCatching { DdcVdc.read(existing).sha256 == sha256 }.getOrDefault(false)) return path
        }
        val root = context.getExternalFilesDir(null)
            ?: throw IOException("无法访问 DDC 文件库")
        return install(root)
    }

    internal fun install(root: File, writePayload: (FileOutputStream, ByteArray) -> Unit = { stream, bytes -> stream.write(bytes) }): String = synchronized(restoreLock) {
        val parsed = validated()
        val directory = File(root, "DDC")
        require(directory.mkdirs() || directory.isDirectory) { "无法创建 DDC 文件库" }
        var suffix = 1
        var relative: String
        var target: File
        while (true) {
            require(suffix <= 10000) { "DDC 收藏副本名称冲突过多" }
            relative = "DDC/收藏-$sha256" + (if (suffix == 1) "" else "-$suffix") + ".vdc"
            target = File(root, relative)
            require(target.canonicalFile.parentFile == directory.canonicalFile && directory.canonicalFile.parentFile == root.canonicalFile) {
                "DDC 还原位置无效"
            }
            if (runCatching { DdcVdc.read(target).sha256 == sha256 }.getOrDefault(false)) return@synchronized relative
            // A content-addressed filename can itself have been edited by the user.
            // Keep it and any interrupted atomic write; restore into a new sibling.
            if (!target.exists() && !File(target.path + ".bak").exists() && !File(target.path + ".new").exists()) break
            suffix++
        }
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            writePayload(output, parsed.bytes()); output.fd.sync(); atomic.finishWrite(output)
            if (DdcVdc.read(target).sha256 != sha256 || File(target.path + ".new").exists())
                throw IOException("无法完整还原 DDC 文件")
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
        relative
    }

    fun matchesSelection(context: Context): Boolean = path.isNotEmpty() && runCatching {
        DdcVdc.read(resolve(context, path)).sha256 == sha256
    }.getOrDefault(false)

    companion object {
        private val restoreLock = Any()
        fun selected(context: Context, path: String): DdcFoundation {
            require(path.isNotBlank()) { "请先选择有效的 DDC 文件" }
            val file = resolve(context, path)
            return from(path, file.nameWithoutExtension.take(120), DdcVdc.read(file))
        }
        fun from(path: String, label: String, parsed: DdcVdc.Parsed) =
            DdcFoundation(path, label, parsed.sha256, parsed.text).also { it.validated() }
        private fun resolve(context: Context, path: String) =
            File(path).takeIf { it.isAbsolute } ?: File(FileLibraryPreference.createFullPathCompat(context, path))
        private fun validUnicode(value: String) = value.indices.all { i -> when {
            value[i].isHighSurrogate() -> i + 1 < value.length && value[i + 1].isLowSurrogate()
            value[i].isLowSurrogate() -> i > 0 && value[i - 1].isHighSurrogate()
            else -> true
        } }
    }
}
