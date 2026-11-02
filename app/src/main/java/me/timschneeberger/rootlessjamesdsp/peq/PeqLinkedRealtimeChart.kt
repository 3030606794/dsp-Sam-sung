package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.eq.SpectrumDisplayOptions
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Only the PEQ tap's unnormalised, same-window PCM may enter this chart. */
internal class PeqLinkedRealtimeChart(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val inputColor = if (ColorUtils.calculateLuminance(MaterialColors.getColor(this,
        com.google.android.material.R.attr.colorSurface)) < .5) 0xff68d8ec.toInt() else 0xff107c92.toInt()
    private val muted = ColorUtils.setAlphaComponent(ink, 150)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = min(9f * resources.displayMetrics.scaledDensity, 10f * d)
        fontFeatureSettings = "tnum"
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.35f * d; strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 35); strokeWidth = .65f * d
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inputPath = Path()
    private val outputPath = Path()
    private val heldOutputPath = Path()
    private val areaPath = Path()
    private val ledBackgroundPath = Path()
    private val ledOutputPath = Path()
    private val ledCapsPath = Path()
    private val ledGapsPath = Path()
    private val groupedInputPath = Path()
    private val groupedOutputPath = Path()
    private val groupedInputTops = Path()
    private val groupedOutputTops = Path()
    private val groupedPeakPath = Path()
    private val gainedPath = Path()
    private val reducedPath = Path()
    private val gainedCaps = Path()
    private val reducedCaps = Path()
    private val axesPicture = Picture()
    private var axesKey = ""
    private var traceKey = ""
    private var cachedOutputSpan: TraceSpan? = null
    private var differenceKey = ""
    private val bounds = RectF()
    private val pendingDash = DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)
    private var chartStyle = 0
    private var options = SpectrumDisplayOptions()
    private var minimumDb = -66.0
    private var maximumDb = 0.0
    private var axisReady = false
    private var axisFallStarted = 0L
    private var axisLastStep = 0L
    private var lastDrawAt = 0L
    private var redrawPosted = false
    private val redraw = Runnable { redrawPosted = false; invalidate() }
    private class DisplayPlane(size: Int) {
        val amplitude = DoubleArray(size)
        val levels = DoubleArray(size)
        val peak = DoubleArray(size)
        val until = LongArray(size)
    }
    private var displayInput: DisplayPlane? = null
    private var displayOutput: DisplayPlane? = null
    private var displayDomain: Domain? = null
    private var displayAt = 0L
    private var displayRevision = 0L
    private var areaGradient: LinearGradient? = null
    private var gradientTop = Float.NaN
    private var gradientBottom = Float.NaN
    private var gradientColor = 0
    private var ledGradient: LinearGradient? = null
    private var ledGradientLeft = Float.NaN
    private var ledGradientRight = Float.NaN
    private val ledColors = floatArrayOf(235f, 188f, 141f, 94f, 47f, 0f).map {
        Color.HSVToColor(floatArrayOf(it, .78f, 1f))
    }.toIntArray()
    private data class Domain(val capture: Int, val generation: Int, val rate: Int)
    private data class Frame(val domain: Domain, val sequence: Int, val revision: Long,
        val inputLeft: FloatArray, val inputRight: FloatArray, val outputLeft: FloatArray,
        val outputRight: FloatArray, val transitioning: Boolean, val moduleEnabled: Boolean,
        val first: Long, val last: Long, val epoch: Int)
    private data class Spectrum(val frame: Frame, val input: DoubleArray, val output: DoubleArray,
        val inputRms: Double, val outputRms: Double, val inputPeak: Double, val outputPeak: Double,
        val frames: Int)
    private var source: Frame? = null
    private var spectrum: Spectrum? = null
    private var status = "等待 PEQ 只读采样"
    private var revision = 0L
    private var shownRevision = -1L
    private var active = false
    private var worker: Worker? = null
    private var lifecycle = 0L
    private var initialized = false

    init {
        isLongClickable = true
        setOnLongClickListener { showDetails(); true }
        contentDescription = "参数均衡器真实输入与输出音乐幅度谱，等待只读采样"
        initialized = true
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value && isAttachedToWindow && isShown && hasWindowFocus())
            source?.takeIf { it.revision > shownRevision }?.let(::submit)
        else {
            stopWorker(); removeCallbacks(redraw); redrawPosted = false
        }
        requestRedraw()
    }

    fun setStatus(value: String) {
        if (status == value) return
        status = value; describeStatus(); requestRedraw()
    }

    private fun requestRedraw() {
        if (!hasWindowFocus() || redrawPosted) return
        redrawPosted = true
        postDelayed(redraw, max(0L, 34L - (SystemClock.elapsedRealtime() - lastDrawAt)))
    }
    private fun describeStatus() {
        val value = "真实输入／输出频谱，$status"
        if (contentDescription != value) contentDescription = value
    }

    /** Compatibility only: the live spectrum is exclusively measured PCM, without model overlays. */
    @Suppress("UNUSED_PARAMETER")
    fun setModelResponse(plot: LiveprogStaticPlot?) = Unit

    /** Changes presentation only; the current measured window and FFT worker stay intact. */
    fun setChartStyle(value: Int) {
        val next = value.coerceIn(0, 4)
        if (chartStyle == next) return
        chartStyle = next
        describeStatus()
        requestRedraw()
    }

    /** Display options never change the PCM tap, the FFT calculation, or audio parameters. */
    fun setDisplayOptions(value: SpectrumDisplayOptions) {
        val normalized = value.normalized()
        if (options == normalized) return
        val wasHolding = options.peakHold
        options = normalized
        minimumDb = displayFloor()
        // A zero response is immediate, including the already accepted window. Changing scale or
        // sensitivity reprojects the same real FFT data without waiting for another capture.
        if (normalized.responseMs == 0) spectrum?.let { measured ->
            listOf(measured.input to displayInput, measured.output to displayOutput).forEach { (levels, plane) ->
                if (plane != null && plane.levels.size == levels.size) levels.forEachIndexed { index, level ->
                    plane.levels[index] = level
                    plane.amplitude[index] = if (level.isFinite()) 10.0.pow(level / 20.0) else 0.0
                }
            }
        }
        if (wasHolding != normalized.peakHold) spectrum?.let { measured ->
            val now = SystemClock.elapsedRealtime()
            listOf(measured.input to displayInput, measured.output to displayOutput).forEach { (levels, plane) ->
                if (plane != null && plane.peak.size == levels.size) levels.forEachIndexed { index, level ->
                    plane.peak[index] = level; plane.until[index] = now + 500L
                }
            }
        }
        displayRevision++
        spectrum?.let { followAxis(it, false) }
        describeStatus(); requestRedraw()
    }

    fun clear(message: String) {
        stopWorker(); source = null; spectrum = null; shownRevision = -1L
        resetDisplay()
        status = message; requestRedraw()
    }

    fun setSample(sample: PeqPcmTelemetry.Snapshot) {
        if (!active || !hasWindowFocus()) return
        if (!sample.leaseActive || sample.invalidSamples) {
            clear(if (sample.invalidSamples) "采样含无效值，等待有效窗口" else "只读采样已暂停")
            return
        }
        if (!sample.valid || sample.rate !in 8000..768000) {
            status = if (sample.transitioning || sample.configurationChanged) "参数切换中，等待完整窗口" else "等待完整 PCM 窗口"
            requestRedraw(); return
        }
        val size = minOf(sample.frames, sample.inputLeft.size, sample.inputRight.size,
            sample.outputLeft.size, sample.outputRight.size)
        if (size < 64) return
        val domain = Domain(sample.captureId, sample.generation, sample.rate)
        if (source?.domain != domain) {
            stopWorker(); spectrum = null; shownRevision = -1L; resetDisplay()
        }
        if (source?.domain == domain && source?.sequence == sample.sequence) return
        val frame = Frame(domain, sample.sequence, ++revision,
            sample.inputLeft.copyOf(size), sample.inputRight.copyOf(size), sample.outputLeft.copyOf(size),
            sample.outputRight.copyOf(size), sample.transitioning || sample.configurationChanged, sample.moduleEnabled,
            sample.firstFramePosition, sample.lastFramePosition, sample.configurationEpoch)
        source = frame
        status = when {
            sample.transitioning || sample.configurationChanged -> "参数过渡窗口 · 真实前后采样"
            sample.moduleEnabled -> "真实 PEQ 前／后 · 正常处理"
            else -> "PEQ 已关闭 · 真实旁路前／后"
        }
        submit(frame)
    }

    private fun submit(frame: Frame) {
        if (!active || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE || !hasWindowFocus()) return
        val token = lifecycle
        val current = worker ?: Worker { result ->
            postOnAnimation {
                if (token != lifecycle || !active || !isAttachedToWindow || !isShown || !hasWindowFocus() ||
                    source?.domain != result.frame.domain || result.frame.revision <= shownRevision) return@postOnAnimation
                shownRevision = result.frame.revision
                spectrum = result
                updateDisplay(result)
                describeStatus()
                requestRedraw()
            }
        }.also { worker = it }
        current.submit(frame)
    }

    private fun stopWorker() { lifecycle++; worker?.close(); worker = null }

    private fun resetDisplay() {
        displayInput = null; displayOutput = null; displayDomain = null; displayAt = 0L
        axisReady = false; axisFallStarted = 0L; axisLastStep = 0L
        maximumDb = 0.0; minimumDb = displayFloor()
        displayRevision++
    }

    /** Only accepted real FFT frames advance smoothing and the optional historical peak marker. */
    private fun updateDisplay(result: Spectrum) {
        val now = SystemClock.elapsedRealtime()
        val reset = displayDomain != result.frame.domain || displayInput?.levels?.size != result.input.size ||
            displayOutput?.levels?.size != result.output.size || now - displayAt > 1200L
        val elapsed = if (reset) 0L else (now - displayAt).coerceAtLeast(1L)
        if (reset) {
            displayInput = DisplayPlane(result.input.size); displayOutput = DisplayPlane(result.output.size)
        }
        val response = options.responseMs.coerceIn(0, 500).toDouble()
        val attack = if (response <= 0.0 || reset) 1.0 else 1.0 - exp(-elapsed / response)
        val release = if (response <= 0.0 || reset) 1.0 else 1.0 - exp(-elapsed / (response * 1.7))
        fun update(values: DoubleArray, plane: DisplayPlane) {
            values.forEachIndexed { index, measured ->
                val amplitude = if (measured.isFinite()) 10.0.pow(measured / 20.0) else 0.0
                plane.amplitude[index] = if (reset) amplitude else plane.amplitude[index] +
                    (amplitude - plane.amplitude[index]) * if (amplitude >= plane.amplitude[index]) attack else release
                plane.levels[index] = if (plane.amplitude[index] > 0.0) 20.0 * log10(plane.amplitude[index]) else Double.NEGATIVE_INFINITY
                if (reset || measured >= plane.peak[index]) {
                    plane.peak[index] = measured; plane.until[index] = now + 500L
                } else if (now > plane.until[index]) {
                    plane.peak[index] = max(measured, plane.peak[index] - min(elapsed, now - plane.until[index]) * .018)
                }
            }
        }
        update(result.input, displayInput!!); update(result.output, displayOutput!!)
        displayDomain = result.frame.domain; displayAt = now
        followAxis(result, reset)
        displayRevision++
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) source?.let(::submit)
    }
    override fun onDetachedFromWindow() {
        stopWorker(); removeCallbacks(redraw); redrawPosted = false
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!initialized) return
        if (active && isAttachedToWindow && isShown) source?.let(::submit) else stopWorker()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!initialized) return
        if (visibility == VISIBLE && active && isAttachedToWindow && isShown) source?.let(::submit) else stopWorker()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!initialized) return
        if (!hasWindowFocus) {
            stopWorker(); removeCallbacks(redraw); redrawPosted = false
        } else if (active && isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            source?.takeIf { it.revision > shownRevision }?.let(::submit)
            requestRedraw()
        }
    }

    private fun baseline(top: Float): Float = top - text.fontMetrics.top
    private fun label(canvas: Canvas, value: String, x: Float, y: Float, color: Int = muted,
        align: Paint.Align = Paint.Align.LEFT) {
        text.color = color; text.textAlign = align; canvas.drawText(value, x, y, text)
    }
    private fun db(value: Double): String = if (value == Double.NEGATIVE_INFINITY) "−∞" else
        if (!value.isFinite()) "—" else String.format(Locale.ROOT, "%.1f", value)
    private fun x(frequency: Double, maximum: Double): Float = bounds.left +
        ((if (options.linearFrequency) (frequency - 20.0) / (maximum - 20.0)
            else ln(frequency / 20.0) / ln(maximum / 20.0)) * bounds.width()).toFloat()
    private fun y(db: Double): Float = bounds.bottom - ((db.coerceIn(minimumDb, maximumDb) - minimumDb) /
        (maximumDb - minimumDb) * bounds.height()).toFloat()
    /** Sensitivity exposes weaker real bins; scale zooms the calibrated display, never the PCM. */
    private fun displayFloor(): Double {
        val sensitivity = options.sensitivity.takeIf { it.isFinite() }?.coerceIn(.05f, 1f) ?: .25f
        val scale = options.scale.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f
        val span = ((60.0 + sensitivity * 24.0) / sqrt(scale.toDouble())).coerceIn(36.0, 96.0)
        return maximumDb - span
    }
    /** One shared absolute dBFS axis: stable input reference, output expands it only if clipping. */
    private fun followAxis(result: Spectrum, reset: Boolean) {
        val maximumFrequency = if (options.followSampleRate) result.frame.domain.rate / 2.0
            else min(20000.0, result.frame.domain.rate / 2.0)
        var inputMaximum = Double.NEGATIVE_INFINITY
        var outputMaximum = Double.NEGATIVE_INFINITY
        for (bin in 1 until min(result.input.size, result.output.size)) {
            val frequency = bin.toDouble() * result.frame.domain.rate / result.frames
            if (frequency < 20.0 || frequency > maximumFrequency) continue
            inputMaximum = max(inputMaximum, displayLevel(result.input[bin], frequency))
            outputMaximum = max(outputMaximum, displayLevel(result.output[bin], frequency))
        }
        val reference = if (inputMaximum.isFinite()) inputMaximum else outputMaximum
        if (!reference.isFinite() || reference < -120.0) {
            axisFallStarted = 0L; minimumDb = displayFloor(); return
        }
        fun step(value: Double) = ceil((value - 1e-8) / 6.0) * 6.0
        val inputTarget = step(reference + 12.0)
        val outputLimit = if (outputMaximum.isFinite()) step(outputMaximum + 3.0) else inputTarget
        val now = SystemClock.elapsedRealtime()
        when {
            !axisReady || reset -> {
                maximumDb = max(inputTarget, if (outputMaximum > inputTarget) outputLimit else inputTarget)
                axisReady = true; axisFallStarted = 0L
            }
            outputMaximum > maximumDb || inputTarget > maximumDb -> {
                maximumDb = max(maximumDb, max(inputTarget, if (outputMaximum > maximumDb) outputLimit else maximumDb))
                axisFallStarted = 0L
            }
            max(inputTarget, outputLimit) <= maximumDb - 6.0 -> {
                if (axisFallStarted == 0L) axisFallStarted = now
                if (now - axisFallStarted >= 1100L && now - axisLastStep >= 350L) {
                    maximumDb -= 6.0; axisLastStep = now
                }
            }
            else -> axisFallStarted = 0L
        }
        minimumDb = displayFloor()
    }
    private fun displayLevel(value: Double, frequency: Double): Double = if (options.flatten && value.isFinite())
        value + 3.0 * ln(frequency / 1000.0) / ln(2.0) else value

    private fun styleName(): String = when (chartStyle) {
        1 -> "渐变面积"
        2 -> "双线对照"
        3 -> "双色柱谱"
        4 -> "霓虹轮廓"
        else -> "彩色柱谱"
    }

    private data class TraceSpan(val first: Float, val last: Float)

    /** A screen column keeps its strongest real FFT bin, including the original absolute scale. */
    private fun trace(values: DoubleArray, result: Spectrum, maximum: Double, target: Path): TraceSpan? {
        target.rewind()
        var previousColumn = -1
        var columnMaximum = Double.NEGATIVE_INFINITY
        var columnX = 0f
        var firstX = Float.NaN
        var lastX = Float.NaN
        fun flush() {
            if (previousColumn < 0) return
            if (firstX.isNaN()) { target.moveTo(columnX, y(columnMaximum)); firstX = columnX }
            else target.lineTo(columnX, y(columnMaximum))
            lastX = columnX
        }
        values.forEachIndexed { bin, amplitude ->
            val frequency = bin.toDouble() * result.frame.domain.rate / result.frames
            if (frequency < 20.0 || frequency > maximum || amplitude.isNaN()) return@forEachIndexed
            val px = x(frequency, maximum)
            val column = floor(px).toInt()
            val displayed = displayLevel(amplitude, frequency)
            if (column != previousColumn) {
                flush(); previousColumn = column; columnMaximum = displayed; columnX = px
            } else columnMaximum = max(columnMaximum, displayed)
        }
        flush()
        return if (firstX.isNaN()) null else TraceSpan(firstX, lastX)
    }

    private data class BarGeometry(val rate: Int, val frames: Int, val bins: Int,
        val maximum: Double, val linear: Boolean, val left: Float, val top: Float,
        val right: Float, val bottom: Float)
    private class Bars(val geometry: BarGeometry, val binGroup: IntArray, val frequencies: DoubleArray,
        val left: FloatArray, val right: FloatArray) {
        val input = DoubleArray(left.size)
        val output = DoubleArray(left.size)
        val peak = DoubleArray(left.size)
        var revision = -1L
    }
    private var barCache: Bars? = null
    private var ledBarCache: Bars? = null
    private var ledForegroundRevision = -1L
    private var groupedBarCache: Bars? = null
    private var groupedForegroundRevision = -1L

    /** Only bins occupying the same physical pixel are merged; no dp-sized frequency buckets. */
    private fun bars(result: Spectrum, maximum: Double): Bars {
        val binCount = min(result.input.size, result.output.size)
        val old = barCache
        val geometry = old?.geometry
        val values = if (old != null && geometry != null && geometry.rate == result.frame.domain.rate &&
            geometry.frames == result.frames && geometry.bins == binCount && geometry.maximum == maximum &&
            geometry.linear == options.linearFrequency && geometry.left == bounds.left && geometry.top == bounds.top &&
            geometry.right == bounds.right && geometry.bottom == bounds.bottom) old else {
            val mapping = IntArray(binCount) { -1 }
            val frequencies = DoubleArray(binCount) { it.toDouble() * result.frame.domain.rate / result.frames }
            val centers = FloatArray(binCount)
            val firstPixel = floor(bounds.left).toInt()
            val lastPixel = max(firstPixel, ceil(bounds.right).toInt() - 1)
            var previousPixel = Int.MIN_VALUE
            var count = 0
            for (bin in 1 until binCount) {
                val frequency = frequencies[bin]
                if (frequency < 20.0 || frequency > maximum) continue
                // The Nyquist endpoint belongs to the final visible pixel, rather than an
                // invisible column immediately outside the clipping rectangle.
                val pixel = floor(x(frequency, maximum)).toInt().coerceIn(firstPixel, lastPixel)
                if (pixel != previousPixel) {
                    centers[count++] = (pixel + .5f).coerceIn(bounds.left, bounds.right)
                    previousPixel = pixel
                }
                mapping[bin] = count - 1
            }
            val left = FloatArray(count)
            val right = FloatArray(count)
            for (index in 0 until count) {
                // At most two physical pixels per real group. Wider low-frequency gaps
                // remain gaps; a sparse logarithmic FFT is not filled with invented bins.
                val before = if (index > 0) centers[index] - centers[index - 1] else 2f
                val after = if (index + 1 < count) centers[index + 1] - centers[index] else 2f
                val half = min(2f, min(before, after)).coerceAtLeast(.5f) * .5f
                left[index] = max(bounds.left, centers[index] - half)
                right[index] = min(bounds.right, centers[index] + half)
            }
            Bars(BarGeometry(result.frame.domain.rate, result.frames, binCount, maximum, options.linearFrequency,
                bounds.left, bounds.top, bounds.right, bounds.bottom), mapping, frequencies, left, right)
                .also { barCache = it }
        }
        if (values.revision == displayRevision) return values
        values.input.fill(Double.NEGATIVE_INFINITY)
        values.output.fill(Double.NEGATIVE_INFINITY)
        values.peak.fill(Double.NEGATIVE_INFINITY)
        val sourceInput = displayInput?.levels ?: result.input
        val sourceOutput = displayOutput?.levels ?: result.output
        val sourcePeak = displayOutput?.peak ?: result.output
        for (bin in 1 until binCount) {
            val group = values.binGroup[bin]
            if (group < 0) continue
            val frequency = values.frequencies[bin]
            values.input[group] = max(values.input[group], displayLevel(sourceInput[bin], frequency))
            values.output[group] = max(values.output[group], displayLevel(sourceOutput[bin], frequency))
            values.peak[group] = max(values.peak[group], displayLevel(sourcePeak[bin], frequency))
        }
        values.revision = displayRevision
        return values
    }

    private fun drawTrace(canvas: Canvas, value: Path, color: Int, thickness: Float, transitioning: Boolean) {
        stroke.shader = null; stroke.color = color; stroke.strokeWidth = thickness
        stroke.pathEffect = if (transitioning) pendingDash else null
        canvas.drawPath(value, stroke)
        stroke.pathEffect = null
    }

    private fun prepareTraces(result: Spectrum, maximum: Double): TraceSpan? {
        val key = "$displayRevision/$chartStyle/${options.peakHold}/$maximum/$minimumDb/$maximumDb/${bounds.left}/${bounds.top}/${bounds.right}/${bounds.bottom}/${options.linearFrequency}/${options.flatten}"
        if (traceKey == key) return cachedOutputSpan
        traceKey = key
        cachedOutputSpan = null
        inputPath.rewind(); outputPath.rewind(); heldOutputPath.rewind()
        if (chartStyle == 3) return null
        trace(displayInput?.levels ?: result.input, result, maximum, inputPath)
        if (chartStyle != 0) {
            cachedOutputSpan = trace(displayOutput?.levels ?: result.output, result, maximum, outputPath)
            if (options.peakHold) trace(displayOutput?.peak ?: result.output, result, maximum, heldOutputPath)
        }
        return cachedOutputSpan
    }

    /** Emphasise the actual, same-window spectrum difference without moving either measured height. */
    private fun drawDifferences(canvas: Canvas, result: Spectrum, maximum: Double, dark: Boolean) {
        val values = bars(result, maximum)
        val key = "$displayRevision/$chartStyle/$minimumDb/$maximumDb/${bounds.left}/${bounds.top}/${bounds.right}/${bounds.bottom}/$maximum/${options.linearFrequency}/${options.flatten}"
        if (differenceKey != key) {
            differenceKey = key
            gainedPath.rewind(); reducedPath.rewind(); gainedCaps.rewind(); reducedCaps.rewind()
            for (index in values.left.indices) {
                val before = values.input[index]
                val after = values.output[index]
                if (!before.isFinite() || !after.isFinite() || kotlin.math.abs(after - before) < .6) continue
                if (max(before, after) <= minimumDb || min(before, after) >= maximumDb) continue
                val right = values.right[index]
                val left = if (chartStyle == 3) (values.left[index] + right) * .5f else values.left[index]
                val inputY = y(before)
                val outputY = y(after)
                if (after > before) {
                    gainedPath.addRect(left, outputY, right, inputY, Path.Direction.CW)
                    gainedCaps.moveTo(left, outputY); gainedCaps.lineTo(right, outputY)
                } else {
                    // An outline, not a filled output column, marks removed amplitude above it.
                    reducedPath.addRect(left, inputY, right, outputY, Path.Direction.CW)
                    reducedCaps.moveTo(left, outputY); reducedCaps.lineTo(right, outputY)
                }
            }
        }
        val gain = if (dark) 0xff8affd5.toInt() else 0xff087f59.toInt()
        val cut = if (dark) 0xffffbd80.toInt() else 0xffa85a15.toInt()
        fill.shader = null; fill.color = ColorUtils.setAlphaComponent(gain, if (dark) 170 else 100)
        canvas.drawPath(gainedPath, fill)
        drawTrace(canvas, gainedCaps, ColorUtils.setAlphaComponent(gain, 65), 3.4f * d, false)
        drawTrace(canvas, gainedCaps, gain, 1.25f * d, false)
        drawTrace(canvas, reducedPath, ColorUtils.setAlphaComponent(cut, 135), .6f * d, true)
        drawTrace(canvas, reducedCaps, cut, 1.25f * d, false)
    }

    private fun drawLedBars(canvas: Canvas, result: Spectrum, maximum: Double, heldColor: Int) {
        val values = bars(result, maximum)
        val cellHeight = 1.8f * d
        val cellGap = .85f * d
        if (ledBarCache !== values) {
            ledBackgroundPath.rewind(); ledGapsPath.rewind()
            for (index in values.left.indices) ledBackgroundPath.addRect(values.left[index], bounds.top,
                values.right[index], bounds.bottom, Path.Direction.CW)
            var bottom = bounds.bottom
            while (bottom > bounds.top) {
                val top = max(bounds.top, bottom - cellHeight)
                val gapTop = max(bounds.top, top - cellGap)
                if (gapTop < top) ledGapsPath.addRect(bounds.left, gapTop, bounds.right, top, Path.Direction.CW)
                bottom -= cellHeight + cellGap
            }
            ledBarCache = values
            ledForegroundRevision = -1L
        }
        if (ledForegroundRevision != displayRevision) {
            ledOutputPath.rewind(); ledCapsPath.rewind()
            for (index in values.left.indices) {
                val left = values.left[index]
                val right = values.right[index]
                val peakY = y(values.output[index])
                if (values.output[index].isFinite() && values.output[index] > minimumDb)
                    ledOutputPath.addRect(left, peakY, right, bounds.bottom, Path.Direction.CW)
                val cap = if (options.peakHold) values.peak[index] else values.output[index]
                if (cap.isFinite() && cap > minimumDb) {
                    ledCapsPath.moveTo(left, y(cap)); ledCapsPath.lineTo(right, y(cap))
                }
            }
            ledForegroundRevision = displayRevision
        }
        fill.color = 0xff1e2a3b.toInt()
        canvas.drawPath(ledBackgroundPath, fill)
        // Hue encodes frequency only. Cached gaps retain the LED style without constructing
        // thousands of individual lit cells for every new 512-bin frame.
        if (ledGradientLeft != bounds.left || ledGradientRight != bounds.right) {
            ledGradientLeft = bounds.left; ledGradientRight = bounds.right
            ledGradient = LinearGradient(bounds.left, 0f, bounds.right, 0f, ledColors, null, Shader.TileMode.CLAMP)
        }
        fill.color = Color.WHITE; fill.shader = ledGradient
        canvas.drawPath(ledOutputPath, fill)
        fill.shader = null
        fill.color = 0xff0e1522.toInt()
        canvas.drawPath(ledGapsPath, fill)
        stroke.color = ColorUtils.setAlphaComponent(if (options.peakHold) heldColor else 0xffecf4ff.toInt(), 160)
        stroke.strokeWidth = .6f * d; stroke.pathEffect = null
        canvas.drawPath(ledCapsPath, stroke)
    }

    private fun drawGroupedBars(canvas: Canvas, result: Spectrum, maximum: Double, input: Int, output: Int, heldColor: Int) {
        val values = bars(result, maximum)
        if (groupedBarCache !== values || groupedForegroundRevision != displayRevision) {
            groupedInputPath.rewind(); groupedOutputPath.rewind()
            groupedInputTops.rewind(); groupedOutputTops.rewind(); groupedPeakPath.rewind()
            for (index in values.left.indices) {
                val left = values.left[index]
                val available = values.right[index] - left
                val gap = if (available >= 2f) .125f else 0f
                val width = (available - gap) * .5f
                fun bar(position: Float, amplitude: Double, target: Path, tops: Path) {
                    if (amplitude <= minimumDb || amplitude.isNaN()) return
                    val top = y(amplitude)
                    target.addRect(position, top, position + width, bounds.bottom, Path.Direction.CW)
                    tops.moveTo(position, top); tops.lineTo(position + width, top)
                }
                bar(left, values.input[index], groupedInputPath, groupedInputTops)
                bar(left + width + gap, values.output[index], groupedOutputPath, groupedOutputTops)
                if (options.peakHold && values.peak[index].isFinite() && values.peak[index] > minimumDb) {
                    groupedPeakPath.moveTo(left + width + gap, y(values.peak[index]))
                    groupedPeakPath.lineTo(values.right[index], y(values.peak[index]))
                }
            }
            groupedBarCache = values
            groupedForegroundRevision = displayRevision
        }
        fill.color = ColorUtils.setAlphaComponent(input, 95); canvas.drawPath(groupedInputPath, fill)
        fill.color = ColorUtils.setAlphaComponent(output, 195); canvas.drawPath(groupedOutputPath, fill)
        drawTrace(canvas, groupedInputTops, ColorUtils.setAlphaComponent(input, 110), .6f * d, false)
        drawTrace(canvas, groupedOutputTops, output, .75f * d, false)
        if (options.peakHold) drawTrace(canvas, groupedPeakPath, heldColor, .7f * d, false)
    }

    private fun frequencyLabel(frequency: Double, unit: Boolean): String {
        if (frequency < 1000.0) return "${frequency.toInt()}${if (unit) "Hz" else ""}"
        val value = String.format(Locale.ROOT, "%.4g", frequency / 1000.0).let {
            if ('.' in it && 'e' !in it && 'E' !in it) it.trimEnd('0').trimEnd('.') else it
        }
        return "$value${if (unit) "kHz" else "k"}"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        lastDrawAt = SystemClock.elapsedRealtime()
        val dark = chartStyle == 0 || chartStyle == 4
        val axis = if (dark) 0xffa8b8ce.toInt() else muted
        val foreground = if (dark) 0xffe8eef9.toInt() else ink
        val input = if (dark) 0xff78d9e9.toInt() else inputColor
        val output = when {
            dark -> 0xffd1aeff.toInt()
            chartStyle == 3 -> if (ColorUtils.calculateLuminance(surface) < .5) 0xffc9a7ff.toInt() else 0xff7553b5.toInt()
            else -> primary
        }
        val heldColor = if (dark) 0xffffcc85.toInt() else 0xffa96312.toInt()
        fill.shader = null
        fill.color = if (dark) 0xff0e1522.toInt() else ColorUtils.blendARGB(surface, primary, .025f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 7f * d, 7f * d, fill)
        val line = text.fontSpacing
        val caption = baseline(3f * d)
        stroke.pathEffect = null; stroke.strokeWidth = 1.4f * d
        stroke.color = input
        canvas.drawLine(6f * d, caption + text.ascent() * .4f, 16f * d, caption + text.ascent() * .4f, stroke)
        label(canvas, "输入", 20f * d, caption, input)
        if (chartStyle == 0) {
            for (index in 0..3) {
                fill.color = Color.HSVToColor(floatArrayOf(235f * (1f - index / 3f), .78f, 1f))
                canvas.drawRect((56f + index * 2.5f) * d, caption + text.ascent() * .7f,
                    (58f + index * 2.5f) * d, caption + text.ascent() * .15f, fill)
            }
        } else {
            stroke.color = output
            canvas.drawLine(56f * d, caption + text.ascent() * .4f, 66f * d, caption + text.ascent() * .4f, stroke)
        }
        label(canvas, "PEQ后", 70f * d, caption, output)
        if (options.peakHold && width >= 290f * d) {
            stroke.color = heldColor; stroke.strokeWidth = .8f * d; stroke.pathEffect = pendingDash
            canvas.drawLine(110f * d, caption + text.ascent() * .4f, 120f * d, caption + text.ascent() * .4f, stroke)
            stroke.pathEffect = null
            label(canvas, "出峰", 124f * d, caption, heldColor)
        }
        label(canvas, if (options.flatten) "补偿显示 · dB" else "峰幅谱 · dBFS",
            width - 7f * d, caption, axis, Paint.Align.RIGHT)
        bounds.set(31f * d, line + 10f * d, width - 9f * d,
            height - line * 3f - 20f * d)
        if (bounds.width() <= 0 || bounds.height() <= line) return
        val result = spectrum
        val rate = result?.frame?.domain?.rate ?: source?.domain?.rate ?: 48000
        val maximum = if (options.followSampleRate) rate / 2.0 else min(20000.0, rate / 2.0)
        minimumDb = displayFloor()
        val nextAxesKey = "$width/$height/$minimumDb/$maximumDb/$maximum/${options.linearFrequency}/${options.flatten}/$dark/$axis/${bounds.left}/${bounds.top}/${bounds.right}/${bounds.bottom}"
        if (axesKey != nextAxesKey) {
            axesKey = nextAxesKey
            val axesCanvas = axesPicture.beginRecording(width, height)
            val range = maximumDb - minimumDb
            val gridStep = when { range / 4 <= 5 -> 5.0; range / 4 <= 10 -> 10.0; range / 4 <= 20 -> 20.0;
                range / 4 <= 30 -> 30.0; range / 4 <= 40 -> 40.0; else -> 60.0 }
            grid.color = ColorUtils.setAlphaComponent(if (dark) 0xff9db5d5.toInt() else ink, if (dark) 19 else 13)
            grid.strokeWidth = .5f * d
            var minor = ceil(minimumDb / (gridStep / 2)) * gridStep / 2
            while (minor <= maximumDb) {
                axesCanvas.drawLine(bounds.left, y(minor), bounds.right, y(minor), grid); minor += gridStep / 2
            }
            val frequencyGrid = if (options.linearFrequency) DoubleArray(9) { 20.0 + (maximum - 20.0) * it / 8.0 }
                else doubleArrayOf(20.0, 30.0, 50.0, 100.0, 200.0, 300.0, 500.0, 1000.0, 2000.0,
                    3000.0, 5000.0, 10000.0, 20000.0, 30000.0, 50000.0, 100000.0, 200000.0, 300000.0)
            frequencyGrid
                .filter { it <= maximum }.forEach { frequency ->
                    val px = x(frequency, maximum)
                    axesCanvas.drawLine(px, bounds.top, px, bounds.bottom, grid)
                }
            grid.color = ColorUtils.setAlphaComponent(if (dark) 0xffa6bddc.toInt() else ink, if (dark) 55 else 35)
            grid.strokeWidth = .65f * d
            val levels = mutableListOf(minimumDb, maximumDb)
            var major = ceil(minimumDb / gridStep) * gridStep
            while (major < maximumDb) {
                if (y(major) - bounds.top > line * 1.1f && bounds.bottom - y(major) > line * 1.1f) levels += major
                major += gridStep
            }
            levels.forEach { value ->
                val py = y(value)
                axesCanvas.drawLine(bounds.left, py, bounds.right, py, grid)
                label(axesCanvas, if (value == floor(value)) value.toInt().toString() else db(value), bounds.left - 4f * d,
                    py - (text.ascent() + text.descent()) / 2f, axis, Paint.Align.RIGHT)
            }
            axesCanvas.drawRect(bounds, grid.apply { style = Paint.Style.STROKE })
            val maximumLabel = frequencyLabel(maximum, true)
            val maximumLeft = bounds.right - text.measureText(maximumLabel)
            var previousRight = -Float.MAX_VALUE
            val frequencyLabels = if (options.linearFrequency) doubleArrayOf(20.0, maximum * .25, maximum * .5, maximum * .75)
                else doubleArrayOf(20.0, 100.0, 1000.0, 10000.0)
            frequencyLabels.filter { it >= 20.0 && it < maximum }.forEach { frequency ->
                val px = x(frequency, maximum)
                axesCanvas.drawLine(px, bounds.top, px, bounds.bottom, grid)
                val value = frequencyLabel(frequency, frequency == 20.0)
                val w = text.measureText(value)
                val left = (px - w / 2f).coerceIn(bounds.left, max(bounds.left, bounds.right - w))
                if (left >= previousRight + 5f * d && left + w + 6f * d <= maximumLeft) {
                    label(axesCanvas, value, left, bounds.bottom + line + d, axis)
                    previousRight = left + w
                }
            }
            label(axesCanvas, maximumLabel, bounds.right, bounds.bottom + line + d, axis, Paint.Align.RIGHT)
            axesPicture.endRecording()
        }
        canvas.drawPicture(axesPicture)
        if (result == null) {
            label(canvas, "等待真实音乐 PCM", bounds.centerX(), bounds.centerY() + line,
                axis, Paint.Align.CENTER)
        } else {
            canvas.save(); canvas.clipRect(bounds)
            val outputSpan = prepareTraces(result, maximum)
            when (chartStyle) {
                0 -> {
                    drawLedBars(canvas, result, maximum, heldColor)
                    drawTrace(canvas, inputPath, ColorUtils.setAlphaComponent(input, 100), .65f * d, result.frame.transitioning)
                }
                1 -> {
                    if (outputSpan != null) {
                        areaPath.set(outputPath)
                        areaPath.lineTo(outputSpan.last, bounds.bottom)
                        areaPath.lineTo(outputSpan.first, bounds.bottom)
                        areaPath.close()
                        if (gradientTop != bounds.top || gradientBottom != bounds.bottom || gradientColor != output) {
                            gradientTop = bounds.top; gradientBottom = bounds.bottom; gradientColor = output
                            areaGradient = LinearGradient(0f, bounds.top, 0f, bounds.bottom,
                                ColorUtils.setAlphaComponent(output, 145), ColorUtils.setAlphaComponent(output, 8), Shader.TileMode.CLAMP)
                        }
                        fill.shader = areaGradient
                        canvas.drawPath(areaPath, fill)
                        fill.shader = null
                    }
                    drawTrace(canvas, inputPath, ColorUtils.setAlphaComponent(input, 100), .65f * d, result.frame.transitioning)
                    if (options.peakHold) drawTrace(canvas, heldOutputPath, ColorUtils.setAlphaComponent(heldColor, 175), .85f * d, true)
                    drawTrace(canvas, outputPath, output, 1.55f * d, result.frame.transitioning)
                }
                3 -> drawGroupedBars(canvas, result, maximum, input, output, heldColor)
                4 -> {
                    drawTrace(canvas, inputPath, ColorUtils.setAlphaComponent(input, 100), .65f * d, result.frame.transitioning)
                    if (options.peakHold) drawTrace(canvas, heldOutputPath, ColorUtils.setAlphaComponent(heldColor, 170), .85f * d, true)
                    drawTrace(canvas, outputPath, ColorUtils.setAlphaComponent(output, 20), 5f * d, result.frame.transitioning)
                    drawTrace(canvas, outputPath, ColorUtils.setAlphaComponent(output, 55), 2.8f * d, result.frame.transitioning)
                    drawTrace(canvas, outputPath, output, 1.25f * d, result.frame.transitioning)
                }
                else -> {
                    drawTrace(canvas, inputPath, ColorUtils.setAlphaComponent(input, 100), .65f * d, result.frame.transitioning)
                    if (options.peakHold) drawTrace(canvas, heldOutputPath, heldColor, .9f * d, true)
                    drawTrace(canvas, outputPath, output, 1.6f * d, result.frame.transitioning)
                }
            }
            drawDifferences(canvas, result, maximum, dark)
            stroke.pathEffect = null
            canvas.restore()
        }
        val resolution = result?.let { "${it.frames}点 · Δf ${db(rate.toDouble() / it.frames)}Hz" }
            ?: "真实 PCM 待就绪"
        label(canvas, resolution, 6f * d, baseline(height - line * 2f - 7f * d), axis)
        label(canvas, "亮段：实测增减", width - 7f * d, baseline(height - line * 2f - 7f * d), axis, Paint.Align.RIGHT)
        val meterTop = height - line - 4f * d
        fun meter(start: Float, end: Float, name: String, value: Double?, color: Int) {
            val captionValue = "$name ${value?.let(::db) ?: "—"} dBFS"
            label(canvas, captionValue, start, baseline(meterTop), color)
            val left = start + max(61f * d, text.measureText(captionValue) + 6f * d)
            if (end - left < 12f * d) return
            val middle = meterTop + line / 2f
            fill.color = ColorUtils.setAlphaComponent(foreground, if (dark) 28 else 22)
            canvas.drawRoundRect(left, middle - 2f * d, end, middle + 2f * d, 2f * d, 2f * d, fill)
            if (value != null && value.isFinite()) {
                fill.color = ColorUtils.setAlphaComponent(color, 190)
                val ratio = ((value.coerceIn(-120.0, 12.0) + 120.0) / 132.0).toFloat()
                canvas.drawRoundRect(left, middle - 2f * d, left + (end - left) * ratio,
                    middle + 2f * d, 2f * d, 2f * d, fill)
            }
        }
        meter(6f * d, width * .5f - 8f * d, "入RMS", result?.inputRms, input)
        meter(width * .5f + 2f * d, width - 9f * d, "出RMS", result?.outputRms, output)
    }

    private fun details(): String {
        val result = spectrum
        return buildString {
            append("真实 PEQ 前后只读 PCM；输入输出共用绝对刻度，不使用参数模型代替音乐。\n$status\n")
            append("显示：${styleName()}；所有样式使用同一真实窗口和同一标尺；绿色亮段/峰帽为实际输出高于输入的部分，橙色空心段为实际输出减少的部分，不改变柱高；输入/输出测量算法不变。彩色柱谱的蓝→红仅代表低→高频，不代表增益；双色柱谱每组青色为输入、紫色为PEQ后。柱图按真实FFT频格定位，仅落在同一物理像素的频格才取最大值合并；空白频段不补柱。\n")
            append("${if (options.linearFrequency) "线性Hz" else "对数Hz"}；${if (options.followSampleRate) "上限为实际分析采样率的Nyquist" else "上限20kHz且不超过实际Nyquist"}；显示响应${options.responseMs}ms（0ms不平滑），灵敏度${options.sensitivity}，规模${options.scale}。灵敏度提高可见弱信号范围，规模只缩放纵轴；可见跨度限制36…96dB，两项都不改变原始采样或读数。共同纵轴以真实输入峰预留12…18dB；输出仅越顶时快扩，下降按6dB档慢收；静音及低于−120dBFS不用于重定位噪底。\n")
            append(if (options.flatten) "平面显示：以1kHz为参考，每倍频程+3dB，仅补偿画图；纵轴为补偿后显示dB，以下RMS/峰值仍为原始dBFS。\n" else "平面显示关闭：原始dBFS标尺，允许只作显示包络平滑。\n")
            append(if (options.peakHold) "橙色出峰/短帽为真实输出逐频格历史峰值，约500ms保持后18dB/s回落；仅新的真实采样帧才推进，不是额外音频支路。\n" else "输出历史峰值保持关闭。\n")
            if (result == null) { append("尚无有效窗口。网格保留，不绘制伪造曲线或零电平。") }
            else {
                append("原处理率 ${result.frame.domain.rate} Hz；${result.frames} 点 Hann；频格间隔 ${result.frame.domain.rate.toDouble() / result.frames} Hz。\n")
                append("同一窗口四个原始 PCM 平面分别 FFT，双声道按平方均值合并幅度，不能先相加抵消反相音乐。单边峰幅按 Hann 相干增益校正，无任意归一化。\n")
                append("输入 RMS ${db(result.inputRms)}，PEQ后 RMS ${db(result.outputRms)} dBFS；输入峰值 ${db(result.inputPeak)}，PEQ后峰值 ${db(result.outputPeak)} dBFS。\n")
                append("显示纵轴 ${db(displayFloor())}…${db(maximumDb)}${if (options.flatten) "补偿dB" else "dBFS"}，超范围仅裁显示，未修改采样。音乐频谱受当前素材、窗长、泄漏与分辨率影响，不能用单一频格的输出/输入比冒充精确滤波器传递增益。25Hz或高Q窄峰可能小于实际频格间隔，空桶不补点、不制造窄音乐峰。\n")
                append("窗口 ${result.frame.first}…${result.frame.last}；序列 ${result.frame.sequence}；配置 ${result.frame.epoch}；${if (result.frame.moduleEnabled) "PEQ启用" else "PEQ旁路"}；${if (result.frame.transitioning) "包含原生参数过渡" else "完整采样窗口"}。")
            }
        }
    }
    private fun showDetails() {
        GlassAlertDialogBuilder(context).setTitle("真实输入与 PEQ 后频谱").setMessage(details())
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private class Worker(private val complete: (Spectrum) -> Unit) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "peq-readonly-fft").apply { isDaemon = true } }
        private val pending = AtomicReference<Frame?>()
        private val running = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        fun submit(frame: Frame) { if (!closed.get()) { pending.set(frame); schedule() } }
        private fun schedule() {
            if (closed.get() || !running.compareAndSet(false, true)) return
            try {
                executor.execute {
                    try {
                        while (!closed.get() && !Thread.currentThread().isInterrupted) {
                            val frame = pending.getAndSet(null) ?: break
                            val result = runCatching { calculate(frame) }.getOrNull()
                            if (result != null && !closed.get()) complete(result)
                        }
                    } finally {
                        running.set(false)
                        if (pending.get() != null && !closed.get()) schedule()
                    }
                }
            } catch (_: RejectedExecutionException) { running.set(false) }
        }
        private fun calculate(frame: Frame): Spectrum {
            val size = frame.inputLeft.size
            val n = Integer.highestOneBit(size).coerceAtMost(1024)
            val window = DoubleArray(n) { .5 - .5 * cos(2.0 * PI * it / n) }
            val windowSum = window.sum()
            fun fft(values: FloatArray): DoubleArray {
                val real = DoubleArray(n) { values[size - n + it] * window[it] }
                val imaginary = DoubleArray(n)
                var j = 0
                for (i in 1 until n) {
                    var bit = n shr 1
                    while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                    j = j xor bit
                    if (i < j) { val old = real[i]; real[i] = real[j]; real[j] = old }
                }
                var length = 2
                while (length <= n) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val wr = cos(-2.0 * PI / length); val wi = sin(-2.0 * PI / length)
                    for (start in 0 until n step length) {
                        var ur = 1.0; var ui = 0.0
                        for (k in 0 until length / 2) {
                            val a = start + k; val b = a + length / 2
                            val vr = real[b] * ur - imaginary[b] * ui
                            val vi = real[b] * ui + imaginary[b] * ur
                            real[b] = real[a] - vr; imaginary[b] = imaginary[a] - vi
                            real[a] += vr; imaginary[a] += vi
                            val next = ur * wr - ui * wi; ui = ur * wi + ui * wr; ur = next
                        }
                    }
                    length = length shl 1
                }
                return DoubleArray(n / 2 + 1) { bin -> hypot(real[bin], imaginary[bin]) *
                    (if (bin == 0 || bin == n / 2) 1.0 else 2.0) / windowSum }
            }
            fun toDb(value: Double): Double = if (value > 0) 20.0 * log10(value) else Double.NEGATIVE_INFINITY
            fun combine(left: FloatArray, right: FloatArray): DoubleArray {
                val a = fft(left); val b = fft(right)
                return DoubleArray(a.size) { toDb(sqrt((a[it] * a[it] + b[it] * b[it]) / 2.0)) }
            }
            fun levels(left: FloatArray, right: FloatArray): Pair<Double, Double> {
                var sum = 0.0; var peak = 0.0
                for (index in size - n until size) {
                    val a = left[index].toDouble(); val b = right[index].toDouble()
                    require(a.isFinite() && b.isFinite())
                    sum += a * a + b * b; peak = max(peak, max(kotlin.math.abs(a), kotlin.math.abs(b)))
                }
                return toDb(sqrt(sum / (2.0 * n))) to toDb(peak)
            }
            val inputLevel = levels(frame.inputLeft, frame.inputRight)
            val outputLevel = levels(frame.outputLeft, frame.outputRight)
            return Spectrum(frame, combine(frame.inputLeft, frame.inputRight), combine(frame.outputLeft, frame.outputRight),
                inputLevel.first, outputLevel.first, inputLevel.second, outputLevel.second, n)
        }
        override fun close() { closed.set(true); pending.set(null); executor.shutdownNow() }
    }
}
