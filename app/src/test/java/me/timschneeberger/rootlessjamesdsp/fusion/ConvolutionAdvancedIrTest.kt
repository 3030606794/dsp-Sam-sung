package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionAdvancedIr
import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionAdvancedAnalysis
import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionIrPreprocessor
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ConvolutionAdvancedIrTest {
    private val p=ConvolutionAdvancedParameters
    @Test fun neutralAndDormantOptionsPreserveExactArrayAndLegacyTopology() {
        for(channels in listOf(1,2,4)) {
            val source=FloatArray(123*channels){sin(it*.07).toFloat()};val copy=source.copyOf()
            val o=ConvolutionAdvancedParameters.IrOptions(gains=listOf(120,-240,20,70),delays=listOf(20000,12000,700,300),early=-100,late=60,decay=600)
            val result=ConvolutionAdvancedIr.edit(source,channels,192000,o)
            assertSame(source,result.samples);assertEquals(channels,result.channels);assertEquals(0,result.commonDelay);assertArrayEquals(copy,source,0f)
        }
    }
    @Test fun matrixCoefficientsPreservePathOrderMutePolarityAndMonoExpansion() {
        val source=floatArrayOf(.1f,.2f,.3f,.4f)
        val o=ConvolutionAdvancedParameters.IrOptions(matrix=true,gains=listOf(0,60,0,0),muted=listOf(false,false,true,false),inverted=listOf(false,true,false,false))
        val result=ConvolutionAdvancedIr.edit(source,4,48000,o)
        assertArrayEquals(floatArrayOf(.1f,(-.2*10.0.pow(.3)).toFloat(),0f,.4f),result.samples,1e-7f)
        assertArrayEquals(floatArrayOf(.1f,.2f,.3f,.4f),source,0f)
        val mono=ConvolutionAdvancedIr.edit(floatArrayOf(.5f),1,48000,ConvolutionAdvancedParameters.IrOptions(matrix=true,gains=listOf(0,120,120,-60)))
        assertEquals(2,mono.channels);assertEquals(.5f,mono.samples[0],0f);assertEquals((.5*10.0.pow(-.3)).toFloat(),mono.samples[1],1e-7f)
    }
    @Test fun monoToStereoUsesAnAverageInputWithoutInventingMeasuredCrossPaths() {
        for(channels in listOf(1,2,4)) {
            val source=when(channels){1->floatArrayOf(.5f);2->floatArrayOf(.5f,.25f);else->floatArrayOf(.1f,.2f,.3f,.4f)}
            val result=ConvolutionAdvancedIr.edit(source,channels,44100,ConvolutionAdvancedParameters.IrOptions(route=1))
            assertEquals(4,result.channels)
            val l=.01;val r=.03
            val expectedL=when(channels){1,2->.5*(l+r)/2;else->(.1+.3)*(l+r)/2}
            val expectedR=when(channels){1->.5*(l+r)/2;2->.25*(l+r)/2;else->(.2+.4)*(l+r)/2}
            assertEquals(expectedL,result.samples[0]*l+result.samples[2]*r,1e-9)
            assertEquals(expectedR,result.samples[1]*l+result.samples[3]*r,1e-9)
        }
    }
    @Test fun fractionalImpulseTimingAndDcGainHoldAcrossRatesAndBothAlgorithms() {
        for(rate in listOf(8000,44100,48000,96000,192000))for(method in 0..1)for(us in listOf(0,10,997,20000)) {
            val source=floatArrayOf(.5f,.25f)
            val result=ConvolutionAdvancedIr.edit(source,2,rate,ConvolutionAdvancedParameters.IrOptions(fractional=true,method=method,delays=listOf(us,0,0,us)))
            val left=result.samples.filterIndexed {i,_->i%2==0}
            assertEquals(if(method==0)1 else 16,result.commonDelay)
            assertEquals(.5,left.sumOf {it.toDouble()},2e-7)
            val centroid=left.mapIndexed {i,v->i*v.toDouble()}.sum()/left.sumOf {it.toDouble()}
            assertEquals(result.commonDelay+us*rate.toDouble()/1e6,centroid,.002)
            assertArrayEquals(floatArrayOf(.5f,.25f),source,0f)
        }
    }
    @Test fun highPrecisionFirReducesHighFrequencyAmplitudeError() {
        fun magnitude(method: Int): Double {
            val data=ConvolutionAdvancedIr.edit(floatArrayOf(1f),1,48000,ConvolutionAdvancedParameters.IrOptions(fractional=true,method=method,delays=List(4){10})).samples
            var real=0.0;var imaginary=0.0
            for(n in 0 until data.size/2){real+=data[2*n]*cos(PI*n/2);imaginary-=data[2*n]*sin(PI*n/2)}
            return hypot(real,imaginary)
        }
        assertTrue(magnitude(0)<.95);assertEquals(1.0,magnitude(1),.002)
    }
    @Test fun earlyTailEnvelopeAddsDecayWithoutMutatingOrExtendingSource() {
        val source=FloatArray(2000){1f};val copy=source.copyOf()
        val result=ConvolutionAdvancedIr.edit(source,1,8000,ConvolutionAdvancedParameters.IrOptions(shape=true,splitUs=100000,blendUs=20000,late=-60,decay=200))
        assertEquals(2000,result.frames);assertEquals(1f,result.samples[0],0f)
        assertEquals(10.0.pow((-6.0-.02*20)/20),result.samples[960].toDouble(),1e-6)
        assertTrue(result.samples[1600]<result.samples[960]);assertArrayEquals(copy,source,0f)
        assertTrue(runCatching {ConvolutionAdvancedIr.edit(floatArrayOf(1f),1,48000,ConvolutionAdvancedParameters.IrOptions(shape=true))}.isFailure)
    }
    @Test fun linkedPeakNormalizationRunsAfterMatrixAndPreservesRelativePathGains() {
        val adjusted=ConvolutionAdvancedIr.edit(floatArrayOf(.5f,.25f),2,48000,ConvolutionAdvancedParameters.IrOptions(matrix=true,gains=listOf(60,0,0,0)))
        val normalized=ConvolutionIrPreprocessor.edit(adjusted.samples,adjusted.channels,48000,ConvolutionIrPreprocessor.Options(normalize=true))
        assertEquals(10.0.pow(-1.0/20),normalized.samples.max().toDouble(),1e-7)
        assertEquals(adjusted.samples[0]/adjusted.samples[1],normalized.samples[0]/normalized.samples[1],1e-6f)
    }
    @Test fun analyticalGroupDelayUsesAllLongKernelCoefficientsAndUndefinedPhaseStaysBlank() {
        val source=FloatArray(90002).apply{this[90001]=1f}
        val data=ConvolutionAdvancedAnalysis.analyze(source,1,source,1,48000)
        assertTrue(data.groupDelayMs[0].all {abs(it-90001*1000f/48000)<.01f})
        assertTrue(data.workingDb[0].all {abs(it)<1e-4});assertEquals(0f,data.energyDb[0][0],0f)
        val silence=ConvolutionAdvancedAnalysis.analyze(FloatArray(4),1,FloatArray(4),1,48000)
        assertTrue(silence.phaseDegrees[0].all {it.isNaN()});assertTrue(silence.groupDelayMs[0].all {it.isNaN()});assertTrue(silence.energyDb[0].all {it.isNaN()})
        val original=data.workingDb[0].copyOf();ConvolutionAdvancedAnalysis.smooth(data.hz,data.workingDb[0],2);assertArrayEquals(original,data.workingDb[0],0f)
    }
}
