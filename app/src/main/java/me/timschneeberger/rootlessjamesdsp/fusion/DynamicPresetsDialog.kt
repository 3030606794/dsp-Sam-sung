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

/** Complete directions are applied only by user choice; none changes effect power. */
object DynamicSystemPresets {
    val entries: List<Pair<String, DynamicParameters.Settings>> = listOf(
        "原版兼容" to DynamicParameters.defaults(),
        "柔和低频 · 专业参数方案" to DynamicParameters.Settings(
            professional = true, base = 0, strength = 12, coreOn = true,
            bassDelta = -10, lowGainDelta = 100, highGainDelta = -20,
            toneOn = true, xDelta = 40, highFreqDelta = 20, hp = 20, lp = 500,
            adaptiveOn = true, detector = 1, detectorHP = 25, detectorLP = 220,
            threshold = -240, knee = 60, maxReduction = 60, attack = 100, release = 300,
            mixOn = true, mix = 700, outputOn = true, output = -20,
        ),
        "紧致鼓点 · 专业参数方案" to DynamicParameters.Settings(
            professional = true, base = 4, strength = 18, coreOn = true,
            injectDelta = -100, qDelta = -10,
            toneOn = true, deepFreqDelta = 15, hp = 25, lp = 200,
            adaptiveOn = true, detector = 0, detectorHP = 25, detectorLP = 180,
            threshold = -180, knee = 30, maxReduction = 80, attack = 30, release = 180,
            mixOn = true, mix = 650, outputOn = true, output = -30,
        ),
        "低电平恢复 · 专业参数方案" to DynamicParameters.Settings(
            professional = true, base = 2, strength = 15, coreOn = true,
            lowGainDelta = 150,
            toneOn = true, highFreqDelta = 15, hp = 20, lp = 650,
            adaptiveOn = true, detector = 1, detectorHP = 20, detectorLP = 250,
            threshold = -300, knee = 80, maxReduction = 90, attack = 150, release = 500,
            mixOn = true, mix = 800, outputOn = true, output = -30,
        ),
    )
}

class DynamicPresetsDialog(
    private val context: Context,
    private val store: DynamicPresetStore,
    private val current: () -> DynamicParameters.Settings,
    private val apply: (DynamicParameters.Settings) -> Unit,
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
            Toast.makeText(context, failure.message ?: "动态音色预设操作失败", Toast.LENGTH_LONG).show()
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
        track(MaterialAlertDialogBuilder(context).setTitle("动态低频塑形预设")
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton("关闭", null).create()).show()
        attempt { refresh() }
    }
    fun refresh() {
        val list = content ?: return; list.removeAllViews()
        list.addView(text("保存当前基础预设、原强度、全部专业参数和分组开关，不改变模块电源；与动态压扩器及其他模块分别管理。原六预设仍是基础，系统方案仅在主动应用时载入。个人库不随设备配置替换。试听、A/B 快照、匹配和测量结果不保存。"))
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
        val options = DynamicSystemPresets.entries
        track(MaterialAlertDialogBuilder(context).setTitle("系统声音方案")
            .setItems(options.map { it.first + "\n" + summary(it.second) }.toTypedArray()) { _, index ->
                attempt { apply(options[index].second); dismiss() }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun summary(settings: DynamicParameters.Settings): String {
        val base = listOf("均衡", "温和", "厚实", "清晰", "深低音", "开放声场")[settings.base]
        if (!settings.professional) return "$base · 强度 ${settings.strength}% · 专业调节关闭"
        val core = if (settings.coreOn) "核心精调开启" else "基础音色"
        val blend = if (settings.mixOn) String.format(Locale.ROOT, "干湿 %.1f%%", settings.mix / 10.0) else "完整处理声"
        val adaptive = if (settings.adaptiveOn) "${if (settings.detector == 0) "峰值" else "RMS"} 自适应" else "自适应关闭"
        return "$base · 强度 ${settings.strength}% · $core · $blend · $adaptive"
    }
    private fun latest(id: String) = store.load().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")
    private fun actions(preset: DynamicPreset) {
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
    private fun nameDialog(preset: DynamicPreset?) {
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
