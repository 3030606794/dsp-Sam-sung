package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PeqPrecisionTest {
    private fun band(q: Double, type: PeqFilterType = PeqFilterType.PEAK, gain: Double = 12.0) =
        PeqBand("precise", type, true, 1000.0, gain, q)
    private fun wireQ(band: PeqBand): Double {
        val payload = PeqProtocol.payload(PeqState(listOf(band)), true)
        return Double.fromBits((payload[7].toLong() and 0xffffffffL) or (payload[8].toLong() shl 32))
    }

    @Test fun fullQRangeAndIndependentStepsPersistWithoutQuantizingStoredDoubles() {
        val state = PeqState(listOf(band(.0001), band(30000.0).copy(id = "maximum")),
            qStep = .0001, bwStep = .0001)
        assertEquals(state, PeqRepository.decode(PeqRepository.encode(state)))
        assertEquals(30000.0, state.qRange, 0.0)
        assertEquals(.0001, state.bands.first().q, 0.0)
        for (invalid in listOf(0.0, .000099, Double.NaN, 1.0001)) {
            assertThrows(IllegalArgumentException::class.java) { PeqModel.validate(state.copy(bwStep = invalid)) }
        }
        assertThrows(IllegalArgumentException::class.java) { PeqModel.validateBand(band(.000099)) }
        assertThrows(IllegalArgumentException::class.java) { PeqModel.validateBand(band(30000.01)) }
    }

    @Test fun oldFullRangeMigratesOnceButCustomAndNewExplicitRangesArePreserved() {
        val oldFull = PeqRepository.encode(PeqState(listOf(band(2.0)), qRange = 100.0))
            .replace("\"schema\":3", "\"schema\":2").replace("\"bwStep\":0.01,", "")
        val migrated = PeqRepository.decode(oldFull)
        assertEquals(30000.0, migrated.qRange, 0.0)
        assertEquals(.01, migrated.bwStep, 0.0)
        assertEquals(30.0, PeqRepository.decode(oldFull.replace("\"qRange\":100.0", "\"qRange\":30.0")).qRange, 0.0)
        val explicit = PeqState(listOf(band(.0001)), qRange = 100.0, bwStep = .0001)
        assertEquals(explicit, PeqRepository.decode(PeqRepository.encode(explicit)))
    }

    @Test fun qAndBandwidthRoundTripMonotonicallyAcrossTheExpandedRangeAndSampleRates() {
        for (rate in listOf(44100.0, 48000.0, 96000.0, 384000.0)) for (frequency in listOf(0.0, 1.0, 20.0, 1000.0, 20000.0)) {
            var lastWidth = Double.POSITIVE_INFINITY
            for (q in listOf(.0001, .0002, .001, .01, .1, 1.0, 100.0, 1000.0, 30000.0)) {
                val width = PeqResponse.bandwidthOctaves(q, frequency, rate)
                assertTrue(width < lastWidth)
                assertEquals(q, PeqResponse.qFromBandwidth(width, frequency, rate), max(q * 2e-14, 2e-18))
                lastWidth = width
            }
        }
    }

    @Test fun bandwidthRangeAndQEndpointStayGloballyConstantAsFrequencyMoves() {
        val reference = PeqResponse.maximumQ(1.0)
        for (frequency in listOf(0.0, 20.0, 1000.0, 20000.0)) {
            val maximum = PeqResponse.maximumQ(frequency)
            assertEquals(reference, maximum, 0.0)
            assertTrue(maximum <= 30000.0)
            assertEquals(.0001, PeqResponse.bandwidthOctaves(maximum, frequency), 1e-16)
            assertEquals(20.0, PeqResponse.maximumQ(frequency, 20.0), 0.0)
        }
        val saved = PeqState(listOf(band(30000.0)))
        assertSame(saved, PeqModel.validate(saved))
        assertTrue(saved.bands.single().q > PeqResponse.maximumQ(1000.0))
    }

    @Test fun bulkBandwidthKeepsItsGlobalRangeWhenQDisplayRangeIsRestricted() {
        val state = PeqState(listOf(
            band(1.0).copy(id = "one", frequency = 1.0, qualityDisplay = PeqQualityDisplay.BW),
            band(1.0).copy(id = "high", frequency = 20000.0, qualityDisplay = PeqQualityDisplay.BW)
        ), qRange = 20.0)
        assertEquals(PeqModel.MIN_BW..PeqModel.MAX_BW, PeqBulkParameters.bandwidthRange(state))
        val narrow = PeqBulkParameters.quality(state, PeqQualityDisplay.BW, PeqModel.MIN_BW)
        assertTrue(narrow.bands.all { it.q > 20.0 })
        assertEquals(narrow.bands[0].q, narrow.bands[1].q, 0.0)
        assertSame(narrow, PeqModel.validate(narrow))
        val qCards = state.copy(bands = state.bands.map { it.copy(qualityDisplay = PeqQualityDisplay.Q) })
        assertThrows(IllegalArgumentException::class.java) { PeqBulkParameters.quality(qCards, PeqQualityDisplay.Q, 21.0) }
    }

    @Test fun doubleWireRetainsDirectQAndBandwidthTenThousandthEditsIncludingBothExtremes() {
        for (q in listOf(.0001, .001, .1, 1.0, 100.0, 29999.0)) {
            assertNotEquals(wireQ(band(q)), wireQ(band(PeqQualityEditor.step(q, .0001))), 0.0)
        }
        for (frequency in listOf(20.0, 1000.0, 20000.0)) {
            val widest = PeqResponse.bandwidthOctaves(.0001, frequency)
            val q1 = PeqResponse.qFromBandwidth(widest, frequency)
            val q2 = PeqResponse.qFromBandwidth(PeqQualityEditor.step(widest, -.0001), frequency)
            assertNotEquals(wireQ(band(q1)), wireQ(band(q2)), 0.0)
            val actual1 = PeqResponse.bandwidthOctaves(wireQ(band(q1)), frequency)
            val actual2 = PeqResponse.bandwidthOctaves(wireQ(band(q2)), frequency)
            assertEquals(.0001, actual1 - actual2, 1e-12)
        }
    }

    @Test fun decimalSteppingAndThreeDigitPresentationNeverReplacePreciseInput() {
        var value = .0001
        repeat(10000) { value = PeqQualityEditor.step(value, .0001) }
        assertEquals(1.0001, value, 0.0)
        repeat(10000) { value = PeqQualityEditor.step(value, -.0001) }
        assertEquals(.0001, value, 0.0)
        assertEquals("0.002", PeqQualityEditor.format(.00149999999999999))
        assertEquals("0.001", PeqQualityEditor.format(.00149))
        val editor = PeqQualityEditor(.0001, 1000.0, PeqQualityDisplay.Q)
        assertEquals("0.0001", editor.renderedText)
        repeat(20) {
            editor.switchMode(PeqQualityDisplay.BW, editor.renderedText, 1000.0)
            editor.switchMode(PeqQualityDisplay.Q, editor.renderedText, 1000.0)
        }
        assertEquals(.0001, requireNotNull(editor.readQ(editor.renderedText, 1000.0)), 0.0)
        val bandwidth = PeqQualityEditor(2.0, 1000.0, PeqQualityDisplay.BW)
        assertNotEquals(requireNotNull(bandwidth.readQ("1.1234", 1000.0)),
            requireNotNull(bandwidth.readQ("1.1235", 1000.0)), 0.0)
    }

    @Test fun everyFilterFollowsIndependentDigitalBiquadResponseAtVeryLowAndHighQuality() {
        for (type in PeqFilterType.entries) for (q in listOf(.0001, .001, .1, 1.0, 100.0)) {
            for (gain in listOf(-12.0, 12.0)) for (frequency in listOf(20.0, 250.0, 700.0, 1001.0, 1500.0, 10000.0, 20000.0)) {
                val requested = band(q, type, gain)
                val expected = cookbook(type, frequency, gain, wireQ(requested))
                assertEquals("$type Q=$q gain=$gain f=$frequency", expected,
                    PeqResponse.responseDb(PeqState(listOf(requested)), frequency), 2e-6)
            }
        }
    }

    @Test fun compensatedNeedleIsGainSymmetricAndAdaptiveCurveRetainsRealSkirts() {
        val boosted = PeqState(listOf(band(100.0, gain = 100.0)))
        val cut = boosted.copy(bands = listOf(band(100.0, gain = -100.0)))
        assertEquals(100.0, PeqResponse.responseDb(boosted, 1000.0), 1e-8)
        assertTrue(PeqResponse.responseDb(boosted, 1010.0) < 2.0)
        for (frequency in listOf(990.0, 999.0, 1000.0, 1001.0, 1010.0)) {
            assertEquals(0.0, PeqResponse.responseDb(boosted, frequency) + PeqResponse.responseDb(cut, frequency), 1e-8)
        }
        val curve = PeqResponse.adaptiveCurve(boosted, 64)
        assertTrue(curve.any { it.frequency == 1000.0 && abs(it.db - 100.0) < 1e-8 })
        assertTrue(curve.count { abs(it.frequency - 1000.0) < .001 } > 10)
        val skirt = curve.single { abs(it.frequency - 1001.0) < 1e-9 }
        assertEquals(cookbook(PeqFilterType.PEAK, 1001.0, 100.0, 100.0), skirt.db, 1e-5)
        assertTrue(skirt.db > .1 && skirt.db < 100.0)
    }

    @Test fun oneHertzIsARealFilterAndWorkersUseTheActualHighSampleRate() {
        val oneHz = PeqState(listOf(band(.7071067811865476, PeqFilterType.LOW_PASS, 0.0).copy(frequency = 1.0)))
        assertEquals(1.0, PeqResponse.curve(oneHz, 16, 384000.0).first().frequency, 0.0)
        assertEquals(20*log10(.7071067811865476), PeqResponse.responseDb(oneHz, 1.0, 384000.0), 1e-8)
        assertTrue(PeqResponse.responseDb(oneHz, 100.0, 384000.0) < -70.0)
        val state = PeqState(listOf(band(1.0, PeqFilterType.LOW_PASS, 0.0).copy(frequency = 20000.0)))
        val complete = CountDownLatch(1)
        var result: List<PeqResponse.Point>? = null
        val worker = PeqResponse.PreviewWorker({ _, points -> result = points; complete.countDown() })
        try {
            worker.submit(PeqResponse.PreviewWorker.Request(state, 64, 1, sampleRate = 384000.0))
            assertTrue(complete.await(3, TimeUnit.SECONDS))
            result!!.forEach { assertEquals(PeqResponse.responseDb(state, it.frequency, 384000.0), it.db, 1e-10) }
            assertTrue(abs(PeqResponse.responseDb(state, 10000.0, 384000.0) - PeqResponse.responseDb(state, 10000.0, 48000.0)) > .05)
        } finally { worker.close() }
    }

    @Test fun graphSettingsRoundTripAndNeverChangeAnyDspWord() {
        val state = PeqState(listOf(band(.0001)), graphMinFrequency = 1.0, graphMaxFrequency = 200.0)
        assertEquals(state, PeqRepository.decode(PeqRepository.encode(state)))
        val full = state.copy(graphMaxFrequency = 768000.0)
        assertArrayEquals(PeqProtocol.payload(state, true), PeqProtocol.payload(full, true))
        assertThrows(IllegalArgumentException::class.java) { PeqModel.validate(state.copy(graphMinFrequency = 201.0)) }
        assertThrows(IllegalArgumentException::class.java) { PeqModel.validate(state.copy(graphMaxFrequency = 768001.0)) }
        val maximumFrequency = band(1.0).copy(frequency = 768000.0)
        assertSame(maximumFrequency, PeqModel.validateBand(maximumFrequency))
    }

    @Test fun graphPreservesLocalZoomAndTruncatesFullRangesAtExactNyquist() {
        val state = PeqState(listOf(band(1.0)), graphMaxFrequency = 768000.0)
        val cache = PeqResponse.PreviewCache()
        for (rate in listOf(48000.0, 384000.0, 768000.0)) {
            val curve = PeqResponse.adaptiveCurve(state, 128, rate)
            assertEquals(1.0, curve.first().frequency, 0.0)
            assertEquals(rate / 2.0, curve.last().frequency, 0.0)
            assertTrue(curve.any { it.frequency == rate / 2.0 })
            curve.forEach { if (it.frequency > rate/2) assertTrue(it.db.isNaN()) else assertTrue(it.db.isFinite()) }
            val cached = requireNotNull(cache.curve(state, 128, rate))
            assertEquals(curve.map { it.frequency }, cached.map { it.frequency })
            curve.zip(cached).forEach { (expected, actual) ->
                if(expected.db.isNaN()) assertTrue(actual.db.isNaN()) else assertEquals(expected.db, actual.db, 1e-10)
            }
        }
        val local = state.copy(graphMaxFrequency = 200.0)
        val curve = requireNotNull(cache.curve(local, 64, 48000.0))
        assertEquals(1.0, curve.first().frequency, 0.0)
        assertEquals(200.0, curve.last().frequency, 0.0)
        assertTrue(curve.all { it.frequency in 1.0..200.0 && it.db.isFinite() })
    }

    /** Direct RBJ digital coefficients, independent of the preview's analog/SVF evaluation. */
    private fun cookbook(type: PeqFilterType, frequency: Double, gain: Double, decodedQ: Double): Double {
        val w0 = 2 * PI * 1000 / 48000
        val c = cos(w0); val s = sin(w0); val a = 10.0.pow(gain / 40)
        val t = log10(decodedQ).coerceIn(0.0, 1.0)
        val q = if (type == PeqFilterType.PEAK) decodedQ * 10.0.pow(abs(gain) * t*t*(3-2*t) / 40) else decodedQ
        val alpha = s / (2*q); val beta = 2*sqrt(a)*alpha
        val b: DoubleArray; val d: DoubleArray
        when (type) {
            PeqFilterType.PEAK -> { b=doubleArrayOf(1+alpha*a,-2*c,1-alpha*a);d=doubleArrayOf(1+alpha/a,-2*c,1-alpha/a) }
            PeqFilterType.LOW_SHELF -> { b=doubleArrayOf(a*((a+1)-(a-1)*c+beta),2*a*((a-1)-(a+1)*c),a*((a+1)-(a-1)*c-beta));d=doubleArrayOf((a+1)+(a-1)*c+beta,-2*((a-1)+(a+1)*c),(a+1)+(a-1)*c-beta) }
            PeqFilterType.HIGH_SHELF -> { b=doubleArrayOf(a*((a+1)+(a-1)*c+beta),-2*a*((a-1)+(a+1)*c),a*((a+1)+(a-1)*c-beta));d=doubleArrayOf((a+1)-(a-1)*c+beta,2*((a-1)-(a+1)*c),(a+1)-(a-1)*c-beta) }
            PeqFilterType.LOW_PASS -> { b=doubleArrayOf((1-c)/2,1-c,(1-c)/2);d=doubleArrayOf(1+alpha,-2*c,1-alpha) }
            PeqFilterType.HIGH_PASS -> { b=doubleArrayOf((1+c)/2,-1-c,(1+c)/2);d=doubleArrayOf(1+alpha,-2*c,1-alpha) }
            PeqFilterType.NOTCH -> { b=doubleArrayOf(1.0,-2*c,1.0);d=doubleArrayOf(1+alpha,-2*c,1-alpha) }
            PeqFilterType.BAND_PASS -> { b=doubleArrayOf(alpha,0.0,-alpha);d=doubleArrayOf(1+alpha,-2*c,1-alpha) }
        }
        val w = 2*PI*frequency/48000
        fun magnitude(v: DoubleArray) = hypot(v[0]+v[1]*cos(w)+v[2]*cos(2*w), -v[1]*sin(w)-v[2]*sin(2*w))
        return (20*log10(magnitude(b)/magnitude(d)) + if(type.isUnityAtZeroGain)0.0 else gain).coerceAtLeast(PeqResponse.FLOOR_DB)
    }
}
