package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PeqBatchParametersDialogBinding
import kotlin.math.ceil
import kotlin.math.floor

/** Q, BW and filter type form one cancellable draft, with no live audio writes. */
object PeqBatchParametersDialog {
    fun create(context: Context, title: String, ids: Set<String>, readState: () -> PeqState,
               canEdit: (String, PeqEditField?) -> Boolean, scan: () -> String,
               onSave: (PeqState) -> Boolean): AlertDialog {
        val ui = PeqBatchParametersDialogBinding.inflate(LayoutInflater.from(context))
        val original = readState()
        fun targets(state: PeqState, mode: PeqQualityDisplay) = state.bands.filter {
            it.id in ids && it.qualityDisplay == mode && canEdit(it.id, PeqEditField.QUALITY)
        }
        fun hint(mode: PeqQualityDisplay, eligible: Int): String = context.getString(R.string.peq_bulk_mode_hint,
            eligible, original.bands.count { it.id in ids && it.qualityDisplay == mode } - eligible)
        val qBands = targets(original, PeqQualityDisplay.Q)
        val bwBands = targets(original, PeqQualityDisplay.BW)
        val qControl = PeqSettingControl(ui.peqBatchQSlider, ui.peqBatchQInput, ui.peqBatchQBox,
            PeqModel.MIN_Q, original.qRange, 0.001,
            (qBands.firstOrNull()?.q ?: PeqModel.DEFAULT_Q).coerceIn(PeqModel.MIN_Q, original.qRange), logarithmic = true)
        val rawBw = if (bwBands.isEmpty()) 0.001..1.0 else PeqBulkParameters.bandwidthRange(original.copy(bands = bwBands))
        val minimum = ceil(rawBw.start * 1000.0 - 1e-9) / 1000.0
        val maximum = floor(rawBw.endInclusive * 1000.0 + 1e-9) / 1000.0
        val validBw = bwBands.isNotEmpty() && minimum <= maximum
        val bwControl = PeqSettingControl(ui.peqBatchBwSlider, ui.peqBatchBwInput, ui.peqBatchBwBox,
            if (validBw) minimum else 0.001, if (validBw) maximum else 1.0, 0.001,
            if (validBw) PeqResponse.bandwidthOctaves(bwBands.first().q, bwBands.first().frequency).coerceIn(minimum, maximum)
            else 0.5, logarithmic = true)
        ui.peqBatchQSlider.isEnabled = qBands.isNotEmpty()
        ui.peqBatchQBox.isEnabled = qBands.isNotEmpty()
        ui.peqBatchBwSlider.isEnabled = validBw
        ui.peqBatchBwBox.isEnabled = validBw
        ui.peqBatchQHint.text = hint(PeqQualityDisplay.Q, qBands.size)
        ui.peqBatchBwHint.text = if (bwBands.isNotEmpty() && !validBw) context.getString(R.string.peq_bulk_bw_no_range)
            else hint(PeqQualityDisplay.BW, bwBands.size)
        val filterTargets = original.bands.filter { it.id in ids && canEdit(it.id, null) }
        ui.peqBatchFilter.setSelection(filterTargets.map { it.type }.distinct().singleOrNull())
        ui.peqBatchFilter.isEnabled = filterTargets.isNotEmpty()
        ui.peqBatchScan.text = scan()
        var qChanged = false
        var bwChanged = false
        var typeChanged = false
        ui.peqBatchQInput.doAfterTextChanged { qChanged = true }
        ui.peqBatchBwInput.doAfterTextChanged { bwChanged = true }
        ui.peqBatchFilter.onTypeSelected = { typeChanged = true }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(ui.root)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val q = if (qChanged) qControl.validatedValue() ?: return@setOnClickListener else null
                val bw = if (bwChanged) bwControl.validatedValue() ?: return@setOnClickListener else null
                // Rescan locks and display modes at confirmation; no stale draft can bypass protection.
                val next = runCatching { PeqBulkParameters.applyDraft(readState(), ids, canEdit, q, bw,
                    if (typeChanged) ui.peqBatchFilter.selectedType else null) }.getOrElse {
                    ui.peqBatchScan.text = context.getString(R.string.peq_bulk_quality_error,
                        PeqQualityEditor.format(minimum), PeqQualityEditor.format(maximum))
                    return@setOnClickListener
                }
                if (onSave(next)) dialog.dismiss()
            }
        }
        return dialog
    }
}
