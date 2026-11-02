package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale
import kotlin.math.log10

/** Professional mode keeps every parameter visible and disables inactive mode controls. */
class HighFrequencyEditor(private val context: Context, private val group: SwitchPreferenceGroup,
    private val spectrum: Boolean) {
    private val preferences = HighFrequencyController.preferences(context)
    private val status = HighFrequencyController.statusPreferences(context)
    private val rows = linkedMapOf<String, BassNumericPreference>()
    private var previousSupport: Boolean? = null
    private var previousRate: Int? = null
    private var previousClarityMode: Int? = null
    private val help = info("high_frequency_help", 1000)
    private val actual = info("high_frequency_actual", 1010)
    private lateinit var advanced: HighFrequencyAdvancedEditor
    private val delayHeading = info("high_frequency_delays", 65).apply {
        title = "高级延时"
        summary = "每步 1 个采样，同时显示对应毫秒；需要已配置且唯一的采样率。"
    }
    private val driver = info("high_frequency_driver", 1020).apply {
        title = "专业参数需要新版驱动"
        summary = "基础强度与激励起始频率仍可调；专业参数需要更新配套驱动。"
    }
    private val automatic = SwitchPreferenceCompat(context).apply {
        key = HighFrequencyParameters.LP_AUTO
        title = "输出截止跟随采样率"
        summary = "自动使用奈奎斯特频率减 2000 Hz；关闭后可手动设置。"
        setDefaultValue(true)
        isIconSpaceReserved = false
    }
    private val audition = SwitchPreferenceCompat(context).apply {
        key = "high_frequency_audition"
        title = "仅试听激励支路"
        summary = "临时聆听附加高频；离开卡片或关闭音效即恢复正常，不写入预设。"
        isPersistent = false
        isIconSpaceReserved = false
        setOnPreferenceChangeListener { _, value ->
            if (value == true) HighFrequencyController.startAudition(context, this@HighFrequencyEditor)
            else { HighFrequencyController.stopAudition(context, this@HighFrequencyEditor); true }
        }
    }
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "v_clarity_mode", HighFrequencyParameters.LP_AUTO, "capabilities", "session" -> renderFrame.request()
            "v_spectrum" -> {
                if (!preferences.getBoolean("v_spectrum", false)) stopAudition()
                renderFrame.request(false)
            }
            "v_clarity", HighFrequencyAdvancedParameters.NATURAL_ENABLED, HighFrequencyAdvancedParameters.DYNAMIC_ENABLED,
            HighFrequencyAdvancedParameters.DEESS_ENABLED, HighFrequencyAdvancedParameters.OVERSAMPLING -> renderFrame.request(false)
            HighFrequencyParameters.SPECTRUM_AMOUNT -> renderFrame.request(false)
            HighFrequencyParameters.CLARITY_AMOUNT -> {
                if ((preferences.all["v_clarity_mode"] as? Number)?.toInt() == 1) renderFrame.request(false)
            }
            HighFrequencyParameters.lowDelay.key, HighFrequencyParameters.midDelay.key ->
                if (!spectrum) renderFrame.request(false)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!runtimeStatusVisible(this@HighFrequencyEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
            if (!spectrum && intent?.action == me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) {
                // File-based profiles can replace the store without individual key callbacks.
                HighFrequencyController.preferences(this@HighFrequencyEditor.context)
                me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSynchronizer.refresh(group,
                    finishInteractions = true)
                renderFrame.request()
                return
            }
            if (previousRate != HighFrequencyController.singleRate) renderFrame.request()
            else renderFrame.request(false)
        }
    }

    init {
        HighFrequencyParameters.migrate(preferences)
        if (spectrum) HighFrequencyController.stopAudition(context)
        val controls = if (spectrum) HighFrequencyParameters.spectrum else HighFrequencyParameters.clarity
        controls.filter { it != HighFrequencyParameters.lowDelay && it != HighFrequencyParameters.midDelay }.forEach { definition ->
            rows[definition.key] = BassNumericPreference(context, definition, HighFrequencyParameters.value(preferences.all, definition))
        }
        advanced = HighFrequencyAdvancedEditor(context, group, spectrum)
        group.onExpandedChanged = { expanded -> if (!expanded) stopAudition() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver, IntentFilter(HighFrequencyController.STATUS_ACTION).apply {
            if (!spectrum) addAction(me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED)
        })
        render()
    }

    private fun info(key: String, order: Int) = RuntimeStatusPreference(context).apply {
        this.key = key; this.order = order; isSelectable = false; isIconSpaceReserved = false
    }

    private fun replaceRow(definition: BassParameters.Control, row: BassNumericPreference) {
        rows[definition.key]?.let { group.removePreference(it) }
        rows[definition.key] = row
    }

    private fun rebuildPrecisionRows(supported: Boolean, rate: Int?) {
        if (spectrum && previousSupport != supported) {
            val definition = HighFrequencyParameters.spectrumAmount
            val row = if (supported) BassNumericPreference(context, definition, HighFrequencyParameters.value(preferences.all, definition))
            else BassNumericPreference(context, definition.copy(max = 560, default = 56, divisor = 1, decimals = 0),
                HighFrequencyParameters.legacySpectrumAmount(preferences.all)).apply {
                isPersistent = false
                setOnPreferenceChangeListener { _, candidate ->
                    preferences.edit().putInt(definition.key, (candidate as Int) * 10).apply(); true
                }
            }
            replaceRow(definition, row)
        }
        if (!spectrum && (previousRate != rate || previousSupport == null)) {
            listOf(HighFrequencyParameters.lowDelay, HighFrequencyParameters.midDelay).forEach { definition ->
                val descriptor = if (rate != null) definition.copy(min = 0,
                    max = HighFrequencyParameters.delaySamples(definition.max, rate),
                    default = HighFrequencyParameters.delaySamples(definition.default, rate),
                    title = definition.title + "（样本）", divisor = 1, unit = "样本", decimals = 0) else definition
                val initial = if (rate != null) HighFrequencyParameters.delaySamples(HighFrequencyParameters.value(preferences.all, definition), rate)
                    else HighFrequencyParameters.value(preferences.all, definition)
                val row = BassNumericPreference(context, descriptor, initial).apply {
                    isPersistent = false
                    valueFormatter = { samples -> if (rate == null) "等待采样率" else
                        "$samples 样本\n${String.format(Locale.ROOT, "%.3f", samples * 1000.0 / rate)} ms" }
                    rejectedValueMessage = "请输入当前采样率允许的整数样本数"
                    setOnPreferenceChangeListener { _, candidate ->
                        if (rate == null) false else {
                            val samples = candidate as Int
                            val us = if (samples == descriptor.default) definition.default
                                else HighFrequencyParameters.delayMicroseconds(samples, rate)
                            preferences.edit().putInt(definition.key, us.coerceIn(definition.min, definition.max)).apply()
                            true
                        }
                    }
                    resetAction = {
                        if (!isEnabled || rate == null) false else {
                            preferences.edit().putInt(definition.key, definition.default).apply()
                            value = descriptor.default
                            true
                        }
                    }
                }
                replaceRow(definition, row)
            }
        }
        previousSupport = supported
        previousRate = rate
    }

    private fun render() {
        val supported = HighFrequencyController.supported(context)
        val rate = HighFrequencyController.singleRate
        rebuildPrecisionRows(supported, rate)
        val p = HighFrequencyParameters
        val mode = (preferences.all["v_clarity_mode"] as? Number)?.toInt()?.coerceIn(0, 2) ?: 0
        if (!spectrum) previousClarityMode = mode
        val controls = if (spectrum) listOf(p.spectrumAmount, p.spectrumFrequency, p.highpassQ) +
            (if (p.automaticLowpass(preferences.all)) emptyList() else listOf(p.lowpassFrequency)) + listOf(p.lowpassQ, p.spectrumOutput)
        else listOf(p.clarityAmount) + when (mode) {
            1 -> listOf(p.shelfFrequency, p.shelfSlope)
            2 -> listOf(p.lowFrequency, p.highFrequency, p.lowTrim, p.midTrim, p.highTrim, p.lowDelay, p.midDelay)
            else -> emptyList()
        } + listOf(p.clarityOutput)
        val keys = controls.map { it.key }.toSet()
        val displayed = if (group.isProfessionalMode) {
            if (spectrum) listOf(p.spectrumAmount, p.spectrumFrequency, p.highpassQ,
                p.lowpassFrequency, p.lowpassQ, p.spectrumOutput)
            else listOf(p.clarityAmount, p.shelfFrequency, p.shelfSlope,
                p.lowFrequency, p.highFrequency, p.lowTrim, p.midTrim, p.highTrim,
                p.lowDelay, p.midDelay, p.clarityOutput)
        } else controls
        if (!group.isProfessionalMode)
            rows.filterKeys { it !in keys }.values.forEach { group.removePreference(it) }
        displayed.forEachIndexed { index, definition ->
            val row = rows.getValue(definition.key)
            row.order = (index + 1) * 10
            row.isEnabled = definition.key in keys && (!definition.advanced || supported) &&
                ((definition != p.lowDelay && definition != p.midDelay) || rate != null)
            add(row)
        }
        if (!spectrum && mode == 2) add(delayHeading) else group.removePreference(delayHeading)
        if (spectrum) {
            automatic.order = 35; automatic.isEnabled = supported; add(automatic)
            audition.order = 180; add(audition)
            help.summary = "从已有高频生成谐波后加回原声，并非还原丢失的原始频谱。强度控制附加支路；输出衰减作用于该效果输出。"
        } else help.summary = when (mode) {
            1 -> "Ozone 使用高架滤波。增强系数不是 dB；斜率 S 越大，过渡越陡。"
            2 -> "XHiFi 按三段处理。增益修正 0 dB 保留原模式形状；延时按当前采样率的整数样本调节，复位保留原 5 / 2.5 ms 目标。"
            else -> "自然模式通过相邻采样差分增强细节；增强系数不是 dB。参数编辑与卡片展开不会开启音效。"
        }
        add(help); add(actual)
        if (!supported) add(driver) else group.removePreference(driver)
        advanced.render(mode)
        // Professional pages show these runtime descriptions with their pinned charts.
        // Keep all original controls, capability warnings and home-card descriptions intact.
        listOf("high_frequency_help", "high_frequency_actual", "high_frequency_advanced_actual",
            "high_frequency_dynamics_help").forEach { key ->
            group.findPreference<Preference>(key)?.isVisible = !group.isProfessionalMode
        }
        renderStatus()
    }

    private fun add(preference: Preference) {
        if (group.findPreference<Preference>(preference.key) == null) group.addPreference(preference)
    }

    /** External preset/control state updates presentation only, never create another edit. */
    private fun showChecked(preference: SwitchPreferenceCompat, checked: Boolean) {
        if (preference.isChecked == checked) return
        val persistent = preference.isPersistent
        preference.isPersistent = false
        try {
            preference.isChecked = checked
        } finally {
            preference.isPersistent = persistent
        }
    }

    private fun renderStatus() {
        if (!spectrum) {
            val values = preferences.all
            val mode = (values["v_clarity_mode"] as? Number)?.toInt()?.coerceIn(0, 2) ?: 0
            group.findPreference<ViperSliderPreference>("v_clarity_mode")?.syncFromPreferences()
            if (previousClarityMode != mode) renderFrame.request()
            // These two rows intentionally have no direct persistence: the original DSP key
            // stores microseconds, while the view edits integer audio samples at the active rate.
            listOf(HighFrequencyParameters.lowDelay, HighFrequencyParameters.midDelay).forEach { definition ->
                val raw = HighFrequencyParameters.value(values, definition)
                val rate = previousRate
                val next = if (rate == null) raw else HighFrequencyParameters.delaySamples(raw, rate)
                rows[definition.key]?.takeIf { it.value != next }?.value = next
            }
        }
        advanced.renderStatus((preferences.all["v_clarity_mode"] as? Number)?.toInt() ?: 0)
        val readings = HighFrequencyController.runtime
        val supported = HighFrequencyController.supported(context)
        audition.isEnabled = supported && HighFrequencyController.masterEnabled && preferences.getBoolean("v_spectrum", false)
        showChecked(audition, HighFrequencyController.audition)
        if (spectrum) {
            val automaticLowpass = HighFrequencyParameters.automaticLowpass(preferences.all)
            if (automatic.isChecked != automaticLowpass) {
                showChecked(automatic, automaticLowpass)
                // File-based preset replacement can refresh the shared store without a key
                // callback; update the dependent manual cutoff row on the next display frame.
                renderFrame.request()
            }
        }
        actual.title = "当前配置的实际目标"
        actual.summary = when {
            !supported && spectrum -> "当前驱动仅支持整数百分比：实际 ${HighFrequencyParameters.legacySpectrumAmount(preferences.all)}%；已保存 ${HighFrequencyParameters.spectrumAmount.label(HighFrequencyParameters.value(preferences.all, HighFrequencyParameters.spectrumAmount))}。专业精调需更新驱动。"
            !supported -> "当前驱动支持 0.01 增强系数；频率、分段与输出衰减需更新驱动。"
            readings.isEmpty() -> "尚未取得有效采样率。配置音频流后显示实际截止与延时；延时精调需单一采样率。"
            else -> readings.distinctBy { it.rate to it.targets }.joinToString("\n") { reading ->
                val t = reading.targets
                if (spectrum) "${reading.rate} Hz：输入高通 ${t[5]} Hz，输出低通 ${t[6]} Hz"
                else when ((preferences.all["v_clarity_mode"] as? Number)?.toInt() ?: 0) {
                    1 -> "${reading.rate} Hz：高架 ${t[0]} Hz，平台增益约 ${String.format(Locale.ROOT, "%.2f",
                        20.0 * log10(1.0 + HighFrequencyParameters.value(preferences.all, HighFrequencyParameters.clarityAmount) / 100.0))} dB（输出衰减前）"
                    2 -> "${reading.rate} Hz：分界 ${t[1]} / ${t[2]} Hz；低频 ${t[3]} 样本（${milliseconds(t[3], reading.rate)} ms），中频 ${t[4]} 样本（${milliseconds(t[4], reading.rate)} ms）"
                    else -> "${reading.rate} Hz：自然模式"
                }
            } + if (HighFrequencyController.singleRate == null) "\n多个采样率：延时精调暂不可用。" else "\n频率受采样率限制；显示已配置目标，不表示过渡中的瞬时值。"
        }
    }

    private fun milliseconds(samples: Int, rate: Int) = String.format(Locale.ROOT, "%.3f", samples * 1000.0 / rate)
    fun refreshFromPreferences() {
        if (!spectrum) {
            HighFrequencyController.preferences(context)
            renderFrame.request()
        }
    }
    fun stopAudition() { if (spectrum) HighFrequencyController.stopAudition(context, this) }
    fun close() { renderFrame.close();
        stopAudition()
        group.onExpandedChanged = null
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        status.unregisterOnSharedPreferenceChangeListener(listener)
        context.unregisterLocalReceiver(receiver)
    }
}
