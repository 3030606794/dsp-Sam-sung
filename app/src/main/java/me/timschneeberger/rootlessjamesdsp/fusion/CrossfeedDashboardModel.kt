package me.timschneeberger.rootlessjamesdsp.fusion

import java.util.Locale
import kotlin.math.*

/** Read-only port of HeadphoneCrossfeed::Kernel response/process. It never configures the audio engine. */
class CrossfeedDashboardModel private constructor(val settings: CrossfeedParameters.Settings,
    val rate: Int, val actualRate: Boolean, val effectEnabled: Boolean) {
    val professional = settings.professional
    val diagnosticsEnabled = settings.advanced.words[10] == 1
    val presetSlot = 0 // The shared drawing worker identifies targets by model identity, not an audio preset.
    val cutoffHz = settings.cutoff.toDouble()
    val stages = if (settings.advanced.words[8] == 1) settings.advanced.words[9] else 1
    val compensation = if (settings.advanced.words[1] == 1) settings.advanced.words[2] / 100.0 else 0.0
    private val separate = settings.advanced.words[3] == 1
    val lrAttenuationDb = (if (separate) settings.advanced.words[4] else settings.attenuation) / 10.0
    val rlAttenuationDb = (if (separate) settings.advanced.words[5] else settings.attenuation) / 10.0
    val lrDelayUs = (if (separate) settings.advanced.words[6] else settings.delay).toDouble()
    val rlDelayUs = (if (separate) settings.advanced.words[7] else settings.delay).toDouble()
    val mix = settings.mix / 100.0
    private val outputGain = 10.0.pow(settings.output / 200.0)
    private val level = settings.attenuation / 10.0
    private val gLow = 10.0.pow((-level * 5.0 / 6.0 - 3.0) / 20.0)
    private val gHigh = 1.0 - 10.0.pow((level / 6.0 - 3.0) / 20.0)
    private val highCutoff = cutoffHz * 2.0.pow((-level * 5.0 / 6.0 - 3.0 - 20.0 * log10(gHigh)) / 12.0)
    private val bLow = exp(-2.0 * PI * cutoffHz / rate)
    private val aLow = gLow * (1.0 - bLow)
    private val bHigh = exp(-2.0 * PI * highCutoff / rate)
    private val aHigh = 1.0 - gHigh * (1.0 - bHigh)
    private val gain = 1.0 / (1.0 - gHigh + gLow)
    private val delays = doubleArrayOf(rlDelayUs * rate / 1e6, lrDelayUs * rate / 1e6)
    private val crossGains = doubleArrayOf(10.0.pow((level - rlAttenuationDb) / 20.0), 10.0.pow((level - lrAttenuationDb) / 20.0))
    private data class FirstOrder(val b0: Double, val b1: Double, val a1: Double)
    private val post = if (settings.filter == 0) listOf(firstOrder(10.0, true)) +
        List(3) { firstOrder(min(18000.0, rate * .45), false) } else emptyList()
    private val key = Key(settings.copy(professional = true, advanced = settings.advanced.copy(
        words = settings.advanced.words.mapIndexed { index, value -> if (index == 10) 0 else value })), rate)
    val maximumFrequencyGainDb: Double? get() = synchronized(lock) { gainCache[key] }
    val staticStatusLines: List<String> get() = listOf(
        "${if (actualRate) "实际" else "参考"} $rate Hz · ${if (professional) "专业交馈模型" else "保存的专业参数预览（当前旧算法）"}${if (effectEnabled) "" else " · 已关闭"}",
        "低通 ${settings.cutoff} Hz · ${stages * 6} dB/oct · 中央补偿 ${(compensation * 100).roundToInt()}%",
        maximumFrequencyGainDb?.let { "频率增益估计 ${String.format(Locale.ROOT, "%.2f", it)} dB · 非真峰值上界" }
            ?: "频率增益估计待计算 · 不包含试听切换和后续效果")

    private fun firstOrder(frequency: Double, high: Boolean): FirstOrder {
        // IIR_1st stores float coefficients; preserve that quantization in the displayed model.
        val angle = (PI.toFloat() * frequency.toFloat() / rate.toFloat())
        val t = tan(angle.toDouble()).toFloat()
        val b = if (high) 1f / (1f + t) else t / (1f + t)
        val a = (1f - t) / (1f + t)
        return FirstOrder(b.toDouble(), (if (high) -b else b).toDouble(), a.toDouble())
    }
    private fun matrix(frequency: Double): List<SurroundProfessionalResponse.Complex> {
        val C = SurroundProfessionalResponse.Complex(1.0)
        val omega = 2.0 * PI * frequency / rate
        val z = SurroundProfessionalResponse.Complex(cos(omega), -sin(omega))
        val direct = (SurroundProfessionalResponse.Complex(aHigh) + z * -bHigh) /
            (C - z * bHigh) * gain
        var lowpass = SurroundProfessionalResponse.Complex(aLow) / (C - z * bLow) * gain
        repeat(stages - 1) { lowpass = lowpass * (SurroundProfessionalResponse.Complex(1.0 - bLow) / (C - z * bLow)) }
        val cross = (0..1).map { channel ->
            val whole = floor(delays[channel]).toInt(); val fraction = delays[channel] - whole
            lowpass * crossGains[channel] * z.power(whole) * (SurroundProfessionalResponse.Complex(1.0 - fraction) + z * fraction)
        }
        var filter = C
        post.forEach { section -> filter = filter * ((SurroundProfessionalResponse.Complex(section.b0) + z * section.b1) / (C - z * section.a1)) }
        val directOut = (0..1).map { channel ->
            (SurroundProfessionalResponse.Complex(1.0 - mix) + filter *
                (direct + (C - direct - cross[1 - channel]) * (compensation * .5)) * mix) * outputGain
        }
        val crossOut = (0..1).map { channel ->
            filter * (cross[channel] + (C - direct - cross[channel]) * (compensation * .5)) * (mix * outputGain)
        }
        return listOf(directOut[0], directOut[1], crossOut[0], crossOut[1])
    }
    fun cachedFrequency(): SurroundProfessionalResponse.Curve? = synchronized(lock) { curves[key] }
    fun frequency(cancel: () -> Unit): SurroundProfessionalResponse.Curve {
        cachedFrequency()?.let { return it }
        val frequencies = DoubleArray(512) { 20.0 * (min(20000.0, rate * .45) / 20.0).pow(it / 511.0) }
        val result = List(8) { DoubleArray(frequencies.size) }
        var largest = 0.0
        frequencies.forEachIndexed { index, f ->
            if (index % 32 == 0) cancel()
            val h = matrix(f)
            val all = h + listOf(h[0] + h[2], h[1] + h[3], h[0] - h[2], h[1] - h[3])
            all.forEachIndexed { series, value -> result[series][index] = 20.0 * log10(value.magnitude().coerceAtLeast(1e-12)) }
            val trace = h.sumOf { it.real * it.real + it.imaginary * it.imaginary }
            val determinant = h[0] * h[1] - h[2] * h[3]
            val determinantPower = determinant.real * determinant.real + determinant.imaginary * determinant.imaginary
            largest = max(largest, sqrt(max(0.0, (trace + sqrt(max(0.0, trace * trace - 4.0 * determinantPower))) / 2.0)))
        }
        val curve = SurroundProfessionalResponse.Curve(frequencies,
            listOf("直达 L", "直达 R", "交叉→L", "交叉→R", "同相 L", "同相 R", "反相 L", "反相 R"), result)
        synchronized(lock) { curves[key] = curve; gainCache[key] = 20.0 * log10(largest.coerceAtLeast(1e-12)); trim(curves, 12); trim(gainCache, 12) }
        return curve
    }
    fun cachedDiagnostic(): SurroundDiagnostics.Result? = synchronized(lock) { impulses[key] }
    fun diagnostic(cancel: () -> Unit): SurroundDiagnostics.Result {
        cachedDiagnostic()?.let { return it }
        val size = ceil(rate * .020).toInt() + 1
        val output = List(4) { DoubleArray(size) }
        repeat(2) { source ->
            val previous = DoubleArray(2); val low = DoubleArray(2); val high = DoubleArray(2)
            val extras = Array(2) { DoubleArray(3) }; val history = Array(2) { DoubleArray(512) }
            val filterX = Array(2) { DoubleArray(post.size) }; val filterY = Array(2) { DoubleArray(post.size) }
            var position = 0
            for (n in 0 until size) {
                if (n % 256 == 0) cancel()
                val input = doubleArrayOf(if (n == 0 && source == 0) 1.0 else 0.0, if (n == 0 && source == 1) 1.0 else 0.0)
                for (c in 0..1) {
                    low[c] = aLow * input[c] + bLow * low[c]
                    high[c] = aHigh * input[c] - bHigh * previous[c] + bHigh * high[c]
                    previous[c] = input[c]
                    var filtered = low[c]
                    for (stage in 0 until stages - 1) { extras[c][stage] = (1.0 - bLow) * filtered + bLow * extras[c][stage]; filtered = extras[c][stage] }
                    history[c][position] = filtered.toFloat().toDouble()
                }
                val delayed = DoubleArray(2) { c ->
                    val whole = floor(delays[c]).toInt(); val fraction = delays[c] - whole
                    (history[1 - c][(position + 512 - whole) % 512] * (1.0 - fraction) +
                        history[1 - c][(position + 511 - whole) % 512] * fraction) * crossGains[c]
                }
                for (c in 0..1) {
                    var processed = ((high[c] + delayed[c]) * gain + compensation * .5 *
                        (input[0] + input[1] - (high[0] + high[1] + delayed[0] + delayed[1]) * gain)).toFloat().toDouble()
                    post.forEachIndexed { index, section ->
                        val next = (section.b0 * processed + section.b1 * filterX[c][index] + section.a1 * filterY[c][index]).toFloat().toDouble()
                        filterX[c][index] = processed; filterY[c][index] = next; processed = next
                    }
                    val value = ((input[c] * (1.0 - mix) + processed * mix) * outputGain).toFloat().toDouble()
                    output[if (source == 0) { if (c == 0) 0 else 3 } else { if (c == 0) 2 else 1 }][n] = value
                }
                position = (position + 1) % 512
            }
        }
        val curve = frequency(cancel)
        val result = SurroundDiagnostics.Result(DoubleArray(size) { it * 1000.0 / rate }, output,
            curve.labels.take(4), "幅度", false, maximumFrequencyGainDb ?: 0.0)
        synchronized(lock) { impulses[key] = result; trim(impulses, 3) }
        return result
    }

    companion object {
        private data class Key(val settings: CrossfeedParameters.Settings, val rate: Int)
        private val lock = Any()
        private val curves = LinkedHashMap<Key, SurroundProfessionalResponse.Curve>(12, .75f, true)
        private val impulses = LinkedHashMap<Key, SurroundDiagnostics.Result>(3, .75f, true)
        private val gainCache = LinkedHashMap<Key, Double>(12, .75f, true)
        private fun <K, V> trim(cache: LinkedHashMap<K, V>, maximum: Int) { while (cache.size > maximum) cache.remove(cache.keys.first()) }
        fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false): CrossfeedDashboardModel =
            CrossfeedDashboardModel(CrossfeedParameters.capture(values), rate.takeIf { it in 8000..768000 } ?: 48000,
                actualRate && rate in 8000..768000, values["v_cure"] == true)
    }
}
