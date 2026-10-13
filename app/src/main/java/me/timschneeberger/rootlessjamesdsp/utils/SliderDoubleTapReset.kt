package me.timschneeberger.rootlessjamesdsp.utils

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R

/** Adds reset without consuming ordinary slider taps, drags or scrolling gestures. */
object SliderDoubleTapReset {
    fun install(slider: Slider, defaultValue: Float = 0f, onCancel: () -> Unit = {}, resetLabel: String? = null, onReset: () -> Unit) {
        // Preference rows can rebind after the first tap changes their stored value.
        // Keep the detector alive across that bind so the second tap is still recognized.
        val binding = (slider.getTag(R.id.slider_reset_binding) as? Binding)
            ?: Binding(slider).also { slider.setTag(R.id.slider_reset_binding, it) }
        binding.updateAccessibilityLabel(resetLabel ?: slider.context.getString(R.string.slider_reset_zero))
        binding.defaultValue = defaultValue
        binding.onCancel = onCancel
        binding.onReset = onReset
    }

    @SuppressLint("ClickableViewAccessibility")
    private class Binding(private val slider: Slider) {
        var defaultValue = 0f
        var onReset: () -> Unit = {}
        var onCancel: () -> Unit = {}
        private var resetGesture = false
        private var accessibilityLabel: String? = null
        private var accessibilityAction = -1

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
            override fun onDown(event: MotionEvent) = true
            override fun onDoubleTap(event: MotionEvent): Boolean {
                resetGesture = reset()
                return resetGesture
            }
        }).apply { setIsLongpressEnabled(false) }

        init {
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
