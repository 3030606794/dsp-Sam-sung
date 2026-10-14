package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.DialogTextinputBinding
import kotlin.math.roundToInt

/** An integer tick represents the smallest real DSP step, not an approximate displayed float. */
class BassNumericPreference(context: Context, val control: BassParameters.Control,
    initialValue: Int = control.default) : Preference(context) {
    private var storedValue = initialValue.coerceIn(control.min, control.max)
    var value: Int
        get() = storedValue
        set(value) { updateValue(value, true) }

    private fun updateValue(value: Int, notify: Boolean) {
        val next = value.coerceIn(control.min, control.max)
        val changed = storedValue != next
        storedValue = next
        persistInt(next)
        if (changed && notify) notifyChanged()
    }

    init {
        key = control.key
        title = control.title
        isIconSpaceReserved = false
        layoutResource = R.layout.preference_bass_numeric
        setDefaultValue(initialValue)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        value = if (isPersistent && sharedPreferences?.contains(key) == true)
            BassParameters.value(sharedPreferences!!.all, control)
        else (defaultValue as? Int) ?: control.default
    }

    override fun getSummary(): CharSequence = control.label(value)

    /** Returns false when a linked range rejects this edit; the slider then restores its last value. */
    fun requestValue(candidate: Int): Boolean = acceptValue(candidate, true)

    private fun acceptValue(candidate: Int, notify: Boolean): Boolean {
        if (!isEnabled || candidate !in control.min..control.max) return false
        if (candidate == value) return true
        if (!callChangeListener(candidate)) return false
        updateValue(candidate, notify)
        return true
    }

    fun step(delta: Int): Boolean = requestValue((value.toLong() + delta).coerceIn(control.min.toLong(), control.max.toLong()).toInt())
    fun resetToDefault(): Boolean = requestValue(control.default)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isSaveFromParentEnabled = false
        val slider = holder.findViewById(R.id.seekbar) as Slider
        val number = holder.findViewById(R.id.seekbar_value) as TextView
        val minus = holder.findViewById(R.id.bass_decrement)
        val plus = holder.findViewById(R.id.bass_increment)
        val reset = holder.findViewById(R.id.bass_reset)
        slider.clearOnChangeListeners()
        slider.clearOnSliderTouchListeners()
        slider.isSaveEnabled = false
        slider.valueFrom = control.min.toFloat()
        slider.valueTo = control.max.toFloat()
        slider.stepSize = 1f
        slider.value = value.toFloat()
        slider.setLabelFormatter { control.label(it.roundToInt()) }
        slider.contentDescription = control.title
        slider.isEnabled = isEnabled
        number.isEnabled = isEnabled
        reset.isEnabled = isEnabled
        fun renderValue() {
            number.text = control.label(value)
            number.contentDescription = "${control.title}：${control.label(value)}，点击输入"
            minus.isEnabled = isEnabled && value > control.min
            plus.isEnabled = isEnabled && value < control.max
        }
        renderValue()
        minus.contentDescription = "${control.title}减少 ${control.label(1)}"
        plus.contentDescription = "${control.title}增加 ${control.label(1)}"
        reset.contentDescription = "${control.title}恢复 ${control.label(control.default)}"
        number.setOnClickListener { onClick() }
        minus.setOnClickListener { step(-1) }
        plus.setOnClickListener { step(1) }
        reset.setOnClickListener { resetToDefault() }
        slider.addOnChangeListener { _, candidate, fromUser ->
            if (fromUser) {
                // A drag must not recycle/rebind its own row between ACTION_MOVE events.
                acceptValue(candidate.roundToInt(), false)
                if (slider.value != value.toFloat()) slider.value = value.toFloat()
                renderValue()
            }
        }
    }

    override fun onClick() {
        if (!isEnabled) return
        val content = DialogTextinputBinding.inflate(LayoutInflater.from(context))
        content.textInputLayout.hint = "${control.label(control.min)} … ${control.label(control.max)}"
        content.textInputLayout.suffixText = control.unit
        content.text1.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
            (if (control.decimals > 0) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        content.text1.setText(control.number(value))
        content.text1.selectAll()
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(content.root)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = control.parse(content.text1.text?.toString().orEmpty())
                if (entered == null) content.textInputLayout.error =
                    "请输入 ${control.label(control.min)} 至 ${control.label(control.max)}，步长 ${control.label(1)}"
                else if (requestValue(entered)) dialog.dismiss()
                else content.textInputLayout.error = "跟踪下限必须小于上限"
            }
        }
        dialog.show()
    }
}
