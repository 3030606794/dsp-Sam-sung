package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Second-generation controls use separate keys; the first-generation packet is unchanged. */
object FieldAdvancedParameters {
    const val CAPABILITY = 512
    const val DYNAMIC_PACKET = 65627
    const val GUARD_PACKET = 65628
    const val TRANSIENT_PACKET = 65629
    const val SPACE_PACKET = 65630
    const val DIFFERENCE_PACKET = 65631
    const val DYNAMIC_ENABLED = "v_field_dynamic_enabled"
    const val GUARD_ENABLED = "v_field_guard_enabled"
    const val TRANSIENT_ENABLED = "v_field_transient_enabled"
    const val DELAY_ENABLED = "v_field_delay_enabled"
    const val DIFFUSION_ENABLED = "v_field_diffusion_enabled"
    private fun control(key: String, title: String, min: Int, max: Int, default: Int,
        divisor: Int = 1, unit: String = "", decimals: Int = 0) =
        BassParameters.Control("v_field_$key",title,min,max,default,divisor,unit,decimals,advanced=true)
    val dynamicThreshold = control("dynamic_threshold","Side RMS 阈值",-480,-60,-180,10,"dBFS",1)
    val dynamicRatio = control("dynamic_ratio","压缩比",10,80,20,10,":1",1)
    val dynamicMaximum = control("dynamic_max_gr","最大 Side 衰减",0,120,30,10,"dB",1)
    val dynamicAttack = control("dynamic_attack_ms","启动时间",1,100,10,unit="ms")
    val dynamicRelease = control("dynamic_release_ms","恢复时间",50,1500,250,unit="ms")
    val guardThreshold = control("guard_threshold","相关度介入阈值",-30,50,0,100,decimals=2)
    val guardHysteresis = control("guard_hysteresis","相关度恢复间隔",2,20,5,100,decimals=2)
    val guardWithdraw = control("guard_withdraw_ms","收回时间",10,100,30,unit="ms")
    val guardRecover = control("guard_recover_ms","恢复时间",200,2000,600,unit="ms")
    val guardMaximum = control("guard_maximum","最大回收比例",0,1000,1000,10,"%",1)
    val transientAmount = control("transient_protection","瞬态保护量",0,1000,500,10,"%",1)
    val transientThreshold = control("transient_threshold","起音检测阈值",30,180,60,10,"dB",1)
    val transientRecovery = control("transient_recover_ms","空间恢复时间",20,300,80,unit="ms")
    val delayTime = control("delay_centims","侧向微延时",0,500,30,100,"ms",2)
    val delayMix = control("delay_mix","微延时混合",0,1000,150,10,"%",1)
    val diffusionStages = control("diffusion_stages","全通级数",1,4,2,unit="级")
    val diffusionCenter = control("diffusion_center_hz","扩散中心频率",200,8000,1200,unit="Hz")
    val diffusionSpan = control("diffusion_span","扩散频率跨度",5,30,10,10,"oct",1)
    val diffusionMix = control("diffusion_mix","扩散混合",0,1000,150,10,"%",1)
    val dynamic = listOf(dynamicThreshold,dynamicRatio,dynamicMaximum,dynamicAttack,dynamicRelease)
    val guard = listOf(guardThreshold,guardHysteresis,guardWithdraw,guardRecover,guardMaximum)
    val transient = listOf(transientAmount,transientThreshold,transientRecovery)
    val delay = listOf(delayTime,delayMix)
    val diffusion = listOf(diffusionStages,diffusionCenter,diffusionSpan,diffusionMix)
    val all = dynamic+guard+transient+delay+diffusion
    val switches = listOf(DYNAMIC_ENABLED,GUARD_ENABLED,TRANSIENT_ENABLED,DELAY_ENABLED,DIFFUSION_ENABLED)
    val newKeys = all.map { it.key }.toSet()+switches
    fun value(values: Map<String,*>, control: BassParameters.Control): Int {
        val raw = (values[control.key] as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it % 1.0 == 0.0 && it in control.min.toDouble()..control.max.toDouble()
        }?.toInt() ?: control.default
        return if (control == diffusionStages && raw !in listOf(1,2,4)) control.default else raw
    }
    fun enabled(values: Map<String,*>, key: String) = values[key] == true
    fun migrate(preferences: SharedPreferences) {
        val values = preferences.all; val editor = preferences.edit(); var changed = false
        all.forEach { control -> if (values.containsKey(control.key) &&
            (values[control.key] !is Int || values[control.key] != value(values,control))) {
            editor.putInt(control.key,value(values,control)); changed = true
        } }
        switches.forEach { key -> if (values.containsKey(key) && values[key] !is Boolean) {
            editor.putBoolean(key,false); changed = true
        } }
        if (changed) editor.apply()
    }
    fun packets(values: Map<String,*>) : List<Pair<Int,IntArray>> {
        fun flag(key: String) = if (enabled(values,key)) 1 else 0
        fun packet(key: String, controls: List<BassParameters.Control>) =
            (listOf(flag(key))+controls.map { value(values,it) }).toIntArray()
        return listOf(DYNAMIC_PACKET to packet(DYNAMIC_ENABLED,dynamic), GUARD_PACKET to packet(GUARD_ENABLED,guard),
            TRANSIENT_PACKET to packet(TRANSIENT_ENABLED,transient), SPACE_PACKET to
                (packet(DELAY_ENABLED,delay)+packet(DIFFUSION_ENABLED,diffusion)))
    }
    fun requiresNewApp(values: Map<String,*>) = newKeys.any { values.containsKey(it) }
    data class Runtime(val inputCorrelation: Int, val dynamicReduction: Int, val guardRecovery: Int,
        val transientProtection: Int, val delayUs: Int, val delaySamplesMilli: Int, val diffusionCenter: Int,
        val diffusionLow: Int, val diffusionHigh: Int, val inputMid: Int, val inputSide: Int,
        val monoFoldDifference: Int, val guardBaseline: Int, val ready: Boolean) {
        companion object {
            fun decode(values: List<Int?>): Runtime? {
                if (values.size != 14 || values.any { it == null }) return null
                val v = values.filterNotNull()
                fun correlation(n: Int) = n == Int.MIN_VALUE || n in -10000..10000
                if (!correlation(v[0]) || !correlation(v[12]) || v[1] !in 0..1200 ||
                    v[2] !in 0..1000 || v[3] !in 0..1000 || v[4] !in 0..5000 ||
                    v[5] !in 0..960000 || v.subList(6,9).any { it !in 0..96000 } || v[13] !in 0..1) return null
                return Runtime(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13]==1)
            }
        }
    }
}
