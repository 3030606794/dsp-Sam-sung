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
}
