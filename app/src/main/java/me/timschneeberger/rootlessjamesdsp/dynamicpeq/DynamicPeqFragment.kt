package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Own bank, own switch, own favorites. The original parametric equalizer is never written. */
class DynamicPeqFragment : Fragment() {
    var editorState: DynamicPeqState = DynamicPeqModel.defaultState()
        private set
    private lateinit var repository: DynamicPeqRepository
    private lateinit var favorites: DynamicPeqPresetStore
    private var preferences: SharedPreferences? = null
    private var root: LinearLayout? = null
    private var chart: DynamicPeqResponseView? = null
    private var status: TextView? = null
    private var count: TextView? = null
    private var bandList: LinearLayout? = null
    private var scroll: NestedScrollView? = null
    private var power: AppCompatImageButton? = null
    private var addButton: MaterialButton? = null
    private val cards = linkedMapOf<String, DynamicPeqBandCard>()
    private val expanded = linkedSetOf<String>()
    private var selected: String? = null
    private var restoredUi = false
    private var restoredScroll = 0
    private var writing = false
    private var saveQueued = false
    private var dirty = false
    private var durablePending = false
    private var sliderTracking = false
    private var lastSentAt = 0L
    private var hashedState: DynamicPeqState? = null
    private var parameterHash = 0
    private var renderQueued = false
    private var statusColor: Int? = null
    private var runtime: DynamicPeqRuntime? = null
    private var supported: Boolean? = null
    private var corrupt = false
    private var pendingExport: String? = null
    private val dialogs = mutableSetOf<AlertDialog>()
    private var presetsDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pendingSave = Runnable { persist() }
    private val renderFrame = Runnable { renderQueued = false; if (root != null) renderRuntime() }
    private val externalReload = Runnable { if (root != null) reload() }
    private val presetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_PRESET_LOADED) {
                // Device/profile loading replaces XML directly and may not emit preference callbacks.
                // Never let the previous route's delayed edit overwrite the replacement bank.
                handler.removeCallbacks(pendingSave); handler.removeCallbacks(externalReload)
                saveQueued = false; dirty = false; durablePending = false
                sliderTracking = false
                dismissDialogs()
                reload()
            }
        }
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!writing && key in listOf(null, DynamicPeqRepository.KEY_STATE, DynamicPeqRepository.KEY_ENABLED)) {
            handler.removeCallbacks(externalReload); handler.post(externalReload)
        }
    }

    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val appContext = requireContext().applicationContext
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val output = ByteArrayOutputStream()
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            require(output.size() + size <= DynamicPeqPresetStore.MAX_IMPORT_BYTES) { "预设文件超过 2 MiB" }
                            output.write(buffer, 0, size)
                        }
                    } ?: throw IOException("无法读取预设文件")
                    val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
                    favorites.importJson(json).size
                } }
                result.fold({ message("已导入 $it 个动态均衡器 预设"); if (root != null) showPresets() },
                    { message(it.message ?: "导入失败") })
            }
        }
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshotName = pendingExport
        pendingExport = null
        val snapshot = exportFile(snapshotName)
        if (uri == null) snapshot?.delete()
        else {
            val appContext = requireContext().applicationContext
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    try {
                        val file = requireNotNull(snapshot?.takeIf { it.isFile }) { "导出快照已失效，请重新导出" }
                        appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        } ?: throw IOException("无法写入文件")
                    } finally { snapshot?.delete() }
                } }
                result.fold({ message("动态均衡器 预设已导出") }, { message(it.message ?: "导出失败") })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = DynamicPeqRepository(requireContext())
        favorites = DynamicPeqPresetStore(requireContext())
        if (savedInstanceState != null) {
            restoredUi = true
            expanded += savedInstanceState.getStringArrayList("dynamic_expanded").orEmpty()
            selected = savedInstanceState.getString("dynamic_selected")
            restoredScroll = savedInstanceState.getInt("dynamic_scroll")
            pendingExport = savedInstanceState.getString("dynamic_export")
        }
        preferences = PreferenceCache.getPreferences(requireContext(), Constants.PREF_DYNAMIC_PEQ).also {
            it.registerOnSharedPreferenceChangeListener(listener)
        }
        requireContext().registerLocalReceiver(presetReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val page = context.column()
        root = page
        val responseCard = context.card()
        val responseContent = context.column()
        val chartHeader = context.row().apply { setPadding(context.dp(12), context.dp(5), context.dp(10), 0) }
        chartHeader.addWeighted(context.text("频率响应", 12f, true))
        chartHeader.addView(context.text("┄ 基础   ━ 当前   ░ 最大压低", 10f).apply {
            setTextColor(context.mutedColor()); maxLines = 1; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 6, 10, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
        responseContent.addFull(chartHeader, context.dp(25))
        chart = DynamicPeqResponseView(context).apply {
            id = R.id.dynamic_peq_chart
            onBandSelected = { id ->
                selected = id; expanded += id; cards[id]?.setExpanded(true, true)
                setChartState()
                scroll?.post { cards[id]?.let { scroll?.smoothScrollTo(0, it.top) } }
            }
        }
        responseContent.addFull(chart!!, context.dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 92 else 158))
        status = context.text("待机 · 等待播放", 11f).apply {
            id = R.id.dynamic_peq_status; setPadding(context.dp(12), 0, context.dp(10), context.dp(6)); maxLines = 2
        }
        responseContent.addFull(status!!)
        responseCard.addView(responseContent)
        page.addView(responseCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(context.dp(3), context.dp(1), context.dp(3), 0) })

        val actions = context.row().apply { setPadding(context.dp(10), 0, context.dp(10), 0) }
        addButton = context.action("＋ 添加频段", ::addBand).apply { id = R.id.dynamic_peq_add }
        actions.addWeighted(addButton!!, context.dp(40))
        count = context.text("", 11f).apply { gravity = Gravity.CENTER; setTextColor(context.mutedColor()) }
        actions.addWeighted(count!!, context.dp(40))
        actions.addWeighted(context.action("全部收起") {
            expanded.clear(); cards.values.forEach { it.setExpanded(false, true) }
        }, context.dp(40))
        page.addFull(actions)

        scroll = NestedScrollView(context).apply { isFillViewport = true; clipToPadding = false }
        bandList = context.column().apply {
            id = R.id.dynamic_peq_band_list
            setPadding(context.dp(11), 0, context.dp(11), context.dp(8))
        }
        scroll!!.addView(bandList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = context.row().apply { setPadding(context.dp(7), 0, context.dp(7), 0) }
        bottom.addWeighted(context.action("说明", ::showHelp), context.dp(54))
        bottom.addWeighted(context.action("重置", ::confirmReset).apply { id = R.id.dynamic_peq_reset }, context.dp(54))
        val powerSlot = android.widget.FrameLayout(context)
        power = AppCompatImageButton(context).apply {
            id = R.id.dynamic_peq_power
            setImageResource(R.drawable.ic_twotone_power_settings_24dp)
            setBackgroundResource(R.drawable.output_header_action_surface)
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setOnClickListener { replaceState(editorState.copy(enabled = !editorState.enabled), rebuild = false); persist(synchronous = true) }
        }
        powerSlot.addView(power, android.widget.FrameLayout.LayoutParams(context.dp(56), context.dp(56), Gravity.CENTER))
        bottom.addWeighted(powerSlot, context.dp(56))
        bottom.addWeighted(context.action("预设", ::showPresets).apply { id = R.id.dynamic_peq_presets }, context.dp(54))
        bottom.addWeighted(context.action("展开全部") {
            expanded += editorState.bands.map { it.id }; cards.values.forEach { it.setExpanded(true, true) }
        }, context.dp(54))
        page.addFull(bottom)
        reload()
        scroll?.post { scroll?.scrollTo(0, restoredScroll); restoredScroll = 0 }
        return page
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val appContext = requireContext().applicationContext
                    val snapshot = withContext(Dispatchers.IO) {
                        runCatching { DynamicPeqController.supported(appContext) to DynamicPeqController.currentRuntime(appContext) }
                            .getOrElse { null to null }
                    }
                    supported = snapshot.first; runtime = snapshot.second
                    renderRuntime()
                    delay(250)
                }
            }
        }
    }

    private fun reload() {
        handler.removeCallbacks(pendingSave); saveQueued = false; dirty = false; durablePending = false
        sliderTracking = false
        repository = DynamicPeqRepository(requireContext())
        val result = repository.loadResult()
        if (result.state != editorState) dismissDialogs()
        editorState = result.state; corrupt = result.corrupt
        if (!restoredUi) { editorState.bands.firstOrNull()?.let { expanded += it.id; selected = it.id }; restoredUi = true }
        expanded.retainAll(editorState.bands.map { it.id }.toSet())
        if (selected !in editorState.bands.map { it.id }) selected = editorState.bands.firstOrNull()?.id
        buildCards(); renderPower(); setChartState(); renderRuntime()
        if (result.corrupt) message("动态均衡器 保存数据无法读取，原文件已保留；重置此模块后可重新配置")
    }

    private fun replaceState(next: DynamicPeqState, rebuild: Boolean = true) {
        DynamicPeqModel.validate(next)
        val powerChanged = editorState.enabled != next.enabled
        editorState = next; corrupt = false; dirty = true
        if (rebuild) buildCards()
        // The touched row updates its own readout. Merge chart/state work into the next frame.
        setChartState()
        if (powerChanged) renderPower()
        scheduleRuntimeRender()
        if (!saveQueued) {
            saveQueued = true
            val wait = if (sliderTracking) (lastSentAt + 100 - SystemClock.uptimeMillis()).coerceAtLeast(0) else 60L
            handler.postDelayed(pendingSave, wait)
        }
    }

    private fun editBand(band: DynamicPeqBand) {
        selected = band.id
        replaceState(editorState.copy(bands = editorState.bands.map { if (it.id == band.id) band else it }), rebuild = false)
    }

    private fun buildCards() {
        val list = bandList ?: return
        list.removeAllViews(); cards.clear()
        editorState.bands.forEachIndexed { index, band ->
            val card = DynamicPeqBandCard(requireContext(), band, index, band.id in expanded, ::editBand, ::confirmDelete,
                trackingChanged = { sliderTracking = it }, editFinished = ::finishParameterEdit) { id, opened ->
                if (opened) expanded += id else expanded -= id
            }
            cards[band.id] = card
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = requireContext().dp(7) })
        }
        if (editorState.bands.isEmpty()) list.addFull(requireContext().text("暂无频段\n点击“添加频段”开始调节", 14f).apply {
            gravity = Gravity.CENTER; setPadding(0, requireContext().dp(40), 0, requireContext().dp(40))
            setTextColor(requireContext().mutedColor())
        })
        count?.text = "${editorState.bands.size} / ${DynamicPeqModel.MAX_BANDS} 段"
        addButton?.isEnabled = editorState.bands.size < DynamicPeqModel.MAX_BANDS
    }

    private fun setChartState() {
        val rate = runtime?.sampleRate?.toDouble()?.takeIf { it >= 8000 } ?: 48000.0
        chart?.setState(editorState, selected, rate)
        if (!liveRuntime()) chart?.setReductions(emptyList(), animate = false)
    }

    private fun liveRuntime(): Boolean = runtime?.let {
        it.isProcessing && it.enabled && editorState.enabled &&
            it.parameterHash != 0 && it.parameterHash == expectedParameterHash()
    } == true

    private fun expectedParameterHash(): Int {
        if (hashedState !== editorState) {
            hashedState = editorState
            parameterHash = DynamicPeqProtocol.payload(editorState).contentHashCode()
        }
        return parameterHash
    }

    private fun scheduleRuntimeRender() {
        if (!renderQueued) { renderQueued = true; root?.postOnAnimation(renderFrame) }
    }

    private fun finishParameterEdit() {
        // Send the final thumb position now without a main-thread disk commit.
        // Lifecycle boundaries retain the durable flush and profile-replacement guard.
        persist()
    }

    private fun renderRuntime() {
        val current = runtime
        val active = liveRuntime()
        val rate = current?.sampleRate?.takeIf { it >= 8000 }?.toDouble() ?: 48000.0
        chart?.setState(editorState, selected, rate)
        chart?.setReductions(if (active) current!!.reductions else emptyList(), animate = active)
        val statusText = when {
            corrupt -> "保存数据无法读取 · 请重置此模块"
            supported == false -> "驱动待更新 · 需要配套动态均衡器 驱动"
            !editorState.enabled -> "模块已关闭 · 可先调节参数"
            supported == null -> "等待驱动连接"
            current != null && (!current.enabled || current.parameterHash != expectedParameterHash()) -> "参数同步中 · 等待生效"
            active -> "实时处理 · ${dynamicNumber(rate / 1000.0, 1)} kHz · ${editorState.bands.count { it.enabled && it.frequency < .499 * rate }} 段生效"
            else -> "待机 · 等待播放"
        }
        if (status?.text?.toString() != statusText) status?.text = statusText
        val nextStatusColor = if (active) Color.rgb(46, 170, 83) else requireContext().mutedColor()
        if (statusColor != nextStatusColor) { statusColor = nextStatusColor; status?.setTextColor(nextStatusColor) }
        editorState.bands.forEachIndexed { index, band ->
            cards[band.id]?.setTelemetry(if (active) current!!.reductions.getOrElse(index) { 0.0 } else 0.0,
                if (active) current!!.detectorRms.getOrNull(index) else null, active,
                band.frequency < .499 * rate)
        }
    }

    private fun renderPower() {
        power?.isSelected = editorState.enabled
        power?.imageTintList = ColorStateList.valueOf(if (editorState.enabled) Color.rgb(46, 170, 83) else Color.GRAY)
        power?.contentDescription = if (editorState.enabled) "关闭动态均衡器" else "开启动态均衡器"
    }

    private fun persist(synchronous: Boolean = false) {
        handler.removeCallbacks(pendingSave); saveQueued = false
        if (!dirty && (!synchronous || !durablePending)) return
        val notify = dirty
        writing = true
        try {
            // Slider changes reach the engine from SharedPreferences memory immediately.
            // Disk writes stay asynchronous until an explicit action or lifecycle boundary.
            if (repository.save(editorState, synchronous = synchronous)) {
                dirty = false
                durablePending = !synchronous
                if (notify) {
                    lastSentAt = SystemClock.uptimeMillis()
                    DynamicPeqController.notifyChanged(requireContext())
                }
            } else message("保存失败，请重试")
        } catch (error: RuntimeException) { message(error.message ?: "无法保存参数") }
        finally { writing = false }
    }

    private fun addBand() {
        if (editorState.bands.size >= DynamicPeqModel.MAX_BANDS) { message("最多支持 16 个动态频段"); return }
        val band = DynamicPeqModel.newBand()
        expanded += band.id; selected = band.id
        replaceState(editorState.copy(bands = editorState.bands + band))
        scroll?.post { cards[band.id]?.let { scroll?.smoothScrollTo(0, it.top) } }
    }

    private fun confirmDelete(band: DynamicPeqBand) {
        show(GlassAlertDialogBuilder(requireContext()).setTitle("删除频段？")
            .setMessage("删除 ${dynamicFrequency(band.frequency)} 的${dynamicType(band.type)}频段及其动态设置。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                expanded -= band.id
                replaceState(editorState.copy(bands = editorState.bands.filterNot { it.id == band.id }))
            }.create())
    }

    private fun confirmReset() {
        show(GlassAlertDialogBuilder(requireContext()).setTitle("重置动态均衡器？")
            .setMessage("恢复 3 个默认动态频段。此模块开关和已收藏预设保留。")
            .setNegativeButton("取消", null).setPositiveButton("重置") { _, _ ->
                val reset = DynamicPeqModel.defaultState().copy(enabled = editorState.enabled)
                expanded.clear(); expanded += reset.bands.first().id; selected = reset.bands.first().id
                replaceState(reset); persist(synchronous = true); scroll?.scrollTo(0, 0)
            }.create())
    }

    private fun showHelp() {
        show(GlassAlertDialogBuilder(requireContext()).setTitle("动态均衡器 怎么调")
            .setMessage("先选择需要控制的频率和 Q 值，再降低阈值，让这一频段只在过响时压低。\n\n" +
                "基础增益：始终作用的均衡量。\n阈值：该频段的检测电平超过它后开始压低，单位 dBFS。\n" +
                "压缩比：超过阈值后压低的力度；1:1 不产生动态压低。\n最大压低：限制动态衰减的最大幅度。\n" +
                "启动／释放：压低和恢复的速度。\n软拐点：让进入压低的过程更平滑。\n\n" +
                "虚线是基础响应，实线是驱动当前响应，淡色区域是最大压低范围；没有播放时显示基础曲线。\n" +
                "关闭单段“动态控制”后，该段只保留基础均衡。")
            .setPositiveButton("知道了", null).create())
    }

    private fun showPresets() {
        presetsDialog?.dismiss()
        val entries = try { favorites.load() } catch (error: Exception) { message(error.message ?: "无法读取预设"); return }
        val context = requireContext()
        val content = context.column().apply { setPadding(context.dp(16), 0, context.dp(16), context.dp(8)) }
        val tools = context.row()
        tools.addWeighted(context.action("保存当前") { askName("保存动态均衡器 预设", "") { name ->
            persist(synchronous = true); favorites.save(name, editorState); showPresets(); message("已保存预设")
        } }, context.dp(44))
        tools.addWeighted(context.action("导入") { importDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, context.dp(44))
        tools.addWeighted(context.action("导出全部") { beginExport() }.apply { isEnabled = entries.isNotEmpty() }, context.dp(44))
        content.addFull(tools)
        if (entries.isEmpty()) content.addFull(context.text("还没有收藏预设\n调好声音后，点击“保存当前”", 13f).apply {
            gravity = Gravity.CENTER; setPadding(0, context.dp(28), 0, context.dp(28)); setTextColor(context.mutedColor())
        })
        entries.forEach { preset ->
            val row = context.row()
            val name = context.text(preset.name, 14f, true).apply {
                setPadding(context.dp(6), context.dp(12), context.dp(6), context.dp(12))
                maxLines = 2; isClickable = true; isFocusable = true
                contentDescription = "应用预设 ${preset.name}，${preset.state.bands.size} 段"
                setOnClickListener { applyPreset(preset) }
            }
            row.addWeighted(name)
            row.addView(context.action("应用") { applyPreset(preset) }, LinearLayout.LayoutParams(context.dp(52), context.dp(48)))
            row.addView(context.action("⋮") { presetMenu(preset) }.apply { contentDescription = "管理预设 ${preset.name}" },
                LinearLayout.LayoutParams(context.dp(40), context.dp(48)))
            content.addFull(row)
        }
        val scroller = NestedScrollView(context).apply { addView(content); isFillViewport = false }
        val dialog = GlassAlertDialogBuilder(context).setTitle("动态均衡器 · 个人预设")
            .setView(scroller).setNegativeButton("关闭", null).create()
        presetsDialog = dialog
        show(dialog)
    }

    private fun applyPreset(preset: DynamicPeqPreset) {
        val next = preset.state.copy(enabled = editorState.enabled)
        expanded.clear(); next.bands.firstOrNull()?.let { expanded += it.id; selected = it.id }
        replaceState(next); persist(synchronous = true); presetsDialog?.dismiss(); scroll?.scrollTo(0, 0)
        message("已应用 ${preset.name}")
    }

    private fun presetMenu(preset: DynamicPeqPreset) {
        show(GlassAlertDialogBuilder(requireContext()).setTitle(preset.name)
            .setItems(arrayOf("重命名", "用当前参数覆盖", "导出此预设", "删除")) { _, item ->
                when (item) {
                    0 -> askName("重命名预设", preset.name) { favorites.rename(preset.id, it); showPresets() }
                    1 -> show(GlassAlertDialogBuilder(requireContext()).setTitle("覆盖预设？")
                        .setMessage("用当前动态均衡器 参数替换“${preset.name}”。")
                        .setNegativeButton("取消", null).setPositiveButton("覆盖") { _, _ ->
                            presetOperation { favorites.overwrite(preset.id, editorState); showPresets() }
                        }.create())
                    2 -> beginExport(preset.id)
                    3 -> show(GlassAlertDialogBuilder(requireContext()).setTitle("删除预设？")
                        .setMessage("删除“${preset.name}”，当前声音参数保留。")
                        .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                            presetOperation { favorites.delete(preset.id); showPresets() }
                        }.create())
                }
            }.create())
    }

    private fun askName(title: String, initial: String, accepted: (String) -> Unit) {
        val context = requireContext()
        val box = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "预设名称" }
        val input = TextInputEditText(box.context).apply { setSingleLine(); setText(initial); setSelectAllOnFocus(true) }
        box.addView(input)
        val wrapper = context.column().apply {
            setPadding(context.dp(24), context.dp(6), context.dp(24), 0); addFull(box)
        }
        val dialog = GlassAlertDialogBuilder(context).setTitle(title).setView(wrapper)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { accepted(input.text?.toString().orEmpty()); dialog.dismiss() }
                catch (error: Exception) { box.error = error.message ?: "保存失败" }
            }
        }
        show(dialog)
    }

    private fun beginExport(id: String? = null) {
        presetOperation {
            val snapshot = File.createTempFile("dynamic-peq-export-", ".json", requireContext().cacheDir)
            try { snapshot.writeText(favorites.exportJson(id), Charsets.UTF_8) }
            catch (error: Exception) { snapshot.delete(); throw error }
            pendingExport?.let { exportFile(it)?.delete() }
            pendingExport = snapshot.name
            exportDocument.launch("DynamicPEQ-${System.currentTimeMillis()}.json")
        }
    }

    private fun exportFile(name: String?): File? = name?.takeIf {
        it.startsWith("dynamic-peq-export-") && it.endsWith(".json") && '/' !in it && '\\' !in it
    }?.let { File(requireContext().cacheDir, it) }

    private fun presetOperation(action: () -> Unit) {
        try { action() } catch (error: Exception) { message(error.message ?: "操作失败") }
    }
    private fun show(dialog: AlertDialog) {
        dialogs += dialog
        dialog.setOnDismissListener { dialogs -= dialog; if (presetsDialog === dialog) presetsDialog = null }
        dialog.show()
    }
    private fun message(text: String) { context?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() } }

    private fun dismissDialogs() {
        dialogs.toList().forEach { it.dismiss() }; dialogs.clear(); presetsDialog = null
        // Removing the old cards dismisses their private precise-input dialogs in onDetachedFromWindow.
        bandList?.removeAllViews(); cards.clear()
    }

    override fun onResume() { super.onResume(); if (root != null) reload() }
    override fun onPause() { persist(synchronous = true); super.onPause() }
    override fun onStop() { persist(synchronous = true); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        persist(synchronous = true)
        outState.putStringArrayList("dynamic_expanded", ArrayList(expanded))
        outState.putString("dynamic_selected", selected)
        outState.putInt("dynamic_scroll", scroll?.scrollY ?: restoredScroll)
        outState.putString("dynamic_export", pendingExport)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroyView() {
        persist(synchronous = true)
        root?.removeCallbacks(renderFrame); renderQueued = false; sliderTracking = false
        dismissDialogs()
        handler.removeCallbacks(externalReload)
        restoredScroll = scroll?.scrollY ?: 0
        cards.clear(); root = null; chart = null; status = null; statusColor = null; count = null; bandList = null; scroll = null; power = null; addButton = null
        super.onDestroyView()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        preferences?.unregisterOnSharedPreferenceChangeListener(listener); preferences = null
        requireContext().unregisterLocalReceiver(presetReceiver)
        super.onDestroy()
    }
}
