package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import kotlin.math.*

/** Ordinary RBJ Q sections, matching the independent dynamic engine (not static PEQ's Q law). */
internal object DynamicPeqResponse {
    data class Section(val b0: Double, val b1: Double, val b2: Double,
                       val a1: Double, val a2: Double) {
        fun db(frequency: Double, rate: Double): Double {
            val w = 2.0 * PI * frequency / rate
            return dbAtTrig(cos(w), sin(w), cos(2 * w), sin(2 * w))
        }
        fun dbAtTrig(c: Double, s: Double, c2: Double, s2: Double): Double {
            val nr = b0 + b1 * c + b2 * c2; val ni = b1 * s + b2 * s2
            val dr = 1.0 + a1 * c + a2 * c2; val di = a1 * s + a2 * s2
            return 10.0 * log10(((nr * nr + ni * ni) / (dr * dr + di * di)).coerceAtLeast(1e-24))
        }
    }

    fun section(band: DynamicPeqBand, reduction: Double, rate: Double): Section? {
        if (!band.enabled || band.frequency >= .499 * rate || rate <= 0) return null
        val gain = band.gain - if (band.dynamicEnabled) reduction.coerceIn(0.0, band.range) else 0.0
        if (abs(gain) < .000001) return null
        val a = 10.0.pow(gain / 40.0)
        val w = 2 * PI * band.frequency / rate
        val c = cos(w); val alpha = sin(w) / (2 * band.q)
        val beta = 2 * sqrt(a) * alpha
        val b0: Double; val b1: Double; val b2: Double
        val a0: Double; val a1: Double; val a2: Double
        when (band.type) {
            1 -> {
                b0 = a * ((a + 1) - (a - 1) * c + beta)
                b1 = 2 * a * ((a - 1) - (a + 1) * c)
                b2 = a * ((a + 1) - (a - 1) * c - beta)
                a0 = (a + 1) + (a - 1) * c + beta
                a1 = -2 * ((a - 1) + (a + 1) * c)
                a2 = (a + 1) + (a - 1) * c - beta
            }
            2 -> {
                b0 = a * ((a + 1) + (a - 1) * c + beta)
                b1 = -2 * a * ((a - 1) + (a + 1) * c)
                b2 = a * ((a + 1) + (a - 1) * c - beta)
                a0 = (a + 1) - (a - 1) * c + beta
                a1 = 2 * ((a - 1) - (a + 1) * c)
                a2 = (a + 1) - (a - 1) * c - beta
            }
            else -> {
                b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a
            }
        }
        return Section(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    fun db(state: DynamicPeqState, frequency: Double, rate: Double = 48000.0,
           reductions: List<Double> = emptyList()): Double = state.bands.mapIndexedNotNull { index, band ->
        section(band, reductions.getOrElse(index) { 0.0 }, rate)
    }.sumOf { it.db(frequency, rate) }
}

/** The dashed line is the configured baseline; only real driver telemetry moves the solid line. */
class DynamicPeqResponseView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val primary = context.primaryColor()
    private val foreground = context.textColor()
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = density * 1.7f; color = primary
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density * .5f }
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * resources.displayMetrics.scaledDensity; color = context.mutedColor()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(primary, 22) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashed = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
    private val bounds = RectF()
    private val path = Path()
    private var state = DynamicPeqState()
    private var selected: String? = null
    private var sampleRate = 48000.0
    private var reductions = List(state.bands.size) { 0.0 }
    private var targetReductions = reductions
    private var requestedReductions = reductions
    private var animateNextReduction = false
    private var renderedState: DynamicPeqState? = null
    private var renderedRate = 0.0
    private var updateQueued = false
    private val updateFrame = Runnable {
        updateQueued = false
        updateCurves()
    }
    private var frequencies = DoubleArray(0)
    private var cosines = DoubleArray(0)
    private var sines = DoubleArray(0)
    private var doubleCosines = DoubleArray(0)
    private var doubleSines = DoubleArray(0)
    private var gridSignature: List<Pair<Double, Double>> = emptyList()
    private var gridRate = 0.0
    private data class CachedCurve(val section: DynamicPeqResponse.Section?, val values: DoubleArray)
    private val baselineCache = mutableMapOf<String, CachedCurve>()
    private val maximumCache = mutableMapOf<String, CachedCurve>()
    private val currentCache = mutableMapOf<String, CachedCurve>()
    private var xPositions = FloatArray(0)
    private var geometryDirty = true
    private var pathsDirty = true
    private val baselinePath = Path()
    private val currentPath = Path()
    private val rangePath = Path()
    private var bandPositions = FloatArray(0)
    private var bandValues = DoubleArray(0)
    private var baseline = DoubleArray(0)
    private var maximum = DoubleArray(0)
    private var current = DoubleArray(0)
    private var scale = 18.0
    private var motion: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    var onBandSelected: ((String) -> Unit)? = null

    init { isClickable = true; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun setState(next: DynamicPeqState, selectedId: String?, rate: Double = sampleRate) {
        val selectionChanged = selected != selectedId
        selected = selectedId
        val validRate = rate.takeIf { it >= 8000 } ?: 48000.0
        if (state != next || sampleRate != validRate || frequencies.isEmpty()) {
            state = next; sampleRate = validRate
            scheduleCurves()
        } else if (selectionChanged) {
            invalidate()
        }
        if (contentDescription == null)
            contentDescription = "动态均衡器频率响应；虚线为基础，实线为当前，阴影为最大压低范围"
    }

    fun setReductions(values: List<Double>, animate: Boolean = true) {
        val next = state.bands.mapIndexed { index, band ->
            if (state.enabled && band.enabled && band.dynamicEnabled) values.getOrElse(index) { 0.0 }
                .takeIf { it.isFinite() }?.coerceIn(0.0, band.range) ?: 0.0 else 0.0
        }
        if (next == requestedReductions) return
        requestedReductions = next
        animateNextReduction = animate
        scheduleCurves()
    }

    private fun scheduleCurves() {
        if (!updateQueued) { updateQueued = true; postOnAnimation(updateFrame) }
    }

    private fun updateCurves() {
        // Combine all MOVE events received before this frame. Only coefficients of changed
        // bands are evaluated; threshold/timing/ratio edits do not rebuild the baseline.
        val changedState = renderedState != state || renderedRate != sampleRate
        val gridChanged = rebuildGridIfNeeded()
        val nextBaseline = response(emptyList(), baselineCache)
        val nextMaximum = response(state.bands.map { if (it.dynamicEnabled) it.range else 0.0 }, maximumCache)
        if (!nextBaseline.contentEquals(baseline) || !nextMaximum.contentEquals(maximum) || gridChanged) {
            baseline = nextBaseline; maximum = nextMaximum
            var largest = 0.0
            for (index in baseline.indices) largest = max(largest, max(abs(baseline[index]), abs(maximum[index])))
            val nextScale = max(18.0, ceil(largest / 6.0) * 6.0)
            if (scale != nextScale) { scale = nextScale; geometryDirty = true }
            pathsDirty = true
        }
        val nextReductions = state.bands.mapIndexed { index, band ->
            if (state.enabled && band.enabled && band.dynamicEnabled)
                requestedReductions.getOrElse(index) { 0.0 }.coerceIn(0.0, band.range) else 0.0
        }
        if (changedState || gridChanged || nextReductions != targetReductions || current.isEmpty()) {
            motion?.cancel(); motion = null
            targetReductions = nextReductions
            val target = response(nextReductions, currentCache)
            val previous = current
            if (!changedState && !gridChanged && animateNextReduction && isAttachedToWindow &&
                ValueAnimator.areAnimatorsEnabled() && previous.size == target.size && !previous.contentEquals(target)) {
                // Interpolate between two actual driver snapshots. RBJ evaluation is performed
                // once per telemetry update, never in the animator or onDraw.
                motion = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 100
                    addUpdateListener { animation ->
                        val progress = (animation.animatedValue as Float).toDouble()
                        current = DoubleArray(target.size) { previous[it] + (target[it] - previous[it]) * progress }
                        pathsDirty = true; updateBandValues(); invalidate()
                    }
                    start()
                }
            } else {
                current = target; pathsDirty = true; updateBandValues()
            }
            reductions = nextReductions
        }
        renderedState = state; renderedRate = sampleRate; animateNextReduction = false
        invalidate()
    }

    private fun rebuildGridIfNeeded(): Boolean {
        val signature = state.bands.map { it.frequency to it.q }
        if (frequencies.isNotEmpty() && gridSignature == signature && gridRate == sampleRate) return false
        gridSignature = signature; gridRate = sampleRate
        val maxHz = min(20000.0, sampleRate * .499)
        frequencies = (List(384) { 20.0 * (maxHz / 20.0).pow(it / 383.0) } +
            state.bands.flatMap { band -> (-6..6).map {
                band.frequency * exp(it / (band.q * 10.0))
            } }.filter { it in 20.0..maxHz }).distinct().sorted().toDoubleArray()
        cosines = DoubleArray(frequencies.size) { cos(2 * PI * frequencies[it] / sampleRate) }
        sines = DoubleArray(frequencies.size) { sin(2 * PI * frequencies[it] / sampleRate) }
        doubleCosines = DoubleArray(frequencies.size) { 2 * cosines[it] * cosines[it] - 1 }
        doubleSines = DoubleArray(frequencies.size) { 2 * sines[it] * cosines[it] }
        baselineCache.clear(); maximumCache.clear(); currentCache.clear()
        geometryDirty = true
        return true
    }

    private fun response(values: List<Double>, cache: MutableMap<String, CachedCurve>): DoubleArray {
        val output = DoubleArray(frequencies.size)
        for ((index, band) in state.bands.withIndex()) {
            val section = DynamicPeqResponse.section(band, values.getOrElse(index) { 0.0 }, sampleRate)
            val old = cache[band.id]
            val contribution = if (old != null && old.section == section) old.values else {
                DoubleArray(frequencies.size) { section?.dbAtTrig(cosines[it], sines[it], doubleCosines[it], doubleSines[it]) ?: 0.0 }
                    .also { cache[band.id] = CachedCurve(section, it) }
            }
            for (point in output.indices) output[point] += contribution[point]
        }
        cache.keys.retainAll(state.bands.map { it.id }.toSet())
        return output
    }

    private fun updateBandValues() {
        bandValues = DoubleArray(state.bands.size) { index ->
            val point = frequencies.binarySearch(state.bands[index].frequency)
            if (point >= 0) current.getOrElse(point) { 0.0 } else 0.0
        }
    }
    private fun x(frequency: Double): Float = bounds.left +
        (ln(frequency / 20.0) / ln(min(20000.0, sampleRate * .499) / 20.0)).toFloat() * bounds.width()
    private fun y(db: Double): Float = bounds.centerY() - (db / scale).toFloat() * bounds.height() / 2f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frequencies.isEmpty()) { scheduleCurves(); return }
        if (geometryDirty) {
            bounds.set(max(31 * density, labels.measureText("+${dynamicNumber(scale, 0)}") + 8 * density),
                10 * density, width - max(13 * density, labels.measureText("20k") / 2 + 3 * density), height - 25 * density)
            xPositions = FloatArray(frequencies.size) { x(frequencies[it]) }
            bandPositions = FloatArray(state.bands.size) { x(state.bands[it].frequency) }
            geometryDirty = false; pathsDirty = true
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val ticks = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
        labels.textAlign = Paint.Align.CENTER
        for (frequency in ticks) {
            if (frequency > min(20000.0, sampleRate * .499) + .1) continue
            grid.color = ColorUtils.setAlphaComponent(foreground, 25)
            canvas.drawLine(x(frequency), bounds.top, x(frequency), bounds.bottom, grid)
            val label = if (frequency >= 1000) "${(frequency / 1000).toInt()}k" else frequency.toInt().toString()
            if (bounds.width() > density * 360 || frequency !in listOf(50.0, 200.0, 2000.0))
                canvas.drawText(label, x(frequency), height - 7 * density, labels)
        }
        labels.textAlign = Paint.Align.RIGHT
        for (fraction in -2..2) {
            val db = scale * fraction / 2.0
            grid.color = ColorUtils.setAlphaComponent(foreground, if (fraction == 0) 70 else 25)
            canvas.drawLine(bounds.left, y(db), bounds.right, y(db), grid)
            canvas.drawText((if (db > 0) "+" else "") + dynamicNumber(db, 0),
                bounds.left - 5 * density, y(db) + 3 * density, labels)
        }
        canvas.save(); canvas.clipRect(bounds)
        if (pathsDirty) {
            makePath(baselinePath, baseline); makePath(currentPath, current)
            rangePath.set(baselinePath)
            maximum.indices.reversed().forEach { rangePath.lineTo(xPositions[it], y(maximum[it])) }
            rangePath.close(); pathsDirty = false
        }
        fill.alpha = if (state.enabled) 24 else 12; canvas.drawPath(rangePath, fill)
        drawCurve(canvas, baselinePath, ColorUtils.setAlphaComponent(primary, 130), true)
        drawCurve(canvas, currentPath, if (state.enabled) primary else ColorUtils.setAlphaComponent(foreground, 120), false)
        state.bands.forEachIndexed { index, band ->
            if (band.frequency >= .499 * sampleRate || !band.enabled) return@forEachIndexed
            val value = bandValues.getOrElse(index) { 0.0 }
            val position = bandPositions.getOrElse(index) { x(band.frequency) }
            val chosen = band.id == selected
            dot.color = if (state.enabled) primary else Color.GRAY
            if (chosen) {
                dot.alpha = 35; canvas.drawCircle(position, y(value), 10 * density, dot); dot.alpha = 255
            }
            canvas.drawCircle(position, y(value), (if (chosen) 4.5f else 3f) * density, dot)
        }
        canvas.restore()
    }

    private fun makePath(path: Path, values: DoubleArray) {
        path.reset()
        values.indices.forEach { if (it == 0) path.moveTo(xPositions[it], y(values[it]))
            else path.lineTo(xPositions[it], y(values[it])) }
    }

    private fun drawCurve(canvas: Canvas, path: Path, color: Int, dash: Boolean) {
        curve.color = color; curve.pathEffect = if (dash) dashed else null
        curve.strokeWidth = density * if (dash) 1f else 1.7f
        canvas.drawPath(path, curve)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; return true }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                    performClick()
                    state.bands.filter { it.enabled && it.frequency < sampleRate * .499 }
                        .minByOrNull { abs(x(it.frequency) - event.x) }?.let {
                            if (abs(x(it.frequency) - event.x) < 30 * density) onBandSelected?.invoke(it.id)
                        }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); geometryDirty = true
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); scheduleCurves() }
    override fun onDetachedFromWindow() {
        motion?.cancel(); motion = null; removeCallbacks(updateFrame); updateQueued = false
        super.onDetachedFromWindow()
    }
}
