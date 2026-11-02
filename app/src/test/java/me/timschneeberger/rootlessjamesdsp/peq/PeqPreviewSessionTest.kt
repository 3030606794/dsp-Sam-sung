package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PeqPreviewSessionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private val owners = mutableListOf<PeqPreviewSession.Token>()

    @Before fun clear() { prefs.edit().clear().commit() }
    @After fun finish() { owners.asReversed().forEach(PeqPreviewSession::end) }

    private fun begin(state: PeqState) = PeqPreviewSession.begin(state).also(owners::add)
    private fun packet(): IntArray {
        var result = intArrayOf()
        assertTrue(PeqController.sync(app) { parameter, values ->
            assertEquals(PeqController.PARAMETER, parameter)
            result = values
            true
        })
        return result
    }

    @Test fun auditionChangesDriverPacketButNeverSavedParametersOrEnableFlags() {
        val original = PeqState(listOf(PeqBand("active", gain = 4.0),
            PeqBand("bypassed", enabled = false, gain = 8.0)))
        assertTrue(repository.save(original))
        val prefsBefore = prefs.all.toMap()
        val draft = original.copy(bands = original.bands.map { it.copy(type = PeqFilterType.HIGH_PASS) })
        val owner = begin(draft)
        assertArrayEquals(PeqProtocol.payload(draft, false), packet())
        assertEquals(prefsBefore, prefs.all)
        assertEquals(original, repository.load())

        prefs.edit().putBoolean(app.getString(R.string.key_geq_enable), true).commit()
        assertArrayEquals(PeqProtocol.payload(draft, true), packet())
        assertFalse(PeqPreviewSession.current()!!.bands[1].enabled)
        assertTrue(PeqPreviewSession.end(owner))
        assertArrayEquals(PeqProtocol.payload(original, true), packet())
        assertEquals(original, repository.load())
    }

    @Test fun endingAuditionUsesLatestSavedBankInsteadOfRestoringAnOldSnapshot() {
        val original = PeqModel.defaultState(1)
        repository.save(original)
        val draft = original.copy(bands = original.bands.map { it.copy(type = PeqFilterType.NOTCH) })
        val owner = begin(draft)
        val confirmed = draft.copy(bands = draft.bands.map { it.copy(gain = -2.0) })
        repository.save(confirmed)
        assertTrue(PeqPreviewSession.end(owner))
        assertArrayEquals(PeqProtocol.payload(confirmed, false), packet())
        assertFalse(PeqPreviewSession.end(owner))
        assertNull(PeqPreviewSession.current())
    }

    @Test fun oldDialogCannotUpdateOrClearNewDialogsAudition() {
        val first = PeqModel.defaultState(1)
        val oldOwner = begin(first)
        val next = PeqModel.defaultState(2)
        val owner = begin(next)
        assertFalse(PeqPreviewSession.update(oldOwner, first))
        assertFalse(PeqPreviewSession.end(oldOwner))
        assertEquals(next, PeqPreviewSession.current())
        val changed = next.copy(bands = next.bands.map { it.copy(type = PeqFilterType.BAND_PASS) })
        assertTrue(PeqPreviewSession.update(owner, changed))
        assertEquals(changed, PeqPreviewSession.current())
    }

    @Test fun snapshotsDoNotShareMutableCallerListsAndInvalidUpdatesPreserveActiveDraft() {
        val bands = mutableListOf(PeqBand("first"))
        val original = PeqState(bands)
        val owner = begin(original)
        bands.clear()
        assertEquals(1, PeqPreviewSession.current()!!.bands.size)
        val active = PeqPreviewSession.current()
        assertThrows(IllegalArgumentException::class.java) {
            PeqPreviewSession.update(owner, original.copy(gainStep = Double.NaN))
        }
        assertEquals(active, PeqPreviewSession.current())
        assertThrows(IllegalArgumentException::class.java) {
            begin(original.copy(gainRange = -1.0))
        }
        assertEquals(active, PeqPreviewSession.current())
    }

    @Test fun audioThreadSeesCompleteValidatedSnapshotsWhileUiChangesAndEndsAudition() {
        val initial = PeqModel.defaultState(4)
        val owner = begin(initial)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = executor.submit<Boolean> {
                repeat(1000) {
                    PeqPreviewSession.current()?.let { state ->
                        PeqModel.validate(state)
                        assertEquals(4, state.bands.size)
                        assertEquals(1, state.bands.map { it.type }.distinct().size)
                    }
                }
                true
            }
            repeat(100) { index ->
                val type = PeqFilterType.entries[index % PeqFilterType.entries.size]
                assertTrue(PeqPreviewSession.update(owner,
                    initial.copy(bands = initial.bands.map { it.copy(type = type) })))
            }
            assertTrue(PeqPreviewSession.end(owner))
            assertTrue(reader.get(5, TimeUnit.SECONDS))
            assertNull(PeqPreviewSession.current())
        } finally {
            executor.shutdownNow()
        }
    }
}
