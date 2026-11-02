package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import java.util.Locale

/** Independent switches select new processing without changing either parent effect switch. */
class HighFrequencyAdvancedEditor(private val context: Context, private val group: SwitchPreferenceGroup,
    private val spectrum: Boolean) {
    private val p = HighFrequencyAdvancedParameters
    private val preferences = HighFrequencyController.preferences(context)
    private val rows = (if (spectrum) p.nonlinear else p.clarity).associate { control ->
        control.key to BassNumericPreference(context, control, p.value(preferences.all, control)).apply {
            if (p.frequencyPairs.any { control == it.first || control == it.second }) {
                rejectedValueMessage = "频带下限必须小于上限；请先调整另一端频率"
                setOnPreferenceChangeListener { _, candidate -> p.acceptsFrequency(preferences.all, control.key, candidate as Int) }
                rejectedButtonFeedback = {
                    val message = if (p.frequencyPairs.any { control == it.first })
                        "下限必须小于上限；请先调高上限，再增加或复位下限"
                    else "上限必须大于下限；请先调低下限，再减少或复位上限"
                    Toast.makeText(context,message,Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun toggle(key: String, title: String, summary: String, order: Int) = SwitchPreferenceCompat(context).apply {
        this.key = key; this.title = title; this.summary = summary; this.order = order
        isIconSpaceReserved = false; setDefaultValue(false)
    }
    private val natural = toggle(p.NATURAL_ENABLED, "自然模式选频锐化",
        "仅在设定频带增强细节；关闭保留原自然模式。", 100)
    private val dynamic = toggle(p.DYNAMIC_ENABLED, "动态高频抑制",
        "按高频峰值自动衰减；左右声道联动，关闭时仍可编辑参数。", 200)
    private val deess = toggle(p.DEESS_ENABLED, "去齿音",
        "对指定频带进行动态抑制；并非人声识别，左右声道联动。", 400)
    private val nonlinear = info("high_frequency_nonlinear", 100).apply {
        title = "非线性谐波设置"
        summary = "配比控制 Chebyshev 谐波生成项，不是输出谐波的实测幅度。正输入驱动含软饱和；默认配比、0 dB 与 1× 保留原非线性处理。"
    }
    private val oversampling = HighFrequencyOversamplingPreference(context).apply { order = 140 }
    private val oversamplingHelp = info("high_frequency_oversampling_help", 150).apply {
        summary = "2× / 4× 减少非线性混叠，增加运算量和 32 个宿主采样延时；原声支路同步补偿。1× 无新增延时。"
    }
    private val dynamicsHelp = info("high_frequency_dynamics_help", 500).apply {
        summary = "阈值按所选频带的双声道最大绝对峰值（dBFS）检测，启动 / 恢复控制检测包络。高频抑制使用负增益高架，去齿音使用负增益峰值均衡；最大衰减分别指高频平台和齿音频带中心。两项位于清晰度末级、输出衰减之前。"
    }
    private val driver = info("high_frequency_advanced_driver", 990).apply {
        title = "新增处理需要第二轮配套驱动"
        summary = "当前驱动继续支持原有参数；选频锐化、谐波配比、过采样和动态处理需更新驱动后使用。"
    }
    private val actual = info("high_frequency_advanced_actual", 980)

    private fun info(key: String, order: Int) = RuntimeStatusPreference(context).apply {
        this.key = key; this.order = order; isSelectable = false; isIconSpaceReserved = false
    }
    private fun add(preference: Preference) {
        if (group.findPreference<Preference>(preference.key) == null) group.addPreference(preference)
    }
    private fun controls(controls: List<BassParameters.Control>, order: Int, supported: Boolean) {
        controls.forEachIndexed { index, control -> rows.getValue(control.key).apply {
            this.order = order + index; isEnabled = supported; add(this)
        } }
    }
    fun render(mode: Int) {
        val supported = HighFrequencyController.advancedSupported(context)
        if (spectrum) {
            add(nonlinear); controls(p.nonlinear, 110, supported)
            oversampling.isEnabled = supported; add(oversampling); add(oversamplingHelp)
        } else {
            if (mode == 0 || group.isProfessionalMode) {
                natural.isEnabled = supported && mode == 0; add(natural)
                controls(p.natural, 110, supported && mode == 0)
            } else {
                group.removePreference(natural)
                p.natural.forEach { group.removePreference(rows.getValue(it.key)) }
            }
            dynamic.isEnabled = supported; add(dynamic); controls(p.dynamic, 210, supported)
            deess.isEnabled = supported; add(deess); controls(p.deess, 410, supported)
            add(dynamicsHelp)
        }
        if (!supported) add(driver) else group.removePreference(driver)
        add(actual)
        renderStatus(mode)
    }
    private fun band(low: Int, high: Int): String =
        if (low <= 0 || high <= low) "当前采样率下无有效频带" else "$low–$high Hz"
    private fun reduction(reading: HighFrequencyController.Reading, enabled: Boolean, value: Int): String = when {
        !enabled -> "关闭"
        !reading.enabled -> "主开关关闭"
        reading.processing == OutputRuntimeStatus.Processing.UNKNOWN -> "等待读取处理状态"
        reading.processing == OutputRuntimeStatus.Processing.IDLE -> "当前未处理音频"
        else -> "${String.format(Locale.ROOT, "%.2f", value / 100.0)} dB"
    }
    fun renderStatus(mode: Int) {
        actual.title = "新增处理状态"
        val values = preferences.all
        if (!spectrum) {
            listOf(natural, dynamic, deess).forEach { toggle ->
                val checked = p.enabled(values, toggle.key)
                if (toggle.isChecked != checked) {
                    // Rendering an imported preset must never publish an edit back into it.
                    val persistent = toggle.isPersistent
                    toggle.isPersistent = false
                    try { toggle.isChecked = checked } finally { toggle.isPersistent = persistent }
                }
            }
        }
        val readings = HighFrequencyController.runtime
        actual.summary = when {
            !HighFrequencyController.advancedSupported(context) -> "当前驱动不支持新增处理，相关参数不会发送。"
            readings.isEmpty() -> "等待音频流配置；有效频带和过采样状态以驱动返回值为准。"
            else -> readings.distinctBy { listOf(it.rate, it.advancedTargets, it.processing, it.enabled) }.joinToString("\n") { reading ->
                val t = reading.advancedTargets
                if (t.size != 9) "${reading.rate} Hz：等待新增状态"
                else if (spectrum) {
                    val request = p.oversampling(values)
                    val state = when {
                        !preferences.getBoolean("v_spectrum", false) -> "音效关闭"
                        !reading.enabled -> "主开关关闭"
                        reading.processing != OutputRuntimeStatus.Processing.ACTIVE -> "等待音频处理"
                        t[5] == 0 -> "尚未应用"
                        else -> "实际 ${t[5]}×，新增 ${t[6]} 样本（${String.format(Locale.ROOT, "%.3f", t[6] * 1000.0 / reading.rate)} ms）"
                    }
                    "${reading.rate} Hz：设置 ${request}×；$state"
                } else {
                    val module = preferences.getBoolean("v_clarity", false)
                    val naturalState = if (mode == 0) "锐化 ${band(t[0], t[1])}" +
                        (if (p.enabled(values, p.NATURAL_ENABLED)) "" else "（关闭）") + "；" else ""
                    val dynamicReduction = reduction(reading, module && p.enabled(values, p.DYNAMIC_ENABLED), t[7])
                    val deessReduction = reduction(reading, module && p.enabled(values, p.DEESS_ENABLED), t[8])
                    "${reading.rate} Hz：${naturalState}高频抑制起点 ${t[2]} Hz，衰减 $dynamicReduction；" +
                        "去齿音 ${band(t[3], t[4])}，衰减 $deessReduction"
                }
            }
        }
    }
}
