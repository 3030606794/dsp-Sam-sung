package me.timschneeberger.rootlessjamesdsp.creative

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceFragmentCompat
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Coalesces parameter previews; runtime reads are bounded and never happen on the UI thread. */
internal class CreativeEffectsDashboard(
    private val fragment: PreferenceFragmentCompat,
    private val kind: CreativeEffectsKind,
    private val preferences: SharedPreferences,
) : AutoCloseable {
    private val context = fragment.requireContext()
    private val host = fragment.requireActivity() as ProfessionalEffectActivity
    private val display = context.applicationContext.getSharedPreferences("creative_display_${kind.name}", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "creative-monitor-${kind.name.lowercase()}").apply { isDaemon = true }
    }
    private val visibleBounds = Rect()
    private var worker: Future<*>? = null
    private var resumed = false
    private var closed = false
    private var power = display.getBoolean("enabled", true)
    private var pinned = display.getBoolean("pinned", true)
    private var expanded = !display.getBoolean("realtime_collapsed", false)
    private var locked = display.getBoolean("locked", false)
    private val controls = CreativeEffectsParameters.controls(kind)
    private var focus = display.getString("focus", null)?.takeIf { key -> controls.any { it.key == key } } ?: when (kind) {
        CreativeEffectsKind.GRANULAR -> CreativeEffectsParameters.granularGrain.key
        CreativeEffectsKind.FREEZE -> CreativeEffectsParameters.freezeDecay.key
        CreativeEffectsKind.SHIMMER -> CreativeEffectsParameters.shimmerDecay.key
    }
    private var renderedValues: Map<String, *>? = null
    private var renderedFocus: String? = null
    private var epoch = 0L
    private val frame = PreferenceFrameUpdate { refreshConfiguration() }
    private val header = CreativeEffectsHeader(context, kind, power, pinned, expanded,
        onPowerChanged = { value ->
            if (!closed) {
                power = value; display.edit().putBoolean("enabled", value).apply()
                headerView().setDisplay(power, pinned, expanded, locked); restartMonitoring()
            }
        }, onPinChanged = { value ->
            if (!closed) {
                pinned = value; display.edit().putBoolean("pinned", value).apply()
                headerView().setDisplay(power, pinned, expanded, locked); host.setPinnedHeader(headerView(), pinned)
            }
        }, onExpandedChanged = { value ->
            if (!closed) {
                expanded = value; display.edit().putBoolean("realtime_collapsed", !value).apply()
                headerView().setDisplay(power, pinned, expanded, locked); restartMonitoring()
            }
        }, locked = locked, onLockChanged = { value ->
            if (!closed) {
                locked = value; display.edit().putBoolean("locked", value).apply()
                headerView().setDisplay(power, pinned, expanded, locked)
            }
        })
    private fun headerView(): CreativeEffectsHeader = header
    private val tick = object : Runnable {
        override fun run() {
            if (closed || !resumed) return
            val visible = header.isShown && header.windowVisibility == View.VISIBLE && header.getGlobalVisibleRect(visibleBounds)
            val enabled = preferences.all[kind.enableKey] == true
            val reason = when {
                !power -> "实时采样已关闭 · 两张参数图保留"
                !expanded -> "实时图已折叠 · 两张参数图保留"
                !enabled -> "${kind.title}已关闭 · 参数预览保留"
                !visible -> "图表已离开可见区域 · 采样暂停"
                else -> null
            }
            if (reason != null) header.showPaused(reason)
            else if (worker == null) {
                val requestEpoch = epoch
                worker = executor.submit {
                    val result = runCatching {
                        CreativeEffectsController.supported(context) to CreativeEffectsController.currentRuntime(context, kind)
                    }
                    main.post {
                        worker = null
                        if (closed || !resumed || requestEpoch != epoch || !power || !expanded ||
                            !header.isShown || !header.getGlobalVisibleRect(visibleBounds)) return@post
                        val (supported, sample) = result.getOrNull() ?: (null to null)
                        val currentPacket = CreativeEffectsController.packet(context, kind)
                        when {
                            supported == false -> header.showPaused(CreativeEffectsController.DRIVER_REQUIRED)
                            currentPacket.getOrNull(1) != 1 -> header.showPaused("${kind.title}已关闭 · 参数预览保留")
                            sample == null -> header.showPaused("等待驱动连接与单路活动音频")
                            sample.parameterHash != currentPacket.contentHashCode() -> header.showPaused("参数正在同步 · 等待生效")
                            !sample.isProcessing -> header.showPaused("等待正常播放与活动音频")
                            !sample.inputPeakDb.isFinite() || !sample.wetPeakDb.isFinite() -> header.showPaused("等待有效音频读数")
                            else -> header.showSample(sample)
                        }
                    }
                }
            }
            main.postDelayed(this, 200L)
        }
    }

    init {
        refreshConfiguration()
        host.setPinnedHeader(header, pinned)
        host.setBassGraphActions(header, header.toolbarActions)
    }

    fun configurationChanged(key: String?) {
        if (closed) return
        if (!locked && key != null && controls.any { it.key == key }) {
            focus = key; display.edit().putString("focus", key).apply()
        }
        frame.request()
    }

    private fun refreshConfiguration() {
        if (closed) return
        val keys = controls.map { it.key }.toSet() + kind.enableKey
        val values = preferences.all.filterKeys { it in keys }
        if (values != renderedValues || focus != renderedFocus) {
            // At most 200 scalar preview points; no FFT, audio query or whole-list rebind here.
            header.setConfiguration(CreativeEffectsLinkedPlots.pair(kind, values, focus))
            renderedValues = values; renderedFocus = focus
        }
    }

    private fun restartMonitoring() {
        epoch++
        main.removeCallbacks(tick)
        if (resumed && !closed) main.post(tick)
    }
    fun onResume() {
        if (closed) return
        resumed = true; refreshConfiguration(); restartMonitoring()
    }
    fun onPause() {
        resumed = false; epoch++; main.removeCallbacks(tick)
        header.showPaused("页面暂停 · 实时采样已暂停")
    }
    override fun close() {
        if (closed) return
        closed = true; resumed = false; epoch++
        frame.close(); worker?.cancel(true); worker = null
        executor.shutdownNow(); main.removeCallbacksAndMessages(null)
        header.close(); host.clearBassGraphActions(header); host.clearPinnedHeader(header)
    }
}
