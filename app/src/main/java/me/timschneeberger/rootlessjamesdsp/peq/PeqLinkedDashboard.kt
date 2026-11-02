package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPlotStyle
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot
import me.timschneeberger.rootlessjamesdsp.eq.SpectrumDisplayPreferences
import me.timschneeberger.rootlessjamesdsp.eq.SpectrumAppearanceDialog
import kotlin.math.min
import kotlin.math.roundToInt

/** Independent display surface; frequency cards, editing, audition and bank locks stay untouched. */
class PeqLinkedDashboard @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    private val d = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val displayPreferences = context.getSharedPreferences("peq_linked_display", Context.MODE_PRIVATE)
    private val leftTitle = title("总响应与当前段")
    private val rightTitle = title("当前段作用")
    private val locate = title("定位").apply {
        setTextColor(primary); gravity = Gravity.END or Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true
        setPadding(dp(5), 0, dp(3), 0)
        contentDescription = "定位两张静图到当前频段"
    }
    private val professional = PeqStyledStaticChart(context)
    private val illustration = PeqStyledStaticChart(context)
    private val plots = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val staticHeading = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val liveHeading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val liveTitle = title("真实输入与 PEQ 后频谱")
    private val spectrumSettings = title("频谱设置").apply {
        setTextColor(primary); gravity = Gravity.END or Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true
        setPadding(dp(5), 0, dp(8), 0)
        background = null; foreground = null
        contentDescription = "打开PEQ频谱分析器显示设置"
        setOnClickListener { SpectrumAppearanceDialog.show(context, "peq") }
    }
    private val collapse = title("折叠").apply {
        setTextColor(primary); gravity = Gravity.END or Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true
        setPadding(dp(5), 0, dp(3), 0)
    }
    private val realtime = PeqLinkedRealtimeChart(context)
    private val spectrumPreferences = SpectrumDisplayPreferences(context, "peq")
    private var spectrumPreferencesRegistered = false
    private val spectrumPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        realtime.setDisplayOptions(spectrumPreferences.load())
    }
    private val status = TextView(context).apply {
        setTextColor(ColorUtils.setAlphaComponent(ink, 165))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, min(9f * resources.displayMetrics.scaledDensity, 10f * d))
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        text = "等待 PEQ 只读采样"
        setPadding(dp(3), 0, dp(3), 0)
        includeFontPadding = false
    }
    private var initialized = false
    private var attached = false
    private var state = PeqState(emptyList())
    private var selectedId: String? = null
    private var previewBand: PeqBand? = null
    private var rate = PeqResponse.DEFAULT_SAMPLE_RATE
    private var effectEnabled = false
    private var locatedId: String? = displayPreferences.getString("located_band", null)
    private var closed = false
    private var worker: PeqLinkedPlots.Worker? = null
    private var lifecycle = 0L
    private var revision = 0L
    private var shownRevision = -1L
    private data class ModelKey(val state: PeqState, val band: PeqBand?, val rate: Double,
        val enabled: Boolean, val columns: Int)
    private var requestedKey: ModelKey? = null
    private var pending: PeqLinkedPlots.Request? = null
    private var submissionPosted = false
    private var realtimeExpanded = displayPreferences.getBoolean("realtime_expanded", true)
    private var showLinkedStatic = false
    private var showRealtime = false
    val isRealtimeExpanded: Boolean get() = realtimeExpanded
    val isRealtimeVisible: Boolean get() = displayActive() && showRealtime && realtimeExpanded
    private var lastRealtimeVisibility: Boolean? = null
    /** Optional display callback; collapsing never changes the PEQ enable or audition state. */
    var onRealtimeVisibilityChanged: ((Boolean) -> Unit)? = null
    private val submitFrame = Runnable {
        submissionPosted = false
        if (canCompute()) pending?.let { request ->
            pending = null
            val token = lifecycle
            val current = worker ?: PeqLinkedPlots.Worker { result ->
                postOnAnimation {
                    val requestBand = result.request.band?.id
                    val currentBand = focusBand()
                    if (token != lifecycle || !canCompute() || result.request.rate != rate ||
                        requestBand != currentBand?.id || result.request.enabled != effectEnabled ||
                        result.request.band?.enabled != currentBand?.enabled ||
                        result.request.band?.type != currentBand?.type || result.request.revision <= shownRevision ||
                        result.request.state.graphMinFrequency != state.graphMinFrequency ||
                        result.request.state.graphMaxFrequency != state.graphMaxFrequency) return@postOnAnimation
                    shownRevision = result.request.revision
                    // A completed same-domain frame is useful while a newer edit is still running.
                    professional.setPlot(result.pair.professional)
                    illustration.setPlot(result.pair.illustration)
                    realtime.setModelResponse(result.pair.professional)
                    setTextIfChanged(leftTitle, result.pair.professional.title)
                    setTextIfChanged(rightTitle, result.pair.illustration.title)
                }
            }.also { worker = it }
            current.submit(request)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(5), 0, dp(5), 0)
        minimumHeight = dp(if (realtimeExpanded) 284 else 156)
        val firstTitle = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(leftTitle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        val secondTitle = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(rightTitle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(locate, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        staticHeading.addView(firstTitle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        staticHeading.addView(secondTitle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(staticHeading, LayoutParams(LayoutParams.MATCH_PARENT, dp(24)))
        plots.addView(professional, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(5) })
        plots.addView(illustration, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(5) })
        addView(plots, LayoutParams(LayoutParams.MATCH_PARENT, dp(144)))
        liveHeading.addView(liveTitle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        liveHeading.addView(spectrumSettings, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        liveHeading.addView(collapse, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(liveHeading, LayoutParams(LayoutParams.MATCH_PARENT, dp(24)))
        addView(realtime, LayoutParams(LayoutParams.MATCH_PARENT, dp(146)))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, dp(18)))
        realtime.visibility = if (realtimeExpanded) VISIBLE else GONE
        status.visibility = if (realtimeExpanded) VISIBLE else GONE
        spectrumSettings.visibility = if (realtimeExpanded) VISIBLE else GONE
        collapse.text = if (realtimeExpanded) "折叠" else "展开"
        locate.setOnClickListener {
            locatedId = if (locatedId == null) focusBand()?.id else null
            displayPreferences.edit().putString("located_band", locatedId).apply()
            syncLocate()
            requestModel(force = true)
        }
        collapse.setOnClickListener {
            realtimeExpanded = !realtimeExpanded
            collapse.text = if (realtimeExpanded) "折叠" else "展开"
            displayPreferences.edit().putBoolean("realtime_expanded", realtimeExpanded).apply()
            applySectionVisibility()
        }
        initialized = true
        realtime.setDisplayOptions(spectrumPreferences.load())
        applySectionVisibility()
    }

    /** Settings hide whole rows, while preserving the static lock and in-page fold choice. */
    fun setChartStyles(staticStyle: Int, realtimeStyle: Int) {
        // Render the existing model and captured music in place. Style changes do not
        // restart the worker, unlock the current band or reset the live capture.
        professional.setChartStyle(PeqChartStyles.staticStyle(staticStyle))
        illustration.setChartStyle(PeqChartStyles.staticStyle(staticStyle))
        realtime.setChartStyle(PeqChartStyles.realtimeStyle(realtimeStyle))
    }

    fun setChartVisibility(staticVisible: Boolean, realtimeVisible: Boolean) {
        if (closed || showLinkedStatic == staticVisible && showRealtime == realtimeVisible) return
        val stopCapture = showRealtime && !realtimeVisible
        showLinkedStatic = staticVisible
        showRealtime = realtimeVisible
        if (stopCapture) clearCapture("实时图已关闭")
        applySectionVisibility()
    }

    private fun applySectionVisibility() {
        staticHeading.visibility = if (showLinkedStatic) VISIBLE else GONE
        plots.visibility = if (showLinkedStatic) VISIBLE else GONE
        liveHeading.visibility = if (showRealtime) VISIBLE else GONE
        val showBody = showRealtime && realtimeExpanded
        spectrumSettings.visibility = if (showBody) VISIBLE else GONE
        realtime.visibility = if (showBody) VISIBLE else GONE
        status.visibility = if (showBody) VISIBLE else GONE
        val staticMinimum = if (showLinkedStatic) 24 + 108 else 0
        val realtimeMinimum = if (showRealtime) 24 + (if (realtimeExpanded) 110 + 18 else 0) else 0
        minimumHeight = dp(staticMinimum + realtimeMinimum)
        visibility = if (showLinkedStatic || showRealtime) VISIBLE else GONE
        syncVisibility()
        notifyRealtimeVisibility()
        requestLayout()
    }

    private fun dp(value: Int): Int = (value * d).roundToInt()
    private fun setTextIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }
    private fun title(value: String) = TextView(context).apply {
        text = value
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, min(11f * resources.displayMetrics.scaledDensity, 12f * d))
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        setPadding(dp(3), 0, dp(2), 0)
    }

    fun setState(state: PeqState, selectedId: String?, rate: Double, effectEnabled: Boolean,
        previewBand: PeqBand? = null) {
        this.state = state
        this.selectedId = selectedId
        this.previewBand = previewBand
        this.rate = PeqFrequencyLimits.sampleRate(rate)
        this.effectEnabled = effectEnabled
        if (locatedId != null && state.bands.none { it.id == locatedId }) {
            locatedId = null
            displayPreferences.edit().remove("located_band").apply()
        }
        syncLocate()
        requestModel()
    }

    /** Session selection and lease ownership belong to the Fragment's read-only monitor. */
    fun setCaptureSample(snapshot: PeqPcmTelemetry.Snapshot) { if (!closed) realtime.setSample(snapshot) }

    /** Updating a waiting/transition label never discards a previously valid music window. */
    fun setCaptureStatus(message: String) {
        setTextIfChanged(status, message)
        if (status.contentDescription != message) status.contentDescription = message
        realtime.setStatus(message)
    }

    fun clearCapture(message: String = "等待 PEQ 只读采样") {
        realtime.clear(message)
        setCaptureStatus(message)
    }

    /** A preset replacement is a new bank even when its bands reuse previous identifiers. */
    fun resetBank() {
        stopModel()
        realtime.setModelResponse(null)
        pending = null
        shownRevision = -1L
        state = PeqState(emptyList())
        selectedId = null; previewBand = null; locatedId = null
        displayPreferences.edit().remove("located_band").apply()
        professional.setPlot(LiveprogStaticPlot("总响应与当前段", "等待当前银行", LiveprogPlotStyle.TEXT))
        illustration.setPlot(LiveprogStaticPlot("当前段作用", "等待选择频段", LiveprogPlotStyle.TEXT))
        setTextIfChanged(leftTitle, "总响应与当前段")
        setTextIfChanged(rightTitle, "当前段作用")
        syncLocate()
        clearCapture("银行已切换，等待新的只读窗口")
    }

    /** Explicit final disposal, separate from a reversible detach/reattach. */
    fun close() {
        closed = true
        if (spectrumPreferencesRegistered) spectrumPreferences.unregister(spectrumPreferenceListener)
        spectrumPreferencesRegistered = false
        stopModel(); pending = null
        realtime.setModelResponse(null)
        professional.setActive(false); illustration.setActive(false)
        realtime.setActive(false)
        realtime.clear("只读采样已暂停")
        notifyRealtimeVisibility()
        onRealtimeVisibilityChanged = null
    }

    private fun focusBand(): PeqBand? {
        val id = locatedId ?: selectedId
        val committed = state.bands.firstOrNull { it.id == id } ?: state.bands.firstOrNull()
        return previewBand?.takeIf { it.id == committed?.id } ?: committed
    }

    private fun syncLocate() {
        setTextIfChanged(locate, if (locatedId == null) "定位" else "取消定位")
        val description = if (locatedId == null) "定位两张静图到当前频段" else "取消两张静图的共同定位"
        if (locate.contentDescription != description) locate.contentDescription = description
        val enabled = focusBand() != null
        if (locate.isEnabled != enabled) locate.isEnabled = enabled
        val opacity = if (enabled) 1f else .45f
        if (locate.alpha != opacity) locate.alpha = opacity
    }

    private fun requestModel(force: Boolean = false) {
        if (!initialized || closed || !needsModel()) return
        val columns = (professional.width.takeIf { it > 0 } ?: dp(180)).coerceIn(128, 1536)
        val key = ModelKey(state, focusBand(), rate, effectEnabled, columns)
        if (!force && requestedKey == key) return
        requestedKey = key
        pending = PeqLinkedPlots.Request(state, key.band, rate, effectEnabled, ++revision, columns)
        if (canCompute() && !submissionPosted) {
            submissionPosted = true
            postOnAnimation(submitFrame)
        }
    }

    private fun displayActive(): Boolean = initialized && !closed && attached && isAttachedToWindow && isShown &&
        windowVisibility == VISIBLE && hasWindowFocus()
    // Actual PCM charts do not consume the static response model.
    private fun needsModel(): Boolean = showLinkedStatic
    private fun canCompute(): Boolean = displayActive() && needsModel()
    private fun syncVisibility() {
        val active = canCompute()
        val staticActive = displayActive() && showLinkedStatic
        professional.setActive(staticActive); illustration.setActive(staticActive)
        realtime.setActive(displayActive() && showRealtime && realtimeExpanded)
        if (active) requestModel(force = true) else stopModel()
        notifyRealtimeVisibility()
    }
    private fun notifyRealtimeVisibility() {
        val visible = isRealtimeVisible
        if (lastRealtimeVisibility != visible) {
            lastRealtimeVisibility = visible
            onRealtimeVisibilityChanged?.invoke(visible)
        }
    }
    private fun stopModel() {
        lifecycle++
        removeCallbacks(submitFrame); submissionPosted = false
        worker?.close(); worker = null
        requestedKey = null
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (!closed && !spectrumPreferencesRegistered) {
            spectrumPreferences.register(spectrumPreferenceListener)
            spectrumPreferencesRegistered = true
        }
        realtime.setDisplayOptions(spectrumPreferences.load())
        syncVisibility()
    }
    override fun onDetachedFromWindow() {
        attached = false
        if (spectrumPreferencesRegistered) spectrumPreferences.unregister(spectrumPreferenceListener)
        spectrumPreferencesRegistered = false
        stopModel(); realtime.setActive(false)
        notifyRealtimeVisibility()
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (initialized) syncVisibility()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (initialized) syncVisibility()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (initialized) syncVisibility()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (initialized && w != oldw) post { requestModel(force = true) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Hidden sections consume no height. Reduce visible plots, never their fonts.
        val liveBody = showRealtime && realtimeExpanded
        val headingHeight = (if (showLinkedStatic) 24 else 0) + (if (showRealtime) 24 else 0)
        val fixed = dp(headingHeight + (if (liveBody) 18 else 0)) + paddingTop + paddingBottom
        val staticDesired = if (showLinkedStatic) dp(144) else 0
        val liveDesired = if (liveBody) dp(146) else 0
        val staticMinimum = if (showLinkedStatic) dp(108) else 0
        val liveMinimum = if (liveBody) dp(110) else 0
        val natural = fixed + staticDesired + liveDesired
        val limit = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> natural
            else -> MeasureSpec.getSize(heightMeasureSpec)
        }
        val available = min(natural, limit).coerceAtLeast(fixed + staticMinimum + liveMinimum)
        val plotBudget = available - fixed
        val staticHeight = when {
            !showLinkedStatic -> 0
            !liveBody -> plotBudget.coerceIn(staticMinimum, staticDesired)
            else -> (plotBudget * 144f / 290f).roundToInt().coerceIn(staticMinimum, staticDesired)
        }
        plots.layoutParams.height = staticHeight
        realtime.layoutParams.height = if (liveBody) (plotBudget - staticHeight).coerceIn(liveMinimum, liveDesired) else 0
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
