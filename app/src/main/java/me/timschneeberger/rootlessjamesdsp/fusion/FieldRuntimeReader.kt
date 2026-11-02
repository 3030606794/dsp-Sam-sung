package me.timschneeberger.rootlessjamesdsp.fusion

/** The epoch covers both generations, so input/output meters never combine different windows. */
object FieldRuntimeReader {
    const val EPOCH = 131127
    data class Snapshot(val basic: List<Int>, val advanced: FieldAdvancedParameters.Runtime?)
    fun reading(capabilities: Int, identity: Any, enabled: Boolean, rate: Int, frames: Int?, get: (Int) -> Int?): FieldController.Reading? {
        if (capabilities and FieldController.CAPABILITY == 0) return null
        val snapshot=read(capabilities,get)
        if (snapshot==null) {
            // Inconsistent telemetry is not evidence that this confirmed session has stopped.
            return if (capabilities and FieldAdvancedParameters.CAPABILITY != 0)
                FieldController.Reading(identity,enabled,rate,frames,emptyList(),null,null,null,null,null,false)
            else null
        }
        val v=snapshot.basic
        return FieldController.Reading(identity,enabled,rate,frames,v.take(3),v[3],v[4],v[5],v[6],v[7],v[8]==1,advanced=snapshot.advanced)
    }
    fun read(capabilities: Int, get: (Int) -> Int?): Snapshot? {
        if (capabilities and FieldController.CAPABILITY == 0) return null
        val advanced=capabilities and FieldAdvancedParameters.CAPABILITY != 0
        repeat(if (advanced) 3 else 1) {
            val before=if (advanced) get(EPOCH) ?: return@repeat else 0
            if (before and 1 != 0) return@repeat
            val basic=(131103..131111).map(get)
            val extra=if (advanced) FieldAdvancedParameters.Runtime.decode((131112..131125).map(get)) else null
            val after=if (advanced) get(EPOCH) ?: return@repeat else 0
            if (before != after || after and 1 != 0 || basic.any { it == null } || (advanced && extra==null)) return@repeat
            val b=basic.filterNotNull()
            if (b.take(3).any { it<0 } || b[2]<=0 ||
                (b[3]!=Int.MIN_VALUE && b[3] !in -10000..10000) || b[7] !in 0..(if (advanced) 4 else 3) || b[8] !in 0..1) return@repeat
            return Snapshot(b,extra)
        }
        return null
    }
}
