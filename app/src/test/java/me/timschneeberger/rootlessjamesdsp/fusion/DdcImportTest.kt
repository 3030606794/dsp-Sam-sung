package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.storage.StorageUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class DdcImportTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val valid="SR_44100:1,0,0,0,0\nSR_48000:1,0,0,0,0\n".toByteArray()
    private fun directory()=File(app.cacheDir,"ddc-import-${System.nanoTime()}/DDC").apply {mkdirs()}
    @Test fun uppercaseExtensionAndBomImportKeepOriginalBytes() {
        assertTrue(FileLibraryPreference.hasVdcExtension("personal.VDC"))
        val bytes=byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+valid
        val target=StorageUtils.importStream(app,directory(),"personal.VDC",bytes.inputStream())
        assertNotNull(target);assertArrayEquals(bytes,target!!.readBytes())
        assertEquals(DdcVdc.parse(bytes).sha256,DdcVdc.read(target).sha256)
    }
    @Test fun duplicateNameCreatesANewFileAndKeepsTheExistingCorrection() {
        val dir=directory();val old=File(dir,"personal.vdc").apply {writeBytes(valid)}
        val changed=String(valid).replace(":1,",":0.5,").toByteArray()
        val imported=StorageUtils.importStream(app,dir,"personal.vdc",changed.inputStream())
        assertEquals("personal-2.vdc",imported!!.name)
        assertArrayEquals(valid,old.readBytes());assertArrayEquals(changed,imported.readBytes())
    }
    @Test fun malformedOrInterruptedReadNeverChangesAnExistingFileOrLeavesAPartialCopy() {
        val dir=directory();val old=File(dir,"personal.vdc").apply {writeBytes(valid)}
        assertNull(StorageUtils.importStream(app,dir,old.name,"SR_44100:1,0,0,0,0".byteInputStream()))
        val interrupted=object:InputStream(){var n=0;override fun read():Int {if(++n>7)throw java.io.IOException("interrupted");return 65}}
        assertNull(StorageUtils.importStream(app,dir,old.name,interrupted))
        assertArrayEquals(valid,old.readBytes());assertEquals(listOf(old.name),dir.list()!!.toList())
    }
    @Test fun providerTraversalNameIsRejectedBeforeWritingOutsideTheLibrary() {
        val dir=directory()
        for(name in listOf("../outside.vdc","sub/path.vdc","sub\\path.vdc"))
            assertNull(StorageUtils.importStream(app,dir,name,valid.inputStream()))
        assertTrue(dir.list()!!.isEmpty());assertFalse(File(dir.parentFile,"outside.vdc").exists())
    }
}
