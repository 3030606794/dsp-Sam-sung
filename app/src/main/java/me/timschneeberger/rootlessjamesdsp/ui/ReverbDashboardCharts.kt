package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbDashboardRuntimeMonitor
import java.util.Locale
import kotlin.math.*

/** Canvas-only observers. There is no timer, audio listener, preference write or audition mutation. */
internal abstract class ReverbDashboardChart(context: Context) : View(context) {
    var onStatusChanged: (() -> Unit)? = null
    protected val dp = resources.displayMetrics.density
    protected val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    protected val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    protected val colors = intArrayOf(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary),
        if (ColorUtils.calculateLuminance(surface) < .5) Color.rgb(108, 210, 241) else Color.rgb(15, 126, 164))
    protected val red = if (ColorUtils.calculateLuminance(surface) < .5) Color.rgb(255, 140, 140) else Color.rgb(181, 43, 43)
    protected val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(9f * resources.displayMetrics.scaledDensity, 11f * dp) }
    protected val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * dp; style = Paint.Style.STROKE }
    protected val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(foreground, 0x30); strokeWidth = dp; style = Paint.Style.STROKE }
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val path = Path()
    protected val dashed = DashPathEffect(floatArrayOf(4f * dp, 3f * dp), 0f)
    protected var configuration: ReverbDashboardModel? = null
        private set
    protected var sample: ReverbDashboardRuntimeMonitor.Sample? = null
        private set
    protected var pauseReason = "监视已关闭"
        private set
    private var active = false
    protected val canDraw get() = active && isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE
    protected val compact get() = height < 60f * dp
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        text.textSize = if (compact) min(6.5f * resources.displayMetrics.scaledDensity, 7.5f * dp)
            else min(9f * resources.displayMetrics.scaledDensity, 11f * dp)
    }
    fun setModel(value: ReverbDashboardModel) {
        if (configuration === value) return
        configuration = value; onModelChanged(); if (canDraw) invalidate()
    }
    fun setActive(value: Boolean) { active = value; if (canDraw) invalidate() }
    fun showSample(value: ReverbDashboardRuntimeMonitor.Sample) {
        sample = value; onSample(value); if (canDraw) invalidate()
    }
    fun pause(reason: String) { pauseReason = reason; sample = null; onPause(); if (canDraw) invalidate() }
    protected open fun onModelChanged() = Unit
    protected open fun onSample(value: ReverbDashboardRuntimeMonitor.Sample) = Unit
    protected open fun onPause() = Unit
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (active) invalidate() }
    protected fun fmt(value: Double?, digits: Int = 1) = value?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.ROOT, "% .${digits}f", it).trim() } ?: "—"
    protected fun label(canvas: Canvas, value: String, x: Float, y: Float, available: Float,
        color: Int = foreground, align: Paint.Align = Paint.Align.LEFT) {
        if (available <= 0) return
        val original = text.textSize; text.color = color; text.textAlign = align
        val measured = text.measureText(value)
        if (measured > available) text.textSize *= available / measured
        canvas.drawText(value, x, y, text); text.textSize = original
    }
    protected fun point(canvas: Canvas, x: Float, y: Float, color: Int) { fill.color = color; canvas.drawCircle(x, y, 2.5f * dp, fill) }
    protected fun tooltip(canvas: Canvas, rows: List<String>, x: Float, y: Float) {
        val gap = text.textSize * 1.25f; val pad = 4f * dp
        val w = min(width - 2f * dp, (rows.maxOfOrNull { text.measureText(it) } ?: 0f) + pad * 2)
        val h = rows.size * gap + pad * 2
        val left = x.coerceIn(dp, max(dp, width - w - dp)); val top = y.coerceIn(dp, max(dp, height - h - dp))
        fill.color = ColorUtils.setAlphaComponent(surface, 0xf4)
        canvas.drawRoundRect(RectF(left, top, left + w, top + h), 3f * dp, 3f * dp, fill)
        rows.forEachIndexed { i, row -> label(canvas, row, left + pad, top + pad + gap * (i + .8f), w - pad * 2) }
    }
    protected fun axes(canvas: Canvas, plot: RectF, low: Double, high: Double, unit: String) {
        for (i in 0..2) {
            val y = plot.top + plot.height() * i / 2
            canvas.drawLine(plot.left, y, plot.right, y, grid)
            label(canvas, fmt(high - (high - low) * i / 2, if (high > 10) 0 else 1), plot.left - 3f * dp, y + 3f * dp,
                max(1f, plot.left - 5f * dp), foreground, Paint.Align.RIGHT)
        }
        for (i in 0..4) { val x = plot.left + plot.width() * i / 4; canvas.drawLine(x, plot.top, x, plot.bottom, grid) }
        if (!compact) label(canvas, unit, 2f * dp, plot.top - 3f * dp, plot.left - 4f * dp)
    }
}

/** Exponential amplitude envelope of the driver feedback prediction, not an actual impulse envelope. */
internal class ReverbDecayView(context: Context) : ReverbDashboardChart(context) {
    private val plot = RectF()
    private var cursor: Float? = null
    private val gesture = ReverbChartCursor(this, { x, y -> plot.contains(x, y) }, { x -> cursor = x.coerceIn(plot.left, plot.right); invalidate() }, { cursor = null; invalidate() })
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val value = configuration ?: return
        plot.set((if (compact) 19f else 27f) * dp, (if (compact) 11f else 25f) * dp,
            width - 5f * dp, height - (if (compact) 7f else 17f) * dp)
        if (plot.height() <= 0 || plot.width() <= 0) return
        // A fixed time domain makes longer RT60/room feedback visibly extend the tail.
        // log1p retains a true zero and enough screen space for a 0–200 ms predelay.
        val endMs = 10000.0; val timeScaleMs = 100.0
        val timeNorm = ln(1 + endMs / timeScaleMs)
        fun timeAt(fraction: Double) = timeScaleMs * (exp(fraction * timeNorm) - 1)
        fun x(ms: Double) = plot.left + (ln(1 + ms / timeScaleMs) / timeNorm).toFloat() * plot.width()
        fun y(amplitude: Double) = plot.bottom - amplitude.toFloat() * plot.height()
        if (compact) label(canvas, "包络% · 固定对数时间 0–10s${if (value.predelayMs + (value.decayMs.maxOrNull() ?: 0.0) > endMs) " · 尾部截断" else ""}",
            2f * dp, 8f * dp, width - 4f * dp)
        else {
            label(canvas, "RT60 指数包络 · 固定对数时间 0–10s${if (value.predelayMs + (value.decayMs.maxOrNull() ?: 0.0) > endMs) " · 尾部截断" else ""}", 2f * dp, 10f * dp, width - 4f * dp)
            label(canvas, "低 ${fmt(value.decayMs[0] / 1000, 2)} / 中 ${fmt(value.decayMs[1] / 1000, 2)} / 高 ${fmt(value.decayMs[2] / 1000, 2)} s", 2f * dp, 22f * dp, width - 4f * dp)
        }
        axes(canvas, plot, 0.0, 100.0, "%")
        for (ms in listOf(100.0, 1000.0)) canvas.drawLine(x(ms), plot.top, x(ms), plot.bottom, grid)
        fill.color = ColorUtils.setAlphaComponent(foreground, 0x18)
        canvas.drawRect(plot.left, plot.top, x(value.predelayMs), plot.bottom, fill)
        canvas.save(); canvas.clipRect(plot)
        for (band in 0..2) {
            path.rewind()
            val startFraction = ln(1 + value.predelayMs / timeScaleMs) / timeNorm
            for (i in 0..96) {
                val ms = timeAt(startFraction + (1 - startFraction) * i / 96)
                if (i == 0) path.moveTo(x(ms), y(1.0)) else path.lineTo(x(ms), y(value.decayAmplitude(ms, band)))
            }
            line.color = colors[band]; line.pathEffect = if (band == 1) dashed else null
            canvas.drawPath(path, line); line.pathEffect = null
        }
        cursor?.let { xx -> canvas.drawLine(xx, plot.top, xx, plot.bottom, grid) }
        canvas.restore()
        label(canvas, "0", plot.left, height - (if (compact) 1f else 4f) * dp, 12f * dp)
        for (ms in listOf(100.0, 1000.0)) label(canvas, if (ms == 100.0) "0.1" else "1", x(ms),
            height - (if (compact) 1f else 4f) * dp, plot.width() * .2f, foreground, Paint.Align.CENTER)
        label(canvas, "10 s", plot.right, height - (if (compact) 1f else 4f) * dp, plot.width() * .25f, foreground, Paint.Align.RIGHT)
        if (value.predelayMs > 0 && !compact) label(canvas, "预延迟 ${fmt(value.predelayMs, 1)} ms", plot.left + 2f * dp,
            plot.bottom - 3f * dp, plot.width() * .65f)
        cursor?.let { xx ->
            val ms = timeAt(((xx - plot.left) / plot.width()).toDouble())
            tooltip(canvas, listOf("${fmt(ms, 1)} ms · 相对包络", "低/中/高 ${fmt(value.decayAmplitude(ms, 0) * 100, 2)} / ${fmt(value.decayAmplitude(ms, 1) * 100, 2)} / ${fmt(value.decayAmplitude(ms, 2) * 100, 2)}%"),
                plot.left, plot.top + 3f * dp)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Wet-only HP/LP response and a representative loop's damping, deliberately separate curves. */
internal class ReverbDampingView(context: Context) : ReverbDashboardChart(context) {
    private val plot = RectF(); private var cursor: Float? = null
    private val gesture = ReverbChartCursor(this, { x, y -> plot.contains(x, y) }, { x -> cursor = x.coerceIn(plot.left, plot.right); invalidate() }, { cursor = null; invalidate() })
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val value = configuration ?: return
        plot.set((if (compact) 19f else 27f) * dp, (if (compact) 11f else 25f) * dp,
            width - 5f * dp, height - (if (compact) 7f else 17f) * dp)
        if (plot.height() <= 0 || plot.width() <= 0) return
        fun x(index: Int) = plot.left + index.toFloat() / value.spectrumHz.lastIndex * plot.width()
        fun y(db: Double) = plot.bottom - ((db + 60) / 60).toFloat() * plot.height()
        if (compact) label(canvas, "dB模型：紫 湿声滤波 / 青 回路损耗 / 虚线关闭", 2f * dp, 8f * dp, width - 4f * dp)
        else {
            label(canvas, "阻尼与湿声滤波 · 模型", 2f * dp, 10f * dp, width - 4f * dp)
            label(canvas, "紫 湿声滤波 / 青 回路损耗 / 虚线 关闭预览", 2f * dp, 22f * dp, width - 4f * dp)
        }
        axes(canvas, plot, -60.0, 0.0, "dB")
        canvas.save(); canvas.clipRect(plot)
        val arrays = listOf(value.filterDb, value.loopLossDb) +
            (if (!value.lowCutOn) listOf(value.lowCutPreviewDb) else emptyList()) +
            (if (!value.highCutOn) listOf(value.highCutPreviewDb) else emptyList())
        arrays.forEachIndexed { row, values ->
            path.rewind(); values.forEachIndexed { i, db -> if (i == 0) path.moveTo(x(i), y(db)) else path.lineTo(x(i), y(db)) }
            line.color = if (row < 2) colors[row * 2] else ColorUtils.setAlphaComponent(foreground, 0x77)
            line.pathEffect = if (row > 0) dashed else null
            canvas.drawPath(path, line); line.pathEffect = null
        }
        if (value.advanced.bandsOn) for (frequency in listOf(value.lowBandHz, value.highBandHz)) {
            val xx = plot.left + (ln(frequency / value.spectrumHz.first()) / ln(value.spectrumHz.last() / value.spectrumHz.first())).toFloat() * plot.width()
            line.color = ColorUtils.setAlphaComponent(colors[1], 0x70); line.pathEffect = dashed
            canvas.drawLine(xx, plot.top, xx, plot.bottom, line); line.pathEffect = null
        }
        cursor?.let { canvas.drawLine(it, plot.top, it, plot.bottom, grid) }
        canvas.restore()
        label(canvas, "20", plot.left, height - 4f * dp, 24f * dp)
        label(canvas, "${fmt(value.spectrumHz.last() / 1000, 1)}k Hz", plot.right, height - 4f * dp,
            plot.width() * .5f, foreground, Paint.Align.RIGHT)
        cursor?.let { xx ->
            val index = (((xx - plot.left) / plot.width()) * value.spectrumHz.lastIndex).roundToInt().coerceIn(value.spectrumHz.indices)
            tooltip(canvas, listOf("${fmt(value.spectrumHz[index], 1)} Hz", "滤波 ${fmt(value.filterDb[index], 2)} dB", "单回路 ${fmt(value.loopLossDb[index], 2)} dB"), plot.left, plot.top + 3f * dp)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Width is a matrix coefficient; room size is feedback strength, never a geometric metre value. */
internal class ReverbSpaceView(context: Context) : ReverbDashboardChart(context) {
    private val oval = RectF()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val value = configuration ?: return
        label(canvas, "房间反馈 / 立体声扩散示意 · 非实测声场", 2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        val left = (if (compact) 10f else 20f) * dp; val right = width * .61f
        val top = (if (compact) 10f else 18f) * dp; val bottom = height - (if (compact) 7f else 16f) * dp
        if (right <= left || bottom <= top) return
        val cx = (left + right) / 2; val cy = bottom
        val radius = min((right - left) / 2, bottom - top)
        for (ring in 1..3) {
            val r = radius * ring / 3
            oval.set(cx - r, cy - r, cx + r, cy + r); canvas.drawArc(oval, 180f, 180f, false, grid)
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, grid)
        canvas.drawLine(cx, cy - radius, cx, cy, grid)
        val spread = 10 + value.widthPercent * .8
        val r = radius * (.45 + value.roomPercent * .0055).toFloat()
        oval.set(cx - r, cy - r, cx + r, cy + r)
        fill.color = ColorUtils.setAlphaComponent(colors[0], (35 + value.wetGain * 50).roundToInt().coerceIn(35, 155))
        canvas.drawArc(oval, (270 - spread).toFloat(), (spread * 2).toFloat(), true, fill)
        line.color = colors[0]; line.pathEffect = if (value.enabled) null else dashed
        canvas.drawArc(oval, (270 - spread).toFloat(), (spread * 2).toFloat(), true, line); line.pathEffect = null
        line.color = colors[2]; line.strokeWidth = (1 + value.dryGain * 2).toFloat() * dp
        canvas.drawLine(cx, cy, cx, cy - radius * .85f, line); line.strokeWidth = 1.5f * dp
        point(canvas, cx, cy, colors[2])
        label(canvas, "L", cx - radius, height - 3f * dp, 20f * dp)
        label(canvas, "M", cx, height - 3f * dp, 20f * dp, foreground, Paint.Align.CENTER)
        label(canvas, "R", cx + radius, height - 3f * dp, 20f * dp, foreground, Paint.Align.RIGHT)
        val x = width * .64f; val available = width - x - 3f * dp
        if (compact) {
            label(canvas, "宽 ${fmt(value.widthPercent)}% · 反馈 ${fmt(value.feedback, 3)}", x, 16f * dp, available, colors[0])
            label(canvas, "干 ${fmt(value.dryGain, 2)} / 湿 ${fmt(value.wetGain, 2)}", x, 24f * dp, available)
            return
        }
        val rows = listOf("宽度 ${fmt(value.widthPercent)}%", "反馈 ${fmt(value.feedback, 3)}", "预延迟 ${fmt(value.predelayMs)} ms",
            "干 ${fmt(value.dryGain, 2)} / 湿 ${fmt(value.wetGain, 2)}", "输出 ${fmt(20 * log10(value.outputGain), 1)} dB")
        val rowHeight = min(12f * dp, (height - 20f * dp) / rows.size)
        rows.forEachIndexed { index, row -> label(canvas, row, x, 22f * dp + index * rowHeight, available,
            if (index == 0) colors[0] else foreground) }
    }
}

/** Native input/wet/output peak holds. No dry/wet RMS or inferred branch energy is fabricated. */
internal class ReverbEnergyView(context: Context) : ReverbDashboardChart(context) {
    private data class Point(val time: Long, val input: Double?, val wet: Double?, val output: Double?, val gap: Boolean)
    private val history = ArrayList<Point>(320)
    private var now = 0L; private var identity: Any? = null; private var epoch: Int? = null; private var mode: Int? = null
    private var rate: Int? = null; private var frames: Int? = null; private var split = true
    private var held: List<Point>? = null; private var heldTime = 0L; private var selected: Point? = null
    private val plot = RectF()
    private val gesture = ReverbChartCursor(this, { x, y ->
        if (plot.contains(x, y) && history.isNotEmpty()) { held = history.toList(); heldTime = now; true } else false
    }, { xx ->
        val at = heldTime - ((plot.right - xx.coerceIn(plot.left, plot.right)) / plot.width() * 20000).toLong()
        selected = held?.minByOrNull { abs(it.time - at) }; invalidate()
    }, { held = null; selected = null; invalidate() })
    override fun onModelChanged() { split = true }
    override fun onPause() { split = true; gesture.clear() }
    override fun onSample(value: ReverbDashboardRuntimeMonitor.Sample) {
        now = max(now, value.elapsedRealtimeMs)
        while (history.isNotEmpty() && (history.first().time < now - 20000 || history.size > 300)) history.removeAt(0)
        val reading = value.reading; val data = reading?.snapshot?.data
        val a = value.inputPeakDbfs?.toDouble()?.takeIf { it.isFinite() }
        val b = value.wetPeakDbfs?.toDouble()?.takeIf { it.isFinite() }
        val c = value.outputPeakDbfs?.toDouble()?.takeIf { it.isFinite() }
        if (data == null || (a == null && b == null && c == null)) { split = true; return }
        if (identity !== value.streamIdentity || epoch != data[11] || mode != data[5] || rate != data[2]) split = true
        identity = value.streamIdentity; epoch = data[11]; mode = data[5]; rate = data[2]
        if (!split && frames == data[29]) return
        frames = data[29]
        history += Point(value.elapsedRealtimeMs, a, b, c, split); split = false
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        plot.set((if (compact) 23f else 32f) * dp, (if (compact) 11f else 26f) * dp,
            width - 7f * dp, height - (if (compact) 7f else 16f) * dp)
        if (plot.height() <= 0 || plot.width() <= 0) return
        val points = held ?: history; val end = if (held != null) heldTime else now
        var top = 6.0; var bottom = -90.0
        points.forEach { p -> listOfNotNull(p.input, p.wet, p.output).forEach { top = max(top, ceil(it / 6) * 6); bottom = min(bottom, floor(it / 12) * 12) } }
        fun x(time: Long) = plot.right - ((end - time) / 20000.0).toFloat() * plot.width()
        fun y(db: Double) = plot.bottom - ((db - bottom) / (top - bottom)).toFloat() * plot.height()
        if (!compact) label(canvas, "Dry参考仅输入峰值 / 原保存湿声 / 所选试听输出 · 非 RMS", 2f * dp, 10f * dp, width - 4f * dp)
        val names = if (compact) listOf("输入峰值dBFS", "原保存湿声峰值", "所选试听输出峰值") else listOf("输入峰值（Dry参考）", "原保存湿声峰值", "所选试听输出峰值")
        names.forEachIndexed { i, name -> label(canvas, name, plot.left + plot.width() * (i + .5f) / 3,
            (if (compact) 8f else 23f) * dp, plot.width() / 3, colors[i], Paint.Align.CENTER) }
        axes(canvas, plot, bottom, top, "dBFS")
        canvas.save(); canvas.clipRect(plot)
        for (channel in 0..2) {
            path.rewind(); var connected = false; var previous = 0L
            for (p in points) {
                val v = when (channel) { 0 -> p.input; 1 -> p.wet; else -> p.output }
                if (v == null) { connected = false; continue }
                if (connected && !p.gap && p.time - previous < 1500) path.lineTo(x(p.time), y(v)) else path.moveTo(x(p.time), y(v))
                previous = p.time; connected = true
            }
            line.color = colors[channel]; canvas.drawPath(path, line)
        }
        selected?.let { p ->
            canvas.drawLine(x(p.time), plot.top, x(p.time), plot.bottom, grid)
            listOf(p.input, p.wet, p.output).forEachIndexed { i, v -> v?.let { point(canvas, x(p.time), y(it), colors[i]) } }
        }
        canvas.restore()
        label(canvas, "−20 s", plot.left, height - 3f * dp, 40f * dp)
        label(canvas, "现在", plot.right, height - 3f * dp, 40f * dp, foreground, Paint.Align.RIGHT)
        val current = sample
        val reason = current?.unavailableReason ?: if (current == null) pauseReason else null
        if (reason != null) label(canvas, reason, plot.centerX(), plot.centerY(), plot.width() - 10f * dp, foreground, Paint.Align.CENTER)
        selected?.let { p -> tooltip(canvas, listOf("−${fmt((heldTime - p.time) / 1000.0, 2)} s · 输入峰值 ${fmt(p.input, 2)}", "原保存湿声 ${fmt(p.wet, 2)} / 所选输出 ${fmt(p.output, 2)} dBFS"),
            if (x(p.time) > plot.centerX()) plot.left else plot.centerX(), plot.top + 2f * dp) }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Vertical scrolling wins until a deliberate long press begins inspection. */
private class ReverbChartCursor(private val view: View, private val begin: (Float, Float) -> Boolean,
    private val move: (Float) -> Unit, private val end: () -> Unit) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var currentX = 0f
    private var pending = false; private var inspecting = false
    private val hold = Runnable {
        if (pending && view.isAttachedToWindow && view.isShown) {
            pending = false
            if (begin(downX, downY)) { inspecting = true; view.parent?.requestDisallowInterceptTouchEvent(true); move(currentX) }
        }
    }
    fun clear() {
        view.removeCallbacks(hold); pending = false
        if (inspecting) { inspecting = false; view.parent?.requestDisallowInterceptTouchEvent(false); end() }
    }
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { clear(); downX = event.x; currentX = downX; downY = event.y; pending = true
                view.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong()); return true }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) { clear(); return false }
                currentX = event.x
                if (inspecting) { move(currentX); return true }
                if (pending && (abs(currentX - downX) > slop || abs(event.y - downY) > slop)) { pending = false; view.removeCallbacks(hold) }
                return true
            }
            MotionEvent.ACTION_UP -> { val click = pending && !inspecting; clear(); if (click) view.performClick(); return true }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { clear(); return false }
        }
        return pending || inspecting
    }
}
