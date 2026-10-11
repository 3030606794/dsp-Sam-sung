package me.timschneeberger.rootlessjamesdsp.peq

import kotlin.math.*

/**
 * Steady-state filter-only preview, excluding later limiters and other effects.
 * Matches the driver's RBJ Q-based shelves and constant-0-dB-peak band-pass:
 * https://www.w3.org/TR/audio-eq-cookbook/
 */
object PeqResponse {
    const val DEFAULT_SAMPLE_RATE = 48000.0
    const val FLOOR_DB = -240.0
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
    fun curve(state: PeqState, points: Int = 256,
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

    /** Digital bandwidth: peak's half-gain width; notch/band-pass's -3 dB width. */
    fun bandwidthOctaves(q: Double, frequency: Double,
                         sampleRate: Double = DEFAULT_SAMPLE_RATE): Double {
        require(q.isFinite() && q > 0.0) { "Q must be finite and positive" }
        val omega = bandwidthOmega(frequency, sampleRate)
        return 2.0 * asinh(1.0 / (2.0 * q)) / ln(2.0) * sin(omega) / omega
    }

    /** No clipping: the editor must reject results outside the supported Q range. */
    fun qFromBandwidth(bandwidth: Double, frequency: Double,
                       sampleRate: Double = DEFAULT_SAMPLE_RATE): Double {
        require(bandwidth.isFinite() && bandwidth > 0.0) { "Bandwidth must be finite and positive" }
        val omega = bandwidthOmega(frequency, sampleRate)
        val q = 1.0 / (2.0 * sinh(ln(2.0) / 2.0 * bandwidth * omega / sin(omega)))
        require(q.isFinite() && q > 0.0) { "Bandwidth exceeds the representable Q range" }
        return q
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

    private fun prepare(state: PeqState, sampleRate: Double): List<Coefficients> {
        PeqModel.validate(state)
        return state.bands.mapNotNull { original ->
            // Preview the fixed-point values sent to the driver, while keeping saved decimals intact.
            val band = PeqProtocol.quantized(original)
            if (!band.enabled || band.frequency >= sampleRate / 2.0 ||
                (band.type.usesGain && band.gain == 0.0)) null else coefficients(band, sampleRate)
        }
    }

    private fun evaluate(filters: List<Coefficients>, frequency: Double, sampleRate: Double): Double {
        if (filters.isEmpty()) return 0.0
        val omega = 2.0 * PI * frequency / sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val doubleCosine = cos(2.0 * omega)
        val doubleSine = sin(2.0 * omega)
        // Add dB rather than multiplying magnitudes: even 170 extreme bands remain finite.
        return filters.sumOf { it.db(cosine, sine, doubleCosine, doubleSine) }.coerceAtLeast(FLOOR_DB)
    }

    private data class Coefficients(val b0: Double, val b1: Double, val b2: Double,
                                    val a1: Double, val a2: Double) {
        fun db(c: Double, s: Double, c2: Double, s2: Double): Double {
            val numeratorReal = b0 + b1 * c + b2 * c2
            val numeratorImaginary = -b1 * s - b2 * s2
            val denominatorReal = 1.0 + a1 * c + a2 * c2
            val denominatorImaginary = -a1 * s - a2 * s2
            val numeratorPower = numeratorReal * numeratorReal + numeratorImaginary * numeratorImaginary
            val denominatorPower = denominatorReal * denominatorReal + denominatorImaginary * denominatorImaginary
            return 10.0 * (log10(numeratorPower.coerceAtLeast(1e-300)) -
                log10(denominatorPower.coerceAtLeast(1e-300)))
        }
    }

    private fun normalized(b0: Double, b1: Double, b2: Double,
                           a0: Double, a1: Double, a2: Double) =
        Coefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    private fun coefficients(band: PeqBand, sampleRate: Double): Coefficients {
        val omega = 2.0 * PI * band.frequency / sampleRate
        val c = cos(omega)
        val alpha = sin(omega) / (2.0 * band.q)
        val a = 10.0.pow(band.gain / 40.0)
        return when (band.type) {
            PeqFilterType.PEAK -> normalized(1.0 + alpha * a, -2.0 * c, 1.0 - alpha * a,
                1.0 + alpha / a, -2.0 * c, 1.0 - alpha / a)
            PeqFilterType.LOW_SHELF -> {
                val beta = 2.0 * sqrt(a) * alpha
                normalized(a * ((a + 1.0) - (a - 1.0) * c + beta),
                    2.0 * a * ((a - 1.0) - (a + 1.0) * c),
                    a * ((a + 1.0) - (a - 1.0) * c - beta),
                    (a + 1.0) + (a - 1.0) * c + beta,
                    -2.0 * ((a - 1.0) + (a + 1.0) * c),
                    (a + 1.0) + (a - 1.0) * c - beta)
            }
            PeqFilterType.HIGH_SHELF -> {
                val beta = 2.0 * sqrt(a) * alpha
                normalized(a * ((a + 1.0) + (a - 1.0) * c + beta),
                    -2.0 * a * ((a - 1.0) + (a + 1.0) * c),
                    a * ((a + 1.0) + (a - 1.0) * c - beta),
                    (a + 1.0) - (a - 1.0) * c + beta,
                    2.0 * ((a - 1.0) - (a + 1.0) * c),
                    (a + 1.0) - (a - 1.0) * c - beta)
            }
            PeqFilterType.LOW_PASS -> normalized((1.0 - c) / 2.0, 1.0 - c, (1.0 - c) / 2.0,
                1.0 + alpha, -2.0 * c, 1.0 - alpha)
            PeqFilterType.HIGH_PASS -> normalized((1.0 + c) / 2.0, -(1.0 + c), (1.0 + c) / 2.0,
                1.0 + alpha, -2.0 * c, 1.0 - alpha)
            PeqFilterType.NOTCH -> normalized(1.0, -2.0 * c, 1.0,
                1.0 + alpha, -2.0 * c, 1.0 - alpha)
            PeqFilterType.BAND_PASS -> normalized(alpha, 0.0, -alpha,
                1.0 + alpha, -2.0 * c, 1.0 - alpha)
        }
    }
}
