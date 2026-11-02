package me.timschneeberger.rootlessjamesdsp.peq

import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PeqSettingsDialogBinding
import me.timschneeberger.rootlessjamesdsp.eq.SpectrumAppearanceDialog
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch

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
        fun restore(toggle: SpringMaterialSwitch, checked: Boolean) {
            toggle.springEnabled = true
            toggle.setCheckedImmediately(checked)
        }
        restore(ui.peqShowResponse, initial.showResponse)
        restore(ui.peqOnlyTotalResponse, initial.onlyTotalResponse)
        restore(ui.peqShowBandwidthOverlay, initial.showBandwidthOverlay)
        restore(ui.peqShowLinkedStatic, initial.showLinkedStatic)
        restore(ui.peqShowRealtime, initial.showRealtime)
        bindChartStyle(ui.peqStaticChartStyle, PeqChartStyles.staticNames, initial.staticChartStyle)
        bindChartStyle(ui.peqRealtimeChartStyle, PeqChartStyles.realtimeNames, initial.realtimeChartStyle)
        ui.peqSpectrumSettings.setOnClickListener { SpectrumAppearanceDialog.show(ui.root.context, "peq") }
        restore(ui.peqConfirmDelete, initial.confirmDelete)
        restore(ui.peqShowMarkerFrequency, initial.showMarkerFrequency)
        restore(ui.peqShowMarkerGain, initial.showMarkerGain)
        restore(ui.peqShowMarkerQuality, initial.showMarkerQuality)
        restore(ui.peqShowMarkerFilterType, initial.showMarkerFilterType)
        restore(ui.peqShowMarkerBandNumber, initial.showMarkerBandNumber)
        restore(ui.peqShowInlineFilters, initial.showInlineFilters)
        restore(ui.peqShowMarkerPreviewStatus, initial.showMarkerPreviewStatus)
        restore(ui.peqShowMarkerGuides, initial.showMarkerGuides)
        restore(ui.peqAllowLockedBandReorder, initial.allowLockedBandReorder)
        restore(ui.peqDoubleTapUnlocksBand, initial.doubleTapUnlocksBand)
        restore(ui.peqShowCardFilterIcons, initial.showCardFilterIcons)
        // The fragment supplies these two bank-local values before this view is attached.
        restore(ui.peqShowQSlider, ui.peqShowQSlider.isChecked)
        restore(ui.peqShowFrequencySlider, ui.peqShowFrequencySlider.isChecked)
        textSizeControl = PeqSettingControl(ui.peqMarkerTextSizeSlider, ui.peqMarkerTextSize,
            ui.peqMarkerTextSizeBox, 8.0, 20.0, 1.0, initial.markerTextSizeSp.toDouble(), integer = true)
        ui.peqMarkerDurationSlider.apply {
            valueFrom = 0f
            valueTo = 9f
            stepSize = 1f
            value = position(initial.markerDurationSeconds)
            setGlassLabelFormatter { formatDuration(duration(it)) }
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

    /** Selection remains a dialog draft, including while its chart is hidden. */
    private fun bindChartStyle(spinner: Spinner, names: List<String>, selected: Int) {
        spinner.adapter = object : ArrayAdapter<String>(ui.root.context,
            android.R.layout.simple_spinner_item, names) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            private fun formatLabel(view: View, dropdown: Boolean): View = view.apply {
                if (this is TextView) {
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    if (dropdown) minimumHeight = (48 * resources.displayMetrics.density + .5f).toInt()
                }
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                formatLabel(super.getView(position, convertView, parent), false)

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                formatLabel(super.getDropDownView(position, convertView, parent), true)
        }
        spinner.setSelection(selected, false)
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
            allowLockedBandReorder = ui.peqAllowLockedBandReorder.isChecked,
            doubleTapUnlocksBand = ui.peqDoubleTapUnlocksBand.isChecked,
            showCardFilterIcons = ui.peqShowCardFilterIcons.isChecked,
            showResponse = ui.peqShowResponse.isChecked,
            onlyTotalResponse = ui.peqOnlyTotalResponse.isChecked,
            showBandwidthOverlay = ui.peqShowBandwidthOverlay.isChecked,
            showLinkedStatic = ui.peqShowLinkedStatic.isChecked,
            showRealtime = ui.peqShowRealtime.isChecked,
            staticChartStyle = PeqChartStyles.staticStyle(ui.peqStaticChartStyle.selectedItemPosition),
            realtimeChartStyle = PeqChartStyles.realtimeStyle(ui.peqRealtimeChartStyle.selectedItemPosition))
    }

    fun save(settings: PeqEditorSettings) = preferences.save(settings)
    fun validateAndSave(): Boolean = validatedSettings()?.let(::save) ?: false
}
