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
import java.util.Locale

/** Parameter combinations describe sound direction, without naming unmodelled physical tubes. */
object TubeSystemPresets {
    val entries: List<Pair<String, TubeParameters.Settings>> = listOf(
        "原版默认参数" to TubeParameters.defaults(),
        "轻暖人声 · 专业参数方案" to TubeParameters.Settings(
            professional = true, satOn = true, drive = 60, softness = 750, asym = 200,
            toneOn = true, softenMix = 700, softenCut = 0, preHP = 150, postLP = 16000,
            mixOn = true, mix = 300, outputOn = true, output = -10, qualityOn = true, factor = 2,
        ),
        "厚实 · 专业参数方案" to TubeParameters.Settings(
            professional = true, satOn = true, drive = 120, softness = 650, asym = 350,
            toneOn = true, softenMix = 850, softenCut = 0, preHP = 80, postLP = 14000,
            mixOn = true, mix = 600, outputOn = true, output = -20, qualityOn = true, factor = 4,
        ),
        "通透 · 专业参数方案" to TubeParameters.Settings(
            professional = true, satOn = true, drive = 45, softness = 850, asym = 100,
            toneOn = true, softenMix = 250, softenCut = 12000, preHP = 180, postLP = 0,
            mixOn = true, mix = 250, outputOn = true, output = 0, qualityOn = true, factor = 2,
        ),
    )
}

class TubePresetsDialog(
    private val context: Context,
    private val store: TubePresetStore,
    private val current: () -> TubeParameters.Settings,
    private val apply: (TubeParameters.Settings) -> Unit,
    private val requestImport: () -> Unit,
    private val requestExport: (String?) -> Unit,
) {
    private val dialogs = mutableListOf<AlertDialog>()
    private var content: LinearLayout? = null
    private fun track(dialog: AlertDialog): AlertDialog {
        dialogs += dialog; dialog.setOnDismissListener { dialogs.remove(dialog) }; return dialog
    }
    private fun attempt(action: () -> Unit) {
        try { action() } catch (failure: Exception) {
            Toast.makeText(context, failure.message ?: "电子管预设操作失败", Toast.LENGTH_LONG).show()
        }
    }
    private fun button(label: String, action: () -> Unit) = MaterialButton(context).apply {
        text = label; isAllCaps = false; setSingleLine(false)
        minimumHeight = (48 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(-1, -2); setOnClickListener { attempt(action) }
    }
    private fun text(label: String) = TextView(context).apply {
        text = label; textSize = 14f; setPadding(0, 16, 0, 8); setSingleLine(false)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    fun show() {
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, padding)
        }
        content = list
        track(MaterialAlertDialogBuilder(context).setTitle("电子管柔化预设")
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton("关闭", null).create()).show()
        attempt { refresh() }
    }
    fun refresh() {
        val list = content ?: return; list.removeAllViews()
        list.addView(text("预设保存本模块完整专业参数和分组开关，保留模块电源，与分频染色及谐波染色独立。原版默认声音保留，新增功能默认关闭。系统声音方案仅在主动应用时载入，名称描述声音取向。个人库不随设备配置替换。试听、A/B 快照、等电平匹配和测量结果不保存。"))
        list.addView(button("系统声音方案") { systems() })
        val saved = store.load()
        list.addView(text("个人收藏 ${saved.size} / 20"))
        list.addView(button("保存当前参数") { nameDialog(null) }.apply { isEnabled = saved.size < 20 })
        list.addView(button("导入预设文件", requestImport).apply { isEnabled = saved.size < 20 })
        list.addView(button("导出全部个人预设") { requestExport(null) }.apply { isEnabled = saved.isNotEmpty() })
        saved.forEach { preset ->
            list.addView(button(preset.name + "\n" + summary(preset.settings)) { actions(preset) })
        }
    }
    private fun systems() {
        val options = TubeSystemPresets.entries
        track(MaterialAlertDialogBuilder(context).setTitle("系统声音方案")
            .setItems(options.map { it.first + "\n" + summary(it.second) }.toTypedArray()) { _, index ->
                attempt {
                    val selected = options[index].second
                    apply(selected)
                    dismiss()
                }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun summary(settings: TubeParameters.Settings): String {
        if (!settings.professional) return "原版柔化 · 专业调节关闭"
        val saturation = if (settings.satOn) String.format(Locale.ROOT, "驱动 %.1f dB", settings.drive / 10.0)
            else "非线性饱和关闭"
        val blend = if (settings.mixOn) String.format(Locale.ROOT, "干湿 %.1f%%", settings.mix / 10.0)
            else "完整处理声"
        val quality = if (settings.qualityOn) "${settings.factor}× 过采样" else "过采样关闭"
        return "$saturation · $blend · $quality"
    }
    private fun latest(id: String) = store.load().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")
    private fun actions(preset: TubePreset) {
        track(MaterialAlertDialogBuilder(context).setTitle(preset.name)
            .setItems(arrayOf("应用", "重命名", "用当前参数覆盖", "导出此预设", "删除")) { _, index ->
                attempt { when (index) {
                    0 -> { apply(latest(preset.id).settings); dismiss() }
                    1 -> nameDialog(latest(preset.id))
                    2 -> confirm("用当前参数覆盖“${preset.name}”？") { store.overwrite(preset.id, current()); refresh() }
                    3 -> requestExport(latest(preset.id).id)
                    4 -> confirm("删除“${preset.name}”？") { store.delete(preset.id); refresh() }
                } }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun nameDialog(preset: TubePreset?) {
        val input = EditText(context).apply {
            setSingleLine(); hint = "1–40 个字符"; setText(preset?.name.orEmpty()); selectAll()
        }
        val dialog = track(MaterialAlertDialogBuilder(context)
            .setTitle(if (preset == null) "保存个人预设" else "重命名")
            .setView(input).setPositiveButton("保存", null).setNegativeButton("取消", null).create())
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val name = input.text.toString()
                    if (preset == null) store.save(name, current()) else store.rename(preset.id, name)
                    dialog.dismiss(); refresh()
                } catch (failure: Exception) { input.error = failure.message ?: "保存失败" }
            }
        }
        dialog.show()
    }
    private fun confirm(message: String, action: () -> Unit) {
        track(MaterialAlertDialogBuilder(context).setTitle(message)
            .setPositiveButton("确认") { _, _ -> attempt(action) }.setNegativeButton("取消", null).create()).show()
    }
    fun dismiss() {
        dialogs.toList().asReversed().forEach { it.dismiss() }; dialogs.clear(); content = null
    }
}
