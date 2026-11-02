package me.timschneeberger.rootlessjamesdsp.fusion

import java.util.Locale
import kotlin.math.*

/** Read-only steady settings from TubeSimulator.cpp; no audio state, parameter writes or auditions. */
class TubeDashboardModel private constructor(val settings: TubeParameters.Settings, val rate: Int,
    val actualRate: Boolean, val enabled: Boolean) {
    val sampleRate get() = rate
    val moduleEnabled get() = enabled
    val professional = settings.professional
    val saturationOn = professional && settings.satOn
    val toneOn = professional && settings.toneOn
    val gain = if (saturationOn) 10.0.pow(settings.drive / 200.0) else 1.0
    val softness = if (saturationOn) settings.softness / 1000.0 else .5
    val bias = if (saturationOn) settings.asym / 2000.0 else 0.0
    val softenMix = if (toneOn) settings.softenMix / 1000.0 else 1.0
    val effectiveFactor = if (saturationOn && settings.qualityOn) settings.factor else 1
    val latencySamples = if (effectiveFactor > 1) 31 else 0
    val outputGain = if (professional && settings.outputOn) 10.0.pow(settings.output / 200.0) else 1.0
    val mix = if (professional && settings.mixOn) settings.mix / 1000.0 else 1.0
    private val softenPole = if (toneOn && settings.softenCut > 0) {
        val c = 2 - cos(2 * PI * min(settings.softenCut.toDouble(), rate * .45) / rate)
        c - sqrt(c * c - 1)
    } else .5
    val softenCutoffHz = if (toneOn && settings.softenCut > 0) min(settings.softenCut.toDouble(), rate * .45)
        else acos(.75) * rate / (2 * PI)
    val preHpHz = if (saturationOn && toneOn && settings.preHP > 0) min(settings.preHP.toDouble(), rate * .45) else 0.0
    val postLpHz = if (toneOn && settings.postLP > 0) min(settings.postLP.toDouble(), rate * .45) else 0.0
    val input = DoubleArray(257) { -1.5 + 3.0 * it / 256 }
    val hardTransfer = input.map { hard(it) }.toDoubleArray()
    val softTransfer = input.map { soft(it) }.toDoubleArray()
    val selectedTransfer = input.map { shape(it) }.toDoubleArray()
    val mixedTransfer = input.map { (it + mix * (shape(it) - it)) * outputGain }.toDoubleArray()
    val frequencies = DoubleArray(192) { 20.0 * (min(20000.0, rate * .45) / 20).pow(it / 191.0) }
    val softeningDb: DoubleArray
    val preResidualDb: DoubleArray
    val linearReferenceDb: DoubleArray
    val reconstructionDb: DoubleArray
    val staticStatusLines get() = listOf(
        "${if (actualRate) "实际" else "参考"} $rate Hz · ${if (professional) "专业电子管" else "原版柔化"}${if (enabled) "" else " · 关闭预览"}",
        "归一化驱动 ${format(if (saturationOn) settings.drive / 10.0 else 0.0, 1)}dB · 柔化 ${format(softenCutoffHz, 0)}Hz · 混合 ${format(mix * 100, 1)}%",
        "$effectiveFactor× · 对齐 $latencySamples 样本 · 静态内核非实测频响；音乐FFT不代表THD")

    init {
        val one = SurroundProfessionalResponse.Complex(1.0)
        val fir = if (effectiveFactor > 1) firTaps(effectiveFactor) else doubleArrayOf(1.0)
        val soft = DoubleArray(frequencies.size); val hp = DoubleArray(frequencies.size)
        val reference = DoubleArray(frequencies.size); val quality = DoubleArray(frequencies.size)
        frequencies.forEachIndexed { index, hz ->
            val z = SurroundProfessionalResponse.Complex(cos(2 * PI * hz / rate), -sin(2 * PI * hz / rate))
            val low = SurroundProfessionalResponse.Complex(1 - softenPole) / (one - z * softenPole)
            val base = one + (low - one) * softenMix
            val high = if (preHpHz > 0) firstOrder(hz, preHpHz, rate * effectiveFactor.toDouble(), true) else one
            val post = if (postLpHz > 0) firstOrder(hz, postLpHz, rate.toDouble(), false) else one
            val output = (one * (1 - mix) + base * post * mix) * outputGain
            soft[index] = db(base.magnitude()); hp[index] = db(high.magnitude()); reference[index] = db(output.magnitude())
            var real = 0.0; var imaginary = 0.0
            fir.forEachIndexed { n, tap ->
                val angle = -2 * PI * hz * n / (rate * effectiveFactor.toDouble())
                real += tap * cos(angle); imaginary += tap * sin(angle)
            }
            quality[index] = db(real * real + imaginary * imaginary)
        }
        softeningDb = soft; preResidualDb = hp; linearReferenceDb = reference; reconstructionDb = quality
    }
    /** Exact normalized instantaneous hard/soft formula, before memory and residual DC blocking. */
    fun hard(x: Double): Double = if (saturationOn) ((gain * x + bias).coerceIn(-1.0, 1.0) - bias) / gain else x
    fun soft(x: Double): Double {
        if (!saturationOn) return x
        val b = tanh(bias)
        return (tanh(gain * x + bias) - b) / (gain * (1 - b * b))
    }
    fun shape(x: Double) = if (!saturationOn) x else hard(x) + softness * (soft(x) - hard(x))
    fun mixedShape(x: Double) = (x + mix * (shape(x) - x)) * outputGain
    private fun firstOrder(hz: Double, cutoff: Double, fs: Double, high: Boolean): SurroundProfessionalResponse.Complex {
        val k = tan(PI * cutoff.coerceIn(1.0, fs * .45) / fs)
        val pole = (1 - k) / (1 + k)
        val z = SurroundProfessionalResponse.Complex(cos(2 * PI * hz / fs), -sin(2 * PI * hz / fs))
        val one = SurroundProfessionalResponse.Complex(1.0)
        val low = (one + z) * ((1 - pole) * .5) / (one - z * pole)
        return if (high) one - low else low
    }
    companion object {
        private fun db(value: Double) = 20 * log10(value.coerceAtLeast(1e-12))
        private fun format(value: Double, digits: Int = 2) = String.format(Locale.ROOT, "%.${digits}f", value)
        /** The original Blackman-window FIR used only by the nonlinear residual's up/down branch. */
        private fun firTaps(factor: Int): DoubleArray {
            val n = 31 * factor + 1; val center = (n - 1) * .5; val cutoff = .45 / factor
            val taps = DoubleArray(n) { i ->
                val t = i - center
                val sinc = if (t == 0.0) 2 * cutoff else sin(2 * PI * cutoff * t) / (PI * t)
                sinc * (.42 - .5 * cos(2 * PI * i / (n - 1)) + .08 * cos(4 * PI * i / (n - 1)))
            }
            val sum = taps.sum(); return taps.map { it / sum }.toDoubleArray()
        }
        fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false) = TubeDashboardModel(
            TubeParameters.capture(values), rate.takeIf { it in 8000..768000 } ?: 48000,
            actualRate && rate in 8000..768000, TubeParameters.moduleEnabled(values))
    }
}
