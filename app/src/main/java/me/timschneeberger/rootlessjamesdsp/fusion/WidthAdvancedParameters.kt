package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.SharedPreferences

/** Generated ordered contract; ui23 keys and Float legacy width remain untouched. */
object WidthAdvancedParameters {
    const val CAPABILITY=32768
    const val PACKET=1412
    const val AUDITION=1413
    const val RUNTIME=131216
    const val RUNTIME_WORDS=176
    data class Entry(val control: BassParameters.Control,val kind: String,val group: Int,val band: Int,val items: List<String>)
    val entries=listOf(
        Entry(BassParameters.Control("width2_dyn0_on","低频动态宽度",0,1,0,1,"",0), "switch", 1, 0, listOf()),
        Entry(BassParameters.Control("width2_dyn0_threshold","低频 Side/Mid 触发阈值",-300,60,-60,10,"dB",1), "number", 1, 0, listOf()),
        Entry(BassParameters.Control("width2_dyn0_ratio","低频动态压缩比",10,100,20,10,":1",1), "number", 1, 0, listOf()),
        Entry(BassParameters.Control("width2_dyn0_knee","低频软拐点宽度",0,120,30,10,"dB",1), "number", 1, 0, listOf()),
        Entry(BassParameters.Control("width2_dyn0_attack","低频动态启动",2,200,20,1,"ms",0), "number", 1, 0, listOf()),
        Entry(BassParameters.Control("width2_dyn0_release","低频动态恢复",50,2000,300,1,"ms",0), "number", 1, 0, listOf()),
        Entry(BassParameters.Control("width2_dyn0_max","低频最大动态回收",0,1000,500,10,"%",1), "number", 1, 0, listOf()),
        Entry(BassParameters.Control("width2_dyn1_on","中频动态宽度",0,1,0,1,"",0), "switch", 1, 1, listOf()),
        Entry(BassParameters.Control("width2_dyn1_threshold","中频 Side/Mid 触发阈值",-300,60,-60,10,"dB",1), "number", 1, 1, listOf()),
        Entry(BassParameters.Control("width2_dyn1_ratio","中频动态压缩比",10,100,20,10,":1",1), "number", 1, 1, listOf()),
        Entry(BassParameters.Control("width2_dyn1_knee","中频软拐点宽度",0,120,30,10,"dB",1), "number", 1, 1, listOf()),
        Entry(BassParameters.Control("width2_dyn1_attack","中频动态启动",2,200,20,1,"ms",0), "number", 1, 1, listOf()),
        Entry(BassParameters.Control("width2_dyn1_release","中频动态恢复",50,2000,300,1,"ms",0), "number", 1, 1, listOf()),
        Entry(BassParameters.Control("width2_dyn1_max","中频最大动态回收",0,1000,500,10,"%",1), "number", 1, 1, listOf()),
        Entry(BassParameters.Control("width2_dyn2_on","高频动态宽度",0,1,0,1,"",0), "switch", 1, 2, listOf()),
        Entry(BassParameters.Control("width2_dyn2_threshold","高频 Side/Mid 触发阈值",-300,60,-60,10,"dB",1), "number", 1, 2, listOf()),
        Entry(BassParameters.Control("width2_dyn2_ratio","高频动态压缩比",10,100,20,10,":1",1), "number", 1, 2, listOf()),
        Entry(BassParameters.Control("width2_dyn2_knee","高频软拐点宽度",0,120,30,10,"dB",1), "number", 1, 2, listOf()),
        Entry(BassParameters.Control("width2_dyn2_attack","高频动态启动",2,200,20,1,"ms",0), "number", 1, 2, listOf()),
        Entry(BassParameters.Control("width2_dyn2_release","高频动态恢复",50,2000,300,1,"ms",0), "number", 1, 2, listOf()),
        Entry(BassParameters.Control("width2_dyn2_max","高频最大动态回收",0,1000,500,10,"%",1), "number", 1, 2, listOf()),
        Entry(BassParameters.Control("width2_dyn3_on","全频动态宽度",0,1,0,1,"",0), "switch", 1, 3, listOf()),
        Entry(BassParameters.Control("width2_dyn3_threshold","全频 Side/Mid 触发阈值",-300,60,-60,10,"dB",1), "number", 1, 3, listOf()),
        Entry(BassParameters.Control("width2_dyn3_ratio","全频动态压缩比",10,100,20,10,":1",1), "number", 1, 3, listOf()),
        Entry(BassParameters.Control("width2_dyn3_knee","全频软拐点宽度",0,120,30,10,"dB",1), "number", 1, 3, listOf()),
        Entry(BassParameters.Control("width2_dyn3_attack","全频动态启动",2,200,20,1,"ms",0), "number", 1, 3, listOf()),
        Entry(BassParameters.Control("width2_dyn3_release","全频动态恢复",50,2000,300,1,"ms",0), "number", 1, 3, listOf()),
        Entry(BassParameters.Control("width2_dyn3_max","全频最大动态回收",0,1000,500,10,"%",1), "number", 1, 3, listOf()),
        Entry(BassParameters.Control("width2_transient_on","启用瞬态扩宽抑制",0,1,0,1,"",0), "switch", 2, -1, listOf()),
        Entry(BassParameters.Control("width2_transient_bands","瞬态作用频段",0,7,7,1,"",0), "choice", 2, -1, listOf("不选择频段","低频","中频","低＋中频","高频","低＋高频","中＋高频","低＋中＋高频")),
        Entry(BassParameters.Control("width2_transient_threshold","瞬态包络差阈值",0,180,60,10,"dB",1), "number", 2, -1, listOf()),
        Entry(BassParameters.Control("width2_transient_max","最大瞬态回收",0,1000,600,10,"%",1), "number", 2, -1, listOf()),
        Entry(BassParameters.Control("width2_transient_attack","瞬态启动",1,20,2,1,"ms",0), "number", 2, -1, listOf()),
        Entry(BassParameters.Control("width2_transient_hold","瞬态保持",0,100,20,1,"ms",0), "number", 2, -1, listOf()),
        Entry(BassParameters.Control("width2_transient_release","瞬态恢复",20,1000,100,1,"ms",0), "number", 2, -1, listOf()),
        Entry(BassParameters.Control("width2_guard_on","启用高级相关度保护",0,1,0,1,"",0), "switch", 3, -1, listOf()),
        Entry(BassParameters.Control("width2_guard_mode","高级保护联动方式",0,1,0,1,"",0), "choice", 3, -1, listOf("全频联动","分段独立")),
        Entry(BassParameters.Control("width2_guard_window","保护检测窗口",10,500,100,1,"ms",0), "number", 3, -1, listOf()),
        Entry(BassParameters.Control("width2_guard_point","保护检测位置",0,1,0,1,"",0), "choice", 3, -1, listOf("湿声候选","干湿混合候选")),
        Entry(BassParameters.Control("width2_guard_floor","保护有效信号门限",-900,-300,-600,10,"dBFS",1), "number", 3, -1, listOf()),
        Entry(BassParameters.Control("width2_guard_low","低频相关度阈值",-20,50,0,100,"",2), "number", 3, -1, listOf()),
        Entry(BassParameters.Control("width2_guard_mid","中频相关度阈值",-20,50,0,100,"",2), "number", 3, -1, listOf()),
        Entry(BassParameters.Control("width2_guard_high","高频相关度阈值",-20,50,0,100,"",2), "number", 3, -1, listOf()),
        Entry(BassParameters.Control("width2_eq0_on","启用扩宽增量均衡 1",0,1,0,1,"",0), "switch", 4, -1, listOf()),
        Entry(BassParameters.Control("width2_eq0_hz","增量均衡 1 频率",80,16000,400,1,"Hz",0), "number", 4, -1, listOf()),
        Entry(BassParameters.Control("width2_eq0_gain","增量均衡 1 增益",-60,60,0,10,"dB",1), "number", 4, -1, listOf()),
        Entry(BassParameters.Control("width2_eq0_q","增量均衡 1 Q 值",30,400,100,100,"",2), "number", 4, -1, listOf()),
        Entry(BassParameters.Control("width2_eq1_on","启用扩宽增量均衡 2",0,1,0,1,"",0), "switch", 4, -1, listOf()),
        Entry(BassParameters.Control("width2_eq1_hz","增量均衡 2 频率",80,16000,6000,1,"Hz",0), "number", 4, -1, listOf()),
        Entry(BassParameters.Control("width2_eq1_gain","增量均衡 2 增益",-60,60,0,10,"dB",1), "number", 4, -1, listOf()),
        Entry(BassParameters.Control("width2_eq1_q","增量均衡 2 Q 值",30,400,100,100,"",2), "number", 4, -1, listOf()),
        Entry(BassParameters.Control("width2_auto_on","启用电平匹配高级响应",0,1,0,1,"",0), "switch", 5, -1, listOf()),
        Entry(BassParameters.Control("width2_auto_attack","匹配衰减响应",10,500,80,1,"ms",0), "number", 5, -1, listOf()),
        Entry(BassParameters.Control("width2_auto_release","匹配增益恢复",100,3000,800,1,"ms",0), "number", 5, -1, listOf()),
        Entry(BassParameters.Control("width2_auto_deadband","匹配变化死区",0,20,3,10,"dB",1), "number", 5, -1, listOf()),
        Entry(BassParameters.Control("width2_auto_floor","匹配静音冻结门限",-900,-300,-600,10,"dBFS",1), "number", 5, -1, listOf()),
        Entry(BassParameters.Control("width2_auto_direction","匹配补偿方式",0,1,1,1,"",0), "choice", 5, -1, listOf("允许双向补偿","仅衰减")),
        Entry(BassParameters.Control("width2_history","相关度历史长度",5,30,10,1,"\u79d2",0), "number", 6, -1, listOf()),
        Entry(BassParameters.Control("width2_meter_on","启用分段监测与声场图",0,1,0,1,"",0), "switch", 6, -1, listOf())
    )
    val all=entries.filter {it.kind=="number"}.map {it.control}
    val keys=entries.map {it.control.key}.toSet()
    fun value(v: Map<String,*>,e: Entry): Int {
        if(e.kind=="switch")return if(v[e.control.key] as? Boolean==true)1 else 0
        val x=(v[e.control.key] as? Number)?.toDouble()
        return x?.takeIf {it.isFinite() && it%1.0==0.0 && it>=e.control.min && it<=e.control.max}?.toInt() ?: e.control.default
    }
    fun packet(v: Map<String,*>)=intArrayOf(1)+entries.map {value(v,it)}.toIntArray()
    fun normalize(prefs: SharedPreferences) {
        val v=prefs.all;val edit=prefs.edit();var dirty=false
        entries.filter {v.containsKey(it.control.key)}.forEach {e->
            val x=value(v,e);val key=e.control.key
            if(e.kind=="switch"){if(v[key] !is Boolean){edit.putBoolean(key,x==1);dirty=true}}
            else if(v[key]!=x){edit.putInt(key,x);dirty=true}
        }
        if(dirty)edit.apply()
    }
}
