package me.timschneeberger.rootlessjamesdsp.peq

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.floor

/** Fixed, logarithmic-frequency magnitude preview using the same coefficients as the engine. */
class PeqResponseView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primary
        strokeWidth = 1.25f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(primary, 26) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foreground, 35)
        strokeWidth = 0.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foreground, 180)
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 220, 40, 40) }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(markerPaint.color, 105)
        strokeWidth = curvePaint.strokeWidth
    }
    private val markerBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(surface, 235)
    }
    private val markerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(surface, 100)
    }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foreground
        textSize = 9f * resources.displayMetrics.scaledDensity
    }
    private val graphBounds = RectF()
    private val line = Path()
    private val fill = Path()
    private var responseFrequencies = DoubleArray(BASE_POINTS) { 20.0 * 1000.0.pow(it.toDouble() / (BASE_POINTS - 1)) }
    private var responseValues = DoubleArray(BASE_POINTS)
    private var gainRange = 18.0
    private var requestedWidth = -1
    private var animator: ValueAnimator? = null
    private var worker: PeqResponse.PreviewWorker? = null
    private val generation = AtomicInteger()
    private val lifecycleGeneration = AtomicInteger()
    private var displayedRevision = -1
    private var selectedBandId: String? = null
    private var previewBandId: String? = null
    private var previewStatusBandId: String? = null
    private var previewStatusListening = false
    private var displayOptions = PeqEditorSettings()
    var isMarkerVisible: Boolean = false
        private set
    var requestedState: PeqState? = null
        private set
    /** The curve-only bank may preview one disabled band; requestedState is always the real bank. */
    internal var requestedPlotState: PeqState? = null
        private set
    data class AxisLabel(val text: String, val position: Float, val bounds: RectF)
    internal var renderedFrequencyLabels: List<AxisLabel> = emptyList()
        private set
    internal var renderedGainLabels: List<AxisLabel> = emptyList()
        private set
    internal data class MarkerLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float,
                                   val strokeWidth: Float)
    internal data class MarkerDot(val x: Float, val y: Float, val radius: Float)
    internal data class MarkerTextRun(val text: String, val color: Int)
    /** Actual parameters remain available even when the marker is clamped to the visible plot. */
    internal data class BandMarker(
        val bandId: String,
        val frequency: Double,
        val gain: Double,
        val q: Double,
        val bw: Double?,
        val x: Float,
        val y: Float,
        val radius: Float,
        val frequencyText: String,
        val gainText: String,
        val qText: String?,
        val bwText: String?,
        val labelBounds: RectF,
        val plotBounds: RectF,
        val horizontalCrosshair: MarkerLine,
        val verticalCrosshair: MarkerLine,
        val gainAxisMarker: MarkerDot,
        val frequencyAxisMarker: MarkerDot,
        val statusText: String?,
        val labelLines: List<String>,
        val textSizeSp: Float,
        val filterTypeText: String?,
        val labelColors: List<Int>,
        val labelRuns: List<List<MarkerTextRun>>
    )
    internal var renderedMarker: BandMarker? = null
        private set

    init {
        isSaveEnabled = false
        contentDescription = context.getString(R.string.peq_response_title)
    }

    /** Reuse the chart axes and drawing for EQ without converting EQ into PEQ filters. */
    fun setExternalResponse(frequencies: DoubleArray, gains: DoubleArray, range: Double = 18.0) {
        require(frequencies.size == gains.size && frequencies.size >= 2)
        generation.incrementAndGet()
        animator?.cancel()
        worker?.close()
        worker = null
        requestedState = null
        requestedPlotState = null
        previewBandId = null
        hideMarker()
        gainRange = range
        responseFrequencies = frequencies.copyOf()
        responseValues = gains.copyOf()
        invalidate()
    }

    fun setState(state: PeqState, animate: Boolean = true) {
        requestedState = state
        val plotState = displayState(state)
        val previousState = requestedPlotState
        val sameResponse = previousState != null && sameFilters(previousState, plotState)
        val sameWidth = requestedWidth == width
        if (!animate && animator?.isRunning == true) {
            if (sameResponse) animator?.end() else animator?.cancel()
        }
        requestedPlotState = plotState
        updateMarkerDescription()
        requestedWidth = width
        gainRange = state.gainRange
        invalidate()
        // Display preferences and Q/BW mode do not alter the filter response. Let an
        // already-running calculation finish, retaining its pending parameter update.
        if (sameResponse && sameWidth && worker?.isClosed == false) return
        val revision = generation.incrementAndGet()
        val points = (requestedWidth / density * 4).roundToInt().coerceIn(BASE_POINTS, 4096)
        val current = worker?.takeUnless { it.isClosed } ?: createPreviewWorker().also { worker = it }
        // Conflate drag events: at most one computation and one newest request exist.
        current.submit(PeqResponse.PreviewWorker.Request(plotState, points, revision, animate))
    }

    fun setDisplayOptions(settings: PeqEditorSettings) {
        settings.validate()
        if (displayOptions == settings) return
        displayOptions = settings
        if (!settings.showMarkerGuides) renderedMarker = null
        requestedState?.let { setState(it, false) }
        updateMarkerDescription()
        invalidate()
    }

    /** Opt-in visual inspection only: no audio session, enable switch, or repository is touched. */
    fun setPreviewBand(id: String?) {
        if (previewBandId == id) return
        previewBandId = id
        requestedState?.let { setState(it, false) }
    }

    /** Presentation only. The editor owns the draft lifetime and clears it explicitly. */
    fun setPreviewStatus(bandId: String?, listening: Boolean = false) {
        val isListening = bandId != null && listening
        if (previewStatusBandId == bandId && previewStatusListening == isListening) return
        if (bandId == null && previewBandId == previewStatusBandId) previewBandId = null
        previewStatusBandId = bandId
        previewStatusListening = isListening
        requestedState?.let { setState(it, false) }
        updateMarkerDescription()
        invalidate()
    }

    private fun displayState(state: PeqState): PeqState {
        // An explicit filter draft must be visible even when ordinary disabled-band
        // inspection is off. Neither option changes the real bank or starts audio.
        // Draft lifetime belongs to the editor, not the marker's short display timeout.
        val explicitId = previewStatusBandId?.takeIf { it == selectedBandId }
        val id = explicitId ?: previewBandId?.takeIf {
            displayOptions.previewDisabledBands && isMarkerVisible && it == selectedBandId
        } ?: return state
        if (state.bands.none { it.id == id && !it.enabled }) return state
        return state.copy(bands = state.bands.map { if (it.id == id) it.copy(enabled = true) else it })
    }

    private fun sameFilters(first: PeqState, second: PeqState): Boolean =
        first.bands.size == second.bands.size && first.bands.indices.all { index ->
            val left = first.bands[index]
            val right = second.bands[index]
            left.id == right.id && left.type == right.type && left.enabled == right.enabled &&
                left.frequency == right.frequency && left.gain == right.gain && left.q == right.q
        }

    private fun createPreviewWorker(): PeqResponse.PreviewWorker {
        val lifecycle = lifecycleGeneration.get()
        return PeqResponse.PreviewWorker(onComplete = { request, next ->
            post {
                if (lifecycle == lifecycleGeneration.get() && request.revision == generation.get() &&
                    request.revision > displayedRevision) {
                    displayedRevision = request.revision
                    updateCurve(next, request.animate && request.revision == generation.get())
                }
            }
        })
    }

    /** Selection never reveals a hidden marker or changes the audio filters. */
    fun setSelectedBand(id: String?) {
        if (selectedBandId == id) return
        selectedBandId = id
        requestedState?.let { setState(it, false) }
        updateMarkerDescription()
        invalidate()
    }

    /** The fragment owns interaction timing; this view only controls the overlay's visibility. */
    fun showMarker(id: String? = selectedBandId) {
        setSelectedBand(id)
        isMarkerVisible = true
        requestedState?.let { setState(it, false) }
        updateMarkerDescription()
        invalidate()
    }

    fun hideMarker() {
        isMarkerVisible = false
        renderedMarker = null
        previewBandId = null
        requestedState?.let { setState(it, false) }
        updateMarkerDescription()
        invalidate()
    }

    private fun selectedBand(): PeqBand? = requestedState?.bands?.let { bands ->
        bands.firstOrNull { it.id == selectedBandId } ?: bands.firstOrNull()
    }

    private fun markerFrequencyText(band: PeqBand) = "${PeqBandAdapter.number(band.frequency, 3)} Hz"

    private fun markerGain(band: PeqBand) = band.gain

    private fun markerGainText(band: PeqBand): String {
        val gain = markerGain(band)
        return "${if (gain > 0) "+" else ""}${PeqBandAdapter.fixedNumber(gain, 2)} dB"
    }

    private fun markerBandwidth(band: PeqBand): Double? = if (band.qualityDisplay == PeqQualityDisplay.BW)
        PeqResponse.bandwidthOctaves(band.q, band.frequency) else null

    private fun markerQText(band: PeqBand): String? = if (band.qualityDisplay == PeqQualityDisplay.Q)
        "Q: ${String.format(Locale.ROOT, "%.3f", band.q)}" else null

    private fun markerBwText(band: PeqBand): String? = markerBandwidth(band)?.let {
        "BW: ${String.format(Locale.ROOT, "%.3f", it)}"
    }

    private fun markerStatusText(band: PeqBand): String? = if (!band.enabled)
        context.getString(R.string.peq_marker_disabled) else null

    private fun markerFilterTypeText(band: PeqBand): String? =
        PeqBandAdapter.typeLabel(context, band.type).takeIf { displayOptions.showMarkerFilterType }

    private fun markerPreviewStatusText(band: PeqBand): String? =
        if (displayOptions.showMarkerPreviewStatus && previewStatusBandId == band.id)
            context.getString(if (previewStatusListening) R.string.peq_marker_listening else R.string.peq_marker_preview)
        else null

    private fun markerHeadingText(band: PeqBand): String? {
        // Derive the ordinal from the complete bank, including disabled bands, not the
        // audible/preview subset. Reordering therefore updates the label immediately.
        val ordinal = requestedState?.bands?.indexOfFirst { it.id == band.id }
            ?.takeIf { it >= 0 }?.plus(1)
        val number = ordinal?.takeIf { displayOptions.showMarkerBandNumber }
            ?.let { context.getString(R.string.peq_band_ordinal, it) }
        return listOfNotNull(markerFilterTypeText(band), number, markerPreviewStatusText(band)).joinToString(" • ")
            .takeIf { it.isNotEmpty() }
    }

    private fun markerLines(band: PeqBand): List<String> = listOfNotNull(
        markerStatusText(band),
        markerHeadingText(band),
        markerFrequencyText(band).takeIf { displayOptions.showMarkerFrequency },
        markerGainText(band).takeIf { displayOptions.showMarkerGain },
        markerQText(band).takeIf { displayOptions.showMarkerQuality },
        markerBwText(band).takeIf { displayOptions.showMarkerQuality })

    private fun updateMarkerDescription() {
        val title = context.getString(R.string.peq_response_title)
        contentDescription = selectedBand()?.takeIf { isMarkerVisible && displayOptions.showMarkerGuides }?.let {
            (listOf(title) + markerLines(it))
                .joinToString(", ")
        } ?: title
    }

    private fun updateCurve(points: List<PeqResponse.Point>, animate: Boolean) {
        animator?.cancel()
        val nextFrequencies = points.map { it.frequency }.toDoubleArray()
        val next = points.map { it.db }.toDoubleArray()
        if (!animate || !isAttachedToWindow) {
            responseFrequencies = nextFrequencies
            responseValues = next
            invalidate()
            return
        }
        // Adaptive sampling moves frequency points as Q/frequency changes. Resample the old
        // drawn curve on the new log-frequency grid before interpolating its gains.
        var leftIndex = 0
        val previous = DoubleArray(next.size) { index ->
            val frequency = nextFrequencies[index]
            while (leftIndex < responseFrequencies.lastIndex - 1 && responseFrequencies[leftIndex + 1] < frequency) leftIndex++
            val rightIndex = (leftIndex + 1).coerceAtMost(responseFrequencies.lastIndex)
            val low = responseFrequencies[leftIndex]
            val high = responseFrequencies[rightIndex]
            val fraction = if (low == high) 0.0 else (ln(frequency / low) / ln(high / low)).coerceIn(0.0, 1.0)
            responseValues[leftIndex] + (responseValues[rightIndex] - responseValues[leftIndex]) * fraction
        }
        responseFrequencies = nextFrequencies
        responseValues = previous
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            addUpdateListener {
                val fraction = it.animatedValue as Float
                responseValues = DoubleArray(next.size) { index -> previous[index] + (next[index] - previous[index]) * fraction }
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestedState?.takeIf { worker == null || worker!!.isClosed }?.let { setState(it, false) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) requestedState?.let { setState(it, false) }
    }

    override fun onDetachedFromWindow() {
        generation.incrementAndGet()
        lifecycleGeneration.incrementAndGet()
        animator?.cancel()
        worker?.close()
        worker = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderedMarker = null
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val labelWidth = maxOf(textPaint.measureText("+${PeqBandAdapter.number(gainRange)}"),
            textPaint.measureText("−${PeqBandAdapter.number(gainRange)}"))
        graphBounds.set(labelWidth + 8f * density, textHeight / 2 + 5f * density,
            width - 15f * density, height - textHeight - 9f * density)
        if (graphBounds.width() <= 0 || graphBounds.height() <= 0) return
        val frequencies = listOf(20.0, 30.0, 40.0, 50.0, 60.0, 80.0, 100.0, 150.0, 200.0,
            300.0, 400.0, 500.0, 600.0, 800.0, 1000.0, 1500.0, 2000.0, 3000.0, 4000.0,
            5000.0, 6000.0, 8000.0, 10000.0, 12000.0, 16000.0, 20000.0)
        textPaint.textAlign = Paint.Align.CENTER
        frequencies.forEach { frequency ->
            val position = x(frequency)
            gridPaint.alpha = if (frequency in listOf(20.0, 100.0, 1000.0, 10000.0, 20000.0)) 60 else 25
            canvas.drawLine(position, graphBounds.top, position, graphBounds.bottom, gridPaint)
        }
        val xLabels = mutableListOf<AxisLabel>()
        val priorityFrequencies = (listOf(20.0, 20000.0, 1000.0, 100.0, 10000.0) + frequencies).distinct()
        val baseline = height - 4f * density - metrics.descent
        priorityFrequencies.forEach { frequency ->
            val text = if (frequency >= 1000) "${PeqBandAdapter.number(frequency / 1000)}k" else PeqBandAdapter.number(frequency)
            val position = x(frequency)
            val halfWidth = textPaint.measureText(text) / 2
            val bounds = RectF(position - halfWidth, baseline + metrics.ascent, position + halfWidth, baseline + metrics.descent)
            if (xLabels.none { bounds.left < it.bounds.right + 7f * density && bounds.right + 7f * density > it.bounds.left })
                xLabels.add(AxisLabel(text, position, bounds))
        }
        renderedFrequencyLabels = xLabels.sortedBy { it.position }
        renderedFrequencyLabels.forEach { canvas.drawText(it.text, it.position, baseline, textPaint) }
        textPaint.textAlign = Paint.Align.RIGHT
        val intervals = (graphBounds.height() / (textHeight + 5f * density)).coerceAtLeast(2f)
        val minimumStep = gainRange * 2 / intervals
        val gainStep = listOf(0.5, 1.0, 2.0, 2.5, 3.0, 5.0, 6.0, 10.0, 12.0, 20.0, 25.0, 50.0, 100.0)
            .firstOrNull { it >= minimumStep } ?: 100.0
        val gains = (-floor(gainRange / gainStep).toInt()..floor(gainRange / gainStep).toInt()).map { it * gainStep }
        (gains + listOf(-gainRange, gainRange)).distinct().forEach { gain ->
            gridPaint.alpha = if (gain == 0.0) 90 else 35
            canvas.drawLine(graphBounds.left, y(gain), graphBounds.right, y(gain), gridPaint)
        }
        val yLabels = mutableListOf<AxisLabel>()
        (listOf(gainRange, -gainRange, 0.0) + gains.sortedBy { abs(it) }).distinct().forEach { gain ->
            val text = (if (gain > 0.0) "+" else "") + PeqBandAdapter.number(gain)
            val position = y(gain)
            val base = position - (metrics.ascent + metrics.descent) / 2
            val bounds = RectF(graphBounds.left - 5f * density - textPaint.measureText(text),
                base + metrics.ascent, graphBounds.left - 5f * density, base + metrics.descent)
            if (yLabels.none { bounds.top < it.bounds.bottom + 3f * density && bounds.bottom + 3f * density > it.bounds.top })
                yLabels.add(AxisLabel(text, base, bounds))
        }
        renderedGainLabels = yLabels.sortedBy { it.position }
        renderedGainLabels.forEach { canvas.drawText(it.text, graphBounds.left - 5f * density, it.position, textPaint) }
        line.rewind()
        responseValues.forEachIndexed { index, db ->
            val position = x(responseFrequencies[index])
            if (index == 0) line.moveTo(position, y(db)) else line.lineTo(position, y(db))
        }
        fill.set(line)
        fill.lineTo(graphBounds.right, y(0.0))
        fill.lineTo(graphBounds.left, y(0.0))
        fill.close()
        canvas.save()
        canvas.clipRect(graphBounds)
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, curvePaint)
        canvas.restore()
        drawSelectedBand(canvas)
    }

    private fun drawSelectedBand(canvas: Canvas) {
        // Presentation-only visibility must not end the editor's draft or marker timer.
        if (!isMarkerVisible || !displayOptions.showMarkerGuides) return
        val band = selectedBand() ?: return
        val radius = minOf(3f * density, graphBounds.width() / 4, graphBounds.height() / 4)
        val markerX = x(band.frequency).coerceIn(graphBounds.left + radius, graphBounds.right - radius)
        val gain = markerGain(band)
        val markerY = y(gain).coerceIn(graphBounds.top + radius, graphBounds.bottom - radius)
        val frequencyText = markerFrequencyText(band).takeIf { displayOptions.showMarkerFrequency }.orEmpty()
        val gainText = markerGainText(band).takeIf { displayOptions.showMarkerGain }.orEmpty()
        val qText = markerQText(band).takeIf { displayOptions.showMarkerQuality }
        val bwText = markerBwText(band).takeIf { displayOptions.showMarkerQuality }
        val statusText = markerStatusText(band)
        val labelLines = markerLines(band)
        val gainColor = when {
            gain > 0.0 -> Color.rgb(211, 47, 47)
            gain < 0.0 -> Color.rgb(25, 118, 210)
            else -> foreground
        }
        val previewStatusText = markerPreviewStatusText(band)
        val headingText = markerHeadingText(band)
        val labelRuns = labelLines.map { text ->
            if (previewStatusText != null && text == headingText) {
                val prefix = text.removeSuffix(previewStatusText)
                buildList {
                    if (prefix.isNotEmpty()) add(MarkerTextRun(prefix, foreground))
                    add(MarkerTextRun(previewStatusText, if (previewStatusListening)
                        Color.rgb(46, 125, 50) else Color.rgb(25, 118, 210)))
                }
            } else listOf(MarkerTextRun(text, if (text == gainText) gainColor else foreground))
        }
        val labelColors = labelRuns.map { it.first().color }
        val horizontalCrosshair = MarkerLine(graphBounds.left, markerY, graphBounds.right, markerY, crosshairPaint.strokeWidth)
        val verticalCrosshair = MarkerLine(markerX, graphBounds.top, markerX, graphBounds.bottom, crosshairPaint.strokeWidth)
        val gainAxisMarker = MarkerDot(horizontalCrosshair.x1, horizontalCrosshair.y1, radius)
        val frequencyAxisMarker = MarkerDot(verticalCrosshair.x2, verticalCrosshair.y2, radius)
        val padding = 4f * density
        val gap = radius + 5f * density
        markerTextPaint.textSize = displayOptions.markerTextSizeSp * resources.displayMetrics.scaledDensity
        // Keep all values readable on compact displays without clipping the graph's axes.
        val naturalWidth = labelLines.maxOfOrNull { markerTextPaint.measureText(it) } ?: 1f
        val availableWidth = (graphBounds.width() - padding * 2).coerceAtLeast(1f)
        val naturalMetrics = markerTextPaint.fontMetrics
        val naturalHeight = maxOf(1f, labelLines.size * (naturalMetrics.descent - naturalMetrics.ascent))
        val availableHeight = (graphBounds.height() - padding * 2).coerceAtLeast(1f)
        markerTextPaint.textSize *= minOf(1f, availableWidth / naturalWidth, availableHeight / naturalHeight)
        val metrics = markerTextPaint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val labelWidth = if (labelLines.isEmpty()) 0f else
            labelLines.maxOf { markerTextPaint.measureText(it) } + padding * 2
        val labelHeight = if (labelLines.isEmpty()) 0f else lineHeight * labelLines.size + padding * 2
        val preferredLeft = if (markerX + gap + labelWidth <= graphBounds.right) markerX + gap
            else markerX - gap - labelWidth
        val labelLeft = preferredLeft.coerceIn(graphBounds.left, maxOf(graphBounds.left, graphBounds.right - labelWidth))
        val labelTop = (markerY - labelHeight / 2).coerceIn(graphBounds.top, maxOf(graphBounds.top, graphBounds.bottom - labelHeight))
        val label = RectF(labelLeft, labelTop, labelLeft + labelWidth, labelTop + labelHeight)
        renderedMarker = BandMarker(band.id, band.frequency, gain, band.q, markerBandwidth(band),
            markerX, markerY, radius, frequencyText, gainText, qText, bwText, RectF(label), RectF(graphBounds),
            horizontalCrosshair, verticalCrosshair, gainAxisMarker, frequencyAxisMarker,
            statusText, labelLines, markerTextPaint.textSize / resources.displayMetrics.scaledDensity,
            markerFilterTypeText(band), labelColors, labelRuns)
        canvas.save()
        canvas.clipRect(graphBounds)
        canvas.drawLine(horizontalCrosshair.x1, horizontalCrosshair.y1,
            horizontalCrosshair.x2, horizontalCrosshair.y2, crosshairPaint)
        canvas.drawLine(verticalCrosshair.x1, verticalCrosshair.y1,
            verticalCrosshair.x2, verticalCrosshair.y2, crosshairPaint)
        canvas.restore()
        if (labelLines.isNotEmpty()) canvas.drawRoundRect(label, 3f * density, 3f * density, markerBackgroundPaint)
        val baseline = label.top + padding - metrics.ascent
        labelRuns.forEachIndexed { index, runs ->
            var textX = label.left + padding
            runs.forEach { run ->
                markerTextPaint.color = run.color
                canvas.drawText(run.text, textX, baseline + lineHeight * index, markerTextPaint)
                textX += markerTextPaint.measureText(run.text)
            }
        }
        markerTextPaint.color = foreground
        // Draw last so a tiny viewport or an edge-clamped label cannot hide the red point.
        for (dot in listOf(gainAxisMarker, frequencyAxisMarker)) {
            canvas.drawCircle(dot.x, dot.y, dot.radius + density, markerHaloPaint)
            canvas.drawCircle(dot.x, dot.y, dot.radius, markerPaint)
        }
        canvas.drawCircle(markerX, markerY, radius + density, markerHaloPaint)
        canvas.drawCircle(markerX, markerY, radius, markerPaint)
    }

    private fun y(db: Double): Float = graphBounds.centerY() -
        (db.coerceIn(-gainRange * 2, gainRange * 2) / (2 * gainRange) * graphBounds.height()).toFloat()

    private fun x(frequency: Double): Float = if (frequency <= PeqModel.MIN_FREQUENCY) graphBounds.left
        else graphBounds.left +
            (log10(frequency / PeqModel.MIN_FREQUENCY) / 3.0 * graphBounds.width()).toFloat()

    companion object { private const val BASE_POINTS = 1024 }
}
