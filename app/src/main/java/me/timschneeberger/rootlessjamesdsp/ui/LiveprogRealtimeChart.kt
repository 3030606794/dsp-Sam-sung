package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogAudioFrame
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogRealtimeMode
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/** Observes only script input/output captures. No microphone, script execution or parameter writes. */
internal class LiveprogRealtimeChart(context: Context) : View(context) {
    private data class Source(val stream: Any?, val session: Int?, val script: String, val generation: Int, val rate: Int)
    private data class Level(val rms: Double, val peak: Double)
    private data class Reading(val frame: LiveprogAudioFrame, val input: Level, val output: Level, val split: Boolean)
    private data class Spectrum(val reading: Reading, val input: DoubleArray, val output: DoubleArray, val revision: Long)
    private val LiveprogAudioFrame.timeMs: Long get() = elapsedRealtimeMs
    private val LiveprogAudioFrame.stream: Any? get() = sessionIdentity
    private val LiveprogAudioFrame.session: Int? get() = sessionId
    private val LiveprogAudioFrame.scriptIdentity: String get() = "${pcm!!.scriptHash.toUInt().toString(16)}:${pcm!!.compileRevision}"
    private val LiveprogAudioFrame.generation: Int get() = pcm!!.generation
    private val LiveprogAudioFrame.rate: Int get() = pcm!!.rate
    private val LiveprogAudioFrame.sequence: Int get() = pcm!!.sequence
    private val LiveprogAudioFrame.configurationEpoch: Int get() = pcm!!.configurationEpoch
    private val LiveprogAudioFrame.inputLeft: FloatArray get() = pcm!!.inputLeft
    private val LiveprogAudioFrame.inputRight: FloatArray get() = pcm!!.inputRight
    private val LiveprogAudioFrame.outputLeft: FloatArray get() = pcm!!.outputLeft
    private val LiveprogAudioFrame.outputRight: FloatArray get() = pcm!!.outputRight
    private val density = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val cyan = if (ColorUtils.calculateLuminance(surface) < .5) Color.rgb(104,216,236) else Color.rgb(16,124,146)
    private val amber = if (ColorUtils.calculateLuminance(surface) < .5) Color.rgb(255,198,118) else Color.rgb(169,102,22)
    private val muted = ColorUtils.setAlphaComponent(ink, 155)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        // Match the static pair, including its cap for enlarged system font settings.
        // TextPaint also has a density field (default 1). Use the View's density explicitly.
        textSize = min(9f * resources.displayMetrics.scaledDensity, 10f * this@LiveprogRealtimeChart.density)
        fontFeatureSettings = "tnum"
    }
    private val textBoundsHeight: Float get() = ceil(text.fontMetrics.bottom - text.fontMetrics.top)
    private val textRowHeight: Float get() = ceil(max(text.fontSpacing, textBoundsHeight)) + 2 * density
    private val captionBaseline: Float get() = 2 * density - text.fontMetrics.top
    private val axisBaseline: Float get() = plot.bottom + 3 * density - text.fontMetrics.top
    private val footerBaseline: Float get() = height - 2 * density - text.fontMetrics.bottom
    private val footerTop: Float get() = height - 2 * density - textBoundsHeight
    val minimumReadableHeight: Int get() = ceil(7 * textRowHeight + 16 * density).toInt()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = .7f * density; color = ColorUtils.setAlphaComponent(ink, 38)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val plot = RectF()
    private val main = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var worker: Future<*>? = null
    private var pending: Reading? = null
    private var token = 0L
    private var revision = 0L
    private var mode = LiveprogRealtimeMode.SPECTRUM
    private var active = false
    private var frozen = true
    private var anchor = 0L
    private var source: Source? = null
    private var latest: Reading? = null
    private var spectrum: Spectrum? = null
    private var breakHistory = true
    private var waveRange = 1.0
    private var state = "等待脚本前后真实采样"
    private val history = ArrayDeque<Reading>()
    private val spectrumHistory = ArrayDeque<Spectrum>()
    private val canDraw: Boolean get() = active && isAttachedToWindow && windowVisibility == VISIBLE

    init {
        isLongClickable = true
        setOnLongClickListener { showDetails(); true }
        contentDescription = "DSP 脚本处理 处理前后只读音频图，长按查看完整测量说明"
    }
    private fun redraw() { if (canDraw) invalidate() }
    fun setMode(value: LiveprogRealtimeMode) {
        if (mode == value) return
        mode = value; revision++; spectrum = null; stopWorker()
        if (needsFft()) latest?.let { schedule(it) }
        redraw()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) stopWorker() else if (needsFft()) latest?.let { schedule(it) }
        redraw()
    }
    fun pause(reason: String) { frozen = true; state = reason; breakHistory = true; stopWorker(); redraw() }
    fun showWaiting(reason: String) {
        frozen = false; state = reason; breakHistory = true; redraw()
    }
    fun reset(reason: String) {
        stopWorker(); source = null; latest = null; spectrum = null; history.clear(); spectrumHistory.clear()
        anchor = 0L; revision++; waveRange = 1.0; breakHistory = true; state = reason; redraw()
    }
    private fun same(a: Source?, b: Source?): Boolean = a != null && b != null &&
        a.stream === b.stream && a.session == b.session && a.script == b.script &&
        a.rate == b.rate
    private fun source(frame: LiveprogAudioFrame) = Source(frame.stream, frame.session, frame.scriptIdentity, frame.generation, frame.rate)
    private fun levels(left: FloatArray, right: FloatArray): Level {
        var sum = 0.0; var peak = 0.0
        left.indices.forEach { i ->
            val l = left[i].toDouble(); val r = right[i].toDouble()
            sum += l*l + r*r; peak = max(peak, max(abs(l), abs(r)))
        }
        return Level(if (sum > 0) 10*log10(sum/(2*left.size)) else Double.NEGATIVE_INFINITY,
            if (peak > 0) 20*log10(peak) else Double.NEGATIVE_INFINITY)
    }
    fun showSample(frame: LiveprogAudioFrame) {
        if (frame.discontinuity) reset(frame.status)
        anchor = max(anchor, frame.elapsedRealtimeMs)
        if (frame.pcm == null || frame.sessionIdentity == null || !frame.pcm.ready || !frame.pcm.moduleEnabled || !frame.pcm.executing || !frame.pcm.leaseActive || frame.pcm.invalidSamples) {
            showWaiting(frame.status); return
        }
        if (frame.rate !in 8000..768000 || frame.inputLeft.size !in 64..4096 ||
            listOf(frame.inputRight, frame.outputLeft, frame.outputRight).any { it.size != frame.inputLeft.size } ||
            listOf(frame.inputLeft,frame.inputRight,frame.outputLeft,frame.outputRight).any { it.any { sample -> !sample.isFinite() } }) {
            showWaiting("当前采样窗口无效 · 保留末次真实读数"); return
        }
        val nextSource = source(frame)
        if (!same(source, nextSource)) {
            stopWorker(); latest = null; spectrum = null; history.clear(); spectrumHistory.clear()
            source = nextSource; breakHistory = true; waveRange = 1.0; revision++
        }
        anchor = max(anchor, frame.timeMs); frozen = false
        val previous = latest
        if (previous?.frame?.sequence == frame.sequence && previous.frame.generation == frame.generation &&
            previous.frame.configurationEpoch == frame.configurationEpoch) { redraw(); return }
        val split = breakHistory || previous == null || frame.timeMs - previous.frame.timeMs > 1000 ||
            frame.generation != previous.frame.generation ||
            frame.configurationEpoch != previous.frame.configurationEpoch
        val reading = Reading(frame, levels(frame.inputLeft,frame.inputRight), levels(frame.outputLeft,frame.outputRight), split)
        latest = reading; history.addLast(reading); breakHistory = false
        state = "真实脚本输入 / 输出 · 同一捕获窗口"
        trim()
        if (needsFft()) schedule(reading)
        redraw()
    }
    private fun trim() {
        while (history.isNotEmpty() && (history.size > 256 || history.first().frame.timeMs < anchor-20000)) history.removeFirst()
        while (spectrumHistory.isNotEmpty() && (spectrumHistory.size > 256 || spectrumHistory.first().reading.frame.timeMs < anchor-20000)) spectrumHistory.removeFirst()
    }
    private fun needsFft() = mode == LiveprogRealtimeMode.SPECTRUM || mode == LiveprogRealtimeMode.TIME_FREQUENCY
    private fun stale() = !frozen && latest?.let { anchor-it.frame.timeMs > 1200 } == true
    private fun label(c: Canvas, value: String, x: Float, y: Float, space: Float, color: Int = ink, right: Boolean = false) {
        if (space <= 0 || height < textBoundsHeight) return
        val anchor = x.coerceIn(0f, width.toFloat())
        val available = min(space, if (right) anchor else width - anchor)
        if (available <= 0) return
        text.color = color; text.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        val baseline = y.coerceIn(-text.fontMetrics.top, height - text.fontMetrics.bottom)
        c.drawText(TextUtils.ellipsize(value,text,available,TextUtils.TruncateAt.END).toString(),anchor,baseline,text)
    }
    private fun centeredBaseline(center: Float): Float = center - (text.fontMetrics.top + text.fontMetrics.bottom) / 2
    private fun setPlot(leftLabel: String = "−120", left: Float? = null, right: Float = width - 7 * density) {
        val margin = left ?: max(31 * density, text.measureText(leftLabel) + 8 * density)
        val top = 2 * density + textRowHeight + textBoundsHeight / 2 + 3 * density
        val bottom = footerTop - textBoundsHeight - 6 * density
        plot.set(margin, top, right, bottom)
    }
    private fun fmt(v: Double?, places: Int = 1): String = when {
        v == null || v.isNaN() -> "—"
        v == Double.NEGATIVE_INFINITY -> "−∞"
        !v.isFinite() -> "+∞"
        else -> String.format(Locale.ROOT,"%.${places}f",v)
    }
    private fun alpha(color: Int) = ColorUtils.setAlphaComponent(color, if (frozen || stale()) 105 else 235)
    private fun axes(c: Canvas, low: Double, high: Double, time: Boolean = false) {
        if (plot.width() <= 0 || plot.height() <= 0) return
        val divisions = if (plot.height() < 2 * textBoundsHeight + 4 * density) 1 else 2
        for (j in 0..divisions) {
            val y = plot.top+plot.height()*j/divisions
            c.drawLine(plot.left,y,plot.right,y,grid)
            label(c,fmt(high-(high-low)*j/divisions,if(high-low<=2)1 else 0),plot.left-3*density,centeredBaseline(y),plot.left-5*density,muted,true)
        }
        for (j in 0..4) {
            val x = plot.left+plot.width()*j/4
            c.drawLine(x,plot.top,x,plot.bottom,grid)
            if (time) label(c,if(j==4)"现在" else "${-20+j*5}s",x,axisBaseline,
                max(24*density,plot.width()/4),muted,j==4)
        }
    }
    private fun footer(c: Canvas, value: String) = label(c,value,2*density,footerBaseline,width-4*density,muted)
    private fun waiting(c: Canvas) {
        if (latest == null && plot.height() >= textBoundsHeight) label(c,state,plot.left+4*density,centeredBaseline(plot.centerY()),plot.width()-8*density,muted)
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (width <= 0 || height <= 0) return
        when (mode) { LiveprogRealtimeMode.SPECTRUM -> drawFft(c); LiveprogRealtimeMode.LEVEL -> drawLevel(c); LiveprogRealtimeMode.WAVEFORM -> drawWave(c)
            LiveprogRealtimeMode.STEREO -> drawStereo(c); LiveprogRealtimeMode.TIME_FREQUENCY -> drawSpectrogram(c) }
    }
    private fun drawFft(c: Canvas) {
        val outputX = 3 * density + text.measureText("输入") + 16 * density
        val spectrumX = outputX + text.measureText("脚本输出") + 12 * density
        label(c,"输入",3*density,captionBaseline,outputX-6*density,cyan)
        label(c,"脚本输出",outputX,captionBaseline,spectrumX-outputX-6*density,primary)
        label(c,"真实音乐幅度谱",width-4*density,captionBaseline,width-spectrumX-4*density,muted,true)
        setPlot()
        if (plot.height() < 8*density || plot.width() <= 0) return
        axes(c,-120.0,12.0)
        val s = spectrum; val rate = s?.reading?.frame?.rate ?: source?.rate ?: 48000
        val high = min(20000.0,rate*.5).coerceAtLeast(21.0)
        c.save(); c.clipRect(plot)
        s?.let { spectrum -> listOf(spectrum.input,spectrum.output).forEachIndexed { series, values ->
            path.rewind(); var joined = false
            values.indices.drop(1).forEach { bin ->
                val hz = bin.toDouble()*rate/((values.size-1)*2)
                if (hz in 20.0..high) {
                    val x = plot.left+(ln(hz/20)/ln(high/20)).toFloat()*plot.width()
                    val y = plot.bottom-((values[bin].coerceIn(-120.0,12.0)+120)/132).toFloat()*plot.height()
                    if (joined) path.lineTo(x,y) else { path.moveTo(x,y); joined = true }
                }
            }
            stroke.color = alpha(if(series==0)cyan else primary); c.drawPath(path,stroke)
        } }
        c.restore()
        listOf(20.0,100.0,1000.0,high).distinct().forEach { hz ->
            val x=plot.left+(ln(hz/20)/ln(high/20)).toFloat()*plot.width()
            label(c,if(hz>=1000)"${fmt(hz/1000,0)}kHz" else "${hz.toInt()}Hz",x,axisBaseline,plot.width()/3,muted,hz==high)
        }
        waiting(c)
        footer(c,if(frozen)"$state · 已冻结" else if(stale())"等待新窗口 · 保留末次真实频谱" else "Hann 单边峰幅 · 双声道均方合并 · 非滤波器频响")
    }
    private fun y(value: Double, low: Double, high: Double) = plot.bottom-
        ((value.coerceIn(low,high)-low)/(high-low)).toFloat()*plot.height()
    private fun drawLevel(c: Canvas) {
        val last=latest
        label(c,"输入 RMS ${fmt(last?.input?.rms)} / 峰 ${fmt(last?.input?.peak)}",2*density,captionBaseline,width*.49f-2*density,cyan)
        label(c,"输出 RMS ${fmt(last?.output?.rms)} / 峰 ${fmt(last?.output?.peak)}",width*.51f,captionBaseline,width*.49f-2*density,primary)
        setPlot()
        if(plot.height()<8*density||plot.width()<=0)return
        axes(c,-120.0,12.0,true)
        c.save();c.clipRect(plot)
        repeat(2) { series ->
            path.rewind();var joined=false
            history.forEach { r ->
                val x=plot.right-((anchor-r.frame.timeMs)/20000.0).toFloat()*plot.width()
                val value=if(series==0)r.input.rms else r.output.rms
                if(r.split)joined=false
                if(value.isFinite()||value==Double.NEGATIVE_INFINITY) {
                    if(joined)path.lineTo(x,y(value,-120.0,12.0)) else {path.moveTo(x,y(value,-120.0,12.0));joined=true}
                }else joined=false
            }
            stroke.color=alpha(if(series==0)cyan else primary);c.drawPath(path,stroke)
        }
        c.restore();waiting(c)
        footer(c,if(frozen)"$state · 已冻结" else "最近20秒 · dBFS · 前后电平差不等同内部增益减少量")
    }
    private fun drawWave(c: Canvas) {
        label(c,"输入（青）／输出（紫） · 上左声道／下右声道",2*density,captionBaseline,width-4*density)
        val frame=latest?.frame
        setPlot("−1.0")
        if(plot.height()<24*density||plot.width()<=0)return
        val area=RectF(plot)
        val peak=latest?.let {max(it.input.peak,it.output.peak)}
        if(peak!=null&&peak.isFinite())waveRange=max(waveRange,10.0.pow(ceil(peak/6.0)*6/20))
        val gap=textBoundsHeight+6*density
        val panelHeight=(area.height()-gap)/2
        if(panelHeight<textBoundsHeight)return
        repeat(2) {channel->
            val top=area.top+channel*(panelHeight+gap)
            plot.set(area.left,top,area.right,top+panelHeight)
            axes(c,-waveRange,waveRange)
            c.save();c.clipRect(plot)
            frame?.let {
                val signals=if(channel==0)listOf(it.inputLeft,it.outputLeft)else listOf(it.inputRight,it.outputRight)
                signals.forEachIndexed {series,values->
                    path.rewind()
                    values.forEachIndexed {i,sample->
                        val x=plot.left+i.toFloat()/(values.size-1)*plot.width()
                        val yy=y(sample.toDouble(),-waveRange,waveRange)
                        if(i==0)path.moveTo(x,yy)else path.lineTo(x,yy)
                    }
                    stroke.color=alpha(if(series==0)cyan else primary);c.drawPath(path,stroke)
                }
            }
            c.restore()
            if (plot.height() >= textBoundsHeight + 4 * density)
                label(c,if(channel==0)"左 L"else "右 R",plot.left+2*density,plot.top+2*density-text.fontMetrics.top,text.measureText("左 L")+2*density,muted)
        }
        plot.set(area)
        waiting(c)
        label(c,"0",plot.left,axisBaseline,30*density,muted)
        label(c,frame?.let {"${fmt(it.inputLeft.size*1000.0/it.rate,1)} ms"}?:"时间",plot.right,axisBaseline,max(65*density,plot.width()/2),muted,true)
        footer(c,if(frozen)"$state · 已冻结"else "两声道共用稳定幅度刻度 · 保留脚本实际延迟")
    }
    private fun drawStereo(c: Canvas) {
        label(c,"输出 L/R 相位 · 横向 Side / 纵向 Mid",2*density,captionBaseline,width-4*density)
        setPlot(left=12*density,right=width*.57f)
        if(plot.height()<8*density||plot.width()<=0)return
        val radius=min(plot.width()/2,plot.height()/2);val cx=plot.centerX();val cy=plot.centerY()
        c.drawCircle(cx,cy,radius,grid);c.drawCircle(cx,cy,radius*.5f,grid)
        c.drawLine(cx-radius,cy,cx+radius,cy,grid);c.drawLine(cx,cy-radius,cx,cy+radius,grid)
        var rho:Double?=null;var mid:Double?=null;var side:Double?=null;var ratio:Double?=null
        latest?.frame?.let { frame ->
            val l=frame.outputLeft;val r=frame.outputRight;var mm=0.0;var ss=0.0;var scale=0.0
            val meanL=l.average();val meanR=r.average();var ll=0.0;var rr=0.0;var lr=0.0
            for(i in l.indices) {
                val m=(l[i].toDouble()+r[i])*.5;val s=(l[i].toDouble()-r[i])*.5
                mm+=m*m;ss+=s*s;scale=max(scale,max(abs(m),abs(s)))
                val a=l[i]-meanL;val b=r[i]-meanR;ll+=a*a;rr+=b*b;lr+=a*b
            }
            if(ll>0&&rr>0)rho=(lr/sqrt(ll*rr)).coerceIn(-1.0,1.0)
            mid=if(mm>0)10*log10(mm/l.size)else Double.NEGATIVE_INFINITY
            side=if(ss>0)10*log10(ss/l.size)else Double.NEGATIVE_INFINITY
            if(mm+ss>0)ratio=ss/(mm+ss)*100
            if(scale>0) {
                fill.color=alpha(primary)
                for(i in l.indices step max(1,l.size/256)) {
                    val m=(l[i].toDouble()+r[i])*.5;val s=(l[i].toDouble()-r[i])*.5
                    c.drawCircle(cx+(s/scale).toFloat()*radius,cy-(m/scale).toFloat()*radius,1.2f*density,fill)
                }
            }
        }
        val x=width*.6f;val space=width-x-5*density
        val metricsTop=plot.top
        val metricsBaseline=metricsTop-text.fontMetrics.top
        label(c,"相关度 ρ ${fmt(rho,3)}",x,metricsBaseline,space,primary)
        label(c,"Mid ${fmt(mid)} dBFS",x,metricsBaseline+textRowHeight,space)
        label(c,"Side ${fmt(side)} dBFS",x,metricsBaseline+2*textRowHeight,space)
        if(metricsTop+3*textRowHeight+textBoundsHeight<=footerTop-3*density)
            label(c,"Side能量 ${fmt(ratio)}%",x,metricsBaseline+3*textRowHeight,space)
        waiting(c)
        footer(c,if(frozen)"$state · 已冻结" else "逐窗等比显示真实比例；声场外形不代表物理距离")
    }
    private fun drawSpectrogram(c: Canvas) {
        label(c,"输出时频图 · 颜色表示实际频格幅度",2*density,captionBaseline,width-4*density)
        setPlot("20Hz")
        if(plot.height()<8*density||plot.width()<=0)return
        val rate=source?.rate?:48000;val high=min(20000.0,rate*.5).coerceAtLeast(21.0)
        c.save();c.clipRect(plot)
        spectrumHistory.forEach { s ->
            val age=anchor-s.reading.frame.timeMs
            if(age < 0L || age > 20000L)return@forEach
            val x=plot.right-(age/20000.0).toFloat()*plot.width()
            val column=max(1.5f*density,plot.width()/120)
            val count=48
            for(row in 0 until count) {
                val f0=20*exp(ln(high/20)*row/count);val f1=20*exp(ln(high/20)*(row+1)/count)
                val fftFrames=(s.output.size-1)*2
                val bin0=max(1,floor(f0*fftFrames/rate).toInt())
                val bin1=min(s.output.lastIndex,ceil(f1*fftFrames/rate).toInt())
                var db=Double.NEGATIVE_INFINITY
                if(bin0<=bin1)for(bin in bin0..bin1)db=max(db,s.output[bin])
                val strength=((db.coerceIn(-100.0,0.0)+100)/100).toFloat()
                val base=ColorUtils.blendARGB(surface,primary,strength*.85f)
                fill.color=if(strength>.75f)ColorUtils.blendARGB(base,amber,(strength-.75f)*3f)else base
                val top=plot.bottom-plot.height()*(row+1)/count
                c.drawRect(x-column/2,top,x+column/2,top+plot.height()/count+.5f,fill)
            }
        }
        c.restore()
        for(j in 0..4) {
            val x=plot.left+plot.width()*j/4
            label(c,if(j==4)"现在" else "${-20+j*5}s",x,axisBaseline,plot.width()/4,muted,j==4)
        }
        label(c,"20Hz",plot.left-3*density,centeredBaseline(plot.bottom),plot.left-5*density,muted,true)
        label(c,"${fmt(high/1000,0)}k",plot.left-3*density,centeredBaseline(plot.top),plot.left-5*density,muted,true)
        c.drawRect(plot,grid);waiting(c)
        footer(c,if(frozen)"$state · 已冻结" else "最近20秒 · 缺包处留空，不补造连续信号")
    }
    private fun schedule(reading: Reading) {
        if(!canDraw||frozen||!needsFft())return
        pending=reading
        if(worker==null)startWork()
    }
    private fun startWork() {
        val requested=pending?:return;pending=null
        val currentToken=token;val requestedRevision=revision
        val pool=executor?:Executors.newSingleThreadExecutor { task -> Thread(task,"liveprog-readonly-fft").apply {isDaemon=true} }.also {executor=it}
        worker=pool.submit {
            val result=runCatching {calculate(requested,requestedRevision)}.getOrNull()
            main.post {
                if(currentToken!=token)return@post
                worker=null
                if(result!=null&&requestedRevision==revision&&same(source,source(result.reading.frame))) {
                    spectrum=result
                    val previous=spectrumHistory.lastOrNull()?.reading?.frame
                    val current=result.reading.frame
                    if(previous?.sequence!=current.sequence || previous.generation!=current.generation ||
                        previous.configurationEpoch!=current.configurationEpoch)spectrumHistory.addLast(result)
                    trim()
                }
                if(pending!=null)startWork()
                redraw()
            }
        }
    }
    private fun calculate(reading: Reading, revision: Long): Spectrum {
        val frame=reading.frame;val size=frame.inputLeft.size
        val n=Integer.highestOneBit(size)
        require(n>=64)
        val window=DoubleArray(n) {.5-.5*cos(2*PI*it/n)};val sum=window.sum()
        fun fft(values: FloatArray): DoubleArray {
            val real=DoubleArray(n) {values[size-n+it]*window[it]};val imaginary=DoubleArray(n)
            var j=0
            for(i in 1 until n) {
                var bit=n shr 1
                while(j and bit!=0){j=j xor bit;bit=bit shr 1}
                j=j xor bit
                if(i<j){var t=real[i];real[i]=real[j];real[j]=t;t=imaginary[i];imaginary[i]=imaginary[j];imaginary[j]=t}
            }
            var length=2
            while(length<=n) {
                if(Thread.currentThread().isInterrupted)throw InterruptedException()
                val wr=cos(-2*PI/length);val wi=sin(-2*PI/length)
                for(start in 0 until n step length) {
                    var ur=1.0;var ui=0.0
                    for(k in 0 until length/2) {
                        val a=start+k;val b=a+length/2;val vr=real[b]*ur-imaginary[b]*ui;val vi=real[b]*ui+imaginary[b]*ur
                        real[b]=real[a]-vr;imaginary[b]=imaginary[a]-vi;real[a]+=vr;imaginary[a]+=vi
                        val next=ur*wr-ui*wi;ui=ur*wi+ui*wr;ur=next
                    }
                }
                length=length shl 1
            }
            return DoubleArray(n/2+1) {bin->hypot(real[bin],imaginary[bin])*(if(bin==0||bin==n/2)1 else 2)/sum}
        }
        fun combine(left:FloatArray,right:FloatArray):DoubleArray {
            val a=fft(left);val b=fft(right)
            return DoubleArray(a.size) {bin->val amplitude=sqrt((a[bin]*a[bin]+b[bin]*b[bin])*.5)
                if(amplitude>0)20*log10(amplitude)else Double.NEGATIVE_INFINITY}
        }
        return Spectrum(reading,combine(frame.inputLeft,frame.inputRight),combine(frame.outputLeft,frame.outputRight),revision)
    }
    private fun stopWorker(){token++;worker?.cancel(true);worker=null;pending=null}
    private fun showDetails() {
        val r=latest;val f=r?.frame
        val detail=buildString {
            append("${if(frozen)"采样已冻结" else state}\n\n")
            if(f!=null) {
                append("脚本标识：${f.scriptIdentity}\n会话：${f.session ?: "—"}；处理率：${f.rate} Hz\n")
                append("捕获序列：${f.sequence}；脚本代：${f.generation}；参数代：${f.configurationEpoch}\n")
                append("窗口：${f.inputLeft.size} 采样／${fmt(f.inputLeft.size*1000.0/f.rate,3)} ms\n")
                append("输入 RMS ${fmt(r.input.rms,3)}／峰值 ${fmt(r.input.peak,3)} dBFS\n")
                append("输出 RMS ${fmt(r.output.rms,3)}／峰值 ${fmt(r.output.peak,3)} dBFS\n\n")
            }
            append("采样点分别位于本脚本处理前、处理后。波形使用同一捕获时窗，保留脚本实际造成的延迟。\n")
            append("频谱使用 Hann 窗、单边峰幅和双声道均方合并；它描述当前音乐，不是对脚本施加测试信号后的传递函数。\n")
            append("电平与相关度基于捕获短窗。前后电平差不能自动解释成压缩增益、失真强度或内部跟随频率。\n")
            append("相位图按当前窗等比显示 Mid／Side 比例，不代表声源物理方位、距离或房间尺寸。\n")
            append("短时缺包保留最后真实画面；跨脚本、处理代、采样率、会话时分开历史。")
        }
        GlassAlertDialogBuilder(context).setTitle("脚本前后真实音频").setMessage(detail).setPositiveButton(android.R.string.ok,null).show()
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(needsFft())latest?.let {schedule(it)};redraw()}
    fun close(){active=false;stopWorker();executor?.shutdownNow();executor=null}
    override fun onDetachedFromWindow(){close();super.onDetachedFromWindow()}
}
