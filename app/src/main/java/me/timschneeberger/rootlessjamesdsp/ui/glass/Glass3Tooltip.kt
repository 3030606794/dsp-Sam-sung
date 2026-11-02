package me.timschneeberger.rootlessjamesdsp.ui.glass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** V3-only value label. The original slider remains responsible for every input and value change. */
object Glass3Tooltip {
    // The Slider itself owns its formatter; weak values avoid retaining a recycled binding through it.
    private val formatters = WeakHashMap<Slider, WeakReference<LabelFormatter>>()
    private val bindings = WeakHashMap<Slider, Binding>()

    fun setLabelFormatter(slider: Slider, formatter: LabelFormatter) {
        slider.setLabelFormatter(formatter)
        registerFormatter(slider, formatter)
        decorate(slider)
    }

    fun registerFormatter(slider: Slider, formatter: LabelFormatter) {
        formatters[slider] = WeakReference(formatter)
    }

    fun decorate(slider: Slider) {
        if (!GlassThemeUi.isThirdVersion(slider.context)) return
        val binding = bindings.getOrPut(slider) { Binding(slider) }
        binding.refreshListeners()
    }

    private class Binding(slider: Slider) : Slider.OnChangeListener,
        Slider.OnSliderTouchListener, View.OnAttachStateChangeListener {
        private val owner = WeakReference(slider)
        private var host: WeakReference<ViewGroup>? = null
        private val bubble = LiquidBubble(this)
        private var animation: ValueAnimator? = null
        private var generation = 0
        private var originalLabel = LabelFormatter.LABEL_FLOATING
        private var showing = false
        private var tracking = false
        private var refreshQueued = false
        private var lastValue = slider.value
        var reveal = 0f
            private set
        var drift = 0f
            private set
        var text = ""
            private set

        init { slider.addOnAttachStateChangeListener(this) }

        fun slider() = owner.get()
        fun overlayHost() = host?.get()

        fun refreshListeners() {
            val slider = owner.get() ?: return
            if (refreshQueued) return
            refreshQueued = true
            // Rebinding can originate inside a native change callback. Post the list mutation
            // until that dispatch finishes; only our own listeners are removed and re-added.
            slider.post {
                refreshQueued = false
                slider.removeOnChangeListener(this)
                slider.removeOnSliderTouchListener(this)
                slider.addOnChangeListener(this)
                slider.addOnSliderTouchListener(this)
                if (!Glass3Preferences.snapshot(slider.context).liquidTooltip) remove()
            }
        }

        override fun onStartTrackingTouch(slider: Slider) {
            val options = Glass3Preferences.snapshot(slider.context)
            if (!GlassThemeUi.isThirdVersion(slider.context) || !options.liquidTooltip) return
            // An unregistered custom formatter keeps the native label instead of losing units.
            if (slider.hasLabelFormatter() && formatters[slider]?.get() == null) return
            val root = slider.rootView as? ViewGroup ?: return
            tracking = true
            lastValue = slider.value
            text = format(slider)
            if (!showing) {
                originalLabel = slider.labelBehavior
                slider.labelBehavior = LabelFormatter.LABEL_GONE
                host = WeakReference(root)
                bubble.setBounds(0, 0, root.width, root.height)
                root.overlay.add(bubble)
                showing = true
            }
            animate(1f)
        }

        override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
            if (!showing || !tracking) { lastValue = value; return }
            text = format(slider)
            val options = Glass3Preferences.snapshot(slider.context)
            val range = (slider.valueTo - slider.valueFrom).coerceAtLeast(.0001f)
            val direction = if (slider.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
            val delta = (value - lastValue) / range * slider.trackWidth * direction
            drift = (drift + delta * .6f).coerceIn(-9f * density(slider), 9f * density(slider)) *
                options.strength.coerceIn(0f, 1.5f)
            lastValue = value
            animate(1f)
        }

        override fun onStopTrackingTouch(slider: Slider) {
            tracking = false
            if (showing) animate(0f)
        }

        private fun format(slider: Slider): String = formatters[slider]?.get()?.getFormattedValue(slider.value)
            ?: if (slider.value == slider.value.roundToInt().toFloat()) slider.value.roundToInt().toString()
            else String.format(Locale.getDefault(), "%.2f", slider.value)

        private fun animate(target: Float) {
            val slider = owner.get() ?: return remove()
            val options = Glass3Preferences.snapshot(slider.context)
            val motion = options.motionEnabled && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
            val token = ++generation
            animation?.cancel()
            animation = null
            if (!motion) {
                reveal = target
                drift = 0f
                bubble.invalidateSelf()
                if (target == 0f) remove()
                return
            }
            val start = reveal
            val pull = drift
            animation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ((if (target == 0f) 140L else 260L) / options.speed.coerceIn(.5f, 1.5f)).toLong()
                interpolator = OvershootInterpolator(1.25f * options.strength)
                addUpdateListener {
                    val progress = it.animatedValue as Float
                    reveal = start + (target - start) * progress
                    drift = pull * (1f - progress)
                    bubble.invalidateSelf()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (generation == token && !tracking && target == 0f) remove()
                    }
                })
                start()
            }
        }

        private fun remove() {
            generation++
            animation?.cancel()
            animation = null
            host?.get()?.overlay?.remove(bubble)
            host = null
            if (showing) owner.get()?.let { it.labelBehavior = originalLabel }
            showing = false
            tracking = false
            reveal = 0f
            drift = 0f
        }

        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = remove()
    }

    /** Drawn in the window overlay so card/RecyclerView clipping never cuts off the bubble. */
    private class LiquidBubble(private val binding: Binding) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        private val outline = Path()
        private val body = RectF()
        private val inner = RectF()
        private val matrix = Matrix()
        private val sliderLocation = IntArray(2)
        private val rootLocation = IntArray(2)
        private var source: Bitmap? = null
        private var backdrop: BitmapShader? = null
        private val lens = GlassLensShader.create()
        private var lensFailed = false

        override fun draw(canvas: Canvas) {
            val slider = binding.slider() ?: return
            val root = binding.overlayHost() ?: return
            if (!slider.isAttachedToWindow || !slider.isShown || binding.reveal <= 0f) return
            val options = Glass3Preferences.snapshot(slider.context)
            val d = density(slider)
            val night = slider.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val neutral = !options.colorfulBackground
            slider.getLocationOnScreen(sliderLocation)
            root.getLocationOnScreen(rootLocation)
            val fraction = ((slider.value - slider.valueFrom) / (slider.valueTo - slider.valueFrom).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
            val thumbX = sliderLocation[0] - rootLocation[0] + slider.trackSidePadding + slider.trackWidth *
                if (slider.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f - fraction else fraction
            val thumbY = sliderLocation[1] - rootLocation[1] + slider.height / 2f
            textPaint.textSize = 13f * slider.resources.displayMetrics.scaledDensity
            val maxWidth = (root.width - 24f * d).coerceAtLeast(60f * d)
            val width = (textPaint.measureText(binding.text) + 26f * d).coerceIn(min(64f * d, maxWidth), maxWidth)
            val height = 32f * d
            val center = (thumbX - binding.drift).coerceIn(8f * d + width / 2f, root.width - 8f * d - width / 2f)
            val bottom = (thumbY - slider.thumbRadius - 14f * d).coerceAtLeast(height + 8f * d)
            body.set(center - width / 2f, bottom - height, center + width / 2f, bottom)
            val tipX = thumbX.coerceIn(body.left + 12f * d, body.right - 12f * d)
            val tipY = (thumbY - slider.thumbRadius + d).coerceAtLeast(bottom + 3f * d)
            val r = height / 2f
            // One connected contour: liquid shoulders flow into the droplet stem.
            outline.reset()
            outline.moveTo(body.left + r, body.top)
            outline.lineTo(body.right - r, body.top)
            outline.quadTo(body.right, body.top, body.right, body.top + r)
            outline.lineTo(body.right, body.bottom - r)
            outline.quadTo(body.right, body.bottom, body.right - r, body.bottom)
            outline.lineTo(tipX + 10f * d, body.bottom)
            outline.cubicTo(tipX + 4f * d, body.bottom, tipX + 4f * d, tipY - 2f * d, tipX, tipY)
            outline.cubicTo(tipX - 4f * d, tipY - 2f * d, tipX - 4f * d, body.bottom, tipX - 10f * d, body.bottom)
            outline.lineTo(body.left + r, body.bottom)
            outline.quadTo(body.left, body.bottom, body.left, body.bottom - r)
            outline.lineTo(body.left, body.top + r)
            outline.quadTo(body.left, body.top, body.left + r, body.top)
            outline.close()

            val save = canvas.save()
            val scale = .86f + .14f * binding.reveal
            val stretch = (abs(binding.drift) / (9f * d)).coerceIn(0f, 1f)
            canvas.scale(scale * (1f + stretch * .045f), scale * (1f - stretch * .035f), tipX, tipY)
            val alpha = (binding.reveal.coerceIn(0f, 1f) * 255).toInt()
            paint.style = Paint.Style.FILL
            paint.shader = null
            val environment = GlassThemeUi.environmentFor(slider)
            val bitmap = environment.bitmap()
            if (source !== bitmap) {
                source = bitmap
                backdrop = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            val origin = environment.screenOrigin()
            val magnification = 1f + .045f * options.refraction.coerceIn(0f, 1.5f)
            matrix.reset()
            matrix.setScale(environment.width.toFloat() / bitmap.width * magnification,
                environment.height.toFloat() / bitmap.height * magnification)
            matrix.postTranslate(origin[0] - rootLocation[0] - body.centerX() * (magnification - 1f),
                origin[1] - rootLocation[1] - body.centerY() * (magnification - 1f))
            backdrop?.setLocalMatrix(matrix)
            val scene = backdrop
            val liquidLens = lens
            paint.alpha = (alpha * options.opacity.coerceIn(.3f, 1f)).toInt()
            val lensDrawn = if (!lensFailed && liquidLens != null && scene != null) {
                runCatching {
                    GlassLensShader.configure(liquidLens, scene, body, r, d, night, GlassThemeUi.Role.DIALOG, options)
                    paint.shader = liquidLens
                    canvas.drawPath(outline, paint)
                }.onFailure { lensFailed = true }.isSuccess
            } else false
            if (!lensDrawn) {
                paint.shader = scene
                canvas.drawPath(outline, paint)
            }
            paint.shader = null
            val wash = (alpha * (.30f + options.opacity.coerceIn(.3f, 1f) * .32f)).toInt()
            paint.color = when {
                neutral && night -> Color.argb(wash, 28, 28, 30)
                neutral -> Color.argb(wash, 252, 252, 252)
                night -> Color.argb(wash, 24, 38, 54)
                else -> Color.argb(wash, 244, 249, 253)
            }
            canvas.drawPath(outline, paint)

            paint.alpha = alpha
            paint.shader = LinearGradient(body.left, body.top, body.right, body.bottom,
                intArrayOf(if (night) 0x55ffffff else 0xc5ffffff.toInt(), 0x05ffffff,
                    if (neutral) 0x307c7c80 else 0x305a809d),
                floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(outline, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = .85f * d
            paint.alpha = (alpha * options.highlight.coerceIn(0f, 1.5f)).toInt().coerceIn(0, 255)
            paint.shader = LinearGradient(body.left, body.top, body.right, tipY,
                intArrayOf(Color.WHITE, if (neutral) 0x807b7b80.toInt() else 0x80658299.toInt(),
                    0x35ffffff, 0xf0ffffff.toInt()),
                floatArrayOf(0f, .4f, .6f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(outline, paint)
            inner.set(body)
            inner.inset(1.6f * d, 1.6f * d)
            paint.strokeWidth = .45f * d
            paint.shader = LinearGradient(body.left, body.top, body.right, body.bottom,
                intArrayOf(if (neutral) 0x2077777c else 0x2058758c, 0x06ffffff,
                    0x99ffffff.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(inner, r - d, r - d, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
            textPaint.color = if (night) 0xfff1f6fb.toInt() else 0xff172b3d.toInt()
            textPaint.alpha = alpha
            val textWidth = textPaint.measureText(binding.text)
            val textSave = canvas.save()
            if (textWidth > width - 18f * d) canvas.scale((width - 18f * d) / textWidth, 1f, body.centerX(), body.centerY())
            canvas.drawText(binding.text, body.centerX() - textWidth / 2f,
                body.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            canvas.restoreToCount(textSave)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun density(view: View) = view.resources.displayMetrics.density
}

/** Keeps the formatter available to the liquid label without reflective access to Material fields. */
fun Slider.setGlassLabelFormatter(formatter: LabelFormatter) = Glass3Tooltip.setLabelFormatter(this, formatter)
