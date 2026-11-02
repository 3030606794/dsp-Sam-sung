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

private fun companderPointLabel(p: List<Int>): String {
    fun db(n: Int)=if(n==Int.MIN_VALUE) "无信号" else String.format(Locale.ROOT,"%.2f dB",n/100.0)
    return "${p[0]} Hz；动态增益 ${db(p[1])}；系数 ${String.format(Locale.ROOT,"%.3f",p[2]/1000.0)}（无量纲）；检测 ${db(p[3])}；背景 ${db(p[4])}；${if(p[5]==1) "作用频段" else "频段外"}"
}

/** Measured control values from the active kernel, never an invented transfer response. */
class CompanderResponseView @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): View(context,attrs) {
    var reading: CompanderParameters.Response?=null
        set(value) {if(field!=value) {
            if(value==null || field?.rate!=value.rate || field?.data?.get(3)!=value.data[3] || field?.data?.get(6)!=value.data[6]) selected=-1
            field=value;invalidate()
        }}
    var onPoint: ((Int,String)->Unit)?=null
    private var selected=-1
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun left()=40*resources.displayMetrics.scaledDensity
    private fun right()=width-12*resources.displayMetrics.density
    private fun x(hz: Int,r: CompanderParameters.Response): Float {
        val first=r.points.first()[0].coerceAtLeast(1).toDouble();val last=r.points.last()[0].coerceAtLeast(2).toDouble()
        return left()+(ln(hz.coerceAtLeast(1)/first)/ln(last/first)).toFloat()*(right()-left())
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r=reading?.takeIf {it.ready} ?: return false
        if(event.action==MotionEvent.ACTION_UP) {
            selected=r.points.indices.minByOrNull {abs(x(r.points[it][0],r)-event.x)} ?: -1
            if(selected>=0) {onPoint?.invoke(selected,companderPointLabel(r.points[selected]));invalidate();performClick()}
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
        val values=r?.points?.map {it[1]/100.0}.orEmpty()
        val low=minOf(-12.0,floor((values.minOrNull() ?: -12.0)/6)*6)
        val high=maxOf(12.0,ceil((values.maxOrNull() ?: 12.0)/6)*6)
        fun y(db: Double)=top+((high-db.coerceIn(low,high))/(high-low)).toFloat()*(bottom-top)
        paint.textSize=10*sp;paint.strokeWidth=dp
        for(db in listOf(high,0.0,low).distinct()) {
            paint.color=text;paint.alpha=40;canvas.drawLine(left(),y(db),right(),y(db),paint)
            paint.alpha=255;canvas.drawText(db.toInt().toString(),0f,y(db)+3*sp,paint)
        }
        if(r==null) {paint.color=text;canvas.drawText("等待实际增益",left(),top+28*sp,paint);return}
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
        paint.alpha=255
        if(selected in points.indices) {paint.color=text;paint.strokeWidth=dp;canvas.drawLine(x(points[selected][0],r),top,x(points[selected][0],r),bottom,paint)}
    }
}

class CompanderResponsePreference(context: Context): Preference(context) {
    private var reading: CompanderParameters.Response?=null
    private var selectedIndex: Int?=null
    init {key="compander_pro_response_graph";title="实际动态增益控制图";layoutResource=R.layout.preference_compander_response;isSelectable=false;isIconSpaceReserved=false;update(null)}
    private fun description()=if(reading?.ready!=true) "等待配套驱动与活动音频；开启专业调节和实时动态增益图后显示。" else
        "${reading!!.rate} Hz；${if(reading!!.data[3]==0) "七点引擎" else "标准阈值引擎"}。横轴 Hz，纵轴 dB，纵轴按当前作用量自动缩放。亮线为作用频段，淡线为频段外。\n图为算法实际动态增益控制量，不是完整传递频响或房间测量；频带滤波相位仍影响最终声音。七点系数无量纲，不能当作 dB 或压缩比。点击图查看频率、增益、系数、检测与背景电平。" +
            (selectedIndex?.let {reading?.points?.getOrNull(it)}?.let {"\n${companderPointLabel(it)}"} ?: "")
    fun update(next: CompanderParameters.Response?) {
        if(next==null || reading?.rate!=next.rate || reading?.data?.get(3)!=next.data[3] || reading?.data?.get(6)!=next.data[6]) selectedIndex=null
        reading=next;summary=description();notifyChanged()
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.compander_response_chart) as CompanderResponseView).apply {
            reading=this@CompanderResponsePreference.reading;contentDescription=this@CompanderResponsePreference.summary
            onPoint={index,_->selectedIndex=index;summary=description();notifyChanged()}
        }
    }
}
