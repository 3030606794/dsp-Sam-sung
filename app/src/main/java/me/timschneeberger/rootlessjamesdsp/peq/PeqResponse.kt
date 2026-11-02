package me.timschneeberger.rootlessjamesdsp.peq

import kotlin.math.*
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Steady-state filter-only preview, excluding later limiters and other effects.
 * Matches the driver's RBJ Q-based shelves; pass/notch filters add an output gain:
 * https://www.w3.org/TR/audio-eq-cookbook/
 */
object PeqResponse {
    const val DEFAULT_SAMPLE_RATE = 48000.0
    const val FLOOR_DB = -240.0
    const val DEFAULT_CURVE_POINTS = 2048
    const val MAX_ADAPTIVE_POINTS = 16384
    data class Point(val frequency: Double, val db: Double)

    /** Explicit edits honor the octave-width floor; loading an old saved band never clips its Q. */
    fun maximumQ(frequency: Double, qRange: Double = PeqModel.MAX_Q,
                 sampleRate: Double = DEFAULT_SAMPLE_RATE): Double =
        min(qRange, qFromBandwidth(PeqModel.MIN_BW, frequency, sampleRate))

    /** Schema-3 peak skirts use the same gain-symmetric compensation as the native section. */
    internal fun coefficientQ(type: PeqFilterType, q: Double, gain: Double): Double {
        if (type != PeqFilterType.PEAK || q <= 1.0) return q
        val t = log10(q).coerceIn(0.0, 1.0)
        val weight = t * t * (3.0 - 2.0 * t)
        return q * 10.0.pow(abs(gain) * weight / 40.0)
    }

    /** Match the native exact-Nyquist left limit; the saved/visible frequency stays fs/2. */
    internal fun coefficientFrequency(frequency: Double, sampleRate: Double): Double =
        if (frequency == sampleRate / 2.0) sampleRate / 2.0 - 0.001 else frequency

    fun responseDb(state: PeqState, frequency: Double,
                   sampleRate: Double = DEFAULT_SAMPLE_RATE): Double {
        requireSampleRate(sampleRate)
        require(frequency.isFinite() && frequency in 0.0..sampleRate / 2.0) {
            "Response frequency must lie between DC and Nyquist"
        }
        return evaluate(prepare(state, sampleRate), frequency, sampleRate)
    }

    /** Shared actual filter evaluator for import fitting; cache the frequency warping once. */
    class ResponseGrid(frequencies: DoubleArray, private val sampleRate: Double = DEFAULT_SAMPLE_RATE) {
        private val warped: DoubleArray
        init {
            requireSampleRate(sampleRate)
            require(frequencies.isNotEmpty() && frequencies.all { it.isFinite() && it in 0.0..sampleRate / 2.0 }) {
                "Response frequencies must lie between DC and Nyquist"
            }
            warped = DoubleArray(frequencies.size) { tan(PI * frequencies[it] / sampleRate) }
        }

        fun bandDb(original: PeqBand, destination: DoubleArray) {
            require(destination.size == warped.size)
            val band = PeqProtocol.quantized(PeqModel.validateBand(original))
            if (!band.enabled || band.frequency <= 0.0 || band.frequency > sampleRate / 2.0 ||
                (band.type.isUnityAtZeroGain && band.gain == 0.0)) {
                destination.fill(0.0)
                return
            }
            val transfer = Transfer(band.type,
                tan(PI * coefficientFrequency(band.frequency, sampleRate) / sampleRate),
                10.0.pow(band.gain / 40.0), coefficientQ(band.type, band.q, band.gain))
            for (index in warped.indices) destination[index] = transfer.db(warped[index])
        }

        fun stateDb(state: PeqState): DoubleArray {
            val sum = DoubleArray(warped.size)
            val scratch = DoubleArray(warped.size)
            state.bands.forEach { band ->
                bandDb(band, scratch)
                for (index in sum.indices) sum[index] += scratch[index]
            }
            for (index in sum.indices) sum[index] = sum[index].coerceAtLeast(FLOOR_DB)
            return sum
        }
    }

    /** Logarithmic frequency samples; coefficients are calculated once per band, not per point. */
    fun curve(state: PeqState, points: Int = DEFAULT_CURVE_POINTS,
              sampleRate: Double = DEFAULT_SAMPLE_RATE,
              frequencyMin: Double = state.graphMinFrequency,
              frequencyMax: Double = state.graphMaxFrequency): List<Point> {
        requireSampleRate(sampleRate)
        requireGraphRange(frequencyMin, frequencyMax)
        require(points in 2..8192) { "A curve requires 2 to 8192 points" }
        val coefficients = prepare(state, sampleRate)
        val maximum = minOf(frequencyMax, sampleRate / 2.0)
        require(frequencyMin < maximum) { "Graph lower frequency must lie below Nyquist" }
        val octaves = ln(maximum / frequencyMin)
        return List(points) { index ->
            val frequency = if (index == points - 1) maximum else
                frequencyMin * exp(octaves * index / (points - 1))
            Point(frequency, evaluate(coefficients, frequency, sampleRate))
        }
    }

    /**
     * A non-uniform grid for narrow Q=100 peaks/notches. Includes every enabled band's
     * quantized center, local widths, and shelving pole/zero centers. Consumers must use
     * Point.frequency rather than deriving x from the array index.
     */
    fun adaptiveCurve(state: PeqState, points: Int = DEFAULT_CURVE_POINTS,
                      sampleRate: Double = DEFAULT_SAMPLE_RATE,
                      frequencyMin: Double = state.graphMinFrequency,
                      frequencyMax: Double = state.graphMaxFrequency): List<Point> {
        requireSampleRate(sampleRate)
        requireGraphRange(frequencyMin, frequencyMax)
        require(points in 2..8192) { "A curve requires 2 to 8192 base points" }
        val filters = prepare(state, sampleRate)
        return adaptiveFrequencies(state, points, sampleRate, frequencyMin, frequencyMax).map {
            Point(it, if (it > sampleRate / 2.0) Double.NaN else evaluate(filters, it, sampleRate))
        }
    }

    private fun adaptiveFrequencies(state: PeqState, points: Int, sampleRate: Double,
                                    frequencyMin: Double, frequencyMax: Double): DoubleArray =
        requireNotNull(FrequencyCache().frequencies(state, points, sampleRate, frequencyMin, frequencyMax) { false })

    /** Reuse every unchanged band's exact adaptive grid while one band is dragged. */
    private class FrequencyCache {
        private data class BandGrid(val band: PeqBand, val frequencies: DoubleArray)
        private var bands = emptyMap<String, BandGrid>()
        private var base = DoubleArray(0)
        private var baseMinimum = Double.NaN
        private var baseMaximum = Double.NaN
        private var rate = Double.NaN
        private var minimum = Double.NaN
        private var maximum = Double.NaN
        private var candidates = DoubleArray(MAX_ADAPTIVE_POINTS)

        fun frequencies(state: PeqState, points: Int, sampleRate: Double, frequencyMin: Double,
                        frequencyMax: Double, cancelled: () -> Boolean): DoubleArray? {
            val upper = minOf(frequencyMax, sampleRate / 2.0)
            require(frequencyMin < upper) { "Graph lower frequency must lie below Nyquist" }
            val sameDomain = rate == sampleRate && minimum == frequencyMin && maximum == upper
            if (baseMinimum != frequencyMin || baseMaximum != upper || base.size != points) {
                val span = ln(upper / frequencyMin)
                base = DoubleArray(points) { index -> if (index == points - 1) upper else
                    frequencyMin * exp(span * index / (points - 1)) }
                baseMinimum = frequencyMin
                baseMaximum = upper
            }
            var count = 0
            fun append(values: DoubleArray) {
                if (count + values.size > candidates.size)
                    candidates = candidates.copyOf(max(candidates.size * 2, count + values.size))
                values.copyInto(candidates, count)
                count += values.size
            }
            append(base)
            val nextBands = HashMap<String, BandGrid>(state.bands.size)
            for (original in state.bands) {
                if (cancelled()) return null
                val band = PeqProtocol.quantized(original)
                if (!band.enabled || band.frequency <= 0.0 || band.frequency > sampleRate / 2.0) continue
                val previous = if (sameDomain) bands[band.id] else null
                val previousBand = previous?.band
                val grid = if (previousBand != null && previousBand.type == band.type &&
                    previousBand.frequency == band.frequency && previousBand.gain == band.gain &&
                    previousBand.q == band.q) requireNotNull(previous) else BandGrid(band, bandGrid(band, sampleRate, frequencyMin, upper))
                nextBands[band.id] = grid
                append(grid.frequencies)
            }
            if (cancelled()) return null
            java.util.Arrays.sort(candidates, 0, count)
            var unique = 0
            for (index in 0 until count) if (unique == 0 || candidates[index] != candidates[unique - 1])
                candidates[unique++] = candidates[index]
            check(unique <= MAX_ADAPTIVE_POINTS)
            bands = nextBands
            rate = sampleRate; minimum = frequencyMin; maximum = upper
            return candidates.copyOf(unique)
        }

        private fun bandGrid(band: PeqBand, sampleRate: Double, minimum: Double, maximum: Double): DoubleArray {
            val values = DoubleArray(48)
            var count = 0
            fun add(frequency: Double) {
                if (frequency.isFinite() && frequency in minimum..maximum) values[count++] = frequency
            }
            fun shape(center: Double, q: Double) {
                if (center !in minimum..maximum || center >= sampleRate / 2.0) return
                add(center)
                val halfWidth = bandwidthOctaves(q, center, sampleRate) / 2.0
                val warpedCenter = tan(PI * center / sampleRate)
                for (fraction in SHAPE_FRACTIONS) {
                    add(sampleRate / PI * atan(warpedCenter * 2.0.pow(halfWidth * fraction)))
                    add(sampleRate / PI * atan(warpedCenter * 2.0.pow(-halfWidth * fraction)))
                }
            }
            val center = coefficientFrequency(band.frequency, sampleRate)
            shape(center, band.q)
            add(center * .999); add(center * 1.001)
            val a = 10.0.pow(band.gain / 40.0)
            if (band.type == PeqFilterType.PEAK) {
                val q = coefficientQ(band.type, band.q, band.gain)
                shape(center, q)
                shape(center, q * max(a, 1.0 / a))
            } else if (band.type == PeqFilterType.LOW_SHELF || band.type == PeqFilterType.HIGH_SHELF) {
                val warped = tan(PI * center / sampleRate)
                shape(sampleRate / PI * atan(warped * sqrt(a)), band.q)
                shape(sampleRate / PI * atan(warped / sqrt(a)), band.q)
            }
            return values.copyOf(count)
        }
    }

    private val SHAPE_FRACTIONS = doubleArrayOf(.0625, .125, .25, .5, 1.0, 2.0)

    /**
     * Worker-confined preview cache. Retained frequencies update only changed filters;
     * new adaptive frequencies still evaluate every filter, preserving narrow extrema.
     * Store the unclipped sum so extreme cuts can be removed without losing information.
     */
    internal class PreviewCache {
        private var filters = emptyMap<String, Transfer>()
        private val grid = FrequencyCache()
        private var cachedFrequencies = DoubleArray(0)
        private var cachedWarped = DoubleArray(0)
        private var cachedSums = DoubleArray(0)
        private var cachedSampleRate = Double.NaN
        private var updatesSinceRebuild = 0
        internal var lastFilterEvaluations: Long = 0
            private set

        fun curve(state: PeqState, points: Int = DEFAULT_CURVE_POINTS,
                  sampleRate: Double = DEFAULT_SAMPLE_RATE,
                  cancelled: () -> Boolean = { false },
                  frequencyMin: Double = state.graphMinFrequency,
                  frequencyMax: Double = state.graphMaxFrequency): List<Point>? {
            requireSampleRate(sampleRate)
            requireGraphRange(frequencyMin, frequencyMax)
            require(points in 2..8192) { "A curve requires 2 to 8192 base points" }
            PeqModel.validate(state)
            lastFilterEvaluations = 0
            if (cancelled()) return null
            val nextFilters = prepareById(state, sampleRate)
            val frequencies = grid.frequencies(state, points, sampleRate, frequencyMin, frequencyMax, cancelled) ?: return null
            if (cancelled()) return null
            val removed = filters.filter { (id, value) -> nextFilters[id] != value }.values.toTypedArray()
            val added = nextFilters.filter { (id, value) -> filters[id] != value }.values.toTypedArray()
            val allFilters = nextFilters.values.toTypedArray()
            // Rebuild occasionally to bound accumulated floating-point subtraction error.
            val rebuild = cachedSampleRate != sampleRate || updatesSinceRebuild >= 128 ||
                removed.size + added.size >= nextFilters.size
            val nextWarped = DoubleArray(frequencies.size)
            val nextSums = DoubleArray(frequencies.size)
            val result = ArrayList<Point>(frequencies.size)
            var previousIndex = 0
            frequencies.forEachIndexed { index, frequency ->
                if (index % 16 == 0 && cancelled()) return null
                if (frequency > sampleRate / 2.0) {
                    result.add(Point(frequency, Double.NaN))
                    return@forEachIndexed
                }
                while (!rebuild && previousIndex < cachedFrequencies.size && cachedFrequencies[previousIndex] < frequency)
                    ++previousIndex
                val retained = !rebuild && previousIndex < cachedFrequencies.size && cachedFrequencies[previousIndex] == frequency
                val warped = if (retained) cachedWarped[previousIndex] else tan(PI * frequency / sampleRate)
                val sum = if (retained) {
                    lastFilterEvaluations += removed.size + added.size
                    cachedSums[previousIndex] - removed.sumOf { it.db(warped) } + added.sumOf { it.db(warped) }
                } else {
                    lastFilterEvaluations += nextFilters.size
                    allFilters.sumOf { it.db(warped) }
                }
                nextWarped[index] = warped
                nextSums[index] = sum
                result.add(Point(frequency, sum.coerceAtLeast(FLOOR_DB)))
            }
            // A cancelled draft must not replace the last complete cache snapshot.
            if (cancelled()) return null
            filters = nextFilters
            cachedFrequencies = frequencies
            cachedWarped = nextWarped
            cachedSums = nextSums
            cachedSampleRate = sampleRate
            updatesSinceRebuild = if (rebuild) 0 else updatesSinceRebuild + 1
            return result
        }
    }

    /**
     * One running frame plus the newest pending frame. Finish the running snapshot even
     * when new gestures arrive: cancelling cold starts/rebases on every move can starve
     * the graph indefinitely. Closing the view is the only reason to cancel computation.
     */
    internal class PreviewWorker(
        private val onComplete: (Request, List<Point>) -> Unit,
        private val computeOverride: ((Request, () -> Boolean) -> List<Point>?)? = null
    ) : AutoCloseable {
        data class Request(val state: PeqState, val points: Int, val revision: Int, val animate: Boolean = false,
                           val sampleRate: Double = DEFAULT_SAMPLE_RATE,
                           val frequencyMin: Double = state.graphMinFrequency,
                           val frequencyMax: Double = state.graphMaxFrequency)
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "peq-response-preview").apply { isDaemon = true }
        }
        private val cache = PreviewCache()
        private val pending = AtomicReference<Request?>()
        private val running = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        val isClosed: Boolean get() = closed.get()

        fun submit(request: Request) {
            if (isClosed) return
            pending.set(request)
            schedule()
        }

        private fun schedule() {
            if (isClosed || !running.compareAndSet(false, true)) return
            try {
                executor.execute {
                    try {
                        while (!isClosed && !Thread.currentThread().isInterrupted) {
                            val request = pending.getAndSet(null) ?: break
                            val cancelled = { isClosed || Thread.currentThread().isInterrupted }
                            val curve = if (computeOverride != null) computeOverride.invoke(request, cancelled)
                                else cache.curve(request.state, points = request.points,
                                    sampleRate = request.sampleRate, cancelled = cancelled,
                                    frequencyMin = request.frequencyMin, frequencyMax = request.frequencyMax)
                            if (curve != null && !cancelled()) onComplete(request, curve)
                        }
                    } finally {
                        running.set(false)
                        if (pending.get() != null && !isClosed) schedule()
                    }
                }
            } catch (_: RejectedExecutionException) {
                // close() can run between testing the flag and submitting a task.
                running.set(false)
            }
        }

        override fun close() {
            closed.set(true)
            pending.set(null)
            executor.shutdownNow()
        }
    }

    /** Frequency-independent octave reference: BW = 2 asinh(1 / (2 Q)) / ln(2). */
    fun bandwidthOctaves(q: Double, frequency: Double,
                         sampleRate: Double = DEFAULT_SAMPLE_RATE): Double {
        require(q.isFinite() && q > 0.0) { "Q must be finite and positive" }
        requireBandwidthContext(frequency, sampleRate)
        val inverseQ = 1.0 / (2.0 * q)
        // ln(x + sqrt(1+x²)) loses digits for narrow bands. ln1p preserves the
        // Q=100 boundary and the much narrower local sampling widths of boosted peaks.
        val inverseHyperbolic = if (inverseQ <= 1.0)
            ln1p(inverseQ + inverseQ * inverseQ / (1.0 + sqrt(1.0 + inverseQ * inverseQ)))
        else asinh(inverseQ)
        return 2.0 * inverseHyperbolic / ln(2.0)
    }

    /** No clipping: the editor must reject results outside the supported Q range. */
    fun qFromBandwidth(bandwidth: Double, frequency: Double,
                       sampleRate: Double = DEFAULT_SAMPLE_RATE): Double {
        require(bandwidth.isFinite() && bandwidth > 0.0) { "Bandwidth must be finite and positive" }
        requireBandwidthContext(frequency, sampleRate)
        val q = 1.0 / (2.0 * sinh(ln(2.0) / 2.0 * bandwidth))
        require(q.isFinite() && q > 0.0) { "Bandwidth exceeds the representable Q range" }
        // Remove only a few ULPs of outward error at valid endpoints. Do not flatten
        // the in-range mapping or accept genuinely out-of-range bandwidth edits.
        return when {
            q < PeqModel.MIN_Q && PeqModel.MIN_Q - q <= 64.0 * Math.ulp(PeqModel.MIN_Q) -> PeqModel.MIN_Q
            q > PeqModel.MAX_Q && q - PeqModel.MAX_Q <= 64.0 * Math.ulp(PeqModel.MAX_Q) -> PeqModel.MAX_Q
            else -> q
        }
    }

    private fun requireBandwidthContext(frequency: Double, sampleRate: Double) {
        requireSampleRate(sampleRate)
        require(frequency.isFinite() && frequency >= 0.0) {
            "Bandwidth context frequency must be finite and nonnegative"
        }
    }

    private fun requireSampleRate(sampleRate: Double) {
        require(sampleRate.isFinite() && sampleRate >= 1000.0) { "Invalid audio sample rate" }
    }

    private fun requireGraphRange(minimum: Double, maximum: Double) {
        require(minimum.isFinite() && maximum.isFinite() && minimum >= 1.0 &&
            maximum <= PeqModel.MAX_FREQUENCY && maximum > minimum) { "Invalid graph frequency range" }
    }

    private fun prepare(state: PeqState, sampleRate: Double): List<Transfer> {
        PeqModel.validate(state)
        return prepareById(state, sampleRate).values.toList()
    }

    private fun prepareById(state: PeqState, sampleRate: Double): Map<String, Transfer> = buildMap {
        state.bands.forEach { original ->
            // Preview the actual milli-Hz/milli-dB and exact double-Q wire values.
            val band = PeqProtocol.quantized(original)
            if (band.enabled && band.frequency > 0.0 && band.frequency <= sampleRate / 2.0 &&
                !(band.type.isUnityAtZeroGain && band.gain == 0.0)) put(band.id, Transfer(band.type,
                tan(PI * coefficientFrequency(band.frequency, sampleRate) / sampleRate), 10.0.pow(band.gain / 40.0),
                coefficientQ(band.type, band.q, band.gain)))
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
        private val squareRootA = sqrt(a)
        private val outputGainDb = if (type.isUnityAtZeroGain) 0.0 else 40.0 * log10(a)

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
                    numeratorImaginary = a * squareRootA * imaginary
                    denominatorReal = a * s2 + s0
                    denominatorImaginary = squareRootA * imaginary
                }
                PeqFilterType.HIGH_SHELF -> {
                    numeratorReal = a * (a * s2 + s0)
                    numeratorImaginary = a * squareRootA * imaginary
                    denominatorReal = s2 + a * s0
                    denominatorImaginary = squareRootA * imaginary
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
            val shapeDb = 10.0 * (log10(numeratorPower.coerceAtLeast(1e-300)) -
                log10(denominatorPower.coerceAtLeast(1e-300)))
            return shapeDb + outputGainDb
        }
    }

}
