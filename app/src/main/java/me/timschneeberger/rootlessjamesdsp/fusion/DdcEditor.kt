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
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale

/** The selected source is always editable; additional controls never rewrite its coefficients. */
class DdcEditor(private val context: Context, private val group: SwitchPreferenceGroup, host: Fragment) {
    private val p=DdcParameters;private val c=DdcController
    private val prefs=c.preferences(context);private val status=c.statusPreferences(context)
    private val store=DdcPresetStore(context)
    private val rows=linkedMapOf<String,BassNumericPreference>()
    private var rowFlags: List<Boolean>?=null
    private var foundation: DdcFoundation?=null
    private var foundationError: String?=null
    private var foundationEpoch=0L
    private var closed=false
    private var library: DdcPresetsDialog?=null
    private var pendingExport: String?=null
    private val importLauncher=host.registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null && !closed)attempt {val json=context.contentResolver.openInputStream(uri)?.use(DdcPresetCodec::read) ?: error("无法读取预设文件");val n=store.importJson(json);library?.refresh();toast("已导入 $n 项 DDC 预设")}
    }
    private val exportLauncher=host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        val json=pendingExport;pendingExport=null
        if(uri!=null && json!=null && !closed)attempt {context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(json.toByteArray(Charsets.UTF_8))} ?: error("无法写入预设文件");toast("DDC 预设已导出")}
    }
    private fun multiline(holder:PreferenceViewHolder){listOf(android.R.id.title,android.R.id.summary).forEach {id->(holder.findViewById(id) as? TextView)?.apply {setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}}
    private fun info(key:String,title:String,order:Int)=object : RuntimeStatusPreference(context){override fun onBindViewHolder(holder:PreferenceViewHolder){super.onBindViewHolder(holder);multiline(holder)}}.apply {this.key=key;this.title=title;this.order=order;isIconSpaceReserved=false;isSelectable=false}
    private fun action(key:String,title:String,order:Int)=info(key,title,order).apply {isSelectable=true}
    private fun toggle(key:String,title:String,body:String,order:Int)=object:SwitchPreferenceCompat(context){override fun onBindViewHolder(holder:PreferenceViewHolder){super.onBindViewHolder(holder);multiline(holder)}}.apply {
        this.key=key;this.title=title;summary=body;this.order=order;isIconSpaceReserved=false;setDefaultValue(key==p.LINKED)
        setOnPreferenceChangeListener {_,_->!closed && p.enabled(prefs.all) && isEnabled}
    }
    private val professional=toggle(p.PROFESSIONAL,"专业调节","默认关闭。校正文件作为基础，个人补偿叠加其上；关闭后恢复当前文件的原 DDC 处理。",100).apply {
        isPersistent=false;setOnPreferenceChangeListener {_,n->if(closed)false else {if(n==false)stopAudition()else stopPlayback();p.setProfessional(prefs,n as Boolean);true}}
    }
    private val baseline=info("ddc_pro_baseline","所选文件与实际应用",101)
    private val ratio=toggle(p.RATIO_ON,"① 校正混合比例","100% 完整应用当前 VDC，0% 旁路基础 DDC；个人补偿仍可作用。混合受相位影响，50% 不等于校正曲线的 dB 值减半。",110)
    private val compensation=toggle(p.COMPENSATION_ON,"② 个人补偿精调","在校正混合之后增加低／高架式与两路峰式滤波器，各路可单独启用。原文件保持不变。",120)
    private val low=toggle(p.LOW_ON,"启用低频架式补偿","低频总体增减；坡度 S 为 RBJ 架式参数，不是 dB/oct。",121)
    private val high=toggle(p.HIGH_ON,"启用高频架式补偿","高频总体增减；增益为 0 dB 时，频率与坡度不会产生音色变化。",125)
    private val peak1=toggle(p.PEAK1_ON,"启用峰式补偿 1","选择中心频率、增益与 Q；Q 越高频段越窄。",129)
    private val peak2=toggle(p.PEAK2_ON,"启用峰式补偿 2","第二路独立峰式补偿，不含人声识别或动态去齿音。",133)
    private val ears=toggle(p.EAR_ON,"③ 双耳微调","左右共用当前 VDC；实际校正比例为主比例乘各耳权重，耳电平在补偿后作用。",140)
    private val linked=toggle(p.LINKED,"左右联动","默认联动：左右均使用左耳权重和电平，右耳保存值置灰并保留；解除后恢复右耳独立值。",141)
    private val preamp=toggle(p.PREAMP_ON,"④ 启用前级衰减","在基础 DDC 的干／湿两路之前共同衰减；0 dB 为当前文件原电平。",150)
    private val auto=toggle(p.AUTO_HEADROOM_ON,"启用预测静态余量","根据基础文件、混合、补偿和双耳／输出增益预测静态峰值，再加入安全边际，仅衰减。有限预测不是限制器，也不能保证所有音乐瞬态不削波。",153)
    private val output=toggle(p.OUTPUT_ON,"启用输出增益","最后调整本模块电平；正增益可能增加削波，请结合预测余量和电平读数。",156)
    private val meters=toggle(p.METERS,"⑥ 显示输入／输出电平","当前音频块的采样峰值与 RMS，输出读数包含当前试听路由。不是 LUFS、真峰值或耳内声压；2 秒测量只用于 A/B 匹配。",200)
    private val response=toggle(p.RESPONSE,"显示校正与双耳响应","原文件与实际左右输出的复数滤波响应；采样率变化后重新计算，不改写保存的频率。",201)
    private val toggles=listOf(ratio,compensation,low,high,peak1,peak2,ears,linked,preamp,auto,output,meters,response)
    private val responseDescription=info("ddc_pro_response_description","DDC 原文件与双耳输出响应",202).apply {
        summary="等待配套驱动与活动音频；开启专业调节和响应图后在顶部联动响应图显示。横轴 Hz，纵轴 dB。淡线为原文件，实线为左输出，虚线为右输出；输出包含校正混合、个人补偿、双耳电平和余量／输出增益。图属于当前主配置，不含临时 A/B 试听；交接完成前显示旧有效配置。图为算法计算结果，不是耳机实测频响。顶部响应图可点击查看频点。"
    }
    private val help=info("ddc_pro_help","DDC 精调说明",203).apply {summary="""DDC 使用文件中 44.1／48 kHz 的滤波系数；不是耳机实测曲线，也不能从任意系数准确还原原作者的频率／增益／Q。
校正混合比例为原声与校正声的线性混合，存在相位作用。低／高架和峰式补偿始终位于混合之后；校正为 0% 时它们仍能生效。单项复位回到基础：比例及双耳权重 100%，附加增益 0 dB；频率、Q 和坡度恢复中性补偿的初始值。
双耳联动只改变实际使用值，右耳保存值不被改写。关闭分组恢复其基础作用，参数保留；关闭专业调节恢复原文件处理。
显示实际 JamesDSP 内部处理率与宿主率。频率安全限制按内部率计算，保存值不变；不支持的内部率安全旁路并提示。
文件先完整校验，失败保留上一有效校正；已接受新文件与实际听到的新文件分开显示，平滑交接完成后才更新实际身份。"""}
    private val presets=action("ddc_pro_presets","⑤ DDC 独立预设",190).apply {summary="当前文件及专业参数 · 个人 20 项 · 导入／导出";setOnPreferenceClickListener {
        stopPlayback();library?.dismiss();library=DdcPresetsDialog(context,store,{requireNotNull(foundation){foundationError ?: "请先选择有效 DDC 文件"}},{p.capture(prefs.all)},{f,s->
            val matches=f.matchesSelection(context);val path=f.install(context)
            stopAudition();invalidateRows();val e=prefs.edit();p.put(e,s.values());e.putString(p.FILE,path).apply()
            group.findPreference<FileLibraryPreference>(p.FILE)?.apply {refresh();value=path}
            refreshFoundation();render();if(!matches)toast("原文件丢失或内容已改变，已从收藏还原校正文件")
        },{importLauncher.launch(arrayOf("application/json","text/plain","application/octet-stream"))},{id->pendingExport=store.export(id);exportLauncher.launch("DDC 频响校正预设.json")},canSave={foundation!=null && p.validSavedFoundation(prefs.all)}).also {it.show()};true
    }}
    private fun save(slot:Int)=action(if(slot==0)"ddc_pro_save_a" else "ddc_pro_save_b",if(slot==0)"保存试听快照 A" else "保存试听快照 B",210+slot).apply {setOnPreferenceClickListener {if(!c.saveComparison(context,this@DdcEditor,slot))toast("请先应用有效校正文件并连接配套驱动");renderStatus();true}}
    private val saveA=save(0);private val saveB=save(1)
    private val audition=FieldChoicePreference(context,"ddc_pro_audition","临时试听",listOf("正常处理","本模块输入原声","处理后效果","效果差分","快照 A","快照 B","测量 A/B 电平"),"恢复正常").apply {order=220;setOnPreferenceChangeListener {_,n->c.startAudition(context,this@DdcEditor,n as Int).also {if(!it)toast("试听需要已生效的同一文件与活动音频；A/B 需两个快照，匹配需先测量")}}}
    private val measure=action("ddc_pro_measure","测量／重新测量 A/B 电平",230).apply {summary="同一输入并行处理两套设置，预热后测量 2 秒。";setOnPreferenceClickListener {if(!c.measure(context,this@DdcEditor))toast("请保存同一文件的 A/B 快照并播放音乐");true}}
    private val match=toggle("ddc_pro_match","匹配 A/B 电平","同一输入的 A/B RMS 测量完成后固定衰减，仅减小较响一侧，不持续追踪。",240).apply {isPersistent=false;setOnPreferenceChangeListener {_,n->c.setMatch(context,n as Boolean).also {if(!it)toast("请先完成 A/B 电平测量")}}}
    private val stop=action("ddc_pro_stop","停止试听／恢复保存设置",250).apply {setOnPreferenceClickListener {stopAudition();true}}
    private val actual=StableRuntimeReadoutPreference(context).apply {key="ddc_pro_actual";title="试听与实际处理状态";order=260}
    private val compatibility=info("ddc_pro_compatibility","驱动支持",270)
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {source,key->
        if(closed)return@OnSharedPreferenceChangeListener
        if(source===prefs && key !in setOf(p.METERS,p.RESPONSE)) {
            if(key==p.FILE || key==p.ENABLED && !p.moduleEnabled(prefs.all) || key==p.PROFESSIONAL && !p.enabled(prefs.all))stopAudition()else stopPlayback()
        }
        if(key==p.FILE || source===status && key=="file_revision"){foundationEpoch++;refreshFoundation()}
        if(source===prefs && p.numbers.any {it.key==key}){rows[key]?.let {if(it.value!=p.value(prefs.all,it.control))it.value=p.value(prefs.all,it.control)};renderFrame.request(false)}else renderFrame.request()
    }
    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,intent:Intent?){
            if (!runtimeStatusVisible(this@DdcEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
            if(intent?.action==Constants.ACTION_PRESET_LOADED){stopAudition();foundationEpoch++;refreshFoundation();invalidateRows();render()}else renderFrame.request(false)}}
    init {
        p.normalize(prefs);c.stopAudition(context,clearSnapshots=true);refreshFoundation()
        group.onExpandedChanged={if(!it)stopAudition()};prefs.registerOnSharedPreferenceChangeListener(listener);status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(c.STATUS_ACTION).apply {addAction(Constants.ACTION_PRESET_LOADED)})
        render()
    }
    private fun refreshFoundation(){val path=prefs.all[p.FILE] as? String
        val result=runCatching {require(!path.isNullOrBlank()){ "尚未选择校正文件" };DdcFoundation.selected(context,path)}
        foundation=result.getOrNull();foundationError=result.exceptionOrNull()?.message
    }
    private fun add(row:Preference){if(group.findPreference<Preference>(row.key)==null)group.addPreference(row)}
    private fun invalidateRows(){foundationEpoch++;rows.values.forEach {it.isEnabled=false;group.removePreference(it)};rows.clear();rowFlags=null}
    private fun ensureRows(){val v=prefs.all;val flags=p.switches.filterNot {it in setOf(p.METERS,p.RESPONSE)}.map {p.on(v,it)};if(rowFlags==flags)return
        invalidateRows();rowFlags=flags
        p.numbers.forEach {control->rows[control.key]=BassNumericPreference(context,control,p.value(v,control)).apply {
            order=when(control){p.ratio->111;in p.lowNumbers->122+p.lowNumbers.indexOf(control);in p.highNumbers->126+p.highNumbers.indexOf(control);in p.peak1Numbers->130+p.peak1Numbers.indexOf(control);in p.peak2Numbers->134+p.peak2Numbers.indexOf(control);in p.earNumbers->142+p.earNumbers.indexOf(control);p.preamp->151;p.margin->154;else->157}
            dialogToken={foundationEpoch};rejectedValueMessage="校正文件或分组状态已改变，请重新打开输入框。"
            setOnPreferenceChangeListener {_,n->!closed && foundation!=null && p.numberEnabled(control.key,prefs.all) && (n as Int) in control.min..control.max}
        }}
    }
    private fun render(){if(closed)return;p.normalize(prefs);ensureRows();val v=prefs.all;val pro=p.enabled(v)
        add(professional);professional.isChecked=pro;add(baseline)
        group.findPreference<Preference>(p.FILE)?.isEnabled=true
        rows.forEach {(key,row)->row.isEnabled=foundation!=null && p.numberEnabled(key,v);if(row.value!=p.value(v,row.control))row.value=p.value(v,row.control);if(pro || group.isProfessionalMode)add(row)else group.removePreference(row)}
        toggles.forEach {row->row.isChecked=p.on(v,row.key);row.isEnabled=pro && when(row.key){p.LOW_ON,p.HIGH_ON,p.PEAK1_ON,p.PEAK2_ON->p.on(v,p.COMPENSATION_ON);p.LINKED->p.on(v,p.EAR_ON);else->true};if(pro || group.isProfessionalMode)add(row)else group.removePreference(row)}
        if(pro || group.isProfessionalMode){add(responseDescription);add(help)}else {group.removePreference(responseDescription);group.removePreference(help)}
        listOf(presets,saveA,saveB,audition,measure,match,stop,actual,compatibility).forEach(::add);renderStatus()
    }
    fun renderStatus(){if(closed)return;val supported=c.supported(context);val s=p.capture(prefs.all);val selected=foundation
        val bindings=c.fileBindings();val active=c.runtime.filter {it.enabled && it.active}
        baseline.summary=buildString {
            if(selected==null)append("所选文件无效：${foundationError ?: "未知原因"}。保留上次有效校正，不用默认值覆盖。")else append("已选择：${selected.label}\n内容指纹 ${selected.sha256.take(16)}…")
            bindings.distinct().forEach {b->append("\n已接受：${b.label.ifBlank {"尚无有效文件"}}；${b.appliedSha256?.take(16) ?: "无"}");if(b.loading)append("\n所选文件装载中，上一有效校正继续处理；完成后再交接。")else b.error?.let {append("\n加载失败：$it；上一有效校正保留。")}}
            active.mapNotNull {it.snapshot}.forEach {r->
                val v=r.data
                val state=when {
                    v[12]==0 && v[60]==1 && v[49]>0->"新文件／参数预热中，仍显示旧主配置"
                    v[61]!=v[19]->"已接受新目标，等待主配置绑定"
                    v[60]==1->"主配置交接中"
                    else->"已稳定"
                }
                append("\n实际主配置：${r.activeSha256?.take(16) ?: "无"}；$state")
            }
            append("\n校正文件始终可选择；精调与文件系数独立保存，单项复位不改变原文件。")
        }
        saveA.isEnabled=selected!=null && c.selectedSha256(context)==selected.sha256 && c.canListen(context);saveB.isEnabled=saveA.isEnabled
        saveA.summary=if(c.hasComparison(0))"已保存，仅当前页面及同一文件使用"else "保存当前文件基础与专业设置";saveB.summary=if(c.hasComparison(1))"已保存，仅当前页面及同一文件使用"else "保存当前文件基础与专业设置"
        audition.value=c.audition;audition.isEnabled=c.canListen(context);measure.isEnabled=audition.isEnabled && c.hasComparison(0) && c.hasComparison(1);match.isChecked=c.match;match.isEnabled=audition.isEnabled && c.matchingReady()
        compatibility.summary=if(supported)"已连接本轮 DDC 专业驱动；校正与其他模块独立。"else "当前驱动不支持新增处理，实际继续原文件 DDC。专业参数可保存；请更新本轮配套驱动并重启。"
        val moduleEnabled=p.moduleEnabled(prefs.all)
        val matchingReady=c.matchingReady()
        val selectedSha=c.selectedSha256(context)
        val streams=c.runtime.map {it as DdcController.Reading?}.ifEmpty {listOf(null)}
        actual.summary=streams.joinToString("\n\n") {r->
            val snapshot=r?.snapshot
            val stableFile=snapshot!=null && snapshot.fileReady && snapshot.data[50]==0 && snapshot.data[60]==0 &&
                selectedSha!=null && snapshot.activeSha256==selectedSha && snapshot.acceptedSha256==selectedSha
            val currentAudio=snapshot?.mode==c.audition && (c.audition==0 || snapshot?.epoch==c.epoch && stableFile)
            // An accepted target and the still-active main file intentionally differ during handover.
            val valid=supported && moduleEnabled && r!=null && r.enabled && r.active && snapshot!=null &&
                snapshot.enabled && snapshot.ready && snapshot.data[16]==r.rate && r.rate in 8000..768000 &&
                snapshot.rate==expectedInternalRate(r.rate) && currentAudio
            val v=if(valid)snapshot?.data else null
            val tools=if(valid && snapshot?.epoch==c.epoch && stableFile)v else null
            val attenuation=tools?.takeIf {it[8]==3 && matchingReady}
            val metersOn=v?.get(13)==1
            fun n(index:Int,divisor:Double=1.0,decimals:Int=0,width:Int=7,allowed:Boolean=true)=
                StableRuntimeReadoutPreference.fixedNumber(v?.get(index).takeIf {allowed},divisor,decimals,width=width)
            fun t(index:Int)=StableRuntimeReadoutPreference.fixedNumber(tools?.get(index),1.0,0,width=6)
            val phase=when(tools?.get(8)){0->"未测量";1->"预热中";2->"测量中";3->if(matchingReady)"测量完成" else "等待确认";4->"信号不足，请播放后重测";else->"等待工具确认"}
            val statusText=when {
                !supported->"等待配套驱动\n—表示暂无有效读数。"
                !moduleEnabled->"本模块关闭；\n编辑参数不会自动开启"
                r==null || !r.enabled || !r.active->"当前没有活动处理会话；\n试听状态等待驱动确认"
                v==null->"等待读数\n—表示暂无有效读数。"
                selected==null->"所选文件无效；\n上一有效校正继续显示。"
                v[60]==1->"新目标等待绑定或交接；\n读数仍属于旧有效主配置。"
                else->"当前主配置读数；\n与保存目标、试听路由分别标注。"
            }
            buildList {
                add(statusText)
                add("所选文件：${selected?.label ?: foundationError ?: "尚未选择有效文件"}")
                fun sha(label:String,value:String?) {
                    add(label)
                    add(value?.take(32) ?: "—")
                    add(value?.drop(32) ?: "—")
                }
                sha("所选内容 SHA256",selected?.sha256)
                sha("驱动已接受 SHA256",snapshot?.acceptedSha256.takeIf {valid})
                sha("实际主配置 SHA256",snapshot?.activeSha256.takeIf {valid})
                add("内部处理率 ${n(2)} Hz")
                add("宿主输出率 ${n(16)} Hz")
                add("VDC 系数率 ${n(17)} Hz")
                add("VDC 系数组 ${n(18)}")
                add("44.1/48k 使用对应文件系数。")
                add("其他支持率由48k系数映射；以文件有效标志为准。")
                add("有效文件：${when{v==null->"—";v[5]==1->"已有有效校正";else->"当前未有有效校正"}}")
                add("内部率状态：${when{v==null->"—";v[50]==1->"不支持，安全旁路 DDC";else->"支持"}}")
                add("文件处理状态码 ${n(51)}")
                add("错误或装载失败时保留上一有效校正。")
                add("活动主配置代 ${n(19)}")
                add("已接受目标代 ${n(61)}")
                add("保存专业目标 ${n(4)}（1 开启／0 关闭）")
                add("当前试听模式 ${n(6)}")
                add("待绑定或交接 ${n(60)}（1 是／0 否）")
                add("主配置预热 ${n(48,allowed=v?.get(12)==0 && v?.get(60)==1 && (v?.get(49) ?: 0)>0)} / ${n(49,allowed=v?.get(12)==0 && v?.get(60)==1 && (v?.get(49) ?: 0)>0)} ms")
                add("A/B 状态：$phase")
                add("A/B 预热   ${t(48)} / ${t(49)} ms")
                add("A/B 测量   ${t(9)} / 2000 ms")
                add("固定衰减 A ${StableRuntimeReadoutPreference.fixedNumber(attenuation?.get(10),100.0,2)} dB")
                add("固定衰减 B ${StableRuntimeReadoutPreference.fixedNumber(attenuation?.get(11),100.0,2)} dB")
                add("以下属于实际主配置，非 A/B 试听输出")
                add("左耳校正比例 ${n(32,10.0,1)} %")
                add("右耳校正比例 ${n(33,10.0,1)} %")
                add("左耳电平 ${n(34,10.0,1)} dB")
                add("右耳电平 ${n(35,10.0,1)} dB")
                add("自动衰减 ${n(29,100.0,2)} dB")
                add("手动前级 ${n(30,10.0,1)} dB")
                add("模块输出 ${n(31,10.0,1)} dB")
                add("预测峰值（未乘自动衰减） ${n(59,100.0,2)} dB")
                add("主配置自动余量 ${n(52)}（1 开／0 关）")
                add("主配置双耳联动 ${n(53)}（1 开／0 关）")
                add("主配置个人补偿 ${n(54)}（1 开／0 关）")
                add("低架补偿 ${n(55)}（1 开／0 关）")
                add("高架补偿 ${n(56)}（1 开／0 关）")
                add("峰 1 补偿 ${n(57)}（1 开／0 关）")
                add("峰 2 补偿 ${n(58)}（1 开／0 关）")
                add("低架实际频率 ${n(44)} Hz")
                add("高架实际频率 ${n(45)} Hz")
                add("峰 1 实际频率 ${n(46)} Hz")
                add("峰 2 实际频率 ${n(47)} Hz")
                add("当前音频块电平（左／右；实际试听路由）")
                add("实时电平：${when{v==null->"—";metersOn->"开启";else->"已关闭"}}")
                add("输入峰值 ${n(20,100.0,2,allowed=metersOn)} / ${n(21,100.0,2,allowed=metersOn)} dBFS")
                add("输入 RMS ${n(24,100.0,2,allowed=metersOn)} / ${n(25,100.0,2,allowed=metersOn)} dBFS")
                add("输出峰值 ${n(22,100.0,2,allowed=metersOn)} / ${n(23,100.0,2,allowed=metersOn)} dBFS")
                add("输出 RMS ${n(26,100.0,2,allowed=metersOn)} / ${n(27,100.0,2,allowed=metersOn)} dBFS")
                add("超限 ${n(28,allowed=metersOn)} 帧")
            }.joinToString("\n")
        }+"\n图和读数仅代表本模块；校正曲线不是耳机声学实测。试听及快照不写入预设；切文件、收起、离页、暂停、电源／专业关闭、会话或内外采样率变化清除快照。声音参数变化停止试听并保留同文件快照。"
    }
    private fun expectedInternalRate(host:Int)=if(host>=44100)host else if(host>0 && 48000%host==0)48000 else if(host>0 && 44100%host==0)44100 else 48000
    private fun toast(text:String)=Toast.makeText(context,text,Toast.LENGTH_LONG).show()
    private fun attempt(action:()->Unit){try {action()}catch(e:Exception){toast(e.message ?: "操作失败")}}
    private fun stopPlayback(){c.stopAudition(context,this)}
    fun stopAudition(){c.stopAudition(context,this,clearSnapshots=true)}
    fun close() { renderFrame.close();stopAudition();closed=true;invalidateRows();library?.dismiss();pendingExport=null;group.onExpandedChanged=null;prefs.unregisterOnSharedPreferenceChangeListener(listener);status.unregisterOnSharedPreferenceChangeListener(listener);context.unregisterLocalReceiver(receiver)}
}
