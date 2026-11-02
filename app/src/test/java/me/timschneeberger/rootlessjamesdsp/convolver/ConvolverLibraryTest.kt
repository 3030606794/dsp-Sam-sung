package me.timschneeberger.rootlessjamesdsp.convolver

import android.app.Application
import android.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.storage.StorageUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConvolverLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_CONVOLVER, 0)
    private val root get() = app.getExternalFilesDir(null)!!
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    private fun install(root: File) = ConvolverLibrary.installFiles(root) { File(assets, it).inputStream() }
    private fun key() = app.getString(R.string.key_convolver_file)
    @Before fun prepare() {
        prefs.edit().clear().commit()
        File(root, "Convolver").deleteRecursively()
    }

    @Test fun packagedAssetCanBeOpenedThroughAndroidAssetManager() {
        // Let a packaging or native AssetManager error reach the test report with its real cause.
        app.assets.open("Convolver/CorredHRTF_Crossfeed.wav").use { assertTrue(it.read() >= 0) }
        app.assets.open(ConvolverLibrary.DEFAULT_ASSET_PATH).use { input ->
            assertEquals(131108, input.readBytes().size)
        }
    }

    @Test fun failedBuiltinReadLeavesExistingSourceAndRetiredOriginalIntact() {
        val isolated = temporary.newFolder()
        val target = File(isolated, ConvolverLibrary.DEFAULT_PATH).apply { parentFile!!.mkdirs(); writeText("existing user's IR") }
        val old = File(target.parentFile, "SwapChannels.wav")
        GZIPInputStream(javaClass.getResourceAsStream("/convolver/retired-swap.wav.gz")!!).use { input -> old.outputStream().use { input.copyTo(it) } }
        assertTrue(runCatching { ConvolverLibrary.installFiles(isolated) { throw java.io.IOException("asset unavailable") } }.isFailure)
        assertEquals("existing user's IR", target.readText()); assertTrue(old.exists())
        assertEquals(setOf(target.name, old.name), target.parentFile!!.list()!!.toSet())
    }

    @Test fun bundledDefaultDecodesAsFiniteStereoFloatAndSourceHashIsExact() {
        val library = File(assets, "Convolver")
        assertEquals(setOf("stereo_enhance.irs", "CorredHRTF_Crossfeed.wav", "CorredHRTF_Surround1.wav", "CorredHRTF_Surround2.wav"), library.list()!!.toSet())
        val default = File(assets, ConvolverLibrary.DEFAULT_ASSET_PATH)
        assertEquals(ConvolverLibrary.DEFAULT_SHA256, ConvolverLibrary.sha256(default))
        val b = ByteBuffer.wrap(default.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(131108, b.limit()); assertEquals(3, b.getShort(20).toInt())
        assertEquals(2, b.getShort(22).toInt()); assertEquals(44100, b.getInt(24)); assertEquals(32, b.getShort(34).toInt())
        assertEquals(16383, b.getInt(40) / 8)
        b.position(44)
        while (b.remaining() >= 4) assertTrue(b.float.isFinite())
    }

    @Test fun freshAndMissingSelectionsUseDefaultWithoutTurningModuleOn() {
        listOf("", "Convolver/missing.wav", "Convolver/broken.wav").forEach { path ->
            if (path.endsWith("broken.wav")) File(root, path).apply { parentFile!!.mkdirs(); writeText("not audio") }
            prefs.edit().putString(key(), path).putBoolean("convolver_enable", false).commit()
            ConvolverLibrary.install(app)
            assertEquals(ConvolverLibrary.DEFAULT_PATH, prefs.getString(key(), ""))
            assertFalse(prefs.getBoolean("convolver_enable", true))
            assertEquals(ConvolverLibrary.DEFAULT_SHA256, ConvolverLibrary.sha256(File(root, ConvolverLibrary.DEFAULT_PATH)))
        }
    }

    @Test fun validOldRelativeAndAbsoluteSelectionsSurviveAndBuiltinNamesAreOnlyDisplayAliases() {
        ConvolverLibrary.install(app)
        val old = "Convolver/CorredHRTF_Surround2.wav"
        listOf(old, File(root, old).absolutePath).forEach { path ->
            prefs.edit().putString(key(), path).commit(); ConvolverLibrary.install(app)
            assertEquals(path, prefs.getString(key(), ""))
        }
        assertEquals("校正型交叉馈送（HRTF）", ConvolverLibrary.displayName("CorredHRTF_Crossfeed.wav"))
        assertEquals("校正型环绕一（HRTF）", ConvolverLibrary.displayName("CorredHRTF_Surround1.wav"))
        assertEquals("校正型环绕二（HRTF）", ConvolverLibrary.displayName("CorredHRTF_Surround2.wav"))
        assertEquals("用户房间", ConvolverLibrary.displayName("用户房间.wav"))
    }

    @Test fun onlyUntouchedRetiredBuiltinIsRemovedAndCustomSameNameIsPreserved() {
        val isolated = temporary.newFolder()
        val dir = File(isolated, "Convolver").apply { mkdirs() }
        val original = javaClass.getResourceAsStream("/convolver/retired-swap.wav.gz")!!
        val old = File(dir, "SwapChannels.wav")
        GZIPInputStream(original).use { input -> old.outputStream().use { input.copyTo(it) } }
        assertEquals("f1fc0cd52f978bf70bf6971922e81105fca528a19b872e52446845b6a2e85a6f", ConvolverLibrary.sha256(old))
        val church = File(dir, "Church.wav").apply { writeText("a user's different IR") }
        install(isolated)
        assertFalse(old.exists()); assertEquals("a user's different IR", church.readText())
        old.writeText("custom swap IR")
        install(isolated)
        assertEquals("custom swap IR", old.readText())
    }

    @Test fun existingUserFileAtDefaultNameAndItsSelectionArePreservedAsSeparateCopy() {
        val target = File(root, ConvolverLibrary.DEFAULT_PATH).apply { parentFile!!.mkdirs() }
        val custom = File(assets, "Convolver/CorredHRTF_Crossfeed.wav").readBytes()
        target.writeBytes(custom)
        prefs.edit().putString(key(), target.absolutePath).putBoolean("convolver_enable", true).commit()
        ConvolverLibrary.install(app)
        val selection = prefs.getString(key(), "")!!
        assertTrue(selection.startsWith("Convolver/立体声增强_用户保留_"))
        assertArrayEquals(custom, File(root, selection).readBytes())
        assertEquals(ConvolverLibrary.DEFAULT_SHA256, ConvolverLibrary.sha256(target))
        assertTrue(prefs.getBoolean("convolver_enable", false))
        ConvolverLibrary.install(app)
        assertEquals(selection, prefs.getString(key(), ""))
        assertEquals(1, target.parentFile!!.list()!!.count { it.startsWith("立体声增强_用户保留_") })
    }

    @Test fun mutationServicesRejectDefaultAndCanonicalAliasesWithoutDependingOnMenus() {
        ConvolverLibrary.install(app)
        val protectedIr = File(root, ConvolverLibrary.DEFAULT_PATH)
        val alias = File(root, "Convolver/../Convolver/${ConvolverLibrary.DEFAULT_NAME}")
        val copy = File(protectedIr.parentFile, "copy.irs")
        assertTrue(ConvolverLibrary.copy(app, protectedIr, copy))
        assertFalse(ConvolverLibrary.delete(app, alias))
        assertFalse(ConvolverLibrary.rename(app, alias, File(copy.parentFile, "renamed.irs")))
        assertFalse(ConvolverLibrary.rename(app, copy, alias))
        assertFalse(ConvolverLibrary.copy(app, copy, alias))
        assertNull(StorageUtils.importStream(app, protectedIr.parentFile!!, protectedIr.name, "replacement".byteInputStream()))
        assertNull(StorageUtils.importStream(app, protectedIr.parentFile!!, "../${protectedIr.name}", "replacement".byteInputStream()))
        assertEquals(ConvolverLibrary.DEFAULT_SHA256, ConvolverLibrary.sha256(protectedIr))
        assertTrue(ConvolverLibrary.delete(app, copy))
    }

    @Test fun importsWithExistingNamesCreateCopiesAndNeverReplaceTheOriginalIr() {
        ConvolverLibrary.install(app)
        val directory = File(root, "Convolver")
        val original = File(directory, "room.wav").apply { writeText("original") }
        val first = StorageUtils.importStream(app, directory, original.name, "version one".byteInputStream())!!
        val second = StorageUtils.importStream(app, directory, original.name, "version two".byteInputStream())!!
        assertNotEquals(original, first); assertNotEquals(first, second)
        assertEquals("original", original.readText()); assertEquals("version one", first.readText()); assertEquals("version two", second.readText())
    }

    @Test fun removedStockSelectionFallsBackWhileAnEditedStockFileStaysSelected() {
        val old = File(root, "Convolver/SwapChannels.wav").apply { parentFile!!.mkdirs() }
        GZIPInputStream(javaClass.getResourceAsStream("/convolver/retired-swap.wav.gz")!!).use { input -> old.outputStream().use { input.copyTo(it) } }
        prefs.edit().putString(key(), "Convolver/SwapChannels.wav").commit(); ConvolverLibrary.install(app)
        assertEquals(ConvolverLibrary.DEFAULT_PATH, prefs.getString(key(), "")); assertFalse(old.exists())
        File(assets, "Convolver/CorredHRTF_Crossfeed.wav").copyTo(old)
        prefs.edit().putString(key(), "Convolver/SwapChannels.wav").commit(); ConvolverLibrary.install(app)
        assertEquals("Convolver/SwapChannels.wav", prefs.getString(key(), "")); assertTrue(old.exists())
    }

    @Test fun resamplingRunsOnWorkingCopiesAndCannotReplaceDefaultOrEarlierResult() {
        ConvolverLibrary.install(app)
        val source = File(root, ConvolverLibrary.DEFAULT_PATH)
        val previous = File(source.parentFile, "48000_立体声增强_1.irs").apply { writeText("previous result") }
        val seen = mutableListOf<File>()
        val backend = { directory: String, name: String, _: Int ->
            val working = File(directory, name)
            assertNotEquals(source.canonicalFile, working.canonicalFile)
            assertArrayEquals(source.readBytes(), working.readBytes()); seen += working.parentFile!!
            File(directory, "48000_$name").apply { writeText("resampled copy") }.absolutePath
        }
        val first = ConvolverLibrary.resampleCopy(app, source, 48000, backend)!!
        val second = ConvolverLibrary.resampleCopy(app, source, 48000, backend)!!
        assertNotEquals(first, second); assertNotEquals(previous, first)
        assertEquals("previous result", previous.readText()); assertEquals("resampled copy", first.readText())
        assertEquals(ConvolverLibrary.DEFAULT_SHA256, ConvolverLibrary.sha256(source))
        assertTrue(seen.all { !it.exists() })
        assertNull(ConvolverLibrary.resampleCopy(app, source, 48000) { _, _, _ -> source.absolutePath })
        assertEquals(ConvolverLibrary.DEFAULT_SHA256, ConvolverLibrary.sha256(source))
    }

    @Test fun firstPreferenceAttachShowsDefaultSummaryAndThreeChineseAliasesWithPortableOldPaths() {
        val context = ContextThemeWrapper(app, R.style.Theme_RootlessJamesDSP)
        val manager = PreferenceManager(context).apply { sharedPreferencesName = Constants.PREF_CONVOLVER }
        val screen = manager.createPreferenceScreen(context)
        val row = FileLibraryPreference(context, null).apply { key = app.getString(R.string.key_convolver_file); type = "Convolver"; setDefaultValue("") }
        screen.addPreference(row)
        assertEquals(ConvolverLibrary.DEFAULT_PATH, row.value)
        assertEquals("立体声增强", row.summary.toString())
        assertEquals(4, row.entries.size)
        assertTrue(row.entries.map { it.toString() }.containsAll(listOf("校正型交叉馈送（HRTF）", "校正型环绕一（HRTF）", "校正型环绕二（HRTF）")))
        assertTrue(row.entryValues.map { it.toString() }.contains("Convolver/CorredHRTF_Surround2.wav"))
        row.value = "Convolver/CorredHRTF_Surround2.wav"
        assertEquals("校正型环绕二（HRTF）", row.summary.toString())
    }
}
