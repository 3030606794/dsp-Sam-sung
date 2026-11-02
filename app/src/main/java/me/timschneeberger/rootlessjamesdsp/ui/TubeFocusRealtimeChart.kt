package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.TubeChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.TubeController
import me.timschneeberger.rootlessjamesdsp.fusion.TubeDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.TubeDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.TubePcmTelemetry
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/** GET-only, full-rate observations. The processed branch is not isolated generated harmonics. */
internal class TubeFocusRealtimeChart(context: Context) : View(context) {
    private data class Frame(
        val time: Long, val source: Any, val session: Int?, val rate: Int,
        val mode: Int, val toolGeneration: Int, val rawToolEpoch: Int,
        val pcm: Boolean, val generation: Int?, val configuration: Int?, val sequence: Int,
        val factor: Int?, val selectedFactor: Int?, val oldFactor: Int?,
        val delay: Int?, val delayEnd: Int?, val normalDelay: Int?, val selectedDelay: Int?,
        val alignmentChanged: Boolean, val fading: Boolean, val routeFading: Boolean,
        val aligned: Boolean, val split: Boolean, val rms: List<Double?>,
        val peaks: List<Double?>, val runtime: List<Int>?
    )
    private data class Work(val pcm: TubePcmTelemetry.Snapshot, val frame: Frame, val modelRevision: Long)
    private data class Spectrum(val frame: Frame, val modelRevision: Long, val levels: List<DoubleArray?>)

    private val d = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    private val cyan = if (dark) Color.rgb(104, 216, 236) else Color.rgb(16, 124, 146)
    private val amber = if (dark) Color.rgb(255, 198, 118) else Color.rgb(169, 102, 22)
    private val colors = listOf(cyan, primary, amber)
    private val muted = ColorUtils.setAlphaComponent(ink, 155)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = min(9f * resources.displayMetrics.scaledDensity, 10f * d)
        fontFeatureSettings = "tnum"
    }
    private val digits = TextPaint(text).apply { typeface = Typeface.MONOSPACE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 40); style = Paint.Style.STROKE; strokeWidth = d
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * d; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val plot = RectF()
    private val main = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var worker: Future<*>? = null
    private var pendingWork: Work? = null
    private var workToken = 0L
    private var modelRevision = 0L
    private var model: TubeDashboardModel? = null
    private var latestPcm: Work? = null
    private var spectrum: Spectrum? = null
    private var focus = TubeChartFocus.SATURATION
    private var focusKey = ""
    private var active = false
    private var frozen = true
    private var anchor = 0L
    private var current: Frame? = null
    private var breakNext = true
    private var state = "实时采样已关闭"
    private val history = ArrayDeque<Frame>()
    private var inspected: Frame? = null
    private var held: List<Frame>? = null
    private var heldEnd = 0L
    private var downX = 0f
    private var downY = 0f
    private var pendingTouch = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val modes = listOf("正常处理", "本模块原声", "完整处理支路", "处理差分", "快照 A", "快照 B", "双快照测量")
    private val longPress = Runnable {
        if (pendingTouch && isShown && !usesSpectrum() && focus != TubeChartFocus.SATURATION && history.isNotEmpty()) {
            pendingTouch = false; held = history.toList(); heldEnd = anchor
            parent?.requestDisallowInterceptTouchEvent(true); select(downX)
        }
    }
    private val canDraw: Boolean get() = active && isAttachedToWindow && windowVisibility == VISIBLE
    private fun redraw() { if (canDraw) invalidate() }
    private fun usesSpectrum(): Boolean = focus == TubeChartFocus.SOFTEN || focus == TubeChartFocus.FILTER || focus == TubeChartFocus.QUALITY

    fun setConfiguration(value: TubeDashboardModel) {
        val old = model
        model = value
        if (old != null && old.settings == value.settings && old.rate == value.rate && old.enabled == value.enabled) return
        modelRevision++; spectrum = null; latestPcm = null; stopWorker(); redraw()
    }
    fun setFocus(value: TubeChartFocus, key: String) {
        if (focus == value && focusKey == key) return
        finishInspection(); focus = value; focusKey = key
        if (usesSpectrum()) latestPcm?.let(::schedule) else stopWorker()
        redraw()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) { finishInspection(); stopWorker() }
        else if (usesSpectrum()) latestPcm?.let(::schedule)
        redraw()
    }
    fun pause(reason: String) {
        frozen = true; state = reason; breakNext = true; finishInspection(); stopWorker(); redraw()
    }
    private fun db(energy: Double): Double? = if (energy > 0 && energy.isFinite()) 10 * log10(energy) else null
    private fun raw(value: Int?): Double? = value?.takeIf { it != Int.MIN_VALUE }?.div(100.0)
    private fun maximum(a: Double?, b: Double?): Double? = when { a != null && b != null -> max(a, b); a != null -> a; else -> b }
    private fun analyse(a: FloatArray, b: FloatArray): Pair<Double?, Double?> {
        if (a.isEmpty() || a.size != b.size) return null to null
        var aa = 0.0; var bb = 0.0; var peak = 0.0
        for (i in a.indices) { val x = a[i].toDouble(); val y = b[i].toDouble(); aa += x*x; bb += y*y; peak = max(peak, max(abs(x), abs(y))) }
        return maximum(db(aa/a.size), db(bb/b.size)) to if (peak > 0) 20*log10(peak) else null
    }
    private fun sameContext(frame: Frame, identity: Any, session: Int?, rate: Int, mode: Int, tools: Int): Boolean =
        frame.source === identity && frame.session == session && frame.rate == rate && frame.mode == mode && frame.toolGeneration == tools
    private fun samePcmContext(a: Frame, b: Frame): Boolean = sameContext(a, b.source, b.session, b.rate, b.mode, b.toolGeneration) &&
        a.pcm && b.pcm && a.generation == b.generation && a.factor == b.factor && a.selectedFactor == b.selectedFactor &&
        a.oldFactor == b.oldFactor && a.delay == b.delay && a.delayEnd == b.delayEnd &&
        a.normalDelay == b.normalDelay && a.selectedDelay == b.selectedDelay && a.alignmentChanged == b.alignmentChanged &&
        a.routeFading == b.routeFading && a.fading == b.fading

    fun showSample(sample: TubeDashboardRuntimeMonitor.Sample) {
        anchor = max(anchor, sample.elapsedRealtimeMs); frozen = false
        while (history.isNotEmpty() && (history.size > 120 || history.first().time < anchor - 20000)) history.removeFirst()
        val actualMode = TubeController.audition
        val actualEpoch = TubeController.epoch
        // Normal processing has no comparison generation. Its native tool epoch can be zero.
        val tools = if (actualMode == 0) 0 else actualEpoch
        val identity = sample.streamIdentity
        val hardInvalid = !sample.output.connected || sample.output.globalIssue != null || sample.output.activeSessions.size > 1
        val snapshot = sample.reading?.snapshot
        val modeMatches = snapshot?.mode == actualMode && (actualMode == 0 || snapshot.epoch == actualEpoch)
        val runtime = snapshot?.data?.takeIf { !hardInvalid && sample.reading?.active == true && modeMatches && snapshot.enabled && snapshot.ready }
        val pcm = sample.pcm?.takeIf { !hardInvalid && it.mode == actualMode && (actualMode == 0 || it.comparisonEpoch == actualEpoch) &&
            it.moduleEnabled && it.ready && it.inputValid && it.outputValid && it.frames > 0 && sample.output.processing == OutputRuntimeStatus.Processing.ACTIVE }
        val previous = current
        val rate = pcm?.rate ?: runtime?.get(2) ?: sample.reading?.rate
        val compatible = identity != null && rate != null && previous != null && sameContext(previous, identity, sample.streamSessionId, rate, actualMode, tools)
        if (hardInvalid || (previous != null && (identity != null && previous.source !== identity ||
                    previous.session != sample.streamSessionId || previous.mode != actualMode || previous.toolGeneration != tools ||
                    rate != null && previous.rate != rate))) {
            current = null; latestPcm = null; spectrum = null; breakNext = true; stopWorker()
        }
        if (identity == null || (pcm == null && runtime == null)) {
            breakNext = true
            if (hardInvalid || !compatible || previous == null || anchor - previous.time > 1000) {
                current = null; latestPcm = null; spectrum = null; stopWorker()
                state = sample.runtimeUnavailableReason ?: sample.pcmUnavailableReason ?: sample.levelUnavailableReason ?: "等待活动音频"
            }
            redraw(); return
        }
        // A GET can see the preceding publication. Keep its real display for one second,
        // without appending duplicate PCM points or switching the three-column layout.
        if (pcm == null && compatible && previous != null && previous.pcm && anchor - previous.time <= 1000) {
            current = previous.copy(runtime = runtime ?: previous.runtime)
            redraw(); return
        }
        val sequence: Int = pcm?.sequence ?: runtime!![1]
        if (pcm != null && compatible && previous != null && previous.pcm && previous.generation == pcm.generation && previous.sequence == sequence) {
            current = previous.copy(runtime = runtime ?: previous.runtime); redraw(); return
        }
        if (pcm == null && compatible && previous != null && !previous.pcm && previous.sequence == sequence) { redraw(); return }
        val rms: List<Double?>
        val peaks: List<Double?>
        if (pcm != null) {
            val input = analyse(pcm.inputLeft, pcm.inputRight)
            val effect = if (pcm.wetValid && pcm.wetIsNormalConfiguration) analyse(pcm.wetLeft, pcm.wetRight) else null
            val output = analyse(pcm.outputLeft, pcm.outputRight)
            rms = listOf(input.first, effect?.first, output.first); peaks = listOf(input.second, effect?.second, output.second)
        } else {
            val meters = runtime?.get(12) == 1
            rms = listOf(if (meters) maximum(raw(runtime?.get(20)), raw(runtime?.get(21))) else null, null,
                if (meters) maximum(raw(runtime?.get(22)), raw(runtime?.get(23))) else null)
            peaks = listOf(if (meters) maximum(raw(runtime?.get(16)), raw(runtime?.get(17))) else null, null,
                if (meters) maximum(raw(runtime?.get(18)), raw(runtime?.get(19))) else null)
        }
        val split = breakNext || previous == null || !compatible || previous.pcm != (pcm != null) ||
            previous.generation != pcm?.generation || previous.factor != pcm?.effectiveFactor || previous.selectedFactor != pcm?.selectedFactor ||
            previous.oldFactor != pcm?.oldFactor || previous.delay != pcm?.commonDelayFrames || previous.delayEnd != pcm?.commonDelayEndFrames ||
            previous.normalDelay != pcm?.normalKernelDelayFrames || previous.selectedDelay != pcm?.selectedKernelDelayFrames ||
            previous.alignmentChanged != (pcm?.commonDelayChanged == true) || previous.fading != (pcm?.fading == true) ||
            previous.routeFading != (pcm?.routeFading == true) || anchor - previous.time > 1000
        // Native configurationEpoch is the module publication revision and also advances
        // with audio blocks. Generation, model and alignment identify actual boundaries.
        val frame = Frame(anchor, identity, sample.streamSessionId, rate!!, actualMode, tools,
            pcm?.comparisonEpoch ?: runtime!![11], pcm != null, pcm?.generation, pcm?.configurationEpoch, sequence,
            pcm?.effectiveFactor, pcm?.selectedFactor, pcm?.oldFactor, pcm?.commonDelayFrames, pcm?.commonDelayEndFrames,
            pcm?.normalKernelDelayFrames, pcm?.selectedKernelDelayFrames, pcm?.commonDelayChanged == true,
            pcm?.fading == true, pcm?.routeFading == true, pcm?.alignedDryValid == true, split, rms, peaks, runtime)
        if (previous != null && (!samePcmContext(previous, frame) || !frame.pcm)) { spectrum = null; latestPcm = null; stopWorker() }
        current = frame; history.addLast(frame); breakNext = false
        state = when {
            pcm == null -> sample.levelUnavailableReason ?: "真实原生电平 · 完整处理支路等待采样"
            pcm.commonDelayChanged -> "对齐延迟变化中 · 暂停转换与频谱比较"
            pcm.fading || pcm.routeFading -> "真实倍率 / 试听路径过渡窗口"
            else -> "处理支路取保存配置 · 输出取当前试听"
        }
        if (pcm != null) { latestPcm = Work(pcm, frame, modelRevision); if (usesSpectrum()) schedule(latestPcm!!) }
        redraw()
    }

    private fun label(c: Canvas, value: String, x: Float, y: Float, space: Float, color: Int = ink, right: Boolean = false, numeric: Boolean = false) {
        if (space <= 0) return
        val p: TextPaint = if (numeric) digits else text
        p.color = color; p.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        val left = if (right) x - space else x
        c.save(); c.clipRect(left, y+p.ascent()-d, left+space, y+p.descent()+d)
        c.drawText(if (numeric) value else TextUtils.ellipsize(value, p, space, TextUtils.TruncateAt.END).toString(), x, y, p); c.restore()
    }
    private fun fmt(value: Double?, places: Int = 1): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.${places}f", it) } ?: "—"
    override fun onDraw(c: Canvas) {
        super.onDraw(c); if (width < 80*d || height < 80*d) return
        val frame = inspected ?: current
        label(c, "原声 / 完整处理支路 / 试听输出 · 较大通道 RMS dBFS", 2*d, 10*d, width-4*d)
        val step = (width-35*d)/3
        repeat(3) { i ->
            val x = 30*d+i*step; val value = frame?.rms?.get(i)
            label(c, listOf("入", "管", "出")[i], x, 24*d, 14*d, colors[i])
            label(c, fmt(value).padStart(7), x+step-7*d, 24*d, step-17*d, colors[i], true, true)
            val bounds = RectF(x, 28*d, x+step-9*d, 34*d)
            fill.color = ColorUtils.setAlphaComponent(ink, 18); c.drawRoundRect(bounds, 2*d, 2*d, fill)
            if (value != null) {
                fill.color = ColorUtils.setAlphaComponent(colors[i], if (frozen) 90 else 190)
                c.drawRoundRect(RectF(bounds.left, bounds.top, bounds.left+bounds.width()*((value.coerceIn(-96.0, 6.0)+96)/102).toFloat(), bounds.bottom), 2*d, 2*d, fill)
            }
        }
        val match = focus == TubeChartFocus.MATCH
        if (match) drawMatch(c, frame) else {
            val detail = if (focus == TubeChartFocus.QUALITY && frame?.pcm == true)
                " · 核 ${frame.factor}× / 输出 ${frame.selectedFactor}× · 对齐 ${frame.delay} 样本"
            else if (focus == TubeChartFocus.LEVEL || focus == TubeChartFocus.MONITOR)
                " · 输出峰值 ${fmt(frame?.peaks?.get(2))} dBFS"
            else ""
            label(c, "试听：${frame?.mode?.let { modes.getOrElse(it) { "等待确认" } } ?: "等待确认"}$detail", 2*d, 46*d, width-4*d, muted)
        }
        plot.set(30*d, (if (match) 70 else 55)*d, width-7*d, height-26*d)
        if (plot.height() < 9*d) return
        when {
            usesSpectrum() -> drawSpectrum(c)
            focus == TubeChartFocus.SATURATION -> drawTransfer(c, frame)
            else -> drawHistory(c)
        }
        label(c, if (frozen) "$state · 已冻结" else state, 2*d, height-3*d, width-4*d, muted)
    }
    private fun drawMatch(c: Canvas, frame: Frame?) {
        // Independently strict even in normal mode: epoch zero has no current A/B evidence.
        val data = frame?.runtime?.takeIf { it[11] == TubeController.epoch && it[5] == TubeController.audition }
        val phase = when (data?.get(7)) {
            1 -> "预热 ${data[28]}/${data[29]} ms"
            2 -> "测量 ${data[8]}/${data[30]} ms"
            3 -> "测量完成"
            4 -> "信号不足"
            0 -> "尚未测量"
            else -> "等待当前 A/B 读数"
        }
        label(c, phase, 2*d, 46*d, width-4*d, primary)
        val gain = data?.takeIf { it[7] == 3 && TubeController.matchingReady() }
        label(c, "固定衰减 A ${fmt(raw(gain?.get(9)), 2)} / B ${fmt(raw(gain?.get(10)), 2)} dB", 2*d, 59*d, width-4*d, muted)
    }
    private fun axes(c: Canvas, low: Double, high: Double) {
        c.drawRect(plot, grid)
        for (i in 0..4) { val x = plot.left+plot.width()*i/4; c.drawLine(x, plot.top, x, plot.bottom, grid) }
        for (i in 0..2) {
            val y = plot.top+plot.height()*i/2; c.drawLine(plot.left, y, plot.right, y, grid)
            label(c, fmt(high-(high-low)*i/2, if (high < 10) 1 else 0), plot.left-3*d, y+3*d, 26*d, muted, true)
        }
    }
    private fun drawHistory(c: Canvas) {
        axes(c, -96.0, 0.0)
        val values = held ?: history.toList(); val end = if (held != null) heldEnd else anchor
        repeat(3) { series ->
            path.rewind(); var joined = false
            for (frame in values) {
                val value = frame.rms[series]; val x = plot.right-(end-frame.time)/20000f*plot.width()
                if (value == null || x < plot.left) joined = false else {
                    val y = plot.bottom-((value.coerceIn(-96.0, 0.0)+96)/96).toFloat()*plot.height()
                    if (!joined || frame.split) path.moveTo(x, y) else path.lineTo(x, y)
                    joined = true
                }
            }
            line.color = ColorUtils.setAlphaComponent(colors[series], if (frozen) 100 else 230)
            c.save(); c.clipRect(plot); c.drawPath(path, line); c.restore()
        }
        label(c, "−20s", plot.left, plot.bottom+12*d, plot.width()/2, muted)
        label(c, "现在", plot.right, plot.bottom+12*d, plot.width()/2, muted, true)
        if (current == null) label(c, state, plot.left+4*d, plot.centerY()+3*d, plot.width()-8*d, muted)
    }
    private fun drawTransfer(c: Canvas, frame: Frame?) {
        axes(c, -1.0, 1.0)
        label(c, "对齐干声 → 完整处理支路 · FS", plot.left+3*d, plot.top+10*d, plot.width()-6*d, primary)
        label(c, "−1", plot.left, plot.bottom+12*d, plot.width()/2, muted)
        label(c, "+1", plot.right, plot.bottom+12*d, plot.width()/2, muted, true)
        val work = latestPcm?.takeIf { frame != null && samePcmContext(it.frame, frame) && (frozen || anchor-it.frame.time <= 1000) }
        val data = work?.pcm?.takeIf { it.alignedDryValid && it.wetValid && it.wetIsNormalConfiguration && !it.fading && !it.routeFading }
        if (data == null) {
            label(c, if (work != null) "等待稳定的对齐窗口" else "等待真实 PCM 转换轨迹", plot.left+4*d, plot.centerY()+3*d, plot.width()-8*d, muted)
            return
        }
        c.save(); c.clipRect(plot)
        val references = listOf(data.alignedDryLeft, data.alignedDryRight)
        val processed = listOf(data.wetLeft, data.wetRight)
        repeat(2) { channel ->
            path.rewind(); var started = false
            for (i in 0 until data.frames step 3) {
                val x = plot.centerX()+references[channel][i]*plot.width()*.5f
                val y = plot.centerY()-processed[channel][i]*plot.height()*.5f
                if (!started) path.moveTo(x, y) else path.lineTo(x, y)
                started = true
            }
            line.color = ColorUtils.setAlphaComponent(if (channel == 0) primary else cyan, if (frozen) 85 else 155)
            c.drawPath(path, line)
        }
        c.restore()
    }
    private fun schedule(work: Work) {
        if (!canDraw || frozen || !usesSpectrum() || !work.pcm.alignedDryValid || work.modelRevision != modelRevision || anchor-work.frame.time > 1500) return
        pendingWork = work
        if (worker == null) startWork()
    }
    private fun startWork() {
        val work = pendingWork ?: return
        pendingWork = null
        val token = workToken
        val pool = executor ?: Executors.newSingleThreadExecutor { task -> Thread(task, "tube-focus-fft").apply { isDaemon = true } }.also { executor = it }
        worker = pool.submit {
            val result = runCatching { calculate(work) }.getOrNull()
            main.post {
                if (token != workToken) return@post
                worker = null
                if (result != null && result.modelRevision == modelRevision && current?.let { samePcmContext(it, result.frame) } == true &&
                    anchor-result.frame.time <= 1500) spectrum = result
                if (pendingWork != null) startWork()
                redraw()
            }
        }
    }
    private fun calculate(work: Work): Spectrum {
        val data = work.pcm; val n = data.frames
        val window = DoubleArray(n) { .5-.5*cos(2*PI*it/n) }; val sum = window.sum()
        fun transform(samples: FloatArray): DoubleArray {
            val real = DoubleArray(n) { samples[it]*window[it] }; val imaginary = DoubleArray(n)
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { val value = real[i]; real[i] = real[j]; real[j] = value }
            }
            var length = 2
            while (length <= n) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val angle = -2*PI/length; val wr = cos(angle); val wi = sin(angle)
                for (start in 0 until n step length) {
                    var ur = 1.0; var ui = 0.0
                    for (k in 0 until length/2) {
                        val a = start+k; val b = a+length/2; val vr = real[b]*ur-imaginary[b]*ui; val vi = real[b]*ui+imaginary[b]*ur
                        real[b] = real[a]-vr; imaginary[b] = imaginary[a]-vi; real[a] += vr; imaginary[a] += vi
                        val next = ur*wr-ui*wi; ui = ur*wi+ui*wr; ur = next
                    }
                }
                length = length shl 1
            }
            return DoubleArray(n/2+1) { bin -> hypot(real[bin], imaginary[bin])*(if (bin == 0 || bin == n/2) 1.0 else 2.0)/sum }
        }
        // The dry reference carries the same native common delay as the processed and
        // selected paths. This is an observed spectrum, not THD or a harmonic-only FFT.
        val planes = listOf(data.alignedDryLeft, data.alignedDryRight, data.wetLeft, data.wetRight, data.outputLeft, data.outputRight)
        val levels: List<DoubleArray?> = List(3) { series ->
            if (series == 1 && (!data.wetValid || !data.wetIsNormalConfiguration)) null else {
                val left = transform(planes[series*2]); val right = transform(planes[series*2+1])
                DoubleArray(left.size) { bin -> db((left[bin]*left[bin]+right[bin]*right[bin])*.5) ?: Double.NaN }
            }
        }
        return Spectrum(work.frame, work.modelRevision, levels)
    }
    private fun drawSpectrum(c: Canvas) {
        axes(c, -120.0, 12.0)
        val data = spectrum?.takeIf { it.modelRevision == modelRevision && current?.let { frame -> samePcmContext(frame, it.frame) } == true &&
            (frozen || anchor-it.frame.time <= 1500) }
        data?.levels?.forEachIndexed { series, values ->
            if (values != null) {
                path.rewind(); var started = false
                for (bin in values.indices) {
                    val hz = bin.toDouble()*data.frame.rate/((values.size-1)*2)
                    if (hz < 20 || hz > 20000) continue
                    val value = values[bin]
                    if (!value.isFinite()) { started = false; continue }
                    val x = plot.left+(ln(hz/20)/ln(1000.0)).toFloat()*plot.width()
                    val y = plot.bottom-((value.coerceIn(-120.0, 12.0)+120)/132).toFloat()*plot.height()
                    if (started) path.lineTo(x, y) else path.moveTo(x, y)
                    started = true
                }
                line.color = ColorUtils.setAlphaComponent(colors[series], if (frozen) 100 else 230)
                c.save(); c.clipRect(plot); c.drawPath(path, line); c.restore()
            }
        }
        label(c, "20 Hz", plot.left, plot.bottom+12*d, plot.width()/2, muted)
        label(c, "20 kHz", plot.right, plot.bottom+12*d, plot.width()/2, muted, true)
        if (data == null) label(c, "等待真实对齐 PCM 频谱", plot.left+4*d, plot.centerY()+3*d, plot.width()-8*d, muted)
    }
    private fun stopWorker() { workToken++; pendingWork = null; worker?.cancel(true); worker = null; executor?.shutdownNow(); executor = null }
    private fun select(x: Float) {
        val values = held ?: return
        val time = heldEnd-((plot.right-x)/plot.width()).coerceIn(0f, 1f)*20000
        inspected = values.minByOrNull { abs(it.time-time) }; redraw()
    }
    private fun finishInspection() { pendingTouch = false; main.removeCallbacks(longPress); held = null; inspected = null; parent?.requestDisallowInterceptTouchEvent(false) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (!plot.contains(event.x, event.y)) return false; downX = event.x; downY = event.y; pendingTouch = true; main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()); return true }
            MotionEvent.ACTION_MOVE -> { if (held != null) select(event.x) else if (abs(event.x-downX) > slop || abs(event.y-downY) > slop) { pendingTouch = false; main.removeCallbacks(longPress) }; return true }
            MotionEvent.ACTION_UP -> { finishInspection(); performClick(); redraw(); return true }
            MotionEvent.ACTION_CANCEL -> { finishInspection(); redraw(); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (usesSpectrum()) latestPcm?.let(::schedule); redraw() }
    override fun onDetachedFromWindow() { finishInspection(); stopWorker(); super.onDetachedFromWindow() }
}
