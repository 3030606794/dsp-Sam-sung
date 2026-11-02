package me.timschneeberger.rootlessjamesdsp.eq

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.view.EqualizerSurface
import java.util.Locale
import kotlin.math.round

/** Edits a private draft. Only the Save callback writes the preset library. */
internal object EqPresetEditorDialog {
    fun create(
        context: Context,
        preset: EqPreset,
        save: (List<Double>, String?, String?) -> Unit,
    ): AlertDialog {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
            isFocusableInTouchMode = true
        }
        fun option(title: String, labelsId: Int, valuesId: Int, selected: String?, tagName: String): Spinner {
            body.addView(TextView(context).apply {
                text = title
                textSize = 14f
                setPadding(dp(4), dp(8), 0, 0)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            })
            val values = context.resources.getStringArray(valuesId)
            return Spinner(context).apply {
                tag = tagName
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item,
                    listOf("保留当前") + context.resources.getStringArray(labelsId).toList()).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(if (selected == null) 0 else values.indexOf(selected) + 1)
                body.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        val filter = option(context.getString(R.string.eq_filter_type), R.array.eq_filter_types,
            R.array.eq_filter_types_values, preset.filterType, "eq_preset_edit_filter")
        val interpolation = option(context.getString(R.string.eq_interpolator), R.array.eq_interpolators,
            R.array.eq_interpolators_values, preset.interpolation, "eq_preset_edit_interpolation")
        val initialTexts = preset.gains.map { String.format(Locale.ROOT, "%.1f", it) }
        val inputs = preset.gains.mapIndexed { index, _ ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply {
                text = "${EqualizerSurface.SCALE[index].toInt()} Hz"
                textSize = 13f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            }, LinearLayout.LayoutParams(dp(68), -2))
            val input = EditText(context).apply {
                tag = "eq_preset_edit_gain_$index"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                setSingleLine()
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(2), 0, dp(2), 0)
                minimumWidth = 0
                setText(initialTexts[index])
                contentDescription = "${EqualizerSurface.SCALE[index].toInt()} Hz 增益，dB"
            }
            fun nudge(delta: Double) {
                val value = input.text.toString().trim().toDoubleOrNull()
                if (value == null || !value.isFinite() || value !in -12.0..12.0) {
                    input.error = "请输入 −12 至 +12 dB"
                } else {
                    input.error = null
                    input.setText(String.format(Locale.ROOT, "%.1f", (round(value * 10) / 10 + delta).coerceIn(-12.0, 12.0)))
                }
            }
            fun step(label: String, delta: Double, tagName: String) =
                MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    tag = tagName
                    text = label
                    textSize = 20f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(0, 0, 0, 0)
                    contentDescription = "${EqualizerSurface.SCALE[index].toInt()} Hz ${if (delta > 0) "增加" else "减少"} 1 dB"
                    setOnClickListener { nudge(delta) }
                }
            row.addView(step("−", -1.0, "eq_preset_edit_minus_$index"), LinearLayout.LayoutParams(dp(48), dp(48)))
            row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(step("+", 1.0, "eq_preset_edit_plus_$index"), LinearLayout.LayoutParams(dp(48), dp(48)))
            body.addView(row, LinearLayout.LayoutParams(-1, dp(52)))
            input
        }
        val scroll = ScrollView(context).apply {
            tag = "eq_preset_edit_scroll"
            addView(body)
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle("编辑预设：${preset.name}")
            .setView(scroll).setPositiveButton("保存", null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            body.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val gains = inputs.mapIndexed { index, input ->
                    val value = input.text.toString().trim().toDoubleOrNull()
                    if (value == null || !value.isFinite() || value !in -12.0..12.0) {
                        input.error = "请输入 −12 至 +12 dB"
                        input.requestFocus()
                        return@setOnClickListener
                    }
                    // Imported presets can carry more precision than the editing step.
                    // Preserve every untouched band; quantize only a changed field.
                    if (input.text.toString() == initialTexts[index]) preset.gains[index] else round(value * 10) / 10
                }
                fun selection(spinner: Spinner, values: Int): String? = if (spinner.selectedItemPosition == 0) null
                    else context.resources.getStringArray(values)[spinner.selectedItemPosition - 1]
                try {
                    save(gains, selection(filter, R.array.eq_filter_types_values),
                        selection(interpolation, R.array.eq_interpolators_values))
                    dialog.dismiss()
                } catch (failure: Exception) {
                    inputs.first().error = failure.message ?: "保存失败"
                }
            }
        }
        return dialog
    }
}
