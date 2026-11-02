package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionAdvancedAnalysis
import me.timschneeberger.rootlessjamesdsp.ui.IrProfessionalHeader
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import android.widget.TextView
import kotlin.math.*

class ConvolutionAdvancedResponseView @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): View(context,attrs) {
    var data: ConvolutionAdvancedAnalysis.Data?=null
    var mode=0
    var smoothing=0
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);val d=resources.displayMetrics.density;val sp=resources.displayMetrics.scaledDensity
        val text=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
        paint.textSize=10*sp;paint.color=text
        val p=data ?: run{canvas.drawText("启用深度分析后等待工作核准备完成",8*d,30*sp,paint);return}
        val left=48*d;val right=width-12*d;val top=20*sp;val bottom=height-38*sp
        if(right<=left || bottom<=top)return
        val axis=if(mode==3)p.timeMs else p.hz
        val lines=when(mode){1->p.phaseDegrees;2->p.groupDelayMs;3->p.energyDb;else->p.workingDb.map {ConvolutionAdvancedAnalysis.smooth(p.hz,it,smoothing)}}
        val original=if(mode==0)p.originalDb.map {ConvolutionAdvancedAnalysis.smooth(p.hz,it,smoothing)} else emptyList()
        val valid=(lines+original).flatMap {it.asIterable()}.filter {it.isFinite()}
        val low=when(mode){1->-180.0;3->-90.0;2->min(0.0,valid.minOrNull()?.toDouble() ?: 0.0);else->min(-60.0,(valid.minOrNull()?.toDouble() ?: -60.0).coerceAtLeast(-120.0))}
        val high=when(mode){1->180.0;3->0.0;2->max(low+1.0,valid.maxOrNull()?.toDouble() ?: 1.0);else->max(0.0,valid.maxOrNull()?.toDouble() ?: 0.0)}
        fun x(v: Float): Float = if(mode==3)left+(v/(axis.lastOrNull()?.coerceAtLeast(.001f) ?: 1f))*(right-left)
            else left+((ln(v.toDouble().coerceAtLeast(1.0))-ln(axis.first().toDouble()))/ln(axis.last().toDouble()/axis.first())).toFloat()*(right-left)
        fun y(v: Float)=top+((high-v.toDouble().coerceIn(low,high))/(high-low)).toFloat()*(bottom-top)
        repeat(5){j->val value=high+(low-high)*j/4;val yy=y(value.toFloat());paint.color=text;paint.alpha=35;paint.strokeWidth=d;canvas.drawLine(left,yy,right,yy,paint);paint.alpha=255
            canvas.drawText(String.format(java.util.Locale.ROOT,"%.1f",value),0f,yy+4*sp,paint)}
        val ticks=if(mode==3)listOf(0f,axis.last()/2,axis.last())else listOf(20f,100f,1000f,10000f)
        ticks.filter {it>=axis.first() && it<=axis.last()}.forEach {paint.color=text;canvas.drawText(if(mode==3)"${it.roundToInt()}"else if(it>=1000)"${(it/1000).roundToInt()}k"else "${it.roundToInt()}",x(it)-7*sp,bottom+18*sp,paint)}
        fun draw(values: FloatArray,color: Int,alpha: Int) {
            if(values.size!=axis.size)return
            paint.color=color;paint.alpha=alpha;paint.strokeWidth=1.5f*d
            for(j in 1 until values.size)if(!values[j].isNaN() && !values[j-1].isNaN() && (mode!=1 || abs(values[j]-values[j-1])<=180))
                canvas.drawLine(x(axis[j-1]),y(values[j-1]),x(axis[j]),y(values[j]),paint)
            paint.alpha=255
        }
        original.forEach {draw(it,text,80)}
        val colors=intArrayOf(MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary),Color.rgb(26,134,117),Color.rgb(175,101,33),Color.rgb(73,122,178))
        lines.forEachIndexed {j,v->draw(v,colors[j%4],255)}
        paint.color=text;canvas.drawText(when(mode){1->"相位 °";2->"群延迟 ms";3->"能量 dB / 时间 ms";else->"灰：原 IR；彩：工作核 dB"},left,height-6*sp,paint)
    }
}

class ConvolutionAdvancedResponsePreference(context: Context): Preference(context) {
    private var data: ConvolutionAdvancedAnalysis.Data?=null;private var mode=0;private var smoothing=0
    private var prepared: ConvolutionController.PreparedIr?=null
    private var header: IrProfessionalHeader?=null
    private var headerChart: ConvolutionAdvancedResponseView?=null
    private var headerFallback: ConvolutionResponseView?=null
    init{key="conv2_response";title="深度 IR 分析";layoutResource=R.layout.preference_convolution_advanced_response;isSelectable=false;isIconSpaceReserved=false}
    fun configure(prepared: ConvolutionController.PreparedIr?,mode: Int,smoothing: Int) {
        // Keep the last complete drawing while its replacement is being prepared.
        // The independent status rows still report preparation progress immediately.
        val displayed=if(prepared?.status==ConvolutionController.Preparation.PROCESSING &&
            this.prepared?.hasWorkingKernel==true)this.prepared else prepared
        if(this.prepared===displayed && this.mode==mode && this.smoothing==smoothing)return
        this.prepared=displayed
        data=displayed?.takeIf {it.hasWorkingKernel}?.advancedAnalysis;this.mode=mode;this.smoothing=smoothing
        summary=if(data==null)"等待工作核深度分析" else "${listOf("幅度","相位","群延迟","能量").getOrElse(mode){"幅度"}} · 工作 IR"
        refreshHeader()
        if(header==null)notifyChanged()
    }
    fun createProfessionalHeader(onModeChanged: (Int)->Unit,onPinChanged: (Boolean)->Unit): IrProfessionalHeader {
        header?.let{return it}
        val plot=ConvolutionAdvancedResponseView(context)
        val fallback=ConvolutionResponseView(context)
        headerChart=plot;headerFallback=fallback
        return IrProfessionalHeader(context,plot,fallback,onModeChanged,onPinChanged).also {header=it;refreshHeader()}
    }
    fun updateDetails(metadata: String,runtime: String,details: String=""){header?.updateDetails(metadata,runtime,details)}
    private fun refreshHeader() {
        header?.select(mode)
        headerChart?.apply {data=this@ConvolutionAdvancedResponsePreference.data;mode=this@ConvolutionAdvancedResponsePreference.mode;smoothing=this@ConvolutionAdvancedResponsePreference.smoothing;visibility=if(data==null && mode==0)View.INVISIBLE else View.VISIBLE;invalidate()}
        headerFallback?.apply {prepared=this@ConvolutionAdvancedResponsePreference.prepared;visibility=if(data==null && mode==0)View.VISIBLE else View.INVISIBLE}
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.title) as? TextView)?.apply {
            text="深度 IR 分析  !"
            setOnClickListener {ParameterHelp.show(context,"深度 IR 分析",
                technical="原 IR 以实际处理采样率重采样，彩色对应工作核 1–4。相位与群延迟以工作核起点为参考，深凹陷或静音处留空。能量衰减是工作核计算值，不是房间实测混响时间。图不含湿声滤波、干湿、电平校准及后续效果。",
                range="相位为 °，群延迟为 ms，能量为 dB。倍频程平滑仅改变幅度显示，不改变播放参数。")}
        }
        (holder.findViewById(R.id.convolution_advanced_chart) as ConvolutionAdvancedResponseView).apply {
            data=this@ConvolutionAdvancedResponsePreference.data;mode=this@ConvolutionAdvancedResponsePreference.mode;smoothing=this@ConvolutionAdvancedResponsePreference.smoothing;invalidate()
            contentDescription="原 IR 与工作核幅度对照、相位、群延迟和剩余能量分析"
        }
    }
}
