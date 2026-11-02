package me.timschneeberger.rootlessjamesdsp.convolver

import android.app.Application
import android.content.Context
import android.net.Uri
import me.timschneeberger.rootlessjamesdsp.backup.BackupManager
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.storage.Tar
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarHeader
import org.kamranzafar.jtar.TarInputStream
import org.kamranzafar.jtar.TarOutputStream
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class)
class ConvolverBackupRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val root get()=app.getExternalFilesDir(null)!!
    private val assets=listOf(File("src/main/assets"),File("app/src/main/assets")).first {it.isDirectory}
    private val original get()=File(root,ConvolverLibrary.DEFAULT_PATH)
    private val defaultBytes get()=File(assets,ConvolverLibrary.DEFAULT_ASSET_PATH).readBytes()
    private fun userBytes()=File(assets,"Convolver/CorredHRTF_Crossfeed.wav").readBytes()
    @Before fun prepare() {
        stopKoin();val prefs=Preferences(app)
        startKoin { modules(module {single<Context> {app};single {prefs.App()}}) }
        app.getSharedPreferences(Constants.PREF_CONVOLVER,0).edit().clear().commit()
        File(root,"Convolver").deleteRecursively();ConvolverLibrary.install(app)
    }
    @After fun cleanup(){stopKoin()}
    private fun xml(path: String)="""<?xml version="1.0" encoding="utf-8"?><map><string name="convolver_file">$path</string><boolean name="convolver_enable" value="true"/><int name="conv1_mix_tenths" value="725"/></map>""".toByteArray()
    private fun selected(file: File): String {
        val nodes=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map {nodes.item(it)}.first {it.attributes.getNamedItem("name").nodeValue=="convolver_file"}.textContent
    }
    private fun tar(entries: Map<String,ByteArray>,backup: Boolean=true): ByteArray {
        val output=ByteArrayOutputStream()
        Tar.Composer(output).use {composer->
            if(backup)composer.metadata[BackupManager.META_IS_BACKUP]="true"
            else {composer.metadata[Preset.META_VERSION]="3";composer.metadata[Preset.META_MIN_VERSION_CODE]="1010"}
            entries.forEach {(name,bytes)->val file=temporary.newFile().apply {writeBytes(bytes)};assertTrue(composer.add(file,name))}
        }
        return output.toByteArray()
    }
    private fun restore(entries: Map<String,ByteArray>,dirty: Boolean) {
        val bytes=ByteArrayOutputStream().also {out->GZIPOutputStream(out).use {it.write(tar(entries))}}.toByteArray()
        val uri=Uri.parse("content://convolver-test/${System.nanoTime()}")
        shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream(bytes))
        BackupManager(app).restoreBackup(uri,dirty)
    }
    private fun selectionFile()=File(app.applicationInfo.dataDir,"shared_prefs/${Constants.PREF_CONVOLVER}.xml")

    @Test fun cleanRestorePreservesDefaultAndExistingIrWhileIncomingCollisionGetsItsOwnSelection() {
        val existing=File(root,"Convolver/房间校正.irs").apply {writeBytes(userBytes())}
        restore(mapOf("Convolver/房间校正.irs" to defaultBytes,"shared_prefs/dsp_convolver.xml" to xml("Convolver/房间校正.irs")),false)
        assertArrayEquals(userBytes(),existing.readBytes())
        assertEquals(ConvolverLibrary.DEFAULT_SHA256,ConvolverLibrary.sha256(original))
        val path=selected(selectionFile());assertNotEquals("Convolver/房间校正.irs",path)
        assertTrue(path.startsWith("Convolver/房间校正_恢复_"));assertArrayEquals(defaultBytes,File(root,path).readBytes())
        assertTrue(selectionFile().readText().contains("725"))
    }

    @Test fun bothRestoreModesPreserveProtectedDefaultAndIncomingDifferentSameNameWithAbsoluteSelection() {
        listOf(false,true).forEach {dirty->
            restore(mapOf(ConvolverLibrary.DEFAULT_PATH to userBytes(),"shared_prefs/dsp_convolver.xml" to xml("/storage/emulated/0/Android/data/old.app/files/${ConvolverLibrary.DEFAULT_PATH}")),dirty)
            assertEquals(ConvolverLibrary.DEFAULT_SHA256,ConvolverLibrary.sha256(original))
            val path=selected(selectionFile());assertTrue(path.startsWith("Convolver/立体声增强_恢复_"))
            assertArrayEquals(userBytes(),File(root,path).readBytes());assertTrue(original.exists())
        }
    }

    @Test fun identicalIncomingIrIsReusedAndStockDefaultRemainsUndeletable() {
        val existing=File(root,"Convolver/room.wav").apply {writeBytes(userBytes())}
        restore(mapOf("Convolver/room.wav" to userBytes(),ConvolverLibrary.DEFAULT_PATH to defaultBytes,
            "shared_prefs/dsp_convolver.xml" to xml("Convolver/room.wav")),true)
        assertEquals("Convolver/room.wav",selected(selectionFile()));assertArrayEquals(userBytes(),existing.readBytes())
        assertEquals(5,File(root,"Convolver").listFiles()!!.size)
        assertFalse(ConvolverLibrary.delete(app,original))
    }

    @Test fun deviceProfileTarSelectionIsRemappedWhileOtherEntriesMetadataAndSourceBackupStayUnchanged() {
        val profile=tar(mapOf("dsp_convolver.xml" to xml(ConvolverLibrary.DEFAULT_PATH),"dsp_fusion_viper.xml" to "<map><int name=\"v_bass\" value=\"123\"/></map>".toByteArray(),"unrelated.bin" to byteArrayOf(0,1,2,-1,127)),backup=false)
        fun entries(bytes: ByteArray): Map<String,ByteArray> = linkedMapOf<String,ByteArray>().apply {
            TarInputStream(ByteArrayInputStream(bytes)).use {input->while(true){val entry=input.nextEntry ?: break;put(entry.name,input.readBytes())}}
        }
        val before=entries(profile)
        restore(mapOf(ConvolverLibrary.DEFAULT_PATH to userBytes(),"profiles/device/profile.tar" to profile,
            "profiles/device/profile.json" to "{\"name\":\"USB\",\"id\":\"device\",\"group\":\"USB\"}".toByteArray()),true)
        val afterFile=File(app.filesDir,"profiles/device/profile.tar");val after=entries(afterFile.readBytes())
        assertEquals(before.keys,after.keys)
        before.filterKeys {it!="dsp_convolver.xml"}.forEach {(name,bytes)->assertArrayEquals(name,bytes,after[name])}
        val extracted=temporary.newFolder();assertNotNull(Tar.Reader(afterFile.inputStream()).extract(extracted))
        val path=selected(File(extracted,"dsp_convolver.xml"));assertTrue(path.startsWith("Convolver/立体声增强_恢复_"))
        assertArrayEquals(userBytes(),File(root,path).readBytes());assertArrayEquals(before["dsp_convolver.xml"],entries(profile)["dsp_convolver.xml"])
        Preset.load(app,afterFile.inputStream()) // The restored profile remains a valid legacy preset.
        assertEquals(ConvolverLibrary.DEFAULT_SHA256,ConvolverLibrary.sha256(original))
    }

    @Test fun utf8ChineseAndAsciiArchivePathsRoundTripAndLegacyNonUtf8NameKeepsOldBehavior() {
        val bytes=tar(mapOf("Convolver/立体声增强.irs" to userBytes(),"Convolver/ascii.wav" to defaultBytes))
        val folder=temporary.newFolder();assertNotNull(Tar.Reader(ByteArrayInputStream(bytes)).extract(folder))
        assertArrayEquals(userBytes(),File(folder,"Convolver/立体声增强.irs").readBytes())
        assertArrayEquals(defaultBytes,File(folder,"Convolver/ascii.wav").readBytes())
        val legacy=ByteArrayOutputStream()
        TarOutputStream(legacy).use {out->val header=TarHeader.createHeader("legacy_\u00e9.wav",1,0,false,420);out.putNextEntry(TarEntry(header));out.write(7)}
        val expected=TarInputStream(ByteArrayInputStream(legacy.toByteArray())).use {it.nextEntry!!.name}
        val oldFolder=temporary.newFolder();assertNotNull(Tar.Reader(ByteArrayInputStream(legacy.toByteArray())).extract(oldFolder))
        assertArrayEquals(byteArrayOf(7),File(oldFolder,expected).readBytes())
    }

    @Test fun escapingBackupEntryCannotOverwriteExistingIrBeforeRestoreGuardsRun() {
        val outside=temporary.newFile("outside.wav").apply {writeText("original outside")}
        val archive=tar(mapOf("../outside.wav" to "replace".toByteArray()))
        val extraction=File(outside.parentFile,"extract")
        assertNull(Tar.Reader(ByteArrayInputStream(archive)).extract(extraction))
        assertEquals("original outside",outside.readText())
        val defaultHash=ConvolverLibrary.sha256(original)
        val escape="Convolver/../../../../../outside.wav"
        assertTrue(runCatching {restore(mapOf(escape to "replace".toByteArray()),false)}.isFailure)
        assertEquals(defaultHash,ConvolverLibrary.sha256(original))
    }
}
