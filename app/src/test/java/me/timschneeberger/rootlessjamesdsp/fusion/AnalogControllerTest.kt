package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], application = Application::class)
class AnalogControllerTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val p = AnalogParameters
    private val c = AnalogController
    private val prefs get() = c.preferences(app)
    private val identity = Any()
    private val owner = Any()
    private val caps = 134217727
    @Before fun prepare() {
        c.stopAudition(app, clearSnapshots = true); c.publishRuntime(app, emptyList()); prefs.edit().clear().commit()
        c.publishCapabilities(app, caps); p.apply(prefs, p.defaults().copy(professional = true)); prefs.edit().putBoolean(p.ENABLED, true).commit()
    }
    @After fun cleanup() { c.sessionClosed(app, identity); c.stopAudition(app, clearSnapshots = true); c.publishRuntime(app, emptyList()) }
    private fun reading(frames: Int, mode: Int = c.audition, stage: Int = 0, epoch: Int = c.epoch, ready: Boolean = true, rate: Int = 48000): AnalogController.Reading {
        val v = IntArray(40).apply { this[0] = 1; this[1] = 2; this[2] = rate; this[3] = 1; this[4] = 1; this[5] = mode;
            this[7] = stage; this[11] = epoch; this[13] = 1; this[15] = 240; this[26] = 19650; this[27] = 633; this[32] = 20000; this[33] = 100; this[34] = 1000; this[25] = if (ready) 1 else 0; this[30] = 2000 }
        return AnalogController.Reading(identity, true, frames, rate, p.decode(v))
    }
    private fun active() { c.publishRuntime(app, listOf(reading(0))); c.publishRuntime(app, listOf(reading(512))) }
    @Test fun supportedDriverReceivesCoreThenOriginalPowerThenToolsWithBaseAwareFallbackWithoutChangingPower() {
        prefs.edit().putInt(p.BASE, 2).commit()
        val packets = mutableListOf<Pair<Int, IntArray>>()
        assertTrue(c.sync(app, caps, identity) { id, v -> packets += id to v; true })
        assertEquals(listOf(65652, 65584, 65653), packets.map { it.first }); assertEquals(28, packets[0].second.size)
        assertArrayEquals(intArrayOf(1), packets[1].second); assertEquals(60, packets[2].second.size)
        assertArrayEquals(intArrayOf(0, 0, c.epoch, 0), packets[2].second.copyOfRange(0, 4))
        assertArrayEquals(p.defaults(2).packet(), packets[2].second.copyOfRange(4, 32))
        assertArrayEquals(p.defaults(2).packet(), packets[2].second.copyOfRange(32, 60))
        prefs.edit().putBoolean(p.ENABLED, false).commit(); packets.clear(); c.sync(app, caps, identity) { id, v -> packets += id to v; true }
        assertArrayEquals(intArrayOf(0), packets[1].second); assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun viperIntegrationSendsPowerOnceAndOldDriverUsesOnlyItsOriginalPowerCommand() {
        val packets = mutableListOf<Pair<Int, IntArray>>()
        assertTrue(ViperController.sync(app, caps, identity) { id, v -> packets += id to v; true })
        val ids = packets.map { it.first }; val index = ids.indexOf(p.PACKET)
        assertEquals(listOf(65652, 65584, 65653), ids.subList(index, index + 3)); assertEquals(1, ids.count { it == 65584 }); assertFalse(ids.contains(65585))
        packets.clear(); assertTrue(ViperController.sync(app, 67108863, identity) { id, v -> packets += id to v; true })
        assertEquals(1, packets.count { it.first == 65584 }); assertEquals(1, packets.count { it.first == 65585 }); assertFalse(packets.any { it.first in listOf(p.PACKET, p.TOOLS, p.CLEAR) })
    }
    @Test fun activeFramesActualReadinessAndProfessionalStateGateListeningWithoutEnablingModule() {
        assertFalse(c.startAudition(app, owner, 1)); active()
        for (mode in 1..3) assertTrue(c.startAudition(app, owner, mode))
        c.publishRuntime(app, listOf(reading(1024, ready = false))); assertEquals(0, c.audition); assertFalse(c.canListen(app))
        active(); prefs.edit().putBoolean(p.ENABLED, false).commit(); c.sync(app, caps, identity) { _, _ -> true }
        assertFalse(c.canListen(app)); assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun originalSnapshotPreservesItsBaseAndIgnoresSavedProfessionalFineValues() {
        p.apply(prefs, p.defaults(2).copy(coreOn = true, drive = 137, toneOn = true, hp = 317, qualityOn = true, factor = 4))
        assertTrue(c.saveComparison(app, owner, 0)); p.setProfessional(prefs, true); assertTrue(c.saveComparison(app, owner, 1))
        val packets = linkedMapOf<Int, IntArray>(); c.sync(app, caps, identity) { id, v -> packets[id] = v; true }
        assertArrayEquals(p.defaults(2).packet(), packets[p.TOOLS]!!.copyOfRange(4, 32))
        assertArrayEquals(p.capture(prefs.all).packet(), packets[p.TOOLS]!!.copyOfRange(32, 60))
    }
    @Test fun matchingNeedsBothSnapshotsWarmupAndTwoSecondMeasurementThenUsesFixedAttenuation() {
        active(); assertFalse(c.measure(app, owner)); assertFalse(c.startAudition(app, owner, 4))
        c.saveComparison(app, owner, 0); prefs.edit().putInt(p.drive.key, 137).commit(); c.saveComparison(app, owner, 1)
        assertTrue(c.measure(app, owner)); assertEquals(6, c.audition); assertFalse(c.setMatch(app, true))
        c.publishRuntime(app, listOf(reading(1024, 6, 1))); assertFalse(c.matchingReady())
        c.publishRuntime(app, listOf(reading(1536, 6, 2))); assertFalse(c.setMatch(app, true))
        c.publishRuntime(app, listOf(reading(2048, 6, 3))); assertTrue(c.setMatch(app, true)); assertTrue(c.startAudition(app, owner, 4))
        assertFalse(prefs.all.keys.any { it.contains("snapshot") || it.contains("audition") || it.contains("match") })
    }
    @Test fun silenceAndNewSnapshotEpochInvalidateMatchingAndRemeasurementClearsOldMatch() {
        active(); c.saveComparison(app, owner, 0); c.saveComparison(app, owner, 1); c.measure(app, owner)
        c.publishRuntime(app, listOf(reading(1024, 6, 4))); assertFalse(c.setMatch(app, true))
        c.publishRuntime(app, listOf(reading(1536, 6, 3))); assertTrue(c.setMatch(app, true)); val epoch = c.epoch
        assertTrue(c.startAudition(app, owner, 6)); assertTrue(c.epoch != epoch); assertFalse(c.match)
        c.saveComparison(app, owner, 1); assertFalse(c.matchingReady())
    }
    @Test fun anyAudioNamespaceChangeAndFailedPowerOrToolsSendStopAuditionCapabilityLossClearsSnapshots() {
        active(); c.sync(app, caps, identity) { _, _ -> true }; c.saveComparison(app, owner, 0); c.startAudition(app, owner, 2)
        PreferenceCache.getPreferences(app, Constants.PREF_OUTPUT).edit().putFloat("output_postgain", -2f).commit()
        c.sync(app, caps, identity) { _, _ -> true }; assertEquals(0, c.audition)
        for (bad in listOf(p.PACKET, 65584, p.TOOLS)) {
            assertTrue(c.startAudition(app, owner, 3)); assertFalse(c.sync(app, caps, identity) { id, _ -> id != bad }); assertEquals(0, c.audition)
        }
        c.publishCapabilities(app, 67108863); assertFalse(c.hasComparison(0)); assertFalse(c.supported(app))
    }
    @Test fun stalePollingCannotReactivateClosedOrDisabledSessionAndRateChangeStopsAudition() {
        active(); c.startAudition(app, owner, 2); val poll = c.pollState(); c.sessionClosed(app, identity)
        c.publishRuntime(app, listOf(reading(1024, 2)), poll); assertEquals(0, c.audition); assertFalse(c.canListen(app))
        c.sessionEnabled(app, identity, false); c.publishRuntime(app, listOf(reading(1536))); assertFalse(c.canListen(app))
        c.sessionEnabled(app, identity, true); c.publishRuntime(app, listOf(reading(2048))); assertFalse(c.canListen(app))
        c.publishRuntime(app, listOf(reading(2560))); assertTrue(c.canListen(app)); c.startAudition(app, owner, 1)
        c.publishRuntime(app, listOf(reading(3072, 1, rate = 44100))); assertEquals(0, c.audition); assertFalse(c.canListen(app))
    }
    @Test fun meterDisplayToggleKeepsAuditionMatchingAndMeasurementEpochWhileStillSendingMeterState() {
        active(); c.saveComparison(app, owner, 0); c.saveComparison(app, owner, 1); c.measure(app, owner)
        c.publishRuntime(app, listOf(reading(1024, 6, 3))); assertTrue(c.setMatch(app, true)); assertTrue(c.startAudition(app, owner, 4))
        c.sync(app, caps, identity) { _, _ -> true }; val epoch = c.epoch
        for (enabled in listOf(true, false)) {
            prefs.edit().putBoolean(p.METERS, enabled).commit()
            val packets = linkedMapOf<Int, IntArray>(); assertTrue(c.sync(app, caps, identity) { id, v -> packets[id] = v; true })
            assertEquals(4, c.audition); assertTrue(c.match); assertEquals(epoch, c.epoch); assertTrue(c.matchingReady())
            assertTrue(c.hasComparison(0)); assertTrue(c.hasComparison(1))
            assertEquals(if (enabled) 1 else 0, packets[p.PACKET]!![24])
            assertArrayEquals(intArrayOf(4, 1, epoch, 0), packets[p.TOOLS]!!.copyOfRange(0, 4))
        }
        prefs.edit().putInt(p.BASE, 1).commit(); c.sync(app, caps, identity) { _, _ -> true }; assertEquals(0, c.audition)
        assertTrue(c.hasComparison(0)); assertTrue(c.hasComparison(1))
    }
}
