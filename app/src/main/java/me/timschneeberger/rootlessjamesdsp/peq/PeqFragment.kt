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
import android.widget.Toast
import android.widget.TextView
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PeqBandDialogBinding
import me.timschneeberger.rootlessjamesdsp.databinding.PeqBandDialogTitleBinding
import me.timschneeberger.rootlessjamesdsp.databinding.PeqAddCountDialogBinding
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
    private var displaySettings = PeqEditorSettings()
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
    private var inlineFilterDraft: PeqInlineFilterDraft? = null
    private var previewBandDraft: PeqBand? = null
    private data class FineInputs(val bandId: String, val frequency: String, val gain: String,
        val quality: String, val qualityEditor: PeqQualityEditor)
    private var retainedFineInputs: FineInputs? = null
    private var invalidFineDraft = false
    private var inlinePreviewToken: PeqPreviewSession.Token? = null
    private var inlineFilterDialog: AlertDialog? = null
    private var inlineFilterDialogBandId: String? = null
    private class FineFilterBridge(
        val bandId: String,
        val synchronize: (PeqInlineFilterDraft?) -> Unit,
        val preview: () -> Unit
    )
    private var fineFilterBridge: FineFilterBridge? = null
    private var fineFilterDialog: AlertDialog? = null
    private val fineLockGestures = mutableListOf<PeqBandLockGestures>()
    private var filterPreviewFromDialog = false
    private var committingInlineFilter = false
    private val resyncInlineFilter = Runnable {
        if (binding != null && displaySettings.showInlineFilters)
            bandAdapter.submitInlineFilter(true, inlineFilterDraft)
    }
    private var filterLockPreferences: SharedPreferences? = null
    private val filterLockListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (binding != null) {
            // Hidden inline rows have no adapter-owned draft, so locks must also revoke the
            // shared audition here after a dialog hands its listening session back to the card.
            val previewId = inlineFilterDraft?.bandId ?: previewBandDraft?.id
            previewId?.let { if (!canEditInlineFilter(it)) stopInlineFilterPreview() }
            syncInlineFilterDialog()
            previewId?.let { id ->
                // External numeric locks restore the fine editor's fields above. Refresh its
                // existing audition after that guarded sync, but never during markUsed/save.
                val numericLockChanged = listOf(PeqEditField.GAIN, PeqEditField.QUALITY, PeqEditField.FREQUENCY)
                    .any { key == "$id.${it.name}" }
                if (numericLockChanged && canEditInlineFilter(id)) {
                    fineFilterBridge?.takeIf { it.bandId == id }?.preview?.invoke() ?: renderRetainedFilterPreview()
                }
            }
        }
    }
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
        filterLockPreferences = requireContext().getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(filterLockListener)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ui = PeqFragmentBinding.inflate(inflater, container, false)
        binding = ui
        bandAdapter = PeqBandAdapter(onBandChanged = { edited ->
            val previous = editorState.bands.firstOrNull { it.id == edited.id }
            val changesOnlyPower = previous != null && previous.enabled != edited.enabled &&
                edited.copy(enabled = previous.enabled) == previous
            if (!changesOnlyPower) stopInlineFilterPreview()
            val next = editorState.copy(bands = editorState.bands.map { if (it.id == edited.id) edited else it })
            editorState = next
            render(interactingBand = edited.id)
            if (changesOnlyPower) renderRetainedFilterPreview()
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
                if (canMoveBand(holder.bindingAdapterPosition)) {
                    stopInlineFilterPreview()
                    flushPendingSave()
                    bandTouchHelper?.startDrag(holder)
                }
            }, onDelete = { deleteBands(setOf(it)) }, onInlineFilterAction = ::onInlineFilterAction,
            onDuplicate = ::duplicateBand)
        ui.peqBandList.layoutManager = LinearLayoutManager(requireContext())
        ui.peqBandList.adapter = bandAdapter
        ui.peqBandList.itemAnimator = null
        bandTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = false
            override fun getMovementFlags(list: RecyclerView, holder: RecyclerView.ViewHolder): Int =
                if (!canMoveBand(holder.bindingAdapterPosition)) 0
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
            else { stopInlineFilterPreview(); editingSelection = true; renderSelection() }
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
            stopInlineFilterPreview()
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
                stopInlineFilterPreview()
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
        stopInlineFilterPreview()
        cancelPendingSave()
        clearMarker()
        repository = PeqRepository(requireContext())
        displaySettings = PeqEditorPreferences(requireContext()).load()
        binding?.peqCurve?.setDisplayOptions(displaySettings)
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
            bandAdapter.submitReorderPolicy(displaySettings.allowLockedBandReorder)
            bandAdapter.submitGestureSettings(displaySettings.doubleTapUnlocksBand)
            bandAdapter.submitCardFilterIcons(displaySettings.showCardFilterIcons)
            bandAdapter.submitInlineFilter(displaySettings.showInlineFilters, inlineFilterDraft)
            renderSelection()
            // Gesture updates must not trail the thumb behind a transition animation.
            peqCurve.setState(editorState, animate && interactingBand == null)
            peqCurve.setSelectedBand(selectedBandId)
            requireActivity().findViewById<MaterialToolbar>(R.id.toolbar)?.subtitle = getString(R.string.peq_summary,
                editorState.bands.size, PeqBandAdapter.number(editorState.gainStep), PeqBandAdapter.number(editorState.gainRange))
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
        if ((inlineFilterDraft?.bandId ?: previewBandDraft?.id)?.let { it != id } == true) stopInlineFilterPreview()
        selectedBandId = id
        binding?.peqCurve?.showMarker(id)
        binding?.peqCurve?.setPreviewBand(id)
        handler.removeCallbacks(hideMarker)
        if (!trackingSlider) scheduleMarkerHide()
    }

    private fun scheduleMarkerHide() {
        handler.removeCallbacks(hideMarker)
        if (displaySettings.markerDurationSeconds > 0)
            handler.postDelayed(hideMarker, displaySettings.markerDurationSeconds * 1000L)
    }

    private fun startInteraction(id: String) {
        trackingSlider = true
        selectBand(id)
    }

    private fun finishInteraction() {
        flushPendingSave()
        trackingSlider = false
        scheduleMarkerHide()
    }

    private fun clearMarker() {
        trackingSlider = false
        handler.removeCallbacks(hideMarker)
        binding?.peqCurve?.setPreviewStatus(null)
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

    /** Recheck movement permission at gesture start and every drop, including stale callbacks. */
    internal fun canMoveBand(index: Int): Boolean =
        !editingSelection && editorState.bands.size > 1 && index in editorState.bands.indices &&
            (displaySettings.allowLockedBandReorder ||
                !PeqBandLockStore(requireContext()).isBandLocked(editorState.bands[index].id))

    /** Move intact bands by stable identity; persistence is flushed when the drag ends. */
    internal fun moveBand(from: Int, to: Int): Boolean {
        if (!canMoveBand(from) || to !in editorState.bands.indices || from == to)
            return false
        stopInlineFilterPreview()
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
        deleteBands(ids)
    }

    private fun duplicateBand(id: String) {
        if (editingSelection || PeqBandLockStore(requireContext()).isBandLocked(id)) return
        flushPendingSave()
        val index = editorState.bands.indexOfFirst { it.id == id }
        if (index < 0) return
        if (editorState.bands.size >= PeqModel.MAX_BANDS) {
            toast(getString(R.string.peq_copy_limit, PeqModel.MAX_BANDS))
            return
        }
        // Copy the committed card, never the retained fine editor/monitoring draft.
        val copied = editorState.bands[index].copy(id = UUID.randomUUID().toString(), enabled = false)
        stopInlineFilterPreview()
        val next = editorState.copy(bands = editorState.bands.toMutableList().apply { add(index + 1, copied) })
        if (save(next)) toast(getString(R.string.peq_copied_band_numbers, index + 1, index + 2))
    }

    private fun lockPolicy() = PeqLockedOperations(PeqBandLockStore(requireContext())::canEdit)

    private fun unlocked(ids: Set<String>, field: PeqEditField? = null) = lockPolicy().eligible(editorState, ids, field)

    private fun protectionHint(ids: Set<String>, allowed: Set<String>) =
        getString(R.string.peq_bulk_lock_hint, allowed.size, ids.size - allowed.size)

    private fun scanHint(ids: Set<String>): TextView {
        val locks = PeqBandLockStore(requireContext())
        val bands = editorState.bands.filter { it.id in ids }
        return TextView(requireContext()).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            textSize = 13f
            text = getString(R.string.peq_bulk_scan_hint, bands.size,
                bands.count { locks.isBandLocked(it.id) },
                bands.count { locks.isFieldLocked(it.id, PeqEditField.GAIN) },
                bands.count { it.qualityDisplay == PeqQualityDisplay.Q && locks.isFieldLocked(it.id, PeqEditField.QUALITY) },
                bands.count { it.qualityDisplay == PeqQualityDisplay.BW && locks.isFieldLocked(it.id, PeqEditField.QUALITY) },
                bands.count { locks.isFieldLocked(it.id, PeqEditField.FREQUENCY) }) + "\n" +
                getString(R.string.peq_bulk_filter_scan_hint,
                    bands.count { locks.isFieldLocked(it.id, PeqEditField.FILTER_TYPE) })
        }
    }

    private fun deleteBands(ids: Set<String>, onDeleted: () -> Unit = {}) {
        stopInlineFilterPreview()
        flushPendingSave()
        val allowed = unlocked(ids)
        if (allowed.size != ids.size) toast(protectionHint(ids, allowed))
        if (allowed.isEmpty()) return
        if (allowed.size >= editorState.bands.size) {
            toast(getString(R.string.peq_delete_last_band))
            return
        }
        fun remove() {
            val current = unlocked(allowed)
            val removedNumber = editorState.bands.indexOfFirst { it.id in current } + 1
            if (current.isNotEmpty() && current.size < editorState.bands.size && save(PeqSelection.delete(editorState, current))) {
                clearMarker()
                val deletedMessage = if (current.size == 1) getString(R.string.peq_deleted_band_number, removedNumber)
                    else getString(R.string.peq_deleted_band_count, current.size)
                val skipped = ids.size - current.size
                toast(deletedMessage + if (skipped > 0) "\n" + getString(R.string.peq_delete_skipped_locked, skipped) else "")
                onDeleted()
            }
        }
        if (!displaySettings.confirmDelete) { remove(); return }
        track(MaterialAlertDialogBuilder(requireContext())
            .setTitle(scopedTitle(R.string.peq_selection_delete, ids))
            .setMessage(getString(R.string.peq_selection_delete_confirm, allowed.size, editorState.bands.size - allowed.size))
            .setPositiveButton(android.R.string.ok) { _, _ -> remove() }
            .setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun showResetMenu() {
        stopInlineFilterPreview()
        flushPendingSave()
        val ids = requireOperationIds() ?: return
        val resetLabels = arrayOf(getString(R.string.peq_reset_q), getString(R.string.peq_reset_frequency),
            getString(R.string.peq_reset_gain), getString(R.string.peq_reset_filter_type), getString(R.string.peq_reset_all),
            getString(R.string.peq_redistribute))
        val bulkLabels = if (editingSelection) arrayOf(R.string.peq_bulk_lock_band, R.string.peq_bulk_lock_gain,
            R.string.peq_bulk_lock_q, R.string.peq_bulk_lock_bw, R.string.peq_bulk_lock_frequency,
            R.string.peq_bulk_enable, R.string.peq_bulk_disable,
            R.string.peq_bulk_lock_filter_type).map(::getString).toTypedArray()
            else emptyArray()
        val targets = listOf(PeqResetTarget.Q, PeqResetTarget.FREQUENCY, PeqResetTarget.GAIN,
            PeqResetTarget.FILTER_TYPE, PeqResetTarget.ALL)
        // Normal reset actions show their own eligible/skipped counts directly in each row.
        val scan = if (editingSelection) scanHint(ids).apply { id = R.id.peq_bulk_lock_scan } else null
        val lockKinds = if (editingSelection) mapOf(
            resetLabels.size to PeqBulkLockKind.BAND,
            resetLabels.size + 1 to PeqBulkLockKind.GAIN,
            resetLabels.size + 2 to PeqBulkLockKind.Q,
            resetLabels.size + 3 to PeqBulkLockKind.BW,
            resetLabels.size + 4 to PeqBulkLockKind.FREQUENCY,
            resetLabels.size + 7 to PeqBulkLockKind.FILTER_TYPE) else emptyMap()
        val menuAdapter = PeqResetMenuAdapter(requireContext(), (resetLabels + bulkLabels).toList(),
            lockKinds, ids, { editorState }, PeqBandLockStore(requireContext()),
            resetTargets = targets.mapIndexed { index, target -> index to target }.toMap() +
                (targets.size to PeqResetTarget.FREQUENCY), redistributePositions = setOf(targets.size)) {
            bandAdapter.notifyDataSetChanged()
            scan?.text = scanHint(ids).text
        }
        track(MaterialAlertDialogBuilder(requireContext()).setTitle(scopedTitle(
            if (editingSelection) R.string.peq_selection_reset else R.string.peq_reset_title, ids))
            .apply { if (scan != null) setView(scan) }
            .setAdapter(menuAdapter) { _, index ->
                if (index == targets.size) showRedistribute(ids)
                else if (index < targets.size) resetProtected(ids, targets[index])
                else applyBulkControl(ids, index - resetLabels.size)
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun resetProtected(ids: Set<String>, target: PeqResetTarget) {
        val result = lockPolicy().reset(editorState, ids, target)
        if (result.editableCount > 0 && save(result.state)) clearMarker()
        toast(getString(R.string.peq_reset_protected_result, result.editableCount, result.protectedCount))
    }

    private fun applyBulkControl(ids: Set<String>, action: Int) {
        val locks = PeqBandLockStore(requireContext())
        val bands = editorState.bands.filter { it.id in ids }
        if (action == 5 || action == 6) {
            // Power bypass is independent of parameter protection, just like each card's switch.
            if (save(editorState.copy(bands = editorState.bands.map {
                    if (it.id in ids) it.copy(enabled = action == 5) else it
                }))) clearMarker()
        } else {
            val affected = bands.filter { action != 2 && action != 3 ||
                it.qualityDisplay == if (action == 2) PeqQualityDisplay.Q else PeqQualityDisplay.BW }
            affected.forEach { band -> when (action) {
                0 -> locks.setBandLocked(band.id, true)
                1 -> locks.setFieldLocked(band.id, PeqEditField.GAIN, true)
                2, 3 -> locks.setFieldLocked(band.id, PeqEditField.QUALITY, true)
                4 -> locks.setFieldLocked(band.id, PeqEditField.FREQUENCY, true)
                7 -> locks.setFieldLocked(band.id, PeqEditField.FILTER_TYPE, true)
            } }
            bandAdapter.notifyDataSetChanged()
            toast(getString(R.string.peq_bulk_lock_result, affected.size))
        }
    }

    private fun showRedistribute(ids: Set<String>) {
        flushPendingSave()
        track(MaterialAlertDialogBuilder(requireContext())
            .setTitle(scopedTitle(R.string.peq_redistribute_title, ids))
            .setMessage(getString(R.string.peq_redistribute_confirm, ids.size) + "\n\n" +
                protectionHint(ids, unlocked(ids, PeqEditField.FREQUENCY)))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                resetProtected(ids, PeqResetTarget.FREQUENCY)
            }.setNegativeButton(android.R.string.cancel, null).create()).show()
    }

    private fun showParametersMenu() {
        stopInlineFilterPreview()
        flushPendingSave()
        val ids = requireOperationIds() ?: return
        track(PeqBatchParametersDialog.create(requireContext(), scopedTitle(R.string.peq_parameters, ids), ids,
            readState = { editorState }, canEdit = PeqBandLockStore(requireContext())::canEdit,
            scan = { scanHint(ids).text.toString() }, onSave = { next ->
                if (save(next)) { clearMarker(); toast(getString(R.string.peq_bulk_applied)); true } else false
            })).show()
    }

    private fun stopInlineFilterPreview() {
        handler.removeCallbacks(resyncInlineFilter)
        val hadInlinePreview = inlineFilterDraft != null || inlinePreviewToken != null || previewBandDraft != null
        inlineFilterDraft = null
        previewBandDraft = null
        retainedFineInputs = null
        invalidFineDraft = false
        filterPreviewFromDialog = false
        inlinePreviewToken?.let { if (PeqPreviewSession.end(it)) notifyAudioAndPreview() }
        inlinePreviewToken = null
        if (::bandAdapter.isInitialized) bandAdapter.submitInlineFilter(displaySettings.showInlineFilters, null)
        syncInlineFilterDialog()
        if (hadInlinePreview) clearMarker() else binding?.peqCurve?.setPreviewStatus(null)
        binding?.peqCurve?.setState(editorState, animate = false)
    }

    private fun dismissFineFilterDialog() {
        val dialog = fineFilterDialog ?: return
        stopInlineFilterPreview()
        // Release ownership before Android dispatches the (possibly queued) dismiss callback.
        fineFilterBridge = null
        fineFilterDialog = null
        dialog.dismiss()
    }

    private fun onInlineFilterAction(id: String, action: PeqInlineFilterAction) {
        if (action == PeqInlineFilterAction.Cancel || action == PeqInlineFilterAction.Unavailable) {
            if (inlineFilterDraft?.bandId == id) {
                // RecyclerView may detach/rebind a card while its dialog still owns the audition.
                // A hidden inline row is likewise not the lifetime owner of a dialog's session.
                val anotherOwner = inlineFilterDialogBandId == id || fineFilterBridge?.bandId == id || committingInlineFilter ||
                    filterPreviewFromDialog
                if (action == PeqInlineFilterAction.Unavailable && anotherOwner && canEditInlineFilter(id)) {
                    // The adapter relinquishes its presentation copy before this callback. Restore
                    // it after detach/bind completes so reattached cards mirror the surviving owner.
                    if (displaySettings.showInlineFilters) {
                        handler.removeCallbacks(resyncInlineFilter)
                        handler.post(resyncInlineFilter)
                    }
                    return
                }
                stopInlineFilterPreview()
            }
            return
        }
        if (!canEditInlineFilter(id)) {
            stopInlineFilterPreview()
            return
        }
        flushPendingSave()
        val band = editorState.bands.firstOrNull { it.id == id } ?: return
        if (inlineFilterDialogBandId != id && fineFilterBridge?.bandId != id) {
            inlineFilterDialog?.dismiss()
            dismissFineFilterDialog()
            stopFilterPreview?.invoke()
        }
        if (inlineFilterDraft?.bandId != id) stopInlineFilterPreview()
        if (inlineFilterDialogBandId == id || fineFilterBridge?.bandId == id) filterPreviewFromDialog = true
        val old = inlineFilterDraft ?: PeqInlineFilterDraft(id, band.type)
        if (action == PeqInlineFilterAction.Confirm) {
            confirmInlineFilter(old)
            return
        }
        val draft = when (action) {
            is PeqInlineFilterAction.Select -> old.copy(type = action.type)
            is PeqInlineFilterAction.Monitor -> old.copy(monitoring = action.enabled)
            else -> return
        }
        updateInlineFilterPreview(draft)
    }

    private fun canEditInlineFilter(id: String): Boolean = !editingSelection &&
        editorState.bands.any { it.id == id } && PeqBandLockStore(requireContext()).canEdit(id, PeqEditField.FILTER_TYPE)

    private fun syncInlineFilterDialog() {
        if (binding == null) return
        fineFilterBridge?.let { bridge ->
            bridge.synchronize(inlineFilterDraft?.takeIf { it.bandId == bridge.bandId })
        }
        val dialog = inlineFilterDialog ?: return
        val id = inlineFilterDialogBandId ?: return
        val committed = editorState.bands.firstOrNull { it.id == id }?.type ?: return
        val draft = inlineFilterDraft?.takeIf { it.bandId == id }
        PeqFilterTypeDialog.setState(dialog, committed, draft?.type ?: committed, draft?.monitoring == true)
    }

    private fun updateInlineFilterPreview(draft: PeqInlineFilterDraft) {
        val id = draft.bandId
        if (!canEditInlineFilter(id)) { stopInlineFilterPreview(); return }
        fineFilterBridge?.takeIf { it.bandId == id }?.let { bridge ->
            bridge.synchronize(draft)
            bridge.preview()
            return
        }
        if (invalidFineDraft && retainedFineInputs?.bandId == id) {
            inlineFilterDraft = draft
            bandAdapter.submitInlineFilter(displaySettings.showInlineFilters, draft)
            syncInlineFilterDialog()
            renderRetainedFilterPreview()
            return
        }
        val retained = previewBandDraft?.takeIf { it.id == id }
        val next = editorState.copy(bands = editorState.bands.map {
            if (it.id == id) protectedPreviewBand(retained ?: it).copy(type = draft.type) else it
        })
        publishInlineFilterPreview(draft, next)
    }

    private fun protectedPreviewBand(draft: PeqBand): PeqBand {
        val current = editorState.bands.firstOrNull { it.id == draft.id } ?: return draft
        val locks = PeqBandLockStore(requireContext())
        if (locks.isBandLocked(draft.id)) return current
        return draft.copy(enabled = current.enabled,
            type = if (locks.isFieldLocked(draft.id, PeqEditField.FILTER_TYPE)) current.type else draft.type,
            frequency = if (locks.isFieldLocked(draft.id, PeqEditField.FREQUENCY)) current.frequency else draft.frequency,
            gain = if (locks.isFieldLocked(draft.id, PeqEditField.GAIN)) current.gain else draft.gain,
            q = if (locks.isFieldLocked(draft.id, PeqEditField.QUALITY)) current.q else draft.q,
            qualityDisplay = if (locks.isFieldLocked(draft.id, PeqEditField.QUALITY)) current.qualityDisplay else draft.qualityDisplay)
    }

    private fun renderRetainedFilterPreview() {
        val retained = previewBandDraft ?: return
        if (editingSelection || editorState.bands.none { it.id == retained.id } ||
            PeqBandLockStore(requireContext()).isBandLocked(retained.id)) { stopInlineFilterPreview(); return }
        val band = protectedPreviewBand(retained)
        previewBandDraft = band
        retainedFineInputs?.takeIf { it.bandId == band.id }?.let { inputs ->
            val locks = PeqBandLockStore(requireContext())
            val frequencyLocked = locks.isFieldLocked(band.id, PeqEditField.FREQUENCY)
            val qualityLocked = locks.isFieldLocked(band.id, PeqEditField.QUALITY)
            val quality = if (frequencyLocked || qualityLocked)
                PeqQualityEditor(band.q, band.frequency, band.qualityDisplay) else inputs.qualityEditor
            retainedFineInputs = inputs.copy(
                frequency = if (frequencyLocked) PeqBandAdapter.number(band.frequency, 4) else inputs.frequency,
                gain = if (locks.isFieldLocked(band.id, PeqEditField.GAIN)) PeqBandAdapter.number(band.gain, 4) else inputs.gain,
                quality = if (frequencyLocked || qualityLocked) quality.renderedText else inputs.quality,
                qualityEditor = quality)
        }
        if (invalidFineDraft) {
            inlinePreviewToken?.let { if (PeqPreviewSession.end(it)) notifyAudioAndPreview() }
            inlinePreviewToken = null
            clearMarker()
            binding?.peqCurve?.setState(editorState, animate = false)
            return
        }
        val draft = inlineFilterDraft?.takeIf { it.bandId == band.id } ?: PeqInlineFilterDraft(band.id, band.type)
        publishInlineFilterPreview(draft.copy(type = band.type),
            editorState.copy(bands = editorState.bands.map { if (it.id == band.id) band else it }))
    }

    private fun publishInlineFilterPreview(draft: PeqInlineFilterDraft, next: PeqState) {
        val id = draft.bandId
        val editableType = canEditInlineFilter(id)
        previewBandDraft = next.bands.firstOrNull { it.id == id }
        inlineFilterDraft = draft.takeIf { editableType }
        if (draft.monitoring && editableType) {
            val token = inlinePreviewToken
            if (token == null) inlinePreviewToken = PeqPreviewSession.begin(next)
            else PeqPreviewSession.update(token, next)
            notifyAudioAndPreview()
        } else {
            inlinePreviewToken?.let { if (PeqPreviewSession.end(it)) notifyAudioAndPreview() }
            inlinePreviewToken = null
        }
        binding?.peqCurve?.setState(next, animate = false)
        selectBand(id)
        binding?.peqCurve?.setPreviewStatus(id, listening = draft.monitoring)
        bandAdapter.submitInlineFilter(displaySettings.showInlineFilters, inlineFilterDraft)
        syncInlineFilterDialog()
    }

    private fun confirmInlineFilter(draft: PeqInlineFilterDraft): Boolean {
        if (!canEditInlineFilter(draft.bandId)) { stopInlineFilterPreview(); return false }
        inlineFilterDraft = draft
        val next = editorState.copy(bands = editorState.bands.map {
            if (it.id == draft.bandId) it.copy(type = draft.type) else it
        })
        committingInlineFilter = true
        val saved = try { save(next) } finally { committingInlineFilter = false }
        if (saved) updateInlineFilterPreview(draft)
        return saved
    }

    private fun showBandFilterType(id: String) {
        val locks = PeqBandLockStore(requireContext())
        if (editingSelection || editorState.bands.none { it.id == id } || locks.isBandLocked(id)) return
        if (inlineFilterDialogBandId == id && inlineFilterDialog?.isShowing == true) return
        inlineFilterDialog?.dismiss()
        flushPendingSave()
        if (fineFilterBridge?.bandId != id) {
            dismissFineFilterDialog()
            stopFilterPreview?.invoke()
        }
        if (inlineFilterDraft?.bandId != id || !canEditInlineFilter(id)) stopInlineFilterPreview()
        val committed = editorState.bands.first { it.id == id }.type
        val inherited = inlineFilterDraft ?: PeqInlineFilterDraft(id, committed)
        lateinit var dialog: AlertDialog
        val stop: () -> Unit = {
            if (inlineFilterDialog === dialog) stopInlineFilterPreview()
        }
        dialog = PeqFilterTypeDialog.create(requireContext(), getString(R.string.peq_filter_type), committed,
            onTypeSelected = { type ->
                if (inlineFilterDialog === dialog) onInlineFilterAction(id, PeqInlineFilterAction.Select(type))
            },
            onMonitorChanged = { enabled ->
                if (inlineFilterDialog === dialog) onInlineFilterAction(id, PeqInlineFilterAction.Monitor(enabled))
            },
            onConfirm = { type ->
                if (inlineFilterDialog !== dialog) false else {
                    val draft = inlineFilterDraft ?: PeqInlineFilterDraft(id, type)
                    confirmInlineFilter(draft.copy(type = type))
                }
            }, onDismiss = {
                dialogs.remove(dialog)
                if (inlineFilterDialog === dialog) {
                    inlineFilterDialog = null
                    inlineFilterDialogBandId = null
                    renderRetainedFilterPreview()
                }
                if (stopFilterPreview === stop) stopFilterPreview = null
            }, isLocked = { locks.isFieldLocked(id, PeqEditField.FILTER_TYPE) },
            canToggleLock = { !editingSelection && editorState.bands.any { it.id == id } && !locks.isBandLocked(id) },
            canEdit = { canEditInlineFilter(id) },
            onToggleLock = {
                if (inlineFilterDialog === dialog && !editingSelection && !locks.isBandLocked(id)) {
                    val locked = !locks.isFieldLocked(id, PeqEditField.FILTER_TYPE)
                    locks.setFieldLocked(id, PeqEditField.FILTER_TYPE, locked)
                    if (locked) stopInlineFilterPreview()
                    bandAdapter.notifyDataSetChanged()
                    syncInlineFilterDialog()
                }
            }, onCancel = { if (inlineFilterDialog === dialog) stopInlineFilterPreview() })
        inlineFilterDialog = dialog
        inlineFilterDialogBandId = id
        filterPreviewFromDialog = true
        stopFilterPreview = stop
        dialogs.add(dialog)
        if (canEditInlineFilter(id)) updateInlineFilterPreview(inherited) else syncInlineFilterDialog()
        dialog.show()
    }

    private fun addBands(edge: PeqResizeEdge, count: Int): Boolean {
        stopInlineFilterPreview()
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
        val previous = editorState.bands.associateBy { it.id }
        val locks = PeqBandLockStore(requireContext())
        next.bands.forEach { band ->
            if (band.id !in previous) locks.markNew(band.id)
            else if (previous[band.id] != band) locks.markUsed(band.id)
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
            renderRetainedFilterPreview()
            binding?.root?.let { Snackbar.make(it, R.string.peq_save_failed, Snackbar.LENGTH_LONG).show() }
        }
    }

    private fun notifyAudioAndPreview() {
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_GRAPHIC_EQ_CHANGED))
    }

    private fun resizeProtected(count: Int, edge: PeqResizeEdge, redistribute: Boolean): PeqState {
        val locks = PeqBandLockStore(requireContext())
        val oldIds = editorState.bands.mapTo(hashSetOf()) { it.id }
        val resized = if (count >= editorState.bands.size) {
            PeqModel.resize(editorState, count, edge, redistribute = false)
        } else {
            val candidates = if (edge == PeqResizeEdge.FRONT) editorState.bands else editorState.bands.asReversed()
            val remove = candidates.filter { locks.canEdit(it.id) }
                .take(editorState.bands.size - count).mapTo(hashSetOf()) { it.id }
            editorState.copy(bands = editorState.bands.filterNot { it.id in remove }).also {
                if (candidates.any { band -> locks.isBandLocked(band.id) })
                    toast(getString(R.string.peq_resize_protected, remove.size, it.bands.size))
            }
        }
        if (!redistribute) return resized
        val policy = PeqLockedOperations { id, field -> id !in oldIds || locks.canEdit(id, field) }
        val result = policy.reset(resized, resized.bands.mapTo(hashSetOf()) { it.id }, PeqResetTarget.FREQUENCY)
        if (result.protectedCount > 0)
            toast(getString(R.string.peq_reset_protected_result, result.editableCount, result.protectedCount))
        return result.state
    }

    private fun showSettings() {
        stopInlineFilterPreview()
        flushPendingSave()
        val ui = PeqSettingsDialogBinding.inflate(layoutInflater)
        val displayControls = PeqDisplaySettings(ui, PeqEditorPreferences(requireContext()))
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
                val display = displayControls.validatedSettings() ?: return@setOnClickListener
                fun applySettings(edge: PeqResizeEdge = PeqResizeEdge.BACK, redistribute: Boolean = false) {
                    val resized = resizeProtected(count, edge, redistribute)
                    if (save(resized.copy(gainStep = step, gainRange = range,
                        qStep = qStep, qRange = qRange, frequencyStep = frequencyStep,
                        showQSlider = ui.peqShowQSlider.isChecked,
                        showFrequencySlider = ui.peqShowFrequencySlider.isChecked))) {
                        if (displayControls.save(display)) {
                            displaySettings = display
                            bandAdapter.submitReorderPolicy(display.allowLockedBandReorder)
                            bandAdapter.submitGestureSettings(display.doubleTapUnlocksBand)
                            bandAdapter.submitCardFilterIcons(display.showCardFilterIcons)
                            bandAdapter.submitInlineFilter(display.showInlineFilters, inlineFilterDraft)
                            binding?.peqCurve?.setDisplayOptions(display)
                            if (binding?.peqCurve?.isMarkerVisible == true) scheduleMarkerHide()
                            dialog.dismiss()
                        } else toast(getString(R.string.peq_save_failed))
                    }
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
        // The number badge always opens a read-only view; field shortcuts still obey locks.
        if (editingSelection || (focusField != null && !locks.canEdit(id, focusField))) return
        if (fineFilterBridge?.bandId == id && fineFilterDialog?.isShowing == true) return
        dismissFineFilterDialog()
        flushPendingSave()
        if (inlineFilterDialogBandId != id) {
            inlineFilterDialog?.dismiss()
            stopFilterPreview?.invoke()
        }
        if ((inlineFilterDraft?.bandId ?: previewBandDraft?.id) != id || locks.isBandLocked(id)) stopInlineFilterPreview()
        selectBand(id)
        val savedBand = editorState.bands.firstOrNull { it.id == id } ?: return
        val band = previewBandDraft?.takeIf { it.id == id }?.let(::protectedPreviewBand) ?: savedBand
        val retainedInputs = retainedFineInputs?.takeIf { it.bandId == id }
        val inheritedFilter = inlineFilterDraft?.takeIf { it.bandId == id }
        val ui = PeqBandDialogBinding.inflate(layoutInflater)
        val titleUi = PeqBandDialogTitleBinding.inflate(layoutInflater)
        titleUi.peqBandDialogTitle.text = getString(R.string.peq_fine_band_title,
            editorState.bands.indexOfFirst { it.id == id } + 1)
        var activeDialog: AlertDialog? = null
        fun frequencyLocked() = !locks.canEdit(id, PeqEditField.FREQUENCY)
        fun gainLocked() = !locks.canEdit(id, PeqEditField.GAIN)
        fun qualityLocked() = !locks.canEdit(id, PeqEditField.QUALITY)
        fun typeLocked() = !locks.canEdit(id, PeqEditField.FILTER_TYPE)
        var selectedType = inheritedFilter?.type ?: band.type
        var changingMode = false
        var synchronizingSharedFilter = false
        var previewDraft: () -> Unit = {}
        val monitorTextColors = ui.peqBandMonitor.textColors
        var qualitySlider: PeqFineSliderControl? = null
        var listening = inheritedFilter?.monitoring == true
        fun clearAudioDraft() {
            if (activeDialog != null && fineFilterDialog !== activeDialog) return
            inlinePreviewToken?.let { if (PeqPreviewSession.end(it)) notifyAudioAndPreview() }
            inlinePreviewToken = null
        }
        fun renderListening() {
            if (typeLocked()) {
                listening = false
                clearAudioDraft()
            }
            ui.peqBandType.monitoring = listening
            ui.peqBandMonitor.isEnabled = !typeLocked()
            ui.peqBandMonitor.backgroundTintList = ColorStateList.valueOf(
                if (listening) android.graphics.Color.rgb(46, 125, 50) else android.graphics.Color.TRANSPARENT)
            ui.peqBandMonitor.setTextColor(if (listening) ColorStateList.valueOf(android.graphics.Color.WHITE) else monitorTextColors)
            ui.peqBandMonitor.isSelected = listening
            ui.peqBandMonitor.setText(if (listening) R.string.peq_live_monitor_on else R.string.peq_live_monitor_off)
        }
        val stop: () -> Unit = {
            if (activeDialog == null || fineFilterDialog === activeDialog) {
                listening = false
                stopInlineFilterPreview()
                renderListening()
            }
        }
        stopFilterPreview = stop
        val frequencyText = retainedInputs?.frequency ?: PeqBandAdapter.number(band.frequency, 4)
        var qualityEditor = retainedInputs?.qualityEditor ?: PeqQualityEditor(band.q, frequencyText.toDouble(), band.qualityDisplay)
        ui.peqBandType.committedType = savedBand.type
        ui.peqBandType.setSelection(selectedType)
        ui.peqFrequencyInput.setText(frequencyText)
        ui.peqGainInput.setText(retainedInputs?.gain ?: PeqBandAdapter.number(band.gain, 4))
        val gainLimit = PeqBandAdapter.number(editorState.gainRange)
        val qLimit = PeqBandAdapter.number(editorState.qRange, 3)
        ui.peqFrequencyLabel.setText(R.string.peq_frequency)
        ui.peqGainLabel.text = getString(R.string.peq_gain_limit, gainLimit)
        ui.peqGainLabel.contentDescription = getString(R.string.peq_gain_limit, gainLimit)
        fun frequency() = ui.peqFrequencyInput.text?.toString()?.toDoubleOrNull()
        fun inputQ(): Double? {
            val freq = frequency() ?: return null
            return qualityEditor.readQ(ui.peqQualityInput.text?.toString().orEmpty(), freq, editorState.qRange)
        }
        fun qualityError() = getString(R.string.peq_invalid_quality_range, qLimit)
        fun updateQualityRange() {
            ui.peqQualityRangeHint.text = if (qualityEditor.mode == PeqQualityDisplay.BW) {
                val freq = frequency()?.takeIf { PeqModel.validFrequency(it) } ?: band.frequency
                getString(R.string.peq_bw_range,
                    PeqQualityEditor.format(PeqResponse.bandwidthOctaves(editorState.qRange, freq)),
                    PeqQualityEditor.format(PeqResponse.bandwidthOctaves(PeqModel.MIN_Q, freq)))
            } else getString(R.string.peq_quality_range, qLimit)
            qualitySlider?.refresh()
        }
        fun renderQualityMode() {
            changingMode = true
            val bandwidthMode = qualityEditor.mode == PeqQualityDisplay.BW
            ui.peqQualityMode.check(if (bandwidthMode) R.id.peq_quality_bw else R.id.peq_quality_q)
            ui.peqQualityInput.setText(qualityEditor.renderedText)
            ui.peqQualityLabel.text = if (bandwidthMode) "BW" else "Q"
            updateQualityRange()
            ui.peqQualityBox.error = null
            changingMode = false
            previewDraft()
        }
        fun updateTypeFields() {
            ui.peqGainRow.isVisible = selectedType.usesGain
            ui.peqGainBox.isVisible = selectedType.usesGain
            ui.peqQualityBw.isVisible = true
        }
        ui.peqBandType.onTypeSelected = { type ->
            if (!typeLocked()) selectedType = type
            else ui.peqBandType.setSelection(selectedType)
            updateTypeFields()
            previewDraft()
        }
        ui.peqQualityMode.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked || changingMode || qualityLocked() ||
                (activeDialog != null && fineFilterDialog !== activeDialog)) return@addOnButtonCheckedListener
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
        retainedInputs?.let { ui.peqQualityInput.setText(it.quality) }
        updateTypeFields()
        fun editedBand(showErrors: Boolean = true): PeqBand? {
            val current = editorState.bands.firstOrNull { it.id == id } ?: return null
            if (locks.isBandLocked(id)) return current
            val effectiveType = if (typeLocked()) current.type else selectedType
            val freq = if (frequencyLocked()) current.frequency else frequency()
            val gain = if (effectiveType.usesGain && !gainLocked()) ui.peqGainInput.text?.toString()?.toDoubleOrNull() else current.gain
            // Conversion roundoff at an exact Q boundary must not reject an unchanged value.
            val q = if (qualityLocked()) current.q else inputQ()?.let { value ->
                if (value >= PeqModel.MIN_Q - 1e-9 && value <= editorState.qRange + 1e-9)
                    value.coerceIn(PeqModel.MIN_Q, editorState.qRange) else value
            }
            val frequencyError = if (freq == null || !PeqModel.validFrequency(freq))
                getString(R.string.peq_invalid_frequency) else null
            val gainError = if (effectiveType.usesGain && !gainLocked() &&
                (gain == null || !gain.isFinite() || gain !in -editorState.gainRange..editorState.gainRange))
                getString(R.string.peq_invalid_gain, gainLimit) else null
            val qualityError = if (!qualityLocked() && (q == null || !q.isFinite() || q !in PeqModel.MIN_Q..editorState.qRange))
                qualityError() else null
            if (showErrors) {
                ui.peqFrequencyBox.error = frequencyError
                ui.peqGainBox.error = gainError
                ui.peqQualityBox.error = qualityError
            }
            if (frequencyError != null || gainError != null || qualityError != null) return null
            return current.copy(type = effectiveType, frequency = requireNotNull(freq), gain = requireNotNull(gain),
                q = requireNotNull(q), qualityDisplay = if (qualityLocked()) current.qualityDisplay else qualityEditor.mode)
        }
        previewDraft = preview@{
            if (changingMode || synchronizingSharedFilter || fineFilterDialog !== activeDialog || activeDialog == null) return@preview
            if (locks.isBandLocked(id)) {
                stop()
                return@preview
            }
            renderListening()
            if (!listening) clearAudioDraft()
            val edited = editedBand(showErrors = false) ?: run {
                invalidFineDraft = true
                clearAudioDraft()
                inlineFilterDraft = PeqInlineFilterDraft(id, selectedType, listening).takeIf { !typeLocked() }
                bandAdapter.submitInlineFilter(displaySettings.showInlineFilters, inlineFilterDraft)
                syncInlineFilterDialog()
                clearMarker()
                binding?.peqCurve?.setState(editorState, animate = false)
                return@preview
            }
            invalidFineDraft = false
            val next = editorState.copy(bands = editorState.bands.map {
                if (it.id == id) edited else it
            })
            filterPreviewFromDialog = true
            publishInlineFilterPreview(PeqInlineFilterDraft(id, edited.type, listening), next)
        }
        ui.peqBandMonitor.setOnClickListener {
            if (fineFilterDialog !== activeDialog) return@setOnClickListener
            if (typeLocked()) { renderListening(); return@setOnClickListener }
            listening = !listening
            renderListening()
            if (!listening) clearAudioDraft()
            previewDraft()
        }
        ui.peqFrequencyInput.doAfterTextChanged {
            val freq = frequency()
            if (qualityLocked() && freq != null && PeqModel.validFrequency(freq)) {
                val current = editorState.bands.firstOrNull { it.id == id }
                if (current != null) {
                    qualityEditor = PeqQualityEditor(current.q, freq, current.qualityDisplay)
                    renderQualityMode()
                }
            }
            updateQualityRange()
            previewDraft()
        }
        ui.peqGainInput.doAfterTextChanged { previewDraft() }
        ui.peqQualityInput.doAfterTextChanged { previewDraft() }
        PeqFineSliderControl(ui.peqFrequencySlider, ui.peqFrequencyInput,
            { PeqModel.MIN_FREQUENCY..PeqModel.MAX_FREQUENCY }, { 0.001 }, logarithmic = true)
        PeqFineSliderControl(ui.peqGainSlider, ui.peqGainInput,
            { -editorState.gainRange..editorState.gainRange }, { 0.01 })
        qualitySlider = PeqFineSliderControl(ui.peqQualitySlider, ui.peqQualityInput, {
            if (qualityEditor.mode == PeqQualityDisplay.Q) PeqModel.MIN_Q..editorState.qRange else {
                val freq = frequency()?.takeIf { PeqModel.validFrequency(it) } ?: band.frequency
                PeqResponse.bandwidthOctaves(editorState.qRange, freq)..PeqResponse.bandwidthOctaves(PeqModel.MIN_Q, freq)
            }
        }, { 0.001 }, logarithmic = true,
            formatValue = { value, endpoint ->
                if (endpoint && qualityEditor.mode == PeqQualityDisplay.BW) PeqQualityEditor.format(value)
                else PeqBandAdapter.number(value, 6)
            })
        fun renderLocks() {
            val mainLocked = locks.isBandLocked(id)
            val bandEnabled = editorState.bands.firstOrNull { it.id == id }?.enabled == true
            titleUi.peqBandDialogPower.apply {
                isEnabled = !mainLocked
                isSelected = bandEnabled
                iconTint = ColorStateList.valueOf(if (bandEnabled && !mainLocked)
                    android.graphics.Color.rgb(46, 170, 83) else android.graphics.Color.GRAY)
                contentDescription = getString(if (bandEnabled) R.string.peq_disable_band else R.string.peq_enable_band)
            }
            titleUi.peqBandDialogLock.apply {
                setIconResource(if (mainLocked) R.drawable.ic_peq_lock else R.drawable.ic_peq_lock_open)
                iconTint = ColorStateList.valueOf(if (mainLocked) android.graphics.Color.rgb(211, 47, 47) else android.graphics.Color.GRAY)
                contentDescription = getString(if (mainLocked) R.string.peq_unlock_band else R.string.peq_lock_band)
            }
            renderListening()
            ui.peqBandCopy.isEnabled = !mainLocked && editorState.bands.size < PeqModel.MAX_BANDS
            activeDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !mainLocked
            activeDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !mainLocked && editorState.bands.size > 1
            ui.peqFrequencyBox.isEnabled = !frequencyLocked()
            ui.peqFrequencySlider.isEnabled = !frequencyLocked()
            ui.peqGainBox.isEnabled = !gainLocked()
            ui.peqGainSlider.isEnabled = !gainLocked()
            ui.peqQualityBox.isEnabled = !qualityLocked()
            ui.peqQualitySlider.isEnabled = !qualityLocked()
            ui.peqQualityQ.isEnabled = !qualityLocked()
            ui.peqQualityBw.isEnabled = !qualityLocked()
            ui.peqBandType.isEnabled = !typeLocked()
            listOf(ui.peqFrequencyLock to PeqEditField.FREQUENCY, ui.peqGainLock to PeqEditField.GAIN,
                ui.peqQualityLock to PeqEditField.QUALITY, ui.peqTypeLock to PeqEditField.FILTER_TYPE).forEach { (button, field) ->
                val locked = locks.isFieldLocked(id, field)
                button.setIconResource(if (locked) R.drawable.ic_peq_lock else R.drawable.ic_peq_lock_open)
                button.iconTint = ColorStateList.valueOf(if (locked) android.graphics.Color.rgb(211, 47, 47) else android.graphics.Color.GRAY)
                button.isEnabled = !locks.isBandLocked(id)
                button.contentDescription = getString(if (locked) R.string.peq_unlock_parameter else R.string.peq_lock_parameter,
                    when (field) { PeqEditField.FREQUENCY -> "频率"; PeqEditField.GAIN -> "增益"; PeqEditField.QUALITY -> "Q / BW"; PeqEditField.FILTER_TYPE -> "滤波器类型" })
            }
        }
        listOf(ui.peqFrequencyLock to PeqEditField.FREQUENCY, ui.peqGainLock to PeqEditField.GAIN,
            ui.peqQualityLock to PeqEditField.QUALITY, ui.peqTypeLock to PeqEditField.FILTER_TYPE).forEach { (button, field) ->
            button.setOnClickListener {
                if (fineFilterDialog === activeDialog && !locks.isBandLocked(id)) {
                    val locked = !locks.isFieldLocked(id, field)
                    locks.setFieldLocked(id, field, locked)
                    if (locked) {
                        val current = editorState.bands.first { it.id == id }
                        when (field) {
                            PeqEditField.FREQUENCY -> ui.peqFrequencyInput.setText(PeqBandAdapter.number(current.frequency, 4))
                            PeqEditField.GAIN -> ui.peqGainInput.setText(PeqBandAdapter.number(current.gain, 4))
                            PeqEditField.QUALITY -> {
                                qualityEditor = PeqQualityEditor(current.q,
                                    frequency()?.takeIf { PeqModel.validFrequency(it) } ?: current.frequency,
                                    current.qualityDisplay)
                                renderQualityMode()
                            }
                            PeqEditField.FILTER_TYPE -> { selectedType = current.type; ui.peqBandType.setSelection(current.type); updateTypeFields() }
                        }
                    }
                    renderLocks()
                    previewDraft()
                    bandAdapter.notifyDataSetChanged()
                }
            }
        }
        fun toggleFineMasterLock() {
            if (fineFilterDialog !== activeDialog) return
            val locked = !locks.isBandLocked(id)
            locks.setBandLocked(id, locked)
            if (locked) {
                stop()
                val current = editorState.bands.first { it.id == id }
                changingMode = true
                selectedType = current.type
                ui.peqBandType.setSelection(current.type)
                ui.peqFrequencyInput.setText(PeqBandAdapter.number(current.frequency, 4))
                ui.peqGainInput.setText(PeqBandAdapter.number(current.gain, 4))
                qualityEditor = PeqQualityEditor(current.q, current.frequency, current.qualityDisplay)
                renderQualityMode()
                updateTypeFields()
            }
            renderLocks()
            bandAdapter.notifyDataSetChanged()
        }
        val lockGestures = PeqBandLockGestures(titleUi.peqBandDialogLock,
            onSingle = ::toggleFineMasterLock,
            onDouble = {
                if (fineFilterDialog === activeDialog && !editingSelection) {
                    val includeBand = displaySettings.doubleTapUnlocksBand
                    locks.unlockAll(id, includeBand)
                    renderLocks()
                    bandAdapter.notifyDataSetChanged()
                    toast(getString(when {
                        includeBand -> R.string.peq_all_band_locks_unlocked
                        locks.isBandLocked(id) -> R.string.peq_all_field_locks_unlocked_band_locked
                        else -> R.string.peq_all_field_locks_unlocked
                    }))
                }
            })
        fineLockGestures += lockGestures
        titleUi.peqBandDialogPower.setOnClickListener {
            if (fineFilterDialog !== activeDialog || locks.isBandLocked(id)) return@setOnClickListener
            val current = editorState.bands.firstOrNull { it.id == id } ?: return@setOnClickListener
            // Like the card power control, this changes only the saved bypass state.
            // Unconfirmed filter/slider edits remain a separate visual/audio draft.
            val previousPreview = PeqPreviewSession.current()
            val owner = inlinePreviewToken
            if (owner != null && previousPreview != null) PeqPreviewSession.update(owner,
                previousPreview.copy(bands = previousPreview.bands.map {
                    if (it.id == id) it.copy(enabled = !current.enabled) else it
                }))
            val saved = save(editorState.copy(bands = editorState.bands.map {
                if (it.id == id) current.copy(enabled = !current.enabled) else it
            }))
            if (!saved && owner != null && previousPreview != null) PeqPreviewSession.update(owner, previousPreview)
            renderLocks()
            previewDraft()
        }
        renderLocks()
        renderListening()
        val dialog = MaterialAlertDialogBuilder(requireContext()).setCustomTitle(titleUi.root)
            .setView(ui.root).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (fineFilterDialog === activeDialog) stopInlineFilterPreview()
            }
            .setNeutralButton(R.string.peq_delete_band, null).create()
        activeDialog = dialog
        val bridge = FineFilterBridge(id, synchronize = { draft ->
            val current = editorState.bands.firstOrNull { it.id == id } ?: band
            synchronizingSharedFilter = true
            try {
                selectedType = if (typeLocked()) current.type else draft?.type ?: current.type
                listening = !typeLocked() && draft?.monitoring == true
                ui.peqBandType.committedType = current.type
                ui.peqBandType.setSelection(selectedType)
                // An external card/popup lock has the same effect as this dialog's lock buttons.
                // Only newly protected numeric fields are restored; other fine drafts stay local.
                if (frequencyLocked()) ui.peqFrequencyInput.setText(PeqBandAdapter.number(current.frequency, 4))
                if (gainLocked()) ui.peqGainInput.setText(PeqBandAdapter.number(current.gain, 4))
                if (qualityLocked()) {
                    qualityEditor = PeqQualityEditor(current.q,
                        frequency()?.takeIf { PeqModel.validFrequency(it) } ?: current.frequency, current.qualityDisplay)
                    renderQualityMode()
                }
                updateTypeFields()
                renderLocks()
            } finally { synchronizingSharedFilter = false }
        }, preview = { previewDraft() })
        fineFilterBridge = bridge
        fineFilterDialog = dialog
        filterPreviewFromDialog = true
        dialog.setOnShowListener {
            renderLocks()
            val input = when (focusField) {
                PeqEditField.GAIN -> ui.peqGainInput
                PeqEditField.QUALITY -> ui.peqQualityInput
                PeqEditField.FREQUENCY -> ui.peqFrequencyInput
                PeqEditField.FILTER_TYPE, null -> null
            }
            input?.apply {
                requestFocus()
                selectAll()
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (fineFilterDialog !== dialog || locks.isBandLocked(id)) return@setOnClickListener
                val edited = editedBand() ?: return@setOnClickListener
                if (save(editorState.copy(bands = editorState.bands.map { if (it.id == id) edited else it }))) {
                    // Persist first, then update the shared owner without an end/begin audio gap.
                    previewDraft()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = !locks.isBandLocked(id) && editorState.bands.size > 1
                setOnClickListener {
                    if (fineFilterDialog === dialog) deleteBands(setOf(id)) { dialog.dismiss() }
                }
            }
        }
        ui.peqBandCopy.isEnabled = !locks.isBandLocked(id) && editorState.bands.size < PeqModel.MAX_BANDS
        ui.peqBandCopy.setOnClickListener {
            if (fineFilterDialog !== dialog || locks.isBandLocked(id)) return@setOnClickListener
            val edited = editedBand() ?: return@setOnClickListener
            if (editorState.bands.size >= PeqModel.MAX_BANDS) return@setOnClickListener
            val index = editorState.bands.indexOfFirst { it.id == id }
            val copied = editorState.bands.toMutableList().apply {
                add(index + 1, edited.copy(id = UUID.randomUUID().toString(), enabled = false))
            }
            stopInlineFilterPreview()
            if (save(editorState.copy(bands = copied))) dialog.dismiss()
        }
        track(dialog).also {
            it.setOnDismissListener {
                dialogs.remove(it)
                lockGestures.cancel()
                fineLockGestures.remove(lockGestures)
                val ownsPreview = fineFilterBridge === bridge
                if (ownsPreview) {
                    if (previewBandDraft?.id == id) retainedFineInputs = FineInputs(id,
                        ui.peqFrequencyInput.text?.toString().orEmpty(), ui.peqGainInput.text?.toString().orEmpty(),
                        ui.peqQualityInput.text?.toString().orEmpty(), qualityEditor)
                    fineFilterBridge = null
                    fineFilterDialog = null
                }
                bandAdapter.notifyDataSetChanged()
                if (stopFilterPreview === stop) stopFilterPreview = null
                if (ownsPreview) renderRetainedFilterPreview()
            }
        }.show()
        previewDraft()
    }

    private fun track(dialog: AlertDialog): AlertDialog {
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        return dialog
    }

    private fun dismissDialogs() { dialogs.toList().forEach { it.dismiss() } }

    override fun onPause() {
        stopInlineFilterPreview()
        stopFilterPreview?.invoke()
        fineLockGestures.forEach { it.cancel() }
        addButtonGestures.forEach { it.cancel() }
        bandAdapter.cancelPendingRepeats()
        flushPendingSave()
        clearMarker()
        super.onPause()
    }

    override fun onDestroyView() {
        stopInlineFilterPreview()
        fineLockGestures.forEach { it.cancel() }
        fineLockGestures.clear()
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
        filterLockPreferences?.unregisterOnSharedPreferenceChangeListener(filterLockListener)
        filterLockPreferences = null
        powerPreferences?.unregisterOnSharedPreferenceChangeListener(powerListener)
        powerPreferences = null
        context?.unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
}
