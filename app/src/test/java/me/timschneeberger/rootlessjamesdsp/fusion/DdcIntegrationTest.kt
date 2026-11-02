package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.storage.Tar
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowPosixAtomicFile::class])
class DdcIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val p = DdcParameters
    private val prefs get() = DdcController.preferences(app)
    private val other get() = ToneController.preferences(app)
    private val target = Any()
    private val foundation get() = DdcTestFixture.foundation
    @Before fun prepare() {
        stopKoin(); startKoin { modules(module { single<Context> { app } }) }
        prefs.edit().clear().commit(); other.edit().clear().commit()
        DdcController.stopAudition(app, clearSnapshots = true)
        ToneAdvancedController.stopAudition(app, clearSnapshots = true)
    }
    @After fun cleanup() {
        DdcController.sessionClosed(app, target); DdcController.stopAudition(app, clearSnapshots = true)
        ToneAdvancedController.stopAudition(app, clearSnapshots = true)
        prefs.edit().clear().commit(); other.edit().clear().commit(); stopKoin()
    }
    private fun install(settings: DdcParameters.Settings = DdcTestFixture.settings) {
        p.apply(prefs, settings); prefs.edit().putBoolean(p.ENABLED, true).putString(p.FILE, foundation.path).commit()
        DdcController.publishCapabilities(app, 3)
        DdcController.publishFile(app, target, foundation.path, foundation.path, foundation.sha256, foundation.label)
        val words = IntArray(80).apply {
            this[0] = 1; this[1] = 2; this[2] = 48000; this[3] = 1; this[4] = if (settings.professional) 1 else 0
            this[5] = 1; this[12] = DdcController.epoch; this[15] = 1; this[16] = 48000; this[17] = 48000
            this[18] = 1; this[32] = 1000; this[33] = 1000
            p.shaWords(foundation.sha256).copyInto(this, 36); p.shaWords(foundation.sha256).copyInto(this, 62)
        }
        val runtime = requireNotNull(p.decode(words))
        DdcController.publishRuntime(app, listOf(DdcController.Reading(target, true, 0, 48000, runtime)))
        DdcController.publishRuntime(app, listOf(DdcController.Reading(target, true, 512, 48000, runtime)))
    }

    @Test fun everyProfessionalKeyRequires1051WhileOriginalPowerAndFileStayCompatible() {
        prefs.edit().putBoolean(p.ENABLED, false).putString(p.FILE, "DDC/original.vdc").commit()
        val old = Preset.minimumVersionCode(app); assertTrue(old.toInt() < 1051); val before = prefs.all.toMap()
        p.newKeys.forEach { key ->
            val edit = prefs.edit()
            when (val value = p.defaults().values().getValue(key)) { is Boolean -> edit.putBoolean(key, value); is Int -> edit.putInt(key, value); else -> fail(key) }
            edit.commit(); assertEquals("1051", Preset.minimumVersionCode(app)); prefs.edit().remove(key).commit()
        }
        assertEquals(old, Preset.minimumVersionCode(app)); assertEquals(before, prefs.all)
    }

    @Test fun wholeProfileKeepsOriginalFileStringPowerAndProfessionalTypesWithoutFavoritesOrTools() {
        install(); val settings = p.capture(prefs.all)
        val store = DdcPresetStore(app); val saved = store.save("设备配置之外DDC收藏", foundation, settings)
        assertTrue(DdcController.saveComparison(app, this, 0))
        val profile = Preset("ddc.tar", temporary.newFolder()); assertTrue(profile.save())
        val extracted = temporary.newFolder(); val metadata = Tar.Reader(profile.file().inputStream()).extract(extracted)!!
        assertEquals("1051", metadata[Preset.META_MIN_VERSION_CODE])
        val file = File(extracted, "${p.NAMESPACE}.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        fun element(key: String) = document.documentElement.childNodes.let { nodes ->
            (0 until nodes.length).map { nodes.item(it) }.single { it.attributes?.getNamedItem("name")?.nodeValue == key }
        }
        assertEquals("string", element(p.FILE).nodeName); assertEquals(foundation.path, element(p.FILE).textContent)
        assertEquals("boolean", element(p.ENABLED).nodeName)
        p.numbers.forEach { control -> assertEquals("int", element(control.key).nodeName) }
        p.switches.forEach { key -> assertEquals("boolean", element(key).nodeName) }
        val xml = file.readText(); listOf("audition", "snapshot", "measurement_result", "capabilities", "session", "sha256").forEach { assertFalse(xml.contains(it)) }
        assertTrue(extracted.listFiles()!!.none { it.name.contains("favorite") || it.name.contains("driver_status") })
        assertEquals(listOf(saved), store.load()); assertTrue(DdcController.hasComparison(0))
    }

    @Test fun olderProfileMissingDdcClearsModuleAndListeningStateButKeepsIndependentFavorites() {
        val output = app.getSharedPreferences(Constants.PREF_OUTPUT, Context.MODE_PRIVATE)
        output.edit().putFloat("output_postgain", -1f).commit()
        val profile = Preset("old.tar", temporary.newFolder())
        Tar.Composer(profile.file()).use { tar ->
            tar.metadata[Preset.META_VERSION] = "3"; tar.metadata[Preset.META_MIN_VERSION_CODE] = "1010"
            assertTrue(tar.add(File(app.applicationInfo.dataDir, "shared_prefs/${Constants.PREF_OUTPUT}.xml")))
        }
        install(); val store = DdcPresetStore(app); val saved = store.save("保留DDC收藏", foundation, p.capture(prefs.all))
        assertTrue(DdcController.saveComparison(app, this, 0))
        Preset.load(app, profile.file().inputStream())
        assertTrue(p.keys.none(prefs.all::containsKey)); assertFalse(p.moduleEnabled(prefs.all)); assertEquals(p.defaults(), p.capture(prefs.all))
        assertFalse(DdcController.hasComparison(0)); assertEquals(listOf(saved), store.load())
    }

    @Test fun advancedDriverGetsSinglePowerConfigShaAndToolsWithoutTouchingOtherModules() {
        install(); val settings = p.capture(prefs.all)
        val tone = ToneAdvancedParameters.defaults().copy(professional = true, coreOn = true, delta = List(10) { 73 })
        ToneAdvancedParameters.apply(other, tone); other.edit().putBoolean(ToneController.ENABLED, true).commit()
        val beforeOther = other.all.toMap(); val before = prefs.all.toMap(); val packets = mutableListOf<Pair<Int, IntArray>>()
        assertTrue(DdcController.sync(app, 3, target) { id, values -> packets += id to values; true })
        assertEquals(listOf(p.PACKET, p.TOOLS), packets.map { it.first })
        assertArrayEquals(settings.packet(true, foundation.sha256), packets[0].second)
        assertEquals(64, packets[0].second.size); assertEquals(132, packets[1].second.size); assertEquals(0, packets[1].second[0])
        assertEquals(before, prefs.all); assertEquals(beforeOther, other.all)
        packets.clear(); assertTrue(ToneAdvancedController.sync(app, 3, Any()) { id, values -> packets += id to values; true })
        assertArrayEquals(tone.packet(true), packets.single { it.first == ToneAdvancedParameters.PACKET }.second)
        assertEquals(before, prefs.all); assertEquals(beforeOther, other.all)
    }

    @Test fun unsupportedDdcCapabilitySendsNoUnknownCommandsAndKeepsSavedRequests() {
        install(); val before = prefs.all.toMap(); val packets = mutableListOf<Pair<Int, IntArray>>()
        listOf(0, 1).forEach { capability ->
            assertTrue(DdcController.sync(app, capability, target) { id, values -> packets += id to values; true })
        }
        assertTrue(packets.isEmpty()); assertEquals(before, prefs.all); assertEquals("1051", Preset.minimumVersionCode(app))
    }

    @Test fun failedFileBindingOrMalformedSelectionOnlyClearsToolsAndNeverSendsFallbackCorrection() {
        install(); val settings = p.capture(prefs.all)
        DdcController.publishFile(app, target, "DDC/broken.vdc", foundation.path, foundation.sha256, foundation.label, "DDC文件无效")
        prefs.edit().putString(p.FILE, "DDC/broken.vdc").commit()
        assertNull(DdcController.selectedSha256(app)); val before = prefs.all.toMap(); val packets = mutableListOf<Pair<Int, IntArray>>()
        assertTrue(DdcController.sync(app, 3, target) { id, values -> packets += id to values; true })
        assertEquals(listOf(p.CLEAR), packets.map { it.first }); assertArrayEquals(intArrayOf(0), packets.single().second)
        assertEquals(before, prefs.all); assertEquals(settings, p.capture(prefs.all)); assertFalse(DdcController.hasComparison(0))
        prefs.edit().putBoolean(p.ENABLED, false).putInt(p.FILE, 42).commit(); val beforeOff = prefs.all.toMap(); packets.clear()
        assertTrue(DdcController.sync(app, 3, target) { id, values -> packets += id to values; true })
        assertEquals(listOf(p.CLEAR, p.OFF), packets.map { it.first }); assertArrayEquals(intArrayOf(0), packets.last().second)
        assertFalse(packets.any { it.first == p.PACKET }); assertEquals(beforeOff, prefs.all)
    }
}
