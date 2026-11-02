package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlin.math.*

class SurroundDiagnosticsView(context:Context):View(context){
    var result:SurroundDiagnostics.Result?=null;set(v){field=v;invalidate()}
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){
        super.onDraw(c);val v=result ?: return;val dp=resources.displayMetrics.density;val sp=resources.displayMetrics.scaledDensity
        val fg=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
        val colors=intArrayOf(MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary),MaterialColors.getColor(this,com.google.android.material.R.attr.colorTertiary),0xff287ab6.toInt(),0xffbc6630.toInt())
        val left=58*dp;val right=width-12*dp;val top=78*dp;val bottom=height-44*dp;if(right<=left || bottom<=top)return
        pen.style=Paint.Style.FILL;pen.textSize=11*sp;pen.alpha=255
        v.labels.forEachIndexed {i,label->pen.color=colors[i];c.drawText(label,if(i%2==0)left else (left+right)/2,18*dp+(i/2)*23*dp,pen)}
        pen.color=fg;pen.textSize=9*sp;c.drawText(v.unit,left,68*dp,pen)
        var low=0.0;var high=0.0;v.values.forEach {a->a.forEach {value->if(value.isFinite()){low=min(low,value);high=max(high,value)}}}
        if(high-low<1e-6){low-=1;high+=1};val pad=(high-low)*.05;low-=pad;high+=pad
        fun y(value:Double)=bottom-((value-low)/(high-low)).toFloat()*(bottom-top)
        fun x(value:Double)=left+(if(v.logarithmic)ln(value/v.x.first())/ln(v.x.last()/v.x.first()) else (value-v.x.first())/(v.x.last()-v.x.first())).toFloat()*(right-left)
        for(i in 0..4){val value=low+(high-low)*i/4;val yy=y(value);pen.color=fg;pen.alpha=45;pen.strokeWidth=dp;c.drawLine(left,yy,right,yy,pen);pen.alpha=255;pen.textSize=9*sp
            val text=String.format(Locale.ROOT,if(abs(value)>=100)"%.0f" else "%.2f",value);c.drawText(text,max(0f,left-4*dp-pen.measureText(text)),yy+3*dp,pen)}
        val ticks=if(v.logarithmic)listOf(v.x.first(),100.0,1000.0,v.x.last())else (0..4).map {v.x.last()*it/4}
        ticks.forEach {value->val xx=x(value);pen.color=fg;pen.alpha=40;c.drawLine(xx,top,xx,bottom,pen);pen.alpha=255;val text=if(v.logarithmic && value>=1000)String.format(Locale.ROOT,"%.1fk",value/1000) else String.format(Locale.ROOT,if(v.logarithmic)"%.0f" else "%.1f",value);val w=pen.measureText(text);c.drawText(text,(xx-w/2).coerceIn(0f,width-w),height-22*dp,pen)}
        pen.color=fg;pen.alpha=110;c.drawLine(left,y(0.0),right,y(0.0),pen);pen.alpha=255
        c.save();c.clipRect(left,top,right,bottom)
        v.values.forEachIndexed {i,values->val path=Path();var started=false;values.forEachIndexed {j,value->if(value.isFinite()){if(started)path.lineTo(x(v.x[j]),y(value))else {path.moveTo(x(v.x[j]),y(value));started=true}}else started=false};pen.color=colors[i];pen.alpha=255;pen.style=Paint.Style.STROKE;pen.strokeWidth=1.5f*dp;c.drawPath(path,pen)}
        c.restore();pen.style=Paint.Style.FILL;pen.color=fg;pen.textSize=9*sp;c.drawText(if(v.logarithmic)"频率 / Hz" else "时间 / ms",left,height-2*dp,pen)
    }
}
