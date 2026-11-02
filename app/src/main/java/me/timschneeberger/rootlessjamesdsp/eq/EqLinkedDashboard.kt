package me.timschneeberger.rootlessjamesdsp.eq

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.peq.PeqStyledStaticChart
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot
import kotlin.math.min
import kotlin.math.roundToInt

/** The fifteen-band EQ's independent model/visualiser header, with no audio parameter writes. */
internal class EqLinkedDashboard(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val muted = ColorUtils.setAlphaComponent(ink, 165)
    private val preferences = context.applicationContext.getSharedPreferences("eq_linked_display", Context.MODE_PRIVATE)
    private val spectrumPreferences = SpectrumDisplayPreferences(context, "eq")
    private val leftTitle = title("全局设定目标")
    private val rightTitle = title("当前点邻域目标")
    private val locate = title("定位").apply {
        setTextColor(primary); gravity = Gravity.END or Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true
        setPadding(dp(5), 0, dp(3), 0)
    }
    private val professional = PeqStyledStaticChart(context)
    private val illustration = PeqStyledStaticChart(context)
    private val plots = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val staticHeading = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val liveHeading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val liveTitle = title("真实输入与多模态后频谱")
    private val spectrumSettings = title("频谱设置").apply {
        setTextColor(primary); gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(dp(5), 0, dp(8), 0)
        isClickable = true; isFocusable = true
        background = null; foreground = null
        contentDescription = "打开均衡器频谱分析器显示设置"
        setOnClickListener { SpectrumAppearanceDialog.show(context, "eq") }
    }
    private val collapse = title("折叠").apply {
        setTextColor(primary); gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(dp(5), 0, dp(3), 0)
        isClickable = true; isFocusable = true
    }
    private val realtime = EqLinkedRealtimeChart(context)
    private val status = TextView(context).apply {
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, min(9f * resources.displayMetrics.scaledDensity, 10f * d))
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(3), 0, dp(3), 0)
        includeFontPadding = false
        text = "等待多模态前后只读采样"
    }
    private var initialized = false
    private var closed = false
    private var attached = false
    private var registered = false
    private var levels = DoubleArray(15)
    private var filter = 0
    private var interpolation = 0
    private var selectedBand = 8
    private var rate = 48000.0
    private var enabled = false
    private var locatedBand = -1
    private var showStatic = false
    private var showRealtime = false
    private var realtimeExpanded = true
    private var runtimeState: EqRuntimeMonitor.State = EqRuntimeMonitor.State.Starting
    private var lastRealtimeVisibility: Boolean? = null
    private var worker: EqLinkedPlots.Worker? = null
    private var lifecycle = 0L
    private var revision = 0L
    private var shownRevision = -1L
    private data class ModelKey(val levels: List<Double>, val filter: Int, val interpolation: Int,
        val selected: Int, val rate: Double, val enabled: Boolean, val columns: Int)
    private var requestedKey: ModelKey? = null
    private var pending: EqLinkedPlots.Request? = null
    private var submissionPosted = false
    val isRealtimeVisible: Boolean get() = displayActive() && showRealtime && realtimeExpanded
    var onRealtimeVisibilityChanged: ((Boolean) -> Unit)? = null
    var onRequestPermission: (() -> Unit)? = null
    var onModelResponse: ((LiveprogStaticPlot) -> Unit)? = null
    private val displayListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!closed) refreshAppearance()
    }
    private val spectrumListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!closed && (key == null || key.startsWith("eq.")))
            realtime.setDisplayOptions(spectrumPreferences.load())
    }
    private val submitFrame = Runnable {
        submissionPosted = false
        if (canCompute()) pending?.let { request ->
            pending = null
            val token = lifecycle
            val current = worker ?: EqLinkedPlots.Worker { result ->
                postOnAnimation {
                    val source = result.request
                    if (token != lifecycle || !canCompute() || source.filter != filter ||
                        source.interpolation != interpolation || source.selectedBand != focusBand() ||
                        source.rate != rate || source.enabled != enabled || source.revision <= shownRevision)
                        return@postOnAnimation
                    // Same-domain completed frames remain visible while the newest slider edit waits.
                    shownRevision = source.revision
                    professional.setPlot(result.pair.professional)
                    illustration.setPlot(result.pair.illustration)
                    realtime.setModelResponse(result.pair.professional)
                    onModelResponse?.invoke(result.pair.professional)
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
        locate.setOnClickListener {
            locatedBand = if (locatedBand in 0..14) -1 else focusBand()
            preferences.edit().putInt("located_band", locatedBand).apply()
            syncLocate()
            requestModel(force = true)
        }
        collapse.setOnClickListener {
            realtimeExpanded = !realtimeExpanded
            preferences.edit().putBoolean("realtime_expanded", realtimeExpanded).apply()
            applySections()
        }
        status.setOnClickListener {
            if (runtimeState == EqRuntimeMonitor.State.PermissionRequired) onRequestPermission?.invoke()
        }
        initialized = true
        refreshAppearance()
    }

    private fun dp(value: Int): Int = (value * d).roundToInt()
    private fun title(value: String) = TextView(context).apply {
        text = value
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, min(11f * resources.displayMetrics.scaledDensity, 12f * d))
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
        setPadding(dp(3), 0, dp(2), 0)
    }
    private fun setTextIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }
    private fun focusBand(): Int = locatedBand.takeIf { it in 0..14 } ?: selectedBand.takeIf { it in 0..14 } ?: 8
    private fun syncLocate() {
        setTextIfChanged(locate, if (locatedBand in 0..14) "取消定位" else "定位")
        val description = if (locatedBand in 0..14) "取消两张静图的共同定位" else "把两张静图定位到当前频段"
        if (locate.contentDescription != description) locate.contentDescription = description
    }

    fun setState(levels: DoubleArray, filter: Int, interpolation: Int, selectedBand: Int,
        rate: Double, enabled: Boolean) {
        if (closed) return
        this.levels = levels.copyOf()
        this.filter = filter
        this.interpolation = interpolation
        this.selectedBand = selectedBand.takeIf { it in 0..14 } ?: 8
        this.rate = rate.takeIf { it.isFinite() && it in 8000.0..768000.0 } ?: 48000.0
        this.enabled = enabled
        syncLocate()
        requestModel()
    }

    fun setSample(sample: EqPcmTelemetry.Snapshot) {
        if (!closed && showRealtime && realtimeExpanded) realtime.setSample(sample)
    }

    fun setRuntimeState(state: EqRuntimeMonitor.State) {
        if (closed) return
        runtimeState = state
        realtime.setRuntimeState(state)
        setTextIfChanged(status, state.label)
        if (status.contentDescription != state.label) status.contentDescription = state.label
        val permission = state == EqRuntimeMonitor.State.PermissionRequired
        status.isClickable = permission; status.isFocusable = permission
        status.setTextColor(if (permission) primary else muted)
    }

    /** Only analyser/display preferences are read here, never the fifteen EQ audio controls. */
    fun refreshAppearance() {
        if (!initialized || closed) return
        val values = preferences.all
        fun int(key: String, fallback: Int): Int = (values[key] as? Number)?.toInt() ?: fallback
        fun boolean(key: String, fallback: Boolean): Boolean = values[key] as? Boolean ?: fallback
        val nextStatic = boolean("show_static", false)
        val nextRealtime = boolean("show_realtime", false)
        val nextExpanded = boolean("realtime_expanded", true)
        val nextLocated = int("located_band", -1).takeIf { it in 0..14 } ?: -1
        val layoutChanged = nextStatic != showStatic || nextRealtime != showRealtime || nextExpanded != realtimeExpanded
        val focusChanged = nextLocated != locatedBand
        showStatic = nextStatic; showRealtime = nextRealtime; realtimeExpanded = nextExpanded; locatedBand = nextLocated
        val staticStyle = int("static_style", 0).coerceIn(0, 4)
        professional.setChartStyle(staticStyle); illustration.setChartStyle(staticStyle)
        realtime.setChartStyle(int("realtime_style", 0).coerceIn(0, 4))
        realtime.setDisplayOptions(spectrumPreferences.load())
        syncLocate()
        if (layoutChanged || !isAttachedToWindow) applySections() else syncActive()
        if (focusChanged) requestModel(force = true)
    }

    private fun applySections() {
        staticHeading.visibility = if (showStatic) VISIBLE else GONE
        plots.visibility = if (showStatic) VISIBLE else GONE
        liveHeading.visibility = if (showRealtime) VISIBLE else GONE
        val showBody = showRealtime && realtimeExpanded
        spectrumSettings.visibility = if (showBody) VISIBLE else GONE
        realtime.visibility = if (showBody) VISIBLE else GONE
        status.visibility = if (showBody) VISIBLE else GONE
        setTextIfChanged(collapse, if (realtimeExpanded) "折叠" else "展开")
        val staticMinimum = if (showStatic) 24 + 108 else 0
        val liveMinimum = if (showRealtime) 24 + (if (realtimeExpanded) 110 + 18 else 0) else 0
        minimumHeight = dp(staticMinimum + liveMinimum)
        visibility = if (showStatic || showRealtime) VISIBLE else GONE
        syncActive()
        requestLayout()
    }

    private fun displayActive(): Boolean = initialized && !closed && attached && isAttachedToWindow && isShown &&
        windowVisibility == VISIBLE && hasWindowFocus()
    // Actual PCM charts do not consume the static response model.
    private fun needsModel(): Boolean = showStatic
    private fun canCompute(): Boolean = displayActive() && needsModel()
    private fun syncActive() {
        val active = canCompute()
        val staticActive = displayActive() && showStatic
        professional.setActive(staticActive); illustration.setActive(staticActive)
        realtime.setActive(isRealtimeVisible)
        if (active) requestModel() else stopModel()
        notifyRealtimeVisibility()
    }
    private fun notifyRealtimeVisibility() {
        val visible = isRealtimeVisible
        if (lastRealtimeVisibility != visible) {
            lastRealtimeVisibility = visible
            onRealtimeVisibilityChanged?.invoke(visible)
        }
    }

    private fun requestModel(force: Boolean = false) {
        if (!initialized || closed || !needsModel()) return
        val columns = (professional.width.takeIf { it > 0 } ?: dp(180)).coerceIn(128, 1536)
        val key = ModelKey(levels.toList(), filter, interpolation, focusBand(), rate, enabled, columns)
        if (!force && requestedKey == key) {
            if (canCompute() && pending != null && !submissionPosted) {
                submissionPosted = true; postOnAnimation(submitFrame)
            }
            return
        }
        requestedKey = key
        pending = EqLinkedPlots.Request(levels.copyOf(), filter, interpolation, key.selected,
            rate, ++revision, columns, enabled)
        if (canCompute() && !submissionPosted) {
            submissionPosted = true
            postOnAnimation(submitFrame)
        }
    }
    private fun stopModel() {
        lifecycle++
        removeCallbacks(submitFrame); submissionPosted = false
        worker?.close(); worker = null
        requestedKey = null
    }
    private fun registerPreferences() {
        if (registered || closed) return
        preferences.registerOnSharedPreferenceChangeListener(displayListener)
        spectrumPreferences.register(spectrumListener)
        registered = true
    }
    private fun unregisterPreferences() {
        if (!registered) return
        preferences.unregisterOnSharedPreferenceChangeListener(displayListener)
        spectrumPreferences.unregister(spectrumListener)
        registered = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        registerPreferences(); refreshAppearance(); syncActive()
    }
    override fun onDetachedFromWindow() {
        attached = false
        unregisterPreferences(); stopModel(); realtime.setActive(false)
        notifyRealtimeVisibility()
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (initialized) syncActive()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (initialized) syncActive()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (initialized) syncActive()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (initialized && w != oldw) requestModel(force = true)
    }

    fun close() {
        if (closed) return
        closed = true
        unregisterPreferences(); stopModel(); pending = null
        professional.setActive(false); illustration.setActive(false)
        realtime.setModelResponse(null)
        realtime.close()
        notifyRealtimeVisibility()
        onRealtimeVisibilityChanged = null; onRequestPermission = null; onModelResponse = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val liveBody = showRealtime && realtimeExpanded
        val fixed = dp((if (showStatic) 24 else 0) + (if (showRealtime) 24 else 0) + (if (liveBody) 18 else 0)) +
            paddingTop + paddingBottom
        val staticDesired = if (showStatic) dp(144) else 0
        val liveDesired = if (liveBody) dp(146) else 0
        val staticMinimum = if (showStatic) dp(108) else 0
        val liveMinimum = if (liveBody) dp(110) else 0
        val natural = fixed + staticDesired + liveDesired
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) natural else MeasureSpec.getSize(heightMeasureSpec)
        val available = min(natural, limit).coerceAtLeast(fixed + staticMinimum + liveMinimum)
        val budget = available - fixed
        val staticHeight = when {
            !showStatic -> 0
            !liveBody -> budget.coerceIn(staticMinimum, staticDesired)
            else -> (budget * 144f / 290f).roundToInt().coerceIn(staticMinimum, staticDesired)
        }
        plots.layoutParams.height = staticHeight
        realtime.layoutParams.height = if (liveBody) (budget - staticHeight).coerceIn(liveMinimum, liveDesired) else 0
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
