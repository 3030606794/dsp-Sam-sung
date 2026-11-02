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
 * Optional read-only analog-colour dashboard sampling, with no microphone permission.
 *
 * Native 131226 meter words 16/17 are input L/R peak holds, 18/19 are output
 * peak holds, 20/21 are input RMS and 22/23 are output RMS (0.01 dBFS).
 * RMS uses 100 ms exponential mean-square smoothing; peak decay is about 0.6 s.
 * The original professional/METERS controls govern these old meters only.
 *
 * New GET-only PCM observation is independent from METERS and quality controls.
 * Its processed wet branch is the normal configuration's full pre-mix output,
 * not just newly created harmonics; its selected output follows the real A/B
 * route. All delivery requires advancing host frames and a fresh PCM sequence.
 * Folding charts never modifies this subscription or any controller cache.
 */
object AnalogDashboardRuntimeMonitor {
    data class Sample(
        val elapsedRealtimeMs: Long,
        val streamIdentity: Any?,
        val reading: AnalogController.Reading?,
        val output: OutputRuntimeStatus.Snapshot,
        val inputRmsLeftDbfs: Float?,
        val inputRmsRightDbfs: Float?,
        val outputRmsLeftDbfs: Float?,
        val outputRmsRightDbfs: Float?,
        val inputPeakLeftDbfs: Float?,
        val inputPeakRightDbfs: Float?,
        val outputPeakLeftDbfs: Float?,
        val outputPeakRightDbfs: Float?,
        val levelUnavailableReason: String?,
        val pcm: AnalogPcmTelemetry.Snapshot? = null,
        val pcmUnavailableReason: String? = null,
        val streamSessionId: Int? = null,
        val runtimeUnavailableReason: String? = null
    ) {
        val unavailableReason: String? get() = levelUnavailableReason
        /** Largest channel, not a phase-correlated stereo sum. */
        val inputRmsDbfs: Float? get() = maximum(inputRmsLeftDbfs, inputRmsRightDbfs)
        val outputRmsDbfs: Float? get() = maximum(outputRmsLeftDbfs, outputRmsRightDbfs)
        val inputPeakDbfs: Float? get() = maximum(inputPeakLeftDbfs, inputPeakRightDbfs)
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
        val analog: List<AnalogController.Reading>,
        val baselines: IdentityHashMap<Any, Baseline>,
        val activeOutputIdentities: List<Any>,
        val pcmSupportedIdentities: List<Any>,
        val pcms: IdentityHashMap<Any, AnalogPcmTelemetry.Snapshot>,
        val freshPcmIdentities: List<Any>
    )

    private const val INTERVAL_MS = 250L

    /** The coordinator gates only module/page availability and power, never folds. */
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
                        val poll = AnalogController.pollState()
                        val auditionEpoch = AnalogController.epoch
                        val auditionMode = AnalogController.audition
                        val savedValues = AnalogController.preferences(appContext).all
                        if (!AnalogParameters.moduleEnabled(savedValues)) return@collectLatest
                        val savedSettings = AnalogParameters.capture(savedValues)
                        val result = withContext(Dispatchers.IO) {
                            if (!enabled.value) return@withContext null
                            read(targets, previous, auditionEpoch, auditionMode)
                        }
                        currentCoroutineContext().ensureActive()
                        if (!enabled.value ||
                            !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                            return@collectLatest
                        if (result != null) {
                            val output = tracker.update(result.outputs)
                                .copy(mode = targets.mode, globalIssue = targets.issue)
                            val currentValues = AnalogController.preferences(appContext).all
                            val sameGeneration = AnalogController.pollState() == poll &&
                                AnalogController.epoch == auditionEpoch &&
                                AnalogController.audition == auditionMode &&
                                AnalogParameters.moduleEnabled(currentValues) &&
                                AnalogParameters.capture(currentValues) == savedSettings
                            previous = if (sameGeneration) result.baselines else IdentityHashMap()
                            onSample(sample(result, output, sameGeneration))
                        }
                        delay(INTERVAL_MS)
                    }
                    // Stopping display observation has no audition/DSP teardown.
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
        auditionMode: Int
    ): Readings {
        val outputs = ArrayList<OutputRuntimeStatus.Session>(targets.engines.size)
        val readings = ArrayList<AnalogController.Reading>(targets.engines.size)
        val baselines = IdentityHashMap<Any, Baseline>()
        val activeIdentities = ArrayList<Any>(targets.engines.size)
        val pcmSupportedIdentities = ArrayList<Any>(targets.engines.size)
        val pcms = IdentityHashMap<Any, AnalogPcmTelemetry.Snapshot>()
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
            val reading = runCatching { engine.analogRuntimeReading(output) }.getOrNull()
            val snapshot = reading?.snapshot
            currentCoroutineContext().ensureActive()
            val pcmSupported = engine.analogPcmSupported()
            if (pcmSupported) pcmSupportedIdentities += output.identity
            val pcm = if (pcmSupported) runCatching {
                engine.analogPcmSnapshot(output)
            }.getOrNull() else null
            if (pcm != null) {
                pcms[output.identity] = pcm
                val comparisonMatches = if (auditionMode == 0) true
                    else pcm.comparisonEpoch == auditionEpoch
                // The native runtime reports the selected A/B kernel's actual
                // base, whereas PCM also carries the separate normal branch base.
                // During queued-kernel warmup that active base may legitimately
                // differ from newly saved preferences; show real active samples.
                val selectedBaseMatches = snapshot?.baseMode == pcm.selectedBaseMode
                val moduleMatching = pcm.rate == output.hostRate && pcm.mode == auditionMode && comparisonMatches && selectedBaseMatches &&
                    pcm.moduleEnabled && pcm.leaseActive && pcm.ready &&
                    !pcm.invalidSamples && pcm.frames > 0
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
                    it.ready && it.mode == auditionMode && comparisonMatches
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
            result.analog.singleOrNull { it.identity === activeIdentity }
        else if (result.activeOutputIdentities.isEmpty())
            result.analog.filter { it.enabled }.singleOrNull()
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
            packet == null -> "当前驱动未提供谐波染色诊断"
            packet[3] != 1 -> "谐波染色已关闭"
            packet[4] != 1 -> "原版染色处理：专业调节开启后才提供原生电平"
            reading?.active != true -> "等待谐波染色处理或试听参数同步"
            else -> null
        }
        val levelReason = when {
            runtimeReason != null -> runtimeReason
            packet?.get(12) != 1 -> "原生“显示实时电平”已关闭"
            packet != null && (16..23).all { packet[it] == Int.MIN_VALUE } -> "当前没有可用原生电平"
            else -> null
        }
        val levels = packet.takeIf { levelReason == null }
        val rawPcm = activeIdentity?.let { result.pcms[it] }
        val pcmReason = when {
            !sameGeneration -> "参数或试听状态正在更新"
            !output.connected -> "驱动未连接"
            output.globalIssue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局处理链路已挂起"
            output.globalIssue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频使用其他输出链路"
            result.activeOutputIdentities.size > 1 -> "多路音频同时处理，暂不合并 PCM"
            activeIdentity == null -> "等待活动音频与真实 PCM 窗口"
            result.pcmSupportedIdentities.none { it === activeIdentity } ->
                "当前驱动不提供谐波染色 PCM，请更新配套 1076 驱动"
            rawPcm == null -> "等待配套驱动的只读 PCM 采样"
            !rawPcm.moduleEnabled -> "谐波染色已关闭"
            rawPcm.invalidSamples -> "当前 PCM 窗口含无效样本，已跳过"
            !rawPcm.ready || rawPcm.frames == 0 -> "正在收集真实 PCM 窗口"
            !rawPcm.leaseActive -> "只读 PCM 采样租约已暂停"
            result.freshPcmIdentities.none { it === activeIdentity } ->
                "等待新的 PCM 窗口或谐波染色参数同步"
            else -> null
        }
        val pcm = rawPcm.takeIf { pcmReason == null }
        return Sample(SystemClock.elapsedRealtime(), activeIdentity ?: reading?.identity, reading, output,
            level(levels, 20), level(levels, 21), level(levels, 22), level(levels, 23),
            level(levels, 16), level(levels, 17), level(levels, 18), level(levels, 19),
            levelReason, pcm, pcmReason,
            result.outputs.singleOrNull { it.identity === (activeIdentity ?: reading?.identity) }?.sessionId,
            runtimeReason)
    }

    private fun level(packet: List<Int>?, index: Int): Float? = packet?.get(index)
        ?.takeUnless { it == Int.MIN_VALUE }?.div(100f)

    private fun progressed(before: Int?, after: Int?): Boolean =
        before != null && after != null && before >= 0 && after >= 0 &&
            (after > before || (before >= 0x70000000 && after <= 0x0fffffff))
}
