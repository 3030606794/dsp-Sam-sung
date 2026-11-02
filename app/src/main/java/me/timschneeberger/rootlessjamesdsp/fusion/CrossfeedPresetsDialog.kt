package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder

class CrossfeedPresetsDialog(private val context:Context,private val store:CrossfeedPresetStore,
    private val current:()->CrossfeedParameters.Settings,private val apply:(CrossfeedParameters.Settings)->Unit,
    private val requestImport:()->Unit,private val requestExport:(String?)->Unit){
    private val dialogs=mutableListOf<AlertDialog>()
    private var content:LinearLayout?=null
    private fun track(d:AlertDialog):AlertDialog {dialogs+=d;d.setOnDismissListener {dialogs.remove(d)};return d}
    private fun attempt(block:()->Unit){try{block()}catch(e:Exception){Toast.makeText(context,e.message ?: "预设操作失败",Toast.LENGTH_LONG).show()}}
    private fun button(label:String,action:()->Unit)=MaterialButton(context).apply {
        text=label;isAllCaps=false;setSingleLine(false);minimumHeight=(48*resources.displayMetrics.density).toInt()
        layoutParams=LinearLayout.LayoutParams(-1,-2);setOnClickListener {attempt(action)}
    }
    private fun text(label:String)=TextView(context).apply {
        text=label;textSize=14f;setPadding(0,16,0,8);setSingleLine(false)
        layoutParams=LinearLayout.LayoutParams(-1,-2)
    }
    fun show(){
        val pad=(20*context.resources.displayMetrics.density).toInt()
        val list=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(pad,0,pad,pad)}
        content=list
        track(MaterialAlertDialogBuilder(context).setTitle("耳机交叉馈送预设")
            .setView(ScrollView(context).apply {addView(list)}).setNegativeButton("关闭",null).create()).show()
        refresh()
    }
    fun refresh(){
        val list=content ?: return;list.removeAllViews()
        list.addView(text("应用预设只更新本模块参数，保留本模块电源。个人库独立保存，设备配置切换不会覆盖。试听快照与电平匹配不保存。"))
        list.addView(button("系统方案"){systems()})
        val saved=store.load()
        list.addView(text("个人收藏 ${saved.size} / 20"))
        list.addView(button("保存当前参数"){nameDialog(null)}.apply {isEnabled=saved.size<20})
        list.addView(button("导入预设文件",requestImport).apply {isEnabled=saved.size<20})
        list.addView(button("导出全部个人预设"){requestExport(null)}.apply {isEnabled=saved.isNotEmpty()})
        saved.forEach {p->list.addView(button(p.name+"\n"+summary(p.settings)){actions(p)})}
    }
    private fun systems(){
        val p=CrossfeedParameters
        fun legacy(tier:Int)=p.capture(emptyMap<String,Any>()).copy(legacy=tier,professional=false)
        val options=listOf(
            "原版轻度" to legacy(0),"原版中度" to legacy(1),"原版强" to legacy(2),
            "专业轻柔" to CrossfeedParameters.Settings(0,true,650,95,60,0,0,0),
            "专业自然" to CrossfeedParameters.Settings(1,true,700,60,70,0,0,0),
            "专业全频" to CrossfeedParameters.Settings(1,true,700,60,70,0,0,1),
            "专业极端分离录音" to CrossfeedParameters.Settings(2,true,700,45,100,0,-10,0))
        track(MaterialAlertDialogBuilder(context).setTitle("系统方案")
            .setItems(options.map {it.first+" · "+summary(it.second)}.toTypedArray()){_,i->apply(options[i].second);dismiss()}
            .setNegativeButton("取消",null).create()).show()
    }
    private fun summary(s:CrossfeedParameters.Settings)=if(!s.professional)listOf("原版轻度","原版中度","原版强")[s.legacy]
        else "${s.cutoff} Hz · ${CrossfeedParameters.attenuation.number(s.attenuation)} dB · ${s.mix}% · ${s.delay} μs"
    private fun latest(id:String)=store.load().firstOrNull {it.id==id} ?: error("此预设已不存在")
    private fun actions(p:CrossfeedPreset){
        track(MaterialAlertDialogBuilder(context).setTitle(p.name)
            .setItems(arrayOf("应用","重命名","用当前参数覆盖","导出此预设","删除")){_,i->attempt {when(i){
                0->{apply(latest(p.id).settings);dismiss()}
                1->nameDialog(latest(p.id))
                2->confirm("用当前参数覆盖“${p.name}”？"){store.overwrite(p.id,current());refresh()}
                3->requestExport(latest(p.id).id)
                4->confirm("删除“${p.name}”？"){store.delete(p.id);refresh()}
            }}}.setNegativeButton("取消",null).create()).show()
    }
    private fun nameDialog(p:CrossfeedPreset?){
        val input=EditText(context).apply {setSingleLine();hint="1–40 个字符";setText(p?.name.orEmpty());selectAll()}
        val d=track(MaterialAlertDialogBuilder(context).setTitle(if(p==null)"保存个人预设"else"重命名")
            .setView(input).setPositiveButton("保存",null).setNegativeButton("取消",null).create())
        d.setOnShowListener {d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try{val name=input.text.toString();if(p==null)store.save(name,current())else store.rename(p.id,name);d.dismiss();refresh()}
            catch(e:Exception){input.error=e.message ?: "保存失败"}
        }};d.show()
    }
    private fun confirm(message:String,action:()->Unit){
        track(MaterialAlertDialogBuilder(context).setTitle(message).setPositiveButton("确认"){_,_->attempt(action)}
            .setNegativeButton("取消",null).create()).show()
    }
    fun dismiss(){dialogs.toList().asReversed().forEach {it.dismiss()};dialogs.clear();content=null}
}
