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
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderPcmTelemetry
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderGainTelemetry
import java.util.Locale
import kotlin.math.*

/** Canvas-only observers. There is no timer, audio listener, preference write or audition mutation. */
internal abstract class CompanderRealtimeChart(context: Context) : View(context) {
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
    protected var configuration: CompanderDashboardModel? = null
        private set
    protected var sample: CompanderDashboardRuntimeMonitor.Sample? = null
        private set
    protected var pauseReason = "监视已关闭"
        private set
    private var active = false
    private var attached = false
    protected val canDraw get() = active && attached && visibility == VISIBLE && windowVisibility == VISIBLE
    protected val compact get() = height <= 64f * dp
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        text.textSize = if (compact) min(6.5f * resources.displayMetrics.scaledDensity, 7.5f * dp)
            else min(9f * resources.displayMetrics.scaledDensity, 11f * dp)
    }
    fun setModel(value: CompanderDashboardModel) {
        if (configuration === value) return
        configuration = value; onModelChanged(); if (canDraw) invalidate()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; onActivationChanged(); if (canDraw) invalidate()
    }
    fun showSample(value: CompanderDashboardRuntimeMonitor.Sample) {
        sample = value; onSample(value); if (canDraw) invalidate()
    }
    fun pause(reason: String) { pauseReason = reason; sample = null; onPause(); if (canDraw) invalidate() }
    protected open fun onModelChanged() = Unit
    protected open fun onSample(value: CompanderDashboardRuntimeMonitor.Sample) = Unit
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

/** Latest-only bounded worker. A hidden chart retains the newest sample but performs no DSP math. */
internal abstract class CompanderPcmChart<T>(context: Context) : CompanderRealtimeChart(context) {
    protected var calculated: T? = null
        private set
    private data class Request(val data: CompanderPcmTelemetry.Snapshot, val source: Any?)
    private var latest: Request? = null; private var pending: Request? = null; private var displayed: Request? = null
    private var scope: CoroutineScope? = null; private var worker: Job? = null
    private var error: String? = null
    protected val currentWindow get() = latest?.data
    protected val displayedWindow get() = displayed?.data
    protected val isCurrentWindow get() = same(latest, displayed)
    protected val pcmReason: String? get() = sample?.pcmUnavailableReason
        ?: if (sample == null) pauseReason else if (latest == null) "驱动尚未提供原始音频窗口" else error
    protected abstract fun calculate(data: CompanderPcmTelemetry.Snapshot, cancel: () -> Unit): T
    private fun same(a: Request?, b: Request?): Boolean = a != null && b != null && a.source === b.source &&
        a.data.sequence == b.data.sequence && a.data.generation == b.data.generation &&
        a.data.rate == b.data.rate && a.data.firstFramePosition == b.data.firstFramePosition && a.data.frames == b.data.frames
    override fun onSample(value: CompanderDashboardRuntimeMonitor.Sample) {
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

internal data class CompanderSpectrumFrame(val rate: Int, val n: Int, val frequencies: DoubleArray,
    val levels: List<DoubleArray?>, val notice: String?)

/** Real full-rate PCM, periodic Hann coherent-gain correction, single-sided per-bin peak amplitude. */
internal class CompanderSpectrumView(context: Context) : CompanderPcmChart<CompanderSpectrumFrame>(context) {
    private val plot = RectF(); private var cursor: Float? = null; private var held: CompanderSpectrumFrame? = null
    private val gesture = CompanderChartCursor(this, { x, y ->
        if (plot.contains(x, y) && calculated != null) { held = calculated; true } else false
    }, { cursor = it.coerceIn(plot.left, plot.right); invalidate() }, { held = null; cursor = null; invalidate() })
    override fun onPause() { super.onPause(); gesture.clear() }
    override fun calculate(data: CompanderPcmTelemetry.Snapshot, cancel: () -> Unit): CompanderSpectrumFrame {
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
            companderPreviewFft(real, imaginary, cancel)
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
            !data.wetValid -> "保存配置完整输出暂不可用，不绘假零线"
            data.fading || data.routeFading -> "当前为处理/试听路由的真实过渡窗口"
            data.wetIsNormalConfiguration -> "保存配置完整输出（含mix/输出增益）· 非独立湿分量"
            else -> "以当前采样元数据标定声源"
        }
        return CompanderSpectrumFrame(data.rate, n, DoubleArray(n / 2 + 1) { it.toDouble() * data.rate / n }, levels, notice)
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
        label(canvas, if (frame == null) "对齐原声 / 完整输出 FFT · 等待PCM" else "$status · N${frame.n} · ${fmt(frame.n * 1000.0 / frame.rate, 2)}ms · Δf${fmt(frame.rate.toDouble() / frame.n, 1)}Hz",
            2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        val names = listOf("对齐干声", "保存配置完整输出", "所选试听输出")
        if (!compact) {
            names.forEachIndexed { i, name -> label(canvas, name + if (frame?.levels?.get(i) == null) "—" else "", plot.left + plot.width() * (i + .5f) / 3,
                21f * dp, plot.width() / 3, colors[i], Paint.Align.CENTER) }
            label(canvas, "实际输出FFT · 原时频变换引擎" + (sample?.gain?.let { "${it.transform} / 粒度${it.granularity}" } ?: "（等待状态）"), plot.left + 2f * dp, 32f * dp, plot.width() - 4f * dp)
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

/** Seven samples of the one real frequency gain mask; never seven independent compressors. */
internal class CompanderGainReductionView(context: Context) : CompanderRealtimeChart(context) {
    private val plot = RectF(); private var cursor: Float? = null
    private var current: CompanderGainTelemetry.Snapshot? = null
    private var lastValid: CompanderGainTelemetry.Snapshot? = null
    private var held: CompanderGainTelemetry.Snapshot? = null
    private val gesture = CompanderChartCursor(this, { x, y ->
        if (plot.contains(x, y) && lastValid != null) { held = current ?: lastValid; true } else false
    }, { cursor = it.coerceIn(plot.left, plot.right); invalidate() }, { cursor = null; held = null; invalidate() })
    override fun onSample(value: CompanderDashboardRuntimeMonitor.Sample) {
        current = value.gain?.takeIf { it.available && it.frequenciesHz.size == it.gainsDb.size && it.gainsDb.size == 7 }
        current?.let { lastValid = it }
    }
    override fun onPause() { current = null; gesture.clear() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        plot.set((if (compact) 22f else 30f) * dp, (if (compact) 12f else 35f) * dp,
            width - 5f * dp, height - (if (compact) 8f else 16f) * dp)
        if (plot.width() <= 0 || plot.height() <= 0) return
        val frame = held ?: current ?: lastValid
        val status = if (held != null) "锁定" else if (current == null && frame != null) "末次有效" else "实际增益"
        label(canvas, "$status · 七频点采样（非七独立压缩器）", 2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        if (!compact) {
            val engine = when (frame?.engine) { 0 -> "原版：实际频率增益掩模"; 1 -> "标准：同一宽带增益按范围作用"; else -> "等待实际控制掩模" }
            label(canvas, engine, 2f * dp, 21f * dp, width - 4f * dp)
            label(canvas, frame?.let { "采样${it.rate}Hz · 变换${it.transform} / 粒度${it.granularity} · 控制窗${it.controlBlockFrames}采样" } ?: "正值提升 / 负值衰减 · 来自驱动实际控制值", plot.left, 32f * dp, plot.width())
        }
        val highHz = min(20000.0, (frame?.rate ?: configuration?.rate ?: 48000) * .5)
        fun x(hz: Double) = plot.left + (ln(hz.coerceIn(20.0, highHz) / 20) / ln(highHz / 20)).toFloat() * plot.width()
        fun y(db: Double) = plot.bottom - ((db + 36) / 60).toFloat() * plot.height()
        axes(canvas, plot, -36.0, 24.0, "dB")
        val zero = y(0.0)
        canvas.drawLine(plot.left, zero, plot.right, zero, grid)
        for (hz in listOf(20.0, 100.0, 1000.0, 10000.0, highHz).distinct()) {
            if (hz > highHz) continue
            canvas.drawLine(x(hz), plot.top, x(hz), plot.bottom, grid)
            label(canvas, if (hz >= 1000) "${fmt(hz / 1000, 0)}k" else fmt(hz, 0), x(hz), height - 2f * dp,
                plot.width() * .18f, foreground, if (hz == 20.0) Paint.Align.LEFT else if (hz == highHz) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        var selected = 0
        canvas.save(); canvas.clipRect(plot)
        if (frame != null) {
            val points = frame.frequenciesHz.indices.filter { frame.frequenciesHz[it] in 20.0..highHz && frame.gainsDb[it].isFinite() }
            val opacity = if (current === frame || held != null) 235 else 90
            if (points.isNotEmpty()) {
                path.rewind(); path.moveTo(x(frame.frequenciesHz[points.first()]), zero)
                points.forEach { path.lineTo(x(frame.frequenciesHz[it]), y(frame.gainsDb[it])) }
                path.lineTo(x(frame.frequenciesHz[points.last()]), zero); path.close()
                fill.color = ColorUtils.setAlphaComponent(colors[0], if (opacity > 100) 40 else 16); canvas.drawPath(path, fill)
                path.rewind()
                points.forEachIndexed { i, node ->
                    val xx = x(frame.frequenciesHz[node]); val yy = y(frame.gainsDb[node])
                    if (i == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
                    line.color = ColorUtils.setAlphaComponent(if (frame.gainsDb[node] < 0) colors[1] else colors[2], opacity)
                    line.strokeWidth = 2f * dp; canvas.drawLine(xx, zero, xx, yy, line)
                    point(canvas, xx, yy, line.color)
                }
                line.color = ColorUtils.setAlphaComponent(colors[0], opacity); line.strokeWidth = 1.5f * dp
                line.pathEffect = if (current == null && held == null) dashed else null
                canvas.drawPath(path, line); line.pathEffect = null
                cursor?.let { xx ->
                    val hz = 20 * (highHz / 20).pow(((xx - plot.left) / plot.width()).toDouble())
                    selected = points.minByOrNull { abs(ln(frame.frequenciesHz[it] / hz)) } ?: points.first()
                    val snapped = x(frame.frequenciesHz[selected])
                    canvas.drawLine(snapped, plot.top, snapped, plot.bottom, grid)
                    point(canvas, snapped, y(frame.gainsDb[selected]), colors[0])
                }
            }
        }
        canvas.restore()
        val reason = sample?.gainUnavailableReason ?: if (sample == null) pauseReason else if (current == null) "等待实际增益掩模" else null
        if (reason != null) label(canvas, reason, plot.centerX(), plot.centerY(), plot.width() - 4f * dp, foreground, Paint.Align.CENTER)
        else if (!compact && cursor == null && frame?.gainsDb?.any { it < -36 || it > 24 } == true)
            label(canvas, "超出固定−36..+24dB显示窗：游标仍读实际值", plot.left, plot.bottom - 3f * dp, plot.width())
        if (cursor != null && frame != null) {
            val detector = frame.detectorDbfs.getOrNull(selected)?.takeIf { it.isFinite() }
            tooltip(canvas, listOf("${fmt(frame.frequenciesHz[selected], 2)}Hz · 实际增益 ${fmt(frame.gainsDb[selected], 3)}dB",
                "检测电平 ${fmt(detector, 2)}dBFS · 系数 ${fmt(frame.coefficients.getOrNull(selected), 3)}",
                "七频点读取同一增益掩模 · 非七套独立阈值/压缩器"), plot.left, plot.top + 2f * dp)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** In-place radix-2 forward FFT, with cooperative cancellation and no zero padding. */
private fun companderPreviewFft(real: DoubleArray, imaginary: DoubleArray, cancel: () -> Unit) {
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
private class CompanderChartCursor(private val view: View, private val begin: (Float, Float) -> Boolean,
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
