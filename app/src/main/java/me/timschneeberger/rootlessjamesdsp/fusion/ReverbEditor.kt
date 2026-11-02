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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ReverbEditor(private val context:Context,private val group:SwitchPreferenceGroup,host:Fragment){
    private val p=ReverbParameters
    private val advanced=ReverbAdvancedParameters
    private val prefs=ReverbController.preferences(context)
    private val status=ReverbController.statusPreferences(context)
    private val store=ReverbPresetStore(context)
    private val rows=linkedMapOf<String,BassNumericPreference>()
    private var pendingExport:String?=null
    private var closed=false
    private var library:ReverbPresetsDialog?=null
    private val importLauncher=host.registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null && !closed)attempt {
            val json=context.contentResolver.openInputStream(uri)?.use(ReverbPresetCodec::read) ?: error("无法读取预设文件")
            val count=store.importJson(json);library?.refresh();toast("已导入 $count 项经典混响预设")
        }
    }
    private val exportLauncher=host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val json=pendingExport;pendingExport=null
        if(uri!=null && json!=null && !closed)attempt {
            context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(json.toByteArray(Charsets.UTF_8))} ?: error("无法写入预设文件")
            toast("经典混响预设已导出")
        }
    }
    private fun info(key:String,title:String,order:Int)=object : RuntimeStatusPreference(context){
        override fun onBindViewHolder(holder:PreferenceViewHolder){super.onBindViewHolder(holder)
            for(id in listOf(android.R.id.title,android.R.id.summary))(holder.findViewById(id) as? TextView)?.apply {
                setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}
    }.apply {this.key=key;this.title=title;this.order=order;isIconSpaceReserved=false;isSelectable=false}
    private fun action(key:String,title:String,order:Int)=info(key,title,order).apply {isSelectable=true}
    private fun toggle(key:String,title:String,description:String,order:Int)=object:SwitchPreferenceCompat(context){
        override fun onBindViewHolder(holder:PreferenceViewHolder){super.onBindViewHolder(holder)
            for(id in listOf(android.R.id.title,android.R.id.summary))(holder.findViewById(id) as? TextView)?.apply {
                setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}
    }.apply {this.key=key;this.title=title;summary=description;this.order=order;isIconSpaceReserved=false;setDefaultValue(false)}
    private val professional=toggle(p.PROFESSIONAL,"专业调节","默认关闭。开启后接管原五项；本模块电源仍独立控制。",10).apply {
        isPersistent=false
        setOnPreferenceChangeListener {_,next->p.setProfessional(prefs,next as Boolean);true}
    }
    private val linked=toggle(p.LINKED,"干湿联动","默认关闭。开启后混合比例接管干声／湿声，原独立数值保留；0% 为原声，100% 为混响声，采用线性混合。",70)
    private val predelay=toggle(p.PREDELAY_ON,"启用混响预延迟","默认关闭。只延后混响支路，原声保持原时序。",90)
    private val lowCut=toggle(p.LOW_CUT_ON,"启用混响声低切","默认关闭。减少混响中的低频，不处理干声。",110)
    private val highCut=toggle(p.HIGH_CUT_ON,"启用混响声高切","默认关闭。控制尾音亮度；低采样率时实际频率受采样率限制。",130)
    private val output=toggle(p.OUTPUT_ON,"启用输出增益","默认关闭。对本模块混合后的整体声音作衰减。",150)
    private val meters=toggle(p.METERS,"显示实时电平","默认关闭。显示本模块输入、混响支路和输出的采样峰值及超限帧数；关闭再开启可清零。",170)
    private val rt60=toggle(advanced.RT_ON,"秒制尾音时间 RT60","默认关闭。按秒设定反馈衰减参考时间；开启后房间大小精调置灰，关闭后恢复保存值。",171)
    private val bands=toggle(advanced.BANDS_ON,"分频衰减调节","默认关闭。低频／高频倍率改变反馈中的尾音衰减，开启后阻尼精调置灰；关闭后恢复。分界附近为平滑过渡。",174)
    private val duck=toggle(advanced.DUCK_ON,"人声避让混响","默认关闭。按整体输入电平压低湿声，停顿时释放尾音；左右联动，干声保持原音量。也会响应鼓声等强信号，不识别人声。",180)
    private val graphToggle=toggle(advanced.GRAPH_ON,"显示尾音衰减预测","默认关闭。显示当前保存声音在驱动中的低／中／高频反馈衰减预测；不包含避让包络、试听切换和后续效果，也不是房间实测。",188).apply {
        if(group.isProfessionalMode)summary=summary.toString()+" 预测图已移至顶部联动静图区，原开关及驱动预测状态保留。"
    }
    private val graph:Preference=if(group.isProfessionalMode)StableRuntimeReadoutPreference(context).apply {
        key="v_reverb_pro_decay_graph";title="尾音衰减预测 · 算法模型";order=189
    }else ReverbDecayPreference(context).apply {order=189}
    private val toggles=listOf(linked,predelay,lowCut,highCut,output,meters,rt60,bands,duck,graphToggle)
    private val help=info("v_reverb_pro_help","调节说明",195).apply {
        summary="专业五项按 0.1% 调节，首次开启复制原五项，之后两组参数独立保留。秒制尾音时间接管房间大小，分频衰减接管阻尼；关闭对应开关即恢复。RT60 表示反馈模型中衰减 60 dB 的时间，分频过渡和整体尾音存在差异。原房间大小仍为反馈百分比。低切／高切只改变混响声频谱，分频衰减改变尾音消失速度。独立模式干声50%对应原声音量；联动模式0%为原声、100%为混响声。"
    }
    private val presets=action("v_reverb_pro_presets","经典混响预设",200).apply {
        summary="系统方案 · 个人 20 项 · 导入／导出"
        setOnPreferenceClickListener {
            stopPlayback()
            library?.dismiss()
            library=ReverbPresetsDialog(context,store,{p.capture(prefs.all)},{settings->
                stopPlayback();p.apply(prefs,settings)
            },{importLauncher.launch(arrayOf("application/json","text/plain","application/octet-stream"))},{id->
                pendingExport=store.export(id);exportLauncher.launch("经典混响预设.json")
            }).also {it.show()};true
        }
    }
    private fun save(slot:Int)=action(if(slot==0)"v_reverb_pro_save_a"else"v_reverb_pro_save_b",
        if(slot==0)"保存试听快照 A"else"保存试听快照 B",210+slot).apply {
        setOnPreferenceClickListener {if(!ReverbController.saveComparison(context,this@ReverbEditor,slot))toast("请先连接配套驱动");renderStatus();true}
    }
    private val saveA=save(0);private val saveB=save(1)
    private val measure=action("v_reverb_pro_measure","测量／重新测量 A/B 电平",225).apply {
        summary="A/B 各自充分预热后测量 2 秒，预热时间随拖尾与采样率变化；同一输入同时处理两套快照。"
        setOnPreferenceClickListener {
            if(!ReverbController.measure(context,this@ReverbEditor))toast("请先保存 A/B 快照，并播放经过 DSP 的音乐")
            true
        }
    }
    private val audition=FieldChoicePreference(context,"v_reverb_pro_audition","临时试听",
        listOf("正常处理","本模块原声","效果差分（−6 dB）","仅混响声","快照 A","快照 B","测量 A/B 电平"),"恢复正常").apply {
        order=220;setOnPreferenceChangeListener {_,next->
            ReverbController.startAudition(context,this@ReverbEditor,next as Int).also {if(!it)toast("试听需要活动音频；A/B 和测量需先保存两个快照")}
        }
    }
    private val match=SwitchPreferenceCompat(context).apply {
        key="v_reverb_pro_match";title="匹配 A/B 电平";summary="先测量同一输入的 A/B RMS，再固定衰减；不持续追踪、不提升电平。"
        order=230;isPersistent=false;isIconSpaceReserved=false;setDefaultValue(false)
        setOnPreferenceChangeListener {_,v->ReverbController.setMatch(context,v as Boolean).also {if(!it)toast("请先完成 A/B 电平测量")}}
    }
    private val stop=action("v_reverb_pro_stop","停止试听／恢复保存设置",240).apply {
        setOnPreferenceClickListener {stopAudition();true}
    }
    private val actual=StableRuntimeReadoutPreference(context).apply {
        key="v_reverb_pro_actual";title="试听与电平状态";order=250
    }
    private val compatibility=info("v_reverb_pro_compatibility","驱动支持",260)
    private val overlap=info("v_reverb_pro_overlap","混响叠加提示",270).apply {
        summary="下方“场景混响”也已开启，两模块将顺序处理。单独比较本模块时，可手动关闭场景混响。"
    }
    private val jamesPrefs=me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache.getPreferences(context,
        me.timschneeberger.rootlessjamesdsp.utils.Constants.PREF_REVERB)
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {source,key->
        if(closed)return@OnSharedPreferenceChangeListener
        if(source===prefs && (key in p.newKeys || key in p.legacyKeys || key==p.ENABLED))ReverbController.stopAudition(context,this)
        if(source===prefs && p.allNumbers.any {it.key==key}) {
            val row=rows[key];if(row!=null){val value=p.value(prefs.all,row.control);if(row.value!=value)row.value=value}
            renderFrame.request(false)
        }else renderFrame.request()
    }
    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,intent:Intent?){
            if (!runtimeStatusVisible(this@ReverbEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
            renderFrame.request(false)}}
    init {
        p.normalize(prefs);ReverbController.stopAudition(context,clearSnapshots=true)
        p.allNumbers.forEachIndexed {i,c->rows[c.key]=BassNumericPreference(context,c,p.value(prefs.all,c)).apply {
            order=when(c){p.predelay->100;p.lowCut->120;p.highCut->140;p.mix->80;p.output->160
                advanced.rt60->172
                in advanced.bandNumbers->175+advanced.bandNumbers.indexOf(c)
                in advanced.duckNumbers->181+advanced.duckNumbers.indexOf(c)
                else->20+i*10}
        }}
        group.onExpandedChanged={if(!it)stopAudition()}
        prefs.registerOnSharedPreferenceChangeListener(listener);status.registerOnSharedPreferenceChangeListener(listener)
        jamesPrefs.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(ReverbController.STATUS_ACTION))
        render()
    }
    private fun add(row:Preference){if(group.findPreference<Preference>(row.key)==null)group.addPreference(row)}
    private fun render(){
        if(closed)return
        p.normalize(prefs);val enabled=p.enabled(prefs.all);add(professional);professional.isChecked=enabled
        professional.summary=if(enabled)"专业参数已接管，原五项暂时置灰；关闭后恢复原五项保存值。本模块电源仍独立控制。"
            else"默认关闭。首次开启复制原五项；关闭后恢复原五项，专业设置单独保留。"
        p.legacyControls.zip(p.legacy(prefs.all)).forEach {(c,value)->group.findPreference<ViperSliderPreference>(c.key)?.apply {
            if(this.value!=value)this.value=value;disabledHint="专业调节已接管";isEnabled=!enabled
        }}
        rows.forEach {(key,row)->
            row.isEnabled=enabled && when(key){
                p.size.key->!p.on(prefs.all,advanced.RT_ON)
                p.damp.key->!p.on(prefs.all,advanced.BANDS_ON)
                advanced.rt60.key->p.on(prefs.all,advanced.RT_ON)
                in advanced.bandNumbers.map {it.key}->p.on(prefs.all,advanced.BANDS_ON)
                in advanced.duckNumbers.map {it.key}->p.on(prefs.all,advanced.DUCK_ON)
                p.wet.key,p.dry.key->!p.on(prefs.all,p.LINKED)
                p.predelay.key->p.on(prefs.all,p.PREDELAY_ON)
                p.lowCut.key->p.on(prefs.all,p.LOW_CUT_ON)
                p.highCut.key->p.on(prefs.all,p.HIGH_CUT_ON)
                p.mix.key->p.on(prefs.all,p.LINKED)
                p.output.key->p.on(prefs.all,p.OUTPUT_ON)
                else->true
            }
            if(enabled || group.isProfessionalMode)add(row)else group.removePreference(row)
        }
        toggles.forEach {row->row.isChecked=p.on(prefs.all,row.key);row.isEnabled=enabled;if(enabled || group.isProfessionalMode)add(row)else group.removePreference(row)}
        if(group.isProfessionalMode || enabled && p.on(prefs.all,advanced.GRAPH_ON))add(graph)else group.removePreference(graph)
        if(group.isProfessionalMode || enabled)add(help)else group.removePreference(help)
        for(row in listOf(presets,saveA,saveB,audition,measure,match,stop,actual,compatibility))add(row)
        val key=context.getString(me.timschneeberger.rootlessjamesdsp.R.string.key_reverb_enable)
        if(prefs.getBoolean(p.ENABLED,false) && jamesPrefs.getBoolean(key,false))add(overlap)else group.removePreference(overlap)
        renderStatus()
    }
    fun renderStatus(){
        if(closed)return
        val supported=ReverbController.supported(context);val enabled=p.enabled(prefs.all)
        saveA.isEnabled=supported;saveB.isEnabled=supported
        val snapshotHint=if(enabled)"保存当前专业声音；仅本次页面使用"else"保存当前原五项声音；仅本次页面使用"
        saveA.summary=if(ReverbController.hasComparison(0))"已保存，仅本次页面使用"else snapshotHint
        saveB.summary=if(ReverbController.hasComparison(1))"已保存，仅本次页面使用"else snapshotHint
        audition.value=ReverbController.audition;audition.isEnabled=ReverbController.canListen(context)
        measure.isEnabled=audition.isEnabled && ReverbController.hasComparison(0) && ReverbController.hasComparison(1)
        match.isChecked=ReverbController.match;match.isEnabled=ReverbController.matchingReady()
        compatibility.summary=when {
            ReverbController.advancedSupported(context)->"已连接配套混响增强驱动。专业调节关闭时恢复原五项和原版声音。"
            supported->"当前驱动支持第一轮专业调节，秒制尾音、分频衰减、避让和衰减图暂不生效；设置会保留，更新配套驱动后生效。"
            else->"当前驱动不支持新增处理。专业设置可编辑并保留，实际仍使用原五项；更新配套驱动并重启后生效。"
        }
        val readings=ReverbController.runtime
        val decay=if(ReverbController.advancedSupported(context))readings.firstOrNull {it.enabled && it.active && it.advanced?.ready==true}?.advanced else null
        (graph as? ReverbDecayPreference)?.update(decay)
        if(graph is StableRuntimeReadoutPreference)renderDecayStatus(decay)
        val streams=readings.map {it as ReverbController.Reading?}.ifEmpty {listOf(null)}
        val moduleEnabled=prefs.getBoolean(p.ENABLED,false)
        val matchingReady=ReverbController.matchingReady()
        actual.summary=streams.joinToString("\n\n"){r->
            val snapshot=r?.snapshot
            val valid=supported && moduleEnabled && enabled && r!=null && r.enabled && r.active &&
                snapshot!=null && snapshot.enabled && snapshot.professional && snapshot.ready &&
                snapshot.mode==ReverbController.audition &&
                (ReverbController.audition==0 || snapshot.epoch==ReverbController.epoch)
            val v=if(valid)snapshot?.data else null
            val tools=if(snapshot?.epoch==ReverbController.epoch)v else null
            val statusText=when {
                !supported->"等待配套驱动\n—表示暂无有效读数。"
                !moduleEnabled->"本模块关闭；\n编辑参数不会自动启用"
                !enabled->"原五项处理；\n开启专业调节后才能试听"
                r==null || !r.enabled || !r.active->"等待活动处理帧；\n试听状态等待驱动确认"
                v==null->"等待读数\n—表示暂无有效读数。"
                else->"实际处理状态；\n—表示暂无有效读数。"
            }
            val measurement=tools?.get(7)
            val phase=when(measurement){
                1->"A/B 预热"
                2->"A/B 电平测量"
                3->if(matchingReady)"测量完成，固定衰减"else"等待当前快照的测量结果"
                4->"信号不足，未建立匹配"
                0->"未测量"
                else->"—"
            }
            fun number(index:Int,divisor:Double=1.0,decimals:Int=0)=StableRuntimeReadoutPreference.fixedNumber(v?.get(index),divisor,decimals)
            fun toolNumber(index:Int)=StableRuntimeReadoutPreference.fixedNumber(tools?.get(index),1.0,0)
            fun attenuation(index:Int)=StableRuntimeReadoutPreference.fixedNumber(
                if(measurement==3 && matchingReady)tools?.get(index)else null,100.0,2)
            fun cutoff(index:Int)=v?.get(index)?.let {if(it==0)"关闭"else"$it Hz"} ?: "—"
            val levelsOn=v?.get(23)==1
            fun peakValue(index:Int)=StableRuntimeReadoutPreference.fixedNumber(if(levelsOn)v?.get(index)else null,100.0,2)
            val ducking=if(valid)r?.advanced?.takeIf {
                it.sampleRate==r?.rate && it.data[3]==1 && it.data[4]==1 && it.data[7]==1
            }?.duckCentiDb else null
            val rate=r?.rate?.toString()?.padStart(6) ?: "—".padStart(6)
            val currentAudition=v?.get(5)?.let {listOf("正常处理","原声","差分","仅混响声","快照 A","快照 B","测量").getOrNull(it)} ?: "—"
            "处理 $rate Hz\n$statusText\n当前试听：$currentAudition\n测量状态：$phase\n"+
                "A/B 预热 ${toolNumber(24)} / ${toolNumber(25)} ms\n随后测量 ${toolNumber(26)} ms\n"+
                "A/B 电平测量 ${toolNumber(8)} / ${toolNumber(26)} ms\n"+
                "固定衰减 A ${attenuation(9)}／B ${attenuation(10)} dB\n"+
                "信号不足时请播放音乐后重测\n实际预延迟 ${number(13,10.0,1)} ms；\n"+
                "低切 ${cutoff(14)}／高切 ${cutoff(15)}\n"+
                "${if(levelsOn)"实时电平已开启"else if(v==null)"实时电平状态等待确认"else"实时电平已关闭"}\n"+
                "采样峰值 dBFS（左／右）\n输入 ${peakValue(16)}／${peakValue(17)}；\n"+
                "湿声 ${peakValue(18)}／${peakValue(19)}\n输出 ${peakValue(20)}／${peakValue(21)}；\n"+
                "超限 ${if(levelsOn)number(22)else StableRuntimeReadoutPreference.fixedNumber(null)} 帧\n"+
                "当前保存声音的湿声避让：${StableRuntimeReadoutPreference.fixedNumber(ducking,100.0,2)} dB；\n按整体输入电平触发"
        }+"\n试听与电平取自本模块，后续效果仍会改变声音。峰值不是耳内声压或真峰值；仅混响声取实际湿声支路。快照、测量及试听状态不写入预设；收起、离页、暂停会清除快照，参数编辑或会话失效时停止试听。"
    }
    private fun renderDecayStatus(reading:ReverbAdvancedParameters.Runtime?){
        val rate=reading?.sampleRate?.toString()?.padStart(6) ?: "—".padStart(6)
        fun seconds(ms:Int?)=StableRuntimeReadoutPreference.fixedNumber(ms,1000.0,2)+" s"
        val frequencies=reading?.let {listOf(min(80.0,it.data[14]/4.0),sqrt(it.data[14].toDouble()*it.data[15]),
            min(it.sampleRate*.45,max(8000.0,it.data[15]*2.0)))}
        fun hzAt(index:Int)=frequencies?.get(index)?.roundToInt()?.toString()?.padStart(6) ?: "—".padStart(6)
        graph.summary="$rate Hz\n低频 ${seconds(reading?.decayMs?.get(0))}，中频 ${seconds(reading?.decayMs?.get(1))}，高频 ${seconds(reading?.decayMs?.get(2))}。\n"+
            "参考 ${seconds(reading?.referenceMs)}；\n代表频率 ${hzAt(0)}／${hzAt(1)}／${hzAt(2)} Hz。\n"+
            "${if(reading==null)"等待配套驱动与活动音频。开启本模块和专业调节后显示。"else"当前保存声音的驱动反馈衰减预测。"}\n"+
            "实线／长虚线／点线分别为低／中／高频。横轴为时间，纵轴为相对衰减；−60 dB 为各频段预测时间。\n"+
            "使用当前保存参数的反馈系数估算代表频率衰减，分界 ${reading?.data?.get(14) ?: "—"}／${reading?.data?.get(15) ?: "—"} Hz。"+
            "不包括预延迟、避让动态包络、试听切换和后续效果，不代表真实房间测量。"
    }
    private fun hz(value:Int)=if(value==0)"关闭"else"$value Hz"
    private fun peak(value:Int)=if(value==Int.MIN_VALUE)"—"else db(value)
    private fun db(value:Int)=String.format(Locale.ROOT,"%.2f",value/100.0)
    private fun toast(message:String)=Toast.makeText(context,message,Toast.LENGTH_LONG).show()
    private fun attempt(action:()->Unit){try{action()}catch(e:Exception){toast(e.message ?: "操作失败")}}
    private fun stopPlayback(){ReverbController.stopAudition(context,this)}
    fun stopAudition(){ReverbController.stopAudition(context,this,clearSnapshots=true)}
    fun close() { renderFrame.close();
        stopAudition();closed=true;library?.dismiss();pendingExport=null;group.onExpandedChanged=null
        prefs.unregisterOnSharedPreferenceChangeListener(listener);status.unregisterOnSharedPreferenceChangeListener(listener)
        jamesPrefs.unregisterOnSharedPreferenceChangeListener(listener);context.unregisterLocalReceiver(receiver)
    }
}
