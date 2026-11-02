package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Read-only Bauer-crossfeed display subscription, separate from persistent effects and A/B tools.
 * Diagnostics[5..6] are actual output L/R peak holds in 0.01 dBFS, with a 0.5 s decay.
 * Native does not export direct/cross branch energy, normal-processing module frame counts or
 * a meter-ready flag. Fresh host frames, module flags and the joint publication epoch are used;
 * diagnostics[20..211] remain transfer-function models, never presented as measured energy.
 */
object CrossfeedDashboardRuntimeMonitor {
    data class Sample(val elapsedRealtimeMs: Long, val streamIdentity: Any?,
        val reading: CrossfeedController.Reading?, val output: OutputRuntimeStatus.Snapshot,
        val unavailableReason: String? = null)
    private data class Targets(val engines: List<Pair<Int, JamesDspRemoteEngine>>,
        val mode: OutputRuntimeStatus.Mode, val issue: OutputRuntimeStatus.GlobalIssue?)
    private data class Baseline(val output: OutputRuntimeStatus.Session, val epoch: Int?)
    private data class Readings(val outputs: List<OutputRuntimeStatus.Session>,
        val crossfeed: List<CrossfeedController.Reading>,
        val baselines: IdentityHashMap<Any, Baseline>, val activeOutputCount: Int)
    private const val INTERVAL_MS = 250L

    /** At most two reads initialize playback proof for the original audition/measurement buttons. */
    fun refreshOnce(owner: LifecycleOwner, context: Context, onSample: (Sample) -> Unit): Job =
        owner.lifecycleScope.launch {
            delay(350L)
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            val appContext = context.applicationContext
            var selectedTargets = targets(appContext)
            val poll = CrossfeedController.pollState()
            val auditionEpoch: Int = CrossfeedController.epoch
            val auditionMode: Int = CrossfeedController.audition
            val before = cachedBaselines(selectedTargets)
            var result = withContext(Dispatchers.IO) { read(selectedTargets, before, auditionEpoch, auditionMode) }
            currentCoroutineContext().ensureActive()
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            if (result.baselines.any { (identity, value) -> !reliableBaseline(before[identity], value) }) {
                delay(100L)
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                selectedTargets = targets(appContext)
                result = withContext(Dispatchers.IO) { read(selectedTargets, result.baselines, auditionEpoch, auditionMode) }
            }
            currentCoroutineContext().ensureActive()
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            val published = CrossfeedController.publishPageRuntime(appContext, result.crossfeed, poll) ?: return@launch
            val configuration = published.singleOrNull()
            val output = OutputRuntimeStatus.Tracker().update(result.outputs)
                .copy(mode = selectedTargets.mode, globalIssue = selectedTargets.issue)
            onSample(Sample(SystemClock.elapsedRealtime(), configuration?.identity, configuration, output))
        }

    fun bind(owner: LifecycleOwner, context: Context, enabled: StateFlow<Boolean>,
        publishCache: Boolean = true, onSample: (Sample) -> Unit) {
        val appContext = context.applicationContext
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                enabled.collectLatest { monitoring ->
                    if (!monitoring) return@collectLatest
                    var previous = IdentityHashMap<Any, Baseline>()
                    val tracker = OutputRuntimeStatus.Tracker()
                    while (currentCoroutineContext().isActive && enabled.value) {
                        val targets = targets(appContext)
                        val poll = CrossfeedController.pollState()
                        val auditionEpoch: Int = CrossfeedController.epoch
                        val auditionMode: Int = CrossfeedController.audition
                        val result = withContext(Dispatchers.IO) {
                            if (!enabled.value || !CrossfeedController.preferences(appContext)
                                    .getBoolean("v_cure", false)) return@withContext null
                            read(targets, previous, auditionEpoch, auditionMode)
                        }
                        currentCoroutineContext().ensureActive()
                        if (!enabled.value || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                            return@collectLatest
                        if (result != null) {
                            val sameGeneration: Boolean = CrossfeedController.pollState() == poll &&
                                CrossfeedController.epoch == auditionEpoch && CrossfeedController.audition == auditionMode
                            previous = if (sameGeneration) result.baselines else IdentityHashMap()
                            val output = tracker.update(result.outputs)
                                .copy(mode = targets.mode, globalIssue = targets.issue)
                            val published: List<CrossfeedController.Reading>? = if (publishCache)
                                CrossfeedController.publishPageRuntime(appContext, result.crossfeed, poll)
                            else if (sameGeneration) result.crossfeed else emptyList()
                            if (published != null) {
                                val reading: CrossfeedController.Reading? = if (sameGeneration && targets.issue == null && result.activeOutputCount == 1) published.filter {
                                    it.enabled && it.active && it.snapshot?.let { snapshot ->
                                        snapshot.enabled && snapshot.professional && toolsMatch(snapshot, auditionEpoch, auditionMode)
                                    } == true
                                }.singleOrNull() else null
                                val reason: String? = when {
                                    !output.connected -> "驱动未连接"
                                    targets.issue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局音效链已挂起"
                                    targets.issue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频经过其他输出链路"
                                    !sameGeneration -> "试听状态正在同步"
                                    result.activeOutputCount > 1 -> "多路音频正在处理；等待单一活动输出"
                                    reading == null -> "等待本模块的新音频处理帧"
                                    else -> null
                                }
                                onSample(Sample(SystemClock.elapsedRealtime(), reading?.identity, reading, output, reason))
                            }
                        }
                        delay(INTERVAL_MS)
                    }
                    // No display teardown may stop audition, clear comparisons or complete measurements.
                }
            }
        }
    }

    private fun cachedBaselines(targets: Targets) = IdentityHashMap<Any, Baseline>().apply {
        val cached = CrossfeedController.runtime
        targets.engines.forEach { (id, engine) ->
            val identity = engine.effect ?: return@forEach
            val reading = cached.firstOrNull { it.identity === identity } ?: return@forEach
            this[identity] = Baseline(OutputRuntimeStatus.Session(identity, reading.enabled, reading.rate,
                reading.frames, id), reading.snapshot?.data?.get(1))
        }
    }

    private fun targets(context: Context): Targets {
        val application = context as? MainApplication
        val mode = if (application?.isPlayerSessionMode == true) OutputRuntimeStatus.Mode.PLAYER_SESSION
            else OutputRuntimeStatus.Mode.GLOBAL
        val issue = if (mode == OutputRuntimeStatus.Mode.GLOBAL) when (
            application?.rootSessionDatabase?.globalPlaybackIssue) {
            GlobalPlaybackEvidence.Reason.SUSPENDED -> OutputRuntimeStatus.GlobalIssue.SUSPENDED
            GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT
            null -> null
        } else null
        val engines = application?.rootSessionDatabase?.sessionList?.toList()?.mapNotNull { (id, session) ->
            (session as? RemoteEffectSession)?.effect?.let { id to it }
        }.orEmpty()
        return Targets(engines, mode, issue)
    }

    private suspend fun read(targets: Targets, previous: IdentityHashMap<Any, Baseline>,
        auditionEpoch: Int, auditionMode: Int): Readings {
        val outputs = ArrayList<OutputRuntimeStatus.Session>(targets.engines.size)
        val readings = ArrayList<CrossfeedController.Reading>(targets.engines.size)
        val baselines = IdentityHashMap<Any, Baseline>()
        var activeOutputCount = 0
        for ((id, engine) in targets.engines) {
            currentCoroutineContext().ensureActive()
            val output = runCatching { engine.outputRuntimeSession()?.copy(sessionId = id) }.getOrNull() ?: continue
            outputs += output
            val before = previous[output.identity]
            val oldOutput = before?.output
            val outputActive = output.enabled && oldOutput != null && oldOutput.enabled &&
                oldOutput.hostRate == output.hostRate && oldOutput.sessionId == output.sessionId &&
                output.hostRate?.let { it in 8000..768000 } == true &&
                progressed(oldOutput.processedFrames, output.processedFrames)
            if (outputActive) activeOutputCount++
            val reading = runCatching { engine.crossfeedRuntimeReading(output) }.getOrNull()
            val snapshot = reading?.snapshot?.takeIf {
                it.data[2] == output.hostRate && it.data[1] >= 0 && toolsMatch(it, auditionEpoch, auditionMode)
            }
            val epoch = snapshot?.data?.get(1)
            baselines[output.identity] = Baseline(output, epoch)
            if (reading == null) continue
            val diagnostic = reading.diagnostics?.takeIf {
                snapshot != null && it.data[1] == epoch && it.rate == snapshot.data[2] &&
                    it.data[3] == snapshot.data[4]
            }
            val active = outputActive && snapshot?.enabled == true && snapshot.professional &&
                epoch != before?.epoch
            readings += reading.copy(snapshot = snapshot, diagnostics = diagnostic, active = active)
        }
        return Readings(outputs, readings, baselines, activeOutputCount)
    }

    private fun progressed(before: Int?, after: Int?): Boolean = before != null && after != null &&
        before >= 0 && after >= 0 && (after > before || (before >= 0x70000000 && after <= 0x0fffffff))

    // Native parameter changes may clear the listening tools to mode/epoch 0 while
    // ordinary audio keeps processing. Its tools epoch is not an audio generation.
    private fun toolsMatch(snapshot: CrossfeedParameters.Runtime, epoch: Int, mode: Int): Boolean =
        snapshot.mode == mode && (mode == 0 || snapshot.epoch == epoch)

    private fun reliableBaseline(before: Baseline?, current: Baseline): Boolean {
        if (before == null || !before.output.enabled || !current.output.enabled || before.epoch == null ||
            current.epoch == null || before.output.hostRate != current.output.hostRate ||
            before.output.sessionId != current.output.sessionId) return false
        val old = before.output.processedFrames ?: return false
        val now = current.output.processedFrames ?: return false
        return old >= 0 && (old == now || progressed(old, now))
    }
}
