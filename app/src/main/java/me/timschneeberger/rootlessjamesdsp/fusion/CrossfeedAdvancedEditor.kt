package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.SharedPreferences
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import java.util.Locale

class CrossfeedAdvancedEditor(private val context:Context,private val group:SwitchPreferenceGroup,private val prefs:SharedPreferences){
    private val p=CrossfeedAdvancedParameters
    private fun toggle(key:String,title:String,text:String,position:Int)=object:SwitchPreferenceCompat(context){
        override fun onBindViewHolder(holder:PreferenceViewHolder){super.onBindViewHolder(holder)
            for(id in listOf(android.R.id.title,android.R.id.summary))(holder.findViewById(id) as? TextView)?.apply {setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}
    }.apply {
        this.key=key;this.title=title;summary=text;order=position;isIconSpaceReserved=false;setDefaultValue(false)
    }
    private val center=toggle(p.COMPENSATION,"中央音色补偿","默认关闭。补偿中央（M）声道的音色变化，保留处理后的侧向声场。",90)
    private val separate=toggle(p.SEPARATE,"左右交叉路径独立调节","默认联动。开启后分别控制左 → 右、右 → 左的交叉衰减和附加延时。",110).apply {
        setOnPreferenceChangeListener {_,value->
            if(value==true && !prefs.contains(p.left.key))prefs.edit()
                .putInt(p.left.key,CrossfeedParameters.value(prefs.all,CrossfeedParameters.attenuation))
                .putInt(p.right.key,CrossfeedParameters.value(prefs.all,CrossfeedParameters.attenuation))
                .putInt(p.leftDelay.key,CrossfeedParameters.value(prefs.all,CrossfeedParameters.delay))
                .putInt(p.rightDelay.key,CrossfeedParameters.value(prefs.all,CrossfeedParameters.delay)).apply()
            true
        }
    }
    private val filter=toggle(p.FILTER,"交叉支路滤波精调","默认关闭，保留原来的 6 dB/oct。开启后选择交叉支路低通斜率。",160)
    private val slope=FieldChoicePreference(context,p.SLOPE,"交叉支路低通斜率",listOf("6 dB/oct","12 dB/oct","18 dB/oct","24 dB/oct"),resetSelection=1).apply {
        order=170;setOnPreferenceChangeListener {_,v->prefs.edit().putInt(p.SLOPE,(v as Int)+1).apply();true}
    }
    private val diagnostics=toggle(p.DIAGNOSTICS,"响应与电平诊断","默认关闭。显示保存参数的理论频响和本模块试听点的输出峰值。",180)
    private val rows=p.numbers.mapIndexed {i,c->c.key to BassNumericPreference(context,c,p.value(prefs.all,c)).apply {order=if(i==0)100 else 110+i*10}}.toMap()
    private val response:Preference=if(group.isProfessionalMode)StableRuntimeReadoutPreference(context).apply {
        key="crossfeed2_response";title="理论频响与输出峰值";order=190
    }else CrossfeedResponsePreference(context).apply {order=190}
    private val description=object : RuntimeStatusPreference(context){override fun onBindViewHolder(holder:PreferenceViewHolder){super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.summary) as? TextView)?.apply {setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}}.apply {
        key="crossfeed2_detail";title="专业增强说明";order=195;isIconSpaceReserved=false;isSelectable=false
        summary="中央音色补偿处理左右相加的中央声道，不是人声提取。100% 时中央声道在本模块交叉处理前后保持一致，仍经过所选输出滤波、混合和输出衰减。左右独立时，各方向延时接管共用附加延时。更陡的交叉支路滤波也会改变相位和时间响应。曲线显示直达、交叉和左右同相等幅输入的中央响应，不代表耳机或房间实测。"
    }
    private val all get()=listOf(center,separate,filter,slope,diagnostics,response,description)+rows.values
    private fun visible(row:Preference,on:Boolean){if(on){if(group.findPreference<Preference>(row.key)==null)group.addPreference(row)}else group.removePreference(row)}
    fun render(professional:Boolean){
        p.normalize(prefs);val v=prefs.all
        for(row in listOf(center,separate,filter,diagnostics)){
            row.isChecked=p.on(v,row.key);row.isEnabled=professional
            visible(row,professional || group.isProfessionalMode)
        }
        separate.summary=if(p.on(v,p.SEPARATE))"左右独立参数正在生效，共用附加延时已停用；关闭后恢复共用参数。"
            else"默认联动。开启后分别控制左 → 右、右 → 左的交叉衰减和附加延时。"
        rows.forEach {(key,row)->
            val value=p.value(v,row.control);if(row.value!=value)row.value=value
            row.isEnabled=professional && (if(key==p.amount.key)p.on(v,p.COMPENSATION)else p.on(v,p.SEPARATE))
            visible(row,group.isProfessionalMode || row.isEnabled)
        }
        slope.value=p.stages(v)-1;slope.isEnabled=professional && p.on(v,p.FILTER)
        visible(slope,group.isProfessionalMode || slope.isEnabled)
        visible(response,group.isProfessionalMode || professional && p.on(v,p.DIAGNOSTICS))
        visible(description,group.isProfessionalMode || professional && (p.on(v,p.COMPENSATION)||p.on(v,p.SEPARATE)||p.on(v,p.FILTER)||p.on(v,p.DIAGNOSTICS)))
        if(!professional && !group.isProfessionalMode)all.forEach {group.removePreference(it)}
    }
    fun update(){
        val ready=prefs.getBoolean("v_cure",false) && CrossfeedParameters.enabled(prefs.all) && p.on(prefs.all,p.DIAGNOSTICS) && CrossfeedController.advancedSupported(context)
        val reading=if(ready)CrossfeedController.runtime.firstOrNull {r->
            r.enabled && r.active && r.diagnostics?.on==true && (!group.isProfessionalMode ||
                r.snapshot?.let {it.enabled && it.professional && it.mode==CrossfeedController.audition &&
                    (CrossfeedController.audition==0 || it.epoch==CrossfeedController.epoch) &&
                    it.data[1]==r.diagnostics?.data?.get(1)}==true &&
                r.diagnostics?.let {it.data[3]==1 && it.rate==r.rate}==true)
        }?.diagnostics else null
        (response as? CrossfeedResponsePreference)?.update(reading)
        if(response is StableRuntimeReadoutPreference){
            val state=when {
                !p.on(prefs.all,p.DIAGNOSTICS)->"响应与电平诊断已关闭；峰值不采集。"
                reading==null->"等待配套驱动和活动音频。"
                else->"当前诊断读数"
            }
            fun peak(index:Int)=StableRuntimeReadoutPreference.fixedNumber(reading?.peaks?.get(index),100.0,2)
            val rate=reading?.rate?.toString()?.padStart(6) ?: "—".padStart(6)
            response.summary="$rate Hz\n$state\n—表示无信号或暂无有效读数。\n峰值 左 ${peak(0)} dBFS／右 ${peak(1)} dBFS。\n"+
                "主色：直达；辅色：交叉支路；第三色：中央响应。实线为左输出，虚线为右输出。曲线对应保存参数，中央响应假设左右同相等幅；不包含试听切换及后续效果。显示下限 −60 dB。"
        }
    }
    fun numberChanged(key:String?){rows[key]?.let {row->val value=p.value(prefs.all,row.control);if(row.value!=value)row.value=value}}
}
