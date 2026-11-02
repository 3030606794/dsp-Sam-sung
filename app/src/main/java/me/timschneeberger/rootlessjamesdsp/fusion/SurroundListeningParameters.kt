package me.timschneeberger.rootlessjamesdsp.fusion

/** ui26 controls. Trigger, measurements and A/B gains are intentionally absent. */
object SurroundListeningParameters {
    const val CAPABILITY=131072
    const val PACKET=196612
    const val RUNTIME=131218
    private fun e(key:String,title:String,min:Int,max:Int,default:Int,div:Int=1,unit:String="",dec:Int=0,kind:String="number")=
        SurroundProfessionalParameters.Entry(BassParameters.Control(key,title,min,max,default,div,unit,dec),null,kind)
    val entries=listOf(
        e("match_on","启用等响度 A/B 试听",0,1,0,kind="switch"),
        e("match_limit","试听最大衰减",0,120,60,10,"dB",1),
        e("match_window","每组电平测量时长",500,10000,3000,unit="ms"),
        e("cal_on","允许手动校准试听",0,1,0,kind="switch"),
        e("cal_mode","校准信号",0,3,2,kind="choice"),
        e("cal_level","校准信号峰值",-600,-120,-300,10,"dBFS",1),
        e("cal_frequency","正弦信号频率",20,16000,1000,unit="Hz"),
        e("cal_low","噪声低频边界",20,16000,200,unit="Hz"),
        e("cal_high","噪声高频边界",21,20000,8000,unit="Hz"),
        e("cal_duration","校准试听时长",1000,30000,5000,unit="ms"),
        e("cal_fade","校准淡入淡出",20,500,100,unit="ms"),
        e("diagnostics_on","显示相位与时间诊断",0,1,0,kind="switch"),
        e("diagnostics_view","诊断视图",0,2,0,kind="choice"),
        e("impulse_window","脉冲响应显示时长",1,100,20,unit="ms")
    )
    val keys=entries.map {it.control.key}.toSet()
    val displayKeys=setOf("diagnostics_on","diagnostics_view","impulse_window")
    fun requiresNewApp(values:Map<String,*>)=values.keys.any {key->keys.any {key.matches(Regex("slot_[0-7]_pro_$it"))}}
    fun packet(s:SurroundController.Settings,epoch:Int,calibration:Int,token:Int):IntArray {
        val p=SurroundProfessionalParameters
        return intArrayOf(1,p.value(s,"match_on"),p.value(s,"match_limit"),p.value(s,"match_window"),epoch,
            calibration,p.value(s,"cal_level"),p.value(s,"cal_frequency"),p.value(s,"cal_low"),p.value(s,"cal_high"),
            p.value(s,"cal_duration"),p.value(s,"cal_fade"),if(calibration==0)0 else token)
    }
    fun decode(v:IntArray):List<Int>? {
        if(v.size!=24 || v[0]!=1 || v[1]<0 || v[1]%2!=0 || v[2] !in 8000..768000 || v[4] !in 0..4 || v[13] !in listOf(0,3,4))return null
        if(listOf(3,5,6,7,20,21).any {v[it] !in 0..1} || (8..9).any {v[it]!=Int.MIN_VALUE && v[it] !in -120000..120000})return null
        if((10..12).any {v[it] !in -12000..0} || v[14] !in 0..1000 || v[15] !in 0..4 || v[16] !in 0..30000 || v[19]<1 || v[23]<0)return null
        if(v[17] !in 1..16000 || v[18] !in 1..20000 || v[17]>=v[18] || v[18]>v[2]*.45+1 || v[22] !in 1..16000 || v[22]>v[2]*.45+1)return null
        if((v[6]==1 && v[8]==Int.MIN_VALUE) || (v[7]==1 && v[9]==Int.MIN_VALUE) || (v[3]==0 && (v[4]!=0 || v[15]!=0)))return null
        return v.toList()
    }
}
