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

/** Doubles act on the second down; callers opt into singles on the first up when reversible. */
class PeqBandLockGestures(
    private val button: View,
    private val onSingle: () -> Unit,
    private val onDouble: () -> Unit,
    private val doubleTapActionLabel: String = button.context.getString(R.string.peq_unlock_all_field_locks),
    private val immediateSingle: Boolean = false,
    // Capture before the immediate single changes state. The returned action replaces onDouble
    // only for the physical double following that single; accessibility never reuses its state.
    private val captureDoubleAction: (() -> (() -> Unit))? = null,
) {
    private var acceptingGesture = false
    private var pendingDoubleAction = false
    private var pendingImmediateSingle = false
    private var immediateDoubleAction: (() -> Unit)? = null
    private var dispatchingImmediateClick = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(button.context).scaledTouchSlop
    private val detector = GestureDetector(button.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (immediateSingle) immediateDoubleAction = null
            else if (acceptingGesture && button.isEnabled) button.performClick()
            return true
        }

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            pendingImmediateSingle = immediateSingle && acceptingGesture && button.isEnabled
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            // GestureDetector dispatches this while handling the second ACTION_DOWN.
            // Run the callback just after its dispatch returns so callback-driven
            // recycling/cancellation cannot re-enter the detector mid-dispatch.
            pendingDoubleAction = acceptingGesture && button.isEnabled
            return true
        }
    }, Handler(Looper.getMainLooper())).apply { setIsLongpressEnabled(false) }

    init { install() }

    @SuppressLint("ClickableViewAccessibility")
    private fun install() {
        button.setOnClickListener {
            // A physical first up deliberately leaves the detector's double candidate alive.
            // Programmatic/accessibility clicks are independent actions and discard it.
            if (!dispatchingImmediateClick) cancel()
            if (button.isEnabled) onSingle()
        }
        button.setOnTouchListener { _, event ->
            if (!button.isEnabled) {
                cancel()
                false
            } else {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    acceptingGesture = true
                    pendingDoubleAction = false
                    pendingImmediateSingle = false
                    downX = event.x
                    downY = event.y
                    button.drawableHotspotChanged(event.x, event.y)
                    button.isPressed = true
                }
                when {
                    event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN -> cancel()
                    (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) &&
                        (abs(event.x - downX) > slop || abs(event.y - downY) > slop) -> cancel()
                    acceptingGesture -> detector.onTouchEvent(event)
                }
                if (pendingDoubleAction) {
                    val action = immediateDoubleAction ?: onDouble
                    cancel()
                    action()
                    // Keep immediate pressed feedback until this second touch ends,
                    // even if the callback refreshed the icon and cancelled gestures.
                    button.isPressed = button.isAttachedToWindow && button.isEnabled
                } else if (pendingImmediateSingle) {
                    pendingImmediateSingle = false
                    immediateDoubleAction = captureDoubleAction?.invoke() ?: onDouble
                    dispatchingImmediateClick = true
                    try { button.performClick() } finally { dispatchingImmediateClick = false }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) button.isPressed = false
                true
            }
        }
        ViewCompat.addAccessibilityAction(button, doubleTapActionLabel) { _, _ ->
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
        pendingDoubleAction = false
        pendingImmediateSingle = false
        immediateDoubleAction = null
        button.isPressed = false
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { detector.onTouchEvent(event) } finally { event.recycle() }
    }
}
