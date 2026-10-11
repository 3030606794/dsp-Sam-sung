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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.GraphicEqualizerActivity
import me.timschneeberger.rootlessjamesdsp.databinding.PeqBandDialogBinding
import me.timschneeberger.rootlessjamesdsp.databinding.PeqFragmentBinding
import me.timschneeberger.rootlessjamesdsp.databinding.PeqSettingsDialogBinding
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.UUID

/** An editor only: saving bands does not enable the effect or replace the selected legacy mode. */
class PeqFragment : Fragment() {
    private var binding: PeqFragmentBinding? = null
    private lateinit var repository: PeqRepository
    private lateinit var bandAdapter: PeqBandAdapter
    var editorState: PeqState = PeqModel.defaultState()
        private set
    private var lastSavedState = editorState
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
        requireContext().registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ui = PeqFragmentBinding.inflate(inflater, container, false)
        binding = ui
        bandAdapter = PeqBandAdapter(onGainChanged = { id, gain ->
            val next = editorState.copy(bands = editorState.bands.map { if (it.id == id) it.copy(gain = gain) else it })
            editorState = next
            render(interactingBand = id)
            if (!saveQueued) {
                saveQueued = true
                handler.postDelayed(pendingSave, 50)
            }
            true
        }, onEdit = ::editBand, onInteractionFinished = ::flushPendingSave)
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
        ui.peqLegacy.setOnClickListener {
            startActivity(Intent(requireContext(), GraphicEqualizerActivity::class.java)
                .putExtra("legacy_curve", true))
        }
        ui.peqActivate.setOnClickListener {
            PeqController.useParametric(requireContext())
            refreshMode()
        }
        reload()
        return ui.root
    }

    override fun onResume() {
        super.onResume()
        // Returning from the legacy/AutoEQ editor can change the selected mode or its curve.
        if (binding != null) reload()
    }

    private fun reload() {
        cancelPendingSave()
        repository = PeqRepository(requireContext())
        val loaded = repository.loadResult()
        editorState = loaded.state
        lastSavedState = editorState
        render(false)
        refreshMode()
        if (loaded.corrupt) binding?.root?.post {
            binding?.root?.let { Snackbar.make(it, R.string.peq_corrupt_profile, Snackbar.LENGTH_LONG).show() }
        }
    }

    private fun refreshMode() {
        binding?.apply {
            peqLegacy.setText(if (PeqController.hasLegacyCurve(requireContext()))
                R.string.peq_legacy_curve else R.string.peq_curve_autoeq)
            peqModeNotice.isVisible = !PeqController.isParametric(requireContext())
        }
    }

    private fun render(animate: Boolean = true, interactingBand: String? = null) {
        binding?.apply {
            bandAdapter.submitState(editorState, interactingBand)
            peqCurve.setState(editorState, animate)
            peqSummary.text = getString(R.string.peq_summary, editorState.bands.size,
                PeqBandAdapter.number(editorState.gainStep), PeqBandAdapter.number(editorState.gainRange))
            peqAdd.isEnabled = editorState.bands.size < PeqModel.MAX_BANDS
        }
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
        ui.peqBandCount.setText(editorState.bands.size.toString())
        val steps = PeqModel.GAIN_STEPS.map { "${PeqBandAdapter.number(it)} dB" }
        val ranges = PeqModel.GAIN_RANGES.map { "±${PeqBandAdapter.number(it)} dB" }
        ui.peqGainStep.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, steps))
        ui.peqGainRange.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, ranges))
        ui.peqGainStep.setText(steps[PeqModel.GAIN_STEPS.indexOf(editorState.gainStep)], false)
        ui.peqGainRange.setText(ranges[PeqModel.GAIN_RANGES.indexOf(editorState.gainRange)], false)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.peq_settings_title).setView(ui.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val count = ui.peqBandCount.text?.toString()?.toIntOrNull()
                if (count == null || count !in 1..PeqModel.MAX_BANDS) {
                    ui.peqCountBox.error = getString(R.string.peq_invalid_count)
                    return@setOnClickListener
                }
                val step = PeqModel.GAIN_STEPS.getOrNull(steps.indexOf(ui.peqGainStep.text.toString())) ?: return@setOnClickListener
                val range = PeqModel.GAIN_RANGES.getOrNull(ranges.indexOf(ui.peqGainRange.text.toString())) ?: return@setOnClickListener
                fun applySettings() {
                    if (save(PeqModel.resize(editorState, count).copy(gainStep = step, gainRange = range))) dialog.dismiss()
                }
                if (count < editorState.bands.size) {
                    track(MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.peq_reduce_title)
                        .setMessage(getString(R.string.peq_reduce_confirm, editorState.bands.size, count, editorState.bands.size - count))
                        .setPositiveButton(android.R.string.ok) { _, _ -> applySettings() }
                        .setNegativeButton(android.R.string.cancel, null).create()).show()
                } else applySettings()
            }
        }
        track(dialog).show()
    }

    private fun editBand(id: String) {
        flushPendingSave()
        val band = editorState.bands.firstOrNull { it.id == id } ?: return
        val ui = PeqBandDialogBinding.inflate(layoutInflater)
        val types = PeqFilterType.entries.toList()
        val labels = types.map { PeqBandAdapter.typeLabel(requireContext(), it) }
        var selectedType = band.type
        var bandwidthMode = false
        var changingMode = false
        ui.peqBandType.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels))
        ui.peqBandType.setText(PeqBandAdapter.typeLabel(requireContext(), band.type), false)
        ui.peqFrequencyInput.setText(PeqBandAdapter.number(band.frequency, 4))
        ui.peqGainInput.setText(PeqBandAdapter.number(band.gain, 4))
        ui.peqQualityInput.setText(PeqBandAdapter.number(band.q, 6))
        fun frequency() = ui.peqFrequencyInput.text?.toString()?.toDoubleOrNull()
        fun inputQ(): Double? {
            val raw = ui.peqQualityInput.text?.toString()?.toDoubleOrNull() ?: return null
            return if (!bandwidthMode) raw else runCatching {
                PeqResponse.qFromBandwidth(raw, frequency() ?: return null)
            }.getOrNull()
        }
        fun supportsBandwidth(type: PeqFilterType) = type in listOf(PeqFilterType.PEAK, PeqFilterType.NOTCH, PeqFilterType.BAND_PASS)
        fun updateTypeFields() {
            ui.peqGainBox.isVisible = selectedType.usesGain
            ui.peqQualityBw.isVisible = supportsBandwidth(selectedType)
        }
        ui.peqBandType.setOnItemClickListener { _, _, position, _ ->
            val previousQ = inputQ()
            selectedType = types[position]
            if (!supportsBandwidth(selectedType) && bandwidthMode) {
                changingMode = true
                bandwidthMode = false
                ui.peqQualityMode.check(R.id.peq_quality_q)
                ui.peqQualityInput.setText(PeqBandAdapter.number(previousQ ?: band.q, 6))
                ui.peqQualityBox.hint = getString(R.string.peq_quality)
                ui.peqQualityBox.helperText = getString(R.string.peq_quality_hint)
                changingMode = false
            }
            updateTypeFields()
        }
        ui.peqQualityMode.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked || changingMode) return@addOnButtonCheckedListener
            val q = inputQ()
            val useBandwidth = checkedId == R.id.peq_quality_bw
            val converted = runCatching {
                if (q == null || q !in PeqModel.MIN_Q..PeqModel.MAX_Q) error("Invalid Q")
                if (useBandwidth) PeqResponse.bandwidthOctaves(q, frequency() ?: error("Invalid frequency")) else q
            }.getOrNull()
            if (converted == null) {
                changingMode = true
                ui.peqQualityMode.check(if (bandwidthMode) R.id.peq_quality_bw else R.id.peq_quality_q)
                changingMode = false
                ui.peqQualityBox.error = getString(R.string.peq_invalid_quality)
            } else {
                bandwidthMode = useBandwidth
                ui.peqQualityInput.setText(PeqBandAdapter.number(converted, 6))
                ui.peqQualityBox.hint = getString(if (useBandwidth) R.string.peq_bandwidth else R.string.peq_quality)
                ui.peqQualityBox.helperText = getString(if (useBandwidth) R.string.peq_bandwidth_hint else R.string.peq_quality_hint)
                ui.peqQualityBox.error = null
            }
        }
        updateTypeFields()
        fun editedBand(): PeqBand? {
            val freq = frequency()
            val gain = if (selectedType.usesGain) ui.peqGainInput.text?.toString()?.toDoubleOrNull() else band.gain
            val q = inputQ()
            ui.peqFrequencyBox.error = if (freq == null || !freq.isFinite() || freq !in PeqModel.MIN_FREQUENCY..PeqModel.MAX_FREQUENCY)
                getString(R.string.peq_invalid_frequency) else null
            ui.peqGainBox.error = if (gain == null || !gain.isFinite() || gain !in -PeqModel.MAX_GAIN..PeqModel.MAX_GAIN)
                getString(R.string.peq_invalid_gain) else null
            ui.peqQualityBox.error = if (q == null || !q.isFinite() || q !in PeqModel.MIN_Q..PeqModel.MAX_Q)
                getString(R.string.peq_invalid_quality) else null
            if (ui.peqFrequencyBox.error != null || ui.peqGainBox.error != null || ui.peqQualityBox.error != null) return null
            return band.copy(type = selectedType, frequency = requireNotNull(freq), gain = requireNotNull(gain), q = requireNotNull(q))
        }
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
        track(dialog).show()
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
