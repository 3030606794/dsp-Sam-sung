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
            valid.replace("\"schema\":2", "\"schema\":999"),
            valid.replace("\"type\":\"PEAK\"", "\"type\":\"UNKNOWN\""),
            valid.replace("\"enabled\":true", "\"enabled\":\"true\""),
            valid.replace("\"frequency\":1000.0", "\"frequency\":\"1000\""),
            valid.replace("\"frequency\":1000.0", "\"frequency\":1e999"),
            valid.replace("\"gain\":12.0", "\"gain\":NaN"),
            valid.replace("\"gain\":12.0", "\"gain\":100.1"),
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

    @Test fun alpha6JsonDefaultsNewDisplayFieldsWithoutChangingOldFrequencies() {
        val old = """{"schema":1,"gainRange":18.0,"gainStep":0.1,"bands":[
            {"id":"default-1","type":"PEAK","enabled":true,"frequency":31.5,"gain":7.123,"q":30.0}
        ]}"""
        preferences.edit().putString(PeqRepository.KEY_STATE, old).commit()
        val loaded = repository.loadResult()
        assertTrue(loaded.oldGeneratedDistribution)
        assertFalse(loaded.corrupt)
        assertFalse(loaded.state.showQSlider)
        assertFalse(loaded.state.showFrequencySlider)
        assertEquals(0.01, loaded.state.qStep, 0.0)
        assertEquals(1.0, loaded.state.frequencyStep, 0.0)
        assertEquals(PeqQualityDisplay.Q, loaded.state.bands.single().qualityDisplay)
        assertEquals(31.5, loaded.state.bands.single().frequency, 0.0)
        assertEquals(old, preferences.getString(PeqRepository.KEY_STATE, null))
    }

    @Test fun oldInactiveFilterGainsMigrateToZeroWithoutRewritingStoredProfileUntilSaved() {
        val original = PeqState(PeqFilterType.entries.map { type ->
            PeqBand(type.name, type = type, frequency = 1234.5, gain = 37.125, q = 3.125,
                qualityDisplay = PeqQualityDisplay.BW)
        }, gainRange = 50.0, pinResponse = false)
        val old = repository.exportJson(original).replace("\"schema\":2", "\"schema\":1")
        preferences.edit().putString(PeqRepository.KEY_STATE, old).commit()
        val result = repository.loadResult()
        assertFalse(result.corrupt)
        val expected = original.copy(bands = original.bands.map {
            if (it.type.isUnityAtZeroGain) it else it.copy(gain = 0.0)
        })
        assertEquals(expected, result.state)
        assertEquals(old, preferences.getString(PeqRepository.KEY_STATE, null))
        assertTrue(repository.save(result.state))
        assertTrue(preferences.getString(PeqRepository.KEY_STATE, null)!!.contains("\"schema\":2"))
        assertEquals(expected, repository.load())
        // New-format gains are active for every type, including after saving/loading twice.
        assertTrue(repository.save(original))
        assertEquals(original, repository.load())
        assertEquals(original, repository.importJson(repository.exportJson(repository.load())))
    }

    @Test fun newDisplayOptionsAndExtendedLimitsRoundTripWhileWrongTypesAreRejected() {
        val state = PeqState(listOf(PeqBand("new", gain = -100.0, q = 100.0,
            qualityDisplay = PeqQualityDisplay.BW)), 100.0, 1.5, true, true)
        assertTrue(repository.save(state))
        assertEquals(state, repository.load())
        val json = repository.exportJson(state)
        listOf(json.replace("\"showQSlider\":true", "\"showQSlider\":\"true\""),
            json.replace("\"qualityDisplay\":\"BW\"", "\"qualityDisplay\":\"UNKNOWN\""))
            .forEach { malformed -> assertThrows(IllegalArgumentException::class.java) { repository.importJson(malformed) } }
        assertEquals(state, repository.load())
    }

    @Test fun alpha7JsonDefaultsMissingStepsWithoutRewritingSavedBandwidthOrParameters() {
        val old = """{"schema":1,"gainRange":100.0,"gainStep":1.5,
            "showQSlider":true,"showFrequencySlider":true,"bands":[
            {"id":"custom","type":"PEAK","enabled":true,"frequency":777.125,
            "gain":-27.375,"q":23.125,"qualityDisplay":"BW"}]}"""
        preferences.edit().putString(PeqRepository.KEY_STATE, old).commit()
        val before = preferences.all.toMap()
        val result = repository.loadResult()
        assertFalse(result.corrupt)
        assertTrue(result.hasSavedState)
        assertEquals(PeqState(listOf(PeqBand("custom", frequency = 777.125, gain = -27.375,
            q = 23.125, qualityDisplay = PeqQualityDisplay.BW)), gainRange = 100.0,
            gainStep = 1.5, showQSlider = true, showFrequencySlider = true,
            qStep = 0.01, frequencyStep = 1.0), result.state)
        assertEquals(before, preferences.all)
    }

    @Test fun continuousSettingsAndStepEndpointsRoundTripWithoutQuantizingStoredValues() {
        val base = PeqState(listOf(PeqBand("precise", frequency = 777.125, gain = 17.375, q = 2.123456)))
        listOf(
            base.copy(gainRange = 1.0, gainStep = 0.01, qStep = 0.01, frequencyStep = 1.0),
            base.copy(gainRange = 100.0, gainStep = 2.0, qStep = 100.0, frequencyStep = 1000.0),
            base.copy(gainRange = 17.25, gainStep = 0.37, qStep = 0.125, frequencyStep = 12.5)
        ).forEach { state ->
            assertTrue(repository.save(state))
            assertEquals(state, repository.load())
            assertEquals(state, repository.importJson(repository.exportJson(state)))
            assertEquals(base.bands, repository.load().bands)
        }
    }

    @Test fun invalidNewStepValuesRejectImportsWithoutReplacingWorkingState() {
        val working = PeqState(listOf(PeqBand("working")), qStep = 0.25, frequencyStep = 25.0)
        repository.save(working)
        val valid = repository.exportJson(working)
        val invalid = listOf(
            valid.replace("\"qStep\":0.25", "\"qStep\":0.009"),
            valid.replace("\"qStep\":0.25", "\"qStep\":100.01"),
            valid.replace("\"qStep\":0.25", "\"qStep\":\"0.25\""),
            valid.replace("\"qStep\":0.25", "\"qStep\":null"),
            valid.replace("\"qStep\":0.25", "\"qStep\":1e999"),
            valid.replace("\"frequencyStep\":25.0", "\"frequencyStep\":0.99"),
            valid.replace("\"frequencyStep\":25.0", "\"frequencyStep\":1000.01"),
            valid.replace("\"frequencyStep\":25.0", "\"frequencyStep\":\"25\""),
            valid.replace("\"frequencyStep\":25.0", "\"frequencyStep\":false"),
            valid.replace("\"frequencyStep\":25.0", "\"frequencyStep\":1e999")
        )
        invalid.forEach { json ->
            assertNotEquals(valid, json)
            assertThrows(IllegalArgumentException::class.java) { repository.importJson(json) }
            assertEquals(working, repository.load())
        }
    }
}
