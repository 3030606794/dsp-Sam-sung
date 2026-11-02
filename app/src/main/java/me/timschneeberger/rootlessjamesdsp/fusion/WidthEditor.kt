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
import kotlin.math.roundToInt

class WidthEditor(private val context: Context,private val group: SwitchPreferenceGroup) {
    private val p=WidthParameters
    private val prefs=WidthController.preferences(context)
    private val status=WidthController.status(context)
    private var closed=false
    private var legacyFormat: Double?=null
    private var advanced: WidthAdvancedEditor?=null
    private val numbers=linkedMapOf<String,BassNumericPreference>()
    private val switches=linkedMapOf<String,SwitchPreferenceCompat>()
    private val choices=linkedMapOf<String,FieldChoicePreference>()
    private fun info(key: String,title: String,order: Int,body: String="")=object : RuntimeStatusPreference(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {super.onBindViewHolder(holder);(holder.findViewById(android.R.id.summary) as? TextView)?.apply {maxLines=Int.MAX_VALUE;ellipsize=null}}
    }.apply {this.key=key;this.title=title;this.order=order;summary=body;isSelectable=false;isIconSpaceReserved=false}
    private val actual=StableRuntimeReadoutPreference(context).apply {
        key="width1_actual";title="本模块输入／输出监测";order=620
    }
    private val support=info("width1_support","驱动与当前模式",40)
    private val legacy=BassNumericPreference(context,p.legacy,legacyValue()).apply {
        isPersistent=false;order=20;setOnPreferenceChangeListener {_,next->prefs.edit().putFloat(p.LEGACY,(next as Int).toFloat()).apply();true}
    }
    private val audition=FieldChoicePreference(context,"width1_audition","临时分量试听",listOf("正常处理","本模块原声","仅中置 Mid","仅侧声 Side"),"恢复正常").apply {
        order=630;setOnPreferenceChangeListener {_,next->if(next==0){stopAudition();true}else WidthController.startAudition(context,this@WidthEditor,next as Int)}
    }
    private val renderFrame=me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate {full->if(full)render()else renderStatus()}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(WidthController.audition>=8 && (key in p.newKeys || key in WidthAdvancedParameters.keys || key==p.LEGACY))stopAudition()
        if(key==p.MODE || (key==p.ENABLED && !prefs.getBoolean(p.ENABLED,false)))stopAudition()
        renderFrame.request()
    }
    private val receiver=object: BroadcastReceiver(){override fun onReceive(context: Context?,intent: Intent?){if(runtimeStatusVisible(this@WidthEditor.context))renderFrame.request(false)}}
    init {
        p.normalize(prefs);WidthController.stopAudition(context)
        group.findPreference<Preference>(p.LEGACY)?.let(group::removePreference)
        add(info("width1_group1","① 处理模式与精确宽度",0,"原算法保留旧30–75刻度。专业M/S宽度0%为单声道、100%保持中侧比例、200%为侧声幅度两倍。两种算法在本卡片内切换；纯单声道不会仅靠扩大Side产生立体声。"))
        choice(p.MODE,"处理模式",listOf("原算法（兼容）","全频 M/S 精确控制","三段 M/S 精确控制"),10);add(legacy);number(p.master,30);add(support)
        add(info("width1_group2","② 三段独立宽度",100,"总体宽度乘以各段宽度，最终侧声倍率最高4倍。分频采用Linkwitz–Riley 12／24 dB/oct；三段及干声／中置采用匹配的全通相位，等宽时幅度平坦。切换模式会改变公共相位，不宣称逐样本直通。联动只影响后续编辑，单项复位仅恢复当前项。"))
        p.bands.forEachIndexed {i,c->number(c,110+i*10)};number(p.crossLow,140);number(p.crossHigh,150)
        choice(p.SPLIT_SLOPE,"三段分频斜率",listOf("12 dB/oct","24 dB/oct"),160,1);toggle(p.LINK,"联动三段宽度",170)
        add(info("width1_group3","③ 低频单声道化",200,"对本模块侧声施加Butterworth高通，中置保持。100%为所选斜率的完整滤波强度；截止频率附近渐变，不能恢复原素材中已相消的低频。"))
        toggle(p.MONO,"启用低频收拢",210);number(p.monoHz,220);number(p.monoAmount,230)
        choice(p.MONO_SLOPE,"低频收拢斜率",listOf("12 dB/oct","24 dB/oct","48 dB/oct"),240,1)
        add(info("width1_group4","④ 中置与输出电平校准",300,"Mid包含中央位置的所有声音。混合为线性干湿比例；三段专业模式的干声匹配分频相位，原算法的干声混入可能影响频响。RMS匹配先比较混合前后平均电平，再施加有界补偿，手动输出校准最后执行；峰值仍由现有限幅器控制。"))
        number(p.midGain,310);number(p.mix,320);number(p.output,330);toggle(p.AUTO,"RMS 自动电平匹配",340);number(p.autoWindow,350);number(p.autoMax,360)
        add(info("width1_group5","⑤ 相关度保护",400,"专业模式监测未保护候选的100 ms相关度，低于阈值逐渐回收新增扩宽；超过阈值加迟滞后恢复。最多回到原始宽度，已经收窄的频段不再变窄。它不能保证修复素材本身的反相，也不影响其他声场模块。"))
        toggle(p.GUARD,"启用相关度保护",410);number(p.threshold,420);number(p.hysteresis,430);number(p.attack,440);number(p.release,450);number(p.strength,460)
        add(info("width1_group6","⑥ 专业监测与分量试听",600,"监测位于本模块处理后、临时试听之前；读数属于JamesDSP内部处理采样率，不代表后续ViPER或设备最终输出。峰值使用1秒衰减保持，RMS及相关度使用下方平滑时间。无信号显示无有效读数。收起、滚出、离页、暂停、停用或会话结束后退出试听，试听不写入预设。"))
        number(p.meterWindow,610);add(actual);add(audition)
        actual.isVisible = true
        advanced=WidthAdvancedEditor(context,group,this)
        group.onExpandedChanged={if(!it)stopAudition();advanced?.render()}
        prefs.registerOnSharedPreferenceChangeListener(listener);status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(WidthController.STATUS_ACTION));render()
    }
    private fun legacyValue()=(prefs.all[p.LEGACY] as? Number)?.toDouble()?.takeIf {it.isFinite()}?.roundToInt()?.coerceIn(30,75) ?: 60
    private fun add(row: Preference){group.addPreference(row)}
    private fun number(c: BassParameters.Control,order: Int) {
        val row=BassNumericPreference(context,c,p.value(prefs.all,c)).apply {this.order=order}
        row.setOnPreferenceChangeListener {_,next->
            val value=next as Int
            if(c in listOf(p.autoWindow,p.meterWindow) && value%10!=0)return@setOnPreferenceChangeListener false
            val edit=prefs.edit().putInt(c.key,value)
            if(c in p.bands && p.on(prefs.all,p.LINK))p.bands.filter {it!=c}.forEach {edit.putInt(it.key,value)}
            edit.apply();true
        }
        if(c in p.bands)row.resetAction={if(!row.isEnabled)false else {prefs.edit().putInt(c.key,c.default).apply();true}}
        if(c in listOf(p.autoWindow,p.meterWindow)) {
            row.sliderRange=c.min..c.max;row.sliderStep=10;row.stepLabel={"10 ms"}
            row.stepAction={delta->row.requestValue((row.value+delta*10).coerceIn(c.min,c.max))};row.rejectedValueMessage="请按10 ms刻度输入"
        }
        numbers[c.key]=row;add(row)
    }
    private fun toggle(key: String,title: String,order: Int) {
        val row=object: SwitchPreferenceCompat(context) {
            override fun onBindViewHolder(holder: PreferenceViewHolder){super.onBindViewHolder(holder);(holder.findViewById(android.R.id.title) as? TextView)?.apply {setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}
        }.apply {this.key=key;this.title=title;this.order=order;isIconSpaceReserved=false;setDefaultValue(false)}
        switches[key]=row;add(row)
    }
    private fun choice(key: String,title: String,items: List<String>,order: Int,default: Int=0) {
        val row=FieldChoicePreference(context,key,title,items,resetSelection=default).apply {this.order=order;setOnPreferenceChangeListener {_,next->prefs.edit().putInt(key,next as Int).apply();true}}
        choices[key]=row;add(row)
    }
    private fun render() {
        if(closed)return
        p.normalize(prefs);val values=prefs.all;val supported=WidthController.supported(context);val mode=p.choice(values,p.MODE)
        numbers.forEach {(_,row)->val next=p.value(values,row.control);if(row.value!=next)row.value=next
            row.isEnabled=supported && when(row.control){p.master->mode!=0;p.low,p.mid,p.high,p.crossLow,p.crossHigh->mode==2;p.threshold,p.hysteresis,p.attack,p.release,p.strength->mode!=0;else->true}}
        switches.forEach {(key,row)->val checked=p.on(values,key);if(row.isChecked!=checked)row.isChecked=checked;row.isEnabled=supported && when(key){p.LINK->mode==2;p.GUARD->mode!=0;else->true}}
        choices.forEach {(key,row)->row.value=p.choice(values,key);row.isEnabled=supported && (key!=p.SPLIT_SLOPE || mode==2)}
        legacy.isEnabled=!supported || mode==0;legacy.value=legacyValue()
        val raw=(values[p.LEGACY] as? Number)?.toDouble()
        val nextFormat=raw?.takeIf {it%1!=0.0 || it !in 30.0..75.0}
        if(legacyFormat!=nextFormat){legacyFormat=nextFormat;legacy.valueFormatter=nextFormat?.let {n->{_: Int -> "$n（保留旧值，驱动取整）"}}}
        support.summary=if(!supported)"当前驱动使用原算法。新增处理和监测需要配套新版驱动；新参数保留，更新后生效。" else "已连接专业宽度驱动。原算法的30–75为旧控制量；专业百分比仅用于M/S模式。保存参数不会自动启用本模块。"
        advanced?.render()
        renderStatus()
    }
    private fun db(v: Int?)=StableRuntimeReadoutPreference.fixedNumber(v)
    private fun rho(v: Int?)=StableRuntimeReadoutPreference.fixedNumber(v,10000.0,4,true)
    private fun width(v: Int?)=StableRuntimeReadoutPreference.fixedNumber(v)+"%"
    private fun listening(v: Int)=WidthAdvancedRuntime.auditionLabel(v)
    fun renderStatus() {
        if(closed)return
        advanced?.renderStatus()
        audition.updateChoices(WidthAdvancedRuntime.auditionLabels.take(if(WidthController.supportedAdvanced(context))10 else 4))
        audition.value=WidthController.audition;audition.isEnabled=WidthController.supported(context) && prefs.getBoolean(p.ENABLED,false) && WidthController.runtime.any {it.enabled}
        val supported=WidthController.supported(context)
        val moduleEnabled=prefs.getBoolean(p.ENABLED,false)
        val readings=WidthController.runtime.map {it as WidthController.Reading?}.ifEmpty {listOf(null)}
        actual.summary=readings.joinToString("\n\n") {r->
            val s=r?.snapshot;val v=s?.data
            val state=when {
                !supported->"当前驱动不支持专业宽度监测；原算法继续使用。"
                r==null->"等待本模块处理会话；无有效读数。"
                s==null->"等待有效驱动快照"
                !moduleEnabled || !r.enabled || !s.enabled->"未启用"
                !r.active->"等待音频／读数未更新"
                !s.ready->"积累监测窗口"
                else->"处理中"
            }
            val signal=supported && moduleEnabled && r!=null && s!=null && r.enabled && s.enabled && s.ready && r.active
            fun sample(i: Int)=if(signal)v?.get(i)else null
            fun saved(i: Int)=if(supported)v?.get(i)else null
            fun hz(value: Int?)=value?.toString()?.padStart(6) ?: "—".padStart(6)
            "James内部 ${hz(s?.rate)} Hz\n$state\n"+
                "输入相关度 ${rho(sample(5))}；输出 ${rho(sample(6))}\n"+
                "输入Mid／Side ${db(sample(7))}／${db(sample(8))} dBFS\n"+
                "输出Mid／Side ${db(sample(9))}／${db(sample(10))} dBFS\n"+
                "Side相对Mid ${db(sample(11))} dB\n输出RMS ${db(sample(14))} dBFS\n"+
                "输入／输出峰值 ${db(sample(12))}／${db(sample(13))} dBFS\n"+
                "保护后主宽度 ${width(saved(15))}\n低／中／高 ${width(saved(16))}／${width(saved(17))}／${width(saved(18))}\n"+
                "宽度值未折算收拢、混合及增量均衡。\n"+
                "最大扩宽回收 ${width(saved(19))}\nRMS补偿 ${db(saved(20))} dB\n"+
                "有效分频目标 ${hz(saved(21))}／${hz(saved(22))} Hz\n收拢目标 ${hz(saved(23))} Hz\n"+
                "实际试听 ${saved(24)?.let(::listening) ?: "—"}\n"+
                if(s?.mode==0)"原算法／不适用：专业宽度值；—表示无有效读数。"else "—表示无有效读数。"
        }
    }
    fun stopAudition(){WidthController.stopAudition(context,this)}
    fun close(){closed=true;renderFrame.close();stopAudition();WidthController.clearComparisons(context);prefs.unregisterOnSharedPreferenceChangeListener(listener);status.unregisterOnSharedPreferenceChangeListener(listener);context.unregisterLocalReceiver(receiver);group.onExpandedChanged=null}
}
