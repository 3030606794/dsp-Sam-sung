package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs

/** Selection mode owns the complete touch stream; child controls never receive it. */
class PeqBandCardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    MaterialCardView(context, attrs) {
    var selectionMode = false
        set(value) {
            if (field == value) return
            field = value
            selecting = false
            if (value) {
                val now = SystemClock.uptimeMillis()
                val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            }
        }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var selecting = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!selectionMode) {
            selecting = false
            return super.dispatchTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                selecting = true
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                selecting = false
            MotionEvent.ACTION_UP -> {
                if (selecting && abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop)
                    performClick()
                selecting = false
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> selecting = false
        }
        return true
    }
}
