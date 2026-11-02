package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Second-round keys are independent of the original and first-round parameter formats. */
object ConvolutionAdvancedParameters {
    const val CAPABILITY = 8192
    const val PACKET_ID = 1402
    const val RUNTIME_ID = 131214
    const val MATRIX = "conv2_matrix_enabled"
    const val FRACTIONAL = "conv2_fractional_enabled"
    const val SHAPE = "conv2_shape_enabled"
    const val DIRECT_LINK = "conv2_direct_link"
    const val CROSS_LINK = "conv2_cross_link"
    const val ROUTE = "conv2_route"
    const val METHOD = "conv2_fractional_method"
    const val SWITCH = "conv2_switch_mode"
    const val GRAPH = "conv2_graph"
    const val SMOOTH = "conv2_graph_smoothing"
    const val ANALYSIS = "conv2_analysis_enabled"
    val paths = listOf("左入→左出", "左入→右出", "右入→左出", "右入→右出")
    fun mute(i: Int) = "conv2_mute_$i"
    fun invert(i: Int) = "conv2_invert_$i"
    fun enabledFilter(i: Int) = "conv2_filter_enabled_$i"
    fun slope(i: Int) = "conv2_filter_slope_$i"
    private fun c(key: String,title: String,min: Int,max: Int,default: Int,scale: Int=1,unit: String="Hz",digits: Int=0) =
        BassParameters.Control("conv2_$key",title,min,max,default,scale,unit,digits)
    val hp = c("hp_hz","湿声高通截止频率",20,1000,60)
    val lp = c("lp_hz","湿声低通截止频率",1000,20000,12000)
    val lowHz = c("low_shelf_hz","湿声低频搁架频率",20,1000,150)
    val lowGain = c("low_shelf_tenths","湿声低频搁架增益",-120,120,0,10,"dB",1)
    val highHz = c("high_shelf_hz","湿声高频搁架频率",1000,16000,6000)
    val highGain = c("high_shelf_tenths","湿声高频搁架增益",-120,120,0,10,"dB",1)
    val filterControls = listOf(hp,lp,lowHz,lowGain,highHz,highGain)
    val gain = paths.mapIndexed {i,title->c("gain_$i","$title 路径增益",-240,120,0,10,"dB",1)}
    val delay = paths.mapIndexed {i,title->c("delay_us_$i","$title 精细延时",0,20000,0,1000,"ms",3)}
    val split = c("split_us","前段／尾段分界",5000,200000,80000,1000,"ms",1)
    val blend = c("blend_us","前后段过渡宽度",1000,100000,20000,1000,"ms",1)
    val early = c("early_tenths","IR 前段增益",-240,60,0,10,"dB",1)
    val late = c("late_tenths","IR 尾段增益",-240,60,0,10,"dB",1)
    val decay = c("decay_tenths","附加尾段衰减速率",0,600,0,10,"dB/s",1)
    val shapeControls = listOf(split,blend,early,late,decay)
    val fade = c("switch_fade_us","换核淡化时间",5000,500000,50000,1000,"ms",1)
    val tail = c("tail_limit_ms","旧尾保留上限",100,3000,1000,1,"ms")
    val exitFade = c("tail_exit_us","旧尾结束淡出",10000,200000,50000,1000,"ms",1)
    val transitionControls = listOf(fade,tail,exitFade)
    // Comparison compensation is temporary, deliberately absent from persistent keys/presets.
    val comparison = c("comparison_tenths","B 对照临时电平补偿",-120,120,0,10,"dB",1)
    val all = filterControls+gain+delay+shapeControls+transitionControls
    val switches = listOf(MATRIX,FRACTIONAL,SHAPE,DIRECT_LINK,CROSS_LINK,ANALYSIS)+(0..3).flatMap {listOf(mute(it),invert(it),enabledFilter(it))}
    val choices = mapOf(ROUTE to 1,METHOD to 1,SWITCH to 2,GRAPH to 3,SMOOTH to 2,slope(0) to 2,slope(1) to 2)
    val newKeys = all.map {it.key}.toSet()+switches+choices.keys
    fun value(v: Map<String,*>,c: BassParameters.Control): Int = ConvolutionParameters.value(v,c)
    fun on(v: Map<String,*>,key: String) = v[key] as? Boolean ?: false
    fun choice(v: Map<String,*>,key: String): Int = (v[key] as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it%1.0==0.0 && it in 0.0..(choices[key] ?: 0).toDouble()
    }?.toInt() ?: 0
    fun normalize(prefs: SharedPreferences) {
        val v=prefs.all;val edit=prefs.edit();var changed=false
        all.forEach {if(v.containsKey(it.key) && v[it.key]!=value(v,it)){edit.putInt(it.key,value(v,it));changed=true}}
        switches.forEach {if(v.containsKey(it) && v[it] !is Boolean){edit.putBoolean(it,false);changed=true}}
        choices.forEach {(key,_)->if(v.containsKey(key) && v[key]!=choice(v,key)){edit.putInt(key,choice(v,key));changed=true}}
        if(changed)edit.apply()
    }
    fun packet(v: Map<String,*>,commonDelay: Int=0,comparisonTenths: Int=0) = intArrayOf(
        1,if(on(v,enabledFilter(0)))1 else 0,value(v,hp),choice(v,slope(0)),
        if(on(v,enabledFilter(1)))1 else 0,value(v,lp),choice(v,slope(1)),
        if(on(v,enabledFilter(2)))1 else 0,value(v,lowHz),value(v,lowGain),
        if(on(v,enabledFilter(3)))1 else 0,value(v,highHz),value(v,highGain),
        choice(v,SWITCH),value(v,fade),value(v,tail),value(v,exitFade),comparisonTenths.coerceIn(-120,120),commonDelay.coerceIn(0,16))
    data class IrOptions(val matrix: Boolean=false,val route: Int=0,val gains: List<Int> = List(4){0},
        val muted: List<Boolean> = List(4){false},val inverted: List<Boolean> = List(4){false},
        val fractional: Boolean=false,val method: Int=0,val delays: List<Int> = List(4){0},
        val shape: Boolean=false,val splitUs: Int=80000,val blendUs: Int=20000,val early: Int=0,val late: Int=0,val decay: Int=0) {
        companion object {
            fun read(v: Map<String,*>) = IrOptions(on(v,MATRIX),choice(v,ROUTE),gain.map {value(v,it)},
                (0..3).map {on(v,mute(it))},(0..3).map {on(v,invert(it))},on(v,FRACTIONAL),choice(v,METHOD),delay.map {value(v,it)},
                on(v,SHAPE),value(v,split),value(v,blend),value(v,early),value(v,late),value(v,decay))
        }
    }
}
