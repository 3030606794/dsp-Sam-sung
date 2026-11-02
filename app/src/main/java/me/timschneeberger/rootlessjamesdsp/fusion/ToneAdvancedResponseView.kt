package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.ceil
import kotlin.math.floor

private fun tonePointLabel(p: List<Int>): String {
    fun db(n: Int)=if(n==Int.MIN_VALUE) "无信号" else String.format(Locale.ROOT,"%.2f dB",n/100.0)
    return "${p[0]} Hz；静态滤波 ${db(p[1])}；静态混合输出 ${db(p[2])}"
}

/** Predicted static filter response; dynamic control gains are reported separately. */
class ToneAdvancedResponseView @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): View(context,attrs) {
    var reading: ToneAdvancedParameters.Response?=null
        set(value) {if(field!=value) {
            if(value==null || field?.rate!=value.rate) selected=-1
            field=value;invalidate()
        }}
    var onPoint: ((Int,String)->Unit)?=null
    private var selected=-1
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun left()=40*resources.displayMetrics.scaledDensity
    private fun right()=width-12*resources.displayMetrics.density
    private fun x(hz: Int,r: ToneAdvancedParameters.Response): Float {
        val first=r.points.first()[0].coerceAtLeast(1).toDouble();val last=r.points.last()[0].coerceAtLeast(2).toDouble()
        return left()+(ln(hz.coerceAtLeast(1)/first)/ln(last/first)).toFloat()*(right()-left())
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r=reading?.takeIf {it.ready} ?: return false
        if(event.action==MotionEvent.ACTION_UP) {
            selected=r.points.indices.minByOrNull {abs(x(r.points[it][0],r)-event.x)} ?: -1
            if(selected>=0) {onPoint?.invoke(selected,tonePointLabel(r.points[selected]));invalidate();performClick()}
        }
        return true
    }
    override fun performClick(): Boolean {super.performClick();return true}
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);val dp=resources.displayMetrics.density;val sp=resources.displayMetrics.scaledDensity
        val text=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
        val primary=MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary)
        val top=12*dp;val bottom=height-30*sp
        if(bottom<=top || right()<=left()) return
        val r=reading?.takeIf {it.ready}
        val values=r?.points?.flatMap {listOf(it[1]/100.0,it[2]/100.0)}.orEmpty()
        val low=minOf(-12.0,floor((values.minOrNull() ?: -12.0)/6)*6)
        val high=maxOf(12.0,ceil((values.maxOrNull() ?: 12.0)/6)*6)
        fun y(db: Double)=top+((high-db.coerceIn(low,high))/(high-low)).toFloat()*(bottom-top)
        paint.textSize=10*sp;paint.strokeWidth=dp
        for(db in listOf(high,0.0,low).distinct()) {
            paint.color=text;paint.alpha=40;canvas.drawLine(left(),y(db),right(),y(db),paint)
            paint.alpha=255;canvas.drawText(db.toInt().toString(),0f,y(db)+3*sp,paint)
        }
        if(r==null) {paint.color=text;canvas.drawText("等待实际响应",left(),top+28*sp,paint);return}
        val points=r.points
        for(hz in listOf(20,200,2000,20000).filter {it in points.first()[0]..points.last()[0]}) {
            paint.color=text;val label=if(hz>=1000) "${hz/1000}k" else hz.toString()
            canvas.drawText(label,(x(hz,r)-paint.measureText(label)/2).coerceIn(0f,width-paint.measureText(label)),bottom+22*sp,paint)
        }
        paint.color=primary;paint.strokeWidth=2*dp
        for(i in 1 until points.size) {
            val a=points[i-1];val b=points[i];paint.alpha=if(a[5]==1 || b[5]==1) 255 else 80
            canvas.drawLine(x(a[0],r),y(a[1]/100.0),x(b[0],r),y(b[1]/100.0),paint)
        }
        paint.alpha=100
        for(i in 1 until points.size) {val a=points[i-1];val b=points[i];canvas.drawLine(x(a[0],r),y(a[2]/100.0),x(b[0],r),y(b[2]/100.0),paint)}
        paint.alpha=255
        if(selected in points.indices) {paint.color=text;paint.strokeWidth=dp;canvas.drawLine(x(points[selected][0],r),top,x(points[selected][0],r),bottom,paint)}
    }
}

class ToneAdvancedResponsePreference(context: Context): Preference(context) {
    private var reading: ToneAdvancedParameters.Response?=null
    private var selectedIndex: Int?=null
    init {key="tone_pro_response_graph";title="静态音色响应与动态控制";layoutResource=R.layout.preference_tone_advanced_response;isSelectable=false;isIconSpaceReserved=false;update(null)}
    private fun description()=if(reading?.ready!=true) "等待配套驱动与活动音频；开启专业调节和响应图后显示。" else
        "${reading!!.rate} Hz；横轴 Hz，纵轴 dB。实线为稳定后的静态滤波响应，淡线为含干湿与自动／额外余量及输出的静态响应；弹性与瞬态为当前控制量，另在状态中显示。图属于主配置内核，预热交接前仍为旧实际值，不含 A/B 试听路由；不是实测频响，不能代表动态音乐的完整响应。点击查看频点。" +
            (selectedIndex?.let {reading?.points?.getOrNull(it)}?.let {"\n${tonePointLabel(it)}"} ?: "")
    fun update(next: ToneAdvancedParameters.Response?) {
        if(next==null || reading?.rate!=next.rate) selectedIndex=null
        reading=next;summary=description();notifyChanged()
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.tone_advanced_response_chart) as ToneAdvancedResponseView).apply {
            reading=this@ToneAdvancedResponsePreference.reading;contentDescription=this@ToneAdvancedResponsePreference.summary
            onPoint={index,_->selectedIndex=index;summary=description();notifyChanged()}
        }
    }
}
