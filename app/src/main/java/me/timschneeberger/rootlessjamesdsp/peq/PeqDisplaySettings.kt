package me.timschneeberger.rootlessjamesdsp.peq

import androidx.core.widget.doAfterTextChanged
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PeqSettingsDialogBinding

/** Keeps UI settings as a dialog draft; cancelling does not write either preference store. */
internal class PeqDisplaySettings(
    private val ui: PeqSettingsDialogBinding,
    private val preferences: PeqEditorPreferences
) {
    private var updatingDuration = false
    private val textSizeControl: PeqSettingControl
    private val forever = ui.root.context.getString(R.string.peq_marker_duration_forever)

    init {
        val initial = preferences.load()
        ui.peqConfirmDelete.isChecked = initial.confirmDelete
        ui.peqShowMarkerFrequency.isChecked = initial.showMarkerFrequency
        ui.peqShowMarkerGain.isChecked = initial.showMarkerGain
        ui.peqShowMarkerQuality.isChecked = initial.showMarkerQuality
        ui.peqShowMarkerFilterType.isChecked = initial.showMarkerFilterType
        ui.peqShowMarkerBandNumber.isChecked = initial.showMarkerBandNumber
        ui.peqShowInlineFilters.isChecked = initial.showInlineFilters
        ui.peqShowMarkerPreviewStatus.isChecked = initial.showMarkerPreviewStatus
        ui.peqShowMarkerGuides.isChecked = initial.showMarkerGuides
        ui.peqAllowLockedBandReorder.isChecked = initial.allowLockedBandReorder
        textSizeControl = PeqSettingControl(ui.peqMarkerTextSizeSlider, ui.peqMarkerTextSize,
            ui.peqMarkerTextSizeBox, 8.0, 20.0, 1.0, initial.markerTextSizeSp.toDouble(), integer = true)
        ui.peqMarkerDurationSlider.apply {
            valueFrom = 0f
            valueTo = 9f
            stepSize = 1f
            value = position(initial.markerDurationSeconds)
            setLabelFormatter { formatDuration(duration(it)) }
            addOnChangeListener { _, value, fromUser ->
                if (fromUser && !updatingDuration) {
                    updatingDuration = true
                    ui.peqMarkerDuration.setText(formatDuration(duration(value)))
                    ui.peqMarkerDurationBox.error = null
                    updatingDuration = false
                }
            }
        }
        ui.peqMarkerDuration.setText(formatDuration(initial.markerDurationSeconds))
        ui.peqMarkerDuration.doAfterTextChanged {
            if (!updatingDuration) {
                ui.peqMarkerDurationBox.error = null
                readDuration()?.let { seconds ->
                    updatingDuration = true
                    ui.peqMarkerDurationSlider.value = position(seconds)
                    updatingDuration = false
                }
            }
        }
        ui.peqShowMarkerGuides.setOnCheckedChangeListener { _, _ -> renderMarkerAvailability() }
        renderMarkerAvailability()
    }

    private fun renderMarkerAvailability() {
        val enabled = ui.peqShowMarkerGuides.isChecked
        // Disable editing only: hidden marker options remain part of the dialog draft,
        // so switching the guides back on restores the user's choices unchanged.
        listOf(ui.peqMarkerDurationSlider, ui.peqMarkerTextSizeSlider,
            ui.peqMarkerDurationBox, ui.peqMarkerTextSizeBox,
            ui.peqShowMarkerFrequency, ui.peqShowMarkerFilterType, ui.peqShowMarkerBandNumber,
            ui.peqShowMarkerPreviewStatus, ui.peqShowMarkerGain, ui.peqShowMarkerQuality)
            .forEach { it.isEnabled = enabled }
        listOf(ui.peqMarkerDurationLabel, ui.peqMarkerTextSizeLabel).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.38f
        }
        if (!enabled) {
            ui.peqMarkerDuration.clearFocus()
            ui.peqMarkerTextSize.clearFocus()
        }
    }

    private fun position(seconds: Int) = if (seconds == 0) 0f else (seconds - 1).toFloat()
    private fun duration(position: Float) = if (position == 0f) 0 else position.toInt() + 1
    private fun formatDuration(seconds: Int) = if (seconds == 0) forever else seconds.toString()
    private fun readDuration(): Int? {
        val text = ui.peqMarkerDuration.text?.toString()?.trim().orEmpty()
        if (text == forever) return 0
        return text.toIntOrNull()?.takeIf { it == 0 || it in 2..10 }
    }

    fun validatedSettings(): PeqEditorSettings? {
        val duration = readDuration()
        ui.peqMarkerDurationBox.error = if (duration == null)
            ui.root.context.getString(R.string.peq_invalid_marker_duration) else null
        val size = textSizeControl.validatedValue()?.toInt()
        if (duration == null || size == null) return null
        return PeqEditorSettings(
            confirmDelete = ui.peqConfirmDelete.isChecked,
            markerDurationSeconds = duration,
            markerTextSizeSp = size,
            showMarkerFrequency = ui.peqShowMarkerFrequency.isChecked,
            showMarkerGain = ui.peqShowMarkerGain.isChecked,
            showMarkerQuality = ui.peqShowMarkerQuality.isChecked,
            showMarkerFilterType = ui.peqShowMarkerFilterType.isChecked,
            showMarkerBandNumber = ui.peqShowMarkerBandNumber.isChecked,
            showInlineFilters = ui.peqShowInlineFilters.isChecked,
            showMarkerPreviewStatus = ui.peqShowMarkerPreviewStatus.isChecked,
            showMarkerGuides = ui.peqShowMarkerGuides.isChecked,
            allowLockedBandReorder = ui.peqAllowLockedBandReorder.isChecked)
    }

    fun save(settings: PeqEditorSettings) = preferences.save(settings)
    fun validateAndSave(): Boolean = validatedSettings()?.let(::save) ?: false
}
