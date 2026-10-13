package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PeqSettingsDialogBinding
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
class PeqDisplaySettingsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val stored get() = app.getSharedPreferences(PeqEditorPreferences.PREFERENCES, Context.MODE_PRIVATE)
    private val prefs get() = PeqEditorPreferences(app)

    @Before fun clear() { stored.edit().clear().commit() }

    private fun controls(): Pair<PeqSettingsDialogBinding, PeqDisplaySettings> {
        val context = ContextThemeWrapper(app, R.style.Theme_RootlessJamesDSP)
        val ui = PeqSettingsDialogBinding.inflate(LayoutInflater.from(context))
        return ui to PeqDisplaySettings(ui, prefs)
    }

    @Test fun defaultsAndSavedOptionsAreIndependentOfAudioAndBandLocks() {
        assertEquals(PeqEditorSettings(), prefs.load())
        assertTrue(stored.all.isEmpty())
        val audio = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        PeqRepository(app).save(PeqModel.defaultState())
        val locks = PeqBandLockStore(app)
        locks.setBandLocked("default-1", false)
        val before = audio.all.toMap()
        val custom = PeqEditorSettings(true, 0, 20, false, true, false, false, false,
            showMarkerBandNumber = true, showInlineFilters = true)
        assertTrue(prefs.save(custom))
        assertEquals(custom, PeqEditorPreferences(app).load())
        assertEquals(before, audio.all)
        assertFalse(locks.isBandLocked("default-1"))
        assertThrows(IllegalArgumentException::class.java) { prefs.save(custom.copy(markerDurationSeconds = 1)) }
        assertThrows(IllegalArgumentException::class.java) { prefs.save(custom.copy(markerTextSizeSp = 21)) }
        assertEquals(custom, prefs.load())
    }

    @Test fun openingAndDiscardingDraftDoesNotWritePreferences() {
        val original = PeqEditorSettings(markerDurationSeconds = 10, markerTextSizeSp = 15)
        prefs.save(original)
        val before = stored.all.toMap()
        val (ui, settings) = controls()
        ui.peqMarkerDuration.setText("0")
        ui.peqMarkerTextSize.setText("8")
        ui.peqConfirmDelete.isChecked = true
        ui.peqShowMarkerGain.isChecked = false
        ui.peqPreviewDisabledBands.isChecked = false
        ui.peqShowMarkerFilterType.isChecked = false
        assertEquals(original.copy(confirmDelete = true, markerDurationSeconds = 0, markerTextSizeSp = 8,
            showMarkerGain = false, previewDisabledBands = false, showMarkerFilterType = false), settings.validatedSettings())
        assertEquals(before, stored.all)
        assertEquals(original, prefs.load())
    }

    @Test fun durationIncludesPermanentAndSlidersSynchronizeWithoutAcceptingOneSecond() {
        val (ui, settings) = controls()
        ui.peqMarkerDuration.setText("0")
        assertEquals(0f, ui.peqMarkerDurationSlider.value, 0f)
        ui.peqMarkerDurationSlider.requestFocus()
        ui.peqMarkerDurationSlider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("2", ui.peqMarkerDuration.text.toString())
        repeat(12) { ui.peqMarkerDurationSlider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }
        assertEquals("10", ui.peqMarkerDuration.text.toString())
        repeat(12) { ui.peqMarkerDurationSlider.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) }
        assertEquals(app.getString(R.string.peq_marker_duration_forever), ui.peqMarkerDuration.text.toString())
        assertEquals(0, settings.validatedSettings()!!.markerDurationSeconds)
        for (invalid in listOf("1", "11", "", "-1")) {
            ui.peqMarkerDuration.setText(invalid)
            assertNull(settings.validatedSettings())
            assertNotNull(ui.peqMarkerDurationBox.error)
            assertFalse(settings.validateAndSave())
            assertTrue(stored.all.isEmpty())
        }
        ui.peqMarkerDuration.setText("4")
        ui.peqMarkerTextSize.setText("20")
        assertEquals(20f, ui.peqMarkerTextSizeSlider.value, 0f)
        assertTrue(settings.validateAndSave())
        assertEquals(PeqEditorSettings(markerDurationSeconds = 4, markerTextSizeSp = 20), prefs.load())
    }

    @Test fun malformedStoredUiValuesUseSafeDefaultsWithoutChangingTheSource() {
        stored.edit().putString("markerDurationSeconds", "bad").putInt("markerTextSizeSp", 500)
            .putInt("confirmDelete", 5).putBoolean("showMarkerFrequency", false)
            .putString("showMarkerBandNumber", "true").putInt("showInlineFilters", 1).commit()
        val before = stored.all.toMap()
        assertEquals(PeqEditorSettings(showMarkerFrequency = false), prefs.load())
        assertEquals(before, stored.all)
    }

    @Test fun filterTypeDisplayDefaultsOnForExistingSettingsAndOnlyConfirmationSavesIt() {
        stored.edit().putBoolean("showMarkerFrequency", false).putInt("markerTextSizeSp", 13).commit()
        val before = stored.all.toMap()
        val (ui, settings) = controls()
        assertTrue(ui.peqShowMarkerFilterType.isChecked)
        ui.peqShowMarkerFilterType.performClick()
        assertFalse(settings.validatedSettings()!!.showMarkerFilterType)
        assertEquals(before, stored.all)
        assertTrue(settings.validateAndSave())
        assertFalse(prefs.load().showMarkerFilterType)
        assertFalse(prefs.load().showMarkerFrequency)
        assertEquals(13, prefs.load().markerTextSizeSp)
        assertFalse(controls().first.peqShowMarkerFilterType.isChecked)
    }

    @Test fun bandNumbersAndInlineFiltersDefaultOffForFreshAndExistingPreferences() {
        val (fresh, freshSettings) = controls()
        assertFalse(fresh.peqShowMarkerBandNumber.isChecked)
        assertFalse(fresh.peqShowInlineFilters.isChecked)
        assertEquals(PeqEditorSettings(), freshSettings.validatedSettings())
        assertTrue(stored.all.isEmpty())

        stored.edit().putBoolean("showMarkerFilterType", false).putInt("markerTextSizeSp", 13).commit()
        val before = stored.all.toMap()
        val (existing, existingSettings) = controls()
        assertFalse(existing.peqShowMarkerBandNumber.isChecked)
        assertFalse(existing.peqShowInlineFilters.isChecked)
        assertFalse(existingSettings.validatedSettings()!!.showMarkerBandNumber)
        assertFalse(existingSettings.validatedSettings()!!.showInlineFilters)
        assertEquals(before, stored.all)
    }

    @Test fun bandNumberAndInlineFilterDraftsPersistOnlyOnConfirmationAndReopenIndependently() {
        val original = PeqEditorSettings(showMarkerFilterType = false, showMarkerQuality = false)
        assertTrue(prefs.save(original))
        val (discardedUi, discardedSettings) = controls()
        discardedUi.peqShowMarkerBandNumber.performClick()
        discardedUi.peqShowInlineFilters.performClick()
        assertEquals(original.copy(showMarkerBandNumber = true, showInlineFilters = true),
            discardedSettings.validatedSettings())
        assertEquals(original, prefs.load())
        val (confirmedUi, confirmedSettings) = controls()
        assertFalse(confirmedUi.peqShowMarkerBandNumber.isChecked)
        assertFalse(confirmedUi.peqShowInlineFilters.isChecked)
        confirmedUi.peqShowMarkerBandNumber.performClick()
        assertTrue(confirmedSettings.validateAndSave())
        assertEquals(original.copy(showMarkerBandNumber = true), prefs.load())

        val (reopenedUi, reopenedSettings) = controls()
        assertTrue(reopenedUi.peqShowMarkerBandNumber.isChecked)
        assertFalse(reopenedUi.peqShowInlineFilters.isChecked)
        reopenedUi.peqShowInlineFilters.performClick()
        assertTrue(reopenedSettings.validateAndSave())
        assertEquals(original.copy(showMarkerBandNumber = true, showInlineFilters = true), prefs.load())
        assertTrue(controls().first.peqShowInlineFilters.isChecked)
    }
}
