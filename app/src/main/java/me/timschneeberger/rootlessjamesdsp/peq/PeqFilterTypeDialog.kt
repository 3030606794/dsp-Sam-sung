package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.roundToInt

/** Compact filter chooser. Its host owns draft persistence and the audio preview lifetime. */
object PeqFilterTypeDialog {
    private class MonitorControl(val update: (Boolean, Boolean) -> Unit)

    fun create(
        context: Context,
        title: CharSequence,
        initialType: PeqFilterType?,
        onTypeSelected: (PeqFilterType) -> Unit,
        onMonitorChanged: (Boolean) -> Unit,
        onConfirm: (PeqFilterType) -> Boolean,
        onDismiss: () -> Unit,
        lockedHint: CharSequence? = null
    ): AlertDialog {
        val root = LayoutInflater.from(context).inflate(R.layout.peq_filter_type_dialog, null)
        root.findViewById<TextView>(R.id.peq_filter_dialog_title).text = title
        root.findViewById<TextView>(R.id.peq_filter_lock_hint).apply {
            text = lockedHint
            isVisible = !lockedHint.isNullOrBlank()
        }
        val types = root.findViewById<MaterialButtonToggleGroup>(R.id.peq_filter_types)
        val scroll = root.findViewById<HorizontalScrollView>(R.id.peq_filter_scroll)
        val monitor = root.findViewById<MaterialButton>(R.id.peq_filter_monitor)
        val confirm = root.findViewById<MaterialButton>(R.id.peq_filter_confirm)
        val surfaceText = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface)
        val primaryContainer = MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimaryContainer)
        val onPrimaryContainer = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnPrimaryContainer)
        val outline = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOutline)
        val monitorOff = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val monitorOn = Color.rgb(46, 125, 50)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        var selected = initialType
        var listening = false
        val buttons = PeqFilterType.entries.associateWith { type ->
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = buttonId(type)
                text = PeqBandAdapter.typeLabel(context, type)
                contentDescription = text
                setTextSize(14f)
                minWidth = dp(62)
                minimumWidth = dp(62)
                minHeight = 0
                minimumHeight = 0
                maxLines = 1
                setPadding(dp(10), 0, dp(10), 0)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(24)
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(outline)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
                types.addView(this)
            }
        }
        fun render() {
            buttons.forEach { (type, button) ->
                val checked = selected == type
                button.backgroundTintList = ColorStateList.valueOf(when {
                    !checked -> Color.TRANSPARENT
                    listening -> monitorOn
                    else -> primaryContainer
                })
                button.setTextColor(when {
                    !checked -> surfaceText
                    listening -> Color.WHITE
                    else -> onPrimaryContainer
                })
            }
            monitor.isSelected = listening
            monitor.setTextColor(if (listening) monitorOn else monitorOff)
            monitor.contentDescription = context.getString(if (listening)
                R.string.peq_live_monitor_on else R.string.peq_live_monitor_off)
            confirm.isEnabled = selected != null
        }
        fun monitoring(enabled: Boolean, notify: Boolean) {
            if (listening == enabled) return
            listening = enabled
            render()
            if (notify) onMonitorChanged(enabled)
        }
        root.tag = MonitorControl(::monitoring)
        initialType?.let { types.check(buttonId(it)) }
        types.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val type = PeqFilterType.entries.first { buttonId(it) == checkedId }
            if (selected != type) {
                selected = type
                render()
                onTypeSelected(type)
            }
        }
        render()
        val dialog = MaterialAlertDialogBuilder(context).setView(root).create()
        root.findViewById<View>(R.id.peq_filter_cancel).setOnClickListener { dialog.dismiss() }
        monitor.setOnClickListener { monitoring(!listening, true) }
        confirm.setOnClickListener { selected?.let { if (onConfirm(it)) dialog.dismiss() } }
        dialog.setOnDismissListener {
            monitoring(false, false)
            onDismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setGravity(Gravity.BOTTOM)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            initialType?.let { type -> scroll.post {
                val button = buttons.getValue(type)
                scroll.smoothScrollTo((button.left + button.width / 2 - scroll.width / 2).coerceAtLeast(0), 0)
            } }
        }
        return dialog
    }

    /** Host lifecycle cleanup can reset appearance without starting another preview callback. */
    fun setMonitoring(dialog: AlertDialog, enabled: Boolean, notify: Boolean = false) {
        val control = dialog.findViewById<View>(R.id.peq_filter_dialog_root)?.tag as? MonitorControl
        control?.update?.invoke(enabled, notify)
    }

    fun buttonId(type: PeqFilterType): Int = when (type) {
        PeqFilterType.PEAK -> R.id.peq_filter_peak
        PeqFilterType.LOW_SHELF -> R.id.peq_filter_low_shelf
        PeqFilterType.HIGH_SHELF -> R.id.peq_filter_high_shelf
        PeqFilterType.LOW_PASS -> R.id.peq_filter_low_pass
        PeqFilterType.HIGH_PASS -> R.id.peq_filter_high_pass
        PeqFilterType.NOTCH -> R.id.peq_filter_notch
        PeqFilterType.BAND_PASS -> R.id.peq_filter_band_pass
    }
}
