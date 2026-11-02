package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale

/** Second-round rows belong to the existing card and do not change its persistent power switch. */
class DiffAdvancedEditor(private val context: Context,private val group: SwitchPreferenceGroup) {
    private val p=DiffAdvancedParameters
    private val prefs=DiffController.preferences(context)
    private val status=DiffController.statusPreferences(context)
    private val editable=mutableListOf<Preference>()
    private val numbers=linkedMapOf<String,BassNumericPreference>()
    private fun info(key: String,title: String,order: Int)=object : RuntimeStatusPreference(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder){super.onBindViewHolder(holder);(holder.findViewById(android.R.id.summary) as? TextView)?.apply { maxLines=Int.MAX_VALUE;ellipsize=null }}
    }.apply {this.key=key;this.title=title;this.order=order;isSelectable=false;isIconSpaceReserved=false}
    private val actual=info("diff2_actual","动态处理与分频监测",900)
    private val units=info("diff2_units","时间单位联动",145)
    private val driver=info("diff2_driver","第二轮需要新版驱动",950).apply {summary="当前驱动继续支持第一轮。三阶插值、微秒精调、滤波档位、动态保护及分频监测需要第二轮驱动；已保存数值保留。"}
    private val response=DiffResponsePreference(context,showChart=!group.isProfessionalMode).apply {order=850}
    private val quality=FieldChoicePreference(context,p.QUALITY,"分数延时插值",listOf("第一轮线性","三阶 Lagrange")).apply {
        order=130;setOnPreferenceChangeListener { _,v->prefs.edit().putInt(p.QUALITY,v as Int).apply();true }
    }
    private val detector=FieldChoicePreference(context,p.GUARD_MODE,"兼容判断依据",listOf("相关度","单声道折叠差","联合判断"),resetSelection=2).apply {
        order=310;setOnPreferenceChangeListener { _,v->prefs.edit().putInt(p.GUARD_MODE,v as Int).apply();true }
    }
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key=="capabilities" || key=="session")renderCapability()
        else if(key in p.newKeys || key==DiffParameters.MODE || key==DiffParameters.delay.key || key==DiffParameters.BAND_ENABLED || key==DiffParameters.CHANNEL || DiffParameters.all.any { it.key==key }) {
            p.migrate(prefs)
            numbers[key]?.let { it.value=p.value(prefs.all,it.control) }
            quality.value=p.quality(prefs.all);detector.value=p.detector(prefs.all);renderStatus()
        } else if(key=="v_diff")renderStatus()
    }
    private val renderFrame=me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate {renderStatus()}
    private val receiver=object: BroadcastReceiver(){override fun onReceive(context: Context?,intent: Intent?){if(runtimeStatusVisible(this@DiffAdvancedEditor.context))renderFrame.request(false)}}
    init {
        p.migrate(prefs)
        add(info("diff2_quality_help","插值质量与微秒精调",120).apply {summary="专业精密模式下生效。三阶插值减轻部分高频衰减，双方匹配额外1采样公共延时；仍存在幅相误差。微秒精调接管第一轮延时值，1 μs是控制刻度，不是测量误差保证。"})
        add(quality);editable+=quality
        toggle(p.FINE_ENABLED,"启用微秒精调","关闭时继续使用第一轮0.01 ms参数；开启后使用下面独立保存的0.001 ms参数。调节不自动打开模块。",135)
        number(p.fine,140);add(units)
        add(info("diff2_window_help","选频分辨率与延迟",160).apply {summary="20／40／80 ms为FIR窗长，不是额外左右时间差。长窗让频带边缘更细，也增加公共延迟和计算；过渡带与前振铃仍存在。选频关闭时该档位不参与处理。"})
        number(p.window,170)
        add(info("diff2_levels_help","支路电平与输出修正",200).apply {summary="延迟支路电平改变迟到分量，也可能改变所选声道音量，零时间差时仍可能改变平衡。输出衰减作用于双方；默认0 dB保持第一轮。"})
        number(p.wet,210);number(p.output,220)
        toggle(p.GUARD_ENABLED,"动态混合约束","比较延时对齐基线与未保护候选；联合模式任一有效条件恶化时回收新增混合。用候选信号决定恢复，减少保护反复开关；不能恢复原录音反相成分。",300)
        add(detector);editable+=detector
        p.guard.forEachIndexed { i,c->number(c,320+i*10) }
        toggle(p.TRANSIENT_ENABLED,"瞬态保留","起音时减少新增混合，保持延时读点固定。检测控制与公共延时匹配，并覆盖迟到起音；不分离鼓点、人声，也不保证消除最初起音误差或前振铃。",500)
        p.transient.forEachIndexed { i,c->number(c,510+i*10) }
        add(info("diff2_monitor_help","分频监测与对齐试听",700).apply {summary="低／中／高频使用相同的一阶互补监测滤波器；频带边缘平滑，监测分界不改音频。三组信号为对齐基线、保护前候选、实际输出，均取试听之前的完整100 ms窗口。对齐旁路保留公共延时；效果差分=正常处理输出减对齐原声，不作响度归一化。"})
        number(p.low,710);number(p.high,720);add(response);add(actual)
        prefs.registerOnSharedPreferenceChangeListener(listener);status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(DiffController.STATUS_ACTION));renderCapability()
    }
    private fun add(row: Preference){if(group.findPreference<Preference>(row.key)==null)group.addPreference(row)}
    private fun number(c: BassParameters.Control,order: Int) {
        val row=BassNumericPreference(context,c,p.value(prefs.all,c),if(c==p.window)listOf(20,40,80) else null).apply {
            this.order=order;if(c==p.window)rejectedValueMessage="请输入20、40或80 ms"
        }
        numbers[c.key]=row;editable+=row;add(row)
    }
    private fun toggle(key: String,title: String,description: String,order: Int) {
        val row=object: SwitchPreferenceCompat(context){override fun onBindViewHolder(holder: PreferenceViewHolder){super.onBindViewHolder(holder);(holder.findViewById(android.R.id.summary) as? TextView)?.apply {maxLines=Int.MAX_VALUE;ellipsize=null}}}.apply {
            this.key=key;this.title=title;summary=description;this.order=order;isIconSpaceReserved=false;setDefaultValue(false)
        };editable+=row;add(row)
    }
    private fun renderCapability(){val supported=DiffController.advancedSupported(context);editable.forEach {it.isEnabled=supported};if(supported)group.removePreference(driver) else add(driver);renderStatus()}
    private fun correlation(v: Int)=if(v==Int.MIN_VALUE)"未定义" else String.format(Locale.ROOT,"%+.4f",v/10000.0)
    private fun fold(b: DiffAdvancedParameters.Band)=when {
        b.fold!=Int.MIN_VALUE->String.format(Locale.ROOT,"%.2f dB",b.fold/100.0)
        b.mid==Int.MIN_VALUE && b.side!=Int.MIN_VALUE->"−∞（零Mid）"
        else->"未定义（静音）"
    }
    private fun percent(v: Int)=String.format(Locale.ROOT,"%.1f%%",v/10.0)
    fun renderStatus() {
        val time=p.value(prefs.all,p.fine)
        val rates=DiffController.runtime.map {it.rate}.filter {it in 8000..768000}.distinct()
        units.summary=p.fine.label(time)+"／$time μs；"+if(rates.isEmpty())"采样数等待实际处理采样率" else rates.joinToString("；") {"$it Hz："+String.format(Locale.ROOT,"%.3f采样",it*time/1e6)}
        quality.value=p.quality(prefs.all);detector.value=p.detector(prefs.all)
        val supported=DiffController.advancedSupported(context)
        response.configure(if(supported)rates.firstOrNull() else null,prefs.all)
        actual.summary=when {
            !supported->"当前驱动未提供第二轮监测。"
            DiffController.runtime.isEmpty()->"等待实际音频流与完整监测窗口。"
            else->DiffController.runtime.joinToString("\n\n") { r ->
                val a=r.snapshot?.advanced
                if(a==null)"${r.rate} Hz：等待读数" else {
                    val config="${r.rate} Hz：${if(a.quality==0)"线性" else "三阶 Lagrange"}；微秒精调${if(a.fine)"接管" else "关闭"}；窗长${a.window} ms；支路${a.wet/10.0} dB／输出${a.output/10.0} dB"
                    val signal=when {
                        !prefs.getBoolean("v_diff",false)->"音效关闭"
                        !r.enabled->"主开关关闭"
                        r.processing==OutputRuntimeStatus.Processing.UNKNOWN->"等待读取处理状态"
                        r.processing==OutputRuntimeStatus.Processing.IDLE->"当前未处理音频"
                        !a.ready->"等待完整100 ms窗口"
                        else->{
                            "混合设定${percent(a.staticMix)} → 实际${percent(a.actualMix)}；兼容回收${percent(a.withdrawal)}；瞬态介入${percent(a.protection)}\n"+
                                "全频相关度：对齐基线${correlation(a.values[11])}；未保护候选${correlation(a.values[12])}；输出${correlation(r.snapshot.outputCorrelation)}\n"+
                                (0..2).joinToString("\n") { band->
                                    val label=when(band){0->"低频 <${a.low} Hz";1->"中频 ${a.low}–${a.high} Hz";else->"高频 >${a.high} Hz"}
                                    label+"："+listOf("基线","候选","输出").mapIndexed { s,name->val b=a.band(band,s);"$name ρ${correlation(b.correlation)}／折叠${fold(b)}"}.joinToString("；")
                                }
                        }
                    };config+"\n"+signal
                }
            }
        }
    }
    fun close(){renderFrame.close();response.close();prefs.unregisterOnSharedPreferenceChangeListener(listener);status.unregisterOnSharedPreferenceChangeListener(listener);context.unregisterLocalReceiver(receiver)}
}
