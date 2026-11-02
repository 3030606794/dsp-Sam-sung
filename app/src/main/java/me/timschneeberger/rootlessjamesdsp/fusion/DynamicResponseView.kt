package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
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

private fun dynamicPointLabel(p: List<Int>): String {
    fun db(v: Int)=if(v==Int.MIN_VALUE) "无信号" else String.format(Locale.ROOT,"%.2f dB",v/100.0)
    return "${p[0]} Hz；效果 ${db(p[1])}，相位 ${String.format(Locale.ROOT,"%.2f°",p[2]/1000.0)}；对齐干声 ${db(p[3])}，相位 ${String.format(Locale.ROOT,"%.2f°",p[4]/1000.0)}；增强支路 ${db(p[5])}"
}

/** Actual driver transfer estimate, with no invented ideal crossover curve. */
class DynamicResponseView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context,attrs) {
    var reading: DynamicParameters.Response? = null
        set(value) { if(field!=value) {
            if(value==null || field?.rate!=value.rate || field?.data?.get(3)!=value.data[3]) selected=-1
            field=value; invalidate()
        } }
    var onPoint: ((Int,String)->Unit)? = null
    private var selected=-1
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun left()=38*resources.displayMetrics.scaledDensity
    private fun right()=width-12*resources.displayMetrics.density
    private fun x(hz: Int, r: DynamicParameters.Response): Float {
        val first=r.points.first()[0].coerceAtLeast(1).toDouble(); val last=r.points.last()[0].coerceAtLeast(2).toDouble()
        return left()+(ln(hz.coerceAtLeast(1)/first)/ln(last/first)).toFloat()*(right()-left())
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r=reading?.takeIf { it.ready } ?: return false
        if(event.action==MotionEvent.ACTION_UP) {
            selected=r.points.indices.minByOrNull { abs(x(r.points[it][0],r)-event.x) } ?: -1
            if(selected>=0) {
                onPoint?.invoke(selected,dynamicPointLabel(r.points[selected]))
                invalidate(); performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val dp=resources.displayMetrics.density; val sp=resources.displayMetrics.scaledDensity
        val text=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
        val colors=listOf(MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary),
            MaterialColors.getColor(this,com.google.android.material.R.attr.colorSecondary),MaterialColors.getColor(this,com.google.android.material.R.attr.colorTertiary))
        val top=12*dp; val bottom=height-30*sp
        if(bottom<=top || right()<=left()) return
        fun y(db: Double)=top+((24-db.coerceIn(-60.0,24.0))/84).toFloat()*(bottom-top)
        paint.textSize=10*sp; paint.strokeWidth=dp; paint.pathEffect=null
        for(db in listOf(24,0,-20,-40,-60)) {
            paint.color=text; paint.alpha=40; canvas.drawLine(left(),y(db.toDouble()),right(),y(db.toDouble()),paint)
            paint.alpha=255; canvas.drawText(db.toString(),0f,y(db.toDouble())+3*sp,paint)
        }
        val r=reading?.takeIf { it.ready }
        if(r==null) { paint.color=text; canvas.drawText("等待有效预测",left(),top+28*sp,paint); return }
        val points=r.points
        for(hz in listOf(20,200,2000,20000).filter { it in points.first()[0]..points.last()[0] }) {
            paint.color=text; val label=if(hz>=1000) "${hz/1000}k" else hz.toString()
            canvas.drawText(label,(x(hz,r)-paint.measureText(label)/2).coerceIn(0f,width-paint.measureText(label)),bottom+22*sp,paint)
        }
        for((curve,column) in listOf(1,3,5).withIndex()) {
            paint.color=colors[curve]; paint.strokeWidth=2*dp
            paint.pathEffect=when(curve) { 1 -> DashPathEffect(floatArrayOf(7*dp,4*dp),0f); 2 -> DashPathEffect(floatArrayOf(2*dp,4*dp),0f); else -> null }
            for(i in 1 until points.size) {
                val a=points[i-1]; val b=points[i]
                if(a[column]!=Int.MIN_VALUE && b[column]!=Int.MIN_VALUE) canvas.drawLine(x(a[0],r),y(a[column]/100.0),x(b[0],r),y(b[column]/100.0),paint)
            }
        }
        paint.pathEffect=null
        if(selected in points.indices) { paint.color=text; paint.strokeWidth=dp; canvas.drawLine(x(points[selected][0],r),top,x(points[selected][0],r),bottom,paint) }
    }
}

class DynamicResponsePreference(context: Context): Preference(context) {
    private var reading: DynamicParameters.Response? = null
    private var selectedIndex: Int? = null
    init { key="dynamic_pro_response_graph"; title="实际滤波响应预测"; layoutResource=R.layout.preference_dynamic_response; isSelectable=false; isIconSpaceReserved=false; update(null) }
    private fun description() = if(reading?.ready!=true) "等待配套驱动与活动音频；开启专业调节和响应预测后显示。" else
        "${reading!!.rate} Hz；当前增强回收 ${String.format(Locale.ROOT,"%.2f",reading!!.data[4]/100.0)} dB。\n实线：效果；长虚线：对齐干声；点线：增强支路。横轴 Hz，纵轴 dB，显示范围 −60 至 +24 dB。\nL=R 同相等幅输入的滤波预测；深低音共通注入按左右相加计算。自适应按当前回收量冻结；不代表房间测量，滤波相位会影响干湿混合。点击图查看频率、电平和相位。" +
            (selectedIndex?.let { reading?.points?.getOrNull(it) }?.let { "\n${dynamicPointLabel(it)}" } ?: "")
    fun update(next: DynamicParameters.Response?) {
        if(next==null || reading?.rate!=next.rate || reading?.data?.get(3)!=next.data[3]) selectedIndex=null
        reading=next; summary=description(); notifyChanged()
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.dynamic_response_chart) as DynamicResponseView).apply {
            reading=this@DynamicResponsePreference.reading; contentDescription=this@DynamicResponsePreference.summary
            onPoint={ index,_ -> selectedIndex=index; summary=description(); notifyChanged() }
        }
    }
}
