package me.timschneeberger.rootlessjamesdsp.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityGraphicEqBinding
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundController
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundResponse
import me.timschneeberger.rootlessjamesdsp.peq.PeqResponseView
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale
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
    private var pinResponse = false
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
        val binding = ActivityGraphicEqBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.surround_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        chartArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chartArea.addView(TextView(this).apply {
            text = "${getString(R.string.surround_frequency_response)} · ${getString(R.string.surround_reference)}"
            textSize = 13f
            setPadding(dp(12), dp(12), dp(12), dp(4))
        })
        chart = PeqResponseView(this).apply { id = R.id.surround_chart }
        chartArea.addView(chart, LinearLayout.LayoutParams(-1, dp(220)))
        body.addView(chartArea)

        val controlsArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply {
            text = getString(R.string.surround_description)
            textSize = 13f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        power = AppCompatImageButton(this).apply {
            id = R.id.surround_power
            background = null
            setImageResource(R.drawable.ic_twotone_power_settings_24dp)
            imageTintList = getColorStateList(R.color.selector_floating_toggle_foreground_tint)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                if (!SurroundController.setEnabled(this@SurroundActivity, !SurroundController.enabled(this@SurroundActivity)))
                    Toast.makeText(this@SurroundActivity, R.string.surround_driver_required, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
        heading.addView(power, LinearLayout.LayoutParams(dp(48), dp(48)))
        controlsArea.addView(heading)
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
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = getString(parameter.title); textSize = 15f },
                LinearLayout.LayoutParams(0, -2, 1f))
            val value = button().apply { setOnClickListener { editValue(parameter) } }
            row.addView(value, LinearLayout.LayoutParams(-2, dp(48)))
            controlsArea.addView(row)
            val slider = Slider(this).apply {
                valueFrom = parameter.minimum.toFloat()
                valueTo = parameter.maximum.toFloat()
                stepSize = if (parameter == Parameter.ATTENUATION) 0.05f else 1f
                this.value = valueFrom
                contentDescription = getString(parameter.title)
                setLabelFormatter { format(parameter, it.toDouble()) + " " + parameter.unit }
                addOnChangeListener { _, selected, fromUser ->
                    if (fromUser && !refreshing) updateValue(parameter, selected.toDouble())
                }
            }
            controls += slider to value
            controlsArea.addView(slider)
        }
        controlsArea.addView(button().apply {
            text = getString(R.string.surround_reset)
            setOnClickListener { SurroundController.reset(this@SurroundActivity) }
        })
        body.addView(controlsArea)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pinnedArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(pinnedArea, LinearLayout.LayoutParams(-1, -2))
        page.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        binding.params.addView(page)
        pinResponse = prefsApp.preferences.getBoolean("surround_pin_response", false)
        if (pinResponse) { body.removeView(chartArea); pinnedArea.addView(chartArea) }
        stores = listOf(SurroundController.preferences(this), SurroundController.statusPreferences(this))
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        refresh()
    }

    private fun currentValue(parameter: Parameter): Double = SurroundController.settings(this).let {
        when (parameter) {
            Parameter.LOW -> it.lowHz.toDouble()
            Parameter.HIGH -> it.highHz.toDouble()
            Parameter.ATTENUATION -> it.attenuationMilliDb / 1000.0
            Parameter.DELAY -> it.delayUs.toDouble()
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
        if (parameter == Parameter.ATTENUATION) String.format(Locale.ROOT, "%.2f", value)
        else value.roundToInt().toString()

    private fun editValue(parameter: Parameter) {
        val input = EditText(this).apply {
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
            controls[index].second.text = "${format(parameter, value)} ${parameter.unit}"
        }
        val response = SurroundResponse.calculate(SurroundController.settings(this))
        chart.setExternalResponse(response.frequencies, response.decibels, 24.0)
        refreshing = false
    }

    internal fun setPinned(value: Boolean) {
        pinResponse = value
        (chartArea.parent as? android.view.ViewGroup)?.removeView(chartArea)
        if (value) pinnedArea.addView(chartArea) else body.addView(chartArea, 0)
        prefsApp.preferences.edit().putBoolean("surround_pin_response", value).apply()
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
        stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        if (stores.isNotEmpty()) unregisterLocalReceiver(receiver)
        super.onDestroy()
    }
}
