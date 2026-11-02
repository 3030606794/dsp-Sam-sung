package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/** The six original algorithms remain the base; the neutral fine settings preserve that base. */
object PresetCrossfeedParameters {
    const val NAMESPACE=Constants.PREF_CROSSFEED
    const val CAPABILITY=524288
    const val PACKET=1420
    const val TOOLS=1421
    const val ADVANCED_CAPABILITY=8388608
    const val ADVANCED_PACKET=1422
    const val ADVANCED_TOOLS=1423
    const val PATH_ON="crossfeed6_path_on"
    const val PATH_LINK="crossfeed6_path_link"
    const val FILTER_ON="crossfeed6_filter_on"
    const val FILTER_SLOPE="crossfeed6_filter_slope"
    const val CENTER_ON="crossfeed6_center_on"
    const val RUNTIME=131220
    const val ENABLED="bs2b_crossfeed_enable"
    const val BASE="bs2b_crossfeed_mode"
    const val PROFESSIONAL="crossfeed6_professional_on"
    val cutoff=BassParameters.Control("crossfeed6_cutoff_hz","交叉馈送截止频率",300,2000,700,unit="Hz")
    val attenuation=BassParameters.Control("crossfeed6_attenuation_tenths","交叉衰减（越大越弱）",10,150,60,10,"dB",1)
    val mix=BassParameters.Control("crossfeed6_mix_percent","效果混合比例",0,100,100,unit="%")
    val delay=BassParameters.Control("crossfeed6_delay_us","交叉支路附加延时",0,500,0,unit="μs")
    val output=BassParameters.Control("crossfeed6_output_tenths","输出衰减",-120,0,0,10,"dB",1)
    val spatial=BassParameters.Control("crossfeed6_spatial_tenths","空间模型交叉支路电平",-120,60,0,10,"dB",1)
    val coreNumbers=listOf(cutoff,attenuation,mix,delay,output,spatial)
    val leftToRight=BassParameters.Control("crossfeed6_left_right_tenths","左声道混入右声道",-120,60,0,10,"dB",1)
    val rightToLeft=BassParameters.Control("crossfeed6_right_left_tenths","右声道混入左声道",-120,60,0,10,"dB",1)
    val filterCutoff=BassParameters.Control("crossfeed6_filter_hz","交叉支路低通频率",300,20000,3000,unit="Hz")
    val center=BassParameters.Control("crossfeed6_center_tenths","中央声音补偿",-60,60,0,10,"dB",1)
    val advancedNumbers=listOf(leftToRight,rightToLeft,filterCutoff,center)
    val numbers=coreNumbers+advancedNumbers
    val advancedKeys=advancedNumbers.map {it.key}.toSet()+setOf(PATH_ON,PATH_LINK,FILTER_ON,FILTER_SLOPE,CENTER_ON)
    val newKeys=numbers.map {it.key}.toSet()+PROFESSIONAL+advancedKeys
    data class Advanced(val pathOn:Boolean=false,val linked:Boolean=true,val leftToRight:Int=0,val rightToLeft:Int=0,
        val filterOn:Boolean=false,val filterHz:Int=3000,val slope:Int=1,val centerOn:Boolean=false,val center:Int=0){
        fun valid()=leftToRight in -120..60 && rightToLeft in -120..60 && filterHz in 300..20000 && slope in 1..2 && center in -60..60
        fun packet()=intArrayOf(1,if(pathOn)1 else 0,if(linked)1 else 0,leftToRight,rightToLeft,if(filterOn)1 else 0,filterHz,slope,if(centerOn)1 else 0,center)
        fun values():Map<String,Any> = mapOf(PATH_ON to pathOn,PATH_LINK to linked,
            PresetCrossfeedParameters.leftToRight.key to leftToRight,PresetCrossfeedParameters.rightToLeft.key to rightToLeft,
            FILTER_ON to filterOn,PresetCrossfeedParameters.filterCutoff.key to filterHz,FILTER_SLOPE to slope,
            CENTER_ON to centerOn,PresetCrossfeedParameters.center.key to center)
    }
    fun captureAdvanced(v:Map<String,*>)=Advanced(v[PATH_ON] as? Boolean ?: false,v[PATH_LINK] as? Boolean ?: true,
        value(v,leftToRight),value(v,rightToLeft),v[FILTER_ON] as? Boolean ?: false,value(v,filterCutoff),
        (v[FILTER_SLOPE] as? Int)?.takeIf {it in 1..2} ?: 1,v[CENTER_ON] as? Boolean ?: false,value(v,center))
    val baseNames=listOf("BS2B 弱","BS2B 强","出头","环绕一","环绕二","Joe0Blogs 现实环绕模式")
    fun base(v:Map<String,*>)=(v[BASE] as? String)?.toIntOrNull()?.takeIf {it in 0..5}
        ?: if(!v.containsKey(BASE) && v[ENABLED]==true)0 else 5 // Old headless profiles used the service's mode-0 fallback.
    fun enabled(v:Map<String,*>)=v[PROFESSIONAL] as? Boolean ?: false
    fun baseSettings(mode:Int)=Settings(mode,false,if(mode==1)650 else 700,if(mode==1)95 else 60,100,0,0,0)
    fun defaultFor(mode:Int,c:BassParameters.Control)=when(c.key){cutoff.key->baseSettings(mode).cutoff;attenuation.key->baseSettings(mode).attenuation;else->c.default}
    fun value(v:Map<String,*>,c:BassParameters.Control)=(v[c.key] as? Int)?.takeIf {it in c.min..c.max} ?: defaultFor(base(v),c)
    data class Settings(val base:Int,val professional:Boolean,val cutoff:Int,val attenuation:Int,val mix:Int,val delay:Int,val output:Int,val spatial:Int,val advanced:Advanced=Advanced()){
        fun valid()=base in 0..5 && listOf(cutoff,attenuation,mix,delay,output,spatial).zip(coreNumbers).all {(v,c)->v in c.min..c.max} && advanced.valid()
        fun packet()=intArrayOf(1,if(professional)1 else 0,base,cutoff,attenuation,mix,delay,output,spatial)
        fun values():Map<String,Any> = mapOf(BASE to base.toString(),PROFESSIONAL to professional,
            PresetCrossfeedParameters.cutoff.key to cutoff,PresetCrossfeedParameters.attenuation.key to attenuation,
            PresetCrossfeedParameters.mix.key to mix,PresetCrossfeedParameters.delay.key to delay,
            PresetCrossfeedParameters.output.key to output,PresetCrossfeedParameters.spatial.key to spatial)+advanced.values()
    }
    fun capture(v:Map<String,*>)=Settings(base(v),enabled(v),value(v,cutoff),value(v,attenuation),value(v,mix),value(v,delay),value(v,output),value(v,spatial),captureAdvanced(v))
    fun packet(v:Map<String,*>)=capture(v).packet()
    fun apply(p:SharedPreferences,s:Settings){require(s.valid());val edit=p.edit();s.values().forEach {(k,v)->when(v){is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is String->edit.putString(k,v)}};edit.apply()}
    fun selectBase(p:SharedPreferences,mode:Int){require(mode in 0..5);apply(p,baseSettings(mode).copy(professional=enabled(p.all)))}
    fun normalize(p:SharedPreferences){
        val v=p.all;val changes=capture(v).values().filter {(k,value)->v.containsKey(k) && v[k]!=value}
        if(changes.isNotEmpty()){val edit=p.edit();changes.forEach {(k,value)->when(value){is Boolean->edit.putBoolean(k,value);is Int->edit.putInt(k,value);is String->edit.putString(k,value)}};edit.apply()}
    }
    data class Runtime(val data:List<Int>){val mode get()=data[5];val measurement get()=data[6];val epoch get()=data[10];val enabled get()=data[3]==1;val professional get()=data[4]==1}
    fun decode(v:IntArray):Runtime?=v.takeIf {it.size==24 && it[0]==1 && it[1] and 1==0 && it[2] in 8000..768000 && it[3] in 0..1 && it[4] in 0..1 &&
        it[5] in 0..6 && it[6] in 0..3 && it[12] in 0..5 && it[19] in 0..1 && it[23] in 0..1}?.let {Runtime(it.toList())}
}
