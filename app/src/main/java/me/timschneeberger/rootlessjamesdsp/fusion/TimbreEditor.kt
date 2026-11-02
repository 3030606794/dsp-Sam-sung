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

class TimbreEditor(private val context: Context, private val group: SwitchPreferenceGroup, host: Fragment) {
    private val p = TimbreParameters
    private val c = TimbreController
    private val prefs = c.preferences(context)
    private val status = c.statusPreferences(context)
    private val store = TimbrePresetStore(context)
    private val rows = linkedMapOf<String, BassNumericPreference>()
    private var rowFlags: List<Boolean>? = null
    private var pendingExport: String? = null
    private var closed = false
    private var library: TimbrePresetsDialog? = null
    private val importLauncher = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !closed) attempt {
            val json = context.contentResolver.openInputStream(uri)?.use(TimbrePresetCodec::read) ?: error("无法读取预设文件")
            val count = store.importJson(json); library?.refresh(); toast("已导入 $count 项音色仿真预设")
        }
    }
    private val exportLauncher = host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExport; pendingExport = null
        if (uri != null && json != null && !closed) attempt {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("无法写入预设文件")
            toast("音色仿真预设已导出")
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
    private val professional = toggle(p.PROFESSIONAL, "专业调节", "默认关闭。原版兼容声音保留；按组开放染色驱动、四路染色、分频和抗混叠，关闭后恢复原版。", 10).apply {
        isPersistent = false
        setOnPreferenceChangeListener { _, next -> stopPlayback(); p.setProfessional(prefs, next as Boolean); true }
    }
    private val baseline = info("timbre_pro_baseline", "原版兼容与染色驱动", 11)
    private val core = toggle(p.CORE_ON, "启用核心分段染色", "默认关闭。染色驱动初始 0 dB；总量与四路强度相乘，100% 为原版基础染色，最高 1000%。高量变化更明显，也可能增加失真。某路 0% 只移除该路附加染色，仍保留基础声。", 20)
    private val bands = info("timbre_pro_bands", "四路频段说明", 23)
    private val tone = toggle(p.TONE_ON, "启用分频与音色", "默认关闭。控制附加染色的频段；并非主干均衡器。五个分频点须递增，相邻至少相差 50 Hz；冲突输入拒绝且不改动其他项。染色支路低切／高切的 0 Hz 为旁路。", 40)
    private val dc = toggle(p.DC_ON, "启用染色支路直流消除", "默认关闭。仅音色组开启时生效；对平方染色产生的偏置作高通处理，保留基础声。", 48)
    private val mix = toggle(p.MIX_ON, "启用干湿混合", "默认关闭。0% 为经过相同分频与升降采样的线性基础声，100% 为完整染色；基础声与效果保持相位和延迟匹配。", 60)
    private val output = toggle(p.OUTPUT_ON, "启用输出增益", "默认关闭。对本模块完整混合输出作固定衰减；0 dB 不作额外衰减。", 70)
    private val compensation = toggle(p.COMP_ON, "启用自定义驱动补偿", "默认关闭时沿用原反向驱动增益补偿。开启后 100% 为完整反向补偿，0% 为不补偿；降低补偿可能提高输出电平。", 74)
    private val quality = toggle(p.QUALITY_ON, "启用专业抗混叠", "默认关闭时保留原版自动 IIR 过采样。开启使用 FIR 2×／4×，新增固定 31 个基础采样延迟；实际倍率和采样率见下方。", 80)
    private val factor = FieldChoicePreference(context, p.FACTOR, "专业过采样倍率", listOf("2×", "4×"), "恢复 2×").apply {
        order = 81; setOnPreferenceChangeListener { _, next ->
            val values = prefs.all
            if (closed || !p.enabled(values) || !p.on(values, p.QUALITY_ON)) false
            else { prefs.edit().putInt(p.FACTOR, if (next as Int == 0) 2 else 4).apply(); true }
        }
    }
    private val meters = toggle(p.METERS, "显示实时电平与直流", "默认关闭。显示输入／输出采样峰值、RMS、超限帧数、输出直流均值与四路混合前染色 RMS；关闭再开启可清零。", 90)
    private val toggles = listOf(core, tone, dc, mix, output, compensation, quality, meters)
    private val help = info("timbre_pro_help", "音色仿真调节说明", 100).apply {
        summary = "染色驱动先放大输入再按补偿比例反向衰减；原版完整补偿时主体响度保持接近，驱动不等同于输出音量。核心强度和音色滤波控制附加的平方染色，原版附加量较弱；分频主体幅度通常变化小，滤波也不是主干均衡器。总染色 100% 为原基础，最高 1000%；高量更明显且可能失真。可用总染色独听、临时独听增益和混合前染色 RMS 判断附加量。分组关闭后置灰并保留；开启后恢复，单项复位只恢复该项基础值。五个分频点将信号分成六段，中间四段参与染色；低／高两端保留基础声。染色强度是算法增量倍率，不是实际音乐中的谐波百分比。0% 干湿为相位匹配基础声；原声试听为本模块输入，两者可能听感不同。低采样率下频率安全限幅，实际值由驱动报告。与电子管柔化、谐波染色独立。"
    }
    private val presets = action("timbre_pro_presets", "音色仿真独立预设", 110).apply {
        summary = "原版兼容与 ${TimbreSystemPresets.entries.size - 1} 种专业方案 · 个人 20 项 · 导入／导出"
        setOnPreferenceClickListener {
            stopPlayback(); library?.dismiss()
            library = TimbrePresetsDialog(context, store, { p.capture(prefs.all) }, { settings ->
                stopPlayback(); invalidateRows(); p.apply(prefs, settings); render()
            }, { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, { id ->
                pendingExport = store.export(id); exportLauncher.launch("音色仿真预设.json")
            }).also { it.show() }; true
        }
    }
    private fun save(slot: Int) = action(if (slot == 0) "timbre_pro_save_a" else "timbre_pro_save_b",
        if (slot == 0) "保存试听快照 A" else "保存试听快照 B", 120 + slot).apply {
        setOnPreferenceClickListener { if (!c.saveComparison(context, this@TimbreEditor, slot)) toast("请先连接配套驱动"); renderStatus(); true }
    }
    private val saveA = save(0)
    private val saveB = save(1)
    private val audition = FieldChoicePreference(context, "timbre_pro_audition", "临时试听",
        listOf("正常处理", "本模块输入原声", "仅处理后效果", "效果差分", "快照 A", "快照 B", "测量 A/B 电平", "第一路染色独听", "第二路染色独听", "第三路染色独听", "第四路染色独听", "混合前总染色独听"), "恢复正常").apply {
        order = 130
        setOnPreferenceChangeListener { _, next -> c.startAudition(context, this@TimbreEditor, next as Int).also {
            if (!it) toast(if (next == 11 && !c.supportedExtended(context)) "总染色独听需要更新配套驱动并重启" else "试听需要活动音频；A/B 和测量需先保存两个快照")
        } }
    }
    private val soloGain = FieldChoicePreference(context, "timbre_pro_solo_gain", "独听临时增益（仅独听）",
        listOf("0 dB", "+6 dB", "+12 dB", "+18 dB"), "恢复 0 dB").apply {
        order = 131
        setOnPreferenceChangeListener { _, next -> c.setSoloGain(context, (next as Int) * 60).also {
            if (!it) toast("临时增益仅用于染色独听，需要配套增强驱动和活动音频")
        } }
    }
    private val soloHelp = info("timbre_pro_solo_help", "染色独听说明", 132).apply {
        summary = "四路和总染色独听只输出附加染色，不含基础声；总染色取干湿混合前。临时 0／6／12／18 dB 仅作用于独听，帮助听清较弱的染色；不改变正常声音，不写入收藏或 A/B 快照。停止试听、收起、离页或暂停恢复 0 dB。"
    }
    private val measure = action("timbre_pro_measure", "测量／重新测量 A/B 电平", 140).apply {
        summary = "同一输入同时处理两套快照，滤波与延迟状态预热后测量 2 秒。"
        setOnPreferenceClickListener { if (!c.measure(context, this@TimbreEditor)) toast("请先保存 A/B 快照，并播放经过 DSP 的音乐"); true }
    }
    private val match = toggle("timbre_pro_match", "匹配 A/B 电平", "先测量同一输入的 A/B RMS，再固定衰减；不持续追踪、不提升电平。", 150).apply {
        isPersistent = false
        setOnPreferenceChangeListener { _, next -> c.setMatch(context, next as Boolean).also { if (!it) toast("请先完成 A/B 电平测量") } }
    }
    private val stop = action("timbre_pro_stop", "停止试听／恢复保存设置", 160).apply {
        setOnPreferenceClickListener { stopAudition(); true }
    }
    private val actual = StableRuntimeReadoutPreference(context).apply {
        key = "timbre_pro_actual"; title = "试听与电平状态"; order = 170
    }
    private val compatibility = info("timbre_pro_compatibility", "驱动支持", 180)
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
        if (closed) return@OnSharedPreferenceChangeListener
        if (source === prefs && key != p.METERS) stopPlayback()
        if (source === prefs && p.numbers.any { it.key == key }) {
            rows[key]?.let { row -> val value = p.value(prefs.all, row.control); if (row.value != value) row.value = value }
            renderBands(prefs.all)
            renderFrame.request(false)
        } else renderFrame.request()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (!runtimeStatusVisible(this@TimbreEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
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
        p.dcHz.key -> p.on(v, p.TONE_ON) && p.on(v, p.DC_ON)
        in p.toneNumbers.map { it.key } -> p.on(v, p.TONE_ON)
        p.mix.key -> p.on(v, p.MIX_ON)
        p.output.key -> p.on(v, p.OUTPUT_ON)
        p.comp.key -> p.on(v, p.COMP_ON)
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
            val minimum = when (control.key) {
                p.colorHP.key -> 20
                p.colorLP.key -> 1000
                else -> 0
            }
            rows[control.key] = BassNumericPreference(context, control, p.value(prefs.all, control)).apply {
                order = when (control.key) {
                    p.drive.key -> 21
                    p.amount.key -> 22
                    in listOf(p.band1.key, p.band2.key, p.band3.key, p.band4.key) -> 24 + p.coreNumbers.indexOfFirst { it.key == control.key } - 2
                    p.dcHz.key -> 49
                    in p.toneNumbers.map { it.key } -> 41 + p.toneNumbers.indexOfFirst { it.key == control.key }
                    p.mix.key -> 61
                    p.output.key -> 71
                    else -> 75
                }
                if (minimum != 0) {
                    valueFormatter = { if (it == 0) "旁路（0 Hz）" else control.label(it) }
                    stepAction = { delta ->
                        val target = if (value == 0 && delta > 0) minimum else if (value == minimum && delta < 0) 0
                            else (value.toLong() + delta).coerceIn(control.min.toLong(), control.max.toLong()).toInt()
                        requestValue(target)
                    }
                    stepLabel = { if (value == 0 || value == minimum) "有效频率档位" else "1 Hz" }
                }
                rejectedValueMessage = "分频点须递增且相邻至少 50 Hz；低切须小于高切（或设 0 Hz 旁路）。总染色超过 200% 需要更新配套增强驱动。控制状态改变后请重新打开输入框。"
                setOnPreferenceChangeListener { _, next ->
                    val candidate = next as Int
                    if (control == p.amount && candidate > 2000 && !c.supportedExtended(context)) {
                        toast("总染色超过 200% 需要更新配套增强驱动并重启；已保存高值会保留")
                        false
                    } else !closed && numberEnabled(control.key, prefs.all) && p.candidateValid(prefs.all, control, candidate)
                }
            }
        }
    }
    private fun renderBands(values: Map<String, *>) {
        val points = p.cuts.map { p.value(values, it) }
        bands.summary = (0..3).joinToString("；") { "第${it + 1}路 ${points[it]}–${points[it + 1]} Hz" } +
            "。实际分频会随采样率安全限幅，下方显示驱动实际频率。"
        val actual = c.runtime.firstNotNullOfOrNull { currentSnapshot(it)?.data?.subList(32, 37) }
        listOf(p.band1, p.band2, p.band3, p.band4).forEachIndexed { i, control ->
            val bounds = actual ?: points
            rows[control.key]?.title = "${control.title}（${if (actual == null) "设置" else "实际"} ${bounds[i]}–${bounds[i + 1]} Hz）"
        }
    }
    private fun render() {
        if (closed) return
        p.normalize(prefs); ensureRows(); val values = prefs.all; val enabled = p.enabled(values)
        add(professional); professional.isChecked = enabled
        group.removePreference(baseline)
        group.findPreference<Preference>(p.LEGACY)?.let(group::removePreference)
        baseline.summary = if (enabled)
            "开启核心分段染色后，可调“染色驱动”和附加染色量；驱动带反向补偿，不等同于输出音量。关闭核心组恢复原版驱动。"
        else "当前保留原版声音。需要调整附加染色时，开启专业调节与核心分段染色，使用“染色驱动”和总染色强度。"
        renderBands(values)
        rows.forEach { (key, row) ->
            row.isEnabled = numberEnabled(key, values)
            if (row.value != p.value(values, row.control)) row.value = p.value(values, row.control)
            if (enabled || group.isProfessionalMode) add(row) else group.removePreference(row)
        }
        toggles.forEach { row -> row.isChecked = p.on(values, row.key); row.isEnabled = enabled; if (enabled || group.isProfessionalMode) add(row) else group.removePreference(row) }
        quality.isEnabled = enabled
        bands.isEnabled = enabled && p.on(values, p.CORE_ON)
        dc.isEnabled = enabled && p.on(values, p.TONE_ON)
        factor.value = if (p.factor(values) == 2) 0 else 1
        factor.isEnabled = enabled && p.on(values, p.QUALITY_ON)
        if (enabled || group.isProfessionalMode) { add(factor); add(help); add(bands) }
        else { group.removePreference(factor); group.removePreference(help); group.removePreference(bands) }
        if (group.isProfessionalMode) { add(factor); add(bands) }
        for (row in listOf(presets, saveA, saveB, audition, soloGain, soloHelp, measure, match, stop, actual, compatibility)) add(row)
        renderStatus()
    }
    fun renderStatus() {
        if (closed) return
        renderBands(prefs.all)
        val supported = c.supported(context); val enabled = p.enabled(prefs.all)
        saveA.isEnabled = supported; saveB.isEnabled = supported
        val hint = if (enabled) "保存完整专业音色参数；仅本次页面使用" else "保存原版兼容声音；忽略未启用的专业数值"
        saveA.summary = if (c.hasComparison(0)) "已保存，仅本次页面使用" else hint
        saveB.summary = if (c.hasComparison(1)) "已保存，仅本次页面使用" else hint
        audition.value = c.audition; audition.isEnabled = c.canListen(context)
        soloGain.value = c.soloGain / 60
        soloGain.isEnabled = audition.isEnabled && c.supportedExtended(context) && c.audition in 7..11
        measure.isEnabled = audition.isEnabled && c.hasComparison(0) && c.hasComparison(1)
        match.isChecked = c.match; match.isEnabled = audition.isEnabled && c.matchingReady()
        val extended = c.supportedExtended(context)
        val savedAmount = p.value(prefs.all, p.amount)
        val rangeHint = if (!extended && savedAmount > 2000)
            "\n旧驱动实际最高 200%，保存值 ${p.amount.label(savedAmount)} 保留；更新配套增强驱动并重启后才能按高值生效。" else ""
        compatibility.summary = when {
            extended -> "已连接配套增强驱动：支持最高 1000% 总染色、总染色独听和临时独听增益。关闭专业模式恢复原版。"
            supported -> "已连接上一版音色仿真驱动：总染色实际最高 200%，总染色独听与临时增益需更新配套增强驱动。关闭专业模式恢复原版。"
            else -> "当前驱动不支持新增处理。专业参数可编辑并保留，实际仍使用原版音色仿真；更新配套驱动并重启后生效。"
        } + rangeHint
        val moduleEnabled = p.moduleEnabled(prefs.all)
        val matchingReady = c.matchingReady()
        val streams = c.runtime.map { it as TimbreController.Reading? }.ifEmpty { listOf(null) }
        actual.summary = streams.joinToString("\n\n") { r ->
            val snapshot = currentSnapshot(r)
            val v = snapshot?.data
            val tools = if (snapshot?.epoch == c.epoch) v else null
            val statusText = when {
                !supported -> "等待配套驱动\n—表示暂无有效读数。"
                !moduleEnabled -> "本模块关闭；\n编辑参数不会自动启用"
                r == null || !r.enabled || !r.active -> "当前没有活动处理会话；\n试听状态等待驱动确认"
                v == null -> "等待读数\n—表示暂无有效读数。"
                !enabled -> "原版兼容处理；\n开启专业调节后才能试听"
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
            fun cutoff(index: Int) = v?.get(index)?.let { if (it == 0) "旁路" else "$it Hz" } ?: "—"
            val metersOn = v?.get(12) == 1
            fun meter(index: Int) = StableRuntimeReadoutPreference.fixedNumber(
                if (metersOn) v?.get(index) else null, 100.0, 2)
            fun dcMean(index: Int) = StableRuntimeReadoutPreference.fixedNumber(
                if (metersOn) v?.get(index) else null, 1.0, 0, width = 12)
            val latencyMs = StableRuntimeReadoutPreference.fixedNumber(v?.get(14), (v?.get(2) ?: 1000) / 1000.0, 2)
            val listening = when {
                v == null -> "当前试听：—"
                v[4] == 0 -> "原版兼容；专业试听已关闭"
                else -> "当前试听：" + (listOf("正常处理", "输入原声", "处理后效果", "效果差分", "快照 A", "快照 B", "测量",
                    "第一路染色", "第二路染色", "第三路染色", "第四路染色", "混合前总染色独听").getOrNull(v[5]) ?: "—")
            }
            val reference = when (v?.get(45)) {
                1 -> "干湿基础：同分频／升降采样的\n相位匹配线性声"
                0 -> "干湿基础：原版兼容路径\n原生配置状态"
                else -> "干湿基础：等待读数\n—表示暂无有效读数。"
            }
            val dcState = v?.get(39)?.let { if (it == 0) "关闭" else number(39, 10.0, 1) + " Hz" } ?: "—"
            "$statusText\n$listening\n实际独听临时增益 ${number(46, 10.0, 1)} dB\n测量状态：$phase\n" +
                "A/B 预热 ${toolNumber(29)} / ${toolNumber(30)} ms\n随后测量 ${toolNumber(31)} ms\n" +
                "A/B 电平测量 ${toolNumber(8)} / ${toolNumber(31)} ms\n" +
                "固定衰减 A ${attenuation(9)}／B ${attenuation(10)} dB\n信号不足时请播放音乐后重测\n" +
                "本模块基础处理 ${number(2)} Hz\n宿主 ${number(26)} Hz\n实际过采样 ${number(13)}×\n" +
                "专业 FIR 新增延迟 ${number(14)} 个采样\n专业 FIR 新增延迟 $latencyMs ms\n原自动 IIR 延迟不以该数值表示\n" +
                "实际五处分频\n${number(32)}／${number(33)} Hz\n${number(34)}／${number(35)} Hz\n${number(36)} Hz\n" +
                "染色低切 ${cutoff(37)}\n染色高切 ${cutoff(38)}\n直流消除 $dcState\n" +
                "实际驱动 ${number(15, 10.0, 1)} dB\n实际补偿 ${number(44, 10.0, 1)}%\n$reference\n" +
                "${if (metersOn) "实时电平已开启" else if (v == null) "实时电平状态等待确认" else "实时电平已关闭"}\n" +
                "电平 dBFS（左／右）\n输入峰值 ${meter(16)}／${meter(17)}\n输入 RMS ${meter(20)}／${meter(21)}\n" +
                "输出峰值 ${meter(18)}／${meter(19)}\n输出 RMS ${meter(22)}／${meter(23)}\n" +
                "超限 ${if (metersOn) number(24) else StableRuntimeReadoutPreference.fixedNumber(null)} 帧\n" +
                "输出直流均值 左 ${dcMean(27)} ppm\n输出直流均值 右 ${dcMean(28)} ppm\n" +
                "四路混合前染色 RMS\n第一路 ${meter(40)} dBFS\n第二路 ${meter(41)} dBFS\n第三路 ${meter(42)} dBFS\n第四路 ${meter(43)} dBFS"
        } + rangeHint + "\n电平取自本模块，后续效果仍会改变声音。采样峰值不是耳内声压或真峰值；直流均值为 100 ms 指数平滑平均值。差分为处理与输入的差异；四路和总染色独听只输出附加染色，不含基础声，临时增益仅用于独听。快照、匹配、临时增益和试听状态不写入预设；收起、离页或暂停会清除快照和临时增益，声音参数变化或会话失效时停止试听。"
    }
    /** Runtime word 2 is the internal base rate; word 26 and Reading.rate are the host rate. */
    private fun currentSnapshot(reading: TimbreController.Reading?): TimbreParameters.Runtime? {
        val values = prefs.all
        if (!c.supported(context) || !p.moduleEnabled(values) || reading == null || !reading.enabled || !reading.active) return null
        val snapshot = reading.snapshot ?: return null
        val hostRate = reading.rate
        val internalRate = if (hostRate >= 44100) hostRate else if (hostRate > 0 && 48000 % hostRate == 0) 48000
            else if (hostRate > 0 && 44100 % hostRate == 0) 44100 else 48000
        return snapshot.takeIf {
            it.enabled && it.professional == p.enabled(values) && it.ready && hostRate in 8000..768000 &&
                it.data[26] == hostRate && it.data[2] == internalRate && it.mode == c.audition &&
                (c.audition == 0 || it.epoch == c.epoch)
        }
    }
    private fun dcLevel(value: Int) = if (value == Int.MIN_VALUE) "—" else value.toString()
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
