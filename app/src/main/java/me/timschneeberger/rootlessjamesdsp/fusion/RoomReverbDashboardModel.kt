package me.timschneeberger.rootlessjamesdsp.fusion

import java.util.Locale
import kotlin.math.*

/** Saved SF-reverb controls only. The stochastic, modulated room is not presented as measured IR. */
class RoomReverbDashboardModel private constructor(val settings: RoomReverbParameters.Settings,
    val rate: Int, val actualRate: Boolean, val enabled: Boolean) {
    val moduleEnabled get() = enabled
    val sampleRate get() = rate
    val professional = settings.professional
    private val base = bases.getValue(settings.base)
    val roomName = RoomReverbParameters.baseName(settings.base)
    val rt60Seconds = if (professional && settings.timeOn) settings.rt60 / 100.0 else base.rt60
    val earlyScale = if (professional && settings.timeOn) settings.earlyScale / 100.0 else base.earlyScale
    val earlyWidth = if (professional && settings.widthsOn) settings.earlyWidth / 1000.0 else base.earlyWidth
    val lateWidth = if (professional && settings.widthsOn) settings.lateWidth / 1000.0 else base.lateWidth
    val predelayMs = if (professional && settings.predelayOn) settings.predelay / 10.0 else 0.0
    val lateOnsetMs = predelayMs + base.delaySeconds * 1000
    val dampHz = if (professional && settings.toneOn) min(settings.damp.toDouble(), rate * .45) else base.damp
    val lowCutHz = if (professional && settings.toneOn) min(settings.lowCut.toDouble(), rate * .45) else 0.0
    val highCutHz = if (professional && settings.toneOn) min(settings.highCut.toDouble(), rate * .45) else 0.0
    val outputDb = if (professional && settings.outputOn) settings.output / 10.0 else 0.0
    val linked = professional && settings.linked
    val mix = if (linked) settings.mix / 1000.0 else 0.0
    val relativeGains = if (linked) doubleArrayOf(1 - mix, mix, mix) else doubleArrayOf(
        if (professional && settings.levelsOn) 10.0.pow(settings.dryTrim / 200.0) else 1.0,
        if (professional && settings.levelsOn) 10.0.pow(settings.earlyTrim / 200.0) else 1.0,
        if (professional && settings.levelsOn) 10.0.pow(settings.lateTrim / 200.0) else 1.0)
    val relativeGainDb = relativeGains.map { if (it > 0) 20 * log10(it) + outputDb else Double.NEGATIVE_INFINITY }.toDoubleArray()
    val baseLevelDb = doubleArrayOf(base.dryDb, base.earlyDb, base.lateDb)
    val loopDecay = .237.pow(1 / rt60Seconds)
    val effectiveBassBoost = if (professional && settings.timeOn) min(base.bassBoost, max(0.0, .995 / loopDecay - 1)) else base.bassBoost
    val stabilityProtected = effectiveBassBoost + 1e-7 < base.bassBoost
    /** Native early-reflection source tap positions; not the allpass-shaped full impulse response. */
    val earlyTapMs = Array(2) { channel ->
        val source = if (channel == 0) delayLeft else delayRight
        source.map { seconds ->
            val original = floor(seconds * base.earlyScale * rate).coerceAtLeast(1.0)
            val ratio = earlyScale / base.earlyScale
            predelayMs + original * ratio * 1000 / rate
        }.toDoubleArray()
    }
    val frequencies = DoubleArray(128) { 20.0 * (min(20000.0, rate * .45) / 20).pow(it / 127.0) }
    val wetFilterDb: DoubleArray
    val dampingDb: DoubleArray
    val inputOutputDb: DoubleArray
    val staticStatusLines get() = listOf(
        "$roomName · ${if (actualRate) "实际" else "参考"} $rate Hz${if (enabled) "" else " · 关闭预览"}",
        "RT60 ${format(rt60Seconds)} s · 早反射 ${format(earlyScale)}× · 附加预延迟 ${format(predelayMs, 1)} ms",
        "参数模型非实测声场 · ${if (stabilityProtected) "低频反馈保护" else "原房型系数"} · 实时频谱需要原始 PCM")

    init {
        val osRate = rate * base.oversampling
        wetFilterDb = frequencies.map { hz ->
            var response = SurroundProfessionalResponse.Complex(1.0)
            if (lowCutHz > 0) { val hp = onePole(hz, lowCutHz, rate.toDouble(), true); response = response * hp * hp }
            if (highCutHz > 0) { val lp = onePole(hz, highCutHz, rate.toDouble(), false); response = response * lp * lp }
            db(response.magnitude())
        }.toDoubleArray()
        dampingDb = frequencies.map { hz -> db(onePole(hz, dampHz, osRate.toDouble(), false).magnitude()) }.toDoubleArray()
        inputOutputDb = frequencies.map { hz -> db((onePole(hz, base.inputLowPass, osRate.toDouble(), false) *
            outputLowPass(hz, base.outputLowPass, osRate.toDouble())).magnitude()) }.toDoubleArray()
    }
    fun tailAmplitude(timeMs: Double): Double = if (timeMs < lateOnsetMs) 0.0 else .001.pow((timeMs - lateOnsetMs) / (rt60Seconds * 1000))
    private fun onePole(hz: Double, cutoff: Double, fs: Double, high: Boolean): SurroundProfessionalResponse.Complex {
        val t = tan(PI * cutoff.coerceIn(0.0, fs * .499999) / fs)
        val pole = (1 - t) / (1 + t)
        val z = SurroundProfessionalResponse.Complex(cos(2 * PI * hz / fs), -sin(2 * PI * hz / fs))
        val one = SurroundProfessionalResponse.Complex(1.0)
        val low = (one + z) * (t / (1 + t)) / (one - z * pole)
        return if (high) one - low else low
    }
    private fun outputLowPass(hz: Double, cutoff: Double, fs: Double): SurroundProfessionalResponse.Complex {
        val w = 2 * PI * cutoff.coerceIn(.01, fs * .499999) / fs; val sine = sin(w); val cosine = cos(w)
        val alpha = sine * sinh((ln(2.0) * .5 * w / sine).coerceAtMost(300.0))
        val b = (1 - cosine) / (2 * (1 + alpha))
        val z = SurroundProfessionalResponse.Complex(cos(2 * PI * hz / fs), -sin(2 * PI * hz / fs))
        return (SurroundProfessionalResponse.Complex(b) + z * (2 * b) + z * z * b) /
            (SurroundProfessionalResponse.Complex(1.0) + z * (-2 * cosine / (1 + alpha)) + z * z * ((1 - alpha) / (1 + alpha)))
    }
    private data class Base(val rt60: Double, val earlyScale: Double, val earlyWidth: Double, val lateWidth: Double,
        val damp: Double, val dryDb: Double, val earlyDb: Double, val lateDb: Double,
        val inputLowPass: Double, val outputLowPass: Double, val delaySeconds: Double,
        val bassBoost: Double, val oversampling: Int = 1)
    companion object {
        /** Literal selected entries of sf_presetreverb's immutable table. */
        private val bases = mapOf(
            0 to Base(3.2, 1.6, .7, 1.0, 7000.0, -7.0, -9.0, 0.0, 17000.0, 10000.0, .020, .15),
            1 to Base(2.1, 1.0, .7, 1.0, 9000.0, -7.0, -9.0, -8.0, 18000.0, 17000.0, .010, .25),
            2 to Base(2.3, 1.0, .7, 1.0, 7000.0, -7.0, -9.0, -8.0, 18000.0, 9000.0, .010, .20),
            4 to Base(2.9, 1.2, .7, 1.0, 6000.0, -7.0, -9.0, -8.0, 18000.0, 8000.0, .010, .15),
            5 to Base(3.8, 1.4, .7, 1.0, 9000.0, -7.0, -9.0, -8.0, 18000.0, 14000.0, .018, .20),
            7 to Base(.5, .7, -.4, .8, 18000.0, -7.0, -8.0, -8.0, 18000.0, 18000.0, .005, .30),
            8 to Base(.5, .8, .6, .9, 10000.0, -7.0, -8.0, -8.0, 18000.0, 18000.0, .005, .30),
            9 to Base(.8, 1.2, -.4, .8, 18000.0, -7.0, -8.0, -8.0, 18000.0, 18000.0, .008, .10),
            11 to Base(1.8, 2.2, -.4, .9, 16000.0, -7.0, -8.0, -8.0, 18000.0, 18000.0, .010, .10),
            15 to Base(1.8, 1.0, 1.0, 1.0, 16000.0, -12.0, -30.0, -8.0, 18000.0, 18000.0, 0.0, .10, 2),
            16 to Base(1.9, 1.0, 1.0, 1.0, 9000.0, -12.0, -30.0, -8.0, 18000.0, 18000.0, 0.0, .20, 2),
            17 to Base(12.0, 1.0, .1, 1.0, 10000.0, -14.0, -16.0, -5.0, 18000.0, 18000.0, 0.0, .05, 2),
            18 to Base(30.0, 1.0, .1, 1.0, 9000.0, -14.0, -16.0, -5.0, 18000.0, 18000.0, 0.0, .05, 2))
        private val delayLeft = doubleArrayOf(.0043,.0215,.0225,.0268,.0270,.0298,.0458,.0485,.0572,.0587,.0595,.0612,.0707,.0708,.0726,.0741,.0753,.0797)
        private val delayRight = doubleArrayOf(.0053,.0225,.0235,.0278,.0290,.0288,.0468,.0475,.0582,.0577,.0575,.0622,.0697,.0718,.0736,.0751,.0763,.0817)
        private fun db(value: Double) = 20 * log10(value.coerceAtLeast(1e-12))
        private fun format(value: Double, digits: Int = 2) = String.format(Locale.ROOT, "%.${digits}f", value)
        fun from(values: Map<String, *>, rate: Int = 48000, actualRate: Boolean = false) = RoomReverbDashboardModel(
            RoomReverbParameters.capture(values), rate.takeIf { it in 8000..768000 } ?: 48000,
            actualRate && rate in 8000..768000, RoomReverbParameters.moduleEnabled(values))
    }
}
