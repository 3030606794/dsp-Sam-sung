package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.widget.TextView
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.ui.IrHelpPreference
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale
import kotlin.math.roundToInt

/** Six groups extend the existing convolution card without owning its persistent power switch. */
class ConvolutionEditor(private val context: Context,private val group: SwitchPreferenceGroup,private val professionalMode: Boolean=false) {
    private val advancedEditor by lazy {if(professionalMode)ConvolutionAdvancedEditor(context,group,this)else null}
    private val p=ConvolutionParameters
    private val prefs=ConvolutionController.preferences(context)
    private val status=ConvolutionController.statusPreferences(context)
    private val numbers=linkedMapOf<String,BassNumericPreference>()
    private val runtimeRows=mutableListOf<Preference>()
    private val legacyRows=mutableListOf<BassNumericPreference>()
    private val toggles=linkedMapOf<String,SwitchPreferenceCompat>()
    private val response by lazy {if(professionalMode)null else ConvolutionResponsePreference(context).apply {order=540}}
    private var displayedReadyIr: ConvolutionController.PreparedIr?=null
    private fun info(key: String,title: String,order: Int)=object : RuntimeStatusPreference(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder){super.onBindViewHolder(holder);(holder.findViewById(android.R.id.summary) as? TextView)?.apply {
            maxLines=if(professionalMode)Int.MAX_VALUE else 4
            ellipsize=if(professionalMode)null else android.text.TextUtils.TruncateAt.END
        }}
    }.apply {this.key=key;this.title=title;this.order=order;isSelectable=false;isIconSpaceReserved=false}
    private fun stableInfo(key:String,title:String,order:Int)=StableRuntimeReadoutPreference(context).apply {
        this.key=key;this.title=title;this.order=order
    }
    private val units=stableInfo("conv1_time_units","实际时间单位",185)
    private val legacyWarning=info("conv1_legacy_warning","原参数校验  !",195).apply {
        isSelectable=true
        setOnPreferenceClickListener {ParameterHelp.show(context,"原参数校验",
            technical="头尾阈值相对整组 IR 峰值，仅收缩模式使用。原版四核移位按整数采样保存，实际范围由 IR 长度校验。旧值超出范围时保留原文本，修改单项不会覆盖其它旧项。",
            range="兼容文本应包含六项；完整时可单独输入或复位，项数错误时请先检查兼容文本。");true}
    }
    private val metadata=stableInfo("conv1_metadata","IR 文件与装载状态",510)
    private val actual=stableInfo("conv1_actual","卷积同窗口监测",550)
    private val driver=info("conv1_driver","驱动功能  !",570).apply {
        summary="部分调节需要配套新版驱动；设置已保存。";isSelectable=true
        setOnPreferenceClickListener {ParameterHelp.show(context,"驱动功能",technical="当前驱动保留旧卷积处理。混合、电平、预延时、湿声校准、临时对照和同窗口监测需要配套新版驱动；已保存的新参数保留。");true}
    }
    private val norm=FieldChoicePreference(context,p.NORM_MODE,"IR 归一化方式",listOf("关闭（保留原电平）","整组 IR 峰值归一化")).apply {
        order=310;setOnPreferenceChangeListener {_,next->prefs.edit().putInt(p.NORM_MODE,next as Int).apply();true}
    }
    private val audition=FieldChoicePreference(context,"conv1_audition","临时对照",listOf("正常混合输出","本模块原声旁路","仅卷积湿声","效果差分（混合输出减原声）"),"恢复正常").apply {
        order=560;setOnPreferenceChangeListener {_,next->if(next==0){stopAudition();true}else ConvolutionController.startAudition(context,this@ConvolutionEditor,next as Int)}
    }
    private val renderFrame=me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate {full->if(full)render()else renderStatus()}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key==p.ENABLED && !prefs.getBoolean(p.ENABLED,false))stopAudition()
        if(key in p.newKeys || key==p.LEGACY_ADVANCED || key==p.LEGACY_MODE || key==p.FILE || key==p.ENABLED || key=="capabilities" || key=="session")renderFrame.request()
    }
    private val receiver=object: BroadcastReceiver(){override fun onReceive(context: Context?,intent: Intent?){
            if (!runtimeStatusVisible(this@ConvolutionEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
            renderFrame.request(false)}}
    init {
        p.normalize(prefs);ConvolutionController.stopAudition(context)
        group.findPreference<Preference>(p.FILE)?.order=0
        group.findPreference<Preference>(p.LEGACY_MODE)?.order=10
        group.findPreference<EditTextPreference>(p.LEGACY_ADVANCED)?.apply {order=190;title="原版六参数文本（兼容）";isVisible=professionalMode}
        add(IrHelpPreference(context,"conv1_level_help","电平与干湿混合",20,"混合使用线性比例：中间比例受 IR 相位影响，可能相消。湿声增益只作用于卷积分量，模块输出衰减作用于本模块混合输出，不替代末端总音量或限幅器。","混合 0% 为原声，100% 为卷积湿声；每项支持独立微调与复位。"))
        number(p.mix,30,true);number(p.wet,40,true);number(p.output,50,true)
        add(IrHelpPreference(context,"conv1_time_help","时间与核移位",100,"湿声预延时按卷积实际采样率量化为整数采样；未知采样率时不假设 48 kHz。原版移位以各 IR 核整数采样保存，并非独立播放声道延时。预延时不改原版四核参数。","预延时的 ± 每次移动一个实际采样；实际采样率和量化时间显示在调节下方。"))
        number(p.predelay,110,true)
        val old=p.legacy(prefs.all)
        repeat(6){i->
            val c=BassParameters.Control("conv1_legacy_field_$i",when(i){0->"头部裁剪阈值";1->"尾部裁剪阈值";else->"IR 核 ${i-1} 原版移位"},if(i<2)-160 else 0,if(i<2)0 else Int.MAX_VALUE-99,if(i==0)-80 else if(i==1)-100 else 0,unit=if(i<2)"dB" else "采样")
            val row=BassNumericPreference(context,c,old?.get(i)?.takeIf {it in c.min..c.max} ?: c.default).apply {
                isPersistent=false;order=120+i*10;notifyUnchangedEdits=true
                setOnPreferenceChangeListener {_,next->
                    val fields=p.legacyText(prefs.all).split(';').takeIf {it.size==6}?.toMutableList()
                    if(fields==null)false else {fields[i]=(next as Int).toString();prefs.edit().putString(p.LEGACY_ADVANCED,fields.joinToString(";")).apply();true}
                }
                resetAction={requestValue(c.default)}
            };legacyRows+=row;add(row)
        }
        add(units);add(legacyWarning)
        add(IrHelpPreference(context,"conv1_window_help","IR 时间窗与淡入淡出",200,"对原版优化后的工作 IR 再取窗口。尾部使用余弦淡出，过短时间窗会改变低频与空间尾音；原文件保持完整。","起点、长度与淡入淡出按 0.1 ms 编辑，再量化为实际采样；长度 0 保留起点后的全部。范围由当前工作 IR 时长决定。"))
        toggle(p.WINDOW_ENABLED,"启用 IR 时间窗",210,false)
        p.window.forEachIndexed {i,c->number(c,220+i*10)}
        add(IrHelpPreference(context,"conv1_norm_help","IR 峰值归一化",300,"在文件原采样率完成优化、时间窗及高级编辑后，按整组样本峰值计算一次缩放。各工作采样率共用这份增益，并补偿 IR 重采样的频响增益；实际工作核样本峰值可随采样率变化。保持核之间比例；这不等于频响峰值限制，也不能保证任意音乐不削波。","默认关闭；目标是原采样率参考峰值，可选择仅允许衰减。切换播放采样率不会自动把声音越放越大。"))
        add(norm);number(p.normTarget,320);toggle(p.NORM_ONLY_ATTENUATION,"仅允许归一化衰减",330,true)
        add(IrHelpPreference(context,"conv1_channel_help","湿声输出声道校准",400,"左／右校准、极性与交换位于卷积湿声输出，原声支路不受影响。声道联动只决定后续编辑是否同步两侧，不会覆盖已保存差异；四路径内部增益、极性和路由在路径矩阵中设置。","单项复位仅恢复当前项；左右联动只影响后续增益编辑。"))
        toggle(p.CHANNEL_LINK,"左右校准联动",410,true)
        number(p.left,420,true);number(p.right,430,true)
        toggle(p.INVERT_LEFT,"湿声左声道反相",440,false,true);toggle(p.INVERT_RIGHT,"湿声右声道反相",450,false,true);toggle(p.SWAP,"交换湿声左右声道",460,false,true)
        add(IrHelpPreference(context,"conv1_analysis_help","IR 分析与临时试听",500,"生成的工作 IR 与驱动当前核按 CRC 核对。监测分为模块输入、校准后湿声、正常混合输出，位于临时试听之前，仅属于本模块。旁路不补偿 IR 自身前置静音或湿声预延时；效果差分不作响度归一化。","收起、滚出卡片、离页或关闭音效后恢复正常；临时试听不写入预设。顶部图表下方显示当前文件、CRC 与实际处理状态。"))
        add(metadata)
        if(!professionalMode)response?.let(::add)else add(IrHelpPreference(context,"conv1_response_description","工作 IR 分析预览说明",540,
            "曲线是各核离散频率网格上的幅频预览；波形是带符号峰值包络。紫、绿、橙、蓝依次对应核 1–4。不含干湿混合、湿声输出校准和后续音效，不是音乐实测或群延迟图。",
            "幅频横轴为 Hz，纵轴为 dB；波形按本组峰值显示相对幅度。CRC 确认表示当前工作核与驱动装载内容一致。等待工作 IR 预处理完成；图纸已移至顶部焦点联动区。").apply {
                summary="各核离散频率幅频预览与带符号峰值包络，紫／绿／橙／蓝对应核1–4；不含干湿混合、湿声输出校准与后续效果，不是音乐实测或群延迟。幅频为Hz/dB，波形按整组峰值显示相对幅度；CRC确认表示工作核与驱动装载一致。图纸移至顶部焦点区，等待工作IR预处理完成。"
            })
        add(actual);add(audition)
        group.onExpandedChanged={if(!it)stopAudition()}
        prefs.registerOnSharedPreferenceChangeListener(listener);status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(ConvolutionController.STATUS_ACTION));advancedEditor;render()
    }
    private fun add(row: Preference){
        if(!professionalMode && row.order>110 && row!==units && row!==driver)return
        if(group.findPreference<Preference>(row.key)==null)group.addPreference(row)
    }
    private fun number(c: BassParameters.Control,order: Int,runtime: Boolean=false) {
        val row=BassNumericPreference(context,c,p.value(prefs.all,c)).apply {this.order=order}
        row.setOnPreferenceChangeListener {_,next->
            val candidate=next as Int
            if(c in p.window && candidate%100!=0)return@setOnPreferenceChangeListener false
            val edit=prefs.edit().putInt(c.key,candidate)
            if((c==p.left || c==p.right) && p.enabled(prefs.all,p.CHANNEL_LINK))edit.putInt(if(c==p.left)p.right.key else p.left.key,candidate)
            edit.apply()
            true
        }
        if(c==p.left || c==p.right)row.resetAction={
            if(!row.isEnabled)false else {prefs.edit().putInt(c.key,c.default).apply();true}
        }
        if(c in p.window) {
            row.stepLabel={"0.1 ms"}
            row.sliderStep=100;row.sliderRange=0..if(c==p.fadeIn || c==p.fadeOut)c.max else 2000000
            row.valueTransform={value->((value.toLong()+50)/100*100).coerceIn(c.min.toLong(),c.max.toLong()).toInt()}
            row.stepAction={delta->row.requestValue((row.value.toLong()+delta*100).coerceIn(c.min.toLong(),c.max.toLong()).toInt())}
            row.rejectedValueMessage="请按 0.1 ms 刻度输入；超出实际 IR 的窗口会保留设定并提示错误。"
        }
        if(c==p.predelay) {
            row.stepLabel={"1 个实际采样"}
            row.valueTransform={value->actualRate()?.let {rate->((value.toDouble()*rate/1e6).roundToInt()*1e6/rate).roundToInt().coerceIn(c.min,c.max)} ?: value}
            row.stepAction={delta->actualRate()?.let {rate->row.requestValue((((row.value.toDouble()*rate/1e6).roundToInt()+delta).coerceAtLeast(0)*1e6/rate).roundToInt().coerceIn(c.min,c.max))} ?: false}
            row.rejectedButtonFeedback={Toast.makeText(context,"预延时微调需要实际卷积采样率",Toast.LENGTH_SHORT).show()}
            row.valueFormatter={value->String.format(Locale.ROOT,"%.3f ms",value/1000.0)}
        }
        numbers[c.key]=row;if(runtime)runtimeRows+=row;add(row)
    }
    private fun toggle(key: String,title: String,order: Int,default: Boolean,runtime: Boolean=false) {
        val row=SwitchPreferenceCompat(context).apply {this.key=key;this.title=title;this.order=order;isIconSpaceReserved=false;setDefaultValue(default)}
        toggles[key]=row;if(runtime)runtimeRows+=row;add(row)
    }
    private fun actualRate()=ConvolutionController.runtime.mapNotNull {it.snapshot?.rate?.takeIf {rate->rate in 8000..768000}}.distinct().singleOrNull()
    private fun render() {
        p.normalize(prefs);val values=prefs.all
        numbers.forEach {(key,row)->val next=p.value(values,row.control);if(row.value!=next)row.value=next}
        norm.value=p.normMode(values)
        toggles.forEach {(key,row)->val next=p.enabled(values,key);if(row.isChecked!=next)row.isChecked=next}
        val fields=p.legacyText(values).split(';');var invalid=fields.size!=6
        legacyRows.forEachIndexed {i,row->
            val text=fields.getOrNull(i);val number=text?.trim()?.toIntOrNull()?.takeIf {it in row.control.min..row.control.max}
            if(number==null)invalid=true
            row.isEnabled=fields.size==6
            row.valueFormatter=if(number==null) { { _ -> "旧值：${text ?: "缺失"}" } }else null
            if(row.value!=(number ?: row.control.default))row.value=number ?: row.control.default
        }
        legacyWarning.summary=if(invalid)"旧值超出范围或六项格式无效；原文本已保留。" else "六参数有效 · 移位范围按当前 IR 核校验"
        val supported=ConvolutionController.supported(context);runtimeRows.filterNot {it.key==p.right.key}.forEach {it.isEnabled=supported}
        if(supported)group.removePreference(driver)else add(driver)
        renderStatus()
    }
    private fun db(value: Int)=when(value){Int.MIN_VALUE->"未定义／无能量";else->String.format(Locale.ROOT,"%.1f dBFS",value/10.0)}
    private fun irPeak(value: Double)=when {
        value.isNaN()->"未知"
        value==Double.NEGATIVE_INFINITY->"−∞ dBFS"
        value.isFinite()->String.format(Locale.ROOT,"%.2f dBFS",value)
        else->"无效读数"
    }
    private fun meter(m: ConvolutionRuntimeReader.Meter): String {
        val rho=if(m.correlation==Int.MIN_VALUE)"未定义"else String.format(Locale.ROOT,"%+.4f",m.correlation/10000.0)
        val fold=when(m.fold){Int.MIN_VALUE->"未定义";ConvolutionRuntimeReader.FOLD_NEGATIVE_INFINITY->"−∞（零中央分量）";else->String.format(Locale.ROOT,"%.1f dB",m.fold/10.0)}
        return "峰值 ${db(m.peak)}；RMS ${db(m.rms)}；相关度 $rho；折叠差 $fold"
    }
    fun renderStatus() {
        val rate=actualRate();val time=p.value(prefs.all,p.predelay)
        units.summary=if(rate==null)"预延时 ${p.predelay.label(time)} · 等待实际采样率，± 暂不可用" else
            "$rate Hz · 预延时 ${(time.toDouble()*rate/1e6).roundToInt()} 采样 · 每采样 ${String.format(Locale.ROOT,"%.6f",1e3/rate)} ms"
        val prepared=ConvolutionController.prepared
        if(prepared?.status!=ConvolutionController.Preparation.PROCESSING)
            displayedReadyIr=prepared?.takeIf {it.hasWorkingKernel}
        val ready=displayedReadyIr
        val durationUs=ready?.let {
            kotlin.math.floor(it.beforeWindowFrames*1e6/it.processingSampleRate/100).toLong().times(100).coerceIn(0,Int.MAX_VALUE-99L).toInt()
        }
        numbers[p.start.key]?.apply {sliderRange=0..(ready?.let {kotlin.math.floor((it.beforeWindowFrames-1).coerceAtLeast(0)*1e6/it.processingSampleRate/100).toLong().times(100).coerceAtMost(Int.MAX_VALUE-99L).toInt()} ?: 2000000);sliderEnabled=ready!=null}
        numbers[p.length.key]?.apply {sliderRange=0..(durationUs ?: 2000000);sliderEnabled=ready!=null}
        listOf(p.fadeIn,p.fadeOut).forEach {c->numbers[c.key]?.apply {sliderRange=0..(durationUs?.coerceAtMost(c.max) ?: c.max);sliderEnabled=ready!=null}}
        legacyRows.drop(2).forEach {row->row.sliderRange=0..((ready?.beforeWindowFrames ?: 200001)-1).coerceAtLeast(1);row.sliderEnabled=ready!=null}
        val values=prefs.all
        p.window.forEach {numbers[it.key]?.isEnabled=p.enabled(values,p.WINDOW_ENABLED)}
        val normalization=p.normMode(values)!=0
        numbers[p.normTarget.key]?.isEnabled=normalization
        toggles[p.NORM_ONLY_ATTENUATION]?.isEnabled=normalization
        numbers[p.right.key]?.isEnabled=ConvolutionController.supported(context) && !p.enabled(values,p.CHANNEL_LINK)
        val confirmed=ConvolutionController.kernelConfirmed(prepared)
        metadata.summary=when {
            prepared==null->"等待选择有效 IR 并生成工作副本。"
            !prepared.hasWorkingKernel->"${when(prepared.status){ConvolutionController.Preparation.PROCESSING->"正在预处理";ConvolutionController.Preparation.FAILED->"预处理失败，原参数保留";else->"等待处理"}}：${prepared.message}"
            else->{
                val duration=1000.0*prepared.workingFrames/prepared.processingSampleRate
                val peaks=prepared.peakFrames.mapIndexed {i,frame->"核${i+1}：$frame 采样"}.joinToString("；")
                "源文件 ${java.io.File(prepared.sourcePath).name}；${prepared.sourceSampleRate} Hz／${prepared.sourceFrames} 帧\n"+
                    "工作核 ${prepared.processingSampleRate} Hz／${prepared.channels} 核／${prepared.workingFrames} 帧（${String.format(Locale.ROOT,"%.3f",duration)} ms）\n"+
                    "窗口前 ${prepared.beforeWindowFrames} 帧；裁去头部 ${prepared.trimmedStartFrames} 帧；淡入 ${prepared.fadeInFrames}／淡出 ${prepared.fadeOutFrames} 帧\n"+
                    "窗口前峰值 ${irPeak(prepared.originalPeakDb)}；工作核峰值 ${irPeak(prepared.workingPeakDb)}\n窗口前指原版优化后、新时间窗前。\n"+
                    "参考归一化 ${String.format(Locale.ROOT,"%+.2f",prepared.normalizationGainDb)} dB；采样率补偿 ${String.format(Locale.ROOT,"%+.2f",prepared.sampleRateCompensationDb)} dB；CRC ${prepared.crc.toUInt().toString(16)}：${if(confirmed)"驱动核已确认"else"装载结果待确认"}${if(prepared.status==ConvolutionController.Preparation.STAGED)" · 已暂存待激活"else""}"+
                    (if(peaks.isEmpty())"" else "\n峰位置 $peaks")+(if(prepared.message.isEmpty())""else"\n${prepared.message}")
            }
        }
        if(!professionalMode)response?.configure(prepared,confirmed)
        audition.value=ConvolutionController.audition
        audition.isEnabled=ConvolutionController.supported(context) && ConvolutionController.masterEnabled && prefs.getBoolean(p.ENABLED,false)
        val supported=ConvolutionController.supported(context)
        val streams=ConvolutionController.runtime.map {it as ConvolutionController.Reading?}.ifEmpty {listOf(null)}
        actual.summary=streams.joinToString("\n\n") {r->
            val s=r?.snapshot
            val enabled=prefs.getBoolean(p.ENABLED,false)
            val valid=supported && enabled && r?.enabled==true && s!=null && s.loaded && s.rate>0 &&
                s.ready && s.available && r.processing!=OutputRuntimeStatus.Processing.UNKNOWN && r.processing!=OutputRuntimeStatus.Processing.IDLE
            fun n(value:Int?,divisor:Double=1.0,decimals:Int=0)=StableRuntimeReadoutPreference.fixedNumber(value,divisor,decimals,width=8)
            val signal=when {
                !supported->"当前驱动未提供本轮同窗口监测。"
                !enabled->"卷积开关关闭"
                r==null->"等待音频流配置和完整 100 ms 监测窗口。"
                !r.enabled->"主开关关闭"
                s==null->"等待读取卷积状态。"
                !s.loaded && prepared?.status==ConvolutionController.Preparation.STAGED->"已暂存待激活；等待音频流恢复后自动装载"
                !s.loaded && prepared?.status==ConvolutionController.Preparation.WAITING->"等待音频流锁定实际采样率后自动装载"
                !s.loaded->"未装载有效卷积核"
                s.rate==0->"工作核已暂存；等待音频流恢复后自动激活"
                r.processing==OutputRuntimeStatus.Processing.UNKNOWN->"等待读取处理状态"
                r.processing==OutputRuntimeStatus.Processing.IDLE || !s.available->"当前未处理音频"
                !s.ready->"等待完整 100 ms 监测窗口"
                else->"同窗口三路监测有效"
            }
            buildList {
                add(signal)
                add("实际采样率 ${n(s?.rate?.takeIf {it>0})} Hz")
                add("核数 ${n(s?.channels)}；核帧数 ${n(s?.frames)}")
                add("预延时 ${n(s?.delaySamples)} 采样")
                add("预延时 ${n(s?.delayUs,1000.0,3)} ms")
                add("算法新增缓冲 ${n(s?.algorithmSamples)} 采样")
                add("混合比例 ${n(s?.mix,10.0,1)} %")
                add("湿声增益 ${n(s?.wet,10.0,1)} dB")
                add("模块输出 ${n(s?.output,10.0,1)} dB")
                add("当前临时试听路由 ${n(s?.audition)}")
                add("三路原生监测在临时试听之前，不冒充实际试听输出。")
                fun meterRows(title:String,value:ConvolutionRuntimeReader.Meter?) {
                    val m=value.takeIf {valid}
                    add(title)
                    add("峰值 ${n(m?.peak,10.0,1)} dBFS")
                    add("RMS  ${n(m?.rms,10.0,1)} dBFS")
                    add("相关度 ${n(m?.correlation,10000.0,4)}")
                    val fold=when(m?.fold){null,Int.MIN_VALUE->"未定义";ConvolutionRuntimeReader.FOLD_NEGATIVE_INFINITY->"−∞（零中央分量）";else->n(m?.fold,10.0,1)+" dB"}
                    add("折叠差 $fold")
                }
                meterRows("模块输入",s?.input)
                meterRows("校准后湿声",s?.wetMeter)
                meterRows("正常混合输出",s?.result)
            }.joinToString("\n")
        }
    }
    fun stopAudition(){ConvolutionController.stopAudition(context,this);advancedEditor?.stopTemporary()}
    fun close(){stopAudition();renderFrame.close();advancedEditor?.close();group.onExpandedChanged=null;prefs.unregisterOnSharedPreferenceChangeListener(listener);status.unregisterOnSharedPreferenceChangeListener(listener);context.unregisterLocalReceiver(receiver)}
}
