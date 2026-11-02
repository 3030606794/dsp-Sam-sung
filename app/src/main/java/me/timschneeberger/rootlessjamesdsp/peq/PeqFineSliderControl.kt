package me.timschneeberger.rootlessjamesdsp.peq

import android.view.KeyEvent
import android.view.View
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlin.math.round

/** Updating a thumb never rewrites a precise or invalid typed draft. */
internal class PeqFineSliderControl(
    private val slider: Slider,
    private val input: TextInputEditText,
    private val range: () -> ClosedFloatingPointRange<Double>,
    private val step: () -> Double,
    private val logarithmic: Boolean = false,
    private val formatValue: (Double, Boolean) -> String = { value, _ -> PeqBandAdapter.number(value, 6) }
) {
    private var updating = false
    init {
        slider.valueFrom = 0f
        slider.valueTo = 1f
        slider.stepSize = 0f
        slider.addOnChangeListener { _, position, fromUser ->
            if (fromUser && !updating && slider.isEnabled) {
                val bounds = range()
                val raw = if (logarithmic) PeqBandAdapter.logarithmicValue(position, bounds.start, bounds.endInclusive)
                    else bounds.start + position * (bounds.endInclusive - bounds.start)
                val endpoint = position <= 0f || position >= 1f
                val value = when {
                    position <= 0f -> bounds.start
                    position >= 1f -> bounds.endInclusive
                    else -> (round(raw / step()) * step()).coerceIn(bounds.start, bounds.endInclusive)
                }
                input.setText(formatValue(value, endpoint || value == bounds.start || value == bounds.endInclusive))
            }
        }
        // Material's continuous slider advances by 1 on arrow keys. On our normalized 0..1
        // track that means the entire range, so keyboard edits must use the actual parameter unit.
        slider.setOnKeyListener { _, keyCode, event ->
            if (!slider.isEnabled || (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT))
                return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener event.action == KeyEvent.ACTION_UP
            val current = input.text?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: return@setOnKeyListener true
            val bounds = range()
            val forward = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) != (slider.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            val value = (current + (if (forward) step() else -step())).coerceIn(bounds)
            input.setText(formatValue(value, value == bounds.start || value == bounds.endInclusive))
            true
        }
        input.doAfterTextChanged { refresh() }
        refresh()
    }

    fun refresh() {
        if (updating) return
        val value = input.text?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return
        val bounds = range()
        updating = true
        slider.value = if (bounds.start == bounds.endInclusive) 0f
            else if (logarithmic) PeqBandAdapter.logarithmicPosition(value.coerceIn(bounds), bounds.start, bounds.endInclusive)
            else ((value.coerceIn(bounds) - bounds.start) / (bounds.endInclusive - bounds.start)).toFloat()
        updating = false
    }
}
