package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
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
@Config(sdk = [35], application = Application::class)
class PeqPresetIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val nodesKey get() = app.getString(R.string.key_geq_nodes)
    private val enabledKey get() = app.getString(R.string.key_geq_enable)
    private val legacyCurve = "GraphicEQ: 100 12; 1000 -3; 12000 4;"

    @Before fun prepare() {
        stopKoin()
        startKoin { modules(module { single<Context> { app } }) }
        prefs.edit().clear().commit()
    }

    @After fun cleanup() { stopKoin() }

    private fun export(): Pair<Preset, Map<String, String>> {
        val preset = Preset("profile.tar", temporary.newFolder())
        assertTrue(preset.save())
        val folder = temporary.newFolder()
        val metadata = requireNotNull(Tar.Reader(preset.file().inputStream()).extract(folder))
        val xml = File(folder, "${Constants.PREF_GEQ}.xml")
        assertTrue("Preset must contain the actual graphic EQ settings", xml.isFile)
        return preset to metadata
    }

    @Test fun selectedNeutralPeqWithoutJsonRequiresNewAppAndPreservesLegacyCurve() {
        prefs.edit().putString(nodesKey, legacyCurve).putBoolean(enabledKey, true).commit()
        assertFalse(PeqController.isParametric(app))
        PeqController.useParametric(app)
        assertFalse(PeqRepository(app).hasSavedState())
        val (preset, metadata) = export()
        assertEquals("1006", metadata[Preset.META_MIN_VERSION_CODE])
        val folder = temporary.newFolder()
        Tar.Reader(preset.file().inputStream()).extract(folder)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(folder, "${Constants.PREF_GEQ}.xml"))
        val strings = document.getElementsByTagName("string")
        val exported = (0 until strings.length).associate { index ->
            strings.item(index).let { it.attributes.getNamedItem("name").nodeValue to it.textContent }
        }
        assertEquals(PeqController.PARAMETRIC, exported[PeqController.MODE_KEY])
        assertEquals(legacyCurve, exported[nodesKey])
        assertFalse(exported.containsKey(PeqRepository.KEY_STATE))
    }

    @Test fun unchangedLegacyOnlyPresetKeepsItsOlderAppCompatibility() {
        prefs.edit().putString(nodesKey, legacyCurve).putBoolean(enabledKey, true)
            .putString(PeqController.MODE_KEY, PeqController.LEGACY).commit()
        assertEquals(Preset.MIN_VERSION_CODE, export().second[Preset.META_MIN_VERSION_CODE])
    }

    @Test fun savedPeqIsProtectedEvenWhenLegacyModeIsCurrentlySelected() {
        prefs.edit().putString(PeqController.MODE_KEY, PeqController.LEGACY).commit()
        PeqRepository(app).save(PeqModel.defaultState())
        assertEquals("1006", export().second[Preset.META_MIN_VERSION_CODE])
    }

    @Test fun profileArchiveRestoresAll170BandsModeDisplaySettingsAndUnchangedLegacyNodes() {
        val original = PeqModel.defaultState(170).let { state -> state.copy(
            bands = state.bands.mapIndexed { index, band -> band.copy(
                type = PeqFilterType.entries[index % 7], enabled = index % 2 == 0,
                gain = 22.123, q = 0.345 + index * 0.01) },
            gainRange = 6.0, gainStep = 0.25) }
        PeqRepository(app).save(original)
        prefs.edit().putString(PeqController.MODE_KEY, PeqController.PARAMETRIC)
            .putString(nodesKey, legacyCurve).putBoolean(enabledKey, true).commit()
        val (preset, _) = export()

        PeqRepository(app).save(PeqModel.defaultState(1))
        prefs.edit().putString(PeqController.MODE_KEY, PeqController.LEGACY)
            .putString(nodesKey, "GraphicEQ: 500 -12;").putBoolean(enabledKey, false).commit()
        Preset.load(app, preset.file().inputStream())

        assertEquals(original, PeqRepository(app).load())
        assertTrue(PeqController.isParametric(app))
        assertEquals(legacyCurve, prefs.getString(nodesKey, null))
        assertTrue(prefs.getBoolean(enabledKey, false))
    }
}
