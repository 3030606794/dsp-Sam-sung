package me.timschneeberger.rootlessjamesdsp.ui.glass

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.util.TypedValue
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.roundToInt

/** One softly lit environment is shared by the window and all of its glass surfaces. */
internal class GlassEnvironment(root: View) {
    private val rootReference = WeakReference(root)
    private var texture: Bitmap? = null
    private var textureWidth = 0
    private var textureHeight = 0
    val night = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    val density = root.resources.displayMetrics.density
    val secondVersion = GlassThemeUi.hasLensMaterial(root.context)
    val thirdVersion = GlassThemeUi.isThirdVersion(root.context)
    private val context = root.context.applicationContext
    private val plainBackground = TypedValue().let {
        root.context.theme.resolveAttribute(android.R.attr.colorBackground, it, true)
        if (it.resourceId != 0) root.context.getColor(it.resourceId) else it.data
    }
    private var textureColorful = true
    private val location = IntArray(2)

    val width: Int
        get() = rootReference.get()?.width?.takeIf { it > 0 }
            ?: rootReference.get()?.resources?.displayMetrics?.widthPixels ?: 1
    val height: Int
        get() = rootReference.get()?.height?.takeIf { it > 0 }
            ?: rootReference.get()?.resources?.displayMetrics?.heightPixels ?: 1

    fun screenOrigin(): IntArray {
        rootReference.get()?.getLocationOnScreen(location)
        return location
    }

    fun bitmap(): Bitmap {
        val colorful = !thirdVersion || Glass3Preferences.snapshot(context).colorfulBackground
        val fullWidth = width
        val fullHeight = height
        val scale = ((if (secondVersion) 1024f else 720f) / max(fullWidth, fullHeight)).coerceAtMost(1f)
        val w = (fullWidth * scale).roundToInt().coerceAtLeast(1)
        val h = (fullHeight * scale).roundToInt().coerceAtLeast(1)
        texture?.takeIf { textureWidth == w && textureHeight == h && textureColorful == colorful }?.let { return it }
        // Do not recycle an older bitmap: a render-thread display list may still reference it.
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
            texture = bitmap
            textureWidth = w
            textureHeight = h
            textureColorful = colorful
            paintEnvironment(Canvas(bitmap), w.toFloat(), h.toFloat())
        }
    }

    private fun paintEnvironment(canvas: Canvas, w: Float, h: Float) {
        if (thirdVersion && !textureColorful) {
            canvas.drawColor(plainBackground)
            return
        }
        if (secondVersion) {
            paintLensEnvironment(canvas, w, h)
            return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w, h,
            if (night) intArrayOf(0xff0b1425.toInt(), 0xff141d33.toInt(), 0xff11182b.toInt())
            else intArrayOf(0xffeaf3fc.toInt(), 0xffeeeafa.toInt(), 0xffdfeaf6.toInt()),
            floatArrayOf(0f, .53f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)

        fun glow(x: Float, y: Float, radius: Float, light: Int, dark: Int) {
            val color = if (night) dark else light
            paint.shader = RadialGradient(w * x, h * y, w * radius,
                intArrayOf(color, color and 0x00ffffff), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        glow(.03f, .08f, 1.15f, 0xa0b4dcff.toInt(), 0x624469b2)
        glow(.98f, .31f, 1.08f, 0x82d6c1ff.toInt(), 0x545c3e9e)
        glow(.02f, .63f, .94f, 0x75a5e6ee, 0x44357383)
        glow(.89f, .90f, 1.2f, 0x7ab4c7ff, 0x51434f99)
        glow(.43f, .41f, .86f, 0x75ffffff, 0x1e8093ba)
        paint.shader = LinearGradient(-w * .4f, 0f, w * 1.25f, h * .35f,
            intArrayOf(Color.TRANSPARENT, if (night) 0x0bffffff else 0x40ffffff, Color.TRANSPARENT),
            floatArrayOf(0f, .56f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    private fun paintLensEnvironment(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w, h,
            if (night) intArrayOf(0xff071523.toInt(), 0xff16273d.toInt(), 0xff1c1936.toInt())
            else intArrayOf(0xffe6f5fc.toInt(), 0xffdce9f6.toInt(), 0xffece6f9.toInt()),
            floatArrayOf(0f, .51f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)

        fun glow(x: Float, y: Float, radius: Float, light: Int, dark: Int) {
            val color = if (night) dark else light
            paint.shader = RadialGradient(w * x, h * y, w * radius,
                intArrayOf(color, color and 0x00ffffff), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        glow(.04f, .08f, 1.10f, 0xb793d6ff.toInt(), 0x794278a7)
        glow(1.02f, .34f, .85f, 0x99c8b5ff.toInt(), 0x704e449d)
        glow(-.08f, .64f, .95f, 0x89a1ece5.toInt(), 0x59378289)
        glow(.85f, .97f, 1.0f, 0x82a5b9ff.toInt(), 0x66504690)
        glow(.55f, .45f, .70f, 0x96ffffff.toInt(), 0x254677a1)

        // Broad, quiet light ribbons give the convex rim something visible to bend.
        // They are part of the shared backdrop, rather than a tint drawn over text.
        val ribbon = Path()
        ribbon.moveTo(-w * .25f, h * .14f)
        ribbon.cubicTo(w * .05f, h * .37f, w * .80f, -h * .06f, w * 1.20f, h * .20f)
        ribbon.lineTo(w * 1.20f, h * .28f)
        ribbon.cubicTo(w * .75f, h * .05f, w * .05f, h * .47f, -w * .25f, h * .23f)
        ribbon.close()
        paint.shader = LinearGradient(0f, h * .13f, w, h * .36f,
            intArrayOf(if (night) 0x1576c8ff else 0x2875b6ed,
                if (night) 0x20c9e9ff else 0x74ffffff,
                if (night) 0x218d95ff else 0x30b3a1ed),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(ribbon, paint)
        ribbon.reset()
        ribbon.moveTo(-w * .16f, h * .83f)
        ribbon.cubicTo(w * .35f, h * .45f, w * .73f, h * .98f, w * 1.17f, h * .66f)
        ribbon.lineTo(w * 1.17f, h * .77f)
        ribbon.cubicTo(w * .71f, h * 1.08f, w * .26f, h * .58f, -w * .16f, h * .96f)
        ribbon.close()
        paint.shader = LinearGradient(0f, h * .7f, w, h * .9f,
            intArrayOf(if (night) 0x263eaaa8 else 0x35a8e4dd,
                if (night) 0x188da4dd else 0x45ffffff,
                if (night) 0x22595db1 else 0x26b7a9ee),
            floatArrayOf(0f, .50f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(ribbon, paint)
    }
}

/** Static, low-frequency light rather than a blur applied to text or other foreground content. */
class GlassBackdropDrawable internal constructor(private val environment: GlassEnvironment) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    override fun draw(canvas: Canvas) {
        destination.set(bounds)
        canvas.drawBitmap(environment.bitmap(), null, destination, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
