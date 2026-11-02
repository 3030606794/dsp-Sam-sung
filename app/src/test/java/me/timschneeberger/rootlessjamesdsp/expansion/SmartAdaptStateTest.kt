package me.timschneeberger.rootlessjamesdsp.expansion

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.HomeEffectCardExpansion
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SmartAdaptStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_RootlessJamesDSP)
    private val kind = ExpansionEffectsKind.SMART_ADAPT
    private val prefs get() = ExpansionEffectsController.preferences(context, kind)
    private val snapshot = SmartAdaptState.Snapshot(0, 1300, -800, 400, 150)
    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun snapshotSurvivesPreferencesReloadWithoutChangingPowerOrManualEqualizer() {
        prefs.edit().putBoolean(kind.enableKey, false).putInt("p1", 670).commit()
        val other = context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        other.edit().putString("manual_curve", "keep").commit()
        val before = other.all.toMap()
        SmartAdaptState.save(prefs, snapshot)
        assertTrue(prefs.edit().commit())
        assertEquals(snapshot, SmartAdaptState.read(context.getSharedPreferences(kind.prefsName, Context.MODE_PRIVATE).all))
        assertFalse(prefs.getBoolean(kind.enableKey, true)); assertEquals(670, prefs.getInt("p1", 0))
        assertEquals(before, other.all)
        SmartAdaptState.clear(prefs)
        assertNull(SmartAdaptState.read(prefs.all)); assertEquals(670, prefs.getInt("p1", 0)); assertEquals(before, other.all)
    }

    @Test fun targetAndIntensityRowsClearSnapshotButStrengthRowKeepsIt() {
        SmartAdaptState.save(prefs, snapshot)
        val strength = ExpansionEffectsRows.numeric(context, kind, ExpansionEffectsParameters.control(kind, "strength"), prefs)
        assertTrue(strength.requestValue(600)); assertEquals(snapshot, SmartAdaptState.read(prefs.all))
        val target = ExpansionEffectsRows.numeric(context, kind, ExpansionEffectsParameters.control(kind, "target"), prefs)
        assertTrue(target.requestValue(1000)); assertNull(SmartAdaptState.read(prefs.all))
        assertTrue(SmartAdaptState.PREFERENCE_KEYS.none(prefs::contains))
        prefs.edit().putInt("p0", 0).commit()
        SmartAdaptState.save(prefs, snapshot)
        val intensity = ExpansionEffectsRows.numeric(context, kind, ExpansionEffectsParameters.control(kind, "intensity"), prefs)
        assertTrue(intensity.requestValue(4000)); assertNull(SmartAdaptState.read(prefs.all))
        assertTrue(SmartAdaptState.PREFERENCE_KEYS.none(prefs::contains))
    }

    @Test fun resetPreservesEnabledAndOtherEffectsAndDefaultHomeIsCollapsed() {
        val other = ExpansionEffectsController.preferences(context, ExpansionEffectsKind.MS_EQ)
        other.edit().putInt("p5", 1234).commit(); val before = other.all.toMap()
        prefs.edit().putBoolean(kind.enableKey, true).putInt("p1", 850).putInt("p14", -6000).commit()
        SmartAdaptState.save(prefs, snapshot)
        ExpansionEffectsController.restoreSmartDefaults(context)
        assertTrue(prefs.getBoolean(kind.enableKey, false)); assertEquals(400, prefs.getInt("p1", -1))
        assertEquals(3000, prefs.getInt("p16", -1))
        assertEquals(0, prefs.getInt("p14", -1)); assertNull(SmartAdaptState.read(prefs.all))
        assertEquals(before, other.all)
        context.getSharedPreferences(HomeEffectCardExpansion.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(HomeEffectCardExpansion.read(context, kind.enableKey))
    }

    @Test fun upgradeUnfrozenPreferencesAddsOnlyStrongLevelAndIsIdempotent() {
        val stored = context.getSharedPreferences(kind.prefsName, Context.MODE_PRIVATE)
        stored.edit().clear().putBoolean(kind.enableKey, true).putInt("p0", 2000).putInt("p1", 610)
            .putInt("p2", 1900).putInt("p3", 2300).putInt("p8", 1700000).putInt("p14", -4000).commit()
        val before = stored.all.toMap()
        SmartAdaptState.migrate(stored)
        assertEquals(before + ("p16" to 3000), stored.all)
        SmartAdaptState.migrate(stored)
        assertEquals(before + ("p16" to 3000), stored.all)
        stored.edit().putInt("p16", 1000).commit()
        SmartAdaptState.migrate(stored)
        assertEquals(before + ("p16" to 1000), stored.all)
    }

    private fun legacyValues(): Map<String, Any> = mapOf(kind.enableKey to true, "p0" to 0, "p1" to 670,
        SmartAdaptState.SCHEMA_KEY to 1, SmartAdaptState.TARGET_KEY to 0,
        SmartAdaptState.LOW_KEY to 1300, SmartAdaptState.MID_KEY to -800,
        SmartAdaptState.HIGH_KEY to 400, SmartAdaptState.WIDTH_KEY to 150)

    @Test fun upgradeOldFrozenPreferencesKeepsExactVectorAndBindsGentleLevel() {
        val stored = context.getSharedPreferences(kind.prefsName, Context.MODE_PRIVATE)
        val edit = stored.edit().clear()
        legacyValues().forEach { (key, value) -> when (value) { is Int -> edit.putInt(key, value); is Boolean -> edit.putBoolean(key, value) } }
        edit.commit()
        SmartAdaptState.migrate(stored)
        val expected = legacyValues() + mapOf("p16" to 0, SmartAdaptState.SCHEMA_KEY to 2, SmartAdaptState.INTENSITY_KEY to 0)
        assertEquals(expected, stored.all)
        assertEquals(snapshot.copy(intensity = 0), SmartAdaptState.read(stored.all))
        val words = ExpansionEffectsParameters.packet(kind, stored.all)
        assertEquals(0, words[18]); assertArrayEquals(intArrayOf(1, 1300, -800, 400, 150, 2, 0, 0), words.sliceArray(110..117))
        SmartAdaptState.migrate(stored)
        assertEquals(expected, stored.all)
    }

    private fun profile(values: Map<String, Any>): String = "<map>" + values.entries.joinToString("") { (key, value) ->
        "<${if (value is Boolean) "boolean" else "int"} name=\"$key\" value=\"$value\"/>"
    } + "</map>"

    @Test fun importOldFrozenAndUnfrozenProfilesMigratesOnlyNewLevelMetadata() {
        val old = temporary.newFile("old-frozen.xml").apply { writeText(profile(legacyValues())) }
        val imported = ExpansionEffectsRepository.read(kind, old)
        assertEquals(legacyValues() + mapOf("p16" to 0, SmartAdaptState.SCHEMA_KEY to 2, SmartAdaptState.INTENSITY_KEY to 0), imported)
        assertEquals(snapshot.copy(intensity = 0), SmartAdaptState.read(imported))
        val unfrozen = mapOf<String, Any>(kind.enableKey to false, "p0" to 3000, "p1" to 250, "p14" to -3000)
        val oldUnfrozen = temporary.newFile("old-unfrozen.xml").apply { writeText(profile(unfrozen)) }
        assertEquals(unfrozen + ("p16" to 3000), ExpansionEffectsRepository.read(kind, oldUnfrozen))
        val malformed = temporary.newFile("old-incomplete.xml").apply { writeText(profile(legacyValues() - SmartAdaptState.LOW_KEY)) }
        assertThrows(Exception::class.java) { ExpansionEffectsRepository.read(kind, malformed) }
    }

    @Test fun newProfileRejectsFrozenLevelMismatchAndOutOfRangeGains() {
        val valid = legacyValues() + mapOf("p16" to 4000, SmartAdaptState.SCHEMA_KEY to 2,
            SmartAdaptState.INTENSITY_KEY to 4000, SmartAdaptState.LOW_KEY to 12000)
        val good = temporary.newFile("new-high.xml").apply { writeText(profile(valid)) }
        assertEquals(valid, ExpansionEffectsRepository.read(kind, good))
        listOf(valid + (SmartAdaptState.INTENSITY_KEY to 3000), valid + (SmartAdaptState.LOW_KEY to 12001),
            valid - SmartAdaptState.INTENSITY_KEY).forEachIndexed { i, values ->
            val bad = temporary.newFile("bad-level-$i.xml").apply { writeText(profile(values)) }
            assertThrows(Exception::class.java) { ExpansionEffectsRepository.read(kind, bad) }
        }
    }

    @Test fun profileReaderAcceptsOnlyCompleteMatchingSnapshotAndNeverPersistentRoute() {
        SmartAdaptState.save(prefs, snapshot)
        val xml = "<map>" + prefs.all.entries.joinToString("") { "<int name=\"${it.key}\" value=\"${it.value}\"/>" } + "</map>"
        val valid = temporary.newFile("valid.xml").apply { writeText(xml) }
        assertEquals(snapshot, SmartAdaptState.read(ExpansionEffectsRepository.read(kind, valid)))
        listOf(xml.replace("value=\"150\"", "value=\"601\""),
            xml.replace("<int name=\"${SmartAdaptState.MID_KEY}\" value=\"-800\"/>", ""),
            xml.replace("</map>", "<int name=\"p0\" value=\"1000\"/></map>"),
            "<map><int name=\"route\" value=\"2000\"/></map>").forEachIndexed { index, bad ->
            val file = temporary.newFile("bad$index.xml").apply { writeText(bad) }
            assertThrows(Exception::class.java) { ExpansionEffectsRepository.read(kind, file) }
        }
    }
}
