package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import me.timschneeberger.rootlessjamesdsp.activity.SurroundActivity
import me.timschneeberger.rootlessjamesdsp.ui.SurroundDashboardHeader

/** Owns display preferences only; the original page retains all processing and listening state. */
internal class SurroundDashboardCoordinator(private val host: SurroundActivity) : AutoCloseable {
    private val display = host.applicationContext.getSharedPreferences("surround_dashboard_display", Context.MODE_PRIVATE)
    private var power = display.getBoolean("power", false)
    private var pinned = display.getBoolean("pinned", true)
    private var closed = false
    private var resumed = false
    private var effectEnabled = false
    private var pausedLabel: String? = null
    private val monitoring = MutableStateFlow(false)
    private val header: SurroundDashboardHeader = SurroundDashboardHeader(host,
        onMonitorChanged = { enabled ->
            if (!closed) {
                if (!enabled) monitoring.value = false
                power = enabled
                display.edit().putBoolean("power", enabled).apply()
                headerControls(); updateMonitoring()
            }
        }, onPinChanged = { value -> setPinned(value) })

    init {
        headerControls()
        host.setDashboardHeader(header, pinned)
        host.setDashboardActions(header, header.toolbarActions)
        refreshConfiguration()
        // Pure GETs, never publish: the Activity's original poller owns A/B and calibration ACKs.
        SurroundFocusRuntimeMonitor.bind(host, host, monitoring) { sample ->
            if (!closed && monitoring.value) {
                pausedLabel = null
                header.showRuntime(sample)
            }
        }
    }

    private fun headerControls(): Unit = header.setControls(power, pinned)

    fun setPinned(value: Boolean) {
        if (closed) return
        pinned = value
        display.edit().putBoolean("pinned", value).apply()
        headerControls()
        host.setDashboardHeader(header, pinned)
    }

    fun followParameter(key: String) {
        if (!closed && resumed) header.followParameter(key)
    }

    /** Original preset, parameter and runtime notifications are already coalesced by the Activity. */
    fun refreshConfiguration() {
        if (closed) return
        effectEnabled = SurroundController.enabled(host)
        val rate = SurroundProfessionalRuntime.curveRate()?.takeIf { it in 8000..768000 }
        // Static charts describe the current saved slot; dynamic instruments show the actual path.
        header.setConfiguration(SurroundController.settings(host), rate)
        updateMonitoring()
    }

    private fun updateMonitoring() {
        val previous = monitoring.value
        monitoring.value = !closed && resumed && power && effectEnabled
        val message = when {
            !effectEnabled -> "扬声器串音抵消已关闭 · 参数模型预览"
            !power -> "实时采样已关闭 · 两个参数图保留"
            !resumed -> "页面暂停 · 图纸采样已停止"
            !previous -> "等待活动环绕处理读数"
            else -> null
        }
        if (message != null && pausedLabel != message) {
            pausedLabel = message
            header.showPaused(message)
        }
    }

    fun onResume() { if (!closed) { resumed = true; refreshConfiguration() } }
    fun onPause() { if (!closed) { resumed = false; updateMonitoring() } }

    override fun close() {
        if (closed) return
        closed = true; monitoring.value = false
        host.clearDashboardActions(header)
        host.clearDashboardHeader(header)
    }
}
