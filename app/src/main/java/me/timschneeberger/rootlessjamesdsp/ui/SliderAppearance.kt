package me.timschneeberger.rootlessjamesdsp.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/** Visual styling only; keeps the slider's value formatter, touch target and callbacks intact. */
object SliderAppearance {
    fun apply(slider: Slider) {
        val density = slider.resources.displayMetrics.density
        slider.labelBehavior = LabelFormatter.LABEL_FLOATING
        slider.trackHeight = (4 * density).roundToInt().coerceAtLeast(1)
        slider.thumbRadius = (6 * density).roundToInt().coerceAtLeast(1)
        slider.thumbElevation = 0f
        slider.haloRadius = 0
        slider.haloTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        (slider.background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Color.TRANSPARENT))
        me.timschneeberger.rootlessjamesdsp.ui.glass.GlassControlUi.decorateSlider(slider)
    }
}
