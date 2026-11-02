package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.util.AttributeSet

class CompanderSurface(context: Context?, attrs: AttributeSet?) : BaseEqualizerSurface(context, attrs, 7, 40.0, 20000.0, -1.2, 1.2, 0.2f) {
    private var nodes=SCALE.copyOf()
    fun setFrequencies(values: DoubleArray) {
        if(values.size!=7 || values.any {!it.isFinite() || it<=0 || it>=24000} || values.asList().zipWithNext().any {(a,b)->a>=b})return
        nodes=values.copyOf();invalidate()
    }
    override fun computeCurve(
        freqs: DoubleArray,
        gains: DoubleArray,
        resolution: Int,
        dispFreq: DoubleArray,
        response: FloatArray
    ) {
        if(!CompanderCurve.fill(freqs,gains,dispFreq,response,resolution))response.fill(0f)
    }

    override val frequencyScale: DoubleArray
        get() = nodes

    companion object {
        val SCALE = doubleArrayOf(95.0, 200.0, 400.0, 800.0, 1600.0, 3400.0, 7500.0)
    }
}
