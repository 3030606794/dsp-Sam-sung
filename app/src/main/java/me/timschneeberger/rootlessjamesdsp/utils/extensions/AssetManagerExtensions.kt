package me.timschneeberger.rootlessjamesdsp.utils.extensions

import android.content.Context
import android.content.res.AssetManager
import me.timschneeberger.rootlessjamesdsp.convolver.ConvolverLibrary
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

object AssetManagerExtensions {
    fun AssetManager.installPrivateAssets(context: Context, force: Boolean) {
        Timber.d("Installing private assets; force=$force")
        context.getExternalFilesDir(null)?.absolutePath?.let {
            try {
                // Convolver owns its protected default; force never overwrites imported IRs.
                ConvolverLibrary.install(context)
                this.copyAssetDir("DDC", it, force)
                this.copyAssetDir("Liveprog", it, force)
            }
            catch (ex: Exception) {
                Timber.e("Failed to extract assets")
                Timber.e(ex)
            }
        }
    }

    private fun AssetManager.copyAssetDir(assetPath: String, destDirPath: String, force: Boolean) {
        this.walkAssetDir(assetPath, force) {
            this.copyAssetFile(it, "$destDirPath/$it", force)
        }
    }

    private fun AssetManager.walkAssetDir(assetPath: String, force: Boolean, callback: ((String) -> Unit)) {
        val children = this.list(assetPath) ?: return
        if (children.isEmpty()) {
            callback(assetPath)
        } else {
            for (child in children) {
                this.walkAssetDir("$assetPath/$child", force, callback)
            }
        }
    }

    private fun AssetManager.copyAssetFile(assetPath: String, destPath: String, force: Boolean): File? {
        val destFile = File(destPath)
        // Existing scripts belong to the user, including their literal parameter values.
        // First-run setup and resource repair may fill missing files, never replace scripts.
        val protectedScript = assetPath.startsWith("Liveprog/") &&
            assetPath.endsWith(".eel", ignoreCase = true)
        if(destFile.exists() && (protectedScript || !force)) {
            return null
        }

        File(destFile.parent!!).mkdirs()
        destFile.createNewFile()

        this.open(assetPath).use { src ->
            FileOutputStream(destFile).use { dest ->
                src.copyTo(dest)
            }
        }

        return destFile
    }
}
