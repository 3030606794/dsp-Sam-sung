package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.ProfessionalRuntimeMonitor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Real, independently timed RMS-difference and attenuation readings. No audio or preference writes. */
internal class ClarityRealtimeChart(context:Context):View(context){
    data class Current(val enhancement:Double?,val dynamic:Double?,val deess:Double?,val status:String)
    private data class Point(val time:Long,val enhancement:Double?,val dynamic:Double?,val deess:Double?,val split:Boolean)
    private val density=resources.displayMetrics.density
    private val ink=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
    private val surface=MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface)
    private val dark=ColorUtils.calculateLuminance(surface)<.5
    private val colors=intArrayOf(MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary),
        if(dark)Color.rgb(255,198,118)else Color.rgb(169,102,22),
        if(dark)Color.rgb(104,216,236)else Color.rgb(16,124,146))
    private val text=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize=min(9f*resources.displayMetrics.scaledDensity,10f*this@ClarityRealtimeChart.density)
        fontFeatureSettings="tnum"
    }
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=1.6f*density;strokeJoin=Paint.Join.ROUND}
    private val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=.7f*density;color=ColorUtils.setAlphaComponent(ink,40)}
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val boundaryMarker=Path()
    private val plot=RectF()
    private val history=ArrayDeque<Point>()
    private var anchor=0L
    private var source:Any?=null
    private var rate=0
    private var generation:Int?=null
    private var sequence:Int?=null
    private var lastEnhancementTime=0L
    private var frames:Int?=null
    private var breakNext=true
    private var state="等待真实增强与动态衰减"
    private var current=Current(null,null,null,state)
    private var active=false
    private var held:List<Point>?=null
    private var heldAnchor=0L
    private var selected:Point?=null
    private var inspecting=false
    private var pendingHold=false
    private var downX=0f
    private var downY=0f
    private var cursorX=0f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    var onCursor:((String?)->Unit)?=null
    val minimumReadableHeight:Int get()=ceil(7*max(text.fontSpacing,text.fontMetrics.bottom-text.fontMetrics.top)+16*density).toInt()
    private val hold=Runnable {
        if(pendingHold)beginInspection(cursorX)
    }
    init {isClickable=true;contentDescription="真实增强RMS差、高频抑制和去齿音历史；长按并横拖查看"}
    fun setActive(value:Boolean){if(active==value)return;active=value;if(!value)cancelInspection();redraw()}
    private fun redraw(){if(active&&isAttachedToWindow&&isShown)invalidate()}
    private fun trim(){while(history.isNotEmpty()&&(history.size>128||history.first().time<anchor-20000L))history.removeFirst()}
    private fun waiting(reason:String):Current{
        breakNext=true;state=reason;current=Current(null,null,null,reason);redraw();return current
    }
    fun pause(reason:String){cancelInspection();anchor=max(anchor,SystemClock.elapsedRealtime());trim();waiting(reason)}
    fun showRuntime(sample:ProfessionalRuntimeMonitor.ClaritySample,model:ClarityPreviewModel?):Current{
        if(sample.elapsedRealtimeMs<anchor)return current
        anchor=sample.elapsedRealtimeMs;trim()
        val reading=sample.reading
        val issue=sample.output.globalIssue
        val reason=when{
            !sample.output.connected->"驱动未连接"
            issue==OutputRuntimeStatus.GlobalIssue.SUSPENDED->"全局音效链已挂起"
            issue==OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT->"当前声音未经过全局音效链"
            issue!=null->"当前输出链尚未处理音频"
            sample.output.activeSessions.size>1->"多路音频同时处理，暂不合并监测"
            sample.output.processing!=OutputRuntimeStatus.Processing.ACTIVE||sample.output.activeSessions.size!=1->"等待正常播放与活动音频"
            reading==null->"等待单一活动会话的清晰度读数"
            !reading.enabled||reading.processing!=OutputRuntimeStatus.Processing.ACTIVE->"等待实际音频处理帧"
            reading.rate !in 8000..768000||sample.output.activeSessions.single().rate!=reading.rate->"处理采样率正在同步"
            model==null->"正在读取当前参数模型"
            else->null
        }
        if(reason!=null)return waiting(reason)
        val value=reading?:return waiting("等待清晰度读数")
        val actualModel=model?:return waiting("等待参数模型")
        val native=value.visualization
        val nextGeneration=native?.clarityGeneration
        val nextSequence=native?.claritySequence
        val changedSource=source!==value.identity||rate!=value.rate
        val changedGeneration=generation!=null&&nextGeneration!=null&&generation!=nextGeneration
        if(changedSource){source=value.identity;rate=value.rate;frames=null;sequence=null;generation=null;lastEnhancementTime=0;breakNext=true}
        if(changedGeneration){sequence=null;lastEnhancementTime=0;breakNext=true}
        if(nextGeneration!=null)generation=nextGeneration
        val energyWindow=nextGeneration!=null&&nextSequence!=null&&nextGeneration>=0&&nextSequence>0
        val freshEnhancement=energyWindow&&(sequence==null||nextSequence!=sequence)
        val staleSequence=energyWindow&&sequence!=null&&nextSequence!!<sequence!!&&!changedGeneration
        val nextFrames=value.frames
        val freshFrames=nextFrames!=null&&nextFrames>=0&&nextFrames!=frames
        if(nextFrames!=null&&frames!=null&&nextFrames<frames!!&&!(frames!!>=0x70000000&&nextFrames<=0x0fffffff))breakNext=true
        val enhancement=value.clarityEnhancementDb?.takeIf {it.isFinite()&&native?.clarityClamped!=true&&energyWindow&&!staleSequence}
        val advanced=value.advancedTargets
        fun reduction(index:Int,enabled:Boolean):Double?=when{
            !enabled->0.0
            advanced.size!=9||advanced[index] !in 0..1800->null
            else->-advanced[index]/100.0
        }
        val dynamic=reduction(7,actualModel.dynamicEnabled)
        val deess=reduction(8,actualModel.deessEnabled)
        val validEnhancement=enhancement?.takeIf {freshEnhancement||anchor-lastEnhancementTime<=1500L}
        if(staleSequence)return waiting("等待同步后的增强采样窗口")
        if(freshEnhancement){sequence=nextSequence;lastEnhancementTime=anchor}
        if(nextFrames!=null)frames=nextFrames
        state=when{
            native?.clarityClamped==true->"增强窗口超出量程；有效动态衰减继续显示"
            validEnhancement==null&&dynamic==null&&deess==null->"驱动尚未提供有效增强与衰减读数"
            validEnhancement==null->"增强暂无有效RMS窗；动态衰减独立显示"
            else->"增强RMS差在抑制前测量；衰减为独立读数"
        }
        val outside=listOf("增强" to validEnhancement,"高频抑制" to dynamic,"去齿音" to deess)
            .filter {(_,value)->value!=null&&abs(value)>24.0}
        if(outside.isNotEmpty())state+="；"+outside.joinToString(" · ") {(name,value)->
            "$name 实际 ${number(value)} dB（超出图示±24 dB）"
        }
        current=Current(validEnhancement,dynamic,deess,state)
        if(freshEnhancement||freshFrames){
            val e=enhancement.takeIf {freshEnhancement}
            val d=dynamic.takeIf {freshFrames}
            val s=deess.takeIf {freshFrames}
            if(e!=null||d!=null||s!=null){
                val previous=history.lastOrNull()
                if(previous==null||anchor>previous.time){
                    history.addLast(Point(anchor,e,d,s,breakNext||previous==null||anchor-previous.time>1600L))
                    breakNext=false;trim()
                }
            }else breakNext=true
        }
        redraw();return current
    }
    private fun number(value:Double?)=value?.takeIf {it.isFinite()}?.let {String.format(Locale.ROOT,"%+.2f",it)}?:"—"
    private fun label(c:Canvas,value:String,x:Float,y:Float,space:Float,tint:Int=ink,align:Paint.Align=Paint.Align.LEFT){
        if(space<=0)return;text.color=tint;text.textAlign=align
        c.drawText(TextUtils.ellipsize(value,text,space,TextUtils.TruncateAt.END).toString(),x,y,text)
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        val metrics=text.fontMetrics;val row=max(text.fontSpacing,metrics.bottom-metrics.top)+2*density
        val base=2*density-metrics.top
        val names=listOf("增强 RMS差","高频抑制","去齿音")
        names.forEachIndexed {index,name->label(c,"— $name",2*density+(width-4*density)*index/3,base,(width-4*density)/3-3*density,colors[index])}
        plot.set(max(30*density,text.measureText("−24")+5*density),row+metrics.bottom-metrics.top/2+2*density,width-5*density,height-row-3*density)
        if(plot.width()<=0||plot.height()<=0)return
        fun y(value:Double)=plot.bottom-((value+24)/48).toFloat()*plot.height()
        fun x(time:Long,end:Long)=plot.right-((end-time)/20000.0).toFloat()*plot.width()
        val ticks=if(plot.height()<row*3)listOf(-24,0,24)else listOf(-24,-12,0,12,24)
        ticks.forEach {value->
            val yy=y(value.toDouble());c.drawLine(plot.left,yy,plot.right,yy,grid)
            label(c,value.toString(),plot.left-3*density,yy-(metrics.ascent+metrics.descent)/2,plot.left-5*density,ColorUtils.setAlphaComponent(ink,170),Paint.Align.RIGHT)
        }
        pen.color=ColorUtils.setAlphaComponent(ink,100);pen.strokeWidth=density
        c.drawLine(plot.left,y(0.0),plot.right,y(0.0),pen)
        repeat(5){index->
            val xx=plot.left+plot.width()*index/4;c.drawLine(xx,plot.top,xx,plot.bottom,grid)
            label(c,if(index==4)"现在"else if(index==0)"−20s"else"−${20-index*5}",xx,height-2*density-metrics.bottom,plot.width()/4,
                ColorUtils.setAlphaComponent(ink,170),if(index==0)Paint.Align.LEFT else if(index==4)Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        val points=held?:history.toList();val end=if(held!=null)heldAnchor else anchor
        c.save();c.clipRect(plot)
        repeat(3){series->
            path.rewind();var drawing=false
            points.forEach {point->
                val value=when(series){0->point.enhancement;1->point.dynamic;else->point.deess}
                if(value==null){drawing=false;return@forEach}
                val xx=x(point.time,end);val yy=y(value)
                if(!drawing||point.split)path.moveTo(xx,yy)else path.lineTo(xx,yy)
                drawing=true
            }
            pen.color=colors[series];pen.strokeWidth=1.6f*density;c.drawPath(path,pen)
            // Keep the actual out-of-range sample, marking its direction at the fixed axis edge.
            fill.color=colors[series]
            points.forEach {point->
                val value=when(series){0->point.enhancement;1->point.dynamic;else->point.deess}
                if(value!=null&&abs(value)>24.0){
                    val xx=x(point.time,end)
                    if(xx in plot.left..plot.right){
                        val edge=if(value>24.0)plot.top else plot.bottom
                        val inside=if(value>24.0)edge+4.5f*density else edge-4.5f*density
                        boundaryMarker.rewind();boundaryMarker.moveTo(xx,edge)
                        boundaryMarker.lineTo(xx-2.8f*density,inside)
                        boundaryMarker.lineTo(xx+2.8f*density,inside)
                        boundaryMarker.close();c.drawPath(boundaryMarker,fill)
                    }
                }
            }
        }
        selected?.let {point->val xx=x(point.time,end);c.drawLine(xx,plot.top,xx,plot.bottom,grid)
            listOf(point.enhancement,point.dynamic,point.deess).forEachIndexed {i,value->if(value!=null){fill.color=colors[i];c.drawCircle(xx,y(value),2*density,fill)}}}
        c.restore()
        if(points.isEmpty()||current.enhancement==null&&current.dynamic==null&&current.deess==null)
            label(c,state,plot.centerX(),plot.centerY()-(metrics.ascent+metrics.descent)/2,plot.width()-6*density,ColorUtils.setAlphaComponent(ink,180),Paint.Align.CENTER)
        selected?.let {point->
            val lines=listOf("−${String.format(Locale.ROOT,"%.2f",(heldAnchor-point.time)/1000.0)}s · 增强 ${number(point.enhancement)} dB",
                "高频 ${number(point.dynamic)} · 齿音 ${number(point.deess)} dB")
            val box=RectF(plot.left+2*density,plot.top+2*density,plot.right-2*density,plot.top+2*row+5*density)
            fill.color=ColorUtils.setAlphaComponent(surface,245);c.drawRoundRect(box,3*density,3*density,fill)
            lines.forEachIndexed {i,line->label(c,line,box.left+3*density,box.top-metrics.top+i*row,box.width()-6*density)}
        }
    }
    private fun select(x:Float){
        if(plot.width()<=0)return
        val time=heldAnchor-((plot.right-x.coerceIn(plot.left,plot.right))/plot.width()*20000).toLong()
        selected=held?.minByOrNull {abs(it.time-time)}
        selected?.let {point->onCursor?.invoke("增强 ${number(point.enhancement)} · 高频 ${number(point.dynamic)} · 齿音 ${number(point.deess)} dB")}
        redraw()
    }
    private fun beginInspection(x:Float):Boolean{
        if(!isAttachedToWindow||!isShown||!plot.contains(downX,downY)||history.isEmpty())return false
        removeCallbacks(hold);pendingHold=false;inspecting=true
        held=history.toList();heldAnchor=anchor
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
