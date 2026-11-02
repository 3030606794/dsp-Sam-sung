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
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbController
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbPcmTelemetry
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbParameters
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/** Existing synchronous PCM and original output meters only; no parameter or audition writes. */
internal class RoomReverbFocusRealtimeChart(context: Context) : View(context) {
    // configuration records the block revision of this window; generation/settings identify changes across windows.
    private data class Frame(val time: Long, val source: Any, val session: Int?, val rate: Int,
        val localEpoch: Int, val mode: Int, val toolEpoch: Int, val pcm: Boolean,
        val generation: Int?, val configuration: Int?, val sequence: Int, val split: Boolean,
        val rms: List<Double?>, val peaks: List<Double?>, val wetLeft: Double?, val wetRight: Double?,
        val mid: Double?, val side: Double?, val correlation: Double?, val runtime: List<Int>?,
        val settings: RoomReverbParameters.Settings?)
    private data class Spectrum(val rate: Int, val source: Any, val session: Int?, val generation: Int,
        val configuration: Int, val mode: Int, val localEpoch: Int, val time: Long,
        val levels: List<DoubleArray?>, val settings: RoomReverbParameters.Settings?)
    private data class Work(val data: RoomReverbPcmTelemetry.Snapshot, val frame: Frame)
    private val d = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    private val cyan = if (dark) Color.rgb(104,216,236) else Color.rgb(16,124,146)
    private val amber = if (dark) Color.rgb(255,198,118) else Color.rgb(169,102,22)
    private val colors = listOf(cyan,primary,amber)
    private val muted = ColorUtils.setAlphaComponent(ink,155)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize=min(9f*resources.displayMetrics.scaledDensity,10f*d); fontFeatureSettings="tnum" }
    private val digits = TextPaint(text).apply { typeface=Typeface.MONOSPACE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=ColorUtils.setAlphaComponent(ink,40); style=Paint.Style.STROKE; strokeWidth=d }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=1.5f*d; strokeJoin=Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val plot = RectF()
    private val main = Handler(Looper.getMainLooper())
    private var executor: java.util.concurrent.ExecutorService? = null
    private var worker: Future<*>? = null
    private var pendingWork: Work? = null
    private var workToken=0L
    private var spectrum: Spectrum?=null
    private var latestPcm: Work?=null
    private var model: RoomReverbDashboardModel?=null
    private var focus=RoomReverbChartFocus.ROOM
    private var focusKey=""
    private var active=false
    private var frozen=true
    private var anchor=0L
    private var current: Frame?=null
    private var breakNext=true
    private var state="实时采样已关闭"
    private val history=ArrayDeque<Frame>()
    private var inspected: Frame?=null
    private var held: List<Frame>?=null
    private var heldEnd=0L
    private var downX=0f
    private var downY=0f
    private var pendingTouch=false
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val modes=listOf("正常处理","本模块原声","早反射","尾音","总湿声","快照 A","快照 B","双快照测量")
    private val longPress=Runnable {
        if(pendingTouch && isShown && focus!=RoomReverbChartFocus.TONE && history.isNotEmpty()) {
            pendingTouch=false; held=history.toList(); heldEnd=anchor
            parent?.requestDisallowInterceptTouchEvent(true); select(downX)
        }
    }
    private val canDraw get()=active && isAttachedToWindow && windowVisibility==VISIBLE
    private fun redraw() { if(canDraw) invalidate() }
    fun setConfiguration(value: RoomReverbDashboardModel) {
        val changed=model?.settings!=value.settings
        model=value
        if(changed){breakNext=true;latestPcm=null;spectrum=null;stopWorker()}
    }
    fun setFocus(value: RoomReverbChartFocus,key: String) {
        if(focus==value && focusKey==key)return
        finishInspection(); focus=value; focusKey=key
        if(value==RoomReverbChartFocus.TONE) latestPcm?.let(::schedule) else stopWorker()
        redraw()
    }
    fun setActive(value: Boolean) {
        if(active==value)return
        active=value
        if(!value){finishInspection();stopWorker()} else if(focus==RoomReverbChartFocus.TONE)latestPcm?.let(::schedule)
        redraw()
    }
    fun pause(reason: String) { frozen=true;state=reason;breakNext=true;finishInspection();stopWorker();redraw() }
    private fun db(energy: Double): Double?=if(energy>0 && energy.isFinite())10*log10(energy)else null
    private fun raw(value: Int?): Double?=value?.takeIf{it!=Int.MIN_VALUE}?.div(100.0)
    private fun maximum(a: Double?,b: Double?): Double?=when{a!=null && b!=null->max(a,b);a!=null->a;else->b}
    private fun analyse(a: FloatArray,b: FloatArray): Pair<Double?,Double?> {
        var aa=0.0;var bb=0.0;var peak=0.0
        for(i in a.indices){val x=a[i].toDouble();val y=b[i].toDouble();aa+=x*x;bb+=y*y;peak=max(peak,max(abs(x),abs(y)))}
        return maximum(db(aa/a.size),db(bb/b.size)) to if(peak>0)20*log10(peak)else null
    }
    fun showSample(sample: RoomReverbDashboardRuntimeMonitor.Sample) {
        anchor=max(anchor,sample.elapsedRealtimeMs);frozen=false
        while(history.isNotEmpty() && (history.size>120 || history.first().time<anchor-20000))history.removeFirst()
        val hardInvalid=!sample.output.connected || sample.output.globalIssue!=null || sample.output.activeSessions.size>1
        val pcm=sample.pcm.takeUnless{hardInvalid}
        val snapshot=sample.reading?.snapshot
        val actualMode=RoomReverbController.audition
        val actualEpoch=RoomReverbController.epoch
        val modeMatches=snapshot?.mode==actualMode && (actualMode==0 || snapshot.epoch==actualEpoch)
        val runtime=snapshot?.data?.takeIf{!hardInvalid && sample.reading?.active==true && modeMatches && snapshot.ready}
        val validPcm=pcm?.takeIf{it.mode==actualMode && (actualMode==0 || it.comparisonEpoch==actualEpoch) &&
            it.moduleEnabled && it.ready && it.inputValid && it.frames>0 && sample.output.processing==OutputRuntimeStatus.Processing.ACTIVE}
        val identity=sample.streamIdentity
        if(identity==null || (validPcm==null && runtime==null)) {
            breakNext=true
            if(hardInvalid || current?.let{anchor-it.time>1000 || it.localEpoch!=actualEpoch || it.mode!=actualMode || it.settings!=model?.settings}!=false) {
                current=null;latestPcm=null;spectrum=null
                state=sample.pcmUnavailableReason ?: sample.runtimeUnavailableReason ?: sample.unavailableReason ?: "等待活动音频"
            }
            redraw();return
        }
        val previous=current
        if(validPcm==null && previous?.pcm==true && previous.source===identity && previous.session==sample.streamSessionId &&
            previous.rate==runtime?.get(2) && previous.localEpoch==actualEpoch && previous.mode==actualMode &&
            previous.settings==model?.settings && anchor-previous.time<=1000) {
            // Retain the last real PCM window briefly, without adding a fabricated history point.
            current=previous.copy(runtime=runtime,toolEpoch=runtime?.get(11) ?: previous.toolEpoch)
            state="等待新采样窗口 · 保留最近一次真实读数"
            redraw();return
        }
        val sequence=validPcm?.sequence ?: runtime!![1]
        val rate=validPcm?.rate ?: runtime!![2]
        val fromPcm=validPcm!=null
        if(previous!=null && previous.source===identity && previous.session==sample.streamSessionId &&
            previous.rate==rate && previous.pcm==fromPcm && previous.generation==validPcm?.generation && previous.settings==model?.settings &&
            previous.sequence==sequence && previous.localEpoch==actualEpoch && previous.mode==actualMode) { redraw();return }
        var rms: List<Double?>;var peaks: List<Double?>
        var wetL: Double?=null;var wetR: Double?=null;var mid: Double?=null;var side: Double?=null;var correlation: Double?=null
        if(validPcm!=null) {
            val input=analyse(validPcm.inputLeft,validPcm.inputRight)
            val wet=if(validPcm.wetValid)analyse(validPcm.wetLeft,validPcm.wetRight)else null
            val output=analyse(validPcm.outputLeft,validPcm.outputRight)
            rms=listOf(input.first,wet?.first,output.first);peaks=listOf(input.second,wet?.second,output.second)
            if(validPcm.wetValid){
                var ll=0.0;var rr=0.0;var lr=0.0;var mm=0.0;var ss=0.0
                for(i in 0 until validPcm.frames){val l=validPcm.wetLeft[i].toDouble();val r=validPcm.wetRight[i].toDouble()
                    ll+=l*l;rr+=r*r;lr+=l*r;mm+=(l+r)*(l+r)*.25;ss+=(l-r)*(l-r)*.25}
                wetL=db(ll/validPcm.frames);wetR=db(rr/validPcm.frames);mid=db(mm/validPcm.frames);side=db(ss/validPcm.frames)
                if(ll>0 && rr>0)correlation=(lr/sqrt(ll*rr)).coerceIn(-1.0,1.0)
            }
        }else{
            val meters=runtime?.get(12)==1
            rms=listOf(null,null,if(meters)maximum(raw(runtime?.get(18)),raw(runtime?.get(19)))else null)
            peaks=listOf(null,null,if(meters)maximum(raw(runtime?.get(16)),raw(runtime?.get(17)))else null)
        }
        val split=breakNext || previous==null || previous.source!==identity || previous.session!=sample.streamSessionId ||
            previous.rate!=rate || previous.localEpoch!=actualEpoch || previous.mode!=actualMode || previous.pcm!=fromPcm ||
            previous.generation!=validPcm?.generation || previous.settings!=model?.settings || anchor-previous.time>1000
        val frame=Frame(anchor,identity,sample.streamSessionId,rate,actualEpoch,actualMode,
            validPcm?.comparisonEpoch ?: runtime!![11],fromPcm,validPcm?.generation,validPcm?.configurationEpoch,
            sequence,split,rms,peaks,wetL,wetR,mid,side,correlation,runtime,model?.settings)
        if(split && previous?.let{it.source!==identity || it.session!=frame.session || it.rate!=rate || it.localEpoch!=actualEpoch || it.mode!=actualMode ||
                it.pcm!=fromPcm || it.generation!=frame.generation || it.settings!=frame.settings}==true){spectrum=null;stopWorker()}
        current=frame;history.addLast(frame);breakNext=false
        state=when{validPcm==null->sample.levelUnavailableReason ?: "真实输出电平 · 分离湿声等待采样"
            !validPcm.wetValid->"原房型路径 · 湿声未分离，不补画零线"
            validPcm.fading->"真实音频过渡窗口"
            else->"湿声取保存配置 · 输出取当前试听"}
        if(validPcm!=null){latestPcm=Work(validPcm,frame);if(focus==RoomReverbChartFocus.TONE)schedule(latestPcm!!)}else latestPcm=null
        redraw()
    }
    private fun label(c: Canvas,value: String,x: Float,y: Float,space: Float,color: Int=ink,right: Boolean=false,numeric: Boolean=false) {
        if(space<=0)return
        val p=if(numeric)digits else text;p.color=color;p.textAlign=if(right)Paint.Align.RIGHT else Paint.Align.LEFT
        val start=if(right)x-space else x
        c.save();c.clipRect(start,y+p.ascent()-d,start+space,y+p.descent()+d)
        c.drawText(if(numeric)value else TextUtils.ellipsize(value,p,space,TextUtils.TruncateAt.END).toString(),x,y,p);c.restore()
    }
    private fun fmt(v: Double?,digits: Int=1): String=v?.takeIf{it.isFinite()}?.let{String.format(Locale.ROOT,"%.${digits}f",it)} ?: "—"
    override fun onDraw(c: Canvas) {
        super.onDraw(c);if(width<80*d || height<80*d)return
        val frame=inspected ?: current
        label(c,if(frame?.pcm==true)"同窗原声 / 总湿声 / 试听输出 · 较大通道 RMS dBFS"else"真实输出 RMS / 峰值 · dBFS",2*d,10*d,width-4*d)
        val step=(width-35*d)/3
        repeat(3){i->val x=30*d+i*step;val value=frame?.rms?.get(i)
            label(c,listOf("入","湿","出")[i],x,24*d,14*d,colors[i])
            label(c,fmt(value).padStart(7),x+step-7*d,24*d,step-17*d,colors[i],true,true)
            val bounds=RectF(x,28*d,x+step-9*d,34*d)
            fill.color=ColorUtils.setAlphaComponent(ink,18);c.drawRoundRect(bounds,2*d,2*d,fill)
            if(value!=null){fill.color=ColorUtils.setAlphaComponent(colors[i],if(frozen)90 else 190)
                c.drawRoundRect(RectF(bounds.left,bounds.top,bounds.left+bounds.width()*((value.coerceIn(-96.0,6.0)+96)/102).toFloat(),bounds.bottom),2*d,2*d,fill)}
        }
        val match=focus==RoomReverbChartFocus.MATCH
        if(match)drawMatch(c,frame)else label(c,"试听：${frame?.mode?.let{modes.getOrElse(it){"等待确认"}} ?: "等待确认"}"+
            if(focus==RoomReverbChartFocus.OUTPUT)" · 输出峰值 ${fmt(frame?.peaks?.get(2))} dBFS"else "",2*d,46*d,width-4*d,muted)
        plot.set(30*d,(if(match)70 else 55)*d,width-7*d,height-26*d)
        if(plot.height()<9*d)return
        if(focus==RoomReverbChartFocus.WIDTH)drawWidth(c,frame)
        else if(focus==RoomReverbChartFocus.TONE)drawSpectrum(c)
        else drawHistory(c,plot,-96.0,0.0,false)
        label(c,if(frozen)"$state · 已冻结"else state,2*d,height-3*d,width-4*d,muted)
    }
    private fun drawMatch(c: Canvas,frame: Frame?) {
        // Normal audio may have tool epoch zero; measured A/B data must still match the current tool generation.
        val v=frame?.runtime?.takeIf{frame.localEpoch==RoomReverbController.epoch && it[11]==frame.localEpoch}
        val phase=when(v?.get(7)){1->"预热参考 ${v[24]} ms · 随后测量 ${v[25]} ms";2->"测量 ${v[8]}/${v[25]} ms";3->"测量完成";4->"信号不足";0->"尚未测量";else->"等待当前 A/B 读数"}
        label(c,phase,2*d,46*d,width-4*d,primary)
        val gain=v?.takeIf{it[7]==3 && RoomReverbController.matchingReady()}
        label(c,"固定衰减 A ${fmt(raw(gain?.get(9)),2)} / B ${fmt(raw(gain?.get(10)),2)} dB",2*d,59*d,width-4*d,muted)
    }
    private fun axes(c: Canvas,area: RectF,low: Double,high: Double) {
        c.drawRect(area,grid)
        for(i in 0..4){val x=area.left+area.width()*i/4;c.drawLine(x,area.top,x,area.bottom,grid)}
        for(i in 0..2){val y=area.top+area.height()*i/2;c.drawLine(area.left,y,area.right,y,grid)
            label(c,fmt(high-(high-low)*i/2,if(high==1.0)1 else 0),area.left-3*d,y+3*d,26*d,muted,true)}
    }
    private fun drawHistory(c: Canvas,area: RectF,low: Double,high: Double,correlation: Boolean) {
        axes(c,area,low,high)
        val values=held ?: history.toList();val end=if(held!=null)heldEnd else anchor
        repeat(if(correlation)1 else 3){series->path.rewind();var joined=false
            values.forEach{f->val value=if(correlation)f.correlation else f.rms[series]
                val x=area.right-(end-f.time)/20000f*area.width()
                if(value==null || x<area.left){joined=false}else{val y=area.bottom-((value.coerceIn(low,high)-low)/(high-low)).toFloat()*area.height()
                    if(!joined || f.split)path.moveTo(x,y)else path.lineTo(x,y);joined=true}}
            line.color=if(correlation)primary else colors[series];c.save();c.clipRect(area);c.drawPath(path,line);c.restore()}
        label(c,"−20s",area.left,area.bottom+12*d,area.width()/2,muted)
        label(c,"现在",area.right,area.bottom+12*d,area.width()/2,muted,true)
        if(current==null)label(c,state,area.left+4*d,area.centerY()+3*d,area.width()-8*d,muted)
    }
    private fun drawWidth(c: Canvas,frame: Frame?) {
        val radar=RectF(plot.left,plot.top,plot.left+plot.width()*.34f,plot.bottom)
        val cx=radar.centerX();val cy=radar.bottom-8*d;val radius=min(radar.width()*.47f,radar.height()-12*d).coerceAtLeast(0f)
        for(i in 1..3){val r=radius*i/3;c.drawArc(RectF(cx-r,cy-r,cx+r,cy+r),180f,180f,false,grid)}
        c.drawLine(cx-radius,cy,cx+radius,cy,grid)
        if(frame?.mid!=null || frame?.side!=null){
            val m=frame.mid?.let{10.0.pow(it/10)} ?: 0.0;val s=frame.side?.let{10.0.pow(it/10)} ?: 0.0
            val half=atan2(sqrt(s),sqrt(m)).coerceIn(0.0,PI/2)*180/PI
            fill.color=ColorUtils.setAlphaComponent(primary,55);c.drawArc(RectF(cx-radius,cy-radius,cx+radius,cy+radius),(270-half).toFloat(),(half*2).toFloat(),true,fill)
        }
        label(c,"湿声 M/S",radar.left,radar.top+9*d,radar.width(),primary)
        label(c,"ρ ${fmt(frame?.correlation,2)}",radar.left,radar.bottom+12*d,radar.width(),primary)
        val historyArea=RectF(radar.right+30*d,plot.top,plot.right,plot.bottom)
        if(historyArea.width()>30*d)drawHistory(c,historyArea,-1.0,1.0,true)
    }
    private fun schedule(value: Work) {
        if(!canDraw || frozen || focus!=RoomReverbChartFocus.TONE)return
        pendingWork=value
        if(worker!=null)return
        startWork()
    }
    private fun startWork() {
        val task=pendingWork ?: return;pendingWork=null
        val token=workToken
        val pool=executor ?: Executors.newSingleThreadExecutor{r->Thread(r,"room-reverb-focus-fft").apply{isDaemon=true}}.also{executor=it}
        worker=pool.submit{
            val result=runCatching{calculate(task)}.getOrNull()
            main.post{
                if(token!=workToken)return@post
                worker=null
                if(result!=null && current?.let{it.source===result.source && it.session==result.session && it.rate==result.rate && it.localEpoch==result.localEpoch && it.mode==result.mode && it.generation==result.generation && it.settings==result.settings && model?.settings==result.settings}==true)spectrum=result
                if(pendingWork!=null)startWork()
                redraw()
            }
        }
    }
    private fun calculate(work: Work): Spectrum {
        val p=work.data;val n=p.frames
        val window=DoubleArray(n){.5-.5*cos(2*PI*it/n)};val sum=window.sum()
        fun transform(samples: FloatArray): DoubleArray {
            val real=DoubleArray(n){samples[it]*window[it]};val imaginary=DoubleArray(n)
            var j=0
            for(i in 1 until n){var bit=n shr 1;while(j and bit!=0){j=j xor bit;bit=bit shr 1};j=j xor bit
                if(i<j){val a=real[i];real[i]=real[j];real[j]=a}}
            var length=2
            while(length<=n){if(Thread.currentThread().isInterrupted)throw InterruptedException()
                val angle=-2*PI/length;val wr=cos(angle);val wi=sin(angle)
                for(start in 0 until n step length){var ur=1.0;var ui=0.0
                    for(k in 0 until length/2){val a=start+k;val b=a+length/2;val vr=real[b]*ur-imaginary[b]*ui;val vi=real[b]*ui+imaginary[b]*ur
                        real[b]=real[a]-vr;imaginary[b]=imaginary[a]-vi;real[a]+=vr;imaginary[a]+=vi
                        val next=ur*wr-ui*wi;ui=ur*wi+ui*wr;ur=next}}
                length=length shl 1}
            return DoubleArray(n/2+1){bin->hypot(real[bin],imaginary[bin])*(if(bin==0 || bin==n/2)1.0 else 2.0)/sum}
        }
        val channels=listOf(p.inputLeft,p.inputRight,p.wetLeft,p.wetRight,p.outputLeft,p.outputRight)
        val levels=List(3){series->if(series==1 && !p.wetValid)null else{
            val l=transform(channels[series*2]);val r=transform(channels[series*2+1])
            DoubleArray(l.size){db((l[it]*l[it]+r[it]*r[it])*.5) ?: Double.NaN}}}
        val f=work.frame
        return Spectrum(p.rate,f.source,f.session,p.generation,p.configurationEpoch,p.mode,f.localEpoch,f.time,levels,f.settings)
    }
    private fun drawSpectrum(c: Canvas) {
        axes(c,plot,-120.0,12.0)
        val data=spectrum?.takeIf{s->(frozen || anchor-s.time<=1500) && current?.let{
            it.pcm && it.source===s.source && it.session==s.session && it.rate==s.rate && it.localEpoch==s.localEpoch &&
                it.mode==s.mode && it.generation==s.generation && it.settings==s.settings && model?.settings==s.settings}==true}
        data?.levels?.forEachIndexed{index,values->
            if(values!=null){path.rewind();var started=false
                for(bin in values.indices){val hz=bin.toDouble()*data.rate/((values.size-1)*2)
                    if(hz<20 || hz>20000)continue
                    val db=values[bin]
                    if(!db.isFinite()){started=false;continue}
                    val x=plot.left+(ln(hz/20)/ln(1000.0)).toFloat()*plot.width()
                    val y=plot.bottom-((db.coerceIn(-120.0,12.0)+120)/132).toFloat()*plot.height()
                    if(started)path.lineTo(x,y)else path.moveTo(x,y);started=true}
                line.color=colors[index];c.save();c.clipRect(plot);c.drawPath(path,line);c.restore()}}
        label(c,"20 Hz",plot.left,plot.bottom+12*d,plot.width()/2,muted)
        label(c,"20 kHz",plot.right,plot.bottom+12*d,plot.width()/2,muted,true)
        if(data==null)label(c,"等待真实 PCM 频谱",plot.left+4*d,plot.centerY()+3*d,plot.width()-8*d,muted)
    }
    private fun stopWorker(){workToken++;pendingWork=null;worker?.cancel(true);worker=null;executor?.shutdownNow();executor=null}
    private fun select(x: Float){val list=held ?: return;val time=heldEnd-((plot.right-x)/plot.width()).coerceIn(0f,1f)*20000
        inspected=list.minByOrNull{abs(it.time-time)};redraw()}
    private fun finishInspection(){pendingTouch=false;main.removeCallbacks(longPress);held=null;inspected=null;parent?.requestDisallowInterceptTouchEvent(false)}
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{if(!plot.contains(event.x,event.y))return false;downX=event.x;downY=event.y;pendingTouch=true;main.postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong());return true}
            MotionEvent.ACTION_MOVE->{if(held!=null)select(event.x)else if(abs(event.x-downX)>slop || abs(event.y-downY)>slop){pendingTouch=false;main.removeCallbacks(longPress)};return true}
            MotionEvent.ACTION_UP->{finishInspection();performClick();redraw();return true}
            MotionEvent.ACTION_CANCEL->{finishInspection();redraw();return true}}
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean {super.performClick();return true}
    override fun onDetachedFromWindow(){finishInspection();stopWorker();super.onDetachedFromWindow()}
}
