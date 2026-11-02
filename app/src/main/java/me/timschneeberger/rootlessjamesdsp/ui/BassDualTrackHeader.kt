package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fusion.BassEnergyMonitor
import java.util.Locale
import kotlin.math.*

/** Shared presentation only; capture ownership stays with the page lifecycle coordinator. */
class BassDualTrackHeader(
    context: Context,
    private val dynamic: Boolean,
    realtime: Boolean,
    pinned: Boolean,
    private val onRealtimeChanged: (Boolean) -> Unit,
    private val onPinChanged: (Boolean) -> Unit
) : LinearLayout(context) {
    private val foregroundColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val tabs = ArrayList<TextView>(2)
    private lateinit var workspace: ChartWorkspacePager
    private val power = ImageButton(context)
    private val pin = ImageButton(context)
    private val staticContainer = LinearLayout(context)
    private val liveContainer = LinearLayout(context)
    private val staticChart = BassStaticResponseView(context, dynamic)
    private val detailsToggle = TextView(context)
    private val details = BassParameterPreview(context)
    private val energyChart = BassEnergyChart(context)
    private val numbers = TextView(context)
    private val resolution = TextView(context)
    private val status = TextView(context)
    private var realtimeState = realtime
    private var pinnedState = pinned
    private var effectEnabled = false
    private var detailsExpanded = false
    private var lastStateLabel: String? = null
    private var liveNumbers = "相对能量 — dB"
    private var cursorNumbers: String? = null
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(8))
        setBackgroundColor(surface)
        power.apply {
            setImageResource(R.drawable.ic_twotone_power_settings_24dp)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener {
                val next = !realtimeState
                if (next && !workspace.select(1)) return@setOnClickListener
                onRealtimeChanged(next)
            }
        }
        pin.apply {
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { pinnedState = !pinnedState; updatePin(); onPinChanged(pinnedState) }
        }
        val pages = mutableListOf<Pair<String, View>>("参数模型" to staticChart, "实时趋势" to energyChart)
        if (dynamic) pages += "跟随包络" to details
        workspace = ChartWorkspacePager(context, if (dynamic) "adaptive_bass" else "bass", pages, power, pin) { page ->
            val next = page == 1
            if (next != realtimeState) onRealtimeChanged(next)
        }
        workspace.select(if (realtimeState) 1 else 0)
        addView(workspace, LayoutParams(-1, 0, 1f))
        numbers.apply { textSize = 10.5f; maxLines = 1; setTextColor(foregroundColor); setTypeface(null, Typeface.BOLD) }
        resolution.apply { textSize = 9.5f; maxLines = 1; setTextColor(ColorUtils.setAlphaComponent(foregroundColor, 0xb4)) }
        status.apply { textSize = 9.5f; maxLines = 1; setTextColor(ColorUtils.setAlphaComponent(foregroundColor, 0xb4)) }
        addView(numbers, LayoutParams(-1, -2)); addView(resolution, LayoutParams(-1, -2)); addView(status, LayoutParams(-1, -2))
        energyChart.onCursorChanged = { value -> cursorNumbers = value; numbers.text = value ?: liveNumbers }
        updatePin()
        updateMode()
        showWaiting()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
            ChartWorkspacePager.measuredHeight(this, heightMeasureSpec), MeasureSpec.EXACTLY))
    }

    fun setRealtime(enabled: Boolean) {
        if (realtimeState == enabled) return
        realtimeState = enabled
        staticChart.cancelPendingInputEvents()
        details.cancelPendingInputEvents()
        energyChart.clear()
        lastStateLabel = null
        updateMode()
        showWaiting()
    }

    fun setEffectEnabled(enabled: Boolean) {
        effectEnabled = enabled
        staticChart.setEffectEnabled(enabled)
        // The meter observes system-mix audio, including when this effect is off.
    }

    fun setViperConfiguration(values: Map<String, *>) = staticChart.setViperConfiguration(values)

    fun setDynamicConfiguration(packet: IntArray) {
        val normalized = normalizedDynamicBassPacket(packet)
        staticChart.setDynamicConfiguration(normalized)
        details.setConfiguration(normalized[1].toDouble(), normalized[2].toDouble(), normalized[0] / 10.0,
            normalized[3] / 100.0, normalized[4].toDouble(), normalized[5].toDouble(), normalized[6].toDouble())
    }

    fun showEnergy(sample: BassEnergyMonitor.Sample) {
        if (!realtimeState) return
        energyChart.add(sample)
        liveNumbers = if (sample.hasSignal && sample.levelDb.isFinite())
            String.format(Locale.ROOT, "低频相对能量 %.2f dB", sample.levelDb) else "低频相对能量 —"
        numbers.text = cursorNumbers ?: liveNumbers
        resolution.text = String.format(Locale.ROOT,
            "当前采样：%.1f–%.1f Hz · 分辨率 %.1f Hz · %d Hz / FFT %d",
            sample.lowerHz, sample.upperHz, sample.binWidthHz, sample.sampleRateHz, sample.fftSize)
        val prefix = if (effectEnabled) "" else "当前音效未启用；"
        status.text = prefix + if (sample.hasSignal && sample.levelDb.isFinite())
            "系统混音低频相对量，非 dBFS；目标频带 10–500 Hz。" else
            "系统混音暂无可见低频信号；静音或当前输出未进入系统混音采样。"
    }

    fun showCaptureState(state: String) {
        if (!realtimeState || state == lastStateLabel) return
        lastStateLabel = state
        energyChart.markGap()
        liveNumbers = "低频相对能量 —"
        numbers.text = cursorNumbers ?: liveNumbers
        status.text = state
    }

    fun showWaiting() {
        energyChart.clear()
        liveNumbers = "低频相对能量 —"
        numbers.text = liveNumbers
        resolution.text = "目标 10–500 Hz；实际频带与分辨率由采样结果显示"
        status.text = if (realtimeState) "等候系统混音低频采样" else "静态模型无需实时采样"
        lastStateLabel = null
    }

    fun suspendMonitoring() {
        staticChart.cancelPendingInputEvents()
        details.cancelPendingInputEvents()
        energyChart.clear()
        liveNumbers = "低频相对能量 —"
        numbers.text = liveNumbers
        status.text = "页面离开，采样显示已暂停"
        lastStateLabel = null
    }

    private fun updateMode() {
        power.isSelected = realtimeState
        power.setColorFilter(if (realtimeState) accent else ColorUtils.setAlphaComponent(foregroundColor, 110))
        power.contentDescription = if (realtimeState) "关闭图纸实时采样" else "开启图纸实时采样"
    }

    private fun updatePin() {
        pin.setImageResource(if (pinnedState) R.drawable.ic_peq_pin else R.drawable.ic_peq_pin_off)
        pin.setColorFilter(if (pinnedState) accent else ColorUtils.setAlphaComponent(foregroundColor, 0x90))
        pin.isSelected = pinnedState
        pin.contentDescription = if (pinnedState) "图表已固定，点击随页面滚动" else "图表随页面滚动，点击固定"
    }

    private fun segmentBackground(view: View) {
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(ColorUtils.setAlphaComponent(accent, 0x30)))
            addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(ColorUtils.setAlphaComponent(accent, 0x18)))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }
}

/** At most 400 actual samples, with stream/gap boundaries and a frozen inspection snapshot. */
private class BassEnergyChart(context: Context) : View(context) {
    var onCursorChanged: ((String?) -> Unit)? = null
    private val density = resources.displayMetrics.density
    private val foregroundColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0xc0)
        textSize = min(10f * resources.displayMetrics.scaledDensity, 12f * density)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(foregroundColor, 0x28)
        strokeWidth = density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; strokeWidth = 1.8f * density; style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val path = Path()
    private val plot = RectF()
    private data class Point(val sample: BassEnergyMonitor.Sample, val breakBefore: Boolean)
    private val history = arrayOfNulls<Point>(400)
    private var head = 0
    private var count = 0
    private var anchor = 0L
    private var breakNext = true
    private var held: List<Point>? = null
    private var heldAnchor = 0L
    private var selected: Point? = null
    private val gesture = BassChartGesture(this, { x, y ->
        if (!plot.contains(x, y) || count == 0) false else {
            held = List(count) { history[(head + it) % history.size]!! }
            heldAnchor = anchor
            true
        }
    }, { x -> selectSample(x) }, {
        held = null; selected = null; onCursorChanged?.invoke(null); invalidate()
    })

    fun add(sample: BassEnergyMonitor.Sample) {
        if (sample.elapsedRealtimeMs < 0L) return
        val previous = if (count > 0) history[(head + count - 1) % history.size]?.sample else null
        if (previous != null && sample.elapsedRealtimeMs <= previous.elapsedRealtimeMs) return
        if (previous != null && sample.streamId == previous.streamId && sample.elapsedRealtimeMs - previous.elapsedRealtimeMs < 50L) {
            if (!valid(sample)) breakNext = true
            return
        }
        anchor = max(anchor, sample.elapsedRealtimeMs)
        val split = breakNext || previous == null || previous.streamId != sample.streamId ||
            sample.elapsedRealtimeMs - previous.elapsedRealtimeMs > 300L || !valid(previous)
        val point = Point(sample, split)
        if (count == history.size) { history[head] = point; head = (head + 1) % history.size }
        else { history[(head + count) % history.size] = point; count++ }
        breakNext = !valid(sample)
        prune()
        if (held == null) invalidate()
    }

    fun markGap() {
        breakNext = true
        anchor = max(anchor, SystemClock.elapsedRealtime())
        prune()
        if (held == null) invalidate()
    }

    fun clear() {
        gesture.clear()
        history.fill(null); head = 0; count = 0; anchor = 0L; breakNext = true
        onCursorChanged?.invoke(null)
        invalidate()
    }

    private fun prune() {
        while (count > 0 && history[head]!!.sample.elapsedRealtimeMs < anchor - 20000L) {
            history[head] = null; head = (head + 1) % history.size; count--
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gesture.clear()
        plot.set(38f * density, 26f * density, w - 7f * density, h - 23f * density)
    }

    private fun valid(sample: BassEnergyMonitor.Sample?) = sample != null && sample.hasSignal && sample.levelDb.isFinite()
    private fun pointAt(index: Int) = held?.get(index) ?: history[(head + index) % history.size]!!

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (plot.width() <= 0f || plot.height() <= 0f) return
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("最近 20 秒 · 低频相对能量（非 dBFS）", density, 15f * density, textPaint)
        val visibleCount = held?.size ?: count
        val end = if (held != null) heldAnchor else anchor
        var minimum = -60.0
        var maximum = 0.0
        for (index in 0 until visibleCount) {
            val sample = pointAt(index).sample
            if (valid(sample)) { minimum = min(minimum, floor(sample.levelDb / 10.0) * 10.0); maximum = max(maximum, ceil(sample.levelDb / 10.0) * 10.0) }
        }
        fun y(value: Double) = plot.bottom - plot.height() * ((value - minimum) / (maximum - minimum)).toFloat()
        fun x(time: Long) = plot.right - plot.width() * ((end - time) / 20000.0).toFloat()
        for (step in 0..4) {
            val xx = plot.left + plot.width() * step / 4f
            val yy = plot.bottom - plot.height() * step / 4f
            canvas.drawLine(xx, plot.top, xx, plot.bottom, gridPaint)
            canvas.drawLine(plot.left, yy, plot.right, yy, gridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.ROOT, "%.0f", minimum + (maximum - minimum) * step / 4.0),
                plot.left - 4f * density, yy + 3f * density, textPaint)
            textPaint.textAlign = when (step) { 0 -> Paint.Align.LEFT; 4 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            canvas.drawText(if (step == 4) "当前" else "−${20 - step * 5}s", xx, plot.bottom + 15f * density, textPaint)
        }
        path.rewind()
        var started = false
        var latest: Point? = null
        for (index in 0 until visibleCount) {
            val point = pointAt(index)
            if (!valid(point.sample)) { started = false; continue }
            val xx = x(point.sample.elapsedRealtimeMs)
            val yy = y(point.sample.levelDb)
            if (!started || point.breakBefore) path.moveTo(xx, yy) else path.lineTo(xx, yy)
            started = true
            latest = point
        }
        canvas.save(); canvas.clipRect(plot)
        canvas.drawPath(path, linePaint)
        latest?.let { canvas.drawCircle(x(it.sample.elapsedRealtimeMs), y(it.sample.levelDb), 2.6f * density, dotPaint) }
        selected?.let {
            val xx = x(it.sample.elapsedRealtimeMs)
            val yy = y(it.sample.levelDb)
            canvas.drawLine(xx, plot.top, xx, plot.bottom, gridPaint)
            canvas.drawLine(plot.left, yy, plot.right, yy, gridPaint)
            canvas.drawCircle(xx, yy, 4f * density, dotPaint)
        }
        canvas.restore()
        if (latest == null) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("暂无可见低频样本", plot.centerX(), plot.centerY(), textPaint)
        }
    }

    private fun selectSample(x: Float) {
        val values = held ?: return
        val target = heldAnchor - ((plot.right - x.coerceIn(plot.left, plot.right)) / plot.width() * 20000L).toLong()
        selected = values.filter { valid(it.sample) }.minByOrNull { abs(it.sample.elapsedRealtimeMs - target) }
        onCursorChanged?.invoke(selected?.sample?.let {
            String.format(Locale.ROOT, "样本 −%.2f s · %.2f dB（相对）\n该样本 %.1f–%.1f Hz · 分辨率 %.1f Hz",
                (heldAnchor - it.elapsedRealtimeMs) / 1000.0, it.levelDb, it.lowerHz, it.upperHz, it.binWidthHz)
        } ?: "此窗口无可用低频样本")
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); gesture.clear() }
}

/** One-shot long press or horizontal drag; ordinary vertical scroll is not intercepted. */
internal class BassChartGesture(
    private val view: View,
    private val begin: (Float, Float) -> Boolean,
    private val move: (Float) -> Unit,
    private val end: () -> Unit
) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var latestX = 0f
    private var active = false
    private val longPress = Runnable { if (pointerId != MotionEvent.INVALID_POINTER_ID) start() }
    private fun start() {
        if (active || !view.isAttachedToWindow || !view.isShown || !view.isEnabled) return
        if (!begin(downX, downY)) { clear(); return }
        view.removeCallbacks(longPress)
        active = true
        view.parent?.requestDisallowInterceptTouchEvent(true)
        move(latestX)
    }
    fun clear() {
        view.removeCallbacks(longPress)
        pointerId = MotionEvent.INVALID_POINTER_ID
        if (active) { active = false; view.parent?.requestDisallowInterceptTouchEvent(false); end() }
    }
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!view.isEnabled) { clear(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clear()
                if (!event.x.isFinite() || !event.y.isFinite()) return false
                pointerId = event.getPointerId(0); downX = event.x; downY = event.y; latestX = event.x
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) { clear(); return false }
                val x = event.getX(index); val y = event.getY(index)
                if (!x.isFinite() || !y.isFinite()) { clear(); return false }
                latestX = x
                if (active) { move(x); return true }
                val dx = abs(x - downX); val dy = abs(y - downY)
                if (dy > slop && dy >= dx) { clear(); return false }
                if (dx > slop && dx > dy * 1.2f) {
                    var ancestor = view.parent
                    while (ancestor != null && ancestor !is androidx.viewpager2.widget.ViewPager2)
                        ancestor = ancestor.parent
                    // The added workspace reserves an ordinary swipe for paging.
                    // Long-press inspection still captures and drags the cursor.
                    if (ancestor != null) { clear(); return false }
                    start()
                }
                return pointerId != MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_UP -> {
                val wasActive = active; clear(); if (!wasActive) view.performClick(); return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                clear(); return event.actionMasked == MotionEvent.ACTION_CANCEL
            }
        }
        return pointerId != MotionEvent.INVALID_POINTER_ID
    }
}
