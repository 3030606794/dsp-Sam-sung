package me.timschneeberger.rootlessjamesdsp.peq.importer

import me.timschneeberger.rootlessjamesdsp.peq.PeqBand
import me.timschneeberger.rootlessjamesdsp.peq.PeqFilterType
import me.timschneeberger.rootlessjamesdsp.peq.PeqModel
import me.timschneeberger.rootlessjamesdsp.peq.PeqProtocol
import me.timschneeberger.rootlessjamesdsp.peq.PeqResponse
import me.timschneeberger.rootlessjamesdsp.peq.PeqState
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.CancellationException
import kotlin.math.*

/** Bounded, cancellable magnitude fit. It does not emulate the source application's FIR phase. */
object GraphicEqFitter {
    private const val MAX_EVALUATIONS = 20000
    private const val MIN_FIT_Q = 0.15
    private const val MAX_FIT_Q = 12.0
    private const val MAX_SHAPE_GAIN = 36.0

    fun fit(
        curve: GraphicEqCurve,
        baseState: PeqState,
        options: GraphicEqFitOptions = GraphicEqFitOptions(),
        onProgress: (GraphicEqFitProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): GraphicEqFitResult {
        val start = System.nanoTime()
        fun checkCancelled() {
            if (isCancelled() || Thread.currentThread().isInterrupted) throw CancellationException("已取消曲线拟合")
        }
        checkCancelled()
        PeqModel.validate(baseState)
        require(options.maxBands in 2..PeqModel.MAX_BANDS) { "拟合上限必须为 2 到 ${PeqModel.MAX_BANDS} 段" }
        require(options.targetErrorDb.isFinite() && options.targetErrorDb in 0.01..12.0) { "目标误差必须在 0.01 到 12 dB 之间" }
        require(options.sampleRate.isFinite() && options.sampleRate in 8000.0..768000.0) { "处理采样率无效" }
        require(options.minFrequency.isFinite() && options.maxFrequency.isFinite() &&
            options.minFrequency >= PeqModel.MIN_FREQUENCY && options.maxFrequency <= PeqModel.MAX_FREQUENCY &&
            options.maxFrequency > options.minFrequency) { "拟合频率范围无效" }
        val minimum = options.minFrequency
        val maximum = min(options.maxFrequency, options.sampleRate / 2.0)
        require(maximum > minimum) { "拟合下限必须低于处理采样率的一半" }
        val clipped = curve.points.count { it.frequency < minimum || it.frequency > maximum }
        val frequencies = sampleFrequencies(curve, minimum, maximum, 513, subdivisions = false)
        val target = DoubleArray(frequencies.size) { curve.gainAt(frequencies[it]) }
        val grid = PeqResponse.ResponseGrid(frequencies, options.sampleRate)
        val bands = mutableListOf<PeqBand>()
        val offset = (target.sorted()[target.size / 2] * 1000.0).roundToInt() / 1000.0
        if (offset != 0.0) {
            val center = sqrt(minimum * maximum).coerceAtMost(options.sampleRate / 2.0 - 0.001)
            // Equal-frequency/Q low + high shelves with gain G multiply to a flat G dB,
            // not 2G. Keep them fixed together; never normalize or change the master gain.
            bands += PeqBand(type = PeqFilterType.LOW_SHELF, frequency = center, gain = offset)
            bands += PeqBand(type = PeqFilterType.HIGH_SHELF, frequency = center, gain = offset)
        }
        val offsetBandCount = bands.size
        fun state(): PeqState = baseState.copy(
            bands = bands.map(PeqProtocol::quantized),
            gainRange = max(baseState.gainRange, ceil(bands.maxOfOrNull { abs(it.gain) } ?: 1.0)).coerceIn(1.0, 100.0)
        )
        var total = grid.stateDb(state())
        val budgetMillis = min(30000L, 4000L + options.maxBands * 350L)
        val context = SearchContext(frequencies, grid, minimum,
            min(maximum, options.sampleRate / 2.0 - 0.001), start + budgetMillis * 1000000L, ::checkCancelled)
        onProgress(GraphicEqFitProgress(0.03, "已读取 ${curve.points.size} 个控制点，保留原始电平"))
        var lastVerification: Verification? = null
        var previousWorst = Double.POSITIVE_INFINITY
        var stalledRounds = 0
        while (context.available()) {
            checkCancelled()
            val worst = maximumError(target, total)
            if (worst <= options.targetErrorDb || bands.size >= options.maxBands) {
                lastVerification = verify(curve, state(), minimum, maximum, options.sampleRate, ::checkCancelled)
                if (lastVerification.maximum <= options.targetErrorDb || bands.size >= options.maxBands) break
            }
            if (bands.size >= options.maxBands) break
            val residual = DoubleArray(target.size) { target[it] - total[it] }
            val improvement = context.addBand(residual) ?: break
            bands += improvement.band
            for (index in total.indices) total[index] += improvement.response[index]
            // Joint refinement compensates overlap; controls are not independent point gains.
            if (bands.size - offsetBandCount <= 4 || (bands.size - offsetBandCount) % 3 == 0) {
                for (index in offsetBandCount until bands.size) {
                    if (!context.available()) break
                    val oldResponse = DoubleArray(target.size)
                    grid.bandDb(bands[index], oldResponse)
                    val otherResidual = DoubleArray(target.size) { target[it] - total[it] + oldResponse[it] }
                    val improved = context.refine(bands[index], otherResidual, rounds = 5, fine = true) ?: continue
                    bands[index] = improved.band
                    for (point in total.indices) total[point] += improved.response[point] - oldResponse[point]
                }
            }
            lastVerification = null
            val currentWorst = maximumError(target, total)
            if (previousWorst - currentWorst < 0.0001) stalledRounds++ else stalledRounds = 0
            previousWorst = currentWorst
            val timeFraction = (System.nanoTime() - start).toDouble() / (budgetMillis * 1000000.0)
            val bandFraction = bands.size.toDouble() / options.maxBands
            onProgress(GraphicEqFitProgress((0.08 + 0.8 * max(timeFraction, bandFraction)).coerceAtMost(0.9),
                String.format(Locale.ROOT, "正在拟合：%d 段，优化网格最大误差 %.2f dB", bands.size, currentWorst)))
            if (stalledRounds >= 6) break
        }
        checkCancelled()
        val resultState = PeqModel.validate(state())
        onProgress(GraphicEqFitProgress(0.94, "正在复核原始控制点、频段拐点和密集频响"))
        val verified = lastVerification ?: verify(curve, resultState, minimum, maximum, options.sampleRate, ::checkCancelled)
        checkCancelled()
        val met = verified.maximum <= options.targetErrorDb
        onProgress(GraphicEqFitProgress(1.0, if (met) "拟合完成，已达到目标误差" else "拟合完成，未达到目标误差，请查看曲线差异"))
        return GraphicEqFitResult(resultState, verified.frequencies, verified.target, verified.fitted,
            verified.mean, verified.rms, verified.maximum, verified.maximumFrequency, met,
            (System.nanoTime() - start) / 1000000L, clipped, offsetBandCount)
    }

    private data class Candidate(val band: PeqBand, val response: DoubleArray, val score: Double)

    private class SearchContext(
        private val frequencies: DoubleArray,
        private val grid: PeqResponse.ResponseGrid,
        private val minimum: Double,
        private val maximum: Double,
        private val deadline: Long,
        private val checkCancelled: () -> Unit
    ) {
        private var evaluations = 0
        private val scratch = DoubleArray(frequencies.size)
        // Log-frequency integration keeps a dense cluster of source knots from dominating.
        private val weights = DoubleArray(frequencies.size) { index ->
            val left = if (index == 0) ln(frequencies[index]) else ln(frequencies[index - 1])
            val right = if (index == frequencies.lastIndex) ln(frequencies[index]) else ln(frequencies[index + 1])
            (right - left) / (2.0 * ln(frequencies.last() / frequencies.first()))
        }

        fun available(): Boolean {
            checkCancelled()
            return evaluations < MAX_EVALUATIONS && System.nanoTime() < deadline
        }

        private fun score(residual: DoubleArray, response: DoubleArray?): Double {
            var squared = 0.0
            var worst = 0.0
            for (index in residual.indices) {
                val error = residual[index] - (response?.get(index) ?: 0.0)
                val square = error * error
                squared += weights[index] * square
                worst = max(worst, square)
            }
            return squared + 0.015 * worst
        }

        private fun evaluate(band: PeqBand, residual: DoubleArray, betterThan: Double = Double.POSITIVE_INFINITY): Candidate? {
            if (!available()) return null
            evaluations++
            grid.bandDb(band, scratch)
            val value = score(residual, scratch)
            return if (value < betterThan) Candidate(band, scratch.copyOf(), value) else null
        }

        fun addBand(residual: DoubleArray): Candidate? {
            val centers = mutableListOf<Int>()
            // Peaks of residual error seed the search; no one-to-one mapping of source points.
            for (index in residual.indices.sortedByDescending { abs(residual[it]) }) {
                if (centers.none { abs(ln(frequencies[index] / frequencies[it])) < 0.16 }) centers += index
                if (centers.size == 6) break
            }
            val seeds = mutableListOf<Candidate>()
            for (index in centers) {
                val gain = residual[index].coerceIn(-MAX_SHAPE_GAIN, MAX_SHAPE_GAIN)
                for (q in doubleArrayOf(0.35, 0.7, 1.5, 3.0, 6.0, 12.0)) {
                    evaluate(PeqBand(frequency = frequencies[index].coerceIn(minimum, maximum), gain = gain, q = q), residual)?.let(seeds::add)
                }
                for (type in listOf(PeqFilterType.LOW_SHELF, PeqFilterType.HIGH_SHELF)) {
                    val shelfGain = (if (type == PeqFilterType.LOW_SHELF) residual.first() else residual.last())
                        .coerceIn(-MAX_SHAPE_GAIN, MAX_SHAPE_GAIN)
                    evaluate(PeqBand(type = type, frequency = frequencies[index].coerceIn(minimum, maximum), gain = shelfGain), residual)?.let(seeds::add)
                }
                if (!available()) break
            }
            var best = seeds.minByOrNull { it.score } ?: return null
            for (seed in seeds.sortedBy { it.score }.take(3)) {
                val candidate = refine(seed.band, residual, rounds = 10) ?: continue
                if (candidate.score < best.score) best = candidate
            }
            val unchanged = score(residual, null)
            return best.takeIf { abs(it.band.gain) >= 0.001 && it.score < unchanged - max(1e-10, unchanged * 1e-6) }
        }

        fun refine(seed: PeqBand, residual: DoubleArray, rounds: Int, fine: Boolean = false): Candidate? {
            var best = evaluate(seed, residual) ?: return null
            var frequencyStep = if (fine) 0.16 else 0.65
            var qualityStep = if (fine) 0.24 else 0.8
            var gainStep = if (fine) 0.5 else max(1.0, abs(seed.gain) * 0.4)
            repeat(rounds) {
                if (!available()) return best
                val before = best.score
                for (parameter in 0..2) {
                    val origin = best.band
                    for (direction in doubleArrayOf(-1.0, 1.0)) {
                        val trial = when (parameter) {
                            0 -> origin.copy(gain = (origin.gain + direction * gainStep).coerceIn(-MAX_SHAPE_GAIN, MAX_SHAPE_GAIN))
                            1 -> origin.copy(frequency = (origin.frequency * 2.0.pow(direction * frequencyStep)).coerceIn(minimum, maximum))
                            else -> origin.copy(q = (origin.q * 2.0.pow(direction * qualityStep)).coerceIn(MIN_FIT_Q, MAX_FIT_Q))
                        }
                        val evaluated = evaluate(trial, residual, best.score)
                        if (evaluated != null) best = evaluated
                        else if (!available()) return best
                    }
                }
                val shrink = if (best.score < before - 1e-10) 0.8 else 0.5
                frequencyStep *= shrink
                qualityStep *= shrink
                gainStep *= shrink
            }
            return best
        }
    }

    private data class Verification(
        val frequencies: DoubleArray, val target: DoubleArray, val fitted: DoubleArray,
        val mean: Double, val rms: Double, val maximum: Double, val maximumFrequency: Double
    )

    private fun verify(curve: GraphicEqCurve, state: PeqState, minimum: Double, maximum: Double,
                       sampleRate: Double, checkCancelled: () -> Unit): Verification {
        checkCancelled()
        val positions = TreeSet<Double>()
        sampleFrequencies(curve, minimum, maximum, 2049, subdivisions = true).forEach(positions::add)
        // Include each fitted section's narrow/warped transition, especially near Nyquist.
        PeqResponse.adaptiveCurve(state, points = 512, sampleRate = sampleRate,
            frequencyMin = minimum, frequencyMax = maximum).forEach { positions += it.frequency }
        val frequencies = positions.toDoubleArray()
        val target = DoubleArray(frequencies.size) { curve.gainAt(frequencies[it]) }
        val grid = PeqResponse.ResponseGrid(frequencies, sampleRate)
        val fitted = DoubleArray(frequencies.size)
        val scratch = DoubleArray(frequencies.size)
        state.bands.forEach { band ->
            checkCancelled()
            grid.bandDb(band, scratch)
            for (index in fitted.indices) fitted[index] += scratch[index]
        }
        var absolute = 0.0
        var squared = 0.0
        var worst = 0.0
        var worstIndex = 0
        for (index in target.indices) {
            fitted[index] = fitted[index].coerceAtLeast(PeqResponse.FLOOR_DB)
            val error = abs(target[index] - fitted[index])
            absolute += error
            squared += error * error
            if (error > worst) { worst = error; worstIndex = index }
        }
        return Verification(frequencies, target, fitted, absolute / target.size, sqrt(squared / target.size), worst, frequencies[worstIndex])
    }

    private fun sampleFrequencies(curve: GraphicEqCurve, minimum: Double, maximum: Double,
                                  count: Int, subdivisions: Boolean): DoubleArray {
        val positions = TreeSet<Double>()
        val span = ln(maximum / minimum)
        repeat(count) { index -> positions += if (index == count - 1) maximum else minimum * exp(span * index / (count - 1)) }
        curve.points.forEach { if (it.frequency in minimum..maximum) positions += it.frequency }
        if (subdivisions) curve.points.zipWithNext().forEach { (left, right) ->
            val low = max(minimum, left.frequency)
            val high = min(maximum, right.frequency)
            if (high > low) for (step in 1..3) positions += low * exp(ln(high / low) * step / 4.0)
        }
        return positions.toDoubleArray()
    }

    private fun maximumError(target: DoubleArray, fitted: DoubleArray): Double =
        target.indices.maxOf { abs(target[it] - fitted[it]) }
}
