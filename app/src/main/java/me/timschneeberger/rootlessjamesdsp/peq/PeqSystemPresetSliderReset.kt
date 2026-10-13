package me.timschneeberger.rootlessjamesdsp.peq

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R

/** Imported-card reset gesture: both taps must start on the thumb, without delaying normal edits. */
object PeqSystemPresetSliderReset {
    fun install(slider: Slider, onCancel: () -> Unit = {}, onReset: () -> Unit) {
        val binding = (slider.getTag(R.id.peq_system_preset_slider_reset_binding) as? Binding)
            ?: Binding(slider).also { slider.setTag(R.id.peq_system_preset_slider_reset_binding, it) }
        binding.onCancel = onCancel
        binding.onReset = onReset
        binding.attach()
    }

    /** Clear when a recycled slider changes card identity or no longer belongs to a system preset. */
    fun clear(slider: Slider) {
        (slider.getTag(R.id.peq_system_preset_slider_reset_binding) as? Binding)?.dispose()
        slider.setTag(R.id.peq_system_preset_slider_reset_binding, null)
    }

    @SuppressLint("ClickableViewAccessibility")
    private class Binding(private val slider: Slider) : View.OnTouchListener {
        var onCancel: () -> Unit = {}
        var onReset: () -> Unit = {}
        private val configuration = ViewConfiguration.get(slider.context)
        private val touchSlop = configuration.scaledTouchSlop.toFloat()
        private val doubleTapSlop = configuration.scaledDoubleTapSlop.toFloat()
        private var firstTapUp = -1L
        private var firstTapX = 0f
        private var firstTapY = 0f
        private var downTime = 0L
        private var downX = 0f
        private var downY = 0f
        private var active = false
        private var thumbTap = false
        private var consumeTap = false
        private var accessibilityAction = -1

        fun attach() {
            // Rebinding replaces callbacks, but keeps the first tap and second-tap consumption alive.
            slider.setOnTouchListener(this)
            if (!slider.isEnabled) cancelCandidate()
            if (accessibilityAction == -1) accessibilityAction = ViewCompat.addAccessibilityAction(slider,
                slider.context.getString(R.string.peq_system_preset_restore_value)) { _, _ ->
                if (!slider.isEnabled) false else {
                    cancelCandidate()
                    onReset()
                    true
                }
            }
        }

        fun dispose() {
            val wasActive = active
            cancelCandidate()
            slider.setOnTouchListener(null)
            if (accessibilityAction != -1) ViewCompat.removeAccessibilityAction(slider, accessibilityAction)
            accessibilityAction = -1
            if (wasActive) onCancel()
            onCancel = {}
            onReset = {}
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (!slider.isEnabled) {
                cancelCandidate()
                return false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    consumeTap = false
                    val hitsThumb = hitsThumb(event.x, event.y)
                    val elapsed = event.eventTime - firstTapUp
                    val doubleTap = firstTapUp >= 0 && elapsed in 0..ViewConfiguration.getDoubleTapTimeout().toLong() &&
                        distanceSquared(event.x, event.y, firstTapX, firstTapY) <= doubleTapSlop * doubleTapSlop && hitsThumb
                    firstTapUp = -1
                    active = true
                    downTime = event.eventTime
                    downX = event.x
                    downY = event.y
                    thumbTap = hitsThumb
                    if (doubleTap) {
                        consumeTap = true
                        thumbTap = false
                        onReset()
                    }
                }
                MotionEvent.ACTION_MOVE -> if (distanceSquared(event.x, event.y, downX, downY) > touchSlop * touchSlop) {
                    thumbTap = false
                    firstTapUp = -1
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    thumbTap = false
                    firstTapUp = -1
                }
                MotionEvent.ACTION_UP -> {
                    val consumed = consumeTap
                    if (active && thumbTap && !consumed &&
                        event.eventTime - downTime <= ViewConfiguration.getLongPressTimeout() &&
                        distanceSquared(event.x, event.y, downX, downY) <= touchSlop * touchSlop) {
                        firstTapUp = event.eventTime
                        firstTapX = downX
                        firstTapY = downY
                    }
                    active = false
                    consumeTap = false
                    thumbTap = false
                    return consumed
                }
                MotionEvent.ACTION_CANCEL -> {
                    val consumed = consumeTap
                    cancelCandidate()
                    onCancel()
                    return consumed
                }
            }
            // The first tap and drags use Slider's own handling; all of the reset's second tap is consumed.
            return consumeTap
        }

        private fun hitsThumb(x: Float, y: Float): Boolean {
            if (slider.trackWidth <= 0 || slider.valueTo <= slider.valueFrom) return false
            var fraction = (slider.value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)
            if (slider.layoutDirection == View.LAYOUT_DIRECTION_RTL) fraction = 1f - fraction
            val centerX = slider.trackSidePadding + fraction * slider.trackWidth
            val centerY = slider.height / 2f
            val radius = slider.thumbRadius + touchSlop
            return distanceSquared(x, y, centerX, centerY) <= radius * radius
        }

        private fun cancelCandidate() {
            firstTapUp = -1
            active = false
            thumbTap = false
            consumeTap = false
        }

        private fun distanceSquared(x: Float, y: Float, otherX: Float, otherY: Float): Float =
            (x - otherX) * (x - otherX) + (y - otherY) * (y - otherY)
    }
}
