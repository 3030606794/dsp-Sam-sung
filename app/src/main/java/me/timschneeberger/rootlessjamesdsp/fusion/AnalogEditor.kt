package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale

class AnalogEditor(private val context: Context, private val group: SwitchPreferenceGroup, host: Fragment) {
    private val p = AnalogParameters
    private val c = AnalogController
    private val prefs = c.preferences(context)
    private val status = c.statusPreferences(context)
    private val store = AnalogPresetStore(context)
    private val rows = linkedMapOf<String, BassNumericPreference>()
    private var rowFlags: List<Boolean>? = null
    private var pendingExport: String? = null
    private var closed = false
    private var library: AnalogPresetsDialog? = null
    private val importLauncher = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !closed) attempt {
            val json = context.contentResolver.openInputStream(uri)?.use(AnalogPresetCodec::read) ?: error("无法读取预设文件")
            val count = store.importJson(json); library?.refresh(); toast("已导入 $count 项谐波染色预设")
        }
    }
    private val exportLauncher = host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExport; pendingExport = null
        if (uri != null && json != null && !closed) attempt {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("无法写入预设文件")
            toast("谐波染色预设已导出")
        }
    }
    private fun info(key: String, title: String, order: Int) = object : RuntimeStatusPreference(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            for (id in listOf(android.R.id.title, android.R.id.summary)) (holder.findViewById(id) as? TextView)?.apply {
                setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null
            }
        }
    }.apply { this.key = key; this.title = title; this.order = order; isIconSpaceReserved = false; isSelectable = false }
    private fun action(key: String, title: String, order: Int) = info(key, title, order).apply { isSelectable = true }
    private fun toggle(key: String, title: String, description: String, order: Int) = object : SwitchPreferenceCompat(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            for (id in listOf(android.R.id.title, android.R.id.summary)) (holder.findViewById(id) as? TextView)?.apply {
                setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null
            }
        }
    }.apply { this.key = key; this.title = title; summary = description; this.order = order; isIconSpaceReserved = false; setDefaultValue(false) }
    private val professional = toggle(p.PROFESSIONAL, "专业调节", "默认关闭。原三档始终可选，精调叠加于当前档位；切档保留精调，关闭专业模式恢复当前档原声。", 10).apply {
        isPersistent = false
        setOnPreferenceChangeListener { _, next -> stopPlayback(); p.setProfessional(prefs, next as Boolean); true }
    }
    private val baseline = info("v_analog_pro_baseline", "当前声音基础", 11)
    private val core = toggle(p.CORE_ON, "启用核心谐波精调", "默认关闭。修正输入驱动、染色注入量及二至五次谐波系数；系数不是音乐中真实谐波的百分比。", 20)
    private val advancedHarmonics = info("v_analog_pro_advanced_harmonics", "高级谐波：四次与五次", 25).apply {
        summary = "以下两项细调更高阶染色，与二次、三次共同作用；单项复位恢复本档原系数。"
    }
    private val tone = toggle(p.TONE_ON, "启用频段与音色", "默认关闭。调节染色前低切、高切偏移及 Q、原音色补偿；高切以当前三档原频率为基础，复位偏移至 0 Hz。", 40)
    private val mix = toggle(p.MIX_ON, "启用干湿混合", "默认关闭。0% 为本模块原声，100% 为处理后声音；采用线性混合，原声与效果按实际延迟对齐。", 60)
    private val output = toggle(p.OUTPUT_ON, "启用输出增益", "默认关闭。在原模块固定电平基础上进一步衰减，0 dB 保留原有输出电平。", 70)
    private val quality = toggle(p.QUALITY_ON, "启用抗混叠过采样", "默认关闭。原三档已有非线性染色，可独立使用 2×／4× 过采样；增加处理量与少量延迟。", 80)
    private val factor = FieldChoicePreference(context, p.FACTOR, "抗混叠过采样倍率", listOf("2×", "4×"), "恢复 2×").apply {
        order = 81; setOnPreferenceChangeListener { _, next ->
            val values = prefs.all
            if (closed || !p.enabled(values) || !p.on(values, p.QUALITY_ON)) false
            else { prefs.edit().putInt(p.FACTOR, if (next as Int == 0) 2 else 4).apply(); true }
        }
    }
    private val meters = toggle(p.METERS, "显示实时电平", "默认关闭。显示本模块输入／输出采样峰值与 RMS，以及输出超限帧数；关闭再开启可清零。", 90)
    private val toggles = listOf(core, tone, mix, output, quality, meters)
    private val help = info("v_analog_pro_help", "谐波染色调节说明", 100).apply {
        summary = "轻度、中度、强三档是声音基础，始终可选择。专业精调叠加于所选档位，切档保留相对修正；全部新增处理组关闭时保留当前档原声。关闭分组置灰并保留数值，开启后恢复；单项复位回当前档基础值，不改变其他项目。谐波数值是整形系数偏移：原二至五次系数依次为 0.020000、0.000100、0.001000、0.000000，不代表音乐中实际谐波占比。输出 0 dB 保留原模块固有电平。低采样率时滤波频率自动限制在有效范围，实际值见下方。与电子管柔化、分频染色独立。"
    }
    private val presets = action("v_analog_pro_presets", "谐波染色独立预设", 110).apply {
        summary = "原三档与 3 种专业方案 · 个人 20 项 · 导入／导出"
        setOnPreferenceClickListener {
            stopPlayback(); library?.dismiss()
            library = AnalogPresetsDialog(context, store, { p.capture(prefs.all) }, { settings ->
                stopPlayback(); invalidateRows(); p.apply(prefs, settings); render()
            }, { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, { id ->
                pendingExport = store.export(id); exportLauncher.launch("谐波染色预设.json")
            }).also { it.show() }; true
        }
    }
    private fun save(slot: Int) = action(if (slot == 0) "v_analog_pro_save_a" else "v_analog_pro_save_b",
        if (slot == 0) "保存试听快照 A" else "保存试听快照 B", 120 + slot).apply {
        setOnPreferenceClickListener { if (!c.saveComparison(context, this@AnalogEditor, slot)) toast("请先连接配套驱动"); renderStatus(); true }
    }
    private val saveA = save(0)
    private val saveB = save(1)
    private val audition = FieldChoicePreference(context, "v_analog_pro_audition", "临时试听",
        listOf("正常处理", "本模块原声", "仅处理后效果", "效果差分", "快照 A", "快照 B", "测量 A/B 电平"), "恢复正常").apply {
        order = 130
        setOnPreferenceChangeListener { _, next -> c.startAudition(context, this@AnalogEditor, next as Int).also {
            if (!it) toast("试听需要活动音频；A/B 和测量需先保存两个快照")
        } }
    }
    private val measure = action("v_analog_pro_measure", "测量／重新测量 A/B 电平", 140).apply {
        summary = "同一输入同时处理两套快照，滤波与延迟状态预热后测量 2 秒。"
        setOnPreferenceClickListener { if (!c.measure(context, this@AnalogEditor)) toast("请先保存 A/B 快照，并播放经过 DSP 的音乐"); true }
    }
    private val match = toggle("v_analog_pro_match", "匹配 A/B 电平", "先测量同一输入的 A/B RMS，再固定衰减；不持续追踪、不提升电平。", 150).apply {
        isPersistent = false
        setOnPreferenceChangeListener { _, next -> c.setMatch(context, next as Boolean).also { if (!it) toast("请先完成 A/B 电平测量") } }
    }
    private val stop = action("v_analog_pro_stop", "停止试听／恢复保存设置", 160).apply {
        setOnPreferenceClickListener { stopAudition(); true }
    }
    private val actual = StableRuntimeReadoutPreference(context).apply {
        key = "v_analog_pro_actual"; title = "试听与电平状态"; order = 170
    }
    private val compatibility = info("v_analog_pro_compatibility", "驱动支持", 180)
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
        if (closed) return@OnSharedPreferenceChangeListener
        if (source === prefs && key != p.METERS) stopPlayback()
        if (source === prefs && key == p.BASE) invalidateRows()
        if (source === prefs && p.numbers.any { it.key == key }) {
            rows[key]?.let { row -> val value = p.value(prefs.all, row.control); if (row.value != value) row.value = value }
            renderFrame.request(false)
        } else renderFrame.request()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (!runtimeStatusVisible(this@AnalogEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
            if (intent?.action == Constants.ACTION_PRESET_LOADED) { stopPlayback(); invalidateRows(); render() } else renderFrame.request(false)
        }
    }
    init {
        p.normalize(prefs); c.stopAudition(context, clearSnapshots = true)
        group.onExpandedChanged = { if (!it) stopAudition() }
        prefs.registerOnSharedPreferenceChangeListener(listener); status.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver, IntentFilter(c.STATUS_ACTION).apply { addAction(Constants.ACTION_PRESET_LOADED) })
        render()
    }
    private fun add(row: Preference) { if (group.findPreference<Preference>(row.key) == null) group.addPreference(row) }
    private fun numberEnabled(key: String, v: Map<String, *>) = p.enabled(v) && when (key) {
        in p.coreNumbers.map { it.key } -> p.on(v, p.CORE_ON)
        in p.toneNumbers.map { it.key } -> p.on(v, p.TONE_ON)
        p.mix.key -> p.on(v, p.MIX_ON)
        p.output.key -> p.on(v, p.OUTPUT_ON)
        else -> false
    }
    private fun invalidateRows() {
        rows.values.forEach { it.isEnabled = false; group.removePreference(it) }; rows.clear(); rowFlags = null
    }
    private fun ensureRows() {
        val flags = p.switches.filterNot { it == p.METERS }.map { p.on(prefs.all, it) }
        if (flags == rowFlags) return
        invalidateRows(); rowFlags = flags
        p.numbers.forEach { control ->
            rows[control.key] = BassNumericPreference(context, control, p.value(prefs.all, control)).apply {
                order = when (control.key) {
                    p.h4.key -> 26
                    p.h5.key -> 27
                    in p.coreNumbers.map { it.key } -> 21 + p.coreNumbers.indexOfFirst { it.key == control.key }
                    in p.toneNumbers.map { it.key } -> 41 + p.toneNumbers.indexOfFirst { it.key == control.key }
                    p.mix.key -> 61
                    else -> 71
                }
                rejectedValueMessage = "控制状态已改变，请关闭输入框后重新调节"
                setOnPreferenceChangeListener { _, next -> !closed && numberEnabled(control.key, prefs.all) && p.validValue(control, next as Int) }
            }
        }
    }
    private fun render() {
        if (closed) return
        p.normalize(prefs); ensureRows(); val values = prefs.all; val enabled = p.enabled(values)
        add(professional); professional.isChecked = enabled
        add(baseline)
        val base = p.baseMode(values)
        // Preset/profile loading writes preferences directly; keep the original visible selector in sync.
        group.findPreference<ViperSliderPreference>(p.BASE)?.let { if (it.value != base) it.value = base }
        baseline.summary = "${p.baseNames[base]}档 · 本档原高切 ${p.baseLowpass[base]} Hz\n" +
            if (enabled) "专业精调在此基础上生效；切换三档保留修正量，单项复位回本档基础值。" else "使用本档原版声音；开启专业调节后可叠加精调。"
        rows.forEach { (key, row) ->
            row.isEnabled = numberEnabled(key, values)
            if (row.value != p.value(values, row.control)) row.value = p.value(values, row.control)
            if (enabled || group.isProfessionalMode) add(row) else group.removePreference(row)
        }
        toggles.forEach { row -> row.isChecked = p.on(values, row.key); row.isEnabled = enabled; if (enabled || group.isProfessionalMode) add(row) else group.removePreference(row) }
        quality.isEnabled = enabled
        advancedHarmonics.isEnabled = enabled && p.on(values, p.CORE_ON)
        factor.value = if (p.factor(values) == 2) 0 else 1
        factor.isEnabled = enabled && p.on(values, p.QUALITY_ON)
        if (enabled || group.isProfessionalMode) { add(factor); add(help); add(advancedHarmonics) }
        else { group.removePreference(factor); group.removePreference(help); group.removePreference(advancedHarmonics) }
        for (row in listOf(presets, saveA, saveB, audition, measure, match, stop, actual, compatibility)) add(row)
        renderStatus()
    }
    fun renderStatus() {
        if (closed) return
        val supported = c.supported(context); val enabled = p.enabled(prefs.all)
        saveA.isEnabled = supported; saveB.isEnabled = supported
        val base = p.baseMode(prefs.all)
        val hint = if (enabled) "保存当前${p.baseNames[base]}档与完整专业参数；仅本次页面使用" else "保存原版${p.baseNames[base]}档；仅本次页面使用"
        saveA.summary = if (c.hasComparison(0)) "已保存，仅本次页面使用" else hint
        saveB.summary = if (c.hasComparison(1)) "已保存，仅本次页面使用" else hint
        audition.value = c.audition; audition.isEnabled = c.canListen(context)
        measure.isEnabled = audition.isEnabled && c.hasComparison(0) && c.hasComparison(1)
        match.isChecked = c.match; match.isEnabled = audition.isEnabled && c.matchingReady()
        compatibility.summary = if (supported) "已连接配套谐波染色增强驱动。三档始终可选；关闭专业模式恢复当前档原声，精调值保留。"
            else "当前驱动不支持新增处理。专业设置可编辑并保留，实际仍使用原三档染色；更新配套驱动并重启后生效。"
        val moduleEnabled = p.moduleEnabled(prefs.all)
        val matchingReady = c.matchingReady()
        val streams = c.runtime.map { it as AnalogController.Reading? }.ifEmpty { listOf(null) }
        actual.summary = streams.joinToString("\n\n") { r ->
            val snapshot = r?.snapshot
            // Normal audio has no A/B tool generation; actual audition routes still require their ACK.
            val currentAudio = snapshot?.mode == c.audition && (c.audition == 0 || snapshot?.epoch == c.epoch)
            val valid = supported && moduleEnabled && r != null && r.enabled && r.active &&
                snapshot != null && snapshot.enabled && snapshot.professional == enabled && snapshot.ready &&
                snapshot.data[2] in 8000..768000 && snapshot.data[2] == r.rate && currentAudio
            val v = if (valid) snapshot?.data else null
            val tools = if (valid && snapshot?.epoch == c.epoch) v else null
            val statusText = when {
                !supported -> "等待配套驱动\n—表示暂无有效读数。"
                !moduleEnabled -> "本模块关闭；\n编辑参数不会自动启用"
                r == null || !r.enabled || !r.active -> "当前没有活动处理会话；\n试听状态等待驱动确认"
                v == null -> "等待读数\n—表示暂无有效读数。"
                !enabled -> "原版三档处理；\n开启专业调节后才能试听"
                else -> "当前实际处理状态；\n—表示暂无有效读数。"
            }
            val measurement = tools?.get(7)
            val phase = when (measurement) {
                1 -> "A/B 预热"
                2 -> "A/B 电平测量"
                3 -> if (matchingReady) "测量完成，固定衰减" else "等待当前快照的测量结果"
                4 -> "信号不足，未建立匹配"
                0 -> "未测量"
                else -> "—"
            }
            fun number(index: Int, divisor: Double = 1.0, decimals: Int = 0) =
                StableRuntimeReadoutPreference.fixedNumber(v?.get(index), divisor, decimals)
            fun toolNumber(index: Int) = StableRuntimeReadoutPreference.fixedNumber(tools?.get(index), 1.0, 0)
            fun attenuation(index: Int) = StableRuntimeReadoutPreference.fixedNumber(
                if (measurement == 3 && matchingReady) tools?.get(index) else null, 100.0, 2)
            fun coefficientField(index: Int) = StableRuntimeReadoutPreference.fixedNumber(v?.get(index), 1000000.0, 6, width = 11)
            fun cutoff(index: Int) = v?.get(index)?.let { if (it == 0) "旁路" else "$it Hz" } ?: "—"
            val metersOn = v?.get(12) == 1
            fun meter(index: Int) = StableRuntimeReadoutPreference.fixedNumber(
                if (metersOn) v?.get(index) else null, 100.0, 2)
            val latencyMs = StableRuntimeReadoutPreference.fixedNumber(v?.get(14), (v?.get(2) ?: 1000) / 1000.0, 2)
            val actualBase = v?.get(31)
            val actualBaseName = actualBase?.let { p.baseNames.getOrNull(it) } ?: "—"
            val actualBaseLowpass = StableRuntimeReadoutPreference.fixedNumber(actualBase?.let { p.baseLowpass.getOrNull(it) }, 1.0, 0)
            val listening = when {
                v == null -> "当前试听：—"
                v[4] == 0 -> "原版${actualBaseName}档；专业试听已关闭"
                else -> "当前试听：" + (listOf("正常处理", "原声", "处理后效果", "效果差分", "快照 A", "快照 B", "测量").getOrNull(v[5]) ?: "—")
            }
            "当前保存基础：${p.baseNames[base]}档\n保存的本档原高切 ${p.baseLowpass[base]} Hz\n" +
                "$statusText\n$listening\n测量状态：$phase\n" +
                "A/B 预热 ${toolNumber(28)} / ${toolNumber(29)} ms\n随后测量 ${toolNumber(30)} ms\n" +
                "A/B 电平测量 ${toolNumber(8)} / ${toolNumber(30)} ms\n" +
                "固定衰减 A ${attenuation(9)}／B ${attenuation(10)} dB\n信号不足时请播放音乐后重测\n" +
                "实际处理 ${number(2)} Hz\n抗混叠倍率 ${number(13)}×\n延迟 ${number(14)} 个采样\n延迟 $latencyMs ms\n" +
                "当前实际基础：$actualBaseName\n本档原高切 $actualBaseLowpass Hz\n" +
                "实际低切 ${cutoff(15)}\n实际高切 ${cutoff(26)}\n实际补偿中心 ${cutoff(27)}\n" +
                "实际整形系数 H2 ${coefficientField(32)}\nH3 ${coefficientField(33)}\nH4 ${coefficientField(34)}\nH5 ${coefficientField(35)}\n" +
                "${if (metersOn) "实时电平已开启" else if (v == null) "实时电平状态等待确认" else "实时电平已关闭"}\n" +
                "电平 dBFS（左／右）\n输入峰值 ${meter(16)}／${meter(17)}\n输入 RMS ${meter(20)}／${meter(21)}\n" +
                "输出峰值 ${meter(18)}／${meter(19)}\n输出 RMS ${meter(22)}／${meter(23)}\n" +
                "超限 ${if (metersOn) number(24) else StableRuntimeReadoutPreference.fixedNumber(null)} 帧"
        } + "\n电平取自本模块，后续效果仍会改变声音。采样峰值不是耳内声压或真峰值；差分表示处理与原声的差异。快照、测量及试听状态不写入预设；收起、离页或暂停会清除快照，声音参数变化或会话失效时停止试听。"
    }
    private fun coefficient(value: Int) = String.format(Locale.ROOT, "%.6f", value / 1000000.0)
    private fun hz(value: Int) = if (value == 0) "旁路" else "$value Hz"
    private fun db(value: Int) = String.format(Locale.ROOT, "%.2f", value / 100.0)
    private fun level(value: Int) = if (value == Int.MIN_VALUE) "—" else db(value)
    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    private fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { toast(e.message ?: "操作失败") } }
    private fun stopPlayback() { c.stopAudition(context, this) }
    fun stopAudition() { c.stopAudition(context, this, clearSnapshots = true) }
    fun close() { renderFrame.close();
        stopAudition(); closed = true; invalidateRows(); library?.dismiss(); pendingExport = null; group.onExpandedChanged = null
        prefs.unregisterOnSharedPreferenceChangeListener(listener); status.unregisterOnSharedPreferenceChangeListener(listener); context.unregisterLocalReceiver(receiver)
    }
}
