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

/** Factory directions are opt-in and never change the module's power or legacy Float drive. */
object TimbreSystemPresets {
    val entries: List<Pair<String, TimbreParameters.Settings>> = listOf(
        "原版兼容" to TimbreParameters.defaults(),
        "柔和人声 · 专业参数方案" to TimbreParameters.Settings(
            professional = true, coreOn = true, drive = 10, amount = 800,
            band1 = 650, band2 = 1100, band3 = 850, band4 = 400,
            toneOn = true, cut1 = 600, cut2 = 1900, cut3 = 4400, cut4 = 8000, cut5 = 12000,
            colorHP = 100, colorLP = 14000, dcOn = true, dcHz = 50,
            mixOn = true, mix = 700, outputOn = true, output = -5,
            compOn = true, comp = 1000, qualityOn = true, factor = 2,
        ),
        "暖厚中频 · 专业参数方案" to TimbreParameters.Settings(
            professional = true, coreOn = true, drive = 20, amount = 900,
            band1 = 1300, band2 = 1200, band3 = 650, band4 = 300,
            toneOn = true, cut1 = 500, cut2 = 1700, cut3 = 4000, cut4 = 7500, cut5 = 11500,
            colorHP = 70, colorLP = 12000, dcOn = true, dcHz = 50,
            mixOn = true, mix = 750, outputOn = true, output = -10,
            compOn = true, comp = 1000, qualityOn = true, factor = 4,
        ),
        "清晰细节 · 专业参数方案" to TimbreParameters.Settings(
            professional = true, coreOn = true, drive = 0, amount = 600,
            band1 = 250, band2 = 500, band3 = 900, band4 = 1100,
            toneOn = true, cut1 = 700, cut2 = 2200, cut3 = 4800, cut4 = 8500, cut5 = 12500,
            colorHP = 250, colorLP = 16000, dcOn = true, dcHz = 50,
            mixOn = true, mix = 600, outputOn = true, output = 0,
            compOn = true, comp = 1000, qualityOn = true, factor = 2,
        ),
        "明显暖色 · 专业参数方案" to TimbreParameters.Settings(
            professional = true, coreOn = true, drive = 60, amount = 6000,
            band1 = 1000, band2 = 1000, band3 = 1000, band4 = 1000,
            toneOn = true, cut1 = 600, cut2 = 1900, cut3 = 4400, cut4 = 8000, cut5 = 12000,
            colorHP = 0, colorLP = 12000, dcOn = true, dcHz = 50,
            mixOn = true, mix = 850, outputOn = true, output = -60,
            compOn = true, comp = 1000, qualityOn = true, factor = 4,
        ),
    )
}

class TimbrePresetsDialog(
    private val context: Context,
    private val store: TimbrePresetStore,
    private val current: () -> TimbreParameters.Settings,
    private val apply: (TimbreParameters.Settings) -> Unit,
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
            Toast.makeText(context, failure.message ?: "音色仿真预设操作失败", Toast.LENGTH_LONG).show()
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
        track(MaterialAlertDialogBuilder(context).setTitle("分频染色预设")
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton("关闭", null).create()).show()
        attempt { refresh() }
    }
    fun refresh() {
        val list = content ?: return; list.removeAllViews()
        list.addView(text("预设保存本模块完整专业参数和分组开关，不改变模块电源和旧前级保存值；与其他模块分别管理。原四项方案保留，明显暖色为主动选择的较强染色方案，需要配套增强驱动。染色增量不等于各频段原声 EQ 增益。个人库不随设备配置替换。试听、临时独听增益、A/B 快照、匹配和测量结果不保存。"))
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
        val options = TimbreSystemPresets.entries
        track(MaterialAlertDialogBuilder(context).setTitle("系统声音方案")
            .setItems(options.map { it.first + "\n" + summary(it.second) }.toTypedArray()) { _, index ->
                attempt { apply(options[index].second); dismiss() }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun summary(settings: TimbreParameters.Settings): String {
        if (!settings.professional) return "原版兼容 · 专业调节关闭"
        val core = if (settings.coreOn) String.format(Locale.ROOT, "驱动 %+.1f dB · 总染色 %.1f%%", settings.drive / 10.0, settings.amount / 10.0)
            else "原版驱动和分段染色"
        val blend = if (settings.mixOn) String.format(Locale.ROOT, "干湿 %.1f%%", settings.mix / 10.0) else "完整处理声"
        val quality = if (settings.qualityOn) "${settings.factor}× FIR" else "原版自动品质"
        return "$core · $blend · $quality"
    }
    private fun latest(id: String) = store.load().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")
    private fun actions(preset: TimbrePreset) {
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
    private fun nameDialog(preset: TimbrePreset?) {
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
