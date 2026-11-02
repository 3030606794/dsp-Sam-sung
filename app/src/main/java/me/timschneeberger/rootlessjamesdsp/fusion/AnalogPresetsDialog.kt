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

/** Original bases stay unchanged; professional factory schemes are opt-in parameter directions. */
object AnalogSystemPresets {
    val entries: List<Pair<String, AnalogParameters.Settings>> = listOf(
        "原版轻度" to AnalogParameters.defaults(0),
        "原版中度" to AnalogParameters.defaults(1),
        "原版强" to AnalogParameters.defaults(2),
        "轻暖 · 专业参数方案" to AnalogParameters.Settings(
            professional = true, baseMode = 0, coreOn = true, drive = 20, amount = 900,
            h2 = 2000, h4 = -200, toneOn = true, hp = 180, lpDelta = -1500,
            peak = 600, peakGain = 70, peakBW = 5000,
            mixOn = true, mix = 850, outputOn = true, output = -5, qualityOn = true, factor = 2,
        ),
        "柔厚 · 专业参数方案" to AnalogParameters.Settings(
            professional = true, baseMode = 1, coreOn = true, drive = 30, amount = 750,
            h2 = 4000, h3 = 100, h4 = 200, toneOn = true, hp = 120, lpDelta = -2000,
            peak = 550, peakGain = 100, peakBW = 5500,
            mixOn = true, mix = 750, outputOn = true, output = -10, qualityOn = true, factor = 4,
        ),
        "清亮 · 专业参数方案" to AnalogParameters.Settings(
            professional = true, baseMode = 0, coreOn = true, drive = 0, amount = 700,
            h2 = -3000, h3 = 50, h4 = -500, h5 = 100, toneOn = true, hp = 400,
            peak = 900, peakGain = 20, peakBW = 4000,
            mixOn = true, mix = 650, outputOn = true, output = 0, qualityOn = true, factor = 2,
        ),
    )
}

class AnalogPresetsDialog(
    private val context: Context,
    private val store: AnalogPresetStore,
    private val current: () -> AnalogParameters.Settings,
    private val apply: (AnalogParameters.Settings) -> Unit,
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
            Toast.makeText(context, failure.message ?: "谐波染色预设操作失败", Toast.LENGTH_LONG).show()
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
        track(MaterialAlertDialogBuilder(context).setTitle("谐波染色预设")
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton("关闭", null).create()).show()
        attempt { refresh() }
    }
    fun refresh() {
        val list = content ?: return; list.removeAllViews()
        list.addView(text("预设保存当前基础三档、本模块完整专业参数和分组开关，保留模块电源，与电子管柔化、分频染色独立。三档作为基础，精调在其上生效，单项复位回当前档原值。原三档声音保留，新增功能默认关闭。系统声音方案仅在主动应用时载入，名称描述参数方向。个人库不随设备配置替换。试听、A/B 快照、等电平匹配和测量结果不保存。"))
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
        val options = AnalogSystemPresets.entries
        track(MaterialAlertDialogBuilder(context).setTitle("系统声音方案")
            .setItems(options.map { it.first + "\n" + summary(it.second) }.toTypedArray()) { _, index ->
                attempt {
                    val selected = options[index].second
                    apply(selected)
                    dismiss()
                }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun summary(settings: AnalogParameters.Settings): String {
        val base = listOf("轻度", "中度", "强")[settings.baseMode]
        if (!settings.professional) return "基础：$base · 专业调节关闭"
        val harmonics = if (settings.coreOn) String.format(Locale.ROOT, "驱动 %+.1f dB", settings.drive / 10.0)
            else "原版谐波权重"
        val blend = if (settings.mixOn) String.format(Locale.ROOT, "干湿 %.1f%%", settings.mix / 10.0)
            else "完整处理声"
        val quality = if (settings.qualityOn) "${settings.factor}× 过采样" else "过采样关闭"
        return "基础：$base · $harmonics · $blend · $quality"
    }

    private fun latest(id: String) = store.load().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")
    private fun actions(preset: AnalogPreset) {
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
    private fun nameDialog(preset: AnalogPreset?) {
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
