package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate
import me.timschneeberger.rootlessjamesdsp.ui.TubeDashboardHeader
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

/** Owns only presentation: the original editor and publisher keep all audio and A/B state. */
internal class TubeDashboardCoordinator(private val fragment: Fragment) : AutoCloseable {
    private val context = fragment.requireContext()
    private val host = fragment.requireActivity() as ProfessionalEffectActivity
    private val display = context.applicationContext.getSharedPreferences("viper_tube_dashboard_display", Context.MODE_PRIVATE)
    private var preferences: SharedPreferences? = null
    private var closed = false
    private var resumed = false
    private var power = display.getBoolean("power", false)
    private var pinned = display.getBoolean("pinned", true)
    private var effectEnabled = false
    private data class ModelRequest(val values: Map<String, *>, val rate: Int, val actualRate: Boolean)
    private var requestedModel: ModelRequest? = null
    private var pendingModel: ModelRequest? = null
    private var modelBusy = false
    private val modelHandler = Handler(Looper.getMainLooper())
    private val modelWorker = Executors.newSingleThreadExecutor { work ->
        Thread(work, "tube-dashboard-model").apply { isDaemon = true }
    }
    private var cachedModel: TubeDashboardModel? = null
    private var lastPausedLabel: String? = null
    private val enabled = MutableStateFlow(false)
    private var focusObserver: TubeParameterFocusObserver? = null
    private val relevantKeys = TubeParameters.keys + TubeParameters.ENABLED
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
    private val header: TubeDashboardHeader = TubeDashboardHeader(context,
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
            addAction(TubeController.STATUS_ACTION)
        })
        host.setPinnedHeader(header, pinned)
        host.setBassGraphActions(header, header.toolbarActions)
        (fragment as? PreferenceFragmentCompat)?.let { nativeFragment ->
            focusObserver = TubeParameterFocusObserver(nativeFragment) { key ->
                if (!closed && resumed) header.followParameter(key)
            }
        }
        TubeDashboardRuntimeMonitor.bind(fragment.viewLifecycleOwner, context, enabled) { sample ->
            if (!closed && enabled.value) { lastPausedLabel = null; header.showRuntime(sample) }
        }
    }
    private fun headerView(): TubeDashboardHeader = header
    private fun renderControls(): Unit = header.setControls(power, pinned)
    private fun reloadPreferences() {
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = TubeController.preferences(context).also { it.registerOnSharedPreferenceChangeListener(listener) }
        refreshConfiguration()
    }
    private fun refreshConfiguration() {
        if (closed) return
        val values = preferences?.all.orEmpty().filterKeys { it in relevantKeys }.toMap()
        effectEnabled = TubeParameters.moduleEnabled(values)
        // The original status publisher owns audio and A/B state. Reuse its cached rate.
        val readings = TubeController.runtime
        val rate = readings.filter { it.enabled && it.active }.map { it.rate }.distinct().singleOrNull()
            ?: readings.map { it.rate }.distinct().singleOrNull()
        val confirmedRate = rate?.takeIf { it in 8000..768000 }
        val nextRate = confirmedRate ?: 48000
        val request = ModelRequest(values, nextRate, confirmedRate != null)
        if (requestedModel != request) {
            requestedModel = request; pendingModel = request; startModelWork()
        }
        // Refresh lightweight support/A/B display state without recalculating the saved model.
        cachedModel?.let(header::setConfiguration)
        updateSubscription()
    }
    // One calculation and only the latest waiting settings; normal telemetry never queues models.
    private fun startModelWork() {
        if (closed || modelBusy) return
        val request = pendingModel ?: return
        pendingModel = null; modelBusy = true
        modelWorker.execute {
            val result = runCatching { TubeDashboardModel.from(request.values, request.rate, request.actualRate) }.getOrNull()
            modelHandler.post {
                modelBusy = false
                if (!closed) {
                    if (request == requestedModel && result != null) {
                        cachedModel = result; header.setConfiguration(result)
                    }
                    startModelWork()
                }
            }
        }
    }
    private fun updateSubscription() {
        val previous = enabled.value
        // Folding and the joint static lock are presentation only; power owns this observer.
        enabled.value = !closed && resumed && power && effectEnabled
        val message = when {
            !effectEnabled -> "电子管模拟已关闭 · 参数预览保留"
            !power -> "实时采样已关闭 · 两张静态图保留"
            !resumed -> "页面暂停 · 图纸采样已停止"
            !previous -> "等待 电子管模拟的活动输出读数"
            else -> null
        }
        if (message != null && message != lastPausedLabel) { lastPausedLabel = message; header.showPaused(message) }
    }
    fun onResume() { if (!closed) { resumed = true; refreshConfiguration() } }
    fun onPause() { if (!closed) { resumed = false; updateSubscription() } }
    override fun close() {
        if (closed) return
        closed = true; enabled.value = false
        pendingModel = null; modelWorker.shutdownNow(); modelHandler.removeCallbacksAndMessages(null)
        renderFrame.close(); focusObserver?.close(); focusObserver = null
        preferences?.unregisterOnSharedPreferenceChangeListener(listener); preferences = null
        context.unregisterLocalReceiver(receiver)
        host.clearBassGraphActions(header); host.clearPinnedHeader(header)
    }
}
