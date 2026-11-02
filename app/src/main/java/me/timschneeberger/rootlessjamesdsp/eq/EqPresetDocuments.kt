package me.timschneeberger.rootlessjamesdsp.eq

import android.content.ContentResolver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Document-provider streams are bounded and closed before a preset can be added. */
internal object EqPresetDocuments {
    const val MAX_IMPORT_BYTES = 256 * 1024

    fun read(resolver: ContentResolver, uri: Uri): String =
        (resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")).use(::read)

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_IMPORT_BYTES) { "预设文件不能超过 256 KiB" }
            bytes.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    fun write(resolver: ContentResolver, uri: Uri, json: String) {
        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入所选文件")
        output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    /** A local, read-only snapshot is handed to Android's chooser, never to a chosen recipient. */
    fun shareIntent(context: Context, json: String): Intent {
        val directory = File(context.cacheDir, "eq-presets-share")
        require(directory.isDirectory || directory.mkdirs()) { "无法创建预设分享文件" }
        val file = File.createTempFile("DSP-EQ-", ".json", directory)
        try {
            file.writeText(json, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".file_library_provider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("均衡器预设", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Intent.createChooser(send, "分享预设").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }
}
