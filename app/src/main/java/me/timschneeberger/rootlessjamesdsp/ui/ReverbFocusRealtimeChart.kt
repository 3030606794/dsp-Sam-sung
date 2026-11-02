package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbController
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbParameters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Read-only native peak holds and instrument history, with independent original A/B state. */
internal class ReverbFocusRealtimeChart(context: Context) : View(context) {
    private data class Frame(val time: Long, val source: Any, val session: Int?, val rate: Int,
        val hostFrames: Int, val epoch: Int, val mode: Int, val split: Boolean,
        val data: List<Int>, val metered: Boolean, val duckDb: Double?)
    private data class Series(val name: String, val color: Int, val read: (Frame) -> Double?)
    private val d: Float = resources.displayMetrics.density
    private val ink: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val surface: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val purple: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val dark: Boolean = ColorUtils.calculateLuminance(surface) < .5
    private val cyan: Int = if (dark) Color.rgb(104, 216, 236) else Color.rgb(16, 124, 146)
    private val amber: Int = if (dark) Color.rgb(255, 198, 118) else Color.rgb(169, 102, 22)
    private val red: Int = if (dark) Color.rgb(255, 142, 142) else Color.rgb(180, 42, 42)
    private val muted: Int = ColorUtils.setAlphaComponent(ink, 150)
    private val colors: IntArray = intArrayOf(cyan, purple, amber)
    private val grid: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 38); style = Paint.Style.STROKE; strokeWidth = d
    }
    private val line: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeJoin = Paint.Join.ROUND
    }
    private val fill: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ellipsizePaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val digits: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; fontFeatureSettings = "tnum"
    }
    private val path: Path = Path()
    private val plots: MutableList<RectF> = ArrayList()
    private val touchPlot: RectF = RectF()
    private var focus: ReverbChartFocus = ReverbChartFocus.ROOM
    private var key: String = ReverbParameters.size.key
    private var model: ReverbDashboardModel? = null
    private var active: Boolean = false
    private val canDraw: Boolean get() = active && isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE
    private val history: ArrayDeque<Frame> = ArrayDeque()
    private var current: Frame? = null
    private var latestLevels: Frame? = null
    private var source: Any? = null
    private var session: Int? = null
    private var sourceRate: Int? = null
    private var previousFrames: Int? = null
    private var previousEpoch: Int? = null
    private var previousMode: Int? = null
    private var anchor: Long = 0L
    private var lastGood: Long = 0L
    private var breakNext: Boolean = true
    private var frozen: Boolean = true
    private var stale: Boolean = true
    private var state: String = "监视已关闭"
    private var held: List<Frame>? = null
    private var heldEnd: Long = 0L
    private var selected: Frame? = null
    private var pending: Boolean = false
    private var inspecting: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastX: Float = 0f
    private val slop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val modes: List<String> = listOf("正常处理", "本模块原声", "处理差分", "仅混响声", "快照 A", "快照 B", "双快照测量")
    private val hold: Runnable = Runnable {
        if (pending && isShown && history.isNotEmpty() && touchPlot.contains(downX, downY)) {
            pending = false; inspecting = true; held = history.toList(); heldEnd = anchor
            parent?.requestDisallowInterceptTouchEvent(true); select(lastX)
        }
    }

    init {
        text.textSize = min(9f * resources.displayMetrics.scaledDensity, 10f * d)
        digits.textSize = text.textSize
    }
    fun setConfiguration(model: ReverbDashboardModel) {
        val before: ReverbDashboardModel? = this.model
        this.model = model
        if (!model.settings.meters) latestLevels = null
        if (before != null && before.settings == model.settings && before.rate == model.rate && before.enabled == model.enabled) return
        finishInspection(); redraw()
    }
    fun setFocus(focus: ReverbChartFocus, key: String) {
        if (this.focus == focus && this.key == key) return
        finishInspection(); this.focus = focus; this.key = key; redraw()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; if (!value) finishInspection(); redraw()
    }
    fun pause(reason: String) {
        finishInspection(); frozen = true; state = reason; breakNext = true; redraw()
    }

    fun showSample(sample: ReverbDashboardRuntimeMonitor.Sample) {
        if (sample.elapsedRealtimeMs < 0) return
        anchor = max(anchor, sample.elapsedRealtimeMs); prune()
        val reading: ReverbController.Reading? = sample.reading
        val snapshot: ReverbParameters.Runtime? = reading?.snapshot
        val actualEpoch: Int = ReverbController.epoch
        val actualMode: Int = ReverbController.audition
        val hardInvalid: Boolean = !sample.output.connected || sample.output.globalIssue != null ||
            sample.output.activeSessions.size > 1 || sample.unavailableReason?.startsWith("多路音频") == true ||
            current?.let { it.mode != actualMode || actualMode != 0 && it.epoch != actualEpoch } == true
        val valid: Boolean = !hardInvalid && sample.streamIdentity != null && reading != null && reading.enabled &&
            reading.active && snapshot != null && snapshot.enabled && snapshot.professional && snapshot.ready &&
            snapshot.mode == actualMode && (actualMode == 0 || snapshot.epoch == actualEpoch) &&
            sample.output.processing == OutputRuntimeStatus.Processing.ACTIVE
        frozen = false
        if (!valid) {
            // Parameter hand-offs break the history connection, but never repeatedly clear it.
            if (sample.unavailableReason?.startsWith("参数或试听") == true) breakNext = true
            if (hardInvalid || current == null || anchor - lastGood > 1000L) {
                current = null; latestLevels = null; stale = true; breakNext = true
                state = sample.unavailableReason ?: "等待有效的专业混响处理帧"
            }
            redraw(); return
        }
        val accepted: ReverbController.Reading = reading!!
        val live: ReverbParameters.Runtime = snapshot!!
        val identity: Any = sample.streamIdentity!!
        val frames: Int = accepted.frames?.takeIf { it >= 0 } ?: run { breakNext = true; redraw(); return }
        val audioEpoch: Int = if (live.mode == 0) 0 else live.epoch
        if (source !== identity || session != sample.streamSessionId || sourceRate != live.data[2] ||
            previousEpoch != audioEpoch || previousMode != live.mode) {
            source = identity; session = sample.streamSessionId; sourceRate = live.data[2]
            previousFrames = null; latestLevels = null; breakNext = true
        }
        val before: Int? = previousFrames
        if (before == frames) {
            if (anchor - lastGood > 1000L) {
                current = null; latestLevels = null; stale = true; state = "等待新的音频处理帧"; breakNext = true
            }
            redraw(); return
        }
        if (before != null && frames < before && !(before >= 0x70000000 && frames <= 0x0fffffff)) breakNext = true
        val gap: Boolean = breakNext || history.lastOrNull()?.let { anchor - it.time > 1000L } == true
        val duck: Double? = sample.duckReductionDb?.toDouble()?.takeIf { it.isFinite() && it in 0.0..24.0 }
        val frame: Frame = Frame(anchor, identity, sample.streamSessionId, live.data[2], frames, audioEpoch, live.mode,
            gap, live.data, live.data[23] == 1 && metersEnabled(), duck)
        current = frame; history.addLast(frame); previousFrames = frames
        if (frame.metered) latestLevels = frame
        previousEpoch = audioEpoch; previousMode = live.mode; lastGood = anchor
        breakNext = false; stale = false; state = ""; prune(); redraw()
    }

    private fun metersEnabled(): Boolean = model?.settings?.meters == true
    private fun prune() {
        while (history.isNotEmpty() && (history.size > 120 || history.first().time < anchor - 20000L)) history.removeFirst()
    }
    private fun db(raw: Int?): Double? = raw?.takeIf { it != Int.MIN_VALUE }?.div(100.0)?.takeIf { it.isFinite() }
    private fun channelPeak(frame: Frame?, index: Int): Double? = if (frame?.metered == true) db(frame.data.getOrNull(index)) else null
    private fun peak(frame: Frame?, first: Int): Double? {
        val l: Double? = channelPeak(frame, first); val r: Double? = channelPeak(frame, first + 1)
        return when { l != null && r != null -> max(l, r); l != null -> l; else -> r }
    }
    private fun visibleLevels(): Frame? = if (metersEnabled()) latestLevels?.takeIf { frozen || anchor - it.time <= 1000L } else null
    private fun toolsCurrent(frame: Frame?): Boolean = frame != null && frame.data[11] == ReverbController.epoch &&
        frame.data[5] == ReverbController.audition
    private fun gain(frame: Frame?, index: Int): Double? = if (frame != null && toolsCurrent(frame) && frame.data[7] == 3) db(frame.data[index]) else null
    private fun format(value: Double?, decimals: Int = 1): String = value?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.ROOT, "%.${decimals}f", it) } ?: "—"
    private fun redraw() { if (canDraw) invalidate() }
    private fun label(c: Canvas, value: String, x: Float, y: Float, available: Float,
        color: Int = ink, align: Paint.Align = Paint.Align.LEFT, numeric: Boolean = false) {
        if (available <= 0f) return
        val paint: Paint = if (numeric) digits else text
        paint.color = color; paint.textAlign = align
        val left: Float = when (align) { Paint.Align.RIGHT -> x - available; Paint.Align.CENTER -> x - available / 2; else -> x }
        c.save(); c.clipRect(left, y + paint.ascent() - d, left + available, y + paint.descent() + d)
        ellipsizePaint.set(paint)
        val rendered: String = if (numeric) value else TextUtils.ellipsize(value, ellipsizePaint, available, TextUtils.TruncateAt.END).toString()
        c.drawText(rendered, x, y, paint); c.restore()
    }

    private fun peakSeries(): List<Series> = if (focus == ReverbChartFocus.WIDTH) listOf(
        Series("正常湿声 L", purple) { channelPeak(it, 18) }, Series("正常湿声 R", cyan) { channelPeak(it, 19) })
    else listOf(Series("输入峰值", cyan) { peak(it, 16) }, Series("正常湿声峰值", purple) { peak(it, 18) },
        Series("当前试听输出峰值", amber) { peak(it, 20) })

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width < 20 || height < 20) return
        plots.clear()
        val series: List<Series> = peakSeries()
        label(canvas, if (focus == ReverbChartFocus.WIDTH) "真实正常湿声 L / R 峰值 · dBFS"
            else "输入 / 正常湿声 / 试听输出 · 双声道较大峰值 dBFS", 2f * d, 10f * d, width - 4f * d)
        val leftEdge: Float = 33f * d; val rightEdge: Float = width - 6f * d
        val step: Float = (rightEdge - leftEdge) / series.size
        val levels: Frame? = visibleLevels()
        val silent: Boolean = levels != null && series.all { it.read(levels) == null }
        series.forEachIndexed { index, s ->
            val x: Float = leftEdge + step * index
            val value: Double? = levels?.let { s.read(it) }
            val short: String = if (focus == ReverbChartFocus.WIDTH) if (index == 0) "L" else "R" else listOf("入", "湿", "出")[index]
            label(canvas, short, x, 25f * d, 13f * d, s.color)
            label(canvas, format(value).padStart(7), x + step - 7f * d, 25f * d, step - 18f * d,
                if (value != null && value >= 0.0) red else s.color, Paint.Align.RIGHT, true)
            val bar: RectF = RectF(x, 29f * d, x + step - 9f * d, 36f * d)
            fill.color = ColorUtils.setAlphaComponent(ink, 17); canvas.drawRoundRect(bar, 2f * d, 2f * d, fill)
            value?.let { v ->
                fill.color = ColorUtils.setAlphaComponent(if (v >= 0.0) red else s.color, if (frozen) 85 else 190)
                val length: Float = ((v.coerceIn(-96.0, 6.0) + 96.0) / 102.0).toFloat() * bar.width()
                canvas.drawRoundRect(RectF(bar.left, bar.top, bar.left + length, bar.bottom), 2f * d, 2f * d, fill)
            }
        }
        val match: Boolean = focus == ReverbChartFocus.MATCH
        val duckFocus: Boolean = focus == ReverbChartFocus.DUCK
        val actualMode: String = current?.mode?.let { modes.getOrElse(it) { "等待确认" } } ?: "等待确认"
        if (match) drawMatch(canvas)
        else label(canvas, if (duckFocus) "正常湿声避让 −${format(current?.duckDb)} dB · 输出路径：$actualMode"
            else "当前试听路径：$actualMode", 2f * d, 47f * d, width - 4f * d,
            if (duckFocus && (current?.duckDb ?: 0.0) > .01) red else muted)
        val top: Float = (if (match) 78f else 56f) * d
        val bottom: Float = height - 27f * d
        if (duckFocus) {
            drawHistory(canvas, RectF(leftEdge, top, width * .65f, bottom), -96.0, 6.0, "dBFS", series)
            drawHistory(canvas, RectF(width * .65f + 33f * d, top, rightEdge, bottom), -24.0, 0.0, "dB",
                listOf(Series("正常湿声避让", red) { it.duckDb?.let { depth -> -depth } }))
        } else drawHistory(canvas, RectF(leftEdge, top, rightEdge, bottom), -96.0, 6.0, "dBFS", series)
        val footer: String = when {
            frozen -> "$state · 末次真实读数冻结"
            stale -> state
            !metersEnabled() -> "原生“显示实时电平”已关闭 · A/B 与避让状态仍独立可读"
            levels == null -> "等待新的真实峰值窗口 · 不补造电平"
            silent -> "静音或低于峰值计量下限 · A/B 状态独立保留"
            match -> "预热后测量 2000 ms · 完成后 A/B 衰减固定"
            duckFocus -> "右图为正常保存声音的湿声避让，不代表 A/B 快照避让"
            focus == ReverbChartFocus.WIDTH -> "正常保存声音的真实湿声 L/R · 约 0.5 秒衰减保持"
            else -> "输入/湿声为正常分支，输出为所选试听路径 · 非 RMS"
        }
        label(canvas, footer, 2f * d, height - 3f * d, width - 4f * d, muted)
        if (!frozen && (stale || !metersEnabled() || silent)) {
            val p: RectF? = plots.firstOrNull()
            p?.let { label(canvas, if (stale) state else if (!metersEnabled()) "开启原生“显示实时电平”可看峰值" else "静音或低于计量下限", it.centerX(), it.centerY(),
                it.width() - 8f * d, muted, Paint.Align.CENTER) }
        }
        drawInspection(canvas)
    }

    private fun drawMatch(c: Canvas) {
        val p: List<Int>? = current?.takeIf { toolsCurrent(it) }?.data
        val stage: Int? = p?.get(7)
        val status: String = when (stage) { 0 -> "未测量"; 1 -> "A/B 预热"; 2 -> "正在测量"; 3 -> "测量完成"; 4 -> "信号不足"; else -> "等待测量状态" }
        label(c, status, 2f * d, 49f * d, width * .32f, if (stage == 4) red else ink)
        val timing: String = if (stage == 1) "${p?.get(24) ?: "—"} / ${p?.get(25) ?: "—"} ms" else "${p?.get(8) ?: "—"} / ${p?.get(26) ?: "—"} ms"
        label(c, timing, width - 6f * d, 49f * d, width * .65f, muted, Paint.Align.RIGHT, true)
        val middle: Float = width * .5f
        label(c, "A ${format(gain(current, 9), 2).padStart(7)} dB", middle - 6f * d, 65f * d,
            middle - 10f * d, purple, Paint.Align.RIGHT, true)
        label(c, "B ${format(gain(current, 10), 2).padStart(7)} dB", width - 6f * d, 65f * d,
            middle - 10f * d, cyan, Paint.Align.RIGHT, true)
    }

    private fun drawHistory(c: Canvas, plot: RectF, low: Double, high: Double, unit: String, series: List<Series>) {
        if (plot.width() <= 0 || plot.height() <= 3f * d) return
        plots.add(plot)
        val points: List<Frame> = held ?: history.toList()
        val end: Long = if (held != null) heldEnd else anchor
        fun x(time: Long): Float = plot.right - ((end - time) / 20000.0).toFloat() * plot.width()
        fun y(value: Double): Float = plot.bottom - ((value.coerceIn(low, high) - low) / (high - low)).toFloat() * plot.height()
        val ticks: List<Double> = if (unit == "dBFS") listOf(-96.0, -48.0, 0.0) else listOf(-24.0, -12.0, 0.0)
        for (v in ticks) {
            val yy: Float = y(v); c.drawLine(plot.left, yy, plot.right, yy, grid)
            label(c, format(v, 0), plot.left - 4f * d, yy + 3f * d, 27f * d, muted, Paint.Align.RIGHT, true)
        }
        for (tick in 0..4) {
            val xx: Float = plot.left + plot.width() * tick / 4f
            c.drawLine(xx, plot.top, xx, plot.bottom, grid)
            if (plot.width() > 150f * d || tick == 0 || tick == 4) label(c,
                if (tick == 4) "现在" else if (tick == 0) "−20s" else "−${20 - tick * 5}", xx, plot.bottom + 12f * d,
                max(28f * d, plot.width() / 4f), muted,
                if (tick == 0) Paint.Align.LEFT else if (tick == 4) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        if (unit == "dB") label(c, "避让 dB", plot.right - 2f * d, plot.top + 10f * d, plot.width() - 4f * d, red, Paint.Align.RIGHT)
        c.save(); c.clipRect(plot)
        for (s in series) {
            path.rewind(); var started: Boolean = false; var previous: Frame? = null
            for (point in points) {
                val value: Double? = s.read(point)
                if (value == null) { started = false; previous = point; continue }
                val gap: Boolean = point.split || previous?.let { it.source !== point.source || it.session != point.session ||
                    it.rate != point.rate || it.epoch != point.epoch || it.mode != point.mode || point.time - it.time > 1000L } == true
                if (!started || gap) path.moveTo(x(point.time), y(value)) else path.lineTo(x(point.time), y(value))
                started = true; previous = point
            }
            val subdued: Boolean = frozen || stale || unit == "dBFS" && !metersEnabled()
            line.color = ColorUtils.setAlphaComponent(s.color, if (subdued) 90 else 230); c.drawPath(path, line)
            points.lastOrNull()?.let { point -> s.read(point)?.let { value -> fill.color = line.color
                c.drawCircle(x(point.time), y(value), 1.8f * d, fill) } }
            selected?.let { point -> s.read(point)?.let { value -> fill.color = s.color
                c.drawCircle(x(point.time), y(value), 2.5f * d, fill) } }
        }
        selected?.let { c.drawLine(x(it.time), plot.top, x(it.time), plot.bottom, grid) }
        c.restore()
    }

    private fun drawInspection(c: Canvas) {
        val point: Frame = selected ?: return
        val rows: List<String> = listOf("−${format((heldEnd - point.time) / 1000.0, 2)} s · ${modes.getOrElse(point.mode) { "—" }}") +
            peakSeries().map { "${it.name} ${format(it.read(point), 2)} dBFS" } +
            if (focus == ReverbChartFocus.DUCK) listOf("正常湿声避让 −${format(point.duckDb, 2)} dB") else emptyList()
        val pad: Float = 5f * d; val boxWidth: Float = min(width - 4f * d, max(170f * d, width * .6f))
        val boxHeight: Float = text.fontSpacing * rows.size + pad * 2
        val left: Float = touchPlot.left.coerceIn(2f * d, max(2f * d, width - boxWidth - 2f * d))
        val top: Float = touchPlot.top.coerceAtMost(max(2f * d, height - boxHeight - 2f * d))
        fill.color = ColorUtils.setAlphaComponent(surface, 245)
        c.drawRoundRect(RectF(left, top, left + boxWidth, top + boxHeight), 3f * d, 3f * d, fill)
        rows.forEachIndexed { index, row -> label(c, row, left + pad, top + pad - text.ascent() + index * text.fontSpacing,
            boxWidth - 2 * pad, ink, Paint.Align.LEFT, index > 0) }
    }
    private fun select(x: Float) {
        val points: List<Frame> = held ?: return
        val target: Long = heldEnd - ((touchPlot.right - x.coerceIn(touchPlot.left, touchPlot.right)) / touchPlot.width() * 20000L).toLong()
        selected = points.minByOrNull { abs(it.time - target) }; redraw()
    }
    private fun finishInspection() {
        removeCallbacks(hold); pending = false
        if (inspecting) parent?.requestDisallowInterceptTouchEvent(false)
        inspecting = false; held = null; selected = null
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishInspection()
                val hit: RectF = plots.firstOrNull { it.contains(event.x, event.y) } ?: return false
                if (history.isEmpty()) return false
                touchPlot.set(hit); downX = event.x; downY = event.y; lastX = event.x; pending = true
                postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong()); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) { finishInspection(); return false }
                lastX = event.x
                if (inspecting) { select(lastX); return true }
                if (pending && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) { pending = false; removeCallbacks(hold) }
                return pending
            }
            MotionEvent.ACTION_UP -> { val click: Boolean = pending; finishInspection(); if (click) performClick(); redraw(); return true }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { finishInspection(); redraw(); return false }
        }
        return inspecting || pending
    }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); finishInspection(); redraw() }
    override fun onDetachedFromWindow() { finishInspection(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}
