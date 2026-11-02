package me.timschneeberger.rootlessjamesdsp.fusion

import java.util.Locale
import kotlin.math.*
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalResponse.Complex

/** Read-only AnalogX settings slices. No DSP state, writes, warm-up, A/B or capture ownership. */
class AnalogDashboardModel private constructor(val settings: AnalogParameters.Settings, val rate: Int,
    val actualRate: Boolean, val enabled: Boolean) {
    val sampleRate get() = rate
    val moduleEnabled get() = enabled
    val professional = settings.professional
    val baseMode = settings.baseMode
    val baseName = AnalogParameters.baseNames[baseMode]
    val coreOn = professional && settings.coreOn
    val toneOn = professional && settings.toneOn
    val drive = if (coreOn) 10.0.pow(settings.drive / 200.0) else 1.0
    val amount = if (coreOn) settings.amount / 1000.0 else 1.0
    val baseGain = floatArrayOf(.6f, 1.2f, 2.4f)[baseMode].toDouble()
    val mix = if (professional && settings.mixOn) settings.mix / 1000.0 else 1.0
    val outputGain = if (professional && settings.outputOn) 10.0.pow(settings.output / 200.0) else 1.0
    val effectiveFactor = if (professional && settings.qualityOn) settings.factor else 1
    val latencySamples = if (effectiveFactor > 1) 31 else 0
    private val clampDomain = coreOn && (settings.drive != 0 || settings.h2 != 0 || settings.h3 != 0 ||
        settings.h4 != 0 || settings.h5 != 0)
    val highpassHz = safeFrequency(if (toneOn) settings.hp.toDouble() else 240.0)
    val lowpassHz = safeFrequency(AnalogParameters.baseLowpass[baseMode] +
        if (toneOn) settings.lpDelta.toDouble() else 0.0)
    val peakHz = min(if (toneOn) settings.peak.toDouble() else 633.0, rate * .45).toFloat().toDouble()
    val highpassQ = if (toneOn) (settings.hpQ / 1000f).toDouble() else .717f.toDouble()
    val lowpassQ = if (toneOn) (settings.lpQ / 1000f).toDouble() else .717f.toDouble()
    val peakGainDb = if (toneOn) (settings.peakGain / 100f).toDouble() else .58f.toDouble()
    val peakBandwidthOctaves = if (toneOn) (settings.peakBW / 1000f).toDouble() else 6.28f.toDouble()
    private val weights = floatArrayOf(.01f, .02f, .0001f, .001f, 0f, 0f, 0f, 0f, 0f, 0f).apply {
        if (coreOn) listOf(settings.h2, settings.h3, settings.h4, settings.h5).forEachIndexed { i, v ->
            this[i + 1] += (v / 1000000.0).toFloat()
        }
    }
    private val coefficientScale = weights.sumOf { abs(it.toDouble()) }.let { if (it > 1.0) 1.0 / it else 1.0 }
    val harmonicCoefficients = DoubleArray(4) { weights[it + 1].toDouble() * coefficientScale }
    private val polynomial = harmonicPolynomial(weights)
    val input = DoubleArray(257) { -1.5 + 3.0 * it / 256.0 }
    val selectedTransfer = DoubleArray(input.size) { shape(input[it]) }
    val mixedTransfer = DoubleArray(input.size) { mixedShape(input[it]) }
    val frequencies = DoubleArray(192) { 20.0 * (min(20000.0, rate * .45) / 20.0).pow(it / 191.0) }
    val softeningDb: DoubleArray
    val preResidualDb: DoubleArray
    val linearReferenceDb: DoubleArray
    val reconstructionDb: DoubleArray
    val staticStatusLines get() = listOf(
        "${if (actualRate) "实际" else "参考"} $rate Hz · $baseName${if (professional) " · 专业染色" else " · 原版"}${if (enabled) "" else " · 关闭预览"}",
        "HP ${f(highpassHz, 0)}Hz · LP ${f(lowpassHz, 0)}Hz · 峰形 ${f(peakHz, 0)}Hz/${f(peakGainDb, 2)}dB · 混合 ${f(mix * 100, 1)}%",
        "去常数瞬时切片，非DC阻断实测 · $effectiveFactor×/$latencySamples 样本 · 频响为小信号参考")

    init {
        val hp = biquad(0, highpassHz, highpassQ)
        val lp = biquad(1, lowpassHz, lowpassQ)
        val peak = biquad(2, peakHz, peakBandwidthOctaves, peakGainDb)
        val one = Complex(1.0)
        val fir = if (effectiveFactor > 1) firTaps(effectiveFactor) else doubleArrayOf(1.0)
        val postDb = DoubleArray(frequencies.size)
        val hpDb = DoubleArray(frequencies.size)
        val combined = DoubleArray(frequencies.size)
        val quality = DoubleArray(frequencies.size)
        frequencies.forEachIndexed { index, hz ->
            val high = hp.response(hz, rate)
            val post = lp.response(hz, rate) * peak.response(hz, rate)
            val internalOmega = 2 * PI * hz / (rate.toDouble() * effectiveFactor)
            // Harmonic.cpp's actual DC-block IIR is a separate linear memory stage.
            val z = Complex(cos(internalOmega), -sin(internalOmega))
            val dcPole = if (effectiveFactor == 1) .999 else .999.pow(1.0 / effectiveFactor)
            val dc = (one - z) / (one - z * dcPole)
            // Center the two-pass FIR at the same 31-host-sample delay as AnalogX's dry path.
            val center = (fir.size - 1) * .5
            var centeredFir = 0.0
            fir.forEachIndexed { n, tap -> centeredFir += tap * cos(internalOmega * (n - center)) }
            val reconstruction = centeredFir * centeredFir
            val residual = high * dc * (drive * polynomial[1] * baseGain * amount * reconstruction)
            val wet = post * (one + residual) * .8
            val output = (one * (1 - mix) + wet * mix) * outputGain
            postDb[index] = db(post.magnitude())
            hpDb[index] = db(high.magnitude())
            combined[index] = db(output.magnitude())
            quality[index] = db(reconstruction)
        }
        softeningDb = postDb
        preResidualDb = hpDb
        linearReferenceDb = combined
        reconstructionDb = quality
    }

    /** A centered instantaneous polynomial slice, not a simulation of stateful DC removal. */
    fun shape(x: Double): Double {
        if (!x.isFinite()) return 0.0
        val feed = (x * drive).let { if (clampDomain) it.coerceIn(-1.0, 1.0) else it }
        val centered = harmonicRaw(feed) - polynomial[0]
        return .8 * (x + baseGain * amount * centered)
    }
    fun mixedShape(x: Double) = (x + mix * (shape(x) - x)) * outputGain
    fun harmonicRaw(x: Double): Double {
        var result = polynomial.last().toDouble()
        for (i in polynomial.lastIndex - 1 downTo 0) result = polynomial[i] + x * result
        return result
    }
    private fun safeFrequency(hz: Double) = (if (hz >= rate * .5) rate * .45 else hz).toFloat().toDouble()

    /** Same denominator and octave-bandwidth convention as MultiBiquad::RefreshFilter. */
    private fun biquad(kind: Int, cutoff: Double, qOrBw: Double, gainDb: Double = 0.0): Biquad {
        val omega = 2 * PI * cutoff / rate
        val cosine = cos(omega)
        val sine = sin(omega)
        val alpha = if (kind == 2) sinh(qOrBw * ln(2.0) * omega / (2 * sine)) * sine
            else sine / (2 * qOrBw)
        val gain = 10.0.pow(gainDb / 40.0)
        return when (kind) {
            0 -> Biquad((1 + cosine) * .5, -(1 + cosine), (1 + cosine) * .5,
                1 + alpha, -2 * cosine, 1 - alpha)
            1 -> Biquad((1 - cosine) * .5, 1 - cosine, (1 - cosine) * .5,
                1 + alpha, -2 * cosine, 1 - alpha)
            else -> Biquad(1 + alpha * gain, -2 * cosine, 1 - alpha * gain,
                1 + alpha / gain, -2 * cosine, 1 - alpha / gain)
        }
    }
    private data class Biquad(val b0: Double, val b1: Double, val b2: Double,
        val a0: Double, val a1: Double, val a2: Double) {
        fun response(hz: Double, rate: Int): Complex {
            val omega = 2 * PI * hz / rate
            val z = Complex(cos(omega), -sin(omega))
            val z2 = z * z
            return (Complex(b0) + z * b1 + z2 * b2) / (Complex(a0) + z * a1 + z2 * a2)
        }
    }

    companion object {
        private fun f(value: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", value)
        private fun db(value: Double) = 20 * log10(value.coerceAtLeast(1e-12))

        /** Literal float recurrence from Harmonic::UpdateCoeffs, including its weight normalization. */
        private fun harmonicPolynomial(weights: FloatArray): FloatArray {
            val input = FloatArray(11)
            val previous = FloatArray(11)
            val result = FloatArray(11)
            var total = 0f
            weights.forEach { total += abs(it) }
            val scale = if (total > 1f) 1f / total else 1f
            for (i in weights.indices) input[i + 1] = weights[i] * scale
            result[10] = input[10]
            for (i in 2..10) {
                for (j in 0 until i) {
                    val saved = previous[i - j]
                    previous[i - j] = result[i - j]
                    result[i - j] = result[i - j - 1] * 2f - saved
                }
                val saved = input[11 - i] - previous[0]
                previous[0] = result[0]
                result[0] = saved
            }
            for (i in 1..10) result[11 - i] = result[10 - i] - previous[11 - i]
            result[0] = input[0] / 2f - previous[0]
            return result
        }
        private fun firTaps(factor: Int): DoubleArray {
            val count = 31 * factor + 1
            val center = (count - 1) * .5
            val cutoff = .45 / factor
            val taps = DoubleArray(count) { i ->
                val time = i - center
                val sinc = if (time == 0.0) 2 * cutoff else sin(2 * PI * cutoff * time) / (PI * time)
                sinc * (.42 - .5 * cos(2 * PI * i / (count - 1)) + .08 * cos(4 * PI * i / (count - 1)))
            }
            val sum = taps.sum()
            return DoubleArray(count) { taps[it] / sum }
        }
        fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false) = AnalogDashboardModel(
            AnalogParameters.capture(values), rate.takeIf { it in 8000..768000 } ?: 48000,
            actualRate && rate in 8000..768000, AnalogParameters.moduleEnabled(values))
    }
}
