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

/** GET-only instruments. Neither the native meter preference nor an audition is changed here. */
internal object WidthFocusRuntimeMonitor {
    data class Sample(val elapsedRealtimeMs: Long, val streamIdentity: Any?,
        val reading: WidthController.Reading?, val output: OutputRuntimeStatus.Snapshot,
        val unavailableReason: String? = null)

    private data class Baseline(val output: OutputRuntimeStatus.Session, val epoch: Int?, val frames: Int?)
    private data class Observations(val outputs: List<OutputRuntimeStatus.Session>,
        val readings: List<WidthController.Reading>, val advancing: List<OutputRuntimeStatus.Session>,
        val baselines: IdentityHashMap<Any, Baseline>)

    private fun engines(app: MainApplication?) = app?.rootSessionDatabase?.sessionList?.toList()?.mapNotNull { (id, session) ->
        (session as? RemoteEffectSession)?.effect?.let { id to it }
    }.orEmpty()

    suspend fun refreshConfigurationOnce(context: Context, isCurrent: () -> Boolean): WidthController.Reading? {
        delay(350L)
        if (!isCurrent()) return null
        val app = context.applicationContext as? MainApplication ?: return null
        val targets = engines(app)
        val poll = WidthController.pollState()
        val observed = withContext(Dispatchers.IO) { read(targets, IdentityHashMap()) }
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return null
        val published = WidthController.publishPageRuntime(app, observed.readings, poll) ?: return null
        return if (WidthController.pollState() == poll) published.singleOrNull() else null
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
                        val mode = if (app?.isPlayerSessionMode == true) OutputRuntimeStatus.Mode.PLAYER_SESSION
                            else OutputRuntimeStatus.Mode.GLOBAL
                        val issue = if (mode == OutputRuntimeStatus.Mode.GLOBAL) when (app?.rootSessionDatabase?.globalPlaybackIssue) {
                            GlobalPlaybackEvidence.Reason.SUSPENDED -> OutputRuntimeStatus.GlobalIssue.SUSPENDED
                            GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT
                            null -> null
                        } else null
                        val targets = engines(app)
                        val sample = withContext(Dispatchers.IO) {
                            currentCoroutineContext().ensureActive()
                            if (!enabled.value || !WidthController.preferences(appContext)
                                    .getBoolean(WidthParameters.ENABLED, false)) return@withContext null
                            val poll = WidthController.pollState()
                            val observed = read(targets, previous)
                            previous = observed.baselines
                            currentCoroutineContext().ensureActive()
                            if (!enabled.value) return@withContext null
                            // Preserve native lower-page status without publishRuntime's audition acknowledgement.
                            val published = WidthController.publishPageRuntime(appContext, observed.readings, poll)
                                ?: return@withContext null
                            if (!enabled.value || WidthController.pollState() != poll) return@withContext null
                            val output = tracker.update(observed.outputs).copy(mode = mode, globalIssue = issue)
                            // Count all advancing chains, including chains without Width capability.
                            val selected = observed.advancing.singleOrNull()
                            val reading = if (issue == null) selected?.let { chain -> published.singleOrNull {
                                it.identity === chain.identity && it.enabled && it.active && it.snapshot?.ready == true
                            } } else null
                            val reason = when {
                                !output.connected -> "驱动未连接"
                                issue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局音效链已挂起"
                                issue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频经过其他输出链路"
                                observed.advancing.size > 1 -> "多路音频正在处理；等待单一活动声场"
                                selected == null -> if (output.processing == OutputRuntimeStatus.Processing.IDLE)
                                    "当前没有新的音频处理帧" else "等待活动音频处理窗口"
                                reading == null -> "等待立体声宽度仪表的新统计窗口"
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

    private suspend fun read(targets: List<Pair<Int, JamesDspRemoteEngine>>,
        previous: IdentityHashMap<Any, Baseline>): Observations {
        val outputs = ArrayList<OutputRuntimeStatus.Session>()
        val advancing = ArrayList<OutputRuntimeStatus.Session>()
        val readings = ArrayList<WidthController.Reading>()
        val baselines = IdentityHashMap<Any, Baseline>()
        for ((id, engine) in targets) {
            currentCoroutineContext().ensureActive()
            val output = runCatching { engine.outputRuntimeSession()?.copy(sessionId = id) }.getOrNull() ?: continue
            outputs += output
            val before = previous[output.identity]
            val outputActive = output.enabled && before?.output?.enabled == true &&
                output.hostRate?.let { it in 8000..768000 } == true && before.output.hostRate == output.hostRate &&
                before.output.sessionId == output.sessionId && progressed(before.output.processedFrames, output.processedFrames)
            if (outputActive) advancing += output
            val reading = runCatching { engine.widthRuntimeReading(output) }.getOrNull()
            val basic = reading?.snapshot?.takeIf { it.rate == output.hostRate }
            val epoch = basic?.data?.getOrNull(1)
            val frames = basic?.data?.getOrNull(26)
            baselines[output.identity] = Baseline(output, epoch, frames)
            if (reading == null) continue
            // Two GETs belong to one publication only if epoch, rate, mode and processed count agree.
            val advanced = reading.advanced?.takeIf { extra ->
                val d = extra.data
                basic != null && d[1] == epoch && d[2] == basic.rate && d[3] == basic.data[3] &&
                    d[4] == basic.mode && d[6] == frames
            }
            val active = outputActive && basic?.enabled == true && basic.ready &&
                before?.epoch != epoch && progressed(before?.frames, frames)
            readings += reading.copy(snapshot = basic, active = active, advanced = advanced)
        }
        return Observations(outputs, readings, advancing, baselines)
    }

    private fun progressed(before: Int?, after: Int?): Boolean = before != null && after != null &&
        before >= 0 && after >= 0 && (after > before || before >= 0x70000000 && after <= 0x0fffffff)
}
