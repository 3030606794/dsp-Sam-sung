package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale

class DynamicEditor(private val context: Context, private val group: SwitchPreferenceGroup, host: Fragment) {
    private val p=DynamicParameters
    private val c=DynamicController
    private val prefs=c.preferences(context)
    private val status=c.statusPreferences(context)
    private val store=DynamicPresetStore(context)
    private val rows=linkedMapOf<String,BassNumericPreference>()
    private var rowFlags: List<Any>?=null
    private var closed=false
    private var pendingExport: String?=null
    private var library: DynamicPresetsDialog?=null
    private val importLauncher=host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null && !closed) attempt {
            val json=context.contentResolver.openInputStream(uri)?.use(DynamicPresetCodec::read) ?: error("无法读取预设文件")
            val count=store.importJson(json); library?.refresh(); toast("已导入 $count 项动态音色预设")
        }
    }
    private val exportLauncher=host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json=pendingExport; pendingExport=null
        if(uri!=null && json!=null && !closed) attempt {
            context.contentResolver.openOutputStream(uri,"wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("无法写入预设文件")
            toast("动态音色预设已导出")
        }
    }
    private fun info(key: String,title: String,order: Int)=object : RuntimeStatusPreference(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            listOf(android.R.id.title,android.R.id.summary).forEach { id -> (holder.findViewById(id) as? TextView)?.apply {
                setSingleLine(false); maxLines=Int.MAX_VALUE; ellipsize=null
            } }
        }
    }.apply { this.key=key; this.title=title; this.order=order; isIconSpaceReserved=false; isSelectable=false }
    private fun action(key: String,title: String,order: Int)=info(key,title,order).apply { isSelectable=true }
    private fun toggle(key: String,title: String,text: String,order: Int)=object: SwitchPreferenceCompat(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            listOf(android.R.id.title,android.R.id.summary).forEach { id -> (holder.findViewById(id) as? TextView)?.apply {
                setSingleLine(false); maxLines=Int.MAX_VALUE; ellipsize=null
            } }
        }
    }.apply { this.key=key; this.title=title; summary=text; this.order=order; isIconSpaceReserved=false; setDefaultValue(false) }
    private val professional=toggle(p.PROFESSIONAL,"专业调节","默认关闭。六个原预设与原强度作为基础，精调叠加其上；关闭后恢复原版。",10).apply {
        isPersistent=false; setOnPreferenceChangeListener { _,next -> stopPlayback(); p.setProfessional(prefs,next as Boolean); true }
    }
    private val baseline=info("dynamic_pro_baseline","当前基础与有效模式",11)
    private val core=toggle(p.CORE_ON,"启用核心音色精调","默认关闭。开启后接管原强度杆，以其保存值为基础；0 修正回到当前基础值。当前预设只开放有效参数。",20)
    private val tone=toggle(p.TONE_ON,"启用频段与音色","默认关闭。分带模式调整三个有效分界参数，深低音调整注入频率；0 Hz 低切／高切为旁路。",40)
    private val adaptive=toggle(p.ADAPTIVE_ON,"启用电平自适应","默认关闭。左右联动检测，输入较强时衰减低频处理支路，较弱时恢复基础量；主干不整体压缩。滤波相位会影响合成频响，支路回收不保证每个低频点都变小。与动态压扩器独立。",60)
    private val detector=FieldChoicePreference(context,p.DETECTOR,"电平检测方式",listOf("峰值检测","RMS 检测"),"恢复 RMS",1).apply {
        order=61; setOnPreferenceChangeListener { _,next ->
            if(closed || !p.enabled(prefs.all) || !p.on(prefs.all,p.ADAPTIVE_ON)) false
            else { prefs.edit().putInt(p.DETECTOR,next as Int).apply(); true }
        }
    }
    private val mix=toggle(p.MIX_ON,"启用干湿混合","默认关闭。0% 为固定延迟对齐的模块输入，100% 为完整处理；分带模式对齐 3 个采样，深低音 0 个。滤波相位仍会影响混合。",80)
    private val output=toggle(p.OUTPUT_ON,"启用输出增益","默认关闭。对本模块完整混合输出作固定衰减，0 dB 不额外衰减。",85)
    private val meters=toggle(p.METERS,"显示实时电平","默认关闭。显示输入／输出峰值、RMS、增强支路与检测电平、实际增强回收量及超限帧数。",90)
    private val response=toggle(p.RESPONSE,"显示响应预测","默认关闭。由驱动按实际滤波参数计算；以左右同相等幅输入预测，不代表真实房间测量。",91)
    private val toggles=listOf(core,tone,adaptive,mix,output,meters,response)
    private val homeNumbers=setOf(p.mix.key,p.output.key,p.threshold.key)
    private val homeToggles=setOf(p.CORE_ON,p.ADAPTIVE_ON,p.MIX_ON,p.OUTPUT_ON)
    private val help=info("dynamic_pro_help","动态低频塑形说明",110).apply {
        summary="专业精调为相对当前预设与原强度的修正，单项复位只恢复该项基础值。分组关闭置灰并保留，开启后恢复；不适用的模式参数置灰。分界参数不是标准 −3 dB 分频点；原算法的无效分界在支路重组时抵消，不设置无效滑杆。修改 支路边界时需下限小于上限，低切小于高切；拒绝冲突输入且不改动其他项。切换预设保留修正，由驱动安全限幅，保存值与实际值分别说明。原预设始终可选，不随频率调节暗中切换算法。采样率变化重新准备候选状态，预热后平滑切换。"
    }
    private val presets=action("dynamic_pro_presets","动态音色独立预设",120).apply {
        summary="原版兼容与 3 种专业方案 · 个人 20 项 · 导入／导出"
        setOnPreferenceClickListener {
            stopPlayback(); library?.dismiss()
            library=DynamicPresetsDialog(context,store,{ p.capture(prefs.all) },{ s ->
                stopPlayback(); invalidateRows(); p.apply(prefs,s); render()
            },{ importLauncher.launch(arrayOf("application/json","text/plain","application/octet-stream")) },{ id ->
                pendingExport=store.export(id); exportLauncher.launch("动态音色预设.json")
            }).also { it.show() }; true
        }
    }
    private fun save(slot: Int)=action(if(slot==0) "dynamic_pro_save_a" else "dynamic_pro_save_b",if(slot==0) "保存试听快照 A" else "保存试听快照 B",130+slot).apply {
        setOnPreferenceClickListener { if(!c.saveComparison(context,this@DynamicEditor,slot)) toast("请先连接配套驱动"); renderStatus(); true }
    }
    private val saveA=save(0); private val saveB=save(1)
    private val audition=FieldChoicePreference(context,"dynamic_pro_audition","临时试听",
        listOf("正常处理","延迟对齐的输入原声","仅处理后效果","效果差分","快照 A","快照 B","测量 A/B 电平","低频处理支路独听","检测频段独听"),"恢复正常").apply {
        order=140; setOnPreferenceChangeListener { _,next -> c.startAudition(context,this@DynamicEditor,next as Int).also {
            if(!it) toast("试听需要活动音频；A/B 需先保存两个快照，电平匹配需先完成测量")
        } }
    }
    private val measure=action("dynamic_pro_measure","测量／重新测量 A/B 电平",150).apply {
        summary="同一输入同时处理两套快照，滤波与自适应状态预热后测量 2 秒。"
        setOnPreferenceClickListener { if(!c.measure(context,this@DynamicEditor)) toast("请先保存 A/B 快照，并播放经过 DSP 的音乐"); true }
    }
    private val match=toggle("dynamic_pro_match","匹配 A/B 电平","先测量同一输入的 A/B RMS，再固定衰减；不持续追踪、不提升电平。",160).apply {
        isPersistent=false; setOnPreferenceChangeListener { _,next -> c.setMatch(context,next as Boolean).also { if(!it) toast("请先完成 A/B 电平测量") } }
    }
    private val stop=action("dynamic_pro_stop","停止试听／恢复保存设置",170).apply { setOnPreferenceClickListener { stopAudition(); true } }
    private val actual=StableRuntimeReadoutPreference(context).apply {
        key="dynamic_pro_actual";title="试听与实际处理状态";order=180
    }
    private val compatibility=info("dynamic_pro_compatibility","驱动支持",190)
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener { source,key ->
        if(closed) return@OnSharedPreferenceChangeListener
        if(source===prefs && key !in setOf(p.METERS,p.RESPONSE)) stopPlayback()
        if(source===prefs && p.numbers.any { it.key==key }) {
            rows[key]?.let { row -> val n=p.value(prefs.all,row.control); if(row.value!=n) row.value=n }; renderBaseline(); renderFrame.request(false)
        } else renderFrame.request()
    }
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(c: Context?,intent: Intent?) {
            if (!runtimeStatusVisible(this@DynamicEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
            if(intent?.action==Constants.ACTION_PRESET_LOADED) { stopPlayback(); invalidateRows(); render() } else renderFrame.request(false)
        }
    }
    init {
        p.normalize(prefs); c.stopAudition(context,clearSnapshots=true); group.onExpandedChanged={ if(!it) stopAudition() }
        prefs.registerOnSharedPreferenceChangeListener(listener); status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(c.STATUS_ACTION).apply { addAction(Constants.ACTION_PRESET_LOADED) }); render()
    }
    private fun add(row: Preference) { if(group.findPreference<Preference>(row.key)==null) group.addPreference(row) }
    private fun invalidateRows() { rows.values.forEach { it.isEnabled=false; group.removePreference(it) }; rows.clear(); rowFlags=null }
    private fun ensureRows() {
        val v=prefs.all; val flags=listOf<Any>(p.base(v))+p.switches.filterNot { it in setOf(p.METERS,p.RESPONSE) }.map { p.on(v,it) }
        if(flags==rowFlags) return
        invalidateRows(); rowFlags=flags
        p.numbers.forEach { control -> rows[control.key]=BassNumericPreference(context,control,p.value(v,control)).apply {
            order=when(control) { in p.coreNumbers -> 21+p.coreNumbers.indexOf(control); in p.toneNumbers -> 41+p.toneNumbers.indexOf(control)
                in p.adaptiveNumbers -> 62+p.adaptiveNumbers.indexOf(control); p.mix -> 81; else -> 86 }
            val minimum=when(control) { p.hp -> 10; p.lp -> 100; else -> 0 }
            if(minimum!=0) {
                valueFormatter={ if(it==0) "旁路（0 Hz）" else control.label(it) }
                stepAction={ delta -> requestValue(if(value==0 && delta>0) minimum else if(value==minimum && delta<0) 0
                    else (value.toLong()+delta).coerceIn(control.min.toLong(),control.max.toLong()).toInt()) }
                stepLabel={ if(value==0 || value==minimum) "有效频率档位" else "1 Hz" }
            }
            if(control in listOf(p.lowGainDelta,p.highGainDelta,p.injectDelta)) valueFormatter={ n ->
                val config=context.resources.configuration
                if(config.screenWidthDp<=360 && config.fontScale>=1.3f && control.number(n).length>=4)
                    "${control.number(n)}\n百分点" else control.label(n)
            }
            rejectedValueMessage="支路下分界须小于上分界，低切须小于高切（或设 0 Hz 旁路），检测下限须小于上限。控制状态或基础预设改变后请重新打开输入框。"
            setOnPreferenceChangeListener { _,next -> !closed && p.numberEnabled(control.key,prefs.all) && p.candidateValid(prefs.all,control,next as Int) }
        } }
    }
    private fun renderBaseline() {
        val s=p.capture(prefs.all); val f=p.foundation(s.base); val name=ViperParameters.dynamicPresets.keys.elementAt(s.base)
        val gain=1+.2*s.strength
        baseline.summary="基础预设：$name；原强度 ${s.strength}。" + if(s.base==4)
            "\n深低音共通增强模式：基础频率 55 Hz，注入量 100%，共振 Q ${String.format(Locale.ROOT,"%.2f",(gain-1)/20*1600/666+.5)}；专业调节只开放深低音注入量、Q、频率及共用工具。" else
            "\n分带模式：基础 主分带基准 ${f[0]} Hz；支路下／上分界 ${f[2]}／${f[3]} Hz；支路低／高段比例 ${f[5]}%／${f[4]}%；低频整体倍率 ${String.format(Locale.ROOT,"%.2f",gain)}。"
    }
    private fun render() {
        if(closed) return
        p.normalize(prefs); ensureRows(); val v=prefs.all; val pro=p.enabled(v)
        add(professional); professional.isChecked=pro; add(baseline); renderBaseline()
        if(!group.isProfessionalMode) {
            professional.summary="开启后可手动启用回收、混合与输出。完整调节请点标题齿轮；展开不会开启音效。"
            core.summary=if(pro)"核心组正在接管原强度；手动关闭此开关可恢复原强度杆。"
                else "核心组已保存为开启；启用专业后会接管原强度，可再手动关闭恢复。"
            adaptive.summary="开启后可调回收阈值，只回收低频处理支路。"
            mix.summary="开启后可调效果比例，0%为延迟对齐的输入。"
            output.summary="开启后可调模块输出衰减。"
        }
        group.findPreference<ListPreference>(p.BASE)?.apply {
            order=0; isEnabled=true
            val persistence=isPersistent; isPersistent=false; value=p.base(v).toString(); isPersistent=persistence
        }
        group.findPreference<ViperSliderPreference>(p.LEGACY)?.apply {
            order=1; isEnabled=!(pro && p.on(v,p.CORE_ON)); disabledHint="由专业核心接管；关闭核心组恢复调节"
            val persistence=isPersistent; isPersistent=false; value=p.legacy(v); isPersistent=persistence
            setOnPreferenceChangeListener { _,_ -> !closed && !(p.enabled(prefs.all) && p.on(prefs.all,p.CORE_ON)) }
        }
        rows.forEach { (key,row) -> row.isEnabled=p.numberEnabled(key,v); if(row.value!=p.value(v,row.control)) row.value=p.value(v,row.control)
            if(pro || group.isProfessionalMode || key in homeNumbers) add(row) else group.removePreference(row) }
        toggles.forEach { row ->
            row.isChecked=p.on(v,row.key);row.isEnabled=pro
            if(!group.isProfessionalMode && row.key==p.CORE_ON && !row.isChecked)group.removePreference(row)
            else if(pro || group.isProfessionalMode || row.key in homeToggles)add(row)else group.removePreference(row)
        }
        detector.value=p.detector(v); detector.isEnabled=pro && p.on(v,p.ADAPTIVE_ON)
        if(pro || group.isProfessionalMode) { add(detector);add(help) }else { group.removePreference(detector);group.removePreference(help) }
        listOf(presets,saveA,saveB,audition,measure,match,stop,actual,compatibility).forEach(::add); renderStatus()
    }
    fun renderStatus() {
        if(closed) return
        val supported=c.supported(context); val s=p.capture(prefs.all)
        saveA.isEnabled=supported; saveB.isEnabled=supported
        saveA.summary=if(c.hasComparison(0)) "已保存，仅本次页面使用" else "保存当前基础预设、原强度及专业设置"
        saveB.summary=if(c.hasComparison(1)) "已保存，仅本次页面使用" else "保存当前基础预设、原强度及专业设置"
        audition.value=c.audition; audition.isEnabled=c.canListen(context)
        measure.isEnabled=audition.isEnabled && c.hasComparison(0) && c.hasComparison(1)
        match.isChecked=c.match; match.isEnabled=audition.isEnabled && c.matchingReady()
        compatibility.summary=if(supported) "已连接配套动态音色增强驱动。专业精调以当前六预设与原强度为基础；关闭后恢复原声音。"
            else "当前驱动不支持新增处理。专业参数可编辑并保留，实际仍使用原版动态音色；更新配套驱动并重启后生效。"
        val moduleEnabled=p.moduleEnabled(prefs.all)
        val matchingReady=c.matchingReady()
        val streams=c.runtime.map {it as DynamicController.Reading?}.ifEmpty {listOf(null)}
        actual.summary=streams.joinToString("\n\n") {r->
            val snapshot=r?.snapshot
            val currentAudio=snapshot?.mode==c.audition && (c.audition==0 || snapshot?.epoch==c.epoch)
            val valid=supported && moduleEnabled && r!=null && r.enabled && r.active && snapshot!=null &&
                snapshot.enabled && snapshot.professional==s.professional && snapshot.ready &&
                snapshot.data[2] in 8000..768000 && snapshot.data[2]==r.rate && snapshot.data[26]==r.rate && currentAudio
            val v=if(valid)snapshot?.data else null
            val tools=if(valid && snapshot?.epoch==c.epoch)v else null
            val statusText=when {
                !supported->"等待配套驱动\n—表示暂无有效读数。"
                !moduleEnabled->"本模块关闭；\n编辑参数不会自动启用"
                r==null || !r.enabled || !r.active->"当前没有活动处理会话；\n试听状态等待驱动确认"
                v==null->"等待读数\n—表示暂无有效读数。"
                v[4]==0->"原版兼容处理；\n开启专业调节后才能试听"
                else->"当前专业处理状态；\n—表示暂无有效读数。"
            }
            val measurement=tools?.get(7)
            val phase=when(measurement) {
                1->"A/B 预热"
                2->"A/B 测量"
                3->if(matchingReady)"测量完成，固定衰减"else"等待当前快照的测量结果"
                4->"信号不足，播放音乐后重新测量"
                0->"未测量"
                else->"—"
            }
            fun number(index:Int,divisor:Double=1.0,decimals:Int=0)=
                StableRuntimeReadoutPreference.fixedNumber(v?.get(index),divisor,decimals)
            fun toolNumber(index:Int)=StableRuntimeReadoutPreference.fixedNumber(tools?.get(index),1.0,0)
            fun attenuation(index:Int)=StableRuntimeReadoutPreference.fixedNumber(
                if(measurement==3 && matchingReady)tools?.get(index)else null,100.0,2)
            val deep=v?.get(15)==1
            fun applicable(index:Int,deepOnly:Boolean,divisor:Double=1.0,decimals:Int=0)=
                StableRuntimeReadoutPreference.fixedNumber(v?.get(index)?.takeIf{deep==deepOnly},divisor,decimals)
            fun cutoff(index:Int)=v?.get(index)?.let{if(it==0)"旁路"else"$it Hz"} ?: "—"
            val metersOn=v?.get(12)==1
            fun meter(index:Int)=StableRuntimeReadoutPreference.fixedNumber(if(metersOn)v?.get(index)else null,100.0,2)
            val foundation=p.foundation(s.base)
            fun request(value:Int,deepOnly:Boolean)=StableRuntimeReadoutPreference.fixedNumber(value.takeIf{(s.base==4)==deepOnly},1.0,0)
            val baseName=v?.get(13)?.let{ViperParameters.dynamicPresets.keys.elementAtOrNull(it)} ?: "—"
            val engine=when(v?.get(15)){0->"分带模式";1->"深低音模式";else->"—"}
            val modeName=v?.get(5)?.let {listOf("正常处理","延迟对齐的输入原声","处理后效果","效果差分","快照 A","快照 B",
                "测量 A/B","低频处理支路独听","检测频段独听").getOrNull(it)} ?: "—"
            "$statusText\n处理 ${number(2)} Hz\n宿主 ${number(26)} Hz\n当前基础：$baseName\n"+
                "实际原强度 ${number(14)}\n当前引擎：$engine\n当前试听：$modeName\n测量状态：$phase\n"+
                "A/B 预热 ${toolNumber(29)} / ${toolNumber(30)} ms\n随后测量 ${toolNumber(31)} ms\n"+
                "A/B 测量 ${toolNumber(8)} / ${toolNumber(31)} ms\n固定衰减 A ${attenuation(9)}／B ${attenuation(10)} dB\n"+
                "实际主分带基准 ${applicable(32,false)} Hz\n实际支路下分界 ${applicable(33,false)} Hz\n实际支路上分界 ${applicable(34,false)} Hz\n"+
                "实际低频增益 ${applicable(38,false,100.0,2)} dB\n实际支路低段 ${applicable(39,false,10.0,1)}%\n实际支路高段 ${applicable(40,false,10.0,1)}%\n"+
                "深低音实际频率 ${applicable(35,true)} Hz\n实际注入 ${applicable(41,true,10.0,1)}%\n实际共振 Q ${applicable(42,true,1000.0,3)}\n"+
                "请求主分带 ${request(foundation[0]+s.xDelta,false)} Hz\n请求支路下界 ${request(foundation[2]+s.lowFreqDelta,false)} Hz\n"+
                "请求支路上界 ${request(foundation[3]+s.highFreqDelta,false)} Hz\n请求深低音 ${request(55+s.deepFreqDelta,true)} Hz\n"+
                "频率组开启时生效；\n驱动按采样率安全限幅\n实际支路低切 ${cutoff(36)}\n实际支路高切 ${cutoff(37)}\n"+
                "检测频段 ${number(43)}–${number(44)} Hz\n干声固定对齐 ${number(47)} 个采样\n滤波相位仍影响干湿混合\n"+
                "${if(metersOn)"实时电平已开启"else if(v==null)"实时电平状态等待确认"else"实时电平已关闭"}\n"+
                "电平 dBFS（左／右）\n输入峰值 ${meter(16)}／${meter(17)}\n输入 RMS ${meter(20)}／${meter(21)}\n"+
                "输出峰值 ${meter(18)}／${meter(19)}\n输出 RMS ${meter(22)}／${meter(23)}\n"+
                "超限 ${if(metersOn)number(24)else StableRuntimeReadoutPreference.fixedNumber(null)} 帧\n"+
                "增强支路 RMS ${meter(45)} dBFS\n检测电平 ${meter(28)} dBFS\n"+
                "当前增强回收 ${number(27,100.0,2)} dB\n"+
                "驱动响应预测：${when(v?.get(46)){1->"开启";0->"关闭";else->"等待读数"}}"
        }+"\n电平取自本模块，后续效果仍会改变声音；采样峰值不是真峰值或耳内声压。自适应只回收本模块低频增强，与压扩器独立。差分为效果减对齐输入；低频支路独听只输出处理支路。试听、快照与匹配不写入预设；收起、离页或暂停清除快照，声音变化或会话失效停止试听。"
    }
    private fun db(n: Int)=String.format(Locale.ROOT,"%.2f",n/100.0)
    private fun level(n: Int)=if(n==Int.MIN_VALUE) "—" else db(n)
    private fun hz(n: Int)=if(n==0) "旁路" else "$n Hz"
    private fun toast(s: String)=Toast.makeText(context,s,Toast.LENGTH_LONG).show()
    private fun attempt(action: ()->Unit) { try { action() } catch(e: Exception) { toast(e.message ?: "操作失败") } }
    private fun stopPlayback() { c.stopAudition(context,this) }
    fun stopAudition() { c.stopAudition(context,this,clearSnapshots=true) }
    fun close() { renderFrame.close();
        stopAudition(); closed=true; invalidateRows(); library?.dismiss(); pendingExport=null; group.onExpandedChanged=null
        group.findPreference<Preference>(p.LEGACY)?.onPreferenceChangeListener=null
        prefs.unregisterOnSharedPreferenceChangeListener(listener); status.unregisterOnSharedPreferenceChangeListener(listener); context.unregisterLocalReceiver(receiver)
    }
}
