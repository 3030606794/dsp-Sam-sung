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

/** These are control combinations for the existing FreeVerb, not additional reverb algorithms. */
object ReverbSystemPresets {
    val entries: List<Pair<String, ReverbParameters.Settings>> = listOf(
        "原始默认参数" to ReverbParameters.Settings(),
        "小房间 · 专业参数方案" to ReverbParameters.Settings(
            professional = true, size = 160, width = 650, damp = 620, wet = 120, dry = 500,
            predelayOn = true, predelay = 70, lowCutOn = true, lowCut = 120,
            highCutOn = true, highCut = 9000,
        ),
        "人声清晰 · 专业参数方案" to ReverbParameters.Settings(
            professional = true, size = 380, width = 750, damp = 560, wet = 140, dry = 500,
            predelayOn = true, predelay = 280, lowCutOn = true, lowCut = 180,
            highCutOn = true, highCut = 8000,
        ),
        "大厅 · 专业参数方案" to ReverbParameters.Settings(
            professional = true, size = 780, width = 1000, damp = 420, wet = 220, dry = 500,
            predelayOn = true, predelay = 240, lowCutOn = true, lowCut = 100,
            highCutOn = true, highCut = 11000,
        ),
        "氛围 · 专业参数方案" to ReverbParameters.Settings(
            professional = true, size = 900, width = 1000, damp = 680, wet = 300, dry = 500,
            predelayOn = true, predelay = 450, lowCutOn = true, lowCut = 160,
            highCutOn = true, highCut = 6500,
        ),
    )
}

class ReverbPresetsDialog(
    private val context: Context,
    private val store: ReverbPresetStore,
    private val current: () -> ReverbParameters.Settings,
    private val apply: (ReverbParameters.Settings) -> Unit,
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
            Toast.makeText(context, failure.message ?: "混响预设操作失败", Toast.LENGTH_LONG).show()
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
        track(MaterialAlertDialogBuilder(context).setTitle("经典混响预设")
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton("关闭", null).create()).show()
        attempt { refresh() }
    }
    fun refresh() {
        val list = content ?: return; list.removeAllViews()
        list.addView(text("预设保存本模块原五项、专业参数与秒制尾音／分频衰减／避让设置，保留模块电源，与场景混响独立。支持导入上一版预设，新增功能默认关闭。个人库不随设备配置替换。试听、A/B 快照、等响度匹配和测量结果不保存。"))
        list.addView(button("系统参数方案") { systems() })
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
        val options = ReverbSystemPresets.entries
        track(MaterialAlertDialogBuilder(context).setTitle("系统参数方案")
            .setItems(options.map { it.first + "\n" + summary(it.second) }.toTypedArray()) { _, index ->
                attempt {
                    val selected = options[index].second
                    apply(if (selected.professional) selected.copy(legacy = current().legacy) else selected)
                    dismiss()
                }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun summary(settings: ReverbParameters.Settings): String = if (!settings.professional) {
        "原五项参数 · 专业调节关闭"
    } else {
        String.format(Locale.ROOT, "专业调节 · %s · %s", if(settings.advanced.rtOn)
            String.format(Locale.ROOT,"RT60 %.2f s",settings.advanced.rt60/100.0) else String.format(Locale.ROOT,"房间 %.1f%%",settings.size/10.0),
            if (settings.linked) "联动混合 ${String.format(Locale.ROOT, "%.1f", settings.mix / 10.0)}%"
            else "湿声 ${String.format(Locale.ROOT, "%.1f", settings.wet / 10.0)}%")
    }
    private fun latest(id: String) = store.load().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")
    private fun actions(preset: ReverbPreset) {
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
    private fun nameDialog(preset: ReverbPreset?) {
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
