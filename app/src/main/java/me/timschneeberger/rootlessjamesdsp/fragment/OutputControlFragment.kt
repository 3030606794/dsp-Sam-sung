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
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.BassController
import me.timschneeberger.rootlessjamesdsp.fusion.BassRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.dpToPx
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Kept outside the parameter scroll view; uses the existing per-device output store. */
class OutputControlFragment : Fragment(R.layout.fragment_output_control), RoutingObserver.RoutingChangedCallback {
    private lateinit var preferences: SharedPreferences
    private val controls = mutableListOf<Control>()
    private val routingObserver: RoutingObserver by inject()
    private val appPreferences: Preferences.App by inject()
    private val runtimeTracker = OutputRuntimeStatus.Tracker()
    private val bassRuntimeTracker = BassRuntimeStatus.Tracker()
    private val appPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderRoute() }

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
        routingObserver.registerOnRoutingChangeListener(this)
        appPreferences.registerOnSharedPreferenceChangeListener(appPreferenceListener)
        renderRoute()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Snapshot the session map on the main thread; native reads run off the UI thread.
                runtimeTracker.clear()
                bassRuntimeTracker.clear()
                while (true) {
                    val app = context?.applicationContext as? MainApplication
                    val engines = app?.rootSessionDatabase?.sessionList?.values
                        ?.mapNotNull { (it as? RemoteEffectSession)?.effect }.orEmpty()
                    val readings = withContext(Dispatchers.IO) {
                        engines.mapNotNull { engine -> runCatching {
                            engine.outputRuntimeSession()?.let { it to engine.bassRuntimeReading(it) }
                        }.getOrNull() }
                    }
                    renderRuntimeStatus(runtimeTracker.update(readings.map { it.first }))
                    val bassEnabled = BassController.preferences(requireContext(), false)
                        .getBoolean(getString(R.string.key_bass_enable), false)
                    BassController.publishRuntime(requireContext(), bassRuntimeTracker.update(readings.mapNotNull { it.second }, bassEnabled))
                    delay(1000)
                }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Per-device preferences remain authoritative after a rotation or process restore.
        reloadValues()
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
        // Included tiles reuse their child IDs. Their state comes from preferences;
        // a flat hierarchy-state array would mix values between those children.
        tile.isSaveFromParentEnabled = false
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
        if (key == BALANCE_KEY) {
            SliderDoubleTapReset.install(control.slider, 0f) { saveValue(control, 0f) }
        }
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

    internal fun renderRuntimeStatus(status: OutputRuntimeStatus.Snapshot) {
        val root = view ?: return
        root.findViewById<TextView>(R.id.output_driver_status).setText(
            if (status.connected) R.string.output_driver_connected else R.string.output_driver_disconnected)
        root.findViewById<TextView>(R.id.output_sample_rate).text = when (status.processing) {
            OutputRuntimeStatus.Processing.IDLE -> getString(R.string.output_rate_idle)
            OutputRuntimeStatus.Processing.UNKNOWN -> getString(R.string.output_rate_unknown)
            OutputRuntimeStatus.Processing.ACTIVE -> getString(R.string.output_rate_value,
                status.rates.joinToString(" / ") { rate ->
                    if (rate % 1000 == 0) (rate / 1000).toString()
                    else String.format(Locale.ROOT, "%.3f", rate / 1000.0).trimEnd('0').trimEnd('.')
                })
        }
    }

    override fun onRoutingDeviceChanged(device: RoutingObserver.Device?) { renderRoute() }

    private fun renderRoute() {
        view?.findViewById<TextView>(R.id.output_route)?.apply {
            // The existing profile row already shows the current output route when enabled.
            isVisible = !appPreferences.get<Boolean>(R.string.key_device_profiles_enable)
            text = getString(R.string.output_route_value,
                routingObserver.currentDevice?.name ?: getString(R.string.output_route_unknown))
        }
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

    override fun onPause() {
        BassController.publishRuntime(requireContext(), BassRuntimeStatus.Snapshot(BassRuntimeStatus.State.UNKNOWN))
        super.onPause()
    }

    override fun onDestroyView() {
        routingObserver.unregisterOnRoutingChangeListener(this)
        appPreferences.unregisterOnSharedPreferenceChangeListener(appPreferenceListener)
        runtimeTracker.clear()
        bassRuntimeTracker.clear()
        BassController.publishRuntime(requireContext(), BassRuntimeStatus.Snapshot(BassRuntimeStatus.State.UNKNOWN))
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        requireContext().unregisterLocalReceiver(presetReceiver)
        controls.clear()
        super.onDestroyView()
    }

    companion object {
        const val BALANCE_KEY = "balance"
    }
}
