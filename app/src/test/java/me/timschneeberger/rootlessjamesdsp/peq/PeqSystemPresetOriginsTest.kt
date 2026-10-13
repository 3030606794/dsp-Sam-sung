package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PeqSystemPresetOriginsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(PeqSystemPresetOrigins.PREFERENCES, Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun reopeningAfterEditsRetainsExactImportedValuesAndQualityModeForEachIdentity() {
        val band = PeqBand(id = "imported", frequency = 2178.8125, gain = -3.75,
            q = 6.695873046875, qualityDisplay = PeqQualityDisplay.BW)
        val other = PeqBand(id = "shelf", frequency = 250.0, gain = 2.5,
            q = 0.7071067690849304, qualityDisplay = PeqQualityDisplay.Q)
        assertTrue(PeqSystemPresetOrigins(app).recordImportedBands(listOf(band, other)))

        val edited = band.copy(frequency = 5000.0, gain = 8.0, q = 4.0, qualityDisplay = PeqQualityDisplay.Q)
        assertTrue(PeqRepository(app).save(PeqState(listOf(edited, other))))
        assertTrue(PeqSystemPresetOrigins(app).retain(setOf(band.id, other.id)))

        val reopened = PeqSystemPresetOrigins(app)
        assertEquals(PeqSystemPresetOrigin(band.frequency, band.gain, band.q, PeqQualityDisplay.BW), reopened.find(band.id))
        assertEquals(PeqSystemPresetOrigin(other.frequency, other.gain, other.q, PeqQualityDisplay.Q), reopened.find(other.id))
        assertEquals(edited, PeqRepository(app).load().bands.first())
    }

    @Test fun importedLowFrequencyOriginUsesTheAdapted20HzValueAndItsRecomputedQuality() {
        val preset = PeqSystemPresets.load(app).single { it.name == "Flat 16k (130-Band Graphic)" }
        val applied = preset.applyTo(PeqModel.defaultState())
        val index = preset.bands.indexOfFirst { it.frequency < PeqModel.MIN_FREQUENCY }
        assertTrue(index >= 0)
        assertTrue(PeqSystemPresetOrigins(app).recordImportedBands(applied.bands))

        val source = preset.bands[index]
        val imported = applied.bands[index]
        val origin = PeqSystemPresetOrigins(app).find(imported.id)!!
        assertEquals(20.0, origin.frequency, 0.0)
        assertEquals(imported.q, origin.q, 0.0)
        assertNotEquals(source.q, origin.q, 1e-9)
        assertEquals(preset.sourceBandwidths[index]!!,
            PeqResponse.bandwidthOctaves(origin.q, origin.frequency, 48000.0), 1e-12)
        assertEquals(PeqQualityDisplay.BW, origin.qualityDisplay)
    }

    @Test fun ordinaryNewAndCopiedCardsNeverInheritAnOriginEvenWhenAllValuesMatch() {
        val imported = PeqBand(id = "system", frequency = 1000.0, gain = 5.0, q = 3.0)
        val copy = imported.copy(id = UUID.randomUUID().toString(), enabled = false)
        val added = PeqModel.blankBand()
        val origins = PeqSystemPresetOrigins(app)
        assertTrue(origins.recordImportedBands(listOf(imported)))
        val locks = PeqBandLockStore(app)
        locks.markNew(copy.id)
        locks.markNew(added.id)
        assertTrue(origins.retain(setOf(imported.id, copy.id, added.id)))
        val reloaded = PeqSystemPresetOrigins(app)
        assertNotNull(reloaded.find(imported.id))
        assertNull(reloaded.find(copy.id))
        assertNull(reloaded.find(added.id))
        for (band in listOf(copy, added)) {
            assertFalse(locks.isBandLocked(band.id))
            PeqEditField.entries.forEach { assertFalse(locks.isFieldLocked(band.id, it)) }
        }
    }

    @Test fun removingCardsAndImportingAnotherBankCannotRestoreStaleOrigins() {
        val first = PeqBand(id = "first", frequency = 100.0)
        val survivor = PeqBand(id = "survivor", frequency = 5000.0)
        val replacement = first.copy(id = "replacement", gain = 4.0)
        val origins = PeqSystemPresetOrigins(app)
        assertTrue(origins.recordImportedBands(listOf(first, survivor)))
        assertTrue(origins.retain(setOf(survivor.id)))
        assertNull(PeqSystemPresetOrigins(app).find(first.id))
        assertNotNull(PeqSystemPresetOrigins(app).find(survivor.id))
        assertTrue(origins.recordImportedBands(listOf(replacement)))
        val reloaded = PeqSystemPresetOrigins(app)
        assertNull(reloaded.find(first.id))
        assertNull(reloaded.find(survivor.id))
        assertEquals(4.0, reloaded.find(replacement.id)!!.gain, 0.0)
        assertTrue(reloaded.retain(emptySet()))
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun invalidIncomingBankIsRejectedBeforeReplacingAnyExistingResetTargets() {
        val original = PeqBand(id = "old", frequency = 700.0, gain = -5.0)
        val origins = PeqSystemPresetOrigins(app)
        assertTrue(origins.recordImportedBands(listOf(original)))
        val before = prefs.all.toMap()
        listOf(
            listOf(original.copy(frequency = 0.0)),
            listOf(original.copy(frequency = 16.0)),
            listOf(original.copy(q = Double.NaN)),
            listOf(original.copy(gain = 101.0)),
            listOf(original, original.copy(gain = 2.0))
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { origins.recordImportedBands(invalid) }
            assertEquals(before, prefs.all)
        }
    }

    @Test fun corruptOrUnsupportedOriginDoesNotCrashOrHideOtherValidCards() {
        val valid = PeqBand(id = "valid", frequency = 1000.0)
        val origins = PeqSystemPresetOrigins(app)
        assertTrue(origins.recordImportedBands(listOf(valid)))
        val validJson = prefs.getString(valid.id, null)!!
        prefs.edit().putString("broken", "{").putString("future", validJson.replace("\"version\":1", "\"version\":999"))
            .putString("invalid-frequency", validJson.replace("1000.0", "0.0"))
            .putBoolean("wrong-type", true).commit()
        assertNull(origins.find("broken"))
        assertNull(origins.find("future"))
        assertNull(origins.find("invalid-frequency"))
        assertNull(origins.find("wrong-type"))
        assertNotNull(origins.find(valid.id))
    }
}
