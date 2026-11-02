package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Professional FreeVerb controls retain the original five controls as a separate saved path. */
object ReverbParameters {
    const val NAMESPACE=ViperParameters.PREFS
    const val CAPABILITY=2097152
    const val PACKET=65645
    const val TOOLS=65646
    const val RUNTIME=131222
    const val ENABLED="v_reverb"
    const val PROFESSIONAL="v_reverb_pro_on"
    const val PREDELAY_ON="v_reverb_pro_predelay_on"
    const val LOW_CUT_ON="v_reverb_pro_low_cut_on"
    const val HIGH_CUT_ON="v_reverb_pro_high_cut_on"
    const val LINKED="v_reverb_pro_linked_on"
    const val OUTPUT_ON="v_reverb_pro_output_on"
    const val METERS="v_reverb_pro_meters_on"
    val legacyControls get()=ViperParameters.groups.single {it.key==ENABLED}.controls
    val legacyKeys get()=legacyControls.map {it.key}.toSet()
    val size=BassParameters.Control("v_reverb_pro_size_tenths","房间大小精调",0,1000,0,10,"%",1)
    val width=BassParameters.Control("v_reverb_pro_width_tenths","混响宽度精调",0,1000,0,10,"%",1)
    val damp=BassParameters.Control("v_reverb_pro_damp_tenths","阻尼精调",0,1000,0,10,"%",1)
    val wet=BassParameters.Control("v_reverb_pro_wet_tenths","湿声精调",0,1000,0,10,"%",1)
    val dry=BassParameters.Control("v_reverb_pro_dry_tenths","干声精调",0,1000,500,10,"%",1)
    val predelay=BassParameters.Control("v_reverb_pro_predelay_tenths_ms","混响预延迟",0,2000,0,10,"ms",1)
    val lowCut=BassParameters.Control("v_reverb_pro_low_cut_hz","混响声低切频率",20,1000,80,unit="Hz")
    val highCut=BassParameters.Control("v_reverb_pro_high_cut_hz","混响声高切频率",1000,20000,12000,unit="Hz")
    val mix=BassParameters.Control("v_reverb_pro_mix_tenths","联动混合比例",0,1000,0,10,"%",1)
    val output=BassParameters.Control("v_reverb_pro_output_tenths_db","输出增益",-120,0,0,10,"dB",1)
    val core=listOf(size,width,damp,wet,dry)
    val numbers=core+listOf(predelay,lowCut,highCut,mix,output)
    val switches=setOf(PROFESSIONAL,PREDELAY_ON,LOW_CUT_ON,HIGH_CUT_ON,LINKED,OUTPUT_ON,METERS)
    val allNumbers get()=numbers+ReverbAdvancedParameters.numbers
    val newKeys get()=numbers.map {it.key}.toSet()+switches+ReverbAdvancedParameters.keys
    fun enabled(v:Map<String,*>)=v[PROFESSIONAL] as? Boolean ?: false
    fun moduleEnabled(v:Map<String,*>)=v[ENABLED] as? Boolean ?: false
    fun on(v:Map<String,*>,key:String)=v[key] as? Boolean ?: false
    fun legacy(v:Map<String,*>)=legacyControls.map {(v[it.key] as? Int)?.takeIf {value->value in it.min..it.max} ?: it.default}
    fun value(v:Map<String,*>,c:BassParameters.Control):Int {
        val index=core.indexOf(c)
        return (v[c.key] as? Int)?.takeIf {it in c.min..c.max} ?: if(index>=0)legacy(v)[index]*10 else c.default
    }
    data class Settings(
        val legacy:List<Int> = listOf(0,0,0,0,50), val professional:Boolean=false,
        val size:Int=0,val width:Int=0,val damp:Int=0,val wet:Int=0,val dry:Int=500,
        val predelayOn:Boolean=false,val predelay:Int=0,
        val lowCutOn:Boolean=false,val lowCut:Int=80,val highCutOn:Boolean=false,val highCut:Int=12000,
        val linked:Boolean=false,val mix:Int=0,val outputOn:Boolean=false,val output:Int=0,val meters:Boolean=false,
        val advanced:ReverbAdvancedParameters.Settings=ReverbAdvancedParameters.Settings(),
    ) {
        fun valid()=legacy.size==5 && legacy.all {it in 0..100} &&
            listOf(size,width,damp,wet,dry,predelay,lowCut,highCut,mix,output).zip(numbers).all {(v,c)->v in c.min..c.max} && advanced.valid()
        fun packet()=intArrayOf(1,if(professional)1 else 0,size,width,damp,wet,dry,
            if(predelayOn)1 else 0,predelay,if(lowCutOn)1 else 0,lowCut,if(highCutOn)1 else 0,highCut,
            if(linked)1 else 0,mix,if(outputOn)1 else 0,output,if(meters)1 else 0)
        fun values():Map<String,Any> = buildMap {
            legacyControls.zip(legacy).forEach {(c,v)->put(c.key,v)}
            put(PROFESSIONAL,professional)
            numbers.zip(listOf(this@Settings.size,width,damp,wet,dry,predelay,lowCut,highCut,mix,output)).forEach {(c,v)->put(c.key,v)}
            put(PREDELAY_ON,predelayOn);put(LOW_CUT_ON,lowCutOn);put(HIGH_CUT_ON,highCutOn)
            put(LINKED,linked);put(OUTPUT_ON,outputOn);put(METERS,meters)
            putAll(advanced.values())
        }
    }
    fun capture(v:Map<String,*>)=Settings(legacy(v),enabled(v),value(v,size),value(v,width),value(v,damp),value(v,wet),value(v,dry),
        on(v,PREDELAY_ON),value(v,predelay),on(v,LOW_CUT_ON),value(v,lowCut),on(v,HIGH_CUT_ON),value(v,highCut),
        on(v,LINKED),value(v,mix),on(v,OUTPUT_ON),value(v,output),on(v,METERS),ReverbAdvancedParameters.capture(v))
    fun packet(v:Map<String,*>)=capture(v).packet()
    fun apply(p:SharedPreferences,s:Settings) {
        require(s.valid());val edit=p.edit()
        s.values().forEach {(k,v)->when(v){is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v)}};edit.apply()
    }
    /** Copy legacy values only on first activation; viewing an effect never initializes numbers. */
    fun setProfessional(p:SharedPreferences,on:Boolean) {
        val v=p.all;val edit=p.edit()
        if(on && core.none {v.containsKey(it.key)})core.zip(legacy(v)).forEach {(c,value)->edit.putInt(c.key,value*10)}
        edit.putBoolean(PROFESSIONAL,on).apply()
    }
    fun normalize(p:SharedPreferences) {
        val v=p.all;val changes=capture(v).values().filter {(k,value)->v.containsKey(k) && v[k]!=value}
        if(changes.isNotEmpty()){val edit=p.edit();changes.forEach {(k,value)->when(value){is Boolean->edit.putBoolean(k,value);is Int->edit.putInt(k,value)}};edit.apply()}
    }
    data class Runtime(val data:List<Int>) {
        val mode get()=data[5];val measurement get()=data[7];val epoch get()=data[11]
        val enabled get()=data[3]==1;val professional get()=data[4]==1;val ready get()=data[30]==1
    }
    fun decode(v:IntArray):Runtime?=v.takeIf {
        it.size==32 && it[0]==1 && it[1] and 1==0 && it[2] in 8000..768000 &&
            listOf(3,4,6,12,23,27,28,30).all {i->it[i] in 0..1} && it[5] in 0..6 && it[7] in 0..4 &&
            it[8] in 0..2000 && it[9] in -18000..0 && it[10] in -18000..0 && it[11]>=0 &&
            it[13] in 0..2000 && (it[14]==0 || it[14] in 20..1000) && (it[15]==0 || it[15] in 1000..20000) &&
            (16..21).all {i->it[i]==Int.MIN_VALUE || it[i] in -20000..20000} && it[22]>=0 &&
            it[24] in 0..72000 && it[25] in 0..72000 && it[26]==2000 && it[29]>=0 && it[31]==0
    }?.let {Runtime(it.toList())}
}
