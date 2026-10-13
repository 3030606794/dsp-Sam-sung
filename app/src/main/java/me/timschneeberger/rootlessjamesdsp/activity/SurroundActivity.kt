package me.timschneeberger.rootlessjamesdsp.activity

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityGraphicEqBinding
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundController
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundResponse
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundPresetCodec
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundPresetStore
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundPresetsDialog
import me.timschneeberger.rootlessjamesdsp.peq.PeqResponseView
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class SurroundActivity : BaseActivity() {
    private lateinit var chart: PeqResponseView
    private lateinit var body: LinearLayout
    private lateinit var pinnedArea: LinearLayout
    private lateinit var chartArea: LinearLayout
    private lateinit var power: AppCompatImageButton
    private lateinit var status: TextView
    private lateinit var presets: MaterialButtonToggleGroup
    private val presetIds = IntArray(8) { android.view.View.generateViewId() }
    private var stores = emptyList<SharedPreferences>()
    private val controls = mutableListOf<Pair<Slider, MaterialButton>>()
    private var refreshing = false
    private var pinResponse = true
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
    private val pinMenuId = 0x451101
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
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
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.surround_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        chartArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chart = PeqResponseView(this).apply { id = R.id.surround_chart }
        chartArea.addView(chart, LinearLayout.LayoutParams(-1, dp(220)))
        body.addView(chartArea)

        val controlsArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        power = AppCompatImageButton(this).apply {
            id = R.id.surround_power
            background = null
            setImageResource(R.drawable.ic_twotone_power_settings_24dp)
            imageTintList = getColorStateList(R.color.selector_floating_toggle_foreground_tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                if (!SurroundController.setEnabled(this@SurroundActivity, !SurroundController.enabled(this@SurroundActivity)))
                    Toast.makeText(this@SurroundActivity, R.string.surround_driver_required, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
        status = TextView(this).apply {
            id = R.id.surround_driver_status
            text = getString(R.string.surround_driver_required)
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        controlsArea.addView(status)
        presets = MaterialButtonToggleGroup(this).apply {
            id = R.id.surround_presets
            isSingleSelection = true
            isSelectionRequired = true
        }
        presetIds.forEachIndexed { index, viewId ->
            presets.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = viewId
                text = "${index + 1}"
                contentDescription = getString(R.string.surround_slot, index + 1)
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        presets.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !refreshing) {
                val slot = presetIds.indexOf(id)
                if (slot >= 0) SurroundController.select(this, slot)
            }
        }
        controlsArea.addView(presets, LinearLayout.LayoutParams(-1, -2))
        Parameter.values().forEach { parameter ->
            controlsArea.addView(TextView(this).apply {
                text = getString(parameter.title)
                textSize = 15f
                setPadding(0, dp(14), 0, dp(2))
            })
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val value = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                tag = "surround_value_${parameter.name}"
                textSize = 14f
                minWidth = 0
                minimumWidth = 0
                maxLines = 1
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { editValue(parameter) }
                installValueReset(this, parameter)
            }
            val slider = Slider(this).apply {
                tag = "surround_slider_${parameter.name}"
                valueFrom = parameter.minimum.toFloat()
                valueTo = parameter.maximum.toFloat()
                stepSize = if (parameter == Parameter.ATTENUATION) 0.05f else 1f
                this.value = valueFrom
                contentDescription = getString(parameter.title)
                labelBehavior = LabelFormatter.LABEL_GONE
                isTickVisible = false
                addOnChangeListener { _, selected, fromUser ->
                    if (fromUser && !refreshing) updateValue(parameter, selected.toDouble())
                }
            }
            controls += slider to value
            row.addView(slider, LinearLayout.LayoutParams(0, dp(56), 1f))
            row.addView(value, LinearLayout.LayoutParams(dp(108), dp(48)))
            controlsArea.addView(row)
        }
        body.addView(controlsArea)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pinnedArea = LinearLayout(this).apply { id = R.id.surround_pinned_area; orientation = LinearLayout.VERTICAL }
        page.addView(pinnedArea, LinearLayout.LayoutParams(-1, -2))
        page.addView(ScrollView(this).apply { id = R.id.surround_scroll; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { id = R.id.surround_footer; gravity = Gravity.CENTER_VERTICAL }
        footer.addView(button().apply {
            id = R.id.surround_reset
            text = getString(R.string.surround_reset)
            setOnClickListener { SurroundController.reset(this@SurroundActivity) }
        }, LinearLayout.LayoutParams(0, -1, 1f))
        footer.addView(power, LinearLayout.LayoutParams(0, -1, 1f))
        footer.addView(button().apply {
            id = R.id.surround_user_presets
            text = getString(R.string.surround_presets_button)
            setOnClickListener { showPresets() }
        }, LinearLayout.LayoutParams(0, -1, 1f))
        page.addView(footer, LinearLayout.LayoutParams(-1, dp(64)))
        binding.params.addView(page)
        // Start pinned for this layout revision; subsequent explicit choices are remembered.
        pinResponse = prefsApp.preferences.getBoolean("surround_pin_response_v2", true)
        if (pinResponse) { body.removeView(chartArea); pinnedArea.addView(chartArea) }
        stores = listOf(SurroundController.preferences(this), SurroundController.statusPreferences(this))
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        refresh()
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

    @SuppressLint("ClickableViewAccessibility")
    private fun installValueReset(button: MaterialButton, parameter: Parameter) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (!isFinishing && !isDestroyed) button.performClick()
                return true
            }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                resetParameter(parameter)
                return true
            }
        }).apply { setIsLongpressEnabled(false) }
        button.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            button.isPressed = event.actionMasked == MotionEvent.ACTION_DOWN
            true
        }
        ViewCompat.addAccessibilityAction(button, getString(R.string.surround_reset_parameter)) { _, _ ->
            resetParameter(parameter)
            true
        }
    }

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
        if (!::chart.isInitialized || controls.size != 4) return
        refreshing = true
        val supported = SurroundController.supported(this)
        val enabled = SurroundController.enabled(this)
        power.isSelected = supported && enabled
        power.contentDescription = getString(when {
            enabled && !supported -> R.string.surround_power_pending
            enabled -> R.string.surround_power_on
            else -> R.string.surround_power_off
        })
        status.isVisible = !supported
        presets.check(presetIds[SurroundController.selected(this)])
        Parameter.values().forEachIndexed { index, parameter ->
            val value = currentValue(parameter)
            val slider = controls[index].first
            // Input fields retain their precise value; sliders display their nearest legal tick.
            slider.value = (slider.valueFrom + ((value - slider.valueFrom) / slider.stepSize).roundToInt() * slider.stepSize)
                .coerceIn(slider.valueFrom, slider.valueTo)
            controls[index].second.apply {
                text = "${format(parameter, value)} ${parameter.unit}"
                contentDescription = "${getString(parameter.title)}，$text"
            }
            SliderDoubleTapReset.install(slider, defaultValue(parameter).toFloat(),
                resetLabel = getString(R.string.surround_reset_parameter)) { resetParameter(parameter) }
        }
        val response = SurroundResponse.calculate(SurroundController.settings(this))
        chart.setExternalResponse(response.frequencies, response.decibels, 24.0)
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
            val name = if (id == null) "环绕预设.json" else (store.load().first { it.id == id }.name + ".json")
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

    internal fun setPinned(value: Boolean) {
        pinResponse = value
        (chartArea.parent as? android.view.ViewGroup)?.removeView(chartArea)
        if (value) pinnedArea.addView(chartArea) else body.addView(chartArea, 0)
        prefsApp.preferences.edit().putBoolean("surround_pin_response_v2", value).apply()
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, pinMenuId, 0, R.string.peq_pin_response).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(pinMenuId)?.apply {
            setTitle(if (pinResponse) R.string.peq_unpin_response else R.string.peq_pin_response)
            setIcon(if (pinResponse) R.drawable.ic_peq_pin else R.drawable.ic_peq_pin_off)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == pinMenuId) { setPinned(!pinResponse); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        presetDialog?.dismiss()
        valueDialogs.toList().forEach { it.dismiss() }
        stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        if (stores.isNotEmpty()) unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
}
