package me.timschneeberger.rootlessjamesdsp.ui.power

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Decorative power-transition rendering only: no audio reads, timers or layout changes.
 * The owner supplies one frame, invalidates it, and removes the overlay when finished.
 * SPECTRUM is a visual curtain, never a representation of measured audio.
 */
class PowerFullscreenDrawable(private val density: Float) : Drawable() {
    @JvmField var style: PowerMotionStyle = PowerMotionStyle.WAVE
    @JvmField var progress = 0f
    // A field avoids conflicting with Drawable.getOpacity(), whose return type is Int.
    @JvmField var opacity = 1f
    @JvmField var primaryColor = 0xff956be8.toInt()
    @JvmField var secondaryColor = 0xff48dca7.toInt()
    @JvmField var lightBackground = false
    @JvmField var originX = Float.NaN
    @JvmField var originY = Float.NaN
    @JvmField var phase = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var drawableAlpha = 255
    private var frameAlpha = 1f
    private var width = 0f
    private var height = 0f
    private var ox = Float.NaN
    private var oy = Float.NaN
    private var radius = 1f
    private var previousPrimary = 0
    private var previousSecondary = 0
    private var shadersReady = false
    private val clip = Path()
    private val dynamicPath = Path()
    private val caps = Path()
    private val scratchRect = RectF()

    private var washShader: Shader? = null
    private var curtainShader: Shader? = null
    private var spectrumShader: Shader? = null
    private var phasePrimaryGlow: Shader? = null
    private var phaseSecondaryGlow: Shader? = null
    private var primaryRim: Shader? = null
    private var secondaryRim: Shader? = null
    private var domeShader: Shader? = null

    private var waveReady = false
    private val waveRays = Path()
    private var spectrumReady = false
    private val spectrumCuts = Path()
    private var phaseReady = false
    private val phaseNegative = Path()
    private val phasePositive = Path()
    private val phaseCentral = Path()
    private val phaseLattice = Path()
    private var phaseAx = 0f
    private var phaseBx = 0f
    private var phaseY = 0f
    private var wavelength = 1f

    private data class Route(val points: FloatArray, val lengths: FloatArray, val total: Float)
    private var matrixReady = false
    private val matrixGrid = Path()
    private val matrixPrimary = Path()
    private val matrixSecondary = Path()
    private val matrixNodes = Path()
    private val routes = ArrayList<Route>(30)

    private var refractionReady = false
    private val facets = Array(18) { Path() }
    private val facetEdges = Array(18) { Path() }
    private val facetShaders = arrayOfNulls<Shader>(18)
    private val facetCenters = FloatArray(36)

    private var domeReady = false
    private val domeSurface = Path()
    private val domeLatitudeFront = Path()
    private val domeLatitudeBack = Path()
    private val domeLatitudeAccent = Path()
    private val domeMeridians = Path()
    private val domeMeridianAccent = Path()
    private val domeGround = Path()
    private var domeBaseY = 0f
    private var domeHeight = 0f
    private var domeRadius = 0f
    private var domeDepth = 0f
    private val domeDotSin = FloatArray(9)
    private val domeDotCos = FloatArray(9)

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        width = -1f
        invalidateSelf()
    }

    /** Optional prewarming after the viewport, origin, style and colors have been set. */
    fun prepare() {
        if (!ensureGeometry()) return
        when (style) {
            PowerMotionStyle.WAVE -> prepareWave()
            PowerMotionStyle.SPECTRUM -> prepareSpectrum()
            PowerMotionStyle.PHASE -> preparePhase()
            PowerMotionStyle.MATRIX -> prepareMatrix()
            PowerMotionStyle.REFRACTION -> prepareRefraction()
            PowerMotionStyle.DOME -> prepareDome()
        }
        prepareShaders()
    }

    override fun draw(canvas: Canvas) {
        val p = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        frameAlpha = (opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f) * drawableAlpha / 255f
        if (p <= .0001f || frameAlpha <= .001f) return
        prepare()
        if (width <= 0f || height <= 0f) return
        val save = canvas.save()
        try {
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.clipRect(0f, 0f, width, height)
            solidPaint(Color.BLACK, (if (lightBackground) .08f else .42f) * min(1f, p * 3f))
            canvas.drawRect(0f, 0f, width, height, paint)
            clip.rewind()
            clip.addCircle(ox, oy, radius * p.pow(.82f), Path.Direction.CW)
            canvas.clipPath(clip)
            // Every wave source, node, facet and grid coordinate physically returns
            // to the switch when progress runs backwards; this is not an alpha wipe.
            val scale = p.pow(.7f)
            canvas.scale(scale, scale, ox, oy)
            val time = phase.takeIf { it.isFinite() } ?: 0f
            when (style) {
                PowerMotionStyle.WAVE -> drawWave(canvas, p)
                PowerMotionStyle.SPECTRUM -> drawSpectrum(canvas, p, time)
                PowerMotionStyle.PHASE -> drawPhase(canvas, time)
                PowerMotionStyle.MATRIX -> drawMatrix(canvas, time)
                PowerMotionStyle.REFRACTION -> drawRefraction(canvas, p)
                PowerMotionStyle.DOME -> drawDome(canvas, time)
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun ensureGeometry(): Boolean {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return false
        val x = if (originX.isFinite()) originX - bounds.left else w / 2f
        val y = if (originY.isFinite()) originY - bounds.top else h * .94f
        if (width == w && height == h && ox == x && oy == y) return true
        width = w; height = h; ox = x; oy = y
        radius = hypot(max(x, w - x).toDouble(), max(y, h - y).toDouble()).toFloat().coerceAtLeast(1f)
        phaseAx = ox - width * .225f
        phaseBx = ox + width * .225f
        phaseY = oy - height * .035f
        wavelength = max(width * .11f, dp(22f))
        waveReady = false; spectrumReady = false; phaseReady = false
        matrixReady = false; refractionReady = false; domeReady = false
        shadersReady = false
        for (i in facetShaders.indices) facetShaders[i] = null
        return true
    }

    private fun prepareShaders() {
        if (previousPrimary != primaryColor || previousSecondary != secondaryColor) {
            previousPrimary = primaryColor; previousSecondary = secondaryColor
            shadersReady = false
            for (i in facetShaders.indices) facetShaders[i] = null
        }
        if (!shadersReady) {
            washShader = RadialGradient(ox, oy, radius, intArrayOf(
                tint(secondaryColor, .28f), tint(primaryColor, .24f), tint(primaryColor, .035f),
            ), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
            curtainShader = LinearGradient(0f, height, 0f, 0f, intArrayOf(
                tint(primaryColor, .23f), tint(secondaryColor, .13f), tint(primaryColor, .04f),
            ), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            spectrumShader = LinearGradient(0f, height, 0f, 0f, intArrayOf(
                tint(primaryColor, .62f), tint(primaryColor, .25f), tint(secondaryColor, .78f),
            ), floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
            phasePrimaryGlow = RadialGradient(phaseAx, phaseY, radius * .95f,
                intArrayOf(tint(primaryColor, .25f), tint(primaryColor, .12f), Color.TRANSPARENT),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            phaseSecondaryGlow = RadialGradient(phaseBx, phaseY, radius * .95f,
                intArrayOf(tint(secondaryColor, .25f), tint(secondaryColor, .12f), Color.TRANSPARENT),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            val rimRadius = radius + dp(3f)
            val inner = (1f - dp(22f) / rimRadius).coerceIn(0f, .84f)
            primaryRim = RadialGradient(ox, oy, rimRadius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, tint(primaryColor, .63f), Color.TRANSPARENT),
                floatArrayOf(0f, inner, .96f, 1f), Shader.TileMode.CLAMP)
            secondaryRim = RadialGradient(ox, oy, rimRadius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, tint(secondaryColor, .72f), Color.TRANSPARENT),
                floatArrayOf(0f, inner, .96f, 1f), Shader.TileMode.CLAMP)
            val baseY = max(height * .84f, oy - height * .035f)
            domeShader = LinearGradient(0f, baseY - height * .79f, 0f, baseY,
                intArrayOf(tint(primaryColor, .09f), tint(primaryColor, .19f), tint(secondaryColor, .06f)),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            shadersReady = true
        }
        if (style == PowerMotionStyle.REFRACTION && facetShaders[0] == null) {
            for (i in facets.indices) {
                val color = if (i % 3 == 0) secondaryColor else primaryColor
                facetShaders[i] = LinearGradient(ox, oy, facetCenters[i * 2], facetCenters[i * 2 + 1],
                    intArrayOf(tint(color, .015f), tint(color, .1f), tint(color, .34f)),
                    floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
            }
        }
    }

    private fun prepareWave() {
        if (waveReady) return
        waveRays.rewind()
        for (i in 0 until 22) {
            val angle = i * TAU / 22f
            val r = radius * (.48f + .23f * sin(i * 7.3f))
            val x = cos(angle); val y = sin(angle)
            waveRays.moveTo(ox + x * r * .65f, oy + y * r * .65f)
            waveRays.lineTo(ox + x * r, oy + y * r)
        }
        waveReady = true
    }

    private fun drawWave(canvas: Canvas, p: Float) {
        fillRect(canvas, washShader)
        for (i in 0 until 4) {
            val q = p.pow(.12f) * (1f - i * .125f / p.coerceAtLeast(.001f)).coerceAtLeast(0f)
            if (q <= .005f) continue
            val save = canvas.save()
            try {
                canvas.scale(q, q, ox, oy)
                shaderPaint(if (i % 2 == 0) secondaryRim else primaryRim, 1f - i * .12f)
                canvas.drawCircle(ox, oy, radius + dp(3f), paint)
                solidPaint(if (i % 2 == 0) secondaryColor else primaryColor, .85f - i * .13f,
                    Paint.Style.STROKE, if (i == 0) 2.6f else 1f)
                canvas.drawCircle(ox, oy, radius, paint)
            } finally { canvas.restoreToCount(save) }
        }
        stroke(canvas, waveRays, secondaryColor, .18f, .7f)
    }

    private fun prepareSpectrum() {
        if (spectrumReady) return
        spectrumCuts.rewind()
        var y = 0f
        while (y < height) {
            spectrumCuts.addRect(0f, y, width, y + dp(1.5f), Path.Direction.CW)
            y += dp(11f).coerceAtLeast(1f)
        }
        spectrumReady = true
    }

    private fun drawSpectrum(canvas: Canvas, p: Float, time: Float) {
        fillRect(canvas, curtainShader)
        dynamicPath.rewind(); caps.rewind()
        val base = oy + (height - oy) * p
        val barWidth = width / 42f * .68f
        for (i in 0 until 42) {
            val x = (i + .5f) / 42f * width
            val shape = .75f + .14f * sin(i * .29f + time * 1.7f) + .1f * cos(i * .71f - time * .9f)
            val y = base - height * shape
            dynamicPath.addRect(x - barWidth / 2f, y, x + barWidth / 2f, base, Path.Direction.CW)
            caps.addRect(x - barWidth / 2f, y, x + barWidth / 2f, y + dp(1.6f), Path.Direction.CW)
        }
        shaderPaint(spectrumShader)
        canvas.drawPath(dynamicPath, paint)
        val save = canvas.save()
        try {
            canvas.clipPath(dynamicPath)
            solidPaint(primaryColor, .29f)
            canvas.drawPath(spectrumCuts, paint)
        } finally { canvas.restoreToCount(save) }
        solidPaint(secondaryColor, .95f)
        canvas.drawPath(caps, paint)
    }

    private fun pathDifference(x: Float, y: Float): Float =
        (hypot((x - phaseAx).toDouble(), (y - phaseY).toDouble()) -
            hypot((x - phaseBx).toDouble(), (y - phaseY).toDouble())).toFloat()

    private fun preparePhase() {
        if (phaseReady) return
        phaseNegative.rewind(); phasePositive.rewind(); phaseCentral.rewind(); phaseLattice.rewind()
        val separation = width * .225f
        for (ridge in -7..7) {
            val target = ridge * separation * .245f
            val path = when { ridge < 0 -> phaseNegative; ridge > 0 -> phasePositive; else -> phaseCentral }
            var started = false
            for (j in 0..34) {
                val y = -height * .08f + height * 1.12f * j / 34f
                var low = -width * .12f
                var high = width * 1.12f
                if (target < pathDifference(low, y) || target > pathDifference(high, y)) {
                    started = false
                    continue
                }
                // Once per viewport/origin, not per animation frame.
                repeat(11) {
                    val x = (low + high) / 2f
                    if (pathDifference(x, y) < target) low = x else high = x
                }
                val x = (low + high) / 2f
                if (started) path.lineTo(x, y) else path.moveTo(x, y)
                started = true
            }
        }
        for (row in 1..13) for (column in 0..24) {
            val x = width * column / 24f
            val y0 = height * row / 14f
            val y = y0 + cos(pathDifference(x, y0) / wavelength * TAU) * height * .007f
            if (column == 0) phaseLattice.moveTo(x, y) else phaseLattice.lineTo(x, y)
        }
        phaseReady = true
    }

    private fun drawPhase(canvas: Canvas, time: Float) {
        fillRect(canvas, washShader)
        fillRect(canvas, phasePrimaryGlow)
        fillRect(canvas, phaseSecondaryGlow)
        for (source in 0..1) {
            solidPaint(if (source == 0) primaryColor else secondaryColor, .46f, Paint.Style.STROKE, .85f)
            val x = if (source == 0) phaseAx else phaseBx
            for (i in 0 until 12) {
                val r = (i + fraction(time * .24f)) * wavelength
                canvas.drawCircle(x, phaseY, r, paint)
            }
        }
        stroke(canvas, phaseNegative, primaryColor, .59f, .9f)
        stroke(canvas, phasePositive, secondaryColor, .59f, .9f)
        stroke(canvas, phaseCentral, secondaryColor, .88f, 1.7f)
        stroke(canvas, phaseLattice, primaryColor, .2f, .6f)
        solidPaint(secondaryColor, .94f)
        canvas.drawCircle(phaseAx, phaseY, dp(3.5f), paint)
        canvas.drawCircle(phaseBx, phaseY, dp(3.5f), paint)
    }

    private fun prepareMatrix() {
        if (matrixReady) return
        matrixGrid.rewind(); matrixPrimary.rewind(); matrixSecondary.rewind(); matrixNodes.rewind()
        routes.clear()
        val sx = width / 10f; val sy = height / 16f
        val busX = kotlin.math.round(ox / sx) * sx
        val busY = min(height - sy * .4f, kotlin.math.round(oy / sy) * sy)
        for (column in 0..10) {
            matrixGrid.moveTo(column * sx, 0f); matrixGrid.lineTo(column * sx, height)
        }
        for (row in 0..16) {
            matrixGrid.moveTo(0f, row * sy); matrixGrid.lineTo(width, row * sy)
        }
        for (i in 0 until 30) {
            val x = ((i * 7 + 2) % 11) * sx
            val y = ((i * 5 + i / 11 * 3) % 16 + .15f) * sy
            val turnY = max(sy * .4f, busY - sy * (1 + i % 5))
            val points = floatArrayOf(ox, oy, busX, busY, busX, turnY, x, turnY, x, y)
            val lengths = FloatArray(4)
            var total = 0f
            val routePath = if (i % 2 == 0) matrixPrimary else matrixSecondary
            routePath.moveTo(ox, oy)
            for (k in 0 until 4) {
                val j = k * 2
                lengths[k] = hypot((points[j + 2] - points[j]).toDouble(),
                    (points[j + 3] - points[j + 1]).toDouble()).toFloat()
                total += lengths[k]
                routePath.lineTo(points[j + 2], points[j + 3])
            }
            routes.add(Route(points, lengths, total))
            val size = dp(if (i % 3 == 0) 4.2f else 2.8f)
            matrixNodes.addRect(x - size, y - size, x + size, y + size, Path.Direction.CW)
        }
        matrixReady = true
    }

    private fun drawMatrix(canvas: Canvas, time: Float) {
        fillRect(canvas, washShader)
        stroke(canvas, matrixGrid, primaryColor, .2f, .65f)
        stroke(canvas, matrixPrimary, primaryColor, .15f, 5f)
        stroke(canvas, matrixSecondary, secondaryColor, .15f, 5f)
        stroke(canvas, matrixPrimary, primaryColor, .72f, 1.05f)
        stroke(canvas, matrixSecondary, secondaryColor, .72f, 1.05f)
        solidPaint(primaryColor, .55f)
        canvas.drawPath(matrixNodes, paint)
        dynamicPath.rewind()
        for (i in routes.indices) {
            val route = routes[i]
            var distance = fraction(time * .36f + i * .137f) * route.total
            for (k in route.lengths.indices) {
                val length = route.lengths[k]
                if (distance <= length || k == route.lengths.lastIndex) {
                    val f = if (length > 0f) distance / length else 0f
                    val j = k * 2
                    val x = route.points[j] + (route.points[j + 2] - route.points[j]) * f
                    val y = route.points[j + 1] + (route.points[j + 3] - route.points[j + 1]) * f
                    dynamicPath.addCircle(x, y, dp(2.1f), Path.Direction.CW)
                    break
                }
                distance -= length
            }
        }
        solidPaint(secondaryColor, .96f)
        canvas.drawPath(dynamicPath, paint)
    }

    private fun prepareRefraction() {
        if (refractionReady) return
        for (i in facets.indices) {
            val a = i * TAU / facets.size
            val b = a + TAU / facets.size * .91f
            val x1 = ox + cos(a) * radius; val y1 = oy + sin(a) * radius
            val x2 = ox + cos(b) * radius; val y2 = oy + sin(b) * radius
            facetCenters[i * 2] = (x1 + x2) / 2f
            facetCenters[i * 2 + 1] = (y1 + y2) / 2f
            facets[i].apply {
                rewind(); moveTo(ox, oy); lineTo(x1, y1); lineTo(x2, y2); close()
            }
            facetEdges[i].apply {
                rewind(); moveTo(ox, oy); lineTo(x1, y1); lineTo(x2, y2)
                for (q in 0..2) {
                    val fraction = .38f + q * .22f
                    moveTo(ox + (x1 - ox) * fraction, oy + (y1 - oy) * fraction)
                    lineTo(ox + (x2 - ox) * fraction, oy + (y2 - oy) * fraction)
                }
            }
        }
        refractionReady = true
    }

    private fun drawRefraction(canvas: Canvas, p: Float) {
        val save = canvas.save()
        try {
            canvas.rotate((1f - p) * 44.69f, ox, oy)
            for (i in facets.indices) {
                shaderPaint(facetShaders[i])
                canvas.drawPath(facets[i], paint)
                stroke(canvas, facetEdges[i], if (i % 3 == 0) secondaryColor else primaryColor,
                    .5f, if (i % 3 == 0) 1.3f else .65f)
            }
        } finally { canvas.restoreToCount(save) }
    }

    private fun prepareDome() {
        if (domeReady) return
        domeSurface.rewind(); domeLatitudeFront.rewind(); domeLatitudeBack.rewind()
        domeLatitudeAccent.rewind(); domeMeridians.rewind(); domeMeridianAccent.rewind(); domeGround.rewind()
        domeBaseY = max(height * .84f, oy - height * .035f)
        domeHeight = height * .79f; domeRadius = width * .82f; domeDepth = height * .1f
        domeSurface.moveTo(ox - domeRadius, domeBaseY)
        domeSurface.cubicTo(ox - domeRadius, domeBaseY - domeHeight * .7f,
            ox - domeRadius * .55f, domeBaseY - domeHeight, ox, domeBaseY - domeHeight)
        domeSurface.cubicTo(ox + domeRadius * .55f, domeBaseY - domeHeight,
            ox + domeRadius, domeBaseY - domeHeight * .7f, ox + domeRadius, domeBaseY)
        domeSurface.close()
        for (i in 0 until 12) {
            val latitude = HALF_PI * i / 12f
            val rx = domeRadius * cos(latitude)
            val ry = domeDepth * cos(latitude)
            val cy = domeBaseY - domeHeight * sin(latitude)
            scratchRect.set(ox - rx, cy - ry, ox + rx, cy + ry)
            domeLatitudeBack.addArc(scratchRect, 0f, 180f)
            if (i % 3 == 0) domeLatitudeAccent.addArc(scratchRect, 180f, 180f)
            else domeLatitudeFront.addArc(scratchRect, 180f, 180f)
        }
        for (i in -9..9) {
            val longitude = i / 9f * HALF_PI
            val sinLongitude = sin(longitude); val cosLongitude = cos(longitude)
            val path = if (i % 3 == 0) domeMeridianAccent else domeMeridians
            for (j in 0..36) {
                val latitude = j / 36f * HALF_PI
                val x = ox + domeRadius * cos(latitude) * sinLongitude
                val y = domeBaseY - domeHeight * sin(latitude) - domeDepth * cos(latitude) * cosLongitude
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        for (i in -6..6) {
            val longitude = i / 6f * HALF_PI
            domeGround.moveTo(ox, oy)
            domeGround.lineTo(ox + domeRadius * sin(longitude), domeBaseY - domeDepth * cos(longitude))
        }
        for (i in -4..4) {
            val longitude = i / 4f * HALF_PI * .84f
            domeDotSin[i + 4] = sin(longitude); domeDotCos[i + 4] = cos(longitude)
        }
        domeReady = true
    }

    private fun drawDome(canvas: Canvas, time: Float) {
        fillRect(canvas, washShader)
        shaderPaint(domeShader)
        canvas.drawPath(domeSurface, paint)
        stroke(canvas, domeLatitudeBack, primaryColor, .25f, .75f)
        stroke(canvas, domeLatitudeFront, primaryColor, .58f, .75f)
        stroke(canvas, domeLatitudeAccent, secondaryColor, .58f, 1.25f)
        stroke(canvas, domeMeridians, primaryColor, .32f, .8f)
        stroke(canvas, domeMeridianAccent, secondaryColor, .65f, 1.2f)
        stroke(canvas, domeGround, secondaryColor, .42f, .8f)
        val latitude = (.12f + fraction(time * .27f) * .78f) * HALF_PI
        val latitudeCos = cos(latitude); val latitudeSin = sin(latitude)
        dynamicPath.rewind()
        for (i in domeDotSin.indices) {
            val x = ox + domeRadius * latitudeCos * domeDotSin[i]
            val y = domeBaseY - domeHeight * latitudeSin - domeDepth * latitudeCos * domeDotCos[i]
            dynamicPath.addCircle(x, y, dp(2.2f), Path.Direction.CW)
        }
        solidPaint(secondaryColor, .92f)
        canvas.drawPath(dynamicPath, paint)
    }

    private fun fillRect(canvas: Canvas, shader: Shader?) {
        shaderPaint(shader)
        canvas.drawRect(0f, 0f, width, height, paint)
    }

    private fun shaderPaint(shader: Shader?, alpha: Float = 1f) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = shader
        paint.alpha = (255f * frameAlpha * alpha).toInt().coerceIn(0, 255)
    }

    private fun solidPaint(color: Int, alpha: Float, style: Paint.Style = Paint.Style.FILL, strokeDp: Float = 1f) {
        paint.shader = null
        paint.style = style
        paint.color = color
        paint.alpha = (Color.alpha(color) * frameAlpha * alpha).toInt().coerceIn(0, 255)
        paint.strokeWidth = dp(strokeDp)
    }

    private fun stroke(canvas: Canvas, path: Path, color: Int, alpha: Float, widthDp: Float) {
        solidPaint(color, alpha, Paint.Style.STROKE, widthDp)
        canvas.drawPath(path, paint)
    }

    private fun dp(value: Float) = value * density.coerceAtLeast(.5f)
    private fun fraction(value: Float) = value - kotlin.math.floor(value)
    private fun tint(color: Int, alpha: Float): Int =
        (color and 0x00ffffff) or ((Color.alpha(color) * alpha).toInt().coerceIn(0, 255) shl 24)

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }
    override fun getAlpha() = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Drawable opacity is unused")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val TAU = 6.2831855f
        private const val HALF_PI = 1.5707964f
    }
}
