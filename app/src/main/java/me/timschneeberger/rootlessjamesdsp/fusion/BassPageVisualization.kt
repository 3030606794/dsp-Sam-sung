package me.timschneeberger.rootlessjamesdsp.fusion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.flow.MutableStateFlow
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.ui.BassDualTrackHeader
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** Page-owned visualization only: display preferences and capture never modify the DSP bank. */
internal class BassPageVisualization(
    private val fragment: Fragment,
    private val dynamic: Boolean,
    private val requestAudioPermission: () -> Unit
) : AutoCloseable {
    private val context = fragment.requireContext()
    private val host = fragment.requireActivity() as ProfessionalEffectActivity
    private val display = context.applicationContext.getSharedPreferences("bass_dual_track_display", Context.MODE_PRIVATE)
    private val prefix = if (dynamic) "dynamic" else "viper"
    private var closed = false
    private var permissionPending = false
    private var viperPreferences: SharedPreferences? = null
    private var dynamicPreferences: SharedPreferences? = null
    private val realtime = MutableStateFlow(display.getBoolean("$prefix.realtime", false) && hasAudioPermission())
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("v_bass") || key.startsWith("bass_") ||
            key == context.getString(R.string.key_bass_enable)) refreshConfiguration()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!closed && intent.action == Constants.ACTION_PRESET_LOADED) reloadPreferences()
        }
    }
    private val header = BassDualTrackHeader(context, dynamic, realtime.value,
        display.getBoolean("$prefix.pinned", true),
        onRealtimeChanged = { enabled -> selectRealtime(enabled) },
        onPinChanged = { pinned ->
            if (!closed) {
                display.edit().putBoolean("$prefix.pinned", pinned).apply()
                moveHeader(pinned)
            }
        })

    init {
        // A revoked permission must not restart capture or prompt automatically on page entry.
        if (!realtime.value && display.getBoolean("$prefix.realtime", false))
            display.edit().putBoolean("$prefix.realtime", false).apply()
        reloadPreferences()
        context.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        moveHeader(display.getBoolean("$prefix.pinned", true))
        BassEnergyMonitor.bind(fragment.viewLifecycleOwner, context, realtime,
            onSample = { sample -> if (!closed && realtime.value) header.showEnergy(sample) },
            onState = { state ->
                if (!closed && realtime.value) {
                    if (!hasAudioPermission()) {
                        applyRealtime(false)
                        Toast.makeText(context, "音频权限不可用，已切回静态模型", Toast.LENGTH_SHORT).show()
                    } else header.showCaptureState(state.label)
                }
            })
    }

    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun moveHeader(pinned: Boolean) {
        host.setPinnedHeader(header, pinned)
    }

    private fun selectRealtime(enabled: Boolean) {
        if (closed) return
        if (!enabled) {
            permissionPending = false
            applyRealtime(false)
        } else if (hasAudioPermission()) {
            applyRealtime(true)
        } else {
            // Keep the actual capture disabled until Android has granted the permission.
            header.setRealtime(false)
            if (!permissionPending) {
                permissionPending = true
                requestAudioPermission()
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (closed) return
        permissionPending = false
        applyRealtime(granted && hasAudioPermission())
        if (!realtime.value)
            Toast.makeText(context, "未授予音频权限，已切回静态模型", Toast.LENGTH_SHORT).show()
    }

    private fun applyRealtime(enabled: Boolean) {
        if (closed) return
        // Disable the capture subscription before changing the visible pane.
        if (!enabled) realtime.value = false
        display.edit().putBoolean("$prefix.realtime", enabled).apply()
        header.setRealtime(enabled)
        if (enabled) {
            header.showWaiting()
            realtime.value = true
        }
    }

    private fun reloadPreferences() {
        viperPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        dynamicPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        viperPreferences = BassController.preferences(context, true).also {
            it.registerOnSharedPreferenceChangeListener(preferenceListener)
        }
        dynamicPreferences = BassController.preferences(context, false).also {
            it.registerOnSharedPreferenceChangeListener(preferenceListener)
        }
        refreshConfiguration()
    }

    fun refreshConfiguration() {
        if (closed) return
        val viperValues = viperPreferences?.all.orEmpty()
        val dynamicValues = dynamicPreferences?.all.orEmpty()
        header.setViperConfiguration(viperValues)
        header.setDynamicConfiguration(BassParameters.dynamicPacket(dynamicValues))
        header.setEffectEnabled(if (dynamic)
            dynamicValues[context.getString(R.string.key_bass_enable)] as? Boolean ?: false
            else viperValues["v_bass"] as? Boolean ?: false)
    }

    fun onResume() {
        if (closed) return
        refreshConfiguration()
        if (realtime.value) {
            if (!hasAudioPermission()) applyRealtime(false) else header.showWaiting()
        }
    }

    fun onPause() {
        if (!closed && realtime.value) header.suspendMonitoring()
    }

    override fun close() {
        if (closed) return
        closed = true
        realtime.value = false
        viperPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        dynamicPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        viperPreferences = null
        dynamicPreferences = null
        context.unregisterLocalReceiver(receiver)
        host.clearPinnedHeader(header)
    }
}
