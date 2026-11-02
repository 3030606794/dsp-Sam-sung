package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Old float width and power retain their original keys, units and defaults. */
object WidthParameters {
    const val NAMESPACE="dsp_stereowide"
    const val ENABLED="stereowide_enable"
    const val LEGACY="stereowide_mode"
    const val CAPABILITY=16384
    const val PACKET=1410
    const val AUDITION=1411
    const val RUNTIME=131215
    const val MODE="width1_mode"
    const val SPLIT_SLOPE="width1_split_slope"
    const val MONO_SLOPE="width1_mono_slope"
    const val MONO="width1_mono_enabled"
    const val AUTO="width1_auto_enabled"
    const val GUARD="width1_guard_enabled"
    const val LINK="width1_band_link"
    val master=BassParameters.Control("width1_master","专业总体宽度",0,2000,1000,10,"%",1)
    val low=BassParameters.Control("width1_low","低频宽度",0,2000,1000,10,"%",1)
    val mid=BassParameters.Control("width1_mid","中频宽度",0,2000,1000,10,"%",1)
    val high=BassParameters.Control("width1_high","高频宽度",0,2000,1000,10,"%",1)
    val crossLow=BassParameters.Control("width1_cross_low","低／中频分频点",80,500,180,unit="Hz")
    val crossHigh=BassParameters.Control("width1_cross_high","中／高频分频点",1000,8000,3000,unit="Hz")
    val monoHz=BassParameters.Control("width1_mono_hz","低频收拢截止频率",30,400,120,unit="Hz")
    val monoAmount=BassParameters.Control("width1_mono_amount","低频收拢强度",0,1000,1000,10,"%",1)
    val midGain=BassParameters.Control("width1_mid_gain","中置增益",-120,60,0,10,"dB",1)
    val mix=BassParameters.Control("width1_mix","处理混合比例",0,1000,1000,10,"%",1)
    val output=BassParameters.Control("width1_output","模块输出校准",-120,60,0,10,"dB",1)
    val autoWindow=BassParameters.Control("width1_auto_window","电平匹配分析时间",100,2000,400,unit="ms")
    val autoMax=BassParameters.Control("width1_auto_max","最大电平匹配补偿",0,60,30,10,"dB",1)
    val threshold=BassParameters.Control("width1_threshold","相关度触发阈值",-20,50,0,100,"",2)
    val hysteresis=BassParameters.Control("width1_hysteresis","相关度恢复迟滞",0,20,5,100,"",2)
    val attack=BassParameters.Control("width1_attack","扩宽回收时间",5,200,20,unit="ms")
    val release=BassParameters.Control("width1_release","扩宽恢复时间",50,2000,400,unit="ms")
    val strength=BassParameters.Control("width1_strength","相关度保护强度",0,1000,1000,10,"%",1)
    val meterWindow=BassParameters.Control("width1_meter_window","监测平滑时间",100,1000,300,unit="ms")
    val legacy=BassParameters.Control(LEGACY,"原算法宽度（旧刻度）",30,75,60)
    val bands=listOf(low,mid,high)
    val all=listOf(master)+bands+listOf(crossLow,crossHigh,monoHz,monoAmount,midGain,mix,output,autoWindow,autoMax,threshold,hysteresis,attack,release,strength,meterWindow)
    val switches=listOf(MONO,AUTO,GUARD,LINK)
    val choices=mapOf(MODE to 0..2,SPLIT_SLOPE to 0..1,MONO_SLOPE to 0..2)
    val newKeys=all.map {it.key}.toSet()+switches+choices.keys
    private fun integer(v: Map<String,*>,key: String)=(v[key] as? Number)?.toDouble()?.takeIf {it.isFinite() && it%1.0==0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()}?.toInt()
    fun value(v: Map<String,*>,c: BassParameters.Control): Int=integer(v,c.key)?.takeIf {it in c.min..c.max && (c !in listOf(autoWindow,meterWindow) || it%10==0)} ?: c.default
    fun choice(v: Map<String,*>,key: String)=integer(v,key)?.takeIf {it in choices.getValue(key)} ?: if(key==MODE)0 else 1
    fun on(v: Map<String,*>,key: String)=(v[key] as? Boolean) ?: false
    fun packet(v: Map<String,*>)=intArrayOf(1,choice(v,MODE),value(v,master),value(v,low),value(v,mid),value(v,high),value(v,crossLow),value(v,crossHigh),choice(v,SPLIT_SLOPE),
        if(on(v,MONO))1 else 0,value(v,monoHz),value(v,monoAmount),choice(v,MONO_SLOPE),value(v,midGain),value(v,mix),value(v,output),if(on(v,AUTO))1 else 0,value(v,autoWindow),value(v,autoMax),
        if(on(v,GUARD))1 else 0,value(v,threshold),value(v,hysteresis),value(v,attack),value(v,release),value(v,strength),value(v,meterWindow))
    fun normalize(prefs: SharedPreferences) {
        val v=prefs.all;val edit=prefs.edit();var changed=false
        all.forEach {c->if(v.containsKey(c.key) && v[c.key]!=value(v,c)){edit.putInt(c.key,value(v,c));changed=true}}
        choices.keys.forEach {k->if(v.containsKey(k) && v[k]!=choice(v,k)){edit.putInt(k,choice(v,k));changed=true}}
        switches.forEach {k->if(v.containsKey(k) && v[k] !is Boolean){edit.putBoolean(k,false);changed=true}}
        if(changed)edit.apply()
    }
}
