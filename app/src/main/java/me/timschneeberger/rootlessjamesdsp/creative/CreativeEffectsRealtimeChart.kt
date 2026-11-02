package me.timschneeberger.rootlessjamesdsp.creative

import android.content.Context
import android.os.SystemClock
import me.timschneeberger.rootlessjamesdsp.effectvisuals.EffectVisualView

/** Draws each effect's native activity, not one generic input/output level graph. */
internal class CreativeEffectsRealtimeChart(context: Context, private val kind: CreativeEffectsKind) : EffectVisualView(context, textSp = 12f) {
    private val history = ArrayDeque<CreativeEffectsVisuals.Sample>()
    private var sampleRate = 0
    private var parameterHash: Int? = null
    private var breakNext = true

    init {
        setVisual(CreativeEffectsVisuals.live(kind, emptyList()))
        setStatus("等待正常播放与活动音频")
    }

    fun pause(reason: String) {
        breakNext = true
        setStatus(reason)
    }

    fun showSample(sample: CreativeEffectsRuntime) {
        if (!sample.isProcessing || !sample.enabled) { pause("等待正常播放与活动音频"); return }
        val now = SystemClock.elapsedRealtime()
        if (sampleRate != sample.sampleRate || parameterHash != sample.parameterHash) {
            history.clear(); breakNext = true; sampleRate = sample.sampleRate; parameterHash = sample.parameterHash
        }
        val previous = history.lastOrNull()
        history.addLast(CreativeEffectsVisuals.Sample(now, sample,
            split = breakNext || previous == null || now - previous.timeMs > 1500))
        while (history.isNotEmpty() && (history.size > 128 || history.first().timeMs < now - 20000)) history.removeFirst()
        breakNext = false
        setVisual(CreativeEffectsVisuals.live(kind, history.toList()))
        setStatus(if (kind == CreativeEffectsKind.FREEZE && sample.activity == 0) "尚未捕捉 · 点击捕捉当前声音" else null)
    }
}
