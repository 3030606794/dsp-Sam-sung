package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import org.junit.Assert.*
import org.junit.Test

class DynamicPeqResponseTest {
    @Test fun peakCenterMatchesStaticGainMinusRealReductionAcrossQualityValues() {
        for (q in listOf(.1, .707, 1.0, 8.0, 30.0)) {
            val state = DynamicPeqState(true, listOf(DynamicPeqBand(frequency = 1000.0, gain = 12.0, q = q)))
            assertEquals(12.0, DynamicPeqResponse.db(state, 1000.0), .000001)
            assertEquals(8.0, DynamicPeqResponse.db(state, 1000.0, reductions = listOf(4.0)), .000001)
            assertEquals(6.0, DynamicPeqResponse.db(state, 1000.0, reductions = listOf(50.0)), .000001)
        }
    }
    @Test fun disablingBandOrItsDynamicsHasIndependentResponse() {
        val band = DynamicPeqBand(frequency = 1000.0, gain = 3.0, range = 9.0)
        assertEquals(-3.0, DynamicPeqResponse.db(DynamicPeqState(true, listOf(band)), 1000.0, reductions = listOf(6.0)), .000001)
        assertEquals(3.0, DynamicPeqResponse.db(DynamicPeqState(true, listOf(band.copy(dynamicEnabled = false))),
            1000.0, reductions = listOf(6.0)), .000001)
        assertEquals(0.0, DynamicPeqResponse.db(DynamicPeqState(true, listOf(band.copy(enabled = false))),
            1000.0, reductions = listOf(6.0)), .000001)
    }
    @Test fun shelfLimitsAndNyquistBypassMatchIndependentEngine() {
        val low = DynamicPeqState(true, listOf(DynamicPeqBand(type = 1, frequency = 1000.0, gain = 9.0)))
        val high = DynamicPeqState(true, listOf(DynamicPeqBand(type = 2, frequency = 1000.0, gain = 9.0)))
        assertEquals(9.0, DynamicPeqResponse.db(low, 0.0), .000001)
        assertEquals(0.0, DynamicPeqResponse.db(high, 0.0), .000001)
        assertEquals(0.0, DynamicPeqResponse.db(low, 24000.0), .000001)
        assertEquals(9.0, DynamicPeqResponse.db(high, 24000.0), .000001)
        val unavailable = DynamicPeqState(true, listOf(DynamicPeqBand(frequency = 20000.0, gain = 12.0)))
        assertEquals(0.0, DynamicPeqResponse.db(unavailable, 1000.0, rate = 32000.0), .000001)
    }
}
