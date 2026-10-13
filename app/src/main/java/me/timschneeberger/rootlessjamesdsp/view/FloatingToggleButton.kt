package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.floatingactionbutton.FloatingActionButton
import me.timschneeberger.rootlessjamesdsp.R

class FloatingToggleButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FloatingActionButton(context, attrs) {

    private var onToggledListener: OnToggledListener? = null
    private var onClickListener: OnToggleClickListener? = null

    private var touchX = Float.NaN
    private var touchY = Float.NaN
    private var pendingClickAt: Long? = null
    private var transition: PowerTransition? = null

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
            touchX = event.rawX
            touchY = event.rawY
        }
        if (event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
            touchX = Float.NaN
            touchY = Float.NaN
            pendingClickAt = null
        }
        return super.onTouchEvent(event)
    }

    private fun animatePowerChange(enabled: Boolean) {
        val clickedAt = pendingClickAt ?: return
        pendingClickAt = null
        if (android.os.SystemClock.uptimeMillis() - clickedAt > 2000L || !isAttachedToWindow ||
            !android.animation.ValueAnimator.areAnimatorsEnabled()) return
        val host = rootView as? android.view.ViewGroup ?: return
        if (host.width <= 0 || host.height <= 0) return
        val hostLocation = IntArray(2).also(host::getLocationOnScreen)
        val buttonLocation = IntArray(2).also(::getLocationOnScreen)
        val x = if (touchX.isFinite()) touchX - hostLocation[0] else buttonLocation[0] - hostLocation[0] + width / 2f
        val y = if (touchY.isFinite()) touchY - hostLocation[1] else buttonLocation[1] - hostLocation[1] + height / 2f
        touchX = Float.NaN
        touchY = Float.NaN
        transition?.cancel()
        transition = PowerTransition(host, x.coerceIn(0f, host.width.toFloat()),
            y.coerceIn(0f, host.height.toFloat()), enabled).also { it.start() }
    }

    override fun onDetachedFromWindow() {
        transition?.cancel()
        transition = null
        pendingClickAt = null
        super.onDetachedFromWindow()
    }

    var isToggled = false
        set(value) {
            val changed = field != value
            field = value
            isSelected = value
            onToggledListener?.onToggled(value)
            if (changed) animatePowerChange(value)
        }

    var toggleOnClick = true

    init {
        setOnClickListener {
            pendingClickAt = android.os.SystemClock.uptimeMillis()
            onClickListener?.onClick()
            if(toggleOnClick) {
                isToggled = !isToggled
            }
            // Rejected clicks must not animate a later service-driven state refresh.
            pendingClickAt = null
            touchX = Float.NaN
            touchY = Float.NaN
        }

        // The main switch sits inside the bottom bar rather than floating above it.
        stateListAnimator = null
        compatElevation = 0f
        supportBackgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        rippleColor = android.graphics.Color.TRANSPARENT
        drawable.setTintList(context.getColorStateList(R.color.selector_floating_toggle_foreground_tint))
    }

    fun setOnToggledListener(listener: OnToggledListener) {
        onToggledListener = listener
    }

    fun setOnToggleClickListener(listener: OnToggleClickListener) {
        onClickListener = listener
    }

    interface OnToggleClickListener {
        fun onClick()
    }

    interface OnToggledListener {
        fun onToggled(enabled: Boolean)
    }
}
