package me.timschneeberger.rootlessjamesdsp.fusion

import java.util.Locale
import kotlin.math.*
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalResponse.Complex

/** Native DynamicTuning preset-relative small-signal responses; never a multiband AGC model. */
class DynamicDashboardModel private constructor(val settings: DynamicParameters.Settings, val rate: Int,
    val actualRate: Boolean, val enabled: Boolean) {
    val sampleRate get() = rate
    val moduleEnabled get() = enabled
    val professional = settings.professional
    val baseIndex = settings.base
    val baseName = ViperParameters.dynamicPresets.keys.elementAtOrElse(baseIndex) { "均衡" }
    val deep = baseIndex == 4
    val latencySamples = if (deep) 0 else 3
    val coreOn = professional && settings.coreOn
    val toneOn = professional && settings.toneOn
    val adaptive = professional && settings.adaptiveOn && settings.maxReduction != 0
    val detectorIsRms = settings.detector != 0
    val mix = if (professional && settings.mixOn) settings.mix / 1000.0 else 1.0
    val outputGain = if (professional && settings.outputOn) 10.0.pow(settings.output / 200.0) else 1.0
    val thresholdDb = settings.threshold / 10.0
    val kneeDb = settings.knee / 10.0
    val maxReductionDb = settings.maxReduction / 10.0
    val maxRecoveryDb = if (adaptive) maxReductionDb else 0.0
    val attackMs = settings.attack / 10.0
    val releaseMs = settings.release.toDouble()
    val xHz = safeHz(DynamicParameters.foundation(baseIndex)[0] + if (toneOn && !deep) settings.xDelta else 0)
    val xUpper = safeHz(DynamicParameters.foundation(baseIndex)[1])
    val yLow = min(safeHz(DynamicParameters.foundation(baseIndex)[2] + if (toneOn && !deep) settings.lowFreqDelta else 0),
        floor(rate * .45).toInt() - 1)
    val yHigh = max(yLow + 1, safeHz(DynamicParameters.foundation(baseIndex)[3] + if (toneOn && !deep) settings.highFreqDelta else 0))
    val deepHz = safeHz(55 + if (toneOn && deep) settings.deepFreqDelta else 0)
    val hpHz = if (toneOn && settings.hp > 0) safeHz(settings.hp) else 0
    val lpHz = if (toneOn && settings.lp > 0) max(safeHz(settings.lp), hpHz + 1).coerceAtMost(floor(rate * .45).toInt()) else 0
    val detectorLowHz = safeHz(settings.detectorHP)
    val detectorHighHz = max(detectorLowHz + 1, safeHz(settings.detectorLP))
    val frequencies = DoubleArray(256) { 20.0 * (min(20000.0, rate * .45) / 20.0).pow(it / 255.0) }
    val foundationDb = DoubleArray(frequencies.size)
    val baseDb get() = foundationDb
    val lowDb = DoubleArray(frequencies.size)
    val enhancementDb get() = lowDb
    val highDb = DoubleArray(frequencies.size)
    val unchangedDb get() = highDb
    val referenceDb: DoubleArray
    val recoveryFloorDb: DoubleArray
    val recoveredDb get() = recoveryFloorDb
    val detectorDb = DoubleArray(frequencies.size)
    val detectionMask = DoubleArray(frequencies.size)
    val branchFilterDb = DoubleArray(frequencies.size)
    private val raw = Array(frequencies.size) { Complex(1.0) }
    private val unaffected = Array(frequencies.size) { Complex(1.0) }
    private val enhancement = Array(frequencies.size) { Complex(0.0) }
    val inputLevels = DoubleArray(121) { -60.0 + it * .5 }
    val recoveryCurve = DoubleArray(inputLevels.size) { recoveryForInput(inputLevels[it]) }
    val timeMs = DoubleArray(160) { max(1000.0, max(attackMs, releaseMs) * 5) * it / 159.0 }
    val attackEnvelope = DoubleArray(timeMs.size) { maxReductionDb * (1 - exp(-timeMs[it] / attackMs)) }
    val releaseEnvelope = DoubleArray(timeMs.size) { maxReductionDb * exp(-timeMs[it] / releaseMs) }
    val staticStatusLines get() = listOf(
        "${if (actualRate) "实际" else "参考"} $rate Hz · $baseName · 强度${settings.strength}%${if (enabled) "" else " · 关闭预览"}",
        if (deep) "深低音 $deepHz Hz · 同相L=R小信号参考 · 注入/共振Q相对原预设" else
            "主分带 $xHz/$xUpper Hz · 支路分界 $yLow/$yHigh Hz · 原始重构延时3采样",
        "${if (adaptive) "增强支路回收" else "回收关闭预览"} · ${if (detectorIsRms) "20ms RMS" else "双声道峰值"}检测 $detectorLowHz–$detectorHighHz Hz · 非总输出GR")

    init {
        val foundation = settings.copy(professional = false)
        frequencies.forEachIndexed { index, hz ->
            val components = components(settings, hz)
            raw[index] = components[0]
            unaffected[index] = components[1]
            enhancement[index] = components[2]
            val original = components(foundation, hz)
            foundationDb[index] = db((original[1] + original[2]).magnitude())
            lowDb[index] = db(components[2].magnitude() * mix * outputGain)
            highDb[index] = db((components[0] * (1 - mix) + components[1] * mix).magnitude() * outputGain)
            val z = Complex(cos(2 * PI * hz / rate), -sin(2 * PI * hz / rate))
            detectorDb[index] = db((butterworth(detectorLowHz, true, z) * butterworth(detectorHighHz, false, z)).magnitude())
            var branch = Complex(1.0)
            if (hpHz > 0) branch *= butterworth(hpHz, true, z)
            if (lpHz > 0) branch *= butterworth(lpHz, false, z)
            branchFilterDb[index] = db(branch.magnitude())
        }
        val peakDetector = detectorDb.maxOrNull() ?: 0.0
        detectorDb.indices.forEach { detectionMask[it] = 10.0.pow((detectorDb[it] - peakDetector) / 20.0).coerceIn(0.0, 1.0) }
        referenceDb = responseAtReduction(0.0)
        recoveryFloorDb = responseAtReduction(maxRecoveryDb)
    }

    /** Apply recovery only to the enhancement component, then recombine complex paths. */
    fun responseAtReduction(reductionDb: Double): DoubleArray {
        val amount = if (adaptive) reductionDb.takeIf { it.isFinite() }?.coerceIn(0.0, maxReductionDb) ?: 0.0 else 0.0
        val gain = 10.0.pow(-amount / 20.0)
        return DoubleArray(frequencies.size) { i ->
            db((raw[i] * (1 - mix) + (unaffected[i] + enhancement[i] * gain) * mix).magnitude() * outputGain)
        }
    }

    /** Same soft knee and cap as the one existing enhancement-recovery detector. */
    fun recoveryForInput(dbfs: Double): Double {
        val over = dbfs - thresholdDb
        val wanted = if (kneeDb > 0 && over > -kneeDb * .5 && over < kneeDb * .5)
            (over + kneeDb * .5).pow(2) / (2 * kneeDb) else max(0.0, over)
        return wanted.coerceIn(0.0, maxReductionDb)
    }

    private fun components(s: DynamicParameters.Settings, hz: Double): Array<Complex> {
        val foundation = DynamicParameters.foundation(s.base)
        val isDeep = s.base == 4
        val core = s.professional && s.coreOn
        val tone = s.professional && s.toneOn
        var bass = ((100 + 20 * s.strength) / 100.0).toFloat()
        var lowGain = foundation[5] / 100f
        var highGain = foundation[4] / 100f
        val qPeak = min(1600.0, ((bass.toDouble() - 1) / 20) * 1600).toFloat()
        var quality = qPeak / 666f + .5f
        var inject = 1f
        if (core) {
            if (isDeep) { inject = max(0f, 1 + s.injectDelta / 1000f); quality = (quality + s.qDelta / 100f).coerceIn(.1f, 4f) }
            else { bass *= 10.0.pow(s.bassDelta / 200.0).toFloat(); lowGain = max(0f, lowGain + s.lowGainDelta / 1000f); highGain = max(0f, highGain + s.highGainDelta / 1000f) }
        }
        val z = Complex(cos(2 * PI * hz / rate), -sin(2 * PI * hz / rate))
        val z3 = z * z * z
        val direct = if (isDeep) Complex(1.0) else z3
        val high: Complex
        var low: Complex
        if (isDeep) {
            val cutoff = safeHz(55 + if (tone) s.deepFreqDelta else 0)
            val w = 2 * PI * cutoff.toFloat() / rate
            val cosine = cos(w); val alpha = sin(w) / (quality.toDouble() * 2)
            low = (Complex((1 - cosine) * .5) + z * (1 - cosine) + z * z * ((1 - cosine) * .5)) /
                (Complex(1 + alpha) - z * (2 * cosine) + z * z * (1 - alpha)) * (2 * inject.toDouble())
            high = Complex(1.0)
        } else {
            val x = safeHz(foundation[0] + if (tone) s.xDelta else 0)
            val lower = min(safeHz(foundation[2] + if (tone) s.lowFreqDelta else 0), floor(rate * .45).toInt() - 1)
            val upper = max(lower + 1, safeHz(foundation[3] + if (tone) s.highFreqDelta else 0))
            val xx = poles(x, z); val yl = poles(lower, z); val yh = poles(upper, z)
            low = xx * (z3 * highGain.toDouble() + yh * (1 - highGain.toDouble()) + yl * (lowGain.toDouble() - 1)) * bass.toDouble()
            high = z3 - xx
        }
        val hp = if (tone && s.hp > 0) safeHz(s.hp) else 0
        val lp = if (tone && s.lp > 0) max(safeHz(s.lp), hp + 1).coerceAtMost(floor(rate * .45).toInt()) else 0
        if (hp > 0) low *= butterworth(hp, true, z)
        if (lp > 0) low *= butterworth(lp, false, z)
        return arrayOf(direct, high, low)
    }

    private fun poles(hz: Int, z: Complex): Complex {
        val a = (hz.toFloat() * PI.toFloat() / rate.toFloat()).toDouble()
        val pole = Complex(a) / (Complex(1.0) - z * (1 - a))
        val square = pole * pole
        return square * square
    }
    private fun butterworth(hz: Int, high: Boolean, z: Complex): Complex {
        val omega = 2 * PI * hz / rate; val cosine = cos(omega); val alpha = sin(omega) / sqrt(2.0)
        val first = if (high) 1 + cosine else 1 - cosine
        val middle = if (high) -first else first
        return (Complex(first * .5) + z * middle + z * z * (first * .5)) /
            (Complex(1 + alpha) - z * (2 * cosine) + z * z * (1 - alpha))
    }
    private fun safeHz(hz: Int) = hz.coerceIn(10, floor(rate * .45).toInt())
    companion object {
        private fun db(amplitude: Double) = 20 * log10(amplitude.coerceAtLeast(1e-12))
        fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false) = DynamicDashboardModel(
            DynamicParameters.capture(values), rate.takeIf { it in 8000..768000 } ?: 48000,
            actualRate && rate in 8000..768000, DynamicParameters.moduleEnabled(values))
    }
}
