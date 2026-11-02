package me.timschneeberger.rootlessjamesdsp.fusion

import kotlin.math.*

/** Read-only settled-parameter model of ToneProcessor. It does not simulate music or envelope history. */
class ToneDashboardModel private constructor(
    val settings: ToneAdvancedParameters.Settings,
    val rate: Int,
    val actualRate: Boolean,
    val enabled: Boolean,
    val validFoundation: Boolean
) {
    val effective: DoubleArray = DoubleArray(10) { index ->
        if (settings.professional && settings.coreOn)
            (settings.base[index] + settings.delta[index] / 10.0).coerceIn(-100.0, 100.0) * settings.amount / 1000.0
        else settings.base[index].toDouble()
    }
    private val shape: Boolean = settings.professional && settings.shapeOn
    private val dynamic: Boolean = settings.professional && settings.dynamicOn
    val hz: DoubleArray = DoubleArray(10) { min((if (shape) settings.frequency[it] else ToneAdvancedParameters.frequencyDefaults[it]).toDouble(), rate * .43) }
    val q: DoubleArray = DoubleArray(6) { (if (shape) settings.q[it] else ToneAdvancedParameters.qDefaults[it]) / 100.0 }
    val slopes: DoubleArray = DoubleArray(4) { if (shape) settings.slope[it] / 1000.0 else 1.0 }
    val filterGainDb: DoubleArray = doubleArrayOf(
        .04 * effective[0] * (if (shape) settings.temperatureLowWeight / 1000.0 else 1.0),
        -.04 * effective[0] * (if (shape) settings.temperatureHighWeight / 1000.0 else 1.0),
        .06 * effective[1], .03 * effective[2] * (if (dynamic) settings.elasticStaticWeight / 1000.0 else 1.0),
        .04 * effective[3], .04 * effective[4], .03 * effective[5],
        .04 * effective[6], .04 * effective[7], .04 * effective[9])
    val bassTarget: Double = effective[2] / 100 * (if (dynamic) settings.bassDynamicWeight / 1000.0 else 1.0)
    val transientTarget: Double = effective[8] / 100 * (if (dynamic) settings.transientWeight / 1000.0 else 1.0)
    val bassSensitivity: Double = if (dynamic) settings.bassSensitivity / 1000.0 else 1.0
    val transientSensitivity: Double = if (dynamic) settings.transientSensitivity / 1000.0 else 1.0
    val bassMaximumDb: Double = if (dynamic) settings.bassMax / 10.0 else 3 * abs(bassTarget)
    val transientMaximumDb: Double = if (dynamic) settings.transientMax / 10.0 else 3 * abs(transientTarget)
    val bassBoundDb: Double = min(3 * abs(bassTarget), bassMaximumDb)
    val transientBoundDb: Double = min(3 * abs(transientTarget), transientMaximumDb)
    val bassSplitHz: Double = min(if (dynamic) settings.bassSplit.toDouble() else 150.0, rate * .43)
    val bassFastMs: Double = if (dynamic) settings.bassFast / 10.0 else 5.0
    val bassSlowMs: Double = if (dynamic) settings.bassSlow.toDouble() else 70.0
    val transientFastMs: Double = if (dynamic) settings.transientFast / 10.0 else 2.0
    val transientSlowMs: Double = if (dynamic) settings.transientSlow.toDouble() else 45.0
    val weakEnabled: Boolean = dynamic && settings.weakOn
    val weakFloorDbfs: Double = settings.weakFloor / 10.0
    val mix: Double = if (settings.professional && settings.mixOn) settings.mix / 1000.0 else 1.0
    val outputDb: Double = if (settings.professional && settings.outputOn) settings.output / 10.0 else 0.0
    val manualDb: Double = if (settings.professional && settings.headroomOn) settings.headroom / 10.0 else 0.0
    private data class Complex(val re: Double, val im: Double) {
        operator fun plus(other: Complex): Complex = Complex(re + other.re, im + other.im)
        operator fun times(other: Complex): Complex = Complex(re * other.re - im * other.im, re * other.im + im * other.re)
        operator fun times(value: Double): Complex = Complex(re * value, im * value)
        operator fun div(other: Complex): Complex {
            val d: Double = other.re * other.re + other.im * other.im
            return Complex((re * other.re + im * other.im) / d, (im * other.re - re * other.im) / d)
        }
        fun amplitude(): Double = hypot(re, im)
    }
    private data class Coeff(val b0: Double = 1.0, val b1: Double = 0.0, val b2: Double = 0.0,
        val a1: Double = 0.0, val a2: Double = 0.0) {
        fun at(f: Double, rate: Int): Complex {
            val z: Complex = Complex(cos(2 * PI * f / rate), -sin(2 * PI * f / rate))
            val z2: Complex = z * z
            return (Complex(b0, 0.0) + z * b1 + z2 * b2) / (Complex(1.0, 0.0) + z * a1 + z2 * a2)
        }
    }
    private val filters: List<Coeff> = (0..9).map { index ->
        when (index) {
            0 -> shelf(hz[index], filterGainDb[index], false, slopes[0])
            1 -> shelf(hz[index], filterGainDb[index], true, slopes[1])
            2 -> shelf(hz[index], filterGainDb[index], false, slopes[2])
            9 -> shelf(hz[index], filterGainDb[index], true, slopes[3])
            else -> peak(hz[index], q[index - 3], filterGainDb[index])
        }
    }
    val automaticDb: Double = run {
        val changed: Boolean = shape && (settings.frequency != ToneAdvancedParameters.frequencyDefaults ||
            settings.q != ToneAdvancedParameters.qDefaults || settings.slope.any { it != 1000 } ||
            settings.temperatureLowWeight != 1000 || settings.temperatureHighWeight != 1000)
        val maximum: Double = if (changed) 10.0.pow(filterGainDb.sumOf { max(0.0, it) } / 20) else {
            var bound: Double = 1.0
            for (index in 0..192) {
                val f: Double = 20 * ((rate * .499) / 20).pow(index / 192.0)
                bound = max(bound, response(f).amplitude())
            }
            bound
        }
        -20 * log10(maximum) - bassBoundDb - transientBoundDb
    }
    val frequencies: DoubleArray = DoubleArray(161) { 20 * 1000.0.pow(it / 160.0) }
    val staticDb: DoubleArray = DoubleArray(frequencies.size) { index ->
        if (frequencies[index] < rate / 2.0) db(response(frequencies[index]).amplitude()) else Double.NaN
    }
    private val active: Boolean = enabled && (effective.any { it != 0.0 } || outputDb != 0.0 || manualDb != 0.0)
    val totalDb: DoubleArray = DoubleArray(frequencies.size) { index ->
        val f: Double = frequencies[index]
        if (f >= rate / 2.0) Double.NaN else if (!active) 0.0 else {
            val h: Complex = Complex(1 - mix, 0.0) + response(f) * (mix * 10.0.pow(automaticDb / 20))
            db(h.amplitude()) + outputDb + manualDb
        }
    }
    val phaseDegrees: DoubleArray = DoubleArray(frequencies.size) { index ->
        val f: Double = frequencies[index]
        if (f >= rate / 2.0) Double.NaN else {
            val h: Complex = if (active) Complex(1 - mix, 0.0) + response(f) * (mix * 10.0.pow(automaticDb / 20)) else Complex(1.0, 0.0)
            if (h.amplitude() < 1e-10) Double.NaN else atan2(h.im, h.re) * 180 / PI
        }
    }
    fun componentDb(index: Int): DoubleArray = DoubleArray(frequencies.size) { i ->
        if (frequencies[i] >= rate / 2.0) Double.NaN else db(filters[index.coerceIn(0, 9)].at(frequencies[i], rate).amplitude())
    }
    /** Unit step at the detector, after filtering/headroom; not an input-to-output simulation or measured gain. */
    fun onsetDb(timeMs: Double, bass: Boolean, falling: Boolean = false): Double {
        val fast: Double = if (bass) bassFastMs else transientFastMs
        val slow: Double = if (bass) bassSlowMs else transientSlowMs
        val f: Double = if (falling) exp(-max(0.0, timeMs) / fast) else 1 - exp(-max(0.0, timeMs) / fast)
        val s: Double = if (falling) exp(-max(0.0, timeMs) / slow) else 1 - exp(-max(0.0, timeMs) / slow)
        val sensitivity: Double = if (bass) bassSensitivity else transientSensitivity
        val target: Double = if (bass) bassTarget else transientTarget
        val maximum: Double = if (bass) bassMaximumDb else transientMaximumDb
        val onset: Double = ((f - s) / (s + 1e-5)).coerceIn(0.0, 1.0)
        return (3 * target * (onset * sensitivity).coerceIn(0.0, 1.0)).coerceIn(-maximum, maximum)
    }
    private fun response(frequency: Double): Complex {
        var result: Complex = Complex(1.0, 0.0)
        for (filter in filters) result = result * filter.at(frequency, rate)
        return result
    }
    private fun peak(frequency: Double, q: Double, gain: Double): Coeff {
        if (gain == 0.0) return Coeff()
        val w: Double = 2 * PI * min(frequency, rate * .43) / rate
        val a: Double = 10.0.pow(gain / 40); val cs: Double = cos(w); val al: Double = sin(w) / (2 * q); val d: Double = 1 + al / a
        return Coeff((1 + al * a) / d, -2 * cs / d, (1 - al * a) / d, -2 * cs / d, (1 - al / a) / d)
    }
    private fun shelf(frequency: Double, gain: Double, high: Boolean, slope: Double): Coeff {
        if (gain == 0.0) return Coeff()
        val w: Double = 2 * PI * min(frequency, rate * .43) / rate
        val a: Double = 10.0.pow(gain / 40); val cs: Double = cos(w)
        val t: Double = if (slope == 1.0) sqrt(2 * a) * sin(w) else sin(w) * sqrt(a) * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
        if (high) {
            val d: Double = a + 1 - (a - 1) * cs + t
            return Coeff(a * (a + 1 + (a - 1) * cs + t) / d, -2 * a * (a - 1 + (a + 1) * cs) / d,
                a * (a + 1 + (a - 1) * cs - t) / d, 2 * (a - 1 - (a + 1) * cs) / d, (a + 1 - (a - 1) * cs - t) / d)
        }
        val d: Double = a + 1 + (a - 1) * cs + t
        return Coeff(a * (a + 1 - (a - 1) * cs + t) / d, 2 * a * (a - 1 - (a + 1) * cs) / d,
            a * (a + 1 - (a - 1) * cs - t) / d, -2 * (a - 1 + (a + 1) * cs) / d, (a + 1 + (a - 1) * cs - t) / d)
    }
    companion object {
        private fun db(value: Double): Double = 20 * log10(max(1e-12, value))
        fun from(values: Map<String, *>, rate: Int = 0, actualRate: Boolean = rate in 8000..768000): ToneDashboardModel =
            ToneDashboardModel(ToneAdvancedParameters.capture(values), rate.takeIf { it in 8000..768000 } ?: 48000,
                actualRate && rate in 8000..768000, ToneAdvancedParameters.moduleEnabled(values), ToneAdvancedParameters.validSavedFoundation(values))
        fun fromSettings(settings: ToneAdvancedParameters.Settings, rate: Int, actualRate: Boolean = false): ToneDashboardModel =
            ToneDashboardModel(settings, rate.coerceIn(8000, 768000), actualRate, true, true)
    }
}
