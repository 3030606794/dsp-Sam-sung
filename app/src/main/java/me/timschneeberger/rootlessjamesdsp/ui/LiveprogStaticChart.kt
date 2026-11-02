package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPlotStyle
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import java.util.Locale
import kotlin.math.*

/** Canvas presentation only. Plot construction never executes an imported script. */
internal class LiveprogStaticChart(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    private val colors = listOf(primary, if(dark)Color.rgb(104,216,236) else Color.rgb(16,124,146),
        if(dark)Color.rgb(255,198,118) else Color.rgb(169,102,22),
        if(dark)Color.rgb(169,208,157) else Color.rgb(70,128,61),
        if(dark)Color.rgb(233,162,192) else Color.rgb(159,74,114))
    private val muted=ColorUtils.setAlphaComponent(ink,155)
    private val text=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize=min(9f*resources.displayMetrics.scaledDensity,10f*d);fontFeatureSettings="tnum"
    }
    private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE;strokeWidth=1.5f*d;strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND
    }
    private val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE;strokeWidth=.65f*d;color=ColorUtils.setAlphaComponent(ink,36)
    }
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashed=DashPathEffect(floatArrayOf(4*d,3*d),0f)
    private val path=Path()
    private val rect=RectF()
    private var plot=LiveprogStaticPlot("脚本参数",notes=listOf("等待选择脚本"))
    private var active=false

    init {
        isLongClickable=true
        setOnLongClickListener {
            GlassAlertDialogBuilder(context).setTitle(plot.title).setMessage(details())
                .setPositiveButton(android.R.string.ok,null).show();true
        }
    }
    fun setActive(value:Boolean){active=value;if(active&&isAttachedToWindow)invalidate()}
    fun setPlot(value:LiveprogStaticPlot){plot=value;contentDescription=details();if(active&&isAttachedToWindow)invalidate()}
    private fun color(index:Int,alpha:Int=235)=ColorUtils.setAlphaComponent(colors[Math.floorMod(index,colors.size)],if(plot.inactive)min(alpha,85)else alpha)
    private fun label(c:Canvas,value:String,x:Float,y:Float,space:Float,color:Int=ink,right:Boolean=false){
        if(space<=0)return
        text.color=color;text.textAlign=if(right)Paint.Align.RIGHT else Paint.Align.LEFT
        c.drawText(TextUtils.ellipsize(value,text,space,TextUtils.TruncateAt.END).toString(),x,y,text)
    }
    private fun arrow(c:Canvas,ax:Float,ay:Float,bx:Float,by:Float,tint:Int,width:Float=1.5f*d,dash:Boolean=false){
        val length=hypot(bx-ax,by-ay)
        if(!length.isFinite())return
        if(length<d){fill.color=tint;c.drawCircle(bx,by,1.8f*d,fill);return}
        val oldWidth=stroke.strokeWidth
        stroke.color=tint;stroke.strokeWidth=width;stroke.pathEffect=if(dash)dashed else null
        c.drawLine(ax,ay,bx,by,stroke);stroke.pathEffect=null
        val ux=(bx-ax)/length;val uy=(by-ay)/length
        val head=min(4.5f*d,length*.45f)
        c.drawLine(bx-ux*head-uy*head*.55f,by-uy*head+ux*head*.55f,bx,by,stroke)
        c.drawLine(bx-ux*head+uy*head*.55f,by-uy*head-ux*head*.55f,bx,by,stroke)
        stroke.strokeWidth=oldWidth
    }
    private fun twoLines(c:Canvas,value:String,bounds:RectF,tint:Int){
        if(bounds.width()<=0||bounds.height()<=0)return
        val available=(bounds.width()-6*d).coerceAtLeast(d)
        text.color=tint;text.textAlign=Paint.Align.CENTER
        val metrics=text.fontMetrics
        val lineHeight=metrics.bottom-metrics.top
        if(bounds.height()<lineHeight)return
        val count=text.breakText(value,true,available,null)
        val lines=if(bounds.height()<2*lineHeight)listOf(
            TextUtils.ellipsize(value,text,available,TextUtils.TruncateAt.END).toString())
            else if(count>=value.length)listOf(value)else listOf(value.take(count),
            TextUtils.ellipsize(value.drop(count).trimStart(),text,available,TextUtils.TruncateAt.END).toString())
        val total=lineHeight*lines.size
        val first=bounds.centerY()-total/2-metrics.top
        lines.forEachIndexed {index,line->c.drawText(line,bounds.centerX(),first+index*lineHeight,text)}
    }
    private fun fmt(v:Double):String=when {
        !v.isFinite()->"—"
        abs(v)>=10000->String.format(Locale.ROOT,"%.0fk",v/1000)
        abs(v)>=1000->String.format(Locale.ROOT,"%.1fk",v/1000)
        abs(v)>=100->String.format(Locale.ROOT,"%.0f",v)
        abs(v)>=1->String.format(Locale.ROOT,"%.1f",v)
        v==0.0->"0"
        else->String.format(Locale.ROOT,"%.2f",v)
    }
    /** Keep signs and nonzero small values; numeric captions never use an ellipsis. */
    private fun barNumber(value:Double,space:Float):String {
        if(!value.isFinite())return "—"
        if(value==0.0)return "0"
        var shortest=""
        for(precision in 4 downTo 1){
            val parts=String.format(Locale.ROOT,"%.${precision}g",value).split('e')
            val mantissa=if('.' in parts[0])parts[0].trimEnd('0').trimEnd('.')else parts[0]
            val candidate=mantissa+if(parts.size>1)"e${parts[1].toInt()}"else ""
            if(shortest.isEmpty()||text.measureText(candidate)<text.measureText(shortest))shortest=candidate
            if(text.measureText(candidate)<=space)return candidate
        }
        return shortest
    }
    private fun x(value:Double):Float {
        val lo=plot.xMinimum;val hi=plot.xMaximum
        val ratio=if(plot.logarithmicX&&lo>0&&hi>lo&&value>0)ln(value/lo)/ln(hi/lo)
            else if(hi>lo)(value-lo)/(hi-lo)else 0.0
        return rect.left+ratio.toFloat()*rect.width()
    }
    private fun y(value:Double):Float=rect.bottom-
        (if(plot.yMaximum>plot.yMinimum)(value-plot.yMinimum)/(plot.yMaximum-plot.yMinimum)else 0.0).toFloat()*rect.height()
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        if(width<=0||height<=0)return
        label(c,plot.subtitle,2*d,11*d,width-4*d,if(plot.inactive)muted else primary)
        // Full notes remain in the long-press details; do not repeat them under both charts.
        val footer=15*d
        val routing=plot.style==LiveprogPlotStyle.ROUTING||plot.style==LiveprogPlotStyle.STEREO||plot.style==LiveprogPlotStyle.TEXT
        rect.set(if(routing)6*d else 28*d,22*d,width-6*d,height-footer)
        if(rect.width()>0&&rect.height()>6*d){
            when(plot.style){
                LiveprogPlotStyle.ROUTING->drawRouting(c)
                LiveprogPlotStyle.STEREO->drawStereo(c)
                LiveprogPlotStyle.BARS,LiveprogPlotStyle.PROBABILITY->drawBars(c)
                LiveprogPlotStyle.TEXT->drawTextPlot(c)
                else->drawCurves(c)
            }
        }
    }
    private fun axes(c:Canvas,showXLabels:Boolean=true,showUnit:Boolean=true){
        repeat(3){j->val yy=rect.top+rect.height()*j/2;c.drawLine(rect.left,yy,rect.right,yy,grid)}
        repeat(5){j->val xx=rect.left+rect.width()*j/4;c.drawLine(xx,rect.top,xx,rect.bottom,grid)}
        label(c,fmt(plot.yMaximum),rect.left-3*d,rect.top+3*d,25*d,muted,true)
        label(c,fmt(plot.yMinimum),rect.left-3*d,rect.bottom,25*d,muted,true)
        if(showXLabels&&rect.height()>text.fontSpacing*3){
            val middle=(plot.yMaximum+plot.yMinimum)/2
            label(c,fmt(middle),rect.left-3*d,rect.centerY()+text.fontSpacing*.25f,25*d,muted,true)
        }
        if(showXLabels&&plot.yMinimum<0&&plot.yMaximum>0){
            val zero=y(0.0)
            stroke.color=ColorUtils.setAlphaComponent(ink,105);stroke.pathEffect=null
            c.drawLine(rect.left,zero,rect.right,zero,stroke)
            if(abs(zero-rect.centerY())>text.fontSpacing*1.3f)
                label(c,"0",rect.left-3*d,zero+text.fontSpacing*.25f,25*d,muted,true)
        }
        if(showXLabels){
            label(c,fmt(plot.xMinimum),rect.left,rect.bottom+12*d,35*d,muted)
            label(c,"${fmt(plot.xMaximum)} ${plot.xLabel}",rect.right,rect.bottom+12*d,max(30*d,rect.width()*.7f),muted,true)
            if(rect.width()>115*d){
                val middle=if(plot.logarithmicX&&plot.xMinimum>0)sqrt(plot.xMinimum*plot.xMaximum)
                    else(plot.xMinimum+plot.xMaximum)/2
                val caption=fmt(middle);val half=text.measureText(caption)/2
                val leftEnd=rect.left+text.measureText(fmt(plot.xMinimum))+4*d
                val rightStart=rect.right-text.measureText("${fmt(plot.xMaximum)} ${plot.xLabel}")-4*d
                if(rect.centerX()-half>leftEnd&&rect.centerX()+half<rightStart){
                    text.textAlign=Paint.Align.CENTER;text.color=muted
                    c.drawText(caption,rect.centerX(),rect.bottom+12*d,text)
                }
            }
        }
        if(showUnit&&plot.yLabel.isNotBlank())label(c,plot.yLabel,rect.left+2*d,rect.top-3*d,rect.width(),muted)
    }
    private fun drawCurves(c:Canvas){
        val legends=plot.curves.take(if(plot.curves.size==3)3 else 2).map {it.label to color(it.colorIndex)}.ifEmpty {
            plot.regions.take(2).map {it.label to color(it.colorIndex)}
        }.filter {it.first.isNotBlank()}
        if(legends.isNotEmpty()){
            rect.top+=text.fontSpacing+3*d
            val slot=rect.width()/legends.size
            legends.forEachIndexed {i,(value,tint)->
                label(c,value,rect.left+i*slot,rect.top-4*d,slot-3*d,tint)
            }
            if(plot.yLabel.isNotBlank())label(c,plot.yLabel,rect.left-3*d,rect.top-4*d,25*d,muted,true)
        }
        if(rect.height()<=text.fontSpacing)return
        axes(c,showUnit=legends.isEmpty())
        c.save();c.clipRect(rect)
        plot.regions.forEach {r->
            fill.color=color(r.colorIndex,20)
            c.drawRect(x(r.start).coerceIn(rect.left,rect.right),rect.top,x(r.end).coerceIn(rect.left,rect.right),rect.bottom,fill)
        }
        plot.curves.forEach {curve->
            stroke.color=color(curve.colorIndex);stroke.pathEffect=if(curve.dashed||plot.inactive)dashed else null
            path.rewind();var joined=false
            curve.points.forEach {p->
                if(!p.x.isFinite()||!p.y.isFinite()||plot.logarithmicX&&p.x<=0){joined=false;return@forEach}
                val xx=x(p.x);val yy=y(p.y)
                if(!xx.isFinite()||!yy.isFinite()){joined=false;return@forEach}
                if(joined)path.lineTo(xx,yy)else{path.moveTo(xx,yy);joined=true}
            }
            c.drawPath(path,stroke);stroke.pathEffect=null
        }
        plot.marks.forEachIndexed { index,m->if(m.x.isFinite()&&m.y.isFinite()){
            val xx=x(m.x);val yy=y(m.y)
            if(plot.style==LiveprogPlotStyle.TIMELINE){
                stroke.color=color(m.colorIndex,115);stroke.pathEffect=null
                c.drawLine(xx,y(0.0.coerceIn(plot.yMinimum,plot.yMaximum)),xx,yy,stroke)
            }
            fill.color=color(m.colorIndex);c.drawCircle(xx,yy,2.5f*d,fill)
            if(index<4&&m.label.isNotBlank())label(c,m.label,(xx+3*d).coerceIn(rect.left,max(rect.left,rect.right-24*d)),
                (yy-4*d).coerceIn(rect.top+10*d,rect.bottom-2*d),min(42*d,rect.width()*.35f),color(m.colorIndex))
        }}
        c.restore()
        if(plot.curves.isEmpty()&&plot.regions.isEmpty()&&plot.marks.isEmpty())label(c,"参数／作用示意",rect.left+3*d,rect.centerY(),rect.width()-6*d,muted)
    }
    private fun drawBars(c:Canvas){
        axes(c,false)
        val bars=plot.bars
        if(bars.isEmpty())return
        val step=rect.width()/bars.size
        c.save();c.clipRect(rect)
        val baseline=y(0.0.coerceIn(plot.yMinimum,plot.yMaximum)).coerceIn(rect.top,rect.bottom)
        bars.forEachIndexed {i,b->
            val yy=y(b.value).coerceIn(rect.top,rect.bottom)
            fill.color=if(b.inactive)ColorUtils.setAlphaComponent(ink,65)else color(b.colorIndex,165)
            c.drawRoundRect(rect.left+step*(i+.16f),min(yy,baseline),rect.left+step*(i+.84f),max(yy,baseline)+.8f*d,2*d,2*d,fill)
        }
        c.restore()
        val metrics=text.fontMetrics
        val lineHeight=metrics.bottom-metrics.top
        val lineStep=lineHeight+2*d
        val minTop=rect.top+d
        val maxTop=max(minTop,rect.bottom-lineHeight-d)
        val occupied=mutableListOf<RectF>()
        // Place full numbers near their bars. Search other rows when adjacent captions overlap.
        // Clamping the whole caption keeps both signs and descenders visible at either limit.
        bars.forEachIndexed {i,b->
            val yy=y(b.value).coerceIn(rect.top,rect.bottom)
            val value=barNumber(b.value,max(d,step*(if(bars.size>4)2f else 1f)-2*d))
            val captionWidth=text.measureText(value)
            val cx=if(captionWidth>=rect.width())rect.centerX()else
                (rect.left+step*(i+.5f)).coerceIn(rect.left+captionWidth/2,rect.right-captionWidth/2)
            val preferred=(if(yy<baseline)yy-lineHeight-3*d else yy+3*d).coerceIn(minTop,maxTop)
            val candidates=mutableListOf(preferred,maxTop)
            repeat(ceil((maxTop-minTop)/lineStep).toInt()+1){row->
                candidates.add((minTop+row*lineStep).coerceAtMost(maxTop))
            }
            var chosen=preferred
            var leastOverlap=Float.POSITIVE_INFINITY
            var shortestMove=Float.POSITIVE_INFINITY
            candidates.forEach {top->
                val left=cx-captionWidth/2-d;val right=cx+captionWidth/2+d
                val overlap=occupied.sumOf {other->
                    (max(0f,min(right,other.right)-max(left,other.left))*
                        max(0f,min(top+lineHeight+d,other.bottom)-max(top-d,other.top))).toDouble()
                }.toFloat()
                val move=abs(top-preferred)
                if(overlap<leastOverlap||overlap==leastOverlap&&move<shortestMove){
                    chosen=top;leastOverlap=overlap;shortestMove=move
                }
            }
            val bounds=RectF(cx-captionWidth/2-d,chosen-d,cx+captionWidth/2+d,chosen+lineHeight+d)
            occupied.add(bounds)
            if(abs(chosen-preferred)>lineHeight/2){
                stroke.color=if(b.inactive)muted else color(b.colorIndex,110);stroke.pathEffect=null
                c.drawLine(rect.left+step*(i+.5f),yy,cx,chosen+lineHeight/2,stroke)
            }
            fill.color=ColorUtils.setAlphaComponent(surface,235)
            c.drawRoundRect(bounds,d,d,fill)
            text.color=if(b.inactive)muted else color(b.colorIndex);text.textAlign=Paint.Align.CENTER
            c.drawText(value,cx,chosen-metrics.top,text)
        }
        bars.forEachIndexed {i,b->
            text.color=if(b.inactive)muted else color(b.colorIndex);text.textAlign=Paint.Align.CENTER
            c.drawText((i+1).toString(),rect.left+step*(i+.5f),rect.bottom+12*d,text)
        }
    }
    private fun drawRouting(c:Canvas){
        fun nx(v:Double)=rect.left+(v.coerceIn(0.0,1.0)*rect.width()).toFloat()
        fun ny(v:Double)=rect.top+(v.coerceIn(0.0,1.0)*rect.height()).toFloat()
        val columnNodes=plot.nodes.groupBy {(it.x.coerceIn(0.0,1.0)*1000).roundToInt()}
        val columns=columnNodes.keys.sorted()
        val rowsByColumn=columnNodes.values.map {nodes->
            nodes.map {(it.y.coerceIn(0.0,1.0)*1000).roundToInt()}.distinct().sorted()
        }
        val columnCount=columns.size.coerceAtLeast(1)
        val rowCount=(rowsByColumn.maxOfOrNull {it.size}?:1).coerceAtLeast(1)
        val horizontalSpacing=columns.zipWithNext {a,b->(b-a)/1000f*rect.width()}.minOrNull()?:rect.width()
        // Nodes in separate columns may intentionally be staggered; only same-column rows compete.
        val verticalSpacing=rowsByColumn.flatMap {rows->
            rows.zipWithNext {a,b->(b-a)/1000f*rect.height()}
        }.minOrNull()?:rect.height()
        val widthCap=if(columnCount==1)rect.width()*.85f else
            min(rect.width()*.39f,min(rect.width()/columnCount,horizontalSpacing)-4*d)
        val boxWidth=min(72*d,widthCap).coerceAtLeast(min(4*d,rect.width()))
        val lineHeight=text.fontMetrics.bottom-text.fontMetrics.top
        val heightCap=if(rowCount==1)rect.height() else
            min(rect.height()*.45f,min(rect.height()/rowCount,verticalSpacing)-3*d)
        val boxHeight=min(2*lineHeight+6*d,heightCap).coerceAtLeast(min(4*d,rect.height()))
        val boxes=plot.nodes.associate {node->
            val xx=nx(node.x).coerceIn(rect.left+boxWidth/2,rect.right-boxWidth/2)
            val yy=ny(node.y).coerceIn(rect.top+boxHeight/2,rect.bottom-boxHeight/2)
            node.id to RectF(xx-boxWidth/2,yy-boxHeight/2,xx+boxWidth/2,yy+boxHeight/2)
        }
        fun edgePoint(box:RectF,toward:RectF):Pair<Float,Float>{
            val dx=toward.centerX()-box.centerX();val dy=toward.centerY()-box.centerY()
            val xRatio=if(abs(dx)>1e-4f)box.width()/(2*abs(dx))else Float.POSITIVE_INFINITY
            val yRatio=if(abs(dy)>1e-4f)box.height()/(2*abs(dy))else Float.POSITIVE_INFINITY
            val scale=min(xRatio,yRatio)
            return if(scale.isFinite())box.centerX()+dx*scale to box.centerY()+dy*scale
                else box.centerX() to box.centerY()
        }
        c.save();c.clipRect(rect)
        plot.edges.forEach {edge->
            val a=boxes[edge.from]?:return@forEach;val b=boxes[edge.to]?:return@forEach
            val (ax,ay)=edgePoint(a,b);val(bx,by)=edgePoint(b,a)
            if(!ax.isFinite()||!ay.isFinite()||!bx.isFinite()||!by.isFinite())return@forEach
            if(hypot(b.centerX()-a.centerX(),b.centerY()-a.centerY())<max(a.width(),a.height())*.6f)return@forEach
            stroke.color=color(edge.colorIndex,145);stroke.pathEffect=if(edge.dashed||plot.inactive)dashed else null
            // Finish at the node border so the arrow is never painted over by its box.
            arrow(c,ax,ay,bx,by,color(edge.colorIndex,185),dash=edge.dashed||plot.inactive)
        }
        plot.nodes.forEach {node->
            val box=boxes[node.id]?:return@forEach
            fill.color=surface;c.drawRoundRect(box,4*d,4*d,fill)
            fill.color=color(node.colorIndex,25);c.drawRoundRect(box,4*d,4*d,fill)
            twoLines(c,node.label,box,color(node.colorIndex))
        }
        c.restore()
        if(plot.nodes.isEmpty())drawTextPlot(c)
    }
    private fun drawStereo(c:Canvas){
        val diagram=plot.stereoDiagram
        if(diagram==null){
            // Unknown scripts keep their declared coordinate data; no invented circular sound field.
            when{
                plot.nodes.isNotEmpty()->drawRouting(c)
                plot.curves.isNotEmpty()->{rect.left=min(28*d,rect.right-10*d);drawCurves(c)}
                else->drawTextPlot(c)
            }
            return
        }
        val metrics=text.fontMetrics
        val lineHeight=metrics.bottom-metrics.top
        val legendHeight=lineHeight+2*d
        val barsHeight=2*(lineHeight+4*d)
        val graph=RectF(rect.left+8*d,rect.top+legendHeight,rect.right-8*d,
            height-3*d-barsHeight-3*d)
        if(graph.height()<8*d)return
        val limit=diagram.maximum.takeIf {it.isFinite()&&it>0}?:2.0
        val cx=graph.centerX();val cy=graph.centerY()
        val rx=(graph.width()/2-3*d).coerceAtLeast(d)
        val ry=(graph.height()/2-2*d).coerceAtLeast(d)
        val emphasis=diagram.emphasis
        val referenceColor=ColorUtils.setAlphaComponent(ink,if(plot.inactive)65 else 115)
        fun highlighted(label:String):Boolean=when(emphasis){
            "center","mid"->label.contains("中央")||label.contains("Mid",true)
            "width","side"->label.contains("侧")||label.contains("Side",true)
            "balance"->false
            else->true
        }
        fun endpoint(side:Double,mid:Double):Pair<Float,Float>{
            val magnitude=max(abs(side),abs(mid))
            // Clip a vector by one common ratio, preserving its signed direction at the boundary.
            val clipped=if(magnitude>limit)limit/magnitude else 1.0
            return cx+(side*clipped/limit*rx).toFloat() to cy-(mid*clipped/limit*ry).toFloat()
        }
        // The named probe legend belongs outside the drawing area, not on top of its vectors.
        val probeSlots=diagram.probes.take(2)
        val referenceLabel=diagram.referenceLabel
        val referenceWidth=(text.measureText("灰$referenceLabel")+3*d).coerceAtMost(rect.width()*.38f)
        val slot=(rect.width()-referenceWidth)/max(1,probeSlots.size)
        probeSlots.forEachIndexed {i,probe->
            label(c,probe.label,rect.left+i*slot,rect.top-metrics.top,slot-3*d,
                color(probe.colorIndex,if(highlighted(probe.label))235 else 85))
        }
        label(c,"灰$referenceLabel",rect.right,rect.top-metrics.top,referenceWidth,referenceColor,true)
        c.drawLine(cx-rx,cy,cx+rx,cy,grid);c.drawLine(cx,cy-ry,cx,cy+ry,grid)
        label(c,"共同↑  差异→ · ±${barNumber(limit,25*d)}×",graph.left,graph.top-metrics.top,graph.width(),muted)
        // One-unit reference ticks retain a constant, model-declared scale while parameters change.
        val unitX=(rx/limit).toFloat();val unitY=(ry/limit).toFloat()
        listOf(-1,1).forEach {sign->
            c.drawLine(cx+sign*unitX,cy-2*d,cx+sign*unitX,cy+2*d,grid)
            c.drawLine(cx-2*d,cy+sign*unitY,cx+2*d,cy+sign*unitY,grid)
        }
        c.save();c.clipRect(graph)
        probeSlots.forEach {probe->
            if(probe.referenceSide.isFinite()&&probe.referenceMid.isFinite()){
                val(ex,ey)=endpoint(probe.referenceSide,probe.referenceMid)
                arrow(c,cx,cy,ex,ey,referenceColor,2*d,true)
                stroke.color=referenceColor;stroke.pathEffect=null
                c.drawCircle(ex,ey,2.8f*d,stroke)
                if(probe===probeSlots.getOrNull(1)){
                    val(mx,my)=endpoint(-probe.referenceSide,-probe.referenceMid)
                    arrow(c,cx,cy,mx,my,ColorUtils.setAlphaComponent(referenceColor,65),d,true)
                }
            }
        }
        probeSlots.forEachIndexed {index,probe->
            if(!probe.side.isFinite()||!probe.mid.isFinite())return@forEachIndexed
            val(ex,ey)=endpoint(probe.side,probe.mid)
            val tint=color(probe.colorIndex,if(highlighted(probe.label))235 else 75)
            val length=hypot(ex-cx,ey-cy)
            if(index==0){
                // Central reference input is a strong pillar. Rotation may tilt it; no physical angle is claimed.
                arrow(c,cx,cy,ex,ey,tint,if(highlighted(probe.label))4*d else 2*d,plot.inactive)
            }else{
                // The side reference is a filled wing pointing in its actual signed M/S direction.
                if(length>d){
                    val dx=(ex-cx)/length;val dy=(ey-cy)/length
                    val wing=max(1.5f*d,min(4*d,length*.25f))
                    fill.color=ColorUtils.setAlphaComponent(tint,min(Color.alpha(tint),if(highlighted(probe.label))100 else 35))
                    path.rewind();path.moveTo(cx-dy*wing,cy+dx*wing);path.lineTo(ex,ey)
                    path.lineTo(cx+dy*wing,cy-dx*wing);path.close();c.drawPath(path,fill)
                    // The opposite input polarity follows the same linear matrix. Keep it a faint
                    // outline so the solid, named positive reference still reveals sign inversion.
                    val(mx,my)=endpoint(-probe.side,-probe.mid)
                    stroke.color=ColorUtils.setAlphaComponent(tint,min(Color.alpha(tint),if(highlighted(probe.label))105 else 45))
                    stroke.pathEffect=if(plot.inactive)dashed else null
                    path.rewind();path.moveTo(cx-dy*wing,cy+dx*wing);path.lineTo(mx,my)
                    path.lineTo(cx+dy*wing,cy-dx*wing);path.close();c.drawPath(path,stroke);stroke.pathEffect=null
                }
                arrow(c,cx,cy,ex,ey,tint,2*d,plot.inactive)
            }
        }
        c.restore()
        fill.color=referenceColor;c.drawCircle(cx,cy,1.5f*d,fill)
        // Only exceptional values need another label; normal values are in the subtitle and details.
        val exceptions=probeSlots.mapNotNull {probe->
            val out=max(abs(probe.side),abs(probe.mid))>limit
            when{
                !probe.side.isFinite()||!probe.mid.isFinite()->"${probe.label} —"
                out->{
                    val component=if(abs(probe.side)>=abs(probe.mid))probe.side else probe.mid
                    "${probe.label} ${barNumber(component,30*d)}×"
                }
                probe.side==0.0&&probe.mid==0.0->"${probe.label} 0"
                else->null
            }
        }
        if(exceptions.isNotEmpty()){
            val space=graph.width()/exceptions.size
            exceptions.forEachIndexed {i,value->
                label(c,value,graph.left+i*space,graph.bottom-metrics.bottom,space-2*d,muted)
            }
        }
        val gainStart=graph.bottom+3*d
        val gainRowHeight=lineHeight+4*d
        fun gainRow(name:String,value:Double,reference:Double,index:Int){
            val top=gainStart+gainRowHeight*index
            val base=top-metrics.top
            val activeGain=emphasis=="balance"||emphasis=="rotation"||emphasis=="all"
            val tint=color(index,if(activeGain)235 else 145)
            val caption=if(value.isFinite())barNumber(value,45*d)+"×"else"—"
            val valueWidth=text.measureText(caption)+3*d
            label(c,name,rect.left,base,11*d,tint)
            val left=rect.left+13*d;val right=max(left+d,rect.right-valueWidth-2*d)
            val yy=top+lineHeight/2
            stroke.color=ColorUtils.setAlphaComponent(ink,30);stroke.pathEffect=null
            val old=stroke.strokeWidth;stroke.strokeWidth=3*d;c.drawLine(left,yy,right,yy,stroke);stroke.strokeWidth=old
            // These are matrix row lengths, so the gain bars use their full width for 0…limit.
            val zero=left
            c.drawLine(zero,yy-3*d,zero,yy+3*d,grid)
            fun gainX(gain:Double)=zero+(gain.coerceIn(0.0,limit)/limit*(right-left)).toFloat()
            if(reference.isFinite()){
                stroke.color=referenceColor
                val xx=gainX(reference);c.drawLine(xx,yy-4*d,xx,yy+4*d,stroke)
            }
            if(value.isFinite()){
                val xx=gainX(value)
                stroke.color=tint;stroke.strokeWidth=3*d;c.drawLine(zero,yy,xx,yy,stroke);stroke.strokeWidth=old
                fill.color=tint;c.drawCircle(xx,yy,1.8f*d,fill)
                if(abs(value)>limit)arrow(c,xx-(if(value>0)5*d else -5*d),yy,xx,yy,tint)
            }
            label(c,caption,rect.right,base,valueWidth,tint,true)
        }
        gainRow("左",diagram.leftGain,diagram.referenceLeftGain,0)
        gainRow("右",diagram.rightGain,diagram.referenceRightGain,1)
    }
    private fun drawTextPlot(c:Canvas){
        val values=plot.bars.map {"${it.label}  ${fmt(it.value)}"}.ifEmpty {plot.curves.map {it.label}}.ifEmpty {plot.notes}
        values.take(5).forEachIndexed {i,value->
            label(c,value,rect.left+4*d,rect.top+(i+1)*min(20*d,rect.height()/max(1,min(values.size,5))),rect.width()-8*d,color(i))
        }
    }
    private fun details():String=buildString {
        if(plot.subtitle.isNotBlank())append(plot.subtitle+"\n\n")
        val numbered=plot.style==LiveprogPlotStyle.BARS||plot.style==LiveprogPlotStyle.PROBABILITY
        if(numbered)append("横轴：项目序号 1…${plot.bars.size}；纵轴 ${plot.yLabel}：${plot.yMinimum}…${plot.yMaximum}\n\n")
        else if(plot.xLabel.isNotBlank()||plot.yLabel.isNotBlank())append("横轴 ${plot.xLabel}：${plot.xMinimum}…${plot.xMaximum}；纵轴 ${plot.yLabel}：${plot.yMinimum}…${plot.yMaximum}\n\n")
        plot.curves.forEach {append(it.label+"\n")}
        plot.bars.forEachIndexed {i,bar->append("${if(numbered)"${i+1}. "else ""}${bar.label}：${bar.value}${if(bar.inactive)"（当前不参与）" else ""}\n")}
        plot.regions.forEach {append("${it.label}：${it.start}…${it.end}\n")}
        plot.nodes.forEach {append(it.label+"\n")}
        plot.edges.filter {it.label.isNotBlank()}.forEach {append("${it.from} → ${it.to}：${it.label}\n")}
        plot.marks.filter {it.label.isNotBlank()}.forEach {append("${it.label}：${it.x} / ${it.y}\n")}
        plot.stereoDiagram?.let {diagram->
            append("\n参考声道比例图：灰色为${diagram.referenceLabel}，彩色为当前参数。固定显示范围 ±${diagram.maximum}×，超出范围保留方向并以箭头标记；不代表扬声器角度、房间距离或音乐瞬时能量。\n")
            diagram.probes.forEach {probe->
                append("${probe.label}参考输入：${diagram.referenceLabel} Side ${probe.referenceSide} / Mid ${probe.referenceMid}；当前 Side ${probe.side} / Mid ${probe.mid}。负号表示参考分量方向翻转，零表示该参考输入被抵消。\n")
            }
            append("两侧探针的实心翼和箭头表示命名参考输入的实际方向；淡轮廓翼为该输入反向极性在线性模型中的响应。两翼不是物理左右声源位置。\n")
            append("L 参考输出增益：${diagram.referenceLabel} ${diagram.referenceLeftGain}× → 当前 ${diagram.leftGain}×\n")
            append("R 参考输出增益：${diagram.referenceLabel} ${diagram.referenceRightGain}× → 当前 ${diagram.rightGain}×\n")
            append("L/R 条表示模型参考输入的输出增益；灰刻线为${diagram.referenceLabel}，不代表真实音乐电平。当前高亮 ${diagram.emphasis}；其余参照保持可见。\n")
        }
        if(plot.edges.any {it.dashed})append("\n路由虚线保留模型定义的控制、反向或间接支路语义；不是声音传播距离。\n")
        if(plot.inactive)append("\n当前音效关闭：显示保存参数的模型预览。\n")
        append("\n"+plot.notes.joinToString("\n"))
    }
}
