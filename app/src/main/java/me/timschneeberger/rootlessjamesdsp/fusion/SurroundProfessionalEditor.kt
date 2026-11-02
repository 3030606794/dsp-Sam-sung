package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.slider.LabelFormatter
import androidx.core.view.ViewCompat
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import me.timschneeberger.rootlessjamesdsp.ui.ParameterSwitchUi
import me.timschneeberger.rootlessjamesdsp.ui.SliderAppearance
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.*

class SurroundProfessionalEditor(private val context:Activity,parent:LinearLayout) {
    private val p=SurroundProfessionalParameters
    private val numbers=mutableMapOf<String,Pair<Slider,MaterialButton>>()
    private val trackingNumbers=mutableSetOf<String>()
    private val switches=mutableMapOf<String,SpringMaterialSwitch>()
    private val numberLabels=mutableMapOf<String,TextView>()
    private val switchLabels=mutableMapOf<String,TextView>()
    private val choices=mutableMapOf<String,MaterialButton>()
    private val dialogs=mutableListOf<AlertDialog>()
    private var rendering=false
    private val support=label(13)
    private val geometry=label(13)
    private val actual=label(13)
    private lateinit var applyGeometry:MaterialButton
    private lateinit var saveA:MaterialButton
    private lateinit var saveB:MaterialButton
    private lateinit var audition:MaterialButton
    private lateinit var calibrationButton:MaterialButton
    private lateinit var remeasure:MaterialButton
    private val matchStatus=label(13)
    private val calibrationStatus=label(13)
    private val diagnosticStatus=label(13)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var diagnosticJob:Job?=null
    private data class DiagnosticKey(val settings:SurroundController.Settings,val fs:Double,val view:Int,val window:Int)
    private var diagnosticKey:DiagnosticKey?=null
    val groups=mutableListOf<LinearLayout>()
    private fun dp(v:Int)=(v*context.resources.displayMetrics.density).roundToInt()
    private fun label(size:Int)=TextView(context).apply {textSize=size.toFloat();setPadding(0,dp(6),0,dp(6))}
    private fun button()=MaterialButton(context,null,com.google.android.material.R.attr.borderlessButtonStyle).apply {minWidth=0;minimumWidth=0;setPadding(dp(3),0,dp(3),0);isAllCaps=false;maxLines=3}
    private fun settings()=SurroundController.settings(context)
    private fun change(key:String,value:Int){runCatching {SurroundController.save(context,p.with(settings(),key,value))}.onFailure {android.widget.Toast.makeText(context,it.message,android.widget.Toast.LENGTH_SHORT).show();render()}}
    private fun reset(key:String){val e=p.byKey.getValue(key);change(key,p.default(SurroundController.defaults[SurroundController.selected(context)],e))}
    private fun addTitle(group:LinearLayout,text:String,technical:String="",range:String="",size:Int=15):TextView{
        val row=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        val name=label(size).apply {id=View.generateViewId();this.text=text;if(size==17)setTypeface(typeface,Typeface.BOLD)}
        row.addView(name,LinearLayout.LayoutParams(0,-2,1f));group.addView(row)
        ParameterHelp.attachTitle(name,text,technical,range)
        return name
    }
    private fun section(parent:LinearLayout,index:Int,title:String,info:String):LinearLayout {
        val group=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(8));tag="surround_pro_group_$index"}
        addTitle(group,title,info,size=17)
        groups+=group;parent.addView(group);return group
    }
    private fun number(group:LinearLayout,key:String) {
        val e=p.byKey.getValue(key);val c=e.control
        numberLabels[key]=addTitle(group,c.title,range="范围 ${c.label(c.min)} 至 ${c.label(c.max)}；± 每次 ${c.label(e.step)}。点击右侧数值可输入，双击滑块圆点恢复当前槽的原默认值。").apply { tag="surround_focus_$key" }
        val row=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        fun small(text:String,tagName:String,action:()->Unit){row.addView(button().apply {this.text=text;tag=tagName;textSize=18f;minHeight=0;minimumHeight=0;insetTop=0;insetBottom=0;maxLines=1;setOnClickListener {action()}},LinearLayout.LayoutParams(dp(30),dp(30)))}
        small("−","surround_pro_minus_$key"){change(key,(p.value(settings(),key)-e.step).coerceAtLeast(c.min))}
        val slider=Slider(context).apply {
            tag="surround_pro_slider_$key";valueFrom=c.min.toFloat();valueTo=c.max.toFloat();stepSize=e.step.toFloat();this.value=valueFrom;isTickVisible=false;contentDescription=c.title
            SliderAppearance.apply(this)
            setGlassLabelFormatter { c.label(it.roundToInt()) }
            addOnChangeListener {_,v,user->if(user && !rendering)change(key,v.roundToInt())}
            addOnSliderTouchListener(object:Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider:Slider){trackingNumbers+=key}
                override fun onStopTrackingTouch(slider:Slider){trackingNumbers-=key;render()}
            })
        }
        row.addView(slider,LinearLayout.LayoutParams(0,dp(30),1f))
        small("+","surround_pro_plus_$key"){change(key,(p.value(settings(),key)+e.step).coerceAtMost(c.max))}
        val value=MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            tag="surround_pro_value_$key";textSize=13f;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
            insetTop=0;insetBottom=0;cornerRadius=dp(4);maxLines=1;isAllCaps=false;setPadding(dp(4),0,dp(4),0)
            setOnClickListener {edit(key)}
        }
        row.addView(value,LinearLayout.LayoutParams(dp(104),dp(30)))
        group.addView(row);numbers[key]=slider to value
    }
    private fun edit(key:String){
        val c=p.byKey.getValue(key).control
        val input=EditText(context).apply {id=android.R.id.edit;inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED;setText(c.number(p.value(settings(),key)));selectAll()}
        val dialog=MaterialAlertDialogBuilder(context).setTitle(c.title).setView(input).setPositiveButton("确定",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {val v=c.parse(input.text.toString());if(v==null)input.error="请输入 ${c.label(c.min)} 至 ${c.label(c.max)}，最小精度 ${c.label(1)}" else {change(key,v);dialog.dismiss()}}}
        dialogs+=dialog;dialog.setOnDismissListener {dialogs.remove(dialog)};dialog.show()
    }
    private fun toggle(group:LinearLayout,key:String){
        val e=p.byKey.getValue(key)
        val container=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false}
        // Help markers may resize the label; keep their complete title row in the weighted slot.
        val titleRow=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false}
        val name=label(14).apply {id=View.generateViewId();text=e.control.title;tag="surround_focus_$key"}
        titleRow.addView(name,LinearLayout.LayoutParams(-2,-2))
        container.addView(titleRow,LinearLayout.LayoutParams(0,-2,1f))
        ParameterHelp.attachTitle(name,e.control.title)
        val row=SpringMaterialSwitch(context).apply {
            background=null;minHeight=0;minimumHeight=0;setPadding(0,0,0,0)
            contentDescription=e.control.title;tag="surround_pro_toggle_$key"
            setOnCheckedChangeListener {_,v->if(!rendering)change(key,if(v)1 else 0)}
        }
        container.addView(row,LinearLayout.LayoutParams(-2,dp(30)))
        ParameterSwitchUi.configure(row,name)
        switchLabels[key]=name
        switches[key]=row;group.addView(container,LinearLayout.LayoutParams(-1,-2))
    }
    private val choiceLabels=mapOf("hp_slope" to listOf("6 dB/oct","12 dB/oct","24 dB/oct"),"lp_slope" to listOf("6 dB/oct","12 dB/oct","24 dB/oct"),"response_view" to listOf("处理频段","完整直达／交叉响应","同相／反相信号响应"),"cal_mode" to listOf("左声道正弦","右声道正弦","居中单声道正弦","居中带限噪声"),"diagnostics_view" to listOf("展开相位","群延迟","脉冲响应"))
    private fun choice(group:LinearLayout,key:String){
        addTitle(group,p.byKey.getValue(key).control.title,range="可选 ${choiceLabels.getValue(key).joinToString("、")}；长按选项恢复当前槽默认值。").apply { tag="surround_focus_$key" }
        val row=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        val value=button().apply {tag="surround_pro_choice_$key";setOnClickListener {
            val items=choiceLabels.getValue(key);val d=MaterialAlertDialogBuilder(context).setTitle(p.byKey.getValue(key).control.title).setSingleChoiceItems(items.toTypedArray(),p.value(settings(),key)){dialog,index->change(key,index);dialog.dismiss()}.setNegativeButton("取消",null).create();dialogs+=d;d.setOnDismissListener {dialogs.remove(d)};d.show()
        }}
        value.setOnLongClickListener {reset(key);true}
        ViewCompat.addAccessibilityAction(value,"恢复默认") {_,_->reset(key);true}
        row.addView(value,LinearLayout.LayoutParams(-1,-2));group.addView(row);choices[key]=value
    }
    init {
        section(parent,1,"① 专业混合与电平校准","原四项参数继续保留。专业抵消关闭时沿用原算法；数值编辑不自动开启本模块。").also {g->g.addView(support);toggle(g,"enabled");listOf("mix","pre_db","output_db").forEach {number(g,it)}}
        section(parent,2,"② 双向抵消精调","衰减越大，抵消支路越弱。联动时左→右为共同值；解除后右→左独立。专业关闭时仍使用上方原衰减与延迟。").also {g->toggle(g,"link");listOf("lr_attenuation","rl_attenuation","lr_delay","rl_delay").forEach {number(g,it)}}
        section(parent,3,"③ 抵消频段与滤波斜率","低、高频边界沿用上方数值；两端可分别选择Butterworth斜率。默认12 dB/oct。专业抵消开启后生效。").also {g->choice(g,"hp_slope");choice(g,"lp_slope")}
        section(parent,4,"④ 扬声器摆位辅助","按对称摆位、343 m/s声速估算路径差；未测量头部或房间。仅提供校准起点，应用后也不会自动开启专业抵消。").also {g->listOf("speaker_spacing","listening_distance","ear_spacing").forEach {number(g,it)};g.addView(geometry);applyGeometry=button().apply {text="应用估算延迟到双向支路";tag="surround_pro_apply_geometry";setOnClickListener {p.applyGeometry(settings())?.let {SurroundController.save(context,it)}}};g.addView(applyGeometry)}
        section(parent,5,"⑤ 自动电平与峰值管理","两项独立启用，默认关闭。RMS比较输入预衰减后的原声与处理声；输出校准随后执行。保护针对本模块采样峰值，后续音效仍可能改变电平。").also {g->toggle(g,"rms_on");listOf("rms_limit","rms_attack","rms_release").forEach {number(g,it)};toggle(g,"rms_attenuation_only");toggle(g,"peak_on");number(g,"peak_ceiling");number(g,"peak_release")}
        section(parent,6,"⑥ 完整响应、监测与临时试听","完整曲线表示本模块静态线性响应；动态电平另看读数，曲线不是房间实测。监测位于临时试听之前。A/B仅保留在当前页面会话，离页或暂停退出试听。").also {g->
            choice(g,"response_view");toggle(g,"meters");g.addView(actual)
            saveA=button().apply {tag="surround_pro_save_a";setOnClickListener {SurroundProfessionalRuntime.capture(context,3);render()}}
            saveB=button().apply {tag="surround_pro_save_b";setOnClickListener {SurroundProfessionalRuntime.capture(context,4);render()}}
            g.addView(saveA);g.addView(saveB)
            audition=button().apply {tag="surround_pro_audition";setOnClickListener {
                val labels=SurroundProfessionalRuntime.auditionLabels;val d=MaterialAlertDialogBuilder(context).setTitle("临时试听").setSingleChoiceItems(labels.toTypedArray(),SurroundProfessionalRuntime.audition){dialog,index->
                    if(index==0){SurroundProfessionalRuntime.stop(context);dialog.dismiss()}else if(SurroundProfessionalRuntime.start(context,this@SurroundProfessionalEditor,index)){dialog.dismiss()}else android.widget.Toast.makeText(context,"需要正在处理的会话；参数 A/B 请先保存快照",android.widget.Toast.LENGTH_SHORT).show()
                }.setNegativeButton("取消",null).create();dialogs+=d;d.setOnDismissListener {dialogs.remove(d)};d.show()
            }};g.addView(audition)
        }
        section(parent,7,"⑦ 等响度 A/B 试听","默认关闭。先保存 A/B，建议用同一段音乐分别测量两组。按各组输出相对原声的 RMS 电平匹配，只衰减较响组；测量完成后补偿固定，不随音乐继续追踪。不是 LUFS 测量，也不是最终设备响度。").also {g->
            toggle(g,"match_on");number(g,"match_limit");number(g,"match_window");g.addView(matchStatus)
            remeasure=button().apply {text="清除 A/B 电平测量";tag="surround_match_remeasure";setOnClickListener {SurroundProfessionalRuntime.clearMeasurements(context);render()}};g.addView(remeasure)
        }
        section(parent,8,"⑧ 校准试听工具","默认关闭，允许后仍须手动开始。需保持音频正在播放；测试信号在本模块前替换音乐，后续音效仍参与。定时结束恢复音乐；暂停页面、切槽、编辑音频参数或关闭模块时停止。").also {g->
            toggle(g,"cal_on");choice(g,"cal_mode");listOf("cal_level","cal_frequency","cal_low","cal_high","cal_duration","cal_fade").forEach {number(g,it)}
            g.addView(calibrationStatus)
            calibrationButton=button().apply {tag="surround_calibration_start_stop";setOnClickListener {if(SurroundProfessionalRuntime.calibration!=0)SurroundProfessionalRuntime.stop(context) else if(!SurroundProfessionalRuntime.startCalibration(context,this@SurroundProfessionalEditor))android.widget.Toast.makeText(context,"请连接新版驱动、允许校准试听，并开启模块和音乐播放",android.widget.Toast.LENGTH_LONG).show();render()}};g.addView(calibrationButton)
        }
        section(parent,9,"⑨ 相位与时间响应诊断","默认关闭。预测当前参数的静态线性响应，包含双向路径、分数延迟和混合；不含动态 RMS、限幅、试听匹配及后续音效。曲线不是房间实测，不能换算实际串音抵消量。").also {g->
            toggle(g,"diagnostics_on");choice(g,"diagnostics_view");number(g,"impulse_window");g.addView(diagnosticStatus)
        }
        render()
    }
    fun render(){
        rendering=true;val s=settings();val supported=SurroundController.professionalSupported(context)
        numbers.forEach {(key,pair)->val e=p.byKey.getValue(key);val c=e.control;val v=p.value(s,key);val coarse=(c.min+((v-c.min).toDouble()/e.step).roundToInt()*e.step).coerceIn(c.min,c.max).toFloat();if(key !in trackingNumbers && pair.first.value!=coarse)pair.first.value=coarse;val text=c.label(v);if(pair.second.text.toString()!=text)pair.second.text=text;val enabled=!(key.startsWith("rl_") && p.on(s,"link"));pair.first.isEnabled=enabled;pair.second.isEnabled=enabled
            // Keep linked secondary values intact; their adjacent action buttons are disabled too.
            val row=pair.second.parent as LinearLayout;for(i in 0 until row.childCount)row.getChildAt(i).isEnabled=enabled
            numberLabels[key]?.apply {isEnabled=enabled;alpha=if(enabled)1f else .38f}
            val original=p.default(SurroundController.defaults[SurroundController.selected(context)],e)
            val defaultTick=(c.min+((original-c.min).toDouble()/e.step).roundToInt()*e.step).coerceIn(c.min,c.max).toFloat()
            SliderDoubleTapReset.install(pair.first,defaultTick,onCancel={trackingNumbers-=key;render()},resetLabel="恢复当前槽默认值",thumbOnly=true){reset(key)}
        }
        switches.forEach {(key,row)->
            val checked=p.on(s,key)
            if(row.isChecked!=checked)row.setCheckedImmediately(checked)
            row.isEnabled=when(key){"link","diagnostics_on"->true;"cal_on","match_on"->SurroundController.listeningSupported(context);else->supported}
            switchLabels[key]?.apply {isEnabled=row.isEnabled;alpha=if(row.isEnabled)1f else .38f}
        }
        choices.forEach {(key,row)->row.text=choiceLabels.getValue(key)[p.value(s,key)]}
        support.text=if(supported)"专业驱动已连接。新增处理默认关闭；RMS与峰值保护可独立使用。" else "原处理仍可使用；专业处理与监测需要本轮新版驱动。编辑值会保留。"
        val geo=p.geometry(s);geometry.text=String.format(Locale.ROOT,"估算夹角 %.2f°，路径差 %.2f mm，延迟 %.1f μs。%s",geo.angleDegrees,geo.differenceMm,geo.delayTenthsUs/10.0,if(geo.applicable)"应用只写入当前槽的双向延迟。" else "超出20～210 μs范围，请检查摆位；不会强行截断。")
        applyGeometry.isEnabled=geo.applicable
        saveA.text=if(SurroundProfessionalRuntime.hasComparison(3))"更新参数 A 快照" else "保存当前参数到 A";saveB.text=if(SurroundProfessionalRuntime.hasComparison(4))"更新参数 B 快照" else "保存当前参数到 B";saveA.isEnabled=supported;saveB.isEnabled=supported
        audition.text="临时试听：${SurroundProfessionalRuntime.auditionLabels[SurroundProfessionalRuntime.audition]}";audition.isEnabled=supported
        actual.text=runtimeText(s)
        matchStatus.text=listeningText(s,false);calibrationStatus.text=listeningText(s,true)
        calibrationButton.text=if(SurroundProfessionalRuntime.calibration!=0)"停止校准试听" else "开始校准试听"
        calibrationButton.isEnabled=SurroundProfessionalRuntime.calibration!=0 || (SurroundController.listeningSupported(context) && p.on(s,"cal_on"))
        remeasure.isEnabled=SurroundController.listeningSupported(context)
        renderDiagnostics(s)
        rendering=false
    }
    private fun listeningText(s:SurroundController.Settings,calibration:Boolean):String {
        if(!SurroundController.listeningSupported(context))return "此试听工具需要本轮配套驱动；已保存的设置保留。"
        if(!p.on(s,if(calibration)"cal_on" else "match_on"))return if(calibration)"校准试听未允许，不会生成测试声音。" else "等响度试听已关闭，原 A/B 试听照常使用。"
        val values=SurroundProfessionalRuntime.listeningReadings()
        if(values.isEmpty())return "等待正在处理的音频会话。"
        return values.mapIndexed {index,v->
            val prefix=if(values.size>1)"会话 ${index+1}：\n" else ""
            if(calibration)prefix+if(v[15]==0)"未播放测试信号；需要手动开始。" else "${choiceLabels.getValue("cal_mode")[v[15]-1]}，剩余 ${v[16]} ms\n有效正弦频率 ${v[22]} Hz；噪声边界 ${v[17]}～${v[18]} Hz"
            else {fun gain(i:Int)=String.format(Locale.ROOT,"%.3f",v[i]/1000.0)
                prefix+"A：${if(v[6]==1)"已测量，固定衰减 ${gain(10)} dB" else "待测量"}\nB：${if(v[7]==1)"已测量，固定衰减 ${gain(11)} dB" else "待测量"}\n当前试听增益 ${gain(12)} dB"+
                    if(v[20]==1)"\n组间差异超过最大衰减，尚未完全等响。" else if(v[21]==1)"\n信号过低，等待有效音乐重新测量。" else if(v[13]!=0 && v[if(v[13]==3)6 else 7]==0)"\n正在测量参数 ${if(v[13]==3)"A" else "B"}：${v[14]/10}%（先等待500 ms稳定）" else "\n依次试听 A、B；测量完成后可往返比较。"
            }
        }.joinToString("\n\n")
    }
    private fun renderDiagnostics(s:SurroundController.Settings){
        val on=p.on(s,"diagnostics_on")
        if(!on){diagnosticJob?.cancel();diagnosticKey=null;diagnosticStatus.text="诊断已关闭。";return}
        val fs=SurroundProfessionalRuntime.curveRate()?.toDouble() ?: 48000.0
        val key=DiagnosticKey(SurroundProfessionalRuntime.preview(context),fs,p.value(s,"diagnostics_view"),p.value(s,"impulse_window"))
        if(key==diagnosticKey)return
        diagnosticKey=key;diagnosticJob?.cancel();diagnosticStatus.text="正在计算预测响应…"
        diagnosticJob=scope.launch {
            val result=withContext(Dispatchers.Default){val task=currentCoroutineContext();SurroundDiagnostics.calculate(key.settings,key.fs,key.view,key.window){task.ensureActive()}}
            if(diagnosticKey==key){diagnosticStatus.text=String.format(Locale.ROOT,"%s %.0f Hz；最大频率增益（双声道能量网格估算）%+.2f dB。\n建议预留至少 %.2f dB 用于这一估算；不构成采样峰值或真峰值保证。%s",if(SurroundProfessionalRuntime.curveRate()==null)"参考采样率" else "宿主采样率",key.fs,result.gainDb,kotlin.math.max(0.0,result.gainDb),if(key.view==2)"\n显示窗口后的响应尾部未绘出。" else "\n低于−120 dB的路径不绘制相位或群延迟。")}
        }
    }
    private fun runtimeText(s:SurroundController.Settings):String {
        val r=SurroundProfessionalRuntime.readings;val rates=r.mapNotNull {it.hostRate?.takeIf {v->v in 8000..768000}}.distinct()
        val rate=if(rates.isEmpty())"未获得实际处理率；曲线暂用48 kHz参考" else "宿主配置 ${rates.joinToString(" / ") {"$it Hz"}}"
        if(!p.on(s,"meters"))return "$rate\n监测已关闭。完整曲线不包含动态RMS与峰值保护的变化。"
        fun db(v:Int)=if(v==Int.MIN_VALUE)"无有效信号" else String.format(Locale.ROOT,"%.1f",v/10.0)
        fun rho(v:Int)=if(v==Int.MIN_VALUE)"无有效信号" else String.format(Locale.ROOT,"%+.3f",v/10000.0)
        val text=r.filter {it.enabled && it.active && it.snapshot?.data?.get(5)==1}.mapIndexed {index,reading->val v=reading.snapshot!!.data
            "${if(r.size>1)"会话 ${index+1}：\n" else ""}输入／输出 RMS ${db(v[7])}／${db(v[8])} dBFS\n输入／输出峰值 ${db(v[9])}／${db(v[10])} dBFS\n相关度 ${rho(v[11])} → ${rho(v[12])}\n实际RMS补偿 ${db(v[13])} dB，峰值保护衰减 ${db(v[14])} dB\n有效频段目标 ${v[16]}～${v[17]} Hz；实际试听 ${SurroundProfessionalRuntime.auditionLabels[v[24]]}"
        }.joinToString("\n\n")
        return "$rate\n${text.ifEmpty {"等待正在处理的会话；过期信号读数已隐藏。"}}"
    }
    fun close(){scope.cancel();SurroundProfessionalRuntime.stop(context,this);SurroundProfessionalRuntime.clearComparisons(context);dialogs.toList().forEach {it.dismiss()}}
}
