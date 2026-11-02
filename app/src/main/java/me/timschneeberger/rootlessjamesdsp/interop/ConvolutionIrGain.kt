package me.timschneeberger.rootlessjamesdsp.interop

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/** FIR coefficients require an inverse rate ratio; ordinary audio samples do not. */
object ConvolutionIrGain {
    data class Result(val samples: FloatArray, val normalizationGainDb: Double, val rateCompensationDb: Double)

    fun normalizationGain(reference: FloatArray, options: ConvolutionIrPreprocessor.Options): Double {
        if (!options.normalize) return 1.0
        require(reference.isNotEmpty()) { "原采样率参考 IR 为空" }
        var peak = 0.0
        reference.forEachIndexed { i, value ->
            if (i % 4096 == 0) interrupted()
            require(value.isFinite()) { "原采样率参考 IR 包含无效样本" }
            peak = maxOf(peak, abs(value.toDouble()))
        }
        var gain = if (peak == 0.0) 1.0 else 10.0.pow(options.targetTenths / 200.0) / peak
        if (options.attenuateOnly) gain = min(1.0, gain)
        require(gain.isFinite() && gain > 0.0 && gain.toFloat().isFinite()) { "归一化补偿超出有效范围" }
        return gain
    }

    /** The reference gain is fixed before target-rate interpolation changes the sample peak. */
    fun apply(samples: FloatArray, sourceRate: Int, targetRate: Int, normalizationGain: Double = 1.0): Result {
        require(sourceRate in 8000..768000 && targetRate in 8000..768000) { "IR 原始或工作采样率无效" }
        require(samples.isNotEmpty() && samples.size <= 8_388_608) { "工作 IR 超过驱动传输容量" }
        require(normalizationGain.isFinite() && normalizationGain > 0.0) { "归一化补偿超出有效范围" }
        val rateGain = sourceRate.toDouble() / targetRate
        val gain = rateGain * normalizationGain
        require(gain.isFinite() && gain.toFloat().isFinite()) { "IR 电平补偿超出有效范围" }
        val output = if (gain == 1.0) samples else FloatArray(samples.size)
        samples.forEachIndexed { i, value ->
            if (i % 4096 == 0) interrupted()
            require(value.isFinite()) { "IR 包含无效样本" }
            if (output !== samples) {
                val scaled = (value * gain).toFloat()
                require(scaled.isFinite()) { "IR 电平补偿产生无效样本" }
                output[i] = scaled
            }
        }
        return Result(output, 20 * log10(normalizationGain), 20 * log10(rateGain))
    }

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
    }
}
