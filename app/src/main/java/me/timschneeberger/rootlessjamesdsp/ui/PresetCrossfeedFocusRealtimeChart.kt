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
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedController
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedParameters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The six-preset engine exports current audition-output peak holds, not branch RMS or PCM. */
internal class PresetCrossfeedFocusRealtimeChart(context: Context) : View(context) {
    private data class Frame(val time: Long, val source: Any, val rate: Int, val frames: Int,
        val epoch: Int, val mode: Int, val split: Boolean, val data: List<Int>)
    private val d: Float = resources.displayMetrics.density
    private val ink: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val surface: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val purple: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val dark: Boolean = ColorUtils.calculateLuminance(surface) < .5
    private val cyan: Int = if (dark) Color.rgb(104, 216, 236) else Color.rgb(16, 124, 146)
    private val red: Int = if (dark) Color.rgb(255, 142, 142) else Color.rgb(180, 42, 42)
    private val muted: Int = ColorUtils.setAlphaComponent(ink, 150)
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
    private val plot: RectF = RectF()
    private var focus: PresetCrossfeedChartFocus = PresetCrossfeedChartFocus.RESPONSE
    private var key: String = PresetCrossfeedParameters.BASE
    private var settings: PresetCrossfeedParameters.Settings = PresetCrossfeedParameters.baseSettings(0)
    private var configuredRate: Int = 48000
    private var active: Boolean = false
    private val canDraw: Boolean get() = active && isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE
    private val history: ArrayDeque<Frame> = ArrayDeque()
    private var current: Frame? = null
    private var source: Any? = null
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
    private val modes: List<String> = listOf("正常处理", "本模块原声", "处理差分", "仅交叉支路", "快照 A", "快照 B", "双快照测量")
    private val hold: Runnable = Runnable {
        if (pending && isShown && plot.contains(downX, downY) && history.isNotEmpty()) {
            pending = false; inspecting = true; held = history.toList(); heldEnd = anchor
            parent?.requestDisallowInterceptTouchEvent(true); select(lastX)
        }
    }

    init {
        text.textSize = min(9f * resources.displayMetrics.scaledDensity, 10f * d)
        digits.textSize = text.textSize
    }

    fun setConfiguration(settings: PresetCrossfeedParameters.Settings, rate: Int) {
        val nextRate: Int = rate.takeIf { it in 8000..768000 } ?: 48000
        if (this.settings == settings && configuredRate == nextRate) return
        this.settings = settings; configuredRate = nextRate
        finishInspection(); redraw()
    }
    fun setFocus(focus: PresetCrossfeedChartFocus, key: String) {
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

    fun showSample(sample: PresetCrossfeedDashboardRuntimeMonitor.Sample) {
        if (sample.elapsedRealtimeMs < 0) return
        anchor = max(anchor, sample.elapsedRealtimeMs); prune()
        val reading: PresetCrossfeedController.Reading? = sample.reading
        val snapshot: PresetCrossfeedParameters.Runtime? = reading?.snapshot
        val currentGeneration: Int = PresetCrossfeedController.epoch
        val currentMode: Int = PresetCrossfeedController.audition
        val hardInvalid: Boolean = !sample.output.connected || sample.output.globalIssue != null ||
            sample.output.activeSessions.size > 1 || sample.unavailableReason?.startsWith("多路音频") == true ||
            sample.unavailableReason == "试听状态正在同步" || current?.let {
                it.mode != currentMode || currentMode != 0 && it.epoch != currentGeneration
            } == true
        val valid: Boolean = !hardInvalid && sample.streamIdentity != null && reading != null && reading.enabled && reading.active &&
            sample.output.processing == OutputRuntimeStatus.Processing.ACTIVE && snapshot != null && snapshot.enabled &&
            snapshot.professional && snapshot.data[23] == 1 && snapshot.epoch >= 0 && snapshot.mode == currentMode &&
            (currentMode == 0 || snapshot.epoch == currentGeneration)
        frozen = false
        if (!valid) {
            // A temporary idle/GET miss does not switch the chart layout, erase bars or flash a label.
            // A different generation, output chain or prolonged loss cannot retain live-looking data.
            if (hardInvalid || current == null || anchor - lastGood > 1100L) {
                breakNext = true
                current = null; stale = true
                state = sample.unavailableReason ?: when {
                    !sample.output.connected -> "驱动未连接"
                    sample.output.globalIssue != null -> "当前音频链路不可用"
                    sample.output.activeSessions.size > 1 -> "多路音频正在处理"
                    !settings.professional -> "原预设仍处理；专业精调关闭时无峰值读数"
                    else -> "等待有效音频处理帧"
                }
            }
            redraw(); return
        }
        val accepted: PresetCrossfeedController.Reading = reading!!
        val live: PresetCrossfeedParameters.Runtime = snapshot!!
        val identity: Any = sample.streamIdentity!!
        val frames: Int = accepted.frames?.takeIf { it >= 0 } ?: run { breakNext = true; redraw(); return }
        val audioEpoch: Int = if (live.mode == 0) 0 else live.epoch
        if (source !== identity || sourceRate != live.data[2] || previousEpoch != audioEpoch || previousMode != live.mode) {
            source = identity; sourceRate = live.data[2]; previousFrames = null; breakNext = true
        }
        val before: Int? = previousFrames
        if (before != null && before == frames) {
            if (anchor - lastGood > 1100L) { current = null; stale = true; state = "等待新的音频处理帧"; breakNext = true }
            redraw(); return
        }
        if (before != null && frames < before && !(before >= 0x70000000 && frames <= 0x0fffffff)) breakNext = true
        val gap: Boolean = breakNext || history.lastOrNull()?.let { anchor - it.time > 1100L } == true
        val frame: Frame = Frame(anchor, identity, live.data[2], frames, audioEpoch, live.mode, gap, live.data)
        current = frame; history.addLast(frame); previousFrames = frames
        previousEpoch = audioEpoch; previousMode = live.mode; lastGood = anchor
        breakNext = false; stale = false; state = ""; prune(); redraw()
    }

    private fun prune() {
        while (history.isNotEmpty() && (history.size > 120 || history.first().time < anchor - 20000L)) history.removeFirst()
    }
    private fun peak(frame: Frame?, channel: Int): Double? = frame?.data?.getOrNull(20 + channel)
        ?.takeIf { it != Int.MIN_VALUE }?.div(100.0)?.takeIf { it.isFinite() }
    private fun toolsCurrent(frame: Frame?): Boolean = frame != null && frame.data[10] == PresetCrossfeedController.epoch &&
        frame.data[5] == PresetCrossfeedController.audition
    private fun gain(frame: Frame?, index: Int): Double? = frame?.takeIf { toolsCurrent(it) && it.data[6] == 2 }?.data?.getOrNull(index)
        ?.takeIf { it != Int.MIN_VALUE }?.div(100.0)?.takeIf { it.isFinite() }
    private fun format(value: Double?, decimals: Int = 1): String = value?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.ROOT, "%.${decimals}f", it) } ?: "—"
    private fun redraw() { if (canDraw) invalidate() }

    /** Fixed font and clipping: neither numeric magnitude nor temporary status changes text size. */
    private fun label(c: Canvas, value: String, x: Float, y: Float, available: Float,
        color: Int = ink, align: Paint.Align = Paint.Align.LEFT, numeric: Boolean = false) {
        if (available <= 0f) return
        val paint: Paint = if (numeric) digits else text
        paint.color = color; paint.textAlign = align
        val left: Float = when (align) { Paint.Align.RIGHT -> x - available; Paint.Align.CENTER -> x - available / 2; else -> x }
        c.save(); c.clipRect(left, y + paint.ascent() - d, left + available, y + paint.descent() + d)
        ellipsizePaint.set(paint)
        val rendered: String = if (numeric) value else TextUtils.ellipsize(value, ellipsizePaint, available,
            TextUtils.TruncateAt.END).toString()
        c.drawText(rendered, x, y, paint); c.restore()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width < 20 || height < 20) return
        // Every focus uses the same two actual output channels; static charts explain the parameter.
        label(canvas, "L / R 输出峰值保持 · dBFS", 2f * d, 10f * d, width - 4f * d)
        val leftEdge: Float = 33f * d
        val rightEdge: Float = width - 6f * d
        val half: Float = (rightEdge - leftEdge) / 2f
        for (channel in 0..1) {
            val x: Float = leftEdge + half * channel
            val color: Int = if (channel == 0) purple else cyan
            val value: Double? = peak(current, channel)
            label(canvas, if (channel == 0) "L" else "R", x, 25f * d, 12f * d, color)
            label(canvas, "${format(value).padStart(7)} dBFS", x + half - 9f * d, 25f * d,
                half - 18f * d, if (value != null && value >= 0.0) red else color, Paint.Align.RIGHT, true)
            val bar: RectF = RectF(x, 29f * d, x + half - 10f * d, 36f * d)
            fill.color = ColorUtils.setAlphaComponent(ink, 17); canvas.drawRoundRect(bar, 2f * d, 2f * d, fill)
            value?.let { measured ->
                fill.color = ColorUtils.setAlphaComponent(if (measured >= 0.0) red else color, if (frozen) 85 else 190)
                val length: Float = ((measured.coerceIn(-96.0, 6.0) + 96.0) / 102.0).toFloat() * bar.width()
                canvas.drawRoundRect(RectF(bar.left, bar.top, bar.left + length, bar.bottom), 2f * d, 2f * d, fill)
            }
        }
        val actualMode: String = current?.mode?.let { modes.getOrElse(it) { "等待确认" } } ?: "等待确认"
        val match: Boolean = focus == PresetCrossfeedChartFocus.MATCH
        if (match) drawMatch(canvas) else label(canvas, "当前试听路径：$actualMode", 2f * d, 47f * d, width - 4f * d, muted)
        val top: Float = (if (match) 77f else 56f) * d
        plot.set(leftEdge, top, rightEdge, height - 26f * d)
        if (plot.width() > 0 && plot.height() > 3f * d) drawHistory(canvas)
        val footer: String = when {
            frozen -> "$state · 末次真实峰值冻结"
            stale -> state
            match -> "峰值来自 $actualMode · 测量时间含模型预热"
            else -> "约 0.5 秒衰减保持 · 当前路径输出，后续效果仍会改变声音"
        }
        label(canvas, footer, 2f * d, height - 3f * d, width - 4f * d, muted)
        if (stale && !frozen && plot.height() > 5f * d) label(canvas, state, plot.centerX(), plot.centerY(),
            plot.width() - 8f * d, muted, Paint.Align.CENTER)
        drawInspection(canvas)
    }

    private fun drawMatch(c: Canvas) {
        val frame: Frame? = current.takeIf { toolsCurrent(it) }
        val measureState: Int? = frame?.data?.get(6)
        val status: String = when (measureState) { 0 -> "未测量"; 1 -> "正在测量"; 2 -> "测量完成"; 3 -> "信号不足"; else -> "等待测量状态" }
        label(c, status, 2f * d, 48f * d, width * .38f, if (measureState == 3) red else ink)
        val elapsed: Int? = frame?.data?.get(7)?.takeIf { it >= 0 }
        label(c, "累计 ${elapsed?.toString()?.padStart(5) ?: "    —"} ms", width - 6f * d, 48f * d,
            width * .59f, muted, Paint.Align.RIGHT, true)
        val middle: Float = width * .5f
        label(c, "A ${format(gain(frame, 8), 2).padStart(7)} dB", middle - 6f * d, 64f * d,
            middle - 10f * d, purple, Paint.Align.RIGHT, true)
        label(c, "B ${format(gain(frame, 9), 2).padStart(7)} dB", width - 6f * d, 64f * d,
            middle - 10f * d, cyan, Paint.Align.RIGHT, true)
    }

    private fun drawHistory(c: Canvas) {
        val points: List<Frame> = held ?: history.toList()
        val end: Long = if (held != null) heldEnd else anchor
        fun x(time: Long): Float = plot.right - ((end - time) / 20000.0).toFloat() * plot.width()
        fun y(value: Double): Float = plot.bottom - ((value.coerceIn(-96.0, 6.0) + 96.0) / 102.0).toFloat() * plot.height()
        for (db in listOf(-96.0, -48.0, 0.0, 6.0)) {
            val yy: Float = y(db); c.drawLine(plot.left, yy, plot.right, yy, grid)
            if (db != 6.0 || plot.height() > 90f * d) label(c, format(db, 0), plot.left - 4f * d, yy + 3f * d,
                28f * d, muted, Paint.Align.RIGHT, true)
        }
        for (tick in 0..4) {
            val xx: Float = plot.left + plot.width() * tick / 4f
            c.drawLine(xx, plot.top, xx, plot.bottom, grid)
            label(c, if (tick == 4) "现在" else if (tick == 0) "−20s" else "−${20 - tick * 5}", xx, plot.bottom + 12f * d,
                plot.width() / 4f, muted, if (tick == 0) Paint.Align.LEFT else if (tick == 4) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        c.save(); c.clipRect(plot)
        for (channel in 0..1) {
            path.rewind(); var started: Boolean = false; var previous: Frame? = null
            for (point in points) {
                val value: Double? = peak(point, channel)
                if (value == null) { started = false; previous = point; continue }
                val gap: Boolean = point.split || previous?.let { it.source !== point.source || it.rate != point.rate ||
                    it.epoch != point.epoch || it.mode != point.mode || point.time - it.time > 1100L } == true
                if (!started || gap) path.moveTo(x(point.time), y(value)) else path.lineTo(x(point.time), y(value))
                started = true; previous = point
            }
            val color: Int = if (channel == 0) purple else cyan
            line.color = ColorUtils.setAlphaComponent(color, if (frozen || stale) 90 else 230); c.drawPath(path, line)
            points.lastOrNull()?.let { point -> peak(point, channel)?.let { value ->
                fill.color = line.color; c.drawCircle(x(point.time), y(value), 1.8f * d, fill)
            } }
            selected?.let { point -> peak(point, channel)?.let { value -> fill.color = color
                c.drawCircle(x(point.time), y(value), 2.5f * d, fill) } }
        }
        selected?.let { c.drawLine(x(it.time), plot.top, x(it.time), plot.bottom, grid) }
        c.restore()
    }

    private fun drawInspection(c: Canvas) {
        val point: Frame = selected ?: return
        val rows: List<String> = listOf("−${format((heldEnd - point.time) / 1000.0, 2)} s · ${modes.getOrElse(point.mode) { "—" }}",
            "L ${format(peak(point, 0), 2)} dBFS", "R ${format(peak(point, 1), 2)} dBFS")
        val pad: Float = 5f * d
        val boxWidth: Float = min(width - 4f * d, max(140f * d, plot.width() * .54f))
        val boxHeight: Float = text.fontSpacing * rows.size + pad * 2
        val left: Float = plot.left.coerceIn(2f * d, max(2f * d, width - boxWidth - 2f * d))
        val top: Float = plot.top.coerceAtMost(max(2f * d, height - boxHeight - 2f * d))
        fill.color = ColorUtils.setAlphaComponent(surface, 245)
        c.drawRoundRect(RectF(left, top, left + boxWidth, top + boxHeight), 3f * d, 3f * d, fill)
        rows.forEachIndexed { index, row -> label(c, row, left + pad, top + pad - text.ascent() + index * text.fontSpacing,
            boxWidth - 2 * pad, ink, Paint.Align.LEFT, index > 0) }
    }
    private fun select(x: Float) {
        val points: List<Frame> = held ?: return
        val target: Long = heldEnd - ((plot.right - x.coerceIn(plot.left, plot.right)) / plot.width() * 20000L).toLong()
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
                if (!plot.contains(event.x, event.y) || history.isEmpty()) return false
                downX = event.x; downY = event.y; lastX = event.x; pending = true
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
