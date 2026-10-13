package me.timschneeberger.rootlessjamesdsp.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.hypot
import kotlin.math.max

/** Transient overlay: never intercepts input or changes the underlying screen. */
internal class PowerTransition(private val host: ViewGroup, private val x: Float, private val y: Float,
                               private val enabling: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var animator: ValueAnimator? = null
    internal val maximumRadius = hypot(max(x, host.width - x), max(y, host.height - y)) + 1f
    internal val radius get() = maximumRadius * if (enabling) progress else 1f - progress

    fun start() {
        setBounds(0, 0, host.width, host.height)
        host.overlay.add(this)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480L
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidateSelf() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { host.overlay.remove(this@PowerTransition) }
            })
            start()
        }
    }

    fun cancel() { animator?.cancel(); host.overlay.remove(this) }

    override fun draw(canvas: Canvas) {
        val fade = if (progress < 0.75f) 1f else (1f - progress) / 0.25f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((28 * fade).toInt().coerceIn(0, 28), 46, 173, 85)
        canvas.drawCircle(x, y, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * host.resources.displayMetrics.density
        paint.color = Color.argb((100 * fade).toInt().coerceIn(0, 100), 46, 173, 85)
        canvas.drawCircle(x, y, radius, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Drawable opacity is unused")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
