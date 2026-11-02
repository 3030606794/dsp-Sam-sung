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
 * Optional, read-only dashboard subscription for the six original crossfeed presets.
 *
 * Runtime words 20/21 contain output L/R peak holds in 0.01 dBFS (roughly 0.5 s decay).
 * There are no direct/cross-branch RMS meters in this protocol. Word 1 is reserved and
 * currently stays zero; word 10 is the audition epoch, not a processing-frame counter.
 * Playback proof therefore comes from independent advancing host frames. This observer
 * never publishes to the original controller cache or changes auditions, matching or DSP.
 */
object PresetCrossfeedDashboardRuntimeMonitor {
    data class Sample(
        val elapsedRealtimeMs: Long,
        val streamIdentity: Any?,
        val reading: PresetCrossfeedController.Reading?,
        val output: OutputRuntimeStatus.Snapshot,
        val unavailableReason: String? = null
    )

    private data class Targets(
        val engines: List<Pair<Int, JamesDspRemoteEngine>>,
        val mode: OutputRuntimeStatus.Mode,
        val issue: OutputRuntimeStatus.GlobalIssue?
    )

    private data class Readings(
        val outputs: List<OutputRuntimeStatus.Session>,
        val crossfeed: List<PresetCrossfeedController.Reading>,
        val baselines: IdentityHashMap<Any, OutputRuntimeStatus.Session>,
        val activeOutputCount: Int
    )

    private const val INTERVAL_MS = 250L

    /** The coordinator enables this only for the selected live page and monitor power. */
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
                    var previous = IdentityHashMap<Any, OutputRuntimeStatus.Session>()
                    val tracker = OutputRuntimeStatus.Tracker()
                    while (currentCoroutineContext().isActive && enabled.value) {
                        // The session database and page state belong to the main thread.
                        val targets = targets(appContext)
                        val poll = PresetCrossfeedController.pollState()
                        val auditionEpoch = PresetCrossfeedController.epoch
                        val auditionMode = PresetCrossfeedController.audition
                        val settings = PresetCrossfeedController.preferences(appContext).all
                        if (settings[PresetCrossfeedParameters.ENABLED] != true)
                            return@collectLatest
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
                            // A changed audition/session generation creates a gap instead of
                            // accepting a stale response or acknowledging a controller command.
                            val sameGeneration = PresetCrossfeedController.pollState() == poll &&
                                PresetCrossfeedController.epoch == auditionEpoch &&
                                PresetCrossfeedController.audition == auditionMode
                            previous = if (sameGeneration) result.baselines
                                else IdentityHashMap()
                            val reading = if (sameGeneration && targets.issue == null && result.activeOutputCount == 1)
                                result.crossfeed.singleOrNull { it.enabled && it.active }
                            else null
                            val reason: String? = when {
                                !output.connected -> "驱动未连接"
                                targets.issue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局音效链已挂起"
                                targets.issue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频经过其他输出链路"
                                !sameGeneration -> "试听状态正在同步"
                                result.activeOutputCount > 1 -> "多路音频正在处理；等待单一活动输出"
                                reading == null -> "等待本模块的新音频处理帧"
                                else -> null
                            }
                            onSample(Sample(SystemClock.elapsedRealtime(),
                                reading?.identity, reading, output, reason))
                        }
                        delay(INTERVAL_MS)
                    }
                    // Cancelling this display observer has no controller/DSP teardown action.
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
        previous: IdentityHashMap<Any, OutputRuntimeStatus.Session>,
        auditionEpoch: Int,
        auditionMode: Int
    ): Readings {
        val outputs = ArrayList<OutputRuntimeStatus.Session>(targets.engines.size)
        val readings = ArrayList<PresetCrossfeedController.Reading>(targets.engines.size)
        val baselines = IdentityHashMap<Any, OutputRuntimeStatus.Session>()
        var activeOutputCount = 0
        for ((id, engine) in targets.engines) {
            currentCoroutineContext().ensureActive()
            val output = runCatching {
                engine.outputRuntimeSession()?.copy(sessionId = id)
            }.getOrNull() ?: continue
            outputs += output
            baselines[output.identity] = output
            val before = previous[output.identity]
            val outputActive = output.enabled && before != null && before.enabled &&
                before.hostRate == output.hostRate && before.sessionId == output.sessionId &&
                output.hostRate?.let { it in 8000..768000 } == true &&
                progressed(before.processedFrames, output.processedFrames)
            if (outputActive) activeOutputCount++
            val reading = runCatching {
                engine.presetCrossfeedRuntimeReading(output)
            }.getOrNull() ?: continue
            val snapshot = reading.snapshot?.takeIf {
                it.data[2] == output.hostRate && it.enabled && it.professional &&
                    it.data[23] == 1 && it.epoch >= 0 && it.mode == auditionMode &&
                    (auditionMode == 0 || it.epoch == auditionEpoch)
            }
            // Host progression proves activity even in silence. MIN peaks remain empty, while
            // valid native A/B measurement state is still observable in the same snapshot.
            readings += reading.copy(snapshot = snapshot,
                active = outputActive && snapshot != null)
        }
        return Readings(outputs, readings, baselines, activeOutputCount)
    }

    private fun progressed(before: Int?, after: Int?): Boolean =
        before != null && after != null && before >= 0 && after >= 0 &&
            (after > before || (before >= 0x70000000 && after <= 0x0fffffff))
}
