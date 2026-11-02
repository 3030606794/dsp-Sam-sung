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
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.DdcChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.DdcController
import me.timschneeberger.rootlessjamesdsp.fusion.DdcDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.DdcDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.DdcPcmTelemetry
import me.timschneeberger.rootlessjamesdsp.fusion.DdcParameters
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/** Captured DDC audio and independent native main-kernel/file diagnostics. GET only. */
internal class DdcFocusRealtimeChart(context: Context) : View(context) {
    private data class Frame(
        val time: Long, val source: Any, val session: Int?, val rate: Int,
        val mode: Int, val toolGeneration: Int, val rawToolEpoch: Int,
        val pcm: Boolean, val generation: Int?, val configuration: Int?, val sequence: Int,
        val factor: Int?, val selectedFactor: Int?, val oldFactor: Int?,
        val delay: Int?, val delayEnd: Int?, val normalDelay: Int?, val selectedDelay: Int?,
        val alignmentChanged: Boolean, val fading: Boolean, val routeFading: Boolean,
        val aligned: Boolean, val split: Boolean, val rms: List<Double?>,
        val peaks: List<Double?>, val runtime: List<Int>?, val file: String?, val outputLeft: Double?, val outputRight: Double?
    )
    private data class MeterFrame(val time: Long, val source: Any, val session: Int?, val rate: Int,
        val mode: Int, val tools: Int, val sequence: Int, val file: String?,
        val automatic: Double, val preamp: Double, val output: Double, val prediction: Double, val split: Boolean)
    private data class NativeResponse(val time: Long, val source: Any, val session: Int?, val rate: Int,
        val mode: Int, val tools: Int, val file: String?, val data: DdcParameters.Response)
    private data class FileState(val time: Long, val source: Any, val session: Int?,
        val data: DdcParameters.Runtime, val selected: String?)
    private data class Work(val pcm: DdcPcmTelemetry.Snapshot, val frame: Frame, val modelRevision: Long)
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
    private val responseRightDash = DashPathEffect(floatArrayOf(5f * d, 3f * d), 0f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val plot = RectF()
    private val main = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var worker: Future<*>? = null
    private var pendingWork: Work? = null
    private var workToken = 0L
    private var modelRevision = 0L
    private var model: DdcDashboardModel? = null
    private var latestPcm: Work? = null
    private var spectrum: Spectrum? = null
    private var response: NativeResponse? = null
    private var fileState: FileState? = null
    private var detailDialog: AlertDialog? = null
    private var focus = DdcChartFocus.FILE
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
    private var held: List<Frame>? = null
    private var heldMeters: List<MeterFrame>? = null
    private var heldEnd = 0L
    private var downX = 0f
    private var downY = 0f
    private var pendingTouch = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val modes = listOf("正常处理", "本模块输入原声", "正常处理（含混合）", "处理差分", "快照 A", "快照 B", "双快照测量")
    private val longPress = Runnable {
        if (pendingTouch && isShown && !usesSpectrum() && focus != DdcChartFocus.FILE && focus != DdcChartFocus.RESPONSE && history.isNotEmpty()) {
            pendingTouch = false; held = history.toList(); heldMeters = meterHistory.toList(); heldEnd = anchor
            parent?.requestDisallowInterceptTouchEvent(true); select(downX)
        }
    }
    private val canDraw: Boolean get() = active && isAttachedToWindow && windowVisibility == VISIBLE
    private fun redraw() { if (canDraw) invalidate() }
    private fun usesSpectrum(): Boolean = when (focus) {
        DdcChartFocus.RATIO, DdcChartFocus.COMPENSATION, DdcChartFocus.LOW, DdcChartFocus.HIGH,
        DdcChartFocus.PEAK1, DdcChartFocus.PEAK2, DdcChartFocus.EAR_RATIO -> true
        else -> false
    }
    private fun usesHeadroom(): Boolean = focus == DdcChartFocus.HEADROOM || focus == DdcChartFocus.PREAMP
    fun setConfiguration(value: DdcDashboardModel) {
        val old = model
        model = value
        if (old != null && old.settings == value.settings && old.rate == value.rate && old.enabled == value.enabled) return
        if (old != null && old.rate != value.rate) {
            modelRevision++; spectrum = null; response = null; latestPcm = null; current = null; breakNext = true; stopWorker()
        }
        // Saved targets can warm for seconds while the preceding real kernel remains active.
        redraw()
    }
    fun setFocus(value: DdcChartFocus, key: String = "") {
        if (focus == value && focusKey == key) return
        finishInspection(); detailDialog?.dismiss(); detailDialog = null; focus = value; focusKey = key
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
        a.routeFading == b.routeFading && a.fading == b.fading && a.file == b.file

    private fun fileStamp(packet: DdcParameters.Runtime?): String? = packet?.let { (it.activeSha256 ?: "none")+":"+it.data[19] }
    fun showSample(sample: DdcDashboardRuntimeMonitor.Sample) {
        anchor=max(anchor,sample.elapsedRealtimeMs);frozen=false
        while(history.isNotEmpty()&&(history.size>120||history.first().time<anchor-20000))history.removeFirst()
        while(meterHistory.isNotEmpty()&&(meterHistory.size>120||meterHistory.first().time<anchor-20000))meterHistory.removeFirst()
        val mode=DdcController.audition;val epoch=DdcController.epoch;val tools=if(mode==0)0 else epoch
        val identity=sample.streamIdentity;val packet=sample.reading?.snapshot
        val hard=!sample.output.connected||sample.output.globalIssue!=null||sample.output.activeSessions.size>1
        nativeBlocked=hard
        val runtime=packet?.data?.takeIf{!hard&&sample.reading?.active==true&&sample.runtimeUnavailableReason==null&&
            packet.enabled&&packet.ready&&it[16]==sample.reading?.rate&&it[6]==mode&&(mode==0||it[12]==epoch)}
        val pcm=sample.pcm?.takeIf{!hard&&it.mode==mode&&(mode==0||it.comparisonEpoch==epoch)&&it.moduleEnabled&&
            it.ready&&it.inputValid&&it.outputValid&&sample.output.processing==OutputRuntimeStatus.Processing.ACTIVE}
        val previous=current;val rate=pcm?.rate?:packet?.rate?.takeIf{it in 8000..768000}
        val compatible=identity!=null&&rate!=null&&previous!=null&&sameContext(previous,identity,sample.streamSessionId,rate,mode,tools)
        if(hard||previous!=null&&(identity!=null&&(previous.source!==identity||previous.session!=sample.streamSessionId)||
            previous.mode!=mode||previous.toolGeneration!=tools||rate!=null&&previous.rate!=rate)){
            current=null;latestPcm=null;spectrum=null;response=null;fileState=null;breakNext=true;stopWorker()
        }
        // File preparation metadata is a fresh GET, not proof that music is active.
        if(!hard&&identity!=null&&packet!=null&&packet.rate in 8000..768000&&packet.data[16]==sample.reading?.rate){
            fileState=FileState(anchor,identity,sample.streamSessionId,packet,sample.selectedSha256)
        }
        if(runtime!=null&&identity!=null){
            val last=meterHistory.lastOrNull();val stamp=fileStamp(packet)
            val splitMeter=breakNext||last==null||last.source!==identity||last.session!=sample.streamSessionId||
                last.rate!=runtime[2]||last.mode!=mode||last.tools!=tools||last.file!=stamp||anchor-last.time>1000
            if(packet?.fileReady==true&&(splitMeter||last?.sequence!=runtime[1]))meterHistory.addLast(MeterFrame(anchor,identity,sample.streamSessionId,
                runtime[2],mode,tools,runtime[1],stamp,runtime[29]/100.0,runtime[30]/10.0,runtime[31]/10.0,runtime[59]/100.0,splitMeter))
            val native=sample.reading?.response
            if(native!=null&&native.ready&&runtime[14]==1&&native.rate==runtime[2]&&native.data[1]==runtime[1])
                response=NativeResponse(anchor,identity,sample.streamSessionId,runtime[2],mode,tools,stamp,native)
            else if(runtime[14]!=1)response=null
        }
        if(identity==null||(pcm==null&&runtime==null)){
            breakNext=true
            val softMissing=!hard&&previous!=null&&previous.mode==mode&&previous.toolGeneration==tools&&
                anchor-previous.time<=1000&&(identity==null||compatible)
            if(!softMissing){current=null;latestPcm=null;spectrum=null;response=null;stopWorker()
                state=sample.runtimeUnavailableReason?:sample.pcmUnavailableReason?:sample.levelUnavailableReason?:"等待活动音频"}
            redraw();return
        }
        if(pcm==null&&compatible&&previous!=null&&previous.pcm&&anchor-previous.time<=1000){
            current=previous.copy(runtime=runtime?:previous.runtime);redraw();return
        }
        val sequence:Int=pcm?.sequence?:runtime!![1]
        if(pcm!=null&&compatible&&previous!=null&&previous.pcm&&previous.generation==pcm.generation&&previous.sequence==sequence){
            current=previous.copy(runtime=runtime?:previous.runtime);redraw();return
        }
        if(pcm==null&&compatible&&previous!=null&&!previous.pcm&&previous.sequence==sequence){redraw();return}
        val rms:List<Double?>;val peaks:List<Double?>;val left:Double?;val right:Double?
        if(pcm!=null){
            val input=analyse(pcm.inputLeft,pcm.inputRight)
            val normal=if(pcm.wetValid&&pcm.wetIsNormalConfiguration)analyse(pcm.wetLeft,pcm.wetRight)else null
            val output=analyse(pcm.outputLeft,pcm.outputRight)
            rms=listOf(input.first,normal?.first,output.first);peaks=listOf(input.second,normal?.second,output.second)
            left=analyse(pcm.outputLeft,pcm.outputLeft).first;right=analyse(pcm.outputRight,pcm.outputRight).first
        }else{
            val meters=runtime?.get(13)==1
            rms=listOf(if(meters)maximum(raw(runtime?.get(24)),raw(runtime?.get(25)))else null,null,
                if(meters)maximum(raw(runtime?.get(26)),raw(runtime?.get(27)))else null)
            peaks=listOf(if(meters)maximum(raw(runtime?.get(20)),raw(runtime?.get(21)))else null,null,
                if(meters)maximum(raw(runtime?.get(22)),raw(runtime?.get(23)))else null)
            left=if(meters)raw(runtime?.get(26))else null;right=if(meters)raw(runtime?.get(27))else null
        }
        val stamp=fileStamp(packet)
        val split=breakNext||previous==null||!compatible||previous.pcm!=(pcm!=null)||previous.file!=stamp||
            previous.generation!=pcm?.generation||previous.factor!=pcm?.effectiveFactor||previous.selectedFactor!=pcm?.selectedFactor||
            previous.oldFactor!=pcm?.oldFactor||previous.delay!=pcm?.commonDelayFrames||previous.delayEnd!=pcm?.commonDelayEndFrames||
            previous.normalDelay!=pcm?.normalKernelDelayFrames||previous.selectedDelay!=pcm?.selectedKernelDelayFrames||
            previous.alignmentChanged!=(pcm?.commonDelayChanged==true)||previous.fading!=(pcm?.fading==true)||
            previous.routeFading!=(pcm?.routeFading==true)||anchor-previous.time>1000
        // configurationEpoch advances with processing blocks. Generation and native
        // file/route/alignment identities identify actual observation boundaries.
        val frame=Frame(anchor,identity,sample.streamSessionId,rate!!,mode,tools,pcm?.comparisonEpoch?:runtime!![12],
            pcm!=null,pcm?.generation,pcm?.configurationEpoch,sequence,pcm?.effectiveFactor,pcm?.selectedFactor,pcm?.oldFactor,
            pcm?.commonDelayFrames,pcm?.commonDelayEndFrames,pcm?.normalKernelDelayFrames,pcm?.selectedKernelDelayFrames,
            pcm?.commonDelayChanged==true,pcm?.fading==true,pcm?.routeFading==true,pcm?.alignedDryValid==true,split,rms,peaks,runtime,stamp,left,right)
        if(previous!=null&&(!samePcmContext(previous,frame)||!frame.pcm)){spectrum=null;latestPcm=null;stopWorker()}
        current=frame;history.addLast(frame);breakNext=false
        state=when{
            pcm==null->sample.levelUnavailableReason?:"原生仪表读数 · 等待 DDC PCM"
            packet?.fileReady!=true->"尚无活动 DDC 文件 · 当前为真实旁路声音"
            packet?.data?.get(60)==1->"文件 / 配置交接期间的真实捕获窗口"
            pcm.fading||pcm.routeFading->"正常内核 / 试听路径过渡窗口"
            else->"正常处理已含文件比例、个人补偿和电平 · 非独立文件支路"
        }
        if(pcm!=null){latestPcm=Work(pcm,frame,modelRevision);if(usesSpectrum())schedule(latestPcm!!)}
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
    override fun onDraw(c:Canvas){
        super.onDraw(c);if(width<80*d||height<80*d)return
        when{
            focus==DdcChartFocus.FILE->{drawFile(c);return}
            usesHeadroom()->{drawHeadroom(c);return}
            focus==DdcChartFocus.EAR_LEVEL->{drawEars(c);return}
            focus==DdcChartFocus.RESPONSE->{drawResponse(c);return}
        }
        val frame=inspected?:current
        label(c,"输入 / 正常处理（含混合） / 实际试听 · RMS dBFS",2*d,10*d,width-4*d)
        val step=(width-35*d)/3
        repeat(3){i->
            val x=30*d+i*step;val value=frame?.rms?.get(i)
            label(c,listOf("入","常","出")[i],x,24*d,14*d,colors[i])
            label(c,fmt(value).padStart(7),x+step-7*d,24*d,step-17*d,colors[i],true,true)
            val bounds=RectF(x,28*d,x+step-9*d,34*d);fill.color=ColorUtils.setAlphaComponent(ink,18);c.drawRoundRect(bounds,2*d,2*d,fill)
            if(value!=null){fill.color=ColorUtils.setAlphaComponent(colors[i],if(frozen)90 else 190);c.drawRoundRect(RectF(bounds.left,bounds.top,bounds.left+bounds.width()*((value.coerceIn(-96.0,6.0)+96)/102).toFloat(),bounds.bottom),2*d,2*d,fill)}
        }
        val match=focus==DdcChartFocus.MATCH
        if(match)drawMatch(c,frame)else{
            val detail=if(usesSpectrum())" · 观察窗 FFT1024" else " · 输出峰值 ${fmt(frame?.peaks?.get(2))} dBFS"
            label(c,"试听：${frame?.mode?.let{modes.getOrElse(it){"等待确认"}}?:"等待确认"}$detail",2*d,46*d,width-4*d,muted)
        }
        plot.set(30*d,(if(match)70 else 55)*d,width-7*d,height-26*d)
        if(plot.height()<9*d)return
        if(usesSpectrum())drawSpectrum(c)else drawHistory(c)
        label(c,if(frozen)"$state · 已冻结" else state,2*d,height-3*d,width-4*d,muted)
    }
    private fun drawMatch(c:Canvas,frame:Frame?){
        val data=frame?.runtime?.takeIf{it[12]==DdcController.epoch&&it[6]==DdcController.audition&&
            it[5]==1&&it[50]==0&&it[60]==0&&DdcParameters.sha256(it.subList(36,44))!=null&&
            DdcParameters.sha256(it.subList(36,44))==DdcParameters.sha256(it.subList(62,70))}
        val phase=when(data?.get(8)){
            1->"A/B 预热 ${data[48]}/${data[49]} ms"
            2->"A/B 测量 ${data[9]}/2000 ms"
            3->"A/B 测量完成"
            4->"A/B 信号不足"
            0->"尚未完成测量"
            else->"等待当前文件与工具代的 A/B 读数"
        }
        label(c,phase,2*d,46*d,width-4*d,primary)
        val result=data?.takeIf{it[8]==3&&DdcController.matchingReady()}
        label(c,"固定衰减 A ${fmt(raw(result?.get(10)),2)} / B ${fmt(raw(result?.get(11)),2)} dB",2*d,59*d,width-4*d,muted)
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
    private fun drawEars(c:Canvas){
        val frame=inspected?:current;val left=frame?.outputLeft;val right=frame?.outputRight
        label(c,"实际试听输出 · 左右 RMS dBFS",2*d,10*d,width-4*d)
        label(c,"L ${fmt(left)}",2*d,25*d,width*.48f,primary)
        label(c,"R ${fmt(right)}",width-2*d,25*d,width*.48f,cyan,true)
        label(c,"当前 L−R ${fmt(if(left!=null&&right!=null)left-right else null)} dB",2*d,39*d,width-4*d,muted)
        plot.set(30*d,49*d,width-7*d,height-26*d);if(plot.height()<10*d)return
        axes(c,-96.0,0.0)
        val values=held?:history.toList();val end=if(held!=null)heldEnd else anchor
        repeat(2){series->
            path.rewind();var joined=false
            for(value in values){
                val number=if(series==0)value.outputLeft else value.outputRight
                val x=plot.right-(end-value.time)/20000f*plot.width()
                if(number==null||x<plot.left){joined=false;continue}
                val y=plot.bottom-((number.coerceIn(-96.0,0.0)+96)/96).toFloat()*plot.height()
                if(!joined||value.split)path.moveTo(x,y)else path.lineTo(x,y);joined=true
            }
            line.color=ColorUtils.setAlphaComponent(if(series==0)primary else cyan,if(frozen)100 else 230)
            c.save();c.clipRect(plot);c.drawPath(path,line);c.restore()
        }
        label(c,"−20s",plot.left,plot.bottom+12*d,plot.width()/2,muted)
        label(c,"现在",plot.right,plot.bottom+12*d,plot.width()/2,muted,true)
        if(left==null&&right==null)label(c,if(frozen)state else "等待真实左右电平或 PCM",plot.left+3*d,plot.centerY()+3*d,plot.width()-6*d,muted)
        label(c,if(frozen)"$state · 已冻结" else "左右差来自当前音乐能量 · 不是耳机校准误差",2*d,height-3*d,width-4*d,muted)
    }
    private fun nativeNow():MeterFrame?=meterHistory.lastOrNull()?.takeIf{value->
        (frozen||!nativeBlocked&&anchor-value.time<=1000)&&current?.let{frame->
            sameContext(frame,value.source,value.session,value.rate,value.mode,value.tools)&&frame.file==value.file}==true}
    private fun drawHeadroom(c:Canvas){
        val meter=if(heldMeters!=null)heldMeters?.minByOrNull{abs(it.time-(inspected?.time?:heldEnd))}else nativeNow()
        label(c,"主配置余量 / 前级 / 输出 · dB",2*d,10*d,width-4*d)
        val step=(width-4*d)/3
        val numbers=listOf(meter?.automatic,meter?.preamp,meter?.output)
        repeat(3){i->label(c,listOf("自动 ","前级 ","输出 ")[i]+fmt(numbers[i]),2*d+(i+1)*step-3*d,25*d,step-3*d,colors[i],true,true)}
        label(c,"预测峰值 ${fmt(meter?.prediction)} dB（自动余量之前）",2*d,39*d,width-4*d,muted)
        plot.set(30*d,49*d,width-7*d,height-26*d);if(plot.height()<10*d)return
        axes(c,-96.0,24.0)
        val values=heldMeters?:meterHistory.toList();val end=if(heldMeters!=null)heldEnd else anchor
        repeat(3){series->
            path.rewind();var joined=false
            for(value in values){
                val number=when(series){0->value.automatic;1->value.preamp;else->value.output}
                val x=plot.right-(end-value.time)/20000f*plot.width()
                if(x<plot.left){joined=false;continue}
                val y=plot.bottom-((number.coerceIn(-96.0,24.0)+96)/120).toFloat()*plot.height()
                if(!joined||value.split)path.moveTo(x,y)else path.lineTo(x,y);joined=true
            }
            line.color=ColorUtils.setAlphaComponent(colors[series],if(frozen)100 else 230)
            c.save();c.clipRect(plot);c.drawPath(path,line);c.restore()
        }
        label(c,"−20s",plot.left,plot.bottom+12*d,plot.width()/2,muted)
        label(c,"现在",plot.right,plot.bottom+12*d,plot.width()/2,muted,true)
        if(meter==null)label(c,if(frozen)state else "等待当前主配置余量读数",plot.left+3*d,plot.centerY()+3*d,plot.width()-6*d,muted)
        label(c,if(frozen)"$state · 已冻结" else "源自当前主内核 · 自动余量不是实时压缩 GR",2*d,height-3*d,width-4*d,muted)
    }
    private fun filePhase(value:DdcParameters.Runtime):String{
        val a=value.data
        return when{
            a[50]==1->"当前内部率或文件系数映射未就绪"
            a[51]!=0->"文件处理状态码 ${a[51]} · 保留活动文件读数"
            !value.fileReady->"尚无活动 DDC 文件"
            a[60]==1&&a[12]==0&&a[49]>0->"主配置预热 ${a[48]}/${a[49]} ms"
            a[61]!=a[19]||value.activeSha256!=value.acceptedSha256->"文件版本已接收 · 等待活动内核交接"
            a[60]==1->"配置 / 文件正在交接"
            else->"活动文件稳定"
        }
    }
    private fun drawFile(c:Canvas){
        val value=fileState?.takeIf{frozen||!nativeBlocked&&anchor-it.time<=1000}
        val packet=value?.data;val a=packet?.data
        label(c,"文件选择 / 驱动接收 / 活动内核",2*d,10*d,width-4*d)
        label(c,"已接收不等于已交接 · 无音乐时仅显示配置元数据",2*d,24*d,width-4*d,muted)
        plot.set(4*d,31*d,width-4*d,height-23*d)
        if(plot.height()<20*d)return
        val labels=listOf("选择","已接收","活动","采样率","状态")
        val rows=listOf(
            (model?.fileLabel?:"未选择 VDC")+" · "+(value?.selected?.take(10)?:"身份待确认"),
            (packet?.acceptedSha256?.take(12)?:"—")+" · 代 ${a?.get(61)?:"—"}",
            (packet?.activeSha256?.take(12)?:"—")+" · ${a?.get(18)?:"—"} SOS / 代 ${a?.get(19)?:"—"}",
            "内部 ${a?.get(2)?:"—"} / 宿主 ${a?.get(16)?:"—"} Hz",
            packet?.let(::filePhase)?:state)
        val step=plot.height()/5
        repeat(5){i->
            val y=plot.top+step*i+min(step-2*d,11*d)
            label(c,labels[i],plot.left,y,42*d,if(i==2)primary else muted)
            label(c,rows[i],plot.left+44*d,y,plot.width()-46*d,if(i==2)primary else ink)
        }
        label(c,if(frozen)"$state · 已冻结" else "点按查看完整文件身份、VDC系数率与交接状态",2*d,height-3*d,width-4*d,muted)
    }
    private fun currentResponse():NativeResponse?{
        val mode=DdcController.audition;val tools=if(mode==0)0 else DdcController.epoch
        return response?.takeIf{it.mode==mode&&it.tools==tools&&(frozen||!nativeBlocked&&anchor-it.time<=1000)&&
            (model?.settings?.response==true||frozen)&&current?.let{frame->
                sameContext(frame,it.source,it.session,it.rate,it.mode,it.tools)&&frame.file==it.file}==true}
    }
    private fun drawResponse(c:Canvas){
        label(c,"活动主内核 · 文件 / 左 / 右响应预测 dB",2*d,10*d,width-4*d)
        label(c,"青淡：原文件 · 紫实：左输出 · 橙虚：右输出",2*d,24*d,width-4*d,muted)
        plot.set(30*d,36*d,width-7*d,height-26*d);if(plot.height()<10*d)return
        axes(c,-48.0,24.0)
        val value=currentResponse();val upper=min(20000.0,(value?.rate?:current?.rate?:model?.rate?:48000)*.499).coerceAtLeast(21.0)
        if(value!=null){
            repeat(3){series->
                path.rewind();var joined=false
                for(point in value.data.points){
                    val hz=point[0].toDouble();val number=raw(point[series+1])
                    if(hz<20||hz>upper+1||number==null||point[6]!=1){joined=false;continue}
                    val x=plot.left+(ln(hz/20)/ln(upper/20)).toFloat()*plot.width()
                    val y=plot.bottom-((number.coerceIn(-48.0,24.0)+48)/72).toFloat()*plot.height()
                    if(joined)path.lineTo(x,y)else path.moveTo(x,y);joined=true
                }
                line.color=ColorUtils.setAlphaComponent(colors[series],if(series==0){if(frozen)65 else 145}else if(frozen)100 else 230)
                line.pathEffect=if(series==2)responseRightDash else null
                c.save();c.clipRect(plot);c.drawPath(path,line);c.restore()
            }
            line.pathEffect=null
        }else label(c,if(frozen)state else if(model?.settings?.response!=true)"请打开原生 DDC 响应图开关" else "等待活动文件的同一原子响应快照",plot.left+3*d,plot.centerY()+3*d,plot.width()-6*d,muted)
        label(c,"20 Hz",plot.left,plot.bottom+12*d,plot.width()/2,muted)
        label(c,"${fmt(upper/1000,1)} kHz",plot.right,plot.bottom+12*d,plot.width()/2,muted,true)
        label(c,if(frozen)"$state · 已冻结" else "点按查看频点 · 预测不含交接混合与 A/B 路由，非实测 FFT",2*d,height-3*d,width-4*d,muted)
    }
    private fun inspectPoint(x:Float){
        val title:String;val body:String
        if(focus==DdcChartFocus.RESPONSE){
            val response=currentResponse()?:return
            val upper=min(20000.0,response.rate*.499).coerceAtLeast(21.0)
            val hz=20*(upper/20).pow(((x-plot.left)/plot.width()).coerceIn(0f,1f).toDouble())
            val point=response.data.points.filter{it[0]<=upper+1}.minByOrNull{abs(ln(it[0].coerceAtLeast(1)/hz))}?:return
            title="活动文件预测 · ${point[0]} Hz"
            body="原文件 ${fmt(raw(point[1]),2)} dB\n左耳输出 ${fmt(raw(point[2]),2)} dB\n右耳输出 ${fmt(raw(point[3]),2)} dB\n"+
                "频点有效：${if(point[6]==1)"是" else "否"}\n\n基础 ${response.rate} Hz · 同一原子快照\n"+
                "完整输出预测含文件复数比例、个人补偿、前级、自动余量、耳电平与输出；属于当前活动主内核，不含文件交接混合或 A/B 试听路径，无相位字段。"
        }else if(focus==DdcChartFocus.FILE){
            val value=fileState?.takeIf{frozen||!nativeBlocked&&anchor-it.time<=1000}?:return;val packet=value.data;val a=packet.data
            title="DDC 文件身份与活动状态"
            body="页面选择：${model?.fileLabel?:"未选择"}\n选择身份：${value.selected?:"尚未确认"}\n\n"+
                "驱动已接收：${packet.acceptedSha256?:"—"}\n接收代 ${a[61]}\n\n"+
                "当前活动：${packet.activeSha256?:"—"}\n活动代 ${a[19]} · ${a[18]} SOS\n\n"+
                "内部 ${a[2]} / 宿主 ${a[16]} / 当前 VDC 系数率 ${a[17]} Hz\n"+
                filePhase(packet)+"\n\n"+
                "选择、接收与活动是不同阶段；新文件或配置预热交接前仍可播放旧活动文件。工具代非零时的 ${a[48]}/${a[49]} ms 属于 A/B 预热，不能冒充主配置预热。"+
                if(frozen)"\n当前采样已冻结，保留最后真实读数。"else""
        }else return
        detailDialog?.dismiss();detailDialog=GlassAlertDialogBuilder(context).setTitle(title).setMessage(body).setPositiveButton(android.R.string.ok,null).show()
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
        val pool = executor ?: Executors.newSingleThreadExecutor { task -> Thread(task, "ddc-focus-fft").apply { isDaemon = true } }.also { executor = it }
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
    private fun finishInspection() { pendingTouch = false; main.removeCallbacks(longPress); held = null; heldMeters = null; inspected = null; parent?.requestDisallowInterceptTouchEvent(false) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (!plot.contains(event.x, event.y)) return false; downX = event.x; downY = event.y; pendingTouch = true; main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()); return true }
            MotionEvent.ACTION_MOVE -> { if (held != null) select(event.x) else if (abs(event.x-downX) > slop || abs(event.y-downY) > slop) { pendingTouch = false; main.removeCallbacks(longPress) }; return true }
            MotionEvent.ACTION_UP -> {
                val inspect=pendingTouch&&(focus==DdcChartFocus.RESPONSE||focus==DdcChartFocus.FILE)
                finishInspection();performClick();if(inspect)inspectPoint(event.x);redraw();return true
            }
            MotionEvent.ACTION_CANCEL -> { finishInspection(); redraw(); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (usesSpectrum()) latestPcm?.let(::schedule); redraw() }
    override fun onDetachedFromWindow() { detailDialog?.dismiss();detailDialog=null;finishInspection(); stopWorker(); super.onDetachedFromWindow() }
}
