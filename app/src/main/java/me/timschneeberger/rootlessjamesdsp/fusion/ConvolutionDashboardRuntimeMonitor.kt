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
import java.util.IdentityHashMap
import kotlin.math.log10
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence

/** GET-only observation. The original publisher still owns all kernel/audition acknowledgements. */
object ConvolutionDashboardRuntimeMonitor {
    data class Sample(
        val elapsedRealtimeMs: Long,
        val streamIdentity: Any?,
        val reading: ConvolutionController.Reading?,
        val output: OutputRuntimeStatus.Snapshot,
        val pcm: ConvolutionPcmTelemetry.Snapshot?,
        val pcmUnavailableReason: String?,
        val levelUnavailableReason: String? = pcmUnavailableReason,
        val streamSessionId: Int? = null,
        val runtimeUnavailableReason: String? = null,
        val streamDiscontinuity: Boolean = false
    ) {
        val unavailableReason: String? get() = levelUnavailableReason
        // Match the native meter's stereo mean-square definition, rather than max(L RMS, R RMS).
        // A genuine silent window remains -infinity; it is never a fabricated -180 dBFS reading.
        private fun rms(left: FloatArray, right: FloatArray): Float? {
            if (left.isEmpty() || left.size != right.size) return null
            var square = 0.0
            for (i in left.indices) square += left[i].toDouble()*left[i] + right[i].toDouble()*right[i]
            return if (square > 0.0 && square.isFinite()) (10.0*log10(square/(2.0*left.size))).toFloat()
                else if (square == 0.0) Float.NEGATIVE_INFINITY else null
        }
        private fun meters(): ConvolutionRuntimeReader.Snapshot? = reading?.takeIf {
            runtimeUnavailableReason == null && it.enabled && it.processing == OutputRuntimeStatus.Processing.ACTIVE
        }?.snapshot?.takeIf { it.loaded && it.moduleEnabled && it.available && it.ready }
        private fun level(value: Int?): Float? = value?.takeIf { it != Int.MIN_VALUE }?.div(10f)
        val inputRmsDbfs: Float? get() = pcm?.let { rms(it.inputLeft,it.inputRight) } ?: level(meters()?.input?.rms)
        /** Actual audition output when PCM is available; otherwise the explicitly named normal-output native meter. */
        val outputRmsDbfs: Float? get() = pcm?.let { rms(it.outputLeft,it.outputRight) } ?: level(meters()?.result?.rms)
    }

    private data class Before(val output: OutputRuntimeStatus.Session, val generation: Int?, val sequence: Int?, val crc: Int?)
    private data class Row(val output: OutputRuntimeStatus.Session, val supported: Boolean,
        val pcm: ConvolutionPcmTelemetry.Snapshot?, val reading: ConvolutionController.Reading?,
        val progressed: Boolean, val fresh: Boolean, val discontinuity: Boolean)
    private fun fingerprint(context: Context): Map<String, *> = ConvolutionController.preferences(context).all
        .filterKeys { it in ConvolutionParameters.newKeys || it in setOf(ConvolutionParameters.ENABLED,
            ConvolutionParameters.FILE,ConvolutionParameters.LEGACY_MODE,ConvolutionParameters.LEGACY_ADVANCED) }.toMap()
    private fun internalRate(host: Int): Int = if (host >= 44100) host else if (host > 0 && 48000%host == 0) 48000
        else if (host > 0 && 44100%host == 0) 44100 else 48000

    fun bind(owner: LifecycleOwner, context: Context, enabled: StateFlow<Boolean>, onSample: (Sample) -> Unit) {
        val app = context.applicationContext
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                enabled.collectLatest { power ->
                    if (!power) return@collectLatest
                    var previous = IdentityHashMap<Any, Before>()
                    val tracker = OutputRuntimeStatus.Tracker()
                    while (currentCoroutineContext().isActive && enabled.value) {
                        val values = fingerprint(app)
                        if (values[ConvolutionParameters.ENABLED] as? Boolean != true) return@collectLatest
                        val poll = ConvolutionController.pollState()
                        val audition = ConvolutionController.audition
                        val comparison = ConvolutionController.comparisonTenths
                        val prepared = ConvolutionController.prepared
                        val application = app as? MainApplication
                        val targets = application?.rootSessionDatabase?.sessionList?.toList()
                            ?.mapNotNull { (id, session) -> (session as? RemoteEffectSession)?.effect?.let { id to it } }.orEmpty()
                        val mode = if (application?.isPlayerSessionMode == true) OutputRuntimeStatus.Mode.PLAYER_SESSION else OutputRuntimeStatus.Mode.GLOBAL
                        val issue = if (mode == OutputRuntimeStatus.Mode.GLOBAL) when (application?.rootSessionDatabase?.globalPlaybackIssue) {
                            GlobalPlaybackEvidence.Reason.SUSPENDED -> OutputRuntimeStatus.GlobalIssue.SUSPENDED
                            GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT
                            null -> null
                        } else null
                        val rows = withContext(Dispatchers.IO) {
                            targets.mapNotNull { (id, engine) ->
                                currentCoroutineContext().ensureActive()
                                if (!enabled.value) return@mapNotNull null
                                val output = runCatching { engine.outputRuntimeSession()?.copy(sessionId = id) }.getOrNull() ?: return@mapNotNull null
                                val before = previous[output.identity]
                                val host = output.hostRate?.takeIf { it in 8000..768000 }
                                val advancing = output.enabled && before?.output?.enabled == true && host != null &&
                                    host == before.output.hostRate && id == before.output.sessionId &&
                                    progressed(before.output.processedFrames,output.processedFrames)
                                val discontinuity = before != null && (host != before.output.hostRate || id != before.output.sessionId ||
                                    output.enabled != before.output.enabled || reset(before.output.processedFrames,output.processedFrames))
                                val supported = engine.convolutionPcmSupported()
                                val pcm = if (supported) runCatching { engine.convolutionPcmSnapshot(output) }.getOrNull() else null
                                val raw = runCatching { engine.convolutionRuntimeReading(output) }.getOrNull()
                                val packet = raw?.snapshot
                                val matching = host != null && raw != null && packet != null && raw.identity === output.identity && raw.rate == host &&
                                    packet.rate == internalRate(host) && packet.loaded && packet.moduleEnabled && packet.available && packet.audition == audition
                                val advanced = raw?.advanced?.takeIf { packet != null && it.rate == packet.rate && it.crc == packet.crc }
                                val reading = raw?.copy(processing = if (advancing && matching) OutputRuntimeStatus.Processing.ACTIVE
                                    else if (!output.enabled || !advancing) OutputRuntimeStatus.Processing.IDLE else OutputRuntimeStatus.Processing.UNKNOWN,
                                    advanced = advanced)
                                // Convolution has no A/B tool epoch. Its native capture comparisonEpoch is always zero,
                                // including modes 1..3; actual configuration/reset boundaries invalidate the generation.
                                val fresh = pcm != null && advancing && matching && before?.crc == packet?.crc &&
                                    (pcm.sequence != before?.sequence || pcm.generation != before?.generation) &&
                                    pcm.ready && pcm.moduleEnabled && pcm.leaseActive && !pcm.invalidSamples &&
                                    pcm.rate == packet?.rate && pcm.mode == audition && pcm.comparisonEpoch == 0
                                Row(output,supported,pcm,reading,advancing,fresh,discontinuity)
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (!enabled.value || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collectLatest
                        val same = values == fingerprint(app) && poll == ConvolutionController.pollState() &&
                            audition == ConvolutionController.audition && comparison == ConvolutionController.comparisonTenths &&
                            prepared === ConvolutionController.prepared
                        previous = IdentityHashMap<Any, Before>().also { next ->
                            if (same) rows.forEach { row ->
                                val old = previous[row.output.identity]?.takeIf { it.output.hostRate == row.output.hostRate &&
                                    it.output.sessionId == row.output.sessionId && !row.discontinuity }
                                next[row.output.identity] = Before(row.output,row.pcm?.generation ?: old?.generation,
                                    row.pcm?.sequence ?: old?.sequence,row.reading?.snapshot?.crc ?: old?.crc)
                            }
                        }
                        val output = tracker.update(rows.map { it.output }).copy(mode = mode,globalIssue = issue)
                        val active = rows.filter { it.progressed }
                        val selected = active.singleOrNull()
                        val statusRow = selected ?: rows.singleOrNull().takeIf { active.isEmpty() }
                        val rawReading = statusRow?.reading
                        val snapshot = rawReading?.snapshot
                        val commonReason = when {
                            !same -> "参数或试听状态正在更新"
                            !output.connected -> "驱动未连接"
                            issue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局处理链路已挂起"
                            issue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频使用其他输出链路"
                            active.size > 1 -> "多路音频同时处理，暂不合并监测"
                            selected == null -> "等待活动音频与处理帧"
                            snapshot == null -> "当前驱动未提供卷积诊断"
                            !snapshot.moduleEnabled -> "脉冲反馈已关闭"
                            !snapshot.loaded -> "等待有效工作 IR 装载"
                            rawReading?.processing != OutputRuntimeStatus.Processing.ACTIVE -> "等待卷积参数与试听路径同步"
                            else -> null
                        }
                        val reading = rawReading?.copy(processing = if (commonReason == null) rawReading.processing else OutputRuntimeStatus.Processing.IDLE)
                        val candidate = selected?.pcm
                        val pcmReason = when {
                            commonReason != null -> commonReason
                            selected?.supported != true -> "当前驱动不提供卷积 PCM，请使用配套 1076 驱动"
                            candidate == null -> "等待配套驱动的只读 PCM 窗口"
                            !candidate.moduleEnabled -> "等待有效卷积处理链路"
                            candidate.invalidSamples -> "窗口含无效样本，已跳过"
                            !candidate.ready || candidate.frames == 0 -> "正在收集真实 PCM 窗口"
                            !candidate.leaseActive -> "只读 PCM 采样租约已暂停"
                            selected?.fresh != true -> "等待新的 PCM 窗口或卷积参数同步"
                            else -> null
                        }
                        val levelReason = commonReason ?: if (reading?.snapshot?.let { it.ready && it.windowFrames > 0 } != true)
                            "等待完整 100 ms 仪表窗口" else null
                        onSample(Sample(SystemClock.elapsedRealtime(),statusRow?.output?.identity,reading,output,
                            candidate.takeIf { pcmReason == null },pcmReason,levelReason,statusRow?.output?.sessionId,
                            commonReason,statusRow?.discontinuity == true))
                        delay(250L)
                    }
                }
            }
        }
    }
    private fun progressed(before: Int?, after: Int?): Boolean = before != null && after != null && before >= 0 && after >= 0 &&
        (after > before || (before >= 0x70000000 && after <= 0x0fffffff))
    private fun reset(before: Int?, after: Int?): Boolean = before != null && after != null && before >= 0 && after >= 0 &&
        after < before && !(before >= 0x70000000 && after <= 0x0fffffff)
}
