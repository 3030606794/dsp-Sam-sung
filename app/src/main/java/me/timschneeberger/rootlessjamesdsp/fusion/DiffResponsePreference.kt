package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/** Static transfer preview assumes L=R. Dynamic protection and the rest of the chain are excluded. */
object DiffResponse {
    data class Configuration(val rate: Int,val delaySamples: Double,val mix: Double,val band: Boolean,val low: Int,val high: Int,
        val quality: Int,val window: Int,val wetDb: Double,val outputDb: Double)
    data class Point(val frequency: Double,val selected: Double,val other: Double,val mono: Double)
    fun configuration(rate: Int,values: Map<String,*>): Configuration {
        val b=DiffParameters;val a=DiffAdvancedParameters;val pro=b.mode(values)==1
        val delay=if(pro) {if(a.enabled(values,a.FINE_ENABLED))a.value(values,a.fine)/1e6 else b.value(values,b.delay)/100000.0}
            else floor((b.value(values,b.legacy).toFloat().toDouble()/1000.0)*rate)/rate
        return Configuration(rate,rate*delay,if(pro)b.value(values,b.mix)/1000.0 else 1.0,pro && b.bandEnabled(values),
            b.value(values,b.low),min(b.value(values,b.high),rate/2-100),if(pro)a.quality(values) else 0,a.value(values,a.window),
            if(pro)a.value(values,a.wet)/10.0 else 0.0,if(pro)a.value(values,a.output)/10.0 else 0.0)
    }
    fun calculate(c: Configuration,count: Int=96): List<Point> {
        require(c.rate in 8000..768000 && count>=2)
        val m=(c.rate*c.window/2000.0).roundToInt()
        val coefficients=if(c.band)DoubleArray(m+1) { k ->
            if(k==0)2.0*(c.high-c.low)/c.rate else
                (sin(2*PI*c.high*k/c.rate)-sin(2*PI*c.low*k/c.rate))/(PI*k)*(1-k.toDouble()/(m+1))
        } else doubleArrayOf()
        val maxHz=min(20000,c.rate/2-100).toDouble()
        val integer=floor(c.delaySamples);val f=c.delaySamples-integer
        val weights=if(c.quality==0)doubleArrayOf(1-f,f) else doubleArrayOf(-f*(f-1)*(f-2)/6,(1+f)*(f-1)*(f-2)/2,-(1+f)*f*(f-2)/2,(1+f)*f*(f-1)/6)
        val wet=10.0.pow(c.wetDb/20);val gain=10.0.pow(c.outputDb/20)
        fun db(amplitude: Double)=if(amplitude<=1e-15)Double.NEGATIVE_INFINITY else 20*log10(amplitude)
        val frequencies=(0 until count).map {20*(maxHz/20).pow(it.toDouble()/(count-1))}.toMutableList()
        // Add quadrature points so a logarithmic grid does not miss delay-comb nulls.
        if(c.delaySamples>0) {
            val step=c.rate/(4*c.delaySamples)
            var hz=step
            while(hz<=maxHz){if(hz>=20)frequencies+=hz;hz+=step}
        }
        return frequencies.distinct().sorted().map { hz ->
            if(Thread.currentThread().isInterrupted)throw CancellationException()
            val w=2*PI*hz/c.rate
            var band=1.0
            if(c.band) {
                band=coefficients[0];val cosine=cos(w);var previous=1.0;var current=cosine
                for(k in 1..m){band+=2*coefficients[k]*current;val next=2*cosine*current-previous;previous=current;current=next}
            }
            var real=0.0;var imag=0.0
            weights.forEachIndexed { k,v->val phase=-w*(integer+k-c.quality);real+=v*cos(phase);imag+=v*sin(phase) }
            val re=1+c.mix*band*(wet*real-1);val im=c.mix*band*wet*imag
            Point(hz,db(hypot(re,im)*gain),db(gain),db(hypot(1+re,im)*gain*.5))
        }
    }
}

class DiffResponseView @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): View(context,attrs) {
    var points: List<DiffResponse.Point> = emptyList()
        set(value){field=value;invalidate()}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density=resources.displayMetrics.density;val sp=resources.displayMetrics.scaledDensity
        val primary=MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary)
        val secondary=MaterialColors.getColor(this,com.google.android.material.R.attr.colorSecondary)
        val text=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
        val left=36*density;val top=12*density;val right=width-8*density;val bottom=height-28*density
        if(right<=left || bottom<=top)return
        paint.textSize=10*sp;paint.strokeWidth=density
        for(db in listOf(0,-12,-24,-36)) {
            val y=top+(6-db)/42f*(bottom-top)
            paint.color=text;paint.alpha=35;canvas.drawLine(left,y,right,y,paint);paint.alpha=255
            canvas.drawText(db.toString(),0f,y+4*sp,paint)
        }
        if(points.isEmpty()){paint.color=text;canvas.drawText("等待有效采样率",left,top+30*sp,paint);return}
        val min=ln(points.first().frequency);val range=ln(points.last().frequency)-min
        fun x(hz: Double)=left+((ln(hz)-min)/range).toFloat()*(right-left)
        fun y(db: Double)=top+((6-db.coerceIn(-36.0,6.0))/42).toFloat()*(bottom-top)
        paint.color=text
        for(hz in listOf(20,100,1000,10000)){if(hz<=points.last().frequency)canvas.drawText(if(hz>=1000)"${hz/1000}k" else "$hz",x(hz.toDouble())-8*sp,bottom+18*sp,paint)}
        fun line(color: Int,field: (DiffResponse.Point)->Double){paint.color=color;paint.strokeWidth=2*density;for(i in 1 until points.size){val a=points[i-1];val b=points[i];canvas.drawLine(x(a.frequency),y(field(a)),x(b.frequency),y(field(b)),paint)}}
        line(text) {it.other};line(primary) {it.selected};line(secondary) {it.mono}
    }
}

class DiffResponsePreference(context: Context,private val showChart: Boolean=true): Preference(context) {
    private val executor=Executors.newSingleThreadExecutor { task->Thread(task,"diff-response-preview").apply {isDaemon=true} }
    private val handler=Handler(Looper.getMainLooper())
    private var task: Future<*>?=null
    private var configuration: DiffResponse.Configuration?=null
    private var points: List<DiffResponse.Point> = emptyList()
    private var revision=0L
    private var closed=false
    init {key="diff2_response";title="设定响应预览";layoutResource=R.layout.preference_diff_response;isSelectable=false;isIconSpaceReserved=false}
    fun configure(rate: Int?,values: Map<String,*>) {
        val next=rate?.takeIf {it in 8000..768000}?.let {DiffResponse.configuration(it,values)}
        if(next==configuration)return
        configuration=next;val token=++revision;task?.cancel(true);points=emptyList()
        summary=if(next==null)"等待驱动实际处理采样率。" else "${next.rate} Hz；假设输入左右同相等幅。紫色：延迟声道；灰色：另一声道；主题辅色：单声道合并。曲线使用保存的设定，不含动态保护或后续效果；显示下限−36 dB。"
        notifyChanged()
        if(next!=null && showChart)task=executor.submit {
            try {val result=DiffResponse.calculate(next);handler.post {if(!closed && revision==token){points=result;notifyChanged()}}}catch(_: CancellationException){}
        }
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder){super.onBindViewHolder(holder);(holder.findViewById(R.id.diff_response_chart) as DiffResponseView).apply {visibility=if(showChart)View.VISIBLE else View.GONE;points=this@DiffResponsePreference.points;contentDescription="保存设定的声道与单声道合并幅度响应预览"}}
    fun close(){closed=true;revision++;task?.cancel(true);executor.shutdownNow()}
}
