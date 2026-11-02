package me.timschneeberger.rootlessjamesdsp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.appcompat.graphics.drawable.DrawableWrapperCompat
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.sin

/** Keeps MaterialSwitch's input and checked callbacks; only its thumb drawing has spring motion. */
class SpringMaterialSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialSwitchStyle
) : MaterialSwitch(context, attrs, defStyleAttr) {
    private var ready = false
    private var immediateChange = false
    private var motion: ValueAnimator? = null
    private var drawnPosition = 0f
    private val trackPadding = Rect()
    private val thumbPadding = Rect()
    private var glassStretch = 0f

    // Opt-in material motion for the third glass theme; existing themes keep their exact timing.
    var glassMotionEnabled = false
        set(value) {
            field = value
            if (ready && !value) {
                glassStretch = 0f
                invalidate()
            }
        }
    var glassMotionStrength = .7f
    var glassMotionSpeed = 1f

    var springEnabled: Boolean = false
        set(value) {
            field = value
            if (ready && !value && !glassMotionEnabled) finishMotion()
        }

    init {
        // MaterialSwitch combines these two drawables internally. Move both layers together,
        // retaining its own bounds, tint interpolation, state shapes and optional icon.
        thumbDrawable?.let { super.setThumbDrawable(MovingThumb(it)) }
        thumbIconDrawable?.let { super.setThumbIconDrawable(MovingThumb(it)) }
        drawnPosition = if (isChecked) 1f else 0f
        ready = true
    }

    /** Skin the drawable inside the moving layer, leaving checked callbacks and travel unchanged. */
    fun decorateMovingThumb(transform: (Drawable) -> Drawable) {
        val moving = thumbDrawable as? MovingThumb ?: return
        val original = moving.drawable ?: return
        moving.drawable = transform(original)
        invalidate()
    }

    /** Binding/restoring a preference must not replay a user gesture. */
    fun setCheckedImmediately(value: Boolean) {
        val wasImmediate = immediateChange
        immediateChange = true
        try {
            isChecked = value
        } finally {
            immediateChange = wasImmediate
        }
    }

    override fun setChecked(checked: Boolean) {
        // CompoundButton calls this while the MaterialSwitch superclass is being constructed.
        if (!ready) {
            super.setChecked(checked)
            return
        }
        // Preference binding can repeat the accepted value before its animation finishes.
        // Keep that animation alive; only a real state change or explicit restoration snaps it.
        if (checked == isChecked && !immediateChange) {
            super.setChecked(checked)
            return
        }
        if ((!springEnabled && !glassMotionEnabled) || immediateChange || !isAttachedToWindow || !isLaidOut) {
            cancelMotion()
            super.setChecked(checked)
            if (immediateChange || springEnabled || glassMotionEnabled) super.jumpDrawablesToCurrentState()
            drawnPosition = thumbPosition
            return
        }

        val before = isChecked
        val start = if (motion != null) drawnPosition else thumbPosition
        cancelMotion()
        // Dispatch the existing listener first. It may reject the change and restore the value.
        super.setChecked(checked)
        val accepted = isChecked
        super.jumpDrawablesToCurrentState()
        val target = if (accepted) 1f else 0f
        if (accepted == before ||
            (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            drawnPosition = target
            invalidate()
            return
        }

        drawnPosition = start
        motion = ValueAnimator.ofFloat(start, target).apply {
            duration = if (glassMotionEnabled) (460L / glassMotionSpeed.coerceIn(.5f, 1.5f)).toLong() else 420L
            interpolator = OvershootInterpolator(if (glassMotionEnabled) 1.2f + glassMotionStrength * 1.4f else 1.6f)
            addUpdateListener {
                drawnPosition = it.animatedValue as Float
                glassStretch = if (glassMotionEnabled)
                    sin(it.currentPlayTime.toFloat() / it.duration.coerceAtLeast(1L) * Math.PI.toFloat()) *
                        .24f * glassMotionStrength.coerceIn(0f, 1.5f)
                    else 0f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (motion === animation) {
                        motion = null
                        glassStretch = 0f
                        drawnPosition = if (isChecked) 1f else 0f
                        invalidate()
                    }
                }
            })
            start()
        }
        // Start the compensating draw in the same frame as the logical checked change.
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && motion != null) finishMotion()
        return super.onTouchEvent(event)
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        if (ready) {
            cancelMotion()
            drawnPosition = if (isChecked) 1f else 0f
        }
    }

    override fun onDetachedFromWindow() {
        finishMotion()
        super.onDetachedFromWindow()
    }

    private fun cancelMotion() {
        val previous = motion
        motion = null
        glassStretch = 0f
        previous?.cancel()
    }

    private fun finishMotion() {
        cancelMotion()
        super.jumpDrawablesToCurrentState()
        drawnPosition = if (isChecked) 1f else 0f
        invalidate()
    }

    private fun thumbTranslation(): Float {
        if ((!springEnabled && !glassMotionEnabled) || motion == null) return 0f
        val track = trackDrawable ?: return 0f
        val thumb = thumbDrawable ?: return 0f
        trackPadding.setEmpty()
        thumbPadding.setEmpty()
        track.getPadding(trackPadding)
        thumb.getPadding(thumbPadding)
        // Material3's vector track and thumb have no optical insets. Their measured widths,
        // rather than a hard-coded dp distance, also support the caller's visual scale.
        val travel = (track.bounds.width() -
            (thumb.intrinsicWidth - thumbPadding.left - thumbPadding.right) -
            trackPadding.left - trackPadding.right).coerceAtLeast(0)
        val direction = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
        return (drawnPosition - thumbPosition) * travel * direction
    }

    private inner class MovingThumb(drawable: Drawable) : DrawableWrapperCompat(drawable) {
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(thumbTranslation(), 0f)
            if (glassMotionEnabled && glassStretch != 0f) {
                canvas.scale(1f + glassStretch, 1f - glassStretch * .55f,
                    bounds.exactCenterX(), bounds.exactCenterY())
            }
            super.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
}
