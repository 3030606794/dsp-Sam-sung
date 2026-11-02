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
class ToneAdvancedIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val p = ToneAdvancedParameters
    private val prefs get() = ToneController.preferences(app)
    private val other get() = DynamicController.preferences(app)
    private fun settings() = p.defaults().copy(
        base = listOf(-31,-24,-17,-10,-3,4,11,18,25,32), baseRange = "100",
        professional = true, coreOn = true, amount = 731, delta = List(10) { 73 },
        shapeOn = true, frequency = listOf(173,3517,63,113,353,1817,3531,5531,8531,12317),
        dynamicOn = true, bassFast = 73, bassSlow = 173, transientFast = 37, transientSlow = 73,
        weakOn = true, weakFloor = -731, mixOn = true, mix = 731,
        outputOn = true, output = -17, headroomOn = true, headroom = -13,
        meters = true, response = true)
    @Before fun prepare() {
        stopKoin(); startKoin { modules(module { single<Context> { app } }) }
        prefs.edit().clear().commit(); other.edit().clear().commit()
        ToneAdvancedController.stopAudition(app, clearSnapshots = true)
        DynamicController.stopAudition(app, clearSnapshots = true)
    }
    @After fun cleanup() {
        ToneAdvancedController.stopAudition(app, clearSnapshots = true)
        DynamicController.stopAudition(app, clearSnapshots = true)
        prefs.edit().clear().commit(); other.edit().clear().commit(); stopKoin()
    }

    @Test fun everyProfessionalKeyRequires1050WhileOldTenIntValuesAndRangeRemainCompatible() {
        prefs.edit().putBoolean(p.ENABLED, false).putString(p.BASE_RANGE, "40").apply {
            p.baseKeys.forEachIndexed { index, key -> putInt(key, index * 7 - 31) }
        }.commit()
        val old = Preset.minimumVersionCode(app); assertTrue(old.toInt() < 1050)
        val basis = prefs.all.toMap()
        p.newKeys.forEach { key ->
            val edit = prefs.edit()
            when (val value = p.defaults().values().getValue(key)) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                else -> fail("Unexpected professional storage type: $key")
            }
            edit.commit(); assertEquals("1050", Preset.minimumVersionCode(app))
            prefs.edit().remove(key).commit()
        }
        assertEquals(old, Preset.minimumVersionCode(app)); assertEquals(basis, prefs.all)
    }

    @Test fun wholeProfileKeepsOriginalIntStringTypesAndAdvancedValuesWithoutFavoritesOrTools() {
        val value = settings(); p.apply(prefs, value); prefs.edit().putBoolean(p.ENABLED, true).commit()
        val store = ToneAdvancedPresetStore(app); val saved = store.save("设备配置之外的音色收藏", value)
        ToneAdvancedController.publishCapabilities(app, 1)
        assertTrue(ToneAdvancedController.saveComparison(app, this, 0))
        val profile = Preset("tone.tar", temporary.newFolder()); assertTrue(profile.save())
        val extracted = temporary.newFolder()
        val metadata = Tar.Reader(profile.file().inputStream()).extract(extracted)!!
        assertEquals("1050", metadata[Preset.META_MIN_VERSION_CODE])
        val file = File(extracted, "${ToneController.PREFS}.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        fun element(key: String) = document.documentElement.childNodes.let { nodes ->
            (0 until nodes.length).map { nodes.item(it) }.single { it.attributes?.getNamedItem("name")?.nodeValue == key }
        }
        p.baseKeys.forEachIndexed { index, key ->
            assertEquals("int", element(key).nodeName)
            assertEquals(value.base[index], element(key).attributes.getNamedItem("value").nodeValue.toInt())
        }
        assertEquals("string", element(p.BASE_RANGE).nodeName)
        assertEquals("100", element(p.BASE_RANGE).textContent)
        val xml = file.readText()
        listOf(p.ENABLED, p.delta[5].key, p.frequency[9].key, p.bassFast.key, p.mix.key).forEach { assertTrue(xml.contains(it)) }
        listOf("audition", "snapshot", "measurement_result", "capabilities", "session", "local_window").forEach { assertFalse(xml.contains(it)) }
        assertTrue(extracted.listFiles()!!.none { it.name.contains("favorite") || it.name.contains("driver_status") })
        assertEquals(listOf(saved), store.load()); assertTrue(ToneAdvancedController.hasComparison(0))
    }

    @Test fun olderProfileMissingToneClearsModuleAndTransientStateWhileKeepingItsIndependentLibrary() {
        val output = app.getSharedPreferences(Constants.PREF_OUTPUT, Context.MODE_PRIVATE)
        output.edit().putFloat("output_postgain", -1f).commit()
        val profile = Preset("old.tar", temporary.newFolder())
        Tar.Composer(profile.file()).use { tar ->
            tar.metadata[Preset.META_VERSION] = "3"; tar.metadata[Preset.META_MIN_VERSION_CODE] = "1010"
            assertTrue(tar.add(File(app.applicationInfo.dataDir, "shared_prefs/${Constants.PREF_OUTPUT}.xml")))
        }
        val value = settings(); p.apply(prefs, value); prefs.edit().putBoolean(p.ENABLED, true).commit()
        val store = ToneAdvancedPresetStore(app); val saved = store.save("保留音色收藏", value)
        ToneAdvancedController.publishCapabilities(app, 1)
        assertTrue(ToneAdvancedController.saveComparison(app, this, 0))
        Preset.load(app, profile.file().inputStream())
        assertTrue(p.keys.none(prefs.all::containsKey)); assertFalse(p.moduleEnabled(prefs.all))
        assertEquals(p.defaults(), p.capture(prefs.all)); assertFalse(ToneAdvancedController.hasComparison(0))
        assertEquals(listOf(saved), store.load())
    }

    @Test fun advancedDriverGetsAtomicPowerFoundationAndFineSettingsWithoutASecondLegacyWrite() {
        val value = settings(); p.apply(prefs, value); prefs.edit().putBoolean(p.ENABLED, true).commit()
        val dynamic = DynamicParameters.defaults().copy(base = 4, strength = 31, professional = true,
            coreOn = true, injectDelta = 317, adaptiveOn = true, threshold = -237)
        DynamicParameters.apply(other, dynamic); other.edit().putBoolean(DynamicParameters.ENABLED, true).commit()
        val beforeOther = other.all.toMap(); val beforeTone = prefs.all.toMap()
        val packets = mutableListOf<Pair<Int, IntArray>>()
        assertTrue(ToneAdvancedController.sync(app, 1, Any()) { id, values -> packets += id to values; true })
        assertEquals(listOf(p.PACKET, p.TOOLS), packets.map { it.first })
        assertArrayEquals(value.packet(true), packets[0].second)
        assertEquals(164, packets[1].second.size); assertEquals(0, packets[1].second[0])
        assertFalse(packets.any { it.first == ToneController.PARAMETER })
        assertEquals(beforeTone, prefs.all); assertEquals(beforeOther, other.all)
        packets.clear()
        assertTrue(DynamicController.sync(app, Int.MAX_VALUE, Any()) { id, values -> packets += id to values; true })
        assertArrayEquals(dynamic.packet(), packets.single { it.first == DynamicParameters.PACKET }.second)
        assertEquals(beforeTone, prefs.all); assertEquals(beforeOther, other.all)
    }

    @Test fun unsupportedExtendedCapabilitySendsNoNewCommandsAndRetainsRequestsForLater() {
        val value = settings(); p.apply(prefs, value); prefs.edit().putBoolean(p.ENABLED, true).commit()
        val before = prefs.all.toMap(); val packets = mutableListOf<Pair<Int, IntArray>>()
        assertTrue(ToneAdvancedController.sync(app, 0, Any()) { id, values -> packets += id to values; true })
        assertTrue(packets.isEmpty()); assertEquals(before, prefs.all); assertEquals(value, p.capture(prefs.all))
        assertEquals("1050", Preset.minimumVersionCode(app))
    }

    @Test fun invalidFoundationClearsToolsAndNeverSendsClampedDefaultsOrRewritesTheRequest() {
        val value = settings(); p.apply(prefs, value); prefs.edit().putBoolean(p.ENABLED, true).commit()
        val invalid = listOf<Pair<String, Any>>(p.baseKeys[0] to 101, p.baseKeys[1] to "wrong-type",
            p.BASE_RANGE to "20", p.BASE_RANGE to "40.0", p.BASE_RANGE to 100)
        invalid.forEach { (key, raw) ->
            p.apply(prefs, value)
            prefs.edit().apply { when (raw) { is Int -> putInt(key, raw); is String -> putString(key, raw) } }.commit()
            val before = prefs.all.toMap(); val packets = mutableListOf<Pair<Int, IntArray>>()
            assertTrue(ToneAdvancedController.sync(app, 1, Any()) { id, values -> packets += id to values; true })
            assertEquals(listOf(p.CLEAR), packets.map { it.first }); assertArrayEquals(intArrayOf(0), packets.single().second)
            assertEquals(before, prefs.all)
        }
        prefs.edit().putBoolean(p.ENABLED, false).commit()
        val beforeShutdown = prefs.all.toMap()
        val packets = mutableListOf<Pair<Int, IntArray>>()
        assertTrue(ToneAdvancedController.sync(app, 1, Any()) { id, values -> packets += id to values; true })
        assertEquals(listOf(p.CLEAR, p.OFF), packets.map { it.first })
        assertArrayEquals(intArrayOf(0), packets.last().second)
        assertFalse(packets.any { it.first == ToneController.PARAMETER || it.first == p.PACKET })
        assertEquals(beforeShutdown, prefs.all)
    }
}
