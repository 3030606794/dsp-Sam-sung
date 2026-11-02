package me.timschneeberger.rootlessjamesdsp.liveprog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.ui.LiveprogDashboardHeader
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** Keeps graph visibility and observation subscription separate from script execution. */
internal class LiveprogDashboardCoordinator(private val fragment: PreferenceFragmentCompat,
    private val editor: LiveprogProfessionalEditor) : AutoCloseable {
    private val context = fragment.requireContext()
    private val host = fragment.requireActivity() as ProfessionalEffectActivity
    private val display = context.applicationContext.getSharedPreferences("liveprog_dashboard_display", Context.MODE_PRIVATE)
    private var closed = false
    private var resumed = false
    private var power = display.getBoolean("power", true)
    private var pinned = display.getBoolean("pinned", true)
    private var rate = 48000
    private var actualRate = false
    private val enabled = MutableStateFlow(false)
    private val configuration = MutableStateFlow<LiveprogChartConfiguration?>(null)
    private val header = LiveprogDashboardHeader(context,
        onMonitorChanged = { value ->
            power = value; display.edit().putBoolean("power", value).apply(); controls(); subscription()
        }, onPinChanged = { value ->
            pinned = value; display.edit().putBoolean("pinned", value).apply(); controls(); moveHeader(value)
        })
    private var focusObserver: LiveprogParameterFocusObserver? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (closed) return
            when (intent?.action) {
                Constants.ACTION_PRESET_LOADED -> editor.reload()
                Constants.ACTION_SERVICE_RELOAD_LIVEPROG -> editor.reload(invalidateSource = true)
                Constants.ACTION_LIVEPROG_PARAMETER_RESULT -> {
                    if (intent.getStringExtra(Constants.EXTRA_LIVEPROG_PATH) == configuration.value?.path) {
                        intent.getStringExtra(Constants.EXTRA_LIVEPROG_ERROR)?.let(header::showPaused)
                    }
                }
            }
        }
    }
    init {
        editor.onChanged = { key ->
            if (!closed) refresh(key)
        }
        controls(); host.setPinnedHeader(header, pinned); host.setBassGraphActions(header, header.toolbarActions)
        focusObserver = LiveprogParameterFocusObserver(fragment) { key -> if (!closed && resumed) header.followParameter(key) }
        context.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PRESET_LOADED).apply {
            addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
            addAction(Constants.ACTION_LIVEPROG_PARAMETER_RESULT)
        })
        refresh()
        LiveprogRuntimeMonitor.bind(fragment.viewLifecycleOwner, context, enabled, configuration) { frame ->
            if (closed || !resumed || !power) return@bind
            frame.actualRate?.takeIf { it in 8000..768000 }?.let { observed ->
                if (rate != observed || !actualRate) { rate = observed; actualRate = true; refresh() }
            }
            header.showSample(frame)
        }
    }
    private fun moveHeader(value: Boolean) { if (!closed) host.setPinnedHeader(header, value) }
    private fun controls() { header.setControls(power, pinned) }
    private fun refresh(focusKey: String? = null) {
        if (closed) return
        val next = editor.configuration(rate, actualRate)
        configuration.value = next
        header.setConfiguration(next, focusKey)
        subscription()
    }
    private fun subscription() {
        val config = configuration.value
        val subscribe = !closed && resumed && power && config?.enabled == true && config.path.isNotBlank()
        enabled.value = subscribe
        if (!subscribe) header.showPaused(when {
            !power -> "实时采样已关闭 · 两张参数图保留"
            !resumed -> "页面暂停 · 实时采样已停止"
            config?.path.isNullOrBlank() -> "选择或导入脚本后显示图纸"
            else -> "DSP 脚本处理 已关闭 · 保存参数预览"
        })
    }
    fun onResume() { if (!closed) { resumed = true; refresh() } }
    fun onPause() { resumed = false; enabled.value = false; header.showPaused("页面暂停 · 保留最后真实读数") }
    override fun close() {
        if (closed) return
        closed = true; resumed = false; enabled.value = false
        editor.onChanged = null; focusObserver?.close(); focusObserver = null
        context.unregisterLocalReceiver(receiver)
        header.close(); host.clearBassGraphActions(header); host.clearPinnedHeader(header)
    }
}
