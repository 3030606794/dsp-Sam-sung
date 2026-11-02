package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionAdvancedRuntime
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionController
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionPcmTelemetry
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionRuntimeReader
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/** Real captured audio and independent native 100 ms meters. No parameter or audition writes. */
internal class ConvolutionFocusRealtimeChart(context: Context) : View(context) {
    private data class Source(val identity: Any, val session: Int?, val rate: Int, val crc: Int, val mode: Int)
    private data class Levels(val rms: Double?, val peak: Double?)
    private data class NativeFrame(val time: Long, val source: Source, val sequence: Int,
        val packet: ConvolutionRuntimeReader.Snapshot, val advanced: ConvolutionAdvancedRuntime.Snapshot?, val split: Boolean)
    private data class PcmFrame(val time: Long, val source: Source, val packet: ConvolutionPcmTelemetry.Snapshot,
        val levels: List<Levels>, val split: Boolean)
    private data class Work(val frame: PcmFrame, val modelRevision: Long)
    private data class Spectrum(val frame: PcmFrame, val revision: Long, val levels: List<DoubleArray?>)

    private val d = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    private val cyan = if (dark) Color.rgb(104,216,236) else Color.rgb(16,124,146)
    private val amber = if (dark) Color.rgb(255,198,118) else Color.rgb(169,102,22)
    private val red = if (dark) Color.rgb(255,141,141) else Color.rgb(184,49,49)
    private val colors = listOf(cyan,primary,amber,ink)
    private val muted = ColorUtils.setAlphaComponent(ink,155)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = min(9f*resources.displayMetrics.scaledDensity,10f*d); fontFeatureSettings = "tnum"
    }
    private val digits = TextPaint(text).apply { typeface = Typeface.MONOSPACE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.35f*d; strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink,40); style = Paint.Style.STROKE; strokeWidth = d
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashed = DashPathEffect(floatArrayOf(4f*d,3f*d),0f)
    private val path = Path()
    private val plot = RectF()
    private val main = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var worker: Future<*>? = null
    private var pendingWork: Work? = null
    private var workToken = 0L
    private var modelRevision = 0L
    private var model: ConvolutionDashboardModel? = null
    private var focus = ConvolutionChartFocus.FILE
    private var focusKey = ""
    private var active = false
    private var frozen = true
    private var hardBlocked = false
    private var anchor = 0L
    private var source: Source? = null
    private var pcmFrame: PcmFrame? = null
    private var nativeFrame: NativeFrame? = null
    private var meterFrame: NativeFrame? = null
    private var spectrum: Spectrum? = null
    private var breakPcm = true
    private var breakNative = true
    private var state = "实时采样已关闭"
    private var pcmReason: String? = null
    private var runtimeReason: String? = null
    private val audioHistory = ArrayDeque<PcmFrame>()
    private val meterHistory = ArrayDeque<NativeFrame>()
    private val transitionHistory = ArrayDeque<NativeFrame>()
    private var heldAudio: List<PcmFrame>? = null
    private var heldNative: List<NativeFrame>? = null
    private var heldEnd = 0L
    private var inspectedNative: NativeFrame? = null
    private var inspectedAudio: PcmFrame? = null
    private var pendingTouch = false
    private var downX = 0f
    private var downY = 0f
    private var cursorX = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var detailDialog: AlertDialog? = null
    private val longPress = Runnable {
        if (pendingTouch && isShown && (layout() == 1 && meterHistory.isNotEmpty() || layout() == 4 && transitionHistory.isNotEmpty())) {
            pendingTouch = false
            heldAudio = audioHistory.toList()
            heldNative = if (layout() == 4) transitionHistory.toList() else meterHistory.toList()
            heldEnd = anchor
            parent?.requestDisallowInterceptTouchEvent(true)
            inspectHistory(cursorX)
        }
    }
    private val canDraw: Boolean get() = active && isAttachedToWindow && windowVisibility == VISIBLE
    private fun redraw() { if (canDraw) invalidate() }
    private fun layout(): Int = when (focus) {
        ConvolutionChartFocus.WET,ConvolutionChartFocus.OUTPUT,ConvolutionChartFocus.NORMALIZATION,
        ConvolutionChartFocus.SHAPE,ConvolutionChartFocus.AUDITION,ConvolutionChartFocus.COMPARISON -> 1
        ConvolutionChartFocus.PREDELAY,ConvolutionChartFocus.LEGACY_TRIM,ConvolutionChartFocus.LEGACY_SHIFT,
        ConvolutionChartFocus.WINDOW,ConvolutionChartFocus.FRACTIONAL -> 2
        ConvolutionChartFocus.CHANNEL,ConvolutionChartFocus.MATRIX -> 3
        ConvolutionChartFocus.TRANSITION -> 4
        else -> 0
    }
    fun setConfiguration(value: ConvolutionDashboardModel) {
        val previous = model
        model = value
        if (previous != null && previous.values == value.values && previous.rate == value.rate &&
            previous.preparation === value.preparation && previous.prepared === value.prepared) return
        // A newly generated target IR may not yet be active. Retain genuinely captured old-kernel audio;
        // the live source CRC/generation, not the saved target, determines observation boundaries.
        if (previous != null && previous.rate != value.rate) {
            modelRevision++; spectrum = null; stopWorker()
        }
        redraw()
    }
    fun setFocus(value: ConvolutionChartFocus,key: String = "") {
        if (focus == value && focusKey == key) return
        finishInspection(); detailDialog?.dismiss(); detailDialog = null; focus = value; focusKey = key
        if (layout() == 0) currentPcm()?.let { schedule(Work(it,modelRevision)) } else stopWorker()
        redraw()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) { finishInspection(); detailDialog?.dismiss(); detailDialog = null; stopWorker() }
        else if (layout() == 0) currentPcm()?.let { schedule(Work(it,modelRevision)) }
        redraw()
    }
    fun pause(reason: String) {
        frozen = true; state = reason; breakPcm = true; breakNative = true
        finishInspection(); stopWorker(); redraw()
    }
    fun showPaused(reason: String) = pause(reason)
    private fun same(a: Source?,b: Source?): Boolean = a != null && b != null && a.identity === b.identity &&
        a.session == b.session && a.rate == b.rate && a.crc == b.crc && a.mode == b.mode
    private fun samePcm(a: PcmFrame,b: PcmFrame): Boolean {
        val x = a.packet; val y = b.packet
        // This engine increments configurationEpoch only on real control/reset boundaries.
        return same(a.source,b.source) && x.generation == y.generation && x.configurationEpoch == y.configurationEpoch &&
            x.effectiveFactor == y.effectiveFactor && x.selectedFactor == y.selectedFactor && x.oldFactor == y.oldFactor &&
            x.commonDelayFrames == y.commonDelayFrames && x.commonDelayEndFrames == y.commonDelayEndFrames &&
            x.normalKernelDelayFrames == y.normalKernelDelayFrames && x.selectedKernelDelayFrames == y.selectedKernelDelayFrames &&
            x.commonDelayChanged == y.commonDelayChanged && x.fading == y.fading && x.routeFading == y.routeFading
    }
    private fun currentPcm(): PcmFrame? = pcmFrame?.takeIf {
        same(it.source,source) && (frozen || !hardBlocked && anchor-it.time <= 1000)
    }
    private fun currentNative(): NativeFrame? = nativeFrame?.takeIf {
        same(it.source,source) && (frozen || !hardBlocked && anchor-it.time <= 1000)
    }
    private fun currentMeter(): NativeFrame? = meterFrame?.takeIf {
        same(it.source,source) && (frozen || !hardBlocked && anchor-it.time <= 1000)
    }
    private fun clearCurrent() {
        pcmFrame = null; nativeFrame = null; meterFrame = null; spectrum = null
        breakPcm = true; breakNative = true; stopWorker(); finishInspection()
    }
    private fun analyse(left: FloatArray,right: FloatArray): Levels {
        if (left.isEmpty() || left.size != right.size) return Levels(null,null)
        var square = 0.0; var peak = 0.0
        for (i in left.indices) {
            val l = left[i].toDouble(); val r = right[i].toDouble()
            square += l*l+r*r; peak = max(peak,max(abs(l),abs(r)))
        }
        return Levels(if (square > 0) 10*log10(square/(2*left.size)) else Double.NEGATIVE_INFINITY,
            if (peak > 0) 20*log10(peak) else Double.NEGATIVE_INFINITY)
    }
    fun showSample(sample: ConvolutionDashboardRuntimeMonitor.Sample) {
        anchor = max(anchor,sample.elapsedRealtimeMs); frozen = false
        pcmReason = sample.pcmUnavailableReason; runtimeReason = sample.runtimeUnavailableReason
        val identity = sample.streamIdentity
        val packet = sample.reading?.snapshot
        val mode = ConvolutionController.audition
        val hard = !sample.output.connected || sample.output.globalIssue != null ||
            sample.output.activeSessions.size > 1 || sample.streamDiscontinuity
        hardBlocked = hard
        val rate = sample.pcm?.rate ?: packet?.rate?.takeIf { it in 8000..768000 }
        val nextSource = if (identity != null && rate != null && packet != null && packet.loaded)
            Source(identity,sample.streamSessionId,rate,packet.crc,mode) else null
        val changedIdentity = source?.let { old -> identity != null && (identity !== old.identity || sample.streamSessionId != old.session) } == true
        if (hard || changedIdentity || source?.mode?.let { it != mode } == true || nextSource != null && !same(source,nextSource)) clearCurrent()
        if (nextSource != null) source = nextSource
        trimHistory()
        val validNative = !hard && sample.runtimeUnavailableReason == null && identity != null && packet != null &&
            packet.loaded && packet.moduleEnabled && packet.available && packet.audition == mode &&
            sample.reading?.enabled == true && sample.reading.processing == OutputRuntimeStatus.Processing.ACTIVE
        if (validNative && nextSource != null && packet != null) {
            val previous = nativeFrame
            val seq = sample.reading?.frames ?: -1
            val advanced = sample.reading?.advanced?.takeIf { it.rate == packet.rate && it.crc == packet.crc }
            val split = breakNative || previous == null || !same(previous.source,nextSource) || !packet.ready ||
                !previous.packet.ready || nativeConfiguration(previous.packet) != nativeConfiguration(packet) || anchor-previous.time > 1000
            if (previous == null || previous.sequence != seq || !same(previous.source,nextSource)) {
                val frame = NativeFrame(anchor,nextSource,seq,packet,advanced,split)
                nativeFrame = frame
                if (packet.ready && packet.windowFrames > 0) { meterFrame = frame; meterHistory.addLast(frame) }
                if (advanced != null) transitionHistory.addLast(frame)
                breakNative = !packet.ready
            }
        } else breakNative = true
        val data = sample.pcm?.takeIf { !hard && nextSource != null && it.ready && it.inputValid && it.outputValid &&
            it.moduleEnabled && it.leaseActive && !it.invalidSamples && it.mode == mode && it.comparisonEpoch == 0 &&
            it.rate == nextSource.rate && packet?.audition == mode && sample.output.processing == OutputRuntimeStatus.Processing.ACTIVE }
        if (data != null && nextSource != null) {
            val previous = pcmFrame
            val duplicate = previous != null && same(previous.source,nextSource) &&
                previous.packet.sequence == data.sequence && previous.packet.generation == data.generation
            if (!duplicate) {
                val levels = listOf(analyse(data.inputLeft,data.inputRight),analyse(data.wetLeft,data.wetRight),
                    analyse(data.outputLeft,data.outputRight))
                val candidate = PcmFrame(anchor,nextSource,data,levels,false)
                val split = breakPcm || previous == null || !samePcm(previous,candidate) || anchor-previous.time > 1000
                val frame = candidate.copy(split = split)
                if (previous != null && !samePcm(previous,frame)) { spectrum = null; stopWorker() }
                pcmFrame = frame; audioHistory.addLast(frame); breakPcm = false
                if (layout() == 0) schedule(Work(frame,modelRevision))
            }
        } else breakPcm = true
        state = when {
            hard -> sample.runtimeUnavailableReason ?: sample.pcmUnavailableReason ?: "当前音频链路不可用"
            data != null && (data.fading || data.routeFading) -> "真实 IR / 参数 / 试听过渡窗口"
            data != null -> "真实卷积湿支路 · 干湿混合和输出衰减之前"
            currentPcm() != null -> "等待新窗口 · 暂保留末次真实 PCM"
            currentMeter() != null -> sample.pcmUnavailableReason ?: "真实 100 ms 仪表读数"
            else -> sample.pcmUnavailableReason ?: sample.runtimeUnavailableReason ?: sample.levelUnavailableReason ?: "等待活动音频"
        }
        trimHistory(); redraw()
    }
    private fun trimHistory() {
        while (audioHistory.isNotEmpty() && (audioHistory.size > 120 || audioHistory.first().time < anchor-20000)) audioHistory.removeFirst()
        while (meterHistory.isNotEmpty() && (meterHistory.size > 120 || meterHistory.first().time < anchor-20000)) meterHistory.removeFirst()
        while (transitionHistory.isNotEmpty() && (transitionHistory.size > 120 || transitionHistory.first().time < anchor-20000)) transitionHistory.removeFirst()
    }
    private fun nativeConfiguration(p: ConvolutionRuntimeReader.Snapshot): List<Int> = listOf(p.delaySamples,p.algorithmSamples,
        p.mix,p.wet,p.output,p.left,p.right,if(p.invertLeft)1 else 0,if(p.invertRight)1 else 0,if(p.swap)1 else 0)
    private fun label(c: Canvas,value: String,x: Float,y: Float,space: Float,color: Int = ink,right: Boolean = false,numeric: Boolean = false) {
        if (space <= 0) return
        val paint = if (numeric) digits else text
        paint.color = color; paint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        c.drawText(TextUtils.ellipsize(value,paint,space,TextUtils.TruncateAt.END).toString(),x,y,paint)
    }
    private fun fmt(value: Double?,places: Int = 1): String = when {
        value == null || value.isNaN() -> "—"
        value == Double.NEGATIVE_INFINITY -> "−∞"
        value == Double.POSITIVE_INFINITY -> "+∞"
        else -> String.format(Locale.ROOT,"%.${places}f",value)
    }
    private fun nativeDb(value: Int): Double? = value.takeIf { it != Int.MIN_VALUE }?.div(10.0)
    private fun nativeLevels(value: NativeFrame?): List<Levels> = value?.packet?.let { p ->
        listOf(p.input,p.wetMeter,p.result).map { Levels(nativeDb(it.rms),nativeDb(it.peak)) }
    } ?: List(3) { Levels(null,null) }
    private fun alpha(color: Int,full: Int = 230): Int = ColorUtils.setAlphaComponent(color,if (frozen || hardBlocked) 95 else full)
    private fun footer(c: Canvas,value: String) = label(c,value,2*d,height-3*d,width-4*d,muted)
    private fun status(c: Canvas,reason: String,rect: RectF = plot) {
        if (rect.height() <= 0) return
        label(c,reason,rect.left+3*d,rect.centerY()+3*d,rect.width()-6*d,muted)
    }
    private fun axes(c: Canvas,low: Double,high: Double,time: Boolean = false) {
        if (plot.width() <= 0 || plot.height() <= 0) return
        for (j in 0..2) {
            val y = plot.top+plot.height()*j/2
            c.drawLine(plot.left,y,plot.right,y,grid)
            label(c,fmt(high-(high-low)*j/2,0),plot.left-3*d,y+3*d,plot.left-5*d,muted,true,true)
        }
        for (j in 0..4) {
            val x = plot.left+plot.width()*j/4
            c.drawLine(x,plot.top,x,plot.bottom,grid)
            if (time) label(c,if(j==4)"现在" else "${-20+j*5}s",x,plot.bottom+12*d,
                max(20*d,plot.width()/4),muted,j==4)
        }
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (width <= 0 || height <= 0) return
        when (layout()) { 0 -> drawSpectrum(c); 1 -> drawLevels(c); 2 -> drawWaves(c); 3 -> drawStereo(c); else -> drawTransition(c) }
    }
    private fun drawSpectrum(c: Canvas) {
        val frame = spectrum?.takeIf { result -> currentPcm()?.let { samePcm(it,result.frame) } == true &&
            (frozen || anchor-result.frame.time <= 1000) }
        val data = frame?.frame?.packet
        val rate = data?.rate ?: source?.rate ?: model?.rate ?: 48000
        val upper = min(20000.0,rate*.5).coerceAtLeast(21.0)
        label(c,"真实音乐 FFT · 对齐干声 / 独立湿声 / 试听输出",2*d,10*d,width-4*d)
        val names = listOf("对齐干声","独立湿声·混合前","实际试听输出")
        repeat(3) { i -> label(c,names[i],width*i/3f+4*d,24*d,width/3-8*d,colors[i]) }
        plot.set(30*d,34*d,width-7*d,height-27*d)
        if (plot.height() < 10*d || plot.width() <= 0) return
        axes(c,-120.0,12.0)
        fun x(hz: Double): Float = plot.left+(ln(hz/20)/ln(upper/20)).toFloat()*plot.width()
        fun y(db: Double): Float = plot.bottom-((db.coerceIn(-120.0,12.0)+120)/132).toFloat()*plot.height()
        c.save(); c.clipRect(plot)
        frame?.levels?.forEachIndexed { series,values ->
            if (values == null || data == null) return@forEachIndexed
            path.rewind(); var joined = false
            for (bin in 1 until values.size) {
                val hz = bin.toDouble()*data.rate/data.frames
                val value = values[bin]
                if (hz < 20 || hz > upper || !value.isFinite()) { joined = false; continue }
                if (joined) path.lineTo(x(hz),y(value)) else path.moveTo(x(hz),y(value))
                joined = true
            }
            line.color = alpha(colors[series]); line.pathEffect = if (series == 2) dashed else null
            c.drawPath(path,line)
        }
        line.pathEffect = null; c.restore()
        label(c,"20 Hz",plot.left,plot.bottom+12*d,plot.width()/2,muted)
        label(c,"${fmt(upper/1000,1)} kHz",plot.right,plot.bottom+12*d,plot.width()/2,muted,true)
        if (frame == null) status(c,if(frozen)state else pcmReason ?: "等待真实卷积 PCM")
        footer(c,when {
            frozen -> "$state · 已冻结"
            data == null -> "真实音乐频谱 · 非 IR 文件频响"
            !data.alignedDryValid -> "对齐延迟正在变化 · 只绘可用湿声与试听输出"
            else -> "${if(pcmReason!=null)"末次窗口 · " else ""}${data.rate} Hz · ${data.frames}点 / ${fmt(data.windowMs,1)}ms · Δf ${fmt(data.binWidthHz,1)}Hz"
        })
    }
    private fun drawLevels(c: Canvas) {
        val native = inspectedNative ?: currentMeter()
        val audio = inspectedAudio ?: currentPcm()
        val values = nativeLevels(native)+listOf(audio?.levels?.get(2) ?: Levels(null,null))
        val names = listOf("输入","卷积湿声","正常混合","实际试听")
        label(c,"dBFS · 前三路 100ms 仪表 / 试听 PCM 短窗",2*d,10*d,width-4*d)
        repeat(4) { index ->
            val left = index*width/4f+4*d; val right = (index+1)*width/4f-5*d
            label(c,names[index],left,23*d,right-left,colors[index])
            fill.color = ColorUtils.setAlphaComponent(ink,18)
            c.drawRect(left,27*d,right,32*d,fill)
            val rms = values[index].rms; val peak = values[index].peak
            if (rms != null && rms.isFinite()) {
                fill.color = alpha(if(rms>0)red else colors[index],195)
                c.drawRect(left,27*d,left+((rms.coerceIn(-120.0,0.0)+120)/120).toFloat()*(right-left),32*d,fill)
            }
            if (peak != null && peak.isFinite()) {
                line.color = alpha(if(peak>0)red else colors[index]); line.pathEffect = null
                val x = left+((peak.coerceIn(-120.0,0.0)+120)/120).toFloat()*(right-left)
                c.drawLine(x,25*d,x,34*d,line)
            }
            label(c,"${fmt(rms)}/${fmt(peak)}",left,45*d,right-left,if(peak!=null&&peak>0)red else ink,numeric=true)
        }
        plot.set(30*d,54*d,width-7*d,height-25*d)
        if (plot.height() < 9*d || plot.width() <= 0) return
        axes(c,-120.0,12.0,true)
        val end = if (heldNative != null) heldEnd else anchor
        val natives = heldNative ?: meterHistory.toList()
        val audios = heldAudio ?: audioHistory.toList()
        repeat(3) { series ->
            historyLine(c,natives,end,{it.time},{it.split},{nativeLevels(it)[series].rms},colors[series],-120.0,12.0)
        }
        historyLine(c,audios,end,{it.time},{it.split},{it.levels[2].rms},ink,-120.0,12.0,true)
        if (native == null && audio == null) status(c,if(frozen)state else runtimeReason ?: pcmReason ?: "等待真实电平窗口")
        if (inspectedNative != null || inspectedAudio != null) drawInspection(c)
        footer(c,if(frozen)"$state · 历史已冻结" else "最近20秒 · 数字 RMS / 峰值 · 正常混合仪表在临时试听之前")
    }
    private fun <T> historyLine(c: Canvas,frames: List<T>,end: Long,time: (T)->Long,split: (T)->Boolean,
        value: (T)->Double?,color: Int,low: Double,high: Double,dash: Boolean = false) {
        path.rewind(); var joined = false
        for (frame in frames) {
            val age = end-time(frame); val v = value(frame)
            if (age !in 0..20000 || v == null || !v.isFinite()) { joined = false; continue }
            val x = plot.right-age/20000f*plot.width()
            val y = plot.bottom-((v.coerceIn(low,high)-low)/(high-low)).toFloat()*plot.height()
            if (split(frame)) joined = false
            if (joined) path.lineTo(x,y) else path.moveTo(x,y)
            joined = true
        }
        line.color = alpha(color); line.pathEffect = if(dash)dashed else null
        c.save(); c.clipRect(plot); c.drawPath(path,line); c.restore(); line.pathEffect = null
    }
    private fun drawWaves(c: Canvas) {
        val frame = currentPcm(); val data = frame?.packet
        label(c,"真实音乐时域窗口 · 左实线 / 右虚线",2*d,10*d,width-4*d)
        label(c,if(data==null)"对齐干声 / 独立湿声 / 实际试听" else
            "${data.rate} Hz · ${data.frames}点 · ${fmt(data.windowMs,2)} ms · ±1 FS",2*d,24*d,width-4*d,muted)
        plot.set(7*d,31*d,width-7*d,height-26*d)
        if (plot.height() < 12*d || plot.width() <= 0) return
        val names = listOf("对齐干声","卷积湿声","实际试听")
        val rows = if(data==null)emptyList() else listOf(data.alignedDryLeft to data.alignedDryRight,
            data.wetLeft to data.wetRight,data.outputLeft to data.outputRight)
        repeat(3) { series ->
            val top = plot.top+plot.height()*series/3; val bottom = plot.top+plot.height()*(series+1)/3
            val middle = (top+bottom)/2
            c.drawLine(plot.left,middle,plot.right,middle,grid)
            label(c,names[series],plot.left+2*d,top+9*d,plot.width()/2,colors[series])
            if (data != null && (series != 0 || data.alignedDryValid)) {
                val pair = rows[series]
                listOf(pair.first,pair.second).forEachIndexed { channel,values ->
                    path.rewind()
                    for (i in values.indices) {
                        val x = plot.left+i.toFloat()/max(1,values.lastIndex)*plot.width()
                        val y = middle-values[i].coerceIn(-1f,1f)*(bottom-top)*.42f
                        if(i==0)path.moveTo(x,y)else path.lineTo(x,y)
                    }
                    line.color = alpha(colors[series],if(channel==0)230 else 145)
                    line.pathEffect = if(channel==1)dashed else null
                    c.save(); c.clipRect(plot.left,top,plot.right,bottom); c.drawPath(path,line); c.restore()
                }
            }
        }
        line.pathEffect = null
        label(c,"0",plot.left,plot.bottom+12*d,plot.width()/3,muted)
        label(c,if(data!=null)"${fmt(data.windowMs,2)} ms" else "时间 ms",plot.right,plot.bottom+12*d,plot.width()/2,muted,true)
        if (data == null) status(c,if(frozen)state else pcmReason ?: "等待真实波形窗口")
        footer(c,when {
            frozen -> "$state · 已冻结"
            data == null -> "音乐波形不是 IR 脉冲包络 · 上方静图显示真实工作 IR"
            !data.alignedDryValid -> "公共对齐正在变化 · 干声参考暂空；湿声与试听波形仍是真实样本"
            frame?.levels?.any { it.peak?.let { v -> v>0 } == true } == true -> "存在超过 ±1 FS 的真实样本 · 图形截窗，电平未归一化"
            else -> "捕获预延时 ${fmt(data.normalKernelDelayFrames*1000.0/data.rate,3)}ms · 公共对齐 ${data.commonDelayFrames}采样 · 非实测总延迟"
        })
    }
    private fun drawStereo(c: Canvas) {
        val frame = currentPcm(); val data = frame?.packet
        label(c,"真实卷积湿声 L/R · 逐窗等比缩放外形",2*d,10*d,width-4*d)
        val side = min(width*.55f,height-31*d).coerceAtLeast(0f)
        plot.set(7*d,22*d,7*d+side,22*d+side)
        if (plot.height() <= 0 || plot.width() <= 0) return
        val cx = plot.centerX(); val cy = plot.centerY(); val radius = min(plot.width(),plot.height())*.43f
        c.drawLine(cx,plot.top,cx,plot.bottom,grid); c.drawLine(plot.left,cy,plot.right,cy,grid)
        for (factor in listOf(.5f,1f)) c.drawCircle(cx,cy,radius*factor,grid)
        label(c,"M",cx+3*d,plot.top+8*d,15*d,muted)
        label(c,"S",plot.right-2*d,cy-3*d,15*d,muted,true)
        var midRms: Double? = null; var sideRms: Double? = null; var corr: Double? = null; var sideRatio: Double? = null
        if (data != null && data.wetValid) {
            val l = data.wetLeft; val r = data.wetRight
            var mm = 0.0; var ss = 0.0; var scale = 0.0; var meanL = 0.0; var meanR = 0.0
            for (i in l.indices) {
                val m = (l[i].toDouble()+r[i])*.5; val s = (l[i].toDouble()-r[i])*.5
                mm += m*m; ss += s*s; scale = max(scale,max(abs(m),abs(s))); meanL += l[i]; meanR += r[i]
            }
            meanL /= l.size; meanR /= r.size
            var ll = 0.0; var rr = 0.0; var lr = 0.0
            for (i in l.indices) { val x=l[i]-meanL; val y=r[i]-meanR; ll+=x*x; rr+=y*y; lr+=x*y }
            if (ll>0 && rr>0) corr = (lr/sqrt(ll*rr)).coerceIn(-1.0,1.0)
            midRms = if(mm>0)10*log10(mm/l.size)else Double.NEGATIVE_INFINITY
            sideRms = if(ss>0)10*log10(ss/l.size)else Double.NEGATIVE_INFINITY
            if(mm+ss>0)sideRatio = ss/(mm+ss)*100
            if (scale>0) {
                fill.color = alpha(primary,175)
                val step = max(1,l.size/192)
                c.save(); c.clipRect(plot)
                for (i in l.indices step step) {
                    val m = (l[i].toDouble()+r[i])*.5; val s = (l[i].toDouble()-r[i])*.5
                    c.drawCircle(cx+(s/scale).toFloat()*radius,cy-(m/scale).toFloat()*radius,1.2f*d,fill)
                }
                c.restore()
            }
        }
        val right = plot.right+8*d; val space = width-right-5*d
        val native = currentMeter()?.packet?.wetMeter
        val rho = corr ?: native?.correlation?.takeIf { it != Int.MIN_VALUE }?.div(10000.0)
        label(c,if(corr!=null)"ρ · PCM窗口" else "ρ · 100ms仪表",right,29*d,space,muted)
        label(c,fmt(rho,4),right,44*d,space,primary,numeric=true)
        label(c,"Mid ${fmt(midRms)} dBFS",right,61*d,space,numeric=true)
        label(c,"Side ${fmt(sideRms)} dBFS",right,77*d,space,numeric=true)
        label(c,"Side能量 ${fmt(sideRatio)}%",right,94*d,space,numeric=true)
        val fold = native?.fold?.let { when(it) {
            Int.MIN_VALUE -> null
            ConvolutionRuntimeReader.FOLD_NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY
            else -> it/10.0
        } }
        if (height>130*d) label(c,"折叠差 ${fmt(fold)} dB · 100ms",right,111*d,space,muted,numeric=true)
        if (data == null) status(c,if(frozen)state else "相位云等待真实 PCM")
        footer(c,if(frozen)"$state · 已冻结" else "外形表示实际中侧比例；数值为真实能量，不表示方位或距离")
    }
    private fun drawTransition(c: Canvas) {
        val frame = inspectedNative ?: currentNative(); val a = frame?.advanced
        val names = listOf("稳定","线性交叉淡化","旧尾自然衰减","轻量平滑")
        val fallback = listOf("无回退","双核内存估算超限","空闲时已释放旧尾")
        label(c,"原生工作核过渡 · 剩余时间监测",2*d,10*d,width-4*d)
        label(c,if(a==null)"当前方式 — · 卷积计划 —" else "${names[a.actualMode]} · ${a.plans}套计划 · 粗估 ${fmt(a.memoryKiB/1024.0,2)} MiB",
            2*d,24*d,width-4*d)
        label(c,if(a==null)"剩余 — ms · 等待同核状态" else
            "剩余 ${fmt(a.remaining*1000.0/a.rate,1)} ms · ${fallback[a.fallback]} · CRC ${a.crc.toUInt().toString(16)}",
            2*d,38*d,width-4*d,muted)
        plot.set(35*d,49*d,width-7*d,height-25*d)
        if (plot.height() < 10*d || plot.width() <= 0) return
        axes(c,0.0,4000.0,true)
        historyLine(c,heldNative ?: transitionHistory.toList(),if(heldNative!=null)heldEnd else anchor,
            {it.time},{it.split},{it.advanced?.let { value -> value.remaining*1000.0/value.rate }},primary,0.0,4000.0)
        if (a == null) status(c,if(frozen)state else runtimeReason ?: "等待实际工作核过渡状态")
        if (inspectedNative != null) drawInspection(c)
        footer(c,if(frozen)"$state · 已冻结" else "纵轴 ms · 最近20秒 · 剩余量来自驱动，非旧尾独立波形")
    }
    private fun inspectHistory(x: Float) {
        if (plot.width() <= 0) return
        val time = heldEnd-((plot.right-x).coerceIn(0f,plot.width())/plot.width()*20000).toLong()
        inspectedNative = heldNative?.minByOrNull { abs(it.time-time) }?.takeIf { abs(it.time-time)<=1000 }
        inspectedAudio = heldAudio?.minByOrNull { abs(it.time-time) }?.takeIf { abs(it.time-time)<=1000 }
        redraw()
    }
    private fun drawInspection(c: Canvas) {
        val n = inspectedNative; val a = inspectedAudio
        val time = n?.time ?: a?.time ?: return
        fill.color = ColorUtils.setAlphaComponent(surface,245)
        c.drawRoundRect(plot.left,plot.top,plot.right,plot.top+31*d,3*d,3*d,fill)
        val rows = if(layout()==4) {
            val value = n?.advanced
            listOf("${fmt((time-heldEnd)/1000.0,2)}s · 原生核过渡",
                "剩余 ${fmt(value?.let { it.remaining*1000.0/it.rate },2)} ms · ${value?.plans ?: "—"}套计划")
        } else {
            val native = nativeLevels(n)
            listOf("${fmt((time-heldEnd)/1000.0,2)}s · 入 ${fmt(native[0].rms)} / 湿 ${fmt(native[1].rms)} dBFS",
                "正常 ${fmt(native[2].rms)} / 试听 ${fmt(a?.levels?.get(2)?.rms)} dBFS")
        }
        rows.forEachIndexed { i,value -> label(c,value,plot.left+4*d,plot.top+(12+i*14)*d,plot.width()-8*d,numeric=true) }
    }
    private fun finishInspection() {
        removeCallbacks(longPress); pendingTouch = false; inspectedNative = null; inspectedAudio = null
        if (heldNative != null || heldAudio != null) parent?.requestDisallowInterceptTouchEvent(false)
        heldNative = null; heldAudio = null
    }
    private fun meterDetails(name: String,meter: ConvolutionRuntimeReader.Meter): String {
        val rho = meter.correlation.takeIf { it != Int.MIN_VALUE }?.div(10000.0)
        val fold = when(meter.fold) {
            Int.MIN_VALUE -> null
            ConvolutionRuntimeReader.FOLD_NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY
            else -> meter.fold/10.0
        }
        return "$name：RMS ${fmt(nativeDb(meter.rms),2)} dBFS；峰值 ${fmt(nativeDb(meter.peak),2)} dBFS；"+
            "相关度 ${fmt(rho,4)}；单声道折叠差 ${fmt(fold,2)} dB"
    }
    private fun showDetails() {
        val audio = currentPcm(); val native = currentNative(); val packet = native?.packet
        val prepared = model?.prepared
        val text = buildString {
            append("当前试听：${listOf("正常混合输出","对齐原声","仅卷积湿声","效果差分")[ConvolutionController.audition.coerceIn(0,3)]}\n")
            append(if(frozen)"采样已冻结，以下保留最后有效读数。\n\n" else "\n")
            if(packet!=null) {
                append("活动核 CRC ${packet.crc.toUInt().toString(16)}；${packet.channels} 核／${packet.frames} 帧\n")
                append("实际内部 ${packet.rate} Hz；宿主流 ${native?.source?.session ?: "—"}\n")
                append("预延时 ${packet.delaySamples} 采样／${fmt(packet.delayUs/1000.0,3)} ms；公共对齐 ${packet.algorithmSamples} 采样\n")
                append("混合 ${fmt(packet.mix/10.0)}%；湿声 ${fmt(packet.wet/10.0)} dB；输出 ${fmt(packet.output/10.0)} dB\n")
                if(packet.ready&&packet.windowFrames>0) {
                    append("\n原生 ${fmt(packet.windowFrames*1000.0/packet.rate,2)} ms 仪表（临时试听前）：\n")
                    append(meterDetails("输入",packet.input)+"\n")
                    append(meterDetails("校准后湿声",packet.wetMeter)+"\n")
                    append(meterDetails("正常混合输出",packet.result)+"\n")
                }
                native?.advanced?.let { a ->
                    append("\n换核：请求方式 ${a.requestedMode}，实际方式 ${a.actualMode}；${a.plans} 套计划\n")
                    append("剩余 ${a.remaining} 采样／${fmt(a.remaining*1000.0/a.rate,2)} ms；旧核 ${a.oldFrames} 帧；内存粗估 ${fmt(a.memoryKiB/1024.0,2)} MiB；回退 ${a.fallback}\n")
                    append("实际滤波 Hz：${a.cutoffs.joinToString(" / ")}；滤波过渡剩余 ${a.filterTransition} 采样\n")
                    append("B 临时电平补偿 ${fmt(a.comparison/10.0)} dB\n")
                }
            }
            if(audio!=null) {
                val pcm = audio.packet
                append("\n真实 PCM：${pcm.frames} 点／${fmt(pcm.windowMs,3)} ms；${pcm.rate} Hz；Δf ${fmt(pcm.binWidthHz,3)} Hz\n")
                append("序列 ${pcm.sequence}；音频代 ${pcm.generation}；配置代 ${pcm.configurationEpoch}\n")
                append("位置 ${pcm.firstFramePosition}…${pcm.lastFramePosition}；公共对齐 ${pcm.commonDelayFrames}→${pcm.commonDelayEndFrames} 采样\n")
                append("捕获预延时 ${pcm.normalKernelDelayFrames} 采样；过渡 ${pcm.fading}；试听淡化 ${pcm.routeFading}\n")
                listOf("原输入","独立卷积湿声","实际试听输出").forEachIndexed { i,name ->
                    append("$name：RMS ${fmt(audio.levels[i].rms,2)}／峰值 ${fmt(audio.levels[i].peak,2)} dBFS\n")
                }
            }
            if(prepared!=null) {
                append("\n工作副本 CRC ${prepared.crc.toUInt().toString(16)}；${prepared.processingSampleRate} Hz；${prepared.status}\n")
                append(if(packet!=null&&packet.crc==prepared.crc&&packet.loaded)"与本音频流活动 CRC 一致。\n" else "副本尚未与本音频流活动 CRC 确认，不能替代当前声音。\n")
            }
            append("\n湿声含 IR、湿声滤波、预延时、校准和湿增益，位于干湿混合、输出衰减和 B 临时补偿之前。")
            append("FFT 是真实音乐窗口的单边幅度谱，不是 IR 文件频响；相位云来自真实湿声 L/R，逐窗等比缩放不代表物理距离。")
            append("100ms 原生仪表与 PCM 短窗是不同统计窗口；本模块没有自动 A/B 测量或独立旧尾 PCM。")
        }
        if(audio==null&&native==null) return
        detailDialog?.dismiss()
        detailDialog = GlassAlertDialogBuilder(context).setTitle(focus.liveTitle).setMessage(text)
            .setPositiveButton(android.R.string.ok,null).show()
    }
    private fun schedule(work: Work) {
        if (!canDraw || frozen || layout()!=0 || work.modelRevision!=modelRevision || anchor-work.frame.time>1000) return
        pendingWork = work
        if(worker==null)startWork()
    }
    private fun startWork() {
        val requested = pendingWork ?: return
        pendingWork = null
        val token = workToken
        val pool = executor ?: Executors.newSingleThreadExecutor { task ->
            Thread(task,"convolution-focus-fft").apply { isDaemon = true }
        }.also { executor = it }
        worker = pool.submit {
            val result = runCatching { calculate(requested) }.getOrNull()
            main.post {
                if(token!=workToken)return@post
                worker = null
                if(result!=null&&result.revision==modelRevision&&currentPcm()?.let { samePcm(it,result.frame) }==true&&
                    anchor-result.frame.time<=1000) spectrum = result
                if(pendingWork!=null)startWork()
                redraw()
            }
        }
    }
    private fun calculate(work: Work): Spectrum {
        val data = work.frame.packet; val n = data.frames
        require(n==1024)
        val window = DoubleArray(n) { .5-.5*cos(2*PI*it/n) }; val sum = window.sum()
        fun transform(samples: FloatArray): DoubleArray {
            require(samples.size==n)
            val real = DoubleArray(n) { samples[it]*window[it] }; val imaginary = DoubleArray(n)
            var j = 0
            for(i in 1 until n) {
                var bit = n shr 1
                while(j and bit!=0) { j=j xor bit; bit=bit shr 1 }
                j=j xor bit
                if(i<j) { var t=real[i];real[i]=real[j];real[j]=t;t=imaginary[i];imaginary[i]=imaginary[j];imaginary[j]=t }
            }
            var length = 2
            while(length<=n) {
                if(Thread.currentThread().isInterrupted)throw InterruptedException()
                val theta=-2*PI/length;val wr=cos(theta);val wi=sin(theta)
                for(start in 0 until n step length) {
                    var ur=1.0;var ui=0.0
                    for(k in 0 until length/2) {
                        val a=start+k;val b=a+length/2
                        val vr=real[b]*ur-imaginary[b]*ui;val vi=real[b]*ui+imaginary[b]*ur
                        real[b]=real[a]-vr;imaginary[b]=imaginary[a]-vi;real[a]+=vr;imaginary[a]+=vi
                        val next=ur*wr-ui*wi;ui=ur*wi+ui*wr;ur=next
                    }
                }
                length=length shl 1
            }
            return DoubleArray(n/2+1) { bin -> hypot(real[bin],imaginary[bin])*(if(bin==0||bin==n/2)1 else 2)/sum }
        }
        val channels = listOf(data.alignedDryLeft to data.alignedDryRight,data.wetLeft to data.wetRight,
            data.outputLeft to data.outputRight)
        val valid = listOf(data.alignedDryValid,data.wetValid,data.outputValid)
        val levels = channels.mapIndexed { i,pair ->
            if(!valid[i])null else {
                val left=transform(pair.first);val right=transform(pair.second)
                DoubleArray(left.size) { bin ->
                    val amplitude=sqrt((left[bin]*left[bin]+right[bin]*right[bin])*.5)
                    if(amplitude>0)20*log10(amplitude)else Double.NEGATIVE_INFINITY
                }
            }
        }
        return Spectrum(work.frame,work.modelRevision,levels)
    }
    private fun stopWorker() {
        workToken++; worker?.cancel(true); worker=null; pendingWork=null
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishInspection();downX=event.x;downY=event.y;cursorX=downX;pendingTouch=true
                if(layout()==1||layout()==4)postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(event.pointerCount!=1) {finishInspection();return false}
                cursorX=event.x
                if(heldNative!=null) {inspectHistory(cursorX);return true}
                if(pendingTouch&&(abs(event.x-downX)>slop||abs(event.y-downY)>slop)) {
                    removeCallbacks(longPress);pendingTouch=false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val click=pendingTouch&&abs(event.x-downX)<=slop&&abs(event.y-downY)<=slop
                finishInspection();if(click){performClick();showDetails()};redraw();return true
            }
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP -> {
                finishInspection();redraw();return false
            }
        }
        return pendingTouch||heldNative!=null
    }
    override fun performClick(): Boolean {super.performClick();return true}
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if(layout()==0)currentPcm()?.let {schedule(Work(it,modelRevision))}
        redraw()
    }
    override fun onDetachedFromWindow() {
        finishInspection();detailDialog?.dismiss();detailDialog=null;stopWorker()
        executor?.shutdownNow();executor=null
        super.onDetachedFromWindow()
    }
}
