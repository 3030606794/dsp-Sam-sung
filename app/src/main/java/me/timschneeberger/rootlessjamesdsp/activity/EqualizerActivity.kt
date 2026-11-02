package me.timschneeberger.rootlessjamesdsp.activity

import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.ImageButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityGraphicEqBinding
import me.timschneeberger.rootlessjamesdsp.eq.EqPresetStore
import me.timschneeberger.rootlessjamesdsp.eq.EqPresetDocuments
import me.timschneeberger.rootlessjamesdsp.eq.EqPresetsDialog
import me.timschneeberger.rootlessjamesdsp.peq.PeqChartStyles
import me.timschneeberger.rootlessjamesdsp.eq.EqLinkedDashboard
import me.timschneeberger.rootlessjamesdsp.eq.EqLinkedPlots
import me.timschneeberger.rootlessjamesdsp.eq.EqRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.eq.SpectrumAppearanceDialog
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot
import me.timschneeberger.rootlessjamesdsp.peq.PeqResponseView
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import me.timschneeberger.rootlessjamesdsp.ui.SliderAppearance
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch
import me.timschneeberger.rootlessjamesdsp.ui.ProfessionalTitleBar
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.view.EqPreview
import me.timschneeberger.rootlessjamesdsp.view.EqualizerSurface
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen editor for the existing 15-band EQ namespace. */
class EqualizerActivity : BaseActivity() {
    private lateinit var settings: SharedPreferences
    private lateinit var dashboard: EqLinkedDashboard
    private lateinit var runtimeMonitor: EqRuntimeMonitor
    private lateinit var chartPreferences: SharedPreferences
    private lateinit var chartHeader: LinearLayout
    private lateinit var originalResponse: PeqResponseView
    private var overviewStarted = false
    private var overviewWorker: EqLinkedPlots.Worker? = null
    private var overviewEpoch = 0L
    private var overviewRevision = 0L
    private var overviewShownRevision = -1L
    private data class OverviewKey(val levels: List<Double>, val filter: Int, val interpolation: Int,
        val selectedBand: Int, val rate: Double, val enabled: Boolean, val columns: Int)
    private var overviewRequestedKey: OverviewKey? = null
    private var overviewPlot: LiveprogStaticPlot? = null
    private var overviewPending: EqLinkedPlots.Request? = null
    private var overviewSubmissionPosted = false
    private val submitOverview = Runnable {
        overviewSubmissionPosted = false
        if (needsOverviewWorker()) overviewPending?.let { request ->
            overviewPending = null
            val token = overviewEpoch
            val worker = overviewWorker ?: EqLinkedPlots.Worker { result ->
                originalResponse.postOnAnimation {
                    val current = overviewRequestedKey
                    val source = result.request
                    if (token != overviewEpoch || !needsOverviewWorker() || current == null ||
                        source.filter != current.filter || source.interpolation != current.interpolation ||
                        source.selectedBand != current.selectedBand || source.rate != current.rate ||
                        source.enabled != current.enabled || source.revision <= overviewShownRevision)
                        return@postOnAnimation
                    overviewShownRevision = source.revision
                    applyOverviewResponse(result.pair.professional)
                }
            }.also { overviewWorker = it }
            worker.submit(request)
        }
    }
    private val chartDisplayListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "show_response" || key == "show_static" ||
            key == "show_realtime" || key == "realtime_expanded") syncOverviewVisibility()
    }
    private var selectedBand = 0
    private var analysisRate = 48000.0
    private var levels = DoubleArray(15)
    private val sliders = mutableListOf<Slider>()
    private val gainValues = mutableListOf<MaterialButton>()
    private var refreshing = false
    private lateinit var body: LinearLayout
    private lateinit var pinnedArea: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var filterButton: MaterialButton
    private lateinit var interpolationButton: MaterialButton
    private var pinResponse = false
    private val pinMenuId = 0x451001
    private val powerMenuId = 0x451002
    private var powerSwitch: SpringMaterialSwitch? = null
    private lateinit var professionalTitle: ProfessionalTitleBar
    private var updatingPowerSwitch = false
    private var pinAction: ImageButton? = null
    private var styleActions = emptyList<View>()
    private val presetStore by lazy { EqPresetStore(this) }
    private var presetDialogs: EqPresetsDialog? = null
    private var pendingExportJson: String? = null
    private val importPresets = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPresetDocument(uri)
    }
    private val exportPresets = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = pendingExportJson
        pendingExportJson = null
        if (uri != null) {
            if (snapshot == null) presetMessage("导出内容已失效，请重试")
            else lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    EqPresetDocuments.write(contentResolver, uri, snapshot)
                } }
                result.fold(onSuccess = { presetMessage("预设已导出") },
                    onFailure = { presetMessage("导出失败：${it.message ?: "无法写入文件"}") })
            }
        }
    }

    internal fun setPinned(value: Boolean) {
        pinResponse = value
        (chartHeader.parent as? android.view.ViewGroup)?.removeView(chartHeader)
        if (value) pinnedArea.addView(chartHeader, LinearLayout.LayoutParams(-1, -2))
        else body.addView(chartHeader, 0, LinearLayout.LayoutParams(-1, -2))
        prefsApp.preferences.edit().putBoolean("eq_pin_response", value).apply()
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        val toggle = SpringMaterialSwitch(this).apply {
            minimumHeight = 0
            background = null
            isSaveEnabled = false
            springEnabled = true
            scaleX = 46f / 52f
            scaleY = 46f / 52f
            contentDescription = "${getString(R.string.fusion_eq_title)} · 开关"
            setCheckedImmediately(settings.getBoolean(getString(R.string.key_eq_enable), false))
            setOnCheckedChangeListener { _, checked ->
                if (!updatingPowerSwitch) {
                    settings.edit().putBoolean(getString(R.string.key_eq_enable), checked).apply()
                    sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                }
            }
        }
        powerSwitch = toggle
        professionalTitle.setSwitch(toggle)
        if (styleActions.isEmpty()) {
            fun shortcut(realtime: Boolean) = TextView(this).apply {
                text = if (realtime) "动图" else "静图"
                textSize = 13f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
                background = null; foreground = null
                isClickable = true; isFocusable = true
                contentDescription = "$text 样式：点按切换，长按选择"
                setOnClickListener { cycleChartStyle(realtime) }
                setOnLongClickListener { chooseChartStyle(realtime); true }
            }
            val pin = AppCompatImageButton(this).apply {
                background = null; foreground = null
                setColorFilter(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
                setOnClickListener { setPinned(!pinResponse) }
            }
            pinAction = pin
            styleActions = listOf(shortcut(false), shortcut(true), pin)
        }
        professionalTitle.setTrailingActions(styleActions)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        pinAction?.apply {
            contentDescription = if (pinResponse) getString(R.string.peq_unpin_response) else getString(R.string.peq_pin_response)
            setImageResource(if (pinResponse) R.drawable.ic_peq_pin else R.drawable.ic_peq_pin_off)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == pinMenuId) { setPinned(!pinResponse); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun cycleChartStyle(realtime: Boolean) {
        val names = if (realtime) PeqChartStyles.realtimeNames else PeqChartStyles.staticNames
        val key = if (realtime) "realtime_style" else "static_style"
        val current = chartPreferences.getInt(key, 0).takeIf { it in names.indices } ?: 0
        applyChartStyle(realtime, (current + 1) % names.size)
    }

    private fun applyChartStyle(realtime: Boolean, selected: Int) {
        val names = if (realtime) PeqChartStyles.realtimeNames else PeqChartStyles.staticNames
        if (selected !in names.indices) return
        if (chartPreferences.edit().putInt(if (realtime) "realtime_style" else "static_style", selected).commit()) {
            dashboard.refreshAppearance()
            presetMessage("${if (realtime) "动图" else "静图"} ${selected + 1}/${names.size} · ${names[selected]}")
        } else presetMessage("样式保存失败，请重试")
    }

    private fun chooseChartStyle(realtime: Boolean) {
        val names = if (realtime) PeqChartStyles.realtimeNames else PeqChartStyles.staticNames
        val key = if (realtime) "realtime_style" else "static_style"
        val selected = chartPreferences.getInt(key, 0).takeIf { it in names.indices } ?: 0
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (realtime) "实时图样式" else "联动静图样式")
            .setSingleChoiceItems(names.toTypedArray(), selected) { dialog, value ->
                applyChartStyle(realtime, value); dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null)
        if (realtime) builder.setNeutralButton("频谱设置") { _, _ -> SpectrumAppearanceDialog.show(this, "eq") }
        builder.show()
    }

    private fun showChartSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
        }
        fun toggle(title: String, key: String, defaultValue: Boolean = true) = SpringMaterialSwitch(this).apply {
            text = title; textSize = 13f
            springEnabled = true
            setCheckedImmediately(chartPreferences.getBoolean(key, defaultValue))
            content.addView(this, LinearLayout.LayoutParams(-1, dp(46)))
        }
        val responseVisible = toggle("显示原频响图", "show_response", true)
        val staticVisible = toggle("显示联动静图", "show_static", false)
        val liveVisible = toggle("显示实时图", "show_realtime", false)
        fun selector(title: String, key: String, names: List<String>): Spinner {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = title; textSize = 13f; includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(0, dp(46), 1f))
            val picker = Spinner(this).apply {
                adapter = object : ArrayAdapter<String>(this@EqualizerActivity,
                    android.R.layout.simple_spinner_item, names) {
                    init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                        super.getView(position, convertView, parent).apply { (this as? TextView)?.textSize = 13f }
                    override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                        super.getDropDownView(position, convertView, parent).apply { (this as? TextView)?.textSize = 13f }
                }
                setSelection(chartPreferences.getInt(key, 0).takeIf { it in names.indices } ?: 0)
            }
            row.addView(picker, LinearLayout.LayoutParams(dp(160), dp(46)))
            content.addView(row)
            return picker
        }
        val staticStyle = selector("静图样式", "static_style", PeqChartStyles.staticNames)
        val liveStyle = selector("实时图样式", "realtime_style", PeqChartStyles.realtimeNames)
        content.addView(actionButton("频谱分析器设置") { SpectrumAppearanceDialog.show(this, "eq") },
            LinearLayout.LayoutParams(-1, dp(46)))
        MaterialAlertDialogBuilder(this).setTitle("均衡器显示设置")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (chartPreferences.edit().putBoolean("show_response", responseVisible.isChecked)
                        .putBoolean("show_static", staticVisible.isChecked)
                        .putBoolean("show_realtime", liveVisible.isChecked)
                        .putInt("static_style", PeqChartStyles.staticStyle(staticStyle.selectedItemPosition))
                        .putInt("realtime_style", PeqChartStyles.realtimeStyle(liveStyle.selectedItemPosition)).commit()) {
                    dashboard.refreshAppearance()
                    syncOverviewVisibility()
                } else presetMessage("显示设置保存失败，请重试")
            }.show()
    }

    private fun needsOverviewWorker(): Boolean = overviewStarted && hasWindowFocus() &&
        ::originalResponse.isInitialized && originalResponse.visibility == View.VISIBLE &&
        !chartPreferences.getBoolean("show_static", false)

    private fun syncOverviewVisibility() {
        if (!::originalResponse.isInitialized) return
        val visible = chartPreferences.getBoolean("show_response", true)
        originalResponse.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            overviewPlot?.let(::applyOverviewResponse)
            originalResponse.setEqAnnotations(EqualizerSurface.SCALE, levels, selectedBand,
                settings.getBoolean(getString(R.string.key_eq_enable), false))
        }
        if (needsOverviewWorker()) requestOverview() else stopOverviewWorker()
    }

    /** Use the accepted dashboard model normally; this worker only serves an otherwise hidden dashboard. */
    private fun requestOverview() {
        if (!needsOverviewWorker()) return
        val filter = settings.getString(getString(R.string.key_eq_filter_type), "0")?.toIntOrNull() ?: 0
        val interpolation = settings.getString(getString(R.string.key_eq_interpolation), "0")?.toIntOrNull() ?: 0
        val enabled = settings.getBoolean(getString(R.string.key_eq_enable), false)
        val columns = originalResponse.width.takeIf { it > 0 } ?: dp(360)
        val key = OverviewKey(levels.toList(), filter, interpolation, selectedBand,
            analysisRate, enabled, columns.coerceIn(128, 1536))
        if (overviewRequestedKey == key) return
        overviewRequestedKey = key
        overviewPending = EqLinkedPlots.Request(levels.copyOf(), filter, interpolation, selectedBand,
            analysisRate, ++overviewRevision, key.columns, enabled)
        if (!overviewSubmissionPosted) {
            overviewSubmissionPosted = true
            originalResponse.postOnAnimation(submitOverview)
        }
    }

    private fun applyOverviewResponse(plot: LiveprogStaticPlot) {
        overviewPlot = plot
        if (!::originalResponse.isInitialized || originalResponse.visibility != View.VISIBLE) return
        val points = plot.curves.firstOrNull()?.points.orEmpty()
            .filter { it.x.isFinite() && it.x > 0.0 }
        if (points.size >= 2) {
            originalResponse.setExternalResponse(points.map { it.x }.toDoubleArray(),
                points.map { it.y }.toDoubleArray(), range = 12.0)
        } else {
            originalResponse.setExternalResponse(doubleArrayOf(EqPreview.MINIMUM_FREQUENCY, EqPreview.MAXIMUM_FREQUENCY),
                doubleArrayOf(Double.NaN, Double.NaN), range = 12.0)
        }
        originalResponse.setEffectEnabled(settings.getBoolean(getString(R.string.key_eq_enable), false))
        originalResponse.setEqAnnotations(EqualizerSurface.SCALE, levels, selectedBand,
            settings.getBoolean(getString(R.string.key_eq_enable), false))
        originalResponse.contentDescription = "原总频响图 · ${plot.title} · ${plot.subtitle}"
    }

    private fun stopOverviewWorker() {
        overviewEpoch++
        if (::originalResponse.isInitialized) originalResponse.removeCallbacks(submitOverview)
        overviewSubmissionPosted = false
        overviewPending = null
        overviewWorker?.close(); overviewWorker = null
        overviewRequestedKey = null
    }

    private fun chooseSetting(keyRes: Int, titleRes: Int, labelsRes: Int, valuesRes: Int) {
        val key = getString(keyRes)
        val labels = resources.getStringArray(labelsRes)
        val values = resources.getStringArray(valuesRes)
        val selected = values.indexOf(settings.getString(key, "0")).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes).setSingleChoiceItems(labels, selected) { dialog, index ->
                settings.edit().putString(key, values[index]).apply()
                sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    private val bandKey get() = getString(R.string.key_eq_bands)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showPresets() {
        try {
            if (presetDialogs == null) presetDialogs = EqPresetsDialog(this, presetStore,
                requestImport = { importPresets.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                requestExport = ::requestPresetExport, requestShare = ::requestPresetShare, onApplied = ::refresh)
            presetDialogs!!.show()
        } catch (failure: Exception) { presetMessage("无法打开预设：${failure.message ?: "读取失败"}") }
    }

    private fun importPresetDocument(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                presetStore.importJson(EqPresetDocuments.read(contentResolver, uri))
            } }
            result.fold(onSuccess = {
                presetDialogs?.refresh()
                presetMessage("已导入预设：${it.name}")
            }, onFailure = { presetMessage("导入失败：${it.message ?: "文件无效"}") })
        }
    }

    private fun requestPresetExport(id: String?) {
        // Capture the chosen preset now so later edits or rotation cannot alter the exported file.
        pendingExportJson = presetStore.exportJson(id)
        try { exportPresets.launch(if (id == null) "DSP-EQ-当前设置.json" else "DSP-EQ-预设.json") }
        catch (failure: Exception) { pendingExportJson = null; throw failure }
    }

    private fun requestPresetShare(id: String) {
        val snapshot = presetStore.exportJson(id)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                EqPresetDocuments.shareIntent(this@EqualizerActivity, snapshot)
            } }
            result.fold(onSuccess = { chooser ->
                try { startActivity(chooser) }
                catch (failure: Exception) { presetMessage("分享失败：${failure.message ?: "无法打开分享菜单"}") }
            }, onFailure = { presetMessage("分享失败：${it.message ?: "无法创建文件"}") })
        }
    }

    private fun presetMessage(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun editGain(index: Int) {
        val input = EditText(this).apply {
            id = R.id.eq_gain_input
            tag = "eq_gain_input"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
            setText(String.format(Locale.ROOT, "%.1f", levels[index]))
            selectAll()
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("${EqualizerSurface.SCALE[index].toInt()} Hz · dB")
            .setView(input).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val gain = input.text.toString().trim().toDoubleOrNull()
                if (gain == null || !gain.isFinite() || gain !in -12.0..12.0) {
                    input.error = "请输入 −12 至 +12 dB"
                } else {
                    setBandGain(index, kotlin.math.round(gain * 10) / 10)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    /** Reset only when the user double-taps the thumb; ordinary dragging is unchanged. */
    private inner class GainSlider(index: Int) : Slider(this@EqualizerActivity) {
        init {
            SliderDoubleTapReset.install(this, 0f, thumbOnly = true) {
                setBandGain(index, 0.0)
            }
        }
    }

    private fun actionButton(title: CharSequence, action: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = title
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action() }
        }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pendingExportJson = state?.getString("eq_preset_export_json")
        val binding = ActivityGraphicEqBinding.inflate(layoutInflater)
        binding.peqToolbarSummaryRow.visibility = View.GONE
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.fusion_eq_title)
        EqualizerToolbarLayout.useMainMenuTitleAppearance(binding.toolbar)
        professionalTitle = ProfessionalTitleBar.install(binding.toolbar, getString(R.string.fusion_eq_title))
        professionalTitle.configureBassCompact()
        professionalTitle.titleLabel.textSize = 14f
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        EqualizerToolbarLayout.alignTitle(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        settings = getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
        chartPreferences = getSharedPreferences("eq_linked_display", Context.MODE_PRIVATE)
        levels = EqPreview.parse(settings.getString(bandKey, EqPreview.defaultValue) ?: EqPreview.defaultValue)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        dashboard = EqLinkedDashboard(this).apply { id = R.id.layout_peq }
        originalResponse = PeqResponseView(this).apply {
            setExternalResponse(doubleArrayOf(EqPreview.MINIMUM_FREQUENCY, EqPreview.MAXIMUM_FREQUENCY),
                doubleArrayOf(Double.NaN, Double.NaN), range = 12.0)
            visibility = if (chartPreferences.getBoolean("show_response", true)) View.VISIBLE else View.GONE
            contentDescription = "原总频响图 · 等待当前参数模型"
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) requestOverview()
            }
        }
        dashboard.onModelResponse = ::applyOverviewResponse
        chartHeader = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chartHeader.addView(originalResponse, LinearLayout.LayoutParams(-1, dp(180)))
        chartHeader.addView(dashboard, LinearLayout.LayoutParams(-1, -2))
        body.addView(chartHeader, LinearLayout.LayoutParams(-1, -2))
        EqualizerSurface.SCALE.forEachIndexed { index, frequency ->
            val label = TextView(this).apply {
                text = "${frequency.toInt()} Hz"
                includeFontPadding = false
                setPadding(dp(12), dp(6), 0, 0)
            }
            body.addView(label)
            ParameterHelp.attachTitle(label, "${frequency.toInt()} Hz 增益",
                "调整当前频段的增益。保留原 15 频段均衡器的滤波器与插值选择。",
                "范围 −12 至 +12 dB；加减步长 1 dB；手动输入 0.1 dB；默认 0 dB。")
            val row = LinearLayout(this).apply {
                tag = "eq_gain_row_$index"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(8), 0)
            }
            val slider = GainSlider(index).apply {
                id = R.id.eq_gain_slider
                tag = "eq_gain_slider_$index"
                valueFrom = -12f
                valueTo = 12f
                stepSize = 0.1f
                value = (kotlin.math.round(levels[index].coerceIn(-12.0, 12.0) * 10) / 10).toFloat()
                contentDescription = "${frequency.toInt()} Hz"
                SliderAppearance.apply(this)
                setGlassLabelFormatter { String.format(Locale.ROOT, "%.1f dB", it) }
                addOnChangeListener { _, value, fromUser ->
                    if (fromUser && !refreshing) setBandGain(index, kotlin.math.round(value.toDouble() * 10) / 10)
                }
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) { selectedBand = index; refresh() }
                    override fun onStopTrackingTouch(slider: Slider) = Unit
                })
            }
            sliders += slider
            val plus = actionButton("+") { setBandGain(index, levels[index] + 1.0) }.apply {
                id = R.id.eq_gain_plus
                tag = "eq_gain_plus_$index"
                textSize = 24f
                contentDescription = "${frequency.toInt()} Hz 增加 1 dB"
            }
            val minus = actionButton("−") { setBandGain(index, levels[index] - 1.0) }.apply {
                id = R.id.eq_gain_minus
                tag = "eq_gain_minus_$index"
                textSize = 24f
                contentDescription = "${frequency.toInt()} Hz 减少 1 dB"
            }
            val gain = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = R.id.eq_gain_value
                tag = "eq_gain_value_$index"
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                insetTop = 0
                insetBottom = 0
                setPadding(dp(2), 0, dp(2), 0)
                contentDescription = "${frequency.toInt()} Hz 增益，点击输入"
                setOnClickListener { editGain(index) }
            }
            gainValues += gain
            row.addView(minus, LinearLayout.LayoutParams(dp(30), dp(30)))
            row.addView(slider, LinearLayout.LayoutParams(0, dp(30), 1f))
            row.addView(plus, LinearLayout.LayoutParams(dp(30), dp(30)))
            row.addView(gain, LinearLayout.LayoutParams(dp(80), dp(30)))
            body.addView(row, LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(6) })
        }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pinnedArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { id = R.id.eq_bands_scroll; addView(body) }
        page.addView(pinnedArea, LinearLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            id = R.id.eq_fixed_actions
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterButton = actionButton(getString(R.string.eq_filter_type)) {
            chooseSetting(R.string.key_eq_filter_type, R.string.eq_filter_type, R.array.eq_filter_types, R.array.eq_filter_types_values)
        }.apply { id = R.id.eq_filter_type }
        interpolationButton = actionButton(getString(R.string.eq_interpolator)) {
            chooseSetting(R.string.key_eq_interpolation, R.string.eq_interpolator, R.array.eq_interpolators, R.array.eq_interpolators_values)
        }.apply { id = R.id.eq_interpolation }
        listOf(
            actionButton("设置") { showChartSettings() },
            actionButton("重置") { levels = DoubleArray(15); persist() }.apply { id = R.id.eq_reset },
            actionButton("预设") { showPresets() }.apply { id = R.id.eq_presets },
            filterButton,
            interpolationButton
        ).forEach {
            it.textSize=14f
            it.includeFontPadding=false
            it.clipToOutline=true
            it.stateListAnimator=null
            footer.addView(it,LinearLayout.LayoutParams(0,resources.getDimensionPixelSize(R.dimen.bottom_control_touch_size),1f))
        }
        page.addView(footer, LinearLayout.LayoutParams(-1, dp(56)))
        binding.params.addView(page)
        pinResponse = prefsApp.preferences.getBoolean("eq_pin_response", true)
        if (pinResponse) {
            body.removeView(chartHeader)
            pinnedArea.addView(chartHeader, LinearLayout.LayoutParams(-1, -2))
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        chartPreferences.registerOnSharedPreferenceChangeListener(chartDisplayListener)
        runtimeMonitor = EqRuntimeMonitor(this, this, onSample = { sample ->
            if (sample.rate.toDouble() != analysisRate) {
                analysisRate = sample.rate.toDouble()
                refresh()
            }
            dashboard.setSample(sample)
        }, onState = dashboard::setRuntimeState)
        dashboard.onRealtimeVisibilityChanged = { visible ->
            if (::runtimeMonitor.isInitialized) runtimeMonitor.setEnabled(visible)
        }
        runtimeMonitor.setEnabled(dashboard.isRealtimeVisible)
        refresh()
    }

    internal fun setBandGain(index: Int, gain: Double) {
        require(index in 0..14 && gain.isFinite())
        selectedBand = index
        levels[index] = gain.coerceIn(-12.0, 12.0)
        persist()
    }

    private fun persist() {
        settings.edit().putString(bandKey, EqPreview.encode(levels)).apply()
        sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        refresh()
    }

    private fun refresh() {
        powerSwitch?.let { toggle ->
            val checked = settings.getBoolean(getString(R.string.key_eq_enable), false)
            if (toggle.isChecked != checked) {
                updatingPowerSwitch = true
                toggle.setCheckedImmediately(checked)
                updatingPowerSwitch = false
            }
        }
        if (!::dashboard.isInitialized) return
        val value = settings.getString(bandKey, EqPreview.defaultValue) ?: EqPreview.defaultValue
        levels = EqPreview.parse(value)
        val types = resources.getStringArray(R.array.eq_filter_types)
        val typeValues = resources.getStringArray(R.array.eq_filter_types_values)
        val typeIndex = typeValues.indexOf(settings.getString(getString(R.string.key_eq_filter_type), "0")).coerceAtLeast(0)
        filterButton.contentDescription = "${getString(R.string.eq_filter_type)}：${types[typeIndex]}"
        val interpolators = resources.getStringArray(R.array.eq_interpolators)
        val interpolationValues = resources.getStringArray(R.array.eq_interpolators_values)
        val interpolationIndex = interpolationValues.indexOf(settings.getString(getString(R.string.key_eq_interpolation), "0")).coerceAtLeast(0)
        interpolationButton.contentDescription = "${getString(R.string.eq_interpolator)}：${interpolators[interpolationIndex]}"
        // Both controls retain their own selection. The existing native IIR design
        // uses its own response; the saved interpolator is applied when using FIR.
        refreshing = true
        sliders.forEachIndexed { i, slider ->
            val position = (kotlin.math.round(levels[i].coerceIn(-12.0, 12.0) * 10) / 10).toFloat()
            if (slider.value != position) slider.value = position
            val caption = String.format(Locale.ROOT, "%+.1f dB", levels[i].coerceIn(-12.0, 12.0))
            if (gainValues[i].text.toString() != caption) gainValues[i].text = caption
        }
        refreshing = false
        val enabled = settings.getBoolean(getString(R.string.key_eq_enable), false)
        dashboard.setState(levels,
            typeValues[typeIndex].toIntOrNull() ?: 0,
            interpolationValues[interpolationIndex].toIntOrNull() ?: 0, selectedBand, analysisRate, enabled)
        if (::originalResponse.isInitialized && originalResponse.visibility == View.VISIBLE) {
            originalResponse.setEffectEnabled(enabled)
            originalResponse.setEqAnnotations(EqualizerSurface.SCALE, levels, selectedBand, enabled)
        }
        requestOverview()
    }

    override fun onStart() {
        super.onStart()
        overviewStarted = true
        syncOverviewVisibility()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) stopOverviewWorker()
        else if (::originalResponse.isInitialized && ::chartPreferences.isInitialized && ::settings.isInitialized) {
            syncOverviewVisibility()
            requestOverview()
        }
    }

    override fun onStop() {
        overviewStarted = false
        stopOverviewWorker()
        super.onStop()
    }

    override fun onDestroy() {
        overviewStarted = false
        stopOverviewWorker()
        if (::chartPreferences.isInitialized)
            chartPreferences.unregisterOnSharedPreferenceChangeListener(chartDisplayListener)
        if (::runtimeMonitor.isInitialized) runtimeMonitor.close()
        if (::dashboard.isInitialized) dashboard.close()
        presetDialogs?.dismiss()
        if (::settings.isInitialized) settings.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingExportJson?.let { outState.putString("eq_preset_export_json", it) }
        super.onSaveInstanceState(outState)
    }
}
