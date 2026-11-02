package me.timschneeberger.rootlessjamesdsp.fusion

import java.util.Locale
import kotlin.math.*

/** One real exciter band plus the existing Chebyshev controls. This creates no extra DSP bands. */
class SpectrumDashboardModel private constructor(values: Map<String, *>, val rate: Int, val actualRate: Boolean) {
    val sampleRate get() = rate
    val professional = true
    val enabled = values["v_spectrum"] == true
    val moduleEnabled get() = enabled
    private val p = HighFrequencyParameters
    private val a = HighFrequencyAdvancedParameters
    val amountPercent = p.value(values, p.spectrumAmount) / 10.0
    val branchGain = (p.value(values, p.spectrumAmount) / 1000f).toDouble()
    val hpHz = p.value(values, p.spectrumFrequency).toDouble().coerceIn(1.0, rate * .5 - 100)
    val hpQ = (p.value(values, p.highpassQ) / 1000f).toDouble()
    val automaticLowpass = p.automaticLowpass(values)
    val lpHz = if (automaticLowpass) rate * .5 - 2000 else p.value(values, p.lowpassFrequency).toDouble().coerceIn(1.0, rate * .5 - 100)
    val lpQ = (p.value(values, p.lowpassQ) / 1000f).toDouble()
    val outputDb = p.value(values, p.spectrumOutput) / 10.0
    val outputGain = 10.0.pow(outputDb / 20).toFloat().toDouble()
    val driveDb = a.value(values, a.drive) / 10.0
    val drive = 10.0.pow(driveDb / 20).toFloat().toDouble()
    val evenMix = (a.value(values, a.evenMix) / 100f).toDouble()
    val highOrder = (a.value(values, a.highOrder) / 100f).toDouble()
    val effectiveFactor = a.oversampling(values)
    val legacyPath = a.value(values, a.drive) == 0 && a.value(values, a.evenMix) == 0 &&
        a.value(values, a.highOrder) == 100 && effectiveFactor == 1
    val latencySamples = if (effectiveFactor > 1) 32 else 0
    val probeHz = 1000.0
    val weights = FloatArray(10) { i ->
        when (i) { 0 -> .02f; 1 -> .02f * evenMix.toFloat(); else -> .02f * highOrder.toFloat() * if (i % 2 == 1) evenMix.toFloat() else 1f }
    }
    private val polynomial = harmonicPolynomial(weights)
    private val hp = Biquad.make(true, hpHz, hpQ, rate)
    private val lp = Biquad.make(false, lpHz, lpQ, rate)
    private val taps = firTaps(effectiveFactor)
    // The parameter graph must include the effective LP edge, including settings above 20 kHz.
    // This only changes the display sampling grid, never the native audio filters.
    val frequencies = DoubleArray(192) { 20.0 * ((rate * .5 - 100) / 20).pow(it / 191.0) }
    val workEnvelopeDb = frequencies.map { db((hp.response(it, rate) * lp.response(it, rate)).magnitude() * branchGain * outputGain) }.toDoubleArray()
    val highpassDb = frequencies.map { db(hp.response(it, rate).magnitude()) }.toDoubleArray()
    val lowpassDb = frequencies.map { db(lp.response(it, rate).magnitude() * outputGain) }.toDoubleArray()
    val harmonicFrequencies = DoubleArray(10) { probeHz * (it + 1) }
    /** 1 FS / 1 kHz cosine at the shaping-stage input, not the module input or music spectrum. */
    val harmonicPeakDb: DoubleArray
    val staticStatusLines get() = listOf(
        "${if (actualRate) "实际" else "参考"} $rate Hz · 单支路高频激励${if (enabled) "" else " · 关闭预览"}",
        "HP ${format(hpHz, 0)}Hz · LP ${format(lpHz, 0)}Hz${if (automaticLowpass) "（Fs/2−2k）" else ""} · ${format(amountPercent, 1)}%",
        "$effectiveFactor× / 对齐${latencySamples}样本 · 梳状为单音染色模型；实时wet为纯激励支路")
    init {
        val preAmplitude = firMagnitude(probeHz)
        val samples = DoubleArray(PROBE_SIZE) { i ->
            var v = cos(2 * PI * i / PROBE_SIZE) * preAmplitude
            if (!legacyPath) v = (v * drive / (1 + max(0.0, drive - 1) * abs(v))).coerceIn(-1.0, 1.0)
            var result = polynomial.last().toDouble()
            for (j in polynomial.lastIndex - 1 downTo 0) result = polynomial[j] + v * result
            result
        }
        harmonicPeakDb = DoubleArray(10) { i ->
            var re = 0.0; var im = 0.0
            for (n in 0 until PROBE_SIZE) { re += samples[n] * probeCos[i][n]; im += samples[n] * probeSin[i][n] }
            val hz = harmonicFrequencies[i]
            val fs = rate * effectiveFactor.toDouble()
            val z = Complex(cos(2 * PI * hz / fs), -sin(2 * PI * hz / fs))
            val dc = (Complex(1.0) - z) / (Complex(1.0) - z * .999.pow(1.0 / effectiveFactor))
            val amplitude = hypot(re, im) * 2 / PROBE_SIZE * dc.magnitude() * firMagnitude(hz) *
                lp.response(hz.coerceAtMost(rate * .499), rate).magnitude() * branchGain * outputGain
            if (hz >= rate * .5 || amplitude < 1e-12) Double.NEGATIVE_INFINITY else db(amplitude)
        }
    }
    private fun firMagnitude(hz: Double): Double {
        if (effectiveFactor == 1) return 1.0
        var re = 0.0; var im = 0.0
        taps.forEachIndexed { i, v -> val angle = -2 * PI * hz * i / (rate * effectiveFactor.toDouble()); re += v * cos(angle); im += v * sin(angle) }
        return hypot(re, im)
    }
    private data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a0: Double, val a1: Double, val a2: Double) {
        fun response(hz: Double, rate: Int): Complex {
            val z = Complex(cos(2 * PI * hz / rate), -sin(2 * PI * hz / rate)); val z2 = z * z
            return (Complex(b0) + z * b1 + z2 * b2) / (Complex(a0) + z * a1 + z2 * a2)
        }
        companion object {
            fun make(high: Boolean, cutoff: Double, q: Double, rate: Int): Biquad {
                val w = 2 * PI * cutoff / rate; val c = cos(w); val alpha = sin(w) / (2 * q)
                val b = (if (high) 1 + c else 1 - c) * .5
                return Biquad(b, if (high) -2 * b else 2 * b, b, 1 + alpha, -2 * c, 1 - alpha)
            }
        }
    }
    private data class Complex(val r: Double, val i: Double = 0.0) {
        operator fun plus(v: Complex) = Complex(r + v.r, i + v.i)
        operator fun minus(v: Complex) = Complex(r - v.r, i - v.i)
        operator fun times(v: Double) = Complex(r * v, i * v)
        operator fun times(v: Complex) = Complex(r * v.r - i * v.i, r * v.i + i * v.r)
        operator fun div(v: Complex): Complex { val d = v.r * v.r + v.i * v.i; return Complex((r * v.r + i * v.i) / d, (i * v.r - r * v.i) / d) }
        fun magnitude() = hypot(r, i)
    }
    companion object {
        private const val PROBE_SIZE = 512
        private val probeCos = Array(10) { i -> DoubleArray(PROBE_SIZE) { cos(2 * PI * (i + 1) * it / PROBE_SIZE) } }
        private val probeSin = Array(10) { i -> DoubleArray(PROBE_SIZE) { sin(2 * PI * (i + 1) * it / PROBE_SIZE) } }
        private fun db(value: Double) = if (value > 0) 20 * log10(value) else Double.NEGATIVE_INFINITY
        private fun format(value: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", value)
        private fun harmonicPolynomial(weights: FloatArray): FloatArray {
            val input = FloatArray(11); val previous = FloatArray(11); val result = FloatArray(11)
            var sum = 0f; weights.forEach { sum += abs(it) }; val scale = if (sum > 1f) 1f / sum else 1f
            weights.forEachIndexed { i, v -> input[i + 1] = v * scale }; result[10] = input[10]
            for (i in 2..10) {
                for (j in 0 until i) { val saved = previous[i - j]; previous[i - j] = result[i - j]; result[i - j] = result[i - j - 1] * 2f - saved }
                val saved = input[11 - i] - previous[0]; previous[0] = result[0]; result[0] = saved
            }
            for (i in 1..10) result[11 - i] = result[10 - i] - previous[11 - i]
            result[0] = input[0] / 2f - previous[0]; return result
        }
        private fun firTaps(factor: Int): DoubleArray {
            if (factor == 1) return doubleArrayOf(1.0)
            val n = 32 * factor + 1; val center = (n - 1) * .5; val cutoff = .45 / factor
            val taps = DoubleArray(n) { i ->
                val t = i - center; val sinc = if (t == 0.0) 2 * cutoff else sin(2 * PI * cutoff * t) / (PI * t)
                sinc * (.42 - .5 * cos(2 * PI * i / (n - 1)) + .08 * cos(4 * PI * i / (n - 1)))
            }
            val sum = taps.sum(); return taps.map { it / sum }.toDoubleArray()
        }
        fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false) = SpectrumDashboardModel(
            values.toMap(), rate.takeIf { it in 8000..768000 } ?: 48000, actualRate && rate in 8000..768000)
    }
}
