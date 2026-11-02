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
import kotlin.math.log10
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence

/** Optional GET-only window capture. This observer never writes audio/audition state. */
object CompanderDashboardRuntimeMonitor {
    data class Sample(
        val elapsedRealtimeMs: Long,
        val streamIdentity: Any?,
        val reading: CompanderController.Reading?,
        val output: OutputRuntimeStatus.Snapshot,
        val pcm: CompanderPcmTelemetry.Snapshot?,
        val pcmUnavailableReason: String?,
        val levelUnavailableReason: String? = pcmUnavailableReason,
        val gain: CompanderGainTelemetry.Snapshot? = null,
        val gainUnavailableReason: String? = null,
        val streamSessionId: Int? = null,
        val runtimeUnavailableReason: String? = null
    ) {
        val unavailableReason: String? get() = levelUnavailableReason
        private fun rms(a: FloatArray?, b: FloatArray?): Float? {
            fun level(v: FloatArray?): Double? = v?.takeIf { it.isNotEmpty() }?.let { data ->
                val square = data.sumOf { it.toDouble() * it }
                if (square > 0) 10.0 * log10(square / data.size) else null
            }
            return listOfNotNull(level(a), level(b)).maxOrNull()?.toFloat()
        }
        private fun nativeRms(first: Int): Float? {
            val row = reading?.takeIf { it.active && runtimeUnavailableReason == null } ?: return null
            val packet = row.snapshot?.data?.takeIf { it[12] == 1 } ?: return null
            return listOf(packet[first], packet[first+1]).filter { it != Int.MIN_VALUE }.maxOrNull()?.div(100f)
        }
        val inputRmsDbfs: Float? get() = if (pcm != null) rms(pcm.inputLeft, pcm.inputRight) else nativeRms(20)
        val outputRmsDbfs: Float? get() = if (pcm != null) rms(pcm.outputLeft, pcm.outputRight) else nativeRms(22)
    }
    private data class Before(val output: OutputRuntimeStatus.Session, val generation: Int?, val sequence: Int?, val moduleEpoch: Int?)
    private data class Row(val output: OutputRuntimeStatus.Session, val supported: Boolean,
        val pcm: CompanderPcmTelemetry.Snapshot?, val reading: CompanderController.Reading?, val progressed: Boolean, val fresh: Boolean, val gain: CompanderGainTelemetry.Snapshot?)
    private fun fingerprint(context: Context) = CompanderController.preferences(context).all
        .filterKeys { it in CompanderParameters.keys || it == CompanderParameters.ENABLED }.toMap()
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
                        if (!(CompanderParameters.moduleEnabled(values))) return@collectLatest
                        val poll = CompanderController.pollState()
                        val audition = CompanderController.audition
                        val epoch = CompanderController.epoch
                        val application = app as? MainApplication
                        val targets = application?.rootSessionDatabase?.sessionList?.toList()
                            ?.mapNotNull { (id, session) -> (session as? RemoteEffectSession)?.effect?.let { id to it } }.orEmpty()
                        val mode = if (application?.isPlayerSessionMode == true) OutputRuntimeStatus.Mode.PLAYER_SESSION
                            else OutputRuntimeStatus.Mode.GLOBAL
                        val issue = if (mode == OutputRuntimeStatus.Mode.GLOBAL) when(application?.rootSessionDatabase?.globalPlaybackIssue) {
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
                                val supported = engine.companderPcmSupported()
                                val pcm = if (supported) runCatching { engine.companderPcmSnapshot(output) }.getOrNull() else null
                                val rawReading = runCatching { engine.companderRuntimeReading(output) }.getOrNull()
                                val snapshot = rawReading?.snapshot
                                val matching = snapshot?.let { packet -> packet.enabled && packet.professional && packet.ready &&
                                    packet.data[26] == output.hostRate && packet.data[2] == internalRate(output.hostRate) &&
                                    packet.mode == audition && (audition == 0 || packet.epoch == epoch) } == true
                                val reading = rawReading?.copy(active = advancing && matching &&
                                    progressed(before?.moduleEpoch, snapshot?.data?.get(1)))
                                val fresh = pcm != null && advancing &&
                                    (pcm.sequence != before?.sequence || pcm.generation != before?.generation) &&
                                    pcm.ready && pcm.moduleEnabled && pcm.leaseActive && !pcm.invalidSamples &&
                                    pcm.rate == internalRate(output.hostRate) && pcm.mode == audition &&
                                    (audition == 0 || pcm.comparisonEpoch == epoch)
                                Row(output, supported, pcm, reading, advancing, fresh,
                                    if (supported) runCatching { engine.companderGainSnapshot(output) }.getOrNull() else null)
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (!enabled.value || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collectLatest
                        val same = values == fingerprint(app) && poll == CompanderController.pollState() &&
                            audition == CompanderController.audition && epoch == CompanderController.epoch
                        previous = IdentityHashMap<Any, Before>().also { next ->
                            if (same) rows.forEach { row -> next[row.output.identity] = Before(row.output,
                                row.pcm?.generation ?: previous[row.output.identity]?.generation,
                                row.pcm?.sequence ?: previous[row.output.identity]?.sequence,
                                row.reading?.snapshot?.data?.get(1)) }
                        }
                        val output = tracker.update(rows.map { it.output }).copy(mode = mode, globalIssue = issue)
                        val active = rows.filter { it.progressed }
                        val selected = active.singleOrNull()
                        val candidate = selected?.pcm
                        val rawReading = selected?.reading
                        val matchingReading = rawReading?.let { it.copy(active = same && issue == null && it.active) }
                        val packet = matchingReading?.snapshot?.data
                        val runtimeReason = when {
                            !same -> "参数或试听状态正在更新"
                            !output.connected -> "驱动未连接"
                            issue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局处理链路已挂起"
                            issue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频使用其他输出链路"
                            active.size > 1 -> "多路音频同时处理，暂不合并读数"
                            selected == null -> "等待活动音频与处理帧"
                            packet == null -> "当前驱动未提供动态压扩器诊断"
                            packet[3] != 1 -> "动态压扩器已关闭"
                            packet[4] != 1 -> "原版动态压扩器：专业调节开启后提供原生电平"
                            matchingReading?.active != true -> "等待动态压扩器或试听参数同步"
                            else -> null
                        }
                        val levelReason = when {
                            runtimeReason != null -> runtimeReason
                            packet?.get(12) != 1 -> "原生“显示实时电平”已关闭"
                            packet != null && (16..23).all { packet[it] == Int.MIN_VALUE } -> "当前没有可用原生电平"
                            else -> null
                        }
                        val reason = when {
                            !same -> "参数或试听状态正在更新"
                            !output.connected -> "驱动未连接"
                            issue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局处理链路已挂起"
                            issue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频使用其他输出链路"
                            active.size > 1 -> "多路音频同时处理，暂不合并 PCM"
                            selected == null -> when(issue) {
                                OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局处理链路已挂起"
                                OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频使用其他输出链路"
                                else -> "等待活动音频与真实 PCM 窗口"
                            }
                            !selected.supported -> "当前驱动不提供动态压扩器 PCM，请更新配套 1076 驱动"
                            candidate == null -> "等待配套驱动的只读 PCM 采样"
                            !candidate.moduleEnabled -> "动态压扩器已关闭"
                            candidate.invalidSamples -> "窗口含无效样本，已跳过"
                            !candidate.ready || candidate.frames == 0 -> "正在收集真实 PCM 窗口"
                            !candidate.leaseActive -> "只读 PCM 采样租约已暂停"
                            !selected.fresh -> "等待新的 PCM 窗口或试听参数同步"
                            else -> null
                        }
                        // Separate GETs may straddle publication; use only the same native window.
                        val gain = selected?.gain?.takeIf { g -> same && issue == null && reason == null &&
                            candidate != null && g.available && g.hostRate == selected?.output?.hostRate &&
                            g.rate == candidate.rate && g.sequence == candidate.sequence &&
                            g.generation == candidate.generation && g.lastFramePosition == candidate.lastFramePosition &&
                            g.mode == audition && (audition == 0 || g.epoch == epoch) }
                        onSample(Sample(SystemClock.elapsedRealtime(), selected?.output?.identity,
                            matchingReading, output, candidate.takeIf { reason == null }, reason, levelReason,
                            gain, if (gain != null) null else reason ?: "等待同窗七个控制频点实际增益",
                            selected?.output?.sessionId, runtimeReason))
                        delay(250L)
                    }
                }
            }
        }
    }
    // James may process low-rate hosts at an internal 44.1/48 kHz base.
    private fun internalRate(host: Int?): Int? = host?.let {
        if (it >= 44100) it else if (it > 0 && 48000 % it == 0) 48000
        else if (it > 0 && 44100 % it == 0) 44100 else 48000
    }
    private fun progressed(before: Int?, after: Int?): Boolean = before != null && after != null && before >= 0 &&
        after >= 0 && (after > before || (before >= 0x70000000 && after <= 0x0fffffff))
}
