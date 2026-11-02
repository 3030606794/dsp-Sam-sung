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
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale

/** Advanced corrections are relative to the current ten original controls. */
class ToneAdvancedEditor(private val context:Context,private val group:SwitchPreferenceGroup,host:Fragment) {
    private val p=ToneAdvancedParameters
    private val c=ToneAdvancedController
    private val prefs=c.preferences(context)
    private val status=c.statusPreferences(context)
    private val store=ToneAdvancedPresetStore(context)
    private val rows=linkedMapOf<String,BassNumericPreference>()
    private var rowFlags:List<Any>?=null
    private var closed=false
    private var localWindow=10
    private var foundationEpoch=0L
    private var pendingExport:String?=null
    private var library:ToneAdvancedPresetsDialog?=null
    private val importLauncher=host.registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null && !closed) attempt {val json=context.contentResolver.openInputStream(uri)?.use(ToneAdvancedPresetCodec::read) ?: error("无法读取预设文件");val count=store.importJson(json);library?.refresh();toast("已导入 $count 项音色预设")}
    }
    private val exportLauncher=host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        val json=pendingExport;pendingExport=null
        if(uri!=null && json!=null && !closed) attempt {context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(json.toByteArray(Charsets.UTF_8))} ?: error("无法写入预设文件");toast("音色预设已导出")}
    }
    private fun info(key:String,title:String,order:Int)=object : RuntimeStatusPreference(context) {
        override fun onBindViewHolder(holder:PreferenceViewHolder) {super.onBindViewHolder(holder);listOf(android.R.id.title,android.R.id.summary).forEach {id->(holder.findViewById(id) as? TextView)?.apply {setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}}
    }.apply {this.key=key;this.title=title;this.order=order;isIconSpaceReserved=false;isSelectable=false}
    private fun action(key:String,title:String,order:Int)=info(key,title,order).apply {isSelectable=true}
    private fun toggle(key:String,title:String,text:String,order:Int)=object:SwitchPreferenceCompat(context) {
        override fun onBindViewHolder(holder:PreferenceViewHolder) {super.onBindViewHolder(holder);listOf(android.R.id.title,android.R.id.summary).forEach {id->(holder.findViewById(id) as? TextView)?.apply {setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}}
    }.apply {this.key=key;this.title=title;summary=text;this.order=order;isIconSpaceReserved=false;setDefaultValue(false);setOnPreferenceChangeListener {_,_->!closed && p.enabled(prefs.all) && isEnabled}}
    private val professional=toggle(p.PROFESSIONAL,"专业调节","默认关闭。精调叠加在原十项基础上；原声音与三种范围保留，关闭恢复原版处理。",100).apply {isPersistent=false;setOnPreferenceChangeListener {_,next->if(closed)false else {if(next==false)stopAudition()else stopPlayback();p.setProfessional(prefs,next as Boolean);true}}}
    private val baseline=info("tone_pro_baseline","当前基础与有效修正",101)
    private val core=toggle(p.CORE_ON,"启用十项核心精调","接管上方十项与原范围，原值保持不变。修正单位为点，0.1 点一格；单项复位清零修正，回当前原值。总强度基础为 100%。",110)
    private val window=FieldChoicePreference(context,"tone_pro_local_window","精调局部窗口",listOf("±5 点","±10 点","±20 点","±100 点"),"恢复 ±10 点",1).apply {order=111;value=1;setOnPreferenceChangeListener {_,n->if(closed || !isEnabled)false else {localWindow=listOf(5,10,20,100)[n as Int];rows.values.forEach(::updateWindow);true}}}
    private val shape=toggle(p.SHAPE_ON,"启用作用频率与音色形状","开放十路滤波频率、六路 Q、四路架式坡度与温度双端权重。原十项为作用量基础；原值为 0 时，移动该项频率不会产生音色作用。",140)
    private val dynamic=toggle(p.DYNAMIC_ON,"启用弹性与瞬态精调","低音弹性的静态隆起与动态起音分别设置权重。快／慢包络、灵敏度与最大作用量控制真实起音增强或柔化；这不是固定阈值压缩器。",170)
    private val weak=toggle(p.WEAK_ON,"启用弱信号保护","仅作用于两路动态起音，低于电平时停止动态增减并保留静态音色和原声。",184)
    private val mix=toggle(p.MIX_ON,"启用干湿比例","默认关闭。0% 为本模块输入，100% 为音色处理；滤波相位会影响中间比例。",190)
    private val output=toggle(p.OUTPUT_ON,"启用输出增益","默认关闭。干湿混合后固定衰减，0 dB 保留原输出电平。",193)
    private val headroom=toggle(p.HEADROOM_ON,"启用额外衰减余量","原算法自动余量始终保留；这里另加前置衰减。不是限制器，不代表响度匹配。",196)
    private val meters=toggle(p.METERS,"显示实时电平","显示当前音频块的输入／输出采样峰值与 RMS、两路实际动态增益及自动衰减量。不是测量真峰值或 LUFS；2 秒窗口仅用于 A/B 匹配。",200)
    private val response=toggle(p.RESPONSE,"显示静态响应图","静态滤波及含干湿、余量、输出的响应分别显示；动态起音控制另列读数。",201)
    private val toggles=listOf(core,shape,dynamic,weak,mix,output,headroom,meters,response)
    private val homeNumbers=setOf(p.mix.key,p.output.key,p.bassDynamicWeight.key,p.transientWeight.key)
    private val homeToggles=setOf(p.DYNAMIC_ON,p.MIX_ON,p.OUTPUT_ON)
    private val responseDescription=info("tone_pro_response_description","静态音色响应与动态控制说明",202).apply {
        summary="等待配套驱动与活动音频；开启专业调节和响应图后在顶部响应图显示。横轴 Hz，纵轴 dB。实线为稳定后的静态滤波响应，淡线为含干湿与自动／额外余量及输出的静态响应；弹性与瞬态为当前控制量，另在状态中显示。图属于主配置内核，预热交接前仍为旧实际值，不含 A/B 试听路由；不是实测频响，不能代表动态音乐的完整响应。频点详细读数在顶部联动图查看。"
    }
    private val help=info("tone_pro_help","音色精调说明",203).apply {summary="十项原值保持整数保存，专业修正精度为 0.1 点。实际作用量先将原值加修正限制在 ±100 点，再乘总作用强度；本轮最多 ±200 点。核心组关闭恢复原十项，其他组仍可单独作用。\n局部窗口只缩放修正滑杆，不改变保存值；当前值超出所选窗口时自动扩展显示。输入与加减始终覆盖完整有效范围。原来的 ±20／±40／±100 范围仍按原规则截断原值，与局部窗口不同。\nQ 越大，峰式频段越窄；架式坡度是 RBJ 的 S，0.250 至 1.000，不是 dB/oct。温度正值抬低频、降低高频，双端权重分别缩放。\n低音弹性与乐器瞬态依靠快慢包络差值，持续平稳的声音可能变化很小；起音权重与基础值同为 0 时不会产生效果。快包络时间必须小于慢包络时间。最大作用量限制实际增益，不会改变原值的方向。\n响应图为算法的静态预测，不是全链路测量。人声／女毒／齿音名称代表音色频段，不含人声识别或动态去齿音。"}
    private val resetAll=action("tone_pro_reset_all","专业音色全部重置",205).apply {
        summary="重置全部 49 项精调和专业分组开关；原十项基础归零，范围恢复 ±100。音色主开关保留，A/B 快照清除。"
        setOnPreferenceClickListener {
            if(!closed) {stopAudition();p.resetAll(prefs);invalidateRows();render()}
            true
        }
    }
    private val presets=action("tone_pro_presets","听感音色调节独立预设",210).apply {summary="原版兼容与专业音色方案 · 个人 20 项 · 导入／导出";setOnPreferenceClickListener {stopPlayback();library?.dismiss();library=ToneAdvancedPresetsDialog(context,store,{p.capture(prefs.all)},{s->stopPlayback();invalidateRows();p.apply(prefs,s);render()},{importLauncher.launch(arrayOf("application/json","text/plain","application/octet-stream"))},{id->pendingExport=store.export(id);exportLauncher.launch("听感音色调节预设.json")},canSave={p.validSavedFoundation(prefs.all)}).also {it.show()};true}}
    private fun save(slot:Int)=action(if(slot==0)"tone_pro_save_a" else "tone_pro_save_b",if(slot==0)"保存试听快照 A" else "保存试听快照 B",220+slot).apply {setOnPreferenceClickListener {if(!c.saveComparison(context,this@ToneAdvancedEditor,slot))toast("请先连接配套驱动并修复无效基础");renderStatus();true}}
    private val saveA=save(0);private val saveB=save(1)
    private val audition=FieldChoicePreference(context,"tone_pro_audition","临时试听",listOf("正常处理","本模块输入原声","仅处理后效果","效果差分","快照 A","快照 B","测量 A/B 电平"),"恢复正常").apply {order=230;setOnPreferenceChangeListener {_,n->c.startAudition(context,this@ToneAdvancedEditor,n as Int).also {if(!it)toast("试听需要活动音频；A/B 需保存两个快照，匹配需先完成测量")}}}
    private val measure=action("tone_pro_measure","测量／重新测量 A/B 电平",240).apply {summary="同一输入同时处理两套快照，状态预热后测量 2 秒。";setOnPreferenceClickListener {if(!c.measure(context,this@ToneAdvancedEditor))toast("请保存 A/B 快照并播放经过 DSP 的音乐");true}}
    private val match=toggle("tone_pro_match","匹配 A/B 电平","先测量同一输入的 A/B RMS，再固定衰减，不持续追踪、不提升电平。",250).apply {isPersistent=false;setOnPreferenceChangeListener {_,n->c.setMatch(context,n as Boolean).also {if(!it)toast("请先完成 A/B 电平测量")}}}
    private val stop=action("tone_pro_stop","停止试听／恢复保存设置",260).apply {setOnPreferenceClickListener {stopAudition();true}}
    private val actual=StableRuntimeReadoutPreference(context).apply {key="tone_pro_actual";title="试听与实际处理状态";order=270}
    private val compatibility=info("tone_pro_compatibility","驱动支持",280)
    private val renderFrame=me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate {full->if(full)render()else renderStatus()}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {source,key->
        if(closed)return@OnSharedPreferenceChangeListener
        if(source===prefs && key !in setOf(p.METERS,p.RESPONSE))stopPlayback()
        if(source===prefs && (key in p.baseKeys || key==p.BASE_RANGE)) {
            foundationEpoch++;renderFrame.request();return@OnSharedPreferenceChangeListener
        }
        renderFrame.request()
    }
    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,intent:Intent?){
            if (!runtimeStatusVisible(this@ToneAdvancedEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
            if(intent?.action==Constants.ACTION_PRESET_LOADED){stopAudition();invalidateRows();renderFrame.request()}else renderFrame.request(false)}}
    init {p.normalize(prefs);c.stopAudition(context,clearSnapshots=true);group.onExpandedChanged={if(!it)stopAudition()};prefs.registerOnSharedPreferenceChangeListener(listener);status.registerOnSharedPreferenceChangeListener(listener);context.registerLocalReceiver(receiver,IntentFilter(c.STATUS_ACTION).apply {addAction(Constants.ACTION_PRESET_LOADED)});render()}
    private fun add(row:Preference){if(group.findPreference<Preference>(row.key)==null)group.addPreference(row)}
    private fun invalidateRows(){rows.values.forEach {it.isEnabled=false;group.removePreference(it)};rows.clear();rowFlags=null}
    private fun ensureRows(){val v=prefs.all;val flags=p.switches.filterNot {it in setOf(p.METERS,p.RESPONSE)}.map {p.on(v,it)}
        if(flags==rowFlags)return
        invalidateRows();rowFlags=flags
        p.numbers.forEach {control->rows[control.key]=BassNumericPreference(context,control,p.value(v,control)).apply {
            order=when(control){in p.coreNumbers->112+p.coreNumbers.indexOf(control);in p.shapeNumbers->141+p.shapeNumbers.indexOf(control);in p.dynamicNumbers->171+p.dynamicNumbers.indexOf(control);p.mix->191;p.output->194;else->197}
            dialogToken={foundationEpoch};updateWindow(this);rejectedValueMessage="快包络时间须小于慢包络时间；状态或原基础改变后请重新打开输入框。"
            setOnPreferenceChangeListener {_,n->!closed && p.numberEnabled(control.key,prefs.all) && p.validSavedFoundation(prefs.all) && p.candidateValid(prefs.all,control,n as Int)}
        }}
    }
    private fun updateWindow(row:BassNumericPreference){if(row.control in p.delta){row.sliderRange=p.localRange(row.value,localWindow);row.sliderStep=1;row.stepLabel={"0.1 点"}}}
    private fun renderBaseline(){if(!p.validSavedFoundation(prefs.all)){baseline.summary="原十项或范围无效，保留上次有效处理。请重新设置或应用有效预设；不会用默认值覆盖保存值。";return}
        val v=prefs.all;val titles=context.resources.getStringArray(me.timschneeberger.rootlessjamesdsp.R.array.fusion_tone_names)
        baseline.summary="原范围 ±${p.baseRange(v)}，原值与保存目标作用量（点）：\n"+p.base(v).mapIndexed {i,n->"${titles[i]}：$n → ${String.format(Locale.ROOT,"%.1f",p.effective(v,i))}"}.joinToString("；")+if(p.takenOver(v))"\n核心精调接管原十项和范围，原保存值保留；关闭核心组或专业调节恢复。" else "\n原十项与范围可调；专业修正仅在对应组启用时生效。"
    }
    private fun renderLegacy(){val v=prefs.all;val blocked=p.takenOver(v);val range=p.baseRange(v).toInt();val base=p.base(v)
        p.baseKeys.forEachIndexed {i,key->group.findPreference<ToneSliderPreference>(key)?.apply {if(limit!=range)limit=range;setValueSilently(base[i]);isEnabled=!blocked;editGuard={!closed && !p.takenOver(prefs.all)};setOnPreferenceChangeListener {_,_->!closed && !p.takenOver(prefs.all)}}}
        group.findPreference<ToneRangePreference>(p.BASE_RANGE)?.apply {if(this.range!=range)setRangeSilently(range,false);isEnabled=!blocked;editGuard={!closed && !p.takenOver(prefs.all)}}
        group.findPreference<Preference>("fusion_tone_reset")?.isEnabled=!blocked
    }
    private fun render(){if(closed)return;p.normalize(prefs);ensureRows();val v=prefs.all;val pro=p.enabled(v);add(professional);professional.isChecked=pro;add(baseline);renderBaseline();renderLegacy()
        if(!group.isProfessionalMode) {
            professional.summary="开启后可手动启用混合、输出及弹性／瞬态；完整精调请点标题设置。展开不会开启音效。"
            core.summary="核心已接管基础十项与范围；手动关闭此开关恢复基础调节。"
            val zero=buildList {
                if(p.effective(v,2)==0.0)add("低音弹性当前为 0，动态权重暂不产生作用")
                if(p.effective(v,8)==0.0)add("乐器瞬态当前为 0，瞬态权重暂不产生作用")
            }
            dynamic.summary="开启后可调两路起音权重；不会自动改变基础十项。"+if(zero.isEmpty())"" else "\n"+zero.joinToString("；")
            mix.summary="开启后可调效果比例；0%为模块输入，100%为音色处理。"
            output.summary="开启后可调模块输出固定衰减。"
        }
        rows.forEach {(key,row)->row.isEnabled=p.numberEnabled(key,v) && p.validSavedFoundation(v);if(row.value!=p.value(v,row.control))row.value=p.value(v,row.control);updateWindow(row);if(pro || group.isProfessionalMode || key in homeNumbers)add(row)else group.removePreference(row)}
        toggles.forEach {row->row.isChecked=p.on(v,row.key);row.isEnabled=pro && (row.key!=p.WEAK_ON || p.on(v,p.DYNAMIC_ON));if(!group.isProfessionalMode && row.key==p.CORE_ON && !row.isChecked)group.removePreference(row)else if(pro || group.isProfessionalMode || row.key in homeToggles || row.key==p.CORE_ON && row.isChecked)add(row)else group.removePreference(row)}
        window.isEnabled=pro && p.on(v,p.CORE_ON);if(pro || group.isProfessionalMode){add(window);add(help);add(responseDescription)}else {group.removePreference(window);group.removePreference(help);group.removePreference(responseDescription)}
        if(group.isProfessionalMode)add(window)
        if(group.isProfessionalMode)add(resetAll)else group.removePreference(resetAll)
        listOf(presets,saveA,saveB,audition,measure,match,stop,actual,compatibility).forEach(::add);renderStatus()
    }
    fun renderStatus(){if(closed)return;val supported=c.supported(context);val s=p.capture(prefs.all)
        saveA.isEnabled=supported && s.professional && p.validSavedFoundation(prefs.all);saveB.isEnabled=saveA.isEnabled;saveA.summary=if(!s.professional)"开启专业调节后可保存；各组关闭时为原版对照" else if(c.hasComparison(0))"已保存，仅本次页面使用" else "保存当前原十项、范围与专业设置";saveB.summary=if(!s.professional)"开启专业调节后可保存；各组关闭时为原版对照" else if(c.hasComparison(1))"已保存，仅本次页面使用" else "保存当前原十项、范围与专业设置"
        audition.value=c.audition;audition.isEnabled=c.canListen(context);measure.isEnabled=audition.isEnabled && c.hasComparison(0) && c.hasComparison(1);match.isChecked=c.match;match.isEnabled=audition.isEnabled && c.matchingReady()
        compatibility.summary=if(supported)"已连接配套音色精调驱动；参数与其他模块独立，原十项基线保留。" else "当前驱动不支持新增处理。专业参数可保存；实际继续原版处理，请更新本轮配套驱动并重启。"
        val moduleEnabled=p.moduleEnabled(prefs.all)
        val foundationValid=p.validSavedFoundation(prefs.all)
        val matchingReady=c.matchingReady()
        val streams=c.runtime.map {it as ToneAdvancedController.Reading?}.ifEmpty {listOf(null)}
        val names=context.resources.getStringArray(me.timschneeberger.rootlessjamesdsp.R.array.fusion_tone_names)
        actual.summary=streams.joinToString("\n\n") {r->
            val snapshot=r?.snapshot
            val currentAudio=snapshot?.mode==c.audition && (c.audition==0 || snapshot?.epoch==c.epoch)
            val valid=foundationValid && supported && moduleEnabled && r!=null && r.enabled && r.active &&
                snapshot!=null && snapshot.enabled && snapshot.ready &&
                snapshot.data[2]==r.rate && r.rate in 8000..768000 && currentAudio
            val v=if(valid)snapshot?.data else null
            val tools=if(valid && snapshot?.epoch==c.epoch)v else null
            val newConfig=v?.takeIf {it[11]==0 && it[62]>0}
            val attenuation=tools?.takeIf {it[7]==3 && matchingReady}
            val metersOn=v?.get(12)==1
            fun n(index:Int,divisor:Double=1.0,decimals:Int=0,width:Int=7,allowed:Boolean=true)=
                StableRuntimeReadoutPreference.fixedNumber(v?.get(index).takeIf {allowed},divisor,decimals,width=width)
            fun t(index:Int)=StableRuntimeReadoutPreference.fixedNumber(tools?.get(index),1.0,0,width=6)
            val statusText=when {
                !foundationValid->"原十项或范围无效；\n保留上次有效处理，请重新设置。"
                !supported->"等待配套驱动\n—表示暂无有效读数。"
                !moduleEnabled->"本模块关闭；\n编辑参数不会自动启用"
                r==null || !r.enabled || !r.active->"当前没有活动处理会话；\n试听状态等待驱动确认"
                v==null->"等待读数\n—表示暂无有效读数。"
                v[4]==0 && s.professional->"主配置仍为原版内核；\n专业目标等待预热交接"
                v[4]==0->"当前原版音色处理；\n开启专业调节后才能试听"
                else->"当前专业音色处理；\n—表示暂无有效读数。"
            }
            val phase=when(tools?.get(7)){0->"未测量";1->"预热中";2->"测量中";3->if(matchingReady)"测量完成" else "等待确认";4->"信号不足，请播放后重测";else->"等待工具确认"}
            buildList {
                add(statusText)
                add("处理采样率 ${n(2)} Hz")
                add("宿主采样率 ${StableRuntimeReadoutPreference.fixedNumber(r?.rate,1.0,0,width=7)} Hz")
                add("试听模式   ${n(5)}")
                add("新配置：${if(newConfig!=null)"预热，完成后平滑生效" else if(v!=null)"无新配置预热" else "—"}")
                add("配置预热   ${StableRuntimeReadoutPreference.fixedNumber(newConfig?.get(61),1.0,0,width=6)} / ${StableRuntimeReadoutPreference.fixedNumber(newConfig?.get(62),1.0,0,width=6)} ms")
                add("A/B 状态：$phase")
                add("A/B 预热   ${t(61)} / ${t(62)} ms")
                add("随后：${if(tools?.get(7)==1 && tools[5]!=6)"预热完成后试听" else "测量窗口为 2000 ms"}")
                add("A/B 测量   ${t(8)} / ${t(31)} ms")
                add("固定衰减 A ${StableRuntimeReadoutPreference.fixedNumber(attenuation?.get(9),100.0,2)} dB")
                add("固定衰减 B ${StableRuntimeReadoutPreference.fixedNumber(attenuation?.get(10),100.0,2)} dB")
                add("主配置内核（预热交接前仍为旧实际值）")
                add("自动余量   ${n(27,100.0,2)} dB")
                add("额外余量目标 ${n(28,10.0,1)} dB")
                add("效果比例目标 ${n(32,10.0,1)} %")
                add("输出增益目标 ${n(33,10.0,1)} dB")
                add("低音动态增益 ${n(29,100.0,2)} dB")
                add("瞬态动态增益 ${n(30,100.0,2)} dB")
                add("实际低音分频 ${n(34)} Hz")
                add("低音快包络 ${n(35,10.0,1)} ms")
                add("低音慢包络 ${n(36)} ms")
                add("瞬态快包络 ${n(37,10.0,1)} ms")
                add("瞬态慢包络 ${n(38)} ms")
                add("实际作用量（点，按原十项顺序）")
                repeat(10) {i->add("${names[i]} ${n(41+i,10.0,1)} 点")}
                add("实际滤波频率（按原十路顺序）")
                repeat(10) {i->add("滤波 ${i+1}     ${n(51+i)} Hz")}
                add("当前音频块电平（左／右；实际试听路由）")
                add("实时电平：${when{v==null->"—";metersOn->"开启";else->"已关闭"}}")
                add("输入峰值   ${n(16,100.0,2,allowed=metersOn)} / ${n(17,100.0,2,allowed=metersOn)} dBFS")
                add("输入 RMS   ${n(20,100.0,2,allowed=metersOn)} / ${n(21,100.0,2,allowed=metersOn)} dBFS")
                add("输出峰值   ${n(18,100.0,2,allowed=metersOn)} / ${n(19,100.0,2,allowed=metersOn)} dBFS")
                add("输出 RMS   ${n(22,100.0,2,allowed=metersOn)} / ${n(23,100.0,2,allowed=metersOn)} dBFS")
                add("超限       ${n(24,allowed=metersOn)} 帧")
            }.joinToString("\n")
        }+"\n读数仅代表本模块；峰值与 RMS 是当前音频块的统计，采样峰值不是真峰值、LUFS 或耳内声压。静态图不含两路实时动态起音。试听、匹配与快照不写入预设；收起、离页、暂停、设备会话／采样率／电源变化清除快照。声音参数变化停止试听并保留快照，方便保存 B。"
    }
    private fun toast(s:String)=Toast.makeText(context,s,Toast.LENGTH_LONG).show()
    private fun attempt(action:()->Unit){try {action()}catch(e:Exception){toast(e.message ?: "操作失败")}}
    private fun stopPlayback(){c.stopAudition(context,this)}
    fun stopAudition(){c.stopAudition(context,this,clearSnapshots=true)}
    fun close(){stopAudition();renderFrame.close();closed=true;invalidateRows();library?.dismiss();pendingExport=null;group.onExpandedChanged=null;prefs.unregisterOnSharedPreferenceChangeListener(listener);status.unregisterOnSharedPreferenceChangeListener(listener);context.unregisterLocalReceiver(receiver)}
}
