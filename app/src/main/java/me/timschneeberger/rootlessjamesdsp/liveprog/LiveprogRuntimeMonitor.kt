package me.timschneeberger.rootlessjamesdsp.liveprog

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
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence
import java.util.IdentityHashMap

/** Independent observer of real script boundaries. No effect, parameter or audition SETs. */
internal object LiveprogRuntimeMonitor {
    private data class Before(val session: OutputRuntimeStatus.Session, val sequence: Int?, val generation: Int?)
    private data class Row(val session: OutputRuntimeStatus.Session, val supported: Boolean,
        val packet: LiveprogPcmTelemetry.Snapshot?, val advancing: Boolean, val fresh: Boolean, val boundary: Boolean)

    fun bind(owner: LifecycleOwner, context: Context, enabled: StateFlow<Boolean>,
        configuration: StateFlow<LiveprogChartConfiguration?>, onFrame: (LiveprogAudioFrame) -> Unit) {
        val app = context.applicationContext as? MainApplication ?: return
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                enabled.collectLatest { power ->
                    if (!power) return@collectLatest
                    var previous = IdentityHashMap<Any, Before>()
                    while (currentCoroutineContext().isActive && enabled.value) {
                        val config = configuration.value
                        val targets = app.rootSessionDatabase.sessionList.toList().mapNotNull { (id, session) ->
                            (session as? RemoteEffectSession)?.effect?.let { id to it }
                        }
                        val globalIssue = if (!app.isPlayerSessionMode) app.rootSessionDatabase.globalPlaybackIssue else null
                        val rows = withContext(Dispatchers.IO) {
                            targets.mapNotNull { (id, engine) ->
                                currentCoroutineContext().ensureActive()
                                if (!enabled.value) return@mapNotNull null
                                val session = runCatching { engine.outputRuntimeSession()?.copy(sessionId = id) }.getOrNull()
                                    ?: return@mapNotNull null
                                val before = previous[session.identity]
                                val advancing = session.enabled && before?.session?.enabled == true &&
                                    session.hostRate != null && session.hostRate == before.session.hostRate &&
                                    session.sessionId == before.session.sessionId && progressed(before.session.processedFrames, session.processedFrames)
                                val boundary = before != null && (session.hostRate != before.session.hostRate ||
                                    session.sessionId != before.session.sessionId || session.enabled != before.session.enabled ||
                                    reset(before.session.processedFrames, session.processedFrames))
                                val supported = engine.liveprogPcmSupported()
                                val packet = if (supported) runCatching { engine.liveprogPcmSnapshot(session) }.getOrNull() else null
                                val fresh = packet?.ready == true && advancing &&
                                    (packet.sequence != before?.sequence || packet.generation != before?.generation)
                                Row(session, supported, packet, advancing, fresh, boundary)
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (!enabled.value || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collectLatest
                        val nowConfig = configuration.value
                        val sameScript = config?.identity == nowConfig?.identity && config?.expectedSourceHash == nowConfig?.expectedSourceHash
                        previous = IdentityHashMap<Any, Before>().also { next ->
                            rows.forEach { row ->
                                val old = previous[row.session.identity]?.takeUnless { row.boundary }
                                next[row.session.identity] = Before(row.session,
                                    row.packet?.takeIf { it.ready }?.sequence ?: old?.sequence,
                                    row.packet?.takeIf { it.ready }?.generation ?: old?.generation)
                            }
                        }
                        val active = rows.filter { it.advancing }
                        val selected = active.singleOrNull()
                        val statusRow = selected ?: rows.singleOrNull().takeIf { active.isEmpty() }
                        val packet = statusRow?.packet
                        val reason = when {
                            !sameScript -> "脚本已切换，等待新程序的采样"
                            nowConfig == null || nowConfig.path.isBlank() -> "请选择 DSP 脚本"
                            !nowConfig.enabled -> "DSP 脚本处理 已关闭 · 参数模型保留"
                            nowConfig.source.isBlank() -> "正在读取当前脚本参数"
                            rows.isEmpty() -> "驱动未连接"
                            globalIssue == GlobalPlaybackEvidence.Reason.SUSPENDED -> "全局音效链已挂起"
                            globalIssue == GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> "当前音频经过其他输出链路"
                            active.size > 1 -> "多路音频同时处理，暂不合并脚本监测"
                            rows.none { it.session.enabled } -> "等待活动音频处理链"
                            selected == null -> "等待正常播放与音频处理帧"
                            statusRow?.supported != true -> "需安装配套 1077 驱动并重启，才能读取脚本音频"
                            packet == null -> "等待配套驱动的脚本只读采样"
                            !packet.compiled && packet.compileResult < 0 -> "脚本编译失败，请查看原脚本编辑器的错误信息"
                            !packet.compiled -> "等待脚本成功编译"
                            packet.scriptHash != nowConfig.expectedSourceHash ->
                                if (packet.compileResult <= 0) "新脚本未编译成功，驱动保留原程序"
                                else "等待当前脚本装载，图纸为保存参数预览"
                            !packet.moduleEnabled -> "驱动中的DSP 脚本处理 尚未启用"
                            packet.rateMismatch -> "处理采样率已变化，等待脚本重新就绪"
                            !packet.executing -> "等待当前脚本实际处理音频"
                            packet.invalidSamples -> "跳过包含无效数值的真实采样窗口"
                            !packet.leaseActive -> "脚本只读采样已暂停"
                            !packet.ready || packet.frames == 0 -> "正在收集脚本前后同步音频"
                            !selected.fresh -> "等待新的真实采样窗口"
                            else -> null
                        }
                        onFrame(LiveprogAudioFrame(SystemClock.elapsedRealtime(), statusRow?.session?.identity,
                            statusRow?.session?.sessionId, packet.takeIf { reason == null },
                            reason ?: "真实脚本输入／输出 · 正常播放持续采样", packet?.rate,
                            !sameScript || statusRow?.boundary == true))
                        delay(100L)
                    }
                }
            }
        }
    }
    private fun progressed(before: Int?, after: Int?): Boolean = before != null && after != null && before >= 0 && after >= 0 &&
        (after > before || (before >= 0x70000000 && after <= 0x0fffffff))
    private fun reset(before: Int?, after: Int?): Boolean = before != null && after != null && before >= 0 && after >= 0 &&
        after < before && !(before >= 0x70000000 && after <= 0x0fffffff)
}
