package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/** The ordinary reset menu must explain protection without entering multi-selection mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqNormalResetMenuUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val repository get() = PeqRepository(app)
    private val locks get() = PeqBandLockStore(app)
    private val lockPrefs get() = app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE)
    private var controller: ActivityController<HostActivity>? = null
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment

    @Before fun clear() {
        app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE).edit().clear().commit()
        lockPrefs.edit().clear().commit()
        app.getSharedPreferences(PeqEditorPreferences.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        idle()
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }

    private fun bank() = PeqState(listOf("master", "q", "bw", "gain", "frequency-type", "all-fields", "free")
        .mapIndexed { index, id -> PeqBand(id, type = PeqFilterType.HIGH_SHELF,
            frequency = 300.0 + index * 700.0, gain = 4.0, q = 2.0,
            enabled = index % 2 == 0,
            qualityDisplay = if (id == "bw") PeqQualityDisplay.BW else PeqQualityDisplay.Q) })

    private fun configureProtection(state: PeqState) {
        state.bands.forEach { locks.setBandLocked(it.id, it.id == "master") }
        listOf("q", "bw").forEach { locks.setFieldLocked(it, PeqEditField.QUALITY, true) }
        locks.setFieldLocked("gain", PeqEditField.GAIN, true)
        locks.setFieldLocked("frequency-type", PeqEditField.FREQUENCY, true)
        locks.setFieldLocked("frequency-type", PeqEditField.FILTER_TYPE, true)
        PeqEditField.entries.forEach { locks.setFieldLocked("all-fields", it, true) }
    }

    private fun launch(state: PeqState) {
        assertTrue(repository.save(state))
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
    }

    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        val density = app.resources.displayMetrics.density
        val width = (411 * density).toInt()
        val height = (820 * density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, width, height)
        idle()
    }

    private fun menu(): AlertDialog {
        assertEquals(View.GONE, fragment().requireView().findViewById<View>(R.id.peq_selection_controls).visibility)
        fragment().requireView().findViewById<View>(R.id.peq_reset).performClick()
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun index(menu: AlertDialog, label: Int) = (0 until menu.listView.adapter.count).first {
        menu.listView.adapter.getItem(it).toString() == app.getString(label)
    }

    private fun row(menu: AlertDialog, label: Int): View =
        menu.listView.adapter.getView(index(menu, label), null, menu.listView)

    private fun choose(menu: AlertDialog, label: Int) {
        val position = index(menu, label)
        menu.listView.performItemClick(null, position, position.toLong())
        layout()
    }

    @Test fun normalMenuShowsOnlySixActionsWithFieldCountsAndNoSeparateScanOrLockButtons() {
        val state = bank()
        configureProtection(state)
        launch(state)
        val beforeLocks = lockPrefs.all.toMap()
        val menu = menu()
        assertEquals(6, menu.listView.adapter.count)
        assertNull(menu.findViewById<View>(R.id.peq_bulk_lock_scan))
        val expected = listOf(R.string.peq_reset_q to (3 to 4),
            R.string.peq_reset_frequency to (4 to 3), R.string.peq_reset_gain to (4 to 3),
            R.string.peq_reset_filter_type to (4 to 3), R.string.peq_reset_all to (5 to 2),
            R.string.peq_redistribute to (4 to 3))
        expected.forEach { (label, counts) ->
            val row = row(menu, label)
            assertEquals(app.getString(label), row.findViewById<TextView>(R.id.peq_bulk_lock_title).text.toString())
            val countLabel = if (label == R.string.peq_redistribute) R.string.peq_bulk_redistribute_counts
                else R.string.peq_bulk_reset_counts
            val suffix = if (label == R.string.peq_reset_all) "\n" + app.getString(R.string.peq_bulk_reset_unlocked_only) else ""
            assertEquals(app.getString(countLabel, counts.first, counts.second) + suffix,
                row.findViewById<TextView>(R.id.peq_bulk_reset_counts).text.toString())
            assertNull(row.findViewById<View>(R.id.peq_bulk_lock_button))
            assertNull(row.findViewById<View>(R.id.peq_bulk_unlock_button))
        }
        menu.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(state, repository.load())
        assertEquals(beforeLocks, lockPrefs.all)
    }

    @Test fun normalQualityResetSkipsBothQAndBwLocksAndMatchesItsOwnSummary() {
        val state = bank()
        configureProtection(state)
        launch(state)
        val menu = menu()
        assertEquals(app.getString(R.string.peq_bulk_reset_counts, 3, 4),
            row(menu, R.string.peq_reset_q).findViewById<TextView>(R.id.peq_bulk_reset_counts).text.toString())
        choose(menu, R.string.peq_reset_q)
        assertEquals(state.copy(bands = state.bands.map {
            if (it.id in setOf("gain", "frequency-type", "free")) it.copy(q = PeqModel.DEFAULT_Q) else it
        }), repository.load())
        assertEquals(app.getString(R.string.peq_reset_protected_result, 3, 4), ShadowToast.getTextOfLatestToast())
    }

    @Test fun normalFrequencyRedistributionPreservesOtherParametersAndSkipsProtectedBands() {
        val state = bank()
        configureProtection(state)
        launch(state)
        choose(menu(), R.string.peq_redistribute)
        val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(state, repository.load())
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        val actual = repository.load()
        val frequencies = mapOf("q" to 20.0, "bw" to 200.0, "gain" to 2000.0, "free" to 20000.0)
        state.bands.zip(actual.bands).forEach { (before, after) ->
            assertEquals(frequencies[before.id] ?: before.frequency, after.frequency, 1e-7)
            assertEquals(before, after.copy(frequency = before.frequency))
        }
        assertEquals(app.getString(R.string.peq_reset_protected_result, 4, 3), ShadowToast.getTextOfLatestToast())
    }

    @Test fun ordinaryMenuWithAllCardsLockedReportsZeroAndDoesNotModifyAudio() {
        val state = bank()
        launch(state)
        val beforeLocks = lockPrefs.all.toMap()
        val menu = menu()
        assertEquals(app.getString(R.string.peq_bulk_reset_counts, 0, state.bands.size) + "\n" +
            app.getString(R.string.peq_bulk_reset_unlocked_only),
            row(menu, R.string.peq_reset_all).findViewById<TextView>(R.id.peq_bulk_reset_counts).text.toString())
        choose(menu, R.string.peq_reset_all)
        assertEquals(state, repository.load())
        assertEquals(beforeLocks, lockPrefs.all)
        assertNull(PeqPreviewSession.current())
        assertEquals(app.getString(R.string.peq_reset_protected_result, 0, state.bands.size), ShadowToast.getTextOfLatestToast())
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_graphic_eq)
            setSupportActionBar(findViewById(R.id.toolbar))
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .replace(R.id.params, PeqFragment(), "peq").commitNow()
        }
    }
}
