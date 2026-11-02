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
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence

/** GET-only native instruments. This owns no PCM lease and never acknowledges an audition. */
internal object DiffPageRuntimeMonitor {
    data class Sample(
        val elapsedRealtimeMs: Long,
        val streamIdentity: Any?,
        val reading: DiffController.Reading?,
        val output: OutputRuntimeStatus.Snapshot,
        val unavailableReason: String? = null
    )

    /** One configuration/cache refresh while live monitoring is asleep; no recurring task is started. */
    suspend fun refreshConfigurationOnce(context: Context, isCurrent: () -> Boolean): DiffController.Reading? {
        delay(350L)
        if (!isCurrent()) return null
        val app = context.applicationContext as? MainApplication ?: return null
        val engines = app.rootSessionDatabase?.sessionList?.toList()?.mapNotNull { (id, session) ->
            (session as? RemoteEffectSession)?.effect?.let { id to it }
        }.orEmpty()
        val poll = DiffController.pollState()
        val readings = withContext(Dispatchers.IO) {
            engines.mapNotNull { (id, engine) ->
                currentCoroutineContext().ensureActive()
                runCatching {
                    engine.outputRuntimeSession()?.copy(sessionId = id)?.let(engine::diffRuntimeReading)
                }.getOrNull()
            }
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent() || !DiffController.publishPageRuntime(app, readings, poll)) return null
        return if (DiffController.pollState() == poll) DiffController.runtime.singleOrNull() else null
    }

    fun bind(owner: LifecycleOwner, context: Context, enabled: StateFlow<Boolean>, publishCache: Boolean = true,
        onSample: (Sample) -> Unit) {
        val appContext = context.applicationContext
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                enabled.collectLatest { running ->
                    if (!running) return@collectLatest
                    val tracker = OutputRuntimeStatus.Tracker()
                    var previous = emptyMap<Any, OutputRuntimeStatus.Session>()
                    while (currentCoroutineContext().isActive && enabled.value) {
                        val app = appContext as? MainApplication
                        val mode = if (app?.isPlayerSessionMode == true) OutputRuntimeStatus.Mode.PLAYER_SESSION
                            else OutputRuntimeStatus.Mode.GLOBAL
                        val issue = if (mode == OutputRuntimeStatus.Mode.GLOBAL) when (app?.rootSessionDatabase?.globalPlaybackIssue) {
                            GlobalPlaybackEvidence.Reason.SUSPENDED -> OutputRuntimeStatus.GlobalIssue.SUSPENDED
                            GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT
                            null -> null
                        } else null
                        val engines = app?.rootSessionDatabase?.sessionList?.toList()?.mapNotNull { (id, session) ->
                            (session as? RemoteEffectSession)?.effect?.let { id to it }
                        }.orEmpty()
                        val sample = withContext(Dispatchers.IO) {
                            currentCoroutineContext().ensureActive()
                            if (!enabled.value || !DiffController.preferences(appContext).getBoolean("v_diff", false)) return@withContext null
                            val poll = DiffController.pollState()
                            val outputs = ArrayList<OutputRuntimeStatus.Session>()
                            val advancing = ArrayList<OutputRuntimeStatus.Session>()
                            val readings = ArrayList<DiffController.Reading>()
                            for ((id, engine) in engines) {
                                currentCoroutineContext().ensureActive()
                                if (!enabled.value) return@withContext null
                                val output = runCatching { engine.outputRuntimeSession()?.copy(sessionId = id) }.getOrNull() ?: continue
                                outputs += output
                                val before = previous[output.identity]
                                val frames = output.processedFrames?.takeIf { it >= 0 }
                                val old = before?.processedFrames?.takeIf { it >= 0 }
                                val processing = when {
                                    !output.enabled -> OutputRuntimeStatus.Processing.IDLE
                                    before == null || !before.enabled || frames == null || old == null ||
                                        before.hostRate != output.hostRate || before.sessionId != output.sessionId -> OutputRuntimeStatus.Processing.UNKNOWN
                                    frames > old || (old >= 0x70000000 && frames <= 0x0fffffff) -> OutputRuntimeStatus.Processing.ACTIVE
                                    frames < old -> OutputRuntimeStatus.Processing.UNKNOWN
                                    else -> OutputRuntimeStatus.Processing.IDLE
                                }
                                if (processing == OutputRuntimeStatus.Processing.ACTIVE) advancing += output
                                val reading = runCatching { engine.diffRuntimeReading(output) }.getOrNull() ?: continue
                                readings += reading.copy(processing = processing)
                            }
                            previous = outputs.associateBy { it.identity }
                            currentCoroutineContext().ensureActive()
                            if (!enabled.value || DiffController.pollState() != poll) return@withContext null
                            if (publishCache && !DiffController.publishPageRuntime(appContext, readings, poll)) return@withContext null
                            if (!enabled.value || DiffController.pollState() != poll) return@withContext null
                            // The controller may gate a just-disabled session in its display-only publication.
                            val effective = if (publishCache) DiffController.runtime else readings
                            val output = tracker.update(outputs).copy(mode = mode, globalIssue = issue)
                            val selected = advancing.singleOrNull()
                            val reading = if (issue == null) selected?.let { active -> effective.singleOrNull {
                                it.identity === active.identity && it.enabled && it.processing == OutputRuntimeStatus.Processing.ACTIVE
                            } } else null
                            val reason = when {
                                !output.connected -> "驱动未连接"
                                issue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局音效链已挂起"
                                issue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频经过其他输出链路"
                                advancing.size > 1 -> "多路音频正在处理；不合并不同延时声场"
                                selected == null -> if (output.processing == OutputRuntimeStatus.Processing.IDLE)
                                    "当前没有新的音频处理帧" else "等待活动音频处理窗口"
                                reading == null -> "当前活动链路的差分延时仪表尚未就绪"
                                reading.snapshot == null -> "等待完整差分延时快照通过校验"
                                !reading.snapshot.ready -> "正在收集完整 100 ms 统计窗口"
                                else -> null
                            }
                            Sample(SystemClock.elapsedRealtime(), reading?.identity, reading, output, reason)
                        }
                        currentCoroutineContext().ensureActive()
                        if (enabled.value && sample != null && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onSample(sample)
                        // Native meters summarize 100 ms. These are instrument polls, not raw audio frames.
                        delay(125L)
                    }
                }
            }
        }
    }
}
