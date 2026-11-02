package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence

/** GET-only page observer. The original runtime publisher continues to own audition state. */
internal object ToneDashboardRuntimeMonitor {
    data class Sample(
        val elapsedRealtimeMs: Long,
        val streamIdentity: Any?,
        val reading: ToneAdvancedController.Reading?,
        val output: OutputRuntimeStatus.Snapshot,
        val unavailableReason: String? = null,
        val streamSessionId: Int? = null,
        val levelUnavailableReason: String? = unavailableReason
    )
    private data class Before(val output: OutputRuntimeStatus.Session, val moduleEpoch: Int?)
    private data class Row(val output: OutputRuntimeStatus.Session,
        val reading: ToneAdvancedController.Reading?, val progressed: Boolean)
    private fun fingerprint(context: Context): Map<String, *> = ToneAdvancedController.preferences(context).all
        .filterKeys { it in ToneAdvancedParameters.keys || it == ToneAdvancedParameters.ENABLED }.toMap()

    fun bind(owner: LifecycleOwner, context: Context, enabled: StateFlow<Boolean>, onSample: (Sample) -> Unit) {
        val app = context.applicationContext
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                enabled.collectLatest { power ->
                    if (!power) return@collectLatest
                    var previous = IdentityHashMap<Any, Before>()
                    val tracker = OutputRuntimeStatus.Tracker()
                    while (currentCoroutineContext().isActive && enabled.value) {
                        val values = fingerprint(app)
                        if (!ToneAdvancedParameters.moduleEnabled(values)) return@collectLatest
                        val poll = ToneAdvancedController.pollState()
                        val audition = ToneAdvancedController.audition
                        val toolEpoch = ToneAdvancedController.epoch
                        val application = app as? MainApplication
                        val targets = application?.rootSessionDatabase?.sessionList?.toList()
                            ?.mapNotNull { (id, session) -> (session as? RemoteEffectSession)?.effect?.let { id to it } }.orEmpty()
                        val mode = if (application?.isPlayerSessionMode == true) OutputRuntimeStatus.Mode.PLAYER_SESSION
                            else OutputRuntimeStatus.Mode.GLOBAL
                        val issue = if (mode == OutputRuntimeStatus.Mode.GLOBAL) when (application?.rootSessionDatabase?.globalPlaybackIssue) {
                            GlobalPlaybackEvidence.Reason.SUSPENDED -> OutputRuntimeStatus.GlobalIssue.SUSPENDED
                            GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT
                            null -> null
                        } else null
                        val rows = withContext(Dispatchers.IO) {
                            targets.mapNotNull { (id, engine) ->
                                currentCoroutineContext().ensureActive()
                                if (!enabled.value) return@mapNotNull null
                                val output = runCatching { engine.outputRuntimeSession()?.copy(sessionId = id) }.getOrNull()
                                    ?: return@mapNotNull null
                                val before = previous[output.identity]
                                val advancing = output.enabled && before?.output?.enabled == true &&
                                    output.hostRate == before.output.hostRate && id == before.output.sessionId &&
                                    output.hostRate?.let { it in 8000..768000 } == true &&
                                    progressed(before.output.processedFrames, output.processedFrames)
                                val raw = runCatching { engine.toneAdvancedRuntimeReading(output) }.getOrNull()
                                val packet = raw?.snapshot
                                // Tone runs directly at the Fusion host rate. A normal route has
                                // no comparison generation, even while a new main kernel warms up.
                                val matching = packet?.let { it.enabled && it.ready &&
                                    it.data[2] == output.hostRate && it.mode == audition &&
                                    (audition == 0 || it.epoch == toolEpoch) } == true
                                val reading = raw?.copy(active = advancing && matching &&
                                    progressed(before?.moduleEpoch, packet?.data?.get(1)))
                                Row(output, reading, advancing)
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (!enabled.value || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collectLatest
                        val same = values == fingerprint(app) && poll == ToneAdvancedController.pollState() &&
                            audition == ToneAdvancedController.audition && toolEpoch == ToneAdvancedController.epoch
                        previous = IdentityHashMap<Any, Before>().also { next ->
                            if (same) rows.forEach { next[it.output.identity] = Before(it.output, it.reading?.snapshot?.data?.get(1)) }
                        }
                        val output = tracker.update(rows.map { it.output }).copy(mode = mode, globalIssue = issue)
                        val active = rows.filter { it.progressed }
                        val selected = active.singleOrNull()
                        val reading = selected?.reading?.let { it.copy(active = same && issue == null && it.active) }
                        val data = reading?.snapshot?.data
                        val reason = when {
                            !same -> "参数或试听状态正在同步"
                            !output.connected -> "驱动未连接"
                            issue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局处理链路已挂起"
                            issue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频使用其他输出链路"
                            active.size > 1 -> "多路音频同时处理，暂不合并仪表"
                            selected == null -> "等待活动音频与处理帧"
                            data == null -> "当前驱动未提供听感音色调节诊断"
                            data[3] != 1 -> "听感音色调节已关闭"
                            reading?.active != true -> "等待当前听感音色调节或试听状态"
                            else -> null
                        }
                        val levelReason = when {
                            reason != null -> reason
                            data?.get(12) != 1 -> "原生“显示实时电平”已关闭"
                            data != null && (16..23).all { data[it] == Int.MIN_VALUE } -> "当前没有可用输入输出电平"
                            else -> null
                        }
                        onSample(Sample(SystemClock.elapsedRealtime(), selected?.output?.identity, reading,
                            output, reason, selected?.output?.sessionId, levelReason))
                        delay(250L)
                    }
                }
            }
        }
    }
    private fun progressed(before: Int?, after: Int?): Boolean = before != null && after != null && before >= 0 &&
        after >= 0 && (after > before || before >= 0x70000000 && after <= 0x0fffffff)
}
