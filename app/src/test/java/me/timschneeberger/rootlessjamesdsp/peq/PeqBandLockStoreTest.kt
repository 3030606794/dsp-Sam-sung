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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PeqBandLockStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun oldPreferencesKeepTheirLocksAndNewFilterLockDefaultsOffWithoutMigration() {
        prefs.edit().putBoolean("legacy.band", false).putBoolean("legacy.GAIN", true)
            .putBoolean("legacy.QUALITY", true).putBoolean("legacy.FREQUENCY", false).commit()
        val before = prefs.all.toMap()
        val locks = PeqBandLockStore(app)
        assertFalse(locks.isBandLocked("legacy"))
        assertTrue(locks.isFieldLocked("legacy", PeqEditField.GAIN))
        assertTrue(locks.isFieldLocked("legacy", PeqEditField.QUALITY))
        assertFalse(locks.isFieldLocked("legacy", PeqEditField.FREQUENCY))
        assertFalse(locks.isFieldLocked("legacy", PeqEditField.FILTER_TYPE))
        assertTrue(locks.canEdit("legacy", PeqEditField.FILTER_TYPE))
        assertEquals(before, prefs.all)
    }

    @Test fun filterLockPersistsIndependentlyAndMasterLockAlwaysWins() {
        val locks = PeqBandLockStore(app)
        locks.setBandLocked("band", false)
        locks.setFieldLocked("band", PeqEditField.FILTER_TYPE, true)
        var reloaded = PeqBandLockStore(app)
        assertTrue(reloaded.isFieldLocked("band", PeqEditField.FILTER_TYPE))
        assertFalse(reloaded.canEdit("band", PeqEditField.FILTER_TYPE))
        listOf(PeqEditField.GAIN, PeqEditField.QUALITY, PeqEditField.FREQUENCY).forEach {
            assertTrue(reloaded.canEdit("band", it))
        }
        locks.setBandLocked("band", true)
        locks.setFieldLocked("band", PeqEditField.FILTER_TYPE, false)
        reloaded = PeqBandLockStore(app)
        assertFalse(reloaded.isFieldLocked("band", PeqEditField.FILTER_TYPE))
        PeqEditField.entries.forEach { assertFalse(reloaded.canEdit("band", it)) }
        locks.setBandLocked("band", false)
        assertTrue(PeqBandLockStore(app).canEdit("band", PeqEditField.FILTER_TYPE))
    }

    @Test fun aNewOrCopiedCardClearsAllFourIndependentLocksOnlyForItsOwnId() {
        val locks = PeqBandLockStore(app)
        listOf("new", "source").forEach { id ->
            locks.setBandLocked(id, true)
            PeqEditField.entries.forEach { locks.setFieldLocked(id, it, true) }
        }
        locks.markNew("new")
        val reloaded = PeqBandLockStore(app)
        assertTrue(reloaded.isNew("new"))
        assertFalse(reloaded.isBandLocked("new"))
        assertTrue(reloaded.isBandLocked("source"))
        PeqEditField.entries.forEach {
            assertFalse(reloaded.isFieldLocked("new", it))
            assertTrue(reloaded.canEdit("new", it))
            assertTrue(reloaded.isFieldLocked("source", it))
        }
    }

    @Test fun unlockingAllFieldsPreservesMasterProtectionNewBadgeAndOtherCardsAfterReload() {
        val locks = PeqBandLockStore(app)
        locks.markNew("band")
        locks.setBandLocked("band", true)
        locks.setBandLocked("band-other", false)
        PeqEditField.entries.forEach {
            locks.setFieldLocked("band", it, true)
            locks.setFieldLocked("band-other", it, true)
        }
        val otherBefore = prefs.all.filterKeys { it.startsWith("band-other.") }

        locks.unlockAllFields("band")

        val reloaded = PeqBandLockStore(app)
        assertTrue(reloaded.isBandLocked("band"))
        assertTrue(reloaded.isNew("band"))
        PeqEditField.entries.forEach {
            assertFalse(reloaded.isFieldLocked("band", it))
            assertFalse(reloaded.canEdit("band", it))
            assertTrue(reloaded.isFieldLocked("band-other", it))
        }
        assertEquals(otherBefore, prefs.all.filterKeys { it.startsWith("band-other.") })
    }

    @Test fun unlockingAllFieldsOfAnUnlockedUsedCardEnablesEveryFieldWithoutChangingOtherPreferences() {
        val locks = PeqBandLockStore(app)
        locks.markNew("band")
        locks.markUsed("band")
        PeqEditField.entries.forEach { locks.setFieldLocked("band", it, true) }
        prefs.edit().putString("unrelated", "retained").commit()
        val before = prefs.all.toMap()

        locks.unlockAllFields("band")

        val expected = before.toMutableMap().apply {
            PeqEditField.entries.forEach { put("band.${it.name}", false) }
        }
        assertEquals(expected, prefs.all)
        val reloaded = PeqBandLockStore(app)
        assertFalse(reloaded.isNew("band"))
        assertFalse(reloaded.isBandLocked("band"))
        PeqEditField.entries.forEach { assertTrue(reloaded.canEdit("band", it)) }
        locks.unlockAllFields("band")
        assertEquals(expected, prefs.all)
    }

    @Test fun unlockingMasterAndFieldsPublishesOneCompleteUnlockedStateToObservers() {
        val locks = PeqBandLockStore(app)
        locks.markNew("target")
        locks.setBandLocked("target", true)
        PeqEditField.entries.forEach { locks.setFieldLocked("target", it, true) }
        locks.setBandLocked("other", true)
        val observed = mutableListOf<Boolean>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            observed += !locks.isBandLocked("target") && PeqEditField.entries.none { locks.isFieldLocked("target", it) }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            locks.unlockAll("target", includeBand = true)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(observed.isNotEmpty())
            assertTrue(observed.all { it })
            assertFalse(PeqBandLockStore(app).isBandLocked("target"))
            assertTrue(locks.isNew("target"))
            assertTrue(locks.isBandLocked("other"))
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Test fun systemImportLocksOnlyQualityAndFrequencyAndPreservesNewBadgesAndUnrelatedCards() {
        val locks = PeqBandLockStore(app)
        val ids = listOf("system-q", "system-bw")
        ids.forEach(locks::markNew)
        locks.markNew("personal")
        locks.setBandLocked("personal", true)
        locks.setFieldLocked("personal", PeqEditField.GAIN, true)
        val otherBefore = prefs.all.filterKeys { it.startsWith("personal.") }
        val observedComplete = mutableListOf<Boolean>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            observedComplete += ids.all { id ->
                !locks.isBandLocked(id) && locks.isNew(id) &&
                    locks.isFieldLocked(id, PeqEditField.QUALITY) && locks.isFieldLocked(id, PeqEditField.FREQUENCY) &&
                    !locks.isFieldLocked(id, PeqEditField.GAIN) && !locks.isFieldLocked(id, PeqEditField.FILTER_TYPE)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            locks.applySystemPresetDefaults(ids)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }

        assertTrue(observedComplete.isNotEmpty())
        assertTrue(observedComplete.all { it })
        val reloaded = PeqBandLockStore(app)
        ids.forEach { id ->
            assertFalse(reloaded.isBandLocked(id))
            assertTrue(reloaded.isNew(id))
            assertFalse(reloaded.canEdit(id, PeqEditField.QUALITY))
            assertFalse(reloaded.canEdit(id, PeqEditField.FREQUENCY))
            assertTrue(reloaded.canEdit(id, PeqEditField.GAIN))
            assertTrue(reloaded.canEdit(id, PeqEditField.FILTER_TYPE))
        }
        assertEquals(otherBefore, prefs.all.filterKeys { it.startsWith("personal.") })
    }
}
