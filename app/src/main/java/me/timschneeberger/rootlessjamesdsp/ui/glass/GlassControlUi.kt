package me.timschneeberger.rootlessjamesdsp.ui.glass

import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.graphics.drawable.DrawableWrapperCompat
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.sin

/** V2 visual skin. No touch, change, checked, focus, formatter or input listeners are replaced. */
object GlassControlUi {
    private val sliders = WeakHashMap<Slider, Boolean>()
    private val decorated = WeakHashMap<View, Boolean>()
    private val waves = WeakHashMap<View, Boolean>()

    fun decorate(view: View) {
        if (!GlassThemeUi.hasLensMaterial(view.context)) return
        val third = GlassThemeUi.isThirdVersion(view.context)
        val transparentControl=view.id in listOf(R.id.output_device_title_area,R.id.output_driver_info,
            R.id.output_card_pin,R.id.settings_button,R.id.home_more_button,R.id.power_toggle,R.id.peq_settings,R.id.peq_power)
        if (third) {
            Glass3Motion.decorate(view)
            // The main power button owns its selectable motion and haptics.
            if (view.id != R.id.power_toggle && (view.isClickable || view is Slider || view is SwitchCompat)) {
                removePressedShadow(view)
                if (view !is Slider && waves.put(view, true) == null) {
                    addForeground(view, Glass3WaveDrawable(view))
                }
            }
            val settings = Glass3Preferences.snapshot(view.context)
            if (view is MaterialButton && !transparentControl) {
                view.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(
                    if (night(view)) 0xff476079.toInt() else Color.WHITE,
                    (255f * (.06f + settings.opacity * .16f)).toInt()))
            } else if (view is TextInputLayout) {
                view.boxBackgroundColor = ColorUtils.setAlphaComponent(
                    if (night(view)) 0xff476079.toInt() else Color.WHITE,
                    (255f * settings.opacity * .24f).toInt())
            }
        }
        // These controls intentionally retain only their bounded native/dynamic press effect.
        if(transparentControl)return
        if (view is Slider) { decorateSlider(view); return }
        if (decorated.put(view, true) != null) return
        when (view) {
            is SwitchCompat -> decorateSwitch(view)
            is TextInputLayout -> {
                // Keep Material's floating hint/error and original edit validation behavior.
                if (!third) view.boxBackgroundColor = if (night(view)) 0x334b6a87 else 0x3dffffff
                view.boxStrokeColor = if (night(view)) 0xff8db8d3.toInt() else 0xff759cbe.toInt()
                addForeground(view, ControlLensDrawable(view, 9f, edgeOnly = true))
            }
            is EditText -> {
                // TextInputLayout owns its child's background and hint animation.
                if (view.parent?.parent !is TextInputLayout && view.parent !is TextInputLayout) {
                    addForeground(view, ControlLensDrawable(view, 8f, edgeOnly = true))
                }
            }
            is MaterialButton -> {
                if (!third) view.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (night(view)) 0x254b88aa else 0x35ffffff)
                if (!third) view.strokeWidth = 0
                view.icon?.let { if (it !is GlassyIconDrawable) view.icon = GlassyIconDrawable(it, view) }
                addForeground(view, ControlLensDrawable(view, 100f, edgeOnly = third))
            }
            is Button -> addForeground(view, ControlLensDrawable(view, 9f))
            is ImageView -> {
                view.drawable?.let { if (it !is GlassyIconDrawable) view.setImageDrawable(GlassyIconDrawable(it, view)) }
                // Glyph-only icons retain their silhouette; interactive icons also get a clear lens.
                if (view.isClickable) addForeground(view, ControlLensDrawable(view, 100f))
            }
            is TextView -> {
                // Numeric fields in older preference layouts are clickable TextViews.
                if (view.isClickable && (view.background != null || third) && view.id != android.R.id.title &&
                    view.text?.isNotEmpty() == true && view.text.first().let { it.isDigit() || it == '+' || it == '−' || it == '-' }) {
                    addForeground(view, ControlLensDrawable(view, 8f, edgeOnly = third))
                }
            }
        }
    }

    fun decorateSlider(slider: Slider) {
        if (!GlassThemeUi.hasLensMaterial(slider.context)) return
        if (GlassThemeUi.isThirdVersion(slider.context)) {
            Glass3Motion.decorate(slider)
            Glass3Tooltip.decorate(slider)
            removePressedShadow(slider)
        }
        if (sliders.put(slider, true) == null) {
            // Material clones custom thumbs via ConstantState. The clone keeps only a weak owner.
            slider.setCustomThumbDrawable(GlassThumbDrawable(slider))
            addForeground(slider, GlassRailDrawable(slider))
        }
        // The thumb reads current tint when drawn, so PEQ positive/negative colors stay accurate.
        slider.invalidate()
    }

    private fun decorateSwitch(view: SwitchCompat) {
        if (view is SpringMaterialSwitch) {
            view.decorateMovingThumb { original -> GlassSwitchDrawable(original, view, thumb = true) }
        } else {
            view.thumbDrawable?.let { view.thumbDrawable = GlassSwitchDrawable(it, view, thumb = true) }
        }
        view.trackDrawable?.let { view.trackDrawable = GlassSwitchDrawable(it, view, thumb = false) }
        // Track and thumb still receive the native checked-state tint interpolation.
        if (view is MaterialSwitch) view.trackDecorationTintList = null
        view.invalidate()
    }

    private fun addForeground(view: View, drawable: Drawable) {
        val previous = view.foreground
        view.foreground = if (previous == null) drawable else LayerDrawable(arrayOf(previous, drawable))
    }

    private fun removePressedShadow(view: View) {
        view.stateListAnimator = null
        view.elevation = 0f
        view.translationZ = 0f
        fun clearRipple(drawable: Drawable?) {
            when (drawable) {
                is RippleDrawable -> {
                    drawable.setColor(ColorStateList.valueOf(Color.TRANSPARENT))
                    for (index in 0 until drawable.numberOfLayers) clearRipple(drawable.getDrawable(index))
                }
                is LayerDrawable -> for (index in 0 until drawable.numberOfLayers) clearRipple(drawable.getDrawable(index))
                is InsetDrawable -> clearRipple(drawable.drawable)
                is DrawableWrapperCompat -> clearRipple(drawable.drawable)
            }
        }
        clearRipple(view.background)
        clearRipple(view.foreground)
        if (view is MaterialButton) view.rippleColor = ColorStateList.valueOf(Color.TRANSPARENT)
        if (view is Slider) {
            view.haloRadius = 0
            view.haloTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
    }

    private fun night(view: View) = view.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

/** Clear cover over a native control. Its center remains translucent and text stays sharp. */
private class ControlLensDrawable(view: View, private val radiusDp: Float, private val edgeOnly: Boolean = false) : Drawable() {
    private val owner = WeakReference(view)
    private val painter = ControlGlassPainter(view)
    private val rect = RectF()
    private var opacity = 255

    override fun draw(canvas: Canvas) {
        val view = owner.get() ?: return
        val inset = .65f * view.resources.displayMetrics.density
        rect.set(bounds)
        rect.inset(inset, inset)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        painter.draw(canvas, rect, min(radiusDp * view.resources.displayMetrics.density,
            rect.height() / 2f), opacity, edgeOnly = edgeOnly)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Actual Slider thumb, rather than a halo, with a refracted center and bevel inside its bounds. */
private class GlassThumbDrawable(slider: Slider) : Drawable() {
    private val owner = WeakReference(slider)
    private val painter = ControlGlassPainter(slider)
    private val rect = RectF()
    private var opacity = 255
    override fun getIntrinsicWidth() = owner.get()?.thumbRadius?.times(2) ?: 24
    override fun getIntrinsicHeight() = intrinsicWidth
    override fun getConstantState(): ConstantState = object : ConstantState() {
        override fun getChangingConfigurations() = 0
        override fun newDrawable(): Drawable = owner.get()?.let { GlassThumbDrawable(it) } ?: EmptyGlassDrawable()
    }
    override fun draw(canvas: Canvas) {
        val slider = owner.get() ?: return
        rect.set(bounds)
        rect.inset(.55f * slider.resources.displayMetrics.density, .55f * slider.resources.displayMetrics.density)
        val color = slider.thumbTintList.getColorForState(slider.drawableState, slider.thumbTintList.defaultColor)
        val fraction = ((slider.value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)).coerceIn(0f, 1f)
        val x = slider.trackSidePadding + slider.trackWidth * if (slider.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f - fraction else fraction
        val saved = canvas.save()
        if (GlassThemeUi.isThirdVersion(slider.context)) {
            val settings = Glass3Preferences.snapshot(slider.context)
            if (settings.motionEnabled) {
                val motion = Glass3Motion.stateFor(slider)
                // Compression and velocity stretch only the glass; the thumb's real hit area stays native.
                val stretch = abs(motion.stretch).coerceIn(0f, 1.5f)
                val press = motion.press.coerceIn(-.5f, 1.5f)
                canvas.scale(1f + stretch * .20f + press * .055f,
                    1f - stretch * .10f - press * .025f, rect.centerX(), rect.centerY())
            }
        }
        painter.draw(canvas, rect, min(rect.width(), rect.height()) / 2f,
            if (slider.isEnabled) opacity else opacity / 2, tint = color, strong = true,
            sampleOffsetX = x - slider.thumbRadius, sampleOffsetY = slider.height / 2f - slider.thumbRadius)
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Edge highlights follow the native rail and leave the moving thumb completely unobscured. */
private class GlassRailDrawable(slider: Slider) : Drawable() {
    private val owner = WeakReference(slider)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255
    override fun draw(canvas: Canvas) {
        val slider = owner.get() ?: return
        val density = slider.resources.displayMetrics.density
        val left = slider.trackSidePadding.toFloat()
        val right = left + slider.trackWidth
        val center = slider.height / 2f
        val half = slider.trackHeight / 2f
        val fraction = ((slider.value - slider.valueFrom) / (slider.valueTo - slider.valueFrom)).coerceIn(0f, 1f)
        val x = left + slider.trackWidth * if (slider.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f - fraction else fraction
        val gap = slider.thumbRadius + .6f * density
        val third = GlassThemeUi.isThirdVersion(slider.context)
        val settings = if (third) Glass3Preferences.snapshot(slider.context) else null
        val light = settings?.highlight ?: 1f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = .65f * density
        paint.color = Color.argb(((if (slider.isEnabled) opacity * 4 / 5 else opacity / 3) * light).toInt().coerceIn(0, 255), 255, 255, 255)
        fun line(y: Float) {
            if (x - gap > left) canvas.drawLine(left, y, x - gap, y, paint)
            if (x + gap < right) canvas.drawLine(x + gap, y, right, y, paint)
        }
        line(center - half + .35f * density)
        paint.color = Color.argb(((if (slider.isEnabled) opacity / 3 else opacity / 6) * light).toInt().coerceIn(0, 255), 43, 97, 143)
        line(center + half - .25f * density)
        if (third && settings != null && settings.motionEnabled) {
            val motion = Glass3Motion.stateFor(slider)
            if (motion.dragging || abs(motion.stretch) > .005f || motion.press > .005f) {
                val reach = (12f + 17f * settings.refraction) * density
                val shift = sin(motion.flow * 5f) * reach * .3f
                val a = (145f * settings.highlight * motion.press.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
                paint.shader = LinearGradient(x - reach + shift, center, x + reach + shift, center,
                    intArrayOf(Color.TRANSPARENT, Color.argb(a, 255, 255, 255), Color.TRANSPARENT),
                    floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
                paint.strokeWidth = density * (1f + settings.refraction * .4f)
                line(center - half + density * .55f)
                paint.shader = null
            }
        }
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Wrapping inside SpringMaterialSwitch's moving layer keeps the existing spring timing intact. */
private class GlassSwitchDrawable(source: Drawable, view: View, private val thumb: Boolean) : DrawableWrapperCompat(source) {
    private val owner = WeakReference(view)
    private val painter = ControlGlassPainter(view)
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        // The native shape retains all state-dependent scale/tint and its existing padding.
        super.draw(canvas)
        val view = owner.get() ?: return
        rect.set(bounds)
        val padding = android.graphics.Rect()
        drawable?.getPadding(padding)
        rect.set(rect.left + padding.left, rect.top + padding.top,
            rect.right - padding.right, rect.bottom - padding.bottom)
        val inset = .7f * view.resources.displayMetrics.density
        rect.inset(inset, inset)
        if (rect.width() > 0 && rect.height() > 0) painter.draw(canvas, rect,
            rect.height() / 2f, if (view.isEnabled) 220 else 100, edgeOnly = !thumb)
    }
}

/** Preserves the original glyph tint and adds fine reflected light along its real silhouette. */
private class GlassyIconDrawable(source: Drawable, view: View) : DrawableWrapperCompat(source) {
    private val owner = WeakReference(view)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        val view = owner.get() ?: run { super.draw(canvas); return }
        if (GlassThemeUi.isThirdVersion(view.context)) {
            drawThird(canvas, view)
            return
        }
        rect.set(bounds)
        val d = view.resources.displayMetrics.density
        // A shifted light silhouette creates a narrow polished edge without changing icon bounds.
        var save = canvas.saveLayer(rect, null)
        canvas.translate(-.3f * d, -.3f * d)
        super.draw(canvas)
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0xe6ffffff.toInt(), 0x704bc6f0, 0x20ffffff), null, Shader.TileMode.CLAMP)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawRect(rect, paint)
        paint.xfermode = null
        canvas.restoreToCount(save)
        super.draw(canvas)
        save = canvas.saveLayer(rect, null)
        super.draw(canvas)
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0x48ffffff, 0x04ffffff, 0x2038a6dd), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawRect(rect, paint)
        paint.xfermode = null
        canvas.restoreToCount(save)
        paint.shader = null
    }

    private fun drawThird(canvas: Canvas, view: View) {
        val settings = Glass3Preferences.snapshot(view.context)
        val motion = Glass3Motion.stateFor(view)
        rect.set(bounds)
        if (rect.isEmpty) { super.draw(canvas); return }
        val d = view.resources.displayMetrics.density
        val drift = if (settings.motionEnabled) sin(motion.flow * 4f) * motion.press else 0f
        val saved = canvas.saveLayer(rect, null)
        val edge = d * (.18f + settings.refraction * .3f)
        canvas.translate(-edge, -edge)
        super.draw(canvas)
        paint.shader = LinearGradient(rect.left + drift * d, rect.top, rect.right, rect.bottom,
            intArrayOf(Color.argb((190 * settings.highlight).toInt().coerceIn(0, 255), 255, 255, 255),
                Color.TRANSPARENT, Color.argb((50 * settings.highlight).toInt().coerceIn(0, 255), 230, 242, 255)),
            null, Shader.TileMode.CLAMP)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawRect(rect, paint)
        paint.xfermode = null
        canvas.restoreToCount(saved)
        super.draw(canvas)
        val shine = canvas.saveLayer(rect, null)
        super.draw(canvas)
        paint.shader = LinearGradient(rect.left, rect.top + rect.height() * drift * .15f,
            rect.right, rect.bottom,
            intArrayOf(Color.argb((40 * settings.highlight * settings.opacity).toInt().coerceIn(0, 255), 255, 255, 255),
                Color.TRANSPARENT, Color.argb((20 * settings.refraction).toInt().coerceIn(0, 255), 210, 225, 247)),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawRect(rect, paint)
        paint.xfermode = null
        paint.shader = null
        canvas.restoreToCount(shine)
    }
}

/** All bevels stay inside the original measured touch target and never add a pressed halo. */
private class ControlGlassPainter(view: View) {
    private val owner = WeakReference(view)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = RectF()
    private val matrix = Matrix()
    private val location = IntArray(2)
    private var texture: android.graphics.Bitmap? = null
    private var shader: BitmapShader? = null

    fun draw(canvas: Canvas, rect: RectF, radius: Float, alpha: Int,
             tint: Int = Color.TRANSPARENT, strong: Boolean = false, edgeOnly: Boolean = false,
             sampleOffsetX: Float = 0f, sampleOffsetY: Float = 0f) {
        val view = owner.get() ?: return
        if (GlassThemeUi.isThirdVersion(view.context)) {
            drawThird(canvas, rect, radius, alpha, tint, strong, edgeOnly, sampleOffsetX, sampleOffsetY, view)
            return
        }
        val d = view.resources.displayMetrics.density
        val night = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        paint.style = Paint.Style.FILL
        paint.alpha = alpha
        paint.colorFilter = null
        if (!edgeOnly) {
            val environment = GlassThemeUi.environmentFor(view)
            val bitmap = environment.bitmap()
            if (texture !== bitmap) {
                texture = bitmap
                shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            view.getLocationOnScreen(location)
            val origin = environment.screenOrigin()
            // A slightly magnified sample is bounded by the curved cover, like a shallow lens.
            val magnification = if (strong) 1.12f else 1.035f
            matrix.reset()
            matrix.setScale(environment.width.toFloat() / bitmap.width * magnification,
                environment.height.toFloat() / bitmap.height * magnification)
            matrix.postTranslate((origin[0] - location[0]).toFloat() - sampleOffsetX - rect.centerX() * (magnification - 1f),
                (origin[1] - location[1]).toFloat() - sampleOffsetY - rect.centerY() * (magnification - 1f))
            shader?.setLocalMatrix(matrix)
            paint.shader = shader
            paint.alpha = (alpha * if (strong) .85f else .12f).toInt()
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
            val base = if (tint != Color.TRANSPARENT) tint else if (night) 0xff8ec1e7.toInt() else Color.WHITE
            paint.color = ColorUtils.setAlphaComponent(base, (alpha * if (strong) .55f else .08f).toInt())
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.alpha = alpha
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(if (strong) 0xa8ffffff.toInt() else 0x30ffffff, 0x04ffffff,
                    if (night) 0x3846a7df else 0x305b9edc), floatArrayOf(0f, .46f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (strong) .8f * d else .65f * d
        paint.alpha = alpha
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0xf7ffffff.toInt(), 0x5499cce8, 0x408c7fcb, 0xd9ffffff.toInt()),
            floatArrayOf(0f, .36f, .68f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius, radius, paint)
        inner.set(rect)
        inner.inset(.9f * d, .9f * d)
        paint.strokeWidth = .45f * d
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0x28325d81, 0x00ffffff, 0x8058bfe9.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(inner, (radius - d).coerceAtLeast(0f), (radius - d).coerceAtLeast(0f), paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private fun drawThird(canvas: Canvas, rect: RectF, radius: Float, alpha: Int,
                          tint: Int, strong: Boolean, edgeOnly: Boolean,
                          sampleOffsetX: Float, sampleOffsetY: Float, view: View) {
        if (rect.isEmpty) return
        val settings = Glass3Preferences.snapshot(view.context)
        val motion = Glass3Motion.stateFor(view)
        val d = view.resources.displayMetrics.density
        val flow = if (settings.motionEnabled) sin(motion.flow * 4.2f) * motion.press else 0f
        val bend = settings.refraction
        val light = settings.highlight
        val strength = if (settings.motionEnabled) motion.press.coerceIn(0f, 1.5f) else 0f
        paint.style = Paint.Style.FILL
        paint.colorFilter = null
        paint.alpha = alpha
        if (!edgeOnly) {
            val environment = GlassThemeUi.environmentFor(view)
            val bitmap = environment.bitmap()
            if (texture !== bitmap) {
                texture = bitmap
                shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            view.getLocationOnScreen(location)
            val origin = environment.screenOrigin()
            val magnification = 1f + bend * (if (strong) .15f else .045f) + strength * bend * .025f
            matrix.reset()
            matrix.setScale(environment.width.toFloat() / bitmap.width * magnification,
                environment.height.toFloat() / bitmap.height * magnification)
            matrix.postTranslate(origin[0] - location[0] - sampleOffsetX - rect.centerX() * (magnification - 1f) + flow * bend * d * 1.3f,
                origin[1] - location[1] - sampleOffsetY - rect.centerY() * (magnification - 1f) - flow * bend * d)
            shader?.setLocalMatrix(matrix)
            paint.shader = shader
            paint.alpha = (alpha * settings.opacity * if (strong) .88f else .10f).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
            val base = if (tint != Color.TRANSPARENT) tint else Color.WHITE
            paint.color = ColorUtils.setAlphaComponent(base,
                (alpha * if (strong) (.28f + settings.opacity * .42f) else settings.opacity * .055f).toInt().coerceIn(0, 255))
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.alpha = alpha
            val bright = ((if (strong) 150f else 24f) * light).toInt().coerceIn(0, 255)
            paint.shader = LinearGradient(rect.left + rect.width() * flow * .12f, rect.top,
                rect.right, rect.bottom,
                intArrayOf(Color.argb(bright, 255, 255, 255), Color.TRANSPARENT,
                    Color.argb((30 * bend * settings.opacity).toInt().coerceIn(0, 255), 194, 220, 244)),
                floatArrayOf(0f, (.45f + flow * .10f).coerceIn(.2f, .8f), 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ((if (strong) .75f else .55f) + bend * .18f) * d
        paint.alpha = (alpha * (.45f + settings.opacity * .55f)).toInt().coerceIn(0, 255)
        paint.shader = LinearGradient(rect.left, rect.top + flow * rect.height() * .10f, rect.right, rect.bottom,
            intArrayOf(Color.argb((235f * light).toInt().coerceIn(0, 255), 255, 255, 255),
                Color.argb((25f * light).toInt().coerceIn(0, 255), 180, 199, 218),
                Color.argb((30f * bend).toInt().coerceIn(0, 255), 57, 68, 87),
                Color.argb((195f * light).toInt().coerceIn(0, 255), 255, 255, 255)),
            floatArrayOf(0f, .34f, .67f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius, radius, paint)
        inner.set(rect)
        val rim = (.65f + bend * .65f) * d
        inner.inset(rim, rim)
        if (inner.width() > 0 && inner.height() > 0) {
            paint.strokeWidth = .45f * d
            paint.shader = LinearGradient(inner.left, inner.top, inner.right, inner.bottom,
                intArrayOf(Color.argb((38 * bend).toInt().coerceIn(0, 255), 45, 61, 80),
                    Color.TRANSPARENT, Color.argb((125 * light * bend).toInt().coerceIn(0, 255), 255, 255, 255)),
                floatArrayOf(0f, .47f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(inner, (radius - rim).coerceAtLeast(0f), (radius - rim).coerceAtLeast(0f), paint)
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }
}

private class EmptyGlassDrawable : Drawable() {
    override fun draw(canvas: Canvas) = Unit
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
