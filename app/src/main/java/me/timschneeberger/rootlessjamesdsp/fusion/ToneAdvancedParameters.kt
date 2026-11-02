package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Original values remain Ints and the original range remains a String. */
object ToneAdvancedParameters {
    const val NAMESPACE=ToneController.PREFS
    const val ENABLED=ToneController.ENABLED
    const val BASE_RANGE=ToneController.RANGE
    const val EXTENDED_CAPABILITIES=131234
    const val CAPABILITY=1
    const val PACKET=196640
    const val TOOLS=196641
    const val CLEAR=196642
    const val OFF=196643
    const val RUNTIME=131235
    const val RESPONSE_PACKET=131236
    const val SNAPSHOT=131237
    const val PROFESSIONAL="tone_pro_professional"
    const val CORE_ON="tone_pro_core_on"
    const val SHAPE_ON="tone_pro_shape_on"
    const val DYNAMIC_ON="tone_pro_dynamic_on"
    const val WEAK_ON="tone_pro_weak_on"
    const val MIX_ON="tone_pro_mix_on"
    const val OUTPUT_ON="tone_pro_output_on"
    const val HEADROOM_ON="tone_pro_headroom_on"
    const val METERS="tone_pro_meters"
    const val RESPONSE="tone_pro_response"
    val baseKeys=ToneController.KEYS.toList()
    val delta=(0 until 10).map { BassParameters.Control("tone_pro_delta_$it",listOf("总温度修正","低频深度修正","低音弹性修正","声音厚度修正","人声修正","女毒修正","低齿音修正","高齿音修正","乐器瞬态修正","空气感修正")[it],-2000,2000,0,10,"点",1) }
    val frequencyDefaults=listOf(180,3500,60,110,350,1800,3500,5500,8500,12000)
    val frequency=(0 until 10).map { BassParameters.Control("tone_pro_frequency_$it",listOf("温度低架频率","温度高架频率","低频深度频率","低音弹性静态频率","声音厚度频率","人声频率","女毒频率","低齿音频率","高齿音频率","空气感频率")[it],20,20000,frequencyDefaults[it],1,"Hz",0) }
    val qDefaults=listOf(80,75,80,100,110,110)
    val q=(0 until 6).map { BassParameters.Control("tone_pro_q_$it",listOf("低音弹性静态 Q","声音厚度 Q","人声 Q","女毒 Q","低齿音 Q","高齿音 Q")[it],25,400,qDefaults[it],100,decimals=2) }
    val slope=(0 until 4).map { BassParameters.Control("tone_pro_slope_$it",listOf("温度低架坡度 S","温度高架坡度 S","低频深度坡度 S","空气感坡度 S")[it],250,1000,1000,1000,decimals=3) }
    val amount=BassParameters.Control("tone_pro_amount","总作用强度",0,2000,1000,10,"%",1)
    val temperatureLowWeight=BassParameters.Control("tone_pro_temperature_low_weight","温度低频权重",0,2000,1000,10,"%",1)
    val temperatureHighWeight=BassParameters.Control("tone_pro_temperature_high_weight","温度高频权重",0,2000,1000,10,"%",1)
    val elasticStaticWeight=BassParameters.Control("tone_pro_elastic_static_weight","弹性静态隆起权重",0,2000,1000,10,"%",1)
    val bassDynamicWeight=BassParameters.Control("tone_pro_bass_dynamic_weight","低音动态起音权重",0,2000,1000,10,"%",1)
    val transientWeight=BassParameters.Control("tone_pro_transient_weight","乐器瞬态权重",0,2000,1000,10,"%",1)
    val bassSplit=BassParameters.Control("tone_pro_bass_split","低音动态分频",20,1000,150,1,"Hz",0)
    val bassFast=BassParameters.Control("tone_pro_bass_fast","低音快包络",1,500,50,10,"ms",1)
    val bassSlow=BassParameters.Control("tone_pro_bass_slow","低音慢包络",5,500,70,1,"ms",0)
    val transientFast=BassParameters.Control("tone_pro_transient_fast","瞬态快包络",1,500,20,10,"ms",1)
    val transientSlow=BassParameters.Control("tone_pro_transient_slow","瞬态慢包络",5,500,45,1,"ms",0)
    val bassSensitivity=BassParameters.Control("tone_pro_bass_sensitivity","低音起音灵敏度",250,4000,1000,10,"%",1)
    val transientSensitivity=BassParameters.Control("tone_pro_transient_sensitivity","瞬态灵敏度",250,4000,1000,10,"%",1)
    val bassMax=BassParameters.Control("tone_pro_bass_max","低音动态最大作用量",0,120,30,10,"dB",1)
    val transientMax=BassParameters.Control("tone_pro_transient_max","瞬态最大作用量",0,120,30,10,"dB",1)
    val weakFloor=BassParameters.Control("tone_pro_weak_floor","弱信号保护电平",-1200,-300,-800,10,"dBFS",1)
    val mix=BassParameters.Control("tone_pro_mix","效果比例",0,1000,1000,10,"%",1)
    val output=BassParameters.Control("tone_pro_output","模块输出增益",-240,0,0,10,"dB",1)
    val headroom=BassParameters.Control("tone_pro_headroom","额外衰减余量",-240,0,0,10,"dB",1)
    val coreNumbers=listOf(amount)+delta
    val shapeNumbers=frequency+q+slope+listOf(temperatureLowWeight,temperatureHighWeight)
    val dynamicNumbers=listOf(elasticStaticWeight,bassDynamicWeight,transientWeight,bassSplit,bassFast,bassSlow,transientFast,transientSlow,bassSensitivity,transientSensitivity,bassMax,transientMax,weakFloor)
    val numbers=coreNumbers+shapeNumbers+dynamicNumbers+listOf(mix,output,headroom)
    val switches=linkedSetOf(PROFESSIONAL,CORE_ON,SHAPE_ON,DYNAMIC_ON,WEAK_ON,MIX_ON,OUTPUT_ON,HEADROOM_ON,METERS,RESPONSE)
    val newKeys=numbers.map {it.key}.toSet()+switches
    val keys=newKeys+baseKeys+BASE_RANGE
    fun on(v: Map<String,*>,key:String)=v[key] as? Boolean ?: false
    fun enabled(v:Map<String,*>)=on(v,PROFESSIONAL)
    fun moduleEnabled(v:Map<String,*>)=on(v,ENABLED)
    fun baseRange(v:Map<String,*>)=(v[BASE_RANGE] as? String)?.takeIf {it in listOf("20","40","100")} ?: "100"
    fun validSavedFoundation(v:Map<String,*>)=(!v.containsKey(BASE_RANGE) || v[BASE_RANGE] is String && v[BASE_RANGE] in listOf("20","40","100")) && (!v.containsKey(ENABLED) || v[ENABLED] is Boolean) && baseKeys.all {key->!v.containsKey(key) || (v[key] as? Int)?.let {it in -baseRange(v).toInt()..baseRange(v).toInt()}==true}
    fun value(v:Map<String,*>,c:BassParameters.Control)=(v[c.key] as? Int)?.takeIf {it in c.min..c.max} ?: c.default
    fun base(v:Map<String,*>)=baseKeys.map {(v[it] as? Int)?.coerceIn(-baseRange(v).toInt(),baseRange(v).toInt()) ?: 0}
    data class Settings(
        val base:List<Int> = List(10){0},val baseRange:String="100",
        val delta:List<Int> = List(10){0},val frequency:List<Int> = frequencyDefaults,val q:List<Int> = qDefaults,val slope:List<Int> = List(4){1000},
        val professional:Boolean=false,
        val coreOn:Boolean=false,
        val amount:Int=1000,
        val shapeOn:Boolean=false,
        val temperatureLowWeight:Int=1000,
        val temperatureHighWeight:Int=1000,
        val dynamicOn:Boolean=false,
        val elasticStaticWeight:Int=1000,
        val bassDynamicWeight:Int=1000,
        val transientWeight:Int=1000,
        val bassSplit:Int=150,
        val bassFast:Int=50,
        val bassSlow:Int=70,
        val transientFast:Int=20,
        val transientSlow:Int=45,
        val bassSensitivity:Int=1000,
        val transientSensitivity:Int=1000,
        val bassMax:Int=30,
        val transientMax:Int=30,
        val weakOn:Boolean=false,
        val weakFloor:Int=-800,
        val mixOn:Boolean=false,
        val mix:Int=1000,
        val outputOn:Boolean=false,
        val output:Int=0,
        val headroomOn:Boolean=false,
        val headroom:Int=0,
        val meters:Boolean=false,
        val response:Boolean=false
    ) {
        fun valid():Boolean {
            if(baseRange !in listOf("20","40","100") || base.size!=10 || base.any {it !in -baseRange.toInt()..baseRange.toInt()} || delta.size!=10 || frequency.size!=10 || q.size!=6 || slope.size!=4) return false
            val v=values();if(!numbers.all {c->(v[c.key] as Int) in c.min..c.max}) return false
            return bassFast < bassSlow*10 && transientFast < transientSlow*10
        }
        fun packet(enabled:Boolean=false):IntArray {
            require(valid());val v=IntArray(80);v[0]=1;v[2]=if(enabled)1 else 0;v[3]=baseRange.toInt();base.forEachIndexed {i,n->v[4+i]=n};delta.forEachIndexed {i,n->v[16+i]=n};frequency.forEachIndexed {i,n->v[27+i]=n};q.forEachIndexed {i,n->v[37+i]=n};slope.forEachIndexed {i,n->v[43+i]=n}
            v[1]=if(professional)1 else 0
            v[14]=if(coreOn)1 else 0
            v[15]=amount
            v[26]=if(shapeOn)1 else 0
            v[47]=temperatureLowWeight
            v[48]=temperatureHighWeight
            v[49]=if(dynamicOn)1 else 0
            v[50]=elasticStaticWeight
            v[51]=bassDynamicWeight
            v[52]=transientWeight
            v[53]=bassSplit
            v[54]=bassFast
            v[55]=bassSlow
            v[56]=transientFast
            v[57]=transientSlow
            v[58]=bassSensitivity
            v[59]=transientSensitivity
            v[60]=bassMax
            v[61]=transientMax
            v[62]=if(weakOn)1 else 0
            v[63]=weakFloor
            v[64]=if(mixOn)1 else 0
            v[65]=mix
            v[66]=if(outputOn)1 else 0
            v[67]=output
            v[68]=if(headroomOn)1 else 0
            v[69]=headroom
            v[70]=if(meters)1 else 0
            v[71]=if(response)1 else 0
            return v
        }
        fun values():Map<String,Any> = buildMap {
            baseKeys.zip(base).forEach {(k,n)->put(k,n)};put(BASE_RANGE,baseRange)
            ToneAdvancedParameters.delta.zip(delta).forEach {(c,n)->put(c.key,n)};ToneAdvancedParameters.frequency.zip(frequency).forEach {(c,n)->put(c.key,n)};ToneAdvancedParameters.q.zip(q).forEach {(c,n)->put(c.key,n)};ToneAdvancedParameters.slope.zip(slope).forEach {(c,n)->put(c.key,n)}
            put(PROFESSIONAL,professional)
            put(CORE_ON,coreOn)
            put(SHAPE_ON,shapeOn)
            put(DYNAMIC_ON,dynamicOn)
            put(WEAK_ON,weakOn)
            put(MIX_ON,mixOn)
            put(OUTPUT_ON,outputOn)
            put(HEADROOM_ON,headroomOn)
            put(METERS,meters)
            put(RESPONSE,response)
            put(ToneAdvancedParameters.amount.key,amount)
            put(ToneAdvancedParameters.temperatureLowWeight.key,temperatureLowWeight)
            put(ToneAdvancedParameters.temperatureHighWeight.key,temperatureHighWeight)
            put(ToneAdvancedParameters.elasticStaticWeight.key,elasticStaticWeight)
            put(ToneAdvancedParameters.bassDynamicWeight.key,bassDynamicWeight)
            put(ToneAdvancedParameters.transientWeight.key,transientWeight)
            put(ToneAdvancedParameters.bassSplit.key,bassSplit)
            put(ToneAdvancedParameters.bassFast.key,bassFast)
            put(ToneAdvancedParameters.bassSlow.key,bassSlow)
            put(ToneAdvancedParameters.transientFast.key,transientFast)
            put(ToneAdvancedParameters.transientSlow.key,transientSlow)
            put(ToneAdvancedParameters.bassSensitivity.key,bassSensitivity)
            put(ToneAdvancedParameters.transientSensitivity.key,transientSensitivity)
            put(ToneAdvancedParameters.bassMax.key,bassMax)
            put(ToneAdvancedParameters.transientMax.key,transientMax)
            put(ToneAdvancedParameters.weakFloor.key,weakFloor)
            put(ToneAdvancedParameters.mix.key,mix)
            put(ToneAdvancedParameters.output.key,output)
            put(ToneAdvancedParameters.headroom.key,headroom)
        }
    }
    fun defaults()=Settings()
    fun capture(v:Map<String,*>) :Settings {
        var s=Settings(base=base(v),baseRange=baseRange(v),delta=delta.map {value(v,it)},frequency=frequency.map {value(v,it)},q=q.map {value(v,it)},slope=slope.map {value(v,it)},
            professional=on(v,PROFESSIONAL),
            coreOn=on(v,CORE_ON),
            shapeOn=on(v,SHAPE_ON),
            dynamicOn=on(v,DYNAMIC_ON),
            weakOn=on(v,WEAK_ON),
            mixOn=on(v,MIX_ON),
            outputOn=on(v,OUTPUT_ON),
            headroomOn=on(v,HEADROOM_ON),
            meters=on(v,METERS),
            response=on(v,RESPONSE),
            amount=value(v,amount),
            temperatureLowWeight=value(v,temperatureLowWeight),
            temperatureHighWeight=value(v,temperatureHighWeight),
            elasticStaticWeight=value(v,elasticStaticWeight),
            bassDynamicWeight=value(v,bassDynamicWeight),
            transientWeight=value(v,transientWeight),
            bassSplit=value(v,bassSplit),
            bassFast=value(v,bassFast),
            bassSlow=value(v,bassSlow),
            transientFast=value(v,transientFast),
            transientSlow=value(v,transientSlow),
            bassSensitivity=value(v,bassSensitivity),
            transientSensitivity=value(v,transientSensitivity),
            bassMax=value(v,bassMax),
            transientMax=value(v,transientMax),
            weakFloor=value(v,weakFloor),
            mix=value(v,mix),
            output=value(v,output),
            headroom=value(v,headroom)
        )
        if(s.bassFast>=s.bassSlow*10) s=s.copy(bassFast=50,bassSlow=70)
        if(s.transientFast>=s.transientSlow*10) s=s.copy(transientFast=20,transientSlow=45)
        return s
    }
    fun packet(v:Map<String,*>)=capture(v).packet(moduleEnabled(v))
    fun takenOver(v:Map<String,*>)=enabled(v) && on(v,CORE_ON)
    fun candidateValid(v:Map<String,*>,c:BassParameters.Control,n:Int):Boolean {
        if(n !in c.min..c.max) return false
        val merged=v.toMutableMap().apply {put(c.key,n)};val bf=value(merged,bassFast);val bs=value(merged,bassSlow);val tf=value(merged,transientFast);val ts=value(merged,transientSlow)
        return bf<bs*10 && tf<ts*10
    }
    fun numberEnabled(key:String,v:Map<String,*>)=enabled(v) && when(key) {
        in coreNumbers.map {it.key}->on(v,CORE_ON)
        in shapeNumbers.map {it.key}->on(v,SHAPE_ON)
        weakFloor.key->on(v,DYNAMIC_ON) && on(v,WEAK_ON)
        in dynamicNumbers.map {it.key}->on(v,DYNAMIC_ON)
        mix.key->on(v,MIX_ON)
        output.key->on(v,OUTPUT_ON)
        headroom.key->on(v,HEADROOM_ON)
        else->false
    }
    fun effective(v:Map<String,*>,index:Int):Double {val s=capture(v);return if(s.professional && s.coreOn) (s.base[index]+s.delta[index]/10.0).coerceIn(-100.0,100.0)*s.amount/1000.0 else s.base[index].toDouble()}
    fun localRange(value:Int,windowPoints:Int):IntRange {val w=windowPoints.coerceIn(1,200)*10;return minOf(-w,value)..maxOf(w,value)}
    private fun write(e:SharedPreferences.Editor,v:Map<String,*>) {v.forEach {(k,n)->when(n){is Boolean->e.putBoolean(k,n);is Int->e.putInt(k,n);is String->e.putString(k,n)}}}
    fun apply(p:SharedPreferences,s:Settings) {require(s.valid());val e=p.edit();write(e,s.values());e.apply()}
    fun setProfessional(p:SharedPreferences,on:Boolean) {p.edit().putBoolean(PROFESSIONAL,on).apply()}
    fun normalize(p:SharedPreferences) {val v=p.all;val changes=capture(v).values().filter {(k,n)->k in newKeys && v.containsKey(k) && v[k]!=n};if(changes.isNotEmpty()){val e=p.edit();write(e,changes);e.apply()}}
    fun resetAll(p:SharedPreferences) {val e=p.edit();newKeys.forEach(e::remove);baseKeys.forEach {e.putInt(it,0)};e.putString(BASE_RANGE,"100").apply()}
    data class Runtime(val data:List<Int>) {val mode get()=data[5];val measurement get()=data[7];val epoch get()=data[11];val enabled get()=data[3]==1;val professional get()=data[4]==1;val ready get()=data[13]==1}
    fun decode(v:IntArray):Runtime?=v.takeIf {
        it.size==64 && it[0]==1 && it[1]>=0 && it[1] and 1==0 && (it[2]==0 || it[2] in 8000..768000) && listOf(3,4,6,12,13,15,25,26,40).all {i->it[i] in 0..1} && it[5] in 0..6 && it[7] in 0..4 && it[8] in 0..2000 && it[9] in -18000..0 && it[10] in -18000..0 && it[11]>=0 && it[14] in listOf(20,40,100) && (16..23).all {i->it[i]==Int.MIN_VALUE || it[i] in -20000..20000} && it[24]>=0 && it[27] in -18000..0 && it[28] in -240..0 && it[29] in -1200..1200 && it[30] in -1200..1200 && it[31]==2000 && it[32] in 0..1000 && it[33] in -240..0 && it[34] in 0..it[2]/2 && it[35] in 1..500 && it[36] in 5..500 && it[37] in 1..500 && it[38] in 5..500 && it[39] in -1200..-300 && (41..50).all {i->it[i] in -2000..2000} && (51..60).all {i->it[i] in 0..it[2]/2} && it[61] in 0..60000 && it[62] in 0..60000 && it[63]==0
    }?.let {Runtime(it.toList())}
    data class Response(val data:List<Int>) {val ready get()=data[3]==1;val rate get()=data[2];val points get()=data.drop(8).chunked(6)}
    fun decodeResponse(v:IntArray):Response?=v.takeIf {
        it.size==200 && it[0]==1 && it[1]>=0 && it[1] and 1==0 && (it[2]==0 || it[2] in 8000..768000) && it[3] in 0..1 && it[4]==32 && (5..7).all {i->it[i]==0} && (0 until 32).all {p->val i=8+p*6;it[i] in 0..it[2]/2 && it[i+1] in -20000..20000 && it[i+2] in -20000..20000 && it[i+3]==0 && it[i+4]==0 && it[i+5] in 0..1} && (it[3]==0 || (0 until 31).all {p->it[8+p*6]<it[14+p*6]})
    }?.let {Response(it.toList())}
}
