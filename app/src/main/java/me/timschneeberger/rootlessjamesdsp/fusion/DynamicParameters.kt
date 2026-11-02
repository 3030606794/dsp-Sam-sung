package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Preset-relative fine controls. Legacy preset/strength remain the audible foundation. */
object DynamicParameters {
    const val NAMESPACE = ViperParameters.PREFS
    const val CAPABILITY = 268435456
    const val PACKET = 65655
    const val TOOLS = 65656
    const val CLEAR = 65657
    const val RUNTIME = 131228
    const val RESPONSE_PACKET = 131229
    const val SNAPSHOT = 131230
    const val ENABLED = "v_dynamic"
    const val BASE = "v_dynamic_preset"
    const val LEGACY = "v_dynamic_strength"
    const val PROFESSIONAL = "dynamic_pro_on"
    const val CORE_ON = "dynamic_pro_core_on"
    const val TONE_ON = "dynamic_pro_tone_on"
    const val ADAPTIVE_ON = "dynamic_pro_adaptive_on"
    const val DETECTOR = "dynamic_pro_detector"
    const val MIX_ON = "dynamic_pro_mix_on"
    const val OUTPUT_ON = "dynamic_pro_output_on"
    const val METERS = "dynamic_pro_meters_on"
    const val RESPONSE = "dynamic_pro_response_on"
    val bassDelta = BassParameters.Control("dynamic_pro_bass_delta", "低频整体增益修正", -120, 120, 0, 10, "dB", 1)
    val lowGainDelta = BassParameters.Control("dynamic_pro_low_gain_delta", "低频支路低段比例修正", -1000, 1000, 0, 10, "百分点", 1)
    val highGainDelta = BassParameters.Control("dynamic_pro_high_gain_delta", "低频支路高段比例修正", -1000, 1000, 0, 10, "百分点", 1)
    val injectDelta = BassParameters.Control("dynamic_pro_inject_delta", "深低音注入量修正", -1000, 1000, 0, 10, "百分点", 1)
    val qDelta = BassParameters.Control("dynamic_pro_q_delta", "深低音共振 Q 修正", -240, 240, 0, 100, decimals = 2)
    val xDelta = BassParameters.Control("dynamic_pro_x_delta_hz", "主分带基准修正", -1900, 1900, 0, unit = "Hz")
    val lowFreqDelta = BassParameters.Control("dynamic_pro_low_freq_delta_hz", "支路下分界修正", -500, 500, 0, unit = "Hz")
    val highFreqDelta = BassParameters.Control("dynamic_pro_high_freq_delta_hz", "支路上分界修正", -1000, 1000, 0, unit = "Hz")
    val deepFreqDelta = BassParameters.Control("dynamic_pro_deep_freq_delta_hz", "深低音频率修正", -35, 145, 0, unit = "Hz")
    val hp = BassParameters.Control("dynamic_pro_hp_hz", "增强支路低切", 0, 300, 0, unit = "Hz")
    val lp = BassParameters.Control("dynamic_pro_lp_hz", "增强支路高切", 0, 20000, 0, unit = "Hz")
    val detectorHP = BassParameters.Control("dynamic_pro_detector_hp_hz", "检测频段下限", 20, 500, 20, unit = "Hz")
    val detectorLP = BassParameters.Control("dynamic_pro_detector_lp_hz", "检测频段上限", 60, 2000, 250, unit = "Hz")
    val threshold = BassParameters.Control("dynamic_pro_threshold_tenths", "回收阈值", -600, -60, -180, 10, "dBFS", 1)
    val knee = BassParameters.Control("dynamic_pro_knee_tenths", "软拐点宽度", 0, 120, 60, 10, "dB", 1)
    val maxReduction = BassParameters.Control("dynamic_pro_max_reduction_tenths", "最大增强回收量", 0, 180, 60, 10, "dB", 1)
    val attack = BassParameters.Control("dynamic_pro_attack_tenths_ms", "攻击时间", 10, 2000, 100, 10, "ms", 1)
    val release = BassParameters.Control("dynamic_pro_release_ms", "释放时间", 50, 2000, 250, unit = "ms")
    val mix = BassParameters.Control("dynamic_pro_mix_tenths", "效果比例", 0, 1000, 1000, 10, "%", 1)
    val output = BassParameters.Control("dynamic_pro_output_tenths", "模块输出增益", -240, 0, 0, 10, "dB", 1)
    val coreNumbers = listOf(bassDelta, lowGainDelta, highGainDelta, injectDelta, qDelta)
    val toneNumbers = listOf(xDelta, lowFreqDelta, highFreqDelta, deepFreqDelta, hp, lp)
    val adaptiveNumbers = listOf(detectorHP, detectorLP, threshold, knee, maxReduction, attack, release)
    val numbers = coreNumbers + toneNumbers + adaptiveNumbers + mix + output
    val switches = linkedSetOf(PROFESSIONAL, CORE_ON, TONE_ON, ADAPTIVE_ON, MIX_ON, OUTPUT_ON, METERS, RESPONSE)
    val newKeys = numbers.map { it.key }.toSet() + switches + DETECTOR
    val keys get() = newKeys + BASE + LEGACY
    private val foundations = listOf(intArrayOf(140,6200,40,60,10,80), intArrayOf(180,5800,55,80,10,70),
        intArrayOf(300,5600,60,105,10,50), intArrayOf(600,5400,60,105,10,20), intArrayOf(100,5600,40,80,50,50), intArrayOf(1200,6200,40,80,0,20))
    fun foundation(base: Int) = foundations[base.coerceIn(0, 5)].copyOf()
    fun on(v: Map<String, *>, key: String) = v[key] as? Boolean ?: false
    fun enabled(v: Map<String, *>) = on(v, PROFESSIONAL)
    fun moduleEnabled(v: Map<String, *>) = on(v, ENABLED)
    fun base(v: Map<String, *>) = (v[BASE] as? String)?.toIntOrNull()?.takeIf { it in 0..5 } ?: 0
    fun legacy(v: Map<String, *>) = (v[LEGACY] as? Int)?.takeIf { it in 0..100 } ?: 0
    fun detector(v: Map<String, *>) = (v[DETECTOR] as? Int)?.takeIf { it in 0..1 } ?: 1
    fun validValue(c: BassParameters.Control, n: Int) = n in c.min..c.max && when(c.key) {
        hp.key -> n == 0 || n >= 10
        lp.key -> n == 0 || n >= 100
        else -> true
    }
    fun value(v: Map<String, *>, c: BassParameters.Control) = (v[c.key] as? Int)?.takeIf { validValue(c,it) } ?: c.default
    data class Settings(
        val professional: Boolean = false, val base: Int = 0, val strength: Int = 0,
        val coreOn: Boolean = false, val bassDelta: Int = 0, val lowGainDelta: Int = 0, val highGainDelta: Int = 0,
        val injectDelta: Int = 0, val qDelta: Int = 0, val toneOn: Boolean = false, val xDelta: Int = 0,
        val lowFreqDelta: Int = 0, val highFreqDelta: Int = 0, val deepFreqDelta: Int = 0, val hp: Int = 0, val lp: Int = 0,
        val adaptiveOn: Boolean = false, val detector: Int = 1, val detectorHP: Int = 20, val detectorLP: Int = 250,
        val threshold: Int = -180, val knee: Int = 60, val maxReduction: Int = 60, val attack: Int = 100, val release: Int = 250,
        val mixOn: Boolean = false, val mix: Int = 1000, val outputOn: Boolean = false, val output: Int = 0,
        val meters: Boolean = false, val response: Boolean = false,
    ) {
        private fun nums() = listOf(bassDelta,lowGainDelta,highGainDelta,injectDelta,qDelta,xDelta,lowFreqDelta,
            highFreqDelta,deepFreqDelta,hp,lp,detectorHP,detectorLP,threshold,knee,maxReduction,attack,release,mix,output)
        fun valid() = base in 0..5 && strength in 0..100 && detector in 0..1 && nums().zip(numbers).all { (v,c) -> validValue(c,v) } &&
            (hp == 0 || lp == 0 || hp < lp) && detectorHP < detectorLP
        fun packet() = intArrayOf(1,flag(professional),base,strength,flag(coreOn),bassDelta,lowGainDelta,highGainDelta,injectDelta,qDelta,
            flag(toneOn),xDelta,lowFreqDelta,highFreqDelta,deepFreqDelta,hp,lp,flag(adaptiveOn),detector,detectorHP,detectorLP,
            threshold,knee,maxReduction,attack,release,flag(mixOn),mix,flag(outputOn),output,flag(meters),flag(response),0,0,0,0)
        fun values(): Map<String, Any> = buildMap {
            numbers.zip(nums()).forEach { (c,v) -> put(c.key,v) }
            put(BASE,base.toString()); put(LEGACY,strength); put(PROFESSIONAL,professional); put(CORE_ON,coreOn)
            put(TONE_ON,toneOn); put(ADAPTIVE_ON,adaptiveOn); put(DETECTOR,detector); put(MIX_ON,mixOn)
            put(OUTPUT_ON,outputOn); put(METERS,meters); put(RESPONSE,response)
        }
    }
    private fun flag(v: Boolean) = if(v) 1 else 0
    fun defaults() = Settings()
    fun capture(v: Map<String, *>): Settings {
        var s = Settings(enabled(v),base(v),legacy(v),on(v,CORE_ON),value(v,bassDelta),value(v,lowGainDelta),value(v,highGainDelta),
            value(v,injectDelta),value(v,qDelta),on(v,TONE_ON),value(v,xDelta),value(v,lowFreqDelta),value(v,highFreqDelta),
            value(v,deepFreqDelta),value(v,hp),value(v,lp),on(v,ADAPTIVE_ON),detector(v),value(v,detectorHP),value(v,detectorLP),
            value(v,threshold),value(v,knee),value(v,maxReduction),value(v,attack),value(v,release),on(v,MIX_ON),value(v,mix),
            on(v,OUTPUT_ON),value(v,output),on(v,METERS),on(v,RESPONSE))
        if(s.hp != 0 && s.lp != 0 && s.hp >= s.lp) s=s.copy(hp=0,lp=0)
        if(s.detectorHP >= s.detectorLP) s=s.copy(detectorHP=20,detectorLP=250)
        return s
    }
    fun packet(v: Map<String,*>) = capture(v).packet()
    /** Only a user edit of a Y boundary rejects crossing; switching the foundation retains offsets. */
    fun candidateValid(v: Map<String,*>, control: BassParameters.Control, next: Int): Boolean {
        if(!validValue(control,next)) return false
        val merged = buildMap<String, Any?> { v.forEach { (key,value) -> put(key,value) }; put(control.key,next) }
        if(control == hp || control == lp) {
            val l=value(merged,hp); val h=value(merged,lp); if(l != 0 && h != 0 && l >= h) return false
        }
        if(control == detectorHP || control == detectorLP) if(value(merged,detectorHP) >= value(merged,detectorLP)) return false
        if(control == lowFreqDelta || control == highFreqDelta) {
            val f=foundation(base(v)); if(f[2]+value(merged,lowFreqDelta) >= f[3]+value(merged,highFreqDelta)) return false
        }
        return true
    }
    fun numberEnabled(key: String, v: Map<String,*>) = enabled(v) && when(key) {
        bassDelta.key,lowGainDelta.key,highGainDelta.key -> on(v,CORE_ON) && base(v) != 4
        injectDelta.key,qDelta.key -> on(v,CORE_ON) && base(v) == 4
        xDelta.key,lowFreqDelta.key,highFreqDelta.key -> on(v,TONE_ON) && base(v) != 4
        deepFreqDelta.key -> on(v,TONE_ON) && base(v) == 4
        hp.key,lp.key -> on(v,TONE_ON)
        in adaptiveNumbers.map { it.key } -> on(v,ADAPTIVE_ON)
        mix.key -> on(v,MIX_ON)
        output.key -> on(v,OUTPUT_ON)
        else -> false
    }
    fun apply(p: SharedPreferences,s: Settings) {
        require(s.valid()); val e=p.edit(); s.values().forEach { (k,v) -> when(v) {
            is Boolean -> e.putBoolean(k,v); is Int -> e.putInt(k,v); is String -> e.putString(k,v)
        } }; e.apply()
    }
    fun setProfessional(p: SharedPreferences,on: Boolean) { p.edit().putBoolean(PROFESSIONAL,on).apply() }
    fun normalize(p: SharedPreferences) {
        val all=p.all; val changes=capture(all).values().filter { (k,v) -> k in newKeys && all.containsKey(k) && all[k] != v }
        if(changes.isEmpty()) return
        val e=p.edit(); changes.forEach { (k,v) -> when(v) { is Boolean -> e.putBoolean(k,v); is Int -> e.putInt(k,v) } }; e.apply()
    }
    data class Runtime(val data: List<Int>) {
        val mode get()=data[5]; val measurement get()=data[7]; val epoch get()=data[11]
        val enabled get()=data[3]==1; val professional get()=data[4]==1; val ready get()=data[25]==1
    }
    fun decode(v: IntArray): Runtime? = v.takeIf {
        it.size==48 && it[0]==1 && it[1]>=0 && it[1] and 1==0 && (it[2]==0 || it[2] in 8000..768000) &&
            listOf(3,4,6,12,15,25,46).all { i -> it[i] in 0..1 } && it[5] in 0..8 && it[7] in 0..4 &&
            it[8] in 0..2000 && it[9] in -18000..0 && it[10] in -18000..0 && it[11]>=0 && it[13] in 0..5 && it[14] in 0..100 &&
            (16..23).all { i -> it[i]==Int.MIN_VALUE || it[i] in -20000..20000 } && it[24]>=0 &&
            (it[26]==0 || it[26] in 8000..768000) && it[27] in 0..1800 && (it[28]==Int.MIN_VALUE || it[28] in -20000..20000) &&
            it[29] in 0..60000 && it[30] in 0..60000 && it[31]==2000 && (32..37).all { i -> it[i] in 0..it[2]/2 } &&
            it[38] in -18000..6000 && (39..41).all { i -> it[i] in 0..2000 } && (it[42]==0 || it[42] in 100..4000) &&
            (43..44).all { i -> it[i] in 0..it[2]/2 } && (it[45]==Int.MIN_VALUE || it[45] in -20000..20000) && it[47] in 0..3
    }?.let { Runtime(it.toList()) }
    data class Response(val data: List<Int>) { val ready get()=data[5]==1; val rate get()=data[2]; val points get()=data.drop(8).chunked(6) }
    fun decodeResponse(v: IntArray): Response? = v.takeIf {
        it.size==200 && it[0]==1 && it[1]>=0 && it[1] and 1==0 && (it[2]==0 || it[2] in 8000..768000) &&
            it[3] in 0..1 && it[4] in 0..1800 && it[5] in 0..1 && it[6]==32 && it[7]==0 &&
            (0 until 32).all { p -> val i=8+p*6; it[i] in 0..it[2]/2 &&
                listOf(1,3,5).all { d -> it[i+d]==Int.MIN_VALUE || it[i+d] in -20000..20000 } &&
                listOf(2,4).all { d -> it[i+d] in -180000..180000 } } &&
            (it[5]==0 || (0 until 31).all { p -> it[8+p*6] < it[14+p*6] })
    }?.let { Response(it.toList()) }
}
