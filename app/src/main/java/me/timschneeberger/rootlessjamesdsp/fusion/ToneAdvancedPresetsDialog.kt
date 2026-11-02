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

/** Factory directions retain all ten original values and range; only explicit application changes sound. */
object ToneAdvancedSystemPresets {
    private val foundations = ToneController.KEYS.toSet() + ToneController.RANGE
    fun foundation(current: ToneAdvancedParameters.Settings): ToneAdvancedParameters.Settings =
        ToneAdvancedParameters.capture(ToneAdvancedParameters.defaults().values() +
            current.values().filterKeys { it in foundations })

    fun entries(current: ToneAdvancedParameters.Settings): List<Pair<String, ToneAdvancedParameters.Settings>> {
        val base = foundation(current)
        val warm = base.copy(professional = true, coreOn = true,
            delta = listOf(200,100,0,150,-30,-50,-100,-100,-20,-80),
            shapeOn = true, frequency = listOf(160,3200,60,110,320,1800,3500,5500,8500,12000),
            temperatureLowWeight = 1100, temperatureHighWeight = 900,
            mixOn = true, mix = 850, outputOn = true, output = 0)
        val vocal = base.copy(professional = true, coreOn = true,
            delta = listOf(0,-40,-20,-100,250,80,-20,-30,0,30),
            shapeOn = true, frequency = listOf(180,3500,60,110,350,2000,3300,5500,8500,12000),
            q = listOf(80,75,90,100,110,110),
            mixOn = true, mix = 850, outputOn = true, output = 0)
        val bass = base.copy(professional = true, coreOn = true,
            delta = listOf(50,180,250,20,0,0,0,0,120,0),
            dynamicOn = true, elasticStaticWeight = 700, bassDynamicWeight = 1400,
            bassSplit = 130, bassFast = 30, bassSlow = 90, bassSensitivity = 1250,
            transientFast = 20, transientSlow = 55, transientMax = 30, bassMax = 40,
            weakOn = true, weakFloor = -750,
            mixOn = true, mix = 800, headroomOn = true, headroom = -10,
            outputOn = true, output = 0)
        val air = base.copy(professional = true, coreOn = true,
            delta = listOf(-80,-40,0,-50,50,60,-30,-50,50,250),
            shapeOn = true, frequency = listOf(180,4000,60,110,350,1800,3500,5500,8500,13000),
            slope = listOf(1000,800,1000,800),
            mixOn = true, mix = 800, outputOn = true, output = 0)
        val soft = base.copy(professional = true, coreOn = true,
            delta = listOf(150,40,-60,70,-30,-60,-250,-200,-150,-80),
            dynamicOn = true, elasticStaticWeight = 800, bassDynamicWeight = 700,
            transientWeight = 800, transientFast = 40, transientSlow = 65,
            bassMax = 20, transientMax = 20,
            weakOn = true, weakFloor = -800,
            mixOn = true, mix = 850, outputOn = true, output = 0)
        return listOf("原版兼容 · 保留当前基础" to base,
            "温暖厚润 · 低频与温度修正" to warm,
            "人声靠前 · 中频修正" to vocal,
            "低频弹性 · 起音与静态分开" to bass,
            "清亮空气 · 高频形状修正" to air,
            "柔和耐听 · 高频与瞬态修正" to soft)
    }
}

class ToneAdvancedPresetsDialog(
    private val context: Context,
    private val store: ToneAdvancedPresetStore,
    private val current: () -> ToneAdvancedParameters.Settings,
    private val apply: (ToneAdvancedParameters.Settings) -> Unit,
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
            Toast.makeText(context, failure.message ?: "音色预设操作失败", Toast.LENGTH_LONG).show()
        }
    }
    private fun requireSavable() {
        require(canSave()) { "原十项或范围无效，请重新设置或应用有效预设后再保存" }
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
        track(MaterialAlertDialogBuilder(context).setTitle("听感音色调节预设")
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton("关闭", null).create()).show()
        attempt { refresh() }
    }
    fun refresh() {
        val list = content ?: return; list.removeAllViews()
        list.addView(text("保存原十项整数、原范围及全部专业参数与分组开关，不改变模块电源；与分频染色、动态低频塑形及其他模块分别管理。系统方案保留当前原十项基础，用专业修正叠加方向，仅在主动应用时载入。个人库不随设备配置替换。局部窗口、试听、A/B 快照、匹配和测量结果不保存。"))
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
        val options = ToneAdvancedSystemPresets.entries(current())
        track(MaterialAlertDialogBuilder(context).setTitle("系统声音方案")
            .setItems(options.map { it.first + "\n" + summary(it.second) }.toTypedArray()) { _, index ->
                attempt { apply(options[index].second); dismiss() }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun summary(settings: ToneAdvancedParameters.Settings): String {
        val foundation = "原范围 ±${settings.baseRange} · 原十项保留"
        if (!settings.professional) return "$foundation · 专业调节关闭"
        val strength = if (settings.coreOn) String.format(Locale.ROOT, "精调 %.1f%%", settings.amount / 10.0) else "原十项"
        val blend = if (settings.mixOn) String.format(Locale.ROOT, "效果 %.1f%%", settings.mix / 10.0) else "完整处理声"
        return "$foundation · $strength · $blend"
    }

    private fun latest(id: String) = store.load().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")
    private fun actions(preset: ToneAdvancedPreset) {
        track(MaterialAlertDialogBuilder(context).setTitle(preset.name)
            .setItems(arrayOf("应用", "重命名", "用当前参数覆盖", "导出此预设", "删除")) { _, index ->
                attempt { when (index) {
                    0 -> { apply(latest(preset.id).settings); dismiss() }
                    1 -> nameDialog(latest(preset.id))
                    2 -> { requireSavable(); confirm("用当前参数覆盖“${preset.name}”？") {
                        requireSavable(); store.overwrite(preset.id, current()); refresh()
                    } }
                    3 -> requestExport(latest(preset.id).id)
                    4 -> confirm("删除“${preset.name}”？") { store.delete(preset.id); refresh() }
                } }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun nameDialog(preset: ToneAdvancedPreset?) {
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
                        requireSavable(); store.save(name, current())
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
