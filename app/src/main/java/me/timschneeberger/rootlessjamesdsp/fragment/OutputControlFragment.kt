package me.timschneeberger.rootlessjamesdsp.fragment

import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter

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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.activity.AllowlistActivity
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.ProfessionalRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.fusion.OutputDefaults
import me.timschneeberger.rootlessjamesdsp.session.root.RootProcessingPolicy
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.ProcessingAppPolicy
import me.timschneeberger.rootlessjamesdsp.utils.RootAccessStatus
import me.timschneeberger.rootlessjamesdsp.utils.isRoot
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import me.timschneeberger.rootlessjamesdsp.ui.HelpPreferences
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
    private var lastRuntimeStatus = OutputRuntimeStatus.Snapshot(false, OutputRuntimeStatus.Processing.UNKNOWN)
    private val appPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == getString(R.string.key_device_profiles_enable)) renderRoute()
        if (key == null || key == ProcessingAppPolicy.ENABLED || key == ProcessingAppPolicy.PACKAGES ||
            key == getString(R.string.key_audioformat_processing) ||
            key == getString(R.string.key_audioformat_enhanced_processing) ||
            key == getString(R.string.key_audioformat_usb_rate_follow)) renderRuntimeStatus(lastRuntimeStatus)
    }

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
            R.string.output_limiter_threshold, R.string.fusion_output_threshold_short, -60f, -0.1f, OutputDefaults.THRESHOLD_DB, "dB")
        addControl(view, R.id.output_release, getString(R.string.key_limiter_release),
            R.string.output_limiter_release, R.string.fusion_output_release_short, 1.5f, 500f, OutputDefaults.RELEASE_MS, "ms")
        addControl(view, R.id.output_postgain, getString(R.string.key_output_postgain),
            R.string.output_control_postgain, R.string.fusion_output_gain_short, -15f, 15f, OutputDefaults.POST_GAIN_DB, "dB")
        addControl(view, R.id.output_balance, BALANCE_KEY,
            R.string.fusion_output_balance_title, R.string.fusion_output_balance_short, -100f, 100f, OutputDefaults.BALANCE, "%", true)
        reloadValues()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        requireContext().registerLocalReceiver(presetReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        routingObserver.registerOnRoutingChangeListener(this)
        appPreferences.registerOnSharedPreferenceChangeListener(appPreferenceListener)
        renderRoute()
        ProfessionalRuntimeMonitor.bind(viewLifecycleOwner, requireContext()) { renderRuntimeStatus(it) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RootAccessStatus.state.collect { renderRuntimeStatus(lastRuntimeStatus) }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Per-device preferences remain authoritative after a rotation or process restore.
        reloadValues()
    }

    override fun onResume() {
        super.onResume()
        renderRuntimeStatus(lastRuntimeStatus)
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
            setGlassLabelFormatter { formatValue(control, it) }
            addOnChangeListener { _, value, fromUser ->
                renderValue(control, value)
                if (fromUser) saveValue(control, value)
            }
        }
        control.valueRow.setOnClickListener { showValueDialog(control) }
        val showControlHelp = {
            ParameterHelp.show(requireContext(), control.title,
                range = "范围：${control.minimum} … ${control.maximum} ${control.unit}；默认值：${formatValue(control, control.default)}")
        }
        val controlHelp = tile.findViewById<View>(R.id.output_help).apply {
            contentDescription = "${control.title} · 参数说明"
            setOnClickListener { showControlHelp() }
        }
        HelpPreferences.bindTitleHelp(tile.findViewById(R.id.output_label), controlHelp,
            onHelp = showControlHelp)
        fun bindStep(@IdRes id: Int, delta: Float) {
            tile.findViewById<View>(id).apply {
                contentDescription = "${control.title} ${if (delta < 0) "减少" else "增加"} 1 ${control.unit}"
                setOnClickListener {
                    val next = (control.slider.value + delta).coerceIn(control.minimum, control.maximum)
                    if (next != control.slider.value) saveValue(control, next)
                }
            }
        }
        bindStep(R.id.output_decrease, -1f)
        bindStep(R.id.output_increase, 1f)
        SliderDoubleTapReset.install(control.slider, control.default, resetLabel = "恢复默认值", thumbOnly = true) {
            saveValue(control, control.default)
        }
    }

    private fun reloadValues() {
        if (!::preferences.isInitialized) return
        val storedValues = preferences.all
        controls.forEach { control ->
            val stored = (storedValues[control.key] as? Number)?.toFloat() ?: control.default
            val bounded = (if (stored.isFinite()) stored else control.default)
                .coerceIn(control.minimum, control.maximum)
            val value = if (control.integral) bounded.roundToInt().toFloat() else bounded
            if (control.slider.value != value) control.slider.value = value
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
        if (control.valueView.text.toString() != text) control.valueView.text = text
        val description = "${control.title}: $text"
        if (control.valueRow.contentDescription != description) control.valueRow.contentDescription = description
    }

    internal fun renderRuntimeStatus(status: OutputRuntimeStatus.Snapshot,
        waitingForPlayerSession: Boolean = status.mode?.let { it == OutputRuntimeStatus.Mode.PLAYER_SESSION }
            ?: ((context?.applicationContext as? MainApplication)?.isPlayerSessionMode == true)) {
        lastRuntimeStatus = status
        val root = view ?: return
        val scopePreferences = appPreferences.preferences.all
        val emptySpecified = waitingForPlayerSession && !status.connected && scopePreferences[ProcessingAppPolicy.ENABLED] == true &&
            (scopePreferences[ProcessingAppPolicy.PACKAGES] as? Set<*>)?.isNotEmpty() != true
        val rootUnavailable = isRoot() && !status.connected && !emptySpecified &&
            scopePreferences[getString(R.string.key_powered_on)] != false &&
            RootAccessStatus.state.value == RootAccessStatus.State.UNAVAILABLE
        root.findViewById<TextView>(R.id.output_driver_status).apply {
            setText(when {
                emptySpecified -> R.string.output_scope_empty
                rootUnavailable -> R.string.output_root_access_required
                status.connected -> R.string.output_driver_connected
                else -> R.string.output_driver_disconnected
            })
            if (emptySpecified) {
                setTextColor(com.google.android.material.color.MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant))
            } else setTextColor(ContextCompat.getColor(context, if (status.connected)
                R.color.output_status_connected else R.color.output_status_disconnected))
        }
        val sampleRate = root.findViewById<TextView>(R.id.output_sample_rate)
        sampleRate.setOnClickListener(if (emptySpecified) View.OnClickListener {
            if (!isRoot() || RootProcessingPolicy.playerSessions(requireContext())) {
                startActivity(Intent(requireContext(), AllowlistActivity::class.java))
            }
        } else null)
        sampleRate.isClickable = emptySpecified
        if (emptySpecified) {
            sampleRate.setText(R.string.output_scope_add)
            sampleRate.setTextColor(com.google.android.material.color.MaterialColors.getColor(sampleRate,
                com.google.android.material.R.attr.colorPrimary))
            return
        }
        if (rootUnavailable) {
            sampleRate.setText(R.string.output_root_access_help)
            sampleRate.setTextColor(ContextCompat.getColor(requireContext(), R.color.output_status_disconnected))
            return
        }
        if (status.mode == OutputRuntimeStatus.Mode.GLOBAL && status.globalIssue != null) {
            sampleRate.setText(when (status.globalIssue) {
                OutputRuntimeStatus.GlobalIssue.SUSPENDED -> R.string.output_global_suspended
                OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> R.string.output_global_different_output
                OutputRuntimeStatus.GlobalIssue.NO_PROCESSING -> R.string.output_global_not_processing
            })
            sampleRate.setTextColor(ContextCompat.getColor(requireContext(), R.color.output_status_disconnected))
            return
        }
        val standby = status.connected && status.processing == OutputRuntimeStatus.Processing.IDLE &&
            status.activeSessions.isEmpty()
        sampleRate.setTextColor(if (standby)
            com.google.android.material.color.MaterialColors.getColor(sampleRate,
                com.google.android.material.R.attr.colorOnSurfaceVariant)
        else ContextCompat.getColor(requireContext(),
            if (status.processing == OutputRuntimeStatus.Processing.ACTIVE)
                R.color.output_status_connected else R.color.output_status_disconnected))
        sampleRate.text = if (!status.connected && waitingForPlayerSession)
            getString(R.string.output_runtime_waiting_session) else when (status.processing) {
            OutputRuntimeStatus.Processing.IDLE -> getString(if (standby)
                R.string.output_runtime_standby else R.string.output_rate_idle)
            OutputRuntimeStatus.Processing.UNKNOWN -> getString(R.string.output_rate_unknown)
            OutputRuntimeStatus.Processing.ACTIVE -> {
                fun kilohertz(rate: Int) = if (rate % 1000 == 0) (rate / 1000).toString()
                    else String.format(Locale.ROOT, "%.3f", rate / 1000.0).trimEnd('0').trimEnd('.')
                if (status.activeSessions.isNotEmpty()) status.activeSessions.joinToString("\n") { session ->
                    session.sessionId?.let { getString(if (status.mode == OutputRuntimeStatus.Mode.GLOBAL && it == 0)
                        R.string.output_runtime_global_rate else R.string.output_runtime_session_rate,
                        it, kilohertz(session.rate)) }
                        ?: getString(R.string.output_rate_value, kilohertz(session.rate))
                } else getString(R.string.output_rate_value, status.rates.joinToString(" / ", transform = ::kilohertz))
            }
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

    override fun onDestroyView() {
        routingObserver.unregisterOnRoutingChangeListener(this)
        appPreferences.unregisterOnSharedPreferenceChangeListener(appPreferenceListener)
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        requireContext().unregisterLocalReceiver(presetReceiver)
        controls.clear()
        super.onDestroyView()
    }

    companion object {
        const val BALANCE_KEY = "balance"
    }
}
