package me.timschneeberger.rootlessjamesdsp.peq

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence
import java.util.IdentityHashMap

/** Real PCM observer; a mismatch can request, but never directly write, a saved-bank resync. */
internal object PeqRuntimeMonitor {
    data class Configuration(val state: PeqState, val enabled: Boolean)
    data class Frame(val sample: PeqPcmTelemetry.Snapshot?, val message: String, val clear: Boolean)
    private data class Before(val session: OutputRuntimeStatus.Session,
        val packet: PeqPcmTelemetry.Snapshot?, val freshAt: Long)
    private data class Row(val engine: JamesDspRemoteEngine, val session: OutputRuntimeStatus.Session, val supported: Boolean,
        val packet: PeqPcmTelemetry.Snapshot?, val advancing: Boolean, val fresh: Boolean,
        val boundary: Boolean, val previousFreshAt: Long)
    private data class RepairKey(val identity: Any, val captureId: Int, val generation: Int,
        val rate: Int, val hostRate: Int, val hash: Int, val enabled: Boolean)
    private class Repair(val engine: JamesDspRemoteEngine, val key: RepairKey, val startedAt: Long) {
        var attempts = 0
        var lastAttemptAt = startedAt
    }

    fun bind(owner: LifecycleOwner, context: Context,
        configuration: () -> Configuration, captureVisible: () -> Boolean = { true },
        onFrame: (Frame) -> Unit) {
        val app = context.applicationContext as? MainApplication ?: return
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var previous = IdentityHashMap<Any, Before>()
                var selectedIdentity: Any? = null
                var selectedCapture: Int? = null
                var selectedGeneration: Int? = null
                var lastValidAt = SystemClock.elapsedRealtime()
                var repair: Repair? = null
                fun cancelRepair() {
                    repair?.let { it.engine.cancelPeqParameterResync(it.key.identity) }
                    repair = null
                }
                try {
                while (currentCoroutineContext().isActive) {
                    if (!captureVisible()) {
                        cancelRepair()
                        previous.clear()
                        delay(200L)
                        continue
                    }
                    val targets = app.rootSessionDatabase.sessionList.toList().mapNotNull { (id, session) ->
                        (session as? RemoteEffectSession)?.effect?.let { id to it }
                    }
                    val globalIssue = if (!app.isPlayerSessionMode) app.rootSessionDatabase.globalPlaybackIssue else null
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
                            val advancing = before != null && !boundary && session.enabled &&
                                before.session.enabled && session.hostRate?.let { it in 8000..768000 } == true &&
                                progressed(before.session.processedFrames, session.processedFrames)
                            val supported = runCatching { engine.peqPcmSupported() }.getOrDefault(false)
                            val packet = if (supported && session.enabled)
                                runCatching { engine.peqPcmSnapshot(session) }.getOrNull() else null
                            val oldPacket = before?.packet
                            val fresh = advancing && packet?.valid == true &&
                                packet.hostRate == session.hostRate && packet.rate in 8000..768000 &&
                                (oldPacket == null || packet.captureId != oldPacket.captureId || packet.generation != oldPacket.generation ||
                                    packet.rate != oldPacket.rate ||
                                    packet.sequence != oldPacket.sequence && packet.lastFramePosition > oldPacket.lastFramePosition)
                            Row(engine, session, supported, packet, advancing, fresh, boundary, before?.freshAt ?: now)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@repeatOnLifecycle
                    if (!captureVisible()) {
                        cancelRepair()
                        previous.clear()
                        continue
                    }
                    previous = IdentityHashMap<Any, Before>().also { next ->
                        rows.forEach { row ->
                            val old = previous[row.session.identity]?.takeUnless { row.boundary }
                            next[row.session.identity] = Before(row.session,
                                row.packet?.takeIf { it.valid && it.hostRate == row.session.hostRate } ?: old?.packet,
                                if (row.fresh) now else old?.freshAt ?: now)
                        }
                    }
                    val active = rows.filter { it.advancing }
                    // A global chain may expose multiple processing instances but only one carries
                    // this module's music input. Never add independent windows or use post-mix audio.
                    val audible = active.filter { row -> row.packet?.let { packet ->
                        packet.valid && packet.hostRate == row.session.hostRate &&
                            packet.inputRmsDbfs.isFinite() && packet.inputRmsDbfs > -240.0 &&
                            (row.fresh || now - row.previousFreshAt <= 600L)
                    } == true }
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
                        audible.size > 1 -> "多路PEQ输入都有音乐，暂不合并频谱"
                        active.size > 1 && selected == null -> "多路活动实例未找到唯一PEQ有声输入，暂不合并频谱"
                        rows.none { it.supported } || selected?.supported == false ->
                            "需安装配套1078或更新驱动，才能读取PEQ前后音频"
                        selected == null -> "等待正常播放与音频处理帧"
                        packet == null -> "等待PEQ只读音频采样"
                        !packet.leaseActive -> "PEQ只读采样已暂停"
                        packet.invalidSamples -> "采样窗口包含无效数值，已跳过"
                        !packet.valid -> "正在收集PEQ前后同步音频"
                        packet.hostRate != selected.session.hostRate -> "采样率切换中，等待新窗口"
                        !selected.fresh -> "等待新的真实音频窗口"
                        else -> null
                    }
                    if (issue != null) {
                        if (selected == null || globalIssue != null || selected.boundary ||
                            repair?.key?.identity?.let { it !== selected.session.identity } == true ||
                            packet != null && repair?.key?.let {
                                it.captureId != packet.captureId || it.generation != packet.generation ||
                                    it.rate != packet.rate || it.hostRate != packet.hostRate
                            } == true) cancelRepair()
                        // A target can change between GET and completion of its next 1024-frame
                        // window. Retain the preceding real spectrum briefly, instead of blanking
                        // the chart and restarting its FFT worker on every slider movement.
                        val clear = selected == null || globalIssue != null ||
                            packet?.invalidSamples == true || selected.boundary ||
                            selectedIdentity !== selected.session.identity ||
                            packet != null && selectedCapture != null && selectedCapture != packet.captureId ||
                            packet != null && selectedGeneration != null && selectedGeneration != packet.generation ||
                            now - lastValidAt > 600L
                        onFrame(Frame(null, issue, clear))
                    } else if (packet != null && selected != null) {
                        val changedStream = selectedIdentity !== selected.session.identity || selectedCapture != packet.captureId ||
                            selectedGeneration != packet.generation
                        if (changedStream) {
                            cancelRepair()
                            onFrame(Frame(null, "音频链路已切换", true))
                        }
                        selectedIdentity = selected.session.identity
                        selectedCapture = packet.captureId
                        selectedGeneration = packet.generation
                        lastValidAt = now
                        val expected = configuration()
                        val hash = runCatching { PeqPcmTelemetry.expectedParameterHash(expected.state, expected.enabled) }.getOrNull()
                        val mismatched = packet.moduleEnabled != expected.enabled || hash != null && hash != packet.parameterHash
                        if (!mismatched || hash == null) cancelRepair()
                        else if (!packet.transitioning && !packet.configurationChanged && globalIssue == null) {
                            val key = RepairKey(selected.session.identity, packet.captureId, packet.generation,
                                packet.rate, packet.hostRate, hash, expected.enabled)
                            if (repair?.key != key) {
                                cancelRepair()
                                repair = Repair(selected.engine, key, now)
                            }
                            val current = requireNotNull(repair)
                            if (current.attempts < 3 && now - current.startedAt >= 500L &&
                                now - current.lastAttemptAt >= 500L) {
                                current.lastAttemptAt = now
                                val accepted = withContext(Dispatchers.IO) {
                                    selected.engine.requestPeqParameterResync(selected.session, hash, expected.enabled)
                                }
                                currentCoroutineContext().ensureActive()
                                if (accepted) {
                                    if (repair !== current || !captureVisible() ||
                                        !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                                        current.engine.cancelPeqParameterResync(current.key.identity)
                                    else current.attempts++
                                }
                            }
                        }
                        // Fresh native windows remain true measurements during a drag. A pending
                        // saved bank is labelled explicitly instead of starving the live plot.
                        val message = when {
                            !packet.inputRmsDbfs.isFinite() || packet.inputRmsDbfs <= -240.0 ->
                                "PEQ输入静音 · 未检测到本模块输入音乐"
                            packet.transitioning || packet.configurationChanged -> "真实输入／输出 · 参数过渡中"
                            mismatched && repair?.let { it.attempts >= 3 && now - it.lastAttemptAt >= 500L } == true ->
                                "参数未同步，请重试音效设置"
                            mismatched -> "参数尚未生效，正在重新同步"
                            !packet.moduleEnabled -> "真实输入／输出 · PEQ旁路"
                            else -> "真实PEQ输入／输出 · 正常播放持续采样"
                        }
                        onFrame(Frame(packet, message, false))
                    }
                    delay(50L)
                }
                } finally { cancelRepair() }
            }
        }
    }
    private fun progressed(before: Int?, after: Int?): Boolean = before != null && after != null && before >= 0 && after >= 0 &&
        (after > before || before >= 0x70000000 && after <= 0x0fffffff)
    private fun reset(before: Int?, after: Int?): Boolean = before != null && after != null && before >= 0 && after >= 0 &&
        after < before && !(before >= 0x70000000 && after <= 0x0fffffff)
}
