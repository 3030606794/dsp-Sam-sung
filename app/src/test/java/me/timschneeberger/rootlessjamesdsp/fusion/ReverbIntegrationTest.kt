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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowPosixAtomicFile::class])
class ReverbIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val p = ReverbParameters
    private val c = ReverbController
    private val prefs get() = c.preferences(app)
    private val identity = Any()
    private val owner = Any()
    @Before fun prepare() {
        stopKoin(); startKoin { modules(module { single<Context> { app } }) }
        prefs.edit().clear().commit(); c.stopAudition(app, clearSnapshots = true)
        c.publishRuntime(app, emptyList()); c.publishCapabilities(app, 4194303)
    }
    @After fun cleanup() {
        c.sessionClosed(app, identity); c.stopAudition(app, clearSnapshots = true); stopKoin()
    }
    private fun active() {
        val data = IntArray(32).apply {
            this[0] = 1; this[1] = 2; this[2] = 48000; this[3] = 1; this[4] = 1
            this[11] = c.epoch; this[26] = 2000; this[30] = 1
        }
        val reading = ReverbController.Reading(identity, true, 0, 48000, p.decode(data))
        c.publishRuntime(app, listOf(reading)); c.publishRuntime(app, listOf(reading.copy(frames = 512)))
    }

    @Test fun versionRequirementsDistinguishOriginalAndAdvancedKeysEvenWhenPowerIsOff() {
        prefs.edit().putBoolean(p.ENABLED, false).putInt("v_reverb_size", 31).commit()
        val baseline = Preset.minimumVersionCode(app)
        assertTrue(baseline.toInt() < 1042)
        p.newKeys.forEach { key ->
            prefs.edit().remove(key).commit()
            val default = ReverbParameters.Settings().values().getValue(key)
            val edit = prefs.edit()
            if (default is Boolean) edit.putBoolean(key, default) else edit.putInt(key, default as Int)
            edit.commit()
            assertEquals(if(key in ReverbAdvancedParameters.keys)"1043" else "1042", Preset.minimumVersionCode(app))
            assertFalse(prefs.getBoolean(p.ENABLED, true))
            prefs.edit().remove(key).commit()
        }
        assertEquals(baseline, Preset.minimumVersionCode(app))
    }

    @Test fun exportedProfileKeepsFineValuesButExcludesFavoritesCapabilitiesAndAuditionState() {
        val settings = ReverbParameters.Settings(legacy = listOf(31, 64, 42, 17, 50), professional = true,
            size = 317, width = 643, damp = 427, wet = 173, dry = 500,
            predelayOn = true, predelay = 217, lowCutOn = true, lowCut = 133,
            highCutOn = true, highCut = 9831, meters = true)
        p.apply(prefs, settings); prefs.edit().putBoolean(p.ENABLED, true).commit()
        val favorites = ReverbPresetStore(app); val saved = favorites.save("独立收藏", settings)
        active(); assertTrue(c.saveComparison(app, owner, 0)); assertTrue(c.saveComparison(app, owner, 1))
        assertTrue(c.startAudition(app, owner, 3))
        val archive = Preset("professional.tar", temporary.newFolder()); assertTrue(archive.save())
        val extracted = temporary.newFolder()
        val metadata = Tar.Reader(archive.file().inputStream()).extract(extracted)!!
        assertEquals("1043", metadata[Preset.META_MIN_VERSION_CODE])
        val xml = File(extracted, "${p.NAMESPACE}.xml").readText()
        listOf("v_reverb_pro_size_tenths", "317", "v_reverb_pro_predelay_tenths_ms", "217")
            .forEach { assertTrue(xml.contains(it)) }
        listOf("audition", "snapshot", "measurement", "capabilities", "session", "peak", "match")
            .forEach { assertFalse(xml.contains(it)) }
        assertTrue(extracted.listFiles()!!.none { it.name.contains("favorite") || it.name.contains("reverb_driver_status") })
        assertEquals(listOf(saved), favorites.load()); assertEquals(3, c.audition)
    }

    @Test fun loadingOldProfileClearsTemporaryComparisonAndRestoresLegacyWithoutTouchingFavorites() {
        val oldValues = listOf(11, 22, 33, 44, 55)
        p.legacyControls.zip(oldValues).forEach { (control, value) -> prefs.edit().putInt(control.key, value).commit() }
        prefs.edit().putBoolean(p.ENABLED, true).commit()
        val room = app.getSharedPreferences(Constants.PREF_REVERB, Context.MODE_PRIVATE)
        room.edit().clear().putBoolean("reverb_enable", true).putString("reverb_preset", "7").commit()
        val old = Preset("old.tar", temporary.newFolder()); assertTrue(old.save())
        p.apply(prefs, ReverbParameters.Settings(professional = true, size = 731, predelayOn = true, predelay = 175))
        val favorites = ReverbPresetStore(app); val saved = favorites.save("跨配置收藏", p.capture(prefs.all))
        active(); assertTrue(c.saveComparison(app, owner, 0)); assertTrue(c.saveComparison(app, owner, 1))
        assertTrue(c.startAudition(app, owner, 1))
        Preset.load(app, old.file().inputStream()); p.normalize(prefs)
        assertEquals(0, c.audition); assertFalse(c.hasComparison(0)); assertFalse(c.hasComparison(1))
        assertEquals(oldValues, p.legacy(prefs.all)); assertFalse(p.enabled(prefs.all))
        assertTrue(p.moduleEnabled(prefs.all)); assertTrue(p.newKeys.none(prefs.all::containsKey))
        assertEquals(listOf(saved), favorites.load())
        assertTrue(room.getBoolean("reverb_enable", false)); assertEquals("7", room.getString("reverb_preset", null))
    }
}
