package me.timschneeberger.rootlessjamesdsp.fusion

import java.util.Locale
import kotlin.math.*

/** Saved-parameter reference only; no audio reads, writes or smoothing timer. */
class WidthDashboardModel private constructor(
    private val values: Map<String, *>,
    val rate: Int,
    val actualRate: Boolean
) {
    private val p = WidthParameters
    private val advanced = WidthAdvancedParameters.entries.associateBy { it.control.key }
    private fun value(control: BassParameters.Control) = p.value(values, control)
    private fun extra(key: String) = WidthAdvancedParameters.value(values, advanced.getValue(key))
    val effectEnabled = p.on(values, p.ENABLED)
    val mode = p.choice(values, p.MODE)
    val masterWidth = value(p.master) / 1000.0
    val bandWidths = p.bands.map { masterWidth * value(it) / 1000.0 }
    val crossLowHz = min(value(p.crossLow).toDouble(), rate * .45)
    val crossHighHz = min(value(p.crossHigh).toDouble(), rate * .45)
    val monoHz = min(value(p.monoHz).toDouble(), rate * .45)
    val monoEnabled = p.on(values, p.MONO)
    val monoAmount = if (monoEnabled) value(p.monoAmount) / 1000.0 else 0.0
    val monoOrder = 2 shl p.choice(values, p.MONO_SLOPE)
    private val splitSlope = p.choice(values, p.SPLIT_SLOPE)
    private val mix = value(p.mix) / 1000.0
    private val midGain = 10.0.pow(value(p.midGain) / 200.0)
    private val advancedGuard = extra("width2_guard_on") == 1
    val guardEnabled = mode != 0 && (advancedGuard || p.on(values, p.GUARD))
    val guardThreshold = value(p.threshold) / 100.0
    val guardHysteresis = value(p.hysteresis) / 100.0
    val guardThresholds = listOf("low", "mid", "high").map {
        if (advancedGuard && mode == 2 && extra("width2_guard_mode") == 1)
            extra("width2_guard_$it") / 100.0 else guardThreshold
    }
    val historySeconds = extra("width2_history")
    val nativeMetersEnabled = extra("width2_meter_on") == 1
    val incrementEqEnabled = (0..1).any { extra("width2_eq${it}_on") == 1 && extra("width2_eq${it}_gain") != 0 }
    val compressionReferenceNote = if (incrementEqEnabled)
        "同相基准／新增 Side 参考；增量均衡启用，实际回收还取决于信号协方差"
        else "稳态参考；回收仅作用于超过 100% 的新增 Side，不含启动／恢复过程"
    val dynamics: List<DynamicBand> = (0..3).map { index ->
        val prefix = "width2_dyn${index}_"
        DynamicBand(index, listOf("低频", "中频", "高频", "全频")[index],
            extra(prefix + "on") == 1,
            if (index == 3) mode == 1 else mode == 2,
            extra(prefix + "threshold") / 10.0, extra(prefix + "ratio") / 10.0,
            extra(prefix + "knee") / 10.0, extra(prefix + "max") / 1000.0,
            if (index == 3) masterWidth else bandWidths[index],
            extra(prefix + "attack").toDouble(), extra(prefix + "release").toDouble())
    }
    val staticStatusLines: List<String> get() {
        val source = if (actualRate) "实际配置 $rate Hz" else "参考 $rate Hz"
        val modeLabel = listOf("原算法旧刻度", "全频 M/S", "三段 M/S")[mode]
        val legacy = (values[p.LEGACY] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: 60.0
        val widths = if (mode == 0) String.format(Locale.ROOT, "旧控制量 %.2f；30–75 不代表宽度百分比", legacy)
            else String.format(Locale.ROOT, "总体 %.1f%% · 低／中／高 %.1f／%.1f／%.1f%%",
                masterWidth * 100, bandWidths[0] * 100, bandWidths[1] * 100, bandWidths[2] * 100)
        val guard = if (guardEnabled) String.format(Locale.ROOT, "保护 %.2f / 恢复 %.2f", guardThreshold, guardThreshold + guardHysteresis)
            else "相关度保护未启用"
        return listOf("$source · $modeLabel" + if (effectEnabled) " · 参数模型" else " · 音效关闭",
            widths, guard + if (incrementEqEnabled) " · 动态曲线为同相参考" else " · 非实际声场测量")
    }

    data class DynamicBand(val index: Int, val label: String, val enabled: Boolean, val activeInMode: Boolean,
        val thresholdDb: Double, val ratio: Double, val kneeDb: Double, val maximumWithdrawal: Double,
        val width: Double, val attackMs: Double, val releaseMs: Double) {
        val active get() = enabled && activeInMode
        val maximumReductionDb: Double get() {
            if (width <= 1.0) return 0.0
            val minimumWidth = 1.0 + (width - 1.0) * (1.0 - maximumWithdrawal)
            return 20 * log10(width / minimumWidth)
        }
        /** Native soft-knee law, capped by withdrawing only the extra Side above unity. */
        fun reductionDb(inputDb: Double): Double {
            if (!inputDb.isFinite() || width <= 1.0) return 0.0
            val over = inputDb - thresholdDb
            val slope = 1.0 - 1.0 / ratio
            val requested = when {
                kneeDb > 0 && over > -kneeDb * .5 && over < kneeDb * .5 ->
                    slope * (over + kneeDb * .5).pow(2) / (2 * kneeDb)
                over > 0 -> slope * over
                else -> 0.0
            }
            return requested.coerceIn(0.0, maximumReductionDb)
        }
        /** Input is the pre-recovery candidate Side/Mid ratio in dB. */
        fun outputDb(inputDb: Double) = inputDb - reductionDb(inputDb)
        fun recoveredWidth(inputDb: Double) = width * 10.0.pow(-reductionDb(inputDb) / 20.0)
    }

    /** Magnitude of (1-amount)+amount*ButterworthHighpass; phase is retained while mixing. */
    fun monoSideGainAt(hz: Double): Double = monoTransfer(hz).magnitude

    /** Change applied to the Side/Mid amplitude ratio, not a measured acoustic image angle. */
    fun widthAt(hz: Double): Double {
        if (mode == 0) return Double.NaN // The legacy 30–75 control is a different algorithm.
        val eq = incrementEq(hz)
        fun scaled(width: Double) = C(min(width, 1.0), 0.0) + eq * max(width - 1.0, 0.0)
        val dry: C
        val side: C
        if (mode == 2) {
            val first = split(crossLowHz, hz)
            val second = split(crossHighHz, hz)
            val bands = listOf(first.low * second.allPass, first.high * second.low, first.high * second.high)
            dry = bands.reduce { a, b -> a + b }
            side = bands.indices.map { bands[it] * scaled(bandWidths[it]) }.reduce { a, b -> a + b }
        } else {
            dry = C.ONE
            side = scaled(masterWidth)
        }
        val mixedSide = dry * (1 - mix) + side * monoTransfer(hz) * mix
        val mixedMid = dry * (1 - mix + mix * midGain)
        return if (mixedMid.magnitude > 1e-12) mixedSide.magnitude / mixedMid.magnitude else Double.NaN
    }

    private fun omega(hz: Double) = 2 * PI * hz.coerceIn(0.0, rate * .499999) / rate
    private fun response(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double, hz: Double): C {
        val w = omega(hz)
        val z = C(cos(w), -sin(w)); val z2 = z * z
        return (C(b0, 0.0) + z * b1 + z2 * b2) / (C.ONE + z * a1 + z2 * a2)
    }
    private fun filter(cutoff: Double, q: Double, high: Boolean, hz: Double): C {
        val w = 2 * PI * cutoff / rate
        val c = cos(w); val a = sin(w) / (2 * q); val denominator = 1 + a
        val b0 = (if (high) 1 + c else 1 - c) / (2 * denominator)
        return response(b0, (if (high) -2 else 2) * b0, b0, -2 * c / denominator, (1 - a) / denominator, hz)
    }
    private data class Split(val low: C, val high: C, val allPass: C)
    private fun split(cutoff: Double, hz: Double): Split {
        if (splitSlope == 1) {
            val q = sqrt(.5)
            val low = filter(cutoff, q, false, hz); val high = filter(cutoff, q, true, hz)
            val w = 2 * PI * cutoff / rate; val a = sin(w) / (2 * q)
            val a1 = -2 * cos(w) / (1 + a); val a2 = (1 - a) / (1 + a)
            return Split(low * low, high * high, response(a2, a1, 1.0, a1, a2, hz))
        }
        val k = tan(PI * cutoff / rate); val a = (k - 1) / (k + 1)
        val low = k / (1 + k); val high = 1 / (1 + k)
        return Split(response(low * low, 2 * low * low, low * low, 2 * a, a * a, hz),
            response(high * high, -2 * high * high, high * high, 2 * a, a * a, hz) * -1.0,
            response(a, 1.0, 0.0, a, 0.0, hz))
    }
    private fun monoTransfer(hz: Double): C {
        if (!monoEnabled || monoAmount == 0.0) return C.ONE
        var transfer = C.ONE
        for (index in 0 until monoOrder / 2) {
            val q = 1 / (2 * cos(PI * (2 * index + 1) / (2 * monoOrder)))
            transfer *= filter(monoHz, q, true, hz)
        }
        return C.ONE * (1 - monoAmount) + transfer * monoAmount
    }
    private fun incrementEq(hz: Double): C {
        var transfer = C.ONE
        for (index in 0..1) if (extra("width2_eq${index}_on") == 1) {
            val f = min(extra("width2_eq${index}_hz").toDouble(), rate * .45)
            val gain = extra("width2_eq${index}_gain") / 10.0
            val q = extra("width2_eq${index}_q") / 100.0
            val w = 2 * PI * f / rate; val a = sin(w) / (2 * q); val amplitude = 10.0.pow(gain / 40)
            val denominator = 1 + a / amplitude
            transfer *= response((1 + a * amplitude) / denominator, -2 * cos(w) / denominator,
                (1 - a * amplitude) / denominator, -2 * cos(w) / denominator, (1 - a / amplitude) / denominator, hz)
        }
        return transfer
    }
    private data class C(val re: Double, val im: Double) {
        val magnitude get() = hypot(re, im)
        operator fun plus(other: C) = C(re + other.re, im + other.im)
        operator fun times(other: C) = C(re * other.re - im * other.im, re * other.im + im * other.re)
        operator fun times(scale: Double) = C(re * scale, im * scale)
        operator fun div(other: C): C {
            val denominator = other.re * other.re + other.im * other.im
            return if (denominator > 1e-24) C((re * other.re + im * other.im) / denominator,
                (im * other.re - re * other.im) / denominator) else C(0.0, 0.0)
        }
        companion object { val ONE = C(1.0, 0.0) }
    }
    companion object {
        fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false): WidthDashboardModel {
            val valid = rate in 8000..768000
            return WidthDashboardModel(values.toMap(), if (valid) rate else 48000, actualRate && valid)
        }
    }
}
