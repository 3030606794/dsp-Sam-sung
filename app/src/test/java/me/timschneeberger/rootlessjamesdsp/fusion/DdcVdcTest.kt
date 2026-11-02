package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowPosixAtomicFile::class])
class DdcVdcTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = "SR_44100:1,0,0,0,0\nSR_48000:1,0,0,0,0\n"
    private fun rejects(value: String) = assertThrows(IllegalArgumentException::class.java) { DdcVdc.parse(value) }

    @Test fun allThreeBundledOriginalFilesAreAcceptedWithoutChangingAnyByte() {
        val app: Application = RuntimeEnvironment.getApplication()
        val expected = mapOf("Butterworth.vdc" to 20, "FrontRearContrast.vdc" to 8, "mh750.vdc" to 18)
        expected.forEach { (name, count) ->
            val bytes = app.assets.open("DDC/$name").use { it.readBytes() }
            val parsed = DdcVdc.parse(bytes)
            assertEquals(name, count, parsed.sosCount)
            assertArrayEquals(bytes, parsed.bytes())
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }, parsed.sha256)
            assertEquals(parsed, DdcVdc.parse(parsed.text))
        }
    }

    @Test fun strictDecimalGrammarWhitespaceAndReversedSectionsAreSupported() {
        val text = " \tSR_48000 \n: +1.0e+0, .0, -0., +0E-10, 0\r\nSR_44100:\t1, 0, 0, 0, 0 \n"
        val parsed = DdcVdc.parse(text)
        assertEquals(1, parsed.sosCount)
        assertEquals(1.0, parsed.section44100.single()[0], 0.0)
        assertEquals(text, parsed.text)
        assertNotEquals(DdcVdc.parse(identity).sha256, parsed.sha256)
    }

    @Test fun malformedExtraMissingAndDuplicateFieldsNeverPartlyParse() {
        listOf("", "SR_44100:1,0,0,0,0", identity + "SR_48000:1,0,0,0,0",
            identity.replace("SR_48000", "SR_44100"), identity.replace("SR_48000", "SR_96000"),
            identity.replace("SR_44100:", "SR_44100 ="), "comment\n$identity", identity + "extra:0",
            identity.replace("1,0,0,0,0", "1,0,0,0"), identity.replace("1,0,0,0,0", "1,0,0,0,0,"),
            identity.replace("1,0,0,0,0", "1,0,,0,0"), identity.replace("1,0,0,0,0", "1 0,0,0,0,0"),
            "SR_44100:1,0,0,0,0,1,0,0,0,0\nSR_48000:1,0,0,0,0",
            identity.replace("0,0,0,0", "0,0,0,0 # comment")
        ).forEach(::rejects)
    }

    @Test fun finiteAndAsciiRulesRejectMalformedUtf8NulHexAndTrailingTokens() {
        listOf("NaN", "Infinity", "-Infinity", "1e9999", "1000000000001", "0x1p0", "1x", "--1", "1e", "1_0", "1/2").forEach {
            rejects(identity.replace("1,0,0", "$it,0,0"))
        }
        listOf("\u0000", "\uFEFF\uFEFF", "中", "\u000b", "\u000c").forEach { rejects(it + identity) }
        listOf(byteArrayOf(0xc3.toByte(), 0x28), identity.toByteArray() + byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte()),
            identity.toByteArray() + byteArrayOf(0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { DdcVdc.parse(it) }
        }
        val bom = byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte()) + identity.toByteArray()
        assertArrayEquals(bom, DdcVdc.parse(bom).bytes())
        assertNotEquals(DdcVdc.parse(identity).sha256, DdcVdc.parse(bom).sha256)
    }

    @Test fun unstableFeedbackPolesAreRejectedInEitherRateIncludingUnitCircle() {
        listOf("1,0,0,2,0", "1,0,0,1,0", "1,0,0,-1,0", "1,0,0,0,1", "1,0,0,0,-1",
            "1,0,0,1.9,-0.8", "1,0,0,-1.9,-0.8", "1,0,0,.9999999999999,0").forEach { coefficients ->
            rejects("SR_44100:$coefficients\nSR_48000:1,0,0,0,0")
            rejects("SR_44100:1,0,0,0,0\nSR_48000:$coefficients")
        }
        assertEquals(1, DdcVdc.parse("SR_44100:1,0,0,1.8,-.9\nSR_48000:1,0,0,-1.8,-.9").sosCount)
    }

    @Test fun byteAndSosLimitsAreBoundedBeforeUntrustedParsing() {
        fun document(count: Int) = "SR_44100:" + List(count) { "1,0,0,0,0" }.joinToString(",") +
            "\nSR_48000:" + List(count) { "1,0,0,0,0" }.joinToString(",")
        assertEquals(DdcVdc.MAX_SOS, DdcVdc.parse(document(DdcVdc.MAX_SOS)).sosCount)
        rejects(document(DdcVdc.MAX_SOS + 1))
        val boundary = identity + " ".repeat(DdcVdc.MAX_BYTES - identity.length)
        assertEquals(DdcVdc.MAX_BYTES, DdcVdc.parse(boundary).bytes().size)
        rejects(boundary + " ")
        assertThrows(IllegalArgumentException::class.java) { DdcVdc.read(ByteArrayInputStream(ByteArray(DdcVdc.MAX_BYTES + 1))) }
        val file = temporary.newFile(); file.writeText(boundary + " ")
        assertThrows(IllegalArgumentException::class.java) { DdcVdc.read(file) }
    }

    @Test fun callerOwnsStreamAndZeroLengthReadsCannotBypassTheByteLimit() {
        var closed = false
        val input = object : ByteArrayInputStream(identity.toByteArray()) {
            var zero = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                if (zero) { zero = false; 0 } else { zero = true; super.read(buffer, offset, length) }
            override fun close() { closed = true; super.close() }
        }
        assertEquals(identity, DdcVdc.read(input).text); assertFalse(closed)
    }

    @Test fun foundationValidatesIdentityAndMetadataAndRestoresContentWithoutTrustingPath() {
        val root = temporary.newFolder()
        val parsed = DdcVdc.parse(identity)
        val foundation = DdcFoundation.from("../../outside.vdc", "原校正", parsed)
        val relative = foundation.install(root)
        assertEquals("DDC/收藏-${parsed.sha256}.vdc", relative)
        assertArrayEquals(parsed.bytes(), File(root, relative).readBytes())
        assertEquals(relative, foundation.install(root)); assertEquals(1, File(root, "DDC").listFiles()!!.size)
        listOf(foundation.copy(sha256 = "0".repeat(64)), foundation.copy(sha256 = parsed.sha256.uppercase()),
            foundation.copy(text = identity + " "), foundation.copy(label = ""), foundation.copy(label = "bad\nname"),
            foundation.copy(path = "bad\u0000path")).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validated() }
        }
    }

    @Test fun missingOrReplacedOriginalFileRestoresSavedBytesWithoutOverwritingOriginal() {
        val root = temporary.newFolder()
        val app: Context = RuntimeEnvironment.getApplication()
        val wrapped = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): File = root
        }
        val original = File(root, "DDC/original.vdc"); original.parentFile!!.mkdirs(); original.writeText(identity)
        val selected = DdcFoundation.selected(wrapped, "DDC/original.vdc")
        assertTrue(selected.matchesSelection(wrapped)); assertEquals("DDC/original.vdc", selected.install(wrapped))
        original.writeText(identity.replace("1,0,0", "0.5,0,0"))
        val before = original.readBytes(); assertFalse(selected.matchesSelection(wrapped))
        val restored = selected.install(wrapped)
        assertNotEquals(selected.path, restored); assertArrayEquals(before, original.readBytes())
        assertEquals(selected.sha256, DdcVdc.read(File(root, restored)).sha256)
        val missing = selected.copy(path = "DDC/missing.vdc")
        assertFalse(missing.matchesSelection(wrapped)); assertEquals(restored, missing.install(wrapped))
    }

    @Test fun partialFoundationRestoreFailureCannotDamagePreviousContentOrCommitASelection() {
        val root = temporary.newFolder(); val foundation = DdcFoundation.from("old.vdc", "保留", DdcVdc.parse(identity))
        val target = File(root, "DDC/收藏-${foundation.sha256}.vdc"); target.parentFile!!.mkdirs()
        target.writeText("previous bytes"); val before = target.readBytes()
        val original = File(root, "old.vdc").apply { writeText("original file unchanged") }
        val originalBefore = original.readBytes()
        assertThrows(IOException::class.java) {
            foundation.install(root) { stream, bytes -> stream.write(bytes, 0, 5); throw IOException("partial write") }
        }
        assertArrayEquals(before, target.readBytes()); assertFalse(File(target.path + ".new").exists())
        assertArrayEquals(originalBefore, original.readBytes())
        assertFalse(File(root, "DDC/收藏-${foundation.sha256}-2.vdc").exists())
        val restored = foundation.install(root)
        assertEquals("DDC/收藏-${foundation.sha256}-2.vdc", restored)
        assertEquals(foundation.sha256, DdcVdc.read(File(root, restored)).sha256)
        assertArrayEquals(before, target.readBytes()); assertArrayEquals(originalBefore, original.readBytes())
        // An interrupted sibling is also occupied; it must never be restored over.
        val second = DdcFoundation.from("old.vdc", "备份保留", DdcVdc.parse(identity + " "))
        val backup = File(root, "DDC/收藏-${second.sha256}.vdc.bak").apply { writeText("legacy backup") }
        assertEquals("DDC/收藏-${second.sha256}-2.vdc", second.install(root)); assertEquals("legacy backup", backup.readText())
    }
}
