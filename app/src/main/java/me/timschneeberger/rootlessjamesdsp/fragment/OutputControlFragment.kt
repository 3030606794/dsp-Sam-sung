package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.dpToPx
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Kept outside the parameter scroll view; uses the existing per-device output store. */
class OutputControlFragment : Fragment(R.layout.fragment_output_control) {
    private lateinit var preferences: SharedPreferences
    private val controls = mutableListOf<Control>()

    private data class Control(
        val tile: View,
        val key: String,
        val title: String,
        val minimum: Float,
        val maximum: Float,
        val default: Float,
        val unit: String,
        val integral: Boolean = false,
    ) {
        val slider: Slider get() = tile.findViewById(R.id.output_slider)
        val valueView: TextView get() = tile.findViewById(R.id.output_value)
        val valueRow: View get() = tile.findViewById(R.id.output_value_row)
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        reloadValues()
    }
    private val presetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_PRESET_LOADED) {
                // Presets replace the XML file directly; reacquiring with MULTI_PROCESS
                // asks Android to reload it, as the existing preference pages do.
                preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
                preferences = openPreferences()
                preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
                reloadValues()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = openPreferences()
        addControl(view, R.id.output_threshold, getString(R.string.key_limiter_threshold),
            R.string.output_limiter_threshold, R.string.fusion_output_threshold_short, -60f, -0.1f, -0.1f, "dB")
        addControl(view, R.id.output_release, getString(R.string.key_limiter_release),
            R.string.output_limiter_release, R.string.fusion_output_release_short, 1.5f, 500f, 60f, "ms")
        addControl(view, R.id.output_postgain, getString(R.string.key_output_postgain),
            R.string.output_control_postgain, R.string.fusion_output_gain_short, -15f, 15f, 0f, "dB")
        addControl(view, R.id.output_balance, BALANCE_KEY,
            R.string.fusion_output_balance_title, R.string.fusion_output_balance_short, -100f, 100f, 0f, "%", true)
        reloadValues()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        requireContext().registerLocalReceiver(presetReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
    }

    @Suppress("DEPRECATION")
    private fun openPreferences(): SharedPreferences =
        requireContext().getSharedPreferences(Constants.PREF_OUTPUT, Context.MODE_MULTI_PROCESS)

    private fun addControl(
        parent: View, @IdRes tileId: Int, key: String, @StringRes title: Int,
        @StringRes shortTitle: Int, minimum: Float, maximum: Float, default: Float,
        unit: String, integral: Boolean = false,
    ) {
        val tile = parent.findViewById<View>(tileId)
        val control = Control(tile, key, getString(title), minimum, maximum, default, unit, integral)
        controls.add(control)
        tile.findViewById<TextView>(R.id.output_label).setText(shortTitle)
        control.slider.apply {
            valueFrom = minimum
            valueTo = maximum
            stepSize = if (integral) 1f else 0f
            contentDescription = control.title
            setLabelFormatter { formatValue(control, it) }
            addOnChangeListener { _, value, fromUser ->
                renderValue(control, value)
                if (fromUser) saveValue(control, value)
            }
        }
        control.valueRow.setOnClickListener { showValueDialog(control) }
    }

    private fun reloadValues() {
        if (!::preferences.isInitialized) return
        controls.forEach { control ->
            val stored = (preferences.all[control.key] as? Number)?.toFloat() ?: control.default
            val bounded = (if (stored.isFinite()) stored else control.default)
                .coerceIn(control.minimum, control.maximum)
            val value = if (control.integral) bounded.roundToInt().toFloat() else bounded
            control.slider.value = value
            renderValue(control, value)
        }
    }

    private fun saveValue(control: Control, value: Float) {
        preferences.edit().apply {
            if (control.integral) putInt(control.key, value.roundToInt())
            else putFloat(control.key, value)
        }.apply()
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }

    private fun formatValue(control: Control, value: Float): String {
        if (!control.integral) return String.format(Locale.getDefault(), "%.2f %s", value, control.unit)
        val balance = value.roundToInt()
        return when {
            balance < 0 -> getString(R.string.fusion_output_balance_left, abs(balance))
            balance > 0 -> getString(R.string.fusion_output_balance_right, balance)
            else -> getString(R.string.fusion_output_balance_center)
        }
    }

    private fun renderValue(control: Control, value: Float) {
        val text = formatValue(control, value)
        control.valueView.text = text
        control.valueRow.contentDescription = "${control.title}: $text"
    }

    private fun showValueDialog(control: Control) {
        val input = EditText(requireContext()).apply {
            id = android.R.id.edit
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                (if (control.integral) 0 else InputType.TYPE_NUMBER_FLAG_DECIMAL)
            setText(if (control.integral) control.slider.value.roundToInt().toString()
                else String.format(Locale.ROOT, "%.2f", control.slider.value))
            setSelectAllOnFocus(true)
            hint = "${control.minimum} … ${control.maximum} ${control.unit}"
        }
        val container = LinearLayout(requireContext()).apply {
            setPadding(24.dpToPx, 0, 24.dpToPx, 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(control.title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().replace(',', '.').toFloatOrNull()
                if (value == null || !value.isFinite() || value !in control.minimum..control.maximum ||
                    (control.integral && value != value.roundToInt().toFloat())) {
                    input.error = getString(R.string.fusion_output_value_range,
                        control.minimum.toString(), control.maximum.toString())
                } else {
                    saveValue(control, value)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        requireContext().unregisterLocalReceiver(presetReceiver)
        controls.clear()
        super.onDestroyView()
    }

    companion object {
        const val BALANCE_KEY = "balance"
    }
}
