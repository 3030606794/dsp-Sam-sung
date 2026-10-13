package me.timschneeberger.rootlessjamesdsp.peq

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PeqBandRowBinding
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import me.timschneeberger.rootlessjamesdsp.utils.ButtonPressRepeat
import java.util.Locale
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.round

/** Only visible bands have views; slider binding never rewrites out-of-range or off-step gains. */
class PeqBandAdapter(
    private val onBandChanged: (PeqBand) -> Boolean,
    private val onEdit: (String) -> Unit,
    private val onInteractionFinished: () -> Unit = {},
    private val onBandSelected: (String) -> Unit = {},
    private val onLocate: (String) -> Unit = {},
    private val onInteractionStarted: (String) -> Unit = {},
    private val onEditField: (String, PeqEditField) -> Unit = { id, _ -> onEdit(id) },
    private val onEditType: (String) -> Unit = { onEdit(it) },
    private val onToggleSelection: (String) -> Unit = {},
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {},
    private val onDelete: (String) -> Unit = {},
    private val onInlineFilterAction: (String, PeqInlineFilterAction) -> Unit = { _, _ -> },
    private val onDuplicate: (String) -> Unit = {},
) : RecyclerView.Adapter<PeqBandAdapter.Holder>() {
    private var state = PeqState(emptyList())
    private var editing = false
    private var selectedIds = emptySet<String>()
    private val itemIds = mutableMapOf<String, Long>()
    private var nextItemId = 0L
    private val attachedHolders = mutableSetOf<Holder>()
    private var allowLockedBandReorder = false
    private var doubleTapUnlocksBand = true
    private var showCardFilterIcons = false
    private var showInlineFilters = false
    private var inlineDraft: PeqInlineFilterDraft? = null

    init { setHasStableIds(true) }

    fun cancelPendingRepeats() = attachedHolders.forEach { it.cancelRepeats() }

    /** Lock-only updates preserve every holder's listeners and in-flight gesture candidates. */
    fun refreshLocks(id: String) = attachedHolders.toList().forEach { it.refreshLocks(id) }

    fun submitGestureSettings(doubleTapUnlocksBand: Boolean) {
        this.doubleTapUnlocksBand = doubleTapUnlocksBand
    }

    /** The optional icons only change the inline strip's presentation, never its draft. */
    fun submitCardFilterIcons(show: Boolean) {
        if (showCardFilterIcons == show) return
        showCardFilterIcons = show
        attachedHolders.toList().forEach { it.renderInline() }
    }

    /** Apply this display policy without rebinding sliders or interrupting their gestures. */
    fun submitReorderPolicy(allowLocked: Boolean) {
        if (allowLockedBandReorder == allowLocked) return
        allowLockedBandReorder = allowLocked
        attachedHolders.toList().forEach { it.renderReorderState() }
    }

    /** Updates only the bottom strip; ongoing slider gestures never need to be rebound. */
    fun submitInlineFilter(show: Boolean, draft: PeqInlineFilterDraft? = null) {
        val previousDraft = inlineDraft
        showInlineFilters = show
        inlineDraft = draft
        if (!show || editing) {
            val unavailable = draft ?: previousDraft
            // Drop local ownership before notifying the host: cleanup can call back into this method.
            inlineDraft = null
            unavailable?.let { onInlineFilterAction(it.bandId, PeqInlineFilterAction.Unavailable) }
        }
        attachedHolders.toList().forEach { it.renderInline() }
    }

    private fun invalidateInlineDraft(id: String) {
        if (inlineDraft?.bandId != id) return
        inlineDraft = null
        onInlineFilterAction(id, PeqInlineFilterAction.Unavailable)
    }

    /** Selection updates do not rebind sliders or interrupt an in-progress drag. */
    fun submitSelection(editing: Boolean, selectedIds: Set<String>) {
        val modeChanged = this.editing != editing
        if (modeChanged) cancelPendingRepeats()
        val previous = this.selectedIds
        this.editing = editing
        if (editing) inlineDraft?.bandId?.let(::invalidateInlineDraft)
        this.selectedIds = selectedIds.toSet()
        state.bands.forEachIndexed { index, band ->
            if (modeChanged || (band.id in previous) != (band.id in this.selectedIds)) {
                notifyItemChanged(index, SELECTION_PAYLOAD)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitState(next: PeqState, interactingBand: String? = null) {
        val previous = state
        state = next
        next.bands.forEach { itemIds.getOrPut(it.id) { nextItemId++ } }
        if (previous.bands.map { it.id } != next.bands.map { it.id } ||
            previous.gainRange != next.gainRange || previous.gainStep != next.gainStep ||
            previous.qStep != next.qStep || previous.qRange != next.qRange || previous.frequencyStep != next.frequencyStep ||
            previous.showQSlider != next.showQSlider || previous.showFrequencySlider != next.showFrequencySlider) {
            notifyDataSetChanged()
        } else {
            next.bands.forEachIndexed { index, band ->
                if (band.id != interactingBand && band != previous.bands[index]) notifyItemChanged(index)
            }
        }
    }

    /** ItemTouchHelper must retain its holder and touch listeners throughout a reorder. */
    fun submitReorderedState(next: PeqState, from: Int, to: Int) {
        require(from in state.bands.indices && to in state.bands.indices)
        val reordered = state.bands.toMutableList().apply { add(to, removeAt(from)) }
        require(next == state.copy(bands = reordered)) { "Reorder must preserve every band and editor setting" }
        if (from == to) return
        state = next
        notifyItemMoved(from, to)
        notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(to - from) + 1, RANK_PAYLOAD)
    }

    override fun getItemCount() = state.bands.size
    override fun getItemId(position: Int) = requireNotNull(itemIds[state.bands[position].id])
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(PeqBandRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(state.bands[position], position)
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === SELECTION_PAYLOAD || it === RANK_PAYLOAD }) {
            if (payloads.any { it === SELECTION_PAYLOAD }) holder.bindSelection(state.bands[position].id)
            if (payloads.any { it === RANK_PAYLOAD }) holder.bindRank(state.bands[position], position)
        } else onBindViewHolder(holder, position)
    }
    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        attachedHolders += holder
        holder.renderReorderState()
        holder.renderInline()
    }
    override fun onViewDetachedFromWindow(holder: Holder) {
        holder.cancelRepeats()
        holder.stopInline()
        attachedHolders -= holder
        super.onViewDetachedFromWindow(holder)
    }
    override fun onViewRecycled(holder: Holder) {
        holder.cancelRepeats()
        holder.stopInline()
        attachedHolders -= holder
        super.onViewRecycled(holder)
    }

    inner class Holder(private val binding: PeqBandRowBinding) : RecyclerView.ViewHolder(binding.root) {
        private val defaultGainThumbTint = binding.peqBandSlider.thumbTintList
        private val defaultGainTrackTint = binding.peqBandSlider.trackActiveTintList
        private val defaultGainHaloTint = binding.peqBandSlider.haloTintList
        private val defaultGainTextColors = binding.peqBandGain.textColors
        private val defaultTitleTextColors = binding.peqBandTitle.textColors
        private val defaultDragHandleTint = binding.peqBandDragHandle.iconTint
        private val defaultGainLabelColors = binding.peqGainLabel.textColors
        private val defaultQualityLabelColors = binding.peqQLabel.textColors
        private val defaultFrequencyLabelColors = binding.peqFrequencyLabel.textColors
        private var lastGainSign: Int? = null
        private var lastGainDimmed: Boolean? = null
        private val gainDisabledSurface = MaterialColors.getColor(binding.root,
            com.google.android.material.R.attr.colorSurface)
        private var rank = 0
        private val repeaters = listOf(binding.peqBandMinus, binding.peqBandPlus,
            binding.peqQMinus, binding.peqQPlus, binding.peqFrequencyMinus, binding.peqFrequencyPlus)
            .map { ButtonPressRepeat(it) }
        private var previousEditing: Boolean? = null
        private val normalBackground = binding.root.cardBackgroundColor
        private val normalStroke = binding.root.strokeColorStateList
        private val normalStrokeWidth = binding.root.strokeWidth
        private val selectedBackground = MaterialColors.getColor(binding.root,
            com.google.android.material.R.attr.colorSecondaryContainer)
        private val selectedStroke = MaterialColors.getColor(binding.root,
            com.google.android.material.R.attr.colorPrimary)
        private val locks = PeqBandLockStore(binding.root.context)
        private var boundId: String? = null
        private val lockGestures = PeqBandLockGestures(binding.peqBandLock,
            onSingle = ::toggleBandLock, onDouble = { unlockFieldLocks() },
            doubleTapActionLabel = binding.root.context.getString(R.string.peq_unlock_band),
            immediateSingle = true,
            captureDoubleAction = {
                val id = lockTarget()
                val originalLocked = id?.let(locks::isBandLocked)
                val action: () -> Unit = {
                    if (id != null && lockTarget() == id) unlockFieldLocks(originalLocked)
                }
                action
            })
        private val locateGestures = PeqBandLockGestures(binding.peqBandLocate,
            onSingle = { lockTarget()?.let(onLocate) },
            onDouble = { lockTarget()?.takeIf { canEdit(it) }?.let(onDuplicate) },
            doubleTapActionLabel = binding.root.context.getString(R.string.peq_duplicate_current_band),
            immediateSingle = true,
            captureDoubleAction = {
                val id = lockTarget()
                val action: () -> Unit = {
                    if (id != null && lockTarget() == id && canEdit(id)) onDuplicate(id)
                }
                action
            })

        fun cancelRepeats(preserveLocate: Boolean = false) {
            repeaters.forEach { it.cancel() }
            lockGestures.cancel()
            if (!preserveLocate) locateGestures.cancel()
        }
        fun stopInline() { boundId?.let(::invalidateInlineDraft) }

        private fun lockTarget() = boundId?.takeIf { id ->
            !editing && this in attachedHolders && state.bands.any { it.id == id }
        }

        private fun toggleBandLock() {
            val id = lockTarget() ?: return
            repeaters.forEach { it.cancel() }
            locateGestures.cancel()
            locks.setBandLocked(id, !locks.isBandLocked(id))
            renderControlState()
        }

        private fun unlockFieldLocks(preservedBandLocked: Boolean? = null) {
            val id = lockTarget() ?: return
            cancelRepeats()
            locks.unlockAll(id, includeBand = doubleTapUnlocksBand, preservedBandLocked = preservedBandLocked)
            renderControlState()
            Toast.makeText(binding.root.context, when {
                doubleTapUnlocksBand -> R.string.peq_all_band_locks_unlocked
                locks.isBandLocked(id) -> R.string.peq_all_field_locks_unlocked_band_locked
                else -> R.string.peq_all_field_locks_unlocked
            },
                Toast.LENGTH_SHORT).show()
        }

        fun renderInline() {
            val id = boundId ?: return
            val band = state.bands.firstOrNull { it.id == id } ?: return
            binding.peqBandTitle.filterIcon = band.type.takeUnless { showInlineFilters }
            val editable = canEdit(id, PeqEditField.FILTER_TYPE)
            if ((!showInlineFilters || !editable) && inlineDraft?.bandId == id) invalidateInlineDraft(id)
            val draft = inlineDraft?.takeIf { it.bandId == id }
            binding.peqInlineFilter.isVisible = showInlineFilters
            binding.peqInlineFilter.showFilterIcons = showCardFilterIcons
            binding.peqInlineFilter.bind(band.type, draft?.type, draft?.monitoring == true,
                editable, locks.isFieldLocked(id, PeqEditField.FILTER_TYPE), !editing && !locks.isBandLocked(id))
        }

        private fun selectInstead(id: String): Boolean {
            if (!editing) return false
            onToggleSelection(id)
            return true
        }

        private fun canEdit(id: String, field: PeqEditField? = null) = !editing && locks.canEdit(id, field)

        private fun canReorder(id: String) = !editing && state.bands.size > 1 &&
            state.bands.any { it.id == id } && (allowLockedBandReorder || !locks.isBandLocked(id))

        fun renderReorderState() {
            val id = boundId ?: return
            val enabled = canReorder(id)
            binding.peqBandDragHandle.isEnabled = enabled
            binding.peqBandDragHandle.iconTint = if (enabled) defaultDragHandleTint else ColorStateList.valueOf(Color.GRAY)
        }

        private fun markUsed(id: String) {
            locks.markUsed(id)
            binding.peqBandNew.visibility = View.INVISIBLE
        }

        private fun renderLock(button: MaterialButton, locked: Boolean, description: String) {
            button.setIconResource(if (locked) R.drawable.ic_peq_lock else R.drawable.ic_peq_lock_open)
            button.iconTint = ColorStateList.valueOf(if (locked) Color.rgb(211, 47, 47) else Color.GRAY)
            button.contentDescription = description
            button.isEnabled = !editing
        }

        fun refreshLocks(id: String) {
            if (boundId == id) renderControlState()
        }

        private fun renderControlState() {
            val id = boundId ?: return
            val band = state.bands.firstOrNull { it.id == id } ?: return
            val context = binding.root.context
            val mainLocked = locks.isBandLocked(id)
            binding.peqBandNew.visibility = if (locks.isNew(id)) View.VISIBLE else View.INVISIBLE
            renderLock(binding.peqBandLock, mainLocked, context.getString(
                if (mainLocked) R.string.peq_unlock_band else R.string.peq_lock_band))
            val fieldLocks = listOf(Triple(binding.peqGainLock, PeqEditField.GAIN, R.string.peq_gain),
                Triple(binding.peqQLock, PeqEditField.QUALITY, R.string.peq_quality),
                Triple(binding.peqFrequencyLock, PeqEditField.FREQUENCY, R.string.peq_frequency))
            fieldLocks.forEach { (button, field, label) ->
                val locked = locks.isFieldLocked(id, field)
                renderLock(button, locked, context.getString(
                    if (locked) R.string.peq_unlock_parameter else R.string.peq_lock_parameter, context.getString(label)))
                button.isEnabled = !editing && !mainLocked
            }
            listOf(binding.peqGainLabel to defaultGainLabelColors,
                binding.peqQLabel to defaultQualityLabelColors,
                binding.peqFrequencyLabel to defaultFrequencyLabelColors)
                .forEach { (label, colors) ->
                    label.isEnabled = !editing && !mainLocked
                    label.isFocusable = false
                    label.isClickable = false
                    label.setTextColor(if (label.isEnabled) colors else ColorStateList.valueOf(Color.GRAY))
                    label.contentDescription = null
                }
            val gainEnabled = canEdit(id, PeqEditField.GAIN) && band.type.usesGain
            binding.peqBandSlider.isEnabled = gainEnabled
            binding.peqBandMinus.isEnabled = gainEnabled && band.gain > -state.gainRange
            binding.peqBandPlus.isEnabled = gainEnabled && band.gain < state.gainRange
            binding.peqBandGain.isEnabled = gainEnabled
            renderGainColor(band.gain, band.type.usesGain, dimmed = !gainEnabled)
            val qEnabled = canEdit(id, PeqEditField.QUALITY)
            val bounds = qualityBounds(band, state.qRange)
            binding.peqQSlider.isEnabled = qEnabled && bounds.start < bounds.endInclusive
            binding.peqQMinus.isEnabled = qEnabled && displayedQuality(band) > bounds.start
            binding.peqQPlus.isEnabled = qEnabled && displayedQuality(band) < bounds.endInclusive
            binding.peqQValue.isEnabled = qEnabled
            binding.peqBandSummary.isEnabled = qEnabled
            val frequencyEnabled = canEdit(id, PeqEditField.FREQUENCY)
            binding.peqFrequencySlider.isEnabled = frequencyEnabled
            binding.peqFrequencyMinus.isEnabled = frequencyEnabled && band.frequency > PeqModel.MIN_FREQUENCY
            binding.peqFrequencyPlus.isEnabled = frequencyEnabled && band.frequency < PeqModel.MAX_FREQUENCY
            binding.peqFrequencyValue.isEnabled = frequencyEnabled
            binding.peqBandHeaderFrequency.isEnabled = frequencyEnabled
            // Opening the editor is also the read-only inspection path for a protected band.
            binding.peqBandNumber.isEnabled = !editing
            binding.peqBandTitle.isEnabled = !editing && !mainLocked
            binding.peqBandTitle.setTextColor(if (mainLocked) ColorStateList.valueOf(Color.GRAY) else defaultTitleTextColors)
            binding.peqBandDelete.isEnabled = canEdit(id)
            binding.peqBandDelete.iconTint = ColorStateList.valueOf(if (canEdit(id)) Color.rgb(211, 47, 47) else Color.GRAY)
            renderReorderState()
            binding.peqBandLocate.isEnabled = !editing
            binding.peqBandWarning.isEnabled = !editing
            binding.peqBandPower.isEnabled = !editing
            binding.peqBandPower.iconTint = ColorStateList.valueOf(if (band.enabled) Color.rgb(46, 170, 83) else Color.GRAY)
            binding.peqBandPower.contentDescription = context.getString(
                if (band.enabled) R.string.peq_disable_band else R.string.peq_enable_band)
            renderInline()
        }

        fun bindSelection(id: String) {
            if (previousEditing != editing) cancelRepeats()
            previousEditing = editing
            val selected = editing && id in selectedIds
            binding.root.selectionMode = editing
            binding.root.isSelected = selected
            binding.root.setCardBackgroundColor(if (selected) ColorStateList.valueOf(selectedBackground) else normalBackground)
            binding.root.setStrokeColor(if (selected) ColorStateList.valueOf(selectedStroke) else normalStroke)
            binding.root.strokeWidth = if (selected) (2 * binding.root.resources.displayMetrics.density).toInt()
                .coerceAtLeast(1) else normalStrokeWidth
            binding.root.setOnClickListener(if (editing) View.OnClickListener { onToggleSelection(id) } else null)
            binding.root.isClickable = editing
            binding.root.isFocusable = editing
            binding.peqBandHeader.setOnClickListener(if (editing) View.OnClickListener { onToggleSelection(id) } else null)
            binding.peqBandHeader.isClickable = editing
            binding.peqBandHeader.isFocusable = editing
            binding.peqBandHeader.importantForAccessibility = if (editing) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            renderControlState()
        }

        fun bindRank(band: PeqBand, index: Int) {
            rank = index
            val context = binding.root.context
            binding.peqBandNumber.text = (index + 1).toString()
            binding.peqBandNumber.contentDescription = context.getString(R.string.peq_edit_band) + " " + (index + 1)
            binding.peqBandTitle.text = typeLabel(context, band.type)
            binding.peqBandSlider.contentDescription = context.getString(R.string.peq_band_title,
                index + 1, number(band.frequency))
        }

        private fun renderGainColor(gain: Double, usesGain: Boolean, dimmed: Boolean) {
            val sign = when { !usesGain -> 0; gain > 0.0 -> 1; gain < 0.0 -> -1; else -> 0 }
            if (lastGainSign == sign && lastGainDimmed == dimmed) return
            lastGainSign = sign
            lastGainDimmed = dimmed
            val tint = when {
                sign > 0 -> ColorStateList.valueOf(Color.rgb(211, 47, 47))
                sign < 0 -> ColorStateList.valueOf(Color.rgb(25, 118, 210))
                else -> null
            }
            fun visible(colors: ColorStateList) = if (!dimmed) colors else ColorStateList.valueOf(ColorUtils.blendARGB(
                colors.getColorForState(intArrayOf(android.R.attr.state_enabled), colors.defaultColor), gainDisabledSurface, 0.6f))
            binding.peqBandSlider.thumbTintList = visible(tint ?: defaultGainThumbTint)
            binding.peqBandSlider.trackActiveTintList = visible(tint ?: defaultGainTrackTint)
            binding.peqBandSlider.haloTintList = visible(tint ?: defaultGainHaloTint)
            binding.peqBandGain.setTextColor(visible(tint ?: defaultGainTextColors))
        }

        @SuppressLint("ClickableViewAccessibility")
        fun bind(band: PeqBand, index: Int) {
            // Locating immediately updates the graph and can refresh this same row.
            // Keep its second-tap candidate, but never carry it to another identity.
            cancelRepeats(preserveLocate = boundId == band.id)
            if (boundId != band.id) {
                stopInline()
                SliderDoubleTapReset.clear(binding.peqBandSlider)
                listOf(binding.peqBandSlider, binding.peqQSlider, binding.peqFrequencySlider)
                    .forEach(PeqSystemPresetSliderReset::clear)
            }
            val context = binding.root.context
            binding.root.isSaveFromParentEnabled = false
            boundId = band.id
            binding.peqInlineFilter.onAction = { action ->
                if (!selectInstead(band.id) && canEdit(band.id, PeqEditField.FILTER_TYPE)) {
                    onInlineFilterAction(band.id, action)
                }
            }
            binding.peqInlineFilter.onToggleLock = {
                if (!selectInstead(band.id) && canEdit(band.id)) {
                    val locked = !locks.isFieldLocked(band.id, PeqEditField.FILTER_TYPE)
                    locks.setFieldLocked(band.id, PeqEditField.FILTER_TYPE, locked)
                    if (locked) invalidateInlineDraft(band.id)
                    renderControlState()
                }
            }
            bindSelection(band.id)
            bindRank(band, index)
            binding.peqBandWarning.setOnClickListener {
                if (!selectInstead(band.id)) Toast.makeText(context, R.string.peq_frequency_unset_warning, Toast.LENGTH_LONG).show()
            }
            binding.peqBandDragHandle.setOnLongClickListener {
                if (!canReorder(band.id)) false
                else { onStartDrag(this); true }
            }
            binding.peqBandNumber.setOnClickListener {
                if (!selectInstead(band.id)) {
                    onBandSelected(band.id); onEdit(band.id)
                }
            }
            binding.peqBandDelete.setOnClickListener {
                if (!selectInstead(band.id) && canEdit(band.id)) onDelete(band.id)
            }
            listOf(binding.peqGainLock to PeqEditField.GAIN, binding.peqQLock to PeqEditField.QUALITY,
                binding.peqFrequencyLock to PeqEditField.FREQUENCY).forEach { (button, field) ->
                button.setOnClickListener {
                    if (!selectInstead(band.id) && canEdit(band.id)) {
                        cancelRepeats()
                        locks.setFieldLocked(band.id, field, !locks.isFieldLocked(band.id, field))
                        renderControlState()
                    }
                }
            }
            binding.peqBandPower.setOnClickListener {
                if (!selectInstead(band.id)) {
                    state.bands.firstOrNull { it.id == band.id }?.let { current ->
                        markUsed(band.id)
                        onBandChanged(current.copy(enabled = !current.enabled))
                        onInteractionFinished()
                        renderControlState()
                    }
                }
            }
            fun editField(field: PeqEditField) {
                if (!selectInstead(band.id) && canEdit(band.id, field)) {
                    markUsed(band.id); onBandSelected(band.id); onEditField(band.id, field)
                }
            }
            binding.peqBandGain.setOnClickListener { editField(PeqEditField.GAIN) }
            binding.peqBandSummary.setOnClickListener { editField(PeqEditField.QUALITY) }
            binding.peqQValue.setOnClickListener { editField(PeqEditField.QUALITY) }
            binding.peqBandTitle.setOnClickListener {
                if (!selectInstead(band.id) && canEdit(band.id)) {
                    onBandSelected(band.id); onEditType(band.id)
                }
            }
            binding.peqBandHeaderFrequency.setOnClickListener { editField(PeqEditField.FREQUENCY) }
            binding.peqFrequencyValue.setOnClickListener { editField(PeqEditField.FREQUENCY) }
            binding.peqGainControls.isVisible = band.type.usesGain
            binding.peqQControls.isVisible = state.showQSlider
            binding.peqFrequencyControls.isVisible = state.showFrequencySlider
            binding.peqBandDetails.isVisible = !state.showQSlider || !state.showFrequencySlider
            binding.peqBandHeaderFrequency.isVisible = !state.showFrequencySlider
            binding.peqBandSummary.isVisible = !state.showQSlider
            binding.peqBandDetailSeparator.isVisible = !state.showQSlider && !state.showFrequencySlider
            val slider = binding.peqBandSlider
            slider.clearOnChangeListeners()
            slider.clearOnSliderTouchListeners()
            slider.isSaveEnabled = false
            slider.stepSize = 0f
            slider.valueFrom = -state.gainRange.toFloat()
            slider.valueTo = state.gainRange.toFloat()
            slider.value = band.gain.coerceIn(-state.gainRange, state.gainRange).toFloat()
            slider.setLabelFormatter { "${fixedNumber(it.toDouble(), 2)} dB" }

            fun currentBand() = state.bands.firstOrNull { it.id == band.id }
            fun renderLabels() {
                val current = currentBand() ?: return
                binding.peqBandWarning.visibility = if (current.frequency == 0.0) View.VISIBLE else View.INVISIBLE
                bindRank(current, rank)
                binding.peqBandHeaderFrequency.text = context.getString(R.string.peq_band_header_frequency,
                    number(current.frequency, 3))
                val bandwidth = usesBandwidth(current)
                binding.peqBandSummary.text = context.getString(if (bandwidth) R.string.peq_bw_value else R.string.peq_q_value,
                    fixedNumber(displayedQuality(current), 3))
                binding.peqBandGain.text = if (current.type.usesGain)
                    String.format(Locale.ROOT, "%+.2f dB", current.gain)
                else context.getString(R.string.peq_no_gain)
                binding.peqBandMinus.isEnabled = current.gain > -state.gainRange
                binding.peqBandPlus.isEnabled = current.gain < state.gainRange
                val quality = displayedQuality(current)
                val bounds = qualityBounds(current, state.qRange)
                binding.peqQLabel.setText(if (bandwidth) R.string.peq_card_bandwidth_label else R.string.peq_quality)
                binding.peqQValue.text = fixedNumber(quality, 3)
                binding.peqQValue.contentDescription = context.getString(
                    if (bandwidth) R.string.peq_bw_value else R.string.peq_q_value, fixedNumber(quality, 3))
                binding.peqQSlider.contentDescription = context.getString(if (bandwidth) R.string.peq_bandwidth else R.string.peq_quality)
                binding.peqQMinus.contentDescription = context.getString(if (bandwidth) R.string.peq_bw_decrease else R.string.peq_quality_decrease)
                binding.peqQPlus.contentDescription = context.getString(if (bandwidth) R.string.peq_bw_increase else R.string.peq_quality_increase)
                binding.peqQMinus.isEnabled = quality > bounds.start
                binding.peqQPlus.isEnabled = quality < bounds.endInclusive
                binding.peqFrequencyValue.text = context.getString(R.string.peq_band_header_frequency, number(current.frequency, 3))
                binding.peqFrequencyValue.contentDescription = context.getString(R.string.peq_frequency_value, number(current.frequency, 3))
                binding.peqFrequencyMinus.isEnabled = current.frequency > PeqModel.MIN_FREQUENCY
                binding.peqFrequencyPlus.isEnabled = current.frequency < PeqModel.MAX_FREQUENCY
                renderControlState()
            }
            fun renderSliders() {
                val current = currentBand() ?: return
                slider.value = current.gain.coerceIn(-state.gainRange, state.gainRange).toFloat()
                val bounds = qualityBounds(current, state.qRange)
                binding.peqQSlider.isEnabled = canEdit(band.id, PeqEditField.QUALITY) && bounds.start < bounds.endInclusive
                binding.peqQSlider.value = logarithmicPosition(displayedQuality(current), bounds.start, bounds.endInclusive)
                binding.peqFrequencySlider.value = logarithmicPosition(current.frequency,
                    PeqModel.MIN_FREQUENCY, PeqModel.MAX_FREQUENCY)
            }
            fun userGain(requested: Double) {
                val current = currentBand() ?: return
                if (!canEdit(band.id, PeqEditField.GAIN) || !current.type.usesGain) { renderSliders(); return }
                markUsed(band.id)
                onBandSelected(current.id)
                val next = requested.coerceIn(-state.gainRange, state.gainRange)
                if (next != current.gain) onBandChanged(current.copy(gain = next))
                val updated = currentBand() ?: return
                slider.value = updated.gain.coerceIn(-state.gainRange, state.gainRange).toFloat()
                renderLabels()
            }
            slider.addOnChangeListener { _, changed, fromUser ->
                if (fromUser) userGain(dragGain(changed, state.gainRange))
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    if (!editing && slider.isEnabled) { markUsed(band.id); onInteractionStarted(band.id) }
                }
                override fun onStopTrackingTouch(slider: Slider) = onInteractionFinished()
            })
            binding.peqBandMinus.setOnClickListener { currentBand()?.let {
                if (selectInstead(band.id)) return@setOnClickListener
                userGain(it.gain - state.gainStep)
                onInteractionFinished()
            } }
            binding.peqBandPlus.setOnClickListener { currentBand()?.let {
                if (selectInstead(band.id)) return@setOnClickListener
                userGain(it.gain + state.gainStep)
                onInteractionFinished()
            } }
            fun changeBand(next: PeqBand) {
                onBandSelected(next.id)
                if (next != currentBand()) onBandChanged(next)
                // The active row is deliberately not rebound during a gesture. Update both
                // positions here because moving frequency also changes BW's digital bounds.
                renderSliders()
                renderLabels()
            }
            fun userQuality(requested: Double) {
                val current = currentBand() ?: return
                if (!canEdit(band.id, PeqEditField.QUALITY)) { renderSliders(); return }
                markUsed(band.id)
                changeBand(withQuality(current, requested, state.qRange))
            }
            fun userFrequency(requested: Double) {
                if (!canEdit(band.id, PeqEditField.FREQUENCY)) { renderSliders(); return }
                markUsed(band.id)
                currentBand()?.let {
                    val moved = withFrequency(it, requested, state.qRange)
                    changeBand(if (locks.isFieldLocked(it.id, PeqEditField.QUALITY)) moved.copy(q = it.q) else moved)
                }
            }
            fun bindLogSlider(control: Slider, bounds: (PeqBand) -> ClosedFloatingPointRange<Double>,
                              draggedValue: (Float, Double, Double) -> Double, label: (PeqBand, Double) -> String,
                              update: (Double) -> Unit) {
                control.clearOnChangeListeners()
                control.clearOnSliderTouchListeners()
                control.isSaveEnabled = false
                control.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) onInteractionFinished()
                    false
                }
                control.stepSize = 0f
                control.valueFrom = 0f
                control.valueTo = 1f
                control.setLabelFormatter { raw ->
                    val current = currentBand() ?: band
                    val range = bounds(current)
                    label(current, logarithmicValue(raw, range.start, range.endInclusive))
                }
                control.addOnChangeListener { _, raw, fromUser ->
                    if (fromUser) {
                        val current = currentBand() ?: return@addOnChangeListener
                        val range = bounds(current)
                        update(draggedValue(raw, range.start, range.endInclusive))
                    }
                }
                control.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {
                        if (!editing && slider.isEnabled) { markUsed(band.id); onInteractionStarted(band.id) }
                    }
                    override fun onStopTrackingTouch(slider: Slider) = onInteractionFinished()
                })
            }
            bindLogSlider(binding.peqQSlider, { qualityBounds(it, state.qRange) }, ::dragQuality,
                { current, value -> "${if (usesBandwidth(current)) "BW" else "Q"} ${fixedNumber(value, 3)}" }, ::userQuality)
            bindLogSlider(binding.peqFrequencySlider, { PeqModel.MIN_FREQUENCY..PeqModel.MAX_FREQUENCY },
                { position, _, _ -> dragFrequency(position) }, { _, value -> "${number(value, 0)} Hz" }, ::userFrequency)
            val origin = PeqSystemPresetOrigins(context).find(band.id)
            if (origin != null) {
                SliderDoubleTapReset.clear(slider)
                fun resetImported(field: PeqEditField) {
                    val current = currentBand() ?: return
                    if (!canEdit(current.id, field)) return
                    val next = when (field) {
                        PeqEditField.GAIN -> current.copy(gain = origin.gain)
                        PeqEditField.QUALITY -> origin.restoreQuality(current)
                        PeqEditField.FREQUENCY -> withFrequency(current, origin.frequency, state.qRange).let {
                            if (locks.isFieldLocked(current.id, PeqEditField.QUALITY)) it.copy(q = current.q) else it
                        }
                        else -> return
                    }
                    markUsed(current.id)
                    changeBand(next)
                    onInteractionFinished()
                }
                listOf(slider to PeqEditField.GAIN, binding.peqQSlider to PeqEditField.QUALITY,
                    binding.peqFrequencySlider to PeqEditField.FREQUENCY).forEach { (control, field) ->
                    PeqSystemPresetSliderReset.install(control, onCancel = onInteractionFinished) { resetImported(field) }
                }
            } else {
                listOf(slider, binding.peqQSlider, binding.peqFrequencySlider).forEach(PeqSystemPresetSliderReset::clear)
                SliderDoubleTapReset.install(slider, onCancel = onInteractionFinished) {
                    userGain(0.0); onInteractionFinished()
                }
                listOf(binding.peqQSlider, binding.peqFrequencySlider).forEach { control ->
                    control.setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_CANCEL) onInteractionFinished()
                        false
                    }
                }
            }
            binding.peqQMinus.setOnClickListener { currentBand()?.let {
                if (selectInstead(band.id)) return@setOnClickListener
                userQuality(displayedQuality(it) - state.qStep)
                onInteractionFinished()
            } }
            binding.peqQPlus.setOnClickListener { currentBand()?.let {
                if (selectInstead(band.id)) return@setOnClickListener
                userQuality(displayedQuality(it) + state.qStep)
                onInteractionFinished()
            } }
            binding.peqFrequencyMinus.setOnClickListener { currentBand()?.let {
                if (selectInstead(band.id)) return@setOnClickListener
                userFrequency(it.frequency - state.frequencyStep)
                onInteractionFinished()
            } }
            binding.peqFrequencyPlus.setOnClickListener { currentBand()?.let {
                if (selectInstead(band.id)) return@setOnClickListener
                userFrequency(it.frequency + state.frequencyStep)
                onInteractionFinished()
            } }
            renderSliders()
            renderLabels()
        }
    }

    companion object {
        private val SELECTION_PAYLOAD = Any()
        private val RANK_PAYLOAD = Any()
        fun usesBandwidth(band: PeqBand): Boolean = band.qualityDisplay == PeqQualityDisplay.BW

        fun displayedQuality(band: PeqBand): Double = if (usesBandwidth(band))
            PeqResponse.bandwidthOctaves(band.q, band.frequency) else band.q

        fun qualityBounds(band: PeqBand, qRange: Double = PeqModel.MAX_Q): ClosedFloatingPointRange<Double> = if (usesBandwidth(band))
            PeqResponse.bandwidthOctaves(qRange, band.frequency)..
                PeqResponse.bandwidthOctaves(PeqModel.MIN_Q, band.frequency)
        else PeqModel.MIN_Q..qRange

        /** Range changes preserve stored Q; only an explicit quality edit applies the current limit. */
        fun withQuality(band: PeqBand, requested: Double, qRange: Double = PeqModel.MAX_Q): PeqBand {
            val bounds = qualityBounds(band, qRange)
            val value = requested.coerceIn(bounds.start, bounds.endInclusive)
            val q = if (!usesBandwidth(band)) value else when {
                value <= bounds.start -> qRange
                value >= bounds.endInclusive -> PeqModel.MIN_Q
                else -> PeqResponse.qFromBandwidth(value, band.frequency)
            }
            return band.copy(q = q.coerceIn(PeqModel.MIN_Q, qRange))
        }

        /** Keep the edited unit stable when moving its center frequency. */
        fun withFrequency(band: PeqBand, requested: Double, qRange: Double = PeqModel.MAX_Q): PeqBand {
            val frequency = requested.coerceIn(PeqModel.MIN_FREQUENCY, PeqModel.MAX_FREQUENCY)
            if (frequency == band.frequency) return band
            val q = if (usesBandwidth(band))
                PeqResponse.qFromBandwidth(displayedQuality(band), frequency).coerceIn(PeqModel.MIN_Q, qRange)
            else band.q
            return band.copy(frequency = frequency, q = q)
        }

        /** Drag precision is independent of the configured +/- button step. */
        fun dragGain(value: Float, range: Double): Double = when {
            value <= -range.toFloat() -> -range
            value >= range.toFloat() -> range
            else -> (round(value.toDouble() * 100.0) / 100.0).coerceIn(-range, range)
        }

        fun dragQuality(position: Float, minimum: Double, maximum: Double): Double =
            roundedLogarithmicValue(position, minimum, maximum, 1000.0)

        fun dragFrequency(position: Float): Double = roundedLogarithmicValue(position,
            PeqModel.MIN_FREQUENCY, PeqModel.MAX_FREQUENCY, 1.0)

        /** Keep endpoints exact, including frequency-dependent BW endpoints. */
        private fun roundedLogarithmicValue(position: Float, minimum: Double, maximum: Double,
                                            scale: Double): Double {
            if (position <= 0f) return minimum
            if (position >= 1f) return maximum
            val value = logarithmicValue(position, minimum, maximum)
            return (round(value * scale) / scale).coerceIn(minimum, maximum)
        }

        fun logarithmicPosition(value: Double, minimum: Double, maximum: Double): Float =
            if (minimum == maximum) 0f else
                (ln(value.coerceIn(minimum, maximum) / minimum) / ln(maximum / minimum)).toFloat().coerceIn(0f, 1f)

        fun logarithmicValue(position: Float, minimum: Double, maximum: Double): Double =
            (minimum * exp(position.coerceIn(0f, 1f) * ln(maximum / minimum))).coerceIn(minimum, maximum)

        fun number(value: Double, decimals: Int = 2): String =
            fixedNumber(value, decimals).let { if (decimals == 0) it else it.trimEnd('0').trimEnd('.') }

        fun fixedNumber(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

        fun typeLabel(context: Context, type: PeqFilterType): String = context.getString(when (type) {
            PeqFilterType.PEAK -> R.string.peq_filter_peak
            PeqFilterType.LOW_SHELF -> R.string.peq_filter_low_shelf
            PeqFilterType.HIGH_SHELF -> R.string.peq_filter_high_shelf
            PeqFilterType.LOW_PASS -> R.string.peq_filter_low_pass
            PeqFilterType.HIGH_PASS -> R.string.peq_filter_high_pass
            PeqFilterType.NOTCH -> R.string.peq_filter_notch
            PeqFilterType.BAND_PASS -> R.string.peq_filter_band_pass
        })
    }
}
