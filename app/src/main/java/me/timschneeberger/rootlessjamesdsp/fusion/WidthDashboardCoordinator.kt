package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.ui.WidthDashboardHeader
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** Display preferences are separate from all existing stereo-width processing controls. */
internal class WidthDashboardCoordinator(private val fragment: Fragment) : AutoCloseable {
    private val context = fragment.requireContext()
    private val host = fragment.requireActivity() as ProfessionalEffectActivity
    private val display = context.applicationContext.getSharedPreferences("width_dashboard_display", Context.MODE_PRIVATE)
    private var preferences: SharedPreferences? = null
    private var closed = false
    private var resumed = false
    private var monitoring = false
    private var pinned = display.getBoolean("pinned", true)
    private var effectEnabled = false
    private var lastPausedLabel: String? = null
    private val enabled = MutableStateFlow(false)
    private var configurationSnapshot: Job? = null
    private var focusObserver: WidthParameterFocusObserver? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in WidthParameters.newKeys || key in WidthAdvancedParameters.keys || key == WidthParameters.ENABLED || key == WidthParameters.LEGACY) refreshConfiguration()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            if (intent.action == Constants.ACTION_PRESET_LOADED) reloadPreferences()
            else if (intent.action == WidthController.STATUS_ACTION) refreshConfiguration()
        }
    }
    private val header: WidthDashboardHeader = WidthDashboardHeader(context,
        onMonitorChanged = { value ->
            if (!closed) { monitoring = value; renderControls(); updateSubscription() }
        }, onPinChanged = { value ->
            if (!closed) {
                pinned = value; display.edit().putBoolean("pinned", value).apply()
                renderControls(); host.setPinnedHeader(headerView(), value)
            }
        })

    init {
        reloadPreferences()
        renderControls()
        context.registerLocalReceiver(receiver, IntentFilter().apply {
            addAction(Constants.ACTION_PRESET_LOADED)
            addAction(WidthController.STATUS_ACTION)
        })
        host.setPinnedHeader(header, pinned)
        host.setBassGraphActions(header, header.toolbarActions)
        (fragment as? PreferenceFragmentCompat)?.let { nativeFragment ->
            focusObserver = WidthParameterFocusObserver(nativeFragment) { key ->
                if (!closed && resumed) header.followParameter(key)
            }
        }
        WidthFocusRuntimeMonitor.bind(fragment.viewLifecycleOwner, context, enabled) { sample ->
            if (!closed && enabled.value) { lastPausedLabel = null; header.showRuntime(sample) }
        }
    }
    private fun headerView(): WidthDashboardHeader = header
    private fun renderControls() {
        header.setControls(monitoring, pinned)
    }
    private fun reloadPreferences() {
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = WidthController.preferences(context).also { it.registerOnSharedPreferenceChangeListener(listener) }
        refreshConfiguration()
    }
    fun refreshConfiguration() {
        if (closed) return
        val values = preferences?.all.orEmpty()
        effectEnabled = values[WidthParameters.ENABLED] as? Boolean ?: false
        val rate = WidthController.runtime.mapNotNull { it.snapshot?.rate }.filter { it in 8000..768000 }.distinct().singleOrNull()
        header.setConfiguration(values, rate)
        updateSubscription()
    }
    private fun updateSubscription() {
        val wasEnabled = enabled.value
        enabled.value = !closed && resumed && monitoring && effectEnabled
        val label = when {
            !effectEnabled -> "立体声宽度已关闭 · 参数模型预览"
            !monitoring -> "监视已关闭 · 图纸与网格保留"
            !resumed -> "页面暂停 · 监视已暂停"
            !wasEnabled -> "等待宽度驱动提供有效监测数据"
            else -> null
        }
        if (label != null && label != lastPausedLabel) { lastPausedLabel = label; header.showPaused(label) }
    }
    fun onResume() {
        if (closed) return
        resumed = true
        refreshConfiguration()
        configurationSnapshot?.cancel()
        configurationSnapshot = fragment.viewLifecycleOwner.lifecycleScope.launch {
            val reading = WidthFocusRuntimeMonitor.refreshConfigurationOnce(context) { !closed && resumed && !enabled.value }
            if (!closed && resumed && !enabled.value && reading != null)
                header.setConfiguration(preferences?.all.orEmpty(), reading.snapshot?.rate)
        }
    }
    fun onPause() {
        configurationSnapshot?.cancel(); configurationSnapshot = null
        if (!closed) { resumed = false; updateSubscription() }
    }
    override fun close() {
        if (closed) return
        closed = true; enabled.value = false
        focusObserver?.close(); focusObserver = null
        configurationSnapshot?.cancel(); configurationSnapshot = null
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = null
        context.unregisterLocalReceiver(receiver)
        host.clearBassGraphActions(header)
        host.clearPinnedHeader(header)
    }
}
