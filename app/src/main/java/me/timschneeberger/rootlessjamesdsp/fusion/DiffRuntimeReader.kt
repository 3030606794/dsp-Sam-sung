package me.timschneeberger.rootlessjamesdsp.fusion

/** The native epoch covers configuration and the complete input/output monitoring window. */
object DiffRuntimeReader {
    const val EPOCH = 131148
    data class Snapshot(val delayUs: Int,val samplesMilli: Int,val channel: Int,val commonSamples: Int,val commonUs: Int,
        val lowHz: Int,val highHz: Int,val inputCorrelation: Int,val outputCorrelation: Int,
        val inputMonoFold: Int,val monoFold: Int,val inputPeak: Int,val peak: Int,
        val inputMidRms: Int,val inputSideRms: Int,val midRms: Int,val sideRms: Int,
        val ready: Boolean,val audition: Int,val switching: Boolean,val mode: Int,val mix: Int=1000,val bandEnabled: Boolean=false,
        val advanced: DiffAdvancedParameters.Runtime?=null)
    fun decodeBulk(values: IntArray): Snapshot? {
        if(values.size!=84 || values[20] and 1!=0)return null
        val second=DiffAdvancedParameters.Runtime.decode(values.drop(24)) ?: return null
        val basic=decode(values.take(20)+values.slice(21..23),4) ?: return null
        return basic.copy(advanced=second)
    }
    private fun decode(v: List<Int>,auditionMax: Int=2): Snapshot? {
        if(v.size!=23 || v[0] !in 0..50000 || v[1] !in 0..38400000 || v[2] !in 0..1 ||
            v[3] !in 0..768000 || v[4] !in 0..1000000 || v[5] !in 0..384000 || v[6] !in 0..384000 ||
            listOf(v[7],v[8]).any { it!=Int.MIN_VALUE && it !in -10000..10000 } ||
            v[17] !in 0..1 || v[18] !in 0..auditionMax || v[19] !in 0..1 || v[20] !in 0..1 || v[21] !in 0..1000 || v[22] !in 0..1)return null
        return Snapshot(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],v[14],v[15],v[16],v[17]==1,v[18],v[19]==1,v[20],v[21],v[22]==1)
    }
    fun read(capabilities: Int,get: (Int)->Int?): Snapshot? {
        if(capabilities and DiffParameters.CAPABILITY==0)return null
        repeat(3) {
            val before=get(EPOCH) ?: return@repeat
            if(before and 1!=0)return@repeat
            val values=(131128..131147).map(get)+(131149..131151).map(get)
            val after=get(EPOCH) ?: return@repeat
            if(before!=after || after and 1!=0 || values.any { it==null })return@repeat
            val v=values.filterNotNull()
            return decode(v) ?: return@repeat
        }
        return null
    }
    fun reading(capabilities: Int,identity: Any,enabled: Boolean,rate: Int,frames: Int?,get: (Int)->Int?): DiffController.Reading? {
        if(capabilities and DiffParameters.CAPABILITY==0)return null
        // An incomplete snapshot is not proof that this confirmed session has been disabled.
        return DiffController.Reading(identity,enabled,rate,frames,read(capabilities,get))
    }
}
