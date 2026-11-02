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

/** Independent GET-only charts. The original Activity remains the sole listening-state publisher. */
internal object SurroundFocusRuntimeMonitor {
    data class Sample(val elapsedRealtimeMs: Long, val streamIdentity: Any?,
        val reading: SurroundProfessionalRuntime.Reading?, val output: OutputRuntimeStatus.Snapshot,
        val unavailableReason: String? = null)
    private data class Baseline(val output: OutputRuntimeStatus.Session, val frames: Int?, val epoch: Int?, val revision: Int?)
    private data class Observations(val outputs: List<OutputRuntimeStatus.Session>,
        val advancing: List<OutputRuntimeStatus.Session>, val readings: List<SurroundProfessionalRuntime.Reading>,
        val baselines: IdentityHashMap<Any, Baseline>)

    private fun engines(app: MainApplication?) = app?.rootSessionDatabase?.sessionList?.toList()?.mapNotNull { (id, session) ->
        (session as? RemoteEffectSession)?.effect?.let { id to it }
    }.orEmpty()

    suspend fun refreshConfigurationOnce(context: Context, isCurrent: () -> Boolean): SurroundProfessionalRuntime.Reading? {
        delay(350L)
        if (!isCurrent()) return null
        val app = context.applicationContext as? MainApplication ?: return null
        val targets = engines(app)
        val poll = SurroundProfessionalRuntime.pollState()
        val observed = withContext(Dispatchers.IO) { read(targets, IdentityHashMap()) }
        currentCoroutineContext().ensureActive()
        return if (isCurrent() && SurroundProfessionalRuntime.pollState() == poll) observed.readings.singleOrNull() else null
    }

    fun bind(owner: LifecycleOwner, context: Context, enabled: StateFlow<Boolean>, onSample: (Sample) -> Unit) {
        val appContext = context.applicationContext
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                enabled.collectLatest { running ->
                    if (!running) return@collectLatest
                    val tracker = OutputRuntimeStatus.Tracker()
                    var previous = IdentityHashMap<Any, Baseline>()
                    while (currentCoroutineContext().isActive && enabled.value) {
                        val app = appContext as? MainApplication
                        val mode = if (app?.isPlayerSessionMode == true) OutputRuntimeStatus.Mode.PLAYER_SESSION else OutputRuntimeStatus.Mode.GLOBAL
                        val issue = if (mode == OutputRuntimeStatus.Mode.GLOBAL) when (app?.rootSessionDatabase?.globalPlaybackIssue) {
                            GlobalPlaybackEvidence.Reason.SUSPENDED -> OutputRuntimeStatus.GlobalIssue.SUSPENDED
                            GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT
                            null -> null
                        } else null
                        val targets = engines(app)
                        val sample = withContext(Dispatchers.IO) {
                            currentCoroutineContext().ensureActive()
                            if (!enabled.value || !SurroundController.enabled(appContext)) return@withContext null
                            val poll = SurroundProfessionalRuntime.pollState()
                            val observed = read(targets, previous)
                            previous = observed.baselines
                            currentCoroutineContext().ensureActive()
                            if (!enabled.value || SurroundProfessionalRuntime.pollState() != poll) return@withContext null
                            val output = tracker.update(observed.outputs).copy(mode = mode, globalIssue = issue)
                            val selected = observed.advancing.singleOrNull()
                            val reading = if (issue == null) selected?.let { chain -> observed.readings.singleOrNull {
                                it.identity === chain.identity && it.enabled && it.active && it.snapshot?.data?.get(3) == 1
                            } } else null
                            // Meter-off does not invalidate native protection gains or the independent listening instruments.
                            val reason = when {
                                !output.connected -> "驱动未连接"
                                issue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局音效链已挂起"
                                issue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频经过其他输出链路"
                                observed.advancing.size > 1 -> "多路音频正在处理；等待单一活动环绕声场"
                                selected == null -> if (output.processing == OutputRuntimeStatus.Processing.IDLE)
                                    "当前没有新的音频处理帧" else "等待活动音频处理窗口"
                                reading == null -> "等待环绕处理仪表的新统计窗口"
                                else -> null
                            }
                            Sample(SystemClock.elapsedRealtime(), reading?.identity, reading, output, reason)
                        }
                        currentCoroutineContext().ensureActive()
                        if (sample != null && enabled.value && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onSample(sample)
                        delay(150L)
                    }
                }
            }
        }
    }

    private suspend fun read(targets: List<Pair<Int, JamesDspRemoteEngine>>, previous: IdentityHashMap<Any, Baseline>): Observations {
        val outputs = ArrayList<OutputRuntimeStatus.Session>()
        val advancing = ArrayList<OutputRuntimeStatus.Session>()
        val readings = ArrayList<SurroundProfessionalRuntime.Reading>()
        val baselines = IdentityHashMap<Any, Baseline>()
        for ((id, engine) in targets) {
            currentCoroutineContext().ensureActive()
            val output = runCatching { engine.outputRuntimeSession()?.copy(sessionId = id) }.getOrNull() ?: continue
            outputs += output
            val before = previous[output.identity]
            val oldOutput = before?.output
            val activeOutput = output.enabled && oldOutput?.enabled == true && oldOutput.hostRate == output.hostRate &&
                oldOutput.sessionId == output.sessionId && output.hostRate?.let { it in 8000..768000 } == true &&
                progressed(oldOutput.processedFrames, output.processedFrames)
            if (activeOutput) advancing += output
            val reading = runCatching { engine.surroundRuntimeReading(output) }.getOrNull()
            val snapshot = reading?.snapshot?.takeIf { it.data[2] == output.hostRate }
            val data = snapshot?.data
            val frames = data?.get(6); val epoch = data?.get(1); val revision = data?.get(30)
            baselines[output.identity] = Baseline(output, frames, epoch, revision)
            if (reading == null) continue
            // The tools publish their own atomic epoch. These GETs need common stream/rate/path,
            // not an invented equality between the two independent publication counters.
            val listening = reading.listening?.takeIf {
                data != null && it[2] == data[2] && it[3] == data[3] && it[4] == data[24]
            }
            val active = activeOutput && data?.get(3) == 1 && before?.epoch != epoch &&
                before?.revision == revision && progressed(before?.frames, frames)
            readings += reading.copy(snapshot = snapshot, active = active, listening = listening)
        }
        return Observations(outputs, advancing, readings, baselines)
    }

    private fun progressed(before: Int?, after: Int?): Boolean = before != null && after != null &&
        before >= 0 && after >= 0 && (after > before || before >= 0x70000000 && after <= 0x0fffffff)
}
