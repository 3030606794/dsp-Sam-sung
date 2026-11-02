package me.timschneeberger.rootlessjamesdsp.fusion

/** Native gain mask read at seven selected control frequencies; not seven independent compressors. */
object CompanderGainTelemetry {
    const val QUERY = 131251
    const val WORDS = 64
    data class Snapshot(val rate: Int, val hostRate: Int, val sequence: Int, val generation: Int,
        val mode: Int, val epoch: Int, val revision: Int, val engine: Int, val transform: Int,
        val granularity: Int, val lastFramePosition: Long, val available: Boolean,
        val source: Int, val gridPoints: Int, val controlBlockFrames: Int,
        val frequenciesHz: DoubleArray, val gainsDb: DoubleArray, val detectorDbfs: DoubleArray,
        val coefficients: DoubleArray) {
        val sampledControlPoints get() = true
    }
    fun decode(v: IntArray, expectedHostRate: Int): Snapshot? {
        if(v.size != WORDS || v[0] != 1 || v[1] != 0x43504752 || v[2] < 0 || v[3] < 0 ||
            v[5] !in listOf(0,7) || v[6] !in 0..8191 || v[7] !in 0..6 || v[8] < 0 || v[9] < 0 ||
            v[11] !in 0..1 || v[12] !in 0..3 || v[13] !in 0..4 || v[16] !in listOf(0,127) ||
            v[17] !in 0..2 || v[18] < 0 || v[19] < 0 || v[20] != WORDS || v[21] != 1 ||
            (22..31).any { v[it] != 0 } || listOf(39,47,55,63).any { v[it] != 0 }) return null
        val available = v[5] == 7 && v[16] == 127
        if(available && (v[4] !in 8000..768000 || v[10] != expectedHostRate || v[17] !in 1..2 ||
            (32..38).any { v[it] <= 0 || v[it].toDouble() / 1000.0 >= v[4] / 2.0 } ||
            (40..46).any { v[it] == Int.MIN_VALUE || v[it] !in -20000..20000 })) return null
        val last = (v[15].toLong() shl 32) or (v[14].toLong() and 0xffffffffL)
        return Snapshot(v[4],v[10],v[2],v[3],v[7],v[8],v[9],v[11],v[12],v[13],last,available,
            v[17],v[18],v[19],DoubleArray(7){v[32+it]/1000.0},DoubleArray(7){v[40+it]/100.0},
            DoubleArray(7){ if(v[48+it] == Int.MIN_VALUE) Double.NaN else v[48+it]/100.0 },
            DoubleArray(7){v[56+it]/1000.0})
    }
}
