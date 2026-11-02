package me.timschneeberger.rootlessjamesdsp.effectvisuals

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.liveprog.*
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import java.util.Locale
import kotlin.math.*

/** Geometry may adapt to its panel. Text never shares a geometry scale or a TextPaint density cap. */
internal open class EffectVisualView(context: Context, private val textSp: Float = 11f) : View(context) {
    private val dpScale = resources.displayMetrics.density
    private val fontScale = resources.displayMetrics.scaledDensity
    private val ink = MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
    private val surface = MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface)
    private val primary = MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary)
    private val dark = ColorUtils.calculateLuminance(surface)<.5
    private val colors = listOf(primary,if(dark)Color.rgb(104,216,236)else Color.rgb(16,124,146),
        if(dark)Color.rgb(255,198,118)else Color.rgb(169,102,22),
        if(dark)Color.rgb(169,208,157)else Color.rgb(70,128,61),
        if(dark)Color.rgb(233,162,192)else Color.rgb(159,74,114))
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSp * fontScale
        fontFeatureSettings="tnum"
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val area = RectF()
    private val dash = DashPathEffect(floatArrayOf(4*dpScale,3*dpScale),0f)
    private var visual = EffectVisual("等待参数")
    private var active = false
    private var status: String? = null
    val minimumReadableHeight: Int get() {
        val legendRows=visual.plot?.curves?.map {it.label}?.filter {it.isNotBlank()}?.distinct()?.size?.coerceAtMost(3) ?: 0
        return ceil(text.fontSpacing*(10+legendRows)+24*dpScale).toInt()
    }
    init {
        isLongClickable=true
        setOnLongClickListener {
            GlassAlertDialogBuilder(context).setTitle(visual.title).setMessage(details())
                .setPositiveButton(android.R.string.ok,null).show();true
        }
    }
    fun setVisual(value: EffectVisual) {
        val oldHeight=minimumReadableHeight
        visual=value;contentDescription=details()
        if(oldHeight!=minimumReadableHeight)requestLayout()
        redraw()
    }
    fun setActive(value: Boolean) { active=value;if(value)invalidate() }
    fun setStatus(value: String?) { status=value;contentDescription=details();redraw() }
    private fun redraw() { if(active&&isAttachedToWindow&&isShown)postInvalidateOnAnimation() }
    private fun details()=(listOf(visual.title,visual.subtitle)+visual.notes+visual.plot?.notes.orEmpty()+listOfNotNull(status)).filter{it.isNotBlank()}.joinToString("\n\n")
    private fun tint(index:Int,alpha:Double=1.0):Int {
        val color=if(index<0)ink else colors[Math.floorMod(index,colors.size)]
        // Disabled diagrams remain legible: these are editable parameter previews, not disabled text controls.
        return ColorUtils.setAlphaComponent(color,(255*alpha.coerceIn(0.0,1.0)*(if(visual.inactive).75 else 1.0)).toInt())
    }
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,color:Int,width:Double=1.5,dashed:Boolean=false,arrow:Boolean=false) {
        if(!listOf(x1,y1,x2,y2).all{it.isFinite()})return
        stroke.color=color;stroke.strokeWidth=width.toFloat()*dpScale;stroke.pathEffect=if(dashed)dash else null
        c.drawLine(x1,y1,x2,y2,stroke);stroke.pathEffect=null
        if(arrow) { val length=hypot(x2-x1,y2-y1);if(length>dpScale) {
            val ux=(x2-x1)/length;val uy=(y2-y1)/length;val head=min(6*dpScale,length*.3f)
            c.drawLine(x2-ux*head-uy*head*.5f,y2-uy*head+ux*head*.5f,x2,y2,stroke)
            c.drawLine(x2-ux*head+uy*head*.5f,y2-uy*head-ux*head*.5f,x2,y2,stroke)
        }}
    }
    private fun label(c:Canvas,value:String,x:Float,y:Float,room:Float,color:Int=ink,align:Paint.Align=Paint.Align.LEFT,bold:Boolean=false) {
        if(room<=0||!x.isFinite()||!y.isFinite())return
        text.color=color;text.textAlign=align;text.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        c.drawText(TextUtils.ellipsize(value,text,room,TextUtils.TruncateAt.END).toString(),x,y,text)
        text.typeface=Typeface.DEFAULT
    }
    private fun wrapped(c:Canvas,value:String,bounds:RectF,color:Int=ink,maxLines:Int=2) {
        if(bounds.width()<=0||bounds.height()<text.fontSpacing)return
        text.typeface=Typeface.DEFAULT
        val lines=ArrayList<String>();var rest=value
        val count=min(maxLines,(bounds.height()/text.fontSpacing).toInt()).coerceAtLeast(1)
        repeat(count) { index->
            if(rest.isNotEmpty()) {
                if(index==count-1){lines+=TextUtils.ellipsize(rest,text,bounds.width(),TextUtils.TruncateAt.END).toString();rest=""}
                else {val n=text.breakText(rest,true,bounds.width(),null).coerceAtLeast(1);lines+=rest.take(n);rest=rest.drop(n).trimStart()}
            }
        }
        val first=bounds.centerY()-lines.size*text.fontSpacing/2-text.fontMetrics.top
        lines.forEachIndexed{i,s->label(c,s,bounds.centerX(),first+i*text.fontSpacing,bounds.width(),color,Paint.Align.CENTER)}
    }
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        val row=text.fontSpacing+2*dpScale
        val top=if(visual.subtitle.isBlank())4*dpScale else row*2
        if(visual.subtitle.isNotBlank()) wrapped(c,visual.subtitle,RectF(2*dpScale,0f,width-2*dpScale,top),tint(0),2)
        area.set(4*dpScale,top,width-4*dpScale,height-4*dpScale)
        if(area.width()<10*dpScale||area.height()<row*2)return
        visual.scene?.let { drawScene(c,it,area) } ?: visual.plot?.let {drawPlot(c,it,area)}
        status?.takeIf{it.isNotBlank()}?.let {
            val box=RectF(area.left+4*dpScale,area.centerY()-row*1.7f,area.right-4*dpScale,area.centerY()+row*1.7f)
            fill.color=ColorUtils.setAlphaComponent(surface,235);c.drawRoundRect(box,6*dpScale,6*dpScale,fill)
            wrapped(c,it,box,ink,3)
        }
    }
    private fun drawScene(c:Canvas,scene:EffectScene,b:RectF) {
        fun x(v:Double)=b.left+v.toFloat()*b.width()
        fun y(v:Double)=b.top+v.toFloat()*b.height()
        c.save();c.clipRect(b)
        scene.elements.forEach { e->when(e) {
            is FigureLine -> line(c,x(e.x1),y(e.y1),x(e.x2),y(e.y2),tint(e.colorIndex,e.alpha),e.width,e.dashed,e.arrow)
            is FigurePolyline -> {
                path.rewind();var began=false
                e.points.forEach {p->if(p.x.isFinite()&&p.y.isFinite()){if(!began){path.moveTo(x(p.x),y(p.y));began=true}else path.lineTo(x(p.x),y(p.y))}else began=false}
                if(e.filled){path.close();fill.color=tint(e.colorIndex,e.alpha);c.drawPath(path,fill)}
                else {stroke.color=tint(e.colorIndex,e.alpha);stroke.strokeWidth=e.width.toFloat()*dpScale;stroke.pathEffect=if(e.dashed)dash else null;c.drawPath(path,stroke);stroke.pathEffect=null}
            }
            is FigureBox -> {
                val box=RectF(x(e.left),y(e.top),x(e.right),y(e.bottom))
                if(e.filled){fill.color=tint(e.colorIndex,e.alpha);if(e.rounded)c.drawRoundRect(box,5*dpScale,5*dpScale,fill)else c.drawRect(box,fill)}
                stroke.color=tint(e.colorIndex);stroke.strokeWidth=1.2f*dpScale
                if(e.rounded)c.drawRoundRect(box,5*dpScale,5*dpScale,stroke)else c.drawRect(box,stroke)
                if(e.label.isNotEmpty()){box.inset(3*dpScale,0f);wrapped(c,e.label,box,tint(e.colorIndex),2)}
            }
            is FigureCircle -> {
                val box=RectF(x(e.x-e.rx),y(e.y-e.ry),x(e.x+e.rx),y(e.y+e.ry))
                if(e.filled){fill.color=tint(e.colorIndex,e.alpha);c.drawOval(box,fill)}
                else {stroke.color=tint(e.colorIndex,e.alpha);stroke.strokeWidth=1.5f*dpScale;c.drawOval(box,stroke)}
                if(e.label.isNotEmpty()) {
                    val labelWidth=max(box.width(),text.measureText(e.label)+4*dpScale).coerceAtMost(b.width())
                    val labelHeight=max(box.height(),text.fontSpacing*1.2f)
                    val labelBox=RectF(box.centerX()-labelWidth/2,box.centerY()-labelHeight/2,box.centerX()+labelWidth/2,box.centerY()+labelHeight/2)
                    if(labelBox.left<b.left)labelBox.offset(b.left-labelBox.left,0f)
                    if(labelBox.right>b.right)labelBox.offset(b.right-labelBox.right,0f)
                    if(labelBox.top<b.top)labelBox.offset(0f,b.top-labelBox.top)
                    if(labelBox.bottom>b.bottom)labelBox.offset(0f,b.bottom-labelBox.bottom)
                    wrapped(c,e.label,labelBox,tint(e.colorIndex),2)
                }
            }
            is FigureArc -> {stroke.color=tint(e.colorIndex,e.alpha);stroke.strokeWidth=e.width.toFloat()*dpScale;c.drawArc(RectF(x(e.x-e.rx),y(e.y-e.ry),x(e.x+e.rx),y(e.y+e.ry)),e.startDegrees.toFloat(),e.sweepDegrees.toFloat(),false,stroke)}
            is FigureLabel -> {
                val align=when(e.align){FigureAlign.LEFT->Paint.Align.LEFT;FigureAlign.CENTER->Paint.Align.CENTER;FigureAlign.RIGHT->Paint.Align.RIGHT}
                val xx=x(e.x);val raw=e.width.toFloat()*b.width()
                val room=min(raw,when(e.align){FigureAlign.LEFT->b.right-xx;FigureAlign.RIGHT->xx-b.left;FigureAlign.CENTER->2*min(xx-b.left,b.right-xx)})
                val baseline=(y(e.y)-(text.fontMetrics.ascent+text.fontMetrics.descent)/2)
                    .coerceIn(b.top-text.fontMetrics.ascent,b.bottom-text.fontMetrics.descent)
                label(c,e.text,xx,baseline,room,tint(e.colorIndex),align,e.emphasis)
            }
        }}
        c.restore()
    }
    private fun number(v:Double):String = when {
        !v.isFinite()->"—"
        v==0.0->"0"
        abs(v)>=1000->String.format(Locale.ROOT,"%.1fk",v/1000).replace(".0k","k")
        abs(v)>=10->String.format(Locale.ROOT,"%.0f",v)
        abs(v)>=1->String.format(Locale.ROOT,"%.1f",v)
        else->String.format(Locale.ROOT,"%.2f",v)
    }
    private fun drawPlot(c:Canvas,p:LiveprogStaticPlot,b:RectF) {
        val row=text.fontSpacing+2*dpScale
        var top=b.top
        val legends=p.curves.map {it.label to it.colorIndex}.filter{it.first.isNotBlank()}.distinct()
        // Reserve readable space for up to three legends instead of compressing the plot beneath them.
        legends.take(3).forEach { (name,index)->
            line(c,b.left,top+row*.45f,b.left+10*dpScale,top+row*.45f,tint(index),2.0)
            label(c,name,b.left+14*dpScale,top-text.fontMetrics.top,b.width()-14*dpScale,tint(index));top+=row
        }
        val plotTop=top+row*1.5f
        val plotBottom=b.bottom-row*1.8f
        val ticks=if(plotBottom-plotTop<row*5)3 else 4
        val yValues=List(ticks){i->p.yMinimum+(p.yMaximum-p.yMinimum)*i/(ticks-1)}
        // Intermediate labels such as 0.50 can be wider than both endpoints (0, 1.0).
        // Measure every label we will draw, with one extra dp of edge clearance.
        val yDigits=yValues.maxOf {text.measureText(number(it))}
        val left=b.left+max(27*dpScale,yDigits+7*dpScale)
        val plot=RectF(left,plotTop,b.right-2*dpScale,plotBottom)
        if(plot.width()<20*dpScale||plot.height()<row)return
        label(c,p.yLabel,b.left,top-text.fontMetrics.top,b.width(),ColorUtils.setAlphaComponent(ink,210))
        fun xx(v:Double):Float {val f=if(p.logarithmicX&&p.xMinimum>0&&v>0)ln(v/p.xMinimum)/ln(p.xMaximum/p.xMinimum)else (v-p.xMinimum)/(p.xMaximum-p.xMinimum);return plot.left+f.toFloat()*plot.width()}
        fun yy(v:Double)=plot.bottom-((v-p.yMinimum)/(p.yMaximum-p.yMinimum)).toFloat()*plot.height()
        yValues.forEach {value->val y=yy(value)
            line(c,plot.left,y,plot.right,y,ColorUtils.setAlphaComponent(ink,35),.7)
            label(c,number(value),plot.left-4*dpScale,y-(text.fontMetrics.ascent+text.fontMetrics.descent)/2,plot.left-b.left-5*dpScale,ink,Paint.Align.RIGHT)}
        if(p.bars.isEmpty())repeat(3){i->val value=if(p.logarithmicX&&p.xMinimum>0)p.xMinimum*(p.xMaximum/p.xMinimum).pow(i/2.0)else p.xMinimum+(p.xMaximum-p.xMinimum)*i/2
            val x=xx(value);line(c,x,plot.top,x,plot.bottom,ColorUtils.setAlphaComponent(ink,35),.7)
            val align=if(i==0)Paint.Align.LEFT else if(i==2)Paint.Align.RIGHT else Paint.Align.CENTER
            label(c,number(value),x,plot.bottom+row-text.fontMetrics.descent,plot.width()*.46f,ink,align)}
        label(c,p.xLabel,plot.right,b.bottom-text.fontMetrics.descent,plot.width(),ink,Paint.Align.RIGHT)
        c.save();c.clipRect(plot)
        p.regions.forEach {fill.color=tint(it.colorIndex,.09);c.drawRect(xx(it.start),plot.top,xx(it.end),plot.bottom,fill)}
        p.curves.forEach { curve->
            path.rewind();var began=false
            curve.points.forEach {point->if(point.x.isFinite()&&point.y.isFinite()){val x=xx(point.x);val y=yy(point.y);if(x.isFinite()&&y.isFinite()){if(began)path.lineTo(x,y)else{path.moveTo(x,y);began=true}}}else began=false}
            stroke.color=tint(curve.colorIndex);stroke.strokeWidth=1.8f*dpScale;stroke.pathEffect=if(curve.dashed)dash else null;c.drawPath(path,stroke);stroke.pathEffect=null
        }
        p.marks.forEach {m->val x=xx(m.x);val y=yy(m.y);if(x.isFinite()&&y.isFinite()){fill.color=tint(m.colorIndex);c.drawCircle(x,y,3*dpScale,fill)}}
        p.bars.forEachIndexed {i,bar->val slot=plot.width()/p.bars.size;val x=plot.left+i*slot;val zero=yy(0.0.coerceIn(p.yMinimum,p.yMaximum));val y=yy(bar.value)
            fill.color=tint(bar.colorIndex,if(bar.inactive).3 else .65);c.drawRect(x+slot*.15f,min(zero,y),x+slot*.85f,max(zero,y)+dpScale,fill)}
        c.restore()
        p.bars.forEachIndexed {i,bar->val slot=plot.width()/p.bars.size;label(c,bar.label,plot.left+(i+.5f)*slot,plot.bottom+row-text.fontMetrics.descent,slot-2*dpScale,tint(bar.colorIndex),Paint.Align.CENTER)}
        // Sparse annotated markers; never squeeze their labels into sub-pixel captions.
        if(p.marks.size<=4)p.marks.forEach {m->val x=xx(m.x).coerceIn(plot.left,plot.right);val y=if(plot.height()<2*row)plot.centerY()else yy(m.y).coerceIn(plot.top+row,plot.bottom-row)
            label(c,m.label,x,y-5*dpScale,min(plot.width()*.48f,2*min(x-b.left,b.right-x)),tint(m.colorIndex),Paint.Align.CENTER)}
    }
}
