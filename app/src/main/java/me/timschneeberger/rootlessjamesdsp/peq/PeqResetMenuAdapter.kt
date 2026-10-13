package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.roundToInt

/** Q and BW share storage but only operate on cards currently displaying that mode. */
internal enum class PeqBulkLockKind(val field: PeqEditField?, val mode: PeqQualityDisplay? = null) {
    BAND(null), GAIN(PeqEditField.GAIN), Q(PeqEditField.QUALITY, PeqQualityDisplay.Q),
    BW(PeqEditField.QUALITY, PeqQualityDisplay.BW), FREQUENCY(PeqEditField.FREQUENCY),
    FILTER_TYPE(PeqEditField.FILTER_TYPE)
}

/** Action rows explain their current scope; lock buttons update that scope without closing the menu. */
internal class PeqResetMenuAdapter(
    private val context: Context,
    private val labels: List<String>,
    private val lockKinds: Map<Int, PeqBulkLockKind>,
    private val ids: Set<String>,
    private val readState: () -> PeqState,
    private val locks: PeqBandLockStore,
    private val resetTargets: Map<Int, PeqResetTarget> = emptyMap(),
    private val redistributePositions: Set<Int> = emptySet(),
    private val onLocksChanged: () -> Unit
) : BaseAdapter() {
    data class Counts(val canLock: Int, val canUnlock: Int)

    override fun getCount() = labels.size
    override fun getItem(position: Int) = labels[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = true
    override fun getViewTypeCount() = 3
    override fun getItemViewType(position: Int) = when (position) {
        in lockKinds -> 1
        in resetTargets -> 2
        else -> 0
    }

    private fun targets(kind: PeqBulkLockKind) = readState().bands.filter {
        it.id in ids && (kind.mode == null || it.qualityDisplay == kind.mode)
    }

    private fun isLocked(id: String, kind: PeqBulkLockKind) =
        kind.field?.let { locks.isFieldLocked(id, it) } ?: locks.isBandLocked(id)

    fun counts(position: Int): Counts? = lockKinds[position]?.let { kind ->
        val targets = targets(kind)
        val locked = targets.count { isLocked(it.id, kind) }
        Counts(targets.size - locked, locked)
    }

    fun resetCounts(position: Int): PeqLockedOperations.ResetCounts? = resetTargets[position]?.let {
        PeqLockedOperations(locks::canEdit).resetCounts(readState(), ids, it)
    }

    private fun setLocked(kind: PeqBulkLockKind, locked: Boolean) {
        // Re-read the current bank and display modes when pressed, rather than using stale row counts.
        targets(kind).forEach { band ->
            kind.field?.let { locks.setFieldLocked(band.id, it, locked) }
                ?: locks.setBandLocked(band.id, locked)
        }
        onLocksChanged()
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val kind = lockKinds[position]
        val title = TextView(context).apply {
            id = R.id.peq_bulk_lock_title
            text = labels[position]
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            gravity = Gravity.CENTER_VERTICAL
        }
        if (kind == null && position !in resetTargets) return title.apply {
            minHeight = dp(48)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val summary = TextView(context).apply {
            if (kind != null) {
                val counts = requireNotNull(counts(position))
                id = R.id.peq_bulk_lock_counts
                text = context.getString(R.string.peq_bulk_lock_counts, counts.canLock, counts.canUnlock)
            } else {
                val counts = requireNotNull(resetCounts(position))
                id = R.id.peq_bulk_reset_counts
                text = context.getString(if (position in redistributePositions)
                    R.string.peq_bulk_redistribute_counts else R.string.peq_bulk_reset_counts,
                    counts.editableCount, counts.protectedCount) +
                    if (resetTargets[position] == PeqResetTarget.ALL)
                        "\n" + context.getString(R.string.peq_bulk_reset_unlocked_only) else ""
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(3), 0, 0)
        }
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(summary)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(20), dp(7), dp(12), dp(7))
            layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (kind != null) {
                val counts = requireNotNull(counts(position))
                addView(lockButton(position, kind, false, counts.canUnlock > 0))
                addView(lockButton(position, kind, true, counts.canLock > 0))
            }
        }
    }

    private fun lockButton(position: Int, kind: PeqBulkLockKind, locked: Boolean, enabled: Boolean) =
        AppCompatImageButton(context).apply {
            id = if (locked) R.id.peq_bulk_lock_button else R.id.peq_bulk_unlock_button
            setImageResource(if (locked) R.drawable.ic_peq_lock else R.drawable.ic_peq_lock_open)
            imageTintList = ColorStateList.valueOf(if (locked) Color.rgb(211, 47, 47) else Color.rgb(158, 158, 158))
            val feedback = TypedValue().also {
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            }.resourceId
            if (feedback != 0) setBackgroundResource(feedback) else setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            isEnabled = enabled
            // Keep taps on the row's text compatible with AlertDialog's existing list action.
            isFocusable = false
            alpha = if (enabled) 1f else 0.4f
            contentDescription = context.getString(if (locked) R.string.peq_bulk_lock_action else R.string.peq_bulk_unlock_action,
                labels[position])
            setOnClickListener { if (isEnabled) setLocked(kind, locked) }
        }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
