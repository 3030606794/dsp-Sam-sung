package me.timschneeberger.rootlessjamesdsp.creative

import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters
import me.timschneeberger.rootlessjamesdsp.utils.Constants

enum class CreativeEffectsKind(val prefsName: String, val enableKey: String, val title: String,
    val parameter: Int, val query: Int, val icon: Int) {
    GRANULAR(Constants.PREF_GRANULAR, "granular_enable", "颗粒化延迟", 196730, 132011, R.drawable.ic_effect_granular_24),
    FREEZE(Constants.PREF_FREEZE, "freeze_enable", "频谱冻结", 196731, 132012, R.drawable.ic_effect_freeze_24),
    SHIMMER(Constants.PREF_SHIMMER, "shimmer_enable", "闪烁混响 Shimmer", 196732, 132013, R.drawable.ic_effect_shimmer_24),
}

/** Integer units are shared with the native protocol; all editors use the existing bass widget. */
object CreativeEffectsParameters {
    const val CAPABILITY_QUERY = 132010
    const val SCHEMA = 1
    const val RUNTIME_WORDS = 32
    private fun percent(key: String, title: String, default: Int, max: Int = 1000) =
        BassParameters.Control(key, title, 0, max, default, 10, "%", 1)
    private fun ms(key: String, title: String, min: Int, max: Int, default: Int) =
        BassParameters.Control(key, title, min, max, default, unit = "ms")
    private fun hz(key: String, title: String, min: Int, max: Int, default: Int) =
        BassParameters.Control(key, title, min, max, default, unit = "Hz")
    private fun pitch(key: String, min: Int, max: Int, default: Int) =
        BassParameters.Control(key, "移调", min, max, default, 100, "半音", 2)

    val granularMix = percent("gr_mix", "效果混合", 350)
    val granularDelay = ms("gr_delay", "延迟时间", 10, 2000, 300)
    val granularGrain = ms("gr_grain", "颗粒长度", 10, 250, 80)
    val granularDensity = BassParameters.Control("gr_density", "颗粒密度", 1, 64, 18, unit = "粒/秒")
    val granularSpread = ms("gr_spread", "时间散布", 0, 1000, 120)
    val granularPitch = pitch("gr_pitch", -2400, 2400, 700)
    val granularReverse = percent("gr_reverse", "倒放概率", 250)
    val granularFeedback = percent("gr_feedback", "反馈", 300, 850)
    val granularWidth = percent("gr_width", "立体声散布", 700)
    val granularLowpass = hz("gr_lowpass", "高频截止", 500, 20000, 10000)

    val freezeMix = percent("fr_mix", "效果混合", 500)
    val freezeDecay = ms("fr_decay", "冻结衰减时间", 500, 60000, 12000)
    val freezeAttack = ms("fr_attack", "冻结淡入", 5, 2000, 120)
    val freezeRelease = ms("fr_release", "释放淡出", 20, 5000, 600)
    val freezePitch = pitch("fr_pitch", -1200, 1200, 0)
    val freezeBlur = percent("fr_blur", "频谱柔化", 120)
    val freezeWidth = percent("fr_width", "立体声宽度", 700)
    val freezeLowpass = hz("fr_lowpass", "高频截止", 500, 20000, 12000)

    val shimmerMix = percent("sh_mix", "效果混合", 300)
    val shimmerSize = percent("sh_size", "空间大小", 650)
    val shimmerDecay = ms("sh_decay", "混响尾音", 500, 20000, 6500)
    val shimmerPredelay = ms("sh_predelay", "预延迟", 0, 250, 30)
    val shimmerPitch = pitch("sh_pitch", -1200, 2400, 1200)
    val shimmerAmount = percent("sh_amount", "闪烁强度", 500)
    val shimmerDamping = hz("sh_damping", "高频阻尼", 500, 20000, 7000)
    val shimmerWidth = percent("sh_width", "立体声宽度", 800)
    val shimmerDiffusion = percent("sh_diffusion", "扩散", 700)
    val shimmerHighpass = hz("sh_highpass", "低频截止", 20, 1000, 120)

    fun controls(kind: CreativeEffectsKind): List<BassParameters.Control> = when (kind) {
        CreativeEffectsKind.GRANULAR -> listOf(granularMix, granularDelay, granularGrain, granularDensity,
            granularSpread, granularPitch, granularReverse, granularFeedback, granularWidth, granularLowpass)
        CreativeEffectsKind.FREEZE -> listOf(freezeMix, freezeDecay, freezeAttack, freezeRelease,
            freezePitch, freezeBlur, freezeWidth, freezeLowpass)
        CreativeEffectsKind.SHIMMER -> listOf(shimmerMix, shimmerSize, shimmerDecay, shimmerPredelay,
            shimmerPitch, shimmerAmount, shimmerDamping, shimmerWidth, shimmerDiffusion, shimmerHighpass)
    }
    fun basic(kind: CreativeEffectsKind): List<BassParameters.Control> = when (kind) {
        CreativeEffectsKind.GRANULAR -> listOf(granularMix, granularDelay, granularPitch)
        CreativeEffectsKind.FREEZE -> listOf(freezeMix, freezeRelease)
        CreativeEffectsKind.SHIMMER -> listOf(shimmerMix, shimmerDecay, shimmerAmount)
    }
    fun value(values: Map<String, *>, control: BassParameters.Control) = BassParameters.value(values, control)
    fun packet(kind: CreativeEffectsKind, values: Map<String, *>, captureToken: Int = 0, hold: Boolean = false): IntArray {
        val enabled = values[kind.enableKey] == true
        val controls = controls(kind).map { value(values, it) }
        val content = if (kind == CreativeEffectsKind.FREEZE)
            listOf(controls[0], captureToken.coerceAtLeast(0), if (hold && enabled) 1 else 0) + controls.drop(1)
        else controls
        return (listOf(SCHEMA, if (enabled) 1 else 0) + content).toIntArray()
    }
    fun decode(kind: CreativeEffectsKind, words: IntArray): CreativeEffectsRuntime? {
        if (words.size != RUNTIME_WORDS || words[0] != SCHEMA || words[1] !in 0..1 ||
            words[2] !in 8000..768000 || words[3] < 0) return null
        return CreativeEffectsRuntime(kind, words[1] == 1, words[2], words[3], words[4],
            words[5] / 1000f, words[6] / 1000f, words[7] / 1000f, words[8], words[9],
            words[10].coerceIn(0, 1000) / 1000f, words.slice(12..27).map { it / 1000f })
    }
}

data class CreativeEffectsRuntime(val kind: CreativeEffectsKind, val enabled: Boolean,
    val sampleRate: Int, val processedFrames: Int, val parameterHash: Int,
    val inputPeakDb: Float, val outputPeakDb: Float, val wetPeakDb: Float,
    val activity: Int, val aux: Int, val phase: Float, val spectrumDb: List<Float>,
    val engineEnabled: Boolean = false, val isProcessing: Boolean = false)
