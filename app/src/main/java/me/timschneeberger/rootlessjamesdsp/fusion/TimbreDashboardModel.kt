package me.timschneeberger.rootlessjamesdsp.fusion

import java.util.Locale
import kotlin.math.*
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalResponse.Complex

/** Exact crossover sections with explicitly labelled unit-sine square-residual illustrations. */
class TimbreDashboardModel private constructor(val settings: TimbreParameters.Settings, val rate: Int,
    val actualRate: Boolean, val enabled: Boolean, val legacyDriveDb: Double) {
    val sampleRate get() = rate
    val moduleEnabled get() = enabled
    val professional = settings.professional
    val coreOn = professional && settings.coreOn
    val toneOn = professional && settings.toneOn
    private val changedCore = coreOn && (settings.drive != 0 || settings.amount != 1000 ||
        listOf(settings.band1, settings.band2, settings.band3, settings.band4).any { it != 1000 })
    private val changedTone = toneOn && (listOf(settings.cut1, settings.cut2, settings.cut3, settings.cut4, settings.cut5) !=
        listOf(600, 1900, 4400, 8000, 12000) || settings.colorHP != 0 || settings.colorLP != 0 ||
        settings.dcOn)
    val legacy = !(changedCore || changedTone || professional && (
        settings.mixOn && settings.mix != 1000 || settings.outputOn && settings.output != 0 ||
        settings.compOn && settings.comp != 1000 || settings.qualityOn))
    val firOn = !legacy && professional && settings.qualityOn
    val effectiveFactor = if (firOn) settings.factor else autoFactor(rate)
    val latencySamples = if (firOn) 31 else 0
    val drive = if (coreOn) 10.0.pow(settings.drive / 200.0) else 1.0
    val amount = if (coreOn) settings.amount / 1000.0 else 1.0
    val bandAmounts = if (coreOn) doubleArrayOf(settings.band1 / 1000.0, settings.band2 / 1000.0,
        settings.band3 / 1000.0, settings.band4 / 1000.0) else DoubleArray(4) { 1.0 }
    val compensation = drive.pow(-(if (professional && settings.compOn) settings.comp / 1000.0 else 1.0))
    val mix = if (professional && settings.mixOn) settings.mix / 1000.0 else 1.0
    val outputGain = if (professional && settings.outputOn) 10.0.pow(settings.output / 200.0) else 1.0
    val cuts: DoubleArray
    val highpassHz = if (!legacy && toneOn && settings.colorHP > 0) min(settings.colorHP.toDouble(), rate * .45) else 0.0
    val lowpassHz = if (!legacy && toneOn && settings.colorLP > 0) min(settings.colorLP.toDouble(), rate * .45) else 0.0
    val dcHz = if (!legacy && toneOn && settings.dcOn) settings.dcHz / 10.0 else 0.0
    val frequencies = DoubleArray(256) { 20.0 * (min(20000.0, rate * .45) / 20.0).pow(it / 255.0) }
    val componentDb = List(4) { DoubleArray(frequencies.size) }
    val squareResidualDb = List(4) { DoubleArray(frequencies.size) }
    val globalFilterDb = DoubleArray(frequencies.size)
    val linearReferenceDb = DoubleArray(frequencies.size)
    val maximumResidualDb: Double
    val bandNames = listOf("第一路", "第二路", "第三路", "第四路")
    val staticStatusLines get() = listOf(
        "${if (actualRate) "实际" else "参考"} $rate Hz · ${if (legacy) "原版四频段" else "专业四频段"}${if (enabled) "" else " · 关闭预览"}",
        "${f(cuts[0], 0)}/${f(cuts[1], 0)}/${f(cuts[2], 0)}/${f(cuts[3], 0)}/${f(cuts[4], 0)}Hz · $effectiveFactor× · 对齐$latencySamples 样本",
        "平方残差以1FS单音示意二次分量，非音乐THD · 原驱动保存 ${f(legacyDriveDb, 1)}dB")

    init {
        val baseCuts = doubleArrayOf(300.0, 950.0, 2200.0, 4000.0, 6000.0)
        // Legacy VTInit uses host-rate coefficients while operating at autoFactor times that rate.
        val rawCuts = if (toneOn && !legacy) doubleArrayOf(settings.cut1.toDouble(), settings.cut2.toDouble(),
            settings.cut3.toDouble(), settings.cut4.toDouble(), settings.cut5.toDouble())
            else DoubleArray(5) { baseCuts[it] * autoFactor(rate) }
        val scale = if (legacy) 1.0 else min(1.0, rate * .45 / rawCuts.last())
        cuts = DoubleArray(5) { rawCuts[it] * scale }
        val crossoverRate = rate.toDouble() * effectiveFactor
        val filters = cuts.map { Crossover(it, crossoverRate) }
        val fir = if (firOn) firTaps(effectiveFactor) else doubleArrayOf(1.0)
        val residualCoefficient = if (effectiveFactor > 1) .2 else .25
        frequencies.forEachIndexed { index, hz ->
            val components = crossover(filters, hz, crossoverRate)
            val source = crossover(filters, hz * .5, crossoverRate)
            val post = colorFilter(hz, crossoverRate)
            val up = firGain(fir, hz * .5, crossoverRate)
            val down = firGain(fir, hz, crossoverRate)
            globalFilterDb[index] = db(post.magnitude())
            val signed = components.withIndex().fold(Complex(0.0)) { total, (i, response) ->
                total + response * if (i % 2 == 1) -1.0 else 1.0
            }
            linearReferenceDb[index] = db(signed.magnitude() * drive * compensation * outputGain)
            for (band in 0..3) {
                componentDb[band][index] = db(components[band + 1].magnitude())
                // sin² = (1-cos(2ωt))/2. This estimates only the AC 2f component of a 1FS sine.
                val sourceAmplitude = source[band + 1].magnitude() * drive * up
                val residual = .5 * sourceAmplitude * sourceAmplitude * residualCoefficient * amount *
                    bandAmounts[band] * compensation * outputGain * mix * post.magnitude() * down
                squareResidualDb[band][index] = if (residual > 0) db(residual) else Double.NEGATIVE_INFINITY
            }
        }
        maximumResidualDb = squareResidualDb.maxOf { row -> row.filter { it.isFinite() }.maxOrNull() ?: 0.0 }
    }

    private fun colorFilter(hz: Double, fs: Double): Complex {
        var response = Complex(1.0)
        if (dcHz > 0) response *= firstOrder(hz, dcHz, fs, true)
        if (highpassHz > 0) response *= firstOrder(hz, highpassHz, fs, true)
        if (lowpassHz > 0) response *= firstOrder(hz, lowpassHz, fs, false)
        return response
    }
    private class Crossover(cut: Double, fs: Double) {
        private val wc2 = (2 * PI * cut).pow(2)
        private val k = 2 * PI * cut / tan(PI * cut / fs)
        private val k2 = k * k
        private val wck2 = 2 * (2 * PI * cut) * k
        private val divisor = k2 + wc2 + wck2
        private val b1 = (-2 * k2 + 2 * wc2) / divisor
        private val b2 = (-wck2 + k2 + wc2) / divisor
        fun response(hz: Double, fs: Double, kind: Int): Complex {
            val omega = 2 * PI * hz / fs
            val z = Complex(cos(omega), -sin(omega)); val z2 = z * z
            val denominator = Complex(1.0) + z * b1 + z2 * b2
            val numerator = when (kind) {
                0 -> (Complex(1.0) + z * 2.0 + z2) * (wc2 / divisor)
                1 -> (Complex(1.0) - z * 2.0 + z2) * (k2 / divisor)
                else -> (Complex(wc2 - k2) + z * (2 * (wc2 + k2)) + z2 * (wc2 - k2)) / Complex(divisor)
            }
            return numerator / denominator
        }
    }
    private fun crossover(filters: List<Crossover>, hz: Double, fs: Double): List<Complex> {
        val low = filters.map { it.response(hz, fs, 0) }
        val high = filters.map { it.response(hz, fs, 1) }
        val ap = filters.map { it.response(hz, fs, 2) }
        return listOf(low[0] * ap[1] * ap[2] * ap[3] * ap[4],
            high[0] * low[1] * ap[2] * ap[3] * ap[4],
            high[0] * high[1] * low[2] * ap[3] * ap[4],
            high[0] * high[1] * high[2] * low[3] * ap[4],
            high[0] * high[1] * high[2] * high[3] * low[4],
            high[0] * high[1] * high[2] * high[3] * high[4])
    }
    companion object {
        private fun autoFactor(rate: Int) = when { rate >= 65000 -> 1; rate >= 30000 -> 2; rate >= 20000 -> 3; rate >= 14000 -> 4; else -> 5 }
        private fun f(value: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", value)
        private fun db(value: Double) = 20 * log10(value.coerceAtLeast(1e-12))
        private fun firstOrder(hz: Double, cutoff: Double, fs: Double, high: Boolean): Complex {
            val k = tan(PI * cutoff.coerceIn(.1, fs * .45) / fs)
            val pole = (1 - k) / (1 + k)
            val z = Complex(cos(2 * PI * hz / fs), -sin(2 * PI * hz / fs))
            val low = (Complex(1.0) + z) * ((1 - pole) * .5) / (Complex(1.0) - z * pole)
            return if (high) Complex(1.0) - low else low
        }
        private fun firTaps(factor: Int): DoubleArray {
            val count = 31 * factor + 1; val center = (count - 1) * .5; val cutoff = .45 / factor
            val taps = DoubleArray(count) { i ->
                val time = i - center
                val sinc = if (time == 0.0) 2 * cutoff else sin(2 * PI * cutoff * time) / (PI * time)
                sinc * (.42 - .5 * cos(2 * PI * i / (count - 1)) + .08 * cos(4 * PI * i / (count - 1)))
            }
            val sum = taps.sum(); return DoubleArray(count) { taps[it] / sum }
        }
        private fun firGain(taps: DoubleArray, hz: Double, fs: Double): Double {
            val center = (taps.size - 1) * .5
            return abs(taps.indices.sumOf { taps[it] * cos(2 * PI * hz * (it - center) / fs) })
        }
        fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false) = TimbreDashboardModel(
            TimbreParameters.capture(values), rate.takeIf { it in 8000..768000 } ?: 48000,
            actualRate && rate in 8000..768000, TimbreParameters.moduleEnabled(values), TimbreParameters.legacy(values).toDouble())
    }
}
