package me.timschneeberger.rootlessjamesdsp.fusion

object ConvolutionAdvancedRuntime {
    data class Snapshot(val rate: Int,val plans: Int,val requestedMode: Int,val actualMode: Int,val fallback: Int,
        val remaining: Int,val oldFrames: Int,val memoryKiB: Int,val cutoffs: List<Int>,val commonDelay: Int,
        val comparison: Int,val filterTransition: Int,val crc: Int,val frames: Int)
    fun decode(v: IntArray): Snapshot? {
        if(v.size!=20 || v[0]!=1 || v[1] and 1!=0 || v[2] !in 8000..768000 || v[3] !in 1..2 ||
            v[4] !in 0..2 || v[5] !in 0..3 || v[6] !in 0..2 || v[7]<0 || v[8]<0 || v[9]<0 ||
            (10..13).any {v[it]<0 || v[it]>v[2]/2} || v[14] !in 0..16 || v[15] !in -120..120 || v[16]<0 || v[19]<0)return null
        return Snapshot(v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v.slice(10..13),v[14],v[15],v[16],v[18],v[19])
    }
}
