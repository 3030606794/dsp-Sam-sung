package me.timschneeberger.rootlessjamesdsp.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters
import me.timschneeberger.rootlessjamesdsp.fusion.HighFrequencyAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.HighFrequencyParameters

/**
 * Read-only, settled parameter models, never audio measurements or current dynamic reduction.
 * Ozone, Natural and the maximum attenuation filters follow their native coefficient equations.
 * XHiFi uses an explicitly approximate Butterworth crossover model with the saved path delays.
 * Dynamic transfer/envelope plots describe a hypothetical input; they do not predict music levels.
 */
data class ClarityPreviewModel(
    val mode: Int,
    val modeLabel: String,
    val rate: Int,
    val amount: Double,
    val outputDb: Double,
    val lowHz: Double,
    val highHz: Double,
    val lowDelayMs: Double,
    val midDelayMs: Double,
    val naturalEnabled: Boolean,
    val dynamicEnabled: Boolean,
    val deessEnabled: Boolean,
    val naturalLow: Double,
    val naturalHigh: Double,
    val naturalQ: Double,
    val dynamicHz: Double,
    val dynamicQ: Double,
    val dynamicThreshold: Double,
    val dynamicRatio: Double,
    val dynamicMaximum: Double,
    val dynamicAttackMs: Double,
    val dynamicReleaseMs: Double,
    val deessLow: Double,
    val deessHigh: Double,
    val deessQ: Double,
    val deessThreshold: Double,
    val deessRatio: Double,
    val deessMaximum: Double,
    val deessAttackMs: Double,
    val deessReleaseMs: Double,
    val shelfHz: Double,
    val shelfSlope: Double,
    val lowTrimDb: Double,
    val midTrimDb: Double,
    val highTrimDb: Double,
) {
    val modelLabel: String get() = "参数模型·非音频采样" + if (mode == 2) "·XHiFi 分频近似" else ""

    private val ozone = shelf(shelfHz, 20.0 * log10(1.0 + amount), shelfSlope)
    private val selectiveHighpass = pass(naturalLow, naturalQ, highpass = true)
    private val selectiveLowpass = pass(naturalHigh, naturalQ, highpass = false)
    private val dynamicShelf = shelf(dynamicHz, -dynamicMaximum, 1.0)
    private val dynamicDetector = pass(dynamicHz, dynamicQ, highpass = true)
    private val deessDetectorHighpass = pass(deessLow, deessQ, highpass = true)
    private val deessDetectorLowpass = pass(deessHigh, deessQ, highpass = false)
    // Native detector Q and attenuation Q are different controls; do not substitute one for the other.
    private val deessPeak = peak(sqrt(deessLow * deessHigh), -deessMaximum,
        (sqrt(deessLow * deessHigh) / (deessHigh - deessLow).coerceAtLeast(1.0)).coerceIn(0.1, 20.0))
    private val hifiLow = firstOrder(lowHz, highpass = false)
    private val hifiMidHp1 = firstOrder(lowHz, highpass = true)
    private val hifiMidHp2 = pass(lowHz, 1.0, highpass = true)
    private val hifiMidLp1 = firstOrder(highHz, highpass = false)
    private val hifiMidLp2 = pass(highHz, 1.0, highpass = false)
    private val hifiHigh1 = firstOrder(highHz, highpass = true)
    private val hifiHigh2 = pass(highHz, 1.0, highpass = true)

    /** Base enhancement plus saved output trim, before signal-dependent dynamic attenuation. */
    fun enhancementDb(hz: Double): Double {
        val w = omega(hz)
        val z = Complex(cos(w), -sin(w))
        val response = when (mode) {
            1 -> ozone.response(z)
            2 -> {
                // Approximation: first-order low path, third-order high path and a cascaded band path.
                val lowDelay = floor(lowDelayMs * rate / 1000.0)
                val midDelay = floor(midDelayMs * rate / 1000.0)
                val low = hifiLow.response(z) * amplitude(lowTrimDb) * delay(w, lowDelay)
                val mid = hifiMidHp1.response(z) * hifiMidHp2.response(z) *
                    hifiMidLp1.response(z) * hifiMidLp2.response(z) *
                    ((1.0 + amount) * amplitude(midTrimDb)) * delay(w, midDelay)
                val high = hifiHigh1.response(z) * hifiHigh2.response(z) *
                    ((1.0 + amount) * 1.2 * amplitude(highTrimDb))
                low + mid + high
            }
            else -> {
                val selected = if (!naturalEnabled) Complex.ONE
                    else if (naturalLow < naturalHigh) selectiveHighpass.response(z) * selectiveLowpass.response(z)
                    else Complex.ZERO
                val sharpen = Complex.ONE + (Complex.ONE - z) * selected * amount
                // NoiseSharpening's actual hand-written history update is a two-tap filter,
                // not the recursive IIR_1st transfer function suggested by its coefficient class.
                val cutoff = (rate * 0.5 - 1000.0).coerceAtLeast(1.0)
                val t = tan(PI * cutoff / rate)
                val b0 = t / (1.0 + t)
                val a1 = (1.0 - t) / (1.0 + t)
                val post = Complex(b0, 0.0) + z * (a1 + b0)
                sharpen * post
            }
        }
        return db(response) + outputDb
    }

    /** Saved maximum-reduction shelf; enable flags are separate so disabled settings remain previewable. */
    fun dynamicDb(hz: Double): Double = db(dynamicShelf.response(unitDelay(hz)))

    fun dynamicDetectorDb(hz: Double): Double = db(dynamicDetector.response(unitDelay(hz)))
    fun deessDetectorDb(hz: Double): Double = db(deessDetectorHighpass.response(unitDelay(hz)) *
        deessDetectorLowpass.response(unitDelay(hz)))

    /** Saved maximum-reduction PEQ, using the driver's attenuation bandwidth rather than detector Q. */
    fun deessDb(hz: Double): Double = if (deessLow < deessHigh) db(deessPeak.response(unitDelay(hz))) else 0.0

    /** Positive steady reduction for a hypothetical detector input in dBFS, without live audio. */
    fun dynamicReduction(inputDb: Double): Double = reduction(inputDb, dynamicThreshold, dynamicRatio, dynamicMaximum)

    fun deessReduction(inputDb: Double): Double = if (deessLow < deessHigh)
        reduction(inputDb, deessThreshold, deessRatio, deessMaximum) else 0.0

    /** Normalized detector response to an 80 ms unit step, followed by the saved release time. */
    fun envelope(ms: Double, deess: Boolean): Double {
        if (!ms.isFinite() || ms < 0.0) return 0.0
        val attack = (if (deess) deessAttackMs else dynamicAttackMs).coerceAtLeast(0.1)
        val release = (if (deess) deessReleaseMs else dynamicReleaseMs).coerceAtLeast(0.1)
        return if (ms <= 80.0) (1.0 - exp(-ms / attack)).coerceIn(0.0, 1.0)
        else ((1.0 - exp(-80.0 / attack)) * exp(-(ms - 80.0) / release)).coerceIn(0.0, 1.0)
    }

    private fun reduction(inputDb: Double, threshold: Double, ratio: Double, maximum: Double): Double =
        if (inputDb.isFinite()) ((inputDb - threshold) * (1.0 - 1.0 / ratio.coerceAtLeast(1.0)))
            .coerceIn(0.0, maximum.coerceAtLeast(0.0)) else 0.0

    private fun omega(hz: Double): Double = 2.0 * PI *
        (hz.takeIf { it.isFinite() } ?: 1.0).coerceIn(0.0, rate * 0.5) / rate
    private fun unitDelay(hz: Double): Complex = omega(hz).let { Complex(cos(it), -sin(it)) }
    private fun delay(w: Double, samples: Double) = Complex(cos(w * samples), -sin(w * samples))
    private fun amplitude(db: Double): Double = 10.0.pow(db / 20.0)
    private fun db(value: Complex): Double = (20.0 * log10(hypot(value.re, value.im).coerceAtLeast(1e-12)))
        .coerceIn(-240.0, 120.0)

    private data class Complex(val re: Double, val im: Double) {
        operator fun plus(other: Complex) = Complex(re + other.re, im + other.im)
        operator fun minus(other: Complex) = Complex(re - other.re, im - other.im)
        operator fun times(other: Complex) = Complex(re * other.re - im * other.im, re * other.im + im * other.re)
        operator fun times(value: Double) = Complex(re * value, im * value)
        operator fun div(other: Complex): Complex {
            val scale = (other.re * other.re + other.im * other.im).coerceAtLeast(1e-30)
            return Complex((re * other.re + im * other.im) / scale, (im * other.re - re * other.im) / scale)
        }
        companion object {
            val ZERO = Complex(0.0, 0.0)
            val ONE = Complex(1.0, 0.0)
        }
    }

    private data class Biquad(val b0: Double, val b1: Double, val b2: Double,
        val a0: Double, val a1: Double, val a2: Double) {
        fun response(z: Complex): Complex {
            val z2 = z * z
            return (Complex(b0, 0.0) + z * b1 + z2 * b2) /
                (Complex(a0, 0.0) + z * a1 + z2 * a2)
        }
    }

    private fun pass(frequency: Double, q: Double, highpass: Boolean): Biquad {
        val w = 2.0 * PI * frequency / rate
        val c = cos(w)
        val alpha = sin(w) / (2.0 * q.coerceAtLeast(0.1))
        val b = if (highpass) (1.0 + c) / 2.0 else (1.0 - c) / 2.0
        return Biquad(b, if (highpass) -2.0 * b else 2.0 * b, b,
            1.0 + alpha, -2.0 * c, 1.0 - alpha)
    }

    private fun firstOrder(frequency: Double, highpass: Boolean): Biquad {
        val t = tan(PI * frequency / rate)
        return if (highpass) Biquad(1.0, -1.0, 0.0, 1.0 + t, t - 1.0, 0.0)
        else Biquad(t, t, 0.0, 1.0 + t, t - 1.0, 0.0)
    }

    private fun shelf(frequency: Double, gainDb: Double, slope: Double): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * frequency / rate
        val c = cos(w)
        val z = sqrt(a) * sin(w) * sqrt(((a + 1.0 / a) *
            (1.0 / slope.coerceIn(0.25, 1.0) - 1.0) + 2.0).coerceAtLeast(0.0))
        val plus = (a + 1.0) + (a - 1.0) * c
        val minus = (a + 1.0) - (a - 1.0) * c
        return Biquad((plus + z) * a, -2.0 * a * ((a - 1.0) + (a + 1.0) * c),
            (plus - z) * a, minus + z, 2.0 * ((a - 1.0) - (a + 1.0) * c), minus - z)
    }

    private fun peak(frequency: Double, gainDb: Double, q: Double): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * frequency / rate
        val alpha = sin(w) / (2.0 * q)
        val c = -2.0 * cos(w)
        return Biquad(1.0 + alpha * a, c, 1.0 - alpha * a, 1.0 + alpha / a, c, 1.0 - alpha / a)
    }

    companion object {
        /** Use the same migration-aware, unit-aware readers as packet creation; never writes prefs. */
        fun from(viper: Map<String, *>, hf: Map<String, *>, rate: Int = 48000): ClarityPreviewModel {
            val values = linkedMapOf<String, Any?>().apply { putAll(viper); putAll(hf) }
            val fs = rate.takeIf { it in 8000..768000 } ?: 48000
            fun base(control: BassParameters.Control) =
                HighFrequencyParameters.value(values, control).toDouble() / control.divisor
            fun advanced(control: BassParameters.Control) =
                HighFrequencyAdvancedParameters.value(values, control).toDouble() / control.divisor
            fun frequency(value: Double) = value.coerceIn(1.0, fs * 0.5 - 100.0)
            val modeValue = (values["v_clarity_mode"] as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()?.coerceIn(0, 2) ?: 0
            val p = HighFrequencyParameters
            val a = HighFrequencyAdvancedParameters
            return ClarityPreviewModel(
                mode = modeValue,
                modeLabel = when (modeValue) { 1 -> "Ozone"; 2 -> "XHiFi"; else -> "自然" },
                rate = fs,
                amount = base(p.clarityAmount),
                outputDb = base(p.clarityOutput),
                lowHz = frequency(base(p.lowFrequency)),
                highHz = frequency(base(p.highFrequency)),
                lowDelayMs = base(p.lowDelay),
                midDelayMs = base(p.midDelay),
                naturalEnabled = a.enabled(values, a.NATURAL_ENABLED),
                dynamicEnabled = a.enabled(values, a.DYNAMIC_ENABLED),
                deessEnabled = a.enabled(values, a.DEESS_ENABLED),
                naturalLow = frequency(advanced(a.naturalLow)),
                naturalHigh = frequency(advanced(a.naturalHigh)),
                naturalQ = advanced(a.naturalQ),
                dynamicHz = frequency(advanced(a.dynamicFrequency)),
                dynamicQ = advanced(a.dynamicQ),
                dynamicThreshold = advanced(a.dynamicThreshold),
                dynamicRatio = advanced(a.dynamicRatio),
                dynamicMaximum = advanced(a.dynamicMaximum),
                dynamicAttackMs = advanced(a.dynamicAttack),
                dynamicReleaseMs = advanced(a.dynamicRelease),
                deessLow = frequency(advanced(a.deessLow)),
                deessHigh = frequency(advanced(a.deessHigh)),
                deessQ = advanced(a.deessQ),
                deessThreshold = advanced(a.deessThreshold),
                deessRatio = advanced(a.deessRatio),
                deessMaximum = advanced(a.deessMaximum),
                deessAttackMs = advanced(a.deessAttack),
                deessReleaseMs = advanced(a.deessRelease),
                shelfHz = frequency(base(p.shelfFrequency)),
                shelfSlope = base(p.shelfSlope),
                lowTrimDb = base(p.lowTrim),
                midTrimDb = base(p.midTrim),
                highTrimDb = base(p.highTrim),
            )
        }
    }
}
