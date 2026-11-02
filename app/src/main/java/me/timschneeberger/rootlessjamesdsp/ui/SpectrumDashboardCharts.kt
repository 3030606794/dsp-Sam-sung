package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.*
import me.timschneeberger.rootlessjamesdsp.fusion.SpectrumDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.SpectrumDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.SpectrumPcmTelemetry
import java.util.Locale
import kotlin.math.*

/** Canvas-only observers. There is no timer, audio listener, preference write or audition mutation. */
internal abstract class SpectrumDashboardChart(context: Context) : View(context) {
    var onStatusChanged: (() -> Unit)? = null
    protected val dp = resources.displayMetrics.density
    protected val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    protected val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    protected val colors = intArrayOf(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary),
        if (ColorUtils.calculateLuminance(surface) < .5) Color.rgb(108, 210, 241) else Color.rgb(15, 126, 164))
    protected val red = if (ColorUtils.calculateLuminance(surface) < .5) Color.rgb(255, 140, 140) else Color.rgb(181, 43, 43)
    protected val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = min(9f * resources.displayMetrics.scaledDensity, 10f * dp)
        fontFeatureSettings = "tnum"
    }
    protected val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * dp; style = Paint.Style.STROKE }
    protected val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(foreground, 0x30); strokeWidth = dp; style = Paint.Style.STROKE }
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val path = Path()
    protected val dashed = DashPathEffect(floatArrayOf(4f * dp, 3f * dp), 0f)
    protected var configuration: SpectrumDashboardModel? = null
        private set
    protected var sample: SpectrumDashboardRuntimeMonitor.Sample? = null
        private set
    protected var pauseReason = "监视已关闭"
        private set
    private var active = false
    private var attached = false
    protected val canDraw get() = active && attached && visibility == VISIBLE && windowVisibility == VISIBLE
    protected val compact get() = height <= 64f * dp
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // A smaller viewport removes detail; it never shrinks the shared chart typography.
        text.textSize = min(9f * resources.displayMetrics.scaledDensity, 10f * dp)
    }
    fun setModel(value: SpectrumDashboardModel) {
        if (configuration === value) return
        configuration = value; onModelChanged(); if (canDraw) invalidate()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; onActivationChanged(); if (canDraw) invalidate()
    }
    fun showSample(value: SpectrumDashboardRuntimeMonitor.Sample) {
        sample = value; onSample(value); if (canDraw) invalidate()
    }
    fun pause(reason: String) { pauseReason = reason; sample = null; onPause(); if (canDraw) invalidate() }
    protected open fun onModelChanged() = Unit
    protected open fun onSample(value: SpectrumDashboardRuntimeMonitor.Sample) = Unit
    protected open fun onPause() = Unit
    protected open fun onActivationChanged() = Unit
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; onActivationChanged(); if (active) invalidate() }
    override fun onDetachedFromWindow() { attached = false; onActivationChanged(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); onActivationChanged() }
    protected fun fmt(value: Double?, digits: Int = 1) = value?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.ROOT, "% .${digits}f", it).trim() } ?: "—"
    protected fun label(canvas: Canvas, value: String, x: Float, y: Float, available: Float,
        color: Int = foreground, align: Paint.Align = Paint.Align.LEFT) {
        if (available <= 0) return
        text.color = color; text.textAlign = align
        canvas.drawText(TextUtils.ellipsize(value,text,available,TextUtils.TruncateAt.END).toString(), x, y, text)
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

/** One actual work band, plus labelled shaping-stage single-tone examples, not invented DSP bands. */
internal class SpectrumTransferView(context: Context) : SpectrumDashboardChart(context) {
    private val plot = RectF(); private var cursor: Float? = null
    private val gesture = SpectrumChartCursor(this, { x, y -> plot.contains(x, y) }, { cursor = it.coerceIn(plot.left, plot.right); invalidate() }, { cursor = null; invalidate() })
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val value = configuration ?: return
        plot.set((if (compact) 23f else 32f) * dp, (if (compact) 13f else 30f) * dp, width - 6f * dp, height - (if (compact) 8f else 18f) * dp)
        if (plot.width() <= 0 || plot.height() <= 0) return
        val high = value.frequencies.last()
        fun x(hz: Double) = plot.left + (ln(hz / 20) / ln(high / 20)).toFloat() * plot.width()
        fun y(db: Double) = plot.bottom - ((db.coerceIn(-90.0, 18.0) + 90) / 108).toFloat() * plot.height()
        label(canvas, "处理范围 · 参数示意", 2f * dp, (if (compact) 8f else 10f) * dp, width * .58f)
        label(canvas, "偶次", width - 46f * dp, (if (compact) 8f else 10f) * dp, 22f * dp, colors[2])
        label(canvas, "奇次", width - 3f * dp, (if (compact) 8f else 10f) * dp, 22f * dp, colors[0], Paint.Align.RIGHT)
        if (!compact) {
            label(canvas, "${fmt(value.hpHz / 1000, 1)}–${fmt(value.lpHz / 1000, 1)} kHz · 激励强度 ${fmt(value.amountPercent, 1)}%", 2f * dp, 22f * dp, width - 4f * dp)
        }
        axes(canvas, plot, -90.0, 18.0, "dB")
        for (tick in listOf(100.0, 1000.0, 10000.0)) if (tick < high) canvas.drawLine(x(tick), plot.top, x(tick), plot.bottom, grid)
        canvas.save(); canvas.clipRect(plot)
        path.rewind(); path.moveTo(plot.left, plot.bottom)
        value.workEnvelopeDb.forEachIndexed { i, db -> path.lineTo(x(value.frequencies[i]), y(db)) }
        path.lineTo(plot.right, plot.bottom); path.close()
        fill.color = ColorUtils.setAlphaComponent(colors[0], 0x27); canvas.drawPath(path, fill)
        path.rewind()
        value.workEnvelopeDb.forEachIndexed { i, db -> if (i == 0) path.moveTo(x(value.frequencies[i]), y(db)) else path.lineTo(x(value.frequencies[i]), y(db)) }
        line.color = colors[0]; line.strokeWidth = 2f * dp; line.pathEffect = if (value.enabled) null else dashed
        canvas.drawPath(path, line); line.pathEffect = dashed
        line.color = ColorUtils.setAlphaComponent(colors[0], 110); line.strokeWidth = dp
        for (edge in listOf(value.hpHz, value.lpHz)) {
            val xx = x(edge.coerceIn(20.0, high))
            canvas.drawLine(xx, plot.top, xx, plot.bottom, line)
        }
        line.pathEffect = null
        line.strokeWidth = 1.5f * dp
        for (i in 1 until value.harmonicFrequencies.size) {
            val hz = value.harmonicFrequencies[i]; val db = value.harmonicPeakDb[i]
            if (hz > high || !db.isFinite() || db < -90) continue
            val xx = x(hz); val yy = y(db); val color = colors[if ((i + 1) % 2 == 0) 2 else 0]
            line.color = ColorUtils.setAlphaComponent(color, 190); line.strokeWidth = (if (compact) 1.5f else 3f) * dp
            canvas.drawLine(xx, plot.bottom, xx, yy, line); point(canvas, xx, yy, color)
        }
        line.strokeWidth = 1.5f * dp
        cursor?.let { canvas.drawLine(it, plot.top, it, plot.bottom, grid) }
        canvas.restore()
        label(canvas, "20Hz", plot.left, height - 2f * dp, 30f * dp)
        label(canvas, "${fmt(high / 1000, 1)}kHz", plot.right, height - 2f * dp, plot.width() * .3f, foreground, Paint.Align.RIGHT)
        cursor?.let { xx ->
            val fraction = ((xx - plot.left) / plot.width()).toDouble()
            val hz = 20 * (high / 20).pow(fraction)
            val i = (fraction * value.frequencies.lastIndex).roundToInt().coerceIn(value.frequencies.indices)
            val h = value.harmonicFrequencies.indices.filter { value.harmonicFrequencies[it] <= high }.minByOrNull { abs(ln(value.harmonicFrequencies[it] / hz)) }
            val harmonic = if (h == null) "无可见单音阶次" else {
                val level = value.harmonicPeakDb[h]
                "模型H${h + 1} ${fmt(value.harmonicFrequencies[h], 0)}Hz · ${if (level == Double.NEGATIVE_INFINITY) "−∞" else fmt(level, 2)}dBFS · 权重${fmt(value.weights[h].toDouble(), 5)}"
            }
            tooltip(canvas, listOf("${fmt(value.frequencies[i], 1)}Hz · 模型包络 ${fmt(value.workEnvelopeDb[i], 2)}dB",
                "HP ${fmt(value.hpHz, 0)}Hz / Q${fmt(value.hpQ, 3)} · LP ${fmt(value.lpHz, 0)}Hz / Q${fmt(value.lpQ, 3)}",
                "驱动 ${fmt(value.driveDb, 1)}dB · 输出 ${fmt(value.outputDb, 1)}dB · ${value.effectiveFactor}×过采样",
                harmonic, "单音参数示意，非音乐实测 / 非THD"), plot.left, plot.top + 2f * dp)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Genuine excitation-only RMS windows. A hidden view still accepts history, without a draw timer. */
internal class SpectrumEnergyHistoryView(context: Context) : SpectrumDashboardChart(context) {
    val minimumReadableHeight:Int get() = ceil(7 * max(text.fontSpacing,
        text.fontMetrics.bottom-text.fontMetrics.top) + 22f*dp).toInt()
    private data class Window(val source: Any, val generation: Int, val sequence: Int)
    private data class Point(val time: Long, val dbfs: Double, val window: Window, val breakBefore: Boolean)
    private val history = ArrayDeque<Point>()
    private val plot = RectF()
    private val meter = RectF()
    private var anchor = 0L
    private var lastWindow: Window? = null
    private var breakNext = true
    private var currentLevel: Double? = null
    private var state = "监视已关闭"
    private var held: List<Point>? = null
    private var heldAnchor = 0L
    private var selected: Point? = null
    private val gesture = SpectrumChartCursor(this, { x, y ->
        if (plot.contains(x, y) && history.isNotEmpty()) {
            held = history.toList(); heldAnchor = anchor; true
        } else false
    }, { x ->
        val time = heldAnchor - ((plot.right - x.coerceIn(plot.left, plot.right)) / plot.width() * 20000L).toLong()
        selected = held?.minByOrNull { abs(it.time - time) }
        if (canDraw) invalidate()
    }, { held = null; selected = null; if (canDraw) invalidate() })

    override fun onSample(value: SpectrumDashboardRuntimeMonitor.Sample) {
        if (value.elapsedRealtimeMs < 0) return
        anchor = max(anchor, value.elapsedRealtimeMs)
        pruneHistory()
        val source = value.streamIdentity
        val generation = value.energyGeneration
        val sequence = value.energySequence
        val dbfs = value.energyDbfs
        val window = if (source != null && generation != null && generation >= 0 && sequence != null && sequence >= 0)
            Window(source, generation, sequence) else null
        val old = lastWindow
        if (window != null && old != null && window.source != old.source) breakNext = true
        if (window != null && old != null && window.generation != old.generation) breakNext = true
        if (value.energyClamped || value.energyUnavailableReason != null || window == null || dbfs == null || !dbfs.isFinite()) {
            currentLevel = null; breakNext = true
            state = value.energyUnavailableReason ?: if (value.energyClamped) "激励能量超出遥测范围" else "等待真实激励支路能量"
            return
        }
        if (window == old) {
            // A repeat poll of one native window is not another audio-energy measurement.
            if (history.lastOrNull()?.let { anchor - it.time > 1500L } == true) {
                currentLevel = null; state = "等待新的激励能量窗口"; breakNext = true
            }
            return
        }
        if (old != null && window.source == old.source && window.generation == old.generation && window.sequence < old.sequence) {
            currentLevel = null; state = "等待同步后的激励能量窗口"; breakNext = true
            return
        }
        val before = history.lastOrNull()
        if (before != null && value.elapsedRealtimeMs <= before.time) return
        val split = breakNext || before == null || value.elapsedRealtimeMs - before.time > 1500L ||
            before.window.source != window.source || before.window.generation != window.generation
        history.addLast(Point(value.elapsedRealtimeMs, dbfs, window, split))
        lastWindow = window; currentLevel = dbfs; state = ""; breakNext = false
        pruneHistory()
    }

    private fun pruneHistory() {
        while (history.isNotEmpty() && (history.size > 128 || history.first().time < anchor - 20000L)) history.removeFirst()
    }

    override fun onPause() {
        gesture.clear(); currentLevel = null; state = pauseReason; breakNext = true
        anchor = max(anchor, android.os.SystemClock.elapsedRealtime()); pruneHistory()
    }

    override fun onActivationChanged() { if (!canDraw) gesture.clear() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val metrics=text.fontMetrics
        val fontHeight=max(text.fontSpacing,metrics.bottom-metrics.top)
        val rowHeight=ceil(fontHeight)+2f*dp
        val captionBaseline=2f*dp-metrics.top
        val left=max(31f*dp,text.measureText("−120")+6f*dp)
        meter.set(left,rowHeight+2f*dp,width-5f*dp,rowHeight+8f*dp)
        val meterTickBaseline=meter.bottom+2f*dp-metrics.top
        val plotTop=meterTickBaseline+metrics.bottom+fontHeight/2+3f*dp
        plot.set(left,plotTop,width-5f*dp,height-rowHeight-3f*dp)
        if (plot.width() <= 0 || plot.height() <= 0) return
        val reading = if (held != null) selected?.dbfs else currentLevel
        val levelColor = if (reading != null && reading > 0.0) red else colors[0]
        label(canvas, "纯激励支路 RMS", 2f * dp, captionBaseline, width * .48f)
        val readingText = reading?.let { (if (it > 0) "+" else "") + fmt(it, 1) + " dBFS" + if (it > 0) " · 超0" else "" } ?: "— dBFS"
        label(canvas, readingText, plot.right, captionBaseline, width * .45f, levelColor, Paint.Align.RIGHT)
        fill.color = ColorUtils.setAlphaComponent(colors[0], 24)
        canvas.drawRoundRect(meter, 2f * dp, 2f * dp, fill)
        if (reading != null) {
            fill.color = ColorUtils.setAlphaComponent(levelColor, 205)
            val right = meter.left + ((reading.coerceIn(-120.0, 0.0) + 120.0) / 120.0).toFloat() * meter.width()
            canvas.drawRoundRect(RectF(meter.left, meter.top, right, meter.bottom), 2f * dp, 2f * dp, fill)
            if (reading > 0.0) {
                line.color = red; line.pathEffect = null; line.strokeWidth = 2f * dp
                canvas.drawLine(meter.right, meter.top, meter.right, meter.bottom, line)
            }
        }
        for (i in 0..4) {
            val xx = meter.left + meter.width() * i / 4
            canvas.drawLine(xx, meter.top, xx, meter.bottom, grid)
            if (i % 2 == 0) label(canvas, "${-120 + i * 30}", xx, meterTickBaseline, meter.width() / 4,
                foreground, if (i == 0) Paint.Align.LEFT else if (i == 4) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        fun x(time: Long, end: Long) = plot.right - ((end - time) / 20000.0).toFloat() * plot.width()
        fun y(db: Double) = plot.bottom - ((db.coerceIn(-120.0, 0.0) + 120.0) / 120.0).toFloat() * plot.height()
        for (db in listOf(-120.0, -60.0, 0.0)) {
            val yy = y(db); canvas.drawLine(plot.left, yy, plot.right, yy, grid)
            label(canvas, fmt(db, 0), plot.left - 3f * dp, yy-(metrics.ascent+metrics.descent)/2,
                plot.left - 5f * dp, foreground, Paint.Align.RIGHT)
        }
        for (i in 0..4) {
            val xx = plot.left + plot.width() * i / 4
            canvas.drawLine(xx, plot.top, xx, plot.bottom, grid)
            label(canvas, if (i == 4) "现在" else if (i == 0) "−20 s" else "−${20 - i * 5}", xx,
                height-2f*dp-metrics.bottom,
                plot.width() / 4, foreground, if (i == 0) Paint.Align.LEFT else if (i == 4) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        val points = held ?: history.toList()
        val end = if (held != null) heldAnchor else anchor
        canvas.save(); canvas.clipRect(plot)
        path.rewind(); var started = false
        points.forEach { point ->
            val xx = x(point.time, end); val yy = y(point.dbfs)
            if (!started || point.breakBefore) path.moveTo(xx, yy) else path.lineTo(xx, yy)
            started = true
        }
        line.color = ColorUtils.setAlphaComponent(colors[0], if (currentLevel != null || held != null) 235 else 115)
        line.strokeWidth = 1.8f * dp; line.pathEffect = null
        canvas.drawPath(path, line)
        points.lastOrNull()?.let { p -> point(canvas, x(p.time, end), y(p.dbfs), if (p.dbfs > 0) red else line.color) }
        selected?.let { p ->
            val xx = x(p.time, end); canvas.drawLine(xx, plot.top, xx, plot.bottom, grid)
            point(canvas, xx, y(p.dbfs), colors[0])
        }
        canvas.restore()
        if (held == null && currentLevel == null && state.isNotEmpty()) {
            label(canvas, state, plot.centerX(), plot.centerY()-(metrics.ascent+metrics.descent)/2,
                plot.width() - 6f * dp, foreground, Paint.Align.CENTER)
        }
        selected?.let { point ->
            tooltip(canvas, listOf("−${fmt((heldAnchor - point.time) / 1000.0, 2)} s · ${fmt(point.dbfs, 2)} dBFS RMS",
                "真实纯激励支路 · 非输出混音 / 非峰值",
                "窗口 ${point.window.generation}:${point.window.sequence}"), plot.left, plot.top + 2f * dp)
        }
    }

    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); gesture.clear() }
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Latest-only bounded worker. A hidden chart retains the newest sample but performs no DSP math. */
internal abstract class SpectrumPcmChart<T>(context: Context) : SpectrumDashboardChart(context) {
    protected var calculated: T? = null
        private set
    private data class Request(val data: SpectrumPcmTelemetry.Snapshot, val source: Any?)
    private var latest: Request? = null; private var pending: Request? = null; private var displayed: Request? = null
    private var scope: CoroutineScope? = null; private var worker: Job? = null
    private var error: String? = null
    protected val currentWindow get() = latest?.data
    protected val displayedWindow get() = displayed?.data
    protected val isCurrentWindow get() = same(latest, displayed)
    protected val pcmReason: String? get() = sample?.pcmUnavailableReason
        ?: if (sample == null) pauseReason else if (latest == null) "驱动尚未提供原始音频窗口" else error
    protected abstract fun calculate(data: SpectrumPcmTelemetry.Snapshot, cancel: () -> Unit): T
    private fun same(a: Request?, b: Request?): Boolean = a != null && b != null && a.source === b.source &&
        a.data.sequence == b.data.sequence && a.data.generation == b.data.generation &&
        a.data.rate == b.data.rate && a.data.firstFramePosition == b.data.firstFramePosition && a.data.frames == b.data.frames
    override fun onSample(value: SpectrumDashboardRuntimeMonitor.Sample) {
        val data = value.pcm
        if (data == null) { latest = null; pending = null; cancelWorker(); return }
        val request = Request(data, value.streamIdentity)
        if (same(request, latest)) return
        latest = request; pending = if (same(request, displayed)) null else request; error = null
        start()
    }
    override fun onPause() { latest = null; pending = null; cancelWorker() }
    override fun onActivationChanged() {
        if (!canDraw) { cancelWorker(); pending = latest }
        else { if (!same(latest, displayed)) pending = latest; start() }
    }
    private fun cancelWorker() { worker?.cancel(); worker = null; scope?.cancel(); scope = null }
    private fun start() {
        if (!canDraw || pending == null || worker?.isActive == true) return
        val owner = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        worker = owner.launch {
            while (canDraw) {
                val requested = pending ?: break
                pending = null
                val result = try {
                    withContext(Dispatchers.Default) {
                        val context = currentCoroutineContext()
                        calculate(requested.data) { context.ensureActive() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (canDraw && same(requested, latest)) {
                    if (result != null) { calculated = result; displayed = requested; error = null }
                    else error = "原始采样绘图暂不可用"
                    invalidate(); onStatusChanged?.invoke()
                }
            }
        }
    }
}

internal data class SpectrumSpectrumFrame(val rate: Int, val n: Int, val frequencies: DoubleArray,
    val levels: List<DoubleArray?>, val notice: String?)

/** Real full-rate PCM, periodic Hann coherent-gain correction, single-sided per-bin peak amplitude. */
internal class SpectrumSpectrumView(context: Context) : SpectrumPcmChart<SpectrumSpectrumFrame>(context) {
    private val plot = RectF(); private var cursor: Float? = null; private var held: SpectrumSpectrumFrame? = null
    private val gesture = SpectrumChartCursor(this, { x, y ->
        if (plot.contains(x, y) && calculated != null) { held = calculated; true } else false
    }, { cursor = it.coerceIn(plot.left, plot.right); invalidate() }, { held = null; cursor = null; invalidate() })
    override fun onPause() { super.onPause(); gesture.clear() }
    override fun calculate(data: SpectrumPcmTelemetry.Snapshot, cancel: () -> Unit): SpectrumSpectrumFrame {
        require(data.rate in 8000..768000 && data.frames >= 64)
        val channels = listOf(data.alignedDryLeft, data.alignedDryRight, data.wetLeft, data.wetRight, data.outputLeft, data.outputRight)
        require(channels.all { it.size >= data.frames })
        val available = min(data.frames, channels.minOf { it.size }).coerceAtMost(8192)
        var n = 1; while (n * 2 <= available) n *= 2
        require(n >= 64)
        val start = data.frames - n
        val window = DoubleArray(n) { .5 - .5 * cos(2 * PI * it / n) }; val sum = window.sum()
        fun transform(samples: FloatArray): DoubleArray {
            val real = DoubleArray(n); val imaginary = DoubleArray(n)
            for (i in 0 until n) { if (i % 128 == 0) cancel(); val v = samples[start + i].toDouble(); require(v.isFinite()); real[i] = v * window[i] }
            spectrumPreviewFft(real, imaginary, cancel)
            return DoubleArray(n / 2 + 1) { bin ->
                val multiplier = if (bin == 0 || bin == n / 2) 1.0 else 2.0
                hypot(real[bin], imaginary[bin]) * multiplier / sum
            }
        }
        val valid = booleanArrayOf(data.alignedDryValid, data.wetValid, data.outputValid)
        val levels = List(3) { series ->
            if (!valid[series]) null else {
                val a = transform(channels[series * 2]); val b = transform(channels[series * 2 + 1])
                DoubleArray(a.size) { i ->
                    val amplitude = sqrt((a[i] * a[i] + b[i] * b[i]) * .5)
                    if (amplitude > 0) 20 * log10(amplitude) else Double.NEGATIVE_INFINITY
                }
            }
        }
        val notice = when {
            !data.alignedDryValid -> "此窗对齐延时变化：不绘不稳定干声参考"
            !data.wetValid -> "纯激励支路暂不可用，不绘假零线"
            data.fading || data.routeFading -> "当前为跨倍频/试听路由的真实过渡窗口"
            data.wetIsNormalConfiguration -> "纯激励支路非THD数值 · 已对齐${data.commonDelayFrames}样本"
            else -> "以当前采样元数据标定声源"
        }
        return SpectrumSpectrumFrame(data.rate, n, DoubleArray(n / 2 + 1) { it.toDouble() * data.rate / n }, levels, notice)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        plot.set((if (compact) 21f else 30f) * dp, (if (compact) 11f else 34f) * dp, width - 5f * dp, height - (if (compact) 7f else 16f) * dp)
        if (plot.width() <= 0 || plot.height() <= 0) return
        val frame = held ?: calculated
        val rate = frame?.rate ?: configuration?.rate ?: 48000
        val highHz = min(20000.0, rate / 2.0)
        fun x(hz: Double) = plot.left + (ln(hz / 20) / ln(highHz / 20)).toFloat() * plot.width()
        fun y(db: Double) = plot.bottom - ((db.coerceIn(-120.0, 12.0) + 120) / 132).toFloat() * plot.height()
        val status = when { held != null -> "锁定窗口"; !isCurrentWindow && frame != null -> "末次有效窗口"; else -> "真实PCM" }
        label(canvas, if (frame == null) "对齐原声 / 纯激励支路 FFT · 等待PCM" else "$status · N${frame.n} · ${fmt(frame.n * 1000.0 / frame.rate, 2)}ms · Δf${fmt(frame.rate.toDouble() / frame.n, 1)}Hz",
            2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        val names = listOf("对齐干声", "纯激励支路", "所选试听输出")
        if (!compact) {
            names.forEachIndexed { i, name -> label(canvas, name + if (frame?.levels?.get(i) == null) "—" else "", plot.left + plot.width() * (i + .5f) / 3,
                21f * dp, plot.width() / 3, colors[i], Paint.Align.CENTER) }
            label(canvas, "Hann单边峰幅 · 双声道均方合并，非PSD", plot.left + 2f * dp, 32f * dp, plot.width() - 4f * dp)
        }
        axes(canvas, plot, -120.0, 12.0, "dBFS")
        canvas.drawLine(plot.left, y(0.0), plot.right, y(0.0), grid)
        canvas.save(); canvas.clipRect(plot)
        if (frame != null) frame.levels.forEachIndexed { series, levels ->
            if (levels == null) return@forEachIndexed
            path.rewind(); var started = false
            for (bin in 1 until frame.frequencies.size) {
                val hz = frame.frequencies[bin]
                if (hz < 20 || hz > highHz) continue
                val xx = x(hz); val yy = y(levels[bin])
                if (started) path.lineTo(xx, yy) else path.moveTo(xx, yy)
                started = true
            }
            line.color = ColorUtils.setAlphaComponent(colors[series], if (isCurrentWindow || held != null) 235 else 110)
            line.pathEffect = if (series == 2) dashed else null
            canvas.drawPath(path, line); line.pathEffect = null
        }
        var selected = 0
        cursor?.let { xx ->
            if (frame != null) {
                val hz = 20 * (highHz / 20).pow(((xx - plot.left) / plot.width()).toDouble())
                val lastVisible = min(frame.frequencies.lastIndex, floor(highHz * frame.n / frame.rate).toInt()).coerceAtLeast(1)
                selected = (hz * frame.n / frame.rate).roundToInt().coerceIn(1, lastVisible)
                val snapped = x(frame.frequencies[selected].coerceAtLeast(20.0))
                canvas.drawLine(snapped, plot.top, snapped, plot.bottom, grid)
                frame.levels.forEachIndexed { i, levels -> levels?.get(selected)?.let { point(canvas, snapped, y(it), colors[i]) } }
            }
        }
        canvas.restore()
        label(canvas, "20Hz", plot.left, height - 2f * dp, 30f * dp)
        label(canvas, "${fmt(highHz / 1000, 1)}kHz", plot.right, height - 2f * dp, plot.width() * .3f, foreground, Paint.Align.RIGHT)
        val reason = pcmReason ?: if (frame == null) "正在计算实际采样窗口" else null
        if (reason != null) label(canvas, reason, plot.centerX(), plot.centerY(), plot.width() - 4f * dp, foreground, Paint.Align.CENTER)
        else if (!compact && cursor == null) label(canvas, frame?.notice.orEmpty(), plot.left + 2f * dp, plot.bottom - 3f * dp, plot.width() - 4f * dp)
        if (cursor != null && frame != null) {
            fun level(v: Double?) = if (v == Double.NEGATIVE_INFINITY) "−∞" else fmt(v, 2)
            tooltip(canvas, listOf("bin $selected · ${fmt(frame.frequencies[selected], 2)}Hz · ${fmt(frame.n * 1000.0 / frame.rate, 2)}ms") + names.mapIndexed { i, name -> "$name ${level(frame.levels[i]?.get(selected))}dBFS" },
                plot.left, plot.top + 2f * dp)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** In-place radix-2 forward FFT, with cooperative cancellation and no zero padding. */
private fun spectrumPreviewFft(real: DoubleArray, imaginary: DoubleArray, cancel: () -> Unit) {
    val n = real.size; var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
        if (i < j) { val r = real[i]; real[i] = real[j]; real[j] = r; val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im }
    }
    var length = 2
    while (length <= n) {
        cancel(); val angle = -2 * PI / length; val wr = cos(angle); val wi = sin(angle)
        for (start in 0 until n step length) {
            var ur = 1.0; var ui = 0.0
            for (k in 0 until length / 2) {
                val a = start + k; val b = a + length / 2
                val vr = real[b] * ur - imaginary[b] * ui; val vi = real[b] * ui + imaginary[b] * ur
                real[b] = real[a] - vr; imaginary[b] = imaginary[a] - vi; real[a] += vr; imaginary[a] += vi
                val next = ur * wr - ui * wi; ui = ur * wi + ui * wr; ur = next
            }
        }
        length = length shl 1
    }
}

/** Vertical scrolling wins until a deliberate long press begins inspection. */
private class SpectrumChartCursor(private val view: View, private val begin: (Float, Float) -> Boolean,
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
