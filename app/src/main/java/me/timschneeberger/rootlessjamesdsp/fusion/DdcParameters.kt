package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/** Personal correction is a separate layer; a VDC document is never rewritten. */
object DdcParameters {
    const val EXTENDED_CAPABILITIES=131234
    const val CAPABILITY=2
    const val PACKET=196648
    const val TOOLS=196649
    const val CLEAR=196650
    const val OFF=196651
    const val ORIGINAL=196652
    const val RUNTIME=131238
    const val RESPONSE_PACKET=131239
    const val SNAPSHOT=131240
    const val NAMESPACE = Constants.PREF_DDC
    const val ENABLED = "ddc_enable"
    const val FILE = "ddc_file"
    const val PROFESSIONAL = "ddc_pro_professional"
    const val RATIO_ON = "ddc_pro_ratio_on"
    const val COMPENSATION_ON = "ddc_pro_compensation_on"
    const val LOW_ON = "ddc_pro_low_on"
    const val HIGH_ON = "ddc_pro_high_on"
    const val PEAK1_ON = "ddc_pro_peak1_on"
    const val PEAK2_ON = "ddc_pro_peak2_on"
    const val EAR_ON = "ddc_pro_ear_on"
    const val LINKED = "ddc_pro_linked"
    const val PREAMP_ON = "ddc_pro_preamp_on"
    const val AUTO_HEADROOM_ON = "ddc_pro_auto_headroom_on"
    const val OUTPUT_ON = "ddc_pro_output_on"
    const val METERS = "ddc_pro_meters"
    const val RESPONSE = "ddc_pro_response"
    val ratio = BassParameters.Control("ddc_pro_ratio", "校正混合比例", 0, 1000, 1000, 10, "%", 1)
    val lowFrequency = BassParameters.Control("ddc_pro_low_frequency", "低频架式频率", 20, 1000, 120, 1, "Hz", 0)
    val lowGain = BassParameters.Control("ddc_pro_low_gain", "低频架式增益", -120, 120, 0, 10, "dB", 1)
    val lowSlope = BassParameters.Control("ddc_pro_low_slope", "低频架式坡度 S", 250, 1000, 1000, 1000, decimals = 3)
    val highFrequency = BassParameters.Control("ddc_pro_high_frequency", "高频架式频率", 1000, 20000, 8000, 1, "Hz", 0)
    val highGain = BassParameters.Control("ddc_pro_high_gain", "高频架式增益", -120, 120, 0, 10, "dB", 1)
    val highSlope = BassParameters.Control("ddc_pro_high_slope", "高频架式坡度 S", 250, 1000, 1000, 1000, decimals = 3)
    val peak1Frequency = BassParameters.Control("ddc_pro_peak1_frequency", "峰式 1 频率", 20, 20000, 1000, 1, "Hz", 0)
    val peak1Gain = BassParameters.Control("ddc_pro_peak1_gain", "峰式 1 增益", -120, 120, 0, 10, "dB", 1)
    val peak1Q = BassParameters.Control("ddc_pro_peak1_q", "峰式 1 Q", 25, 1000, 100, 100, decimals = 2)
    val peak2Frequency = BassParameters.Control("ddc_pro_peak2_frequency", "峰式 2 频率", 20, 20000, 6000, 1, "Hz", 0)
    val peak2Gain = BassParameters.Control("ddc_pro_peak2_gain", "峰式 2 增益", -120, 120, 0, 10, "dB", 1)
    val peak2Q = BassParameters.Control("ddc_pro_peak2_q", "峰式 2 Q", 25, 1000, 100, 100, decimals = 2)
    val leftRatio = BassParameters.Control("ddc_pro_left_ratio", "左耳校正比例权重", 0, 1000, 1000, 10, "%", 1)
    val rightRatio = BassParameters.Control("ddc_pro_right_ratio", "右耳校正比例权重", 0, 1000, 1000, 10, "%", 1)
    val leftLevel = BassParameters.Control("ddc_pro_left_level", "左耳电平", -120, 120, 0, 10, "dB", 1)
    val rightLevel = BassParameters.Control("ddc_pro_right_level", "右耳电平", -120, 120, 0, 10, "dB", 1)
    val preamp = BassParameters.Control("ddc_pro_preamp", "前级衰减", -480, 0, 0, 10, "dB", 1)
    val output = BassParameters.Control("ddc_pro_output", "输出增益", -240, 120, 0, 10, "dB", 1)
    val margin = BassParameters.Control("ddc_pro_margin", "预测余量安全边际", 0, 120, 10, 10, "dB", 1)
    val lowNumbers = listOf(lowFrequency, lowGain, lowSlope)
    val highNumbers = listOf(highFrequency, highGain, highSlope)
    val peak1Numbers = listOf(peak1Frequency, peak1Gain, peak1Q)
    val peak2Numbers = listOf(peak2Frequency, peak2Gain, peak2Q)
    val compensationNumbers = lowNumbers + highNumbers + peak1Numbers + peak2Numbers
    val earNumbers = listOf(leftRatio, rightRatio, leftLevel, rightLevel)
    val numbers = listOf(ratio) + compensationNumbers + earNumbers + listOf(preamp, output, margin)
    val switches = linkedSetOf(PROFESSIONAL, RATIO_ON, COMPENSATION_ON, LOW_ON, HIGH_ON, PEAK1_ON, PEAK2_ON, EAR_ON, LINKED, PREAMP_ON, AUTO_HEADROOM_ON, OUTPUT_ON, METERS, RESPONSE)
    val newKeys = numbers.map { it.key }.toSet() + switches
    val keys = newKeys + FILE
    fun on(values: Map<String, *>, key: String) = values[key] as? Boolean ?: (key == LINKED)
    fun enabled(values: Map<String, *>) = on(values, PROFESSIONAL)
    fun moduleEnabled(values: Map<String, *>) = values[ENABLED] as? Boolean ?: false
    fun value(values: Map<String, *>, control: BassParameters.Control) = (values[control.key] as? Int)?.takeIf { it in control.min..control.max } ?: control.default
    fun validSavedFoundation(values: Map<String, *>) = (!values.containsKey(FILE) || values[FILE] is String) && (!values.containsKey(ENABLED) || values[ENABLED] is Boolean)
    data class Settings(
        val professional: Boolean = false, val ratioOn: Boolean = false, val ratio: Int = 1000,
        val compensationOn: Boolean = false,
        val lowOn: Boolean = false, val lowFrequency: Int = 120, val lowGain: Int = 0, val lowSlope: Int = 1000,
        val highOn: Boolean = false, val highFrequency: Int = 8000, val highGain: Int = 0, val highSlope: Int = 1000,
        val peak1On: Boolean = false, val peak1Frequency: Int = 1000, val peak1Gain: Int = 0, val peak1Q: Int = 100,
        val peak2On: Boolean = false, val peak2Frequency: Int = 6000, val peak2Gain: Int = 0, val peak2Q: Int = 100,
        val earOn: Boolean = false, val linked: Boolean = true, val leftRatio: Int = 1000, val rightRatio: Int = 1000, val leftLevel: Int = 0, val rightLevel: Int = 0,
        val preampOn: Boolean = false, val preamp: Int = 0, val autoHeadroomOn: Boolean = false, val outputOn: Boolean = false, val output: Int = 0, val margin: Int = 10,
        val meters: Boolean = false, val response: Boolean = false,
    ) {
        fun valid() = values().let { v -> numbers.all { (v[it.key] as Int) in it.min..it.max } }
        fun packet(enabled: Boolean=false, sha256: String?=null): IntArray {
            require(valid())
            return intArrayOf(1,if(professional)1 else 0,if(enabled)1 else 0,if(ratioOn)1 else 0,ratio,
                if(compensationOn)1 else 0,if(lowOn)1 else 0,lowFrequency,lowGain,lowSlope,
                if(highOn)1 else 0,highFrequency,highGain,highSlope,
                if(peak1On)1 else 0,peak1Frequency,peak1Gain,peak1Q,
                if(peak2On)1 else 0,peak2Frequency,peak2Gain,peak2Q,
                if(earOn)1 else 0,if(linked)1 else 0,leftRatio,rightRatio,leftLevel,rightLevel,
                if(preampOn)1 else 0,preamp,if(outputOn)1 else 0,output,
                if(autoHeadroomOn)1 else 0,margin,if(meters)1 else 0,if(response)1 else 0) + shaWords(sha256) + IntArray(20)
        }
        fun values(): Map<String, Any> = linkedMapOf(
            PROFESSIONAL to professional, RATIO_ON to ratioOn, DdcParameters.ratio.key to ratio,
            COMPENSATION_ON to compensationOn,
            LOW_ON to lowOn, DdcParameters.lowFrequency.key to lowFrequency, DdcParameters.lowGain.key to lowGain, DdcParameters.lowSlope.key to lowSlope,
            HIGH_ON to highOn, DdcParameters.highFrequency.key to highFrequency, DdcParameters.highGain.key to highGain, DdcParameters.highSlope.key to highSlope,
            PEAK1_ON to peak1On, DdcParameters.peak1Frequency.key to peak1Frequency, DdcParameters.peak1Gain.key to peak1Gain, DdcParameters.peak1Q.key to peak1Q,
            PEAK2_ON to peak2On, DdcParameters.peak2Frequency.key to peak2Frequency, DdcParameters.peak2Gain.key to peak2Gain, DdcParameters.peak2Q.key to peak2Q,
            EAR_ON to earOn, LINKED to linked, DdcParameters.leftRatio.key to leftRatio, DdcParameters.rightRatio.key to rightRatio,
            DdcParameters.leftLevel.key to leftLevel, DdcParameters.rightLevel.key to rightLevel,
            PREAMP_ON to preampOn, DdcParameters.preamp.key to preamp, AUTO_HEADROOM_ON to autoHeadroomOn, OUTPUT_ON to outputOn, DdcParameters.output.key to output, DdcParameters.margin.key to margin,
            METERS to meters, RESPONSE to response,
        )
    }
    fun defaults() = Settings()
    fun capture(v: Map<String, *>) = Settings(
        professional=on(v,PROFESSIONAL),ratioOn=on(v,RATIO_ON),ratio=value(v,ratio),compensationOn=on(v,COMPENSATION_ON),
        lowOn=on(v,LOW_ON),lowFrequency=value(v,lowFrequency),lowGain=value(v,lowGain),lowSlope=value(v,lowSlope),
        highOn=on(v,HIGH_ON),highFrequency=value(v,highFrequency),highGain=value(v,highGain),highSlope=value(v,highSlope),
        peak1On=on(v,PEAK1_ON),peak1Frequency=value(v,peak1Frequency),peak1Gain=value(v,peak1Gain),peak1Q=value(v,peak1Q),
        peak2On=on(v,PEAK2_ON),peak2Frequency=value(v,peak2Frequency),peak2Gain=value(v,peak2Gain),peak2Q=value(v,peak2Q),
        earOn=on(v,EAR_ON),linked=on(v,LINKED),leftRatio=value(v,leftRatio),rightRatio=value(v,rightRatio),leftLevel=value(v,leftLevel),rightLevel=value(v,rightLevel),
        preampOn=on(v,PREAMP_ON),preamp=value(v,preamp),autoHeadroomOn=on(v,AUTO_HEADROOM_ON),outputOn=on(v,OUTPUT_ON),output=value(v,output),margin=value(v,margin),meters=on(v,METERS),response=on(v,RESPONSE),
    )
    fun numberEnabled(key: String, v: Map<String, *>) = enabled(v) && when (key) {
        ratio.key -> on(v,RATIO_ON)
        in lowNumbers.map { it.key } -> on(v,COMPENSATION_ON) && on(v,LOW_ON)
        in highNumbers.map { it.key } -> on(v,COMPENSATION_ON) && on(v,HIGH_ON)
        in peak1Numbers.map { it.key } -> on(v,COMPENSATION_ON) && on(v,PEAK1_ON)
        in peak2Numbers.map { it.key } -> on(v,COMPENSATION_ON) && on(v,PEAK2_ON)
        rightRatio.key,rightLevel.key -> on(v,EAR_ON) && !on(v,LINKED)
        leftRatio.key,leftLevel.key -> on(v,EAR_ON)
        preamp.key -> on(v,PREAMP_ON)
        output.key -> on(v,OUTPUT_ON)
        margin.key -> on(v,AUTO_HEADROOM_ON)
        else -> false
    }
    fun paired(control: BassParameters.Control) = when(control) {leftRatio->rightRatio;rightRatio->leftRatio;leftLevel->rightLevel;rightLevel->leftLevel;else->null}
    fun put(e: SharedPreferences.Editor, values: Map<String,*>) { values.forEach { (k,v) -> when(v) {is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is String->e.putString(k,v)} } }
    fun apply(p: SharedPreferences, s: Settings) { require(s.valid()); val e=p.edit();put(e,s.values());e.apply() }
    fun setProfessional(p: SharedPreferences, on: Boolean) {p.edit().putBoolean(PROFESSIONAL,on).apply()}
    fun normalize(p: SharedPreferences) {val old=p.all;val changes=capture(old).values().filter {(k,v)->old.containsKey(k) && old[k]!=v};if(changes.isNotEmpty()){val e=p.edit();put(e,changes);e.apply()}}
    fun shaWords(sha256: String?): IntArray {
        if(sha256==null) return IntArray(8)
        require(Regex("[0-9a-f]{64}").matches(sha256))
        val bytes=sha256.chunked(2).map {it.toInt(16)}
        return IntArray(8) {i->(0..3).fold(0) {n,j->n or (bytes[i*4+j] shl (j*8))}}
    }
    fun sha256(words: List<Int>): String? {
        if(words.size!=8 || words.all {it==0})return null
        return words.flatMap {n->(0..3).map {j->"%02x".format(java.util.Locale.ROOT,n ushr (j*8) and 255)}}.joinToString("")
    }
    data class Runtime(val data: List<Int>) {
        val mode get()=data[6];val measurement get()=data[8];val epoch get()=data[12]
        val enabled get()=data[3]==1;val professional get()=data[4]==1;val ready get()=data[15]==1
        val rate get()=data[2];val fileReady get()=data[5]==1
        val activeSha256 get()=sha256(data.subList(36,44));val acceptedSha256 get()=sha256(data.subList(62,70))
    }
    private fun rate(n: Int)=n==0 || n in 8000..768000
    fun decode(v: IntArray): Runtime?=v.takeIf {
        it.size==80 && it[0]==1 && it[1]>=0 && it[1] and 1==0 && rate(it[2]) && rate(it[16]) &&
        listOf(3,4,5,7,13,14,15,50,52,53,54,55,56,57,58,60).all {i->it[i] in 0..1} &&
        it[6] in 0..6 && it[8] in 0..4 && it[9] in 0..2000 && it[10] in -36000..0 && it[11] in -36000..0 && it[12]>=0 &&
        rate(it[17]) && it[18] in 0..128 && it[19]>=0 && (20..27).all {i->it[i]==Int.MIN_VALUE || it[i] in -36000..36000} &&
        it[28]>=0 && it[29] in -36000..0 && it[30] in -480..0 && it[31] in -240..120 && it[32] in 0..1000 && it[33] in 0..1000 &&
        it[34] in -120..120 && it[35] in -120..120 && (44..47).all {i->it[i] in 0..it[2]/2} &&
        it[48] in 0..60000 && it[49] in 0..60000 && it[51] in 0..16 && it[59] in -36000..36000 && it[61]>=0 && (70..79).all {i->it[i]==0}
    }?.let {Runtime(it.toList())}
    data class Response(val data: List<Int>) {val ready get()=data[3]==1;val rate get()=data[2];val points get()=data.drop(8).chunked(7)}
    fun decodeResponse(v: IntArray): Response?=v.takeIf {
        it.size==232 && it[0]==1 && it[1]>=0 && it[1] and 1==0 && rate(it[2]) && it[3] in 0..1 && it[4]==32 && (5..7).all {i->it[i]==0} &&
        (0 until 32).all {p->val i=8+p*7;it[i] in 0..it[2]/2 && (1..3).all {j->it[i+j] in -36000..36000} && it[i+4]==0 && it[i+5]==0 && it[i+6] in 0..1} &&
        (it[3]==0 || (0 until 31).all {p->it[8+p*7]<it[15+p*7]})
    }?.let {Response(it.toList())}
}
