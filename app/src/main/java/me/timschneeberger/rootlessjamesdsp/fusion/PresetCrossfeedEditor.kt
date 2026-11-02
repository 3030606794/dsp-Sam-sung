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
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale

class PresetCrossfeedEditor(private val context:Context,private val group:SwitchPreferenceGroup,host:Fragment){
    private val p=PresetCrossfeedParameters
    private val prefs=PresetCrossfeedController.preferences(context)
    private val status=PresetCrossfeedController.statusPreferences(context)
    private val store=PresetCrossfeedPresetStore(context)
    private val rows=linkedMapOf<String,BassNumericPreference>()
    private var pendingExport:String?=null
    private var closed=false
    private var library:PresetCrossfeedPresetsDialog?=null
    private val importLauncher=host.registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null && !closed)attempt {
            val json=context.contentResolver.openInputStream(uri)?.use(PresetCrossfeedPresetCodec::read) ?: error("无法读取预设文件")
            val count=store.importJson(json);library?.refresh();toast("已导入 $count 项耳机空间模拟预设")
        }
    }
    private val exportLauncher=host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val json=pendingExport;pendingExport=null
        if(uri!=null && json!=null && !closed)attempt {
            context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(json.toByteArray(Charsets.UTF_8))} ?: error("无法写入预设文件")
            toast("耳机空间模拟预设已导出")
        }
    }
    private fun info(key:String,title:String,order:Int)=object : RuntimeStatusPreference(context){
        override fun onBindViewHolder(holder:PreferenceViewHolder){super.onBindViewHolder(holder)
            for(id in listOf(android.R.id.title,android.R.id.summary))(holder.findViewById(id) as? TextView)?.apply {
                setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}
    }.apply {this.key=key;this.title=title;this.order=order;isIconSpaceReserved=false;isSelectable=false}
    private val professional=SwitchPreferenceCompat(context).apply {
        key=p.PROFESSIONAL;title="专业精调";summary="默认关闭。以所选预设为基础精调；关闭恢复当前预设的原始声音。"
        order=10;isIconSpaceReserved=false;setDefaultValue(false)
    }
    private fun toggle(key:String,title:String,summary:String,order:Int,default:Boolean=false)=SwitchPreferenceCompat(context).apply {
        this.key=key;this.title=title;this.summary=summary;this.order=order;isIconSpaceReserved=false;setDefaultValue(default)
    }
    private val path=toggle(p.PATH_ON,"左右交叉路径精调","分别调整两个混入方向；0 dB 保持原模型电平。",80)
    private val linked=toggle(p.PATH_LINK,"联动左右路径","开启后两侧均采用“左声道混入右声道”的数值；另一侧数值保留，解除联动后恢复。",81,true)
    private val filter=toggle(p.FILTER_ON,"交叉支路低通","仅过滤混入另一耳的声音，不改变直达支路。",84)
    private val slope=FieldChoicePreference(context,p.FILTER_SLOPE,"低通斜率",listOf("6 dB/oct（一阶）","12 dB/oct（两级一阶）")).apply {
        order=86;setOnPreferenceChangeListener {_,v->prefs.edit().putInt(p.FILTER_SLOPE,(v as Int)+1).apply();true}
    }
    private val center=toggle(p.CENTER_ON,"中央声音补偿","调整两声道共有的中央成分；侧向成分保持原比例。",87)
    private val advancedRows get()=listOf(path,linked,filter,slope,center)
    private val help=info("crossfeed6_help","调节说明",90).apply {
        summary="六种原预设始终作为声音基础。切换预设会载入其中性精调参数，并保留专业精调开关。BS2B 可调截止频率和交叉衰减；空间预设可调原模型的交叉支路电平。附加延时只作用于交叉支路，0 μs 表示不再增加延时。混合 0% 为本模块原声，输出衰减仍有效。新增左右路径、支路低通和中央补偿默认关闭。中央补偿作用于混合后的共同成分；12 dB/oct 为两级一阶串联（设置频率处约 −6 dB），实际低通频率不超过采样率的 45%。切换基础预设也会重置这些进阶设置。"
    }
    private val presets=Preference(context).apply {
        key="crossfeed6_presets";title="耳机空间模拟预设";summary="系统方案 · 个人 20 项 · 导入／导出";order=100;isIconSpaceReserved=false
        setOnPreferenceClickListener {
            stopPlayback()
            library?.dismiss()
            library=PresetCrossfeedPresetsDialog(context,store,{p.capture(prefs.all)},{settings->
                stopPlayback();p.apply(prefs,settings)
            },{importLauncher.launch(arrayOf("application/json","text/plain","application/octet-stream"))},{id->
                pendingExport=store.export(id);exportLauncher.launch("耳机空间模拟预设.json")
            }).also {it.show()};true
        }
    }
    private fun save(slot:Int)=Preference(context).apply {
        key=if(slot==0)"crossfeed6_save_a"else"crossfeed6_save_b";title=if(slot==0)"保存试听快照 A"else"保存试听快照 B"
        order=110+slot;isIconSpaceReserved=false
        setOnPreferenceClickListener {if(!PresetCrossfeedController.saveComparison(context,this@PresetCrossfeedEditor,slot))toast("请先开启专业调节");renderStatus();true}
    }
    private val saveA=save(0);private val saveB=save(1)
    private val measure=Preference(context).apply {
        key="crossfeed6_measure";title="测量／重新测量 A/B 电平";order=125;isIconSpaceReserved=false
        summary="模型充分预热后测量 2 秒；同一输入同时处理 A/B。"
        setOnPreferenceClickListener {
            if(!PresetCrossfeedController.measure(context,this@PresetCrossfeedEditor))toast("请先保存 A/B 快照，并播放经过 DSP 的音乐")
            true
        }
    }
    private val audition=FieldChoicePreference(context,"crossfeed6_audition","临时试听",
        listOf("正常处理","本模块原声","效果差分（−6 dB）","仅交叉馈送支路","快照 A","快照 B","测量 A/B 电平"),"恢复正常").apply {
        order=120;setOnPreferenceChangeListener {_,next->
            PresetCrossfeedController.startAudition(context,this@PresetCrossfeedEditor,next as Int).also {if(!it)toast("试听需要活动音频；A/B 和测量需先保存两个快照")}
        }
    }
    private val match=SwitchPreferenceCompat(context).apply {
        key="crossfeed6_match";title="匹配 A/B 电平";summary="先测量同一输入的 A/B RMS，再固定衰减；不持续追踪、不提升电平。"
        order=130;isPersistent=false;isIconSpaceReserved=false;setDefaultValue(false)
        setOnPreferenceChangeListener {_,v->PresetCrossfeedController.setMatch(context,v as Boolean).also {if(!it)toast("请先完成 A/B 电平测量")}}
    }
    private val stop=Preference(context).apply {
        key="crossfeed6_stop";title="停止试听／恢复保存设置";order=140;isIconSpaceReserved=false
        setOnPreferenceClickListener {stopAudition();true}
    }
    private val actual=StableRuntimeReadoutPreference(context).apply {
        key="crossfeed6_actual";title="试听状态";order=150;isPersistent=false
    }
    private val compatibility=info("crossfeed6_compatibility","驱动支持",160)
    private val overlap=info("crossfeed6_overlap","交叉馈送叠加提示",170).apply {
        summary="上方“耳机交叉馈送”也已开启，两模块将顺序处理。单独试听本模块时，可手动关闭上方模块。"
    }
    private val jamesPrefs=me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache.getPreferences(context,ViperParameters.PREFS)
    private val renderFrame=me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate {full->if(full)render()else renderStatus()}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {source,key->
        if(closed)return@OnSharedPreferenceChangeListener
        if(source===prefs && (key in p.newKeys || key==p.BASE || key==p.ENABLED))PresetCrossfeedController.stopAudition(context,this)
        if(source===prefs && p.numbers.any {it.key==key}) {
            val row=rows[key];if(row!=null){val value=p.value(prefs.all,row.control);if(row.value!=value)row.value=value}
            renderFrame.request(false)
        }else renderFrame.request()
    }
    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,intent:Intent?){if(runtimeStatusVisible(context))renderFrame.request(false)}}
    init {
        p.normalize(prefs);PresetCrossfeedController.stopAudition(context,clearSnapshots=true)
        group.findPreference<androidx.preference.ListPreference>(p.BASE)?.setOnPreferenceChangeListener {_,value->
            stopPlayback();p.selectBase(prefs,(value as String).toInt());false
        }
        p.numbers.forEachIndexed {i,c->rows[c.key]=BassNumericPreference(context,c,p.value(prefs.all,c)).apply {
            order=when(c.key){p.leftToRight.key->82;p.rightToLeft.key->83;p.filterCutoff.key->85;p.center.key->88;else->20+i*10};resetAction={requestValue(p.defaultFor(p.base(prefs.all),c))}
        }}
        group.onExpandedChanged={if(!it)stopAudition()}
        prefs.registerOnSharedPreferenceChangeListener(listener);status.registerOnSharedPreferenceChangeListener(listener)
        jamesPrefs.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(PresetCrossfeedController.STATUS_ACTION))
        render()
    }
    private fun add(row:Preference){if(group.findPreference<Preference>(row.key)==null)group.addPreference(row)}
    private fun render(){
        if(closed)return
        p.normalize(prefs);val enabled=p.enabled(prefs.all);professional.isChecked=enabled;add(professional)
        group.findPreference<androidx.preference.ListPreference>(p.BASE)?.value=p.base(prefs.all).toString()
        val bs=p.base(prefs.all)<2
        rows.forEach {(key,row)->
            val applicable=when(key){p.cutoff.key,p.attenuation.key->bs;p.spatial.key->!bs;else->true}
            row.isEnabled=enabled && applicable
            if(group.isProfessionalMode || enabled && applicable)add(row)else group.removePreference(row)
        }
        for(row in advancedRows){row.isEnabled=enabled;if(enabled || group.isProfessionalMode)add(row)else group.removePreference(row)}
        val advanced=p.captureAdvanced(prefs.all)
        path.isChecked=advanced.pathOn;linked.isChecked=advanced.linked;filter.isChecked=advanced.filterOn;center.isChecked=advanced.centerOn;slope.value=advanced.slope-1
        linked.isEnabled=enabled && advanced.pathOn
        rows[p.leftToRight.key]?.isEnabled=enabled && advanced.pathOn
        rows[p.rightToLeft.key]?.isEnabled=enabled && advanced.pathOn && !advanced.linked
        rows[p.filterCutoff.key]?.isEnabled=enabled && advanced.filterOn;slope.isEnabled=enabled && advanced.filterOn
        rows[p.center.key]?.isEnabled=enabled && advanced.centerOn
        if(group.isProfessionalMode || enabled)add(help)else group.removePreference(help)
        for(row in listOf(presets,saveA,saveB,audition,measure,match,stop,actual,compatibility))add(row)
        val key="v_cure"
        if(prefs.getBoolean(p.ENABLED,false) && jamesPrefs.getBoolean(key,false))add(overlap)else group.removePreference(overlap)
        renderStatus()
    }
    fun renderStatus(){
        if(closed)return
        val supported=PresetCrossfeedController.supported(context);val enabled=p.enabled(prefs.all)
        saveA.isEnabled=enabled && supported;saveB.isEnabled=enabled && supported
        saveA.summary=if(PresetCrossfeedController.hasComparison(0))"已保存，仅本次页面使用"else"未保存"
        saveB.summary=if(PresetCrossfeedController.hasComparison(1))"已保存，仅本次页面使用"else"未保存"
        audition.value=PresetCrossfeedController.audition;audition.isEnabled=PresetCrossfeedController.canListen(context)
        measure.isEnabled=audition.isEnabled && PresetCrossfeedController.hasComparison(0) && PresetCrossfeedController.hasComparison(1)
        match.isChecked=PresetCrossfeedController.match;match.isEnabled=PresetCrossfeedController.matchingReady()
        compatibility.summary=if(PresetCrossfeedController.advancedSupported(context))"已连接配套进阶驱动。左右路径、交叉支路低通与中央补偿可用；关闭专业精调恢复当前预设原声。"
            else if(supported)"已连接基础精调驱动；本轮左右路径、支路低通与中央补偿暂不生效，请更新配套驱动并重启。"
            else"当前驱动不支持新增处理。专业设置可编辑并保留，实际仍使用原六预设；更新配套驱动并重启后生效。"
        val readings=PresetCrossfeedController.runtime
        actual.summary=actualStatus(supported,enabled,readings)+"\n试听点位于本模块之后，后续效果仍会改变声音。快照、匹配和试听均不写入预设；收起、离页、暂停、参数编辑或会话失效时退出。"
    }
    /** Retain complete semantic rows when a GET is briefly missing; absent readings never reuse old numbers. */
    private fun actualStatus(supported:Boolean,professional:Boolean,readings:List<PresetCrossfeedController.Reading>):String {
        val sessions: List<PresetCrossfeedController.Reading?> = if(readings.isEmpty())listOf(null)else readings
        fun fixed(raw:Int?,divisor:Double=100.0,decimals:Int=2)=
            StableRuntimeReadoutPreference.fixedNumber(raw,divisor,decimals,width=8)
        fun peakField(raw:Int?):String=if(raw==Int.MIN_VALUE)"−∞".padStart(8)else fixed(raw)
        val labels=listOf("正常处理","原声","差分","交叉支路","快照 A","快照 B","测量")
        val matchingReady=PresetCrossfeedController.matchingReady()
        return sessions.mapIndexed {index,reading->
            val v=reading?.takeIf {supported && professional && prefs.getBoolean(p.ENABLED,false) && it.enabled && it.active}?.snapshot?.data?.takeIf {
                it[3]==1 && it[4]==1 && it[5]==PresetCrossfeedController.audition && it[23]==1 &&
                    (PresetCrossfeedController.audition==0 || it[10]==PresetCrossfeedController.epoch)
            }
            val tools=v?.takeIf {it[10]==PresetCrossfeedController.epoch}
            val state=when {
                !supported->listOf("等待配套驱动","专业设置可编辑并保留")
                !prefs.getBoolean(p.ENABLED,false)->listOf("本模块关闭","编辑参数不会自动启用")
                !professional->listOf("原预设处理","精调试听未启用")
                v==null->listOf("等待读数／新处理窗口","当前试听状态待确认")
                else->listOf("正在处理音频","真实读数，未使用旧值填充")
            }
            val measured=tools?.get(6)==2 && matchingReady
            val measureState=when(tools?.get(6)){
                1->"模型预热／测量中"
                2->if(matchingReady)"测量完成"else"等待当前快照的测量结果"
                3->"信号不足，未建立匹配"
                0->"未测量"
                else->"—"
            }
            val fullScale=v?.let {it[20]>=0 || it[21]>=0}
            val header=if(sessions.size>1)"会话 ${index+1}\n"else""
            header+(state+listOf(
                "处理 ${fixed(reading?.rate?.takeIf {it in 8000..768000},1.0,0)} Hz",
                "当前试听：${v?.get(5)?.let {labels.getOrNull(it)} ?: "—"}",
                "测量状态：$measureState",
                "测量已用 ${fixed(tools?.get(7),1.0,0)} ms",
                "固定衰减 A ${fixed(tools?.get(8)?.takeIf {measured})} dB",
                "固定衰减 B ${fixed(tools?.get(9)?.takeIf {measured})} dB",
                "模块输出峰值：",
                "左 ${peakField(v?.get(20))} dBFS",
                "右 ${peakField(v?.get(21))} dBFS",
                "约 0.5 秒衰减保持",
                "满幅提示：${when(fullScale){true->"达到满幅";false->"未达到满幅";null->"等待读数"}}",
                "达到满幅时请降低输出",
                "模型充分预热后测量 2 秒",
                "同一输入同时处理 A/B"
            )).joinToString("\n")
        }.joinToString("\n\n")
    }
    private fun peak(value:Int)=if(value==Int.MIN_VALUE)"−∞"else db(value)
    private fun db(value:Int)=String.format(Locale.ROOT,"%.2f",value/100.0)
    private fun toast(message:String)=Toast.makeText(context,message,Toast.LENGTH_LONG).show()
    private fun attempt(action:()->Unit){try{action()}catch(e:Exception){toast(e.message ?: "操作失败")}}
    private fun stopPlayback(){PresetCrossfeedController.stopAudition(context,this)}
    fun stopAudition(){PresetCrossfeedController.stopAudition(context,this,clearSnapshots=true)}
    fun close(){
        renderFrame.close()
        stopAudition();closed=true;library?.dismiss();pendingExport=null;group.onExpandedChanged=null
        prefs.unregisterOnSharedPreferenceChangeListener(listener);status.unregisterOnSharedPreferenceChangeListener(listener)
        jamesPrefs.unregisterOnSharedPreferenceChangeListener(listener);context.unregisterLocalReceiver(receiver)
    }
}
