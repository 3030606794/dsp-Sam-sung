package me.timschneeberger.rootlessjamesdsp.peq

import kotlin.math.*
import java.util.TreeSet

/**
 * Steady-state filter-only preview, excluding later limiters and other effects.
 * Matches the driver's RBJ Q-based shelves and constant-0-dB-peak band-pass:
 * https://www.w3.org/TR/audio-eq-cookbook/
 */
object PeqResponse {
    const val DEFAULT_SAMPLE_RATE = 48000.0
    const val FLOOR_DB = -240.0
    const val DEFAULT_CURVE_POINTS = 2048
    const val MAX_ADAPTIVE_POINTS = 16384
    data class Point(val frequency: Double, val db: Double)

    fun responseDb(state: PeqState, frequency: Double,
                   sampleRate: Double = DEFAULT_SAMPLE_RATE): Double {
        requireSampleRate(sampleRate)
        require(frequency.isFinite() && frequency in 0.0..sampleRate / 2.0) {
            "Response frequency must lie between DC and Nyquist"
        }
        return evaluate(prepare(state, sampleRate), frequency, sampleRate)
    }

    /** Logarithmic frequency samples; coefficients are calculated once per band, not per point. */
    fun curve(state: PeqState, points: Int = DEFAULT_CURVE_POINTS,
              sampleRate: Double = DEFAULT_SAMPLE_RATE): List<Point> {
        requireSampleRate(sampleRate)
        require(points in 2..8192) { "A curve requires 2 to 8192 points" }
        val coefficients = prepare(state, sampleRate)
        val maximum = min(PeqModel.MAX_FREQUENCY, sampleRate / 2.0)
        val octaves = ln(maximum / PeqModel.MIN_FREQUENCY)
        return List(points) { index ->
            val frequency = if (index == points - 1) maximum else
                PeqModel.MIN_FREQUENCY * exp(octaves * index / (points - 1))
            Point(frequency, evaluate(coefficients, frequency, sampleRate))
        }
    }

    /**
     * A non-uniform grid for narrow Q=100 peaks/notches. Includes every enabled band's
     * quantized center, local widths, and shelving pole/zero centers. Consumers must use
     * Point.frequency rather than deriving x from the array index.
     */
    fun adaptiveCurve(state: PeqState, points: Int = DEFAULT_CURVE_POINTS,
                      sampleRate: Double = DEFAULT_SAMPLE_RATE): List<Point> {
        requireSampleRate(sampleRate)
        require(points in 2..8192) { "A curve requires 2 to 8192 base points" }
        val filters = prepare(state, sampleRate)
        val maximum = min(PeqModel.MAX_FREQUENCY, sampleRate / 2.0)
        val frequencies = TreeSet<Double>()
        val span = ln(maximum / PeqModel.MIN_FREQUENCY)
        repeat(points) { index -> frequencies.add(if (index == points - 1) maximum else
            PeqModel.MIN_FREQUENCY * exp(span * index / (points - 1))) }
        fun addFrequency(frequency: Double) {
            if (frequency.isFinite() && frequency in PeqModel.MIN_FREQUENCY..maximum)
                frequencies.add(frequency)
        }
        fun addShape(center: Double, q: Double) {
            if (center !in PeqModel.MIN_FREQUENCY..maximum || center >= sampleRate / 2.0) return
            addFrequency(center)
            val halfWidth = bandwidthOctaves(q, center, sampleRate) / 2.0
            for (fraction in doubleArrayOf(0.0625, 0.125, 0.25, 0.5, 1.0, 2.0)) {
                addFrequency(center * 2.0.pow(halfWidth * fraction))
                addFrequency(center * 2.0.pow(-halfWidth * fraction))
            }
        }
        state.bands.forEach { original ->
            val band = PeqProtocol.quantized(original)
            if (!band.enabled || band.frequency >= sampleRate / 2.0) return@forEach
            addShape(band.frequency, band.q)
            val a = 10.0.pow(band.gain / 40.0)
            if (band.type == PeqFilterType.PEAK) {
                // Large boosts/cuts have a much narrower region near the peak minimum/maximum.
                addShape(band.frequency, band.q * max(a, 1.0 / a))
            } else if (band.type == PeqFilterType.LOW_SHELF || band.type == PeqFilterType.HIGH_SHELF) {
                val warped = tan(PI * band.frequency / sampleRate)
                addShape(sampleRate / PI * atan(warped * sqrt(a)), band.q)
                addShape(sampleRate / PI * atan(warped / sqrt(a)), band.q)
            }
        }
        // At most 8192 + 170*39 candidates; TreeSet also removes shared centers.
        check(frequencies.size <= MAX_ADAPTIVE_POINTS)
        return frequencies.map { Point(it, evaluate(filters, it, sampleRate)) }
    }

    /** Digital bandwidth: peak's half-gain width; notch/band-pass's -3 dB width. */
    fun bandwidthOctaves(q: Double, frequency: Double,
                         sampleRate: Double = DEFAULT_SAMPLE_RATE): Double {
        require(q.isFinite() && q > 0.0) { "Q must be finite and positive" }
        val omega = bandwidthOmega(frequency, sampleRate)
        val inverseQ = 1.0 / (2.0 * q)
        // ln(x + sqrt(1+x²)) loses digits for narrow bands. ln1p preserves the
        // Q=100 boundary and the much narrower local sampling widths of boosted peaks.
        val inverseHyperbolic = if (inverseQ <= 1.0)
            ln1p(inverseQ + inverseQ * inverseQ / (1.0 + sqrt(1.0 + inverseQ * inverseQ)))
        else asinh(inverseQ)
        return 2.0 * inverseHyperbolic / ln(2.0) * sin(omega) / omega
    }

    /** No clipping: the editor must reject results outside the supported Q range. */
    fun qFromBandwidth(bandwidth: Double, frequency: Double,
                       sampleRate: Double = DEFAULT_SAMPLE_RATE): Double {
        require(bandwidth.isFinite() && bandwidth > 0.0) { "Bandwidth must be finite and positive" }
        val omega = bandwidthOmega(frequency, sampleRate)
        val q = 1.0 / (2.0 * sinh(ln(2.0) / 2.0 * bandwidth * omega / sin(omega)))
        require(q.isFinite() && q > 0.0) { "Bandwidth exceeds the representable Q range" }
        // Remove only a few ULPs of outward error at valid endpoints. Do not flatten
        // the in-range mapping or accept genuinely out-of-range bandwidth edits.
        return when {
            q < PeqModel.MIN_Q && PeqModel.MIN_Q - q <= 64.0 * Math.ulp(PeqModel.MIN_Q) -> PeqModel.MIN_Q
            q > PeqModel.MAX_Q && q - PeqModel.MAX_Q <= 64.0 * Math.ulp(PeqModel.MAX_Q) -> PeqModel.MAX_Q
            else -> q
        }
    }

    private fun bandwidthOmega(frequency: Double, sampleRate: Double): Double {
        requireSampleRate(sampleRate)
        require(frequency.isFinite() && frequency > 0.0 && frequency < sampleRate / 2.0) {
            "Bandwidth frequency must be below Nyquist"
        }
        return 2.0 * PI * frequency / sampleRate
    }

    private fun requireSampleRate(sampleRate: Double) {
        require(sampleRate.isFinite() && sampleRate >= 1000.0) { "Invalid audio sample rate" }
    }

    private fun prepare(state: PeqState, sampleRate: Double): List<Transfer> {
        PeqModel.validate(state)
        return state.bands.mapNotNull { original ->
            // Preview the fixed-point values sent to the driver, while keeping saved decimals intact.
            val band = PeqProtocol.quantized(original)
            if (!band.enabled || band.frequency >= sampleRate / 2.0 ||
                (band.type.usesGain && band.gain == 0.0)) null else Transfer(band.type,
                tan(PI * band.frequency / sampleRate), 10.0.pow(band.gain / 40.0), band.q)
        }
    }

    private fun evaluate(filters: List<Transfer>, frequency: Double, sampleRate: Double): Double {
        if (filters.isEmpty()) return 0.0
        val warpedFrequency = tan(PI * frequency / sampleRate)
        // Add dB rather than multiplying magnitudes: even 170 extreme bands remain finite.
        return filters.sumOf { it.db(warpedFrequency) }.coerceAtLeast(FLOOR_DB)
    }

    /**
     * RBJ's analog transfer evaluated after the bilinear frequency mapping. This is
     * equivalent to the driver's TPT SVF, but avoids subtracting nearly equal digital
     * coefficients at DC/Nyquist when gain or Q is high. Scale by x² when x > 1.
     */
    private data class Transfer(val type: PeqFilterType, val centerWarped: Double,
                                val a: Double, val q: Double) {
        fun db(warpedFrequency: Double): Double {
            val x = warpedFrequency / centerWarped
            val s2: Double
            val s1: Double
            val s0: Double
            if (x <= 1.0) {
                s2 = -x * x; s1 = x; s0 = 1.0
            } else {
                val inverse = 1.0 / x
                s2 = -1.0; s1 = inverse; s0 = inverse * inverse
            }
            val real = s2 + s0
            val imaginary = s1 / q
            val numeratorReal: Double
            val numeratorImaginary: Double
            val denominatorReal: Double
            val denominatorImaginary: Double
            when (type) {
                PeqFilterType.PEAK -> {
                    numeratorReal = real; numeratorImaginary = imaginary * a
                    denominatorReal = real; denominatorImaginary = imaginary / a
                }
                PeqFilterType.LOW_SHELF -> {
                    numeratorReal = a * (s2 + a * s0)
                    numeratorImaginary = a * sqrt(a) * imaginary
                    denominatorReal = a * s2 + s0
                    denominatorImaginary = sqrt(a) * imaginary
                }
                PeqFilterType.HIGH_SHELF -> {
                    numeratorReal = a * (a * s2 + s0)
                    numeratorImaginary = a * sqrt(a) * imaginary
                    denominatorReal = s2 + a * s0
                    denominatorImaginary = sqrt(a) * imaginary
                }
                PeqFilterType.LOW_PASS -> {
                    numeratorReal = s0; numeratorImaginary = 0.0
                    denominatorReal = real; denominatorImaginary = imaginary
                }
                PeqFilterType.HIGH_PASS -> {
                    numeratorReal = s2; numeratorImaginary = 0.0
                    denominatorReal = real; denominatorImaginary = imaginary
                }
                PeqFilterType.NOTCH -> {
                    numeratorReal = real; numeratorImaginary = 0.0
                    denominatorReal = real; denominatorImaginary = imaginary
                }
                PeqFilterType.BAND_PASS -> {
                    numeratorReal = 0.0; numeratorImaginary = imaginary
                    denominatorReal = real; denominatorImaginary = imaginary
                }
            }
            val numeratorPower = numeratorReal * numeratorReal + numeratorImaginary * numeratorImaginary
            val denominatorPower = denominatorReal * denominatorReal + denominatorImaginary * denominatorImaginary
            return 10.0 * (log10(numeratorPower.coerceAtLeast(1e-300)) -
                log10(denominatorPower.coerceAtLeast(1e-300)))
        }
    }

}
