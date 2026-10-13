package me.timschneeberger.rootlessjamesdsp.peq

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.abs

/** Defers touch singles so a double tap never toggles the master lock first. */
class PeqBandLockGestures(
    private val button: View,
    private val onSingle: () -> Unit,
    private val onDouble: () -> Unit,
) {
    private var acceptingGesture = false
    private var doubleTap = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(button.context).scaledTouchSlop
    private val detector = GestureDetector(button.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (acceptingGesture && button.isEnabled) button.performClick()
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            doubleTap = acceptingGesture && button.isEnabled
            return true
        }

        override fun onDoubleTapEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP && doubleTap && acceptingGesture && button.isEnabled) {
                cancel()
                onDouble()
            }
            return true
        }
    }, Handler(Looper.getMainLooper())).apply { setIsLongpressEnabled(false) }

    init { install() }

    @SuppressLint("ClickableViewAccessibility")
    private fun install() {
        button.setOnClickListener {
            cancel()
            if (button.isEnabled) onSingle()
        }
        button.setOnTouchListener { _, event ->
            if (!button.isEnabled) {
                cancel()
                false
            } else {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    acceptingGesture = true
                    doubleTap = false
                    downX = event.x
                    downY = event.y
                    button.isPressed = true
                }
                when {
                    event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN -> cancel()
                    (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) &&
                        (abs(event.x - downX) > slop || abs(event.y - downY) > slop) -> cancel()
                    acceptingGesture -> detector.onTouchEvent(event)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) button.isPressed = false
                true
            }
        }
        ViewCompat.addAccessibilityAction(button, button.context.getString(R.string.peq_unlock_all_field_locks)) { _, _ ->
            cancel()
            if (button.isEnabled) { onDouble(); true } else false
        }
        button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { cancel() }
        })
    }

    /** Discards delayed confirmation before recycling, rebinding, selection mode or leaving the page. */
    fun cancel() {
        acceptingGesture = false
        doubleTap = false
        button.isPressed = false
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { detector.onTouchEvent(event) } finally { event.recycle() }
    }
}
