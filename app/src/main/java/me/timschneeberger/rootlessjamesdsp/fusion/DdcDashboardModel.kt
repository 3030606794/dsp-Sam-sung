package me.timschneeberger.rootlessjamesdsp.fusion

import kotlin.math.*

/** Exact personal biquad layer from DdcProcessor.cpp, not a reconstruction of a selected VDC file. */
class DdcDashboardModel private constructor(val settings: DdcParameters.Settings, val rate: Int,
    val actualRate: Boolean, val enabled: Boolean, val filePath: String) {
    val sampleRate get() = rate
    val professional = settings.professional
    val moduleEnabled get() = enabled
    val foundationIncluded = false
    val fileLabel = filePath.replace('\\', '/').substringAfterLast('/').ifBlank { "未选择VDC" }
    val compensation = professional && settings.compensationOn
    val preampDb = if (professional && settings.preampOn) settings.preamp / 10.0 else 0.0
    val outputDb = if (professional && settings.outputOn) settings.output / 10.0 else 0.0
    val leftLevelDb = if (professional && settings.earOn) settings.leftLevel / 10.0 else 0.0
    val rightLevelDb = if (professional && settings.earOn) (if (settings.linked) settings.leftLevel else settings.rightLevel) / 10.0 else 0.0
    private val masterRatio = if (professional && settings.ratioOn) settings.ratio / 1000.0 else 1.0
    val leftRatio = masterRatio * if (professional && settings.earOn) settings.leftRatio / 1000.0 else 1.0
    val rightRatio = masterRatio * if (professional && settings.earOn) (if (settings.linked) settings.leftRatio else settings.rightRatio) / 1000.0 else 1.0
    val automaticHeadroom = professional && settings.autoHeadroomOn
    val marginDb = settings.margin / 10.0
    val frequencies = DoubleArray(256) { 20.0 * (min(20000.0, rate * .499) / 20).pow(it / 255.0) }
    data class Band(val name: String, val frequencyHz: Double, val gainDb: Double, val shape: Double,
        val peak: Boolean, val active: Boolean, val responseDb: DoubleArray)
    val bands: List<Band>
    val personalEqDb: DoubleArray
    val leftDb: DoubleArray
    val rightDb: DoubleArray
    val staticStatusLines get() = listOf(
        "${if (actualRate) "实际" else "参考"} $rate Hz · 个人2架式+2峰式${if (!enabled) " · 关闭预览" else ""}",
        "$fileLabel · 文件混合 左${(leftRatio * 100).roundToInt()}% / 右${(rightRatio * 100).roundToInt()}%",
        "图仅个人补偿与电平；未含原VDC/文件混合/自动余量，非耳机实测")
    init {
        fun band(name: String, hz: Int, gain: Int, shape: Double, high: Boolean?, on: Boolean): Band {
            val clamped = min(hz.toDouble(), rate * .43).roundToInt().toDouble()
            val coeff = if (high == null) Coeff.peak(rate, clamped, gain / 10.0, shape)
                else Coeff.shelf(rate, clamped, gain / 10.0, shape, high)
            return Band(name, clamped, gain / 10.0, shape, high == null, compensation && on,
                DoubleArray(frequencies.size) { db(coeff.amplitude(2 * PI * frequencies[it] / rate)) })
        }
        bands = listOf(
            band("低架", settings.lowFrequency, settings.lowGain, settings.lowSlope / 1000.0, false, settings.lowOn),
            band("高架", settings.highFrequency, settings.highGain, settings.highSlope / 1000.0, true, settings.highOn),
            band("峰1", settings.peak1Frequency, settings.peak1Gain, settings.peak1Q / 100.0, null, settings.peak1On),
            band("峰2", settings.peak2Frequency, settings.peak2Gain, settings.peak2Q / 100.0, null, settings.peak2On))
        personalEqDb = DoubleArray(frequencies.size) { index -> bands.filter { it.active }.sumOf { it.responseDb[index] } }
        leftDb = DoubleArray(frequencies.size) { personalEqDb[it] + preampDb + outputDb + leftLevelDb }
        rightDb = DoubleArray(frequencies.size) { personalEqDb[it] + preampDb + outputDb + rightLevelDb }
    }
    private data class Coeff(val b0: Double=1.0, val b1: Double=0.0, val b2: Double=0.0, val a1: Double=0.0, val a2: Double=0.0) {
        fun amplitude(omega: Double): Double {
            val c = cos(omega); val s = -sin(omega); val c2 = cos(2 * omega); val s2 = -sin(2 * omega)
            return hypot(b0 + b1 * c + b2 * c2, b1 * s + b2 * s2) /
                hypot(1 + a1 * c + a2 * c2, a1 * s + a2 * s2)
        }
        companion object {
            fun peak(fs: Int, hz: Double, gain: Double, q: Double): Coeff {
                if (gain == 0.0) return Coeff()
                val w = 2 * PI * min(hz, fs * .43) / fs; val a = 10.0.pow(gain / 40)
                val cs = cos(w); val al = sin(w) / (2 * q); val d = 1 + al / a
                return Coeff((1 + al * a) / d, -2 * cs / d, (1 - al * a) / d, -2 * cs / d, (1 - al / a) / d)
            }
            fun shelf(fs: Int, hz: Double, gain: Double, slope: Double, high: Boolean): Coeff {
                if (gain == 0.0) return Coeff()
                val w = 2 * PI * min(hz, fs * .43) / fs; val a = 10.0.pow(gain / 40)
                val cs = cos(w); val t = sin(w) * sqrt(a) * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
                if (high) {
                    val d = (a + 1) - (a - 1) * cs + t
                    return Coeff(a * ((a + 1) + (a - 1) * cs + t) / d, -2 * a * ((a - 1) + (a + 1) * cs) / d,
                        a * ((a + 1) + (a - 1) * cs - t) / d, 2 * ((a - 1) - (a + 1) * cs) / d, ((a + 1) - (a - 1) * cs - t) / d)
                }
                val d = (a + 1) + (a - 1) * cs + t
                return Coeff(a * ((a + 1) - (a - 1) * cs + t) / d, 2 * a * ((a - 1) - (a + 1) * cs) / d,
                    a * ((a + 1) - (a - 1) * cs - t) / d, -2 * ((a - 1) + (a + 1) * cs) / d, ((a + 1) + (a - 1) * cs - t) / d)
            }
        }
    }
    companion object {
        private fun db(value: Double) = if (value > 0) 20 * log10(value) else Double.NEGATIVE_INFINITY
        fun from(values: Map<String,*>, rate: Int=48000, actualRate: Boolean=false): DdcDashboardModel {
            val fs = rate.takeIf { it in 8000..768000 } ?: 48000
            return DdcDashboardModel(DdcParameters.capture(values), fs, actualRate && rate == fs,
                DdcParameters.moduleEnabled(values), values[DdcParameters.FILE] as? String ?: "")
        }
    }
}
