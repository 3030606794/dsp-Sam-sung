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

/** The original thirteen room algorithms remain the bases for professional adjustments. */
object RoomReverbSystemPresets {
    val entries: List<Pair<String, RoomReverbParameters.Settings>> get() =
        RoomReverbParameters.baseIds.map { RoomReverbParameters.baseName(it) to RoomReverbParameters.defaults(it) }
}

class RoomReverbPresetsDialog(
    private val context: Context,
    private val store: RoomReverbPresetStore,
    private val current: () -> RoomReverbParameters.Settings,
    private val apply: (RoomReverbParameters.Settings) -> Unit,
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
        track(MaterialAlertDialogBuilder(context).setTitle("场景混响预设")
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton("关闭", null).create()).show()
        attempt { refresh() }
    }
    fun refresh() {
        val list = content ?: return; list.removeAllViews()
        list.addView(text("预设保存基础房型及全部专业参数，保留模块电源，与 经典混响独立。原 13 个房型声音保留，新增功能默认关闭。重新选择基础房型会载入该房型原值并关闭新增功能，保留专业总开关。个人库不随设备配置替换。试听、A/B 快照、等电平匹配和测量结果不保存。"))
        list.addView(button("原 13 个基础房型") { systems() })
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
        val options = RoomReverbSystemPresets.entries
        track(MaterialAlertDialogBuilder(context).setTitle("原 13 个基础房型")
            .setItems(options.map { it.first + "\n" + summary(it.second) }.toTypedArray()) { _, index ->
                attempt {
                    val selected = options[index].second
                    apply(selected.copy(professional = current().professional))
                    dismiss()
                }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun summary(settings: RoomReverbParameters.Settings): String {
        val base = RoomReverbParameters.baseName(settings.base)
        if (!settings.professional) return "$base · 专业调节关闭"
        val tail = if (settings.timeOn) String.format(Locale.ROOT, "RT60 %.2f s", settings.rt60 / 100.0)
            else "基础房型尾音"
        val blend = if (settings.linked) String.format(Locale.ROOT, "联动混合 %.1f%%", settings.mix / 10.0)
            else "三路独立电平"
        return "$base · $tail · $blend"
    }
    private fun latest(id: String) = store.load().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")
    private fun actions(preset: RoomReverbPreset) {
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
    private fun nameDialog(preset: RoomReverbPreset?) {
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
