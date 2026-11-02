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
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionIrPreprocessor
import me.timschneeberger.rootlessjamesdsp.ui.IrHelpPreference
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.io.File
import java.util.Locale

/** The six second-round groups share the existing module switch and folding lifecycle. */
class ConvolutionAdvancedEditor(private val context: Context,private val group: SwitchPreferenceGroup,private val owner: Any) {
    private val p=ConvolutionAdvancedParameters
    private val prefs=ConvolutionController.preferences(context)
    private val status=ConvolutionController.statusPreferences(context)
    private val numeric=linkedMapOf<String,BassNumericPreference>()
    private val switches=linkedMapOf<String,SwitchPreferenceCompat>()
    private val choices=linkedMapOf<String,FieldChoicePreference>()
    private val audioRows=mutableListOf<Preference>()
    private val pathRows=Array(4){mutableListOf<Preference>()}
    private val info=StableRuntimeReadoutPreference(context).apply {
        key="conv2_runtime";title="第二轮实际处理状态";order=1050
    }
    private val comparisonInfo=help("conv2_comparison_info","A/B 参数快照",1150,"记录当前参数后可应用 A/B；选择会成为当前参数。快照只用于同一 IR，离页后清除；B 电平补偿为临时值，收起、滚出、离页或停用时归零。应用 A/B 会写回当前参数；选择正常编辑只结束临时补偿，不回滚进入前的参数。B 支持手动 ±12 dB 补偿，没有自动 A/B 电平测量。").apply {summary="A 未记录 · B 未记录\n应用 A/B 写回当前参数；正常编辑不回滚。B 为手动 ±12 dB 临时补偿，无自动测量。"}
    private var applying=false
    private var comparisonSelection=0
    private var comparisonTenths=0
    private var fingerprint=""
    private var channelFingerprint=""
    private var sourceChannels=0
    private data class Slot(val file: String,val values: Map<String,Any?>)
    private val slots=arrayOfNulls<Slot>(2)
    private val renderFrame=me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate {full->if(full)render()else renderStatus()}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key==ConvolutionParameters.FILE){slots.fill(null);comparisonSelection=0;ConvolutionController.stopComparison(context,owner)}
        else if(!applying && key !in setOf(p.GRAPH,p.SMOOTH) && key in (p.newKeys+ConvolutionParameters.newKeys+ConvolutionParameters.LEGACY_ADVANCED+ConvolutionParameters.LEGACY_MODE)) {
            comparisonSelection=0;ConvolutionController.stopComparison(context,owner)
        }
        renderFrame.request()
    }
    private val receiver=object: BroadcastReceiver(){override fun onReceive(context: Context?,intent: Intent?){
            if (!runtimeStatusVisible(this@ConvolutionAdvancedEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
            renderFrame.request(false)}}
    init {
        p.normalize(prefs)
        add(help("conv2_filter_help","湿声频段与音色整形",600,"高通、低通和搁架只处理卷积湿声，默认关闭。斜率为 Butterworth 12／24／48 dB/oct；搁架为 S=1。实际频率限制到处理采样率的45%，避免接近奈奎斯特频率。"))
        toggle(p.enabledFilter(0),"启用湿声高通",610);number(p.hp,611);choice(p.slope(0),"高通斜率",listOf("12 dB/oct","24 dB/oct","48 dB/oct"),612)
        toggle(p.enabledFilter(1),"启用湿声低通",620);number(p.lp,621);choice(p.slope(1),"低通斜率",listOf("12 dB/oct","24 dB/oct","48 dB/oct"),622)
        toggle(p.enabledFilter(2),"启用湿声低频搁架",630);number(p.lowHz,631);number(p.lowGain,632)
        toggle(p.enabledFilter(3),"启用湿声高频搁架",640);number(p.highHz,641);number(p.highGain,642)
        add(help("conv2_matrix_help","输入路由与四路径矩阵",700,"保留原路由为默认。单声道转立体声先按 (L+R)/2 合并输入，不能替代真四路径 IR。单／双核只调整两条直通路径；四核才有两条交叉路径。联动只影响后续增益编辑；单项复位只恢复当前项。"))
        choice(p.ROUTE,"卷积输入路由",listOf("保留原路由","单声道转立体声（输入取平均）"),710)
        toggle(p.MATRIX,"启用路径增益、静音与极性",720)
        toggle(p.DIRECT_LINK,"直通路径增益联动",721);toggle(p.CROSS_LINK,"交叉路径增益联动",722)
        for(i in 0..3) {
            number(p.gain[i],730+i*10).also{pathRows[i]+=it}
            toggle(p.mute(i),"${p.paths[i]} 静音",731+i*10).also{pathRows[i]+=it}
            toggle(p.invert(i),"${p.paths[i]} 极性反转",732+i*10).also{pathRows[i]+=it}
        }
        add(help("conv2_fractional_help","分数采样时间校准",800,"在原版核移位之后追加延时；0.001 ms 是控制刻度。三阶插值增加1个公共采样，高精度33抽头 FIR 增加16个；干声旁路与混合同步补偿公共延迟。保留各路径原来的时间差，不自动对齐主峰。"))
        toggle(p.FRACTIONAL,"启用分数采样校准",810)
        choice(p.METHOD,"分数采样插值",listOf("标准：三阶 Lagrange","高精度：33 抽头窗化 sinc FIR"),820)
        p.delay.forEachIndexed {i,c->number(c,830+i*10).also{pathRows[i]+=it}}
        add(help("conv2_shape_help","IR 前段与尾段能量塑形",900,"分界相对第一轮时间窗后的工作 IR 起点，过渡采用半余弦。附加衰减只收敛已有尾段，不延长原 IR。校正 IR 使用此功能会改变校正曲线，默认关闭。整组峰值归一化以原采样率完成矩阵、塑形、精细延时后的峰值为参考；不同工作采样率共用增益。"))
        toggle(p.SHAPE,"启用前段／尾段塑形",910)
        p.shapeControls.forEachIndexed {i,c->number(c,920+i*10)}
        add(help("conv2_transition_help","换核过渡与旧尾保留",1000,"默认沿用原切换。交叉淡化采用线性权重；保尾模式先交叉淡化输入，再让旧核以零输入衰减，最后柔和退出。最多两套计划；连续编辑合并为最新待处理参数。内存估算过大时回退轻量过渡，并显示实际方式。"))
        choice(p.SWITCH,"工作核切换方式",listOf("沿用第一轮切换","线性交叉淡化","保留旧尾自然衰减"),1010)
        p.transitionControls.forEachIndexed {i,c->number(c,1020+i*10)}
        add(help("conv2_analysis_help","深度分析与参数 A/B",1100,"分析在后台生成，不会主动开启卷积。灰线为原文件重采样后的幅度，彩线为工作核；时间参考为工作核起点，深凹陷处的相位与群延迟留空。显示平滑不修改播放数据。"))
        toggle(p.ANALYSIS,"启用深度 IR 分析",1110,false)
        choice(p.GRAPH,"深度分析显示类型",listOf("幅度","相位","群延迟","能量"),1120,false)
        choice(p.SMOOTH,"幅度显示平滑",listOf("关闭","1/12 倍频程","1/6 倍频程"),1130,false)
        add(help("conv2_response_description","深度 IR 分析说明",1140,
            "原 IR 以实际处理采样率重采样，彩色对应工作核 1–4。相位与群延迟以工作核起点为参考，深凹陷或静音处留空。能量衰减是工作核计算值，不是房间实测混响时间。图不含湿声滤波、干湿、电平校准及后续效果。相位为 °，群延迟为 ms，能量为 dB。倍频程平滑仅改变幅度显示，不改变播放参数。等待工作核深度分析完成；图纸由顶部焦点联动显示。").apply {
                summary="原 IR 按实际处理率重采样，彩线对应工作核1–4；相位/群延迟以工作核起点为参考，深凹陷或静音处留空。能量为工作核计算值，不是房间实测RT；不含湿声滤波、干湿、电平校准及后续效果。相位°、群延迟ms、能量dB；倍频程平滑只改变幅度显示。等待工作核深度分析完成，图纸由顶部焦点联动显示。"
            })
        add(info) // Original status stays in the scroll area; the dashboard owns all charts.
        add(comparisonInfo)
        repeat(2){i->add(Preference(context).apply {
            key="conv2_capture_$i";title="记录当前参数到 ${if(i==0)"A" else "B"}";order=1160+i;isIconSpaceReserved=false
            setOnPreferenceClickListener {capture(i);true}
        })}
        val comparisonChoice=FieldChoicePreference(context,"conv2_comparison","应用参数快照",listOf("正常编辑","应用 A","应用 B"),"结束临时补偿").apply {
            order=1170;setOnPreferenceChangeListener {_,next->applySlot(next as Int)}
        };choices["conv2_comparison"]=comparisonChoice;add(comparisonChoice);audioRows+=comparisonChoice
        val trim=BassNumericPreference(context,p.comparison).apply {
            order=1180;isPersistent=false
            setOnPreferenceChangeListener {_,next->comparisonTenths=next as Int;ConvolutionController.setComparison(context,owner,if(comparisonSelection==2)comparisonTenths else 0);true}
        };numeric[p.comparison.key]=trim;audioRows+=trim;add(trim)
        prefs.registerOnSharedPreferenceChangeListener(listener);status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver,IntentFilter(ConvolutionController.STATUS_ACTION));fingerprint=fileFingerprint();render()
    }
    private fun help(key: String,title: String,order: Int,text: String)=IrHelpPreference(context,key,title,order,text,
        when(key){
            "conv2_filter_help"->"高通、低通支持 12／24／48 dB/oct；实际频率上限为处理采样率的 45%。搁架为 S=1。"
            "conv2_matrix_help"->"单／双核仅有两条直通路径；四核才有交叉路径。联动只影响后续增益编辑，单项复位仅恢复当前项。"
            "conv2_fractional_help"->"控制刻度为 0.001 ms。三阶插值增加 1 个公共采样；高精度 33 抽头 FIR 增加 16 个公共采样，干声同步补偿。"
            "conv2_shape_help"->"分界相对工作 IR 起点；只衰减已有尾段，不延长 IR。默认关闭。"
            "conv2_transition_help"->"最多保留两套卷积计划；连续编辑合并为最新待处理参数。实际过渡及内存回退在顶部状态中显示。"
            "conv2_analysis_help"->"调节参数自动联动顶部图纸；深度分析显示类型可选幅度、相位、群延迟、能量。倍频程平滑仅用于幅度显示，分析不会主动开启卷积。"
            "conv2_comparison_info"->"快照仅用于当前 IR，离页后清除；B 临时电平补偿不写入预设。"
            else->""
        })
    private fun add(row: Preference){if(group.findPreference<Preference>(row.key)==null)group.addPreference(row)}
    private fun number(c: BassParameters.Control,order: Int): BassNumericPreference {
        val row=BassNumericPreference(context,c,p.value(prefs.all,c)).apply{this.order=order}
        val step=if(c.divisor==1000 && c.decimals==1)100 else 1
        if(step>1){row.sliderStep=step;row.sliderRange=c.min..c.max;row.valueTransform={((it.toLong()+step/2)/step*step).coerceIn(c.min.toLong(),c.max.toLong()).toInt()};row.stepAction={delta->row.requestValue((row.value.toLong()+delta*step).coerceIn(c.min.toLong(),c.max.toLong()).toInt())};row.stepLabel={"0.1 ms"}}
        row.setOnPreferenceChangeListener {_,next->
            val value=next as Int
            if((c==p.hp || c==p.lp) && p.on(prefs.all,p.enabledFilter(0)) && p.on(prefs.all,p.enabledFilter(1)) &&
                (if(c==p.hp)value else p.value(prefs.all,p.hp)) >= (if(c==p.lp)value else p.value(prefs.all,p.lp)))return@setOnPreferenceChangeListener false
            val edit=prefs.edit().putInt(c.key,value)
            val index=p.gain.indexOf(c)
            if(index>=0 && p.on(prefs.all,if(index==0 || index==3)p.DIRECT_LINK else p.CROSS_LINK))edit.putInt(p.gain[3-index].key,value)
            edit.apply();true
        }
        if(c==p.hp || c==p.lp)row.rejectedValueMessage="湿声高通频率必须低于低通频率"
        row.resetAction={if(!row.isEnabled)false else {prefs.edit().putInt(c.key,c.default).apply();true}}
        numeric[c.key]=row;audioRows+=row;add(row);return row
    }
    private fun toggle(key: String,title: String,order: Int,audio: Boolean=true): SwitchPreferenceCompat {
        val row=object:SwitchPreferenceCompat(context){
            override fun onBindViewHolder(holder: PreferenceViewHolder){super.onBindViewHolder(holder);(holder.findViewById(android.R.id.title) as? TextView)?.apply{setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}
        }.apply{this.key=key;this.title=title;this.order=order;isIconSpaceReserved=false;setDefaultValue(false)}
        row.setOnPreferenceChangeListener{_,next->
            if(next==true && ((key==p.enabledFilter(0) && p.on(prefs.all,p.enabledFilter(1))) || (key==p.enabledFilter(1) && p.on(prefs.all,p.enabledFilter(0)))) && p.value(prefs.all,p.hp)>=p.value(prefs.all,p.lp)) {
                Toast.makeText(context,"湿声高通频率必须低于低通频率",Toast.LENGTH_SHORT).show();false
            } else true
        }
        switches[key]=row;if(audio)audioRows+=row;add(row);return row
    }
    private fun choice(key: String,title: String,labels: List<String>,order: Int,audio: Boolean=true) {
        val row=FieldChoicePreference(context,key,title,labels).apply{this.order=order;setOnPreferenceChangeListener{_,next->prefs.edit().putInt(key,next as Int).apply();true}}
        choices[key]=row;if(audio)audioRows+=row;add(row)
    }
    private fun fileFingerprint(): String {
        val file=File(FileLibraryPreference.createFullPathCompat(context,prefs.getString(ConvolutionParameters.FILE,"").orEmpty()))
        return "${file.absolutePath}|${file.length()}|${file.lastModified()}"
    }
    private fun compactLevel(value: Int)=if(value==Int.MIN_VALUE)"—"else String.format(Locale.ROOT,"%.1f",value/10.0)
    private fun capture(index: Int) {
        slots[index]=Slot(fileFingerprint(),prefs.all.filterKeys{it in ConvolutionParameters.newKeys || it==ConvolutionParameters.LEGACY_MODE || it==ConvolutionParameters.LEGACY_ADVANCED})
        comparisonInfo.summary="A ${if(slots[0]==null)"未记录"else"已记录"} · B ${if(slots[1]==null)"未记录"else"已记录"}\n应用 A/B 写回当前参数；正常编辑不回滚。B 为手动 ±12 dB 临时补偿，无自动测量。"
    }
    private fun applySlot(selection: Int): Boolean {
        if(selection==0){comparisonSelection=0;ConvolutionController.stopComparison(context,owner);return true}
        if(!ConvolutionController.masterEnabled || !prefs.getBoolean(ConvolutionParameters.ENABLED,false))return false
        val slot=slots[selection-1]
        if(slot==null || slot.file!=fileFingerprint()){Toast.makeText(context,"请先为当前 IR 记录对应参数快照",Toast.LENGTH_SHORT).show();return false}
        applying=true
        try {
            ConvolutionController.stopAudition(context,owner,keepComparison=true)
            val edit=prefs.edit();(ConvolutionParameters.newKeys+ConvolutionParameters.LEGACY_MODE+ConvolutionParameters.LEGACY_ADVANCED).forEach(edit::remove)
            slot.values.forEach{(key,value)->when(value){is Int->edit.putInt(key,value);is Boolean->edit.putBoolean(key,value);is Float->edit.putFloat(key,value);is String->edit.putString(key,value)}}
            comparisonSelection=selection;edit.apply()
            ConvolutionController.setComparison(context,owner,if(selection==2)comparisonTenths else 0)
        } finally {applying=false}
        render();return true
    }
    private fun render() {
        p.normalize(prefs);val values=prefs.all
        numeric.forEach{(key,row)->if(key!=p.comparison.key){val v=p.value(values,row.control);if(row.value!=v)row.value=v}}
        switches.forEach{(key,row)->val v=p.on(values,key);if(row.isChecked!=v)row.isChecked=v}
        choices.forEach{(key,row)->row.value=if(key=="conv2_comparison")comparisonSelection else p.choice(values,key)}
        renderStatus()
    }
    fun renderStatus() {
        if((comparisonSelection!=0 || comparisonTenths!=0) && (!ConvolutionController.masterEnabled || !prefs.getBoolean(ConvolutionParameters.ENABLED,false)))stopTemporary()
        choices["conv2_comparison"]?.isEnabled=ConvolutionController.advancedSupported(context) && ConvolutionController.masterEnabled && prefs.getBoolean(ConvolutionParameters.ENABLED,false)
        numeric[p.comparison.key]?.isEnabled=ConvolutionController.advancedSupported(context) && ConvolutionController.masterEnabled && prefs.getBoolean(ConvolutionParameters.ENABLED,false) && comparisonSelection==2
        val next=fileFingerprint()
        if(fingerprint!=next){fingerprint=next;slots.fill(null);comparisonSelection=0;ConvolutionController.stopComparison(context,owner)}
        comparisonInfo.summary="A ${if(slots[0]==null)"未记录"else"已记录"} · B ${if(slots[1]==null)"未记录"else"已记录"}\n应用 A/B 写回当前参数；正常编辑不回滚。B 为手动 ±12 dB 临时补偿，无自动测量。"
        val prepared=ConvolutionController.prepared
        if(channelFingerprint!=next) {
            channelFingerprint=next
            sourceChannels=runCatching {ConvolutionIrPreprocessor.metadata(File(FileLibraryPreference.createFullPathCompat(context,prefs.getString(ConvolutionParameters.FILE,"").orEmpty()))).channels}.getOrDefault(0)
        }
        renderAvailability()
        val supported=ConvolutionController.advancedSupported(context)
        val readings=ConvolutionController.runtime.mapNotNull{r->r.advanced?.takeIf{a->r.snapshot?.let{it.rate==a.rate && it.crc==a.crc}==true}}
            .map{it as ConvolutionAdvancedRuntime.Snapshot?}.ifEmpty{listOf(null)}
        info.summary=readings.joinToString("\n\n") {a->
            fun n(value:Int?,divisor:Double=1.0,decimals:Int=0)=StableRuntimeReadoutPreference.fixedNumber(value,divisor,decimals,width=8)
            buildList {
                add(if(!supported)"第二轮音频功能需要新版驱动，已保存的设置保留。" else if(a==null)"等待实际驱动状态。" else "实际第二轮配置与资源状态")
                add("资源估算与工作核分析不是设备总延迟测量。")
                add("实际处理率 ${n(a?.rate)} Hz")
                add("卷积计划 ${n(a?.plans)} 套")
                add("FFT／核内存粗估 ${n(a?.memoryKiB,1024.0,2)} MiB")
                add("请求过渡 ${n(a?.requestedMode)}")
                add("实际过渡：${a?.actualMode?.let{listOf("无过渡","线性交叉淡化","旧尾自然衰减","轻量平滑回退")[it]} ?: "—"}")
                add("过渡剩余 ${if(a!=null)String.format(Locale.ROOT,"%8.1f",a.remaining*1000.0/a.rate)else "—".padStart(8)} ms")
                add("旧核长度 ${n(a?.oldFrames)} 帧")
                add("回退原因：${a?.fallback?.let{listOf("无","双核粗估超过128 MiB","无活动音频，已释放旧尾")[it]} ?: "—"}")
                add("新增公共延迟 ${n(a?.commonDelay)} 采样")
                add("公共延迟 ${if(a!=null)String.format(Locale.ROOT,"%8.6f",a.commonDelay*1000.0/a.rate)else "—".padStart(8)} ms")
                add("公共延迟已同步补偿干声。")
                add("实际湿声高通 ${n(a?.cutoffs?.get(0))} Hz")
                add("实际湿声低通 ${n(a?.cutoffs?.get(1))} Hz")
                add("实际湿声低架 ${n(a?.cutoffs?.get(2))} Hz")
                add("实际湿声高架 ${n(a?.cutoffs?.get(3))} Hz")
                add("截止频率 0 表示该路关闭。")
                add("滤波交接 ${n(a?.filterTransition)}")
                add("B 临时补偿 ${n(a?.comparison,10.0,1)} dB")
                add("实际核 CRC ${a?.crc?.toUInt()?.toString(16)?.padStart(8,'0') ?: "—".padStart(8)}")
                add("实际核长度 ${n(a?.frames)} 帧")
                add("原文件、工作核、装载确认和三路电平见原生状态行与顶部联动图。")
            }.joinToString("\n")
        }
    }
    /** Dependent controls keep their slots; inactive or linked alternatives are greyed out. */
    private fun renderAvailability() {
        val values=prefs.all
        val supported=ConvolutionController.advancedSupported(context)
        for(i in 0..3)switches[p.enabledFilter(i)]?.isEnabled=supported
        listOf(p.MATRIX,p.FRACTIONAL,p.SHAPE).forEach {switches[it]?.isEnabled=supported}
        listOf(p.ROUTE,p.SWITCH).forEach {choices[it]?.isEnabled=supported}
        for(i in 0..1) {
            val enabled=supported && p.on(values,p.enabledFilter(i))
            numeric[if(i==0)p.hp.key else p.lp.key]?.isEnabled=enabled
            choices[p.slope(i)]?.isEnabled=enabled
        }
        listOf(p.lowHz,p.lowGain).forEach {numeric[it.key]?.isEnabled=supported && p.on(values,p.enabledFilter(2))}
        listOf(p.highHz,p.highGain).forEach {numeric[it.key]?.isEnabled=supported && p.on(values,p.enabledFilter(3))}
        val matrix=supported && p.on(values,p.MATRIX)
        val crossPaths=sourceChannels==4
        switches[p.DIRECT_LINK]?.isEnabled=matrix
        switches[p.CROSS_LINK]?.isEnabled=matrix && crossPaths
        for(i in 0..3) {
            val available=i==0 || i==3 || crossPaths
            val linked=(i==3 && p.on(values,p.DIRECT_LINK)) || (i==2 && p.on(values,p.CROSS_LINK))
            numeric[p.gain[i].key]?.isEnabled=matrix && available && !linked
            switches[p.mute(i)]?.isEnabled=matrix && available
            switches[p.invert(i)]?.isEnabled=matrix && available
            numeric[p.delay[i].key]?.isEnabled=supported && available && p.on(values,p.FRACTIONAL)
        }
        choices[p.METHOD]?.isEnabled=supported && p.on(values,p.FRACTIONAL)
        p.shapeControls.forEach {numeric[it.key]?.isEnabled=supported && p.on(values,p.SHAPE)}
        p.transitionControls.forEach {numeric[it.key]?.isEnabled=supported && p.choice(values,p.SWITCH)!=0}
        choices[p.SMOOTH]?.isEnabled=p.on(values,p.ANALYSIS)
    }
    fun stopTemporary(){comparisonSelection=0;comparisonTenths=0;numeric[p.comparison.key]?.value=0;choices["conv2_comparison"]?.value=0;ConvolutionController.stopComparison(context,owner)}
    fun close(){ConvolutionController.stopComparison(context,owner);renderFrame.close();slots.fill(null);prefs.unregisterOnSharedPreferenceChangeListener(listener);status.unregisterOnSharedPreferenceChangeListener(listener);context.unregisterLocalReceiver(receiver)}
}
