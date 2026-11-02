package me.timschneeberger.rootlessjamesdsp.utils

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import java.util.WeakHashMap
import kotlin.math.abs

/** Adds reset without consuming ordinary slider taps, drags or scrolling gestures. */
object SliderDoubleTapReset {
    private val attached = WeakHashMap<Slider, Boolean>()
    fun isAttached(slider: Slider): Boolean = attached[slider] == true
    fun clear(slider: Slider) {
        (slider.getTag(R.id.slider_reset_binding) as? Binding)?.dispose()
        slider.setTag(R.id.slider_reset_binding, null)
        attached.remove(slider)
    }
    fun install(slider: Slider, defaultValue: Float = 0f, onCancel: () -> Unit = {}, resetLabel: String? = null,
                onReset: () -> Unit) = install(slider, defaultValue, onCancel, resetLabel, false, onReset)

    fun install(slider: Slider, defaultValue: Float = 0f, onCancel: () -> Unit = {}, resetLabel: String? = null,
                thumbOnly: Boolean, onReset: () -> Unit) {
        // Preference rows can rebind after the first tap changes their stored value.
        // Keep the detector alive across that bind so the second tap is still recognized.
        val binding = (slider.getTag(R.id.slider_reset_binding) as? Binding)
            ?: Binding(slider).also { slider.setTag(R.id.slider_reset_binding, it) }
        binding.updateAccessibilityLabel(resetLabel ?: slider.context.getString(R.string.slider_reset_zero))
        binding.defaultValue = defaultValue
        binding.onCancel = onCancel
        binding.onReset = onReset
        binding.thumbOnly = thumbOnly
        binding.attach()
        attached[slider] = true
    }

    @SuppressLint("ClickableViewAccessibility")
    private class Binding(private val slider: Slider) {
        var defaultValue = 0f
        var onReset: () -> Unit = {}
        var onCancel: () -> Unit = {}
        var thumbOnly = true
        private var firstDownOnThumb = false
        private var resetGesture = false
        private var accessibilityLabel: String? = null
        private var accessibilityAction = -1

        fun dispose() {
            slider.setOnTouchListener(null)
            if (accessibilityAction != -1) ViewCompat.removeAccessibilityAction(slider, accessibilityAction)
            accessibilityAction = -1
            resetGesture = false
            onReset = {}
            onCancel = {}
        }

        fun updateAccessibilityLabel(label: String) {
            if (accessibilityLabel == label) return
            if (accessibilityAction != -1) ViewCompat.removeAccessibilityAction(slider, accessibilityAction)
            accessibilityAction = ViewCompat.addAccessibilityAction(slider, label) { _, _ -> reset() }
            accessibilityLabel = label
        }

        private fun reset(): Boolean {
            if (!slider.isEnabled) return false
            slider.value = defaultValue.coerceIn(slider.valueFrom, slider.valueTo)
            onReset()
            return true
        }
        private val detector = GestureDetector(slider.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                firstDownOnThumb = onThumb(event)
                return true
            }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (thumbOnly && (!firstDownOnThumb || !onThumb(event))) return false
                resetGesture = reset()
                return resetGesture
            }
        }).apply { setIsLongpressEnabled(false) }

        private fun onThumb(event: MotionEvent): Boolean {
            val fraction = (slider.value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)
            val rtl = slider.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
            val x = slider.trackSidePadding + slider.trackWidth * (if (rtl) 1f - fraction else fraction)
            val radius = maxOf(slider.thumbRadius.toFloat(), 12 * slider.resources.displayMetrics.density)
            return abs(event.x - x) <= radius && abs(event.y - slider.height / 2f) <= radius
        }

        fun attach() {
            slider.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) resetGesture = false
                detector.onTouchEvent(event)
                val consumed = resetGesture
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    resetGesture = false
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) onCancel()
                // Consume the entire second tap, so ACTION_UP cannot move away from the default.
                consumed
            }
        }
    }
}
