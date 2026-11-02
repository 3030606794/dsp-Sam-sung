package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.floatingactionbutton.FloatingActionButton
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.power.PowerMotionConfig
import me.timschneeberger.rootlessjamesdsp.ui.power.PowerMotionController

class FloatingToggleButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FloatingActionButton(context, attrs) {
    private var onToggledListener: OnToggledListener? = null
    private var onClickListener: OnToggleClickListener? = null
    private var dispatchingClick = false
    private var committingUserState = false
    // Nullable because View may dispatch visibility changes during its constructor.
    private var motionController: PowerMotionController? = null
    private val powerAnimationsEnabled = context.obtainStyledAttributes(
        attrs, R.styleable.FloatingToggleButton
    ).let { attributes ->
        try { attributes.getBoolean(R.styleable.FloatingToggleButton_powerAnimationsEnabled, true) }
        finally { attributes.recycle() }
    }

    var isToggled = false
        set(value) {
            field = value
            isSelected = value
            onToggledListener?.onToggled(value)
            if (!dispatchingClick && !committingUserState) motionController?.showState(field)
        }

    var toggleOnClick = true

    init {
        stateListAnimator = null
        compatElevation = 0f
        supportBackgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        rippleColor = android.graphics.Color.TRANSPARENT
        motionController = PowerMotionController(this, powerAnimationsEnabled, isToggled)
        setOnClickListener {
            val before = isToggled
            dispatchingClick = true
            try {
                onClickListener?.onClick()
                if (toggleOnClick) isToggled = !isToggled
            } finally {
                dispatchingClick = false
                if (before != isToggled) motionController?.accepted(isToggled)
                else motionController?.cancelInteraction()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEnabled && event.actionMasked == MotionEvent.ACTION_DOWN) motionController?.press()
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) motionController?.cancelInteraction()
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) motionController?.releaseAfterClickDispatch()
        return handled
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motionController?.attach()
    }
    override fun onDetachedFromWindow() {
        motionController?.detach()
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        motionController?.refreshVisibility()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        motionController?.refreshVisibility()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        motionController?.resize()
    }
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.ToggleButton::class.java.name
        info.isCheckable = true
        info.isChecked = isToggled
    }

    fun applyMotionConfig(config: PowerMotionConfig) { motionController?.applyMotionConfig(config) }
    /** Marks a successful asynchronous user request, without replaying normal service refreshes. */
    fun commitUserToggle(value: Boolean, commit: () -> Unit = {}) {
        val before = isToggled
        committingUserState = true
        try { isToggled = value; commit() } finally { committingUserState = false }
        if (!dispatchingClick && before != isToggled) motionController?.accepted(isToggled)
    }
    fun setMotionResumed(resumed: Boolean) { motionController?.setMotionResumed(resumed) }
    internal fun motionSnapshot(): PowerMotionController.Snapshot = requireNotNull(motionController).snapshot()

    fun setOnToggledListener(listener: OnToggledListener) { onToggledListener = listener }
    fun setOnToggleClickListener(listener: OnToggleClickListener) { onClickListener = listener }
    interface OnToggleClickListener { fun onClick() }
    interface OnToggledListener { fun onToggled(enabled: Boolean) }
}
