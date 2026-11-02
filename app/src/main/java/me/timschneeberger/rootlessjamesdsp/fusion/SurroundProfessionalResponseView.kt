package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.*

class SurroundProfessionalResponseView(context:Context):View(context) {
    var curve:SurroundProfessionalResponse.Curve?=null
        set(v){field=v;invalidate()}
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){
        super.onDraw(c);val v=curve ?: return
        val dp=resources.displayMetrics.density;val sp=resources.displayMetrics.scaledDensity
        val foreground=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
        val primary=MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary)
        val colors=intArrayOf(primary,MaterialColors.getColor(this,com.google.android.material.R.attr.colorTertiary),0xff287ab6.toInt(),0xffbc6630.toInt())
        val left=42*dp;val right=width-12*dp;val top=58*dp;val bottom=height-26*dp
        pen.textSize=11*sp;pen.style=Paint.Style.FILL
        v.labels.forEachIndexed {i,label->pen.color=colors[i];c.drawText(label,if(i%2==0)left else (left+right)/2,18*dp+(i/2)*23*dp,pen)}
        if(bottom<=top || right<=left)return
        fun y(db:Double)=bottom-((db+24)/24).toFloat()*(bottom-top)
        for(db in listOf(-24,-18,-12,-6,0)){pen.color=foreground;pen.alpha=45;pen.strokeWidth=dp;c.drawLine(left,y(db.toDouble()),right,y(db.toDouble()),pen);pen.alpha=220;pen.textSize=9*sp;c.drawText(db.toString(),2*dp,y(db.toDouble())+3*dp,pen)}
        val high=v.frequencies.last();val span=ln(high/20)
        for(hz in listOf(20.0,100.0,1000.0,high)){val x=left+(ln(hz/20)/span).toFloat()*(right-left);pen.color=foreground;pen.alpha=50;c.drawLine(x,top,x,bottom,pen);pen.alpha=220;val label=if(hz>=1000)"${(hz/1000*10).roundToInt()/10.0}k" else hz.roundToInt().toString();val w=pen.measureText(label);c.drawText(label,(x-w/2).coerceIn(0f,width-w),height-5*dp,pen)}
        c.save();c.clipRect(left,top,right,bottom)
        v.decibels.forEachIndexed {i,values->val path=Path();values.forEachIndexed {j,db->val x=left+j.toFloat()/(values.size-1)*(right-left);if(j==0)path.moveTo(x,y(db))else path.lineTo(x,y(db))};pen.style=Paint.Style.STROKE;pen.color=colors[i];pen.alpha=255;pen.strokeWidth=(if(i<2)2f else 1.5f)*dp;c.drawPath(path,pen)}
        c.restore();pen.style=Paint.Style.FILL;pen.alpha=255
    }
}
