package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
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
        val custom = PeqEditorSettings(confirmDelete = true, markerDurationSeconds = 0, markerTextSizeSp = 20,
            showMarkerFrequency = false, showMarkerGain = true, showMarkerQuality = false, showMarkerFilterType = false,
            showMarkerBandNumber = true, showInlineFilters = true, showMarkerPreviewStatus = false,
            showMarkerGuides = false, allowLockedBandReorder = true)
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
        ui.peqShowMarkerFilterType.isChecked = false
        assertEquals(original.copy(confirmDelete = true, markerDurationSeconds = 0, markerTextSizeSp = 8,
            showMarkerGain = false, showMarkerFilterType = false), settings.validatedSettings())
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
            .putString("showMarkerBandNumber", "true").putInt("showInlineFilters", 1)
            .putString("showMarkerPreviewStatus", "false").putInt("showMarkerGuides", 0)
            .putString("allowLockedBandReorder", "true").commit()
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

    @Test fun previewStatusDefaultsOnAndCanBeHiddenWithoutChangingOtherDisplaySettings() {
        val (fresh, _) = controls()
        assertTrue(fresh.peqShowMarkerPreviewStatus.isChecked)
        assertTrue(prefs.load().showMarkerPreviewStatus)
        assertTrue(stored.all.isEmpty())
        stored.edit().putBoolean("showMarkerFilterType", false)
            .putBoolean("showMarkerBandNumber", true).putBoolean("showInlineFilters", true).commit()
        val before = stored.all.toMap()
        val (ui, settings) = controls()
        assertTrue(ui.peqShowMarkerPreviewStatus.isChecked)
        ui.peqShowMarkerPreviewStatus.performClick()
        assertFalse(settings.validatedSettings()!!.showMarkerPreviewStatus)
        assertEquals(before, stored.all)
        assertTrue(prefs.load().showMarkerPreviewStatus)
        assertTrue(settings.validateAndSave())
        val hidden = prefs.load()
        assertFalse(hidden.showMarkerPreviewStatus)
        assertFalse(hidden.showMarkerFilterType)
        assertTrue(hidden.showMarkerBandNumber)
        assertTrue(hidden.showInlineFilters)
        val (reopened, reopenedSettings) = controls()
        assertFalse(reopened.peqShowMarkerPreviewStatus.isChecked)
        reopened.peqShowMarkerPreviewStatus.performClick()
        assertTrue(reopenedSettings.validateAndSave())
        assertEquals(hidden.copy(showMarkerPreviewStatus = true), prefs.load())
    }

    @Test fun markerGuidesDefaultOnForExistingSettingsAndOnlyConfirmationPersistsTheSwitch() {
        val (fresh, _) = controls()
        assertTrue(fresh.peqShowMarkerGuides.isChecked)
        assertTrue(prefs.load().showMarkerGuides)
        assertTrue(stored.all.isEmpty())
        stored.edit().putBoolean("showMarkerGain", false)
            .putInt("markerDurationSeconds", 10).commit()
        val before = stored.all.toMap()
        val (ui, settings) = controls()
        assertTrue(ui.peqShowMarkerGuides.isChecked)
        ui.peqShowMarkerGuides.performClick()
        assertFalse(settings.validatedSettings()!!.showMarkerGuides)
        assertEquals(before, stored.all)
        assertTrue(prefs.load().showMarkerGuides)
        assertTrue(settings.validateAndSave())
        assertEquals(PeqEditorSettings(showMarkerGain = false,
            markerDurationSeconds = 10, showMarkerGuides = false), prefs.load())
        val (reopened, reopenedSettings) = controls()
        assertFalse(reopened.peqShowMarkerGuides.isChecked)
        reopened.peqShowMarkerGuides.performClick()
        assertTrue(reopenedSettings.validateAndSave())
        assertTrue(prefs.load().showMarkerGuides)
        assertFalse(prefs.load().showMarkerGain)
        assertEquals(10, prefs.load().markerDurationSeconds)
    }

    private fun assertMarkerControlsEnabled(ui: PeqSettingsDialogBinding, enabled: Boolean) {
        listOf(ui.peqMarkerDurationSlider, ui.peqMarkerDurationBox, ui.peqMarkerDuration,
            ui.peqMarkerTextSizeSlider, ui.peqMarkerTextSizeBox, ui.peqMarkerTextSize,
            ui.peqMarkerDurationLabel, ui.peqMarkerTextSizeLabel,
            ui.peqShowMarkerFrequency, ui.peqShowMarkerFilterType, ui.peqShowMarkerBandNumber,
            ui.peqShowMarkerPreviewStatus, ui.peqShowMarkerGain, ui.peqShowMarkerQuality).forEach {
            assertEquals("Only marker-dependent controls follow the master switch: ${it.id}", enabled, it.isEnabled)
        }
        listOf(ui.peqMarkerDurationLabel, ui.peqMarkerTextSizeLabel).forEach {
            if (enabled) assertEquals(1f, it.alpha, 0f) else assertTrue(it.alpha < 1f)
        }
        listOf(ui.peqShowMarkerGuides, ui.peqConfirmDelete, ui.peqAllowLockedBandReorder,
            ui.peqShowInlineFilters, ui.peqShowQSlider, ui.peqShowFrequencySlider,
            ui.peqBandCountSlider, ui.peqGainStepSlider, ui.peqGainRangeSlider,
            ui.peqQStepSlider, ui.peqQRangeSlider, ui.peqFrequencyStepSlider).forEach {
            assertTrue("Unrelated settings must remain available: ${it.id}", it.isEnabled)
        }
    }

    @Test fun hidingGuidesDisablesMarkerEditingWithoutLosingUnsavedValuesOrChangingOtherSettings() {
        val initial = PeqEditorSettings(markerDurationSeconds = 7, markerTextSizeSp = 16,
            showMarkerFrequency = false, showMarkerGain = false, showMarkerFilterType = true,
            showMarkerBandNumber = true, showMarkerPreviewStatus = false)
        assertTrue(prefs.save(initial))
        val before = stored.all.toMap()
        val (ui, settings) = controls()
        assertMarkerControlsEnabled(ui, true)
        ui.peqMarkerDuration.setText("8")
        ui.peqMarkerTextSize.setText("19")
        ui.peqShowMarkerGain.performClick()
        ui.peqShowMarkerFilterType.performClick()
        val edited = initial.copy(markerDurationSeconds = 8, markerTextSizeSp = 19,
            showMarkerGain = true, showMarkerFilterType = false)
        ui.peqShowMarkerGuides.performClick()
        assertMarkerControlsEnabled(ui, false)
        assertEquals(edited.copy(showMarkerGuides = false), settings.validatedSettings())

        // Exercise actual touch dispatch: performClick() is a programmatic command
        // and can bypass a widget's disabled state, unlike a user's tap.
        listOf(ui.peqShowMarkerFrequency, ui.peqShowMarkerFilterType, ui.peqShowMarkerBandNumber,
            ui.peqShowMarkerPreviewStatus, ui.peqShowMarkerGain, ui.peqShowMarkerQuality).forEach { toggle ->
            toggle.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
            toggle.layout(0, 0, 320, 48)
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(0, 10, action, 160f, 24f, 0)
                try { toggle.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        assertEquals(edited.copy(showMarkerGuides = false), settings.validatedSettings())
        assertEquals(before, stored.all)
        ui.peqShowMarkerGuides.performClick()
        assertMarkerControlsEnabled(ui, true)
        assertEquals(edited, settings.validatedSettings())
        assertEquals("8", ui.peqMarkerDuration.text.toString())
        assertEquals("19", ui.peqMarkerTextSize.text.toString())
        assertEquals(before, stored.all)
        // Discarding this dialog restores the saved choices when it is reopened.
        assertEquals(initial, controls().second.validatedSettings())
    }

    @Test fun savedHiddenGuidesReopenWithDisabledDetailsAndRestoreTheirExactChoicesWhenEnabled() {
        val hidden = PeqEditorSettings(markerDurationSeconds = 0, markerTextSizeSp = 20,
            showMarkerFrequency = false, showMarkerGain = true, showMarkerQuality = false,
            showMarkerFilterType = false, showMarkerBandNumber = true,
            showMarkerPreviewStatus = false, showMarkerGuides = false,
            showInlineFilters = true, allowLockedBandReorder = true)
        assertTrue(prefs.save(hidden))
        val (ui, settings) = controls()
        assertMarkerControlsEnabled(ui, false)
        assertEquals(hidden, settings.validatedSettings())
        assertTrue(settings.validateAndSave())
        assertEquals(hidden, prefs.load())
        ui.peqShowMarkerGuides.performClick()
        assertMarkerControlsEnabled(ui, true)
        assertEquals(hidden.copy(showMarkerGuides = true), settings.validatedSettings())
        assertEquals(app.getString(R.string.peq_marker_duration_forever), ui.peqMarkerDuration.text.toString())
        assertEquals("20", ui.peqMarkerTextSize.text.toString())
        assertTrue(settings.validateAndSave())
        assertEquals(hidden.copy(showMarkerGuides = true), prefs.load())
        assertMarkerControlsEnabled(controls().first, true)
    }

    @Test fun lockedBandReorderDefaultsOffForFreshAndLegacySettingsWithoutWritingMigrationData() {
        val (fresh, freshSettings) = controls()
        assertFalse(fresh.peqAllowLockedBandReorder.isChecked)
        assertFalse(freshSettings.validatedSettings()!!.allowLockedBandReorder)
        assertFalse(prefs.load().allowLockedBandReorder)
        assertTrue(stored.all.isEmpty())

        stored.edit().putBoolean("showMarkerGuides", false).putBoolean("showInlineFilters", true)
            .putInt("markerDurationSeconds", 6).commit()
        val before = stored.all.toMap()
        val (legacy, legacySettings) = controls()
        assertFalse(legacy.peqAllowLockedBandReorder.isChecked)
        assertEquals(PeqEditorSettings(showMarkerGuides = false, showInlineFilters = true,
            markerDurationSeconds = 6), legacySettings.validatedSettings())
        assertEquals(before, stored.all)
    }

    @Test fun lockedBandReorderDraftCanBeCancelledAndSavedWithoutChangingAnyBandLocksOrAudio() {
        val initial = PeqEditorSettings(showMarkerGuides = false, confirmDelete = true)
        assertTrue(prefs.save(initial))
        val audio = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        assertTrue(PeqRepository(app).save(PeqModel.defaultState()))
        val locks = PeqBandLockStore(app)
        locks.setBandLocked("default-1", true)
        PeqEditField.entries.forEach { locks.setFieldLocked("default-1", it, true) }
        val lockPreferences = app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE)
        val beforeLocks = lockPreferences.all.toMap()
        val beforeAudio = audio.all.toMap()
        val (cancelledUi, cancelledSettings) = controls()
        cancelledUi.peqAllowLockedBandReorder.performClick()
        assertEquals(initial.copy(allowLockedBandReorder = true), cancelledSettings.validatedSettings())
        assertEquals(initial, prefs.load())

        val (confirmedUi, confirmedSettings) = controls()
        assertFalse(confirmedUi.peqAllowLockedBandReorder.isChecked)
        confirmedUi.peqAllowLockedBandReorder.performClick()
        assertTrue(confirmedSettings.validateAndSave())
        assertEquals(initial.copy(allowLockedBandReorder = true), PeqEditorPreferences(app).load())
        assertEquals(beforeLocks, lockPreferences.all)
        assertEquals(beforeAudio, audio.all)
        val (reopenedUi, reopenedSettings) = controls()
        assertTrue(reopenedUi.peqAllowLockedBandReorder.isChecked)
        reopenedUi.peqAllowLockedBandReorder.performClick()
        assertTrue(reopenedSettings.validateAndSave())
        assertEquals(initial, prefs.load())
        assertEquals(beforeLocks, lockPreferences.all)
        assertEquals(beforeAudio, audio.all)
    }

    @Test fun removedDisabledBandPreviewPreferenceIsIgnoredAndDoesNotAffectStatusVisibility() {
        stored.edit().putBoolean("previewDisabledBands", false)
            .putBoolean("showMarkerPreviewStatus", true).commit()
        val before = stored.all.toMap()
        val (ui, settings) = controls()
        assertEquals(PeqEditorSettings(), prefs.load())
        assertEquals(PeqEditorSettings(), settings.validatedSettings())
        assertTrue(ui.peqShowMarkerPreviewStatus.isChecked)
        assertEquals(before, stored.all)
        ui.peqShowMarkerPreviewStatus.performClick()
        assertTrue(settings.validateAndSave())
        assertEquals(PeqEditorSettings(showMarkerPreviewStatus = false), prefs.load())
        assertFalse(controls().first.peqShowMarkerPreviewStatus.isChecked)
        assertFalse(stored.getBoolean("previewDisabledBands", true))
    }
}
