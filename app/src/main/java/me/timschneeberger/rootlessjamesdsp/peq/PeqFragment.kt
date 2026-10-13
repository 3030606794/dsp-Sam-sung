package me.timschneeberger.rootlessjamesdsp.peq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PeqBandDialogBinding
import me.timschneeberger.rootlessjamesdsp.databinding.PeqAddCountDialogBinding
import me.timschneeberger.rootlessjamesdsp.databinding.PeqBulkQualityDialogBinding
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

/** Parameter edits never enable PEQ; only the explicit power button changes its switch. */
class PeqFragment : Fragment() {
    private var binding: PeqFragmentBinding? = null
    private lateinit var repository: PeqRepository
    private lateinit var bandAdapter: PeqBandAdapter
    private var bandTouchHelper: ItemTouchHelper? = null
    var editorState: PeqState = PeqModel.defaultState()
        private set
    private var lastSavedState = editorState
    private var selectedBandId: String? = null
    private var editingSelection = false
    private var selectedIds: Set<String> = emptySet()
    private var selectionScope = PeqSelectionScope.SELECTED
    private var selectionBackCallback: OnBackPressedCallback? = null
    private val actionTextColors = mutableMapOf<Int, ColorStateList>()
    private var appliedPinResponse: Boolean? = null
    private var trackingSlider = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideMarker = Runnable { binding?.peqCurve?.hideMarker() }
    private var saveQueued = false
    private val pendingSave = Runnable { flushPendingSave() }
    private val dialogs = mutableListOf<AlertDialog>()
    private var presetDialogs: PeqPresetsDialog? = null
    private var powerPreferences: SharedPreferences? = null
    private val powerListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == getString(R.string.key_geq_enable)) renderPower()
    }
    private var stopFilterPreview: (() -> Unit)? = null
    private val addButtonGestures = mutableListOf<PeqAddButtonGestures>()
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
        editingSelection = savedInstanceState?.getBoolean("peq_editing_selection") ?: false
        selectedIds = savedInstanceState?.getStringArrayList("peq_selection_ids")?.toSet().orEmpty()
        selectionScope = if (savedInstanceState?.getBoolean("peq_exclude_selection") == true)
            PeqSelectionScope.EXCLUDED else PeqSelectionScope.SELECTED
        pendingExportFileName = savedInstanceState?.getString("peq_export_snapshot")
        requireContext().registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        powerPreferences = requireContext().getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(powerListener)
        }
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
        }, onEdit = { editBand(it) }, onInteractionFinished = ::finishInteraction,
            onBandSelected = ::selectBand, onLocate = ::locateBand,
            onInteractionStarted = ::startInteraction, onEditField = { id, field -> editBand(id, field) },
            onEditType = ::showBandFilterType, onToggleSelection = ::toggleSelection,
            onStartDrag = { holder ->
                if (!editingSelection) {
                    flushPendingSave()
                    bandTouchHelper?.startDrag(holder)
                }
            })
        ui.peqBandList.layoutManager = LinearLayoutManager(requireContext())
        ui.peqBandList.adapter = bandAdapter
        ui.peqBandList.itemAnimator = null
        bandTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = false
            override fun getMovementFlags(list: RecyclerView, holder: RecyclerView.ViewHolder): Int =
                if (editingSelection || editorState.bands.size < 2) 0
                else makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            override fun onMove(list: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder) =
                moveBand(from.bindingAdapterPosition, to.bindingAdapterPosition)
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun clearView(list: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(list, holder)
                flushPendingSave()
                render(false)
            }
        }).also { it.attachToRecyclerView(ui.peqBandList) }
        ui.peqSettings.setOnClickListener { showSettings() }
        ui.peqPower.setOnClickListener {
            flushPendingSave()
            val prefs = powerPreferences ?: return@setOnClickListener
            val key = getString(R.string.key_geq_enable)
            if (prefs.edit().putBoolean(key, !prefs.getBoolean(key, false)).commit()) {
                renderPower()
                notifyAudioAndPreview()
            } else toast(getString(R.string.peq_save_failed))
        }
        ui.peqReset.setOnClickListener { showResetMenu() }
        ui.peqParameters.setOnClickListener { showParametersMenu() }
        listOf(ui.peqReset, ui.peqPresets, ui.peqParameters, ui.peqEditSelection).forEach {
            actionTextColors[it.id] = it.textColors
        }
        ui.peqEditSelection.setOnClickListener {
            flushPendingSave()
            if (editingSelection) deleteSelection()
            else { editingSelection = true; renderSelection() }
        }
        ui.peqSelectionDone.setOnClickListener { finishSelection() }
        ui.peqSelectionMode.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                selectionScope = if (id == R.id.peq_scope_excluded) PeqSelectionScope.EXCLUDED else PeqSelectionScope.SELECTED
                renderSelection()
            }
        }
        ui.peqSelectionAll.setOnClickListener {
            selectedIds = editorState.bands.map { it.id }.toSet()
            renderSelection()
            toast(getString(R.string.peq_selection_all_hint, selectedIds.size))
        }
        ui.peqSelectionNone.setOnClickListener { selectedIds = emptySet(); renderSelection() }
        ui.peqPresets.setOnClickListener {
            flushPendingSave()
            if (presetDialogs == null) presetDialogs = PeqPresetsDialog(requireContext(), layoutInflater,
                readCurrent = {
                    flushPendingSave()
                    if (editingSelection) {
                        val ids = operationIds()
                        require(ids.isNotEmpty()) { getString(R.string.peq_selection_empty) }
                        PeqSelection.snapshot(editorState, ids)
                    } else editorState
                },
                applyState = { state ->
                    flushPendingSave()
                    binding?.peqBandList?.stopScroll()
                    clearMarker()
                    save(state)
                }, onImportRequested = {
                    importPresets.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }, onExportRequested = ::exportPresetJson,
                currentScopeLabel = { if (editingSelection) scopeLabel(operationIds()) else null },
                canSaveCurrent = { !editingSelection || operationIds().isNotEmpty() })
            presetDialogs?.show()
        }
        listOf(ui.peqAddFront to PeqResizeEdge.FRONT, ui.peqAddBack to PeqResizeEdge.BACK).forEach { (button, edge) ->
            addButtonGestures += PeqAddButtonGestures(button,
                onSingle = { addBands(edge, 1) }, onDouble = { jumpToEdge(edge) },
                onLong = {
                    toast(getString(R.string.peq_add_choose_amount))
                    showAddMenu(edge)
                })
        }
        reload()
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectionBackCallback = object : OnBackPressedCallback(editingSelection) {
            override fun handleOnBackPressed() = finishSelection()
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
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
        if (loaded.state != editorState) {
            // Dialogs capture a set of stable ids. A replacement bank invalidates that draft.
            dismissDialogs()
            presetDialogs?.dismissAll()
        }
        editorState = loaded.state
        lastSavedState = editorState
        render(false)
        if (loaded.corrupt) binding?.root?.post {
            binding?.root?.let { Snackbar.make(it, R.string.peq_corrupt_profile, Snackbar.LENGTH_LONG).show() }
        }
    }

    private fun render(animate: Boolean = true, interactingBand: String? = null) {
        renderPower()
        binding?.apply {
            if (editorState.bands.none { it.id == selectedBandId }) selectedBandId = editorState.bands.firstOrNull()?.id
            bandAdapter.submitState(editorState, interactingBand)
            renderSelection()
            // Gesture updates must not trail the thumb behind a transition animation.
            peqCurve.setState(editorState, animate && interactingBand == null)
            peqCurve.setSelectedBand(selectedBandId)
            peqSummary.text = getString(R.string.peq_summary, editorState.bands.size,
                PeqBandAdapter.number(editorState.gainStep), PeqBandAdapter.number(editorState.gainRange))
            // Full banks still need the double-tap navigation shortcut.
            peqAddFront.isEnabled = true
            peqAddBack.isEnabled = true
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

    private fun renderPower() {
        binding?.peqPower?.apply {
            isSelected = powerPreferences?.getBoolean(getString(R.string.key_geq_enable), false) == true
            contentDescription = getString(if (isSelected) R.string.peq_power_on else R.string.peq_power_off)
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

    private fun toggleSelection(id: String) {
        if (!editingSelection || editorState.bands.none { it.id == id }) return
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        renderSelection()
    }

    /** Move intact bands by stable identity; persistence is flushed when the drag ends. */
    internal fun moveBand(from: Int, to: Int): Boolean {
        if (editingSelection || from !in editorState.bands.indices || to !in editorState.bands.indices || from == to)
            return false
        if (PeqBandLockStore(requireContext()).isBandLocked(editorState.bands[from].id)) return false
        cancelPendingSave()
        editorState = PeqBandOrder.move(editorState, from, to)
        saveQueued = true
        bandAdapter.submitReorderedState(editorState, from, to)
        return true
    }

    private fun finishSelection() {
        editingSelection = false
        selectedIds = emptySet()
        renderSelection()
    }

    private fun operationIds(): Set<String> = if (editingSelection)
        PeqSelection.targets(editorState, selectedIds, selectionScope) else editorState.bands.map { it.id }.toSet()

    private fun requireOperationIds(): Set<String>? = operationIds().takeIf { it.isNotEmpty() }
        ?: run { presetMessage(getString(R.string.peq_selection_empty)); null }

    private fun scopeLabel(ids: Set<String>): String = getString(
        if (!editingSelection) R.string.peq_scope_all_count
        else if (selectionScope == PeqSelectionScope.SELECTED) R.string.peq_scope_selected_count
        else R.string.peq_scope_excluded_count, ids.size)

    private fun scopedTitle(title: Int, ids: Set<String>): String =
        if (editingSelection) "${getString(title)} · ${scopeLabel(ids)}" else getString(title)

    private fun renderSelection() {
        selectedIds = PeqSelection.prune(editorState, selectedIds)
        selectionBackCallback?.isEnabled = editingSelection
        binding?.apply {
            val hasTargets = operationIds().isNotEmpty()
            bandAdapter.submitSelection(editingSelection, selectedIds)
            peqSelectionControls.isVisible = editingSelection
            peqEditSelection.setText(if (editingSelection) R.string.peq_selection_delete else R.string.peq_edit_selection)
            peqEditSelection.isEnabled = !editingSelection || hasTargets
            peqSettings.isEnabled = !editingSelection
            peqReset.isEnabled = hasTargets
            peqParameters.isEnabled = hasTargets
            peqPresets.isEnabled = !editingSelection || hasTargets
            listOf(peqReset, peqPresets, peqParameters).forEach { button ->
                button.setTextColor(if (editingSelection && hasTargets) ColorStateList.valueOf(
                    MaterialColors.getColor(button, com.google.android.material.R.attr.colorTertiary))
                    else requireNotNull(actionTextColors[button.id]))
            }
            peqEditSelection.setTextColor(if (editingSelection && hasTargets) ColorStateList.valueOf(
                MaterialColors.getColor(peqEditSelection, com.google.android.material.R.attr.colorError))
                else requireNotNull(actionTextColors[peqEditSelection.id]))
            peqSelectionCount.text = getString(R.string.peq_selection_count, selectedIds.size, editorState.bands.size)
            val checked = if (selectionScope == PeqSelectionScope.EXCLUDED) R.id.peq_scope_excluded else R.id.peq_scope_selected
            if (peqSelectionMode.checkedButtonId != checked) peqSelectionMode.check(checked)
        }
    }

    private fun deleteSelection() {
        flushPendingSave()
        val ids = requireOperationIds() ?: return
        if (ids.size >= editorState.bands.size) {
            toast(getString(R.string.peq_delete_last_band))
            return
        }
        track(MaterialAlertDialogBuilder(requireContext())
            .setTitle(scopedTitle(R.string.peq_selection_delete, ids))
            .setMessage(getString(R.string.peq_selection_delete_confirm, ids.size, editorState.bands.size - ids.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (save(PeqSelection.delete(editorState, ids))) clearMarker()
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun showResetMenu() {
        flushPendingSave()
        val ids = requireOperationIds() ?: return
        val labels = arrayOf(getString(R.string.peq_reset_q), getString(R.string.peq_reset_frequency),
            getString(R.string.peq_reset_gain), getString(R.string.peq_reset_filter_type), getString(R.string.peq_reset_all),
            getString(R.string.peq_redistribute))
        val targets = listOf(PeqResetTarget.Q, PeqResetTarget.FREQUENCY, PeqResetTarget.GAIN,
            PeqResetTarget.FILTER_TYPE, PeqResetTarget.ALL)
        track(MaterialAlertDialogBuilder(requireContext()).setTitle(scopedTitle(
            if (editingSelection) R.string.peq_selection_reset else R.string.peq_reset_title, ids))
            .setItems(labels) { _, index ->
                if (index == targets.size) showRedistribute(ids)
                else if (save(PeqSelection.reset(editorState, ids, targets[index]))) clearMarker()
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun showRedistribute(ids: Set<String>) {
        flushPendingSave()
        track(MaterialAlertDialogBuilder(requireContext())
            .setTitle(scopedTitle(R.string.peq_redistribute_title, ids))
            .setMessage(getString(R.string.peq_redistribute_confirm, ids.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (save(PeqSelection.redistribute(editorState, ids))) clearMarker()
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun showParametersMenu() {
        flushPendingSave()
        val ids = requireOperationIds() ?: return
        val labels = arrayOf(getString(R.string.peq_bulk_q), getString(R.string.peq_bulk_bw),
            getString(R.string.peq_filter_type))
        track(MaterialAlertDialogBuilder(requireContext()).setTitle(scopedTitle(R.string.peq_parameters, ids))
            .setItems(labels) { _, index ->
                when (index) {
                    0 -> showBulkQuality(PeqQualityDisplay.Q, ids)
                    1 -> showBulkQuality(PeqQualityDisplay.BW, ids)
                    2 -> showFilterTypePicker(null, ids)
                }
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun showBulkQuality(mode: PeqQualityDisplay, ids: Set<String>) {
        flushPendingSave()
        val targetState = PeqSelection.snapshot(editorState, ids)
        val rawRange = if (mode == PeqQualityDisplay.Q) PeqModel.MIN_Q..editorState.qRange
            else PeqBulkParameters.bandwidthRange(targetState)
        // Show editable bounds rounded inward, so the displayed limits are themselves valid inputs.
        val minimum = kotlin.math.ceil(rawRange.start * 1000.0 - 1e-9) / 1000.0
        val maximum = kotlin.math.floor(rawRange.endInclusive * 1000.0 + 1e-9) / 1000.0
        if (minimum > maximum) {
            track(MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.peq_bulk_bw)
                .setMessage(R.string.peq_bulk_bw_no_range).setPositiveButton(android.R.string.ok, null).create()).show()
            return
        }
        val ui = PeqBulkQualityDialogBinding.inflate(layoutInflater)
        val name = getString(if (mode == PeqQualityDisplay.Q) R.string.peq_bulk_q else R.string.peq_bulk_bw)
        val low = PeqQualityEditor.format(minimum)
        val high = PeqQualityEditor.format(maximum)
        ui.root.hint = name
        ui.root.helperText = getString(if (editingSelection) R.string.peq_selection_quality_range
            else R.string.peq_bulk_quality_range, low, high, ids.size)
        val first = targetState.bands.first()
        val current = if (mode == PeqQualityDisplay.Q) first.q else PeqResponse.bandwidthOctaves(first.q, first.frequency)
        ui.peqBulkQualityInput.setText(PeqQualityEditor.format(current.coerceIn(minimum, maximum)))
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (editingSelection) "$name · ${scopeLabel(ids)}"
                else getString(R.string.peq_bulk_quality_title, name)).setView(ui.root)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            ui.peqBulkQualityInput.requestFocus()
            ui.peqBulkQualityInput.selectAll()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = ui.peqBulkQualityInput.text?.toString()?.toDoubleOrNull()
                val next = value?.let { requested -> runCatching {
                    PeqSelection.update(editorState, ids) { PeqBulkParameters.quality(it, mode, requested) }
                }.getOrNull() }
                if (next == null) ui.root.error = getString(R.string.peq_bulk_quality_error, low, high)
                else if (save(next)) { clearMarker(); dialog.dismiss() }
            }
        }
        track(dialog).show()
    }

    private fun showBandFilterType(id: String) = showFilterTypePicker(id)

    private fun showFilterTypePicker(bandId: String?, scopeIds: Set<String>? = null) {
        if (bandId != null && (editingSelection || !PeqBandLockStore(requireContext()).canEdit(bandId))) return
        flushPendingSave()
        stopFilterPreview?.invoke()
        val ids = bandId?.let { setOf(it) } ?: scopeIds ?: requireOperationIds() ?: return
        val types = PeqFilterType.entries
        val band = bandId?.let { id -> editorState.bands.firstOrNull { it.id == id } ?: return }
        var selected = band?.type ?: editorState.bands.filter { it.id in ids }.map { it.type }.distinct().singleOrNull()
        var previewToken: PeqPreviewSession.Token? = null
        var listening = false
        fun draft(): PeqState = selected?.let { type ->
            PeqSelection.update(editorState, ids) { PeqBulkParameters.filterType(it, type) }
        } ?: editorState
        fun updatePreview() {
            if (!listening) return
            val next = draft()
            val token = previewToken
            if (token == null) previewToken = PeqPreviewSession.begin(next)
            else PeqPreviewSession.update(token, next)
            binding?.peqCurve?.setState(next, animate = false)
            notifyAudioAndPreview()
        }
        fun clearPreview() {
            listening = false
            previewToken?.let { if (PeqPreviewSession.end(it)) notifyAudioAndPreview() }
            previewToken = null
            binding?.peqCurve?.setState(editorState, animate = false)
        }
        val labels = types.map { PeqBandAdapter.typeLabel(requireContext(), it) }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (bandId == null) scopedTitle(if (editingSelection) R.string.peq_filter_type
                else R.string.peq_bulk_filter_title, ids) else getString(R.string.peq_filter_type))
            .setSingleChoiceItems(labels, selected?.ordinal ?: -1) { _, index ->
                selected = types[index]
                updatePreview()
            }
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.peq_live_monitor_off, null).create()
        fun renderMonitor() {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setText(if (listening) R.string.peq_live_monitor_on else R.string.peq_live_monitor_off)
                isSelected = listening
                setTextColor(if (listening) android.graphics.Color.rgb(46, 173, 85)
                    else MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
        }
        val stop: () -> Unit = { clearPreview(); renderMonitor() }
        stopFilterPreview = stop
        dialog.setOnShowListener {
            renderMonitor()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (listening) clearPreview() else { listening = true; updatePreview() }
                renderMonitor()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val type = selected ?: return@setOnClickListener
                val next = PeqSelection.update(editorState, ids) { PeqBulkParameters.filterType(it, type) }
                if (save(next)) {
                    if (bandId == null) clearMarker() else selectBand(bandId)
                    dialog.dismiss()
                }
            }
        }
        track(dialog).also {
            it.setOnDismissListener {
                dialogs.remove(it)
                clearPreview()
                if (stopFilterPreview === stop) stopFilterPreview = null
            }
        }.show()
    }

    private fun addBands(edge: PeqResizeEdge, count: Int): Boolean {
        flushPendingSave()
        val available = PeqModel.MAX_BANDS - editorState.bands.size
        if (count !in 1..available) {
            presetMessage(getString(R.string.peq_add_too_many, available))
            return false
        }
        val oldCount = editorState.bands.size
        if (!save(PeqModel.resize(editorState, oldCount + count, edge, redistribute = false))) return false
        binding?.peqBandList?.scrollToPosition(if (edge == PeqResizeEdge.FRONT) 0 else oldCount)
        toast(getString(if (edge == PeqResizeEdge.FRONT) R.string.peq_added_front else R.string.peq_added_back,
            count, editorState.bands.size))
        return true
    }

    private fun toast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    private fun jumpToEdge(edge: PeqResizeEdge) {
        val ui = binding ?: return
        if (editorState.bands.isEmpty()) { toast(getString(R.string.peq_jump_empty)); return }
        ui.peqBandList.stopScroll()
        if (edge == PeqResizeEdge.FRONT) ui.peqResponseAppbar.setExpanded(true, false)
        else if (!editorState.pinResponse) ui.peqResponseAppbar.setExpanded(false, false)
        ui.peqBandList.scrollToPosition(if (edge == PeqResizeEdge.FRONT) 0 else editorState.bands.lastIndex)
        toast(getString(if (edge == PeqResizeEdge.FRONT) R.string.peq_jump_front else R.string.peq_jump_back,
            editorState.bands.size))
    }

    private fun showAddMenu(edge: PeqResizeEdge) {
        if (editorState.bands.size >= PeqModel.MAX_BANDS) {
            presetMessage(getString(R.string.peq_add_too_many, 0))
            return
        }
        val counts = listOf(5, 10, 15, 20)
        val labels = counts.map { getString(R.string.peq_add_amount, it) } + getString(R.string.peq_add_custom)
        track(MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (edge == PeqResizeEdge.FRONT) R.string.peq_add_front_title else R.string.peq_add_back_title)
            .setItems(labels.toTypedArray()) { _, index ->
                if (index < counts.size) addBands(edge, counts[index]) else showCustomAdd(edge)
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun showCustomAdd(edge: PeqResizeEdge) {
        val ui = PeqAddCountDialogBinding.inflate(layoutInflater)
        val available = PeqModel.MAX_BANDS - editorState.bands.size
        ui.root.hint = getString(R.string.peq_add_available, available)
        ui.peqAddCount.setText("1")
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.peq_add_custom)
            .setView(ui.root).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            ui.peqAddCount.requestFocus()
            ui.peqAddCount.selectAll()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val count = ui.peqAddCount.text?.toString()?.toIntOrNull()
                if (count == null || count !in 1..available) {
                    ui.root.error = getString(R.string.peq_add_available, available)
                } else if (addBands(edge, count)) dialog.dismiss()
            }
        }
        track(dialog).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("peq_selected_band", selectedBandId)
        outState.putString("peq_export_snapshot", pendingExportFileName)
        outState.putBoolean("peq_editing_selection", editingSelection)
        outState.putStringArrayList("peq_selection_ids", ArrayList(selectedIds))
        outState.putBoolean("peq_exclude_selection", selectionScope == PeqSelectionScope.EXCLUDED)
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
        val qRangeControl = PeqSettingControl(ui.peqQRangeSlider, ui.peqQRange, ui.peqQRangeBox,
            PeqModel.MIN_Q, PeqModel.MAX_Q, 0.001, editorState.qRange, logarithmic = true)
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
                val qRange = qRangeControl.validatedValue() ?: return@setOnClickListener
                val frequencyStep = frequencyStepControl.validatedValue() ?: return@setOnClickListener
                fun applySettings(edge: PeqResizeEdge = PeqResizeEdge.BACK, redistribute: Boolean = false) {
                    if (save(PeqModel.resize(editorState, count, edge, redistribute).copy(gainStep = step, gainRange = range,
                        qStep = qStep, qRange = qRange, frequencyStep = frequencyStep,
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
        track(dialog).show()
    }

    private fun editBand(id: String, focusField: PeqEditField? = null) {
        val locks = PeqBandLockStore(requireContext())
        if (editingSelection || !locks.canEdit(id, focusField)) return
        flushPendingSave()
        selectBand(id)
        val band = editorState.bands.firstOrNull { it.id == id } ?: return
        val ui = PeqBandDialogBinding.inflate(layoutInflater)
        val types = PeqFilterType.entries.toList()
        val labels = types.map { PeqBandAdapter.typeLabel(requireContext(), it) }
        var selectedType = band.type
        var changingMode = false
        var previewDraft: () -> Unit = {}
        val frequencyText = PeqBandAdapter.number(band.frequency, 4)
        val qualityEditor = PeqQualityEditor(band.q, frequencyText.toDouble(), band.qualityDisplay)
        ui.peqBandType.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels))
        ui.peqBandType.setText(PeqBandAdapter.typeLabel(requireContext(), band.type), false)
        ui.peqFrequencyInput.setText(frequencyText)
        ui.peqGainInput.setText(PeqBandAdapter.number(band.gain, 4))
        val frequencyLocked = locks.isFieldLocked(id, PeqEditField.FREQUENCY)
        val gainLocked = locks.isFieldLocked(id, PeqEditField.GAIN)
        val qualityLocked = locks.isFieldLocked(id, PeqEditField.QUALITY)
        ui.peqFrequencyBox.isEnabled = !frequencyLocked
        ui.peqGainBox.isEnabled = !gainLocked
        ui.peqQualityBox.isEnabled = !qualityLocked
        ui.peqQualityQ.isEnabled = !qualityLocked
        ui.peqQualityBw.isEnabled = !qualityLocked
        val gainLimit = PeqBandAdapter.number(editorState.gainRange)
        val qLimit = PeqBandAdapter.number(editorState.qRange, 3)
        ui.peqGainBox.hint = getString(R.string.peq_gain_limit, gainLimit)
        fun frequency() = ui.peqFrequencyInput.text?.toString()?.toDoubleOrNull()
        fun inputQ(): Double? {
            val freq = frequency() ?: return null
            return qualityEditor.readQ(ui.peqQualityInput.text?.toString().orEmpty(), freq, editorState.qRange)
        }
        fun qualityError() = getString(R.string.peq_invalid_quality_range, qLimit)
        fun updateQualityRange() {
            ui.peqQualityBox.helperText = if (qualityEditor.mode == PeqQualityDisplay.BW) {
                val freq = frequency()?.takeIf { PeqModel.validFrequency(it) } ?: band.frequency
                getString(R.string.peq_bw_range,
                    PeqQualityEditor.format(PeqResponse.bandwidthOctaves(editorState.qRange, freq)),
                    PeqQualityEditor.format(PeqResponse.bandwidthOctaves(PeqModel.MIN_Q, freq)))
            } else getString(R.string.peq_quality_range, qLimit)
        }
        fun renderQualityMode() {
            changingMode = true
            val bandwidthMode = qualityEditor.mode == PeqQualityDisplay.BW
            ui.peqQualityMode.check(if (bandwidthMode) R.id.peq_quality_bw else R.id.peq_quality_q)
            ui.peqQualityInput.setText(qualityEditor.renderedText)
            ui.peqQualityBox.hint = getString(if (bandwidthMode) R.string.peq_bandwidth else R.string.peq_quality)
            updateQualityRange()
            ui.peqQualityBox.error = null
            changingMode = false
            previewDraft()
        }
        fun updateTypeFields() {
            ui.peqGainBox.isVisible = selectedType.usesGain
            ui.peqQualityBw.isVisible = true
        }
        ui.peqBandType.setOnItemClickListener { _, _, position, _ ->
            selectedType = types[position]
            updateTypeFields()
            previewDraft()
        }
        ui.peqQualityMode.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked || changingMode) return@addOnButtonCheckedListener
            val nextMode = if (checkedId == R.id.peq_quality_bw) PeqQualityDisplay.BW else PeqQualityDisplay.Q
            val converted = qualityEditor.switchMode(nextMode, ui.peqQualityInput.text?.toString().orEmpty(),
                frequency() ?: Double.NaN, editorState.qRange)
            if (converted == null) {
                changingMode = true
                ui.peqQualityMode.check(if (qualityEditor.mode == PeqQualityDisplay.BW) R.id.peq_quality_bw else R.id.peq_quality_q)
                changingMode = false
                ui.peqQualityBox.error = qualityError()
            } else renderQualityMode()
        }
        renderQualityMode()
        updateTypeFields()
        fun editedBand(showErrors: Boolean = true): PeqBand? {
            val freq = if (frequencyLocked) band.frequency else frequency()
            val gain = if (selectedType.usesGain && !gainLocked) ui.peqGainInput.text?.toString()?.toDoubleOrNull() else band.gain
            // Conversion roundoff at an exact Q boundary must not reject an unchanged value.
            val q = if (qualityLocked) band.q else inputQ()?.let { value ->
                if (value >= PeqModel.MIN_Q - 1e-9 && value <= editorState.qRange + 1e-9)
                    value.coerceIn(PeqModel.MIN_Q, editorState.qRange) else value
            }
            val frequencyError = if (freq == null || !PeqModel.validFrequency(freq))
                getString(R.string.peq_invalid_frequency) else null
            val gainError = if (selectedType.usesGain && !gainLocked &&
                (gain == null || !gain.isFinite() || gain !in -editorState.gainRange..editorState.gainRange))
                getString(R.string.peq_invalid_gain, gainLimit) else null
            val qualityError = if (!qualityLocked && (q == null || !q.isFinite() || q !in PeqModel.MIN_Q..editorState.qRange))
                qualityError() else null
            if (showErrors) {
                ui.peqFrequencyBox.error = frequencyError
                ui.peqGainBox.error = gainError
                ui.peqQualityBox.error = qualityError
            }
            if (frequencyError != null || gainError != null || qualityError != null) return null
            return band.copy(type = selectedType, frequency = requireNotNull(freq), gain = requireNotNull(gain),
                q = requireNotNull(q), qualityDisplay = if (qualityLocked) band.qualityDisplay else qualityEditor.mode)
        }
        previewDraft = preview@{
            if (changingMode) return@preview
            val edited = editedBand(showErrors = false) ?: return@preview
            binding?.peqCurve?.setState(editorState.copy(bands = editorState.bands.map {
                if (it.id == id) edited else it
            }), animate = false)
            selectBand(id)
        }
        ui.peqFrequencyInput.doAfterTextChanged { updateQualityRange(); previewDraft() }
        ui.peqGainInput.doAfterTextChanged { previewDraft() }
        ui.peqQualityInput.doAfterTextChanged { previewDraft() }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.peq_edit_band)
            .setView(ui.root).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.peq_delete_band, null).create()
        var confirmed = false
        dialog.setOnShowListener {
            val input = when (focusField) {
                PeqEditField.GAIN -> ui.peqGainInput
                PeqEditField.QUALITY -> ui.peqQualityInput
                PeqEditField.FREQUENCY -> ui.peqFrequencyInput
                null -> null
            }
            input?.apply {
                requestFocus()
                selectAll()
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
            }
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
        stopFilterPreview?.invoke()
        addButtonGestures.forEach { it.cancel() }
        bandAdapter.cancelPendingRepeats()
        flushPendingSave()
        clearMarker()
        super.onPause()
    }

    override fun onDestroyView() {
        addButtonGestures.forEach { it.cancel() }
        addButtonGestures.clear()
        bandAdapter.cancelPendingRepeats()
        bandTouchHelper?.attachToRecyclerView(null)
        bandTouchHelper = null
        cancelPendingSave()
        dismissDialogs()
        presetDialogs?.dismissAll()
        presetDialogs = null
        clearMarker()
        appliedPinResponse = null
        selectionBackCallback = null
        actionTextColors.clear()
        binding?.peqBandList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        powerPreferences?.unregisterOnSharedPreferenceChangeListener(powerListener)
        powerPreferences = null
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
}
