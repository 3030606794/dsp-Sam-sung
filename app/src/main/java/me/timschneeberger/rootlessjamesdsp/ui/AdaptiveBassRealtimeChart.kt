package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.fusion.AdaptiveBassPlotConfiguration
import me.timschneeberger.rootlessjamesdsp.fusion.BassRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.ProfessionalRuntimeMonitor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A bounded history of actual tracking frequencies. Gain remains a numeric reading only. */
internal class AdaptiveBassRealtimeChart(context:Context):View(context){
    data class Current(val frequencyHz:Double?,val gainDb:Double?,val status:String)
    private data class Point(val time:Long,val frequencyHz:Double,val gainDb:Double,val split:Boolean)
    private val density=resources.displayMetrics.density
    private val ink=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
    private val accent=MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary)
    private val surface=MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface)
    private val text=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize=min(9f*resources.displayMetrics.scaledDensity,10f*this@AdaptiveBassRealtimeChart.density)
        fontFeatureSettings="tnum"
    }
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE;strokeWidth=1.6f*density;strokeJoin=Paint.Join.ROUND;color=accent
    }
    private val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE;strokeWidth=.7f*density;color=ColorUtils.setAlphaComponent(ink,40)
    }
    private val limit=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE;strokeWidth=density;color=ColorUtils.setAlphaComponent(ink,135)
        pathEffect=DashPathEffect(floatArrayOf(4*density,3*density),0f)
    }
    private val band=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=ColorUtils.setAlphaComponent(accent,18)}
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val plot=RectF()
    private val history=ArrayDeque<Point>()
    private var anchor=0L
    private var source:Any?=null
    private var sourceRate=0
    private var lower=0.0
    private var upper=250.0
    private var breakNext=true
    private var state="等待真实低频跟踪读数"
    private var current=Current(null,null,state)
    private var active=false
    private var held:List<Point>?=null
    private var heldAnchor=0L
    private var heldLower=0.0
    private var heldUpper=250.0
    private var selected:Point?=null
    private var inspecting=false
    private var pendingHold=false
    private var downX=0f
    private var downY=0f
    private var cursorX=0f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    var onCursor:((String?)->Unit)?=null
    val minimumReadableHeight:Int get()=ceil(7*max(text.fontSpacing,text.fontMetrics.bottom-text.fontMetrics.top)+16*density).toInt()
    private val hold=Runnable {if(pendingHold)beginInspection(cursorX)}

    init {isClickable=true;contentDescription="真实跟踪频率，纵轴0至250 Hz；阴影与虚线为设定跟踪范围；长按或横拖查看，增强只显示数值"}
    fun setActive(value:Boolean){if(active==value)return;active=value;if(!value)cancelInspection();redraw()}
    private fun redraw(){if(active&&isAttachedToWindow&&isShown)invalidate()}
    private fun trim(){while(history.isNotEmpty()&&(history.size>128||history.first().time<anchor-20000L))history.removeFirst()}
    fun setConfiguration(config:AdaptiveBassPlotConfiguration){
        val a=config.lowerHz.takeIf {it.isFinite()}?.coerceIn(0.0,250.0)?:0.0
        val b=config.upperHz.takeIf {it.isFinite()}?.coerceIn(0.0,250.0)?:250.0
        val low=min(a,b);val high=max(a,b)
        if(lower==low&&upper==high)return
        lower=low;upper=high;redraw()
    }
    private fun waiting(reason:String):Current{
        breakNext=true;state=reason;current=Current(null,null,reason);redraw();return current
    }
    fun pause(reason:String){cancelInspection();anchor=max(anchor,SystemClock.elapsedRealtime());trim();waiting(reason)}
    fun showSample(sample:ProfessionalRuntimeMonitor.BassSample,config:AdaptiveBassPlotConfiguration?):Current{
        // The sampler has already required processed-frame progress. Equal frequencies are real samples.
        if(sample.elapsedRealtimeMs<=anchor)return current
        anchor=sample.elapsedRealtimeMs;trim()
        val bass=sample.bass;val output=sample.output
        val frequency=bass.frequencyHundredths;val gain=bass.gainHundredths
        val reason=when{
            !output.connected->"驱动未连接"
            output.globalIssue==OutputRuntimeStatus.GlobalIssue.SUSPENDED->"全局音效链已挂起"
            output.globalIssue==OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT->"当前声音未经过全局音效链"
            output.globalIssue!=null->"当前输出链尚未处理音频"
            config?.enabled==false||bass.state==BassRuntimeStatus.State.OFF->"自适应低频增强已关闭"
            bass.state==BassRuntimeStatus.State.UNSUPPORTED->"当前驱动不支持实时低频读数"
            bass.state==BassRuntimeStatus.State.MULTIPLE||output.activeSessions.size>1->"多路音频同时处理，暂不合并监测"
            output.processing!=OutputRuntimeStatus.Processing.ACTIVE||output.activeSessions.size!=1->"等待正常播放与活动音频"
            bass.state!=BassRuntimeStatus.State.ACTIVE||sample.streamIdentity==null->"等待推进的真实音频处理帧"
            frequency==null||frequency !in 0..25000||gain==null||gain !in 0..1500->"等待有效低频跟踪读数"
            output.activeSessions.single().rate !in 8000..768000->"处理采样率正在同步"
            else->null
        }
        if(reason!=null)return waiting(reason)
        val rate=output.activeSessions.single().rate
        if(source!==sample.streamIdentity||sourceRate!=rate){
            source=sample.streamIdentity;sourceRate=rate;breakNext=true
        }
        val hz=frequency!!/100.0;val db=gain!!/100.0
        val previous=history.lastOrNull()
        history.addLast(Point(anchor,hz,db,breakNext||previous==null||anchor-previous.time>1600L))
        breakNext=false;trim()
        state=if(hz<lower||hz>upper)
            String.format(Locale.ROOT,"目标 %.0f–%.0f Hz · 实际频率正在平滑过渡",lower,upper)
        else "实际音频跟踪 · 长按或横拖查看"
        current=Current(hz,db,state);redraw();return current
    }
    private fun number(value:Double,signed:Boolean=false)=String.format(Locale.ROOT,if(signed)"%+.2f"else"%.2f",value)
    fun rangeText():String{
        val points=held?:history.toList();val prefix=if(held!=null)"查看窗口"else"近20秒"
        if(points.isEmpty())return "$prefix · 频率范围 — Hz"
        return "$prefix · 频率范围 ${number(points.minOf {it.frequencyHz})}–${number(points.maxOf {it.frequencyHz})} Hz"
    }
    private fun label(c:Canvas,value:String,x:Float,y:Float,space:Float,tint:Int=ink,align:Paint.Align=Paint.Align.LEFT){
        if(space<=0)return;text.color=tint;text.textAlign=align
        c.drawText(TextUtils.ellipsize(value,text,space,TextUtils.TruncateAt.END).toString(),x,y,text)
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        val metrics=text.fontMetrics;val row=max(text.fontSpacing,metrics.bottom-metrics.top)+2*density
        val base=2*density-metrics.top
        val displayedLower=if(held!=null)heldLower else lower
        val displayedUpper=if(held!=null)heldUpper else upper
        label(c,"Hz  — 实时频率",2*density,base,width*.44f,accent)
        val range=String.format(Locale.ROOT,"虚线 %.0f–%.0f Hz",displayedLower,displayedUpper)
        label(c,range,width-5*density,base,width*.55f,ColorUtils.setAlphaComponent(ink,170),Paint.Align.RIGHT)
        plot.set(max(30*density,text.measureText("250")+5*density),row+metrics.bottom-metrics.top/2+2*density,width-5*density,height-row-3*density)
        if(plot.width()<=0||plot.height()<=0)return
        fun y(value:Double)=plot.bottom-(value/250.0).toFloat()*plot.height()
        fun x(time:Long,end:Long)=plot.right-((end-time)/20000.0).toFloat()*plot.width()
        c.drawRect(plot.left,y(displayedUpper),plot.right,y(displayedLower),band)
        val ticks=if(plot.height()<row*5)listOf(0,125,250)else listOf(0,50,100,150,200,250)
        ticks.forEach {value->
            val yy=y(value.toDouble());c.drawLine(plot.left,yy,plot.right,yy,grid)
            label(c,value.toString(),plot.left-3*density,yy-(metrics.ascent+metrics.descent)/2,plot.left-5*density,ColorUtils.setAlphaComponent(ink,170),Paint.Align.RIGHT)
        }
        repeat(5){index->
            val xx=plot.left+plot.width()*index/4;c.drawLine(xx,plot.top,xx,plot.bottom,grid)
            label(c,if(index==4)"现在"else if(index==0)"−20s"else"−${20-index*5}",xx,height-2*density-metrics.bottom,plot.width()/4,
                ColorUtils.setAlphaComponent(ink,170),if(index==0)Paint.Align.LEFT else if(index==4)Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        c.drawLine(plot.left,y(displayedLower),plot.right,y(displayedLower),limit)
        c.drawLine(plot.left,y(displayedUpper),plot.right,y(displayedUpper),limit)
        val points=held?:history.toList();val end=if(held!=null)heldAnchor else anchor
        c.save();c.clipRect(plot.left-2*density,plot.top-2*density,plot.right+2*density,plot.bottom+2*density)
        path.rewind();line.alpha=if(current.frequencyHz==null&&held==null)110 else 255
        points.forEachIndexed {index,point->
            val xx=x(point.time,end);val yy=y(point.frequencyHz)
            if(index==0||point.split)path.moveTo(xx,yy)else path.lineTo(xx,yy)
        }
        c.drawPath(path,line)
        // An isolated valid point is visible without inventing a preceding sample.
        fill.color=ColorUtils.setAlphaComponent(accent,line.alpha)
        points.forEach {point->c.drawCircle(x(point.time,end),y(point.frequencyHz),1.4f*density,fill)}
        selected?.let {point->val xx=x(point.time,end);val yy=y(point.frequencyHz)
            c.drawLine(xx,plot.top,xx,plot.bottom,limit);fill.color=accent;c.drawCircle(xx,yy,3*density,fill)}
        line.alpha=255;c.restore()
        if(points.isEmpty()||current.frequencyHz==null&&held==null)
            label(c,state,plot.centerX(),plot.centerY()-(metrics.ascent+metrics.descent)/2,plot.width()-6*density,ColorUtils.setAlphaComponent(ink,180),Paint.Align.CENTER)
        selected?.let {point->
            val lines=listOf("−${number((heldAnchor-point.time)/1000.0)}s · 跟踪 ${number(point.frequencyHz)} Hz",
                "当前增强 ${number(point.gainDb,true)} dB")
            val box=RectF(plot.left+2*density,plot.top+2*density,plot.right-2*density,plot.top+2*row+5*density)
            fill.color=ColorUtils.setAlphaComponent(surface,245);c.drawRoundRect(box,3*density,3*density,fill)
            lines.forEachIndexed {i,value->label(c,value,box.left+3*density,box.top-metrics.top+i*row,box.width()-6*density)}
        }
    }
    private fun select(x:Float){
        if(plot.width()<=0)return
        val time=heldAnchor-((plot.right-x.coerceIn(plot.left,plot.right))/plot.width()*20000).toLong()
        selected=held?.minByOrNull {abs(it.time-time)}
        selected?.let {point->onCursor?.invoke("当前增强 ${number(point.gainDb,true)} dB · 跟踪频率 ${number(point.frequencyHz)} Hz")}
        redraw()
    }
    private fun beginInspection(x:Float):Boolean{
        if(!isAttachedToWindow||!isShown||!plot.contains(downX,downY)||history.isEmpty())return false
        removeCallbacks(hold);pendingHold=false;inspecting=true;held=history.toList();heldAnchor=anchor
        heldLower=lower;heldUpper=upper
        parent?.requestDisallowInterceptTouchEvent(true);select(x);return true
    }
    private fun cancelInspection(){
        removeCallbacks(hold);pendingHold=false
        if(inspecting){inspecting=false;held=null;selected=null;parent?.requestDisallowInterceptTouchEvent(false);onCursor?.invoke(null);redraw()}
    }
    override fun onTouchEvent(event:MotionEvent):Boolean=when(event.actionMasked){
        MotionEvent.ACTION_DOWN->{cancelInspection();downX=event.x;cursorX=event.x;downY=event.y;pendingHold=true;postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong());true}
        MotionEvent.ACTION_MOVE->{cursorX=event.x;if(event.pointerCount!=1){cancelInspection();false}else if(inspecting){select(cursorX);true}else{
            val dx=abs(event.x-downX);val dy=abs(event.y-downY)
            if(pendingHold&&dx>slop&&dx>dy&&beginInspection(cursorX))true else{
                if(dx>slop||dy>slop){pendingHold=false;removeCallbacks(hold);parent?.requestDisallowInterceptTouchEvent(false)}
                !(dy>slop&&dy>=dx)
            }}}
        MotionEvent.ACTION_UP->{val click=pendingHold&&!inspecting;cancelInspection();if(click)performClick();true}
        MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP->{cancelInspection();false}
        else->pendingHold||inspecting
    }
    override fun performClick():Boolean{super.performClick();return true}
    override fun onCancelPendingInputEvents(){super.onCancelPendingInputEvents();cancelInspection()}
    override fun onDetachedFromWindow(){cancelInspection();super.onDetachedFromWindow()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){cancelInspection();super.onSizeChanged(w,h,oldw,oldh)}
}
