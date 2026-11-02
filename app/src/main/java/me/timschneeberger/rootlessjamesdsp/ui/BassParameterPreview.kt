package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Parameter-only step-response models. No audio samples, polling, animation, or driver access. */
class BassParameterPreview(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val foregroundColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val releaseColor = if (ColorUtils.calculateLuminance(surface) < .5)
        Color.rgb(255, 180, 95) else Color.rgb(170, 78, 15)
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foregroundColor
        textSize = min(11f * scaledDensity, 13f * density)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0xc0)
        textSize = min(9f * scaledDensity, 11f * density)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0x20)
        strokeWidth = density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0x38)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val boundsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0x99)
        style = Paint.Style.STROKE
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val frequencyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val releasePaint = Paint(frequencyPaint).apply { color = releaseColor }
    private val referenceAttackPaint = Paint(frequencyPaint).apply {
        strokeWidth = 1.2f * density
        alpha = 155
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val referenceReleasePaint = Paint(referenceAttackPaint).apply {
        color = ColorUtils.setAlphaComponent(releaseColor, 155)
    }
    private val trackingMarkerPaint = Paint(boundsPaint).apply { color = ColorUtils.setAlphaComponent(accent, 0xa0) }
    private val releaseMarkerPaint = Paint(boundsPaint).apply { color = ColorUtils.setAlphaComponent(releaseColor, 0xa0) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val releaseDotPaint = Paint(dotPaint).apply { color = releaseColor }
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(accent, 0x16) }
    private val bandwidthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(releaseColor, 0x1c) }
    private val cursorPaint = Paint(trackingMarkerPaint)
    private val cursorReleasePaint = Paint(releaseMarkerPaint)
    private val cursorReleaseOutline = Paint(releaseDotPaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val tooltipBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.blendARGB(surface, accent, .07f)
    }
    private val tooltipBorder = Paint(borderPaint).apply { color = ColorUtils.setAlphaComponent(accent, 0x90) }
    private val tooltipText = Paint(textPaint).apply {
        color = foregroundColor
        textSize = min(10f * scaledDensity, 12f * density)
        textAlign = Paint.Align.LEFT
    }
    private val tooltipBounds = RectF()
    private val frequencyArea = RectF()
    private val gainArea = RectF()
    private val followPlot = RectF()
    private val peakPlot = RectF()
    private val gainPlot = RectF()
    private val followPath = Path()
    private val peakPath = Path()
    private val attackPath = Path()
    private val releasePath = Path()
    private val referenceAttackPath = Path()
    private val referenceReleasePath = Path()
    private val followTicks = doubleArrayOf(0.0, 20.0, 200.0, 1000.0)
    private val followTickLabels = arrayOf("0", "20", "200", "1000 ms")
    private val gainTicks = doubleArrayOf(0.0, 20.0, 200.0, 1000.0, 5000.0)
    private val gainTickLabels = arrayOf("0", "20", "200", "1000", "5000 ms")
    private val peakTicks = doubleArrayOf(0.0, 125.0, 250.0)
    private val peakTickLabels = arrayOf("0", "125", "250 Hz")
    private var lower = 0.0
    private var upper = 250.0
    private var maximumGain = 5.0
    private var quality = 2.0
    private var tracking = 48.0
    private var attack = 192.0
    private var release = 192.0
    private var mode = 0
    private var centerFrequency = 125.0
    private var bandwidthLow = 93.75
    private var bandwidthHigh = 156.25
    private var followLegend = "跟随 48 ms · 0–250 Hz"
    private var peakLegend = "Q 2.00 · 峰形 dB"
    private var attackLegend = "上升 192 ms"
    private var releaseLegend = "回落 192 ms"
    private var maximumLabel = "5.0 dB"
    private enum class CursorPane { FOLLOW, PEAK, GAIN }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var pendingPane: CursorPane? = null
    private var cursorPane: CursorPane? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var cursorX = 0f
    private var cursorValue = Double.NaN
    private var cursorSecondary = Double.NaN
    private var cursorLabels = emptyList<String>()
    private val longPress = Runnable {
        if (pendingPane != null && activePointerId != MotionEvent.INVALID_POINTER_ID && cursorPane == null)
            beginInspection(lastTouchX)
    }

    init {
        minimumHeight = (224f * density).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateAccessibility()
    }

    fun setConfiguration(
        lowerHz: Double,
        upperHz: Double,
        maxGainDb: Double,
        q: Double,
        trackingMs: Double,
        attackMs: Double,
        releaseMs: Double
    ) {
        val nextLower = finite(lowerHz, 0.0).coerceIn(0.0, 249.0)
        val nextUpper = finite(upperHz, 250.0).coerceIn(nextLower + 1.0, 250.0)
        val nextGain = finite(maxGainDb, 5.0).coerceIn(0.0, 15.0)
        val nextQ = finite(q, 2.0).coerceIn(.5, 4.0)
        val nextTracking = finite(trackingMs, 48.0).coerceIn(5.0, 1000.0)
        val nextAttack = finite(attackMs, 192.0).coerceIn(5.0, 2000.0)
        val nextRelease = finite(releaseMs, 192.0).coerceIn(5.0, 5000.0)
        if (lower == nextLower && upper == nextUpper && maximumGain == nextGain && quality == nextQ &&
            tracking == nextTracking && attack == nextAttack && release == nextRelease) return
        endInspection()
        lower = nextLower
        upper = nextUpper
        maximumGain = nextGain
        quality = nextQ
        tracking = nextTracking
        attack = nextAttack
        release = nextRelease
        centerFrequency = (lower + upper) * .5
        // This shaded width is a Q illustration (center / Q), not a measured spectral band.
        val halfWidth = centerFrequency / (2.0 * quality)
        bandwidthLow = max(0.0, centerFrequency - halfWidth)
        bandwidthHigh = min(250.0, centerFrequency + halfWidth)
        followLegend = String.format(Locale.ROOT, "跟随 %.0f ms · %.0f–%.0f Hz", tracking, lower, upper)
        peakLegend = String.format(Locale.ROOT, "Q %.2f · 峰形 dB", quality)
        attackLegend = String.format(Locale.ROOT, "上升 %.0f ms", attack)
        releaseLegend = String.format(Locale.ROOT, "回落 %.0f ms", release)
        maximumLabel = String.format(Locale.ROOT, "%.1f dB", maximumGain)
        updateAccessibility()
        rebuildGeometry()
        invalidate()
    }

    fun setMode(value: Int) {
        val next = value.coerceIn(0, 1)
        if (mode == next) return
        endInspection()
        mode = next
        rebuildGeometry()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize((336f * density).toInt(), widthMeasureSpec),
            resolveSize((224f * density).toInt(), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        endInspection()
        rebuildGeometry()
    }

    override fun onDetachedFromWindow() {
        endInspection()
        super.onDetachedFromWindow()
    }

    override fun onCancelPendingInputEvents() {
        super.onCancelPendingInputEvents()
        endInspection()
    }

    private fun rebuildGeometry() {
        if (width <= 0 || height <= 0) return
        val left = 1f * density
        val right = width - 1f * density
        val top = 26f * density
        val bottom = height - 2f * density
        val gap = 9f * density
        val usableHeight = max(0f, bottom - top - gap)
        val large = usableHeight * .61f
        val small = usableHeight - large
        if (mode == 0) {
            frequencyArea.set(left, top, right, top + large)
            gainArea.set(left, top + large + gap, right, bottom)
        } else {
            gainArea.set(left, top, right, top + large)
            frequencyArea.set(left, top + large + gap, right, top + large + gap + small)
        }
        val frequencySplit = frequencyArea.left + frequencyArea.width() * .58f
        val axisMargin = max(22f * density, textPaint.measureText("100%") + 6f * density)
        followPlot.set(frequencyArea.left + axisMargin, frequencyArea.top + 17f * density,
            frequencySplit - 7f * density, frequencyArea.bottom - 16f * density)
        peakPlot.set(frequencySplit + 18f * density, frequencyArea.top + 17f * density,
            frequencyArea.right - 3f * density, frequencyArea.bottom - 16f * density)
        gainPlot.set(gainArea.left + axisMargin, gainArea.top + 17f * density,
            gainArea.right - 3f * density, gainArea.bottom - 16f * density)
        followPath.rewind()
        peakPath.rewind()
        attackPath.rewind()
        releasePath.rewind()
        referenceAttackPath.rewind()
        referenceReleasePath.rewind()
        if (!plotsValid()) return
        for (index in 0..CURVE_STEPS) {
            val fraction = index.toDouble() / CURVE_STEPS
            val followTime = timeAtFraction(fraction, FOLLOW_TIME_MAX)
            val gainTime = timeAtFraction(fraction, GAIN_TIME_MAX)
            val frequency = lower + (upper - lower) * (1.0 - exp(-followTime / tracking))
            val rising = maximumGain * (1.0 - exp(-gainTime / attack))
            val falling = maximumGain * exp(-gainTime / release)
            val peakFrequency = fraction * FREQUENCY_MAX
            val followX = followPlot.left + followPlot.width() * fraction.toFloat()
            val gainX = gainPlot.left + gainPlot.width() * fraction.toFloat()
            val peakX = peakPlot.left + peakPlot.width() * fraction.toFloat()
            append(followPath, index, followX, valueY(followPlot, frequency, FREQUENCY_MAX))
            append(attackPath, index, gainX, valueY(gainPlot, rising, GAIN_MAX))
            append(releasePath, index, gainX, valueY(gainPlot, falling, GAIN_MAX))
            append(peakPath, index, peakX, valueY(peakPlot, peakResponse(peakFrequency), GAIN_MAX))
            if (maximumGain == 0.0) {
                append(referenceAttackPath, index, gainX,
                    valueY(gainPlot, 1.0 - exp(-gainTime / attack), 1.0))
                append(referenceReleasePath, index, gainX,
                    valueY(gainPlot, exp(-gainTime / release), 1.0))
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        headingPaint.textAlign = Paint.Align.LEFT
        drawFitted(canvas, "参数响应预览 · 模型", 1f * density, 15f * density,
            width * .48f, headingPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        drawFitted(canvas, if (cursorPane == null) "长按/横拖查看 · 对数时间" else "模型游标 · 松开返回",
            width - 1f * density, 15f * density,
            width * .49f, textPaint)
        if (!plotsValid()) return
        drawFrequencyModels(canvas)
        drawGainModel(canvas)
        if (cursorPane != null) drawCursor(canvas)
    }

    private fun drawFrequencyModels(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.LEFT
        drawFitted(canvas, followLegend, frequencyArea.left, frequencyArea.top + 10f * density,
            followPlot.right - frequencyArea.left, textPaint)
        drawFitted(canvas, peakLegend, peakPlot.left - 13f * density, frequencyArea.top + 10f * density,
            frequencyArea.right - peakPlot.left + 13f * density, textPaint)

        canvas.drawRect(followPlot.left, valueY(followPlot, upper, FREQUENCY_MAX),
            followPlot.right, valueY(followPlot, lower, FREQUENCY_MAX), rangePaint)
        drawYGrid(canvas, followPlot, FREQUENCY_MAX, true)
        drawTimeAxis(canvas, followPlot, FOLLOW_TIME_MAX, followTicks, followTickLabels)
        val lowY = valueY(followPlot, lower, FREQUENCY_MAX)
        val highY = valueY(followPlot, upper, FREQUENCY_MAX)
        canvas.drawLine(followPlot.left, lowY, followPlot.right, lowY, boundsPaint)
        canvas.drawLine(followPlot.left, highY, followPlot.right, highY, boundsPaint)
        val trackingX = timeX(followPlot, tracking, FOLLOW_TIME_MAX)
        canvas.drawLine(trackingX, followPlot.top, trackingX, followPlot.bottom, trackingMarkerPaint)
        canvas.drawPath(followPath, frequencyPaint)
        canvas.drawCircle(trackingX,
            valueY(followPlot, lower + (upper - lower) * (1.0 - exp(-1.0)), FREQUENCY_MAX),
            2.5f * density, dotPaint)
        canvas.drawRect(followPlot, borderPaint)

        val bandLeft = peakPlot.left + peakPlot.width() * (bandwidthLow / FREQUENCY_MAX).toFloat()
        val bandRight = peakPlot.left + peakPlot.width() * (bandwidthHigh / FREQUENCY_MAX).toFloat()
        canvas.drawRect(bandLeft, peakPlot.top, bandRight, peakPlot.bottom, bandwidthPaint)
        drawYGrid(canvas, peakPlot, GAIN_MAX, false)
        for (index in peakTicks.indices) {
            val x = peakPlot.left + peakPlot.width() * (peakTicks[index] / FREQUENCY_MAX).toFloat()
            canvas.drawLine(x, peakPlot.top, x, peakPlot.bottom, gridPaint)
            textPaint.textAlign = when (index) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            drawFitted(canvas, peakTickLabels[index], x, peakPlot.bottom + 12f * density,
                peakPlot.width() * .35f, textPaint)
        }
        canvas.drawPath(peakPath, frequencyPaint)
        val centerX = peakPlot.left + peakPlot.width() * (centerFrequency / FREQUENCY_MAX).toFloat()
        canvas.drawLine(centerX, peakPlot.top, centerX, peakPlot.bottom, trackingMarkerPaint)
        canvas.drawCircle(centerX, valueY(peakPlot, maximumGain, GAIN_MAX), 2.5f * density, dotPaint)
        canvas.drawRect(peakPlot, borderPaint)
    }

    private fun drawGainModel(canvas: Canvas) {
        val legendY = gainArea.top + 10f * density
        canvas.drawLine(gainArea.left, legendY - 3f * density,
            gainArea.left + 13f * density, legendY - 3f * density, frequencyPaint)
        textPaint.textAlign = Paint.Align.LEFT
        drawFitted(canvas, attackLegend, gainArea.left + 18f * density, legendY,
            gainArea.width() * .43f, textPaint)
        val releaseLegendX = gainArea.left + gainArea.width() * .55f
        canvas.drawLine(releaseLegendX, legendY - 3f * density,
            releaseLegendX + 13f * density, legendY - 3f * density, releasePaint)
        drawFitted(canvas, releaseLegend, releaseLegendX + 18f * density, legendY,
            gainArea.right - releaseLegendX - 18f * density, textPaint)
        drawYGrid(canvas, gainPlot, GAIN_MAX, true, normalizedReference = maximumGain == 0.0)
        drawTimeAxis(canvas, gainPlot, GAIN_TIME_MAX, gainTicks, gainTickLabels)
        val maximumY = valueY(gainPlot, maximumGain, GAIN_MAX)
        canvas.drawLine(gainPlot.left, maximumY, gainPlot.right, maximumY, boundsPaint)
        val attackX = timeX(gainPlot, attack, GAIN_TIME_MAX)
        val releaseX = timeX(gainPlot, release, GAIN_TIME_MAX)
        canvas.drawLine(attackX, gainPlot.top, attackX, gainPlot.bottom, trackingMarkerPaint)
        canvas.drawLine(releaseX, gainPlot.top, releaseX, gainPlot.bottom, releaseMarkerPaint)
        if (maximumGain == 0.0) {
            canvas.drawPath(referenceAttackPath, referenceAttackPaint)
            canvas.drawPath(referenceReleasePath, referenceReleasePaint)
            textPaint.textAlign = Paint.Align.CENTER
            drawFitted(canvas, "零增益 · 虚线为归一化时间参考", gainPlot.centerX(),
                gainPlot.top + textPaint.fontSpacing, gainPlot.width() - 8f * density, textPaint)
        }
        canvas.drawPath(attackPath, frequencyPaint)
        canvas.drawPath(releasePath, releasePaint)
        canvas.drawCircle(attackX, valueY(gainPlot, maximumGain * (1.0 - exp(-1.0)), GAIN_MAX),
            2.5f * density, dotPaint)
        canvas.drawCircle(releaseX, valueY(gainPlot, maximumGain * exp(-1.0), GAIN_MAX),
            2.5f * density, releaseDotPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        val labelY = if (maximumY - gainPlot.top > textPaint.fontSpacing + 3f * density)
            maximumY - 3f * density else gainPlot.top + textPaint.fontSpacing
        drawFitted(canvas, maximumLabel, gainPlot.right - 3f * density, labelY,
            gainPlot.width() * .28f, textPaint)
        canvas.drawRect(gainPlot, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) { endInspection(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                endInspection()
                if (!plotsValid() || !event.x.isFinite() || !event.y.isFinite()) return false
                pendingPane = when {
                    followPlot.contains(event.x, event.y) -> CursorPane.FOLLOW
                    peakPlot.contains(event.x, event.y) -> CursorPane.PEAK
                    gainPlot.contains(event.x, event.y) -> CursorPane.GAIN
                    else -> return false
                }
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                // Leave vertical scrolling to the parent until a long press or horizontal drag.
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) { endInspection(); return false }
                val x = event.getX(index)
                val y = event.getY(index)
                if (!x.isFinite() || !y.isFinite()) { endInspection(); return false }
                lastTouchX = x
                if (cursorPane != null) { updateCursor(x); return true }
                if (pendingPane == null) return false
                val dx = abs(x - downX)
                val dy = abs(y - downY)
                if (dy > touchSlop && dy >= dx) { endInspection(); return false }
                if (dx > touchSlop && dx > dy * 1.2f) beginInspection(x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val inspected = cursorPane != null
                endInspection()
                if (!inspected) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE, MotionEvent.ACTION_POINTER_DOWN -> {
                endInspection()
                return event.actionMasked == MotionEvent.ACTION_CANCEL
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    endInspection()
                    return false
                }
            }
        }
        return pendingPane != null || cursorPane != null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun beginInspection(x: Float) {
        val pane = pendingPane ?: return
        if (!isAttachedToWindow || !isShown || !isEnabled || !plotsValid()) return
        removeCallbacks(longPress)
        cursorPane = pane
        parent?.requestDisallowInterceptTouchEvent(true)
        updateCursor(x)
    }

    private fun endInspection() {
        removeCallbacks(longPress)
        val wasInspecting = cursorPane != null
        cursorPane = null
        pendingPane = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        cursorValue = Double.NaN
        cursorSecondary = Double.NaN
        cursorLabels = emptyList()
        if (wasInspecting) {
            parent?.requestDisallowInterceptTouchEvent(false)
            updateAccessibility()
            invalidate()
        }
    }

    private fun cursorPlot(): RectF? = when (cursorPane) {
        CursorPane.FOLLOW -> followPlot
        CursorPane.PEAK -> peakPlot
        CursorPane.GAIN -> gainPlot
        null -> null
    }

    private fun updateCursor(x: Float) {
        val plot = cursorPlot() ?: return
        if (!x.isFinite() || plot.width() <= 0f) return
        cursorX = x.coerceIn(plot.left, plot.right)
        val fraction = ((cursorX - plot.left) / plot.width()).toDouble().coerceIn(0.0, 1.0)
        cursorSecondary = Double.NaN
        when (cursorPane) {
            CursorPane.FOLLOW -> {
                val time = timeAtFraction(fraction, FOLLOW_TIME_MAX)
                cursorValue = lower + (upper - lower) * (1.0 - exp(-time / tracking))
                cursorLabels = listOf("频率跟随模型 · ${precise(time)} ms", "跟随频率 ${precise(cursorValue)} Hz")
            }
            CursorPane.PEAK -> {
                val frequency = fraction * FREQUENCY_MAX
                cursorValue = peakResponse(frequency)
                cursorLabels = listOf("峰形参数模型 · ${precise(frequency)} Hz", "增益 ${precise(cursorValue)} dB")
            }
            CursorPane.GAIN -> {
                val time = timeAtFraction(fraction, GAIN_TIME_MAX)
                // These are the actual model gains, including 0 dB at zero maximum gain.
                // The dashed normalized timing reference never supplies cursor values.
                cursorValue = maximumGain * (1.0 - exp(-time / attack))
                cursorSecondary = maximumGain * exp(-time / release)
                cursorLabels = listOf("增益包络模型 · ${precise(time)} ms",
                    "上升增益 ${precise(cursorValue)} dB", "回落增益 ${precise(cursorSecondary)} dB")
            }
            null -> return
        }
        updateAccessibility()
        invalidate()
    }

    private fun drawCursor(canvas: Canvas) {
        val plot = cursorPlot() ?: return
        val maximum = if (cursorPane == CursorPane.FOLLOW) FREQUENCY_MAX else GAIN_MAX
        canvas.save()
        canvas.clipRect(plot)
        canvas.drawLine(cursorX, plot.top, cursorX, plot.bottom, cursorPaint)
        if (cursorValue.isFinite()) {
            val y = valueY(plot, cursorValue, maximum).coerceIn(plot.top, plot.bottom)
            canvas.drawLine(plot.left, y, plot.right, y, cursorPaint)
            canvas.drawCircle(cursorX, y, 3.5f * density, dotPaint)
        }
        if (cursorPane == CursorPane.GAIN && cursorSecondary.isFinite()) {
            val y = valueY(plot, cursorSecondary, GAIN_MAX).coerceIn(plot.top, plot.bottom)
            canvas.drawLine(plot.left, y, plot.right, y, cursorReleasePaint)
            // An outline keeps both colors visible when the two gains coincide at zero.
            canvas.drawCircle(cursorX, y, 4.5f * density, cursorReleaseOutline)
        }
        canvas.restore()
        drawCursorTooltip(canvas)
    }

    private fun drawCursorTooltip(canvas: Canvas) {
        if (cursorLabels.isEmpty()) return
        // Temporarily use the opposite model's plot area, leaving headings, ticks and the selected curve visible.
        val zoneTop = if (cursorPane == CursorPane.GAIN) followPlot.top else gainPlot.top
        val zoneBottom = if (cursorPane == CursorPane.GAIN) followPlot.bottom else gainPlot.bottom
        val margin = 3f * density
        val availableHeight = zoneBottom - zoneTop - margin * 2f
        val availableWidth = width - margin * 2f
        if (availableHeight <= 0f || availableWidth <= 0f) return
        val savedSize = tooltipText.textSize
        val padding = min(5f * density, availableHeight / 10f)
        val desiredLineHeight = tooltipText.fontSpacing * 1.1f
        val lineHeight = min(desiredLineHeight, (availableHeight - padding * 2f) / cursorLabels.size)
        if (lineHeight < desiredLineHeight) tooltipText.textSize *= lineHeight / desiredLineHeight
        val textWidth = cursorLabels.maxOfOrNull { tooltipText.measureText(it) } ?: 0f
        val boxWidth = min(availableWidth, textWidth + padding * 2f)
        val boxHeight = lineHeight * cursorLabels.size + padding * 2f
        val left = ((width - boxWidth) * .5f).coerceIn(margin, max(margin, width - margin - boxWidth))
        val top = zoneTop + (zoneBottom - zoneTop - boxHeight) * .5f
        tooltipBounds.set(left, top, left + boxWidth, top + boxHeight)
        canvas.drawRoundRect(tooltipBounds, 5f * density, 5f * density, tooltipBackground)
        canvas.drawRoundRect(tooltipBounds, 5f * density, 5f * density, tooltipBorder)
        var baseline = top + padding + (lineHeight - tooltipText.fontMetrics.top - tooltipText.fontMetrics.bottom) * .5f
        cursorLabels.forEachIndexed { index, label ->
            tooltipText.color = when {
                cursorPane == CursorPane.GAIN && index == 1 -> accent
                cursorPane == CursorPane.GAIN && index == 2 -> releaseColor
                else -> foregroundColor
            }
            drawFitted(canvas, label, left + padding, baseline, boxWidth - padding * 2f, tooltipText)
            baseline += lineHeight
        }
        tooltipText.textSize = savedSize
        tooltipText.color = foregroundColor
    }

    private fun drawYGrid(canvas: Canvas, plot: RectF, maximum: Double, allowMiddleLabel: Boolean,
        normalizedReference: Boolean = false) {
        val frequency = maximum == FREQUENCY_MAX
        val steps = if (frequency) 2 else 3
        for (index in 0..steps) {
            val value = maximum * index / steps
            val y = valueY(plot, value, maximum)
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            if (index == 0 || index == steps || (allowMiddleLabel && plot.height() >= 65f * density)) {
                textPaint.textAlign = Paint.Align.RIGHT
                val label = when {
                    normalizedReference && index == steps -> "100%"
                    normalizedReference && index == 1 -> "33%"
                    normalizedReference && index == 2 -> "67%"
                    frequency && index == steps -> "250"
                    frequency && index == 1 -> "125"
                    index == 0 -> "0"
                    index == 1 -> "5"
                    index == 2 -> "10"
                    else -> "15"
                }
                canvas.drawText(label, plot.left - 4f * density, y + 3f * density, textPaint)
            }
        }
    }

    private fun drawTimeAxis(canvas: Canvas, plot: RectF, maximum: Double, ticks: DoubleArray, labels: Array<String>) {
        for (index in ticks.indices) {
            val x = timeX(plot, ticks[index], maximum)
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            textPaint.textAlign = when (index) { 0 -> Paint.Align.LEFT; ticks.lastIndex -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            drawFitted(canvas, labels[index], x, plot.bottom + 12f * density,
                plot.width() * if (ticks.size == 4) .29f else .23f, textPaint)
        }
    }

    /** Ideal analog peaking response, centered in the selected band; explicitly a model. */
    private fun peakResponse(frequency: Double): Double {
        if (maximumGain == 0.0 || frequency == 0.0) return 0.0
        val ratio = frequency / centerFrequency
        val a = 10.0.pow(maximumGain / 40.0)
        val base = (1.0 - ratio * ratio).pow(2.0)
        val numerator = base + (a * ratio / quality).pow(2.0)
        val denominator = base + (ratio / (a * quality)).pow(2.0)
        return (10.0 * log10(numerator / denominator)).coerceIn(0.0, GAIN_MAX)
    }

    private fun drawFitted(canvas: Canvas, label: String, x: Float, y: Float, available: Float, paint: Paint) {
        if (available <= 0f) return
        val savedSize = paint.textSize
        val measured = paint.measureText(label)
        if (measured > available) paint.textSize *= available / measured
        canvas.drawText(label, x, y, paint)
        paint.textSize = savedSize
    }

    private fun updateAccessibility() {
        contentDescription = String.format(Locale.ROOT,
            "参数响应预览，仅显示模型。跟踪范围 %.0f 至 %.0f Hz，最大增益 %.1f dB，Q %.2f，" +
                "频率跟随 %.0f ms，增益上升 %.0f ms、回落 %.0f ms。时间使用固定对数毫秒刻度。",
            lower, upper, maximumGain, quality, tracking, attack, release) +
            if (cursorPane != null) cursorLabels.joinToString("。") else "长按或横向拖动查看模型数值，松开恢复原图。"
    }

    private fun plotsValid() = followPlot.width() > 0f && followPlot.height() > 0f &&
        peakPlot.width() > 0f && peakPlot.height() > 0f && gainPlot.width() > 0f && gainPlot.height() > 0f

    private fun append(path: Path, index: Int, x: Float, y: Float) {
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    private fun timeX(plot: RectF, time: Double, maximum: Double) = plot.left + plot.width() *
        (ln(1.0 + time / TIME_OFFSET) / ln(1.0 + maximum / TIME_OFFSET)).toFloat()

    private fun timeAtFraction(fraction: Double, maximum: Double) =
        TIME_OFFSET * (exp(fraction * ln(1.0 + maximum / TIME_OFFSET)) - 1.0)

    private fun valueY(plot: RectF, value: Double, maximum: Double) =
        plot.bottom - plot.height() * (value / maximum).toFloat()

    private fun finite(value: Double, fallback: Double) = value.takeIf { it.isFinite() } ?: fallback

    private fun precise(value: Double): String = if (!value.isFinite()) "—" else String.format(Locale.ROOT, "%.6g", value)

    companion object {
        private const val CURVE_STEPS = 160
        private const val TIME_OFFSET = 5.0
        private const val FOLLOW_TIME_MAX = 1000.0
        private const val GAIN_TIME_MAX = 5000.0
        private const val FREQUENCY_MAX = 250.0
        private const val GAIN_MAX = 15.0
    }
}
