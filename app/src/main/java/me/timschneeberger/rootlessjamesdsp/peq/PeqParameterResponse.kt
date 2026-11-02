package me.timschneeberger.rootlessjamesdsp.peq

import kotlin.math.*

/** Quantized, single-band parameter models using PeqResponse's bilinear RBJ convention. */
object PeqParameterResponse {
    data class Bandwidth(
        val lowerHz: Double?, val upperHz: Double?, val thresholdDb: Double?,
        val label: String, val complete: Boolean
    )
    data class Sample(val db: Double, val phaseDegrees: Double, val delayMs: Double)
    data class Impulse(
        val minima: DoubleArray, val maxima: DoubleArray, val durationMs: Double,
        val peak: Double, val tailTruncated: Boolean, val finite: Boolean,
        // Raw unit-impulse samples are retained separately from the display envelopes.
        // NaN marks any uncomputed tail; validSampleCount never includes that tail.
        val samples: DoubleArray = doubleArrayOf(), val samplePeriodMs: Double = Double.NaN,
        val sampleRate: Double = Double.NaN, val validSampleCount: Int = samples.size
    )
    data class Preview(
        val band: PeqBand, val rate: Double, val minimumHz: Double, val maximumHz: Double,
        val frequencies: DoubleArray, val amplitudes: DoubleArray,
        val phases: DoubleArray, val delays: DoubleArray,
        val bandwidth: Bandwidth, val impulse: Impulse
    )

    fun prepare(original: PeqBand, rate: Double): Model? {
        if (!rate.isFinite() || rate < 1000.0) return null
        val band = runCatching { PeqModel.validateBand(PeqProtocol.quantized(original)) }.getOrNull() ?: return null
        if (band.frequency <= 0.0 || band.frequency > rate / 2.0) return null
        return Model(band, rate)
    }

    /** Null on one side means the applicable passband continues to DC or Nyquist. */
    fun bandwidth(band: PeqBand, rate: Double): Bandwidth = prepare(band, rate)?.bandwidth()
        ?: Bandwidth(null, null, null, "当前频率无有效滤波模型", false)

    fun preview(band: PeqBand, rate: Double, cancelled: () -> Boolean = { false }): Preview? {
        val model = prepare(band, rate) ?: return null
        if (cancelled()) return null
        val lower = max(1.0, model.band.frequency / 4.0)
        val upper = minOf(rate / 2.0, PeqModel.MAX_FREQUENCY, max(lower * 1.001, model.band.frequency * 4.0))
        if (lower >= upper) return null
        val enabledBand = model.band.copy(enabled = true)
        val state = PeqState(listOf(enabledBand), graphMinFrequency = lower.coerceAtMost(1000.0),
            graphMaxFrequency = upper.coerceAtMost(PeqModel.MAX_FREQUENCY))
        // Reuse the original amplitude implementation and its narrow-Q adaptive points.
        val curve = PeqResponse.adaptiveCurve(state, points = 320, sampleRate = rate,
            frequencyMin = lower, frequencyMax = upper)
        val frequencies = DoubleArray(curve.size)
        val amplitudes = DoubleArray(curve.size)
        val phases = DoubleArray(curve.size)
        val delays = DoubleArray(curve.size)
        curve.forEachIndexed { index, point ->
            if (index % 32 == 0 && cancelled()) return null
            val response = model.sample(point.frequency)
            frequencies[index] = point.frequency
            amplitudes[index] = point.db
            phases[index] = response.phaseDegrees
            delays[index] = response.delayMs
        }
        val impulse = model.impulse(cancelled) ?: return null
        return Preview(model.band, rate, lower, upper, frequencies, amplitudes, phases, delays,
            model.bandwidth(), impulse)
    }

    class Model internal constructor(val band: PeqBand, val rate: Double) {
        private val center = PeqResponse.coefficientFrequency(band.frequency, rate)
        private val warped = tan(PI * center / rate)
        private val a = 10.0.pow(band.gain / 40.0)
        private val q = PeqResponse.coefficientQ(band.type, band.q, band.gain)
        private val outputDb = if (band.type.isUnityAtZeroGain) 0.0 else band.gain
        private val outputGain = 10.0.pow(outputDb / 20.0)
        private val unity = band.type.isUnityAtZeroGain && band.gain == 0.0
        // Analog numerator and denominator: c2*s² + c1*s + c0, s=j*tan(w/2)/warped.
        private val n2: Double
        private val n1: Double
        private val n0: Double
        private val d2: Double
        private val d1: Double
        private val d0: Double

        init {
            val rootA = sqrt(a)
            val coefficients = when (band.type) {
                PeqFilterType.PEAK -> doubleArrayOf(1.0, a / q, 1.0, 1.0, 1.0 / (a * q), 1.0)
                PeqFilterType.LOW_SHELF -> doubleArrayOf(a, a * rootA / q, a * a, a, rootA / q, 1.0)
                PeqFilterType.HIGH_SHELF -> doubleArrayOf(a * a, a * rootA / q, a, 1.0, rootA / q, a)
                PeqFilterType.LOW_PASS -> doubleArrayOf(0.0, 0.0, 1.0, 1.0, 1.0 / q, 1.0)
                PeqFilterType.HIGH_PASS -> doubleArrayOf(1.0, 0.0, 0.0, 1.0, 1.0 / q, 1.0)
                PeqFilterType.NOTCH -> doubleArrayOf(1.0, 0.0, 1.0, 1.0, 1.0 / q, 1.0)
                PeqFilterType.BAND_PASS -> doubleArrayOf(0.0, 1.0 / q, 0.0, 1.0, 1.0 / q, 1.0)
            }
            n2 = coefficients[0]; n1 = coefficients[1]; n0 = coefficients[2]
            d2 = coefficients[3]; d1 = coefficients[4]; d0 = coefficients[5]
        }

        fun db(frequency: Double): Double = sample(frequency).db

        fun sample(frequency: Double): Sample {
            if (!frequency.isFinite() || frequency < 0.0 || frequency > rate / 2.0)
                return Sample(Double.NaN, Double.NaN, Double.NaN)
            if (unity) return Sample(0.0, 0.0, 0.0)
            val tangent = if (frequency == rate / 2.0) Double.POSITIVE_INFINITY else tan(PI * frequency / rate)
            val x = tangent / warped
            val reciprocal = x > 1.0
            val u = if (reciprocal) 1.0 / x else x
            val u2 = u * u
            val nr = if (reciprocal) n0 * u2 - n2 else n0 - n2 * u2
            val dr = if (reciprocal) d0 * u2 - d2 else d0 - d2 * u2
            val ni = n1 * u
            val di = d1 * u
            val np = nr * nr + ni * ni
            val dp = dr * dr + di * di
            val magnitude = (10.0 * (log10(np.coerceAtLeast(1e-300)) -
                log10(dp.coerceAtLeast(1e-300))) + outputDb).coerceAtLeast(PeqResponse.FLOOR_DB)
            // Phase at a transfer zero is undefined; do not draw a false jump or delay spike.
            if (np <= dp * 1e-12 || dp <= 1e-290)
                return Sample(magnitude, Double.NaN, Double.NaN)
            val angle = atan2(ni, nr) - atan2(di, dr)
            val wrappedPhase = atan2(sin(angle), cos(angle)) * 180.0 / PI
            val nrDerivative = if (reciprocal) 2.0 * n0 * u else -2.0 * n2 * u
            val drDerivative = if (reciprocal) 2.0 * d0 * u else -2.0 * d2 * u
            val phaseDerivativeU = (nr * n1 - ni * nrDerivative) / np -
                (dr * d1 - di * drDerivative) / dp
            // Analytic complex derivative: no difference across wrapped ±180° phase.
            val derivativeOmega = if (reciprocal) {
                val cotangent = if (tangent.isInfinite()) 0.0 else 1.0 / tangent
                -.5 * warped * (1.0 + cotangent * cotangent)
            } else .5 * (1.0 + tangent * tangent) / warped
            val delay = -phaseDerivativeU * derivativeOmega * 1000.0 / rate
            return Sample(magnitude, wrappedPhase, delay.takeIf { it.isFinite() } ?: Double.NaN)
        }

        fun bandwidth(): Bandwidth {
            if (unity) return absent("零增益平直响应：无对应 −3 dB 区")
            return when (band.type) {
                PeqFilterType.PEAK -> if (band.gain > 0.0) {
                    if (band.gain <= 3.0) absent("峰值未高于通带 3 dB：无双边 −3 dB 区")
                    else paired(band.gain - 3.0, "峰顶 −3 dB 带宽")
                } else {
                    if (band.gain >= -3.0) absent("凹口不足 3 dB：无双边恢复区")
                    else paired(-3.0, "通带 −3 dB 凹口恢复区")
                }
                PeqFilterType.NOTCH -> paired(outputDb - 3.0, "通带 −3 dB 凹口恢复区")
                PeqFilterType.BAND_PASS -> paired(outputDb - 3.0, "峰顶 −3 dB 带宽")
                PeqFilterType.LOW_PASS -> single(outputDb - 3.0, true, "低通：通带 −3 dB 单边区")
                PeqFilterType.HIGH_PASS -> single(outputDb - 3.0, false, "高通：通带 −3 dB 单边区")
                PeqFilterType.LOW_SHELF, PeqFilterType.HIGH_SHELF -> {
                    val lowSide = (band.type == PeqFilterType.LOW_SHELF) == (band.gain > 0.0)
                    single(max(0.0, band.gain) - 3.0, lowSide, "棚架：高平台 −3 dB 单边区")
                }
            }
        }

        private fun paired(threshold: Double, label: String): Bandwidth {
            val left = logRoot(-40.0, 0.0, threshold)
            val right = logRoot(0.0, 40.0, threshold)
            if (left == null || right == null || left >= right)
                return absent("当前响应无对应双边 −3 dB 区", threshold)
            val lowHz = fromLogRatio(left)
            val highHz = fromLogRatio(right)
            if (lowHz >= highHz) return absent("−3 dB 区小于当前数值分辨率", threshold)
            return Bandwidth(lowHz, highHz, threshold, label, true)
        }

        private fun single(threshold: Double, lowSide: Boolean, label: String): Bandwidth {
            val crossings = powerCrossings(threshold)
            val crossing = if (lowSide) crossings.firstOrNull() else crossings.lastOrNull()
            if (crossing == null) return absent("当前响应无对应单边 −3 dB 区", threshold)
            return if (lowSide) Bandwidth(null, crossing, threshold, label, true)
                else Bandwidth(crossing, null, threshold, label, true)
        }

        private fun powerCrossings(threshold: Double): List<Double> {
            val target = 10.0.pow((threshold - outputDb) / 10.0)
            var aa = n2 * n2 - target * d2 * d2
            var bb = n1 * n1 - 2.0 * n0 * n2 - target * (d1 * d1 - 2.0 * d0 * d2)
            var cc = n0 * n0 - target * d0 * d0
            val scale = max(abs(aa), max(abs(bb), abs(cc)))
            if (!scale.isFinite() || scale == 0.0) return emptyList()
            aa /= scale; bb /= scale; cc /= scale
            val roots = ArrayList<Double>(2)
            if (abs(aa) < 1e-18) {
                if (abs(bb) > 1e-18) roots += -cc / bb
            } else {
                val discriminant = bb * bb - 4.0 * aa * cc
                if (discriminant <= 0.0) return emptyList()
                val term = -.5 * (bb + Math.copySign(sqrt(discriminant), bb))
                if (term != 0.0) { roots += term / aa; roots += cc / term }
                else roots += -bb / (2.0 * aa)
            }
            return roots.filter { it > 0.0 && it.isFinite() }.map {
                rate / PI * atan(warped * sqrt(it))
            }.filter { it > 0.0 && it < rate / 2.0 }.distinct().sorted()
        }

        private fun logRoot(left: Double, right: Double, target: Double): Double? {
            var lo = left
            var hi = right
            var lowValue = dbAtLogRatio(lo) - target
            val highValue = dbAtLogRatio(hi) - target
            if (!lowValue.isFinite() || !highValue.isFinite() || lowValue * highValue > 0.0) return null
            repeat(72) {
                val middle = (lo + hi) * .5
                val value = dbAtLogRatio(middle) - target
                if (value == 0.0) return middle
                if ((value >= 0.0) == (lowValue >= 0.0)) { lo = middle; lowValue = value } else hi = middle
            }
            return (lo + hi) * .5
        }

        private fun dbAtLogRatio(logRatio: Double): Double {
            val x = exp(logRatio)
            val reciprocal = x > 1.0
            val u = if (reciprocal) 1.0 / x else x
            val nr = if (reciprocal) n0 * u * u - n2 else n0 - n2 * u * u
            val dr = if (reciprocal) d0 * u * u - d2 else d0 - d2 * u * u
            return 10.0 * (log10((nr * nr + n1 * n1 * u * u).coerceAtLeast(1e-300)) -
                log10((dr * dr + d1 * d1 * u * u).coerceAtLeast(1e-300))) + outputDb
        }

        private fun fromLogRatio(value: Double) = rate / PI * atan(warped * exp(value))
        private fun absent(label: String, threshold: Double? = null) = Bandwidth(null, null, threshold, label, false)

        /** Stable TPT state-variable realization of the same analog numerator/denominator. */
        fun impulse(cancelled: () -> Boolean = { false }): Impulse? {
            // The same center retains the same time window while Q/gain are edited.
            val durationSeconds = (32.0 / center).coerceIn(.002, .08)
            val count = ceil(rate * durationSeconds).toInt().coerceIn(32, 65536)
            val bins = min(320, count)
            val minima = DoubleArray(bins) { Double.POSITIVE_INFINITY }
            val maxima = DoubleArray(bins) { Double.NEGATIVE_INFINITY }
            val samples = DoubleArray(count) { Double.NaN }
            val g = warped * sqrt(d0 / d2)
            val damping = d1 / sqrt(d0 * d2)
            val a1 = 1.0 / (1.0 + g * (g + damping))
            val a2 = g * a1
            val a3 = g * a2
            val dry = n2 / d2
            val bandMix = n1 / sqrt(d0 * d2) - dry * damping
            val lowMix = n0 / d0 - dry
            var state1 = 0.0
            var state2 = 0.0
            var peak = 0.0
            var tail = 0.0
            var finite = true
            var validSampleCount = 0
            for (index in 0 until count) {
                if (index % 256 == 0 && cancelled()) return null
                val input = if (index == 0) 1.0 else 0.0
                val value = if (unity) input else {
                    val v3 = input - state2
                    val v1 = a1 * state1 + a2 * v3
                    val v2 = state2 + a2 * state1 + a3 * v3
                    state1 = 2.0 * v1 - state1
                    state2 = 2.0 * v2 - state2
                    (dry * input + bandMix * v1 + lowMix * v2) * outputGain
                }
                if (!value.isFinite()) { finite = false; break }
                samples[index] = value
                validSampleCount = index + 1
                val bin = (index.toLong() * bins / count).toInt()
                minima[bin] = min(minima[bin], value)
                maxima[bin] = max(maxima[bin], value)
                peak = max(peak, abs(value))
                if (index >= count * 7 / 8) tail = max(tail, abs(value))
            }
            for (bin in 0 until bins) {
                if (!minima[bin].isFinite()) minima[bin] = 0.0
                if (!maxima[bin].isFinite()) maxima[bin] = 0.0
            }
            return Impulse(minima, maxima, (count - 1) * 1000.0 / rate,
                peak, tail > max(1e-9, peak * 1e-6), finite,
                samples, 1000.0 / rate, rate, validSampleCount)
        }
    }
}
