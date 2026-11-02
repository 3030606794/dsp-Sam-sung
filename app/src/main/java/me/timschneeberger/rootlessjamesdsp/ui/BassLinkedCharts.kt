package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.fusion.BassEnergyMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.BassLinkedModel
import java.util.Locale
import kotlin.math.*

/** Read-only, event-driven canvases. No capture subscription, timer or preference writer lives here. */
abstract class BassLinkedCanvas(context: Context) : View(context) {
    protected val dp=resources.displayMetrics.density
    protected val foreground=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
    protected val surface=MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface)
    protected val primary=MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary)
    protected val orange=if(ColorUtils.calculateLuminance(surface)<.5)Color.rgb(255,183,110) else Color.rgb(168,82,23)
    protected val cyan=if(ColorUtils.calculateLuminance(surface)<.5)Color.rgb(115,216,185) else Color.rgb(19,125,95)
    protected val muted=ColorUtils.setAlphaComponent(foreground,100)
    protected val line=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=1.5f*dp;strokeJoin=Paint.Join.ROUND}
    protected val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=ColorUtils.setAlphaComponent(foreground,40);strokeWidth=dp;style=Paint.Style.STROKE}
    protected val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    protected val text=Paint(Paint.ANTI_ALIAS_FLAG)
    protected val path=Path()
    protected val plot=RectF()
    protected val dashed=DashPathEffect(floatArrayOf(4f*dp,3f*dp),0f)
    protected var active=true
        private set
    protected val compact get()=height<70f*dp
    protected val canDraw get()=active && visibility==VISIBLE && windowVisibility==VISIBLE
    fun setActive(value:Boolean) {if(active==value)return;active=value;if(value)invalidate()}
    protected fun redraw() {if(canDraw)invalidate()}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        text.textSize=if(compact) min(7f*resources.displayMetrics.scaledDensity,8f*dp) else min(9f*resources.displayMetrics.scaledDensity,10.5f*dp)
    }
    protected fun label(canvas:Canvas,value:String,x:Float,y:Float,available:Float,color:Int=foreground,align:Paint.Align=Paint.Align.LEFT) {
        if(available<=0)return
        val old=text.textSize;text.color=color;text.textAlign=align
        val length=text.measureText(value);if(length>available)text.textSize*=available/length
        canvas.drawText(value,x,y,text);text.textSize=old
    }
    protected fun fmt(v:Double,digits:Int=1)=if(v.isFinite())String.format(Locale.ROOT,"%.${digits}f",v)else "—"
    protected fun dot(canvas:Canvas,x:Float,y:Float,color:Int,radius:Float=2.5f) {fill.color=color;canvas.drawCircle(x,y,radius*dp,fill)}
    protected fun tooltip(canvas:Canvas,rows:List<String>) {
        val pad=4f*dp;val gap=text.textSize*1.3f
        val w=min(width-2f*dp,(rows.maxOfOrNull{text.measureText(it)}?:0f)+2*pad)
        val h=rows.size*gap+pad*2
        val left=plot.left.coerceIn(dp,max(dp,width-w-dp));val top=(plot.top+dp).coerceAtMost(max(dp,height-h-dp))
        fill.color=ColorUtils.setAlphaComponent(surface,245);canvas.drawRoundRect(RectF(left,top,left+w,top+h),3f*dp,3f*dp,fill)
        rows.forEachIndexed {i,row->label(canvas,row,left+pad,top+pad+(i+.8f)*gap,w-pad*2)}
    }
    protected fun frequencyX(hz:Double)=plot.left+(ln(hz.coerceIn(10.0,500.0)/10)/ln(50.0)).toFloat()*plot.width()
    protected fun frequencyAt(x:Float)=10*50.0.pow(((x-plot.left)/plot.width()).coerceIn(0f,1f).toDouble())
    protected fun responseY(db:Double)=plot.bottom-((db+36)/72).toFloat()*plot.height()
    protected fun responseGrid(canvas:Canvas) {
        for(db in listOf(-36.0,0.0,36.0)) {
            val y=responseY(db);canvas.drawLine(plot.left,y,plot.right,y,grid)
            label(canvas,fmt(db,0),plot.left-3f*dp,y+3f*dp,plot.left-5f*dp,foreground,Paint.Align.RIGHT)
        }
        for(hz in listOf(10.0,20.0,50.0,100.0,200.0,500.0)) {
            val x=frequencyX(hz);canvas.drawLine(x,plot.top,x,plot.bottom,grid)
            label(canvas,if(hz==500.0)"500Hz" else fmt(hz,0),x,height-2f*dp,plot.width()*.17f,
                foreground,if(hz==10.0)Paint.Align.LEFT else if(hz==500.0)Paint.Align.RIGHT else Paint.Align.CENTER)
        }
    }
    protected fun curve(canvas:Canvas,frequencies:DoubleArray,levels:DoubleArray,color:Int,width:Float=1.4f,dash:Boolean=false) {
        path.rewind();levels.forEachIndexed {i,db->
            val x=frequencyX(frequencies[i]);val y=responseY(db)
            if(i==0)path.moveTo(x,y)else path.lineTo(x,y)
        }
        line.color=color;line.strokeWidth=width*dp;line.pathEffect=if(dash)dashed else null
        canvas.drawPath(path,line);line.pathEffect=null
    }
    protected fun shadeBetween(canvas:Canvas,frequencies:DoubleArray,levels:DoubleArray,color:Int,zero:Double=0.0) {
        path.rewind();path.moveTo(plot.left,responseY(zero))
        levels.forEachIndexed {i,db->path.lineTo(frequencyX(frequencies[i]),responseY(db))}
        path.lineTo(plot.right,responseY(zero));path.close();fill.color=color;canvas.drawPath(path,fill)
    }
    override fun performClick():Boolean {super.performClick();return true}
}

abstract class BassLinkedStaticChart(context:Context):BassLinkedCanvas(context) {
    protected var configuration:BassLinkedModel?=null
        private set
    protected var focus=BassLinkedModel.Focus.MODE
        private set
    protected var focusKey="v_bass_mode"
        private set
    fun setConfiguration(values:Map<String,*>,rate:Int?,focusKey:String) {
        val next=BassLinkedModel.from(values,rate);val nextFocus=BassLinkedModel.focus(focusKey)
        if(configuration===next && this.focusKey==focusKey)return
        configuration=next;focus=nextFocus;this.focusKey=focusKey;redraw()
    }
    protected fun layoutPlot() {
        plot.set((if(compact)22f else 30f)*dp,(if(compact)12f else 30f)*dp,width-5f*dp,height-(if(compact)9f else 16f)*dp)
    }
    protected fun previewNote(value:BassLinkedModel)=
        "${value.rateLabel} · ${if(value.approximation)"近似：未含Pure Bass+多相核" else "稳态模型，非实测"}${if(!value.enabled)" · 音效关闭仍预览" else ""}"
}

class BassLinkedOverviewView(context:Context):BassLinkedStaticChart(context) {
    private var cursor:Float?=null
    private val gesture=BassLinkedCursor(this,{x,y->plot.contains(x,y)}, {cursor=it.coerceIn(plot.left,plot.right);redraw()}, {cursor=null;redraw()})
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);val value=configuration?:return;layoutPlot()
        if(plot.width()<=0 || plot.height()<=0)return
        label(canvas,"整体响应 · ${value.modeName} · 系数${fmt(value.factor,2)}（非dB）",dp,(if(compact)8f else 10f)*dp,width-2*dp)
        if(!compact)label(canvas,previewNote(value),dp,22f*dp,width-2*dp)
        responseGrid(canvas)
        canvas.save();canvas.clipRect(plot)
        val total=value.totalDb
        shadeBetween(canvas,value.frequencies,total,ColorUtils.setAlphaComponent(primary,20))
        if(value.mode==2) {
            curve(canvas,value.frequencies,value.frequencies.map{value.subLowpass.at(it).db()}.toDoubleArray(),muted,1f,true)
        } else curve(canvas,value.frequencies,value.addedDb,ColorUtils.setAlphaComponent(primary,130),1f,true)
        curve(canvas,value.frequencies,value.deepResponseDb,if(value.mode==2)orange else muted,
            if(focus==BassLinkedModel.Focus.DEEP)2f else 1f,value.mode!=2)
        curve(canvas,value.frequencies,value.punchResponseDb,if(value.mode==2)cyan else muted,
            if(focus==BassLinkedModel.Focus.PUNCH)2f else 1f,value.mode!=2)
        curve(canvas,value.frequencies,total,primary,2f,!value.enabled || value.approximation)
        val center=value.focusFrequency(focus);val xx=frequencyX(center)
        canvas.drawLine(xx,plot.top,xx,plot.bottom,grid);dot(canvas,xx,responseY(value.total(center).db()),primary)
        cursor?.let {canvas.drawLine(it,plot.top,it,plot.bottom,grid)}
        canvas.restore()
        if(cursor==null && !compact)label(canvas,"紫合成 · ${if(value.mode==2)"橙深潜 / 绿冲击 / 灰380Hz低通" else "虚紫低通增强支路 / 灰双峰未启用"}",plot.left+2*dp,plot.bottom-3*dp,plot.width()-4*dp)
        cursor?.let {x->
            val hz=frequencyAt(x);val sum=value.total(hz).db()
            tooltip(canvas,listOf("${fmt(hz,2)}Hz · 合成 ${fmt(sum,3)}dB",
                "深潜 ${fmt(value.deep.at(hz).db(),3)} / 冲击 ${fmt(value.punch.at(hz).db(),3)}dB",
                if(value.mode==2)"双峰先串联；含380Hz低通差分与0.5直达混合" else "低通支路 ${fmt((value.lowpass.at(hz)*value.factor).db(),3)}dB · 非整机增益",
                previewNote(value)))
        }
    }
    override fun onTouchEvent(event:MotionEvent)=gesture.onTouchEvent(event)
    override fun onDetachedFromWindow(){gesture.clear();super.onDetachedFromWindow()}
    override fun onCancelPendingInputEvents(){super.onCancelPendingInputEvents();gesture.clear()}
}

/** Beginner-friendly parameter prediction: three tonal tendencies and a moving concentration cue. */
class BassLinkedDetailView(context:Context):BassLinkedStaticChart(context) {
    private var cursor:Float?=null
    private val gesture=BassLinkedCursor(this,{x,y->plot.contains(x,y)},{cursor=it.coerceIn(plot.left,plot.right);redraw()},{cursor=null;redraw()})
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);val value=configuration?:return;layoutPlot()
        if(plot.width()<=0 || plot.height()<=0)return
        val relevant=value.detailActive(focus)
        val accent=if(!relevant)muted else when(focus){BassLinkedModel.Focus.DEEP->orange;BassLinkedModel.Focus.PUNCH->cyan;else->primary}
        val widthFocus=focusKey=="v_bass_deep_bw_hundredths" || focusKey=="v_bass_punch_bw_hundredths" || focusKey=="v_bass_q_hundredths"
        val strongFocus=focusKey=="v_bass_amount_hundredths" || focusKey=="v_bass_deep_gain_tenths" || focusKey=="v_bass_punch_gain_tenths"
        val focusLabel=if(widthFocus)"集中程度" else if(strongFocus)"低音强弱" else "低音侧重点"
        label(canvas,"听感示意 · 参数预测 · $focusLabel${if(!relevant)"（未启用参数）" else ""}",dp,(if(compact)8f else 10f)*dp,width-2*dp,accent)
        if(!compact)label(canvas,value.hearingSummary()+" · 对本模式默认 / 非实测",dp,22f*dp,width-2*dp)
        val center=value.focusFrequency(focus)
        val bounds=value.bounds(focus) ?: run {
            // A Q-defined visual concentration cue, not a claim that a low-pass is a band-pass.
            val q=value.q;val root=sqrt(1+1/(4*q*q))
            center*(root-1/(2*q)) to center*(root+1/(2*q))
        }
        val top=plot.top;val bandHeight=max(4f*dp,plot.height()*.21f)
        val bandBottom=min(plot.bottom,top+bandHeight)
        val barsTop=min(plot.bottom-2f*dp,bandBottom+if(compact)2f*dp else 8f*dp)
        val zero=(barsTop+plot.bottom)*.5f
        fun barY(db:Double)=zero-(db.coerceIn(-18.0,18.0)/36).toFloat()*(plot.bottom-barsTop)
        canvas.save();canvas.clipRect(plot)
        value.toneRanges.forEachIndexed {i,range->
            val left=frequencyX(range.first);val right=frequencyX(range.second)
            fill.color=ColorUtils.setAlphaComponent(if(i==0)orange else if(i==1)primary else cyan,14)
            canvas.drawRect(left,top,right,plot.bottom,fill)
            canvas.drawLine(left,top,left,plot.bottom,grid)
        }
        fill.color=ColorUtils.setAlphaComponent(accent,65)
        canvas.drawRoundRect(RectF(frequencyX(bounds.first),top+dp,frequencyX(bounds.second),bandBottom),2f*dp,2f*dp,fill)
        line.color=accent;line.strokeWidth=1.4f*dp;line.pathEffect=if(relevant)null else dashed
        val cx=frequencyX(center);canvas.drawLine(cx,top,cx,bandBottom,line);line.pathEffect=null
        dot(canvas,cx,(top+bandBottom)*.5f,accent,if(compact)2f else 3f)
        canvas.drawLine(plot.left,zero,plot.right,zero,grid)
        for(db in listOf(-18.0,18.0))canvas.drawLine(plot.left,barY(db),plot.right,barY(db),grid)
        value.toneRanges.forEachIndexed {i,range->
            val left=frequencyX(range.first);val right=frequencyX(range.second);val pad=(right-left)*.23f
            val level=value.toneDeltaDb[i];val yy=barY(level)
            val color=if(i==0)orange else if(i==1)primary else cyan
            fill.color=ColorUtils.setAlphaComponent(color,165)
            canvas.drawRect(left+pad,min(zero,yy),right-pad,max(zero,yy),fill)
            // A minimum visible marker shows the zero default without inventing a nonzero bar.
            if(abs(level)<.001){line.color=color;line.strokeWidth=1.5f*dp;canvas.drawLine(left+pad,zero,right-pad,zero,line)}
            if(!compact)label(canvas,(if(level>0)"+" else "")+fmt(level,1), (left+right)*.5f,
                if(level>=0)max(barsTop+text.textSize,yy-2*dp)else min(plot.bottom-2*dp,yy+text.textSize),
                right-left-4*dp,color,Paint.Align.CENTER)
        }
        cursor?.let{canvas.drawLine(it,top,it,plot.bottom,grid)}
        canvas.restore()
        value.toneRanges.forEachIndexed {i,range->
            val left=frequencyX(range.first);val right=frequencyX(range.second)
            label(canvas,value.toneNames[i]+if(compact)"" else " ${range.first.toInt()}–${range.second.toInt()}Hz",(left+right)*.5f,height-2*dp,
                right-left-2*dp,foreground,Paint.Align.CENTER)
        }
        if(!compact && cursor==null){
            val cue=when {
                !relevant->"当前模式不使用此参数；灰色作用区只读预览"
                focus==BassLinkedModel.Focus.DEEP || focus==BassLinkedModel.Focus.PUNCH->"侧重${fmt(center,0)}Hz · BW${fmt(if(focus==BassLinkedModel.Focus.DEEP)value.deepWidth else value.punchWidth,2)}oct · ${if((if(focus==BassLinkedModel.Focus.DEEP)value.deepWidth else value.punchWidth)<.6)"更集中" else "更宽广"}"
                focus==BassLinkedModel.Focus.LOWPASS->"转折${fmt(center,0)}Hz · Q${fmt(value.q,2)} · Q越高转折附近更集中"
                else->"上：侧重/集中范围示意 · 下：三带平均增益差±18dB"
            }
            label(canvas,cue,plot.left+2*dp,bandBottom-2*dp,plot.width()-4*dp,accent)
        }
        cursor?.let{x->
            val hz=frequencyAt(x);val i=if(hz<60)0 else if(hz<120)1 else 2
            tooltip(canvas,listOf("${value.toneNames[i]}倾向 ${fmt(value.toneDeltaDb[i],3)}dB（相对本模式默认）",
                "游标${fmt(hz,2)}Hz · 该处模型增益差 ${fmt(value.gainDifference(hz),3)}dB",
                "侧重点${fmt(center,1)}Hz · 色带是参数集中范围示意",
                "非实测声能 / 非听觉保证${if(!relevant)" · 此参数在当前模式未启用" else ""}"))
        }
    }
    override fun onTouchEvent(event:MotionEvent)=gesture.onTouchEvent(event)
    override fun onDetachedFromWindow(){gesture.clear();super.onDetachedFromWindow()}
    override fun onCancelPendingInputEvents(){super.onCancelPendingInputEvents();gesture.clear()}
}

/** Single genuine system-mix relative-energy history. No synthetic input/output or module-gain curves. */
class BassLinkedEnergyView(context:Context):BassLinkedCanvas(context) {
    private data class Point(val sample:BassEnergyMonitor.Sample,val breakBefore:Boolean)
    private val history=ArrayDeque<Point>()
    private var anchor=0L;private var breakNext=true
    private var lastSample:BassEnergyMonitor.Sample?=null
    private var state="实时监视已关闭"
    private var receiving=false
    private var externalTitle=false
    private var interval=100L;private var lastPaint=0L
    private var held:List<Point>?=null;private var heldAnchor=0L;private var selected:Point?=null
    private val gesture=BassLinkedCursor(this,{x,y->if(plot.contains(x,y)&&history.isNotEmpty()){held=history.toList();heldAnchor=anchor;true}else false},
        {x->select(x);redraw()},{held=null;selected=null;redraw()})
    /** Header owns the title row in the collapsible layout; capture and history are unaffected. */
    fun setExternalTitle(value:Boolean){if(externalTitle==value)return;externalTitle=value;redraw()}
    fun setRefreshIntervalMs(ms:Int){interval=ms.coerceIn(50,5000).toLong();lastPaint=0L;redraw()}
    fun add(sample:BassEnergyMonitor.Sample) {
        if(sample.elapsedRealtimeMs<0 || lastSample?.let{sample.elapsedRealtimeMs<=it.elapsedRealtimeMs}==true)return
        val previous=lastSample
        anchor=max(anchor,sample.elapsedRealtimeMs)
        val split=breakNext || previous==null || previous.streamId!=sample.streamId ||
            sample.elapsedRealtimeMs-previous.elapsedRealtimeMs>max(300L,interval*3) || !valid(previous)
        history.addLast(Point(sample,split))
        while(history.size>400 || history.firstOrNull()?.sample?.elapsedRealtimeMs?.let{it<anchor-20000L}==true)history.removeFirst()
        lastSample=sample;breakNext=!valid(sample);receiving=valid(sample)
        state=if(valid(sample))"" else "系统混音暂无可见低频信号"
        if(held==null && sample.elapsedRealtimeMs-lastPaint>=interval){lastPaint=sample.elapsedRealtimeMs;redraw()}
    }
    fun showState(value:String){
        if(value==BassEnergyMonitor.State.Active.label){
            // Capture availability is not a signal sample and must not move the fixed explanation.
            state=if(receiving)"" else value
        }else{
            state=value;receiving=false;breakNext=true
        }
        redraw()
    }
    fun clear(){gesture.clear();history.clear();anchor=0L;lastSample=null;receiving=false;breakNext=true;lastPaint=0L;redraw()}
    private fun valid(sample:BassEnergyMonitor.Sample?)=sample!=null && sample.hasSignal && sample.levelDb.isFinite()
    private fun select(x:Float) {
        val points=held?:return
        val target=heldAnchor-((plot.right-x.coerceIn(plot.left,plot.right))/plot.width()*20000L).toLong()
        selected=points.filter{valid(it.sample)}.minByOrNull{abs(it.sample.elapsedRealtimeMs-target)}
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        plot.set((if(compact)23f else 31f)*dp,(if(externalTitle)8f else if(compact)12f else 30f)*dp,width-5f*dp,height-(if(compact)9f else 16f)*dp)
        if(plot.width()<=0 || plot.height()<=0)return
        if(!externalTitle)label(canvas,"最近20秒 · 系统混音低频相对能量（非dBFS）",dp,(if(compact)8f else 10f)*dp,width-2*dp)
        val last=lastSample
        if(!externalTitle && !compact)label(canvas,last?.let{"实际${fmt(it.lowerHz,1)}–${fmt(it.upperHz,1)}Hz · Δf${fmt(it.binWidthHz,1)}Hz · ${it.sampleRateHz}Hz / FFT${it.fftSize}"}
            ?:"目标10–500Hz · 采集率不是驱动处理采样率",dp,22f*dp,width-2*dp)
        val points=held?:history.toList();val end=if(held!=null)heldAnchor else anchor
        // Fixed reference and fixed axis prevent rescaling from disguising actual level changes.
        fun x(time:Long)=plot.right-((end-time)/20000.0).toFloat()*plot.width()
        fun y(db:Double)=plot.bottom-((db.coerceIn(-120.0,12.0)+120)/132).toFloat()*plot.height()
        for(db in listOf(-120.0,-60.0,0.0,12.0)){
            val yy=y(db);canvas.drawLine(plot.left,yy,plot.right,yy,grid)
            label(canvas,fmt(db,0),plot.left-3*dp,yy+3*dp,plot.left-5*dp,foreground,Paint.Align.RIGHT)
        }
        for(i in 0..4){val xx=plot.left+plot.width()*i/4
            canvas.drawLine(xx,plot.top,xx,plot.bottom,grid)
            label(canvas,if(i==4)"现在" else "−${20-i*5}s",xx,height-2*dp,plot.width()/4,foreground,
                if(i==0)Paint.Align.LEFT else if(i==4)Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        canvas.save();canvas.clipRect(plot)
        path.rewind();var started=false;var latest:Point?=null
        points.forEach {p->
            if(!valid(p.sample)){started=false;return@forEach}
            val xx=x(p.sample.elapsedRealtimeMs);val yy=y(p.sample.levelDb)
            if(!started || p.breakBefore)path.moveTo(xx,yy)else path.lineTo(xx,yy)
            started=true;latest=p
        }
        line.color=primary;line.strokeWidth=1.7f*dp;canvas.drawPath(path,line)
        latest?.let{dot(canvas,x(it.sample.elapsedRealtimeMs),y(it.sample.levelDb),primary)}
        selected?.let{val xx=x(it.sample.elapsedRealtimeMs);canvas.drawLine(xx,plot.top,xx,plot.bottom,grid);dot(canvas,xx,y(it.sample.levelDb),orange,3.5f)}
        canvas.restore()
        if(held==null && (latest==null || !receiving) && state.isNotEmpty()){
            label(canvas,state,plot.centerX(),plot.centerY(),plot.width()-4*dp,foreground,Paint.Align.CENTER)
        }
        selected?.sample?.let{
            tooltip(canvas,listOf("−${fmt((heldAnchor-it.elapsedRealtimeMs)/1000.0,2)}s · ${fmt(it.levelDb,3)}dB（相对）",
                "实际频带 ${fmt(it.lowerHz,2)}–${fmt(it.upperHz,2)}Hz / Δf${fmt(it.binWidthHz,2)}Hz",
                "系统混音单频带统计；不是低频模块输出增益"))
        }
    }
    override fun onTouchEvent(event:MotionEvent)=gesture.onTouchEvent(event)
    override fun onDetachedFromWindow(){gesture.clear();super.onDetachedFromWindow()}
    override fun onCancelPendingInputEvents(){super.onCancelPendingInputEvents();gesture.clear()}
}

/** Vertical scrolling wins; long press or a clearly horizontal movement opens the read-only cursor. */
private class BassLinkedCursor(private val view:View,private val begin:(Float,Float)->Boolean,
    private val move:(Float)->Unit,private val end:()->Unit) {
    private val slop=ViewConfiguration.get(view.context).scaledTouchSlop
    private var downX=0f;private var downY=0f;private var currentX=0f
    private var pending=false;private var inspecting=false
    private val hold=Runnable{if(pending && view.isAttachedToWindow && view.isShown)start()}
    private fun start(){pending=false;view.removeCallbacks(hold);if(begin(downX,downY)){inspecting=true;view.parent?.requestDisallowInterceptTouchEvent(true);move(currentX)}}
    fun clear(){view.removeCallbacks(hold);pending=false;if(inspecting){inspecting=false;view.parent?.requestDisallowInterceptTouchEvent(false);end()}}
    fun onTouchEvent(event:MotionEvent):Boolean{
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{clear();downX=event.x;currentX=downX;downY=event.y;pending=true;view.postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong());return true}
            MotionEvent.ACTION_MOVE->{
                if(event.pointerCount!=1){clear();return false};currentX=event.x
                if(inspecting){move(currentX);return true}
                if(pending){val dx=abs(currentX-downX);val dy=abs(event.y-downY)
                    if(dx>slop && dx>dy*1.2f)start()else if(dy>slop){pending=false;view.removeCallbacks(hold)}}
                return true
            }
            MotionEvent.ACTION_UP->{val click=pending && !inspecting;clear();if(click)view.performClick();return true}
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP->{clear();return false}
        }
        return pending || inspecting
    }
}
