package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate
import me.timschneeberger.rootlessjamesdsp.ui.PresetCrossfeedDashboardHeader
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** Owns only presentation: the original editor and publisher keep all audio and A/B state. */
internal class PresetCrossfeedDashboardCoordinator(private val fragment: Fragment) : AutoCloseable {
    private val context = fragment.requireContext()
    private val host = fragment.requireActivity() as ProfessionalEffectActivity
    private val display = context.applicationContext.getSharedPreferences("preset_crossfeed_dashboard_display", Context.MODE_PRIVATE)
    private var preferences: SharedPreferences? = null
    private var closed = false
    private var resumed = false
    private var power = display.getBoolean("power", false)
    private var pinned = display.getBoolean("pinned", true)
    private var effectEnabled = false
    private var professional = false
    private var lastPausedLabel: String? = null
    private val enabled = MutableStateFlow(false)
    private var focusObserver: PresetCrossfeedParameterFocusObserver? = null
    private val relevantKeys = PresetCrossfeedParameters.newKeys +
        setOf(PresetCrossfeedParameters.BASE, PresetCrossfeedParameters.ENABLED)
    private val renderFrame = PreferenceFrameUpdate { refreshConfiguration() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!closed && (key == null || key in relevantKeys)) renderFrame.request()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            if (intent.action == Constants.ACTION_PRESET_LOADED) reloadPreferences()
            else renderFrame.request()
        }
    }
    private val header: PresetCrossfeedDashboardHeader = PresetCrossfeedDashboardHeader(context,
        onMonitorChanged = { value ->
            if (!closed) {
                if (!value) enabled.value = false
                power = value
                display.edit().putBoolean("power", value).apply()
                renderControls(); updateSubscription()
            }
        }, onPinChanged = { value ->
            if (!closed) {
                pinned = value; display.edit().putBoolean("pinned", value).apply()
                renderControls(); host.setPinnedHeader(headerView(), value)
            }
        })

    init {
        reloadPreferences(); renderControls()
        context.registerLocalReceiver(receiver, IntentFilter().apply {
            addAction(Constants.ACTION_PRESET_LOADED)
            addAction(PresetCrossfeedController.STATUS_ACTION)
        })
        host.setPinnedHeader(header, pinned)
        host.setBassGraphActions(header, header.toolbarActions)
        (fragment as? PreferenceFragmentCompat)?.let { nativeFragment ->
            focusObserver = PresetCrossfeedParameterFocusObserver(nativeFragment) { key ->
                if (!closed && resumed) header.followParameter(key)
            }
        }
        PresetCrossfeedDashboardRuntimeMonitor.bind(fragment.viewLifecycleOwner, context, enabled) { sample ->
            if (!closed && enabled.value) { lastPausedLabel = null; header.showRuntime(sample) }
        }
    }
    private fun headerView(): PresetCrossfeedDashboardHeader = header
    private fun renderControls(): Unit = header.setControls(power, pinned)
    private fun reloadPreferences() {
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = PresetCrossfeedController.preferences(context).also { it.registerOnSharedPreferenceChangeListener(listener) }
        refreshConfiguration()
    }
    private fun refreshConfiguration() {
        if (closed) return
        val values = preferences?.all.orEmpty()
        effectEnabled = values[PresetCrossfeedParameters.ENABLED] == true
        professional = PresetCrossfeedParameters.enabled(values)
        val readings = PresetCrossfeedController.runtime
        val rate = readings.filter { it.enabled && it.active }.map { it.rate }.distinct().singleOrNull()
            ?: readings.map { it.rate }.distinct().singleOrNull()
        header.setConfiguration(PresetCrossfeedParameters.capture(values), rate?.takeIf { it in 8000..768000 })
        updateSubscription()
    }
    private fun updateSubscription() {
        val previous = enabled.value
        enabled.value = !closed && resumed && power && effectEnabled && professional
        val message = when {
            !effectEnabled -> "交叉馈送已关闭 · 保存参数预览"
            !power -> "实时采样已关闭 · 两张静态图保留"
            !resumed -> "页面暂停 · 图纸采样已停止"
            !professional -> "专业精调未开启 · 原预设处理，暂无专业峰值读数"
            !previous -> "等待活动交叉馈送输出读数"
            else -> null
        }
        if (message != null && message != lastPausedLabel) { lastPausedLabel = message; header.showPaused(message) }
    }
    fun onResume() { if (!closed) { resumed = true; refreshConfiguration() } }
    fun onPause() { if (!closed) { resumed = false; updateSubscription() } }
    override fun close() {
        if (closed) return
        closed = true; enabled.value = false
        renderFrame.close(); focusObserver?.close(); focusObserver = null
        preferences?.unregisterOnSharedPreferenceChangeListener(listener); preferences = null
        context.unregisterLocalReceiver(receiver)
        host.clearBassGraphActions(header); host.clearPinnedHeader(header)
    }
}
