package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.view.LayoutInflater
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.abs

/** The host tracks and shows this dialog, and saves only after the confirmation callback. */
object PeqResizeDialog {
    fun create(
        context: Context,
        inflater: LayoutInflater,
        currentCount: Int,
        targetCount: Int,
        onConfirm: (edge: PeqResizeEdge, redistribute: Boolean) -> Unit
    ): AlertDialog {
        require(currentCount in 0..PeqModel.MAX_BANDS)
        require(targetCount in 1..PeqModel.MAX_BANDS && targetCount != currentCount)
        val view = inflater.inflate(R.layout.peq_resize_dialog, null)
        val edges = view.findViewById<RadioGroup>(R.id.peq_resize_edges)
        val front = view.findViewById<MaterialRadioButton>(R.id.peq_resize_front)
        val back = view.findViewById<MaterialRadioButton>(R.id.peq_resize_back)
        val redistribute = view.findViewById<MaterialSwitch>(R.id.peq_resize_redistribute)
        val summary = view.findViewById<TextView>(R.id.peq_resize_summary)
        val positionSummary = view.findViewById<TextView>(R.id.peq_resize_position_summary)
        val frequencySummary = view.findViewById<TextView>(R.id.peq_resize_frequency_summary)
        val increasing = targetCount > currentCount
        val difference = abs(targetCount - currentCount)
        summary.text = context.getString(if (increasing) R.string.peq_resize_increase_summary
            else R.string.peq_resize_decrease_summary, currentCount, targetCount, difference)
        front.setText(if (increasing) R.string.peq_resize_add_front else R.string.peq_resize_remove_front)
        back.setText(if (increasing) R.string.peq_resize_add_back else R.string.peq_resize_remove_back)
        edges.check(R.id.peq_resize_back)
        redistribute.isChecked = false
        fun selectedEdge() = if (edges.checkedRadioButtonId == R.id.peq_resize_front)
            PeqResizeEdge.FRONT else PeqResizeEdge.BACK
        fun updateSummary() {
            val fromFront = selectedEdge() == PeqResizeEdge.FRONT
            val positionText = when {
                increasing && fromFront -> R.string.peq_resize_add_front_summary
                increasing -> R.string.peq_resize_add_back_summary
                fromFront -> R.string.peq_resize_remove_front_summary
                else -> R.string.peq_resize_remove_back_summary
            }
            positionSummary.text = context.getString(positionText, difference)
            frequencySummary.text = when {
                redistribute.isChecked -> context.getString(R.string.peq_resize_redistribute_summary, targetCount)
                increasing -> context.getString(R.string.peq_resize_preserve_add_summary)
                else -> context.getString(R.string.peq_resize_preserve_remove_summary)
            }
        }
        edges.setOnCheckedChangeListener { _, _ -> updateSummary() }
        redistribute.setOnCheckedChangeListener { _, _ -> updateSummary() }
        updateSummary()
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.peq_resize_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm(selectedEdge(), redistribute.isChecked) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
