package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import java.io.DataInputStream
import java.util.Locale
import kotlin.math.*

/** Read-only steady-state model of PresetCrossfeed.cpp. No driver writes or audio callbacks. */
class PresetCrossfeedDashboardModel private constructor(val settings: PresetCrossfeedParameters.Settings,
    val rate: Int, val actualRate: Boolean, val effectEnabled: Boolean) {
    val professional = settings.professional
    private val effective = if (professional) settings else PresetCrossfeedParameters.baseSettings(settings.base)
    val presetSlot = settings.base
    val diagnosticsEnabled = true
    val cutoffHz = effective.cutoff.toDouble()
    val stages = if (effective.advanced.filterOn) effective.advanced.slope else 0
    val compensation = if (effective.advanced.centerOn) effective.advanced.center / 10.0 else 0.0
    private val baseAttenuation = if (effective.base < 2) effective.attenuation / 10.0 else -effective.spatial / 10.0
    val lrAttenuationDb = baseAttenuation - (if (effective.advanced.pathOn) effective.advanced.leftToRight else 0) / 10.0
    val rlAttenuationDb = baseAttenuation - (if (effective.advanced.pathOn)
        if (effective.advanced.linked) effective.advanced.leftToRight else effective.advanced.rightToLeft else 0) / 10.0
    val lrDelayUs = effective.delay.toDouble()
    val rlDelayUs = effective.delay.toDouble()
    val mix = effective.mix / 100.0
    private val key = Key(effective, rate)
    val maximumFrequencyGainDb: Double? get() = synchronized(lock) { gains[key] }
    private val sourceNote get() = if (settings.base < 2) "BS2B 递归模型" else
        if (rate == 48000) "原始 HRTF 核模型" else "48k 原始 HRTF 核 · SRC 近似"
    val staticStatusLines: List<String> get() = listOf(
        "${PresetCrossfeedParameters.baseNames[settings.base]} · ${if (actualRate) "实际" else "参考"} $rate Hz${if (effectEnabled) "" else " · 预览模式"}",
        "$sourceNote · ${if (professional) "专业精调" else "原预设（精调未启用）"}",
        maximumFrequencyGainDb?.let { "频率增益估计 ${String.format(Locale.ROOT, "%.2f", it)} dB · 非真峰值上界" }
            ?: "频率增益估计待计算 · 图纸仅观察原参数")

    private fun matrix(hz: Double, cancel: () -> Unit): List<SurroundProfessionalResponse.Complex> {
        val s = effective
        val one = SurroundProfessionalResponse.Complex(1.0)
        val omega = 2 * PI * hz / rate
        val z = SurroundProfessionalResponse.Complex(cos(omega), -sin(omega))
        val fractionalDelay = s.delay * rate / 1e6
        val whole = floor(fractionalDelay).toInt()
        val delay = z.power(whole) * (SurroundProfessionalResponse.Complex(1.0 - (fractionalDelay - whole)) + z * (fractionalDelay - whole))
        val m = s.mix / 100.0; val trim = 10.0.pow(s.output / 200.0)
        val direct: List<SurroundProfessionalResponse.Complex>
        val cross: List<SurroundProfessionalResponse.Complex>
        val dry: SurroundProfessionalResponse.Complex
        if (s.base < 2) {
            val level = s.attenuation / 10.0
            val lo = 10.0.pow((-level * 5 / 6 - 3) / 20)
            val hi = 1 - 10.0.pow((level / 6 - 3) / 20)
            val highCut = s.cutoff * 2.0.pow((-level * 5 / 6 - 3 - 20 * log10(hi)) / 12)
            val bLo = exp(-2 * PI * s.cutoff / rate)
            val bHi = exp(-2 * PI * highCut / rate)
            val gain = 1 / (1 - hi + lo)
            val d = (SurroundProfessionalResponse.Complex(1 - hi * (1 - bHi)) - z * bHi) / (one - z * bHi) * gain
            val c = SurroundProfessionalResponse.Complex(lo * (1 - bLo)) / (one - z * bLo) * gain * delay
            direct = listOf(d, d); cross = listOf(c, c); dry = one
        } else {
            val base = kernel(s.base, cancel).at(hz)
            val padSeconds = if (rate > 48000) 64.0 / (if (rate % 44100 == 0) 44100 else 48000) else 0.0
            val pad = SurroundProfessionalResponse.Complex(cos(2 * PI * hz * padSeconds), -sin(2 * PI * hz * padSeconds))
            direct = listOf(base[0] * pad, base[3] * pad)
            val spatial = 10.0.pow(s.spatial / 200.0)
            // FFTConvolver arguments are LL, LR, RL, RR; destination L receives RL.
            cross = listOf(base[2] * pad * delay * spatial, base[1] * pad * delay * spatial)
            dry = pad
        }
        var dL = (dry * (1 - m) + direct[0] * m) * trim
        var dR = (dry * (1 - m) + direct[1] * m) * trim
        var cL = cross[0] * (m * trim); var cR = cross[1] * (m * trim)
        val advanced = s.advanced
        if (advanced.filterOn) {
            val t = tan(PI * min(advanced.filterHz.toDouble(), rate * .45) / rate)
            val pole = (1 - t) / (1 + t)
            val lowpass = (one + z) * ((1 - pole) * .5) / (one - z * pole)
            val filter = if (advanced.slope == 2) lowpass * lowpass else lowpass
            cL = cL * filter; cR = cR * filter
        }
        if (advanced.pathOn) {
            cL = cL * 10.0.pow((if (advanced.linked) advanced.leftToRight else advanced.rightToLeft) / 200.0)
            cR = cR * 10.0.pow(advanced.leftToRight / 200.0)
        }
        if (advanced.centerOn) {
            val k = (10.0.pow(advanced.center / 200.0) - 1) * .5
            val leftSource = (dL + cR) * k; val rightSource = (dR + cL) * k
            dL += leftSource; cR += leftSource; dR += rightSource; cL += rightSource
        }
        return listOf(dL, dR, cL, cR)
    }
    fun cachedFrequency(): SurroundProfessionalResponse.Curve? = synchronized(lock) { curves[key] }
    fun frequency(cancel: () -> Unit): SurroundProfessionalResponse.Curve {
        cachedFrequency()?.let { return it }
        val frequencies = DoubleArray(768) { 20.0 * (min(20000.0, rate * .499) / 20).pow(it / 767.0) }
        val values = List(8) { DoubleArray(frequencies.size) }; var largest = 0.0
        frequencies.forEachIndexed { i, hz ->
            if (i % 16 == 0) cancel()
            val h = matrix(hz, cancel)
            val rows = h + listOf(h[0] + h[2], h[1] + h[3], h[0] - h[2], h[1] - h[3])
            rows.forEachIndexed { j, v -> values[j][i] = 20 * log10(v.magnitude().coerceAtLeast(1e-12)) }
            val trace = h.sumOf { it.real * it.real + it.imaginary * it.imaginary }
            val det = h[0] * h[1] - h[2] * h[3]
            val power = det.real * det.real + det.imaginary * det.imaginary
            largest = max(largest, sqrt(max(0.0, (trace + sqrt(max(0.0, trace * trace - 4 * power))) * .5)))
        }
        val result = SurroundProfessionalResponse.Curve(frequencies,
            listOf("直达 L", "直达 R", "交叉→L", "交叉→R", "同相 L", "同相 R", "反相 L", "反相 R"), values)
        synchronized(lock) { curves[key] = result; gains[key] = 20 * log10(largest.coerceAtLeast(1e-12)); trim(curves, 12); trim(gains, 12) }
        return result
    }
    fun cachedDiagnostic(): SurroundDiagnostics.Result? = synchronized(lock) { impulses[key] }
    /** Wrapped coherent-input phase; near-zero complex sums deliberately have no phase value. */
    fun coherentPhase(cancel: () -> Unit): List<DoubleArray> {
        val curve = frequency(cancel)
        val rows = List(2) { DoubleArray(curve.frequencies.size) { Double.NaN } }
        curve.frequencies.forEachIndexed { index, hz ->
            if (index % 32 == 0) cancel()
            val h = matrix(hz, cancel)
            listOf(h[0] + h[2], h[1] + h[3]).forEachIndexed { channel, value ->
                if (value.magnitude() > 1e-9) rows[channel][index] = atan2(value.imaginary, value.real) * 180 / PI
            }
        }
        return rows
    }
    fun diagnostic(cancel: () -> Unit): SurroundDiagnostics.Result {
        cachedDiagnostic()?.let { return it }
        val fs = min(rate, 48000)
        val n = if (settings.base >= 2) 65536 else 4096
        val spectra = Array(4) { Array(2) { DoubleArray(n) } }
        for (i in 0..n / 2) {
            if (i % 128 == 0) cancel()
            val hz = max(.00001, i.toDouble() * fs / n)
            val h = matrix(min(hz, rate * .499999), cancel)
            for (j in 0..3) {
                spectra[j][0][i] = h[j].real
                spectra[j][1][i] = if (i == 0 || i == n / 2) 0.0 else h[j].imaginary
                if (i in 1 until n / 2) { spectra[j][0][n - i] = h[j].real; spectra[j][1][n - i] = -h[j].imaginary }
            }
        }
        // Keep early arrivals readable on a half-screen chart. The complete original kernel
        // is used for the spectrum/IFFT; only the displayed finite time window is cropped.
        val count = min(n, ceil(fs * if (settings.base == 5) .120 else .020).toInt() + 1)
        val values = spectra.map { parts -> fft(parts[0], parts[1], true, cancel); parts[0].copyOf(count) }
        frequency(cancel)
        val result = SurroundDiagnostics.Result(DoubleArray(count) { it * 1000.0 / fs }, values,
            listOf("直达 L", "直达 R", "交叉→L", "交叉→R"), "幅度", false, maximumFrequencyGainDb ?: 0.0)
        synchronized(lock) { impulses[key] = result; trim(impulses, 3) }
        return result
    }

    private class Kernel(val source: Array<DoubleArray>) {
        @Volatile var spectrum: Array<Array<DoubleArray>>? = null
        fun prepare(cancel: () -> Unit) {
            if (spectrum != null) return
            val n = if (source[0].size > 256) 65536 else 8192
            val generated = Array(4) { c -> arrayOf(source[c].copyOf(n), DoubleArray(n)) }
            generated.forEach { fft(it[0], it[1], false, cancel) }
            spectrum = generated
        }
        fun at(hz: Double): List<SurroundProfessionalResponse.Complex> {
            val data = checkNotNull(spectrum)
            val n = data[0][0].size
            val position = (hz * n / 48000.0).coerceIn(0.0, n / 2.0)
            val index = floor(position).toInt(); val next = min(index + 1, n / 2); val fraction = position - index
            return data.map { parts -> SurroundProfessionalResponse.Complex(
                parts[0][index] + (parts[0][next] - parts[0][index]) * fraction,
                parts[1][index] + (parts[1][next] - parts[1][index]) * fraction) }
        }
    }
    companion object {
        private data class Key(val settings: PresetCrossfeedParameters.Settings, val rate: Int)
        private val lock = Any()
        private var application: Context? = null
        private var kernels: List<Kernel>? = null
        private val curves = LinkedHashMap<Key, SurroundProfessionalResponse.Curve>(12, .75f, true)
        private val gains = LinkedHashMap<Key, Double>(12, .75f, true)
        private val impulses = LinkedHashMap<Key, SurroundDiagnostics.Result>(3, .75f, true)
        fun initialize(context: Context) { application = context.applicationContext }
        private fun kernel(base: Int, cancel: () -> Unit): Kernel {
            val loaded = synchronized(lock) {
                kernels ?: DataInputStream(checkNotNull(application).assets.open("preset_crossfeed_preview.bin").buffered()).use { input ->
                    require(input.readInt() == 0x58504631 && input.readInt() == 48000 && input.readInt() == 4)
                    List(4) {
                        cancel(); val count = input.readInt(); require(count in 1..65536)
                        val rows = Array(4) { DoubleArray(count) }
                        for (i in 0 until count) for (c in 0..3) rows[c][i] = input.readFloat().toDouble()
                        Kernel(rows)
                    }.also { kernels = it }
                }
            }
            return loaded[base - 2].also { it.prepare(cancel) }
        }
        private fun fft(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean, cancel: () -> Unit) {
            val n = real.size; var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
                if (i < j) { val r = real[i]; real[i] = real[j]; real[j] = r; val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im }
            }
            var length = 2
            while (length <= n) {
                cancel(); val angle = (if (inverse) 2 else -2) * PI / length
                val wr = cos(angle); val wi = sin(angle)
                for (start in 0 until n step length) {
                    var ur = 1.0; var ui = 0.0
                    for (k in 0 until length / 2) {
                        val a = start + k; val b = a + length / 2
                        val vr = real[b] * ur - imaginary[b] * ui; val vi = real[b] * ui + imaginary[b] * ur
                        real[b] = real[a] - vr; imaginary[b] = imaginary[a] - vi
                        real[a] += vr; imaginary[a] += vi
                        val next = ur * wr - ui * wi; ui = ur * wi + ui * wr; ur = next
                    }
                }
                length = length shl 1
            }
            if (inverse) for (i in real.indices) { real[i] /= n; imaginary[i] /= n }
        }
        private fun <K, V> trim(map: LinkedHashMap<K, V>, size: Int) { while (map.size > size) map.remove(map.keys.first()) }
        fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false) = PresetCrossfeedDashboardModel(
            PresetCrossfeedParameters.capture(values), rate.takeIf { it in 8000..768000 } ?: 48000,
            actualRate && rate in 8000..768000, values[PresetCrossfeedParameters.ENABLED] == true)
    }
}
