package me.timschneeberger.rootlessjamesdsp.utils.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import me.timschneeberger.rootlessjamesdsp.fusion.DdcVdc
import me.timschneeberger.rootlessjamesdsp.convolver.ConvolverLibrary
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream


object StorageUtils {

    fun importFile(context: Context, targetDir: String, uri: Uri): File? {
        val name = queryName(context, uri)
        if(name == null) {
            Timber.e("importFile: name is null")
            return null
        }

        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            return input.use { importStream(context, File(targetDir), name, it) }
        } catch (ex: Exception) {
            Timber.e(ex.message)
            ex.printStackTrace()
            return null
        }
    }

    @Synchronized internal fun importStream(context: Context, directory: File, name: String, input: InputStream): File? {
        // A document provider name must be a basename, never a relative path.
        if (name != File(name).name || name.contains('/') || name.contains('\\') || name.isBlank()) return null
        var destination = File(directory, name)
        if (ConvolverLibrary.isProtected(context, destination)) return null
        if (directory.name == "DDC") {
            if (!name.endsWith(".vdc",ignoreCase=true)) return null
            return runCatching {
                val parsed=DdcVdc.read(input)
                require(directory.isDirectory || directory.mkdirs()) { "无法创建 DDC 文件库" }
                var suffix=2
                while(destination.exists() || File(destination.path+".bak").exists() || File(destination.path+".new").exists()) {
                    require(suffix<=10000) { "DDC 文件名冲突过多" }
                    destination=File(directory,"${File(name).nameWithoutExtension}-${suffix++}.vdc")
                }
                val atomic=AtomicFile(destination)
                var stream:FileOutputStream?=null
                try {
                    stream=atomic.startWrite();stream.write(parsed.bytes());atomic.finishWrite(stream);stream=null
                    require(destination.isFile && !File(destination.path+".new").exists() && DdcVdc.read(destination).sha256==parsed.sha256) {
                        "DDC 原子写入未完成，已有文件保持不变"
                    }
                    destination
                } catch(error:Exception) {
                    stream?.let(atomic::failWrite)
                    throw error
                }
            }.getOrElse { Timber.w(it,"DDC import rejected; existing files retained");null }
        }
        if (destination.exists() && directory.name == "Convolver") {
            destination = ConvolverLibrary.uniqueCopy(directory, destination.nameWithoutExtension, destination.extension)
        }
        return if (createFileFromStream(input, destination)) destination else null
    }

    fun openInputStreamSafe(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (ex: Exception) {
            Timber.e(ex.message)
            ex.printStackTrace()
            null
        }
    }

    private fun createFileFromStream(ins: InputStream, destination: File?): Boolean {
        try {
            FileOutputStream(destination).use { os ->
                val buffer = ByteArray(4096)
                var length: Int
                while (ins.read(buffer).also { length = it } > 0) {
                    os.write(buffer, 0, length)
                }
                os.flush()
            }
        } catch (ex: Exception) {
            Timber.e(ex.message)
            ex.printStackTrace()
            return false
        }
        return true
    }

    fun queryName(context: Context, uri: Uri): String? {
        val returnCursor = context.contentResolver.query(uri, null, null, null, null)
        returnCursor ?: return null
        val nameIndex: Int = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        returnCursor.moveToFirst()
        val name: String = returnCursor.getString(nameIndex)
        returnCursor.close()
        return name
    }
}
