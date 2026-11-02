package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Keep the original whole-millisecond preference and wire parameter unchanged. */
object DiffParameters {
    const val CAPABILITY = 1024
    const val PACKET = 65632
    const val AUDITION_PACKET = 65633
    const val MODE = "v_diff_processing_mode"
    const val CHANNEL = "v_diff_delayed_channel"
    const val BAND_ENABLED = "v_diff_band_enabled"
    val legacy = BassParameters.Control("v_diff_delay","原版延时",1,20,5,unit="ms")
    val delay = BassParameters.Control("v_diff_delay_centims","精密延时",0,2000,500,100,"ms",2,advanced=true)
    val mix = BassParameters.Control("v_diff_mix_tenths","延迟声道混合",0,1000,1000,10,"%",1,advanced=true)
    val low = BassParameters.Control("v_diff_band_low_hz","作用频带下限",40,2000,150,unit="Hz",advanced=true)
    val high = BassParameters.Control("v_diff_band_high_hz","作用频带上限",2000,20000,12000,unit="Hz",advanced=true)
    val professional = listOf(delay,mix,low,high)
    val all = listOf(legacy)+professional
    val newKeys = professional.map { it.key }.toSet()+listOf(MODE,CHANNEL,BAND_ENABLED)
    private fun integer(values: Map<String,*>,key: String)=(values[key] as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it%1.0==0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
    }?.toInt()
    private fun raw(values: Map<String,*>,c: BassParameters.Control)=integer(values,c.key)?.takeIf { it in c.min..c.max } ?: c.default
    fun value(values: Map<String,*>,c: BassParameters.Control): Int =
        if ((c==low || c==high) && raw(values,low)>=raw(values,high)) c.default else raw(values,c)
    fun mode(values: Map<String,*>)=integer(values,MODE)?.takeIf { it in 0..1 } ?: 0
    fun channel(values: Map<String,*>)=integer(values,CHANNEL)?.takeIf { it in 0..1 } ?: 0
    fun bandEnabled(values: Map<String,*>)=values[BAND_ENABLED] as? Boolean ?: false
    fun acceptsFrequency(values: Map<String,*>,key: String,next: Int)=when(key) {
        low.key -> next<value(values,high)
        high.key -> next>value(values,low)
        else -> true
    }
    fun migrate(prefs: SharedPreferences) {
        val values=prefs.all; val edit=prefs.edit(); var changed=false
        all.forEach { c -> if(values.containsKey(c.key) && values[c.key]!=value(values,c)) {
            edit.putInt(c.key,value(values,c)); changed=true
        } }
        listOf(MODE to mode(values),CHANNEL to channel(values)).forEach { (key,v) ->
            if(values.containsKey(key) && values[key]!=v) { edit.putInt(key,v); changed=true }
        }
        if(values.containsKey(BAND_ENABLED) && values[BAND_ENABLED] !is Boolean) {
            edit.putBoolean(BAND_ENABLED,false); changed=true
        }
        if(changed)edit.apply()
    }
    fun packet(values: Map<String,*>)=intArrayOf(mode(values),value(values,delay),channel(values),value(values,mix),
        if(bandEnabled(values))1 else 0,value(values,low),value(values,high))
    fun requiresNewApp(values: Map<String,*>)=newKeys.any(values::containsKey)
}
