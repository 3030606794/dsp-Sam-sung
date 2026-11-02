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
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderController
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderGainTelemetry
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderPcmTelemetry
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderParameters
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/** Real PCM, seven sampled control gains and native detector/mask readings. GET only. */
internal class CompanderFocusRealtimeChart(context: Context) : View(context) {
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
    private data class MeterFrame(val time: Long, val source: Any, val session: Int?, val rate: Int,
        val mode: Int, val tools: Int, val sequence: Int, val engine: Int, val transform: Int,
        val detector: Double?, val boost: Double?, val cut: Double?, val split: Boolean)
    private data class NativeResponse(val time: Long, val source: Any, val session: Int?, val rate: Int,
        val mode: Int, val tools: Int, val modelRevision: Long, val data: CompanderParameters.Response)
    private data class NativeGain(val time: Long, val source: Any, val session: Int?, val rate: Int,
        val mode: Int, val tools: Int, val modelRevision: Long, val data: CompanderGainTelemetry.Snapshot)
    private data class Work(val pcm: CompanderPcmTelemetry.Snapshot, val frame: Frame, val modelRevision: Long)
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
    private var model: CompanderDashboardModel? = null
    private var latestPcm: Work? = null
    private var spectrum: Spectrum? = null
    private var response: NativeResponse? = null
    private var gain: NativeGain? = null
    private var focus = CompanderChartFocus.FOUNDATION
    private var focusKey = ""
    private var active = false
    private var frozen = true
    private var anchor = 0L
    private var current: Frame? = null
    private var breakNext = true
    private var state = "实时采样已关闭"
    private val history = ArrayDeque<Frame>()
    private val meterHistory = ArrayDeque<MeterFrame>()
    private var nativeBlocked = true
    private var inspected: Frame? = null
    private var detailDialog: AlertDialog? = null
    private var held: List<Frame>? = null
    private var heldMeters: List<MeterFrame>? = null
    private var heldEnd = 0L
    private var downX = 0f
    private var downY = 0f
    private var pendingTouch = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val modes = listOf("正常处理", "对齐原声", "正常处理（含混合）", "处理差分", "快照 A", "快照 B", "双快照测量")
    private val longPress = Runnable {
        if (pendingTouch && isShown && !usesSpectrum() && !usesGain() && focus != CompanderChartFocus.RESPONSE && history.isNotEmpty()) {
            pendingTouch = false; held = history.toList(); heldMeters = meterHistory.toList(); heldEnd = anchor
            parent?.requestDisallowInterceptTouchEvent(true); select(downX)
        }
    }
    private val canDraw: Boolean get() = active && isAttachedToWindow && windowVisibility == VISIBLE
    private fun redraw() { if (canDraw) invalidate() }
    private fun usesSpectrum(): Boolean = when (focus) {
        CompanderChartFocus.FOUNDATION, CompanderChartFocus.ENGINE, CompanderChartFocus.RANGE,
        CompanderChartFocus.LOOKAHEAD, CompanderChartFocus.MIX -> true
        else -> false
    }
    private fun usesRecovery(): Boolean = when (focus) {
        CompanderChartFocus.STANDARD, CompanderChartFocus.RELATIVE, CompanderChartFocus.TIMING,
        CompanderChartFocus.DETECTOR, CompanderChartFocus.SIDECHAIN, CompanderChartFocus.HYSTERESIS -> true
        else -> false
    }
    private fun usesGain(): Boolean = focus == CompanderChartFocus.CURVE || focus == CompanderChartFocus.LIMITS
    fun setConfiguration(value: CompanderDashboardModel) {
        val old = model
        model = value
        if (old != null && old.settings == value.settings && old.rate == value.rate && old.enabled == value.enabled) return
        modelRevision++; spectrum = null; response = null; gain = null; latestPcm = null; breakNext = true; stopWorker(); redraw()
    }
    fun setFocus(value: CompanderChartFocus, key: String = "") {
        if (focus == value && focusKey == key) return
        detailDialog?.dismiss(); detailDialog = null
        finishInspection(); focus = value; focusKey = key
        if (usesSpectrum()) latestPcm?.let(::schedule) else stopWorker()
        redraw()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) { detailDialog?.dismiss(); detailDialog = null; finishInspection(); stopWorker() }
        else if (usesSpectrum()) latestPcm?.let(::schedule)
        redraw()
    }
    fun pause(reason: String) {
        frozen = true; state = reason; breakNext = true; finishInspection(); stopWorker(); redraw()
    }
    fun showPaused(reason: String) = pause(reason)
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

    fun showSample(sample: CompanderDashboardRuntimeMonitor.Sample) {
        anchor = max(anchor, sample.elapsedRealtimeMs); frozen = false
        while (history.isNotEmpty() && (history.size > 120 || history.first().time < anchor - 20000)) history.removeFirst()
        while (meterHistory.isNotEmpty() && (meterHistory.size > 120 || meterHistory.first().time < anchor - 20000)) meterHistory.removeFirst()
        val actualMode = CompanderController.audition
        val actualEpoch = CompanderController.epoch
        val tools = if (actualMode == 0) 0 else actualEpoch
        val identity = sample.streamIdentity
        val hardInvalid = !sample.output.connected || sample.output.globalIssue != null || sample.output.activeSessions.size > 1
        nativeBlocked = hardInvalid
        val snapshot = sample.reading?.snapshot
        val modeMatches = snapshot?.mode == actualMode && (actualMode == 0 || snapshot.epoch == actualEpoch)
        val runtime: List<Int>? = snapshot?.data?.takeIf { !hardInvalid && sample.reading?.active == true &&
            modeMatches && snapshot.enabled && snapshot.professional && snapshot.ready }
        val pcm = sample.pcm?.takeIf { !hardInvalid && it.mode == actualMode &&
            (actualMode == 0 || it.comparisonEpoch == actualEpoch) && it.moduleEnabled && it.ready &&
            it.inputValid && it.outputValid && it.frames > 0 && sample.output.processing == OutputRuntimeStatus.Processing.ACTIVE }
        val previous = current
        val rate = pcm?.rate ?: snapshot?.data?.get(2)?.takeIf { it in 8000..768000 &&
            snapshot?.data?.get(26) == sample.reading?.rate }
        val compatible = identity != null && rate != null && previous != null &&
            sameContext(previous, identity, sample.streamSessionId, rate, actualMode, tools)
        val softMissing = !hardInvalid && identity == null && previous != null &&
            previous.mode == actualMode && previous.toolGeneration == tools && anchor-previous.time <= 1000
        if (hardInvalid || (previous != null && (identity != null && previous.source !== identity ||
                    identity != null && previous.session != sample.streamSessionId || previous.mode != actualMode ||
                    previous.toolGeneration != tools || rate != null && previous.rate != rate))) {
            current = null; latestPcm = null; spectrum = null; response = null; gain = null; breakNext = true; stopWorker()
        }
        if (runtime != null && identity != null) {
            val last = meterHistory.lastOrNull()
            val splitMeter = breakNext || last == null || last.source !== identity || last.session != sample.streamSessionId ||
                last.rate != runtime[2] || last.mode != actualMode || last.tools != tools ||
                last.engine != runtime[13] || last.transform != runtime[14] || anchor-last.time > 1000
            if (splitMeter || last?.sequence != runtime[1]) meterHistory.addLast(MeterFrame(anchor, identity,
                sample.streamSessionId, runtime[2], actualMode, tools, runtime[1], runtime[13], runtime[14],
                if (runtime[12] == 1) raw(runtime[41]) else null,
                if (runtime[12] == 1) runtime[27]/100.0 else null,
                if (runtime[12] == 1) runtime[28]/100.0 else null, splitMeter))
            val curve = sample.reading?.response
            val data = curve?.data
            if (curve != null && data != null && curve.ready && curve.rate == runtime[2] &&
                data[1] == runtime[1] && data[3] == runtime[13] && data[6] == runtime[14])
                response = NativeResponse(anchor, identity, sample.streamSessionId, runtime[2],
                    actualMode, tools, modelRevision, curve)
            else if (model?.settings?.response != true) response = null
        }
        val control = sample.gain
        if (control != null && pcm != null && identity != null && control.available &&
            control.sequence == pcm.sequence && control.generation == pcm.generation &&
            control.lastFramePosition == pcm.lastFramePosition && control.rate == pcm.rate &&
            control.mode == actualMode && (actualMode == 0 || control.epoch == actualEpoch)) {
            gain = NativeGain(anchor, identity, sample.streamSessionId, control.rate, actualMode, tools, modelRevision, control)
        }
        if (identity == null || (pcm == null && runtime == null)) {
            breakNext = true
            if (!softMissing && (hardInvalid || !compatible || previous == null || anchor - previous.time > 1000)) {
                current = null; latestPcm = null; spectrum = null; response = null; gain = null; stopWorker()
                state = sample.runtimeUnavailableReason ?: sample.pcmUnavailableReason ?: sample.levelUnavailableReason ?: "等待活动音频"
            }
            redraw(); return
        }
        if (pcm == null && compatible && previous != null && previous.pcm && anchor - previous.time <= 1000) {
            current = previous.copy(runtime = runtime ?: previous.runtime); redraw(); return
        }
        val sequence: Int = pcm?.sequence ?: runtime!![1]
        if (pcm != null && compatible && previous != null && previous.pcm &&
            previous.generation == pcm.generation && previous.sequence == sequence) {
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
        // configurationEpoch is a block publication revision, not an audio-setting generation.
        val frame = Frame(anchor, identity, sample.streamSessionId, rate!!, actualMode, tools,
            pcm?.comparisonEpoch ?: runtime!![11], pcm != null, pcm?.generation, pcm?.configurationEpoch, sequence,
            pcm?.effectiveFactor, pcm?.selectedFactor, pcm?.oldFactor, pcm?.commonDelayFrames, pcm?.commonDelayEndFrames,
            pcm?.normalKernelDelayFrames, pcm?.selectedKernelDelayFrames, pcm?.commonDelayChanged == true,
            pcm?.fading == true, pcm?.routeFading == true, pcm?.alignedDryValid == true, split, rms, peaks, runtime)
        if (previous != null && (!samePcmContext(previous, frame) || !frame.pcm)) { spectrum = null; latestPcm = null; stopWorker() }
        current = frame; history.addLast(frame); breakNext = false
        state = when {
            pcm == null -> sample.levelUnavailableReason ?: "原生仪表读数 · 等待正常处理 PCM"
            pcm.commonDelayChanged -> "对齐延迟变化中 · 等待稳定频谱窗口"
            pcm.fading || pcm.routeFading -> "内核 / 试听路径过渡窗口"
            else -> "真实短窗频谱 · 正常处理已含混合与输出增益"
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
        if (usesRecovery()) { drawRecovery(c); return }
        if (usesGain()) { drawGain(c); return }
        if (focus == CompanderChartFocus.RESPONSE) { drawResponse(c); return }
        val frame = inspected ?: current
        label(c, "原声 / 正常处理（含混合） / 试听输出 · RMS dBFS", 2*d, 10*d, width-4*d)
        val step = (width-35*d)/3
        repeat(3) { i ->
            val x = 30*d+i*step; val value = frame?.rms?.get(i)
            label(c, listOf("入", "常", "出")[i], x, 24*d, 14*d, colors[i])
            label(c, fmt(value).padStart(7), x+step-7*d, 24*d, step-17*d, colors[i], true, true)
            val bounds = RectF(x, 28*d, x+step-9*d, 34*d)
            fill.color = ColorUtils.setAlphaComponent(ink, 18); c.drawRoundRect(bounds, 2*d, 2*d, fill)
            if (value != null) {
                fill.color = ColorUtils.setAlphaComponent(colors[i], if (frozen) 90 else 190)
                c.drawRoundRect(RectF(bounds.left, bounds.top, bounds.left+bounds.width()*((value.coerceIn(-96.0, 6.0)+96)/102).toFloat(), bounds.bottom), 2*d, 2*d, fill)
            }
        }
        val match = focus == CompanderChartFocus.MATCH
        if (match) drawMatch(c, frame) else {
            val detail = if (focus == CompanderChartFocus.LEVEL || focus == CompanderChartFocus.MONITOR)
                " · 输出峰值 ${fmt(frame?.peaks?.get(2))} dBFS"
            else if (focus == CompanderChartFocus.LOOKAHEAD && frame?.runtime != null)
                " · 前瞻 ${frame.runtime[43]} 样本 / ${fmt(frame.runtime[43]*1000.0/frame.rate)} ms"
            else if (usesSpectrum() && frame?.runtime != null)
                " · ${if (frame.runtime[13] == 1) "标准压扩" else listOf("STFT", "CWT", "CWT/STFT", "Gammatone").getOrElse(frame.runtime[14]) { "当前引擎" }} · 基础 ${frame.rate} Hz"
            else ""
            label(c, "试听：${frame?.mode?.let { modes.getOrElse(it) { "等待确认" } } ?: "等待确认"}$detail", 2*d, 46*d, width-4*d, muted)
        }
        plot.set(30*d, (if (match) 70 else 55)*d, width-7*d, height-26*d)
        if (plot.height() < 9*d) return
        when {
            usesSpectrum() -> drawSpectrum(c)
            else -> drawHistory(c)
        }
        label(c, if (frozen) "$state · 已冻结" else state, 2*d, height-3*d, width-4*d, muted)
    }
    private fun drawMatch(c: Canvas, frame: Frame?) {
        // Independently strict even in normal mode: epoch zero has no current A/B evidence.
        val data = frame?.runtime?.takeIf { it[11] == CompanderController.epoch && it[5] == CompanderController.audition }
        val phase = when (data?.get(7)) {
            1 -> "预热 ${data[29]}/${data[30]} ms"
            2 -> "测量 ${data[8]}/${data[31]} ms"
            3 -> "测量完成"
            4 -> "信号不足"
            0 -> "尚未测量"
            else -> "等待当前 A/B 读数"
        }
        label(c, phase, 2*d, 46*d, width-4*d, primary)
        val gain = data?.takeIf { it[7] == 3 && CompanderController.matchingReady() }
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
    private fun nativeNow(): MeterFrame? = meterHistory.lastOrNull()?.takeIf { frozen || !nativeBlocked && anchor-it.time <= 1000 }
    private fun areaAxes(c: Canvas, area: RectF, low: Double, high: Double, tint: Int = muted) {
        c.drawRect(area, grid)
        for (i in 0..4) { val x = area.left+area.width()*i/4; c.drawLine(x, area.top, x, area.bottom, grid) }
        for (i in 0..2) {
            val y = area.top+area.height()*i/2; c.drawLine(area.left, y, area.right, y, grid)
            label(c, fmt(high-(high-low)*i/2, 0), area.left-3*d, y+3*d, 26*d, tint, true)
        }
    }
    private fun traceMeters(c: Canvas, area: RectF, low: Double, high: Double, tint: Int, series: Int) {
        path.rewind(); var joined = false
        val values = heldMeters ?: meterHistory.toList(); val end = if (heldMeters != null) heldEnd else anchor
        for (value in values) {
            val number = when (series) { 0 -> value.detector; 1 -> value.boost; else -> value.cut?.let { -it } }
            val x = area.right-(end-value.time)/20000f*area.width()
            if (number == null || x < area.left) joined = false else {
                val y = area.bottom-((number.coerceIn(low, high)-low)/(high-low)).toFloat()*area.height()
                if (!joined || value.split) path.moveTo(x, y) else path.lineTo(x, y)
                joined = true
            }
        }
        line.color = ColorUtils.setAlphaComponent(tint, if (frozen) 100 else 230)
        c.save(); c.clipRect(area); c.drawPath(path, line); c.restore()
    }
    private fun drawRecovery(c: Canvas) {
        val meter = if (heldMeters != null) heldMeters?.lastOrNull() else nativeNow()
        label(c, "检测 ${fmt(meter?.detector)} dBFS", 2*d, 10*d, width*.52f, cyan)
        label(c, "+${fmt(meter?.boost)} / −${fmt(meter?.cut)} dB", width-2*d, 10*d, width*.47f, primary, true)
        val runtime = (inspected ?: current)?.runtime
        val detail = when {
            focus == CompanderChartFocus.HYSTERESIS && runtime?.get(13) == 1 && runtime[46] == 3 ->
                "噪声门：${if (runtime[55] == 1) "开启" else "关闭"} · 最近 20 秒"
            focus == CompanderChartFocus.TIMING && runtime != null ->
                "攻击 ${fmt(runtime[34]/10.0)} / 保持 ${runtime[35]} / 释放 ${runtime[36]} ms"
            else -> "检测与当前处理块最大提升 / 衰减 · 最近 20 秒"
        }
        label(c, detail, 2*d, 24*d, width-4*d)
        plot.set(30*d, 36*d, width-7*d, height-25*d)
        if (plot.height() < 22*d) return
        val half = (plot.height()-16*d)*.5f
        val detector = RectF(plot.left, plot.top, plot.right, plot.top+half)
        val action = RectF(plot.left, detector.bottom+16*d, plot.right, plot.bottom)
        areaAxes(c, detector, -96.0, 0.0, cyan); areaAxes(c, action, -60.0, 24.0)
        traceMeters(c, detector, -96.0, 0.0, cyan, 0)
        traceMeters(c, action, -60.0, 24.0, primary, 1); traceMeters(c, action, -60.0, 24.0, amber, 2)
        label(c, "检测 dBFS", detector.left+3*d, detector.top-3*d, detector.width(), cyan)
        label(c, "紫：提升 / 橙：衰减 · dB", action.left+3*d, action.top-3*d, action.width(), primary)
        if (meter?.detector == null) label(c, "请打开原生“显示实时电平”读取检测与增益", detector.left+3*d, detector.centerY()+3*d, detector.width()-6*d, muted)
        if (meter == null) label(c, if (frozen) state else "等待新的压扩仪表读数", action.left+3*d, action.centerY()+3*d, action.width()-6*d, muted)
        label(c, "−20s", plot.left, plot.bottom+12*d, plot.width()/2, muted)
        label(c, "现在", plot.right, plot.bottom+12*d, plot.width()/2, muted, true)
        label(c, if (frozen) "$state · 已冻结" else "检测为原生读数 · 增益为处理块极值，不是总输出电平差", 2*d, height-3*d, width-4*d, muted)
    }
    private fun currentGain(): NativeGain? {
        val mode = CompanderController.audition; val tools = if (mode == 0) 0 else CompanderController.epoch
        return gain?.takeIf { it.modelRevision == modelRevision && it.mode == mode && it.tools == tools &&
            (frozen || !nativeBlocked && anchor-it.time <= 1000) &&
            current?.let { f -> f.source === it.source && f.session == it.session && f.rate == it.rate &&
                f.generation == it.data.generation } == true }
    }
    private fun drawGain(c: Canvas) {
        val control = currentGain()?.data
        label(c, "七个控制频点 · 实际增益掩膜 dB", 2*d, 10*d, width-4*d)
        label(c, if (control?.engine == 1) "标准引擎：同一宽带增益在七处取样" else "紫：提升 · 橙：衰减 · 当前处理网格的频点取样", 2*d, 24*d, width-4*d, muted)
        plot.set(30*d, 39*d, width-7*d, height-29*d)
        if (plot.height() < 14*d) return
        areaAxes(c, plot, -60.0, 24.0)
        val zero = plot.bottom-(60f/84f)*plot.height()
        c.drawLine(plot.left, zero, plot.right, zero, grid)
        val step = plot.width()/7
        val highlighted = CompanderParameters.frequencies.indexOfFirst { it.key == focusKey }.takeIf { it >= 0 }
            ?: CompanderParameters.coefficients.indexOfFirst { it.key == focusKey }.takeIf { it >= 0 }
        repeat(7) { i ->
            val x = plot.left+step*(i+.5f); val value = control?.gainsDb?.get(i)
            if (highlighted == i) { fill.color = ColorUtils.setAlphaComponent(primary, 18); c.drawRect(x-step*.48f, plot.top, x+step*.48f, plot.bottom, fill) }
            if (value != null) {
                val y = plot.bottom-((value.coerceIn(-60.0,24.0)+60)/84).toFloat()*plot.height()
                fill.color = ColorUtils.setAlphaComponent(if (value >= 0) primary else amber, if (frozen) 95 else 190)
                c.drawRect(x-step*.25f, min(y,zero), x+step*.25f, max(y,zero).coerceAtLeast(min(y,zero)+d), fill)
                label(c, fmt(value), x+step*.4f, plot.top+10*d, step*.85f, if(value>=0) primary else amber, true, true)
            }
            val hz = control?.frequenciesHz?.get(i)
            val caption = if (hz == null) "${i+1}" else if (hz >= 1000) "${fmt(hz/1000,1)}k" else fmt(hz,0)
            label(c, caption, x+step*.43f, plot.bottom+12*d, step*.9f, muted, true, true)
        }
        if (control == null) label(c, if(frozen) state else "等待同窗七点实际增益", plot.left+4*d, plot.centerY()+3*d, plot.width()-8*d, muted)
        label(c, if(frozen) "$state · 已冻结" else "点按柱查看 C / 检测 · 七处实际控制取样", 2*d, height-3*d, width-4*d, muted)
    }
    private fun drawResponse(c: Canvas) {
        label(c, "驱动当前控制掩膜 · 增益与检测", 2*d, 10*d, width-4*d)
        label(c, "上：处理增益 dB · 下：检测与背景 dBFS", 2*d, 24*d, width-4*d, muted)
        plot.set(30*d, 36*d, width-7*d, height-25*d)
        if (plot.height() < 22*d) return
        val half = (plot.height()-16*d)*.5f
        val action = RectF(plot.left, plot.top, plot.right, plot.top+half)
        val detector = RectF(plot.left, action.bottom+16*d, plot.right, plot.bottom)
        areaAxes(c, action, -60.0, 24.0); areaAxes(c, detector, -100.0, 0.0)
        val curve = currentResponse()
        if (curve != null) {
            repeat(3) { series ->
                val area = if (series == 0) action else detector
                val low = if (series == 0) -60.0 else -100.0
                val high = if (series == 0) 24.0 else 0.0
                path.rewind(); var joined = false
                for (point in curve.data.points) {
                    val number = raw(point[if (series == 0) 1 else if (series == 1) 3 else 4])
                    val hz = point[0].toDouble()
                    if (number == null || hz < 20 || hz > 20000 || series == 0 && point[5] == 0) { joined = false; continue }
                    val x = area.left+(ln(hz/20)/ln(1000.0)).toFloat()*area.width()
                    val y = area.bottom-((number.coerceIn(low,high)-low)/(high-low)).toFloat()*area.height()
                    if (joined) path.lineTo(x,y) else path.moveTo(x,y)
                    joined = true
                }
                line.color = ColorUtils.setAlphaComponent(listOf(primary,cyan,amber)[series],if(frozen)100 else 230)
                c.save(); c.clipRect(area); c.drawPath(path,line); c.restore()
            }
            label(c, "青：检测 · 橙：背景（原版引擎）", detector.left+3*d, detector.top+10*d, detector.width()-6*d, muted)
        } else {
            val reason = if (model?.settings?.response != true) "请打开原生“显示实时动态增益图”" else "等待同一快照的驱动控制掩膜"
            label(c, if(frozen) state else reason, action.left+3*d, action.centerY()+3*d, action.width()-6*d, muted)
        }
        label(c, "20 Hz", plot.left, plot.bottom+12*d, plot.width()/2, muted)
        label(c, "20 kHz", plot.right, plot.bottom+12*d, plot.width()/2, muted, true)
        label(c, if(frozen) "$state · 已冻结" else "点按频点查看 C / 检测 / 背景 · 原生控制快照", 2*d, height-3*d, width-4*d, muted)
    }
    private fun currentResponse(): NativeResponse? {
        val mode = CompanderController.audition; val tools = if (mode == 0) 0 else CompanderController.epoch
        return response?.takeIf { it.modelRevision == modelRevision && it.mode == mode && it.tools == tools &&
            (model?.settings?.response == true || frozen) &&
            (frozen || !nativeBlocked && anchor-it.time <= 1000) && current?.let { f ->
                f.source === it.source && f.session == it.session && f.rate == it.rate } == true }
    }
    /** Tap observes one published control point; it never sends a parameter or tool command. */
    private fun inspectNativePoint(x: Float) {
        if (plot.width() <= 0) return
        val body: String
        val title: String
        if (focus == CompanderChartFocus.RESPONSE) {
            val curve = currentResponse() ?: return
            val hz = 20.0*1000.0.pow(((x-plot.left)/plot.width()).coerceIn(0f, 1f).toDouble())
            val point = curve.data.points.minByOrNull { abs(ln(it[0].coerceAtLeast(1)/hz)) } ?: return
            title = "驱动控制频点 · ${point[0]} Hz"
            body = "动态增益 ${fmt(raw(point[1]), 2)} dB\n" +
                "无量纲系数 C ${fmt(point[2]/1000.0, 3)}\n" +
                "检测电平 ${fmt(raw(point[3]), 2)} dBFS\n" +
                "历史背景 ${fmt(raw(point[4]), 2)} dBFS\n" +
                "频点作用：${if (point[5] == 1) "允许" else "未启用或范围外"}\n\n" +
                "基础采样率 ${curve.rate} Hz · 同一原子运行快照\n" +
                "标准引擎的 C 为 0、背景不可用；不是输出频响或独立压缩通道。" +
                if (frozen) "\n采样已冻结，以上保留最后真实读数。" else ""
        } else if (usesGain()) {
            val control = currentGain()?.data ?: return
            val index = (((x-plot.left)/plot.width()).coerceIn(0f, .99999f)*7).toInt().coerceIn(0,6)
            val coefficient = control.coefficients[index].takeIf { it.isFinite() && abs(it) <= 2.4 }
            title = "七点取样 ${index+1} · ${fmt(control.frequenciesHz[index], 3)} Hz"
            body = "动态增益 ${fmt(control.gainsDb[index], 2)} dB\n" +
                "无量纲系数 C ${fmt(coefficient, 3)}\n" +
                "检测电平 ${fmt(control.detectorDbfs[index], 2)} dBFS\n\n" +
                "基础 ${control.rate} Hz · 宿主 ${control.hostRate} Hz\n" +
                "PCM 窗口 ${control.sequence} · 同窗实际控制取样\n" +
                (if (control.engine == 1) "标准引擎共享宽带控制，C 为 0。" else "原版频谱网格控制量，七个取样点不是七台独立压缩器。") +
                if (frozen) "\n采样已冻结，以上保留最后真实读数。" else ""
        } else return
        detailDialog?.dismiss()
        detailDialog = GlassAlertDialogBuilder(context).setTitle(title).setMessage(body)
            .setPositiveButton(android.R.string.ok, null).show()
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
        val pool = executor ?: Executors.newSingleThreadExecutor { task -> Thread(task, "compander-focus-fft").apply { isDaemon = true } }.also { executor = it }
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
        label(c, "观察窗 FFT1024 · dBFS", plot.left+3*d, plot.top+10*d, plot.width()-6*d, muted)
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
    private fun finishInspection() { pendingTouch = false; main.removeCallbacks(longPress); held = null; heldMeters = null; inspected = null; parent?.requestDisallowInterceptTouchEvent(false) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (!plot.contains(event.x, event.y)) return false; downX = event.x; downY = event.y; pendingTouch = true; main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()); return true }
            MotionEvent.ACTION_MOVE -> { if (held != null) select(event.x) else if (abs(event.x-downX) > slop || abs(event.y-downY) > slop) { pendingTouch = false; main.removeCallbacks(longPress) }; return true }
            MotionEvent.ACTION_UP -> {
                val inspect = pendingTouch && (usesGain() || focus == CompanderChartFocus.RESPONSE)
                finishInspection(); performClick(); if (inspect) inspectNativePoint(event.x); redraw(); return true
            }
            MotionEvent.ACTION_CANCEL -> { finishInspection(); redraw(); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (usesSpectrum()) latestPcm?.let(::schedule); redraw() }
    override fun onDetachedFromWindow() { detailDialog?.dismiss(); detailDialog = null; finishInspection(); stopWorker(); super.onDetachedFromWindow() }
}
