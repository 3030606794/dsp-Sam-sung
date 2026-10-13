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
        val types = root.findViewById<PeqFilterSelector>(R.id.peq_filter_types)
        val monitor = root.findViewById<MaterialButton>(R.id.peq_filter_monitor)
        val confirm = root.findViewById<MaterialButton>(R.id.peq_filter_confirm)
        val monitorOff = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val monitorOn = Color.rgb(46, 125, 50)
        var listening = false
        types.committedType = initialType
        types.setSelection(initialType)
        fun render() {
            types.monitoring = listening
            monitor.isSelected = listening
            monitor.backgroundTintList = android.content.res.ColorStateList.valueOf(if (listening) monitorOn else Color.TRANSPARENT)
            monitor.setTextColor(if (listening) Color.WHITE else monitorOff)
            monitor.contentDescription = context.getString(if (listening)
                R.string.peq_live_monitor_on else R.string.peq_live_monitor_off)
            confirm.isEnabled = types.selectedType != null
        }
        fun monitoring(enabled: Boolean, notify: Boolean) {
            if (listening == enabled) return
            listening = enabled
            render()
            if (notify) onMonitorChanged(enabled)
        }
        root.tag = MonitorControl(::monitoring)
        types.onTypeSelected = { type ->
            render()
            onTypeSelected(type)
        }
        render()
        val dialog = MaterialAlertDialogBuilder(context).setView(root).create()
        dialog.window?.apply {
            setGravity(Gravity.CENTER)
            setWindowAnimations(0)
        }
        root.findViewById<View>(R.id.peq_filter_cancel).setOnClickListener { dialog.dismiss() }
        monitor.setOnClickListener { monitoring(!listening, true) }
        confirm.setOnClickListener { types.selectedType?.let { if (onConfirm(it)) dialog.dismiss() } }
        dialog.setOnDismissListener {
            monitoring(false, false)
            onDismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setGravity(Gravity.CENTER)
                setWindowAnimations(0)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
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
