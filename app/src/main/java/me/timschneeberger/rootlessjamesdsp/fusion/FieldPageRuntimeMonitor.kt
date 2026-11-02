package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

/** Opt-in visible-page reads. Native capture leases never change any audio parameter. */
internal object FieldPageRuntimeMonitor {
    data class Sample(val elapsedRealtimeMs: Long, val streamIdentity: Any?,
        val reading: FieldController.Reading?, val output: OutputRuntimeStatus.Snapshot,
        val unavailableReason: String? = null, val visualizationReason: String? = null)

    /** One configuration read supplies the existing editors without arming visualization. */
    suspend fun refreshConfigurationOnce(context: Context, publishCache: Boolean = true, isCurrent: () -> Boolean) {
        delay(350L)
        if (!isCurrent()) return
        val appContext = context.applicationContext
        val application = appContext as? MainApplication
        val engines = application?.rootSessionDatabase?.sessionList?.toList()?.mapNotNull { (id, session) ->
            (session as? RemoteEffectSession)?.effect?.let { id to it }
        }.orEmpty()
        val poll = FieldController.pollState()
        val readings = withContext(Dispatchers.IO) {
            engines.mapNotNull { (id, engine) ->
                currentCoroutineContext().ensureActive()
                runCatching {
                    engine.outputRuntimeSession()?.copy(sessionId = id)?.let { engine.fieldRuntimeReading(it) }
                }.getOrNull()
            }
        }
        currentCoroutineContext().ensureActive()
        if (publishCache && isCurrent()) FieldController.publishPageRuntime(appContext, readings, poll)
    }

    fun bind(owner: LifecycleOwner, context: Context, enabled: StateFlow<Boolean>, publishCache: Boolean = true,
        onSample: (Sample) -> Unit) {
        val appContext = context.applicationContext
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                enabled.collectLatest { active ->
                    if (!active) return@collectLatest
                    val lease = Any()
                    val tracker = OutputRuntimeStatus.Tracker()
                    val armed = linkedSetOf<JamesDspRemoteEngine>()
                    var previous = emptyMap<Any, OutputRuntimeStatus.Session>()
                    try {
                        while (currentCoroutineContext().isActive && enabled.value) {
                            val application = appContext as? MainApplication
                            val mode = if (application?.isPlayerSessionMode == true)
                                OutputRuntimeStatus.Mode.PLAYER_SESSION else OutputRuntimeStatus.Mode.GLOBAL
                            val globalIssue = if (mode == OutputRuntimeStatus.Mode.GLOBAL) when (
                                application?.rootSessionDatabase?.globalPlaybackIssue) {
                                GlobalPlaybackEvidence.Reason.SUSPENDED -> OutputRuntimeStatus.GlobalIssue.SUSPENDED
                                GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT
                                null -> null
                            } else null
                            val engines = application?.rootSessionDatabase?.sessionList?.toList()?.mapNotNull { (id, session) ->
                                (session as? RemoteEffectSession)?.effect?.let { id to it }
                            }.orEmpty()
                            val sample = withContext(Dispatchers.IO) {
                                currentCoroutineContext().ensureActive()
                                if (!enabled.value || !FieldController.preferences(appContext).getBoolean("v_field", false))
                                    return@withContext null
                                armed.filter { old -> engines.none { it.second === old } }.forEach { old ->
                                    NativeVisualizationTelemetry.setEnabled(old, NativeVisualizationTelemetry.Module.FIELD, lease, false)
                                    armed.remove(old)
                                }
                                val poll = FieldController.pollState()
                                val outputs = ArrayList<OutputRuntimeStatus.Session>(engines.size)
                                val readings = ArrayList<FieldController.Reading>(engines.size)
                                val progressing = ArrayList<OutputRuntimeStatus.Session>(engines.size)
                                val visualReasons = java.util.IdentityHashMap<Any, String?>()
                                for ((id, engine) in engines) {
                                    currentCoroutineContext().ensureActive()
                                    if (!enabled.value) return@withContext null
                                    val captureSupported = engine.visualizationTelemetrySupported()
                                    val captureArmed = NativeVisualizationTelemetry.setEnabled(engine,
                                            NativeVisualizationTelemetry.Module.FIELD, lease, true)
                                    if (captureArmed) armed += engine
                                    val output = runCatching { engine.outputRuntimeSession()?.copy(sessionId = id) }.getOrNull()
                                        ?: continue
                                    outputs += output
                                    val before = previous[output.identity]
                                    val frames = output.processedFrames?.takeIf { it >= 0 }
                                    val oldFrames = before?.processedFrames?.takeIf { it >= 0 }
                                    val processing = when {
                                        !output.enabled -> OutputRuntimeStatus.Processing.IDLE
                                        before == null || !before.enabled || frames == null || oldFrames == null ||
                                            before.hostRate != output.hostRate || before.sessionId != output.sessionId ->
                                            OutputRuntimeStatus.Processing.UNKNOWN
                                        frames > oldFrames || (oldFrames >= 0x70000000 && frames <= 0x0fffffff) ->
                                            OutputRuntimeStatus.Processing.ACTIVE
                                        frames < oldFrames -> OutputRuntimeStatus.Processing.UNKNOWN
                                        else -> OutputRuntimeStatus.Processing.IDLE
                                    }
                                    if (processing == OutputRuntimeStatus.Processing.ACTIVE) progressing += output
                                    val reading = runCatching { engine.fieldRuntimeReading(output) }.getOrNull() ?: continue
                                    visualReasons[output.identity] = when {
                                        !captureSupported -> "当前驱动未提供 L/R 点云接口；仪表读数独立保留"
                                        !captureArmed -> "L/R 采样订阅未就绪；仪表读数独立保留"
                                        reading.visualization == null -> "等待有效 L/R 窗口或窗口校验通过"
                                        reading.visualization.clamped -> "L/R 窗口含超出可视采样范围的数值"
                                        else -> null
                                    }
                                    readings += reading.copy(processing = processing)
                                }
                                previous = outputs.associateBy { it.identity }
                                val output = tracker.update(outputs).copy(mode = mode, globalIssue = globalIssue)
                                currentCoroutineContext().ensureActive()
                                if (!enabled.value) return@withContext null
                                val published = if (publishCache)
                                    FieldController.publishPageRuntime(appContext, readings, poll) ?: return@withContext null
                                else if (FieldController.pollState() == poll) readings else return@withContext null
                                val selectedOutput = progressing.singleOrNull()
                                val reading = selectedOutput?.let { selected -> published.singleOrNull {
                                    it.identity === selected.identity && it.enabled && it.processing == OutputRuntimeStatus.Processing.ACTIVE
                                } }
                                val reason = when {
                                    !output.connected -> "驱动未连接"
                                    globalIssue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局音效链已挂起"
                                    globalIssue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前声音经过其他输出链路"
                                    progressing.size > 1 -> "多路音频正在处理；不合并不同声场"
                                    selectedOutput == null -> if (output.processing == OutputRuntimeStatus.Processing.IDLE)
                                        "当前没有新的音频处理帧" else "等待活动音频处理窗口"
                                    reading == null -> "当前活动会话的成像仪表尚未就绪"
                                    else -> null
                                }
                                Sample(SystemClock.elapsedRealtime(), reading?.identity, reading, output, reason,
                                    reading?.let { visualReasons[it.identity] })
                            }
                            if (enabled.value && sample != null) onSample(sample)
                            delay(100L)
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { NativeVisualizationTelemetry.release(lease) }
                    }
                }
            }
        }
    }
}
