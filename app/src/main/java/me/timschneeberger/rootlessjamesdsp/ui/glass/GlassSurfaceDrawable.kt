package me.timschneeberger.rootlessjamesdsp.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.ref.WeakReference
import kotlin.math.min

/** Frosted material sampled in window coordinates, with a restrained refracting rim. */
class GlassSurfaceDrawable internal constructor(
    view: View,
    private val environment: GlassEnvironment,
    private val role: GlassThemeUi.Role,
    private val radius: Float,
    private val insets: Rect = Rect()
) : Drawable() {
    private val owner = WeakReference(view)
    private val surface = RectF()
    private val inside = RectF()
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val location = IntArray(2)
    private var texture: Bitmap? = null
    private var shader: BitmapShader? = null
    private var drawableAlpha = 255
    private var drawableFilter: ColorFilter? = null
    private val lensShader by lazy { GlassLensShader.create() }
    private val lensRing = Path()
    private val lensSegment = Path()

    override fun draw(canvas: Canvas) {
        surface.set(bounds.left + insets.left.toFloat(), bounds.top + insets.top.toFloat(),
            bounds.right - insets.right.toFloat(), bounds.bottom - insets.bottom.toFloat())
        if (surface.width() <= 0f || surface.height() <= 0f) return
        val round = min(radius, min(surface.width(), surface.height()) * .5f)
        val textureBitmap = environment.bitmap()
        if (texture !== textureBitmap) {
            texture = textureBitmap
            shader = BitmapShader(textureBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        owner.get()?.getLocationOnScreen(location)
        val origin = environment.screenOrigin()
        val scaleX = environment.width.toFloat() / textureBitmap.width
        val scaleY = environment.height.toFloat() / textureBitmap.height
        // A slight optical displacement gives the material depth without distorting labels.
        val materialSettings = owner.get()?.takeIf { environment.thirdVersion }?.let { Glass3Preferences.snapshot(it.context) }
        val refraction = (if (role == GlassThemeUi.Role.PANEL) .7f else 1.5f) * (materialSettings?.refraction ?: 1f)
        matrix.setScale(scaleX, scaleY)
        matrix.postTranslate((origin[0] - location[0]).toFloat() + refraction * environment.density,
            (origin[1] - location[1]).toFloat() - refraction * environment.density)
        shader?.setLocalMatrix(matrix)

        if (environment.secondVersion) {
            drawSecondVersion(canvas, round)
            return
        }

        clip.reset()
        clip.addRoundRect(surface, round, round, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.alpha = drawableAlpha
        paint.colorFilter = drawableFilter
        val header = role == GlassThemeUi.Role.HEADER
        // A heading is a thin highlight on its enclosing panel, not another frosted card.
        if (!header) {
            paint.shader = shader
            canvas.drawRect(surface, paint)
        }

        val frosted = role == GlassThemeUi.Role.PANEL || role == GlassThemeUi.Role.DIALOG
        paint.shader = LinearGradient(surface.left, surface.top, surface.right, surface.bottom,
            if (header) {
                if (environment.night) intArrayOf(0x17d9e9ff, 0x05d9e9ff, 0x0bd9e9ff)
                else intArrayOf(0x45ffffff, 0x12ffffff, 0x20ffffff)
            } else if (environment.night) {
                if (frosted) intArrayOf(0x9c29364c.toInt(), 0xa81d293e.toInt(), 0xa91c273c.toInt())
                else intArrayOf(0x73374560, 0x60303b55, 0x8823324b.toInt())
            } else {
                if (frosted) intArrayOf(0xbeffffff.toInt(), 0x98f9fcff.toInt(), 0xa5f3f8ff.toInt())
                else intArrayOf(0xa7ffffff.toInt(), 0x56f9fcff, 0x83eef5ff.toInt())
            }, floatArrayOf(0f, .47f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(surface, paint)

        // An inner, broad glancing reflection is strongest in the navigation layer.
        if (!frosted && !header) {
            paint.shader = LinearGradient(surface.left, surface.top,
                surface.left + surface.width() * .65f, surface.bottom,
                intArrayOf(if (environment.night) 0x16ffffff else 0x3cffffff, 0x00ffffff),
                null, Shader.TileMode.CLAMP)
            canvas.drawRect(surface, paint)
        }

        val stroke = environment.density
        inside.set(surface)
        inside.inset(stroke * .5f, stroke * .5f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.shader = LinearGradient(surface.left, surface.top, surface.right, surface.bottom,
            if (header) intArrayOf(0x35ffffff, 0x0affffff, 0x00ffffff, 0x16ffffff)
            else if (environment.night) intArrayOf(0x78dceaff, 0x2296b4e7, 0x16ffffff, 0x404e6c98)
            else intArrayOf(0xf2ffffff.toInt(), 0x92ffffff.toInt(), 0x4698b1d3, 0xb5ffffff.toInt()),
            floatArrayOf(0f, .34f, .69f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(inside, (round - stroke * .5f).coerceAtLeast(0f),
            (round - stroke * .5f).coerceAtLeast(0f), paint)
        inside.inset(stroke, stroke)
        paint.strokeWidth = stroke * .55f
        paint.shader = LinearGradient(0f, surface.top, 0f, surface.bottom,
            intArrayOf(if (environment.night) 0x134f80b9 else 0x12637da5,
                0x00ffffff, if (environment.night) 0x22b2d7ff else 0x80ffffff.toInt()),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        if (!header) canvas.drawRoundRect(inside, (round - stroke * 1.5f).coerceAtLeast(0f),
            (round - stroke * 1.5f).coerceAtLeast(0f), paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    private fun drawSecondVersion(canvas: Canvas, round: Float) {
        val settings = owner.get()?.takeIf { environment.thirdVersion }?.let { Glass3Preferences.snapshot(it.context) }
        clip.reset()
        clip.addRoundRect(surface, round, round, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.alpha = drawableAlpha
        paint.colorFilter = drawableFilter
        val sampled = shader
        val rendered = lensShader?.let { lens ->
            if (sampled == null) false else runCatching {
                // Texture coordinates stay anchored to the shared backdrop while the lens
                // normal follows this surface's rounded edge. No labels enter the shader.
                GlassLensShader.configure(lens, sampled, surface, round,
                    environment.density, environment.night, role, settings)
                paint.shader = lens
                canvas.drawRect(surface, paint)
                true
            }.getOrDefault(false)
        } ?: false
        if (!rendered) drawCanvasLens(canvas, round)

        val unit = environment.density
        paint.alpha = (drawableAlpha * (settings?.highlight ?: 1f)).toInt().coerceIn(0, 255)
        inside.set(surface)
        inside.inset(unit * .45f, unit * .45f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = unit * .9f
        paint.shader = LinearGradient(surface.left, surface.top, surface.right, surface.bottom,
            if (environment.night) intArrayOf(0xd5edfbff.toInt(), 0x3481b6ee, 0x1347598a, 0x8bc4e9ff.toInt())
            else intArrayOf(0xfaffffff.toInt(), 0xa5ffffff.toInt(), 0x39718ead, 0xd9ffffff.toInt()),
            floatArrayOf(0f, .32f, .67f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(inside, (round - unit * .45f).coerceAtLeast(0f),
            (round - unit * .45f).coerceAtLeast(0f), paint)
        // A separated inner rim makes the material read as a thin curved shell.
        inside.inset(unit * 1.55f, unit * 1.55f)
        paint.strokeWidth = unit * .65f
        paint.shader = LinearGradient(surface.left, surface.top, surface.right, surface.bottom,
            intArrayOf(if (environment.night) 0x30558cbe else 0x227695bb,
                0x00ffffff, if (environment.night) 0x55a9d8f2 else 0x9affffff.toInt()),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        if (inside.width() > 0f && inside.height() > 0f) {
            canvas.drawRoundRect(inside, (round - unit * 2f).coerceAtLeast(0f),
                (round - unit * 2f).coerceAtLeast(0f), paint)
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(saved)
    }

    /** Canvas-only counterpart for older phones: perimeter bands sample displaced light. */
    private fun drawCanvasLens(canvas: Canvas, round: Float) {
        val sampled = shader ?: return
        val settings = owner.get()?.takeIf { environment.thirdVersion }?.let { Glass3Preferences.snapshot(it.context) }
        val opacity = settings?.let { it.opacity * if (role == GlassThemeUi.Role.HEADER) .58f else 1f } ?: when (role) {
            GlassThemeUi.Role.DIALOG -> if (environment.night) .77f else .65f
            GlassThemeUi.Role.HEADER -> if (environment.night) .44f else .30f
            else -> if (environment.night) .86f else .81f
        }
        paint.alpha = (drawableAlpha * opacity).toInt()
        paint.shader = sampled
        canvas.drawRect(surface, paint)
        val initialMatrix = Matrix(matrix)
        val unit = environment.density
        for (band in 0..2) {
            val outsideInset = band * unit * 2f
            val insideInset = outsideInset + unit * 2f
            inside.set(surface)
            inside.inset(outsideInset, outsideInset)
            if (inside.width() <= 0f || inside.height() <= 0f) break
            lensRing.reset()
            lensRing.fillType = Path.FillType.EVEN_ODD
            lensRing.addRoundRect(inside, (round - outsideInset).coerceAtLeast(0f),
                (round - outsideInset).coerceAtLeast(0f), Path.Direction.CW)
            inside.set(surface)
            inside.inset(insideInset, insideInset)
            if (inside.width() > 0f && inside.height() > 0f) {
                lensRing.addRoundRect(inside, (round - insideInset).coerceAtLeast(0f),
                    (round - insideInset).coerceAtLeast(0f), Path.Direction.CW)
            }
            val ringSave = canvas.save()
            canvas.clipPath(lensRing)
            val corners = floatArrayOf(surface.left, surface.top, surface.right, surface.top,
                surface.right, surface.bottom, surface.left, surface.bottom)
            for (side in 0..3) {
                lensSegment.reset()
                lensSegment.moveTo(surface.centerX(), surface.centerY())
                lensSegment.lineTo(corners[side * 2], corners[side * 2 + 1])
                val next = (side + 1) % 4
                lensSegment.lineTo(corners[next * 2], corners[next * 2 + 1])
                lensSegment.close()
                val segmentSave = canvas.save()
                canvas.clipPath(lensSegment)
                matrix.set(initialMatrix)
                val bend = (3 - band) * unit * 1.3f * (settings?.refraction ?: 1f)
                matrix.postTranslate(when (side) { 1 -> -bend; 3 -> bend; else -> 0f },
                    when (side) { 0 -> bend; 2 -> -bend; else -> 0f })
                sampled.setLocalMatrix(matrix)
                canvas.drawRect(surface, paint)
                canvas.restoreToCount(segmentSave)
            }
            canvas.restoreToCount(ringSave)
        }
        matrix.set(initialMatrix)
        sampled.setLocalMatrix(matrix)
        paint.shader = LinearGradient(surface.left, surface.top, surface.right, surface.bottom,
            if (settings != null && !settings.colorfulBackground) {
                if (environment.night) intArrayOf(0x54202020, 0x431a1a1a, 0x5d181818)
                else intArrayOf(0x69ffffff, 0x48ffffff, 0x58f6f6f6)
            } else if (environment.night) intArrayOf(0x5423334c, 0x43203045, 0x5d18253b)
            else intArrayOf(0x69ffffff, 0x48f8fcff, 0x58e9f5ff),
            floatArrayOf(0f, .47f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(surface, paint)
        paint.alpha = drawableAlpha
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds.left + insets.left, bounds.top + insets.top,
            bounds.right - insets.right, bounds.bottom - insets.bottom, radius)
        outline.alpha = .7f
    }

    override fun getPadding(padding: Rect): Boolean {
        padding.set(insets)
        return !insets.isEmpty
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { drawableFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
