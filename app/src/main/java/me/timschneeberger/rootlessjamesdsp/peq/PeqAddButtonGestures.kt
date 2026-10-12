package me.timschneeberger.rootlessjamesdsp.peq

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/** Disambiguates touch gestures while keeping accessibility/keyboard clicks immediate. */
class PeqAddButtonGestures(
    private val button: View,
    private val onSingle: () -> Unit,
    private val onDouble: () -> Unit,
    private val onLong: () -> Unit,
) {
    private var acceptingGesture = false
    private val detector = GestureDetector(button.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean {
            acceptingGesture = true
            return true
        }

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (acceptingGesture && button.isEnabled) button.performClick()
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (acceptingGesture && button.isEnabled) onDouble()
            return true
        }

        override fun onLongPress(event: MotionEvent) {
            if (acceptingGesture && button.isEnabled) button.performLongClick()
        }

        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            button.isPressed = false
            return true
        }
    }, Handler(Looper.getMainLooper()))

    @SuppressLint("ClickableViewAccessibility")
    private fun install() {
        button.setOnClickListener {
            cancel()
            onSingle()
        }
        button.setOnLongClickListener {
            cancel()
            onLong()
            true
        }
        button.setOnTouchListener { _, event ->
            if (!button.isEnabled) false else {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) button.isPressed = true
                detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    button.isPressed = false
                true
            }
        }
    }

    init { install() }

    /** Also removes the detector's delayed single-tap and long-press callbacks. */
    fun cancel() {
        acceptingGesture = false
        button.isPressed = false
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { detector.onTouchEvent(event) } finally { event.recycle() }
    }
}
