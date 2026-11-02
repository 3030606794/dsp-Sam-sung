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

class CrossfeedEditor(private val context:Context,private val group:SwitchPreferenceGroup,host:Fragment){
    private val p=CrossfeedParameters
    private val prefs=CrossfeedController.preferences(context)
    private val status=CrossfeedController.statusPreferences(context)
    private val store=CrossfeedPresetStore(context)
    private val advanced=CrossfeedAdvancedEditor(context,group,prefs)
    private val rows=linkedMapOf<String,BassNumericPreference>()
    private var pendingExport:String?=null
    private var closed=false
    private var library:CrossfeedPresetsDialog?=null
    private val importLauncher=host.registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null && !closed)attempt {
            val json=context.contentResolver.openInputStream(uri)?.use(CrossfeedPresetCodec::read) ?: error("无法读取预设文件")
            val count=store.importJson(json);library?.refresh();toast("已导入 $count 项耳机交叉馈送预设")
        }
    }
    private val exportLauncher=host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val json=pendingExport;pendingExport=null
        if(uri!=null && json!=null && !closed)attempt {
            context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(json.toByteArray(Charsets.UTF_8))} ?: error("无法写入预设文件")
            toast("耳机交叉馈送预设已导出")
        }
    }
    private fun info(key:String,title:String,order:Int)=object : RuntimeStatusPreference(context){
        override fun onBindViewHolder(holder:PreferenceViewHolder){super.onBindViewHolder(holder)
            for(id in listOf(android.R.id.title,android.R.id.summary))(holder.findViewById(id) as? TextView)?.apply {
                setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}
    }.apply {this.key=key;this.title=title;this.order=order;isIconSpaceReserved=false;isSelectable=false}
    private val professional=SwitchPreferenceCompat(context).apply {
        key=p.ENABLED;title="专业调节";summary="默认关闭。开启后使用独立精调参数；本模块电源仍独立控制。"
        order=10;isIconSpaceReserved=false;setDefaultValue(false)
    }
    private val filter=FieldChoicePreference(context,p.FILTER,"输出滤波方式",listOf("原版滤波","保留全频")).apply {
        order=80;setOnPreferenceChangeListener {_,next->prefs.edit().putInt(p.FILTER,next as Int).apply();true}
    }
    private val help=info("crossfeed_help","调节说明",85).apply {
        summary="交叉衰减越大，左右混入越少。截止频率提高后作用覆盖更多中频。附加延时只作用于对侧馈送路径，叠加在 BS2B 固有频变延迟上；0 μs 不代表整个滤波器零群延迟。效果混合 0% 保留原声，输出衰减仍有效。原版滤波含 10 Hz 高通和 18 kHz 低通，低采样率时低通目标受奈奎斯特频率限制。"
    }
    private val presets=Preference(context).apply {
        key="crossfeed_presets";title="耳机交叉馈送预设";summary="系统方案 · 个人 20 项 · 导入／导出";order=300;isIconSpaceReserved=false
        setOnPreferenceClickListener {
            stopPlayback()
            library?.dismiss()
            library=CrossfeedPresetsDialog(context,store,{p.capture(prefs.all)},{settings->
                stopPlayback();p.apply(prefs,settings)
            },{importLauncher.launch(arrayOf("application/json","text/plain","application/octet-stream"))},{id->
                pendingExport=store.export(id);exportLauncher.launch("耳机交叉馈送预设.json")
            }).also {it.show()};true
        }
    }
    private fun save(slot:Int)=Preference(context).apply {
        key=if(slot==0)"crossfeed_save_a"else"crossfeed_save_b";title=if(slot==0)"保存试听快照 A"else"保存试听快照 B"
        order=310+slot;isIconSpaceReserved=false
        setOnPreferenceClickListener {if(!CrossfeedController.saveComparison(context,this@CrossfeedEditor,slot))toast("请先开启专业调节");renderStatus();true}
    }
    private val saveA=save(0);private val saveB=save(1)
    private val measure=Preference(context).apply {
        key="crossfeed_measure";title="测量／重新测量 A/B 电平";order=325;isIconSpaceReserved=false
        summary="预热 100 ms 后测量 2 秒；同一输入同时处理 A/B。"
        setOnPreferenceClickListener {
            if(!CrossfeedController.measure(context,this@CrossfeedEditor))toast("请先保存 A/B 快照，并播放经过 DSP 的音乐")
            true
        }
    }
    private val audition=FieldChoicePreference(context,"crossfeed_audition","临时试听",
        listOf("正常处理","本模块原声","效果差分（−6 dB）","仅交叉馈送支路","快照 A","快照 B","测量 A/B 电平"),"恢复正常").apply {
        order=320;setOnPreferenceChangeListener {_,next->
            CrossfeedController.startAudition(context,this@CrossfeedEditor,next as Int).also {if(!it)toast("试听需要活动音频；A/B 和测量需先保存两个快照")}
        }
    }
    private val match=SwitchPreferenceCompat(context).apply {
        key="crossfeed_match";title="匹配 A/B 电平";summary="先测量同一输入的 A/B RMS，再固定衰减；不持续追踪、不提升电平。"
        order=330;isPersistent=false;isIconSpaceReserved=false;setDefaultValue(false)
        setOnPreferenceChangeListener {_,v->CrossfeedController.setMatch(context,v as Boolean).also {if(!it)toast("请先完成 A/B 电平测量")}}
    }
    private val stop=Preference(context).apply {
        key="crossfeed_stop";title="停止试听／恢复保存设置";order=340;isIconSpaceReserved=false
        setOnPreferenceClickListener {stopAudition();true}
    }
    private val actual=StableRuntimeReadoutPreference(context).apply {
        key="crossfeed_actual";title="试听状态";order=350
    }
    private val compatibility=info("crossfeed_compatibility","驱动支持",360)
    private val overlap=info("crossfeed_overlap","交叉馈送叠加提示",370).apply {
        summary="JamesDSP 的“交叉馈送”也已开启，两套算法将顺序处理。需要单独比较本模块时，可手动关闭另一套。"
    }
    private val jamesPrefs=me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache.getPreferences(context,
        me.timschneeberger.rootlessjamesdsp.utils.Constants.PREF_CROSSFEED)
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {source,key->
        if(closed)return@OnSharedPreferenceChangeListener
        if(source===prefs && (key in p.newKeys || key in CrossfeedAdvancedParameters.keys || key==p.LEGACY || key=="v_cure"))CrossfeedController.stopAudition(context,this)
        if(source===prefs && p.numbers.any {it.key==key}) {
            val row=rows[key];if(row!=null){val value=p.value(prefs.all,row.control);if(row.value!=value)row.value=value}
            renderFrame.request(false)
        }else if(source===prefs && CrossfeedAdvancedParameters.numbers.any {it.key==key}){
            advanced.numberChanged(key);renderFrame.request(false)
        }else renderFrame.request()
    }
    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,intent:Intent?){
            if (!runtimeStatusVisible(this@CrossfeedEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
            renderFrame.request(false)}}
    init {
        p.normalize(prefs);CrossfeedController.stopAudition(context,clearSnapshots=true)
        p.numbers.forEachIndexed {i,c->rows[c.key]=BassNumericPreference(context,c,p.value(prefs.all,c)).apply {order=20+i*10}}
        group.onExpandedChanged={if(!it)stopAudition()}
        prefs.registerOnSharedPreferenceChangeListener(listener);status.registerOnSharedPreferenceChangeListener(listener)
        jamesPrefs.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(CrossfeedController.STATUS_ACTION))
        render()
    }
    private fun add(row:Preference){if(group.findPreference<Preference>(row.key)==null)group.addPreference(row)}
    private fun render(){
        if(closed)return
        p.normalize(prefs);val enabled=p.enabled(prefs.all);professional.isChecked=enabled;add(professional)
        professional.summary=if(enabled)"正在使用专业参数，原三档暂时停用。关闭后恢复原三档；本模块电源仍独立控制。"
            else"默认关闭。开启后使用专业参数并停用原三档；关闭后恢复原三档。"
        group.findPreference<ViperSliderPreference>(p.LEGACY)?.apply {
            if(value!=p.legacy(prefs.all))value=p.legacy(prefs.all)
            disabledHint="专业调节已接管";isEnabled=!enabled
        }
        rows.values.forEach {row->row.isEnabled=enabled;if(enabled || group.isProfessionalMode)add(row)else group.removePreference(row)}
        filter.value=p.filter(prefs.all);filter.isEnabled=enabled
        if(enabled){add(filter);add(help)}else{if(!group.isProfessionalMode)group.removePreference(filter);if(!group.isProfessionalMode)group.removePreference(help)}
        if(group.isProfessionalMode){add(filter);add(help)}
        advanced.render(enabled)
        val separate=enabled && CrossfeedAdvancedParameters.on(prefs.all,CrossfeedAdvancedParameters.SEPARATE)
        rows[p.delay.key]?.apply {isEnabled=enabled && !separate}
        for(row in listOf(presets,saveA,saveB,audition,measure,match,stop,actual,compatibility))add(row)
        val key=context.getString(me.timschneeberger.rootlessjamesdsp.R.string.key_crossfeed_enable)
        if(prefs.getBoolean("v_cure",false) && jamesPrefs.getBoolean(key,false))add(overlap)else group.removePreference(overlap)
        renderStatus()
    }
    fun renderStatus(){
        if(closed)return
        advanced.update()
        val supported=CrossfeedController.supported(context);val enabled=p.enabled(prefs.all)
        saveA.isEnabled=enabled && supported;saveB.isEnabled=enabled && supported
        saveA.summary=if(CrossfeedController.hasComparison(0))"已保存，仅本次页面使用"else"未保存"
        saveB.summary=if(CrossfeedController.hasComparison(1))"已保存，仅本次页面使用"else"未保存"
        audition.value=CrossfeedController.audition;audition.isEnabled=CrossfeedController.canListen(context)
        measure.isEnabled=audition.isEnabled && CrossfeedController.hasComparison(0) && CrossfeedController.hasComparison(1)
        match.isChecked=CrossfeedController.match;match.isEnabled=CrossfeedController.matchingReady()
        compatibility.summary=if(supported)"已连接配套专业驱动。原三档保留；关闭专业调节恢复原版算法。"
            else"当前驱动不支持新增处理。专业设置可编辑并保留，实际仍使用原三档；更新配套驱动并重启后生效。"
        if(supported && !CrossfeedController.advancedSupported(context))compatibility.summary=compatibility.summary.toString()+" 新增的中央补偿、左右独立、支路滤波和诊断需要本轮配套驱动。"
        val readings=CrossfeedController.runtime.map {it as CrossfeedController.Reading?}.ifEmpty {listOf(null)}
        val moduleEnabled=prefs.getBoolean("v_cure",false)
        val matchingReady=CrossfeedController.matchingReady()
        actual.summary=readings.joinToString("\n\n"){r->
            val snapshot=r?.snapshot
            val signal=supported && moduleEnabled && enabled && r!=null && r.enabled && r.active &&
                snapshot!=null && snapshot.enabled && snapshot.professional && snapshot.mode==CrossfeedController.audition &&
                (CrossfeedController.audition==0 || snapshot.epoch==CrossfeedController.epoch)
            val v=if(signal)snapshot?.data else null
            val tools=if(snapshot?.epoch==CrossfeedController.epoch)v else null
            val statusText=when {
                !supported->"等待配套驱动\n—表示暂无有效读数。"
                !moduleEnabled->"本模块关闭；\n编辑参数不会自动启用"
                !enabled->"原版三档处理；\n专业试听未启用"
                r==null || !r.enabled || !r.active->"等待新的处理窗口；\n当前试听状态待确认"
                v==null->"等待读数\n—表示暂无有效读数。"
                else->"实际处理状态；\n—表示暂无有效读数。"
            }
            val measurement=tools?.get(6)
            val state=when(measurement){
                1->"电平测量中"
                2->if(matchingReady)"测量完成，固定衰减"else"等待当前快照的测量结果"
                3->"信号不足，未建立匹配"
                0->"未测量"
                else->"—"
            }
            val progress=StableRuntimeReadoutPreference.fixedNumber(tools?.get(7),1.0,0,width=6)
            fun attenuation(index:Int)=StableRuntimeReadoutPreference.fixedNumber(
                if(measurement==2 && matchingReady)tools?.get(index)else null,100.0,2)
            val rate=r?.rate?.toString()?.padStart(6) ?: "—".padStart(6)
            val auditionLabel=v?.get(5)?.let {listOf("正常处理","原声","差分","交叉支路","快照 A","快照 B","测量").getOrNull(it)} ?: "—"
            "$rate Hz\n$statusText\n当前试听：$auditionLabel\n测量状态：$state\n"+
                "电平测量 $progress / 2100 ms\n固定衰减 A ${attenuation(8)}／B ${attenuation(9)} dB\n"+
                if(measurement==3)"请播放音乐后重测"else"信号不足时请播放音乐后重测"
        }+"\n试听点位于本模块之后，后续效果仍会改变声音。快照、匹配和试听均不写入预设；收起、离页、暂停、参数编辑或会话失效时退出。"
    }
    private fun db(value:Int)=String.format(Locale.ROOT,"%.2f",value/100.0)
    private fun toast(message:String)=Toast.makeText(context,message,Toast.LENGTH_LONG).show()
    private fun attempt(action:()->Unit){try{action()}catch(e:Exception){toast(e.message ?: "操作失败")}}
    private fun stopPlayback(){CrossfeedController.stopAudition(context,this)}
    fun stopAudition(){CrossfeedController.stopAudition(context,this,clearSnapshots=true)}
    fun close() { renderFrame.close();
        stopAudition();closed=true;library?.dismiss();pendingExport=null;group.onExpandedChanged=null
        prefs.unregisterOnSharedPreferenceChangeListener(listener);status.unregisterOnSharedPreferenceChangeListener(listener)
        jamesPrefs.unregisterOnSharedPreferenceChangeListener(listener);context.unregisterLocalReceiver(receiver)
    }
}
