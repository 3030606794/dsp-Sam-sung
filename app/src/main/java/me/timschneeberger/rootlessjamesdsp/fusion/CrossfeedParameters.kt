package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Separate keys and packet keep all original three-tier values and their old DSP path intact. */
object CrossfeedParameters {
    const val CAPABILITY=262144
    const val PACKET=65640
    const val TOOLS=65641
    const val RUNTIME=131219
    const val ENABLED="v_cure_professional_enabled"
    const val FILTER="v_cure_output_filter"
    const val LEGACY="v_cure_level"
    val cutoff=BassParameters.Control("v_cure_cutoff_hz","交叉馈送截止频率",300,2000,700,unit="Hz")
    val attenuation=BassParameters.Control("v_cure_attenuation_tenths","交叉衰减（越大越弱）",10,150,60,10,"dB",1)
    val mix=BassParameters.Control("v_cure_mix_percent","效果混合比例",0,100,100,unit="%")
    val delay=BassParameters.Control("v_cure_extra_delay_us","附加交叉路径延时",0,500,0,unit="μs")
    val output=BassParameters.Control("v_cure_output_tenths","输出衰减",-120,0,0,10,"dB",1)
    val numbers=listOf(cutoff,attenuation,mix,delay,output)
    val newKeys=numbers.map {it.key}.toSet()+setOf(ENABLED,FILTER)
    private fun integer(v:Map<String,*>,key:String)=(v[key] as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it%1.0==0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
    }?.toInt()
    fun value(v:Map<String,*>,c:BassParameters.Control)=integer(v,c.key)?.takeIf {it in c.min..c.max} ?: c.default
    fun enabled(v:Map<String,*>)=v[ENABLED] as? Boolean ?: false
    fun filter(v:Map<String,*>)=integer(v,FILTER)?.takeIf {it in 0..1} ?: 0
    fun legacy(v:Map<String,*>)=integer(v,LEGACY)?.takeIf {it in 0..2} ?: 0
    fun packet(v:Map<String,*>)=intArrayOf(1,if(enabled(v))1 else 0,value(v,cutoff),value(v,attenuation),
        value(v,mix),value(v,delay),value(v,output),filter(v))
    fun requiresNewApp(v:Map<String,*>)=newKeys.any(v::containsKey)
    fun normalize(p:SharedPreferences){
        CrossfeedAdvancedParameters.normalize(p)
        val v=p.all;val edit=p.edit();var changed=false
        numbers.forEach {c->if(v.containsKey(c.key) && v[c.key]!=value(v,c)){edit.putInt(c.key,value(v,c));changed=true}}
        for((key,value) in listOf(FILTER to filter(v),LEGACY to legacy(v)))if(v.containsKey(key) && v[key]!=value){edit.putInt(key,value);changed=true}
        if(v.containsKey(ENABLED) && v[ENABLED] !is Boolean){edit.putBoolean(ENABLED,false);changed=true}
        if(changed)edit.apply()
    }
    data class Settings(val legacy:Int,val professional:Boolean,val cutoff:Int,val attenuation:Int,val mix:Int,
        val delay:Int,val output:Int,val filter:Int,val advanced:CrossfeedAdvancedParameters.Settings=CrossfeedAdvancedParameters.Settings()){
        fun values():Map<String,Any> = mapOf(LEGACY to legacy,ENABLED to professional,
            CrossfeedParameters.cutoff.key to cutoff,CrossfeedParameters.attenuation.key to attenuation,
            CrossfeedParameters.mix.key to mix,CrossfeedParameters.delay.key to delay,CrossfeedParameters.output.key to output,FILTER to filter)+advanced.values()
        fun valid()=legacy in 0..2 && filter in 0..1 && advanced.valid() && numbers.zip(listOf(cutoff,attenuation,mix,delay,output)).all {(c,n)->n in c.min..c.max}
    }
    fun capture(v:Map<String,*>)=Settings(legacy(v),enabled(v),value(v,cutoff),value(v,attenuation),
        value(v,mix),value(v,delay),value(v,output),filter(v),CrossfeedAdvancedParameters.capture(v))
    fun apply(p:SharedPreferences,s:Settings){
        require(s.valid());val edit=p.edit()
        s.values().forEach {(key,v)->when(v){is Boolean->edit.putBoolean(key,v);is Int->edit.putInt(key,v)}}
        edit.apply()
    }
    data class Runtime(val data:IntArray){
        val mode get()=data[5];val measurement get()=data[6];val epoch get()=data[10]
        val enabled get()=data[3]==1;val professional get()=data[4]==1
        override fun equals(other:Any?)=other is Runtime && data.contentEquals(other.data)
        override fun hashCode()=data.contentHashCode()
    }
    fun decode(v:IntArray):Runtime?=v.takeIf {it.size==20 && it[0]==1 && (it[1] and 1)==0 &&
        it[2] in 8000..768000 && it[3] in 0..1 && it[4] in 0..1 && it[5] in 0..6 && it[6] in 0..3 &&
        it[7] in 0..2100 && it[8]<=0 && it[9]<=0 && it[10]>=0 && it[18] in 0..1}?.let {Runtime(it.copyOf())}
}
