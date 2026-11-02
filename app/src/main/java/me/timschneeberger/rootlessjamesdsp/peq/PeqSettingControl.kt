package me.timschneeberger.rootlessjamesdsp.peq

import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter

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
    private var minimum: Double,
    private var maximum: Double,
    private val resolution: Double,
    initial: Double,
    private val integer: Boolean = false,
    private val logarithmic: Boolean = false
) {
    private var updating = false
    var changedByUser = false
        private set
    init {
        slider.valueFrom = if (logarithmic) 0f else minimum.toFloat()
        slider.valueTo = if (logarithmic) 1f else maximum.toFloat()
        slider.stepSize = if (integer && !logarithmic) 1f else 0f
        slider.setGlassLabelFormatter { format(valueAt(it)) }
        input.setText(format(initial))
        setSlider(initial)
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updating) {
                changedByUser = true
                updating = true
                input.setText(format(valueAt(value)))
                box.error = null
                updating = false
            }
        }
        input.doAfterTextChanged {
            if (!updating) {
                changedByUser = true
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
        slider.value = if (logarithmic) PeqBandAdapter.logarithmicPosition(value.coerceIn(minimum, maximum), minimum, maximum)
            else value.coerceIn(minimum, maximum).toFloat()
    }
    /** Runtime changes update validation and the thumb without rewriting a manually typed draft. */
    fun updateBounds(minimum: Double, maximum: Double, displayedValue: Double? = null) {
        require(minimum < maximum)
        this.minimum = minimum
        this.maximum = maximum
        updating = true
        slider.valueFrom = if (logarithmic) 0f else minimum.toFloat()
        slider.valueTo = if (logarithmic) 1f else maximum.toFloat()
        if (displayedValue != null) input.setText(format(displayedValue.coerceIn(minimum, maximum)))
        input.text?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { setSlider(it) }
        updating = false
        box.error = null
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
