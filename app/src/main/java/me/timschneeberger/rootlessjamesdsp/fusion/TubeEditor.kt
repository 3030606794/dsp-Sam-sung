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

class TubeEditor(private val context: Context, private val group: SwitchPreferenceGroup, host: Fragment) {
    private val p = TubeParameters
    private val c = TubeController
    private val prefs = c.preferences(context)
    private val status = c.statusPreferences(context)
    private val store = TubePresetStore(context)
    private val rows = linkedMapOf<String, BassNumericPreference>()
    private var rowFlags: List<Boolean>? = null
    private var pendingExport: String? = null
    private var closed = false
    private var library: TubePresetsDialog? = null
    private val importLauncher = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !closed) attempt {
            val json = context.contentResolver.openInputStream(uri)?.use(TubePresetCodec::read) ?: error("无法读取预设文件")
            val count = store.importJson(json); library?.refresh(); toast("已导入 $count 项电子管预设")
        }
    }
    private val exportLauncher = host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExport; pendingExport = null
        if (uri != null && json != null && !closed) attempt {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("无法写入预设文件")
            toast("电子管预设已导出")
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
    private val professional = toggle(p.PROFESSIONAL, "专业调节", "默认关闭。原版柔化为基础，分组开启饱和、音色、混合与质量调节；关闭后恢复原版声音，精调值保留。", 10).apply {
        isPersistent = false
        setOnPreferenceChangeListener { _, next -> stopPlayback(); p.setProfessional(prefs, next as Boolean); true }
    }
    private val sat = toggle(p.SAT_ON, "启用饱和精调", "默认关闭。调节驱动、软硬度与正负半波非对称度；软硬度 0% 为硬、100% 为柔。增加驱动可能提高电平，可配合输出衰减。", 20)
    private val tone = toggle(p.TONE_ON, "启用音色控制", "默认关闭。调节柔化比例、柔化低通、饱和前低切与处理后高切；前低切需开启饱和，只限制参与饱和的低频，保留基底低频。", 30)
    private val mix = toggle(p.MIX_ON, "启用干湿混合", "默认关闭。0% 为本模块原声，100% 为处理后声音；采用线性混合，原声与效果路径按驱动实际延迟对齐。", 40)
    private val output = toggle(p.OUTPUT_ON, "启用输出增益", "默认关闭。对本模块混合后的声音作固定衰减。", 50)
    private val quality = toggle(p.QUALITY_ON, "启用饱和过采样", "默认关闭。仅饱和开启时可调，降低非线性处理产生的混叠；增加处理量与少量延迟，不提高音源信息量。", 60)
    private val factor = FieldChoicePreference(context, p.FACTOR, "饱和过采样倍率", listOf("2×", "4×"), "恢复 2×").apply {
        order = 61; setOnPreferenceChangeListener { _, next ->
            val values = prefs.all
            if (closed || !p.enabled(values) || !p.on(values, p.SAT_ON) || !p.on(values, p.QUALITY_ON)) false
            else { prefs.edit().putInt(p.FACTOR, if (next as Int == 0) 2 else 4).apply(); true }
        }
    }
    private val meters = toggle(p.METERS, "显示实时电平", "默认关闭。显示本模块输入／输出的采样峰值与 RMS，以及输出超限帧数；关闭再开启可清零。", 70)
    private val toggles = listOf(sat, tone, mix, output, quality, meters)
    private val homeNumbers = setOf(p.drive.key, p.mix.key, p.output.key)
    private val homeToggles = setOf(p.SAT_ON, p.MIX_ON, p.OUTPUT_ON)
    private val help = info("v_tube_pro_help", "电子管调节说明", 80).apply {
        summary = "原版电子管柔化以声音柔化为基础。新增饱和、音色、干湿混合、输出和过采样分组各自默认关闭；专业模式开启而全部分组关闭时保留原版声音。关闭分组只旁路该组，数值保留；单项复位恢复该项默认值。柔化低通 0 Hz 使用原版系数，其他滤波 0 Hz 为旁路。过采样仅处理饱和支路；实时显示驱动实际采样率、倍率和延迟。此模块与“谐波染色”“分频染色”独立。"
    }
    private val presets = action("v_tube_pro_presets", "电子管独立预设", 90).apply {
        summary = "原版与 3 种声音方案 · 个人 20 项 · 导入／导出"
        setOnPreferenceClickListener {
            stopPlayback(); library?.dismiss()
            library = TubePresetsDialog(context, store, { p.capture(prefs.all) }, { settings ->
                stopPlayback(); invalidateRows(); p.apply(prefs, settings); render()
            }, { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, { id ->
                pendingExport = store.export(id); exportLauncher.launch("电子管预设.json")
            }).also { it.show() }; true
        }
    }
    private fun save(slot: Int) = action(if (slot == 0) "v_tube_pro_save_a" else "v_tube_pro_save_b",
        if (slot == 0) "保存试听快照 A" else "保存试听快照 B", 100 + slot).apply {
        setOnPreferenceClickListener { if (!c.saveComparison(context, this@TubeEditor, slot)) toast("请先连接配套驱动"); renderStatus(); true }
    }
    private val saveA = save(0)
    private val saveB = save(1)
    private val audition = FieldChoicePreference(context, "v_tube_pro_audition", "临时试听",
        listOf("正常处理", "本模块原声", "仅处理后效果", "效果差分", "快照 A", "快照 B", "测量 A/B 电平"), "恢复正常").apply {
        order = 110
        setOnPreferenceChangeListener { _, next -> c.startAudition(context, this@TubeEditor, next as Int).also {
            if (!it) toast("试听需要活动音频；A/B 和测量需先保存两个快照")
        } }
    }
    private val measure = action("v_tube_pro_measure", "测量／重新测量 A/B 电平", 120).apply {
        summary = "同一输入同时处理两套快照，滤波与延迟状态预热后测量 2 秒。"
        setOnPreferenceClickListener { if (!c.measure(context, this@TubeEditor)) toast("请先保存 A/B 快照，并播放经过 DSP 的音乐"); true }
    }
    private val match = toggle("v_tube_pro_match", "匹配 A/B 电平", "先测量同一输入的 A/B RMS，再固定衰减；不持续追踪、不提升电平。", 130).apply {
        isPersistent = false
        setOnPreferenceChangeListener { _, next -> c.setMatch(context, next as Boolean).also { if (!it) toast("请先完成 A/B 电平测量") } }
    }
    private val stop = action("v_tube_pro_stop", "停止试听／恢复保存设置", 140).apply {
        setOnPreferenceClickListener { stopAudition(); true }
    }
    private val actual = StableRuntimeReadoutPreference(context).apply {
        key = "v_tube_pro_actual"; title = "试听与电平状态"; order = 150
    }
    private val compatibility = info("v_tube_pro_compatibility", "驱动支持", 160)
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
        if (closed) return@OnSharedPreferenceChangeListener
        if (source === prefs && key != p.METERS) stopPlayback()
        if (source === prefs && p.numbers.any { it.key == key }) {
            rows[key]?.let { row -> val value = p.value(prefs.all, row.control); if (row.value != value) row.value = value }
            renderFrame.request(false)
        } else renderFrame.request()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (!runtimeStatusVisible(this@TubeEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
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
        in p.satNumbers.map { it.key } -> p.on(v, p.SAT_ON)
        p.preHP.key -> p.on(v, p.TONE_ON) && p.on(v, p.SAT_ON)
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
                    in p.satNumbers.map { it.key } -> 21 + p.satNumbers.indexOfFirst { it.key == control.key }
                    in p.toneNumbers.map { it.key } -> 31 + p.toneNumbers.indexOfFirst { it.key == control.key }
                    p.mix.key -> 41
                    else -> 51
                }
                val minimum = p.minimumFrequency(control)
                if (minimum != 0) {
                    valueFormatter = { if (it == 0) if (control.key == p.softenCut.key) "原版（0 Hz）" else "旁路（0 Hz）" else control.label(it) }
                    stepAction = { delta ->
                        val target = if (value == 0 && delta > 0) minimum else if (value == minimum && delta < 0) 0
                            else (value.toLong() + delta).coerceIn(control.min.toLong(), control.max.toLong()).toInt()
                        requestValue(target)
                    }
                    stepLabel = { if (value == 0 || value == minimum) "有效频率档位" else "1 Hz" }
                    rejectedValueMessage = "请输入 0，或 $minimum 至 ${control.max} Hz；控制关闭时不能编辑"
                } else rejectedValueMessage = "控制状态已改变，请关闭输入框后重新调节"
                setOnPreferenceChangeListener { _, next -> !closed && numberEnabled(control.key, prefs.all) && p.validValue(control, next as Int) }
            }
        }
    }
    private fun render() {
        if (closed) return
        p.normalize(prefs); ensureRows(); val values = prefs.all; val enabled = p.enabled(values)
        add(professional); professional.isChecked = enabled
        professional.summary = if (group.isProfessionalMode)
            "默认关闭。原版柔化为基础，分组开启饱和、音色、混合与质量调节；关闭后恢复原版声音，精调值保留。"
        else "先开启专业调节，再手动启用所需分组。完整设置请点标题齿轮；展开不会开启音效。"
        if (!group.isProfessionalMode) {
            sat.summary = "开启后调节饱和驱动。软硬度与非对称度请进入完整设置。"
            mix.summary = "开启后调节原声与处理后声音的比例。"
            output.summary = "开启后调节整体输出衰减。"
        }
        rows.forEach { (key, row) ->
            row.isEnabled = numberEnabled(key, values)
            if (row.value != p.value(values, row.control)) row.value = p.value(values, row.control)
            if (enabled || group.isProfessionalMode || key in homeNumbers) add(row) else group.removePreference(row)
        }
        toggles.forEach { row ->
            row.isChecked = p.on(values, row.key); row.isEnabled = enabled
            if (enabled || group.isProfessionalMode || row.key in homeToggles) add(row) else group.removePreference(row)
        }
        quality.isEnabled = enabled && p.on(values, p.SAT_ON)
        quality.summary = if (quality.isEnabled) "默认关闭。对饱和支路使用 2×／4× 过采样，降低混叠；增加处理量与少量延迟。"
            else "饱和精调未开启，过采样暂时置灰；开启饱和后恢复保存的开关与倍率。"
        factor.value = if (p.factor(values) == 2) 0 else 1
        factor.isEnabled = enabled && p.on(values, p.SAT_ON) && p.on(values, p.QUALITY_ON)
        if (enabled || group.isProfessionalMode) { add(factor); add(help) }
        else { group.removePreference(factor); group.removePreference(help) }
        for (row in listOf(presets, saveA, saveB, audition, measure, match, stop, actual, compatibility)) add(row)
        renderStatus()
    }
    fun renderStatus() {
        if (closed) return
        val supported = c.supported(context); val enabled = p.enabled(prefs.all)
        saveA.isEnabled = supported; saveB.isEnabled = supported
        val hint = if (enabled) "保存完整专业参数；仅本次页面使用" else "保存原版声音；仅本次页面使用"
        saveA.summary = if (c.hasComparison(0)) "已保存，仅本次页面使用" else hint
        saveB.summary = if (c.hasComparison(1)) "已保存，仅本次页面使用" else hint
        audition.value = c.audition; audition.isEnabled = c.canListen(context)
        measure.isEnabled = audition.isEnabled && c.hasComparison(0) && c.hasComparison(1)
        match.isChecked = c.match; match.isEnabled = audition.isEnabled && c.matchingReady()
        compatibility.summary = if (supported) "已连接配套电子管增强驱动。专业调节关闭时恢复原版声音；与其他染色模块独立。"
            else "当前驱动不支持新增处理。专业设置可编辑并保留，实际仍使用原版电子管；更新配套驱动并重启后生效。"
        val moduleEnabled = p.moduleEnabled(prefs.all)
        val matchingReady = c.matchingReady()
        val streams = c.runtime.map { it as TubeController.Reading? }.ifEmpty { listOf(null) }
        actual.summary = streams.joinToString("\n\n") { r ->
            val snapshot = r?.snapshot
            // Tool epochs identify A/B operations, not normal-mode audio frames.
            val currentAudio = snapshot?.mode == c.audition && (c.audition == 0 || snapshot?.epoch == c.epoch)
            val valid = supported && moduleEnabled && enabled && r != null && r.enabled && r.active &&
                snapshot != null && snapshot.enabled && snapshot.professional && snapshot.ready &&
                snapshot.data[2] in 8000..768000 && snapshot.data[2] == r.rate && currentAudio
            val v = if (valid) snapshot?.data else null
            val tools = if (valid && snapshot?.epoch == c.epoch) v else null
            val statusText = when {
                !supported -> "等待配套驱动\n—表示暂无有效读数。"
                !moduleEnabled -> "本模块关闭；\n编辑参数不会自动启用"
                !enabled -> "原版处理；\n开启专业调节后才能试听"
                r == null || !r.enabled || !r.active -> "当前没有活动处理会话；\n试听状态等待驱动确认"
                v == null -> "等待读数\n—表示暂无有效读数。"
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
            val latencyMs = StableRuntimeReadoutPreference.fixedNumber(v?.get(14), (v?.get(2) ?: 1000) / 1000.0, 2)
            val modeName = v?.get(5)?.let {
                listOf("正常处理", "原声", "处理后效果", "效果差分", "快照 A", "快照 B", "测量").getOrNull(it)
            } ?: "—"
            "实际处理 ${number(2)} Hz\n$statusText\n当前试听：$modeName\n测量状态：$phase\n" +
                "A/B 预热 ${toolNumber(28)} / ${toolNumber(29)} ms\n随后测量 ${toolNumber(30)} ms\n" +
                "A/B 电平测量 ${toolNumber(8)} / ${toolNumber(30)} ms\n" +
                "固定衰减 A ${attenuation(9)}／B ${attenuation(10)} dB\n" +
                "信号不足时请播放音乐后重测\n饱和倍率 ${number(13)}×\n" +
                "延迟 ${number(14)} 个采样\n延迟 $latencyMs ms\n" +
                "实际柔化低通 ${cutoff(15)}\n饱和前低切 ${cutoff(26)}\n处理后高切 ${cutoff(27)}\n" +
                "${if (metersOn) "实时电平已开启" else if (v == null) "实时电平状态等待确认" else "实时电平已关闭"}\n" +
                "电平 dBFS（左／右）\n输入峰值 ${meter(16)}／${meter(17)}\n" +
                "输入 RMS ${meter(20)}／${meter(21)}\n输出峰值 ${meter(18)}／${meter(19)}\n" +
                "输出 RMS ${meter(22)}／${meter(23)}\n超限 ${if (metersOn) number(24) else StableRuntimeReadoutPreference.fixedNumber(null)} 帧"
        } + "\n电平取自本模块，后续效果仍会改变声音。采样峰值不是耳内声压或真峰值；差分表示处理与原声的差异。快照、测量及试听状态不写入预设；收起、离页或暂停会清除快照，声音参数变化或会话失效时停止试听。"
    }
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
