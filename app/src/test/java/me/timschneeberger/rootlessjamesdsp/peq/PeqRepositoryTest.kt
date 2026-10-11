package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PeqRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(context)

    @Before fun clearPreferences() { preferences.edit().clear().commit() }

    @Test fun all170BandsAndUiMetadataRoundTripWithoutLegacyChanges() {
        val legacy = "GraphicEQ: 20 3; 1000 -2; 20000 0;"
        preferences.edit().putString("geq_nodes", legacy).putBoolean("geq_enable", false).commit()
        val state = PeqState(List(170) { index -> PeqBand("band-$index",
            PeqFilterType.entries[index % 7], index % 2 == 0,
            1000.0 + index * 0.001, 23.127, 0.12345 + index * 0.01) }, 6.0, 0.25)
        assertTrue(repository.save(state))
        assertEquals(state, repository.load())
        assertEquals(state, repository.importJson(repository.exportJson(state)))
        assertEquals(legacy, preferences.getString("geq_nodes", null))
        assertFalse(preferences.getBoolean("geq_enable", true))
        assertTrue(repository.hasSavedState())
        assertFalse(repository.loadResult().corrupt)
    }

    @Test fun missingStateUsesNeutralDefaultsWithoutPersistingOrMigratingLegacyNodes() {
        preferences.edit().putString("geq_nodes", "GraphicEQ: 100 12;").commit()
        val before = preferences.all.toMap()
        val result = repository.loadResult()
        assertEquals(10, result.state.bands.size)
        assertFalse(result.hasSavedState)
        assertFalse(result.corrupt)
        assertEquals(before, preferences.all)
    }

    @Test fun corruptStoredJsonIsReportedAndOriginalIsPreserved() {
        for (json in listOf("not-json", "{}", "null", "{\"schema\":999,\"bands\":[]}")) {
            preferences.edit().putString(PeqRepository.KEY_STATE, json).commit()
            val result = repository.loadResult()
            assertTrue(result.hasSavedState)
            assertTrue(result.corrupt)
            assertNotNull(result.error)
            assertTrue(result.state.bands.all { it.gain == 0.0 })
            assertEquals(json, preferences.getString(PeqRepository.KEY_STATE, null))
        }
        preferences.edit().putInt(PeqRepository.KEY_STATE, 7).commit()
        assertTrue(repository.loadResult().corrupt)
        assertEquals(7, preferences.getInt(PeqRepository.KEY_STATE, 0))
    }

    @Test fun malformedImportsCannotOverwriteWorkingState() {
        val working = PeqState(listOf(PeqBand("test", gain = 12.0)))
        repository.save(working)
        val valid = repository.exportJson(working)
        val bad = listOf(
            valid.replace("\"schema\":1", "\"schema\":2"),
            valid.replace("\"type\":\"PEAK\"", "\"type\":\"UNKNOWN\""),
            valid.replace("\"enabled\":true", "\"enabled\":\"true\""),
            valid.replace("\"frequency\":1000.0", "\"frequency\":\"1000\""),
            valid.replace("\"frequency\":1000.0", "\"frequency\":1e999"),
            valid.replace("\"gain\":12.0", "\"gain\":NaN"),
            valid.replace("\"gain\":12.0", "\"gain\":24.1"),
            valid.replace("\"q\":0.7071067811865476", "\"q\":0"),
            " ".repeat(PeqRepository.MAX_JSON_LENGTH + 1)
        )
        bad.forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { repository.importJson(json) }
            assertEquals(working, repository.load())
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.save(working.copy(bands = listOf(working.bands[0], working.bands[0])))
        }
        assertEquals(working, repository.load())
    }
}
