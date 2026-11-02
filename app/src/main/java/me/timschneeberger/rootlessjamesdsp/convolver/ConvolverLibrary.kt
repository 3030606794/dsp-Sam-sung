package me.timschneeberger.rootlessjamesdsp.convolver

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.JdspImpResToolbox
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Owns the bundled IR without ever writing back to an imported source file. */
object ConvolverLibrary {
    const val DEFAULT_NAME = "立体声增强.irs"
    const val DEFAULT_PATH = "Convolver/$DEFAULT_NAME"
    const val DEFAULT_ASSET_PATH = "Convolver/stereo_enhance.irs"
    const val DEFAULT_SHA256 = "2829de835f04e7bcc7871a0a8185365ab8af769cf4edbd89b2a3505e26f054d8"
    private val retired = mapOf(
        "Church.wav" to "28aca4dc43f449f3f7609470473fc9cb66e19acb284be265ad801539d8c7bf81",
        "SwapChannels.wav" to "f1fc0cd52f978bf70bf6971922e81105fca528a19b872e52446845b6a2e85a6f"
    )

    fun displayName(name: String): String = when (name) {
        "CorredHRTF_Crossfeed.wav" -> "校正型交叉馈送（HRTF）"
        "CorredHRTF_Surround1.wav" -> "校正型环绕一（HRTF）"
        "CorredHRTF_Surround2.wav" -> "校正型环绕二（HRTF）"
        else -> name.substringBeforeLast('.')
    }
    internal fun isRetiredBuiltin(file: File) = retired[file.name]?.let { it == sha256(file) } == true

    @Synchronized fun install(context: Context) {
        val root = context.getExternalFilesDir(null) ?: return
        try {
            val prefs = context.getSharedPreferences(Constants.PREF_CONVOLVER, Context.MODE_PRIVATE)
            val key = context.getString(R.string.key_convolver_file)
            val previous = prefs.getString(key, "").orEmpty()
            val selected = resolve(root, previous)
            val moved = installFiles(root) { context.assets.open(it) }
            val replacement = if (selected != null && sameFile(selected, File(root, DEFAULT_PATH)) && moved != null)
                moved.relativeTo(root).path.replace(File.separatorChar, '/')
            else previous
            if (!usable(root, replacement)) prefs.edit().putString(key, DEFAULT_PATH).apply()
            else if (replacement != previous) prefs.edit().putString(key, replacement).apply()
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to install pulse-response library")
        }
    }

    /** A differing pre-existing file is preserved as a separate user copy, including its selection. */
    internal fun installFiles(root: File, openAsset: (String) -> InputStream): File? {
        val directory = File(root, "Convolver").apply { mkdirs() }
        val target = File(root, DEFAULT_PATH)
        var preserved: File? = null
        if (!target.exists() || sha256(target) != DEFAULT_SHA256) {
            val pending = File.createTempFile(".builtin-ir-", ".tmp", directory)
            try {
                openAsset(DEFAULT_ASSET_PATH).use { input -> pending.outputStream().use { input.copyTo(it) } }
                check(sha256(pending) == DEFAULT_SHA256) { "Bundled IR checksum mismatch" }
                // Prepare and verify the replacement before moving any existing user file.
                if (target.exists()) {
                    preserved = uniqueCopy(directory, "立体声增强_用户保留", "irs")
                    check(target.renameTo(preserved)) { "Cannot preserve existing IR" }
                }
                if (!pending.renameTo(target)) {
                    preserved?.renameTo(target)
                    error("Cannot install bundled IR")
                }
            } finally { pending.delete() }
        }
        // Retire originals only after a verified default is available as a fallback.
        retired.forEach { (name, hash) ->
            File(directory, name).takeIf { it.isFile && sha256(it) == hash }?.delete()
        }
        listOf("CorredHRTF_Crossfeed.wav", "CorredHRTF_Surround1.wav", "CorredHRTF_Surround2.wav").forEach { name ->
            val file = File(directory, name)
            if (!file.exists()) openAsset("Convolver/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return preserved
    }

    internal fun usable(root: File, path: String): Boolean {
        val file = resolve(root, path) ?: return false
        if (!file.isFile || !file.canRead() || file.length() == 0L) return false
        if (file.extension.lowercase() !in listOf("wav", "irs", "flac")) return false
        return file.inputStream().use {
            val header = ByteArray(12)
            val n = it.read(header)
            if (file.extension.equals("flac", true)) n >= 4 && String(header, 0, 4, Charsets.US_ASCII) == "fLaC"
            else n == 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
    }

    private fun resolve(root: File, path: String): File? =
        path.takeIf { it.isNotBlank() }?.let { if (File(it).isAbsolute) File(it) else File(root, it) }

    fun isProtected(context: Context, file: File): Boolean = context.getExternalFilesDir(null)?.let {
        isProtected(it, file)
    } ?: false
    internal fun isProtected(root: File, file: File): Boolean = sameFile(File(root, DEFAULT_PATH), file)
    private fun sameFile(a: File, b: File): Boolean = runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(false)

    /** UI visibility is only an aid; every mutation is checked again here. */
    fun delete(context: Context, file: File): Boolean = !isProtected(context, file) && file.delete()
    fun rename(context: Context, source: File, destination: File): Boolean =
        !isProtected(context, source) && !isProtected(context, destination) && !destination.exists() && source.renameTo(destination)
    @Synchronized fun copy(context: Context, source: File, destination: File): Boolean {
        if (isProtected(context, destination)) return false
        return try {
            if (!destination.createNewFile()) false
            else try {
                source.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
                true
            } catch (error: Exception) { destination.delete(); throw error }
        } catch (_: Exception) { false }
    }

    /** Native resampling only sees a private working copy, never the library original. */
    fun resampleCopy(context: Context, source: File, sampleRate: Int): File? =
        resampleCopy(context, source, sampleRate) { directory, name, rate -> JdspImpResToolbox.OfflineAudioResample(directory, name, rate) }

    @Synchronized internal fun resampleCopy(context: Context, source: File, sampleRate: Int,
                                           resample: (String, String, Int) -> String): File? {
        if (!source.isFile || sampleRate !in 8000..384000) return null
        val work = File.createTempFile("ir-resample-", ".work", context.cacheDir)
        if (!work.delete() || !work.mkdir()) return null
        return try {
            val input = File(work, source.name)
            source.copyTo(input)
            val path = resample(work.absolutePath + "/", input.name, sampleRate)
            val output = File(path)
            if (path == "Invalid" || !output.isFile || output.parentFile?.canonicalFile != work.canonicalFile) null
            else {
                val target = uniqueCopy(source.parentFile!!, "${sampleRate}_${source.nameWithoutExtension}", source.extension)
                if (copy(context, output, target)) target else null
            }
        } catch (ex: Exception) { Timber.e(ex, "IR resampling failed"); null }
        finally { work.deleteRecursively() }
    }

    internal fun uniqueCopy(directory: File, stem: String, extension: String): File {
        var number = 1
        var result: File
        do { result = File(directory, "${stem}_${number++}.$extension") } while (result.exists())
        return result
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
