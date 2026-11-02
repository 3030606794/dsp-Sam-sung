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

/** Independently editable sub-groups remain under the existing card's expansion and power switch. */
class FieldAdvancedEditor(private val context: Context, private val group: SwitchPreferenceGroup) {
    private val p = FieldAdvancedParameters
    private val preferences = FieldController.preferences(context)
    private val status = FieldController.statusPreferences(context)
    private val editable = mutableListOf<Preference>()
    private fun info(key: String, title: String, order: Int) = object : RuntimeStatusPreference(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            (holder.findViewById(android.R.id.summary) as? TextView)?.apply { maxLines=Int.MAX_VALUE; ellipsize=null }
        }
    }.apply { this.key=key; this.title=title; this.order=order; isSelectable=false; isIconSpaceReserved=false }
    private val actual = info("field_advanced_actual","进阶成像监测",2030)
    private val driver = info("field_advanced_driver","第二轮成像需要新版驱动",2040).apply {
        summary="当前驱动可继续使用第一轮成像；动态 Side、兼容回收、瞬态保留、微延时、扩散与效果差分试听需要更新驱动。已保存的参数保留。"
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if (key == "capabilities" || key == "session") renderCapability()
        else if (key in p.newKeys) {
            p.migrate(preferences)
            (editable.firstOrNull { it.key==key } as? BassNumericPreference)?.let {
                it.value=p.value(preferences.all,it.control)
            }
            if (key in p.switches) {
                (editable.firstOrNull { it.key==key } as? SwitchPreferenceCompat)?.isChecked=p.enabled(preferences.all,key!!)
                renderStatus()
            }
        } else if (key == "v_field") renderStatus()
    }
    private val renderFrame=me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate {renderStatus()}
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if(runtimeStatusVisible(this@FieldAdvancedEditor.context))renderFrame.request(false) }
    }
    init {
        p.migrate(preferences)
        section(p.DYNAMIC_ENABLED,"动态 Side 控制","检测 Side RMS 电平，超过阈值时只衰减 Side；不是相关度修复。动态值仅用于监测，不改写静态宽度。",300,p.dynamic)
        section(p.GUARD_ENABLED,"相关度约束","仅在新增处理使相关度比基线更差且低于阈值时，收回新增扩宽／空间；通过迟滞和平滑恢复减少反复变化，不能修复原始反相。",500,p.guard)
        section(p.TRANSIENT_ENABLED,"瞬态保留","根据起音相对慢包络的电平差暂时减少新增空间，再平滑恢复；不是人声或鼓点分离。保护量与兼容回收分别显示，不直接相加。",700,p.transient)
        section(p.DELAY_ENABLED,"侧向微延时","仅处理已有 Side，保留直接中央声；支持真实分数采样延时。单声道输入不会因此生成立体声。",900,p.delay)
        section(p.DIFFUSION_ENABLED,"全通扩散","对已有 Side 分散相位，再按混合量加入空间；1／2／4 级是离散选择。中心及跨度受当前采样率限制。干湿混合可能使某些频率的 Side 明显衰减、声场收窄；全通本身幅频平坦不代表混合无染色。",1100,p.diffusion)
        group.addPreference(actual)
        preferences.registerOnSharedPreferenceChangeListener(listener); status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(FieldController.STATUS_ACTION))
        renderCapability()
    }
    private fun section(key: String,title: String,summary: String,order: Int,controls: List<BassParameters.Control>) {
        val toggle = object : SwitchPreferenceCompat(context) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                (holder.findViewById(android.R.id.summary) as? TextView)?.apply { maxLines=Int.MAX_VALUE; ellipsize=null }
            }
        }.apply {
            this.key=key; this.title=title; this.summary=summary; this.order=order
            isIconSpaceReserved=false; setDefaultValue(false)
        }
        editable += toggle; group.addPreference(toggle)
        controls.forEachIndexed { index,control ->
            val preference = BassNumericPreference(context,control,p.value(preferences.all,control),
                if (control == p.diffusionStages) listOf(1,2,4) else null).apply {
                this.order=order+(index+1)*10
                if (control == p.diffusionStages) rejectedValueMessage="请输入 1、2 或 4 级"
            }
            editable += preference; group.addPreference(preference)
        }
    }
    private fun renderCapability() {
        val supported=FieldController.advancedSupported(context)
        editable.forEach { it.isEnabled=supported }
        if (supported) group.removePreference(driver) else if (group.findPreference<Preference>(driver.key)==null) group.addPreference(driver)
        renderStatus()
    }
    private fun db(value: Int?) = when (value) { null -> "未读取"; Int.MIN_VALUE -> "−∞ dBFS（无能量）"; else -> String.format(Locale.ROOT,"%.2f dBFS",value/100.0) }
    private fun correlation(value: Int?) = value?.takeIf { it in -10000..10000 }?.let { String.format(Locale.ROOT,"%+.4f",it/10000.0) } ?: "未定义"
    private fun percent(value: Int) = String.format(Locale.ROOT,"%.1f%%",value/10.0)
    private fun enabled(key: String) = p.enabled(preferences.all,key)
    private fun meter(reading: FieldController.Reading, data: FieldAdvancedParameters.Runtime): String = when {
        !preferences.getBoolean("v_field",false) -> "音效关闭"
        !reading.enabled -> "主开关关闭"
        reading.processing == OutputRuntimeStatus.Processing.UNKNOWN -> "等待读取处理状态"
        reading.processing == OutputRuntimeStatus.Processing.IDLE -> "当前未处理音频"
        !reading.meterReady || !data.ready -> "等待完整 100 ms 监测窗口"
        else -> {
            val fold = if (data.monoFoldDifference != Int.MIN_VALUE) String.format(Locale.ROOT,"%.2f dB",data.monoFoldDifference/100.0)
                else if (reading.midRms == Int.MIN_VALUE && reading.sideRms != null && reading.sideRms != Int.MIN_VALUE)
                    "−∞（合并后无中央能量）" else "未定义（静音或无有效能量）"
            val dynamic=if (enabled(p.DYNAMIC_ENABLED)) String.format(Locale.ROOT,"%.2f dB",data.dynamicReduction/100.0) else "关闭"
            val guard=if (enabled(p.GUARD_ENABLED)) percent(data.guardRecovery) else "关闭"
            val transient=if (enabled(p.TRANSIENT_ENABLED)) percent(data.transientProtection) else "关闭"
            "输入相关度 ${correlation(data.inputCorrelation)} → 输出 ${correlation(reading.correlation)}；兼容基线 ${correlation(data.guardBaseline)}\n"+
                "输入 Mid RMS ${db(data.inputMid)}；输入 Side RMS ${db(data.inputSide)}\n"+
                "单声道折叠电平差 $fold；Side 动态衰减 $dynamic；兼容回收 $guard；瞬态保护 $transient"
        }
    }
    fun renderStatus() {
        actual.summary=when {
            !FieldController.advancedSupported(context) -> "当前驱动未提供第二轮监测。"
            FieldController.runtime.isEmpty() -> "等待音频流配置后显示实际分数延时、全通频点范围与同窗口监测。"
            else -> FieldController.runtime.distinctBy { listOf(it.rate,it.enabled,it.processing,it.meterReady,it.correlation,it.midRms,it.sideRms,it.advanced) }.joinToString("\n") { reading ->
                val data=reading.advanced
                if (data==null) "${reading.rate} Hz：等待第二轮读数" else {
                    val delay=String.format(Locale.ROOT,"%.2f ms／%.3f 采样",data.delayUs/1000.0,data.delaySamplesMilli/1000.0)
                    val diffusion=if (data.diffusionCenter<=0 || data.diffusionLow<=0 || data.diffusionHigh<=0) "频点不可用"
                        else "中心 ${data.diffusionCenter} Hz，全通频点 ${data.diffusionLow}–${data.diffusionHigh} Hz"
                    "${reading.rate} Hz：微延时${if (enabled(p.DELAY_ENABLED)) "开启" else "关闭"}，配置目标 $delay；"+
                        "扩散${if (enabled(p.DIFFUSION_ENABLED)) "开启" else "关闭"}，配置目标 $diffusion\n"+meter(reading,data)
                }
            }
        }
    }
    fun close() {
        renderFrame.close()
        preferences.unregisterOnSharedPreferenceChangeListener(listener); status.unregisterOnSharedPreferenceChangeListener(listener)
        context.unregisterLocalReceiver(receiver)
    }
}
