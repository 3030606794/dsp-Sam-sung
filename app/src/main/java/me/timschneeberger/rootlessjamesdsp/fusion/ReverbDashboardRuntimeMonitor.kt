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
 * Optional read-only observer for the panoramic ViPER reverb dashboard.
 *
 * Native runtime words 16..21 are input L/R, normal wet-branch L/R and selected
 * output L/R peak holds in 0.01 dBFS, with approximately 0.5-second decay. They
 * are not dry/wet RMS measurements, and are absent while the original METERS
 * parameter is off. The dashboard never changes METERS or any audio setting.
 * Word 1 is reserved; word 11 is the audition epoch. Advancing independent host
 * frames and module word 29 are used to reject stale samples instead.
 *
 * Collapsing or hiding a chart must not change [bind]'s enabled flow. Only the
 * dashboard power, page/module availability and foreground lifecycle own this
 * subscription. No result is published into the original A/B controller cache.
 */
object ReverbDashboardRuntimeMonitor {
    data class Sample(
        val elapsedRealtimeMs: Long,
        val streamIdentity: Any?,
        val reading: ReverbController.Reading?,
        val output: OutputRuntimeStatus.Snapshot,
        val inputPeakDbfs: Float?,
        val wetPeakDbfs: Float?,
        val outputPeakDbfs: Float?,
        val duckReductionDb: Float?,
        val unavailableReason: String?,
        val streamSessionId: Int? = null
    )

    private data class Targets(
        val engines: List<Pair<Int, JamesDspRemoteEngine>>,
        val mode: OutputRuntimeStatus.Mode,
        val issue: OutputRuntimeStatus.GlobalIssue?
    )

    private data class Baseline(
        val output: OutputRuntimeStatus.Session,
        val moduleFrames: Int?
    )

    private data class Readings(
        val outputs: List<OutputRuntimeStatus.Session>,
        val reverb: List<ReverbController.Reading>,
        val baselines: IdentityHashMap<Any, Baseline>,
        val activeOutputIdentities: List<Any>
    )

    private const val INTERVAL_MS = 250L

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
                        // Session references and preference/controller generations belong
                        // to the main thread. Only driver GET operations run on IO.
                        val targets = targets(appContext)
                        val poll = ReverbController.pollState()
                        val auditionEpoch = ReverbController.epoch
                        val auditionMode = ReverbController.audition
                        val savedValues = ReverbController.preferences(appContext).all
                        if (!ReverbParameters.moduleEnabled(savedValues))
                            return@collectLatest
                        val savedSettings = ReverbParameters.capture(savedValues)
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
                            val currentValues = ReverbController.preferences(appContext).all
                            val sameGeneration = ReverbController.pollState() == poll &&
                                ReverbController.epoch == auditionEpoch &&
                                ReverbController.audition == auditionMode &&
                                ReverbParameters.moduleEnabled(currentValues) &&
                                ReverbParameters.capture(currentValues) == savedSettings
                            previous = if (sameGeneration) result.baselines
                                else IdentityHashMap()
                            onSample(sample(result, output, sameGeneration))
                        }
                        delay(INTERVAL_MS)
                    }
                    // Cancelling this observer does not stop playback, clear A/B
                    // snapshots, publish controller state or modify DSP parameters.
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
        val readings = ArrayList<ReverbController.Reading>(targets.engines.size)
        val baselines = IdentityHashMap<Any, Baseline>()
        val activeIdentities = ArrayList<Any>(targets.engines.size)
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
                engine.reverbRuntimeReading(output)
            }.getOrNull()
            val snapshot = reading?.snapshot
            baselines[output.identity] = Baseline(output, snapshot?.data?.get(29))
            if (reading == null) continue
            val matching = snapshot?.let {
                it.data[2] == output.hostRate && it.enabled && it.professional &&
                    it.ready && it.mode == auditionMode && (auditionMode == 0 || it.epoch == auditionEpoch)
            } == true
            val moduleFrames = snapshot?.data?.get(29)
            // The native module counter saturates at INT_MAX. Beyond that point,
            // host-frame progress remains required; a counter reset is not progress.
            val moduleActive = progressed(before?.moduleFrames, moduleFrames) ||
                (before?.moduleFrames == Int.MAX_VALUE && moduleFrames == Int.MAX_VALUE)
            readings += reading.copy(active = hostActive && matching && moduleActive)
        }
        return Readings(outputs, readings, baselines, activeIdentities)
    }

    private fun sample(
        result: Readings,
        output: OutputRuntimeStatus.Snapshot,
        sameGeneration: Boolean
    ): Sample {
        val activeIdentity = result.activeOutputIdentities.singleOrNull()
        // Preserve a unique raw reading for status text even before the first
        // proven frame advance. Multiple active streams must not be averaged.
        val candidate = if (activeIdentity != null)
            result.reverb.singleOrNull { it.identity === activeIdentity }
        else if (result.activeOutputIdentities.isEmpty())
            result.reverb.filter { it.enabled }.singleOrNull()
        else null
        val reading = candidate?.copy(active = sameGeneration && output.globalIssue == null &&
            result.activeOutputIdentities.size == 1 && candidate.active)
        val packet = reading?.snapshot?.data
        val reason = when {
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
            packet == null -> "当前驱动未提供混响诊断"
            packet[3] != 1 -> "VIPER 混响已关闭"
            packet[4] != 1 -> "原版混响处理：专业调节开启后才提供支路电平"
            reading?.active != true -> "等待专业混响处理或试听参数同步"
            else -> null
        }
        // Meter-off, silence and a real path crossfade must not hide independent A/B status.
        // A peak is only available when its original meter flag is actually on.
        val levels = packet.takeIf { reason == null && it?.get(23) == 1 }
        val advanced = reading?.let { row -> row.advanced?.data?.takeIf {
            sameGeneration && row.active && packet != null && it[2] == packet[2] &&
                it[3] == packet[3] && it[4] == packet[4]
        }
        }
        return Sample(SystemClock.elapsedRealtime(), reading?.identity, reading, output,
            peak(levels, 16), peak(levels, 18), peak(levels, 20),
            advanced?.get(13)?.div(100f), reason,
            result.outputs.singleOrNull { it.identity === reading?.identity }?.sessionId)
    }

    private fun peak(packet: List<Int>?, first: Int): Float? = packet?.let {
        val left = it[first].takeUnless { value -> value == Int.MIN_VALUE }
        val right = it[first + 1].takeUnless { value -> value == Int.MIN_VALUE }
        when {
            left != null && right != null -> maxOf(left, right) / 100f
            left != null -> left / 100f
            right != null -> right / 100f
            else -> null
        }
    }

    private fun progressed(before: Int?, after: Int?): Boolean =
        before != null && after != null && before >= 0 && after >= 0 &&
            (after > before || (before >= 0x70000000 && after <= 0x0fffffff))
}
