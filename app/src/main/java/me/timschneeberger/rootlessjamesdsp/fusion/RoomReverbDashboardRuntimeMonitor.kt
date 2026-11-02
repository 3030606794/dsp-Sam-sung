package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.util.IdentityHashMap
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
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence

/**
 * Read-only telemetry for the original SF room-reverb dashboard, not ViPER reverb.
 *
 * Runtime 131224 exposes the selected module output only: words 16/17 are L/R
 * peak holds (about 0.6-second decay), while 18/19 are L/R RMS with 100-millisecond
 * exponential mean-square smoothing. All four are in 0.01 dBFS; INT_MIN means
 * unavailable or zero, never 0 dBFS. The original professional/METERS controls
 * must already be enabled; this observer never enables either control.
 *
 * New drivers additionally expose GET-only synchronous input/wet/output PCM.
 * That optional observation lease is independent from original professional
 * and METERS controls. PCM validity is separate from the original meter state;
 * no global Visualizer, microphone capture or persistent audio setting is used.
 *
 * The native word-1 even epoch advances per processed block (and control update),
 * whereas word 11 is the A/B comparison epoch, which is zero in normal mode.
 * A sample needs both advancing host frames and a fresh matching module epoch.
 * Nothing is published into the existing runtime cache or A/B controller.
 */
object RoomReverbDashboardRuntimeMonitor {
    data class Sample(
        val elapsedRealtimeMs: Long,
        val streamIdentity: Any?,
        val reading: RoomReverbController.Reading?,
        val output: OutputRuntimeStatus.Snapshot,
        val outputRmsLeftDbfs: Float?,
        val outputRmsRightDbfs: Float?,
        val outputPeakLeftDbfs: Float?,
        val outputPeakRightDbfs: Float?,
        val unavailableReason: String?,
        val pcm: RoomReverbPcmTelemetry.Snapshot? = null,
        val pcmUnavailableReason: String? = null,
        val streamSessionId: Int? = null,
        val runtimeUnavailableReason: String? = null
    ) {
        /** Kept separately so an original METERS-off state cannot hide valid PCM. */
        val levelUnavailableReason: String? get() = unavailableReason
        /** Largest channel level, not a summed or phase-correlated stereo meter. */
        val outputRmsDbfs: Float? get() = maximum(outputRmsLeftDbfs, outputRmsRightDbfs)
        val outputPeakDbfs: Float? get() = maximum(outputPeakLeftDbfs, outputPeakRightDbfs)
        companion object {
            private fun maximum(left: Float?, right: Float?): Float? = when {
                left != null && right != null -> maxOf(left, right)
                left != null -> left
                else -> right
            }
        }
    }

    private data class Targets(
        val engines: List<Pair<Int, JamesDspRemoteEngine>>,
        val mode: OutputRuntimeStatus.Mode,
        val issue: OutputRuntimeStatus.GlobalIssue?
    )

    private data class Baseline(
        val output: OutputRuntimeStatus.Session,
        val moduleEpoch: Int?,
        val pcmGeneration: Int?,
        val pcmSequence: Int?
    )

    private data class Readings(
        val outputs: List<OutputRuntimeStatus.Session>,
        val room: List<RoomReverbController.Reading>,
        val baselines: IdentityHashMap<Any, Baseline>,
        val activeOutputIdentities: List<Any>,
        val pcmSupportedIdentities: List<Any>,
        val pcms: IdentityHashMap<Any, RoomReverbPcmTelemetry.Snapshot>,
        val freshPcmIdentities: List<Any>
    )

    private const val INTERVAL_MS = 250L

    /** Enable with page/module availability and power only; folding is unrelated. */
    fun bind(
        owner: LifecycleOwner,
        context: Context,
        enabled: StateFlow<Boolean>,
        onSample: (Sample) -> Unit
    ) {
        val appContext = context.applicationContext
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                enabled.collectLatest { monitoring ->
                    if (!monitoring) return@collectLatest
                    var previous = IdentityHashMap<Any, Baseline>()
                    val tracker = OutputRuntimeStatus.Tracker()
                    while (currentCoroutineContext().isActive && enabled.value) {
                        val targets = targets(appContext)
                        val poll = RoomReverbController.pollState()
                        val auditionEpoch = RoomReverbController.epoch
                        val auditionMode = RoomReverbController.audition
                        val savedValues = RoomReverbController.preferences(appContext).all
                        if (!RoomReverbParameters.moduleEnabled(savedValues))
                            return@collectLatest
                        val savedSettings = RoomReverbParameters.capture(savedValues)
                        val result = withContext(Dispatchers.IO) {
                            if (!enabled.value) return@withContext null
                            read(targets, previous, auditionEpoch, auditionMode, savedSettings.base)
                        }
                        currentCoroutineContext().ensureActive()
                        if (!enabled.value ||
                            !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                            return@collectLatest
                        if (result != null) {
                            val output = tracker.update(result.outputs)
                                .copy(mode = targets.mode, globalIssue = targets.issue)
                            val currentValues = RoomReverbController.preferences(appContext).all
                            val sameGeneration = RoomReverbController.pollState() == poll &&
                                RoomReverbController.epoch == auditionEpoch &&
                                RoomReverbController.audition == auditionMode &&
                                RoomReverbParameters.moduleEnabled(currentValues) &&
                                RoomReverbParameters.capture(currentValues) == savedSettings
                            previous = if (sameGeneration) result.baselines
                                else IdentityHashMap()
                            onSample(sample(result, output, sameGeneration))
                        }
                        delay(INTERVAL_MS)
                    }
                    // Cancellation has no audio or audition teardown side effects.
                }
            }
        }
    }

    private fun targets(context: Context): Targets {
        val application = context as? MainApplication
        val mode = if (application?.isPlayerSessionMode == true)
            OutputRuntimeStatus.Mode.PLAYER_SESSION else OutputRuntimeStatus.Mode.GLOBAL
        val issue = if (mode == OutputRuntimeStatus.Mode.GLOBAL) when (
            application?.rootSessionDatabase?.globalPlaybackIssue) {
            GlobalPlaybackEvidence.Reason.SUSPENDED -> OutputRuntimeStatus.GlobalIssue.SUSPENDED
            GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT
            null -> null
        } else null
        val engines = application?.rootSessionDatabase?.sessionList?.toList()
            ?.mapNotNull { (id, session) ->
                (session as? RemoteEffectSession)?.effect?.let { id to it }
            }.orEmpty()
        return Targets(engines, mode, issue)
    }

    private suspend fun read(
        targets: Targets,
        previous: IdentityHashMap<Any, Baseline>,
        auditionEpoch: Int,
        auditionMode: Int,
        base: Int
    ): Readings {
        val outputs = ArrayList<OutputRuntimeStatus.Session>(targets.engines.size)
        val readings = ArrayList<RoomReverbController.Reading>(targets.engines.size)
        val baselines = IdentityHashMap<Any, Baseline>()
        val activeIdentities = ArrayList<Any>(targets.engines.size)
        val pcmSupportedIdentities = ArrayList<Any>(targets.engines.size)
        val pcms = IdentityHashMap<Any, RoomReverbPcmTelemetry.Snapshot>()
        val freshPcmIdentities = ArrayList<Any>(targets.engines.size)
        for ((id, engine) in targets.engines) {
            currentCoroutineContext().ensureActive()
            val output = runCatching {
                engine.outputRuntimeSession()?.copy(sessionId = id)
            }.getOrNull() ?: continue
            outputs += output
            val before = previous[output.identity]
            val hostActive = output.enabled && before != null && before.output.enabled &&
                before.output.hostRate == output.hostRate &&
                before.output.sessionId == output.sessionId &&
                output.hostRate?.let { it in 8000..768000 } == true &&
                progressed(before.output.processedFrames, output.processedFrames)
            if (hostActive) activeIdentities += output.identity
            val reading = runCatching {
                engine.roomReverbRuntimeReading(output)
            }.getOrNull()
            val snapshot = reading?.snapshot
            currentCoroutineContext().ensureActive()
            val pcmSupported = engine.roomReverbPcmSupported()
            if (pcmSupported) pcmSupportedIdentities += output.identity
            val pcm = if (pcmSupported) runCatching {
                engine.roomReverbPcmSnapshot(output)
            }.getOrNull() else null
            if (pcm != null) {
                pcms[output.identity] = pcm
                val comparisonMatches = if (auditionMode == 0) true
                    else pcm.comparisonEpoch == auditionEpoch
                val moduleMatching = pcm.rate == output.hostRate && pcm.base == base && pcm.mode == auditionMode &&
                    comparisonMatches && pcm.moduleEnabled && pcm.leaseActive &&
                    pcm.ready && !pcm.invalidSamples && pcm.frames > 0
                val newWindow = pcm.generation != before?.pcmGeneration ||
                    pcm.sequence != before?.pcmSequence
                if (hostActive && moduleMatching && newWindow)
                    freshPcmIdentities += output.identity
            }
            baselines[output.identity] = Baseline(output, snapshot?.data?.get(1),
                pcm?.generation ?: before?.pcmGeneration,
                pcm?.sequence ?: before?.pcmSequence)
            if (reading == null) continue
            val matching = snapshot?.let {
                val comparisonMatches = if (auditionMode == 0) true
                    else it.epoch == auditionEpoch
                it.data[2] == output.hostRate && it.enabled && it.professional &&
                    it.ready && it.data[22] == base && it.mode == auditionMode &&
                    comparisonMatches
            } == true
            readings += reading.copy(active = hostActive && matching &&
                progressed(before?.moduleEpoch, snapshot?.data?.get(1)))
        }
        return Readings(outputs, readings, baselines, activeIdentities,
            pcmSupportedIdentities, pcms, freshPcmIdentities)
    }

    private fun sample(
        result: Readings,
        output: OutputRuntimeStatus.Snapshot,
        sameGeneration: Boolean
    ): Sample {
        val activeIdentity = result.activeOutputIdentities.singleOrNull()
        val candidate = if (activeIdentity != null)
            result.room.singleOrNull { it.identity === activeIdentity }
        else if (result.activeOutputIdentities.isEmpty())
            result.room.filter { it.enabled }.singleOrNull()
        else null
        val reading = candidate?.let { row -> row.copy(active = sameGeneration && output.globalIssue == null &&
            result.activeOutputIdentities.size == 1 && row.active) }
        val packet = reading?.snapshot?.data
        val runtimeReason = when {
            !sameGeneration -> "参数或试听状态正在更新"
            !output.connected -> "驱动未连接"
            output.globalIssue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局处理链路已挂起"
            output.globalIssue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频使用其他输出链路"
            result.activeOutputIdentities.size > 1 -> "多路音频同时处理，暂不合并读数"
            result.activeOutputIdentities.isEmpty() -> when (output.globalIssue) {
                OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局处理链路已挂起"
                OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频使用其他输出链路"
                else -> "等待活动音频与处理帧"
            }
            packet == null -> "当前驱动未提供场景混响诊断"
            packet[3] != 1 -> "场景混响已关闭"
            packet[4] != 1 -> "原房型处理：专业调节开启后才提供输出电平"
            reading?.active != true -> "等待场景混响处理或试听参数同步"
            else -> null
        }
        val reason = when {
            runtimeReason != null -> runtimeReason
            packet?.get(12) != 1 -> "原生“显示实时电平”已关闭"
            packet != null && (16..19).all { packet[it] == Int.MIN_VALUE } -> "当前没有可用输出电平"
            else -> null
        }
        val levels = packet.takeIf { runtimeReason == null && it?.get(12) == 1 }
        val rawPcm = activeIdentity?.let { result.pcms[it] }
        val pcmReason = when {
            !sameGeneration -> "参数或试听状态正在更新"
            !output.connected -> "驱动未连接"
            output.globalIssue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局处理链路已挂起"
            output.globalIssue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频使用其他输出链路"
            result.activeOutputIdentities.size > 1 -> "多路音频同时处理，暂不合并 PCM"
            activeIdentity == null -> "等待活动音频与真实 PCM 窗口"
            result.pcmSupportedIdentities.none { it === activeIdentity } ->
                "当前驱动不提供分离 PCM，等待支持分离采样的配套驱动"
            rawPcm == null -> "等待配套驱动的只读 PCM 采样"
            !rawPcm.moduleEnabled -> "场景混响已关闭"
            rawPcm.invalidSamples -> "当前 PCM 窗口含无效样本，已跳过"
            !rawPcm.ready || rawPcm.frames == 0 -> "正在收集真实 PCM 窗口"
            !rawPcm.leaseActive -> "只读 PCM 采样租约已暂停"
            result.freshPcmIdentities.none { it === activeIdentity } ->
                "等待新的 PCM 窗口或房间参数同步"
            else -> null
        }
        val pcm = rawPcm.takeIf { pcmReason == null }
        return Sample(SystemClock.elapsedRealtime(), activeIdentity ?: reading?.identity, reading, output,
            level(levels, 18), level(levels, 19), level(levels, 16), level(levels, 17),
            reason, pcm, pcmReason,
            result.outputs.singleOrNull { it.identity === (activeIdentity ?: reading?.identity) }?.sessionId,
            runtimeReason)
    }

    private fun level(packet: List<Int>?, index: Int): Float? = packet?.get(index)
        ?.takeUnless { it == Int.MIN_VALUE }?.div(100f)

    private fun progressed(before: Int?, after: Int?): Boolean =
        before != null && after != null && before >= 0 && after >= 0 &&
            (after > before || (before >= 0x70000000 && after <= 0x0fffffff))
}
