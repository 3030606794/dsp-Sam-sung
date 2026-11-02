package me.timschneeberger.rootlessjamesdsp.peq

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Disambiguates touch gestures while keeping accessibility/keyboard clicks immediate. */
class PeqAddButtonGestures(
    private val button: View,
    private val onSingle: () -> Unit,
    private val onDouble: () -> Unit,
    private val onLong: () -> Unit,
) {
    private var acceptingGesture = false
    private val handler = Handler(Looper.getMainLooper())
    private val doubleTapSlop = ViewConfiguration.get(button.context).scaledDoubleTapSlop.toFloat()
    private var pendingSingle: Runnable? = null
    private var firstUpAt = 0L
    private var firstX = 0f
    private var firstY = 0f
    private var consumingDouble = false
    private val detector = GestureDetector(button.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean {
            acceptingGesture = true
            return true
        }

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (!acceptingGesture) return true
            firstUpAt = event.eventTime
            firstX = event.x
            firstY = event.y
            pendingSingle = Runnable {
                val accepted = acceptingGesture && button.isEnabled
                cancel()
                if (accepted) button.performClick()
            }.also { handler.postDelayed(it, confirmationDelayMillis()) }
            return true
        }

        // The control owns one shorter confirmation window. Do not let the
        // detector's independent 300ms timer commit a second single action.
        override fun onSingleTapConfirmed(event: MotionEvent) = true

        override fun onLongPress(event: MotionEvent) {
            if (acceptingGesture && button.isEnabled) button.performLongClick()
        }

        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            button.isPressed = false
            return true
        }
    }, handler)

    @SuppressLint("ClickableViewAccessibility")
    private fun install() {
        button.setOnClickListener {
            cancel()
            if (button.isEnabled) onSingle()
        }
        button.setOnLongClickListener {
            cancel()
            if (button.isEnabled) onLong()
            true
        }
        button.setOnTouchListener { _, event ->
            if (!button.isEnabled) false else {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.pointerCount > 1) {
                    cancel()
                    return@setOnTouchListener true
                }
                if (consumingDouble) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        consumingDouble = false
                        button.isPressed = false
                    }
                    return@setOnTouchListener true
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN && pendingSingle != null) {
                    val dx = event.x - firstX
                    val dy = event.y - firstY
                    val isDouble = event.eventTime - firstUpAt in 0..confirmationDelayMillis() &&
                        dx * dx + dy * dy <= doubleTapSlop * doubleTapSlop
                    if (isDouble) {
                        cancel()
                        consumingDouble = true
                        button.isPressed = true
                        onDouble()
                        return@setOnTouchListener true
                    }
                    // A separate tap elsewhere must not swallow the earlier add.
                    button.performClick()
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) button.isPressed = true
                detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    button.isPressed = false
                true
            }
        }
        button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = cancel()
        })
    }

    init { install() }

    /** Also removes the detector's delayed single-tap and long-press callbacks. */
    fun cancel() {
        acceptingGesture = false
        consumingDouble = false
        pendingSingle?.let(handler::removeCallbacks)
        pendingSingle = null
        button.isPressed = false
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { detector.onTouchEvent(event) } finally { event.recycle() }
    }

    companion object {
        // Single-add and double-jump cannot both commit immediately. A small,
        // control-specific window avoids speculative audio edits/rollbacks;
        // accessibility and keyboard performClick remain immediate.
        internal fun confirmationDelayMillis() = ViewConfiguration.getDoubleTapTimeout().toLong().coerceAtMost(220L)
    }
}
