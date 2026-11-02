package me.timschneeberger.rootlessjamesdsp.fusion

import kotlin.math.*

/** Read-only settled ViPERBass/Subwoofer responses. Pure Bass+ omits its polyphase core. */
class BassLinkedModel private constructor(val values: List<Int>, val mode: Int, val enabled: Boolean,
    val rate: Int, val actualRate: Boolean) {
    enum class Focus { MODE, LOWPASS, GAIN, DEEP, PUNCH }
    val modeName get() = when (mode) { 1 -> "Pure Bass+"; 2 -> "低音炮"; else -> "自然低音" }
    val rateLabel get() = if (actualRate) "$rate Hz 处理率" else "$rate Hz参考"
    val approximation get() = mode == 1
    val frequency = values[0].toDouble()
    val factor = (values[1] / 100f).toDouble()
    val q = (values[2] / 100f).toDouble()
    val deepHz = values[3].toDouble(); val punchHz = values[4].toDouble()
    val deepWidth = (values[7] / 100f).toDouble(); val punchWidth = (values[8] / 100f).toDouble()
    private val subBaseDb = (20f * log10(max(factor.toFloat() * 2.5f, 1e-6f))).toDouble()
    val deepDb = (subBaseDb.toFloat() + values[5] / 10f).toDouble()
    val punchDb = (subBaseDb.toFloat() - 18.06179974f + values[6] / 10f).toDouble()
    val lowpass = Biquad.lowPass(frequency, q, rate)
    val subLowpass = Biquad.lowPass(380.0, .6f.toDouble(), rate)
    val deep = Biquad.peak(deepHz, deepDb, deepWidth, rate)
    val punch = Biquad.peak(punchHz, punchDb, punchWidth, rate)
    val frequencies = DoubleArray(320) { 10.0 * 50.0.pow(it / 319.0) }
    val totalDb = frequencies.map { total(it).db() }.toDoubleArray()
    val lowpassDb = frequencies.map { lowpass.at(it).db() }.toDoubleArray()
    val addedDb = frequencies.map { (lowpass.at(it) * factor).db() }.toDoubleArray()
    val deepResponseDb = frequencies.map { deep.at(it).db() }.toDoubleArray()
    val punchResponseDb = frequencies.map { punch.at(it).db() }.toDoubleArray()
    private val defaultLowpass = Biquad.lowPass(70.0, .53f.toDouble(), rate)
    private val defaultGain = (20f * log10(1.25f)).toDouble()
    private val defaultDeep = Biquad.peak(44.0, defaultGain, .75f.toDouble(), rate)
    private val defaultPunch = Biquad.peak(80.0, (defaultGain.toFloat()-18.06179974f).toDouble(), .2f.toDouble(), rate)
    val toneNames = listOf("下潜", "鼓点", "厚度")
    val toneRanges = listOf(10.0 to 60.0, 60.0 to 120.0, 120.0 to 500.0)
    /** Mean gain difference on a log-frequency axis, relative to the same mode's defaults. Not audio energy. */
    val toneDeltaDb = DoubleArray(3) { band ->
        val (low,high)=toneRanges[band]
        (0 until 64).sumOf { step ->
            val hz=low*(high/low).pow((step+.5)/64)
            total(hz).db()-defaultTotal(hz).db()
        }/64
    }
    fun defaultTotal(hz: Double) = if(mode==2) Complex(.5,0.0) + subLowpass.at(hz)*(defaultDeep.at(hz)*defaultPunch.at(hz)-Complex.ONE)*.6
        else Complex.ONE+defaultLowpass.at(hz)*.5
    fun gainDifference(hz: Double)=total(hz).db()-defaultTotal(hz).db()
    fun hearingSummary(): String {
        val i=toneDeltaDb.indices.maxByOrNull { abs(toneDeltaDb[it]) } ?: 0
        return if(abs(toneDeltaDb[i])<.15)"接近本模式默认低音" else if(toneDeltaDb[i]>0)"更偏${toneNames[i]} · 相对默认增强" else "${toneNames[i]}相对默认减弱"
    }
    fun total(hz: Double) = if (mode == 2) subTotal(hz) else naturalTotal(hz)
    fun naturalTotal(hz: Double) = Complex.ONE + lowpass.at(hz) * factor
    fun subTotal(hz: Double) = Complex(.5, 0.0) + subLowpass.at(hz) * (deep.at(hz) * punch.at(hz) - Complex.ONE) * .6
    fun detailActive(focus: Focus) = when (focus) { Focus.LOWPASS -> mode != 2; Focus.DEEP, Focus.PUNCH -> mode == 2; else -> true }
    fun detailName(focus: Focus) = when (focus) { Focus.MODE -> "模式与支路"; Focus.LOWPASS -> "低通拐点 / Q"; Focus.GAIN -> "增强系数 / 合成"; Focus.DEEP -> "深潜峰形 / 带宽"; Focus.PUNCH -> "冲击峰形 / 带宽" }
    fun focusFrequency(focus: Focus) = when (focus) { Focus.DEEP -> deepHz; Focus.PUNCH -> punchHz; Focus.GAIN, Focus.LOWPASS -> frequency; else -> if (mode == 2) deepHz else frequency }
    fun bounds(focus: Focus): Pair<Double,Double>? {
        val center: Double; val bw: Double
        when (focus) { Focus.DEEP -> { center=deepHz; bw=deepWidth }; Focus.PUNCH -> { center=punchHz; bw=punchWidth }; else -> return null }
        // Native octave BW is defined via the bilinear digital-frequency mapping.
        val w = 2 * PI * center / rate
        val alpha = sinh(bw * ln(2.0) * w / (2 * sin(w))) * sin(w)
        val q = sin(w) / (2 * alpha)
        val k = tan(w * .5)
        val root = sqrt(1 + 1 / (4 * q * q))
        val low = rate / PI * atan(k * (root - 1 / (2 * q)))
        val high = rate / PI * atan(k * (root + 1 / (2 * q)))
        return low to high
    }
    data class Complex(val real: Double, val imaginary: Double) {
        operator fun plus(b: Complex) = Complex(real + b.real, imaginary + b.imaginary)
        operator fun minus(b: Complex) = Complex(real - b.real, imaginary - b.imaginary)
        operator fun times(b: Complex) = Complex(real*b.real-imaginary*b.imaginary, real*b.imaginary+imaginary*b.real)
        operator fun times(a: Double) = Complex(real*a, imaginary*a)
        operator fun div(b: Complex): Complex { val d=b.real*b.real+b.imaginary*b.imaginary; return Complex((real*b.real+imaginary*b.imaginary)/d,(imaginary*b.real-real*b.imaginary)/d) }
        fun db() = 10 * log10((real*real+imaginary*imaginary).coerceAtLeast(1e-24))
        companion object { val ONE=Complex(1.0,0.0) }
    }
    data class Biquad(val b0: Double,val b1: Double,val b2: Double,val a0: Double,val a1: Double,val a2: Double,val rate: Int) {
        fun at(hz: Double): Complex { val w=2*PI*hz/rate; return Complex(b0+b1*cos(w)+b2*cos(2*w),-b1*sin(w)-b2*sin(2*w)) / Complex(a0+a1*cos(w)+a2*cos(2*w),-a1*sin(w)-a2*sin(2*w)) }
        companion object {
            fun lowPass(hz: Double,q: Double,rate: Int): Biquad { val w=2*PI*hz/rate;val c=cos(w);val a=sin(w)/(2*q);return Biquad((1-c)*.5,1-c,(1-c)*.5,1+a,-2*c,1-a,rate) }
            fun peak(hz: Double,db: Double,bw: Double,rate: Int): Biquad { val w=2*PI*hz/rate;val s=sin(w);val a=sinh(bw*ln(2.0)*w/(2*s))*s;val g=10.0.pow(db/40);return Biquad(1+a*g,-2*cos(w),1-a*g,1+a/g,-2*cos(w),1-a/g,rate) }
        }
    }
    companion object {
        private var cached: BassLinkedModel? = null
        @Synchronized fun from(source: Map<String,*>, actualRate: Int?): BassLinkedModel {
            val numbers=BassParameters.viper.map { BassParameters.value(source,it) }
            val mode=(source["v_bass_mode"] as? Number)?.toInt()?.coerceIn(0,2) ?: 0
            val enabled=source["v_bass"] as? Boolean ?: false
            val fs=actualRate?.takeIf { it in 8000..768000 }
            cached?.takeIf { it.values==numbers && it.mode==mode && it.enabled==enabled && it.rate==(fs ?: 48000) && it.actualRate==(fs!=null) }?.let { return it }
            return BassLinkedModel(numbers,mode,enabled,fs ?: 48000,fs!=null).also { cached=it }
        }
        fun focus(key: String) = when(key) {
            BassParameters.frequency.key,BassParameters.naturalQ.key -> Focus.LOWPASS
            BassParameters.amount.key,BassParameters.LEGACY_AMOUNT -> Focus.GAIN
            BassParameters.deepFrequency.key,BassParameters.deepGain.key,BassParameters.deepWidth.key -> Focus.DEEP
            BassParameters.punchFrequency.key,BassParameters.punchGain.key,BassParameters.punchWidth.key -> Focus.PUNCH
            else -> Focus.MODE
        }
    }
}
