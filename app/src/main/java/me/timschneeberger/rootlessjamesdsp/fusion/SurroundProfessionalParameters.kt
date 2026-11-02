package me.timschneeberger.rootlessjamesdsp.fusion

import kotlin.math.*

/** Scaled integers shared with SurroundProfessionalContract.h; no old keys are migrated. */
object SurroundProfessionalParameters {
    const val CAPABILITY=65536
    const val PACKET=196610
    const val AUDITION=196611
    const val RUNTIME=131217
    data class Entry(val control: BassParameters.Control,val index: Int?,val kind: String="number",val step: Int=1)
    private fun e(key:String,title:String,min:Int,max:Int,default:Int,index:Int?,div:Int=1,unit:String="",dec:Int=0,kind:String="number",step:Int=1)=
        Entry(BassParameters.Control(key,title,min,max,default,div,unit,dec),index,kind,step)
    val entries=listOf(
        e("enabled","启用专业抵消与混合",0,1,0,1,kind="switch"),
        e("mix","干湿混合",0,1000,1000,2,10,"%",1),
        e("pre_db","输入预衰减",-180,0,0,3,10,"dB",1),
        e("output_db","输出校准",-120,60,0,4,10,"dB",1),
        e("link","联动双向抵消",0,1,1,5,kind="switch"),
        e("lr_attenuation","左→右抵消支路衰减",500,10000,5500,6,1000,"dB",3,step=10),
        e("rl_attenuation","右→左抵消支路衰减",500,10000,5500,7,1000,"dB",3,step=10),
        e("lr_delay","左→右抵消延迟",200,2100,350,8,10,"μs",1),
        e("rl_delay","右→左抵消延迟",200,2100,350,9,10,"μs",1),
        e("hp_slope","低频边界滤波斜率",0,2,1,10,kind="choice"),
        e("lp_slope","高频边界滤波斜率",0,2,1,11,kind="choice"),
        e("rms_on","RMS 自动电平匹配",0,1,0,12,kind="switch"),
        e("rms_limit","RMS 最大补偿",0,60,30,13,10,"dB",1),
        e("rms_attack","RMS 衰减启动",10,300,80,14,unit="ms"),
        e("rms_release","RMS 恢复时间",100,3000,800,15,unit="ms"),
        e("rms_attenuation_only","RMS 仅衰减",0,1,1,16,kind="switch"),
        e("peak_on","启用采样峰值保护",0,1,0,17,kind="switch"),
        e("peak_ceiling","采样峰值上限",-120,-1,-10,18,10,"dBFS",1),
        e("peak_release","峰值保护恢复",10,500,100,19,unit="ms"),
        e("meters","启用本模块监测",0,1,0,20,kind="switch"),
        e("speaker_spacing","扬声器中心间距",50,3000,600,null,10,"cm",1),
        e("listening_distance","听音位置到扬声器连线距离",200,5000,2000,null,10,"cm",1),
        e("ear_spacing","双耳间距",120,220,180,null,10,"cm",1),
        e("response_view","曲线显示",0,2,0,null,kind="choice")
    )
    val byKey=(entries+SurroundListeningParameters.entries).associateBy {it.control.key}
    val keys=byKey.keys
    fun default(s:SurroundController.Settings,e:Entry)=when(e.index){6,7->s.attenuationMilliDb;8,9->s.delayUs*10;else->e.control.default}
    fun value(s:SurroundController.Settings,key:String):Int {val e=byKey.getValue(key);return (s.professional[key] ?: default(s,e)).coerceIn(e.control.min,e.control.max)}
    fun on(s:SurroundController.Settings,key:String)=value(s,key)==1
    fun sanitized(values:Map<String,Int>):Map<String,Int> {
        val result=values.filterKeys {it in keys}.mapValues {(key,v)->val c=byKey.getValue(key).control;v.coerceIn(c.min,c.max)}.toMutableMap()
        if((result["cal_low"] ?: 200)>=(result["cal_high"] ?: 8000))result["cal_high"]=(result["cal_low"] ?: 200)+1
        return result
    }
    fun with(s:SurroundController.Settings,key:String,v:Int):SurroundController.Settings {
        val c=byKey.getValue(key).control;require(v in c.min..c.max)
        val next=s.copy(professional=s.professional+mapOf(key to v))
        require(value(next,"cal_low")<value(next,"cal_high")){"噪声低频边界必须小于高频边界"}
        return next
    }
    fun packet(s:SurroundController.Settings,enabled:Boolean):IntArray {
        val p=IntArray(27);p[0]=1;p[1]=if(enabled)1 else 0;p[2]=s.lowHz;p[3]=s.highHz;p[4]=s.attenuationMilliDb;p[5]=s.delayUs;p[6]=1
        entries.forEach {e->e.index?.let {p[6+it]=value(s,e.control.key)}};return p
    }
    data class Geometry(val angleDegrees:Double,val differenceMm:Double,val delayTenthsUs:Int,val applicable:Boolean)
    fun geometry(s:SurroundController.Settings):Geometry {
        val spacing=value(s,"speaker_spacing").toDouble();val distance=value(s,"listening_distance").toDouble();val ears=value(s,"ear_spacing").toDouble()
        val delta=hypot(distance,(spacing+ears)/2)-hypot(distance,(spacing-ears)/2)
        val delay=(delta/343000*1e7).roundToInt()
        return Geometry(2*atan(spacing/(2*distance))*180/PI,delta,delay,delay in 200..2100)
    }
    fun applyGeometry(s:SurroundController.Settings):SurroundController.Settings? {
        val g=geometry(s);if(!g.applicable)return null
        return s.copy(professional=s.professional+mapOf("lr_delay" to g.delayTenthsUs,"rl_delay" to g.delayTenthsUs))
    }
    fun requiresNewApp(values:Map<String,*>)=values.keys.any {key->key.matches(Regex("slot_[0-7]_pro_.+"))}
}
