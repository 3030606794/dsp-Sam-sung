package me.timschneeberger.rootlessjamesdsp.view

import me.timschneeberger.rootlessjamesdsp.interop.JdspImpResToolbox
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Display calculations only. No audio, settings or driver instance is changed here. */
internal object EqListeningModel {
    const val SAMPLE_RATE = 48000
    const val IMPULSE_SIZE = 8192
    private const val BAND_POINTS = 65
    val names = arrayOf("下潜", "鼓点", "温暖", "人声", "清晰", "通透")
    val ranges = arrayOf("1–60", "60–250", "250–500", "500–2k", "2–6k", "6–20k")
    private val edges = doubleArrayOf(1.0, 60.0, 250.0, 500.0, 2000.0, 6000.0, 20000.0)

    data class Input(val levels: DoubleArray, val filterType: Int, val interpolation: Int,
        val filterLabel: String, val interpolationLabel: String, val effectEnabled: Boolean,
        val activeBand: Int) {
        fun sameAs(other: Input?) = other != null && levels.contentEquals(other.levels) &&
            filterType == other.filterType && interpolation == other.interpolation &&
            filterLabel == other.filterLabel && interpolationLabel == other.interpolationLabel &&
            effectEnabled == other.effectEnabled && activeBand == other.activeBand
    }

    data class Result(val input: Input, val averages: DoubleArray, val summary: String,
        val focus: Int, val localFrequencies: DoubleArray, val interpolationDb: DoubleArray,
        val impulse: DoubleArray, val error: String? = null)

    fun unavailable(input: Input) = Result(input, DoubleArray(0), "预览计算暂不可用",
        focus(input), DoubleArray(0), DoubleArray(0), DoubleArray(0), "未取得可计算频响")

    fun compute(input: Input, ensureCurrent: () -> Unit): Result {
        ensureCurrent()
        val focus = focus(input)
        val axis = EqualizerSurface.SCALE.copyOf()
        val minimum = if (focus == 0) 12.5 else axis[focus - 1]
        val maximum = if (focus == axis.lastIndex) 20000.0 else axis[focus + 1]
        val local = logarithmic(129, minimum, maximum)
        val bands = DoubleArray(names.size * BAND_POINTS) { index ->
            val band = index / BAND_POINTS
            edges[band] * (edges[band + 1] / edges[band]).pow((index % BAND_POINTS).toDouble() / (BAND_POINTS - 1))
        }
        val impulseFrequencies = DoubleArray(IMPULSE_SIZE / 2 + 1) { it.toDouble() * SAMPLE_RATE / IMPULSE_SIZE }
        val real = DoubleArray(IMPULSE_SIZE)
        val imaginary = DoubleArray(IMPULSE_SIZE)
        val averages: DoubleArray
        val localDb: DoubleArray
        if (input.filterType == 0) {
            val queries = bands + local + impulseFrequencies
            val response = interpolate(input, axis, queries, ensureCurrent)
            averages = averageBands(response)
            localDb = response.copyOfRange(bands.size, bands.size + local.size)
            val offset = bands.size + local.size
            // Recover a minimum-phase finite-window model from the native interpolation magnitude.
            // This is explicitly not the driver's complete, windowed FIR kernel.
            for (index in impulseFrequencies.indices) {
                real[index] = response[offset + index] * ln(10.0) / 20.0
                if (index > 0 && index < IMPULSE_SIZE / 2) real[IMPULSE_SIZE - index] = real[index]
            }
            transform(real, imaginary, true, ensureCurrent)
            for (index in real.indices) {
                real[index] = when {
                    index == 0 || index == IMPULSE_SIZE / 2 -> real[index]
                    index < IMPULSE_SIZE / 2 -> real[index] * 2.0
                    else -> 0.0
                }
                imaginary[index] = 0.0
            }
            transform(real, imaginary, false, ensureCurrent)
            for (index in real.indices) {
                val magnitude = exp(real[index])
                val phase = imaginary[index]
                real[index] = magnitude * cos(phase)
                imaginary[index] = magnitude * sin(phase)
            }
        } else {
            localDb = interpolate(input, axis, local, ensureCurrent)
            val queries = bands + impulseFrequencies
            val re = DoubleArray(queries.size) { Double.NaN }
            val im = DoubleArray(queries.size) { Double.NaN }
            val order = intArrayOf(4, 6, 8, 10, 12)[input.filterType - 1]
            ensureCurrent()
            synchronized(JdspImpResToolbox) {
                ensureCurrent()
                JdspImpResToolbox.ComputeIIREqualizerCplx(SAMPLE_RATE, order, axis,
                    input.levels.copyOf(), queries.size, queries, re, im)
            }
            ensureCurrent()
            require(re.all(Double::isFinite) && im.all(Double::isFinite))
            averages = averageBands(DoubleArray(bands.size) { 20.0 * log10(max(1e-15, hypot(re[it], im[it]))) })
            for (index in impulseFrequencies.indices) {
                real[index] = re[bands.size + index]
                imaginary[index] = if (index == 0 || index == IMPULSE_SIZE / 2) 0.0 else im[bands.size + index]
                if (index > 0 && index < IMPULSE_SIZE / 2) {
                    real[IMPULSE_SIZE - index] = real[index]
                    imaginary[IMPULSE_SIZE - index] = -imaginary[index]
                }
            }
        }
        ensureCurrent()
        transform(real, imaginary, true, ensureCurrent)
        require(real.all(Double::isFinite))
        return Result(input, averages, summarize(averages), focus, local, localDb, real)
    }

    private fun interpolate(input: Input, axis: DoubleArray, frequencies: DoubleArray,
        ensureCurrent: () -> Unit): DoubleArray {
        val response = FloatArray(frequencies.size) { Float.NaN }
        ensureCurrent()
        synchronized(JdspImpResToolbox) {
            ensureCurrent()
            JdspImpResToolbox.ComputeEqResponse(15, axis.copyOf(), input.levels.copyOf(),
                input.interpolation, frequencies.size, frequencies, response)
        }
        ensureCurrent()
        require(response.all(Float::isFinite))
        return DoubleArray(response.size) { response[it].toDouble() }
    }

    private fun averageBands(response: DoubleArray) = DoubleArray(names.size) { band ->
        var area = 0.0
        for (point in 0 until BAND_POINTS) area += response[band * BAND_POINTS + point] *
            if (point == 0 || point == BAND_POINTS - 1) .5 else 1.0
        area / (BAND_POINTS - 1)
    }

    private fun summarize(values: DoubleArray): String {
        if (values.all { abs(it) < .15 }) return "原音均衡 · 各频段基本平直"
        val strongest = values.indices.sortedByDescending { abs(values[it]) }.take(2)
        return strongest.filter { abs(values[it]) >= .35 }.joinToString(" · ") {
            names[it] + if (values[it] > 0) "增强" else "削减"
        }.ifEmpty { "细微调整 · 接近原音均衡" }
    }

    private fun focus(input: Input): Int {
        val largest = input.levels.maxOf { abs(it) }
        return input.activeBand.takeIf { it in input.levels.indices && abs(input.levels[it]) >= largest - 1e-9 }
            ?: if (largest < 1e-9) 8 else input.levels.indices.maxByOrNull { abs(input.levels[it]) } ?: 8
    }

    private fun logarithmic(count: Int, minimum: Double, maximum: Double) =
        DoubleArray(count) { minimum * (maximum / minimum).pow(it.toDouble() / (count - 1)) }

    /** Bounded radix-2 FFT; callers supply their own arrays and cancellation between stages. */
    private fun transform(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean, ensureCurrent: () -> Unit) {
        val count = real.size
        var reversed = 0
        for (index in 1 until count) {
            var bit = count shr 1
            while (reversed and bit != 0) { reversed = reversed xor bit; bit = bit shr 1 }
            reversed = reversed xor bit
            if (index < reversed) {
                val a = real[index]; real[index] = real[reversed]; real[reversed] = a
                val b = imaginary[index]; imaginary[index] = imaginary[reversed]; imaginary[reversed] = b
            }
        }
        var length = 2
        while (length <= count) {
            ensureCurrent()
            val angle = (if (inverse) 2.0 else -2.0) * PI / length
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            for (start in 0 until count step length) {
                var wRe = 1.0
                var wIm = 0.0
                for (offset in 0 until length / 2) {
                    val left = start + offset
                    val right = left + length / 2
                    val re = real[right] * wRe - imaginary[right] * wIm
                    val im = real[right] * wIm + imaginary[right] * wRe
                    real[right] = real[left] - re; imaginary[right] = imaginary[left] - im
                    real[left] += re; imaginary[left] += im
                    val next = wRe * stepRe - wIm * stepIm
                    wIm = wRe * stepIm + wIm * stepRe; wRe = next
                }
            }
            length = length shl 1
        }
        if (inverse) for (index in 0 until count) { real[index] /= count; imaginary[index] /= count }
    }
}
