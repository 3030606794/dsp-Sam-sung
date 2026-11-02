package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
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
import me.timschneeberger.rootlessjamesdsp.fusion.BassEnergyMonitor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The system Visualizer's fixed-reference relative FFT level; never a module gain or dBFS. */
internal class ViperBassRealtimeChart(context:Context):View(context){
    private data class Point(val sample:BassEnergyMonitor.Sample,val split:Boolean)
    private val density=resources.displayMetrics.density
    private val ink=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
    private val accent=MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary)
    private val surface=MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface)
    private val text=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize=min(9f*resources.displayMetrics.scaledDensity,10f*this@ViperBassRealtimeChart.density)
        fontFeatureSettings="tnum"
    }
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE;strokeWidth=1.6f*density;strokeJoin=Paint.Join.ROUND;color=accent
    }
    private val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE;strokeWidth=.7f*density;color=ColorUtils.setAlphaComponent(ink,40)
    }
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val boundaryMarker=Path()
    private val plot=RectF()
    private val history=ArrayDeque<Point>()
    private var anchor=0L
    private var breakNext=true
    private var state="等待系统混音采样"
    private var active=false
    private var lastPaint=0L
    private var receiving=false
    var latestSample:BassEnergyMonitor.Sample?=null
        private set
    val hasCurrentSignal:Boolean get()=receiving
    private var held:List<Point>?=null
    private var heldAnchor=0L
    private var selected:Point?=null
    private var inspecting=false
    private var pendingHold=false
    private var downX=0f
    private var downY=0f
    private var cursorX=0f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    var onCursor:((BassEnergyMonitor.Sample?)->Unit)?=null
    val minimumReadableHeight:Int get()=ceil(8*max(text.fontSpacing,text.fontMetrics.bottom-text.fontMetrics.top)+16*density).toInt()
    private val hold=Runnable {if(pendingHold)beginInspection(cursorX)}

    init {isClickable=true;contentDescription="系统混音低频相对能量，非dBFS和模块增益；长按或横拖查看实际频带、FFT分辨率和采集率"}
    fun setActive(value:Boolean){if(active==value)return;active=value;if(!value)cancelInspection();redraw()}
    private fun redraw(){if(active&&isAttachedToWindow&&isShown)invalidate()}
    private fun trim(){while(history.isNotEmpty()&&(history.size>400||history.first().sample.elapsedRealtimeMs<anchor-20000L))history.removeFirst()}
    /** Capture availability is not a new signal sample. Keep previous genuine history. */
    fun showState(value:String){
        if(value==BassEnergyMonitor.State.Active.label){if(!receiving)state=value}
        else{
            cancelInspection();state=value;receiving=false;breakNext=true
            anchor=max(anchor,SystemClock.elapsedRealtime());trim()
        }
        redraw()
    }
    /** Keep every actual 50 ms sample; present at a fixed cadence without relaying out controls. */
    fun add(sample:BassEnergyMonitor.Sample):Boolean{
        if(sample.elapsedRealtimeMs<0||sample.elapsedRealtimeMs<anchor||
            latestSample?.let {sample.elapsedRealtimeMs<=it.elapsedRealtimeMs}==true)return false
        val previous=latestSample
        anchor=sample.elapsedRealtimeMs;trim();latestSample=sample
        val valid=isSignal(sample)
        if(valid){
            val split=breakNext||previous==null||previous.streamId!=sample.streamId||
                previous.sampleRateHz!=sample.sampleRateHz||previous.fftSize!=sample.fftSize||
                previous.binWidthHz!=sample.binWidthHz||previous.lowerHz!=sample.lowerHz||previous.upperHz!=sample.upperHz||
                sample.elapsedRealtimeMs-previous.elapsedRealtimeMs>300L||!isSignal(previous)
            history.addLast(Point(sample,split));trim()
        }
        breakNext=!valid;receiving=valid
        state=when{valid->"";validMetadata(sample)->"暂无可见低频信号";else->"系统FFT采样正在同步"}
        val render=sample.elapsedRealtimeMs-lastPaint>=100L
        if(render){lastPaint=sample.elapsedRealtimeMs;if(held==null)redraw()}
        return render
    }
    private fun number(value:Double,digits:Int=2)=String.format(Locale.ROOT,"%.${digits}f",value)
    private fun label(c:Canvas,value:String,x:Float,y:Float,space:Float,tint:Int=ink,align:Paint.Align=Paint.Align.LEFT){
        if(space<=0)return;text.color=tint;text.textAlign=align
        c.drawText(TextUtils.ellipsize(value,text,space,TextUtils.TruncateAt.END).toString(),x,y,text)
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        val metrics=text.fontMetrics;val row=max(text.fontSpacing,metrics.bottom-metrics.top)+2*density
        val base=2*density-metrics.top
        label(c,"系统混音低频 · 相对dB",2*density,base,width-7*density,accent)
        plot.set(max(31*density,text.measureText("−120")+5*density),row+metrics.bottom-metrics.top/2+2*density,width-5*density,height-row-3*density)
        if(plot.width()<=0||plot.height()<=0)return
        fun y(value:Double)=plot.bottom-((value+120.0)/132.0).toFloat()*plot.height()
        fun x(time:Long,end:Long)=plot.right-((end-time)/20000.0).toFloat()*plot.width()
        val ticks=if(plot.height()*12f/132f<row)listOf(-120,-60,12)else listOf(-120,-60,0,12)
        ticks.forEach {value->
            val yy=y(value.toDouble());c.drawLine(plot.left,yy,plot.right,yy,grid)
            label(c,value.toString(),plot.left-3*density,yy-(metrics.ascent+metrics.descent)/2,plot.left-5*density,ColorUtils.setAlphaComponent(ink,170),Paint.Align.RIGHT)
        }
        repeat(5){index->
            val xx=plot.left+plot.width()*index/4;c.drawLine(xx,plot.top,xx,plot.bottom,grid)
            label(c,if(index==4)"现在"else if(index==0)"−20s"else"−${20-index*5}",xx,height-2*density-metrics.bottom,plot.width()/4,
                ColorUtils.setAlphaComponent(ink,170),if(index==0)Paint.Align.LEFT else if(index==4)Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        val points=held?:history.toList();val end=if(held!=null)heldAnchor else anchor
        c.save();c.clipRect(plot)
        path.rewind();line.alpha=if(!receiving&&held==null)110 else 255
        points.forEachIndexed {index,point->
            val xx=x(point.sample.elapsedRealtimeMs,end);val yy=y(point.sample.levelDb)
            if(index==0||point.split)path.moveTo(xx,yy)else path.lineTo(xx,yy)
        }
        c.drawPath(path,line);fill.color=ColorUtils.setAlphaComponent(accent,line.alpha)
        points.forEach {point->
            val xx=x(point.sample.elapsedRealtimeMs,end);val db=point.sample.levelDb
            if(db in -120.0..12.0)c.drawCircle(xx,y(db),1.1f*density,fill)
            else if(xx in plot.left..plot.right){
                val edge=if(db>12.0)plot.top else plot.bottom
                val inside=if(db>12.0)edge+4.5f*density else edge-4.5f*density
                boundaryMarker.rewind();boundaryMarker.moveTo(xx,edge)
                boundaryMarker.lineTo(xx-2.8f*density,inside);boundaryMarker.lineTo(xx+2.8f*density,inside)
                boundaryMarker.close();c.drawPath(boundaryMarker,fill)
            }
        }
        selected?.let {point->val xx=x(point.sample.elapsedRealtimeMs,end)
            c.drawLine(xx,plot.top,xx,plot.bottom,grid);fill.color=accent;c.drawCircle(xx,y(point.sample.levelDb),3*density,fill)}
        line.alpha=255;c.restore()
        if(points.isEmpty()||!receiving&&held==null)
            label(c,state,plot.centerX(),plot.centerY()-(metrics.ascent+metrics.descent)/2,plot.width()-6*density,ColorUtils.setAlphaComponent(ink,180),Paint.Align.CENTER)
        selected?.sample?.let {sample->
            val lines=listOf("−${number((heldAnchor-sample.elapsedRealtimeMs)/1000.0)}s · ${number(sample.levelDb)} dB（系统相对）",
                "频带 ${number(sample.lowerHz,1)}–${number(sample.upperHz,1)} Hz · Δf ${number(sample.binWidthHz,1)} Hz",
                "${sample.sampleRateHz} Hz / FFT${sample.fftSize} · 非dBFS／非模块增益")
            val box=RectF(plot.left+2*density,plot.top+2*density,plot.right-2*density,plot.top+3*row+5*density)
            fill.color=ColorUtils.setAlphaComponent(surface,245);c.drawRoundRect(box,3*density,3*density,fill)
            lines.forEachIndexed {index,value->label(c,value,box.left+3*density,box.top-metrics.top+index*row,box.width()-6*density)}
        }
    }
    private fun select(x:Float){
        if(plot.width()<=0)return
        val time=heldAnchor-((plot.right-x.coerceIn(plot.left,plot.right))/plot.width()*20000).toLong()
        selected=held?.minByOrNull {abs(it.sample.elapsedRealtimeMs-time)}
        selected?.let {onCursor?.invoke(it.sample)};redraw()
    }
    private fun beginInspection(x:Float):Boolean{
        if(!isAttachedToWindow||!isShown||!plot.contains(downX,downY)||history.isEmpty())return false
        removeCallbacks(hold);pendingHold=false;inspecting=true;held=history.toList();heldAnchor=anchor
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
    companion object {
        private fun validMetadata(sample:BassEnergyMonitor.Sample)=sample.streamId>0&&
            sample.sampleRateHz>0&&sample.fftSize>=4&&sample.fftSize%2==0&&sample.binWidthHz.isFinite()&&sample.binWidthHz>0&&
            sample.lowerHz.isFinite()&&sample.upperHz.isFinite()&&sample.lowerHz>=0&&sample.upperHz>=sample.lowerHz&&
            sample.upperHz<=sample.sampleRateHz/2.0
        fun isSignal(sample:BassEnergyMonitor.Sample)=sample.hasSignal&&sample.levelDb.isFinite()&&validMetadata(sample)
    }
}
