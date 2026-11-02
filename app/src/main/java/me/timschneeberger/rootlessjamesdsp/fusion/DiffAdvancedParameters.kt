package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Protocol-5 extension. First-round keys and its seven-word packet remain unchanged. */
object DiffAdvancedParameters {
    const val CAPABILITY=2048
    const val PACKET=65635
    const val AUDITION_PACKET=65636
    const val BULK_RUNTIME=131212
    const val QUALITY="v_diff2_interpolation"
    const val FINE_ENABLED="v_diff2_fine_enabled"
    const val GUARD_ENABLED="v_diff2_guard_enabled"
    const val GUARD_MODE="v_diff2_guard_mode"
    const val TRANSIENT_ENABLED="v_diff2_transient_enabled"
    private fun control(key: String,title: String,min: Int,max: Int,default: Int,divisor: Int=1,unit: String="",decimals: Int=0)=
        BassParameters.Control("v_diff2_$key",title,min,max,default,divisor,unit,decimals,advanced=true)
    val fine=control("delay_us","微秒精调",0,20000,5000,1000,"ms",3)
    val window=control("window_ms","选频 FIR 窗长",20,80,40,unit="ms")
    val wet=control("wet_trim","延迟支路电平",-240,0,0,10,"dB",1)
    val output=control("output_trim","本模块输出衰减",-120,0,0,10,"dB",1)
    val correlation=control("guard_correlation","相关度介入阈值",-30,50,0,100,decimals=2)
    val fold=control("guard_fold","允许折叠恶化",0,120,30,10,"dB",1)
    val hysteresis=control("guard_hysteresis","相关度恢复间隔",2,20,5,100,decimals=2)
    val maximum=control("guard_maximum","最大回收比例",0,1000,1000,10,"%",1)
    val attack=control("guard_attack_ms","兼容收回时间",5,100,20,unit="ms")
    val release=control("guard_release_ms","兼容恢复时间",100,2000,400,unit="ms")
    val protection=control("transient_amount","瞬态保护量",0,1000,500,10,"%",1)
    val threshold=control("transient_threshold","起音检测阈值",30,180,60,10,"dB",1)
    val onset=control("transient_attack","瞬态介入时间",5,200,20,10,"ms",1)
    val recovery=control("transient_release_ms","瞬态恢复时间",20,300,80,unit="ms")
    val low=control("monitor_low_hz","低／中频监测分界",80,500,200,unit="Hz")
    val high=control("monitor_high_hz","中／高频监测分界",1000,6000,2000,unit="Hz")
    val guard=listOf(correlation,fold,hysteresis,maximum,attack,release)
    val transient=listOf(protection,threshold,onset,recovery)
    val all=listOf(fine,window,wet,output)+guard+transient+listOf(low,high)
    val switches=listOf(FINE_ENABLED,GUARD_ENABLED,TRANSIENT_ENABLED)
    val newKeys=all.map { it.key }.toSet()+switches+listOf(QUALITY,GUARD_MODE)
    private fun integer(values: Map<String,*>,key: String)=(values[key] as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it%1.0==0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
    }?.toInt()
    fun value(values: Map<String,*>,c: BassParameters.Control): Int {
        val fallback=if(c==fine)DiffParameters.value(values,DiffParameters.delay)*10 else c.default
        val result=integer(values,c.key)?.takeIf { it in c.min..c.max } ?: fallback
        return if(c==window && result !in listOf(20,40,80))40 else result
    }
    fun quality(values: Map<String,*>)=integer(values,QUALITY)?.takeIf { it in 0..1 } ?: 0
    fun detector(values: Map<String,*>)=integer(values,GUARD_MODE)?.takeIf { it in 0..2 } ?: 2
    fun enabled(values: Map<String,*>,key: String)=values[key]==true
    fun migrate(prefs: SharedPreferences) {
        val values=prefs.all;val editor=prefs.edit();var changed=false
        all.forEach { c -> if(values.containsKey(c.key) && values[c.key]!=value(values,c)){editor.putInt(c.key,value(values,c));changed=true} }
        switches.forEach { key -> if(values.containsKey(key) && values[key] !is Boolean){editor.putBoolean(key,false);changed=true} }
        listOf(QUALITY to quality(values),GUARD_MODE to detector(values)).forEach { (key,v)->if(values.containsKey(key) && values[key]!=v){editor.putInt(key,v);changed=true} }
        if(changed)editor.apply()
    }
    fun packet(values: Map<String,*>)=intArrayOf(quality(values),if(enabled(values,FINE_ENABLED))1 else 0,value(values,fine),value(values,window),value(values,wet),value(values,output),
        if(enabled(values,GUARD_ENABLED))1 else 0,detector(values),value(values,correlation),value(values,fold),value(values,hysteresis),value(values,maximum),value(values,attack),value(values,release),
        if(enabled(values,TRANSIENT_ENABLED))1 else 0,value(values,protection),value(values,threshold),value(values,onset),value(values,recovery),value(values,low),value(values,high))
    fun requiresNewApp(values: Map<String,*>)=newKeys.any(values::containsKey)

    data class Band(val correlation: Int,val fold: Int,val mid: Int,val side: Int)
    data class Runtime(val values: List<Int>) {
        val quality get()=values[0]
        val fine get()=values[1]==1
        val window get()=values[2]
        val wet get()=values[3]
        val output get()=values[4]
        val staticMix get()=values[5]
        val actualMix get()=values[6]
        val withdrawal get()=values[7]
        val protection get()=values[8]
        val guardActive get()=values[9]==1
        val transientDetected get()=values[10]==1
        val ready get()=values[21]==1
        val low get()=values[58]
        val high get()=values[59]
        fun band(index: Int,signal: Int): Band {val p=22+index*12+signal*4;return Band(values[p],values[p+1],values[p+2],values[p+3])}
        companion object {
            fun decode(v: List<Int>): Runtime? {
                if(v.size!=60 || v[0] !in 0..1 || v[1] !in 0..1 || v[2] !in listOf(20,40,80) || v[3] !in -240..0 || v[4] !in -120..0 ||
                    (5..8).any { v[it] !in 0..1000 } || v[9] !in 0..1 || v[10] !in 0..1 || v[21] !in 0..1 || v[58] !in 1..96000 || v[59] !in 1..96000 || v[58]>=v[59])return null
                val correlations=listOf(11,12)+(0..2).flatMap { b->(0..2).map { s->22+b*12+s*4 } }
                if(correlations.any { v[it]!=Int.MIN_VALUE && v[it] !in -10000..10000 })return null
                return Runtime(v)
            }
        }
    }
}
