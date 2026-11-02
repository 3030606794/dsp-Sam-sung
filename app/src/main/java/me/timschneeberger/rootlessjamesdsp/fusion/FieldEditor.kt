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

class FieldEditor(private val context: Context, private val group: SwitchPreferenceGroup) {
    private val p = FieldParameters
    private val preferences = FieldController.preferences(context)
    private val status = FieldController.statusPreferences(context)
    private val rows = linkedMapOf<String,BassNumericPreference>()
    private lateinit var advancedEditor: FieldAdvancedEditor
    private val mode = FieldChoicePreference(context,p.MODE,"处理方式",listOf("原版兼容","专业 M/S"),
        inline = group.isProfessionalMode).apply {
        order = 0
        setOnPreferenceChangeListener { _, next -> preferences.edit().putInt(p.MODE,next as Int).apply(); true }
    }
    private fun toggle(key: String,title: String,summary: String,order: Int,default: Boolean = false) = SwitchPreferenceCompat(context).apply {
        this.key = key; this.title = title; this.summary = summary; this.order = order
        isIconSpaceReserved = false; setDefaultValue(default)
    }
    private val band = toggle(p.BAND_ENABLED,"限定成像频带","仅在选定频带渐变作用；频率是柔和过渡范围，不是硬分频边界。范围越窄，宽度作用越弱。",100)
    private val focus = toggle(p.FOCUS_ENABLED,"低频聚焦","降低指定低频范围的 Side；100% 保留原宽度，0% 最大收窄，分界处仍保持柔和过渡。",140)
    private fun depthToggle(selectedMode: Int) = toggle(p.depthKey(selectedMode),"声场深度处理",
        "两种模式分别记忆开关；深度数值沿用原单位，不是毫秒。",40,selectedMode == 0).apply {
        isPersistent = false
        setOnPreferenceChangeListener { _, next -> preferences.edit().putBoolean(key,next as Boolean).apply(); true }
    }
    private val depthSwitches = listOf(depthToggle(0),depthToggle(1))
    private val audition = FieldChoicePreference(context,"field_audition","临时试听",
        listOf("正常立体声","单声道／中央（Mid）","侧向（Side）"),"恢复正常").apply {
        order = 2000
        setOnPreferenceChangeListener { _, next ->
            if (next == 0) { FieldController.stopAudition(context,this@FieldEditor); true }
            else FieldController.startAudition(context,this@FieldEditor,when (next) { 3 -> 4; 2 -> 3; else -> 1 })
        }
    }
    private fun info(key: String,order: Int) = object : RuntimeStatusPreference(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            (holder.findViewById(android.R.id.summary) as? TextView)?.apply {
                maxLines = Int.MAX_VALUE
                ellipsize = null
            }
        }
    }.apply {
        this.key = key; this.order = order; isSelectable = false; isIconSpaceReserved = false
    }
    private val help = info("field_help",2010)
    private val actual = info("field_actual",2020).apply { title = "成像监测与实际频带" }
    private val driver = info("field_driver",2050).apply {
        title = "专业成像需要新版驱动"
        summary = "当前驱动使用原版兼容处理，原有三个参数按整数步长生效；专业参数和临时试听暂不可用。已保存的专业设置保留。"
    }
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            p.MODE,"capabilities","session" -> renderFrame.request()
            "v_field" -> { if (!preferences.getBoolean("v_field",false)) stopAudition(); renderFrame.request(false) }
            p.LEGACY_DEPTH_ENABLED,p.MS_DEPTH_ENABLED,p.BAND_ENABLED,p.FOCUS_ENABLED -> renderFrame.request()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!runtimeStatusVisible(this@FieldEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
             renderFrame.request(false) }
    }
    init {
        p.migrate(preferences); FieldController.stopAudition(context)
        advancedEditor=FieldAdvancedEditor(context,group)
        p.all.forEach { control -> rows[control.key] = BassNumericPreference(context,control,p.value(preferences.all,control)).apply {
            if (control == p.bandLow || control == p.bandHigh) {
                rejectedValueMessage = "成像频带下限必须小于上限"
                setOnPreferenceChangeListener { _, next -> p.acceptsFrequency(preferences.all,control.key,next as Int) }
                rejectedButtonFeedback = {
                    Toast.makeText(context,if (control == p.bandLow) "请先调高上限，再增加或复位下限"
                        else "请先调低下限，再减少或复位上限",Toast.LENGTH_LONG).show()
                }
            }
        } }
        group.onExpandedChanged = { expanded -> if (!expanded) stopAudition() }
        preferences.registerOnSharedPreferenceChangeListener(listener); status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(FieldController.STATUS_ACTION))
        render()
    }
    private fun add(preference: Preference) {
        if (group.findPreference<Preference>(preference.key) == null) group.addPreference(preference)
    }
    private fun render() {
        val supported = FieldController.supported(context)
        val selectedMode = if (supported) p.mode(preferences.all) else 0
        audition.updateChoices(listOf("正常立体声","单声道／中央（Mid）","侧向（Side）")+
            if (FieldController.advancedSupported(context)) listOf("效果差分（第二轮）") else emptyList())
        mode.isEnabled = supported; mode.value = selectedMode; add(mode)
        val controls = (if (selectedMode == 0) listOf(p.legacyWidth,p.legacyCenter) else listOf(p.width)) +
            listOf(p.midTrim,p.depth,p.depthMix,p.outputTrim,p.bandLow,p.bandHigh,p.focusFrequency,p.focusWidth)
        val visible = controls.map { it.key }.toSet()
        if (!group.isProfessionalMode)
            rows.filterKeys { it !in visible }.values.forEach { group.removePreference(it) }
        val orders = mapOf(p.legacyWidth.key to 10,p.legacyCenter.key to 20,p.width.key to 10,p.midTrim.key to 30,
            p.depth.key to 50,p.depthMix.key to 60,p.outputTrim.key to 70,p.bandLow.key to 110,p.bandHigh.key to 120,
            p.focusFrequency.key to 150,p.focusWidth.key to 160)
        val displayed = if (group.isProfessionalMode) listOf(p.legacyWidth,p.legacyCenter,p.width) +
            controls.filter { it != p.legacyWidth && it != p.legacyCenter && it != p.width } else controls
        displayed.forEach { control -> rows.getValue(control.key).apply {
            order = orders.getValue(control.key)
            isEnabled = control.key in visible && (!control.advanced || supported) && when (control) {
                p.depth,p.depthMix -> p.enabled(preferences.all,p.depthKey(selectedMode))
                p.bandLow,p.bandHigh -> p.enabled(preferences.all,p.BAND_ENABLED)
                p.focusFrequency,p.focusWidth -> p.enabled(preferences.all,p.FOCUS_ENABLED)
                else -> true
            }
            add(this)
        } }
        depthSwitches.forEachIndexed { index, preference ->
            preference.isEnabled = supported && index == selectedMode
            preference.title = if (group.isProfessionalMode) {
                if (index == 0) "原版声场深度处理" else "M/S 声场深度处理"
            } else "声场深度处理"
            if (index == selectedMode || group.isProfessionalMode) add(preference) else group.removePreference(preference)
        }
        band.isEnabled = supported; focus.isEnabled = supported; add(band); add(focus)
        add(audition); add(help); add(actual)
        if (supported) group.removePreference(driver) else add(driver)
        help.summary = (if (selectedMode == 0) "原版拓展与中央声像保留旧含义，Mid 修正是在此基础上的额外调整。"
            else "M/S 宽度 100%、Mid 0 dB、深度关闭以及其他新项默认值保持中性。全频处理时宽度 0% 为单声道；限定频带后只渐变收窄所选频段。") +
            " 临时试听不保存到预设；收起卡片、离开页面或关闭音效后恢复正常。相关度与 M/S RMS、sample-peak 监测位于输出衰减之后、试听之前；使用最近完成的 100 ms 窗口，界面约每秒更新。"+
            if (FieldController.advancedSupported(context)) " 效果差分是第二轮处理后减去同参数下旁路第二轮的输出，保留双声道，不是 Side 独奏，也不做响度归一化。" else ""
        renderStatus()
    }
    private fun db(value: Int?) = when (value) {
        null -> "未读取"
        Int.MIN_VALUE -> "−∞ dBFS（无能量）"
        else -> String.format(Locale.ROOT,"%.2f dBFS",value/100.0)
    }
    private fun meters(reading: FieldController.Reading): String = when {
        !preferences.getBoolean("v_field",false) -> "音效关闭"
        !reading.enabled -> "主开关关闭"
        reading.processing == OutputRuntimeStatus.Processing.UNKNOWN -> "等待读取处理状态"
        reading.processing == OutputRuntimeStatus.Processing.IDLE -> "当前未处理音频"
        !reading.meterReady -> "等待完整 100 ms 监测窗口"
        else -> {
            val correlationValue = reading.correlation?.takeIf { it in -10000..10000 }
            val correlation = correlationValue?.let { String.format(Locale.ROOT,"%+.4f",it/10000.0) }
                ?: "未定义（静音或任一声道无方差）"
            "相关度 $correlation；Mid RMS ${db(reading.midRms)}；Side RMS ${db(reading.sideRms)}；sample-peak ${db(reading.peak)}" +
                if (correlationValue != null && correlationValue < 0) "；单声道合并可能减弱部分成分，可试听检查。" else ""
        }
    }
    private fun renderStatus() {
        val supported = FieldController.supported(context)
        val selectedMode = if (supported) p.mode(preferences.all) else 0
        depthSwitches.forEachIndexed { index, preference ->
            preference.isChecked = if (supported) p.enabled(preferences.all,p.depthKey(index)) else index == 0
        }
        audition.isEnabled = supported && FieldController.masterEnabled && preferences.getBoolean("v_field",false)
        audition.value = when (FieldController.audition) { 1,2 -> 1; 3 -> 2; 4 -> 3; else -> 0 }
        actual.summary = when {
            !supported -> "当前驱动不提供专业成像状态。"
            FieldController.runtime.isEmpty() -> "等待音频流配置；静音、单边信号或不足一个窗口时，相关度不显示为零。"
            else -> FieldController.runtime.distinctBy { listOf(it.rate,it.targets,it.processing,it.enabled,it.meterReady,it.correlation,it.midRms,it.sideRms,it.peak) }
                .joinToString("\n") { reading ->
                    val t = reading.targets
                    val band = when {
                        !preferences.getBoolean(p.BAND_ENABLED,false) -> "选频关闭（全频宽度）"
                        t.size != 3 -> "等待频带读数"
                        t[0] <= 0 || t[1] <= t[0] -> "成像频带不可用"
                        else -> "成像 ${t[0]}–${t[1]} Hz"
                    }
                    val focus = when {
                        !preferences.getBoolean(p.FOCUS_ENABLED,false) -> "低频聚焦关闭"
                        t.size != 3 || t[2] <= 0 -> "等待聚焦频率读数"
                        else -> "聚焦 ${t[2]} Hz"
                    }
                    "${reading.rate} Hz：$band，$focus；" + meters(reading)
                }
        }
    }
    fun stopAudition() = FieldController.stopAudition(context,this)
    fun close() { renderFrame.close();
        stopAudition(); group.onExpandedChanged = null
        advancedEditor.close()
        preferences.unregisterOnSharedPreferenceChangeListener(listener); status.unregisterOnSharedPreferenceChangeListener(listener)
        context.unregisterLocalReceiver(receiver)
    }
}
