package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import java.util.Locale
import kotlin.math.*

class CrossfeedResponseView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):View(context,attrs){
    var reading:CrossfeedAdvancedParameters.Diagnostic?=null
        set(value){if(field!=value){field=value;invalidate()}}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);val dp=resources.displayMetrics.density;val sp=resources.displayMetrics.scaledDensity
        val text=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
        val colors=listOf(MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary),
            MaterialColors.getColor(this,com.google.android.material.R.attr.colorSecondary),MaterialColors.getColor(this,com.google.android.material.R.attr.colorTertiary))
        val left=35*sp;val right=width-12*dp;val top=12*dp;val bottom=height-28*sp
        if(right<=left || bottom<=top)return
        fun y(db:Double)=top+((6-db.coerceIn(-60.0,6.0))/66).toFloat()*(bottom-top)
        paint.textSize=10*sp;paint.strokeWidth=dp
        for(db in listOf(0,-20,-40,-60)){paint.pathEffect=null;paint.color=text;paint.alpha=40;canvas.drawLine(left,y(db.toDouble()),right,y(db.toDouble()),paint)
            paint.alpha=255;canvas.drawText(db.toString(),0f,y(db.toDouble())+3*sp,paint)}
        val r=reading
        if(r==null){paint.color=text;canvas.drawText("等待有效音频",left,top+30*sp,paint);return}
        val maximum=min(20000.0,r.rate*0.45);val range=ln(maximum/20)
        fun x(hz:Double)=left+(ln(hz/20)/range).toFloat()*(right-left)
        for(hz in listOf(20,200,2000,20000).filter {it<=maximum}){
            val label=if(hz>=1000)"${hz/1000}k"else hz.toString();paint.color=text;paint.pathEffect=null
            canvas.drawText(label,(x(hz.toDouble())-paint.measureText(label)/2).coerceIn(0f,width-paint.measureText(label)),bottom+20*sp,paint)
        }
        for(curve in 0..5){paint.color=colors[curve/2];paint.strokeWidth=2*dp;paint.pathEffect=if(curve%2==1)DashPathEffect(floatArrayOf(5*dp,3*dp),0f)else null
            for(i in 1 until 32){val a=r.data[20+(i-1)*6+curve];val b=r.data[20+i*6+curve]
                if(a!=Int.MIN_VALUE && b!=Int.MIN_VALUE)canvas.drawLine(left+(right-left)*(i-1)/31,y(a/100.0),left+(right-left)*i/31,y(b/100.0),paint)}
        }
        paint.pathEffect=null
    }
}
class CrossfeedResponsePreference(context:Context):Preference(context){
    private var reading:CrossfeedAdvancedParameters.Diagnostic?=null
    init{key="crossfeed2_response";title="理论频响与输出峰值";summary="等待配套驱动和活动音频。";layoutResource=R.layout.preference_crossfeed_response;isSelectable=false;isIconSpaceReserved=false}
    fun update(next:CrossfeedAdvancedParameters.Diagnostic?){
        if(next==reading)return;reading=next
        fun db(v:Int)=if(v==Int.MIN_VALUE)"无信号"else String.format(Locale.ROOT,"%.2f dBFS",v/100.0)
        summary=if(next==null)"等待配套驱动和活动音频。"else "${next.rate} Hz；峰值 左 ${db(next.peaks[0])}／右 ${db(next.peaks[1])}。\n主色：直达；辅色：交叉支路；第三色：中央响应。实线为左输出，虚线为右输出。曲线对应保存参数，中央响应假设左右同相等幅；不包含试听切换及后续效果。显示下限 −60 dB。"
        notifyChanged()
    }
    override fun onBindViewHolder(holder:PreferenceViewHolder){super.onBindViewHolder(holder)
        (holder.findViewById(R.id.crossfeed_response_chart) as CrossfeedResponseView).apply {reading=this@CrossfeedResponsePreference.reading;contentDescription="当前保存参数的直达、交叉支路与中央声道理论频响，左右分别显示"}}
}
