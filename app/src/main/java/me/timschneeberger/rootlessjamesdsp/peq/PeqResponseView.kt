package me.timschneeberger.rootlessjamesdsp.peq

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
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ceil

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
    private val bandLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val bandFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandwidthPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val previewDash = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    private val effectStatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foreground
        textAlign = Paint.Align.CENTER
        textSize = 12f * resources.displayMetrics.scaledDensity
    }
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
    private var pathsDirty = true
    private var axesDirty = true
    private data class GridLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val alpha: Int)
    private var gridLines: List<GridLine> = emptyList()
    private var frequencyLabelBaseline = 0f
    private var graphLogSpan = ln(20000.0)
    private var responseFrequencies = DoubleArray(BASE_POINTS) { 20000.0.pow(it.toDouble() / (BASE_POINTS - 1)) }
    private var responseValues = DoubleArray(BASE_POINTS) { Double.NaN }
    private var pathFrequencies = DoubleArray(0)
    private var pathPositions = FloatArray(0)
    private var sparePathPositions = FloatArray(0)
    private var pathGeometry = -1
    private var processingRate = PeqResponse.DEFAULT_SAMPLE_RATE
    private var graphMinimum = 1.0
    private var graphMaximum = 20000.0
    private var gainRange = 18.0
    private var displayGainBounds: ClosedFloatingPointRange<Double>? = null
    private var effectEnabled = true
    private val eqAnnotations = EqChartAnnotations(context)
    private var displayedPlotState: PeqState? = null
    private var displayedSampleRate = PeqResponse.DEFAULT_SAMPLE_RATE
    private data class TouchSnapshot(val state: PeqState?, val sampleRate: Double, val band: PeqBand?,
        val frequencies: DoubleArray, val values: DoubleArray)
    private data class TouchReading(val frequency: Double, val gain: Double?, val lines: List<String>)
    private var touchSnapshot: TouchSnapshot? = null
    private var touchReading: TouchReading? = null
    private var waitingForTouchGesture = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchX = 0f
    private var touchUpdatePosted = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val touchLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(primary, 190)
        strokeWidth = density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val touchDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    private val touchTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground }
    private val touchBox = RectF()
    private val beginTouchLongPress = Runnable {
        if (waitingForTouchGesture) beginTouchInspection()
    }
    private val updateTouchReading = Runnable {
        touchUpdatePosted = false
        calculateTouchReading()
    }

    /** An opt-in external-EQ overlay; no PEQ selection or parameter callback is involved. */
    fun setEqAnnotations(frequencies: DoubleArray, gains: DoubleArray, activeIndex: Int, effectEnabled: Boolean) {
        if (requestedState != null) return
        finishTouchInspection()
        eqAnnotations.setState(frequencies, gains, activeIndex, effectEnabled)
        invalidate()
    }

    /** Passive presentation snapshots; neither the callback nor power state writes audio parameters. */
    var onPreviewChanged: ((PeqState, String?, Double, PeqBand?) -> Unit)? = null
        set(value) {
            field = value
            lastNotification = null
            notifyPreviewChanged()
        }
    private data class PreviewNotification(val state: PeqState, val selectedId: String?,
        val sampleRate: Double, val preview: PeqBand?, val enabled: Boolean)
    private var lastNotification: PreviewNotification? = null

    fun setEffectEnabled(enabled: Boolean) {
        if (effectEnabled == enabled) return
        finishTouchInspection()
        effectEnabled = enabled
        notifyPreviewChanged()
        invalidate()
    }

    private fun activePreviewBand(): PeqBand? {
        val id = previewStatusBandId?.takeIf { it == selectedBandId }
            ?: previewBandId?.takeIf { isMarkerVisible && it == selectedBandId }
            ?: return null
        return requestedState?.bands?.firstOrNull { it.id == id }
    }

    private fun notifyPreviewChanged() {
        val state = requestedState ?: return
        val notification = PreviewNotification(state, selectedBand()?.id, processingRate,
            activePreviewBand(), effectEnabled)
        if (notification == lastNotification) return
        lastNotification = notification
        onPreviewChanged?.invoke(state, notification.selectedId, processingRate, notification.preview)
    }

    private data class OverlayConfiguration(val bands: List<PeqBand>, val selectedId: String?,
        val sampleRate: Double, val minimum: Double, val maximum: Double, val points: Int)
    private data class OverlayRequest(val configuration: OverlayConfiguration, val revision: Int, val lifecycle: Int)
    private data class BandCurve(val band: PeqBand, val points: List<PeqResponse.Point>)
    private data class OverlayFrame(val request: OverlayRequest, val bands: List<BandCurve>,
        val bandwidth: PeqParameterResponse.Bandwidth?)
    private data class BandDrawing(val band: PeqBand, val color: Int, val line: Path, val fill: Path,
        val points: List<PeqResponse.Point>, val geometry: Int)
    private var overlayWorker: BandOverlayWorker? = null
    private var overlayConfiguration: OverlayConfiguration? = null
    private var overlayFrame: OverlayFrame? = null
    private var overlayDrawings = emptyList<BandDrawing>()
    private var overlayDrawingCache = emptyMap<String, BandDrawing>()
    private var overlayGeometry = 0
    private var overlayPathsDirty = true
    private var overlayRevision = 0
    private var displayedOverlayRevision = -1
    private var pendingOverlay: OverlayRequest? = null
    private var overlaySubmissionPosted = false
    private val completedOverlay = AtomicReference<OverlayFrame?>()
    private val overlayDisplayPosted = AtomicBoolean(false)
    private val submitOverlay = Runnable {
        overlaySubmissionPosted = false
        val request = pendingOverlay
        pendingOverlay = null
        if (request != null && requestedState != null && needsBandOverlays() &&
            isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            val current = overlayWorker?.takeUnless { it.isClosed } ?: BandOverlayWorker { frame ->
                if (frame.request.lifecycle == lifecycleGeneration.get()) {
                    completedOverlay.set(frame)
                    scheduleOverlayDisplay()
                }
            }.also { overlayWorker = it }
            current.submit(request)
        }
    }
    private val displayOverlay = Runnable {
        val frame = completedOverlay.getAndSet(null)
        overlayDisplayPosted.set(false)
        if (frame != null && requestedState != null && needsBandOverlays() &&
            frame.request.lifecycle == lifecycleGeneration.get()) {
            val config = frame.request.configuration
            if (frame.request.revision > displayedOverlayRevision && config.sampleRate == processingRate &&
                config.minimum == graphMinimum && config.maximum == graphMaximum) {
                finishTouchInspection()
                displayedOverlayRevision = frame.request.revision
                overlayFrame = frame
                overlayPathsDirty = true
                invalidate()
            }
        }
        scheduleOverlayDisplay()
    }

    private fun scheduleOverlayDisplay() {
        if (completedOverlay.get() != null && overlayDisplayPosted.compareAndSet(false, true))
            postOnAnimation(displayOverlay)
    }

    private fun requestBandOverlays(state: PeqState) {
        if (!needsBandOverlays()) {
            // These are optional display layers; the total response and selected marker
            // have their own model and must remain responsive with both layers hidden.
            if (overlayWorker != null || pendingOverlay != null || overlayFrame != null)
                stopBandOverlays(clear = true)
            return
        }
        val config = OverlayConfiguration(state.bands.toList(), selectedBand()?.id, processingRate,
            graphMinimum, graphMaximum, (width / density * 2f).roundToInt().coerceIn(256, 1024))
        if (config == overlayConfiguration && (overlayWorker?.isClosed == false || overlaySubmissionPosted)) return
        overlayConfiguration = config
        pendingOverlay = OverlayRequest(config, ++overlayRevision, lifecycleGeneration.get())
        if (!overlaySubmissionPosted && isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            overlaySubmissionPosted = true
            postOnAnimation(submitOverlay)
        }
    }

    private fun needsBandOverlays(): Boolean =
        !displayOptions.onlyTotalResponse || displayOptions.showBandwidthOverlay

    private fun stopBandOverlays(clear: Boolean = false) {
        overlayWorker?.close()
        overlayWorker = null
        overlayConfiguration = null
        pendingOverlay = null
        overlaySubmissionPosted = false
        removeCallbacks(submitOverlay)
        removeCallbacks(displayOverlay)
        completedOverlay.set(null)
        overlayDisplayPosted.set(false)
        if (clear) {
            overlayFrame = null
            overlayDrawings = emptyList()
            overlayDrawingCache = emptyMap()
            overlayPathsDirty = true
        }
    }

    /** Viewport only; does not modify response samples or editable parameter ranges. */
    fun setDisplayGainBounds(minimum: Double, maximum: Double) {
        require(minimum.isFinite() && maximum.isFinite() && minimum < maximum)
        if (displayGainBounds?.let { it.start == minimum && it.endInclusive == maximum } == true) return
        finishTouchInspection()
        displayGainBounds = minimum..maximum
        axesDirty = true
        pathsDirty = true
        overlayPathsDirty = true
        invalidate()
    }
    private var requestedWidth = -1
    private var animator: ValueAnimator? = null
    private var worker: PeqResponse.PreviewWorker? = null
    private val generation = AtomicInteger()
    private val lifecycleGeneration = AtomicInteger()
    private var displayedRevision = -1
    private var readyForVisibilityCallbacks = false
    private var pendingSubmission: PeqResponse.PreviewWorker.Request? = null
    private var submissionPosted = false
    private val submitPreview = Runnable {
        submissionPosted = false
        val request = pendingSubmission
        pendingSubmission = null
        if (request != null && isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            val current = worker?.takeUnless { it.isClosed } ?: createPreviewWorker().also { worker = it }
            current.submit(request)
        }
    }
    private data class CompletedPreview(val lifecycle: Int, val request: PeqResponse.PreviewWorker.Request,
                                        val points: List<PeqResponse.Point>)
    private val pendingCompleted = AtomicReference<CompletedPreview?>()
    private val completionPosted = AtomicBoolean(false)
    private val displayPreview = Runnable {
        val completed = pendingCompleted.getAndSet(null)
        completionPosted.set(false)
        if (completed != null && completed.lifecycle == lifecycleGeneration.get()) {
            val request = completed.request
            // A newer drag does not invalidate a completed frame in the same
            // domain. Strict equality starved rendering until the finger stopped.
            if (request.revision > displayedRevision && request.sampleRate == processingRate &&
                request.frequencyMin == graphMinimum && request.frequencyMax == graphMaximum) {
                finishTouchInspection()
                displayedRevision = request.revision
                displayedPlotState = request.state
                displayedSampleRate = request.sampleRate
                updateCurve(completed.points, request.animate && request.revision == generation.get())
            }
        }
        schedulePreviewDisplay()
    }
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
        readyForVisibilityCallbacks = true
    }

    /** Reuse the chart axes and drawing for EQ without converting EQ into PEQ filters. */
    fun setExternalResponse(frequencies: DoubleArray, gains: DoubleArray, range: Double = 18.0) {
        require(frequencies.size == gains.size && frequencies.size >= 2)
        require(range.isFinite() && range > 0)
        require(frequencies.all { it.isFinite() && it > 0 } &&
            frequencies.asSequence().zipWithNext().all { (left, right) -> left < right })
        finishTouchInspection()
        generation.incrementAndGet()
        lifecycleGeneration.incrementAndGet()
        animator?.cancel()
        worker?.close()
        worker = null
        clearPreviewCallbacks()
        stopBandOverlays(clear = true)
        requestedState = null
        requestedPlotState = null
        displayedPlotState = null
        eqAnnotations.clear()
        previewBandId = null
        hideMarker()
        gainRange = range
        // External previews define their own measured/calculated domain. Do not extend
        // a 20 Hz response into an unknown 1–20 Hz region merely to fill the viewport.
        graphMinimum = frequencies.first()
        graphMaximum = frequencies.last()
        responseFrequencies = frequencies.copyOf()
        responseValues = gains.copyOf()
        axesDirty = true
        pathsDirty = true
        invalidate()
    }

    fun setSampleRate(rate: Double?) {
        val next = rate?.let { PeqFrequencyLimits.sampleRate(it) } ?: processingRate
        if (next == processingRate) return
        finishTouchInspection()
        processingRate = next
        requestedPlotState = null
        requestedState?.let { setState(it, false) }
    }

    fun setState(state: PeqState, animate: Boolean = true) {
        if (requestedState != state) finishTouchInspection()
        if (requestedState == null) eqAnnotations.clear()
        requestedState = state
        val plotState = displayState(state)
        if (requestedPlotState != plotState) finishTouchInspection()
        val bounds = PeqFrequencyLimits.graphRange(state, processingRate)
        val previousState = requestedPlotState
        val sameResponse = previousState != null && sameFilters(previousState, plotState) &&
            graphMinimum == bounds.start && graphMaximum == bounds.endInclusive
        if (graphMinimum != bounds.start || graphMaximum != bounds.endInclusive || gainRange != state.gainRange) {
            axesDirty = true
            pathsDirty = true
        }
        graphMinimum = bounds.start
        graphMaximum = bounds.endInclusive
        val sameWidth = requestedWidth == width
        if (!animate && animator?.isRunning == true) {
            if (sameResponse) animator?.end() else animator?.cancel()
        }
        requestedPlotState = plotState
        updateMarkerDescription()
        requestedWidth = width
        gainRange = state.gainRange
        notifyPreviewChanged()
        requestBandOverlays(state)
        invalidate()
        // Display preferences and Q/BW mode do not alter the filter response. Let an
        // already-running calculation finish, retaining its pending parameter update.
        if (sameResponse && sameWidth && worker?.isClosed == false) return
        val revision = generation.incrementAndGet()
        val points = (requestedWidth / density * 4).roundToInt().coerceIn(BASE_POINTS, 4096)
        // Submit at most once per display frame, then let the worker conflate
        // any slower calculations. The newest final draft is always retained.
        pendingSubmission = PeqResponse.PreviewWorker.Request(plotState, points, revision, animate,
            sampleRate = processingRate, frequencyMin = graphMinimum, frequencyMax = graphMaximum)
        if (!submissionPosted && isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            submissionPosted = true
            postOnAnimation(submitPreview)
        }
    }

    fun setDisplayOptions(settings: PeqEditorSettings) {
        settings.validate()
        if (displayOptions == settings) return
        finishTouchInspection()
        displayOptions = settings
        if (!settings.showMarkerGuides) renderedMarker = null
        requestedState?.let { setState(it, false) }
        updateMarkerDescription()
        invalidate()
    }

    /** Graph-only inspection: no audio session, enable switch, or repository is touched. */
    fun setPreviewBand(id: String?) {
        if (previewBandId == id) return
        previewBandId = id
        requestedState?.let { setState(it, false) }
    }

    /** Presentation only. The editor owns the draft lifetime and clears it explicitly. */
    fun setPreviewStatus(bandId: String?, listening: Boolean = false) {
        val isListening = bandId != null && listening
        if (previewStatusBandId == bandId && previewStatusListening == isListening) return
        finishTouchInspection()
        if (bandId == null && previewBandId == previewStatusBandId) previewBandId = null
        previewStatusBandId = bandId
        previewStatusListening = isListening
        requestedState?.let { setState(it, false) }
        updateMarkerDescription()
        invalidate()
    }

    private fun displayState(state: PeqState): PeqState {
        // Disabled bands are always available for visual inspection. A filter draft's
        // lifetime belongs to the editor, not the marker's short display timeout.
        // Both paths retain the actual enable switches and never start audio.
        val explicitId = previewStatusBandId?.takeIf { it == selectedBandId }
        val id = explicitId ?: previewBandId?.takeIf {
            isMarkerVisible && it == selectedBandId
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
            if (lifecycle == lifecycleGeneration.get()) {
                pendingCompleted.set(CompletedPreview(lifecycle, request, next))
                schedulePreviewDisplay()
            }
        })
    }

    private fun schedulePreviewDisplay() {
        if (pendingCompleted.get() != null && completionPosted.compareAndSet(false, true))
            postOnAnimation(displayPreview)
    }

    private fun clearPreviewCallbacks() {
        removeCallbacks(submitPreview)
        removeCallbacks(displayPreview)
        submissionPosted = false
        pendingSubmission = null
        completionPosted.set(false)
        pendingCompleted.set(null)
    }

    /** Selection never reveals a hidden marker or changes the audio filters. */
    fun setSelectedBand(id: String?) {
        if (selectedBandId == id) return
        finishTouchInspection()
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
        "Q: ${PeqQualityEditor.format(band.q)}" else null

    private fun markerBwText(band: PeqBand): String? = markerBandwidth(band)?.let {
        "BW: ${PeqQualityEditor.format(it)}"
    }

    private fun markerStatusText(band: PeqBand): String? = if (!band.enabled)
        context.getString(R.string.peq_marker_disabled) else null

    private fun markerFilterTypeText(band: PeqBand): String? =
        PeqBandAdapter.typeLabel(context, band.type).takeIf { displayOptions.showMarkerFilterType }

    private fun markerIsListening(band: PeqBand) = previewStatusBandId == band.id && previewStatusListening

    private fun markerPreviewStatusText(band: PeqBand): String? =
        if (displayOptions.showMarkerPreviewStatus && (!band.enabled || previewStatusBandId == band.id))
            context.getString(if (markerIsListening(band)) R.string.peq_marker_listening else R.string.peq_marker_preview)
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
        val nextFrequencies = DoubleArray(points.size) { points[it].frequency }
        val next = DoubleArray(points.size) { points[it].db }
        if (!animate || !isAttachedToWindow || next.any { !it.isFinite() } || responseValues.any { !it.isFinite() }) {
            responseFrequencies = nextFrequencies
            responseValues = next
            pathsDirty = true
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
        responseValues = previous.copyOf()
        pathsDirty = true
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            addUpdateListener {
                val fraction = it.animatedValue as Float
                for (index in next.indices) responseValues[index] = previous[index] + (next[index] - previous[index]) * fraction
                pathsDirty = true
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
        finishTouchInspection()
        axesDirty = true
        pathsDirty = true
        overlayPathsDirty = true
        if (w != oldw) requestedState?.let { setState(it, false) }
    }

    override fun onDetachedFromWindow() {
        finishTouchInspection()
        generation.incrementAndGet()
        lifecycleGeneration.incrementAndGet()
        animator?.cancel()
        worker?.close()
        worker = null
        clearPreviewCallbacks()
        stopBandOverlays()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (readyForVisibilityCallbacks) refreshPreviewVisibility()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (readyForVisibilityCallbacks) refreshPreviewVisibility()
    }

    private fun refreshPreviewVisibility() {
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE) {
            finishTouchInspection()
            lifecycleGeneration.incrementAndGet()
            animator?.cancel()
            worker?.close()
            worker = null
            clearPreviewCallbacks()
            stopBandOverlays()
        } else requestedState?.takeIf { worker == null }?.let { setState(it, false) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderedMarker = null
        if (axesDirty) rebuildAxes()
        if (graphBounds.width() <= 0 || graphBounds.height() <= 0) return
        for (segment in gridLines) {
            gridPaint.alpha = segment.alpha
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, gridPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        renderedFrequencyLabels.forEach { canvas.drawText(it.text, it.position, frequencyLabelBaseline, textPaint) }
        textPaint.textAlign = Paint.Align.RIGHT
        renderedGainLabels.forEach { canvas.drawText(it.text, graphBounds.left - 5f * density, it.position, textPaint) }
        if (pathsDirty) rebuildPaths()
        canvas.save()
        canvas.clipRect(graphBounds)
        if (requestedState != null && needsBandOverlays()) drawBandOverlays(canvas)
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, curvePaint)
        canvas.restore()
        if (requestedState == null) eqAnnotations.draw(canvas, graphBounds, graphMinimum, graphMaximum,
            displayGainBounds?.start ?: -gainRange, displayGainBounds?.endInclusive ?: gainRange,
            responseFrequencies, responseValues)
        if (touchSnapshot == null) drawSelectedBand(canvas)
        if (requestedState != null && !effectEnabled) drawEffectDisabled(canvas)
        drawTouchInspection(canvas)
    }

    private fun drawBandOverlays(canvas: Canvas) {
        if (overlayPathsDirty) rebuildBandPaths()
        val selectedId = selectedBand()?.id
        val frame = overlayFrame
        val selected = overlayDrawings.firstOrNull { it.band.id == selectedId }
        val bandwidth = frame?.takeIf { it.request.configuration.selectedId == selectedId }?.bandwidth
        if (displayOptions.showBandwidthOverlay && selected != null && bandwidth?.complete == true) {
            val lower = (bandwidth.lowerHz ?: graphMinimum).coerceAtLeast(graphMinimum)
            val upper = (bandwidth.upperHz ?: graphMaximum).coerceAtMost(minOf(graphMaximum, processingRate / 2.0))
            if (upper > lower) {
                bandwidthPaint.color = ColorUtils.setAlphaComponent(selected.color, 35)
                canvas.drawRect(x(lower), graphBounds.top, x(upper), graphBounds.bottom, bandwidthPaint)
                bandLinePaint.color = ColorUtils.setAlphaComponent(selected.color, 150)
                bandLinePaint.pathEffect = previewDash
                bandLinePaint.strokeWidth = density
                bandwidth.lowerHz?.takeIf { it in graphMinimum..graphMaximum }?.let {
                    canvas.drawLine(x(it), graphBounds.top, x(it), graphBounds.bottom, bandLinePaint)
                }
                bandwidth.upperHz?.takeIf { it in graphMinimum..graphMaximum }?.let {
                    canvas.drawLine(x(it), graphBounds.top, x(it), graphBounds.bottom, bandLinePaint)
                }
            }
        }
        fun draw(drawing: BandDrawing) {
            val selectedCurve = drawing.band.id == selectedId
            bandLinePaint.color = ColorUtils.setAlphaComponent(drawing.color, if (selectedCurve) 195 else 110)
            bandLinePaint.strokeWidth = (if (selectedCurve) 1.4f else .9f) * density
            bandLinePaint.pathEffect = if (drawing.band.enabled) null else previewDash
            if (drawing.band.enabled) {
                bandFillPaint.color = ColorUtils.setAlphaComponent(drawing.color, if (selectedCurve) 30 else 15)
                canvas.drawPath(drawing.fill, bandFillPaint)
            }
            canvas.drawPath(drawing.line, bandLinePaint)
        }
        if (!displayOptions.onlyTotalResponse) {
            overlayDrawings.forEach { if (it.band.id != selectedId) draw(it) }
            selected?.let(::draw)
        }
        bandLinePaint.pathEffect = null
        if (displayOptions.showBandwidthOverlay && selected != null && bandwidth != null) {
            val label = if (bandwidth.complete) {
                if (bandwidth.lowerHz != null && bandwidth.upperHz != null) "−3 dB 带宽" else "−3 dB 单侧通带"
            } else "无对应 −3 dB 区间"
            markerTextPaint.color = ColorUtils.setAlphaComponent(selected.color, 220)
            markerTextPaint.textSize = 9f * resources.displayMetrics.scaledDensity
            canvas.drawText(label, graphBounds.left + 5f * density,
                graphBounds.top + 5f * density - markerTextPaint.ascent(), markerTextPaint)
            markerTextPaint.color = foreground
        }
    }

    private fun rebuildBandPaths() {
        overlayPathsDirty = false
        val curves = overlayFrame?.bands.orEmpty()
        val next = HashMap<String, BandDrawing>(curves.size)
        overlayDrawings = curves.map { curve ->
            val previous = overlayDrawingCache[curve.band.id]
            // The worker already retains every unchanged band's immutable sample list. Carry
            // that reuse through to the UI: dragging one of 130 bands must not rebuild 260 paths.
            if (previous != null && previous.geometry == overlayGeometry && previous.points === curve.points) {
                val drawing = if (previous.band == curve.band) previous else previous.copy(band = curve.band)
                next[curve.band.id] = drawing
                return@map drawing
            }
            val outline = Path()
            val area = Path()
            var started = false
            var first = 0f
            var last = 0f
            fun closeArea() {
                if (!started) return
                area.lineTo(last, y(0.0))
                area.lineTo(first, y(0.0))
                area.close()
                started = false
            }
            curve.points.forEach { point ->
                if (!point.db.isFinite()) { closeArea(); return@forEach }
                val px = x(point.frequency)
                val py = y(point.db)
                if (!started) {
                    outline.moveTo(px, py)
                    area.moveTo(px, py)
                    first = px
                    started = true
                } else {
                    outline.lineTo(px, py)
                    area.lineTo(px, py)
                }
                last = px
            }
            closeArea()
            val hue = (curve.band.id.hashCode().ushr(1) % 360).toFloat()
            val color = Color.HSVToColor(floatArrayOf(hue, .72f, .82f))
            BandDrawing(curve.band, color, outline, area, curve.points, overlayGeometry)
                .also { next[curve.band.id] = it }
        }
        overlayDrawingCache = next
    }

    private fun drawEffectDisabled(canvas: Canvas) {
        val label = "参数均衡器已关闭"
        val padding = 7f * density
        val half = effectStatePaint.measureText(label) / 2f
        val metrics = effectStatePaint.fontMetrics
        val centerY = graphBounds.centerY()
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        val bounds = RectF(graphBounds.centerX() - half - padding, baseline + metrics.ascent - padding,
            graphBounds.centerX() + half + padding, baseline + metrics.descent + padding)
        canvas.drawRoundRect(bounds, 4f * density, 4f * density, markerBackgroundPaint)
        canvas.drawText(label, graphBounds.centerX(), baseline, effectStatePaint)
    }

    private fun rebuildAxes() {
        axesDirty = false
        overlayGeometry++
        pathsDirty = true
        overlayPathsDirty = true
        graphLogSpan = ln(graphMaximum / graphMinimum)
        val segments = ArrayList<GridLine>()
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val minimumGain = displayGainBounds?.start ?: -gainRange
        val maximumGain = displayGainBounds?.endInclusive ?: gainRange
        val labelWidth = maxOf(textPaint.measureText("${if (maximumGain > 0) "+" else ""}${PeqBandAdapter.number(maximumGain)}"),
            textPaint.measureText("−${PeqBandAdapter.number(-minimumGain)}"))
        graphBounds.set(labelWidth + 8f * density, textHeight / 2 + 5f * density,
            width - 15f * density, height - textHeight - 9f * density)
        if (graphBounds.width() <= 0 || graphBounds.height() <= 0) return
        val decades = (floor(log10(graphMinimum)).toInt()..ceil(log10(graphMaximum)).toInt())
            .map { 10.0.pow(it) }.filter { it in graphMinimum..graphMaximum }
        val frequencies = ((floor(log10(graphMinimum)).toInt()..ceil(log10(graphMaximum)).toInt())
            .flatMap { power -> listOf(1.0, 2.0, 5.0).map { it * 10.0.pow(power) } }
            .filter { it in graphMinimum..graphMaximum } + listOf(graphMinimum, graphMaximum)).distinct().sorted()
        textPaint.textAlign = Paint.Align.CENTER
        frequencies.forEach { frequency ->
            val position = x(frequency)
            val alpha = if (frequency in decades || frequency == graphMinimum || frequency == graphMaximum) 60 else 25
            segments.add(GridLine(position, graphBounds.top, position, graphBounds.bottom, alpha))
        }
        val xLabels = mutableListOf<AxisLabel>()
        val priorityFrequencies = (listOf(graphMinimum, graphMaximum) + decades + frequencies).distinct()
        val baseline = height - 4f * density - metrics.descent
        frequencyLabelBaseline = baseline
        priorityFrequencies.forEach { frequency ->
            val text = if (frequency >= 1000) "${PeqBandAdapter.number(frequency / 1000)}k" else PeqBandAdapter.number(frequency)
            val position = x(frequency)
            val halfWidth = textPaint.measureText(text) / 2
            val bounds = RectF(position - halfWidth, baseline + metrics.ascent, position + halfWidth, baseline + metrics.descent)
            if (xLabels.none { bounds.left < it.bounds.right + 7f * density && bounds.right + 7f * density > it.bounds.left })
                xLabels.add(AxisLabel(text, position, bounds))
        }
        renderedFrequencyLabels = xLabels.sortedBy { it.position }
        textPaint.textAlign = Paint.Align.RIGHT
        val intervals = (graphBounds.height() / (textHeight + 5f * density)).coerceAtLeast(2f)
        val minimumStep = (maximumGain - minimumGain) / intervals
        val gainStep = listOf(0.5, 1.0, 2.0, 2.5, 3.0, 5.0, 6.0, 10.0, 12.0, 20.0, 25.0, 50.0, 100.0)
            .firstOrNull { it >= minimumStep } ?: 100.0
        val gains = (ceil(minimumGain / gainStep).toInt()..floor(maximumGain / gainStep).toInt()).map { it * gainStep }
        (gains + listOf(minimumGain, maximumGain)).distinct().forEach { gain ->
            segments.add(GridLine(graphBounds.left, y(gain), graphBounds.right, y(gain), if (gain == 0.0) 90 else 35))
        }
        val yLabels = mutableListOf<AxisLabel>()
        (listOf(maximumGain, minimumGain, 0.0) + gains.sortedBy { abs(it) }).distinct()
            .filter { it in minimumGain..maximumGain }.forEach { gain ->
            val text = (if (gain > 0.0) "+" else "") + PeqBandAdapter.number(gain)
            val position = y(gain)
            val base = position - (metrics.ascent + metrics.descent) / 2
            val bounds = RectF(graphBounds.left - 5f * density - textPaint.measureText(text),
                base + metrics.ascent, graphBounds.left - 5f * density, base + metrics.descent)
            if (yLabels.none { bounds.top < it.bounds.bottom + 3f * density && bounds.bottom + 3f * density > it.bounds.top })
                yLabels.add(AxisLabel(text, base, bounds))
        }
        renderedGainLabels = yLabels.sortedBy { it.position }
        gridLines = segments
    }

    private fun rebuildPaths() {
        pathsDirty = false
        line.rewind()
        fill.rewind()
        val positions = responsePositions()
        var segmentStarted = false
        var firstPosition = 0f
        var lastPosition = 0f
        fun closeFillSegment() {
            if (!segmentStarted) return
            fill.lineTo(lastPosition, y(0.0))
            fill.lineTo(firstPosition, y(0.0))
            fill.close()
            segmentStarted = false
        }
        responseValues.forEachIndexed { index, db ->
            if (!db.isFinite()) { closeFillSegment(); return@forEachIndexed }
            val position = positions[index]
            val vertical = y(db)
            if (!segmentStarted) {
                line.moveTo(position, vertical)
                fill.moveTo(position, vertical)
                firstPosition = position
                segmentStarted = true
            } else {
                line.lineTo(position, vertical)
                fill.lineTo(position, vertical)
            }
            lastPosition = position
        }
        // Each finite segment closes at its own endpoints; gaps and frequencies past
        // Nyquist must never become diagonal response lines or triangular fill.
        closeFillSegment()
    }

    /** Reuse exact x coordinates across animation frames and retained adaptive frequencies. */
    private fun responsePositions(): FloatArray {
        if (pathGeometry == overlayGeometry && pathFrequencies === responseFrequencies) return pathPositions
        val sameGeometry = pathGeometry == overlayGeometry
        val next = if (sparePathPositions.size >= responseFrequencies.size) sparePathPositions else
            FloatArray(maxOf(responseFrequencies.size, pathPositions.size * 2))
        var previous = 0
        responseFrequencies.forEachIndexed { index, frequency ->
            if (sameGeometry) {
                while (previous < pathFrequencies.size && pathFrequencies[previous] < frequency) previous++
            }
            next[index] = if (sameGeometry && previous < pathFrequencies.size &&
                pathFrequencies[previous] == frequency) pathPositions[previous] else x(frequency)
        }
        sparePathPositions = pathPositions
        pathPositions = next
        pathFrequencies = responseFrequencies
        pathGeometry = overlayGeometry
        return next
    }

    private fun drawSelectedBand(canvas: Canvas) {
        // Presentation-only visibility must not end the editor's draft or marker timer.
        if (!isMarkerVisible || !displayOptions.showMarkerGuides) return
        val band = selectedBand() ?: return
        if (band.frequency !in graphMinimum..graphMaximum || band.frequency > processingRate / 2.0) return
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
                    add(MarkerTextRun(previewStatusText, if (markerIsListening(band))
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (axesDirty || !graphBounds.contains(event.x, event.y) || responseValues.none { it.isFinite() })
                    return false
                touchDownX = event.x
                touchDownY = event.y
                touchX = event.x
                waitingForTouchGesture = true
                postDelayed(beginTouchLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                // A pending inspection must not take vertical scrolling away from the page.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                if (touchSnapshot != null) {
                    scheduleTouchReading()
                    return true
                }
                if (!waitingForTouchGesture) return false
                val dx = abs(event.x - touchDownX)
                val dy = abs(event.y - touchDownY)
                if (dy > touchSlop && dy >= dx) {
                    finishTouchInspection()
                    return false
                }
                if (dx > touchSlop && dx > dy * 1.2f) beginTouchInspection()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val consumed = waitingForTouchGesture || touchSnapshot != null
                if (consumed) performClick()
                finishTouchInspection()
                return consumed
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                finishTouchInspection()
                return false
            }
        }
        return waitingForTouchGesture || touchSnapshot != null
    }

    override fun performClick(): Boolean = super.performClick()

    private fun beginTouchInspection() {
        if (!waitingForTouchGesture || axesDirty || graphBounds.width() <= 0f) return
        val plot = displayedPlotState
        // A just-edited bank may still be calculating. Inspect only the model already drawn.
        if (requestedState != null && (plot == null || displayedSampleRate != processingRate ||
                requestedPlotState?.let { sameFilters(plot, it) } != true)) return
        removeCallbacks(beginTouchLongPress)
        animator?.end()
        touchSnapshot = TouchSnapshot(plot, displayedSampleRate, selectedBand(),
            responseFrequencies.copyOf(), responseValues.copyOf())
        waitingForTouchGesture = false
        parent?.requestDisallowInterceptTouchEvent(true)
        calculateTouchReading()
    }

    private fun scheduleTouchReading() {
        if (touchUpdatePosted) return
        touchUpdatePosted = true
        postOnAnimation(updateTouchReading)
    }

    /** Compute one queried frequency per display frame, never a spectrum in onDraw. */
    private fun calculateTouchReading() {
        val snapshot = touchSnapshot ?: return
        val fraction = ((touchX - graphBounds.left) / graphBounds.width()).coerceIn(0f, 1f)
        val frequency = (graphMinimum * (graphMaximum / graphMinimum).pow(fraction.toDouble()))
            .coerceIn(graphMinimum, graphMaximum)
        val state = snapshot.state
        val gain = if (state != null && frequency <= snapshot.sampleRate / 2.0)
            runCatching { PeqResponse.responseDb(state, frequency, snapshot.sampleRate) }.getOrNull()
                ?.takeIf { it.isFinite() }
            else if (state == null) interpolatedPreview(snapshot, frequency) else null
        val lines = ArrayList<String>(3)
        lines += String.format(Locale.ROOT, "%.2f Hz", frequency)
        lines += if (state == null) {
            gain?.let { String.format(Locale.ROOT, "预览 %+.2f dB（插值）", it) } ?: "暂无有效预览值"
        } else gain?.let { String.format(Locale.ROOT, "合成 %+.2f dB", it) } ?: "超出有效模型频域"
        if (state != null && gain != null) snapshot.band?.let { band ->
            val single = state.copy(bands = listOf(band.copy(enabled = true)))
            val bandGain = runCatching { PeqResponse.responseDb(single, frequency, snapshot.sampleRate) }
                .getOrNull()?.takeIf { it.isFinite() }
            if (bandGain != null) lines += String.format(Locale.ROOT,
                if (band.enabled) "选中频段 %+.2f dB" else "关闭频段预览 %+.2f dB", bandGain)
        }
        touchReading = TouchReading(frequency, gain, lines)
        invalidate()
    }

    private fun interpolatedPreview(snapshot: TouchSnapshot, frequency: Double): Double? {
        val frequencies = snapshot.frequencies
        val values = snapshot.values
        if (frequencies.size < 2 || frequency < frequencies.first() || frequency > frequencies.last()) return null
        val exact = frequencies.binarySearch(frequency)
        if (exact >= 0) return values[exact].takeIf { it.isFinite() }
        val upper = -exact - 1
        val lower = upper - 1
        if (lower < 0 || upper >= frequencies.size || !values[lower].isFinite() || !values[upper].isFinite()) return null
        val fraction = ln(frequency / frequencies[lower]) / ln(frequencies[upper] / frequencies[lower])
        return (values[lower] + (values[upper] - values[lower]) * fraction).takeIf { it.isFinite() }
    }

    private fun finishTouchInspection() {
        removeCallbacks(beginTouchLongPress)
        removeCallbacks(updateTouchReading)
        touchUpdatePosted = false
        waitingForTouchGesture = false
        if (touchSnapshot != null) {
            touchSnapshot = null
            touchReading = null
            parent?.requestDisallowInterceptTouchEvent(false)
            invalidate()
        }
    }

    private fun drawTouchInspection(canvas: Canvas) {
        val reading = touchReading ?: return
        if (touchSnapshot == null || graphBounds.width() <= 0f || graphBounds.height() <= 0f) return
        val cursorX = x(reading.frequency).coerceIn(graphBounds.left, graphBounds.right)
        val cursorY = reading.gain?.let { y(it).coerceIn(graphBounds.top, graphBounds.bottom) }
        canvas.save()
        canvas.clipRect(graphBounds)
        canvas.drawLine(cursorX, graphBounds.top, cursorX, graphBounds.bottom, touchLinePaint)
        if (cursorY != null) {
            canvas.drawLine(graphBounds.left, cursorY, graphBounds.right, cursorY, touchLinePaint)
            canvas.drawCircle(cursorX, cursorY, 4f * density, markerBackgroundPaint)
            canvas.drawCircle(cursorX, cursorY, 3f * density, touchDotPaint)
        }
        val padding = 5f * density
        touchTextPaint.textSize = 10f * resources.displayMetrics.scaledDensity
        val naturalWidth = reading.lines.maxOf { touchTextPaint.measureText(it) }.coerceAtLeast(1f)
        val naturalHeight = touchTextPaint.fontSpacing * reading.lines.size
        touchTextPaint.textSize *= minOf(1f,
            (graphBounds.width() - padding * 2).coerceAtLeast(1f) / naturalWidth,
            (graphBounds.height() - padding * 2).coerceAtLeast(1f) / naturalHeight)
        val lineHeight = touchTextPaint.fontSpacing
        val boxWidth = reading.lines.maxOf { touchTextPaint.measureText(it) } + padding * 2
        val boxHeight = lineHeight * reading.lines.size + padding * 2
        val preferredLeft = if (cursorX + boxWidth + 9f * density <= graphBounds.right)
            cursorX + 9f * density else cursorX - boxWidth - 9f * density
        val left = preferredLeft.coerceIn(graphBounds.left, maxOf(graphBounds.left, graphBounds.right - boxWidth))
        val preferredTop = if ((cursorY ?: graphBounds.bottom) > graphBounds.centerY())
            graphBounds.top + 3f * density else graphBounds.bottom - boxHeight - 3f * density
        val top = preferredTop.coerceIn(graphBounds.top, maxOf(graphBounds.top, graphBounds.bottom - boxHeight))
        touchBox.set(left, top, left + boxWidth, top + boxHeight)
        canvas.drawRoundRect(touchBox, 4f * density, 4f * density, markerBackgroundPaint)
        touchTextPaint.textAlign = Paint.Align.LEFT
        val baseline = top + padding - touchTextPaint.ascent()
        reading.lines.forEachIndexed { index, label ->
            canvas.drawText(label, left + padding, baseline + lineHeight * index, touchTextPaint)
        }
        canvas.restore()
    }

    private fun y(db: Double): Float {
        displayGainBounds?.let { bounds ->
            return graphBounds.bottom - ((db - bounds.start) /
                (bounds.endInclusive - bounds.start) * graphBounds.height()).toFloat()
        }
        return graphBounds.centerY() -
            (db.coerceIn(-gainRange * 2, gainRange * 2) / (2 * gainRange) * graphBounds.height()).toFloat()
    }

    private fun x(frequency: Double): Float = when (frequency) {
        graphMinimum -> graphBounds.left
        graphMaximum -> graphBounds.right
        else -> graphBounds.left +
            (ln(frequency / graphMinimum) / graphLogSpan * graphBounds.width()).toFloat()
    }

    /** One active frame plus the newest pending frame; unchanged single-band curves are retained. */
    private class BandOverlayWorker(private val complete: (OverlayFrame) -> Unit) : AutoCloseable {
        private data class CurveKey(val type: PeqFilterType, val frequency: Double, val gain: Double,
            val q: Double, val rate: Double, val minimum: Double, val maximum: Double, val points: Int)
        private data class CachedCurve(val key: CurveKey, val points: List<PeqResponse.Point>)
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "peq-band-overlay").apply { isDaemon = true }
        }
        private val pending = AtomicReference<OverlayRequest?>()
        private val running = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private var curves = emptyMap<String, CachedCurve>()
        private var bandwidthKey: CurveKey? = null
        private var cachedBandwidth: PeqParameterResponse.Bandwidth? = null
        val isClosed: Boolean get() = closed.get()

        fun submit(request: OverlayRequest) {
            if (isClosed) return
            pending.set(request)
            schedule()
        }

        private fun schedule() {
            if (isClosed || !running.compareAndSet(false, true)) return
            try {
                executor.execute {
                    try {
                        while (!cancelled()) {
                            val request = pending.getAndSet(null) ?: break
                            val frame = compute(request)
                            if (frame != null && !cancelled()) complete(frame)
                        }
                    } finally {
                        running.set(false)
                        if (pending.get() != null && !isClosed) schedule()
                    }
                }
            } catch (_: RejectedExecutionException) {
                running.set(false)
            }
        }

        private fun cancelled() = isClosed || Thread.currentThread().isInterrupted

        private fun compute(request: OverlayRequest): OverlayFrame? {
            val config = request.configuration
            val nextCurves = HashMap<String, CachedCurve>()
            val result = ArrayList<BandCurve>()
            var bandwidth: PeqParameterResponse.Bandwidth? = null
            for (original in config.bands) {
                if (cancelled()) return null
                if (!original.enabled && original.id != config.selectedId) continue
                val band = PeqProtocol.quantized(original)
                if (band.frequency <= 0.0 || band.frequency > config.sampleRate / 2.0) continue
                val key = CurveKey(band.type, band.frequency, band.gain, band.q, config.sampleRate,
                    config.minimum, config.maximum, config.points)
                val previous = curves[band.id]?.takeIf { it.key == key }
                val cached = previous ?: CachedCurve(key, PeqResponse.adaptiveCurve(
                    PeqState(bands = listOf(band.copy(enabled = true)),
                        graphMinFrequency = config.minimum, graphMaxFrequency = config.maximum),
                    points = config.points, sampleRate = config.sampleRate,
                    frequencyMin = config.minimum, frequencyMax = config.maximum))
                nextCurves[band.id] = cached
                result.add(BandCurve(original, cached.points))
                if (band.id == config.selectedId) {
                    if (bandwidthKey != key) {
                        cachedBandwidth = PeqParameterResponse.bandwidth(band, config.sampleRate)
                        bandwidthKey = key
                    }
                    bandwidth = cachedBandwidth
                }
            }
            if (cancelled()) return null
            curves = nextCurves
            return OverlayFrame(request, result, bandwidth)
        }

        override fun close() {
            closed.set(true)
            pending.set(null)
            executor.shutdownNow()
        }
    }

    companion object { private const val BASE_POINTS = 1024 }
}
