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

/** Visible opt-in dashboard reads. It never writes meter/effect preferences or controls an audition. */
object WidthDashboardRuntimeMonitor {
    data class Sample(val elapsedRealtimeMs: Long, val streamIdentity: Any?,
        val reading: WidthController.Reading?, val output: OutputRuntimeStatus.Snapshot)

    private data class Targets(val engines: List<Pair<Int, JamesDspRemoteEngine>>,
        val mode: OutputRuntimeStatus.Mode, val issue: OutputRuntimeStatus.GlobalIssue?)
    private data class Baseline(val output: OutputRuntimeStatus.Session,
        val widthFrames: Int?, val epoch: Int?)
    private data class Readings(val outputs: List<OutputRuntimeStatus.Session>,
        val widths: List<WidthController.Reading>, val baselines: IdentityHashMap<Any, Baseline>,
        val activeOutputCount: Int)
    private const val INTERVAL_MS = 250L

    /** One configuration read on resume, with no live baseline, timer or history sample. */
    fun refreshOnce(owner: LifecycleOwner, context: Context, onSample: (Sample) -> Unit): Job =
        owner.lifecycleScope.launch {
            // The previous home page may still be finishing its telemetry hand-off.
            delay(350L)
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            val appContext = context.applicationContext
            val targets = targets(appContext)
                        val values = withContext(Dispatchers.IO) { read(targets, IdentityHashMap()) }
            currentCoroutineContext().ensureActive()
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            val published = values.widths
            val configuration = published.singleOrNull()
            val output = OutputRuntimeStatus.Tracker().update(values.outputs)
                .copy(mode = targets.mode, globalIssue = targets.issue)
            onSample(Sample(SystemClock.elapsedRealtime(), configuration?.identity, configuration, output))
        }

    /** RESUMED plus the coordinator's live-view/power/module gate is the only polling owner. */
    fun bind(owner: LifecycleOwner, context: Context, enabled: StateFlow<Boolean>, onSample: (Sample) -> Unit) {
        val appContext = context.applicationContext
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                enabled.collectLatest { monitoring ->
                    if (!monitoring) return@collectLatest
                    val tracker = OutputRuntimeStatus.Tracker()
                    var previous = IdentityHashMap<Any, Baseline>()
                    while (currentCoroutineContext().isActive && enabled.value) {
                        val targets = targets(appContext)
                                                val result = withContext(Dispatchers.IO) {
                            if (!enabled.value || !WidthController.preferences(appContext)
                                    .getBoolean(WidthParameters.ENABLED, false)) return@withContext null
                            read(targets, previous)
                        }
                        currentCoroutineContext().ensureActive()
                        if (!enabled.value || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                            return@collectLatest
                        if (result != null) {
                            previous = result.baselines
                            val output = tracker.update(result.outputs)
                                .copy(mode = targets.mode, globalIssue = targets.issue)
                            val published = result.widths
                            run {
                                // Even one valid width reading cannot stand in for two active playback chains.
                                val reading = if (result.activeOutputCount == 1)
                                    published.filter { it.enabled && it.active && it.snapshot?.ready == true &&
                                        it.snapshot.enabled }.singleOrNull() else null
                                onSample(Sample(SystemClock.elapsedRealtime(), reading?.identity, reading, output))
                            }
                        }
                        delay(INTERVAL_MS)
                    }
                    // No cache clearing or audition cancellation belongs to this subscription.
                }
            }
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
        val widths = ArrayList<WidthController.Reading>(targets.engines.size)
        val baselines = IdentityHashMap<Any, Baseline>()
        var activeOutputCount = 0
        for ((id, engine) in targets.engines) {
            currentCoroutineContext().ensureActive()
            val output = runCatching { engine.outputRuntimeSession()?.copy(sessionId = id) }.getOrNull() ?: continue
            outputs += output
            val before = previous[output.identity]
            val outputActive = output.enabled && before?.output?.enabled == true &&
                before.output.hostRate == output.hostRate && before.output.sessionId == output.sessionId &&
                output.hostRate?.let { it in 8000..768000 } == true &&
                advanced(before.output.processedFrames, output.processedFrames)
            if (outputActive) activeOutputCount++
            val reading = runCatching { engine.widthRuntimeReading(output) }.getOrNull()
            val basic = reading?.snapshot?.takeIf { it.rate == output.hostRate }
            val frameCount = basic?.data?.get(26)
            val epoch = basic?.data?.get(1)
            baselines[output.identity] = Baseline(output, frameCount, epoch)
            if (reading == null) continue
            // These are two separate GETs: only combine their values when the publication matches.
            val extra = reading.advanced?.takeIf { snapshot ->
                val data = snapshot.data
                basic != null && data[1] == epoch && data[2] == basic.rate &&
                    data[4] == basic.mode && data[6] == frameCount
            }
            val active = outputActive && basic?.enabled == true && basic.ready &&
                epoch != before?.epoch && advanced(before?.widthFrames, frameCount)
            widths += reading.copy(snapshot = basic, active = active,
                advanced = if (active && extra?.data?.get(5) != 1) null else extra)
        }
        return Readings(outputs, widths, baselines, activeOutputCount)
    }

    private fun advanced(before: Int?, after: Int?): Boolean = before != null && after != null &&
        before >= 0 && after >= 0 && (after > before || (before >= 0x70000000 && after <= 0x0fffffff))
}
