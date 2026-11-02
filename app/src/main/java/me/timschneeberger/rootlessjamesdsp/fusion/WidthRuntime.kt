package me.timschneeberger.rootlessjamesdsp.fusion

object WidthRuntime {
    data class Snapshot(val data: List<Int>) {
        val rate get()=data[2]
        val enabled get()=data[3]==1
        val mode get()=data[4]
        val audition get()=data[24]
        val ready get()=data[25]==1
    }
    fun decode(v: IntArray): Snapshot? {
        if(v.size!=32 || v[0]!=1 || v[1]<0 || v[1]%2!=0 || (v[2]!=0 && v[2] !in 8000..768000) || v[3] !in 0..1 || v[4] !in 0..2 || v[24] !in 0..9 || v[25] !in 0..1 || v[26]<0 || v[30] !in 0..1)return null
        if(listOf(5,6).any {v[it]!=Int.MIN_VALUE && v[it] !in -10000..10000})return null
        if((15..18).any {v[it]!=Int.MIN_VALUE && v[it] !in 0..4000} || v[19] !in 0..1000 || v[28] !in 0..1000)return null
        if(v[20] !in -60..60 || v[27] !in -120..60 || v[29] !in -120..60)return null
        if((21..23).any {v[it]<0 || (v[2]>0 && v[it]>v[2]*0.45+1)})return null
        if(v[25]==1 && (v[3]==0 || v[2]==0))return null
        return Snapshot(v.toList())
    }
}
