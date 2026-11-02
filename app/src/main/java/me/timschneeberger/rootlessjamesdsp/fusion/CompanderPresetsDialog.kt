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
import kotlin.math.roundToInt

/** Complete directions are applied only by user choice; none changes effect power. */
object CompanderSystemPresets {
    private val foundations = setOf("compander_timeconstant", "compander_granularity",
        "compander_tftransforms", "compander_response")
    /** Factory directions preserve the user's saved foundation, including its original Float types. */
    fun foundation(current: CompanderParameters.Settings): CompanderParameters.Settings =
        CompanderParameters.capture(CompanderParameters.defaults().values() +
            current.values().filterKeys { it in foundations })
    private fun corrections(base: CompanderParameters.Settings, targets: List<Int>): List<Int> {
        val coefficients = base.legacyCurve.removeSuffix(";").split(';').drop(7).map(String::toDouble)
        return targets.mapIndexed { index, target -> (target - coefficients[index] * 1000).roundToInt() }
    }
    fun entries(current: CompanderParameters.Settings): List<Pair<String, CompanderParameters.Settings>> {
        val base = foundation(current)
        val legacyGentle = base.copy(professional = true, coreOn = true,
            coefficientDeltas = corrections(base, List(7) { -250 }),
            limitsOn = true, maxBoost = 0, maxCut = 60,
            timeOn = true, attack = 100, hold = 0, release = 300,
            detectOn = true, relative = 10, knee = 30, weakFloor = -800, linked = 1,
            mixOn = true, mix = 800, outputOn = true, output = 0)
        val legacyDrum = base.copy(professional = true, coreOn = true, amount = 750,
            coefficientDeltas = corrections(base, listOf(150,350,250,100,50,50,100)),
            limitsOn = true, maxBoost = 40, maxCut = 60,
            timeOn = true, attack = 20, hold = 8, release = 140,
            detectOn = true, relative = 10, knee = 20, weakFloor = -700, linked = 1,
            rangeLow = 40, rangeHigh = 12000, mixOn = true, mix = 700,
            outputOn = true, output = -20)
        val standardGentle = base.copy(professional = true, engine = 1, coreOn = true,
            type = 0, threshold = -180, ratio = 200, standardKnee = 60, detector = 1, rmsWindow = 20,
            limitsOn = true, maxBoost = 0, maxCut = 120,
            timeOn = true, attack = 100, hold = 0, release = 250,
            detectOn = true, weakFloor = -800, linked = 1,
            sidechainOn = true, sideHP = 80,
            lookaheadOn = true, lookahead = 50,
            mixOn = true, mix = 1000, outputOn = true, output = 0)
        val upward = base.copy(professional = true, engine = 1, coreOn = true,
            type = 1, threshold = -300, ratio = 200, standardKnee = 60, detector = 1, rmsWindow = 20,
            limitsOn = true, maxBoost = 60, maxCut = 0,
            timeOn = true, attack = 200, hold = 30, release = 350,
            detectOn = true, weakFloor = -700, linked = 1,
            mixOn = true, mix = 1000, outputOn = true, output = -30)
        val expansion = base.copy(professional = true, engine = 1, coreOn = true,
            type = 2, threshold = -400, ratio = 200, standardKnee = 60, detector = 1, rmsWindow = 20,
            limitsOn = true, maxBoost = 0, maxCut = 180,
            timeOn = true, attack = 50, hold = 50, release = 150,
            detectOn = false, weakFloor = -1000, linked = 1,
            sidechainOn = true, sideHP = 80,
            mixOn = true, mix = 1000, outputOn = true, output = 0)
        val gate = base.copy(professional = true, engine = 1, coreOn = true,
            type = 3, threshold = -450, ratio = 200, standardKnee = 0, detector = 0,
            limitsOn = true, maxBoost = 0, maxCut = 480,
            timeOn = true, attack = 10, hold = 80, release = 200,
            detectOn = false, weakFloor = -1000, linked = 1,
            sidechainOn = true, sideHP = 80,
            lookaheadOn = true, lookahead = 30, hysteresis = 40,
            mixOn = true, mix = 1000, outputOn = true, output = 0)
        return listOf("原版兼容 · 保留当前基础" to base,
            "柔和瞬态压制 · 相对曲线" to legacyGentle,
            "鼓点增强 · 相对曲线" to legacyDrum,
            "温和压缩 · 固定阈值 2:1" to standardGentle,
            "低电平细节 · 向上压缩" to upward,
            "渐进降噪 · 向下扩展" to expansion,
            "噪声门 · 带回差与前瞻" to gate)
    }
}

class CompanderPresetsDialog(
    private val context: Context,
    private val store: CompanderPresetStore,
    private val current: () -> CompanderParameters.Settings,
    private val apply: (CompanderParameters.Settings) -> Unit,
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
            Toast.makeText(context, failure.message ?: "压扩器预设操作失败", Toast.LENGTH_LONG).show()
        }
    }
    private fun requireSavable() {
        require(canSave()) { "原曲线无效，请重新设置或应用有效预设后再保存" }
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
        track(MaterialAlertDialogBuilder(context).setTitle("动态压扩器预设")
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton("关闭", null).create()).show()
        attempt { refresh() }
    }
    fun refresh() {
        val list = content ?: return; list.removeAllViews()
        list.addView(text("保存当前七点曲线、原时间常数、粒度、算法模式、全部专业参数和分组开关，不改变模块电源；与动态低频塑形及其他模块分别管理。系统方案在当前基础上建立，仅在主动应用时载入。个人库不随设备配置替换。试听、A/B 快照、匹配和测量结果不保存。"))
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
        val options = CompanderSystemPresets.entries(current())
        track(MaterialAlertDialogBuilder(context).setTitle("系统声音方案")
            .setItems(options.map { it.first + "\n" + summary(it.second) }.toTypedArray()) { _, index ->
                attempt { apply(options[index].second); dismiss() }
            }.setNegativeButton("取消", null).create()).show()
    }
    private fun summary(settings: CompanderParameters.Settings): String {
        val levels = listOf("极低", "低", "中", "高", "极高")
        val legacy = listOf("短时傅里叶", "连续小波", "欠采样框架", "时域")
        val foundation = "${legacy[settings.legacyTransform.toInt()]} · 粒度${levels[settings.legacyGranularity.toInt()]}"
        if (!settings.professional) return "$foundation · 专业调节关闭"
        val blend = if (settings.mixOn) String.format(Locale.ROOT, "效果 %.1f%%", settings.mix / 10.0) else "完整处理声"
        if (settings.engine == 0) return "$foundation · 相对曲线 · $blend"
        val type = listOf("压缩", "向上压缩", "向下扩展", "噪声门")[settings.type]
        val threshold = String.format(Locale.ROOT, "%.1f dBFS", settings.threshold / 10.0)
        val ratio = if (settings.type == 3) "" else String.format(Locale.ROOT, " · %.2f:1", settings.ratio / 100.0)
        return "$type · $threshold$ratio · $blend"
    }

    private fun latest(id: String) = store.load().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("此预设已不存在，请刷新列表")
    private fun actions(preset: CompanderPreset) {
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
    private fun nameDialog(preset: CompanderPreset?) {
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
