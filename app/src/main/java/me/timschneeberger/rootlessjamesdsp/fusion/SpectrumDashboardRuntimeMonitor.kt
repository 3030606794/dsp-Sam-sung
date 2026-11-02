package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.log10
import kotlin.math.sqrt
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence

/** Reads the existing short native energy packet; never requests PCM or changes audio/audition state. */
object SpectrumDashboardRuntimeMonitor {
    data class Sample(
        val elapsedRealtimeMs: Long,
        val streamIdentity: Any?,
        val reading: HighFrequencyController.Reading?,
        val output: OutputRuntimeStatus.Snapshot,
        val pcm: SpectrumPcmTelemetry.Snapshot?,
        val pcmUnavailableReason: String?,
        val levelUnavailableReason: String? = pcmUnavailableReason,
        val energyDbfs: Double? = null,
        val energySequence: Int? = null,
        val energyGeneration: Int? = null,
        val energyClamped: Boolean = false,
        val energyUnavailableReason: String? = null
    ) {
        private fun rms(a: FloatArray?, b: FloatArray?): Float? {
            fun level(v: FloatArray?): Double? = v?.takeIf { it.isNotEmpty() }?.let { data ->
                val square = data.sumOf { it.toDouble() * it }
                (20.0 * log10(sqrt(square / data.size).coerceAtLeast(1e-9))).coerceAtLeast(-180.0)
            }
            return listOfNotNull(level(a), level(b)).maxOrNull()?.toFloat()
        }
        val inputRmsDbfs: Float? get() = pcm?.let { rms(it.inputLeft, it.inputRight) }
        val outputRmsDbfs: Float? get() = pcm?.let { rms(it.outputLeft, it.outputRight) }
    }
    fun bind(owner: LifecycleOwner, context: Context, enabled: StateFlow<Boolean>, onSample: (Sample) -> Unit) {
        val app = context.applicationContext
        // The existing collector owns ARM/release per subscriber and cancels on pause,
        // power-off or effect-off. It does not publish/acknowledge audition state here.
        ProfessionalRuntimeMonitor.bindClarityMonitor(owner, app, enabled,
            spectrum = true, publishCache = false) { sample ->
            if (!enabled.value) return@bindClarityMonitor
            val application = app as? MainApplication
            val mode = sample.output.mode ?: if (application?.isPlayerSessionMode == true)
                OutputRuntimeStatus.Mode.PLAYER_SESSION else OutputRuntimeStatus.Mode.GLOBAL
            val issue = if (mode == OutputRuntimeStatus.Mode.GLOBAL) when (application?.rootSessionDatabase?.globalPlaybackIssue) {
                GlobalPlaybackEvidence.Reason.SUSPENDED -> OutputRuntimeStatus.GlobalIssue.SUSPENDED
                GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT
                null -> null
            } else null
            val output = sample.output.copy(mode = mode, globalIssue = issue)
            val reading = sample.reading
            val engines = application?.rootSessionDatabase?.sessionList?.values
                ?.mapNotNull { (it as? RemoteEffectSession)?.effect }.orEmpty()
            // This capability accessor only reads the engine's existing cached bitmask.
            val supported = reading?.let { value -> engines.firstOrNull { it.effect === value.identity }
                ?.visualizationTelemetrySupported() } ?: engines.any { it.visualizationTelemetrySupported() }
            val active = output.activeSessions
            val visualization = reading?.visualization
            val sequence = visualization?.spectrumSequence?.takeIf { it > 0 }
            val generation = visualization?.spectrumGeneration?.takeIf { it >= 0 }
            val clamped = visualization?.spectrumClamped == true
            val energy = reading?.spectrumEnergyDbfs
            val matchingReading = reading?.takeIf { it.enabled &&
                it.processing == OutputRuntimeStatus.Processing.ACTIVE && it.rate in 8000..768000 &&
                output.processing == OutputRuntimeStatus.Processing.ACTIVE && active.size == 1 &&
                active.single().rate == it.rate && issue == null }
            val reason = when {
                !HighFrequencyController.preferences(app).getBoolean("v_spectrum", false) -> "高频谐波激励已关闭"
                !output.connected -> "驱动未连接"
                issue == OutputRuntimeStatus.GlobalIssue.SUSPENDED -> "全局处理链路已挂起"
                issue == OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT -> "当前音频使用其他输出链路"
                issue != null -> "当前输出链路未处理音频"
                active.size > 1 -> "多路音频同时处理，暂不合并激励能量"
                !supported -> "当前驱动不支持激励支路能量监视"
                output.processing == OutputRuntimeStatus.Processing.UNKNOWN -> "正在确认活动音频与激励能量"
                output.processing != OutputRuntimeStatus.Processing.ACTIVE || active.isEmpty() -> "未播放音频 · 等待活动音频"
                reading == null -> "等待驱动的有效高频处理状态"
                matchingReading == null -> "高频处理状态与采样率正在同步"
                visualization == null -> "等待驱动的激励支路能量采样"
                clamped -> "激励能量读数超出有效范围，已拒绝该窗口"
                sequence == null || generation == null -> "正在收集激励支路能量窗口"
                energy == null -> "静音或当前窗口无有效激励能量"
                !energy.isFinite() -> "激励能量读数无效，已跳过"
                else -> null
            }
            onSample(Sample(sample.elapsedRealtimeMs, sample.streamIdentity, matchingReading, output,
                pcm = null, pcmUnavailableReason = "当前视图使用真实激励能量读数",
                levelUnavailableReason = reason, energyDbfs = energy.takeIf { reason == null },
                energySequence = sequence, energyGeneration = generation, energyClamped = clamped,
                energyUnavailableReason = reason))
        }
    }
}
