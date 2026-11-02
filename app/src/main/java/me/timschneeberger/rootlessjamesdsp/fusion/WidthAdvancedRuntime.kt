package me.timschneeberger.rootlessjamesdsp.fusion

object WidthAdvancedRuntime {
    val auditionLabels=listOf("正常处理","本模块原声","仅中置 Mid","仅侧声 Side","仅低频（三段模式）","仅中频（三段模式）","仅高频（三段模式）","仅新增扩宽","参数 A","参数 B")
    fun auditionLabel(value: Int)=auditionLabels.getOrElse(value){"等待确认"}
    data class Snapshot(val data: List<Int>)
    fun decode(v: IntArray): Snapshot? {
        if(v.size!=176 || v[0]!=1 || v[1]<0 || v[1]%2!=0 || (v[2]!=0 && v[2] !in 8000..768000) || v[3] !in 0..1 || v[4] !in 0..2 || v[5] !in 0..1 || v[6]<0 || v[44] !in 0..9 || v[45] !in 0..1)return null
        if((7..12).any {v[it]!=Int.MIN_VALUE && v[it] !in -10000..10000} || (35..38).any {v[it]!=Int.MIN_VALUE && v[it] !in -10000..10000})return null
        if((19..34).any {v[it] !in 0..1000} || v[43] !in -60..60 || (48..175).any {v[it] !in -1000000..1000000})return null
        if(v[5]==1 && (v[2]==0 || v[3]==0))return null
        if((46..47).any {v[it]<0 || (v[2]>0 && v[it]>v[2]*.45+1)})return null
        return Snapshot(v.toList())
    }
}
