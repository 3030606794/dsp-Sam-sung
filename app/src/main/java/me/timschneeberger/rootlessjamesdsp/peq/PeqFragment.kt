package me.timschneeberger.rootlessjamesdsp.peq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.AppBarLayout
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
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An editor only: saving bands never enables the effect or its global power switch. */
class PeqFragment : Fragment() {
    private var binding: PeqFragmentBinding? = null
    private lateinit var repository: PeqRepository
    private lateinit var bandAdapter: PeqBandAdapter
    var editorState: PeqState = PeqModel.defaultState()
        private set
    private var lastSavedState = editorState
    private var selectedBandId: String? = null
    private var appliedPinResponse: Boolean? = null
    private var trackingSlider = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideMarker = Runnable { binding?.peqCurve?.hideMarker() }
    private var saveQueued = false
    private val pendingSave = Runnable { flushPendingSave() }
    private val dialogs = mutableListOf<AlertDialog>()
    private var presetDialogs: PeqPresetsDialog? = null
    private var pendingExportFileName: String? = null
    private val importPresets = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("无法读取文件")
                    stream.use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= PeqPresetStore.MAX_IMPORT_BYTES) { "文件超过 4 MB" }
                            bytes.write(buffer, 0, count)
                        }
                    }
                    val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                    PeqPresetStore(context).importJson(json).size
                } }
                result.fold(onSuccess = {
                    presetDialogs?.refresh()
                    presetMessage(getString(R.string.peq_presets_imported, it))
                }, onFailure = { presetMessage(it.message ?: getString(R.string.peq_presets_import_failed)) })
            }
        }
    }
    private val exportPresets = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = exportSnapshotFile(pendingExportFileName)
        pendingExportFileName = null
        if (uri == null) snapshot?.delete()
        else {
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    try { runCatching {
                        requireNotNull(snapshot?.takeIf { it.isFile }) { "导出快照已失效，请重新导出" }
                            .inputStream().use { input ->
                                val stream = context.contentResolver.openOutputStream(uri, "wt")
                                    ?: throw IOException("无法写入文件")
                                stream.use { input.copyTo(it) }
                            }
                    } } finally { snapshot?.delete() }
                }
                result.fold(onSuccess = { presetMessage(getString(R.string.peq_presets_exported)) },
                    onFailure = { presetMessage(it.message ?: getString(R.string.peq_presets_export_failed)) })
            }
        }
    }
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
        pendingExportFileName = savedInstanceState?.getString("peq_export_snapshot")
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
        }, onEdit = ::editBand, onInteractionFinished = ::finishInteraction,
            onBandSelected = ::selectBand, onLocate = ::locateBand,
            onInteractionStarted = ::startInteraction)
        ui.peqBandList.layoutManager = LinearLayoutManager(requireContext())
        ui.peqBandList.adapter = bandAdapter
        ui.peqBandList.itemAnimator = null
        ui.peqSettings.setOnClickListener { showSettings() }
        ui.peqReset.setOnClickListener { showResetMenu() }
        ui.peqPresets.setOnClickListener {
            flushPendingSave()
            if (presetDialogs == null) presetDialogs = PeqPresetsDialog(requireContext(), layoutInflater,
                readCurrent = { flushPendingSave(); editorState },
                applyState = { state ->
                    flushPendingSave()
                    binding?.peqBandList?.stopScroll()
                    clearMarker()
                    save(state)
                }, onImportRequested = {
                    importPresets.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }, onExportRequested = ::exportPresetJson)
            presetDialogs?.show()
        }
        ui.peqAdd.setOnClickListener {
            if (editorState.bands.size < PeqModel.MAX_BANDS &&
                save(editorState.copy(bands = editorState.bands + PeqModel.blankBand()))) {
                ui.peqBandList.smoothScrollToPosition(editorState.bands.lastIndex)
            }
        }
        reload()
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (requireActivity() as MenuHost).addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                inflater.inflate(R.menu.peq_editor, menu)
            }
            override fun onPrepareMenu(menu: Menu) {
                menu.findItem(R.id.peq_pin_response)?.apply {
                    isChecked = editorState.pinResponse
                    setTitle(if (isChecked) R.string.peq_unpin_response else R.string.peq_pin_response)
                    setIcon(if (isChecked) R.drawable.ic_peq_pin else R.drawable.ic_peq_pin_off)
                }
            }
            override fun onMenuItemSelected(item: MenuItem): Boolean {
                if (item.itemId != R.id.peq_pin_response) return false
                flushPendingSave()
                if (save(editorState.copy(pinResponse = !editorState.pinResponse))) {
                    (requireActivity() as MenuHost).invalidateMenu()
                }
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        // Another screen or a device preset can replace the saved band bank.
        if (binding != null) reload()
    }

    private fun reload() {
        cancelPendingSave()
        clearMarker()
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
            // Gesture updates must not trail the thumb behind a transition animation.
            peqCurve.setState(editorState, animate && interactingBand == null)
            peqCurve.setSelectedBand(selectedBandId)
            peqSummary.text = getString(R.string.peq_summary, editorState.bands.size,
                PeqBandAdapter.number(editorState.gainStep), PeqBandAdapter.number(editorState.gainRange))
            peqAdd.isEnabled = editorState.bands.size < PeqModel.MAX_BANDS
            if (appliedPinResponse != editorState.pinResponse) {
                val wasApplied = appliedPinResponse != null
                appliedPinResponse = editorState.pinResponse
                (peqResponseHeader.layoutParams as AppBarLayout.LayoutParams).apply {
                    scrollFlags = if (editorState.pinResponse) 0 else AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
                }
                peqResponseHeader.requestLayout()
                peqResponseAppbar.requestLayout()
                if (editorState.pinResponse || wasApplied) peqResponseAppbar.setExpanded(true, false)
                (requireActivity() as MenuHost).invalidateMenu()
            }
        }
    }

    private fun selectBand(id: String) {
        selectedBandId = id
        binding?.peqCurve?.showMarker(id)
        handler.removeCallbacks(hideMarker)
        if (!trackingSlider) handler.postDelayed(hideMarker, 2000)
    }

    private fun startInteraction(id: String) {
        trackingSlider = true
        selectBand(id)
    }

    private fun finishInteraction() {
        flushPendingSave()
        trackingSlider = false
        handler.removeCallbacks(hideMarker)
        handler.postDelayed(hideMarker, 2000)
    }

    private fun clearMarker() {
        trackingSlider = false
        handler.removeCallbacks(hideMarker)
        binding?.peqCurve?.hideMarker()
    }

    private fun locateBand(id: String) {
        binding?.peqBandList?.stopScroll()
        binding?.peqResponseAppbar?.setExpanded(true, false)
        selectBand(id)
    }

    private fun showResetMenu() {
        flushPendingSave()
        val labels = arrayOf(getString(R.string.peq_reset_q), getString(R.string.peq_reset_frequency),
            getString(R.string.peq_reset_gain), getString(R.string.peq_reset_all))
        track(MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.peq_reset_title)
            .setItems(labels) { _, index ->
                if (save(PeqModel.reset(editorState, PeqResetTarget.entries[index]))) clearMarker()
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("peq_selected_band", selectedBandId)
        outState.putString("peq_export_snapshot", pendingExportFileName)
        super.onSaveInstanceState(outState)
    }

    private fun presetMessage(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
    }

    private fun exportSnapshotFile(name: String?): File? = name?.takeIf {
        it.matches(Regex("peq-export-[a-f0-9-]+\\.json"))
    }?.let { File(requireContext().cacheDir, it) }

    private fun exportPresetJson(filename: String, json: String) {
        // Keep the snapshot out of savedInstanceState: 20 full banks can exceed Binder's limit.
        val snapshot = File(requireContext().cacheDir, "peq-export-${UUID.randomUUID()}.json")
        try {
            snapshot.writeText(json, Charsets.UTF_8)
            pendingExportFileName = snapshot.name
            exportPresets.launch(filename.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        } catch (failure: Exception) {
            snapshot.delete()
            pendingExportFileName = null
            presetMessage(failure.message ?: getString(R.string.peq_presets_export_failed))
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
                fun applySettings(edge: PeqResizeEdge = PeqResizeEdge.BACK, redistribute: Boolean = false) {
                    if (save(PeqModel.resize(editorState, count, edge, redistribute).copy(gainStep = step, gainRange = range,
                        qStep = qStep, frequencyStep = frequencyStep,
                        showQSlider = ui.peqShowQSlider.isChecked,
                        showFrequencySlider = ui.peqShowFrequencySlider.isChecked))) dialog.dismiss()
                }
                if (count != editorState.bands.size) {
                    track(PeqResizeDialog.create(requireContext(), layoutInflater, editorState.bands.size, count) { edge, redistribute ->
                        applySettings(edge, redistribute)
                    }).show()
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
        val gainLimit = PeqBandAdapter.number(editorState.gainRange)
        ui.peqGainBox.hint = getString(R.string.peq_gain_limit, gainLimit)
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
            val frequencyError = if (freq == null || !PeqModel.validFrequency(freq))
                getString(R.string.peq_invalid_frequency) else null
            val gainError = if (selectedType.usesGain &&
                (gain == null || !gain.isFinite() || gain !in -editorState.gainRange..editorState.gainRange))
                getString(R.string.peq_invalid_gain, gainLimit) else null
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
            selectBand(id)
        }
        ui.peqFrequencyInput.doAfterTextChanged { previewDraft() }
        ui.peqGainInput.doAfterTextChanged { previewDraft() }
        ui.peqQualityInput.doAfterTextChanged { previewDraft() }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.peq_edit_band)
            .setView(ui.root).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.peq_delete_band, null).create()
        var confirmed = false
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val edited = editedBand() ?: return@setOnClickListener
                if (save(editorState.copy(bands = editorState.bands.map { if (it.id == id) edited else it }))) {
                    confirmed = true
                    dialog.dismiss()
                }
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
                if (confirmed) selectBand(id) else clearMarker()
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
        clearMarker()
        super.onPause()
    }

    override fun onDestroyView() {
        cancelPendingSave()
        dismissDialogs()
        presetDialogs?.dismissAll()
        presetDialogs = null
        clearMarker()
        appliedPinResponse = null
        binding?.peqBandList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
}
