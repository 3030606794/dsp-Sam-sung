package me.timschneeberger.rootlessjamesdsp.peq

import androidx.core.widget.doAfterTextChanged
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.round

/** Draft settings: typing and dragging stay in sync without touching the saved band bank. */
internal class PeqSettingControl(
    private val slider: Slider,
    private val input: TextInputEditText,
    private val box: TextInputLayout,
    private val minimum: Double,
    private val maximum: Double,
    private val resolution: Double,
    initial: Double,
    private val integer: Boolean = false,
    private val logarithmic: Boolean = false
) {
    private var updating = false
    init {
        slider.valueFrom = if (logarithmic) 0f else minimum.toFloat()
        slider.valueTo = if (logarithmic) 1f else maximum.toFloat()
        slider.stepSize = if (integer && !logarithmic) 1f else 0f
        slider.setLabelFormatter { format(valueAt(it)) }
        input.setText(format(initial))
        setSlider(initial)
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updating) {
                updating = true
                input.setText(format(valueAt(value)))
                box.error = null
                updating = false
            }
        }
        input.doAfterTextChanged {
            if (!updating) {
                val value = parse()
                box.error = null
                if (value != null) {
                    updating = true
                    setSlider(value)
                    updating = false
                }
            }
        }
    }
    private fun format(value: Double) = if (integer) value.toInt().toString() else PeqBandAdapter.number(value, 6)
    private fun valueAt(position: Float): Double {
        val value = if (logarithmic) PeqBandAdapter.logarithmicValue(position, minimum, maximum) else position.toDouble()
        return (round(value / resolution) * resolution).coerceIn(minimum, maximum)
    }
    private fun setSlider(value: Double) {
        slider.value = if (logarithmic) PeqBandAdapter.logarithmicPosition(value, minimum, maximum) else value.toFloat()
    }
    private fun parse(): Double? = input.text?.toString()?.toDoubleOrNull()?.takeIf {
        it.isFinite() && it in minimum..maximum && (!integer || it == round(it))
    }
    fun validatedValue(): Double? {
        val value = parse()
        box.error = if (value == null) input.context.getString(R.string.peq_invalid_setting,
            format(minimum), format(maximum)) else null
        return value
    }
}
