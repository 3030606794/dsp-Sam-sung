package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.view.CompanderCurve
import org.junit.Assert.*
import org.junit.Test

class CompanderCurveTest {
    private val frequencies=doubleArrayOf(95.0,200.0,400.0,800.0,1600.0,3400.0,7500.0)
    @Test fun independentlyRenderedNativePchipMatchesTheDisplayedNonlinearShape() {
        // Golden values rendered by native interpolation.c, not this Kotlin implementation.
        val coefficients=doubleArrayOf(-.8,-.3,.2,.6,.1,-.5,.4)
        val queries=doubleArrayOf(50.0,100.0,500.0,1000.0,3000.0,6000.0,20000.0)
        val expected=floatArrayOf(-.8f,-.7974749324057275f,.346875f,.5388413573085846f,-.45555937479113556f,.12675236865396616f,.4f)
        val result=FloatArray(7)
        assertTrue(CompanderCurve.fill(frequencies,coefficients,queries,result,7))
        assertArrayEquals(expected,result,1e-7f)
    }
    @Test fun constantCurveAndEndpointsRemainConstantAcrossTheDisplay() {
        val query=DoubleArray(241){it*100.0};val result=FloatArray(query.size)
        assertTrue(CompanderCurve.fill(frequencies,DoubleArray(7){-.37},query,result,query.size))
        result.forEach {assertEquals(-.37f,it,0f)}
    }
    @Test fun customFrequencyNodesAndSignsPassThroughTheirExactSavedCoefficients() {
        val custom=doubleArrayOf(80.5,210.0,450.0,900.0,1700.0,3700.0,8000.0)
        val values=doubleArrayOf(-.8,-.3,.2,.6,.1,-.5,.4);val result=FloatArray(7)
        assertTrue(CompanderCurve.fill(custom,values,custom,result,7))
        values.forEachIndexed {i,n->assertEquals(n.toFloat(),result[i],0f)}
    }
    @Test fun interpolationDoesNotInventExtraCompressionOrExpansionBetweenNodes() {
        val values=doubleArrayOf(-.8,-.3,.2,.6,.1,-.5,.4)
        for(i in 0..5) {
            val query=DoubleArray(101){frequencies[i]+(frequencies[i+1]-frequencies[i])*it/100.0};val result=FloatArray(101)
            assertTrue(CompanderCurve.fill(frequencies,values,query,result,101))
            result.forEach {assertTrue(it>=minOf(values[i],values[i+1]).toFloat()-1e-6 && it<=maxOf(values[i],values[i+1]).toFloat()+1e-6)}
        }
    }
    @Test fun invalidAxesAndNonfiniteValuesNeverPartiallyOverwriteTheLastPlot() {
        val result=FloatArray(3){.17f};val query=doubleArrayOf(100.0,500.0,1000.0)
        for(freq in listOf(frequencies.copyOf().apply {this[3]=this[2]},frequencies.copyOf().apply {this[1]=Double.NaN})) {
            assertFalse(CompanderCurve.fill(freq,DoubleArray(7),query,result,3));assertArrayEquals(floatArrayOf(.17f,.17f,.17f),result,0f)
        }
        assertFalse(CompanderCurve.fill(frequencies,DoubleArray(7){Double.POSITIVE_INFINITY},query,result,3))
        assertFalse(CompanderCurve.fill(frequencies,DoubleArray(7),query.copyOf().apply {this[2]=Double.NaN},result,3))
    }
}
