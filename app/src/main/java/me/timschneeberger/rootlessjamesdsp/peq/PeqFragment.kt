package me.timschneeberger.rootlessjamesdsp.peq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PeqBandDialogBinding
import me.timschneeberger.rootlessjamesdsp.databinding.PeqFragmentBinding
import me.timschneeberger.rootlessjamesdsp.databinding.PeqSettingsDialogBinding
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.UUID

/** An editor only: saving bands never enables the effect or its global power switch. */
class PeqFragment : Fragment() {
    private var binding: PeqFragmentBinding? = null
    private lateinit var repository: PeqRepository
    private lateinit var bandAdapter: PeqBandAdapter
    var editorState: PeqState = PeqModel.defaultState()
        private set
    private var lastSavedState = editorState
    private var selectedBandId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var saveQueued = false
    private val pendingSave = Runnable { flushPendingSave() }
    private val dialogs = mutableListOf<AlertDialog>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_PRESET_LOADED && binding != null) {
                dismissDialogs()
                reload()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedBandId = savedInstanceState?.getString("peq_selected_band")
        requireContext().registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ui = PeqFragmentBinding.inflate(inflater, container, false)
        binding = ui
        bandAdapter = PeqBandAdapter(onBandChanged = { edited ->
            val next = editorState.copy(bands = editorState.bands.map { if (it.id == edited.id) edited else it })
            editorState = next
            render(interactingBand = edited.id)
            if (!saveQueued) {
                saveQueued = true
                handler.postDelayed(pendingSave, 50)
            }
            true
        }, onEdit = ::editBand, onInteractionFinished = ::flushPendingSave,
            onBandSelected = ::selectBand)
        ui.peqBandList.layoutManager = LinearLayoutManager(requireContext())
        ui.peqBandList.adapter = bandAdapter
        ui.peqBandList.itemAnimator = null
        ui.peqSettings.setOnClickListener { showSettings() }
        ui.peqAdd.setOnClickListener {
            if (editorState.bands.size < PeqModel.MAX_BANDS &&
                save(editorState.copy(bands = editorState.bands + PeqBand()))) {
                ui.peqBandList.smoothScrollToPosition(editorState.bands.lastIndex)
            }
        }
        reload()
        return ui.root
    }

    override fun onResume() {
        super.onResume()
        // Another screen or a device preset can replace the saved band bank.
        if (binding != null) reload()
    }

    private fun reload() {
        cancelPendingSave()
        repository = PeqRepository(requireContext())
        val loaded = repository.loadResult()
        editorState = loaded.state
        lastSavedState = editorState
        render(false)
        if (loaded.corrupt) binding?.root?.post {
            binding?.root?.let { Snackbar.make(it, R.string.peq_corrupt_profile, Snackbar.LENGTH_LONG).show() }
        }
    }

    private fun render(animate: Boolean = true, interactingBand: String? = null) {
        binding?.apply {
            if (editorState.bands.none { it.id == selectedBandId }) selectedBandId = editorState.bands.firstOrNull()?.id
            bandAdapter.submitState(editorState, interactingBand)
            peqCurve.setState(editorState, animate)
            peqCurve.setSelectedBand(selectedBandId)
            peqSummary.text = getString(R.string.peq_summary, editorState.bands.size,
                PeqBandAdapter.number(editorState.gainStep), PeqBandAdapter.number(editorState.gainRange))
            peqAdd.isEnabled = editorState.bands.size < PeqModel.MAX_BANDS
        }
    }

    private fun selectBand(id: String) {
        selectedBandId = id
        binding?.peqCurve?.setSelectedBand(id)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("peq_selected_band", selectedBandId)
        super.onSaveInstanceState(outState)
    }

    private fun save(next: PeqState, interactingBand: String? = null): Boolean {
        cancelPendingSave()
        if (!repository.save(next)) {
            binding?.root?.let { Snackbar.make(it, R.string.peq_save_failed, Snackbar.LENGTH_LONG).show() }
            return false
        }
        editorState = next
        lastSavedState = next
        render(interactingBand = interactingBand)
        notifyAudioAndPreview()
        return true
    }

    private fun cancelPendingSave() {
        handler.removeCallbacks(pendingSave)
        saveQueued = false
    }

    private fun flushPendingSave() {
        if (!saveQueued) return
        cancelPendingSave()
        if (repository.save(editorState)) {
            lastSavedState = editorState
            notifyAudioAndPreview()
        } else {
            editorState = lastSavedState
            render(false)
            binding?.root?.let { Snackbar.make(it, R.string.peq_save_failed, Snackbar.LENGTH_LONG).show() }
        }
    }

    private fun notifyAudioAndPreview() {
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_GRAPHIC_EQ_CHANGED))
    }

    private fun showSettings() {
        flushPendingSave()
        val ui = PeqSettingsDialogBinding.inflate(layoutInflater)
        val countControl = PeqSettingControl(ui.peqBandCountSlider, ui.peqBandCount, ui.peqCountBox,
            1.0, 170.0, 1.0, editorState.bands.size.coerceAtLeast(1).toDouble(), integer = true)
        val gainStepControl = PeqSettingControl(ui.peqGainStepSlider, ui.peqGainStep, ui.peqGainStepBox,
            0.01, 2.0, 0.01, editorState.gainStep)
        val gainRangeControl = PeqSettingControl(ui.peqGainRangeSlider, ui.peqGainRange, ui.peqGainRangeBox,
            1.0, 100.0, 1.0, editorState.gainRange)
        val qStepControl = PeqSettingControl(ui.peqQStepSlider, ui.peqQStep, ui.peqQStepBox,
            0.01, 100.0, 0.01, editorState.qStep, logarithmic = true)
        val frequencyStepControl = PeqSettingControl(ui.peqFrequencyStepSlider, ui.peqFrequencyStep,
            ui.peqFrequencyStepBox, 1.0, 1000.0, 1.0, editorState.frequencyStep, logarithmic = true)
        ui.peqShowQSlider.isChecked = editorState.showQSlider
        ui.peqShowFrequencySlider.isChecked = editorState.showFrequencySlider
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.peq_settings_title).setView(ui.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val count = countControl.validatedValue()?.toInt() ?: return@setOnClickListener
                val step = gainStepControl.validatedValue() ?: return@setOnClickListener
                val range = gainRangeControl.validatedValue() ?: return@setOnClickListener
                val qStep = qStepControl.validatedValue() ?: return@setOnClickListener
                val frequencyStep = frequencyStepControl.validatedValue() ?: return@setOnClickListener
                fun applySettings() {
                    if (save(PeqModel.resize(editorState, count).copy(gainStep = step, gainRange = range,
                        qStep = qStep, frequencyStep = frequencyStep,
                        showQSlider = ui.peqShowQSlider.isChecked,
                        showFrequencySlider = ui.peqShowFrequencySlider.isChecked))) dialog.dismiss()
                }
                if (count != editorState.bands.size) {
                    track(MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.peq_reduce_title)
                        .setMessage(if (count < editorState.bands.size)
                            getString(R.string.peq_reduce_confirm, editorState.bands.size, count, editorState.bands.size - count)
                        else getString(R.string.peq_increase_confirm, editorState.bands.size, count))
                        .setPositiveButton(android.R.string.ok) { _, _ -> applySettings() }
                        .setNegativeButton(android.R.string.cancel, null).create()).show()
                } else applySettings()
            }
        }
        ui.peqRedistribute.setOnClickListener {
            track(MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.peq_redistribute_title)
                .setMessage(getString(R.string.peq_redistribute_confirm, editorState.bands.size))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (save(PeqModel.redistribute(editorState))) dialog.dismiss()
                }.setNegativeButton(android.R.string.cancel, null).create()).show()
        }
        track(dialog).show()
    }

    private fun editBand(id: String) {
        flushPendingSave()
        selectBand(id)
        val band = editorState.bands.firstOrNull { it.id == id } ?: return
        val ui = PeqBandDialogBinding.inflate(layoutInflater)
        val types = PeqFilterType.entries.toList()
        val labels = types.map { PeqBandAdapter.typeLabel(requireContext(), it) }
        var selectedType = band.type
        var changingMode = false
        var previewDraft: () -> Unit = {}
        fun supportsBandwidth(type: PeqFilterType) = type in listOf(PeqFilterType.PEAK, PeqFilterType.NOTCH, PeqFilterType.BAND_PASS)
        val frequencyText = PeqBandAdapter.number(band.frequency, 4)
        val qualityEditor = PeqQualityEditor(band.q, frequencyText.toDouble(),
            if (supportsBandwidth(band.type)) band.qualityDisplay else PeqQualityDisplay.Q)
        ui.peqBandType.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels))
        ui.peqBandType.setText(PeqBandAdapter.typeLabel(requireContext(), band.type), false)
        ui.peqFrequencyInput.setText(frequencyText)
        ui.peqGainInput.setText(PeqBandAdapter.number(band.gain, 4))
        fun frequency() = ui.peqFrequencyInput.text?.toString()?.toDoubleOrNull()
        fun inputQ(): Double? {
            val freq = frequency() ?: return null
            return qualityEditor.readQ(ui.peqQualityInput.text?.toString().orEmpty(), freq)
        }
        fun renderQualityMode() {
            changingMode = true
            val bandwidthMode = qualityEditor.mode == PeqQualityDisplay.BW
            ui.peqQualityMode.check(if (bandwidthMode) R.id.peq_quality_bw else R.id.peq_quality_q)
            ui.peqQualityInput.setText(qualityEditor.renderedText)
            ui.peqQualityBox.hint = getString(if (bandwidthMode) R.string.peq_bandwidth else R.string.peq_quality)
            ui.peqQualityBox.helperText = getString(if (bandwidthMode) R.string.peq_bandwidth_hint else R.string.peq_quality_hint)
            ui.peqQualityBox.error = null
            changingMode = false
            previewDraft()
        }
        fun updateTypeFields() {
            ui.peqGainBox.isVisible = selectedType.usesGain
            ui.peqQualityBw.isVisible = supportsBandwidth(selectedType)
        }
        ui.peqBandType.setOnItemClickListener { _, _, position, _ ->
            val nextType = types[position]
            if (!supportsBandwidth(nextType) && qualityEditor.mode == PeqQualityDisplay.BW) {
                val converted = qualityEditor.switchMode(PeqQualityDisplay.Q,
                    ui.peqQualityInput.text?.toString().orEmpty(), frequency() ?: Double.NaN)
                if (converted == null) {
                    ui.peqBandType.setText(PeqBandAdapter.typeLabel(requireContext(), selectedType), false)
                    ui.peqQualityBox.error = getString(R.string.peq_invalid_quality)
                    return@setOnItemClickListener
                }
                renderQualityMode()
            }
            selectedType = nextType
            updateTypeFields()
            previewDraft()
        }
        ui.peqQualityMode.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked || changingMode) return@addOnButtonCheckedListener
            val nextMode = if (checkedId == R.id.peq_quality_bw) PeqQualityDisplay.BW else PeqQualityDisplay.Q
            val converted = qualityEditor.switchMode(nextMode, ui.peqQualityInput.text?.toString().orEmpty(),
                frequency() ?: Double.NaN)
            if (converted == null) {
                changingMode = true
                ui.peqQualityMode.check(if (qualityEditor.mode == PeqQualityDisplay.BW) R.id.peq_quality_bw else R.id.peq_quality_q)
                changingMode = false
                ui.peqQualityBox.error = getString(R.string.peq_invalid_quality)
            } else renderQualityMode()
        }
        renderQualityMode()
        updateTypeFields()
        fun editedBand(showErrors: Boolean = true): PeqBand? {
            val freq = frequency()
            val gain = if (selectedType.usesGain) ui.peqGainInput.text?.toString()?.toDoubleOrNull() else band.gain
            val q = inputQ()
            val frequencyError = if (freq == null || !freq.isFinite() || freq !in PeqModel.MIN_FREQUENCY..PeqModel.MAX_FREQUENCY)
                getString(R.string.peq_invalid_frequency) else null
            val gainError = if (gain == null || !gain.isFinite() || gain !in -PeqModel.MAX_GAIN..PeqModel.MAX_GAIN)
                getString(R.string.peq_invalid_gain) else null
            val qualityError = if (q == null || !q.isFinite() || q !in PeqModel.MIN_Q..PeqModel.MAX_Q)
                getString(R.string.peq_invalid_quality) else null
            if (showErrors) {
                ui.peqFrequencyBox.error = frequencyError
                ui.peqGainBox.error = gainError
                ui.peqQualityBox.error = qualityError
            }
            if (frequencyError != null || gainError != null || qualityError != null) return null
            return band.copy(type = selectedType, frequency = requireNotNull(freq), gain = requireNotNull(gain),
                q = requireNotNull(q), qualityDisplay = qualityEditor.mode)
        }
        previewDraft = preview@{
            if (changingMode) return@preview
            val edited = editedBand(showErrors = false) ?: return@preview
            binding?.peqCurve?.setState(editorState.copy(bands = editorState.bands.map {
                if (it.id == id) edited else it
            }), animate = false)
        }
        ui.peqFrequencyInput.doAfterTextChanged { previewDraft() }
        ui.peqGainInput.doAfterTextChanged { previewDraft() }
        ui.peqQualityInput.doAfterTextChanged { previewDraft() }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.peq_edit_band)
            .setView(ui.root).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.peq_delete_band, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val edited = editedBand() ?: return@setOnClickListener
                if (save(editorState.copy(bands = editorState.bands.map { if (it.id == id) edited else it }))) dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = editorState.bands.size > 1
                setOnClickListener {
                    val index = editorState.bands.indexOfFirst { it.id == id }
                    track(MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.peq_delete_band)
                        .setMessage(getString(R.string.peq_delete_confirm, index + 1))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            if (save(editorState.copy(bands = editorState.bands.filterNot { it.id == id }))) dialog.dismiss()
                        }.setNegativeButton(android.R.string.cancel, null).create()).show()
                }
            }
        }
        ui.peqBandCopy.isEnabled = editorState.bands.size < PeqModel.MAX_BANDS
        ui.peqBandCopy.setOnClickListener {
            val edited = editedBand() ?: return@setOnClickListener
            if (editorState.bands.size >= PeqModel.MAX_BANDS) return@setOnClickListener
            val index = editorState.bands.indexOfFirst { it.id == id }
            val copied = editorState.bands.toMutableList().apply { add(index + 1, edited.copy(id = UUID.randomUUID().toString())) }
            if (save(editorState.copy(bands = copied))) dialog.dismiss()
        }
        track(dialog).also {
            it.setOnDismissListener {
                dialogs.remove(it)
                binding?.peqCurve?.setState(editorState, animate = false)
            }
        }.show()
    }

    private fun track(dialog: AlertDialog): AlertDialog {
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        return dialog
    }

    private fun dismissDialogs() { dialogs.toList().forEach { it.dismiss() } }

    override fun onPause() {
        flushPendingSave()
        super.onPause()
    }

    override fun onDestroyView() {
        cancelPendingSave()
        dismissDialogs()
        binding?.peqBandList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
}
