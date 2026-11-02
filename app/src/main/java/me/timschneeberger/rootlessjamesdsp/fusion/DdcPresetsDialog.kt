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

/** Directions change only the added layer and keep the currently selected original VDC. */
object DdcSystemPresets {
    fun foundation(current: DdcParameters.Settings): DdcParameters.Settings = DdcParameters.defaults()
    fun entries(current: DdcParameters.Settings): List<Pair<String, DdcParameters.Settings>> {
        val base = foundation(current)
        return listOf(
            "原文件校正 · 关闭专业附加层" to base,
            "轻校正 · 75% 混合" to base.copy(professional = true, ratioOn = true, ratio = 750),
            "温暖柔润 · 低高频补偿" to base.copy(professional = true, compensationOn = true,
                lowOn = true, lowFrequency = 120, lowGain = 15, highOn = true, highFrequency = 8000, highGain = -10,
                autoHeadroomOn = true),
            "清亮人声 · 中高频补偿" to base.copy(professional = true, compensationOn = true,
                peak1On = true, peak1Frequency = 1800, peak1Gain = 10, peak1Q = 80,
                highOn = true, highFrequency = 10000, highGain = 8, autoHeadroomOn = true),
            "柔和耐听 · 高频局部补偿" to base.copy(professional = true, compensationOn = true,
                peak2On = true, peak2Frequency = 6000, peak2Gain = -15, peak2Q = 100),
            "双耳归中 · 联动基础" to base.copy(professional = true, earOn = true, linked = true)
        )
    }
}

class DdcPresetsDialog(
    private val context: Context,
    private val store: DdcPresetStore,
    private val currentFoundation: () -> DdcFoundation,
    private val current: () -> DdcParameters.Settings,
    private val apply: (DdcFoundation, DdcParameters.Settings) -> Unit,
    private val requestImport: () -> Unit,
    private val requestExport: (String?) -> Unit,
    private val canSave: () -> Boolean = { true },
) {
    private val dialogs = mutableListOf<AlertDialog>()
    private var content: LinearLayout? = null
    private fun track(dialog: AlertDialog): AlertDialog {
        dialogs += dialog; dialog.setOnDismissListener { dialogs.remove(dialog) }; return dialog
    }
    private fun attempt(action: () -> Unit) {
        try { action() } catch (failure: Exception) {
            Toast.makeText(context, failure.message ?: "DDC 预设操作失败", Toast.LENGTH_LONG).show()
        }
    }
    private fun requireSavable() {
        require(canSave()) { "当前 DDC 文件或参数无效，请重新选择有效文件后再保存" }; currentFoundation().validated()
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
        track(MaterialAlertDialogBuilder(context).setTitle("DDC 频响校正预设")
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton("关闭", null).create()).show()
        attempt { refresh() }
    }
    fun refresh() {
        val list = content ?: return; list.removeAllViews()
        list.addView(text("收藏保存当前 VDC 原文、SHA-256 内容指纹、原文件名与全部专业参数，不改变模块电源。文件丢失或同名内容改变时，从收藏还原独立副本；原文件保留。系统方案仅改变附加调节，保留当前文件；它们是听感方向，不是测量目标。与其他模块分别管理，个人库不随设备配置替换。试听、A/B 快照、电平匹配和测量结果不保存。"))
        list.addView(button("系统声音方案") { systems() })
        val saved = store.load()
        list.addView(text("个人收藏 ${saved.size} / 20"))
        list.addView(button("保存当前参数") { nameDialog(null) }.apply { isEnabled = saved.size < 20 })
        list.addView(button("导入预设文件", requestImport).apply { isEnabled = saved.size < 20 })
        list.addView(button("导出全部个人预设") { requestExport(null) }.apply { isEnabled = saved.isNotEmpty() })
        saved.forEach { preset ->
            list.addView(button(preset.name + "\n" + preset.foundation.label + " · " + preset.foundation.sha256.take(8) + "\n" + summary(preset.settings)) { actions(preset) })
        }
    }
    private fun systems() {
        val options = DdcSystemPresets.entries(current())
        track(MaterialAlertDialogBuilder(context).setTitle("系统声音方案")
            .setItems(options.map { it.first + "\n" + summary(it.second) }.toTypedArray()) { _, index ->
                attempt { apply(currentFoundation(), options[index].second); dismiss() }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun summary(settings: DdcParameters.Settings): String {
        if (!settings.professional) return "专业附加层关闭 · 原文件声音"
        val ratio = if (settings.ratioOn) String.format(Locale.ROOT, "校正 %.1f%%", settings.ratio / 10.0) else "完整原文件校正"
        val extras = if (settings.compensationOn) "个人补偿开启" else "个人补偿关闭"
        val ears = if (settings.earOn) if (settings.linked) "双耳联动" else "双耳独立" else "双耳微调关闭"
        return "$ratio · $extras · $ears"
    }

    private fun latest(id: String) = store.load().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")
    private fun actions(preset: DdcPreset) {
        track(MaterialAlertDialogBuilder(context).setTitle(preset.name)
            .setItems(arrayOf("应用", "重命名", "用当前参数覆盖", "导出此预设", "删除")) { _, index ->
                attempt { when (index) {
                    0 -> { val selected = latest(preset.id); apply(selected.foundation, selected.settings); dismiss() }
                    1 -> nameDialog(latest(preset.id))
                    2 -> { requireSavable(); confirm("用当前参数覆盖“${preset.name}”？") {
                        requireSavable(); store.overwrite(preset.id, currentFoundation(), current()); refresh()
                    } }
                    3 -> requestExport(latest(preset.id).id)
                    4 -> confirm("删除“${preset.name}”？") { store.delete(preset.id); refresh() }
                } }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun nameDialog(preset: DdcPreset?) {
        if (preset == null) requireSavable()
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
                    if (preset == null) {
                        requireSavable(); store.save(name, currentFoundation(), current())
                    } else store.rename(preset.id, name)
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
