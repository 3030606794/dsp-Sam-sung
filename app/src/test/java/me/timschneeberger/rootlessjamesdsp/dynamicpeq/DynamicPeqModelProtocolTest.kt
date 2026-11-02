package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import org.junit.Assert.*
import org.junit.Test

class DynamicPeqModelProtocolTest {
    @Test fun defaultBankIsOffAndDoesNotTouchStaticPeqProtocol() {
        val state = DynamicPeqModel.defaultState()
        assertFalse(state.enabled)
        assertEquals(listOf(100.0, 1000.0, 6000.0), state.bands.map { it.frequency })
        assertTrue(state.bands.all { it.gain == 0.0 && it.dynamicEnabled })
        assertEquals(196720, DynamicPeqProtocol.PARAMETER)
        assertNotEquals(me.timschneeberger.rootlessjamesdsp.peq.PeqProtocol.PARAMETER, DynamicPeqProtocol.PARAMETER)
    }

    @Test fun wireUnitsPreserveEveryIndependentDynamicControl() {
        val band = DynamicPeqBand("wire", type = 2, enabled = false, frequency = 6789.125,
            gain = -3.125, q = 2.345, dynamicEnabled = false, threshold = -37.125,
            ratio = 4.567, range = 8.125, attack = 0.125, release = 999.125, knee = 7.125)
        assertArrayEquals(intArrayOf(1, 1, 1, 2, 0, 6789125, -3125, 2345, 0, -37125, 4567,
            8125, 125, 999125, 7125), DynamicPeqProtocol.payload(DynamicPeqState(true, listOf(band))))
        assertArrayEquals(intArrayOf(1, 0, 0), DynamicPeqProtocol.payload(DynamicPeqState(false, emptyList())))
    }

    @Test fun validationRejectsInvalidAudioParametersBeforeTransport() {
        val band = DynamicPeqBand("valid")
        val bad = listOf(band.copy(frequency = Double.NaN), band.copy(frequency = 19.999),
            band.copy(frequency = 20000.001), band.copy(type = 3), band.copy(gain = 24.001),
            band.copy(q = 0.099), band.copy(q = 30.001), band.copy(threshold = 0.001),
            band.copy(ratio = 0.999), band.copy(range = -0.001), band.copy(attack = 0.099),
            band.copy(release = 3000.001), band.copy(knee = Double.POSITIVE_INFINITY))
        bad.forEach { invalid -> assertThrows(IllegalArgumentException::class.java) {
            DynamicPeqProtocol.payload(DynamicPeqState(true, listOf(invalid)))
        } }
        assertThrows(IllegalArgumentException::class.java) {
            DynamicPeqModel.validate(DynamicPeqState(bands = List(17) { band.copy(id = "$it") }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DynamicPeqModel.validate(DynamicPeqState(bands = listOf(band, band)))
        }
    }

    @Test fun malformedRuntimeIsNeverShownAsMeasuredReduction() {
        val packet = IntArray(40).apply {
            this[0] = 1; this[1] = 1; this[2] = 1; this[3] = 48000; this[4] = 512
            this[5] = -3125; this[6] = -6125; this[8] = -19125; this[24] = 4125
        }
        val runtime = requireNotNull(DynamicPeqProtocol.decodeRuntime(packet))
        assertEquals(-19.125, runtime.detectorRms.single(), 0.0)
        assertEquals(4.125, runtime.reductions.single(), 0.0)
        assertFalse(runtime.isProcessing)
        listOf(packet.copyOf(39), packet.copyOf().apply { this[0] = 2 },
            packet.copyOf().apply { this[24] = -1 }, packet.copyOf().apply { this[2] = 17 },
            packet.copyOf().apply { this[3] = 0 }).forEach { assertNull(DynamicPeqProtocol.decodeRuntime(it)) }
        assertTrue(DynamicPeqController.progressed(512, 1024))
        assertFalse(DynamicPeqController.progressed(1024, 512))
        assertFalse(DynamicPeqController.progressed(512, 512))
        assertTrue(DynamicPeqController.progressed(0x7fffff00, 256))
    }

    @Test fun metersBelongToExactlyTheAppliedParameterBank() {
        val state = DynamicPeqState(true, listOf(DynamicPeqBand("hash-test", threshold = -35.125)))
        val packet = IntArray(40).apply {
            this[0] = 1; this[1] = 1; this[2] = 1; this[3] = 48000; this[4] = 512
            this[7] = DynamicPeqProtocol.payload(state).contentHashCode()
        }
        val runtime = requireNotNull(DynamicPeqProtocol.decodeRuntime(packet))
        assertEquals(packet[7], runtime.parameterHash)
        assertTrue(DynamicPeqController.configurationMatches(state, runtime))
        assertFalse(DynamicPeqController.configurationMatches(state, runtime.copy(parameterHash = 0)))
        assertFalse(DynamicPeqController.configurationMatches(state.copy(enabled = false), runtime))
        assertFalse(DynamicPeqController.configurationMatches(state.copy(bands = state.bands.map {
            it.copy(threshold = -18.125)
        }), runtime))
        assertFalse(DynamicPeqController.configurationMatches(state, runtime.copy(bandCount = 0)))
    }
}
