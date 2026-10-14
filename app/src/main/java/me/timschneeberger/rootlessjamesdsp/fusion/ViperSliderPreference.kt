package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.DialogTextinputBinding
import kotlin.math.roundToInt

/** Native DSP slider presentation with the ViPER protocol's existing Int persistence. */
class ViperSliderPreference(context: Context, val control: ViperParameters.Control) : Preference(context) {
    val min: Int get() = control.min
    val max: Int get() = control.max
    private var storedValue = control.default
    var value: Int
        get() = storedValue
        set(value) { updateValue(value, true) }

    init {
        key = control.key
        title = control.title
        isIconSpaceReserved = false
        layoutResource = R.layout.preference_materialslider
        setDefaultValue(control.default)
    }

    override fun getSummary(): CharSequence = control.label(value)

    override fun onSetInitialValue(defaultValue: Any?) {
        updateValue(getPersistedInt((defaultValue as? Int) ?: control.default), false)
    }

    private fun updateValue(value: Int, notify: Boolean) {
        val next = value.coerceIn(min, max)
        val changed = storedValue != next
        storedValue = next
        persistInt(next)
        if (notify && changed) notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // Recycler rows share child IDs. Persisted values must win over view-hierarchy state.
        holder.itemView.isSaveFromParentEnabled = false
        val slider = holder.findViewById(R.id.seekbar) as Slider
        val label = holder.findViewById(R.id.seekbar_value) as TextView
        slider.clearOnChangeListeners()
        slider.clearOnSliderTouchListeners()
        slider.isSaveEnabled = false
        slider.valueFrom = min.toFloat()
        slider.valueTo = max.toFloat()
        slider.stepSize = 1f
        slider.value = value.toFloat()
        slider.isEnabled = isEnabled
        slider.contentDescription = title
        slider.setLabelFormatter { control.label(it.roundToInt()) }
        label.text = control.label(value)

        fun acceptValue() {
            val next = slider.value.roundToInt().coerceIn(min, max)
            if (next != value && callChangeListener(next)) updateValue(next, false)
            slider.value = value.toFloat()
            label.text = control.label(value)
        }
        slider.addOnChangeListener { _, newValue, fromUser ->
            label.text = control.label(newValue.roundToInt())
            // Persist each actual user step so the existing preference broadcast
            // reaches the driver while the finger is still on the slider.
            if (fromUser) acceptValue()
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                // Keep the final-value check for release/cancel without generating
                // another preference write when that step was already accepted.
                acceptValue()
            }
        })
    }

    override fun onClick() {
        if (control.choices.isNotEmpty()) {
            val builder = MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setSingleChoiceItems(control.choices.toTypedArray(), value) { dialog, which ->
                    if (callChangeListener(which)) value = which
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
            if (control.key == "v_bass_mode") builder.setNeutralButton(R.string.bass_reset_value) { _, _ ->
                if (callChangeListener(control.default)) value = control.default
            }
            builder.show()
            return
        }
        val content = DialogTextinputBinding.inflate(LayoutInflater.from(context))
        content.textInputLayout.hint = title
        content.textInputLayout.suffixText = control.unit
        content.text1.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        content.text1.setText(value.toString())
        content.text1.selectAll()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.slider_dialog_title)
            .setView(content.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = content.text1.text?.toString()?.trim()?.toIntOrNull()
                if (entered == null || entered !in min..max) {
                    content.textInputLayout.error = context.getString(R.string.viper_slider_integer_range, min, max)
                } else if (callChangeListener(entered)) {
                    value = entered
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
