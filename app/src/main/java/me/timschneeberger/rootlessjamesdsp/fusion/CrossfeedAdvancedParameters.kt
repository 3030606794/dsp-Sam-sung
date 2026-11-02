package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

object CrossfeedAdvancedParameters {
    const val CAPABILITY=1048576
    const val PACKET=65643
    const val TOOLS=65644
    const val RUNTIME=131221
    const val COMPENSATION="v_cure2_center_on"
    const val SEPARATE="v_cure2_separate_on"
    const val FILTER="v_cure2_slope_on"
    const val DIAGNOSTICS="v_cure2_diagnostics_on"
    const val SLOPE="v_cure2_slope_stages"
    val amount=BassParameters.Control("v_cure2_center_percent","中央音色补偿量",0,100,100,unit="%")
    val left=BassParameters.Control("v_cure2_left_to_right_tenths","左 → 右交叉衰减",10,150,60,10,"dB",1)
    val right=BassParameters.Control("v_cure2_right_to_left_tenths","右 → 左交叉衰减",10,150,60,10,"dB",1)
    val leftDelay=BassParameters.Control("v_cure2_left_to_right_us","左 → 右附加延时",0,500,0,unit="μs")
    val rightDelay=BassParameters.Control("v_cure2_right_to_left_us","右 → 左附加延时",0,500,0,unit="μs")
    val numbers=listOf(amount,left,right,leftDelay,rightDelay)
    val flags=setOf(COMPENSATION,SEPARATE,FILTER,DIAGNOSTICS)
    val keys=numbers.map {it.key}.toSet()+flags+SLOPE
    private val defaults=listOf(1,0,100,0,60,60,0,0,0,2,0)
    private val lows=listOf(1,0,0,0,10,10,0,0,0,1,0)
    private val highs=listOf(1,1,100,1,150,150,500,500,1,4,1)
    data class Settings(val words:List<Int> = defaults){
        fun valid()=words.size==11 && words.indices.all {words[it] in lows[it]..highs[it]}
        fun values():Map<String,Any> = mapOf(COMPENSATION to (words[1]==1),amount.key to words[2],SEPARATE to (words[3]==1),
            left.key to words[4],right.key to words[5],leftDelay.key to words[6],rightDelay.key to words[7],
            FILTER to (words[8]==1),SLOPE to words[9],DIAGNOSTICS to (words[10]==1))
    }
    fun value(v:Map<String,*>,c:BassParameters.Control)=BassParameters.value(v,c)
    fun on(v:Map<String,*>,key:String)=v[key] as? Boolean ?: false
    fun stages(v:Map<String,*>)=(v[SLOPE] as? Int)?.takeIf {it in 1..4} ?: 2
    fun capture(v:Map<String,*>)=Settings(listOf(1,if(on(v,COMPENSATION))1 else 0,value(v,amount),if(on(v,SEPARATE))1 else 0,
        value(v,left),value(v,right),value(v,leftDelay),value(v,rightDelay),if(on(v,FILTER))1 else 0,stages(v),if(on(v,DIAGNOSTICS))1 else 0))
    fun packet(v:Map<String,*>)=capture(v).words.toIntArray()
    fun normalize(p:SharedPreferences){
        val v=p.all;val edit=p.edit();var changed=false
        capture(v).values().forEach {(key,value)->if(v.containsKey(key) && v[key]!=value){when(value){is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value)};changed=true}}
        if(changed)edit.apply()
    }
    data class Diagnostic(val data:List<Int>){val rate get()=data[2];val on get()=data[4]==1;val peaks get()=data.subList(5,7)}
    fun decode(v:IntArray):Diagnostic?=v.takeIf {it.size==212 && it[0]==1 && it[1] and 1==0 && it[2] in 8000..768000 &&
        it[3] in 0..1 && it[4] in 0..1 && Settings(it.slice(9..19)).valid()}?.let {Diagnostic(it.toList())}
}
