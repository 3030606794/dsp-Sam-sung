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
import me.timschneeberger.rootlessjamesdsp.fusion.DdcDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.DdcDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.DdcPcmTelemetry
import java.util.Locale
import kotlin.math.*

/** Canvas-only observers. There is no timer, audio listener, preference write or audition mutation. */
internal abstract class DdcDashboardChart(context: Context) : View(context) {
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
    protected var configuration: DdcDashboardModel? = null
        private set
    protected var sample: DdcDashboardRuntimeMonitor.Sample? = null
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
    fun setModel(value: DdcDashboardModel) {
        if (configuration === value) return
        configuration = value; onModelChanged(); if (canDraw) invalidate()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; onActivationChanged(); if (canDraw) invalidate()
    }
    fun showSample(value: DdcDashboardRuntimeMonitor.Sample) {
        sample = value; onSample(value); if (canDraw) invalidate()
    }
    fun pause(reason: String) { pauseReason = reason; sample = null; onPause(); if (canDraw) invalidate() }
    protected open fun onModelChanged() = Unit
    protected open fun onSample(value: DdcDashboardRuntimeMonitor.Sample) = Unit
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

/** Personal correction only. A native original-file response is optional, and never invented. */
internal class DdcTransferView(context: Context) : DdcDashboardChart(context) {
    private val plot = RectF(); private var cursor: Float? = null
    private val gesture = DdcChartCursor(this, { x, y -> plot.contains(x, y) },
        { cursor = it.coerceIn(plot.left, plot.right); invalidate() }, { cursor = null; invalidate() })
    override fun onPause() { gesture.clear() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val value = configuration ?: return
        plot.set((if (compact) 23f else 32f) * dp, (if (compact) 12f else 43f) * dp,
            width - 6f * dp, height - (if (compact) 8f else 17f) * dp)
        if (plot.width() <= 0 || plot.height() <= 0) return
        val native = sample?.reading?.takeIf { it.enabled && it.active }?.response?.takeIf { it.ready && it.rate == value.rate }
        val nativePoints = native?.points.orEmpty()
        label(canvas, "个人补偿复合频响 · 未含原VDC/文件混合/自动余量${if (!value.enabled) " · 关闭预览" else ""}",
            2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        if (!compact) {
            label(canvas, "粗紫左/虚紫右个人层 · 细彩色4滤波器 · ${if (native != null) "灰线当前生效原文件32点" else "原文件参考尚未提供"}",
                2f * dp, 21f * dp, width - 4f * dp)
            value.bands.forEachIndexed { i, band ->
                val color = if (i == 3) foreground else colors[(i + 1) % 3]
                label(canvas, "${band.name} ${fmt(band.frequencyHz, 0)}Hz " + if (band.peak) "Q${fmt(band.shape, 2)}" else "S${fmt(band.shape, 2)}",
                    plot.left + plot.width() * (i + .5f) / 4, 33f * dp, plot.width() / 4, color, Paint.Align.CENTER)
            }
        }
        val firstHz = value.frequencies.first(); val lastHz = value.frequencies.last()
        fun x(hz: Double) = plot.left + (ln(hz.coerceIn(firstHz, lastHz) / firstHz) / ln(lastHz / firstHz)).toFloat() * plot.width()
        fun y(db: Double) = plot.bottom - ((db + 48) / 72).toFloat() * plot.height()
        axes(canvas, plot, -48.0, 24.0, "dB")
        for (hz in listOf(20.0, 100.0, 1000.0, 10000.0, lastHz).distinct()) {
            if (hz > lastHz) continue
            canvas.drawLine(x(hz), plot.top, x(hz), plot.bottom, grid)
            label(canvas, if (hz >= 1000) "${fmt(hz / 1000, if (hz < 10000) 1 else 0)}k" else fmt(hz, 0), x(hz), height - 2f * dp,
                plot.width() * .18f, foreground, if (hz == firstHz) Paint.Align.LEFT else if (hz == lastHz) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        canvas.save(); canvas.clipRect(plot)
        val zero = y(0.0); canvas.drawLine(plot.left, zero, plot.right, zero, grid)
        value.bands.forEachIndexed { row, band ->
            val color = if (row == 3) foreground else colors[(row + 1) % 3]
            path.rewind(); path.moveTo(plot.left, zero)
            band.responseDb.forEachIndexed { i, db -> path.lineTo(x(value.frequencies[i]), y(db)) }
            path.lineTo(plot.right, zero); path.close()
            fill.color = ColorUtils.setAlphaComponent(color, if (band.active) 25 else 7); canvas.drawPath(path, fill)
            path.rewind(); band.responseDb.forEachIndexed { i, db ->
                if (i == 0) path.moveTo(x(value.frequencies[i]), y(db)) else path.lineTo(x(value.frequencies[i]), y(db))
            }
            line.color = ColorUtils.setAlphaComponent(color, if (band.active) 175 else 90)
            line.strokeWidth = dp; line.pathEffect = if (band.active) null else dashed
            canvas.drawPath(path, line); line.pathEffect = null
            canvas.drawLine(x(band.frequencyHz), plot.top, x(band.frequencyHz), plot.bottom, grid)
        }
        if (nativePoints.isNotEmpty()) {
            path.rewind(); var began = false
            nativePoints.forEach { p ->
                if (p[0].toDouble() !in firstHz..lastHz) return@forEach
                val xx = x(p[0].toDouble()); val yy = y(p[1] / 100.0)
                if (!began) path.moveTo(xx, yy) else path.lineTo(xx, yy); began = true
            }
            line.color = ColorUtils.setAlphaComponent(foreground, 135); line.strokeWidth = dp; line.pathEffect = dashed
            canvas.drawPath(path, line); line.pathEffect = null
        }
        listOf(value.leftDb, value.rightDb).forEachIndexed { row, data ->
            path.rewind(); data.forEachIndexed { i, db ->
                if (i == 0) path.moveTo(x(value.frequencies[i]), y(db)) else path.lineTo(x(value.frequencies[i]), y(db))
            }
            line.color = ColorUtils.setAlphaComponent(colors[0], if (row == 0) 245 else 160)
            line.strokeWidth = (if (row == 0) 1.9f else 1.3f) * dp
            line.pathEffect = if (row == 1 || !value.enabled) dashed else null
            canvas.drawPath(path, line); line.pathEffect = null
        }
        if (value.automaticHeadroom) {
            line.color = ColorUtils.setAlphaComponent(colors[1], 150); line.strokeWidth = dp; line.pathEffect = dashed
            canvas.drawLine(plot.left, y(-value.marginDb), plot.right, y(-value.marginDb), line); line.pathEffect = null
        }
        cursor?.let { canvas.drawLine(it, plot.top, it, plot.bottom, grid) }
        canvas.restore()
        val clipped = value.leftDb.any { it < -48 || it > 24 } || value.rightDb.any { it < -48 || it > 24 }
        if (!compact && cursor == null) label(canvas, if (value.automaticHeadroom) "橙虚线：−${fmt(value.marginDb, 1)}dB余量参考，非预测自动衰减"
            else "文件混合 左${fmt(value.leftRatio * 100, 1)}% / 右${fmt(value.rightRatio * 100, 1)}% · 非耳机实测${if (clipped) " · 超幅截断" else ""}",
            plot.left + 2f * dp, plot.bottom - 3f * dp, plot.width() - 4f * dp)
        cursor?.let { xx ->
            val i = (((xx - plot.left) / plot.width()) * value.frequencies.lastIndex).roundToInt().coerceIn(value.frequencies.indices)
            val hz = value.frequencies[i]
            point(canvas, xx, y(value.leftDb[i]), colors[0]); point(canvas, xx, y(value.rightDb[i]), colors[1])
            val original = nativePoints.minByOrNull { abs(ln(it[0].toDouble().coerceAtLeast(1.0) / hz)) }
            tooltip(canvas, listOf("${fmt(hz, 1)}Hz · 个人层L ${fmt(value.leftDb[i], 2)} / R ${fmt(value.rightDb[i], 2)}dB",
                "低架${fmt(value.bands[0].responseDb[i], 2)} / 高架${fmt(value.bands[1].responseDb[i], 2)}dB",
                "峰1 ${fmt(value.bands[2].responseDb[i], 2)} / 峰2 ${fmt(value.bands[3].responseDb[i], 2)}dB",
                original?.let { "生效原文件离散点 ${it[0]}Hz / ${fmt(it[1] / 100.0, 2)}dB（未与个人层相加）" }
                    ?: "未提供原文件系数：不推测完整DDC或自动余量"), plot.left, plot.top + 2f * dp)
        }
    }
    override fun onTouchEvent(event: MotionEvent) = gesture.onTouchEvent(event)
    override fun onDetachedFromWindow() { gesture.clear(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Latest-only bounded worker. A hidden chart retains the newest sample but performs no DSP math. */
internal abstract class DdcPcmChart<T>(context: Context) : DdcDashboardChart(context) {
    protected var calculated: T? = null
        private set
    private data class Request(val data: DdcPcmTelemetry.Snapshot, val source: Any?)
    private var latest: Request? = null; private var pending: Request? = null; private var displayed: Request? = null
    private var scope: CoroutineScope? = null; private var worker: Job? = null
    private var error: String? = null
    protected val currentWindow get() = latest?.data
    protected val displayedWindow get() = displayed?.data
    protected val isCurrentWindow get() = same(latest, displayed)
    protected val pcmReason: String? get() = sample?.pcmUnavailableReason
        ?: if (sample == null) pauseReason else if (latest == null) "驱动尚未提供原始音频窗口" else error
    protected abstract fun calculate(data: DdcPcmTelemetry.Snapshot, cancel: () -> Unit): T
    private fun same(a: Request?, b: Request?): Boolean = a != null && b != null && a.source === b.source &&
        a.data.sequence == b.data.sequence && a.data.generation == b.data.generation &&
        a.data.rate == b.data.rate && a.data.firstFramePosition == b.data.firstFramePosition && a.data.frames == b.data.frames
    override fun onSample(value: DdcDashboardRuntimeMonitor.Sample) {
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

internal data class DdcSpectrumFrame(val rate: Int, val n: Int, val frequencies: DoubleArray,
    val levels: List<DoubleArray?>, val notice: String?)

/** Real full-rate PCM, periodic Hann coherent-gain correction, single-sided per-bin peak amplitude. */
internal class DdcSpectrumView(context: Context) : DdcPcmChart<DdcSpectrumFrame>(context) {
    private val plot = RectF(); private var cursor: Float? = null; private var held: DdcSpectrumFrame? = null
    private val gesture = DdcChartCursor(this, { x, y ->
        if (plot.contains(x, y) && calculated != null) { held = calculated; true } else false
    }, { cursor = it.coerceIn(plot.left, plot.right); invalidate() }, { held = null; cursor = null; invalidate() })
    override fun onPause() { super.onPause(); gesture.clear() }
    override fun calculate(data: DdcPcmTelemetry.Snapshot, cancel: () -> Unit): DdcSpectrumFrame {
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
            ddcPreviewFft(real, imaginary, cancel)
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
            !data.wetValid -> "完整DDC修正声暂不可用，不绘假零线"
            data.fading || data.routeFading -> "当前为配置/试听路由的真实过渡窗口"
            data.wetIsNormalConfiguration -> "完整正常DDC修正输出 · 对齐仅固定帧，未消除IIR群延时"
            else -> "以当前采样元数据标定声源"
        }
        return DdcSpectrumFrame(data.rate, n, DoubleArray(n / 2 + 1) { it.toDouble() * data.rate / n }, levels, notice)
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
        label(canvas, if (frame == null) "原声 / 完整DDC修正声 FFT · 等待PCM" else "$status · N${frame.n} · ${fmt(frame.n * 1000.0 / frame.rate, 2)}ms · Δf${fmt(frame.rate.toDouble() / frame.n, 1)}Hz",
            2f * dp, (if (compact) 8f else 10f) * dp, width - 4f * dp)
        val names = listOf("原声固定帧对齐", "保存配置完整修正声", "所选试听输出")
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
private fun ddcPreviewFft(real: DoubleArray, imaginary: DoubleArray, cancel: () -> Unit) {
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
private class DdcChartCursor(private val view: View, private val begin: (Float, Float) -> Boolean,
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
