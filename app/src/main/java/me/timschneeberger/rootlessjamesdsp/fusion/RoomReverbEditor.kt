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
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale

class RoomReverbEditor(private val context: Context, private val group: SwitchPreferenceGroup, host: Fragment) {
    private val p = RoomReverbParameters
    private val c = RoomReverbController
    private val prefs = c.preferences(context)
    private val status = c.statusPreferences(context)
    private val store = RoomReverbPresetStore(context)
    private val rows = linkedMapOf<String, BassNumericPreference>()
    private var rowsBase = -1
    private var pendingExport: String? = null
    private var closed = false
    private var library: RoomReverbPresetsDialog? = null
    private val importLauncher = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !closed) attempt {
            val json = context.contentResolver.openInputStream(uri)?.use(RoomReverbPresetCodec::read) ?: error("无法读取预设文件")
            val count = store.importJson(json); library?.refresh(); toast("已导入 $count 项场景混响预设")
        }
    }
    private val exportLauncher = host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExport; pendingExport = null
        if (uri != null && json != null && !closed) attempt {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("无法写入预设文件")
            toast("场景混响预设已导出")
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
    private val professional = toggle(p.PROFESSIONAL, "专业调节", "默认关闭。以当前房型为基础，分组开启精调；关闭后恢复原房型声音，精调数值保留。", 10).apply {
        isPersistent = false
        setOnPreferenceChangeListener { _, next -> stopPlayback(); p.setProfessional(prefs, next as Boolean); true }
    }
    private val time = toggle(p.TIME_ON, "启用时间与空间精调", "默认关闭。分别调节尾音参考 RT60 和早反射时间尺度；这是算法反射时间参数，非实测房间尺寸。单项复位恢复当前房型原值。", 20)
    private val predelay = toggle(p.PREDELAY_ON, "启用附加湿声预延迟", "默认关闭。延后完整湿声，干声时序不变；0 ms 恢复房型原时序，房型自带的尾音延迟继续保留。", 30)
    private val levels = toggle(p.LEVELS_ON, "启用三路电平精调", "默认关闭。干声、早反射、尾音分别相对当前房型调整；0 dB 为房型原值。", 40)
    private val linked = toggle(p.LINKED, "干湿联动", "默认关闭。开启后接管三路独立增益并置灰，关闭后恢复保存值；0% 为原声，100% 为房型湿声，采用线性混合。", 50)
    private val widths = toggle(p.WIDTHS_ON, "启用两组立体声宽度", "默认关闭。早反射与尾音分别控制；早反射负值沿用原算法的交叉成像方式，0 为居中。", 60)
    private val tone = toggle(p.TONE_ON, "启用混响音色控制", "默认关闭。低切／高切作用于总湿声，0 Hz 为旁路；反馈阻尼改变尾音亮度及高频消失速度。", 70)
    private val output = toggle(p.OUTPUT_ON, "启用输出增益", "默认关闭。对本模块混合后的声音作固定衰减。", 80)
    private val meters = toggle(p.METERS, "显示实时电平", "默认关闭。显示本模块当前试听输出的采样峰值、RMS 与超限帧数；关闭再开启可清零。", 90)
    private val toggles = listOf(time, predelay, levels, linked, widths, tone, output, meters)
    private val help = info("room_reverb_help", "房型与精调说明", 100).apply {
        summary = "原 13 个房型保持不变。切换基础房型会载入它的原参数，并关闭各精调分组；专业开关及模块电源保持原状态。专业模式关闭时恢复原房型声音，重新开启恢复已保存的精调。单项复位只恢复当前房型对应原值，不改其他项。RT60 是算法参考衰减时间，实际尾音随频率、阻尼和输入而变化。场景混响与 经典混响独立，可同时串联使用。"
    }
    private val presets = action("room_reverb_presets", "场景混响独立预设", 110).apply {
        summary = "原 13 个房型 · 个人 20 项 · 导入／导出"
        setOnPreferenceClickListener {
            stopPlayback(); library?.dismiss()
            library = RoomReverbPresetsDialog(context, store, { p.capture(prefs.all) }, { settings ->
                stopPlayback(); p.apply(prefs, settings)
            }, { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, { id ->
                pendingExport = store.export(id); exportLauncher.launch("场景混响预设.json")
            }).also { it.show() }; true
        }
    }
    private fun save(slot: Int) = action(if (slot == 0) "room_reverb_save_a" else "room_reverb_save_b",
        if (slot == 0) "保存试听快照 A" else "保存试听快照 B", 120 + slot).apply {
        setOnPreferenceClickListener { if (!c.saveComparison(context, this@RoomReverbEditor, slot)) toast("请先连接配套驱动"); renderStatus(); true }
    }
    private val saveA = save(0)
    private val saveB = save(1)
    private val audition = FieldChoicePreference(context, "room_reverb_audition", "临时分路试听",
        listOf("正常处理", "本模块原声", "仅早反射", "仅后期尾音", "完整湿声", "快照 A", "快照 B", "测量 A/B 电平"), "恢复正常").apply {
        order = 130
        setOnPreferenceChangeListener { _, next -> c.startAudition(context, this@RoomReverbEditor, next as Int).also {
            if (!it) toast("试听需要活动音频；A/B 和测量需先保存两个快照")
        } }
    }
    private val measure = action("room_reverb_measure", "测量／重新测量 A/B 电平", 140).apply {
        summary = "同一输入同时处理两套快照，尾音预热后测量 2 秒；预热时间随房型与尾音参数变化。"
        setOnPreferenceClickListener { if (!c.measure(context, this@RoomReverbEditor)) toast("请先保存 A/B 快照，并播放经过 DSP 的音乐"); true }
    }
    private val match = toggle("room_reverb_match", "匹配 A/B 电平", "先测量同一输入的 A/B RMS，再固定衰减；不持续追踪、不提升电平。", 150).apply {
        isPersistent = false
        setOnPreferenceChangeListener { _, next -> c.setMatch(context, next as Boolean).also { if (!it) toast("请先完成 A/B 电平测量") } }
    }
    private val stop = action("room_reverb_stop", "停止试听／恢复保存设置", 160).apply {
        setOnPreferenceClickListener { stopAudition(); true }
    }
    private val actual = StableRuntimeReadoutPreference(context).apply {
        key = "room_reverb_actual"; title = "试听与电平状态"; order = 170
    }
    private val compatibility = info("room_reverb_compatibility", "驱动支持", 180)
    private val overlap = info("room_reverb_overlap", "混响叠加提示", 190).apply {
        summary = "上方“经典混响”也已开启，两模块将顺序处理。单独比较场景混响时，可手动关闭 经典混响。"
    }
    private val viperPrefs = me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache.getPreferences(context, ViperParameters.PREFS)
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { full -> if (full) render() else renderStatus() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
        if (closed) return@OnSharedPreferenceChangeListener
        if (source === prefs && (key in p.keys || key == p.ENABLED)) stopPlayback()
        if (source === prefs && p.numbers.any { it.key == key } && p.base(prefs.all) == rowsBase) {
            rows[key]?.let { row -> val value = p.value(prefs.all, row.control); if (row.value != value) row.value = value }
            renderFrame.request(false)
        } else renderFrame.request()
    }
    private val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, intent: Intent?) {
            if (!runtimeStatusVisible(this@RoomReverbEditor.context) && intent?.action != me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PRESET_LOADED) return
             renderFrame.request(false) } }
    init {
        p.normalize(prefs); c.stopAudition(context, clearSnapshots = true)
        group.findPreference<ListPreference>(p.BASE)?.apply {
            order = 0
            setOnPreferenceChangeListener { _, next ->
                val nextBase = (next as? String)?.toIntOrNull()
                if (nextBase != null && nextBase in p.baseIds) { stopPlayback(); p.selectBase(prefs, nextBase) }
                false
            }
        }
        group.onExpandedChanged = { if (!it) stopAudition() }
        prefs.registerOnSharedPreferenceChangeListener(listener); status.registerOnSharedPreferenceChangeListener(listener)
        viperPrefs.registerOnSharedPreferenceChangeListener(listener)
        context.registerLocalReceiver(receiver, IntentFilter(c.STATUS_ACTION))
        render()
    }
    private fun add(row: Preference) { if (group.findPreference<Preference>(row.key) == null) group.addPreference(row) }
    private fun numberEnabled(key: String, v: Map<String, *>) = p.enabled(v) && when (key) {
        in p.timeNumbers.map { it.key } -> p.on(v, p.TIME_ON)
        p.predelay.key -> p.on(v, p.PREDELAY_ON)
        in p.levelNumbers.map { it.key } -> p.on(v, p.LEVELS_ON) && !p.on(v, p.LINKED)
        p.mix.key -> p.on(v, p.LINKED)
        in p.widthNumbers.map { it.key } -> p.on(v, p.WIDTHS_ON)
        in p.toneNumbers.map { it.key } -> p.on(v, p.TONE_ON)
        p.output.key -> p.on(v, p.OUTPUT_ON)
        else -> false
    }
    private fun ensureRows() {
        val currentBase = p.base(prefs.all)
        if (rowsBase == currentBase) return
        rows.values.forEach { it.isEnabled = false; group.removePreference(it) }; rows.clear(); rowsBase = currentBase
        p.numbers.forEach { original ->
            val control = original.copy(default = p.defaultValue(prefs.all, original))
            rows[control.key] = BassNumericPreference(context, control, p.value(prefs.all, control)).apply {
                order = when (control.key) {
                    in p.timeNumbers.map { it.key } -> 21 + p.timeNumbers.indexOfFirst { it.key == control.key }
                    p.predelay.key -> 31
                    in p.levelNumbers.map { it.key } -> 41 + p.levelNumbers.indexOfFirst { it.key == control.key }
                    p.mix.key -> 51
                    in p.widthNumbers.map { it.key } -> 61 + p.widthNumbers.indexOfFirst { it.key == control.key }
                    in p.toneNumbers.map { it.key } -> 71 + p.toneNumbers.indexOfFirst { it.key == control.key }
                    else -> 81
                }
                if (control.key == p.lowCut.key || control.key == p.highCut.key)
                    valueFormatter = { if (it == 0) "旁路（0 Hz）" else control.label(it) }
                resetAction = { if (p.base(prefs.all) != currentBase) false else requestValue(p.defaultValue(prefs.all, control)) }
                rejectedValueMessage = "当前房型或控制状态已改变，请关闭输入框后重新调节"
                if (control.key == p.lowCut.key || control.key == p.highCut.key)
                    rejectedValueMessage = "低切须小于高切，或将对应滤波设为 0 Hz 旁路；已被接管的数值不能编辑"
                setOnPreferenceChangeListener { _, next ->
                    val values = prefs.all
                    val low = if (control.key == p.lowCut.key) next as Int else p.value(values, p.lowCut)
                    val high = if (control.key == p.highCut.key) next as Int else p.value(values, p.highCut)
                    !closed && p.base(values) == currentBase && numberEnabled(control.key, values) && (low == 0 || high == 0 || low < high)
                }
            }
        }
    }
    private fun render() {
        if (closed) return
        p.normalize(prefs); ensureRows()
        val values = prefs.all; val enabled = p.enabled(values)
        group.findPreference<ListPreference>(p.BASE)?.apply {
            value = p.base(values).toString(); isEnabled = true
            summaryProvider = null
            summary = p.baseName(p.base(values)) + if (enabled) "\n可随时选择；切换后重新载入房型原值并关闭精调分组" else "\n选择原房型；专业调节关闭，保留原声音"
        }
        add(professional); professional.isChecked = enabled
        rows.forEach { (key, row) ->
            row.isEnabled = numberEnabled(key, values)
            if (row.value != p.value(values, row.control)) row.value = p.value(values, row.control)
            if (enabled || group.isProfessionalMode) add(row) else group.removePreference(row)
        }
        toggles.forEach { row -> row.isChecked = p.on(values, row.key); row.isEnabled = enabled; if (enabled || group.isProfessionalMode) add(row) else group.removePreference(row) }
        levels.isEnabled = enabled && !p.on(values, p.LINKED)
        levels.summary = if (p.on(values, p.LINKED)) "干湿联动已接管，三路独立增益暂时置灰；关闭联动后恢复开关及保存值。"
            else "默认关闭。干声、早反射、尾音分别相对当前房型调整；0 dB 为房型原值。"
        if (group.isProfessionalMode || enabled) add(help) else group.removePreference(help)
        for (row in listOf(presets, saveA, saveB, audition, measure, match, stop, actual, compatibility)) add(row)
        if (p.moduleEnabled(values) && viperPrefs.getBoolean(ReverbParameters.ENABLED, false)) add(overlap) else group.removePreference(overlap)
        renderStatus()
    }
    fun renderStatus() {
        if (closed) return
        val supported = c.supported(context); val enabled = p.enabled(prefs.all)
        saveA.isEnabled = supported; saveB.isEnabled = supported
        val hint = if (enabled) "保存当前房型及完整精调；仅本次页面使用" else "保存当前原房型声音；仅本次页面使用"
        saveA.summary = if (c.hasComparison(0)) "已保存，仅本次页面使用" else hint
        saveB.summary = if (c.hasComparison(1)) "已保存，仅本次页面使用" else hint
        audition.value = c.audition; audition.isEnabled = c.canListen(context)
        measure.isEnabled = audition.isEnabled && c.hasComparison(0) && c.hasComparison(1)
        match.isChecked = c.match; match.isEnabled = audition.isEnabled && c.matchingReady()
        compatibility.summary = if (supported) "已连接配套场景混响驱动。专业调节关闭时恢复原房型声音；与 经典混响独立。"
            else "当前驱动不支持新增处理。精调设置可编辑并保留，实际仍使用原房型；更新配套驱动并重启后生效。"
        val moduleEnabled = p.moduleEnabled(prefs.all)
        val matchingReady = c.matchingReady()
        val streams = c.runtime.map { it as RoomReverbController.Reading? }.ifEmpty { listOf(null) }
        actual.summary = streams.joinToString("\n\n") { r ->
            val snapshot = r?.snapshot
            // Native clearTools resets its tool epoch to zero. Normal audio has no tool generation.
            val currentMode = snapshot?.mode == c.audition
            val currentAudio = currentMode && (c.audition == 0 || snapshot?.epoch == c.epoch)
            val valid = supported && moduleEnabled && enabled && r != null && r.enabled && r.active &&
                snapshot != null && snapshot.enabled && snapshot.professional && snapshot.ready &&
                snapshot.data[2] == r.rate && currentAudio
            val v = if (valid) snapshot?.data else null
            // A/B state belongs to an acknowledged tool generation even while normal audio is selected.
            val tools = if (valid && snapshot?.epoch == c.epoch) v else null
            val statusText = when {
                !supported -> "等待配套驱动\n—表示暂无有效读数。"
                !moduleEnabled -> "本模块关闭；\n编辑参数不会自动启用"
                !enabled -> "原房型处理；\n开启专业调节后才能分路试听"
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
            val rate = r?.rate?.toString()?.padStart(6) ?: "—".padStart(6)
            val baseName = v?.get(22)?.let(p::baseName) ?: "—"
            val modeName = v?.get(5)?.let {
                listOf("正常处理", "原声", "早反射", "尾音", "完整湿声", "快照 A", "快照 B", "测量").getOrNull(it)
            } ?: "—"
            val protection = when {
                v == null -> "长尾稳定保护：等待读数"
                snapshot?.stabilityProtected == true -> "长尾稳定保护已介入；"
                else -> "长尾稳定保护：未介入"
            }
            "处理 $rate Hz\n$statusText\n房型 $baseName\n当前试听：$modeName\n测量状态：$phase\n" +
                "A/B 预热 ${toolNumber(24)} ms\n随后测量 ${toolNumber(25)} ms\n" +
                "A/B 电平测量 ${toolNumber(8)} / ${toolNumber(25)} ms\n" +
                "固定衰减 A ${attenuation(9)}／B ${attenuation(10)} dB\n" +
                "信号不足时请播放音乐后重测\n实际附加预延迟 ${number(13, 10.0, 1)} ms；\n" +
                "湿声低切 ${cutoff(14)}／高切 ${cutoff(15)}\n反馈阻尼 ${number(23)} Hz\n" +
                "${if (metersOn) "实时电平已开启" else if (v == null) "实时电平状态等待确认" else "实时电平已关闭"}\n" +
                "输出电平 dBFS（左／右）\n采样峰值 ${meter(16)}／${meter(17)}\n" +
                "RMS ${meter(18)}／${meter(19)}\n超限 ${if (metersOn) number(20) else StableRuntimeReadoutPreference.fixedNumber(null)} 帧\n" +
                "$protection\n减小 RT60 可保留更多原房型低频尾音。"
        } + "\n试听与电平取自本模块，后续效果仍会改变声音。采样峰值不是耳内声压或真峰值；分路试听保留该支路的当前处理。快照、测量及试听状态不写入预设；收起、离页或暂停会清除快照，参数编辑或会话失效时停止试听。"
    }
    private fun hz(value: Int) = if (value == 0) "旁路" else "$value Hz"
    private fun db(value: Int) = String.format(Locale.ROOT, "%.2f", value / 100.0)
    private fun level(value: Int) = if (value == Int.MIN_VALUE) "—" else db(value)
    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    private fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { toast(e.message ?: "操作失败") } }
    private fun stopPlayback() { c.stopAudition(context, this) }
    fun stopAudition() { c.stopAudition(context, this, clearSnapshots = true) }
    fun close() { renderFrame.close();
        stopAudition(); closed = true; library?.dismiss(); pendingExport = null; group.onExpandedChanged = null
        group.findPreference<ListPreference>(p.BASE)?.onPreferenceChangeListener = null
        prefs.unregisterOnSharedPreferenceChangeListener(listener); status.unregisterOnSharedPreferenceChangeListener(listener)
        viperPrefs.unregisterOnSharedPreferenceChangeListener(listener); context.unregisterLocalReceiver(receiver)
    }
}
