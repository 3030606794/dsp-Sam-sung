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
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import me.timschneeberger.rootlessjamesdsp.preference.CompanderPreference
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale

class CompanderEditor(private val context: Context, private val group: SwitchPreferenceGroup, host: Fragment) {
    private val p=CompanderParameters
    private val c=CompanderController
    private val prefs=c.preferences(context)
    private val status=c.statusPreferences(context)
    private val store=CompanderPresetStore(context)
    private val rows=linkedMapOf<String,BassNumericPreference>()
    private var rowFlags: List<Any>?=null
    private var closed=false
    private var pendingExport: String?=null
    private var library: CompanderPresetsDialog?=null
    private val importLauncher=host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null && !closed) attempt {
            val json=context.contentResolver.openInputStream(uri)?.use(CompanderPresetCodec::read) ?: error("无法读取预设文件")
            val count=store.importJson(json); library?.refresh(); toast("已导入 $count 项动态压扩器预设")
        }
    }
    private val exportLauncher=host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json=pendingExport; pendingExport=null
        if(uri!=null && json!=null && !closed) attempt {
            context.contentResolver.openOutputStream(uri,"wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("无法写入预设文件")
            toast("动态压扩器预设已导出")
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
    private val professional=toggle(p.PROFESSIONAL,"专业调节","默认关闭。保留原七点曲线与四种算法声音；新分组在原保存基础上生效，关闭恢复。标准压扩引擎另选，不替换旧保存值。",10).apply {
        isPersistent=false;setOnPreferenceChangeListener {_,next->stopPlayback();p.setProfessional(prefs,next as Boolean);true}
    }
    private val baseline=info("compander_pro_baseline","当前基础与有效引擎",11)
    private fun choice(key: String,title: String,labels: List<String>,order: Int,reset: Int=0)=FieldChoicePreference(context,key,title,labels,"恢复默认",reset).apply {
        this.order=order;setOnPreferenceChangeListener {_,next->
            if(closed || !p.enabled(prefs.all) || !isEnabled) false else {prefs.edit().putInt(key,next as Int).apply();true}
        }
    }
    private val engine=choice(p.ENGINE,"处理引擎",listOf("原版七点动态曲线","标准阈值压扩"),12)
    private val core=toggle(p.CORE_ON,"启用核心精调","默认关闭。原版引擎接管旧曲线，调整七点修正与总强度；标准引擎开启固定阈值压扩。",20)
    private val exact=toggle(p.EXACT_FREQUENCY,"频点按实际 Hz 映射","默认关闭，保留原频域频率映射。仅原版核心组可用；开启会校准频点映射，已有非零曲线的音色可能改变。",21)
    private val type=choice(p.TYPE,"标准动态类型",listOf("向下压缩","向上压缩","向下扩展","噪声门"),40)
    private val limits=toggle(p.LIMITS_ON,"启用作用量限制","默认关闭。限制实际动态提升与衰减幅度；0 dB 表示禁止对应方向的动态增减。",50)
    private val time=toggle(p.TIME_ON,"启用时间与瞬态精调","默认关闭。原版背景跟随时间以保存值为基础，另加真实攻击、保持、释放平滑；显示各算法实际更新间隔。",60)
    private val detect=toggle(p.DETECT_ON,"启用检测与作用范围","默认关闭。原版的相对差值参照各频带历史背景，弱信号保护只停止动态作用，不静音。标准模式在此限制处理频段；向上压缩另可设置弱信号保护，其他标准类型不使用该保护。",70)
    private val linked=choice(p.LINKED,"左右联动检测",listOf("左右平均（原版基础）","较强声道"),71)
    private val detector=choice(p.DETECTOR,"标准电平检测",listOf("峰值检测","时间窗 RMS"),72,1)
    private val sidechain=toggle(p.SIDECHAIN_ON,"启用检测侧链滤波","默认关闭，仅标准引擎可用。低切／高切只过滤检测信号，听到的原声不会被侧链滤波。",80)
    private val ahead=toggle(p.LOOKAHEAD_ON,"启用前瞻处理","默认关闭，仅标准引擎可用。延迟音频以提前响应峰值，增加的延迟按当前采样率显示。",85)
    private val mix=toggle(p.MIX_ON,"启用干湿混合","默认关闭。0% 为按当前算法延迟对齐的输入，100% 为处理结果；滤波相位仍可能影响中间比例。",90)
    private val output=toggle(p.OUTPUT_ON,"启用输出增益","默认关闭。混合后固定衰减，0 dB 保留原电平；为增强后的峰值留出余量。",95)
    private val meters=toggle(p.METERS,"显示实时电平","默认关闭。显示本模块输入／输出峰值与 RMS、实际提升／衰减量、检测电平及超限帧数。",100)
    private val response=toggle(p.RESPONSE,"显示实时动态增益图","默认关闭。图中是各频率的实际增益控制量和检测状态；动态系数无量纲，增益以 dB 表示，二者独立。",101)
    private val toggles=listOf(core,exact,limits,time,detect,sidechain,ahead,mix,output,meters,response)
    private val choices=listOf(engine,type,linked,detector)
    private val homeNumbers=setOf(p.amount.key,p.mix.key,p.output.key,p.threshold.key)
    private val homeToggles=setOf(p.CORE_ON,p.MIX_ON,p.OUTPUT_ON)
    private val responseExplanation=info("compander_pro_response_description","实际动态增益控制图说明",110).apply {
        summary="等待配套驱动与活动音频；开启专业调节和实时动态增益图后在顶部响应图显示。横轴 Hz，纵轴 dB。亮线为作用频段，淡线为频段外。\n图为算法实际动态增益控制量，不是完整传递频响或房间测量；频带滤波相位仍影响最终声音。七点系数无量纲，不能当作 dB 或压缩比。频率、增益、系数、检测与背景电平在顶部联动响应图查看。"
    }
    private val help=info("compander_pro_help","压扩调节说明",115).apply {
        summary="七点频率和系数为相对原曲线的修正，单项复位回当前基础值；频率滑杆使用当前节点附近的合法微调窗口，输入和加减仍保留 0.001 Hz 精度；系数不是压缩比或 dB。总作用强度的基础值为 100%。切换基础保留修正，由驱动按采样率保持有效频率顺序。旧曲线和时间仅在对应组接管时置灰，标准引擎生效时四项旧控制均置灰，关闭专业模式恢复。\n标准引擎采用固定 dBFS 阈值和真实比率，2.00:1 就是 2:1；噪声门不使用比率。处理频段通过滤波支路作用，其相位会影响最终混合；实时增益控制图不能代表完整传递频响。\n攻击、保持、释放为真实平滑；频域算法受帧更新间隔限制，较小时间值不保证同等实际响应。前瞻增加延迟，无法消除全部过载，不是砖墙限制器。噪声门关闭时为衰减，未开作用量限制时衰减 60 dB；启用后由最大衰减量决定。向上压缩在低于 −100 dBFS 时停止提升，向上压缩的检测组开启后另使用弱信号保护电平，保护只停止提升并保留原声。其他标准类型不使用弱信号保护；向下扩展与噪声门持续处理低电平，不会因触及保护电平恢复原声。"
    }
    private val presets=action("compander_pro_presets","动态压扩器独立预设",120).apply {
        summary="原版兼容与标准压扩方案 · 个人 20 项 · 导入／导出"
        setOnPreferenceClickListener {
            stopPlayback(); library?.dismiss()
            library=CompanderPresetsDialog(context,store,{ p.capture(prefs.all) },{ s ->
                stopPlayback(); invalidateRows(); p.apply(prefs,s); render()
            },{ importLauncher.launch(arrayOf("application/json","text/plain","application/octet-stream")) },{ id ->
                pendingExport=store.export(id); exportLauncher.launch("动态压扩器预设.json")
            },canSave={p.validSavedFoundation(prefs.all)}).also { it.show() }; true
        }
    }
    private fun save(slot: Int)=action(if(slot==0) "compander_pro_save_a" else "compander_pro_save_b",if(slot==0) "保存试听快照 A" else "保存试听快照 B",130+slot).apply {
        setOnPreferenceClickListener { if(!c.saveComparison(context,this@CompanderEditor,slot)) toast("请先连接配套驱动"); renderStatus(); true }
    }
    private val saveA=save(0); private val saveB=save(1)
    private val audition=FieldChoicePreference(context,"compander_pro_audition","临时试听",
        listOf("正常处理","延迟对齐的输入原声","仅处理后效果","效果差分","快照 A","快照 B","测量 A/B 电平"),"恢复正常").apply {
        order=140; setOnPreferenceChangeListener { _,next -> c.startAudition(context,this@CompanderEditor,next as Int).also {
            if(!it) toast("试听需要活动音频；A/B 需先保存两个快照，电平匹配需先完成测量")
        } }
    }
    private val measure=action("compander_pro_measure","测量／重新测量 A/B 电平",150).apply {
        summary="同一输入同时处理两套快照，滤波与自适应状态预热后测量 2 秒。"
        setOnPreferenceClickListener { if(!c.measure(context,this@CompanderEditor)) toast("请先保存 A/B 快照，并播放经过 DSP 的音乐"); true }
    }
    private val match=toggle("compander_pro_match","匹配 A/B 电平","先测量同一输入的 A/B RMS，再固定衰减；不持续追踪、不提升电平。",160).apply {
        isPersistent=false; setOnPreferenceChangeListener { _,next -> c.setMatch(context,next as Boolean).also { if(!it) toast("请先完成 A/B 电平测量") } }
    }
    private val stop=action("compander_pro_stop","停止试听／恢复保存设置",170).apply { setOnPreferenceClickListener { stopAudition(); true } }
    private val actual=StableRuntimeReadoutPreference(context).apply {
        key="compander_pro_actual";title="试听与实际处理状态";order=180
    }
    private val compatibility=info("compander_pro_compatibility","驱动支持",190)
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener { source,key ->
        if(closed) return@OnSharedPreferenceChangeListener
        if(source===prefs && key !in setOf(p.METERS,p.RESPONSE)) stopPlayback()
        if(source===prefs && p.numbers.any { it.key==key }) {
            rows[key]?.let { row -> val n=p.value(prefs.all,row.control); if(row.value!=n) row.value=n }; rows.values.forEach(::updateFrequencySlider); renderBaseline(); renderFrame.request(false)
        } else renderFrame.request()
    }
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(c: Context?,intent: Intent?) {
            if (!runtimeStatusVisible(this@CompanderEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
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
        val v=prefs.all
        val flags=listOf<Any>(p.engine(v),p.type(v),p.detector(v),p.legacyTime(v),p.legacyGranularity(v),p.legacyTransform(v),p.legacyCurve(v))+p.switches.filterNot {it in setOf(p.METERS,p.RESPONSE)}.map {p.on(v,it)}
        if(flags==rowFlags) return
        invalidateRows();rowFlags=flags
        p.numbers.forEach {control->rows[control.key]=BassNumericPreference(context,control,p.value(v,control)).apply {
            order=when(control) {
                in p.coreNumbers -> 22+p.coreNumbers.indexOf(control)
                in p.standardNumbers -> 41+p.standardNumbers.indexOf(control)
                in p.limitNumbers -> 51+p.limitNumbers.indexOf(control)
                in p.timeNumbers -> 61+p.timeNumbers.indexOf(control)
                in p.detectNumbers -> 73+p.detectNumbers.indexOf(control)
                p.sideHP->81;p.sideLP->82;p.lookahead->86;p.mix->91;else->96
            }
            if(control in p.frequencies) {
                updateFrequencySlider(this);stepLabel={"0.001 Hz"}
                valueFormatter={n->val config=context.resources.configuration;if(config.screenWidthDp<=360 && config.fontScale>=1.3f) "${control.number(n)}\nHz" else control.label(n)}
            }
            val minimum=when(control) {p.rangeLow,p.rangeHigh,p.sideHP->20;p.sideLP->500;else->0}
            if(minimum!=0) {
                valueFormatter={if(it==0) "不限（0 Hz）" else control.label(it)}
                stepAction={delta->requestValue(if(value==0 && delta>0) minimum else if(value==minimum && delta<0) 0 else (value.toLong()+delta).coerceIn(control.min.toLong(),control.max.toLong()).toInt())}
                stepLabel={if(value==0 || value==minimum) "有效频率档位" else "1 Hz"}
            }
            rejectedValueMessage="频点须保持升序与有效范围；频段下限须小于上限，侧链低切须小于高切（或设 0 Hz）。状态或基础改变后请重新打开输入框。"
            setOnPreferenceChangeListener {_,next->!closed && p.numberEnabled(control.key,prefs.all) && p.candidateValid(prefs.all,control,next as Int)}
        }}
    }
    private fun updateFrequencySlider(row: BassNumericPreference) {
        val index=p.frequencies.indexOf(row.control);if(index<0) return
        val range=p.frequencySliderRange(prefs.all,index)
        row.sliderEnabled=range!=null && row.value in range
        if(range==null) {row.sliderRange=0..1;row.sliderStep=1;return}
        val low=kotlin.math.ceil(range.first/1000.0).toInt()*1000
        val high=kotlin.math.floor(range.last/1000.0).toInt()*1000
        if(low<high) {row.sliderRange=low..high;row.sliderStep=1000}
        else {row.sliderRange=range;row.sliderStep=1}
    }
    private fun renderBaseline() {
        if(!p.validSavedFoundation(prefs.all)) {baseline.summary=invalidFoundationMessage()+"请重新设置或应用有效预设。不会用默认参数覆盖保存值。";return}
        val s=p.capture(prefs.all);val curve=requireNotNull(p.parseCurve(s.legacyCurve))
        val names=context.resources.getStringArray(me.timschneeberger.rootlessjamesdsp.R.array.compander_tf_transforms)
        baseline.summary="原保存基础：${names[s.legacyTransform.toInt()]}；粒度 ${s.legacyGranularity.toInt()+1}/5；背景跟随 ${String.format(Locale.ROOT,"%.3f",s.legacyTime)} s。\n七点频率 Hz：${curve.frequencies.joinToString("／") {String.format(Locale.ROOT,"%.3f",it)}}\n七点动态系数（无量纲）：${curve.coefficients.joinToString("／") {String.format(Locale.ROOT,"%.3f",it)}}\n" +
            if(!s.professional) "当前使用原版，专业参数只保留。" else if(s.engine==0) "当前使用原版七点引擎；频率与系数精调在以上基础叠加。" else "当前使用标准阈值引擎；旧曲线、时间、粒度和算法已置灰，原保存值保留，关闭专业调节恢复。"
    }
    private fun renderLegacy() {
        val v=prefs.all
        listOf(p.BASE_CURVE,p.BASE_TIME,p.BASE_GRANULARITY,p.BASE_TRANSFORM).forEachIndexed {i,key->
            group.findPreference<Preference>(key)?.apply {
                order=i
                val blocked=when(key) {p.BASE_CURVE->p.curveTakenOver(v);p.BASE_TIME->p.timeTakenOver(v);else->p.transformTakenOver(v)}
                isEnabled=!blocked
                setOnPreferenceChangeListener {_,next->
                    !closed && !when(key) {p.BASE_CURVE->p.curveTakenOver(prefs.all);p.BASE_TIME->p.timeTakenOver(prefs.all);else->p.transformTakenOver(prefs.all)} &&
                    (key!=p.BASE_CURVE || p.parseCurve(next as? String)!=null)
                }
                val persistent=isPersistent;isPersistent=false
                when(this) {
                    is CompanderPreference -> updateFromPreferences()
                    is MaterialSeekbarPreference -> setValue(if(key==p.BASE_TIME) p.legacyTime(v) else p.legacyGranularity(v).toInt().toFloat())
                    is ListPreference -> value=p.legacyTransform(v)
                }
                isPersistent=persistent
            }
        }
    }
    private fun render() {
        if(closed) return
        p.normalize(prefs);ensureRows();val v=prefs.all;val pro=p.enabled(v);val standard=p.engine(v)==1
        add(professional);professional.isChecked=pro;add(baseline);renderBaseline();renderLegacy()
        if(!group.isProfessionalMode) {
            professional.summary="开启后可手动启用核心、混合和输出；完整曲线编辑与精调请点标题设置。展开不会开启音效。"
            core.summary="七点引擎启用总作用强度；标准引擎启用固定阈值。两个快捷杆按当前引擎置灰。"
            mix.summary="开启后可调效果比例；0%为延迟对齐的输入，100%为处理结果。"
            output.summary="开启后可调混合后固定输出衰减。"
        }
        rows.forEach {(key,row)->row.isEnabled=p.numberEnabled(key,v);if(row.value!=p.value(v,row.control)) row.value=p.value(v,row.control);updateFrequencySlider(row);if(pro || group.isProfessionalMode || key in homeNumbers) add(row) else group.removePreference(row)}
        toggles.forEach {row->row.isChecked=p.on(v,row.key);row.isEnabled=pro && when(row.key) {p.EXACT_FREQUENCY->!standard && p.on(v,p.CORE_ON);p.SIDECHAIN_ON,p.LOOKAHEAD_ON->standard;else->true};if(pro || group.isProfessionalMode || row.key in homeToggles) add(row) else group.removePreference(row)}
        engine.value=p.engine(v);engine.isEnabled=pro
        type.value=p.type(v);type.isEnabled=pro && standard && p.on(v,p.CORE_ON)
        linked.value=p.linked(v);linked.isEnabled=pro && p.on(v,p.DETECT_ON)
        detector.value=p.detector(v);detector.isEnabled=pro && standard && p.on(v,p.CORE_ON)
        choices.forEach {if(pro || group.isProfessionalMode || it===engine) add(it) else group.removePreference(it)}
        if(pro || group.isProfessionalMode) {add(responseExplanation);add(help)} else {group.removePreference(responseExplanation);group.removePreference(help)}
        listOf(presets,saveA,saveB,audition,measure,match,stop,actual,compatibility).forEach(::add);renderStatus()
    }
    fun renderStatus() {
        if(closed) return
        val supported=c.supported(context); val s=p.capture(prefs.all)
        saveA.isEnabled=supported && p.validSavedFoundation(prefs.all); saveB.isEnabled=saveA.isEnabled
        saveA.summary=if(c.hasComparison(0)) "已保存，仅本次页面使用" else "保存当前原曲线、原算法与专业设置"
        saveB.summary=if(c.hasComparison(1)) "已保存，仅本次页面使用" else "保存当前原曲线、原算法与专业设置"
        audition.value=c.audition; audition.isEnabled=c.canListen(context)
        measure.isEnabled=audition.isEnabled && c.hasComparison(0) && c.hasComparison(1)
        match.isChecked=c.match; match.isEnabled=audition.isEnabled && c.matchingReady()
        compatibility.summary=if(supported) "已连接配套压扩器增强驱动；原七点引擎与标准阈值引擎独立选择，与动态低频塑形分别管理。" else "当前驱动不支持新增处理。专业参数可保存；实际继续原版处理，请更新本轮配套驱动并重启。"
        val moduleEnabled=p.moduleEnabled(prefs.all)
        val foundationValid=p.validSavedFoundation(prefs.all)
        val matchingReady=c.matchingReady()
        val streams=c.runtime.map {it as CompanderController.Reading?}.ifEmpty {listOf(null)}
        actual.summary=streams.joinToString("\n\n") {r->
            val snapshot=r?.snapshot
            val currentAudio=snapshot?.mode==c.audition && (c.audition==0 || snapshot?.epoch==c.epoch)
            val valid=foundationValid && supported && moduleEnabled && r!=null && r.enabled && r.active &&
                snapshot!=null && snapshot.enabled && snapshot.professional==s.professional && snapshot.ready &&
                snapshot.data[26]==r.rate && snapshot.data[2]==expectedInternalRate(r.rate) && currentAudio
            val v=if(valid)snapshot?.data else null
            // Normal audio need not use the comparison epoch; A/B tools always do.
            val tools=if(valid && snapshot?.epoch==c.epoch)v else null
            val seven=v?.get(13)==0
            val standard=v?.get(13)==1
            val metersOn=v?.get(12)==1
            fun n(index: Int,divisor: Double=1.0,decimals: Int=0,width: Int=7,allowed: Boolean=true)=
                StableRuntimeReadoutPreference.fixedNumber(v?.get(index).takeIf {allowed},divisor,decimals,width=width)
            fun tool(index: Int,width: Int=6)=StableRuntimeReadoutPreference.fixedNumber(tools?.get(index),1.0,0,width=width)
            fun frequency(index: Int,allowed: Boolean=true)=if(allowed && v?.get(index)==0) "旁路".padStart(7)
                else n(index,allowed=allowed)
            val statusText=when {
                !foundationValid->invalidFoundationMessage()+"\n请重新设置或应用有效预设。"
                !supported->"等待配套驱动\n—表示暂无有效读数。"
                !moduleEnabled->"本模块关闭；\n编辑参数不会自动启用"
                r==null || !r.enabled || !r.active->"当前没有活动处理会话；\n试听状态等待驱动确认"
                v==null->"等待读数\n—表示暂无有效读数。"
                v[4]==0->"当前原版兼容处理；\n开启专业调节后才能试听"
                else->"当前专业处理状态；\n—表示暂无有效读数。"
            }
            val phase=when(tools?.get(7)) {1->"预热中";2->"测量中";3->if(matchingReady)"测量完成" else "等待确认";4->"信号不足，播放后重测";0->"未测量";else->"等待工具确认"}
            val attenuation=tools?.takeIf {it[7]==3 && matchingReady}
            buildList {
                add(statusText)
                add("内部基础率 ${n(2)} Hz")
                add("宿主输出率 ${n(26)} Hz")
                add("试听模式   ${n(5)}")
                add("A/B 状态：$phase")
                add("预热       ${tool(29)} / ${tool(30)} ms")
                add("测量       ${tool(8)} / ${tool(31)} ms")
                add("固定衰减 A ${StableRuntimeReadoutPreference.fixedNumber(attenuation?.get(9),100.0,2)} dB")
                add("固定衰减 B ${StableRuntimeReadoutPreference.fixedNumber(attenuation?.get(10),100.0,2)} dB")
                add("处理引擎：${when(v?.get(13)){0->"原版七点动态曲线";1->"标准阈值压扩";else->"—"}}")
                add("频率映射：${if(!seven)"—" else if(v?.get(62)==1)"实际 Hz 映射" else "原版频率映射"}")
                repeat(7) {i->
                    add("节点 ${i+1}     ${n(64+i,allowed=seven)} Hz")
                    add("动态系数 ${i+1} ${n(71+i,1000.0,3,allowed=seven)}")
                }
                add("实际背景   ${n(33,10.0,1,allowed=seven)} ms")
                add("相对触发   ${n(37,10.0,1,allowed=seven)} dB")
                add("标准类型：${if(standard)listOf("向下压缩","向上压缩","向下扩展","噪声门")[requireNotNull(v)[46]] else "—"}")
                add("固定阈值   ${n(44,10.0,1,allowed=standard)} dBFS")
                add("标准比率   ${n(45,100.0,2,allowed=standard && v?.get(46)!=3)} :1")
                add("标准检测：${if(!standard)"—" else if(v?.get(47)==0)"峰值检测" else "时间窗 RMS"}")
                add("RMS 时间窗 ${n(48,allowed=standard && v?.get(47)==1)} ms")
                add("侧链低切   ${frequency(49,standard)} Hz")
                add("侧链高切   ${frequency(50,standard)} Hz")
                add("噪声门：${if(!standard || v?.get(46)!=3)"—（仅噪声门适用）" else if(v?.get(55)==1)"开放" else "关闭"}")
                add("门回差     ${n(56,10.0,1,allowed=standard && v?.get(46)==3)} dB")
                add("实际攻击   ${n(34,10.0,1)} ms")
                add("实际保持   ${n(35)} ms")
                add("实际释放   ${n(36)} ms")
                add("算法更新   ${n(42,1000.0,3)} ms")
                add("处理下限   ${frequency(51)} Hz")
                add("处理上限   ${frequency(52)} Hz")
                add("对齐延迟   ${n(32)} 采样")
                add("对齐时间   ${if(v!=null && v[2]>0)String.format(Locale.ROOT,"%7.2f",v[32]*1000.0/v[2]) else "—".padStart(7)} ms")
                add("其中前瞻   ${n(43)} 采样")
                add("实时电平：${when{v==null->"—";metersOn->"开启（左／右）";else->"已关闭（左／右）"}}")
                add("输入峰值   ${n(16,100.0,2,allowed=metersOn)} / ${n(17,100.0,2,allowed=metersOn)} dBFS")
                add("输入 RMS   ${n(20,100.0,2,allowed=metersOn)} / ${n(21,100.0,2,allowed=metersOn)} dBFS")
                add("输出峰值   ${n(18,100.0,2,allowed=metersOn)} / ${n(19,100.0,2,allowed=metersOn)} dBFS")
                add("输出 RMS   ${n(22,100.0,2,allowed=metersOn)} / ${n(23,100.0,2,allowed=metersOn)} dBFS")
                add("超限       ${n(24,allowed=metersOn)} 帧")
                add("当前最大提升 ${n(27,100.0,2,allowed=metersOn)} dB")
                add("当前最大衰减 ${n(28,100.0,2,allowed=metersOn)} dB")
                add("检测电平   ${n(41,100.0,2,allowed=metersOn)} dBFS")
            }.joinToString("\n")
        }+"\n电平仅代表本模块，后续效果仍会改变声音；采样峰值不是真峰值或耳内声压。图为动态增益控制量，不是测量频响；系数不等于 dB。试听、匹配与快照不写入预设。收起、离页、暂停或音频会话／采样率变化清除快照；声音参数变化停止试听并保留快照，方便调整后保存 B。"
    }
    private fun invalidFoundationMessage()=if(!p.validSavedCurve(prefs.all)) "原曲线无效，保留上次有效处理。" else "原时间、粒度或算法基础参数无效，保留上次有效处理。"
    private fun expectedInternalRate(host: Int)=if(host>=44100)host else if(host>0 && 48000%host==0)48000 else if(host>0 && 44100%host==0)44100 else 48000
    private fun toast(s: String)=Toast.makeText(context,s,Toast.LENGTH_LONG).show()
    private fun attempt(action: ()->Unit) { try { action() } catch(e: Exception) { toast(e.message ?: "操作失败") } }
    private fun stopPlayback() { c.stopAudition(context,this) }
    fun stopAudition() { c.stopAudition(context,this,clearSnapshots=true) }
    fun close() { renderFrame.close();
        stopAudition(); closed=true; invalidateRows(); library?.dismiss(); pendingExport=null; group.onExpandedChanged=null
        listOf(p.BASE_CURVE,p.BASE_TIME,p.BASE_GRANULARITY,p.BASE_TRANSFORM).forEach {group.findPreference<Preference>(it)?.onPreferenceChangeListener=null}
        prefs.unregisterOnSharedPreferenceChangeListener(listener); status.unregisterOnSharedPreferenceChangeListener(listener); context.unregisterLocalReceiver(receiver)
    }
}
