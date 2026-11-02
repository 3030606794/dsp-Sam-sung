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
 * Optional visible dashboard reads, independent from global polling and listening tools.
 * Native RMS/peak data is stereo aggregate only: no independent L/R levels are exported.
 * snapshot[7..10] are input/output RMS and peak in 0.1 dBFS; [11..12] are correlations /10000;
 * [13] is RMS compensation and [14] the applied peak-protection gain, both in 0.1 dB.
 */
object SurroundDashboardRuntimeMonitor {
    data class Sample(val elapsedRealtimeMs: Long, val streamIdentity: Any?,
        val reading: SurroundProfessionalRuntime.Reading?, val output: OutputRuntimeStatus.Snapshot)

    private data class Targets(val engines: List<Pair<Int, JamesDspRemoteEngine>>,
        val mode: OutputRuntimeStatus.Mode, val issue: OutputRuntimeStatus.GlobalIssue?)
    private data class Baseline(val output: OutputRuntimeStatus.Session,
        val moduleFrames: Int?, val epoch: Int?, val settingsRevision: Int?)
    private data class Readings(val outputs: List<OutputRuntimeStatus.Session>,
        val surround: List<SurroundProfessionalRuntime.Reading>,
        val baselines: IdentityHashMap<Any, Baseline>, val activeOutputCount: Int)
    private const val INTERVAL_MS = 250L

    /** A single configuration refresh. Its callback never represents a live history measurement. */
    fun refreshOnce(owner: LifecycleOwner, context: Context, onSample: (Sample) -> Unit): Job =
        owner.lifecycleScope.launch {
            delay(350L)
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            val appContext = context.applicationContext
            var selectedTargets = targets(appContext)
            val poll = SurroundProfessionalRuntime.pollState()
            val baseline = cachedBaselines(selectedTargets)
            var result = withContext(Dispatchers.IO) { read(selectedTargets, baseline) }
            currentCoroutineContext().ensureActive()
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            // Audition/calibration requires proven playback even with the dashboard switched off.
            // With no trustworthy old cache, one bounded second read supplies that proof.
            if (result.baselines.any { (identity, current) -> !reliableBaseline(baseline[identity], current) }) {
                delay(100L)
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                selectedTargets = targets(appContext)
                result = withContext(Dispatchers.IO) { read(selectedTargets, result.baselines) }
            }
            currentCoroutineContext().ensureActive()
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            val published = SurroundProfessionalRuntime.publishPageRuntime(appContext, result.surround, poll)
                ?: return@launch
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
                    val tracker = OutputRuntimeStatus.Tracker()
                    var previous = IdentityHashMap<Any, Baseline>()
                    while (currentCoroutineContext().isActive && enabled.value) {
                        val targets = targets(appContext)
                        val poll = SurroundProfessionalRuntime.pollState()
                        val result = withContext(Dispatchers.IO) {
                            if (!enabled.value || !SurroundController.enabled(appContext)) return@withContext null
                            read(targets, previous)
                        }
                        currentCoroutineContext().ensureActive()
                        if (!enabled.value || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                            return@collectLatest
                        if (result != null) {
                            previous = result.baselines
                            val output = tracker.update(result.outputs)
                                .copy(mode = targets.mode, globalIssue = targets.issue)
                            val published = if (publishCache)
                                SurroundProfessionalRuntime.publishPageRuntime(appContext, result.surround, poll)
                            else if (SurroundProfessionalRuntime.pollState() == poll) result.surround else null
                            if (published != null) {
                                val reading = if (result.activeOutputCount == 1) published.filter {
                                    it.enabled && it.active && it.snapshot?.data?.let { data ->
                                        data[3] == 1 && data[5] == 1 && data[29] == 1
                                    } == true
                                }.singleOrNull() else null
                                onSample(Sample(SystemClock.elapsedRealtime(), reading?.identity, reading, output))
                            }
                        }
                        delay(INTERVAL_MS)
                    }
                    // Leaving or disabling this display does not clear runtime or stop listening tools.
                }
            }
        }
    }

    private fun cachedBaselines(targets: Targets): IdentityHashMap<Any, Baseline> =
        IdentityHashMap<Any, Baseline>().apply {
            val cached = SurroundProfessionalRuntime.readings
            targets.engines.forEach { (id, engine) ->
                val identity = engine.effect ?: return@forEach
                val reading = cached.firstOrNull { it.identity === identity } ?: return@forEach
                val data = reading.snapshot?.data
                this[identity] = Baseline(OutputRuntimeStatus.Session(identity, reading.enabled,
                    reading.hostRate, reading.frames, id), data?.get(6), data?.get(1), data?.get(30))
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

    private suspend fun read(targets: Targets, previous: IdentityHashMap<Any, Baseline>): Readings {
        val outputs = ArrayList<OutputRuntimeStatus.Session>(targets.engines.size)
        val readings = ArrayList<SurroundProfessionalRuntime.Reading>(targets.engines.size)
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
            val reading = runCatching { engine.surroundRuntimeReading(output) }.getOrNull()
            val snapshot = reading?.snapshot?.takeIf { it.data[2] == output.hostRate }
            val data = snapshot?.data
            val frames = data?.get(6)
            val epoch = data?.get(1)
            val revision = data?.get(30)
            baselines[output.identity] = Baseline(output, frames, epoch, revision)
            if (reading == null) continue
            val active = outputActive && data?.get(3) == 1 && epoch != before?.epoch &&
                revision == before?.settingsRevision && progressed(before?.moduleFrames, frames)
            // Listening tools have their own atomic publication epoch, not the professional epoch.
            val listening = reading.listening?.takeIf { values ->
                data != null && values[2] == data[2] && values[3] == data[3] && values[4] == data[24]
            }
            readings += reading.copy(snapshot = snapshot, listening = listening, active = active)
        }
        return Readings(outputs, readings, baselines, activeOutputCount)
    }

    private fun progressed(before: Int?, after: Int?): Boolean = before != null && after != null &&
        before >= 0 && after >= 0 && (after > before || (before >= 0x70000000 && after <= 0x0fffffff))

    private fun reliableBaseline(before: Baseline?, current: Baseline): Boolean {
        if (before == null || !before.output.enabled || !current.output.enabled ||
            before.output.hostRate != current.output.hostRate || before.output.sessionId != current.output.sessionId ||
            before.settingsRevision != current.settingsRevision || before.epoch == null || current.epoch == null)
            return false
        val oldHost = before.output.processedFrames ?: return false
        val newHost = current.output.processedFrames ?: return false
        val oldModule = before.moduleFrames ?: return false
        val newModule = current.moduleFrames ?: return false
        return oldHost >= 0 && oldModule >= 0 && (oldHost == newHost || progressed(oldHost, newHost)) &&
            (oldModule == newModule || progressed(oldModule, newModule))
    }
}
