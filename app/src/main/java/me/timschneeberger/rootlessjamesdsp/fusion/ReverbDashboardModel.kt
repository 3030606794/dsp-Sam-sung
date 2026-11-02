package me.timschneeberger.rootlessjamesdsp.fusion

import java.util.Locale
import kotlin.math.*

/** Observer-only steady-state port of ProfessionalReverb::State::predict and CRevModel. */
class ReverbDashboardModel private constructor(val settings: ReverbParameters.Settings,
    val rate: Int, val actualRate: Boolean, val enabled: Boolean) {
    val sampleRate get() = rate
    val moduleEnabled get() = enabled
    val professional = settings.professional
    private val core = if (professional) listOf(settings.size, settings.width, settings.damp, settings.wet, settings.dry)
        else settings.legacy.map { it * 10 }
    val roomPercent = core[0] / 10.0
    val widthPercent = core[1] / 10.0
    val dampPercent = core[2] / 10.0
    val feedback = .7 + .28 * core[0] / 1000.0
    val advanced = if (professional) settings.advanced else ReverbAdvancedParameters.Settings()
    private val referenceRate = if (professional) 44100.0 else 48000.0
    private val baseDamp = .4 * core[2] / 1000.0
    private val damping = if (baseDamp > 0) baseDamp.pow(referenceRate / rate) else 0.0
    val wetGain = if (professional && settings.linked) settings.mix / 1000.0 else core[3] * .003
    val dryGain = if (professional && settings.linked) 1 - settings.mix / 1000.0 else core[4] / 500.0
    val outputGain = if (professional && settings.outputOn) 10.0.pow(settings.output / 200.0) else 1.0
    val predelayMs = if (professional && settings.predelayOn) settings.predelay / 10.0 else 0.0
    val lowCutOn = professional && settings.lowCutOn
    val highCutOn = professional && settings.highCutOn
    val lowCutHz = min(settings.lowCut.toDouble(), rate * .45)
    val highCutHz = min(settings.highCut.toDouble(), rate * .45)
    val lowBandHz = min(advanced.lowCrossover.toDouble(), rate * .45)
    val highBandHz = min(advanced.highCrossover.toDouble(), rate * .45)
    val representativeHz = doubleArrayOf(min(80.0, lowBandHz / 4), sqrt(lowBandHz * highBandHz), min(rate * .45, max(8000.0, highBandHz * 2)))
    private val lengths = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    val decayMs = DoubleArray(3)
    val referenceMs: Double
    val spectrumHz = DoubleArray(128) { 20.0 * (min(20000.0, rate * .45) / 20).pow(it / 127.0) }
    /** These are one-loop losses and wet-output filters, not a measured full reverb magnitude. */
    val loopLossDb: DoubleArray
    val filterDb: DoubleArray
    val lowCutPreviewDb: DoubleArray
    val highCutPreviewDb: DoubleArray
    val staticStatusLines get() = listOf(
        "${if (actualRate) "实际" else "参考"} $rate Hz · ${if (professional) "专业混响" else "原五项混响"}${if (enabled) "" else " · 已关闭（模型预览）"}",
        "反馈 RT60 ${format(decayMs[0] / 1000)} / ${format(decayMs[1] / 1000)} / ${format(decayMs[2] / 1000)} s · 预延迟 ${format(predelayMs, 1)} ms",
        "预测非实测 · 宽度 ${format(widthPercent, 1)}% · 干/湿系数 ${format(dryGain)} / ${format(wetGain)}")

    init {
        var reference = 0.0
        for (channel in 0..1) for (length in lengths) {
            val delay = floor((length + 23 * channel) * rate / referenceRate + .5).coerceAtLeast(1.0) / rate
            val rt = if (advanced.rtOn) advanced.rt60 / 100.0 else delay * ln(.001) / ln(feedback)
            reference = max(reference, rt)
            for (band in 0..2) decayMs[band] = max(decayMs[band], loopDecay(delay, representativeHz[band]) * 1000)
        }
        referenceMs = reference * 1000
        val representativeDelay = floor(1640 * rate / referenceRate + .5).coerceAtLeast(1.0) / rate
        loopLossDb = spectrumHz.map { hz -> db(loopResponse(representativeDelay, hz).magnitude()) }.toDoubleArray()
        lowCutPreviewDb = spectrumHz.map { hz -> db(filter(hz, lowCutHz, true).magnitude()) }.toDoubleArray()
        highCutPreviewDb = spectrumHz.map { hz -> db(filter(hz, highCutHz, false).magnitude()) }.toDoubleArray()
        filterDb = spectrumHz.indices.map { index ->
            (if (lowCutOn) lowCutPreviewDb[index] else 0.0) + (if (highCutOn) highCutPreviewDb[index] else 0.0)
        }.toDoubleArray()
    }
    private fun loopResponse(delay: Double, hz: Double): SurroundProfessionalResponse.Complex {
        val one = SurroundProfessionalResponse.Complex(1.0)
        val z = SurroundProfessionalResponse.Complex(cos(2 * PI * hz / rate), -sin(2 * PI * hz / rate))
        val gain = if (advanced.rtOn) .001.pow(delay / (advanced.rt60 / 100.0)) else feedback
        if (!advanced.bandsOn) return SurroundProfessionalResponse.Complex(gain * (1 - damping)) / (one - z * damping)
        val lowT = tan(PI * lowBandHz / rate); val highT = tan(PI * highBandHz / rate)
        val aLow = lowT / (1 + lowT); val aHigh = highT / (1 + highT)
        val lo = (one + z) * aLow / (one - z * (1 - 2 * aLow))
        val hi = (one + z) * aHigh / (one - z * (1 - 2 * aHigh))
        val gLow = gain.pow(100.0 / advanced.bassRatio); val gHigh = gain.pow(100.0 / advanced.trebleRatio)
        return SurroundProfessionalResponse.Complex(gHigh) + hi * (gain - gHigh) + lo * (gLow - gain)
    }
    private fun loopDecay(delay: Double, hz: Double): Double = min(60.0,
        delay * ln(.001) / ln(loopResponse(delay, hz).magnitude().coerceIn(1e-12, .999999999)))
    private fun filter(hz: Double, cutoff: Double, high: Boolean): SurroundProfessionalResponse.Complex {
        val w = 2 * PI * cutoff / rate; val cosine = cos(w); val alpha = sin(w) / sqrt(2.0)
        val den = 1 + alpha; val b = (if (high) 1 + cosine else 1 - cosine) / (2 * den)
        val z = SurroundProfessionalResponse.Complex(cos(2 * PI * hz / rate), -sin(2 * PI * hz / rate))
        val one = SurroundProfessionalResponse.Complex(1.0)
        return (SurroundProfessionalResponse.Complex(b) + z * (if (high) -2 * b else 2 * b) + z * z * b) /
            (one + z * (-2 * cosine / den) + z * z * ((1 - alpha) / den))
    }
    fun decayAmplitude(timeMs: Double, band: Int): Double {
        if (timeMs < predelayMs) return 0.0
        return .001.pow((timeMs - predelayMs) / decayMs[band].coerceAtLeast(1.0))
    }
    companion object {
        private fun db(value: Double) = 20 * log10(value.coerceAtLeast(1e-12))
        private fun format(value: Double, digits: Int = 2) = String.format(Locale.ROOT, "% .${digits}f", value).trim()
        fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false) = ReverbDashboardModel(
            ReverbParameters.capture(values), rate.takeIf { it in 8000..768000 } ?: 48000,
            actualRate && rate in 8000..768000, ReverbParameters.moduleEnabled(values))
    }
}
