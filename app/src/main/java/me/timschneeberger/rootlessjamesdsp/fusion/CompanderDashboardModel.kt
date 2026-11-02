package me.timschneeberger.rootlessjamesdsp.fusion

import java.util.Locale
import kotlin.math.*

/** Read-only models of Compander.cpp and dynamic.c. No saved parameter or DSP state is changed. */
class CompanderDashboardModel private constructor(
    val settings: CompanderParameters.Settings,
    val rate: Int,
    val actualRate: Boolean,
    val enabled: Boolean,
    val validFoundation: Boolean,
) {
    val moduleEnabled get() = enabled
    val sampleRate get() = rate
    val professional = settings.professional
    val standard = professional && settings.engine == 1
    val coreOn = professional && settings.coreOn
    val detectOn = professional && settings.detectOn
    val timeOn = professional && settings.timeOn
    val limitsOn = professional && settings.limitsOn
    val transform = settings.legacyTransform.toIntOrNull()?.coerceIn(0, 3) ?: 0
    val transformName = listOf("STFT", "CWT", "CWT/STFT", "Gammatone")[transform]
    val typeName = listOf("向下压缩", "向上压缩", "向下扩展", "噪声门")[settings.type.coerceIn(0, 3)]
    val exactFrequency = !standard && coreOn && settings.exactFrequency
    private val foundation = CompanderParameters.parseCurve(settings.legacyCurve)
        ?: requireNotNull(CompanderParameters.parseCurve(CompanderParameters.DEFAULT_CURVE))
    val nodes: DoubleArray = DoubleArray(7) { i ->
        foundation.frequencies[i].toFloat().toDouble() +
            if (coreOn && !standard) settings.freqDeltas[i] / 1000.0 else 0.0
    }.apply {
        // Native resets untouched, non-exact legacy nodes after deriving the ordered/clamped set.
        if (standard || exactFrequency || coreOn && settings.freqDeltas.any { it != 0 }) {
            val top = min(23999.0, rate * .45)
            indices.forEach { i -> this[i] = this[i].coerceIn(1.0 + i, top - (6 - i)) }
            for (i in 1..lastIndex) this[i] = max(this[i], this[i - 1] + .001)
        }
    }
    val coefficients = DoubleArray(7) { i ->
        (foundation.coefficients[i].toFloat().toDouble() +
            if (coreOn && !standard) settings.coefficientDeltas[i] / 1000.0 else 0.0).coerceIn(-1.2, 1.2)
    }
    /** Legacy FFT samples the saved PCHIP at f/4; Gammatone and exact mode use f. */
    val frequencyScale = if (!standard && transform < 3 && !exactFrequency) 4.0 else 1.0
    val nodeFrequencies = DoubleArray(7) { nodes[it] * frequencyScale }
    val amount = if (coreOn && !standard) settings.amount / 1000.0 else 1.0
    val mix = if (professional && settings.mixOn) settings.mix / 1000.0 else 1.0
    val outputDb = if (professional && settings.outputOn) settings.output / 10.0 else 0.0
    val backgroundMs = (settings.legacyTime.toDouble() * 1000.0 +
        if (timeOn && !standard) settings.backgroundDelta / 10.0 else 0.0).coerceIn(1.0, 4000.0)
    val attackMs = if (timeOn) settings.attack / 10.0 else 10.0
    val holdMs = if (timeOn) settings.hold.toDouble() else 0.0
    val releaseMs = if (timeOn) settings.release.toDouble() else 250.0
    val thresholdDb = settings.threshold / 10.0
    val ratio = settings.ratio / 100.0
    val kneeDb = if (standard) settings.standardKnee / 10.0 else if (detectOn) settings.knee / 10.0 else 0.0
    val relativeDb = if (detectOn && !standard) settings.relative / 10.0 else 0.0
    val weakFloorDb = if (detectOn) settings.weakFloor / 10.0 else -100.0
    val maxBoostDb = if (limitsOn) settings.maxBoost / 10.0 else 60.0
    val maxCutDb = if (limitsOn) settings.maxCut / 10.0 else if (standard && settings.type == 3) 60.0 else 120.0
    val hysteresisDb = settings.hysteresis / 10.0
    val backgroundReferenceDb = -36.0
    private val range: Pair<Double, Double> = run {
        var low = if (detectOn && settings.rangeLow > 0) min(settings.rangeLow.toDouble(), rate * .40) else 0.0
        val high = if (detectOn && settings.rangeHigh > 0) min(settings.rangeHigh.toDouble(), rate * .45) else 0.0
        if (low > 0 && high > 0 && low >= high) low = high * .8
        low to high
    }
    val rangeLowHz = range.first
    val rangeHighHz = range.second
    private val sidechain: Pair<Double, Double> = run {
        var low = if (standard && settings.sidechainOn && settings.sideHP > 0) min(settings.sideHP.toDouble(), rate * .40) else 0.0
        val high = if (standard && settings.sidechainOn && settings.sideLP > 0) min(settings.sideLP.toDouble(), rate * .45) else 0.0
        if (low > 0 && high > 0 && low >= high) low = high * .8
        low to high
    }
    val sideHighpassHz = sidechain.first
    val sideLowpassHz = sidechain.second
    val lookaheadMs = if (standard && settings.lookaheadOn) settings.lookahead / 10.0 else 0.0
    val nominalDelaySamples = if (standard) (lookaheadMs * rate / 1000.0).roundToInt()
        else listOf(1792, 3584, 2048, 0)[transform]
    val updateMs = if (standard || transform == 3) 1000.0 / rate
        else (if (transform == 0) 256.0 else 512.0) * 1000.0 / rate
    val detectorName = if (standard) if (settings.detector == 1) "${settings.rmsWindow}ms RMS" else "峰值"
        else if (detectOn && settings.linked == 1) "左右较强频谱" else "左右平均频谱"
    private val interpolator = CoefficientPchip(nodes, coefficients)
    val frequencies = DoubleArray(256) { 20.0 * (min(20000.0, rate * .49) / 20.0).pow(it / 255.0) }
    val coefficientCurve = DoubleArray(frequencies.size) { coefficientAt(frequencies[it]) }
    // Continuous-frequency reference; the original FFT/Gammatone grid and detector history remain native.
    private val headroomDb = max(10.0, coefficientCurve.maxOrNull()?.times(12.0) ?: 10.0)
    val inputLevels = DoubleArray(161) { -80.0 + it * .5 }
    val ioCurves = List(7) { i -> DoubleArray(inputLevels.size) { j ->
        outputForGain(inputLevels[j], targetGain(inputLevels[j], nodeFrequencies[i], coefficients[i]))
    } }
    val gateClosingCurve = DoubleArray(inputLevels.size) { j ->
        outputForGain(inputLevels[j], standardTarget(inputLevels[j], true))
    }
    val referenceExcessDb = doubleArrayOf(6.0, 12.0, 24.0)
    val actionCurves = referenceExcessDb.map { excess -> DoubleArray(frequencies.size) { i ->
        val level = if (standard) if (settings.type == 1 || settings.type == 2 || settings.type == 3)
            thresholdDb - excess else thresholdDb + excess else backgroundReferenceDb + excess
        responseAt(level.coerceIn(-100.0, 0.0), frequencies[i])
    } }
    val rangeMask = DoubleArray(frequencies.size) { i -> if (standard) rangeResponse(frequencies[i]).magnitude()
        else if (inRange(frequencies[i])) 1.0 else 0.0 }
    val sidechainDb = DoubleArray(frequencies.size) { i -> db(sideResponse(frequencies[i]).magnitude()) }
    val timeMs = DoubleArray(160) { i -> i / 159.0 * max(500.0, max(backgroundMs * 3.0, attackMs * 4 + holdMs + releaseMs * 4)) }
    val attackEnvelope = DoubleArray(timeMs.size) { 1 - exp(-timeMs[it] / attackMs) }
    val releaseEnvelope = DoubleArray(timeMs.size) { if (timeMs[it] < holdMs) 1.0 else exp(-(timeMs[it] - holdMs) / releaseMs) }
    val backgroundEnvelope = DoubleArray(timeMs.size) { 1 - exp(-timeMs[it] / backgroundMs) }
    val staticStatusLines = listOf(
        "${rate} Hz${if (actualRate) " 实际采样率" else " 参考采样率"} · ${if (standard) "标准$typeName" else "原版$transformName"} · ${if (enabled) "已启用" else "已关闭（模型保留）"}",
        if (standard) "${format(thresholdDb)} dBFS · ${format(ratio, 2)}:1 · $detectorName · 前瞻${format(lookaheadMs)}ms"
        else "7点为频率动态系数 · ${if (frequencyScale == 4.0) "原FFT f/4映射" else "实际Hz映射"} · 网格${settings.legacyGranularity.toInt()} · 背景${format(backgroundMs)}ms",
        if (!validFoundation) "原保存值格式异常；仅预览采用安全参考，不改写保存值"
        else "静态为检测器稳态参考；真实背景/平滑/频谱网格/门状态由原驱动监测",
    )

    fun coefficientAt(frequencyHz: Double): Double = interpolator.at(frequencyHz / frequencyScale).coerceIn(-1.2, 1.2)
    private fun inRange(hz: Double) = !detectOn || hz >= rangeLowHz && (rangeHighHz == 0.0 || hz <= rangeHighHz)
    fun targetGain(levelDb: Double, frequencyHz: Double, coefficient: Double = coefficientAt(frequencyHz)): Double {
        if (standard) return standardTarget(levelDb)
        var target = softPositive(levelDb - backgroundReferenceDb - relativeDb, kneeDb) * coefficient
        target = min(target, headroomDb)
        if (detectOn && (levelDb < weakFloorDb || !inRange(frequencyHz))) target = 0.0
        if (coreOn) target *= amount
        if (limitsOn) target = target.coerceIn(-maxCutDb, maxBoostDb)
        return target
    }
    fun standardTarget(levelDb: Double, wasGateOpen: Boolean = false): Double {
        if (!standard || !coreOn) return 0.0
        var gain = when (settings.type) {
            0 -> -(1 - 1 / ratio) * softPositive(levelDb - thresholdDb, kneeDb)
            1 -> if (levelDb <= -100 || detectOn && levelDb < weakFloorDb) 0.0
                else (1 - 1 / ratio) * softPositive(thresholdDb - levelDb, kneeDb)
            2 -> -(ratio - 1) * softPositive(thresholdDb - levelDb, kneeDb)
            else -> {
                val opens = if (wasGateOpen) levelDb >= thresholdDb - hysteresisDb else levelDb >= thresholdDb
                if (opens) 0.0 else if (kneeDb > 0) {
                    val t = ((thresholdDb - hysteresisDb + kneeDb * .5 - levelDb) / kneeDb).coerceIn(0.0, 1.0)
                    -maxCutDb * t * t * (3 - 2 * t)
                } else -maxCutDb
            }
        }
        if (limitsOn) gain = gain.coerceIn(-maxCutDb, maxBoostDb)
        return gain.coerceIn(-120.0, 60.0)
    }
    /** Reference output at a fixed single-frequency input; not a measured programme transfer. */
    fun responseAt(levelDb: Double, hz: Double): Double {
        val gain = targetGain(levelDb, hz)
        val delta = 10.0.pow(gain / 20.0) - 1
        val response = if (standard) Complex(1.0, 0.0) + rangeResponse(hz) * (mix * delta)
            else Complex(1 + mix * delta, 0.0)
        return db(response.magnitude()) + outputDb
    }
    fun outputForGain(levelDb: Double, gainDb: Double) = levelDb + db(1 + mix * (10.0.pow(gainDb / 20) - 1)) + outputDb
    private fun rangeResponse(hz: Double): Complex {
        var response = Complex(1.0, 0.0)
        if (rangeLowHz > 0) response *= poleResponse(hz, rangeLowHz, true)
        if (rangeHighHz > 0) response *= poleResponse(hz, rangeHighHz, false)
        return response
    }
    private fun sideResponse(hz: Double): Complex {
        var response = rangeResponse(hz)
        if (sideHighpassHz > 0) response *= poleResponse(hz, sideHighpassHz, true)
        if (sideLowpassHz > 0) response *= poleResponse(hz, sideLowpassHz, false)
        return response
    }
    private fun poleResponse(hz: Double, cutoff: Double, highpass: Boolean): Complex {
        val k = tan(PI * cutoff.coerceIn(.01, rate * .45) / rate)
        val pole = (1 - k) / (1 + k)
        val z = Complex(cos(-2 * PI * hz / rate), sin(-2 * PI * hz / rate))
        val low = (Complex(1.0, 0.0) + z) * ((1 - pole) * .5) / (Complex(1.0, 0.0) - z * pole)
        return if (highpass) Complex(1.0, 0.0) - low else low
    }
    private data class Complex(val re: Double, val im: Double) {
        operator fun plus(o: Complex) = Complex(re + o.re, im + o.im)
        operator fun minus(o: Complex) = Complex(re - o.re, im - o.im)
        operator fun times(v: Double) = Complex(re * v, im * v)
        operator fun times(o: Complex) = Complex(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun div(o: Complex): Complex { val d = max(1e-30, o.re * o.re + o.im * o.im); return Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d) }
        fun magnitude() = hypot(re, im)
    }
    /** Same linear-Hz PCHIP slopes/endpoints as interpolation.c, not logarithmic spline knots. */
    private class CoefficientPchip(nodes: DoubleArray, coefficients: DoubleArray) {
        val x = doubleArrayOf(0.0) + nodes + doubleArrayOf(24000.0)
        val y = doubleArrayOf(coefficients.first()) + coefficients + doubleArrayOf(coefficients.last())
        val s = DoubleArray(x.size).apply {
            this[0] = (y[1] - y[0]) / (x[1] - x[0])
            this[lastIndex] = (y.last() - y[y.lastIndex - 1]) / (x.last() - x[x.lastIndex - 1])
            for (k in 1 until lastIndex) {
                val a = x[k] - x[k - 1]; val b = x[k + 1] - x[k]
                val da = (y[k] - y[k - 1]) / a; val db = (y[k + 1] - y[k]) / b
                this[k] = if (da == 0.0 || db == 0.0 || da * db < 0) 0.0
                    else (3 * (a + b)) / ((2 * b + a) / da + (b + 2 * a) / db)
            }
        }
        fun at(hz: Double): Double {
            val f = hz.coerceIn(0.0, 24000.0)
            var k = x.binarySearch(f); if (k >= 0) return y[k]
            k = (-k - 2).coerceIn(0, x.size - 2)
            val dx = x[k + 1] - x[k]; val t = (f - x[k]) / dx
            return (1 - t).pow(2) * (y[k] * (1 + 2 * t) + s[k] * (f - x[k])) +
                t * t * (y[k + 1] * (3 - 2 * t) + dx * s[k + 1] * (t - 1))
        }
    }
    companion object {
        fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false) = CompanderDashboardModel(
            CompanderParameters.capture(values), rate.coerceIn(8000, 768000), actualRate,
            CompanderParameters.moduleEnabled(values), CompanderParameters.validSavedFoundation(values))
        private fun softPositive(x: Double, knee: Double): Double = when {
            knee <= 0 -> max(0.0, x)
            x <= -knee * .5 -> 0.0
            x >= knee * .5 -> x
            else -> (x + knee * .5).pow(2) / (2 * knee)
        }
        private fun db(x: Double) = 20 * log10(max(1e-10, abs(x)))
        private fun format(v: Double, digits: Int = 1) = String.format(Locale.ROOT, "%.${digits}f", v)
    }
}
