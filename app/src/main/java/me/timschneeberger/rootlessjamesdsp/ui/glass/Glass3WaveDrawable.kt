package me.timschneeberger.rootlessjamesdsp.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.ref.WeakReference
import kotlin.math.hypot
import kotlin.math.min

/** A bounded water-drop glint, driven by the motion observer without consuming touch events. */
internal class Glass3WaveDrawable(view: View) : Drawable() {
    private val owner = WeakReference(view)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()
    private var opacity = 255

    override fun draw(canvas: Canvas) {
        val view = owner.get() ?: return
        if (!GlassThemeUi.isThirdVersion(view.context)) return
        val settings = Glass3Preferences.snapshot(view.context)
        if (!settings.motionEnabled || settings.strength <= 0f) return
        val state = Glass3Motion.stateFor(view)
        if (!state.rippleActive || state.ripple >= 1f) return
        rect.set(bounds)
        if (rect.isEmpty) return
        val d = view.resources.displayMetrics.density
        val inset = .6f * d
        rect.inset(inset, inset)
        val x = rect.left + rect.width() * state.touchX
        val y = rect.top + rect.height() * state.touchY
        val progress = state.ripple.coerceIn(0f, 1f)
        val reach = hypot(rect.width(), rect.height())
        val radius = d * 1.5f + reach * progress * .78f
        val fade = (1f - progress) * (1f - progress)
        val brightness = (opacity * fade * settings.highlight * settings.strength * .8f).toInt().coerceIn(0, 255)
        if (brightness <= 0) return
        clip.reset()
        clip.addRoundRect(rect, min(rect.height() / 2f, 16f * d), min(rect.height() / 2f, 16f * d), Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (.65f + settings.refraction * .35f) * d
        paint.shader = SweepGradient(x, y, intArrayOf(
            Color.argb(brightness, 255, 255, 255),
            Color.argb(brightness / 5, 207, 224, 240),
            Color.TRANSPARENT,
            Color.argb(brightness * 2 / 3, 255, 255, 255),
            Color.argb(brightness, 255, 255, 255),
        ), floatArrayOf(0f, .22f, .50f, .78f, 1f))
        canvas.drawCircle(x, y, radius, paint)
        // The displaced second rim follows the first like a thin refracting meniscus.
        paint.shader = null
        paint.strokeWidth = .45f * d
        paint.color = Color.argb((brightness * settings.refraction * .34f).toInt().coerceIn(0, 255), 157, 184, 210)
        canvas.drawCircle(x + d * settings.refraction * .4f, y + d * settings.refraction * .65f,
            (radius - d * (1f + settings.refraction)).coerceAtLeast(0f), paint)
        paint.color = Color.argb(brightness / 3, 255, 255, 255)
        canvas.drawCircle(x, y, radius * .72f, paint)
        canvas.restoreToCount(saved)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
