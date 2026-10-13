package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.R

/** Compact filter chooser. Its host owns draft persistence and the audio preview lifetime. */
object PeqFilterTypeDialog {
    private class MonitorControl(
        val update: (Boolean, Boolean) -> Unit,
        val synchronize: (PeqFilterType?, PeqFilterType?, Boolean) -> Unit
    )

    fun create(
        context: Context,
        title: CharSequence,
        initialType: PeqFilterType?,
        onTypeSelected: (PeqFilterType) -> Unit,
        onMonitorChanged: (Boolean) -> Unit,
        onConfirm: (PeqFilterType) -> Boolean,
        onDismiss: () -> Unit,
        lockedHint: CharSequence? = null,
        isLocked: (() -> Boolean)? = null,
        canToggleLock: () -> Boolean = { true },
        canEdit: () -> Boolean = { true },
        onToggleLock: () -> Unit = {}
    ): AlertDialog {
        val root = LayoutInflater.from(context).inflate(R.layout.peq_filter_type_dialog, null)
        root.findViewById<TextView>(R.id.peq_filter_dialog_title).apply {
            text = title
            if (isLocked == null) setPadding(0, paddingTop, 0, paddingBottom)
        }
        root.findViewById<TextView>(R.id.peq_filter_lock_hint).apply {
            text = lockedHint
            isVisible = !lockedHint.isNullOrBlank()
        }
        val types = root.findViewById<PeqFilterSelector>(R.id.peq_filter_types)
        val monitor = root.findViewById<MaterialButton>(R.id.peq_filter_monitor)
        val confirm = root.findViewById<MaterialButton>(R.id.peq_filter_confirm)
        val lock = root.findViewById<MaterialButton>(R.id.peq_filter_dialog_lock)
        val monitorOff = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val monitorOn = Color.rgb(46, 125, 50)
        var listening = false
        var committedType = initialType
        var acceptedSelection = initialType
        types.committedType = initialType
        types.setSelection(initialType)
        fun render() {
            val editable = canEdit()
            if (!editable) {
                listening = false
                acceptedSelection = committedType
                types.setSelection(committedType)
            }
            types.isEnabled = editable
            types.monitoring = listening
            lock.isVisible = isLocked != null
            lock.isEnabled = canToggleLock()
            val locked = isLocked?.invoke() == true
            lock.setIconResource(if (locked) R.drawable.ic_peq_lock else R.drawable.ic_peq_lock_open)
            lock.iconTint = android.content.res.ColorStateList.valueOf(if (locked) Color.rgb(211, 47, 47) else Color.GRAY)
            lock.alpha = if (lock.isEnabled) 1f else 0.45f
            lock.contentDescription = context.getString(if (locked) R.string.peq_unlock_parameter else R.string.peq_lock_parameter,
                context.getString(R.string.peq_filter_type))
            monitor.isEnabled = editable
            monitor.isSelected = listening
            monitor.backgroundTintList = android.content.res.ColorStateList.valueOf(if (listening) monitorOn else Color.TRANSPARENT)
            monitor.setTextColor(if (listening) Color.WHITE else monitorOff)
            monitor.contentDescription = context.getString(if (listening)
                R.string.peq_live_monitor_on else R.string.peq_live_monitor_off)
            confirm.isEnabled = editable && types.selectedType != null
        }
        fun monitoring(enabled: Boolean, notify: Boolean) {
            if (enabled && !canEdit()) { render(); return }
            if (listening == enabled) return
            listening = enabled
            render()
            if (notify) onMonitorChanged(enabled)
        }
        root.tag = MonitorControl(::monitoring) { committed, selected, enabled ->
            committedType = committed
            acceptedSelection = selected
            listening = enabled
            types.committedType = committed
            types.setSelection(selected)
            render()
        }
        types.onTypeSelected = typeSelected@ { type ->
            if (!canEdit()) {
                types.setSelection(acceptedSelection)
                render()
                return@typeSelected
            }
            acceptedSelection = type
            render()
            onTypeSelected(type)
        }
        render()
        val dialog = MaterialAlertDialogBuilder(context).setView(root).create()
        // Let the theme finish initializing the window before setting its final attributes.
        // Updating size or dim flags in OnShow can reconfigure an already attached window.
        dialog.create()
        dialog.window?.apply {
            attributes = attributes.apply {
                gravity = Gravity.CENTER
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                windowAnimations = 0
                flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            }
        }
        root.findViewById<View>(R.id.peq_filter_cancel).setOnClickListener { dialog.dismiss() }
        lock.setOnClickListener {
            if (isLocked != null && canToggleLock()) onToggleLock()
            render()
        }
        monitor.setOnClickListener {
            if (canEdit()) monitoring(!listening, true) else render()
        }
        confirm.setOnClickListener {
            if (canEdit()) types.selectedType?.let { if (onConfirm(it)) dialog.dismiss() } else render()
        }
        dialog.setOnDismissListener {
            monitoring(false, false)
            onDismiss()
        }
        return dialog
    }

    /** Host lifecycle cleanup can reset appearance without starting another preview callback. */
    fun setMonitoring(dialog: AlertDialog, enabled: Boolean, notify: Boolean = false) {
        val control = dialog.findViewById<View>(R.id.peq_filter_dialog_root)?.tag as? MonitorControl
        control?.update?.invoke(enabled, notify)
    }

    /** Mirrors a shared card/dialog draft without firing user callbacks or saving it. */
    fun setState(dialog: AlertDialog, committedType: PeqFilterType?, selectedType: PeqFilterType?, monitoring: Boolean) {
        val control = dialog.findViewById<View>(R.id.peq_filter_dialog_root)?.tag as? MonitorControl
        control?.synchronize?.invoke(committedType, selectedType, monitoring)
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
