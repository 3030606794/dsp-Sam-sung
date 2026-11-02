package me.timschneeberger.rootlessjamesdsp.activity

import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.MotionEvent
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundParameterFocusObserver
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalEditor
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundDashboardCoordinator
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalParameters
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalRuntime
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityGraphicEqBinding
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundController
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundPresetCodec
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundPresetStore
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundPresetsDialog
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import me.timschneeberger.rootlessjamesdsp.ui.SliderAppearance
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch
import me.timschneeberger.rootlessjamesdsp.ui.ProfessionalTitleBar
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class SurroundActivity : BaseActivity() {
    private var professional:SurroundProfessionalEditor?=null
    private var dashboard:SurroundDashboardCoordinator?=null
    private val legacyActions=mutableMapOf<Parameter,List<View>>()
    private lateinit var body: LinearLayout
    private lateinit var dashboardPinnedArea: LinearLayout
    private lateinit var dashboardScrollingArea: LinearLayout
    private lateinit var originalScroll: ScrollView
    private var dashboardHeader: View? = null
    private var dashboardActionsOwner: View? = null
    private var focusObserver: SurroundParameterFocusObserver? = null
    private lateinit var power: SpringMaterialSwitch
    private lateinit var professionalTitle: ProfessionalTitleBar
    private lateinit var status: TextView
    private var stores = emptyList<SharedPreferences>()
    private val controls = mutableListOf<Pair<Slider, MaterialButton>>()
    private val trackingSliders = mutableSetOf<Slider>()
    private val renderFrame = me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate { refresh() }
    private var refreshing = false
    private var presetDialog: SurroundPresetsDialog? = null
    private val valueDialogs = mutableListOf<AlertDialog>()
    private var pendingExportFileName: String? = null
    private val importPresets = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val stream = contentResolver.openInputStream(uri) ?: throw IOException("无法读取文件")
                val json = stream.use { SurroundPresetCodec.readImport(it) }
                SurroundPresetStore(applicationContext).importJson(json).size
            } }
            result.fold(onSuccess = {
                presetDialog?.refreshStatus()
                presetMessage(getString(R.string.surround_presets_imported, it))
            }, onFailure = { presetMessage(it.message ?: getString(R.string.surround_presets_import_failed)) })
        }
    }
    private val exportPresets = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = exportSnapshotFile(pendingExportFileName)
        pendingExportFileName = null
        if (uri == null) snapshot?.delete()
        else lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { runCatching {
                    requireNotNull(snapshot?.takeIf { it.isFile }) { "导出快照已失效，请重新导出" }
                        .inputStream().use { input ->
                            val stream = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入文件")
                            stream.use { input.copyTo(it) }
                        }
                } } finally { snapshot?.delete() }
            }
            result.fold(onSuccess = { presetMessage(getString(R.string.surround_presets_exported)) },
                onFailure = { presetMessage(it.message ?: getString(R.string.surround_presets_export_failed)) })
        }
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        val displayOnly=(listOf("response_view","meters","speaker_spacing","listening_distance","ear_spacing")+me.timschneeberger.rootlessjamesdsp.fusion.SurroundListeningParameters.displayKeys).any {key?.endsWith("_pro_$it")==true}
        if(prefs===SurroundController.preferences(this) && !displayOnly)SurroundProfessionalRuntime.stop(this)
        renderFrame.request()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { renderFrame.request() }
    }

    internal enum class Parameter(val title: Int, val minimum: Double, val maximum: Double, val unit: String) {
        LOW(R.string.surround_low, 10.0, 800.0, "Hz"),
        HIGH(R.string.surround_high, 4000.0, 16000.0, "Hz"),
        ATTENUATION(R.string.surround_attenuation, 0.5, 10.0, "dB"),
        DELAY(R.string.surround_delay, 20.0, 210.0, "µs")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun button() = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportFileName = savedInstanceState?.getString("surround_export_snapshot")
        val binding = ActivityGraphicEqBinding.inflate(layoutInflater)
        binding.peqToolbarSummaryRow.visibility = View.GONE
        binding.toolbar.layoutParams = binding.toolbar.layoutParams.apply { height = dp(56) }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.surround_title)
        EqualizerToolbarLayout.useMainMenuTitleAppearance(binding.toolbar)
        professionalTitle = ProfessionalTitleBar.install(binding.toolbar, getString(R.string.surround_title))
        professionalTitle.configureBassCompact()
        // This activity reuses the EQ shell, whose 64dp minimum would undo the compact
        // title frame's reclaimed bottom margin. Match the plain professional page.
        ((binding.toolbar.parent as? View)?.parent as? View)?.minimumHeight = 0
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        val controlsArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        power = SpringMaterialSwitch(this).apply {
            id = R.id.surround_power
            minimumHeight = 0
            background = null
            isSaveEnabled = false
            springEnabled = true
            scaleX = 46f / 52f
            scaleY = 46f / 52f
            setCheckedImmediately(SurroundController.enabled(this@SurroundActivity))
            setOnCheckedChangeListener { _, checked ->
                if (!refreshing) {
                    if (!SurroundController.setEnabled(this@SurroundActivity, checked))
                        Toast.makeText(this@SurroundActivity, R.string.surround_driver_required, Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
        }
        professionalTitle.setSwitch(power)
        status = TextView(this).apply {
            id = R.id.surround_driver_status
            text = getString(R.string.surround_driver_required)
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        controlsArea.addView(status)
        Parameter.values().forEach { parameter ->
            val name=TextView(this).apply {
                id=View.generateViewId()
                tag="surround_focus_legacy_${parameter.name.lowercase(Locale.ROOT)}"
                text = getString(parameter.title)
                textSize = 15f
                setPadding(0, dp(14), 0, dp(2))
            }
            controlsArea.addView(name)
            ParameterHelp.attachTitle(name,getString(parameter.title),
                range="范围 ${format(parameter,parameter.minimum)} 至 ${format(parameter,parameter.maximum)} ${parameter.unit}；± 每次 ${if(parameter==Parameter.ATTENUATION)"0.01 dB"else"1 ${parameter.unit}"}。点击数值输入，双击滑块圆点恢复当前槽默认值。")
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val value = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                tag = "surround_value_${parameter.name}"
                textSize = 14f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(4)
                maxLines = 1
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { editValue(parameter) }
            }
            val slider = Slider(this).apply {
                tag = "surround_slider_${parameter.name}"
                valueFrom = parameter.minimum.toFloat()
                valueTo = parameter.maximum.toFloat()
                stepSize = if (parameter == Parameter.ATTENUATION) 0.05f else 1f
                this.value = valueFrom
                contentDescription = getString(parameter.title)
                SliderAppearance.apply(this)
                setGlassLabelFormatter { "${format(parameter, it.toDouble())} ${parameter.unit}" }
                isTickVisible = false
                addOnChangeListener { _, selected, fromUser ->
                    if (fromUser && !refreshing) updateValue(parameter, selected.toDouble())
                }
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) { trackingSliders += slider }
                    override fun onStopTrackingTouch(slider: Slider) {
                        trackingSliders -= slider
                        renderFrame.request()
                    }
                })
            }
            controls += slider to value
            val actions=listOf("−","+").mapIndexed {index,label->button().apply {
                text=label;tag="surround_legacy_${parameter.name}_${index}";minWidth=0;minimumWidth=0
                minHeight=0;minimumHeight=0;insetTop=0;insetBottom=0;textSize=18f;maxLines=1;setPadding(0,0,0,0)
                setOnClickListener {
                    val step=if(parameter==Parameter.ATTENUATION)0.01 else 1.0
                    updateValue(parameter,(currentValue(parameter)+if(index==0)-step else step).coerceIn(parameter.minimum,parameter.maximum))
                }
            }}
            row.addView(actions[0],LinearLayout.LayoutParams(dp(30),dp(30)))
            row.addView(slider,LinearLayout.LayoutParams(0,dp(30),1f))
            row.addView(actions[1],LinearLayout.LayoutParams(dp(30),dp(30)))
            row.addView(value,LinearLayout.LayoutParams(dp(104),dp(30)))
            legacyActions[parameter]=actions
            controlsArea.addView(row)
        }
        body.addView(controlsArea)
        professional=SurroundProfessionalEditor(this,body)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dashboardPinnedArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(dashboardPinnedArea, LinearLayout.LayoutParams(-1, -2))
        dashboardScrollingArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val originalContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(dashboardScrollingArea, LinearLayout.LayoutParams(-1, -2))
            addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        originalScroll = ScrollView(this).apply { id = R.id.surround_scroll; addView(originalContent) }
        page.addView(originalScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { id = R.id.surround_footer; gravity = Gravity.CENTER_VERTICAL }
        fun addFooterAction(action: MaterialButton) {
            action.apply {
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                insetTop = 0
                insetBottom = 0
                includeFontPadding = false
                textSize = 14f
                cornerRadius = dp(12)
                stateListAnimator = null
                elevation = 0f
                clipToOutline = true
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            // Bound both the native press ripple and glass rebound to the original button.
            val pressBounds = FrameLayout(this).apply {
                clipChildren = true
                clipToPadding = true
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                    }
                }
                addView(action, FrameLayout.LayoutParams(-2,resources.getDimensionPixelSize(R.dimen.bottom_control_touch_size)))
            }
            footer.addView(FrameLayout(this).apply {
                addView(pressBounds, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }, LinearLayout.LayoutParams(0,resources.getDimensionPixelSize(R.dimen.bottom_control_touch_size),1f))
        }
        addFooterAction(button().apply {
            id = R.id.surround_reset
            text = "重置"
            contentDescription = getString(R.string.surround_reset)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SurroundActivity)
                    .setTitle("重置扬声器串音抵消")
                    .setMessage("确定将扬声器串音抵消恢复为默认设置吗？")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("重置") { _, _ -> SurroundController.reset(this@SurroundActivity) }
                    .show()
            }
        })
        addFooterAction(button().apply {
            id = R.id.surround_user_presets
            text = getString(R.string.surround_presets_button)
            setOnClickListener { showPresets() }
        })
        page.addView(footer, LinearLayout.LayoutParams(-1, -2))
        binding.params.addView(page)
        dashboard = SurroundDashboardCoordinator(this)
        focusObserver = SurroundParameterFocusObserver(body) { key -> dashboard?.followParameter(key) }
        stores = listOf(SurroundController.preferences(this), SurroundController.statusPreferences(this))
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        registerLocalReceiver(receiver, IntentFilter().apply {addAction(Constants.ACTION_PRESET_LOADED);addAction(SurroundProfessionalRuntime.ACTION)})
        refresh()
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.RESUMED){
            try {while(true){
                val engines=runCatching {(application as? MainApplication)?.rootSessionDatabase?.sessionList?.values?.mapNotNull {(it as? RemoteEffectSession)?.effect}}.getOrNull().orEmpty()
                val poll=SurroundProfessionalRuntime.pollState()
                val readings=withContext(Dispatchers.IO){engines.mapNotNull {engine->runCatching {engine.outputRuntimeSession()?.let {engine.surroundRuntimeReading(it)}}.getOrNull()}}
                SurroundProfessionalRuntime.publish(this@SurroundActivity,readings,poll);delay(250)
            }}finally{SurroundProfessionalRuntime.stop(this@SurroundActivity);SurroundProfessionalRuntime.publish(this@SurroundActivity,emptyList())}
        }}
    }

    private fun valueOf(parameter: Parameter, settings: SurroundController.Settings): Double = when (parameter) {
        Parameter.LOW -> settings.lowHz.toDouble()
        Parameter.HIGH -> settings.highHz.toDouble()
        Parameter.ATTENUATION -> settings.attenuationMilliDb / 1000.0
        Parameter.DELAY -> settings.delayUs.toDouble()
    }

    private fun currentValue(parameter: Parameter) = valueOf(parameter, SurroundController.settings(this))
    private fun defaultValue(parameter: Parameter) = valueOf(parameter, SurroundController.defaults[SurroundController.selected(this)])
    private fun resetParameter(parameter: Parameter) { updateValue(parameter, defaultValue(parameter)) }

    internal fun updateValue(parameter: Parameter, value: Double): Boolean {
        if (!value.isFinite() || value < parameter.minimum || value > parameter.maximum) return false
        val old = SurroundController.settings(this)
        val next = when (parameter) {
            Parameter.LOW -> old.copy(lowHz = value.roundToInt())
            Parameter.HIGH -> old.copy(highHz = value.roundToInt())
            Parameter.ATTENUATION -> old.copy(attenuationMilliDb = (value * 1000).roundToInt())
            Parameter.DELAY -> old.copy(delayUs = value.roundToInt())
        }
        SurroundController.save(this, next)
        return true
    }

    private fun format(parameter: Parameter, value: Double) =
        if (parameter == Parameter.ATTENUATION) String.format(Locale.ROOT, if ((value * 1000).roundToInt() % 10 == 0) "%.2f" else "%.3f", value)
        else value.roundToInt().toString()

    private fun editValue(parameter: Parameter) {
        val input = EditText(this).apply {
            id = android.R.id.edit
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(format(parameter, currentValue(parameter)))
            selectAll()
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(parameter.title).setView(input)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().toDoubleOrNull()
                if (value != null && updateValue(parameter, value)) dialog.dismiss()
                else input.error = getString(R.string.surround_range, format(parameter, parameter.minimum),
                    format(parameter, parameter.maximum), parameter.unit)
            }
        }
        valueDialogs += dialog
        dialog.setOnDismissListener { valueDialogs.remove(dialog) }
        dialog.show()
    }

    private fun refresh() {
        if (controls.size != 4) return
        refreshing = true
        val supported = SurroundController.supported(this)
        val enabled = SurroundController.enabled(this)
        if (power.isChecked != enabled) power.setCheckedImmediately(enabled)
        power.contentDescription = getString(when {
            enabled && !supported -> R.string.surround_power_pending
            enabled -> R.string.surround_power_on
            else -> R.string.surround_power_off
        })
        status.isVisible = !supported
        Parameter.values().forEachIndexed { index, parameter ->
            val value = currentValue(parameter)
            val slider = controls[index].first
            // Input fields retain their precise value; sliders display their nearest legal tick.
            val tick = (slider.valueFrom + ((value - slider.valueFrom) / slider.stepSize).roundToInt() * slider.stepSize)
                .coerceIn(slider.valueFrom, slider.valueTo)
            if (slider !in trackingSliders && slider.value != tick) slider.value = tick
            controls[index].second.apply {
                val next="${format(parameter, value)} ${parameter.unit}"
                if(text.toString()!=next)text=next
                contentDescription = "${getString(parameter.title)}，$text"
            }
            val editable=parameter !in listOf(Parameter.ATTENUATION,Parameter.DELAY) || !SurroundController.professionalSupported(this) || !SurroundProfessionalParameters.on(SurroundController.settings(this),"enabled")
            slider.isEnabled=editable;controls[index].second.isEnabled=editable;legacyActions[parameter]?.forEach {it.isEnabled=editable}
            val original=defaultValue(parameter).toFloat()
            val defaultTick=(slider.valueFrom+((original-slider.valueFrom)/slider.stepSize).roundToInt()*slider.stepSize).coerceIn(slider.valueFrom,slider.valueTo)
            SliderDoubleTapReset.install(slider, defaultTick,
                onCancel = { trackingSliders -= slider; renderFrame.request() },
                resetLabel = getString(R.string.surround_reset_parameter),thumbOnly=true) { resetParameter(parameter) }
        }
        professional?.render()
        dashboard?.refreshConfiguration()
        refreshing = false
        presetDialog?.refreshScope()
    }

    private fun showPresets() {
        presetDialog?.dismiss()
        presetDialog = SurroundPresetsDialog(this, SurroundPresetStore(this),
            readCurrent = { SurroundController.settings(this) },
            applyState = { SurroundController.save(this, it) },
            requestImport = {
                runCatching { importPresets.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                    .onFailure { presetMessage(getString(R.string.surround_presets_picker_unavailable)) }
            },
            requestExport = { id -> exportPreset(id) }).also { it.show() }
    }

    private fun presetMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun exportSnapshotFile(name: String?): File? = name
        ?.takeIf { it.matches(Regex("surround-export-[0-9a-f-]{36}\\.json")) }
        ?.let { File(cacheDir, it) }

    private fun exportPreset(id: String?) {
        val snapshot = File(cacheDir, "surround-export-${UUID.randomUUID()}.json")
        try {
            val store = SurroundPresetStore(this)
            val name = if (id == null) "扬声器串音抵消预设.json" else (store.load().first { it.id == id }.name + ".json")
            snapshot.writeText(store.exportJson(id), Charsets.UTF_8)
            pendingExportFileName = snapshot.name
            exportPresets.launch(name.map { if (it in "\\/:*?\"<>|") '_' else it }.joinToString(""))
        } catch (failure: Exception) {
            snapshot.delete()
            pendingExportFileName = null
            presetMessage(failure.message ?: getString(R.string.surround_presets_export_failed))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("surround_export_snapshot", pendingExportFileName)
        super.onSaveInstanceState(outState)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        focusObserver?.observe(event)
        return super.dispatchTouchEvent(event)
    }

    internal fun setDashboardActions(owner: View, actions: List<View>) {
        dashboardActionsOwner = owner
        professionalTitle.setTrailingActions(actions)
    }

    internal fun clearDashboardActions(owner: View) {
        if (dashboardActionsOwner === owner) {
            dashboardActionsOwner = null
            professionalTitle.setTrailingActions(emptyList())
        }
    }

    internal fun setDashboardHeader(header: View, pinned: Boolean) {
        dashboardHeader = header
        val target = if (pinned) dashboardPinnedArea else dashboardScrollingArea
        if (header.parent === target) return
        (header.parent as? android.view.ViewGroup)?.removeView(header)
        target.addView(header, 0, LinearLayout.LayoutParams(-1, -2))
    }

    internal fun clearDashboardHeader(header: View) {
        (header.parent as? android.view.ViewGroup)?.removeView(header)
        if (dashboardHeader === header) dashboardHeader = null
    }

    override fun onDestroy() {
        renderFrame.close()
        trackingSliders.clear()
        focusObserver?.close(); focusObserver = null
        dashboard?.close(); dashboard = null
        professional?.close()
        presetDialog?.dismiss()
        valueDialogs.toList().forEach { it.dismiss() }
        stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        if (stores.isNotEmpty()) unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
    override fun onResume(){super.onResume();dashboard?.onResume()}
    override fun onPause(){dashboard?.onPause();SurroundProfessionalRuntime.stop(this);super.onPause()}
}
