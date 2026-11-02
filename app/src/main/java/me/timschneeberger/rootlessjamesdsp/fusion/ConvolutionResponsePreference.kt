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
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import android.widget.TextView
import kotlin.math.*

/** These traces are derived from the generated IR, not from the playing music. */
class ConvolutionResponseView @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): View(context,attrs) {
    var prepared: ConvolutionController.PreparedIr?=null
        set(value){field=value;invalidate()}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d=resources.displayMetrics.density;val sp=resources.displayMetrics.scaledDensity
        val text=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
        val colors=intArrayOf(MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary),Color.rgb(26,134,117),Color.rgb(175,101,33),Color.rgb(73,122,178))
        paint.textSize=10*sp;paint.strokeWidth=d;paint.color=text
        val p=prepared
        if(p==null || !p.hasWorkingKernel || p.frequencyHz.size<2){canvas.drawText("等待有效工作 IR",12*d,30*sp,paint);return}
        val left=38*d;val right=width-8*d;val top=16*d;val bottom=height*.58f
        if(right<=left)return
        val valid=p.magnitudesDb.flatMap {it.asIterable()}.filter {it.isFinite()}
        val ceiling=max(0.0,ceil((valid.maxOrNull()?.toDouble() ?: 0.0)/12)*12)
        val floor=ceiling-72.0
        val minHz=p.frequencyHz.first().coerceAtLeast(1f).toDouble();val maxHz=p.frequencyHz.last().coerceAtLeast((minHz+1).toFloat()).toDouble()
        fun x(hz: Float)=left+((ln(hz.toDouble().coerceIn(minHz,maxHz))-ln(minHz))/(ln(maxHz)-ln(minHz))).toFloat()*(right-left)
        fun y(db: Float)=top+((ceiling-db.toDouble().coerceIn(floor,ceiling))/72).toFloat()*(bottom-top)
        repeat(4){i->val db=ceiling-i*24;val yy=y(db.toFloat());paint.color=text;paint.alpha=35;canvas.drawLine(left,yy,right,yy,paint);paint.alpha=255;canvas.drawText(db.toInt().toString(),0f,yy+4*sp,paint)}
        for(hz in listOf(20,100,1000,10000)){if(hz.toDouble() in minHz..maxHz){paint.color=text;canvas.drawText(if(hz>=1000)"${hz/1000}k" else "$hz",x(hz.toFloat())-7*sp,bottom+16*sp,paint)}}
        p.magnitudesDb.forEachIndexed {channel,values->
            if(values.size==p.frequencyHz.size){paint.color=colors[channel%4];paint.strokeWidth=1.8f*d;for(i in 1 until values.size){if(!values[i].isNaN() && !values[i-1].isNaN())canvas.drawLine(x(p.frequencyHz[i-1]),y(values[i-1]),x(p.frequencyHz[i]),y(values[i]),paint)}}
        }
        val waveTop=bottom+44*sp;val waveBottom=height-12*d;val center=(waveTop+waveBottom)*.5f
        paint.color=text;paint.textSize=10*sp;canvas.drawText("工作 IR 波形包络（相对幅度）",left,bottom+36*sp,paint)
        val peak=p.waveform.flatMap {it.asIterable()}.maxOfOrNull {abs(it)}?.coerceAtLeast(1e-12f) ?: 1f
        paint.strokeWidth=d;paint.color=text;paint.alpha=35;canvas.drawLine(left,center,right,center,paint);paint.alpha=255
        p.waveform.forEachIndexed {channel,values->
            paint.color=colors[channel%4]
            if(values.size>=2)for(i in 1 until values.size){canvas.drawLine(left+(i-1f)/(values.size-1)*(right-left),center-values[i-1]/peak*(waveBottom-waveTop)*.45f,
                left+i.toFloat()/(values.size-1)*(right-left),center-values[i]/peak*(waveBottom-waveTop)*.45f,paint)}
        }
    }
}

class ConvolutionResponsePreference(context: Context): Preference(context) {
    private var prepared: ConvolutionController.PreparedIr?=null
    init {key="conv1_response";title="工作 IR 分析预览";layoutResource=R.layout.preference_convolution_response;isSelectable=false;isIconSpaceReserved=false}
    fun configure(p: ConvolutionController.PreparedIr?,confirmed: Boolean) {
        prepared=p
        summary=if(p?.hasWorkingKernel!=true)"等待完成工作 IR 预处理。" else
            "${p.processingSampleRate} Hz · ${if(confirmed)"CRC 已确认" else "工作核已生成，待确认装载"}${if(p.status==ConvolutionController.Preparation.STAGED)" · 已暂存待激活"else""}"
        notifyChanged()
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder){
        super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.title) as? TextView)?.apply {
            text="工作 IR 分析预览  !"
            contentDescription="工作 IR 分析预览，点击查看说明"
            setOnClickListener { ParameterHelp.show(context,"工作 IR 分析预览",
                technical="曲线是各核离散频率网格上的幅频预览；波形是带符号峰值包络。紫、绿、橙、蓝依次对应核 1–4。不含干湿混合、湿声输出校准和后续音效，不是音乐实测或群延迟图。",
                range="幅频横轴为 Hz，纵轴为 dB；波形按本组峰值显示相对幅度。CRC 确认表示当前工作核与驱动装载内容一致。") }
        }
        (holder.findViewById(R.id.convolution_response_chart) as ConvolutionResponseView).apply {prepared=this@ConvolutionResponsePreference.prepared;contentDescription="工作脉冲响应各核幅频曲线与带符号峰值波形包络"}
    }
}
