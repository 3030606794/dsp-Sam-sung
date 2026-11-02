package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.widget.TextView
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale

/** A channel delay has its own post-processor meters and temporary listening point. */
class DiffEditor(private val context: Context,private val group: SwitchPreferenceGroup) {
    private val p=DiffParameters
    private val prefs=DiffController.preferences(context)
    private val status=DiffController.statusPreferences(context)
    private val rows=linkedMapOf<String,BassNumericPreference>()
    private lateinit var advancedEditor: DiffAdvancedEditor
    private val mode=FieldChoicePreference(context,p.MODE,"延时方式",listOf("原版兼容","专业精密"),inline=group.isProfessionalMode).apply {
        order=0
        setOnPreferenceChangeListener { _,next -> prefs.edit().putInt(p.MODE,next as Int).apply(); true }
    }
    private val channel=FieldChoicePreference(context,p.CHANNEL,"延迟声道",listOf("右声道","左声道"),inline=group.isProfessionalMode).apply {
        order=20
        setOnPreferenceChangeListener { _,next -> prefs.edit().putInt(p.CHANNEL,next as Int).apply(); true }
    }
    private fun info(key: String,title: String,order: Int)=object : RuntimeStatusPreference(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            (holder.findViewById(android.R.id.summary) as? TextView)?.apply { maxLines=Int.MAX_VALUE; ellipsize=null }
        }
    }.apply { this.key=key; this.title=title; this.order=order; isSelectable=false; isIconSpaceReserved=false }
    private val mixHelp=info("diff_mix_help","延迟混合",35).apply {
        summary="仅混合所选声道的原声与延迟声，另一声道不增加差分延时。0% 保留原声，100% 完全使用延迟声；中间混合可能产生频率相消，并非越多越宽。选频开启时双方匹配公共滤波延时。"
    }
    private val band=object: SwitchPreferenceCompat(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            (holder.findViewById(android.R.id.summary) as? TextView)?.apply { maxLines=Int.MAX_VALUE; ellipsize=null }
        }
    }.apply {
        key=p.BAND_ENABLED; title="限定延时作用频带"; order=40; isIconSpaceReserved=false; setDefaultValue(false)
        summary="只对选定频带的新增延时差量处理；原始全频信号保留，但两声道都匹配公共滤波延时。频带具有过渡，不是硬切分；实际目标边界受采样率限制。"
    }
    private val audition=FieldChoicePreference(context,"diff_audition","临时对照",
        listOf("正常处理结果","本模块旁路对照","处理后单声道合并"),"恢复正常").apply {
        order=80
        setOnPreferenceChangeListener { _,next ->
            if(next==0) { stopAudition(); true } else DiffController.startAudition(context,this@DiffEditor,next as Int)
        }
    }
    private val help=info("diff_help","使用说明",90)
    private val actual=info("diff_actual","延时状态与兼容监测",100)
    private val driver=info("diff_driver","专业延时需要新版驱动",110).apply {
        summary="当前驱动仍使用原版 1–20 ms 整数延时；精密延时、声道选择、混合、选频、对照与监测需要更新驱动。已保存的专业参数保留。"
    }
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        when {
            key=="capabilities" || key=="session" || key==p.MODE || key==DiffAdvancedParameters.FINE_ENABLED -> renderFrame.request()
            key=="v_diff" -> { if(!prefs.getBoolean("v_diff",false))stopAudition(); renderFrame.request(false) }
            key==p.CHANNEL || key==p.BAND_ENABLED -> {
                p.migrate(prefs); channel.value=p.channel(prefs.all)
                if(key==p.BAND_ENABLED)band.isChecked=p.bandEnabled(prefs.all)
                renderFrame.request(false)
            }
            p.all.any { it.key==key } -> { p.migrate(prefs); rows[key]?.let { it.value=p.value(prefs.all,it.control) } }
        }
    }
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context?,intent: Intent?) {
            if (!runtimeStatusVisible(this@DiffEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
             renderFrame.request(false) }
    }
    init {
        p.migrate(prefs); DiffController.stopAudition(context)
        p.all.forEach { c -> rows[c.key]=BassNumericPreference(context,c,p.value(prefs.all,c)).apply {
            order=when(c) { p.legacy,p.delay -> 10; p.mix -> 30; p.low -> 50; else -> 60 }
            if(c==p.low || c==p.high) {
                rejectedValueMessage="作用频带下限必须小于上限"
                setOnPreferenceChangeListener { _,v -> p.acceptsFrequency(prefs.all,c.key,v as Int) }
                rejectedButtonFeedback={ Toast.makeText(context,if(c==p.low)"请先调高上限，再增加或复位下限"
                    else "请先调低下限，再减少或复位上限",Toast.LENGTH_LONG).show() }
            }
        } }
        group.onExpandedChanged={ if(!it)stopAudition() }
        advancedEditor=DiffAdvancedEditor(context,group)
        prefs.registerOnSharedPreferenceChangeListener(listener); status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(DiffController.STATUS_ACTION))
        render()
    }
    private fun add(row: Preference) { if(group.findPreference<Preference>(row.key)==null)group.addPreference(row) }
    private fun render() {
        p.migrate(prefs)
        val supported=DiffController.supported(context)
        val precise=p.mode(prefs.all)==1 && supported
        mode.isEnabled=supported; mode.value=if(supported)p.mode(prefs.all) else 0; add(mode)
        rows.values.forEach { row ->
            val visible=if(precise)row.control!=p.legacy else row.control==p.legacy
            row.isEnabled=visible && (row.control==p.legacy || supported)
            if(row.control==p.delay && precise && DiffController.advancedSupported(context) && DiffAdvancedParameters.enabled(prefs.all,DiffAdvancedParameters.FINE_ENABLED))row.isEnabled=false
            if(visible || group.isProfessionalMode)add(row) else group.removePreference(row)
        }
        channel.isEnabled=supported && precise; channel.value=p.channel(prefs.all); band.isEnabled=supported && precise
        listOf(channel,mixHelp,band).forEach { if(precise || group.isProfessionalMode)add(it) else group.removePreference(it) }
        add(audition); add(help); add(actual)
        if(supported)group.removePreference(driver) else add(driver)
        help.summary=(if(precise)"专业模式提供真实分数采样延时；0.00 ms 不引入左右时间差，选频开启时仍有双方匹配的公共滤波延时。" else
            "原版兼容保持左声道直通、右声道整采样延迟；原 1–20 ms 参数及旧预设含义不变。")+
            " 临时对照位于本模块之后，监测位于对照之前；后续混响等效果仍会改变最终输出。对照不保存到预设，收起、滚出卡片、离页或关闭音效后恢复正常。"
        // Preserve the original help, live status and response row below the added charts.
        listOf("diff_help", "diff_actual", "diff2_actual", "diff2_response").forEach { key ->
            group.findPreference<Preference>(key)?.isVisible = true
        }
        renderStatus()
    }
    private fun correlation(v: Int)=if(v==Int.MIN_VALUE)"未定义（静音或任一声道无方差）" else String.format(Locale.ROOT,"%+.4f",v/10000.0)
    private fun db(v: Int)=if(v==Int.MIN_VALUE)"−∞ dBFS（无能量）" else String.format(Locale.ROOT,"%.2f dBFS",v/100.0)
    private fun fold(v: Int,mid: Int,side: Int)=if(v!=Int.MIN_VALUE)String.format(Locale.ROOT,"%.2f dB",v/100.0)
        else if(mid==Int.MIN_VALUE && side!=Int.MIN_VALUE)"−∞（合并后无中央能量）" else "未定义（静音）"
    private fun meters(r: DiffController.Reading,s: DiffRuntimeReader.Snapshot): String=when {
        !prefs.getBoolean("v_diff",false) -> "音效关闭"
        !r.enabled -> "主开关关闭"
        r.processing==OutputRuntimeStatus.Processing.UNKNOWN -> "等待读取处理状态"
        r.processing==OutputRuntimeStatus.Processing.IDLE -> "当前未处理音频"
        !s.ready -> "等待完整 100 ms 监测窗口"
        else -> {
            "输入相关度 ${correlation(s.inputCorrelation)}；输出相关度 ${correlation(s.outputCorrelation)}\n"+
                "单声道折叠电平差：输入 ${fold(s.inputMonoFold,s.inputMidRms,s.inputSideRms)}；输出 ${fold(s.monoFold,s.midRms,s.sideRms)}\n"+
                "输入峰值 ${db(s.inputPeak)}；输出峰值 ${db(s.peak)}"+
                if(s.outputCorrelation in -10000..-1)"；单声道合并可能减弱部分成分，可试听检查。" else ""
        }
    }
    fun renderStatus() {
        val supported=DiffController.supported(context)
        audition.updateChoices(listOf("正常处理结果","本模块旁路对照","处理后单声道合并")+
            if(DiffController.advancedSupported(context))listOf("延时对齐旁路","本模块效果差分") else emptyList())
        audition.isEnabled=supported && DiffController.masterEnabled && prefs.getBoolean("v_diff",false)
        audition.value=DiffController.audition
        actual.summary=when {
            !supported -> "当前驱动未提供精密延时与处理后监测。"
            DiffController.runtime.isEmpty() -> "等待音频流配置后显示实际延时和同窗口监测。"
            else -> DiffController.runtime.joinToString("\n") { r ->
                val s=r.snapshot
                if(s==null)"${r.rate} Hz：等待读数" else {
                    val delay=String.format(Locale.ROOT,if(s.advanced?.fine==true)"目标 %.3f ms；有效时差 %.3f ms／%.3f 采样" else "目标 %.2f ms；有效时差 %.3f ms／%.3f 采样",s.delayUs/1000.0,s.samplesMilli.toDouble()/r.rate,s.samplesMilli/1000.0)
                    val common=String.format(Locale.ROOT,"%.3f ms／%d 采样",s.commonUs/1000.0,s.commonSamples)
                    val bandText=if(!s.bandEnabled)"选频关闭（全频延时）"
                        else if(s.lowHz<=0 || s.highHz<=s.lowHz)"作用频带不可用" else "有效频带目标 ${s.lowHz}–${s.highHz} Hz"
                    val mix=p.mix.label(s.mix)
                    "${r.rate} Hz：${if(s.mode==0)"原版兼容" else "专业精密"}，延迟${if(s.channel==0)"右" else "左"}声道，混合目标 $mix\n"+
                        (if(s.switching)"正在平滑切换，以下为目标配置：\n" else "")+
                        "$delay；公共滤波延时 $common；$bandText\n"+meters(r,s)
                }
            }
        }
    }
    fun stopAudition()=DiffController.stopAudition(context,this)
    fun close() { renderFrame.close();
        stopAudition(); group.onExpandedChanged=null
        advancedEditor.close()
        prefs.unregisterOnSharedPreferenceChangeListener(listener); status.unregisterOnSharedPreferenceChangeListener(listener)
        context.unregisterLocalReceiver(receiver)
    }
}
