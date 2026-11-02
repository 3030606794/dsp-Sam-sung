package me.timschneeberger.rootlessjamesdsp.ui

import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.roundToInt

/** One compact, right-aligned presentation for parameter switches; listeners are untouched. */
object ParameterSwitchUi {
    fun configure(toggle: MaterialSwitch, label: TextView) {
        val density = toggle.resources.displayMetrics.density
        toggle.background = null
        toggle.minimumHeight = 0
        toggle.setPadding(0, 0, 0, 0)
        toggle.layoutParams?.let { parameters ->
            val height = (30f * density).roundToInt()
            if (parameters.height != height) { parameters.height = height; toggle.layoutParams = parameters }
        }
        val metrics = label.paint.fontMetrics
        val visualHeight = (metrics.descent - metrics.ascent).coerceIn(16f * density, 24f * density)
        val scale = visualHeight / (30f * density)
        toggle.scaleX = scale
        toggle.scaleY = scale
        fun align() {
            toggle.pivotX = if (toggle.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) 0f else toggle.width.toFloat()
            toggle.pivotY = toggle.height / 2f
        }
        if (toggle.getTag(me.timschneeberger.rootlessjamesdsp.R.id.switchWidget) != true) {
            toggle.setTag(me.timschneeberger.rootlessjamesdsp.R.id.switchWidget, true)
            toggle.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> align() }
        }
        align()
    }
}
