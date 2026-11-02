package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** New convolution controls never rewrite the original six-field IR editing string. */
object ConvolutionParameters {
    const val NAMESPACE = "dsp_convolver"
    const val CAPABILITY = 4096
    const val PACKET_ID = 1400
    const val AUDITION_ID = 1401
    const val BULK_RUNTIME_ID = 131213
    const val ENABLED = "convolver_enable"
    const val FILE = "convolver_file"
    const val LEGACY_MODE = "convolver_mode"
    const val LEGACY_ADVANCED = "convolver_adv_imp"
    const val LEGACY_DEFAULT = "-80;-100;0;0;0;0"
    const val WINDOW_ENABLED = "conv1_window_enabled"
    const val NORM_MODE = "conv1_norm_mode"
    const val NORM_ONLY_ATTENUATION = "conv1_norm_attenuate_only"
    const val CHANNEL_LINK = "conv1_channel_link"
    const val INVERT_LEFT = "conv1_invert_left"
    const val INVERT_RIGHT = "conv1_invert_right"
    const val SWAP = "conv1_swap"
    val mix= BassParameters.Control("conv1_mix_tenths","卷积混合比例",0,1000,1000,10,"%",1)
    val wet= BassParameters.Control("conv1_wet_gain_tenths","湿声增益",-240,120,0,10,"dB",1)
    val output= BassParameters.Control("conv1_output_gain_tenths","模块输出衰减",-240,0,0,10,"dB",1)
    val predelay= BassParameters.Control("conv1_predelay_us","湿声预延时",0,200000,0,1000,"ms",3)
    val left= BassParameters.Control("conv1_left_trim_tenths","湿声左声道校准",-120,120,0,10,"dB",1)
    val right= BassParameters.Control("conv1_right_trim_tenths","湿声右声道校准",-120,120,0,10,"dB",1)
    val start= BassParameters.Control("conv1_start_us","保留起点",0,Int.MAX_VALUE-99,0,1000,"ms",1)
    val length= BassParameters.Control("conv1_length_us","保留长度（0 为全部）",0,Int.MAX_VALUE-99,0,1000,"ms",1)
    val fadeIn= BassParameters.Control("conv1_fade_in_us","窗口淡入",0,50000,0,1000,"ms",1)
    val fadeOut= BassParameters.Control("conv1_fade_out_us","窗口淡出",0,500000,0,1000,"ms",1)
    val normTarget= BassParameters.Control("conv1_norm_target_tenths","原采样率参考峰值目标",-120,-10,-10,10,"dBFS",1)
    val runtime=listOf(mix,wet,output,predelay,left,right)
    val window=listOf(start,length,fadeIn,fadeOut)
    val all=runtime+window+normTarget
    val switches=listOf(WINDOW_ENABLED,NORM_ONLY_ATTENUATION,CHANNEL_LINK,INVERT_LEFT,INVERT_RIGHT,SWAP)
    val newKeys=all.map {it.key}.toSet()+switches+NORM_MODE+ConvolutionAdvancedParameters.newKeys
    private fun integer(values: Map<String,*>,key: String)=(values[key] as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it%1.0==0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
    }?.toInt()
    fun value(values: Map<String,*>,c: BassParameters.Control): Int = integer(values,c.key)?.takeIf {it in c.min..c.max} ?: c.default
    fun enabled(values: Map<String,*>,key: String)=(values[key] as? Boolean) ?: (key==NORM_ONLY_ATTENUATION || key==CHANNEL_LINK)
    fun normMode(values: Map<String,*>)=integer(values,NORM_MODE)?.takeIf {it in 0..1} ?: 0
    fun runtimePacket(values: Map<String,*>)=intArrayOf(value(values,mix),value(values,wet),value(values,output),value(values,predelay),
        value(values,left),value(values,right),if(enabled(values,INVERT_LEFT))1 else 0,if(enabled(values,INVERT_RIGHT))1 else 0,if(enabled(values,SWAP))1 else 0,0)
    fun packet(values: Map<String,*>)=runtimePacket(values)
    fun requiresNewApp(values: Map<String,*>)=newKeys.any(values::containsKey)
    fun normalize(prefs: SharedPreferences) {
        ConvolutionAdvancedParameters.normalize(prefs)
        val v=prefs.all; val edit=prefs.edit(); var changed=false
        all.forEach {c->if(v.containsKey(c.key) && v[c.key]!=value(v,c)){edit.putInt(c.key,value(v,c));changed=true}}
        if(v.containsKey(NORM_MODE) && v[NORM_MODE]!=normMode(v)){edit.putInt(NORM_MODE,normMode(v));changed=true}
        switches.forEach {key->if(v.containsKey(key) && v[key] !is Boolean){edit.putBoolean(key,enabled(emptyMap<String,Any>(),key));changed=true}}
        if(changed)edit.apply()
    }
    fun migrate(prefs: SharedPreferences)=normalize(prefs)
    /** Null means retain the original text and show an error, never silently repair it. */
    fun legacy(values: Map<String,*>): IntArray? {
        val parts=legacyText(values).split(';');if(parts.size!=6)return null
        return parts.map {it.trim().toIntOrNull() ?: return null}.toIntArray()
    }
    fun legacyText(values: Map<String,*>)=values[LEGACY_ADVANCED] as? String ?: LEGACY_DEFAULT
}
