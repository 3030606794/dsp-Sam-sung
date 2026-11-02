package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Saved legacy values keep their exact original preference type and text. */
object CompanderParameters {
    const val NAMESPACE=Constants.PREF_COMPANDER
    const val CAPABILITY=1073741824
    const val PACKET=1430
    const val TOOLS=1431
    const val CLEAR=1432
    const val RUNTIME=131231
    const val RESPONSE_PACKET=131232
    const val SNAPSHOT=131233
    const val ENABLED="compander_enable"
    const val BASE_TIME="compander_timeconstant"
    const val BASE_GRANULARITY="compander_granularity"
    const val BASE_TRANSFORM="compander_tftransforms"
    const val BASE_CURVE="compander_response"
    const val DEFAULT_CURVE="95.0;200.0;400.0;800.0;1600.0;3400.0;7500.0;0;0;0;0;0;0;0"
    const val PROFESSIONAL="compander_pro_professional"
    const val CORE_ON="compander_pro_core_on"
    const val LIMITS_ON="compander_pro_limits_on"
    const val TIME_ON="compander_pro_time_on"
    const val DETECT_ON="compander_pro_detect_on"
    const val MIX_ON="compander_pro_mix_on"
    const val OUTPUT_ON="compander_pro_output_on"
    const val SIDECHAIN_ON="compander_pro_sidechain_on"
    const val LOOKAHEAD_ON="compander_pro_lookahead_on"
    const val METERS="compander_pro_meters"
    const val RESPONSE="compander_pro_response"
    const val EXACT_FREQUENCY="compander_pro_exact_frequency"
    const val ENGINE="compander_pro_engine"
    const val TYPE="compander_pro_type"
    const val LINKED="compander_pro_linked"
    const val DETECTOR="compander_pro_detector"
    val amount=BassParameters.Control("compander_pro_amount","总作用强度",0,2000,1000,10,"%",1)
    val maxBoost=BassParameters.Control("compander_pro_max_boost","最大提升量",0,240,120,10,"dB",1)
    val maxCut=BassParameters.Control("compander_pro_max_cut","最大衰减量",0,480,240,10,"dB",1)
    val backgroundDelta=BassParameters.Control("compander_pro_background_delta","背景跟随时间修正",-20000,20000,0,10,"ms",1)
    val attack=BassParameters.Control("compander_pro_attack","攻击时间",1,2000,100,10,"ms",1)
    val hold=BassParameters.Control("compander_pro_hold","保持时间",0,500,0,1,"ms",0)
    val release=BassParameters.Control("compander_pro_release","释放时间",10,2000,250,1,"ms",0)
    val relative=BassParameters.Control("compander_pro_relative","相对触发差值",0,240,0,10,"dB",1)
    val knee=BassParameters.Control("compander_pro_knee","相对软拐点宽度",0,240,0,10,"dB",1)
    val weakFloor=BassParameters.Control("compander_pro_weak_floor","弱信号保护电平",-1000,-300,-800,10,"dBFS",1)
    val rangeLow=BassParameters.Control("compander_pro_range_low","处理频段下限",0,20000,0,1,"Hz",0)
    val rangeHigh=BassParameters.Control("compander_pro_range_high","处理频段上限",0,20000,0,1,"Hz",0)
    val mix=BassParameters.Control("compander_pro_mix","效果比例",0,1000,1000,10,"%",1)
    val output=BassParameters.Control("compander_pro_output","模块输出增益",-240,0,0,10,"dB",1)
    val threshold=BassParameters.Control("compander_pro_threshold","固定阈值",-800,0,-180,10,"dBFS",1)
    val ratio=BassParameters.Control("compander_pro_ratio","压扩比率",100,2000,200,100,":1",2)
    val standardKnee=BassParameters.Control("compander_pro_standard_knee","标准软拐点宽度",0,240,60,10,"dB",1)
    val rmsWindow=BassParameters.Control("compander_pro_rms_window","RMS 时间窗",1,100,20,1,"ms",0)
    val sideHP=BassParameters.Control("compander_pro_side_h_p","检测侧链低切",0,1000,0,1,"Hz",0)
    val sideLP=BassParameters.Control("compander_pro_side_l_p","检测侧链高切",0,20000,0,1,"Hz",0)
    val lookahead=BassParameters.Control("compander_pro_lookahead","前瞻时间",0,200,0,10,"ms",1)
    val hysteresis=BassParameters.Control("compander_pro_hysteresis","噪声门回差",0,120,30,10,"dB",1)
    val frequencies=(1..7).map { BassParameters.Control("compander_pro_freq_delta_$it","第 $it 点频率修正",-20000000,20000000,0,1000,"Hz",3) }
    val coefficients=(1..7).map { BassParameters.Control("compander_pro_coefficient_delta_$it","第 $it 点动态系数修正",-2400,2400,0,1000,decimals=3) }
    val coreNumbers=listOf(amount)+frequencies+coefficients
    val limitNumbers=listOf(maxBoost,maxCut)
    val timeNumbers=listOf(backgroundDelta,attack,hold,release)
    val detectNumbers=listOf(relative,knee,weakFloor,rangeLow,rangeHigh)
    val standardNumbers=listOf(threshold,ratio,standardKnee,rmsWindow,hysteresis)
    val numbers=coreNumbers+limitNumbers+timeNumbers+detectNumbers+mix+output+standardNumbers+listOf(sideHP,sideLP,lookahead)
    val switches=linkedSetOf(PROFESSIONAL,CORE_ON,LIMITS_ON,TIME_ON,DETECT_ON,MIX_ON,OUTPUT_ON,SIDECHAIN_ON,LOOKAHEAD_ON,METERS,RESPONSE,EXACT_FREQUENCY)
    val choices=linkedSetOf(ENGINE,TYPE,LINKED,DETECTOR)
    val newKeys=numbers.map {it.key}.toSet()+switches+choices
    val keys=newKeys+setOf(BASE_TIME,BASE_GRANULARITY,BASE_TRANSFORM,BASE_CURVE)
    data class Curve(val frequencies: List<Double>,val coefficients: List<Double>) {
        fun values()=frequencies+coefficients
        fun text()=values().joinToString(";")
    }
    fun parseCurve(raw: String?): Curve? {
        if(raw==null || raw.length>2048) return null
        val text=if(raw.endsWith(";")) raw.dropLast(1) else raw
        val parts=text.split(';')
        if(parts.size!=14 || parts.any {it.isBlank()}) return null
        val values=parts.map {it.toDoubleOrNull()?.takeIf {n->n.isFinite()} ?: return null}
        val freq=values.take(7);val coeff=values.drop(7)
        if(freq.any {it<=0 || it>=24000} || freq.zipWithNext().any {(a,b)->a>=b} || coeff.any {it !in -1.2..1.2}) return null
        // The legacy wire protocol is FloatArray; reject values that become equal or invalid there.
        val wire=freq.map {it.toFloat()};if(wire.any {it<=0f || it>=24000f} || wire.zipWithNext().any {(a,b)->a>=b}) return null
        return Curve(freq,coeff)
    }
    fun on(v: Map<String,*>,key: String)=v[key] as? Boolean ?: false
    fun enabled(v: Map<String,*>)=on(v,PROFESSIONAL)
    fun moduleEnabled(v: Map<String,*>)=on(v,ENABLED)
    fun validSavedCurve(v: Map<String,*>)=!v.containsKey(BASE_CURVE) || parseCurve(v[BASE_CURVE] as? String)!=null
    fun validSavedFoundation(v: Map<String,*>)=validSavedCurve(v) &&
        (!v.containsKey(BASE_TIME) || (v[BASE_TIME] as? Float)?.let {it.isFinite() && it in .06f.. .3f}==true) &&
        (!v.containsKey(BASE_GRANULARITY) || (v[BASE_GRANULARITY] as? Float)?.let {it.isFinite() && it in 0f..4f}==true) &&
        (!v.containsKey(BASE_TRANSFORM) || v[BASE_TRANSFORM] is String && v[BASE_TRANSFORM] in listOf("0","1","2","3"))
    fun engine(v: Map<String,*>)=(v[ENGINE] as? Int)?.takeIf {it in 0..1} ?: 0
    fun type(v: Map<String,*>)=(v[TYPE] as? Int)?.takeIf {it in 0..3} ?: 0
    fun detector(v: Map<String,*>)=(v[DETECTOR] as? Int)?.takeIf {it in 0..1} ?: 1
    fun linked(v: Map<String,*>)=(v[LINKED] as? Int)?.takeIf {it in 0..1} ?: 0
    fun legacyTime(v: Map<String,*>)=(v[BASE_TIME] as? Float)?.takeIf {it.isFinite() && it in .06f.. .3f} ?: .22f
    fun legacyGranularity(v: Map<String,*>)=(v[BASE_GRANULARITY] as? Float)?.takeIf {it.isFinite() && it in 0f..4f} ?: 0f
    fun legacyTransform(v: Map<String,*>)=(v[BASE_TRANSFORM] as? String)?.takeIf {it in listOf("0","1","2","3")} ?: "0"
    fun legacyCurve(v: Map<String,*>)=(v[BASE_CURVE] as? String)?.takeIf {parseCurve(it)!=null} ?: DEFAULT_CURVE
    fun validValue(c: BassParameters.Control,n: Int)=n in c.min..c.max && when(c) {
        rangeLow,rangeHigh,sideHP -> n==0 || n>=20
        sideLP -> n==0 || n>=500
        else -> true
    }
    fun value(v: Map<String,*>,c: BassParameters.Control)=(v[c.key] as? Int)?.takeIf {validValue(c,it)} ?: c.default
    data class Settings(
        val legacyTime: Float=.22f,val legacyGranularity: Float=0f,val legacyTransform: String="0",val legacyCurve: String=DEFAULT_CURVE,
        val freqDeltas: List<Int> = List(7){0},val coefficientDeltas: List<Int> = List(7){0},
        val professional: Boolean=false,
        val engine: Int=0,
        val coreOn: Boolean=false,
        val amount: Int=1000,
        val limitsOn: Boolean=false,
        val maxBoost: Int=120,
        val maxCut: Int=240,
        val timeOn: Boolean=false,
        val backgroundDelta: Int=0,
        val attack: Int=100,
        val hold: Int=0,
        val release: Int=250,
        val detectOn: Boolean=false,
        val relative: Int=0,
        val knee: Int=0,
        val weakFloor: Int=-800,
        val linked: Int=0,
        val rangeLow: Int=0,
        val rangeHigh: Int=0,
        val mixOn: Boolean=false,
        val mix: Int=1000,
        val outputOn: Boolean=false,
        val output: Int=0,
        val type: Int=0,
        val threshold: Int=-180,
        val ratio: Int=200,
        val standardKnee: Int=60,
        val detector: Int=1,
        val rmsWindow: Int=20,
        val sidechainOn: Boolean=false,
        val sideHP: Int=0,
        val sideLP: Int=0,
        val lookaheadOn: Boolean=false,
        val lookahead: Int=0,
        val hysteresis: Int=30,
        val meters: Boolean=false,
        val response: Boolean=false,
        val exactFrequency: Boolean=false,
    ) {
        fun valid(): Boolean {
            if(!legacyTime.isFinite() || legacyTime !in .06f.. .3f || !legacyGranularity.isFinite() || legacyGranularity !in 0f..4f || legacyTransform !in listOf("0","1","2","3") || parseCurve(legacyCurve)==null || freqDeltas.size!=7 || coefficientDeltas.size!=7 || engine !in 0..1 || type !in 0..3 || linked !in 0..1 || detector !in 0..1) return false
            if(!numbers.all {c-> validValue(c,values()[c.key] as Int)}) return false
            return (rangeLow==0 || rangeHigh==0 || rangeLow<rangeHigh) && (sideHP==0 || sideLP==0 || sideHP<sideLP)
        }
        fun packet(): IntArray {
            val v=IntArray(72);v[0]=1;v[3]=legacyTime.toBits();v[4]=legacyGranularity.toInt();v[5]=legacyTransform.toInt()
            requireNotNull(parseCurve(legacyCurve)).values().forEachIndexed {i,n->v[6+i]=n.toFloat().toBits()}
            freqDeltas.forEachIndexed {i,n->v[22+i]=n};coefficientDeltas.forEachIndexed {i,n->v[29+i]=n}
            v[1]=if(professional) 1 else 0
            v[2]=engine
            v[20]=if(coreOn) 1 else 0
            v[21]=amount
            v[36]=if(limitsOn) 1 else 0
            v[37]=maxBoost
            v[38]=maxCut
            v[39]=if(timeOn) 1 else 0
            v[40]=backgroundDelta
            v[41]=attack
            v[42]=hold
            v[43]=release
            v[44]=if(detectOn) 1 else 0
            v[45]=relative
            v[46]=knee
            v[47]=weakFloor
            v[48]=linked
            v[49]=rangeLow
            v[50]=rangeHigh
            v[51]=if(mixOn) 1 else 0
            v[52]=mix
            v[53]=if(outputOn) 1 else 0
            v[54]=output
            v[55]=type
            v[56]=threshold
            v[57]=ratio
            v[58]=standardKnee
            v[59]=detector
            v[60]=rmsWindow
            v[61]=if(sidechainOn) 1 else 0
            v[62]=sideHP
            v[63]=sideLP
            v[64]=if(lookaheadOn) 1 else 0
            v[65]=lookahead
            v[66]=hysteresis
            v[67]=if(meters) 1 else 0
            v[68]=if(response) 1 else 0
            v[69]=if(exactFrequency) 1 else 0
            return v
        }
        fun values(): Map<String,Any> = buildMap {
            put(BASE_TIME,legacyTime);put(BASE_GRANULARITY,legacyGranularity);put(BASE_TRANSFORM,legacyTransform);put(BASE_CURVE,legacyCurve)
            frequencies.zip(freqDeltas).forEach {(c,n)->put(c.key,n)};coefficients.zip(coefficientDeltas).forEach {(c,n)->put(c.key,n)}
            put(PROFESSIONAL,professional)
            put(CORE_ON,coreOn)
            put(LIMITS_ON,limitsOn)
            put(TIME_ON,timeOn)
            put(DETECT_ON,detectOn)
            put(MIX_ON,mixOn)
            put(OUTPUT_ON,outputOn)
            put(SIDECHAIN_ON,sidechainOn)
            put(LOOKAHEAD_ON,lookaheadOn)
            put(METERS,meters)
            put(RESPONSE,response)
            put(EXACT_FREQUENCY,exactFrequency)
            put(ENGINE,engine)
            put(TYPE,type)
            put(LINKED,linked)
            put(DETECTOR,detector)
            put(CompanderParameters.amount.key,amount)
            put(CompanderParameters.maxBoost.key,maxBoost)
            put(CompanderParameters.maxCut.key,maxCut)
            put(CompanderParameters.backgroundDelta.key,backgroundDelta)
            put(CompanderParameters.attack.key,attack)
            put(CompanderParameters.hold.key,hold)
            put(CompanderParameters.release.key,release)
            put(CompanderParameters.relative.key,relative)
            put(CompanderParameters.knee.key,knee)
            put(CompanderParameters.weakFloor.key,weakFloor)
            put(CompanderParameters.rangeLow.key,rangeLow)
            put(CompanderParameters.rangeHigh.key,rangeHigh)
            put(CompanderParameters.mix.key,mix)
            put(CompanderParameters.output.key,output)
            put(CompanderParameters.threshold.key,threshold)
            put(CompanderParameters.ratio.key,ratio)
            put(CompanderParameters.standardKnee.key,standardKnee)
            put(CompanderParameters.rmsWindow.key,rmsWindow)
            put(CompanderParameters.sideHP.key,sideHP)
            put(CompanderParameters.sideLP.key,sideLP)
            put(CompanderParameters.lookahead.key,lookahead)
            put(CompanderParameters.hysteresis.key,hysteresis)
        }
    }
    fun defaults()=Settings()
    fun capture(v: Map<String,*>) : Settings {
        var s=Settings(legacyTime=legacyTime(v),legacyGranularity=legacyGranularity(v),legacyTransform=legacyTransform(v),legacyCurve=legacyCurve(v),freqDeltas=frequencies.map {value(v,it)},coefficientDeltas=coefficients.map {value(v,it)},
            professional=on(v,PROFESSIONAL),
            coreOn=on(v,CORE_ON),
            limitsOn=on(v,LIMITS_ON),
            timeOn=on(v,TIME_ON),
            detectOn=on(v,DETECT_ON),
            mixOn=on(v,MIX_ON),
            outputOn=on(v,OUTPUT_ON),
            sidechainOn=on(v,SIDECHAIN_ON),
            lookaheadOn=on(v,LOOKAHEAD_ON),
            meters=on(v,METERS),
            response=on(v,RESPONSE),
            exactFrequency=on(v,EXACT_FREQUENCY),
            engine=engine(v),
            type=type(v),
            linked=linked(v),
            detector=detector(v),
            amount=value(v,amount),
            maxBoost=value(v,maxBoost),
            maxCut=value(v,maxCut),
            backgroundDelta=value(v,backgroundDelta),
            attack=value(v,attack),
            hold=value(v,hold),
            release=value(v,release),
            relative=value(v,relative),
            knee=value(v,knee),
            weakFloor=value(v,weakFloor),
            rangeLow=value(v,rangeLow),
            rangeHigh=value(v,rangeHigh),
            mix=value(v,mix),
            output=value(v,output),
            threshold=value(v,threshold),
            ratio=value(v,ratio),
            standardKnee=value(v,standardKnee),
            rmsWindow=value(v,rmsWindow),
            sideHP=value(v,sideHP),
            sideLP=value(v,sideLP),
            lookahead=value(v,lookahead),
            hysteresis=value(v,hysteresis),
        )
        if(s.rangeLow!=0 && s.rangeHigh!=0 && s.rangeLow>=s.rangeHigh) s=s.copy(rangeLow=0,rangeHigh=0)
        if(s.sideHP!=0 && s.sideLP!=0 && s.sideHP>=s.sideLP) s=s.copy(sideHP=0,sideLP=0)
        return s
    }
    fun packet(v: Map<String,*>)=capture(v).packet()
    fun curveTakenOver(v: Map<String,*>)=enabled(v) && (engine(v)==1 || on(v,CORE_ON))
    fun timeTakenOver(v: Map<String,*>)=enabled(v) && (engine(v)==1 || on(v,TIME_ON))
    fun transformTakenOver(v: Map<String,*>)=enabled(v) && engine(v)==1
    fun candidateValid(v: Map<String,*>,c: BassParameters.Control,n: Int): Boolean {
        if(!validValue(c,n)) return false
        val merged=buildMap<String,Any?> {v.forEach {(k,value)->put(k,value)};put(c.key,n)}
        if(c==rangeLow || c==rangeHigh) {val a=value(merged,rangeLow);val b=value(merged,rangeHigh);if(a!=0 && b!=0 && a>=b) return false}
        if(c==sideHP || c==sideLP) {val a=value(merged,sideHP);val b=value(merged,sideLP);if(a!=0 && b!=0 && a>=b) return false}
        if(c in frequencies) {
            val f=requireNotNull(parseCurve(legacyCurve(v))).frequencies.zip(frequencies) {base,control->base+value(merged,control)/1000.0}
            if(f.any {it<=0 || it>=24000} || f.zipWithNext().any {(a,b)->a>=b}) return false
        }
        return true
    }
    /** Presentation only: retain the complete stored range for input and single-step buttons. */
    fun frequencySliderRange(v: Map<String,*>,index: Int): IntRange? {
        if(index !in 0..6) return null
        val curve=parseCurve(legacyCurve(v)) ?: return null
        val current=frequencies.map {value(v,it)}
        val actual=curve.frequencies.zip(current) {base,delta->base+delta/1000.0}
        val base=curve.frequencies[index];val control=frequencies[index]
        val safeLow=ceil(((if(index==0) .001 else actual[index-1]+.001)-base)*1000).toInt().coerceAtLeast(control.min)
        val safeHigh=floor(((if(index==6) 23999.999 else actual[index+1]-.001)-base)*1000).toInt().coerceAtMost(control.max)
        if(safeLow>safeHigh) return null
        val window=(max(20.0,min(1000.0,base*.25))*1000).toInt()
        var low=max(safeLow,-window);var high=min(safeHigh,window)
        if(current[index] in safeLow..safeHigh) {low=min(low,current[index]);high=max(high,current[index])}
        if(0 in safeLow..safeHigh) {low=min(low,0);high=max(high,0)}
        return (low..high).takeIf {low<high}
    }
    fun numberEnabled(key: String,v: Map<String,*>)=enabled(v) && when(key) {
        in coreNumbers.map {it.key} -> engine(v)==0 && on(v,CORE_ON)
        maxBoost.key -> on(v,LIMITS_ON) && (engine(v)==0 || type(v)==1)
        maxCut.key -> on(v,LIMITS_ON) && (engine(v)==0 || type(v)!=1)
        backgroundDelta.key -> engine(v)==0 && on(v,TIME_ON)
        attack.key,hold.key,release.key -> on(v,TIME_ON)
        relative.key,knee.key -> engine(v)==0 && on(v,DETECT_ON)
        weakFloor.key -> on(v,DETECT_ON) && (engine(v)==0 || type(v)==1)
        rangeLow.key,rangeHigh.key -> on(v,DETECT_ON)
        mix.key -> on(v,MIX_ON)
        output.key -> on(v,OUTPUT_ON)
        threshold.key,standardKnee.key -> engine(v)==1 && on(v,CORE_ON)
        ratio.key -> engine(v)==1 && on(v,CORE_ON) && type(v)!=3
        rmsWindow.key -> engine(v)==1 && on(v,CORE_ON) && detector(v)==1
        hysteresis.key -> engine(v)==1 && on(v,CORE_ON) && type(v)==3
        sideHP.key,sideLP.key -> engine(v)==1 && on(v,SIDECHAIN_ON)
        lookahead.key -> engine(v)==1 && on(v,LOOKAHEAD_ON)
        else -> false
    }
    fun apply(p: SharedPreferences,s: Settings) {require(s.valid());val e=p.edit();write(e,s.values());e.apply()}
    private fun write(e: SharedPreferences.Editor,values: Map<String,*>) {values.forEach {(k,v)->when(v) {is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Float->e.putFloat(k,v);is String->e.putString(k,v)}}}
    fun setProfessional(p: SharedPreferences,on: Boolean) {p.edit().putBoolean(PROFESSIONAL,on).apply()}
    fun normalize(p: SharedPreferences) {
        val v=p.all;val changes=capture(v).values().filter {(k,n)->k in newKeys && v.containsKey(k) && v[k]!=n}
        if(changes.isNotEmpty()) {val e=p.edit();write(e,changes);e.apply()}
    }
    data class Runtime(val data: List<Int>) {
        val mode get()=data[5];val measurement get()=data[7];val epoch get()=data[11]
        val enabled get()=data[3]==1;val professional get()=data[4]==1;val ready get()=data[25]==1
    }
    fun decode(v: IntArray): Runtime? = v.takeIf {
        it.size==80 && it[0]==1 && it[1]>=0 && it[1] and 1==0 && (it[2]==0 || it[2] in 8000..768000) &&
        listOf(3,4,6,12,13,25,40,47,55,57,58,59,62,78).all {i->it[i] in 0..1} && it[5] in 0..6 && it[7] in 0..4 &&
        it[8] in 0..2000 && it[9] in -18000..0 && it[10] in -18000..0 && it[11]>=0 && it[14] in 0..3 && it[15] in 0..4 &&
        (16..23).all {i->it[i]==Int.MIN_VALUE || it[i] in -20000..20000} && it[24]>=0 && (it[26]==0 || it[26] in 8000..768000) &&
        it[27] in 0..18000 && it[28] in 0..18000 && it[29] in 0..60000 && it[30] in 0..60000 && it[31]==2000 &&
        it[32] >= 0 && it[33] in 0..40000 && it[34] in 0..2000 && it[35] in 0..500 && it[36] in 0..2000 &&
        it[37] in 0..240 && it[38] in 0..240 && it[39] in -1000..0 && (it[41]==Int.MIN_VALUE || it[41] in -20000..20000) &&
        it[42] in 0..1000000 && it[43] in 0..3840 && it[44] in -800..0 && it[45] in 100..2000 && it[46] in 0..3 && it[48] in 1..100 &&
        (49..52).all {i->it[i] in 0..it[2]/2} && it[53] in 0..2400 && it[54] in 0..4800 && it[56] in 0..120 &&
        it[60] in 0..1000 && it[61] in -240..0 && it[63]==0 && (64..70).all {i->it[i] in 0..24000} &&
        (71..77).all {i->it[i] in -2400..2400} && it[79]==0
    }?.let {Runtime(it.toList())}
    data class Response(val data: List<Int>) {val ready get()=data[4]==1;val rate get()=data[2];val points get()=data.drop(8).chunked(6)}
    fun decodeResponse(v: IntArray): Response? = v.takeIf {
        it.size==200 && it[0]==1 && it[1]>=0 && it[1] and 1==0 && (it[2]==0 || it[2] in 8000..768000) && it[3] in 0..1 && it[4] in 0..1 && it[5]==32 && it[6] in 0..3 && it[7]==0 &&
        (0 until 32).all {p->val i=8+p*6;it[i] in 0..it[2]/2 && it[i+1] in -20000..20000 && it[i+2] in -2400..2400 && listOf(3,4).all {d->it[i+d]==Int.MIN_VALUE || it[i+d] in -20000..20000} && it[i+5] in 0..1} &&
        (it[4]==0 || (0 until 31).all {p->it[8+p*6]<it[14+p*6]})
    }?.let {Response(it.toList())}
}
