package me.timschneeberger.rootlessjamesdsp.utils

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import me.timschneeberger.rootlessjamesdsp.R

/** Repeats the existing click action at a fixed rate; the click owns its step and limits. */
class ButtonPressRepeat(private val button: View, private val intervalMillis: Long = 100L) {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(button.context).scaledTouchSlop
    private var touching = false
    private var suppressRelease = false
    private var wasLongClickable = false
    private var focusObserver: ViewTreeObserver? = null
    private val isCurrent: Boolean get() = button.getTag(R.id.button_press_repeat_binding) === this
    private val canRepeat: Boolean get() = isCurrent && touching && button.isEnabled && button.isAttachedToWindow && button.isShown

    private val repeat = object : Runnable {
        override fun run() {
            if (!canRepeat) { cancel(); return }
            button.performClick()
            if (canRepeat) handler.postDelayed(this, intervalMillis) else cancel()
        }
    }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (!focused) cancel()
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = watchWindowFocus()
        override fun onViewDetachedFromWindow(view: View) {
            cancel()
            stopWatchingWindowFocus()
        }
    }

    init {
        require(intervalMillis > 0)
        (button.getTag(R.id.button_press_repeat_binding) as? ButtonPressRepeat)?.dispose()
        wasLongClickable = button.isLongClickable
        button.setTag(R.id.button_press_repeat_binding, this)
        install()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun install() {
        button.setOnLongClickListener {
            if (!canRepeat) false else {
                suppressRelease = true
                repeat.run()
                true
            }
        }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel()
                    suppressRelease = false
                    touching = button.isEnabled
                    false // Let View provide the ordinary press, long-press and click behavior.
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.x < -slop || event.y < -slop ||
                        event.x >= button.width + slop || event.y >= button.height + slop) cancel()
                    suppressRelease && !touching
                }
                MotionEvent.ACTION_UP -> {
                    val consumed = suppressRelease
                    if (consumed) cancel() else {
                        touching = false
                        handler.removeCallbacks(repeat)
                    }
                    suppressRelease = false
                    consumed // A completed repeat must not add one more step on release.
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancel()
                    suppressRelease = false
                    false
                }
                else -> false
            }
        }
        button.addOnAttachStateChangeListener(attachListener)
        if (button.isAttachedToWindow) watchWindowFocus()
    }

    private fun watchWindowFocus() {
        stopWatchingWindowFocus()
        focusObserver = button.viewTreeObserver.also { it.addOnWindowFocusChangeListener(focusListener) }
    }

    private fun stopWatchingWindowFocus() {
        focusObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusObserver = null
    }

    fun cancel() {
        if (touching) suppressRelease = true
        touching = false
        handler.removeCallbacks(repeat)
        if (isCurrent) {
            button.cancelLongPress()
            button.isPressed = false
        }
    }

    fun dispose() {
        cancel()
        button.removeOnAttachStateChangeListener(attachListener)
        stopWatchingWindowFocus()
        if (isCurrent) {
            button.setOnTouchListener(null)
            button.setOnLongClickListener(null)
            button.isLongClickable = wasLongClickable
            button.setTag(R.id.button_press_repeat_binding, null)
        }
    }
}
