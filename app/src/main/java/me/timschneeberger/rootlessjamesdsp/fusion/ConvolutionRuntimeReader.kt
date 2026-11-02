package me.timschneeberger.rootlessjamesdsp.fusion

import kotlin.math.roundToInt

/** One atomic native snapshot; signal readings are only valid for complete, active windows. */
object ConvolutionRuntimeReader {
    const val FOLD_NEGATIVE_INFINITY = Int.MIN_VALUE+1
    data class Meter(val peak: Int,val rms: Int,val correlation: Int,val fold: Int)
    data class Snapshot(val rate: Int,val flags: Int,val channels: Int,val frames: Int,val crc: Int,
        val delaySamples: Int,val delayUs: Int,val algorithmSamples: Int,val audition: Int,
        val mix: Int,val wet: Int,val output: Int,val left: Int,val right: Int,val invertLeft: Boolean,
        val invertRight: Boolean,val swap: Boolean,val input: Meter,val wetMeter: Meter,val result: Meter,val windowFrames: Int) {
        val loaded get()=flags and 1!=0
        val moduleEnabled get()=flags and 2!=0
        val available get()=flags and 4!=0
        val ready get()=flags and 8!=0
    }
    fun decode(v: IntArray): Snapshot? {
        if(v.size!=32 || v[0]!=1 || v[1] and 1!=0 || v[3] !in 0..15)return null
        val rate=v[2]
        // A configured kernel/CRC survives an idle host stream. Its sample rate is unknown,
        // but active signal or complete-window flags may only accompany a real host rate.
        if(rate !in 8000..768000 && (rate!=0 || v[3] and 12!=0))return null
        val validDelay=if(rate==0) {
            // Retained delay fields belong to the last internal rate; do not divide by zero
            // or incorrectly require zero delay before the next host configuration arrives.
            // 200 ms at 768 kHz is 153600 samples; rounding at 8 kHz can add 63 microseconds.
            v[7] in 0..153600 && v[8] in 0..200063
        } else v[7] in 0..(rate*.2).roundToInt() && v[8]==(v[7]*1e6/rate).roundToInt()
        if(v[4] !in listOf(0,1,2,4) || v[5]<0 || !validDelay || v[9] !in 0..16 ||
            v[10] !in 0..3 || v[11] !in 0..1000 || v[12] !in -240..120 || v[13] !in -240..0 ||
            v[14] !in -120..120 || v[15] !in -120..120 || v[16] !in 0..1 || v[17] !in 0..1 || v[18] !in 0..1 || v[31]<0)return null
        fun meter(i: Int): Meter? {
            val rho=v[i+2];if(rho!=Int.MIN_VALUE && rho !in -10000..10000)return null
            return Meter(v[i],v[i+1],rho,v[i+3])
        }
        return Snapshot(v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],v[14],v[15],
            v[16]!=0,v[17]!=0,v[18]!=0,meter(19)?:return null,meter(23)?:return null,meter(27)?:return null,v[31])
    }
    fun decodeBulk(v: IntArray)=decode(v)
}
