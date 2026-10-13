package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.roundToInt

data class PeqInlineFilterDraft(val bandId: String, val type: PeqFilterType, val monitoring: Boolean = false)

sealed interface PeqInlineFilterAction {
    data class Select(val type: PeqFilterType) : PeqInlineFilterAction
    data class Monitor(val enabled: Boolean) : PeqInlineFilterAction
    data object Confirm : PeqInlineFilterAction
    data object Cancel : PeqInlineFilterAction
    data object Unavailable : PeqInlineFilterAction
}

/** Eleven fixed positions; the Fragment owns drafts and any live audio preview. */
class PeqInlineFilterRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    var onAction: ((PeqInlineFilterAction) -> Unit)? = null
    var onToggleLock: (() -> Unit)? = null
    var showFilterIcons: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            types.forEach { (type, cell) ->
                val icon = if (value) AppCompatResources.getDrawable(context, iconId(type))?.mutate()?.apply {
                    setBounds(0, 0, dp(22), dp(16))
                } else null
                cell.setCompoundDrawables(null, null, null, icon)
                cell.compoundDrawablePadding = if (value) dp(3) else 0
                cell.compoundDrawableTintList = cell.textColors
            }
            for (index in 0 until choices.childCount) {
                val child = choices.getChildAt(index)
                child.layoutParams = child.layoutParams.apply { height = dp(if (value) 56 else 40) }
            }
        }
    private var editable = false
    private var lockEditable = false
    private var listening = false
    private val normalText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val purple = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
    private val purpleText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer)
    private val green = Color.rgb(46, 125, 50)
    private val border = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
    private val feedback = TypedValue().also {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
    }.resourceId
    private val types = linkedMapOf<PeqFilterType, TextView>()
    private val choices = object : LinearLayout(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = border; strokeWidth = dp(1).toFloat() }
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            canvas.drawLine(0f, 0.5f, width.toFloat(), 0.5f, paint)
            for (index in 1 until childCount) {
                val x = getChildAt(index).left.toFloat()
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            }
        }
    }.apply {
        id = R.id.peq_inline_choices
        orientation = HORIZONTAL
        // Text-only actions and text-plus-curve choices have different baselines.
        // Align their complete cells so no compound drawable is pushed below the row.
        isBaselineAligned = false
        gravity = Gravity.CENTER_VERTICAL
    }
    private val monitor = textCell(context.getString(R.string.peq_inline_monitor), R.id.peq_inline_monitor)
    private val lock = ImageView(context).apply {
        id = R.id.peq_inline_lock
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(9), dp(9), dp(9), dp(9))
        layoutParams = LayoutParams(0, dp(40), 1f)
        isClickable = true
        isFocusable = true
        if (feedback != 0) foreground = context.getDrawable(feedback)
        setOnClickListener { if (lockEditable) onToggleLock?.invoke() }
    }
    private val cancel = textCell(context.getString(android.R.string.cancel), R.id.peq_inline_cancel)
    private val confirm = textCell(context.getString(R.string.peq_inline_confirm), R.id.peq_inline_confirm)

    init {
        orientation = VERTICAL
        choices.addView(cancel)
        PeqFilterType.entries.forEach { type ->
            val cell = textCell(PeqBandAdapter.typeLabel(context, type), PeqFilterTypeDialog.buttonId(type))
            cell.setOnClickListener { if (editable) onAction?.invoke(PeqInlineFilterAction.Select(type)) }
            types[type] = cell
            choices.addView(cell)
        }
        choices.addView(monitor)
        choices.addView(confirm)
        choices.addView(lock)
        addView(choices, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        monitor.setOnClickListener { if (editable) onAction?.invoke(PeqInlineFilterAction.Monitor(!listening)) }
        cancel.setOnClickListener { if (editable && cancel.isEnabled) onAction?.invoke(PeqInlineFilterAction.Cancel) }
        confirm.setOnClickListener { if (editable && confirm.isEnabled) onAction?.invoke(PeqInlineFilterAction.Confirm) }
    }

    fun bind(committed: PeqFilterType, draft: PeqFilterType?, monitoring: Boolean,
             canEdit: Boolean, locked: Boolean, canToggleLock: Boolean) {
        editable = canEdit
        lockEditable = canToggleLock
        listening = monitoring
        types.forEach { (type, cell) ->
            val saved = type == committed
            val pending = type == draft && !saved
            cell.isEnabled = canEdit
            cell.alpha = if (canEdit) 1f else 0.45f
            cell.isSelected = type == (draft ?: committed)
            cell.background = if (pending && !monitoring) GradientDrawable().apply {
                setColor(Color.TRANSPARENT); setStroke(dp(2), purpleText)
            } else android.graphics.drawable.ColorDrawable(when {
                saved -> purple
                pending && monitoring -> green
                else -> Color.TRANSPARENT
            })
            val foreground = when { saved -> purpleText; pending && monitoring -> Color.WHITE; else -> normalText }
            cell.setTextColor(foreground)
            cell.compoundDrawableTintList = ColorStateList.valueOf(foreground)
        }
        monitor.isEnabled = canEdit
        monitor.isSelected = monitoring
        monitor.setBackgroundColor(if (monitoring) green else Color.TRANSPARENT)
        monitor.setTextColor(if (monitoring) Color.WHITE else normalText)
        monitor.alpha = if (canEdit) 1f else 0.45f
        monitor.contentDescription = context.getString(if (monitoring) R.string.peq_live_monitor_on else R.string.peq_live_monitor_off)
        lock.isEnabled = canToggleLock
        lock.setImageResource(if (locked) R.drawable.ic_peq_lock else R.drawable.ic_peq_lock_open)
        lock.imageTintList = ColorStateList.valueOf(if (locked) Color.rgb(211, 47, 47) else Color.GRAY)
        lock.alpha = if (canToggleLock) 1f else 0.45f
        lock.contentDescription = context.getString(if (locked) R.string.peq_unlock_parameter else R.string.peq_lock_parameter,
            context.getString(R.string.peq_filter_type))
        cancel.isEnabled = canEdit && draft != null
        confirm.isEnabled = canEdit && draft != null && draft != committed
        cancel.setTextColor(normalText)
        confirm.setTextColor(normalText)
        cancel.alpha = if (cancel.isEnabled) 1f else 0.45f
        confirm.alpha = if (confirm.isEnabled) 1f else 0.45f
    }

    private fun textCell(label: String, identifier: Int) = TextView(context).apply {
        id = identifier
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        includeFontPadding = false
        maxLines = 1
        setPadding(0, 0, 0, 0)
        layoutParams = LayoutParams(0, dp(40), 1f)
        isClickable = true
        isFocusable = true
        if (feedback != 0) foreground = context.getDrawable(feedback)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun iconId(type: PeqFilterType): Int = when (type) {
        PeqFilterType.PEAK -> R.drawable.ic_peq_filter_peak
        PeqFilterType.LOW_SHELF -> R.drawable.ic_peq_filter_low_shelf
        PeqFilterType.HIGH_SHELF -> R.drawable.ic_peq_filter_high_shelf
        PeqFilterType.LOW_PASS -> R.drawable.ic_peq_filter_low_pass
        PeqFilterType.HIGH_PASS -> R.drawable.ic_peq_filter_high_pass
        PeqFilterType.NOTCH -> R.drawable.ic_peq_filter_notch
        PeqFilterType.BAND_PASS -> R.drawable.ic_peq_filter_band_pass
    }
}
