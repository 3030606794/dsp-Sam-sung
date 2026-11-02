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
import kotlinx.coroutines.*
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbPcmTelemetry
import java.util.Locale
import kotlin.math.*

/** Canvas-only observers. There is no timer, audio listener, preference write or audition mutation. */
internal abstract class RoomReverbDashboardChart(context: Context) : View(context) {
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
    protected var configuration: RoomReverbDashboardModel? = null
        private set
    protected var sample: RoomReverbDashboardRuntimeMonitor.Sample? = null
        private set
    protected var pauseReason = "监视已关闭"
        private set
    private var active = false
    private var attached = false
    protected val canDraw get() = active && attached && visibility == VISIBLE && windowVisibility == VISIBLE
    protected val compact get() = height < 60f * dp
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        text.textSize = if (compact) min(6.5f * resources.displayMetrics.scaledDensity, 7.5f * dp)
            else min(9f * resources.displayMetrics.scaledDensity, 11f * dp)
    }
    fun setModel(value: RoomReverbDashboardModel) {
        if (configuration === value) return
        configuration = value; onModelChanged(); if (canDraw) invalidate()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; onActivationChanged(); if (canDraw) invalidate()
    }
    fun showSample(value: RoomReverbDashboardRuntimeMonitor.Sample) {
        sample = value; onSample(value); if (canDraw) invalidate()
    }
    fun pause(reason: String) { pauseReason = reason; sample = null; onPause(); if (canDraw) invalidate() }
    protected open fun onModelChanged() = Unit
    protected open fun onSample(value: RoomReverbDashboardRuntimeMonitor.Sample) = Unit
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

/** Fixed log-time reference: slider changes move tails and early source taps geometrically. */
internal class RoomReverbDecayView(context: Context) : RoomReverbDashboardChart(context) {
    private val plot = RectF(); private var cursor: Float? = null
    private val gesture = RoomReverbChartCursor(this, { x, y -> plot.contains(x, y) }, { cursor = it.coerceIn(plot.left, plot.right); invalidate() }, { cursor = null; invalidate() })
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val value = configuration ?: return
        plot.set((if (compact) 19f else 27f) * dp, (if (compact) 11f else 24f) * dp, width - 5f * dp, height - (if (compact) 7f else 16f) * dp)
        if (plot.width() <= 0 || plot.height() <= 0) return
        val endMs = 30000.0; val scale = 20.0; val norm = ln(1 + endMs / scale)
        fun time(fraction: Double) = scale * (exp(norm * fraction) - 1)
        fun x(ms: Double) = plot.left + (ln(1 + ms / scale) / norm).toFloat() * plot.width()
        fun y(amplitude: Double) = plot.bottom - amplitude.toFloat() * plot.height()
        val cut = value.lateOnsetMs + value.rt60Seconds * 1000 > endMs
        label(canvas, "RT60 ${fmt(value.rt60Seconds, 2)}s · 固定对数0–30s${if (cut) " · 尾部截断" else ""}", 2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        if (!compact) label(canvas, "紫 指数参考包络 / 青 早反射源抽头（非实测IR）", 2f * dp, 21f * dp, width - 4f * dp)
        axes(canvas, plot, 0.0, 100.0, "%")
        canvas.save(); canvas.clipRect(plot)
        fill.color = ColorUtils.setAlphaComponent(foreground, 0x18)
        canvas.drawRect(plot.left, plot.top, x(value.predelayMs), plot.bottom, fill)
        for (ms in listOf(100.0, 1000.0)) canvas.drawLine(x(ms), plot.top, x(ms), plot.bottom, grid)
        for (channel in 0..1) for (i in value.earlyTapMs[channel].indices) {
            val xx = x(value.earlyTapMs[channel][i])
            line.color = ColorUtils.setAlphaComponent(colors[2], if (channel == 0) 155 else 90)
            canvas.drawLine(xx, plot.bottom, xx, y((.75 / (1 + i * .18)).coerceIn(0.0, 1.0)), line)
        }
        path.rewind()
        val onset = ln(1 + value.lateOnsetMs / scale) / norm
        for (i in 0..120) {
            val ms = if (i == 0) value.lateOnsetMs else time(onset + (1 - onset) * i / 120)
            if (i == 0) path.moveTo(x(ms), y(1.0)) else path.lineTo(x(ms), y(value.tailAmplitude(ms)))
        }
        line.color = colors[0]; canvas.drawPath(path, line)
        cursor?.let { canvas.drawLine(it, plot.top, it, plot.bottom, grid) }
        canvas.restore()
        label(canvas, "0", plot.left, height - 2f * dp, 13f * dp)
        for (ms in listOf(100.0, 1000.0)) label(canvas, if (ms == 100.0) ".1" else "1", x(ms), height - 2f * dp, plot.width() * .2f, foreground, Paint.Align.CENTER)
        label(canvas, "30s", plot.right, height - 2f * dp, plot.width() * .25f, foreground, Paint.Align.RIGHT)
        cursor?.let { xx ->
            val ms = time(((xx - plot.left) / plot.width()).toDouble())
            val nearest = value.earlyTapMs.flatMap { it.toList() }.minByOrNull { abs(it - ms) }
            tooltip(canvas, listOf("${fmt(ms, 2)}ms · 尾包络 ${fmt(value.tailAmplitude(ms) * 100, 2)}%", "最近早反射源抽头 ${fmt(nearest, 2)}ms"), plot.left, plot.top + 2f * dp)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** The two layers encode early/late width coefficients, never metres or measured sound directions. */
internal class RoomReverbSpaceView(context: Context) : RoomReverbDashboardChart(context) {
    private val oval = RectF()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val value = configuration ?: return
        label(canvas, "双层宽度模型 · 早反射 / 尾音", 2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        val top = (if (compact) 11f else 19f) * dp; val bottom = height - (if (compact) 6f else 16f) * dp
        val cx = width * .35f; val cy = bottom; val radius = min(width * .32f, bottom - top)
        if (radius <= 0) return
        for (ring in 1..3) { val r = radius * ring / 3; oval.set(cx - r, cy - r, cx + r, cy + r); canvas.drawArc(oval, 180f, 180f, false, grid) }
        canvas.drawLine(cx, top, cx, bottom, grid); canvas.drawLine(cx - radius, cy, cx + radius, cy, grid)
        val widths = doubleArrayOf(value.lateWidth, value.earlyWidth)
        for (layer in 0..1) {
            val spread = 5 + abs(widths[layer]) * 80
            val scale = if (layer == 0) .5 + .48 * (value.rt60Seconds / 30).pow(.35) else .28 + .32 * value.earlyScale / 2.5
            val r = radius * scale.toFloat()
            oval.set(cx - r, cy - r, cx + r, cy + r)
            fill.color = ColorUtils.setAlphaComponent(colors[if (layer == 0) 0 else 2], if (layer == 0) 45 else 95)
            canvas.drawArc(oval, (270 - spread).toFloat(), (spread * 2).toFloat(), true, fill)
            line.color = colors[if (layer == 0) 0 else 2]; line.pathEffect = if (value.enabled) null else dashed
            canvas.drawArc(oval, (270 - spread).toFloat(), (spread * 2).toFloat(), true, line); line.pathEffect = null
        }
        // A negative early width makes cross-channel weight exceed the direct weight.
        if (value.earlyWidth < 0) {
            line.color = colors[2]; canvas.drawLine(cx - radius * .4f, cy - radius * .25f, cx + radius * .4f, cy - radius * .55f, line)
            canvas.drawLine(cx + radius * .4f, cy - radius * .25f, cx - radius * .4f, cy - radius * .55f, line)
        }
        point(canvas, cx, cy, foreground)
        val xx = width * .69f; val available = width - xx - 2f * dp
        label(canvas, "早 ${fmt(value.earlyWidth, 3)}", xx, (if (compact) 17f else 28f) * dp, available, colors[2])
        label(canvas, "尾 ${fmt(value.lateWidth, 3)}", xx, (if (compact) 25f else 41f) * dp, available, colors[0])
        if (!compact) {
            label(canvas, if (value.earlyWidth < 0) "早反射交叉占优" else "参数扇区非实测", xx, 54f * dp, available)
            label(canvas, "时间尺度 ${fmt(value.earlyScale, 2)}×", xx, 67f * dp, available)
            label(canvas, "非房间尺寸/方向测量", cx, height - 3f * dp, width * .65f, foreground, Paint.Align.CENTER)
        }
    }
}

internal class RoomReverbDampingView(context: Context) : RoomReverbDashboardChart(context) {
    private val plot = RectF(); private var cursor: Float? = null
    private val gesture = RoomReverbChartCursor(this, { x, y -> plot.contains(x, y) }, { cursor = it.coerceIn(plot.left, plot.right); invalidate() }, { cursor = null; invalidate() })
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val value = configuration ?: return
        plot.set((if (compact) 19f else 27f) * dp, (if (compact) 11f else 24f) * dp, width - 5f * dp, height - (if (compact) 7f else 16f) * dp)
        if (plot.width() <= 0 || plot.height() <= 0) return
        label(canvas, "滤波包络模型 · 非完整混响频响", 2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        if (!compact) label(canvas, "紫 湿声切频 / 青 反馈阻尼 / 橙 原尾音入出滤波", 2f * dp, 21f * dp, width - 4f * dp)
        axes(canvas, plot, -60.0, 0.0, "dB")
        fun x(i: Int) = plot.left + i.toFloat() / value.frequencies.lastIndex * plot.width()
        fun y(db: Double) = plot.bottom - ((db + 60) / 60).toFloat() * plot.height()
        canvas.save(); canvas.clipRect(plot)
        listOf(value.wetFilterDb, value.dampingDb, value.inputOutputDb).forEachIndexed { row, values ->
            path.rewind(); values.forEachIndexed { i, db -> if (i == 0) path.moveTo(x(i), y(db)) else path.lineTo(x(i), y(db)) }
            line.color = colors[if (row == 1) 2 else if (row == 2) 1 else 0]; line.pathEffect = if (row == 1) dashed else null
            canvas.drawPath(path, line); line.pathEffect = null
        }
        cursor?.let { canvas.drawLine(it, plot.top, it, plot.bottom, grid) }
        canvas.restore()
        label(canvas, "20", plot.left, height - 2f * dp, 20f * dp)
        label(canvas, "${fmt(value.frequencies.last() / 1000, 1)}kHz", plot.right, height - 2f * dp, plot.width() * .35f, foreground, Paint.Align.RIGHT)
        cursor?.let { xx ->
            val i = (((xx - plot.left) / plot.width()) * value.frequencies.lastIndex).roundToInt().coerceIn(value.frequencies.indices)
            tooltip(canvas, listOf("${fmt(value.frequencies[i], 1)}Hz · 湿切频 ${fmt(value.wetFilterDb[i], 2)}dB", "阻尼 ${fmt(value.dampingDb[i], 2)} / 原尾音滤波 ${fmt(value.inputOutputDb[i], 2)}dB"), plot.left, plot.top + 2f * dp)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Relative control gains. Dry has more than one delayed path, so these are not measured levels. */
internal class RoomReverbLevelsView(context: Context) : RoomReverbDashboardChart(context) {
    private val plot = RectF(); private var selected: Int? = null
    private val gesture = RoomReverbChartCursor(this, { x, y -> plot.contains(x, y) }, { x -> selected = (((x - plot.left) / plot.width()) * 3).toInt().coerceIn(0, 2); invalidate() }, { selected = null; invalidate() })
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val value = configuration ?: return
        plot.set((if (compact) 19f else 27f) * dp, (if (compact) 11f else 24f) * dp, width - 5f * dp, height - (if (compact) 7f else 16f) * dp)
        if (plot.width() <= 0 || plot.height() <= 0) return
        label(canvas, "三路${if (value.linked) "联动" else "相对房型"}增益 · 非测量电平", 2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        if (!compact) label(canvas, "已含模块输出增益 · −∞表示该路关闭", 2f * dp, 21f * dp, width - 4f * dp)
        val low = -36.0; val high = 12.0
        fun y(db: Double) = plot.bottom - ((db.coerceIn(low, high) - low) / (high - low)).toFloat() * plot.height()
        axes(canvas, plot, low, high, "dB")
        canvas.drawLine(plot.left, y(0.0), plot.right, y(0.0), grid)
        val names = listOf("干声", "早反射", "尾音")
        names.forEachIndexed { i, name ->
            val cx = plot.left + plot.width() * (i + .5f) / 3
            val db = value.relativeGainDb[i]
            fill.color = ColorUtils.setAlphaComponent(colors[i], if (selected == i) 210 else 120)
            canvas.drawRect(cx - plot.width() / 12, min(y(db), y(0.0)), cx + plot.width() / 12, max(y(db), y(0.0)) + dp, fill)
            point(canvas, cx, y(db), colors[i])
            label(canvas, name, cx, height - 2f * dp, plot.width() / 3, foreground, Paint.Align.CENTER)
            if (!compact) label(canvas, if (db.isFinite()) "${fmt(db, 1)}dB" else "−∞", cx, max(plot.top + 8f * dp, y(db) - 3f * dp), plot.width() / 3, colors[i], Paint.Align.CENTER)
        }
        selected?.let { i -> tooltip(canvas, listOf("${names[i]}相对系数 ${if (value.relativeGainDb[i].isFinite()) fmt(value.relativeGainDb[i], 2) else "−∞"}dB", "原房型基础 ${fmt(value.baseLevelDb[i], 1)}dB · 非支路RMS"), plot.left, plot.top + 2f * dp) }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Latest-only bounded worker. A hidden chart retains the newest sample but performs no DSP math. */
internal abstract class RoomReverbPcmChart<T>(context: Context) : RoomReverbDashboardChart(context) {
    protected var calculated: T? = null
        private set
    private data class Request(val data: RoomReverbPcmTelemetry.Snapshot, val source: Any?)
    private var latest: Request? = null; private var pending: Request? = null; private var displayed: Request? = null
    private var scope: CoroutineScope? = null; private var worker: Job? = null
    private var error: String? = null
    protected val currentWindow get() = latest?.data
    protected val displayedWindow get() = displayed?.data
    protected val isCurrentWindow get() = same(latest, displayed)
    protected val pcmReason: String? get() = sample?.pcmUnavailableReason
        ?: if (sample == null) pauseReason else if (latest == null) "驱动尚未提供原始音频窗口" else error
    protected abstract fun calculate(data: RoomReverbPcmTelemetry.Snapshot, cancel: () -> Unit): T
    private fun same(a: Request?, b: Request?): Boolean = a != null && b != null && a.source === b.source &&
        a.data.sequence == b.data.sequence && a.data.generation == b.data.generation &&
        a.data.rate == b.data.rate && a.data.firstFramePosition == b.data.firstFramePosition && a.data.frames == b.data.frames
    override fun onSample(value: RoomReverbDashboardRuntimeMonitor.Sample) {
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

internal data class RoomReverbSpectrumFrame(val rate: Int, val n: Int, val frequencies: DoubleArray,
    val levels: List<DoubleArray?>, val notice: String?)

/** Real full-rate PCM, periodic Hann coherent-gain correction, single-sided per-bin peak amplitude. */
internal class RoomReverbSpectrumView(context: Context) : RoomReverbPcmChart<RoomReverbSpectrumFrame>(context) {
    private val plot = RectF(); private var cursor: Float? = null; private var held: RoomReverbSpectrumFrame? = null
    private val gesture = RoomReverbChartCursor(this, { x, y ->
        if (plot.contains(x, y) && calculated != null) { held = calculated; true } else false
    }, { cursor = it.coerceIn(plot.left, plot.right); invalidate() }, { held = null; cursor = null; invalidate() })
    override fun onPause() { super.onPause(); gesture.clear() }
    override fun calculate(data: RoomReverbPcmTelemetry.Snapshot, cancel: () -> Unit): RoomReverbSpectrumFrame {
        require(data.rate in 8000..768000 && data.frames >= 64)
        val channels = listOf(data.inputLeft, data.inputRight, data.wetLeft, data.wetRight, data.outputLeft, data.outputRight)
        require(channels.all { it.size >= data.frames })
        val available = min(data.frames, channels.minOf { it.size }).coerceAtMost(8192)
        var n = 1; while (n * 2 <= available) n *= 2
        require(n >= 64)
        val start = data.frames - n
        val window = DoubleArray(n) { .5 - .5 * cos(2 * PI * it / n) }; val sum = window.sum()
        fun transform(samples: FloatArray): DoubleArray {
            val real = DoubleArray(n); val imaginary = DoubleArray(n)
            for (i in 0 until n) { if (i % 128 == 0) cancel(); val v = samples[start + i].toDouble(); require(v.isFinite()); real[i] = v * window[i] }
            roomPreviewFft(real, imaginary, cancel)
            return DoubleArray(n / 2 + 1) { bin ->
                val multiplier = if (bin == 0 || bin == n / 2) 1.0 else 2.0
                hypot(real[bin], imaginary[bin]) * multiplier / sum
            }
        }
        val valid = booleanArrayOf(data.inputValid, data.wetValid, data.outputValid)
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
            !data.wetValid -> "湿声不可分离：原版路径不绘假零线"
            data.fading -> "当前为真实过渡窗口"
            data.wetIsNormalConfiguration -> "湿声=保存配置；输出=所选试听"
            else -> "以当前采样元数据标定声源"
        }
        return RoomReverbSpectrumFrame(data.rate, n, DoubleArray(n / 2 + 1) { it.toDouble() * data.rate / n }, levels, notice)
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
        label(canvas, if (frame == null) "原声 / 湿声 FFT · 等待PCM" else "$status · N${frame.n} · ${fmt(frame.n * 1000.0 / frame.rate, 2)}ms · Δf${fmt(frame.rate.toDouble() / frame.n, 1)}Hz",
            2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        val names = listOf("原声输入", "保存配置湿声", "所选试听输出")
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

internal data class RoomReverbVectorFrame(val rate: Int, val n: Int, val left: FloatArray, val right: FloatArray,
    val trajectory: Path, val rho: Double?, val rmsL: Double?, val rmsR: Double?, val peak: Double, val valid: Boolean)

/** Real output vectors. Screen -Side places a left-only signal on the upper-left L axis. */
internal class RoomReverbWaterfallView(context: Context) : RoomReverbPcmChart<RoomReverbVectorFrame>(context) {
    private data class Energy(val time: Long, val rmsL: Double?, val rmsR: Double?, val peakL: Double?, val peakR: Double?)
    private val history = ArrayList<Energy>(180)
    private val plot = RectF(); private var cx = 0f; private var cy = 0f; private var unitScale = 1f
    private var touchY = 0f; private var selected: Int? = null; private var held: RoomReverbVectorFrame? = null
    private val gesture = RoomReverbChartCursor(this, { x, y ->
        if (plot.contains(x, y) && calculated?.valid == true) { held = calculated; true } else false
    }, { xx ->
        val frame = held
        if (frame != null) {
            var distance = Float.POSITIVE_INFINITY; var found = 0
            for (i in frame.left.indices) {
                val x = cx + (frame.right[i] - frame.left[i]) * (unitScale / sqrt(2f))
                val y = cy - (frame.left[i] + frame.right[i]) * (unitScale / sqrt(2f))
                val d = (x - xx) * (x - xx) + (y - touchY) * (y - touchY)
                if (d < distance) { distance = d; found = i }
            }
            selected = found; invalidate()
        }
    }, { held = null; selected = null; invalidate() })
    override fun onSample(value: RoomReverbDashboardRuntimeMonitor.Sample) {
        super.onSample(value)
        while (history.isNotEmpty() && (history.first().time < value.elapsedRealtimeMs - 20000 || history.size >= 160)) history.removeAt(0)
        fun valid(v: Float?) = v?.toDouble()?.takeIf { it.isFinite() }
        val a = valid(value.outputRmsLeftDbfs); val b = valid(value.outputRmsRightDbfs)
        val c = valid(value.outputPeakLeftDbfs); val d = valid(value.outputPeakRightDbfs)
        if (a != null || b != null || c != null || d != null) history += Energy(value.elapsedRealtimeMs, a, b, c, d)
    }
    override fun onPause() { super.onPause(); gesture.clear() }
    override fun calculate(data: RoomReverbPcmTelemetry.Snapshot, cancel: () -> Unit): RoomReverbVectorFrame {
        if (!data.outputValid) return RoomReverbVectorFrame(data.rate, 0, FloatArray(0), FloatArray(0), Path(), null, null, null, 0.0, false)
        val n = min(data.frames, min(data.outputLeft.size, data.outputRight.size)).coerceAtMost(8192)
        require(n > 0 && data.rate in 8000..768000)
        require(data.outputLeft.size >= data.frames && data.outputRight.size >= data.frames)
        val left = data.outputLeft.copyOfRange(data.frames - n, data.frames)
        val right = data.outputRight.copyOfRange(data.frames - n, data.frames)
        var ll = 0.0; var rr = 0.0; var lr = 0.0; var peak = 0.0
        val trajectory = Path()
        for (i in 0 until n) {
            if (i % 128 == 0) cancel()
            val l = left[i].toDouble(); val r = right[i].toDouble(); require(l.isFinite() && r.isFinite())
            ll += l * l; rr += r * r; lr += l * r; peak = max(peak, max(abs(l), abs(r)))
            val x = ((r - l) / sqrt(2.0)).toFloat(); val y = ((l + r) / sqrt(2.0)).toFloat()
            if (i == 0) trajectory.moveTo(x, y) else trajectory.lineTo(x, y)
        }
        fun db(v: Double): Double? = if (v > 0) 20 * log10(v) else null
        return RoomReverbVectorFrame(data.rate, n, left, right, trajectory,
            if (ll > 1e-20 && rr > 1e-20) (lr / sqrt(ll * rr)).coerceIn(-1.0, 1.0) else null,
            db(sqrt(ll / n)), db(sqrt(rr / n)), peak, true)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        plot.set(6f * dp, (if (compact) 11f else 24f) * dp, width - 6f * dp, height - (if (compact) 6f else 17f) * dp)
        if (plot.width() <= 0 || plot.height() <= 0) return
        val frame = held ?: calculated
        val radius = min(plot.width() * .34f, plot.height() * .46f)
        cx = plot.centerX(); cy = plot.centerY(); unitScale = radius / sqrt(2f)
        label(canvas, "输出L/R相位矢量 · 固定1FS比例${if (!isCurrentWindow && frame != null) " · 末次窗口" else ""}",
            2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        if (!compact) label(canvas, "L在左上 / R在右上 · 中心0 · 水平反相 / 垂直同相", 2f * dp, 22f * dp, width - 4f * dp)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, grid); canvas.drawLine(cx, cy - radius, cx, cy + radius, grid)
        path.rewind(); path.moveTo(cx, cy - radius); path.lineTo(cx + radius, cy); path.lineTo(cx, cy + radius); path.lineTo(cx - radius, cy); path.close()
        canvas.drawPath(path, grid)
        canvas.drawLine(cx - radius * .5f, cy - radius * .5f, cx + radius * .5f, cy + radius * .5f, grid)
        canvas.drawLine(cx + radius * .5f, cy - radius * .5f, cx - radius * .5f, cy + radius * .5f, grid)
        if (frame?.valid == true) {
            canvas.save(); canvas.clipRect(plot); canvas.translate(cx, cy); canvas.scale(unitScale, -unitScale)
            line.color = ColorUtils.setAlphaComponent(if (frame.peak > 1) red else colors[0], if (isCurrentWindow || held != null) 185 else 80)
            line.strokeWidth = max(.6f * dp, dp) / unitScale
            canvas.drawPath(frame.trajectory, line); line.strokeWidth = 1.5f * dp
            canvas.restore()
        }
        point(canvas, cx, cy, foreground)
        label(canvas, "0", cx + 3f * dp, cy + 7f * dp, 10f * dp)
        label(canvas, "L", cx - radius * .55f, cy - radius * .55f, 13f * dp, colors[0], Paint.Align.RIGHT)
        label(canvas, "R", cx + radius * .55f, cy - radius * .55f, 13f * dp, colors[2])
        if (!compact) {
            label(canvas, "−R", cx - radius * .55f, cy + radius * .65f, 16f * dp, colors[2], Paint.Align.RIGHT)
            label(canvas, "−L", cx + radius * .55f, cy + radius * .65f, 16f * dp, colors[0])
            label(canvas, "ρ ${fmt(frame?.rho, 3)} · 窗 ${fmt(frame?.let { it.n * 1000.0 / it.rate }, 2)}ms · RMS L/R ${fmt(frame?.rmsL, 1)}/${fmt(frame?.rmsR, 1)}dBFS",
                2f * dp, height - 3f * dp, width - 4f * dp)
        }
        val reason = pcmReason ?: if (frame == null) "等待真实输出PCM窗口" else if (!frame.valid) "当前输出PCM无效" else null
        if (reason != null) label(canvas, reason, plot.centerX(), plot.centerY(), plot.width() - 4f * dp, foreground, Paint.Align.CENTER)
        val index = selected
        if (index != null && frame?.valid == true && index in frame.left.indices) {
            val l = frame.left[index].toDouble(); val r = frame.right[index].toDouble()
            val s = (l - r) / sqrt(2.0); val m = (l + r) / sqrt(2.0)
            point(canvas, cx - (s * unitScale).toFloat(), cy - (m * unitScale).toFloat(), colors[2])
            tooltip(canvas, listOf("样本$index · ${fmt(index * 1000.0 / frame.rate, 3)}ms", "L ${fmt(l, 5)} / R ${fmt(r, 5)}FS", "S ${fmt(s, 5)} / M ${fmt(m, 5)}FS"), plot.left, plot.top + 2f * dp)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean { touchY = event.y; return gesture.onTouchEvent(event) }
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** In-place radix-2 forward FFT, with cooperative cancellation and no zero padding. */
private fun roomPreviewFft(real: DoubleArray, imaginary: DoubleArray, cancel: () -> Unit) {
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
private class RoomReverbChartCursor(private val view: View, private val begin: (Float, Float) -> Boolean,
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
