package me.timschneeberger.rootlessjamesdsp.eq

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.util.IdentityHashMap

/** Same-window observer; saved-parameter repairs are requested through the engine's normal queue. */
class EqRuntimeMonitor(
    owner: LifecycleOwner,
    context: Context,
    private val onSample: (EqPcmTelemetry.Snapshot) -> Unit,
    private val onState: (State) -> Unit,
) : AutoCloseable {
    sealed class State(val label: String) {
        object Disabled : State("均衡器前后频谱已收起")
        object Starting : State("正在连接本模块真实输入／输出采样")
        object Active : State("曲线均衡器真实输入／输出 · dBFS")
        object Silent : State("本模块输入静音 · 未检测到输入音乐")
        object NoData : State("等待新的真实输入／输出窗口")
        // Kept for existing APIs; native GET telemetry never emits a permission request.
        object PermissionRequired : State("本模块只读采样无需音频采集权限")
        data class Unavailable(val reason: String) : State(reason)
        data class Transition(val reason: String) : State(reason)
    }

    private data class Request(val enabled: Boolean, val revision: Long = 0)
    private data class Before(val session: OutputRuntimeStatus.Session,
        val packet: EqPcmTelemetry.Snapshot?, val freshAt: Long)
    private data class Row(val engine: JamesDspRemoteEngine,
        val session: OutputRuntimeStatus.Session, val supported: Boolean,
        val packet: EqPcmTelemetry.Snapshot?, val advancing: Boolean, val fresh: Boolean,
        val boundary: Boolean, val previousFreshAt: Long)
    private data class SavedConfiguration(val enabled: Boolean, val hash: Int)
    private class Repair(val engine: JamesDspRemoteEngine, val identity: Any, val capture: Int, val generation: Int,
        val rate: Int, val hostRate: Int, val expected: SavedConfiguration, now: Long) {
        var attempts = 0
        var lastRequestAt = now
        fun matches(session: OutputRuntimeStatus.Session, packet: EqPcmTelemetry.Snapshot,
            target: SavedConfiguration): Boolean = identity === session.identity &&
            capture == packet.captureId && generation == packet.generation && rate == packet.rate &&
            hostRate == packet.hostRate && expected == target
    }
    private val app = context.applicationContext as? MainApplication
    private val savedPreferences = PreferenceCache.getPreferences(context, Constants.PREF_EQ)
    private val enableKey = context.getString(R.string.key_eq_enable)
    private val bandsKey = context.getString(R.string.key_eq_bands)
    private val filterKey = context.getString(R.string.key_eq_filter_type)
    private val interpolationKey = context.getString(R.string.key_eq_interpolation)
    @Volatile private var repair: Repair? = null
    private val requests = MutableStateFlow(Request(false))
    private var delivered: State? = null
    private var closed = false
    private val job: Job = owner.lifecycleScope.launch {
        try {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    requests.collectLatest { request ->
                        if (!request.enabled || closed) { report(State.Disabled); return@collectLatest }
                        val application = app ?: run {
                            report(State.Unavailable("驱动未连接")); return@collectLatest
                        }
                        report(State.Starting)
                        var previous = IdentityHashMap<Any, Before>()
                        var selectedIdentity: Any? = null
                        var selectedCapture: Int? = null
                        var selectedGeneration: Int? = null
                        var lastValidAt = SystemClock.elapsedRealtime()
                        while (currentCoroutineContext().isActive) {
                            val targets = application.rootSessionDatabase.sessionList.toList().mapNotNull { (id, session) ->
                                (session as? RemoteEffectSession)?.effect?.let { id to it }
                            }
                            val globalIssue = if (!application.isPlayerSessionMode)
                                application.rootSessionDatabase.globalPlaybackIssue else null
                            val now = SystemClock.elapsedRealtime()
                            val rows = withContext(Dispatchers.IO) {
                                targets.mapNotNull { (id, engine) ->
                                    currentCoroutineContext().ensureActive()
                                    val session = runCatching { engine.outputRuntimeSession()?.copy(sessionId = id) }
                                        .getOrNull() ?: return@mapNotNull null
                                    val before = previous[session.identity]
                                    val boundary = before != null && (session.hostRate != before.session.hostRate ||
                                        session.sessionId != before.session.sessionId || session.enabled != before.session.enabled ||
                                        reset(before.session.processedFrames, session.processedFrames))
                                    val advancing = before != null && !boundary && session.enabled && before.session.enabled &&
                                        session.hostRate?.let { it in 8000..768000 } == true &&
                                        progressed(before.session.processedFrames, session.processedFrames)
                                    val supported = runCatching { engine.eqPcmSupported() }.getOrDefault(false)
                                    // Master-disabled instances must not renew their observation leases.
                                    val packet = if (supported && session.enabled)
                                        runCatching { engine.eqPcmSnapshot(session) }.getOrNull() else null
                                    val old = before?.packet
                                    val fresh = advancing && packet?.valid == true && packet.sourceId == 1 &&
                                        packet.rate in 8000..768000 && packet.hostRate == session.hostRate &&
                                        (old == null || packet.captureId != old.captureId || packet.generation != old.generation ||
                                            packet.rate != old.rate || packet.sequence != old.sequence &&
                                            packet.lastFramePosition > old.lastFramePosition)
                                    Row(engine, session, supported, packet, advancing, fresh, boundary, before?.freshAt ?: now)
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (closed || !requests.value.enabled ||
                                !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collectLatest
                            previous = IdentityHashMap<Any, Before>().also { next ->
                                rows.forEach { row ->
                                    val old = previous[row.session.identity]?.takeUnless { row.boundary }
                                    next[row.session.identity] = Before(row.session,
                                        row.packet?.takeIf { it.valid && it.sourceId == 1 && it.hostRate == row.session.hostRate }
                                            ?: old?.packet,
                                        if (row.fresh) now else old?.freshAt ?: now)
                                }
                            }
                            val active = rows.filter { it.advancing }
                            val audible = active.filter { row ->
                                row.packet?.let { packet -> packet.valid && packet.sourceId == 1 &&
                                    packet.hostRate == row.session.hostRate && packet.inputHasSignal &&
                                    packet.inputRmsDbfs > -240.0 && (row.fresh || now - row.previousFreshAt <= 600L)
                                } == true
                            }
                            val selected = when {
                                audible.size == 1 -> audible.single()
                                audible.size > 1 -> null
                                active.size == 1 -> active.single()
                                else -> null
                            }
                            val status = selected ?: rows.singleOrNull().takeIf { active.isEmpty() }
                            val packet = selected?.packet
                            val issue = when {
                                rows.isEmpty() -> "驱动未连接"
                                globalIssue == GlobalPlaybackEvidence.Reason.SUSPENDED -> "全局音效链已挂起"
                                globalIssue == GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> "当前音乐使用其他输出链路"
                                audible.size > 1 -> "多路模块输入都有音乐，暂不合并频谱"
                                active.size > 1 && selected == null -> "多路活动实例未找到唯一有声输入，暂不合并频谱"
                                rows.none { it.supported } || selected?.supported == false ->
                                    "需配套1079驱动，才能读取曲线均衡器前后音频"
                                selected == null -> "等待正常播放与音频处理帧"
                                packet == null -> "等待本模块只读音频采样"
                                !packet.leaseActive -> "本模块只读采样已暂停"
                                packet.invalidSamples -> "采样窗口包含无效数值，已跳过"
                                !packet.valid -> "正在收集本模块前后同步音频"
                                packet.sourceId != 1 -> "采样来源不符，等待本模块直连窗口"
                                packet.hostRate != selected.session.hostRate -> "宿主采样率切换中，等待新窗口"
                                !selected.fresh -> "等待新的真实音频窗口"
                                else -> null
                            }
                            if (issue != null) {
                                val mustClear = rows.isEmpty() || globalIssue != null || audible.size > 1 ||
                                    active.size > 1 && selected == null || packet?.invalidSamples == true ||
                                    status?.boundary == true || status?.session?.enabled == false ||
                                    selected != null && selectedIdentity != null &&
                                    selectedIdentity !== selected.session.identity ||
                                    packet != null && selectedCapture != null && selectedCapture != packet.captureId ||
                                    packet != null && selectedGeneration != null && selectedGeneration != packet.generation ||
                                    rows.none { it.supported } || selected?.supported == false || now - lastValidAt > 600L
                                if (mustClear) clearRepair()
                                report(if (mustClear) State.Unavailable(issue) else State.NoData)
                            } else if (packet != null && selected != null) {
                                val changed = selectedIdentity !== selected.session.identity ||
                                    selectedCapture != packet.captureId || selectedGeneration != packet.generation
                                if (changed && selectedIdentity != null) report(State.Unavailable("音频链路已切换"))
                                selectedIdentity = selected.session.identity
                                selectedCapture = packet.captureId
                                selectedGeneration = packet.generation
                                lastValidAt = now
                                val expected = savedConfiguration()
                                val confirmed = expected != null && packet.moduleEnabled == expected.enabled &&
                                    (!expected.enabled || packet.parameterHash == expected.hash)
                                if (confirmed || expected == null) clearRepair()
                                else requestSavedParameterSync(selected, packet, requireNotNull(expected), now)
                                // A changing slider target does not invalidate the real transition PCM.
                                onSample(packet)
                                report(when {
                                    expected == null -> State.Transition("保存参数格式异常 · 频谱显示驱动实际输出")
                                    !confirmed -> State.Transition(if (repair?.let {
                                            it.attempts >= 3 && now - it.lastRequestAt >= 500L
                                        } == true)
                                        "参数未同步，请重试音效设置"
                                    else "参数尚未生效，正在重新同步")
                                    !packet.inputHasSignal || packet.inputRmsDbfs <= -240.0 -> State.Silent
                                    packet.transitioning || packet.configurationChanged ->
                                        State.Transition("曲线均衡器真实输入／输出 · 参数过渡中")
                                    !packet.moduleEnabled -> State.Transition("曲线均衡器已关闭 · 真实旁路前后 dBFS")
                                    else -> State.Active
                                })
                            }
                            delay(50L)
                        }
                    }
                } finally { cancelPendingRepair(); report(State.Disabled) }
            }
        } finally { cancelPendingRepair(); report(State.Disabled) }
    }

    /** Observation visibility only: never changes EQ audio flags, parameters or a route. */
    fun setEnabled(enabled: Boolean) {
        if (!enabled) cancelPendingRepair()
        if (!closed && requests.value.enabled != enabled) requests.value = requests.value.copy(enabled = enabled)
    }
    /** Compatibility retry hook; this native observer never requests RECORD_AUDIO. */
    fun onPermissionResult() {
        if (!closed && requests.value.enabled) requests.value = requests.value.copy(revision = requests.value.revision + 1)
    }
    private fun report(value: State) {
        if (delivered != value) { delivered = value; onState(value) }
    }

    /** This snapshot comes only from saved audio preferences, never the dashboard's selection/draft. */
    private fun savedConfiguration(): SavedConfiguration? {
        val values = savedPreferences.all
        val enabled = if (values.containsKey(enableKey)) values[enableKey] as? Boolean ?: return null else false
        // The original disabled EQ retains its last frequency/gain axes. Those inactive
        // values need not match a newly saved bank until the module is enabled again.
        if (!enabled) return SavedConfiguration(false, 0)
        fun string(key: String, default: String): String? =
            if (values.containsKey(key)) values[key] as? String else default
        val filter = string(filterKey, "0")?.toIntOrNull()?.takeIf { it in 0..5 } ?: return null
        val interpolation = string(interpolationKey, "0")?.toIntOrNull()?.takeIf { it in 0..1 } ?: return null
        val bands = string(bandsKey, Constants.DEFAULT_EQ) ?: return null
        val hash = EqPcmTelemetry.expectedParameterHash(bands, filter, interpolation, true) ?: return null
        return SavedConfiguration(true, hash)
    }

    private suspend fun requestSavedParameterSync(row: Row, packet: EqPcmTelemetry.Snapshot,
        expected: SavedConfiguration, now: Long) {
        val pending = repair?.takeIf { it.matches(row.session, packet, expected) } ?: run {
            clearRepair()
            Repair(row.engine, row.session.identity, packet.captureId, packet.generation,
                packet.rate, packet.hostRate, expected, now).also { repair = it }
        }
        if (!row.session.enabled || !row.fresh || packet.transitioning || packet.configurationChanged ||
            pending.attempts >= 3 || now - pending.lastRequestAt < 500L) return
        pending.lastRequestAt = now
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            // The engine checks current control/identity and re-reads saved preferences
            // before queueing a normal configuration sync. This observer never sends SET.
            val accepted = runCatching {
                row.engine.requestEqParameterResync(row.session, expected.hash, expected.enabled)
            }.getOrDefault(false)
            // Count inside the work item so cancellation after a successful enqueue
            // cannot accidentally restore a spent attempt on the next resume.
            if (accepted) {
                pending.attempts++
                if (!currentCoroutineContext().isActive || !requests.value.enabled || repair !== pending)
                    row.engine.cancelEqParameterResync(row.session.identity)
            }
        }
    }

    private fun cancelPendingRepair() {
        repair?.let { it.engine.cancelEqParameterResync(it.identity) }
    }

    private fun clearRepair() {
        val previous = repair
        repair = null
        previous?.let { it.engine.cancelEqParameterResync(it.identity) }
    }

    override fun close() {
        if (closed) return
        closed = true; clearRepair()
        requests.value = requests.value.copy(enabled = false); job.cancel(); report(State.Disabled)
    }
    private companion object {
        fun progressed(before: Int?, after: Int?): Boolean = before != null && after != null && before >= 0 && after >= 0 &&
            (after > before || before >= 0x70000000 && after <= 0x0fffffff)
        fun reset(before: Int?, after: Int?): Boolean = before != null && after != null && before >= 0 && after >= 0 &&
            after < before && !(before >= 0x70000000 && after <= 0x0fffffff)
    }
}
