package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomReverbControllerTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val p = RoomReverbParameters
    private val c = RoomReverbController
    private val prefs get() = c.preferences(app)
    private val identity = Any()
    private val owner = Any()
    private val caps = 33554431
    @Before fun prepare() {
        c.stopAudition(app, clearSnapshots = true); c.publishRuntime(app, emptyList()); prefs.edit().clear().commit()
        c.publishCapabilities(app, caps); p.apply(prefs, p.defaults(15, true)); prefs.edit().putBoolean(p.ENABLED, true).commit()
    }
    @After fun cleanup() { c.sessionClosed(app, identity); c.stopAudition(app, clearSnapshots = true); c.publishRuntime(app, emptyList()) }
    private fun reading(frames: Int, mode: Int = c.audition, stage: Int = 0, epoch: Int = c.epoch, ready: Boolean = true, rate: Int = 48000): RoomReverbController.Reading {
        val v = IntArray(32).apply { this[0] = 1; this[1] = 2; this[2] = rate; this[3] = 1; this[4] = 1; this[5] = mode;
            this[7] = stage; this[11] = epoch; this[21] = if (ready) 1 else 0; this[22] = 15; this[23] = 16000; this[25] = 2000 }
        return RoomReverbController.Reading(identity, true, frames, rate, p.decode(v))
    }
    private fun active() { c.publishRuntime(app, listOf(reading(0))); c.publishRuntime(app, listOf(reading(512))) }
    @Test fun syncSendsExactCoreAndIndependentSnapshotPacketsWithoutLegacyReinitialization() {
        val packets = linkedMapOf<Int, IntArray>()
        assertTrue(c.sync(app, caps, identity) { id, v -> packets[id] = v; true })
        assertEquals(listOf(1424, 1425), packets.keys.toList()); assertEquals(24, packets[1424]!!.size)
        val tools = packets[1425]!!; assertEquals(52, tools.size)
        assertArrayEquals(intArrayOf(0, 0, c.epoch, 0), tools.copyOfRange(0, 4))
        assertArrayEquals(p.defaults().packet(), tools.copyOfRange(4, 28)); assertArrayEquals(p.defaults().packet(), tools.copyOfRange(28, 52))
    }
    @Test fun oldDriverReceivesNoNewCommandsAndStoredSettingsRemainUntouched() {
        val before = prefs.all; var sends = 0
        assertTrue(c.sync(app, 16777215, identity) { _, _ -> sends++; true })
        assertEquals(0, sends); assertEquals(before, prefs.all)
        c.publishCapabilities(app, 16777215); assertFalse(c.supported(app)); assertFalse(c.startAudition(app, owner, 2))
    }
    @Test fun activeFrameReadyAndActualProfessionalGatesAllListeningWithoutChangingPower() {
        assertFalse(c.startAudition(app, owner, 1)); active()
        for (mode in 1..4) assertTrue(c.startAudition(app, owner, mode))
        c.publishRuntime(app, listOf(reading(1024, ready = false))); assertEquals(0, c.audition); assertFalse(c.canListen(app))
        active(); prefs.edit().putBoolean(p.ENABLED, false).commit(); c.sync(app, caps, identity) { _, _ -> true }
        assertEquals(0, c.audition); assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun savingOriginalModeCapturesBaseSoundAndIgnoresSavedFineParameters() {
        p.apply(prefs, p.defaults(7).copy(timeOn = true, rt60 = 237, earlyWidth = 713, linked = true, mix = 417))
        assertTrue(c.saveComparison(app, owner, 0)); p.setProfessional(prefs, true); assertTrue(c.saveComparison(app, owner, 1))
        val packets = linkedMapOf<Int, IntArray>(); c.sync(app, caps, identity) { id, v -> packets[id] = v; true }
        assertArrayEquals(p.defaults(7).packet(), packets[p.TOOLS]!!.copyOfRange(4, 28))
        assertArrayEquals(p.capture(prefs.all).packet(), packets[p.TOOLS]!!.copyOfRange(28, 52))
    }
    @Test fun measurementNeedsBothSnapshotsAndCompletesBeforeFixedAttenuationCanBeSelected() {
        active(); assertFalse(c.measure(app, owner)); assertFalse(c.startAudition(app, owner, 5))
        c.saveComparison(app, owner, 0); prefs.edit().putInt(p.rt60.key, 237).commit(); c.saveComparison(app, owner, 1)
        assertTrue(c.measure(app, owner)); assertEquals(7, c.audition); assertFalse(c.setMatch(app, true))
        c.publishRuntime(app, listOf(reading(1024, 7, 1))); assertFalse(c.matchingReady())
        c.publishRuntime(app, listOf(reading(1536, 7, 2))); assertFalse(c.setMatch(app, true))
        c.publishRuntime(app, listOf(reading(2048, 7, 3))); assertTrue(c.setMatch(app, true)); assertTrue(c.startAudition(app, owner, 5))
        assertFalse(prefs.all.keys.any { it.contains("snapshot") || it.contains("audition") || it.contains("match") })
    }
    @Test fun silentMeasurementAndSnapshotRevisionCannotEstablishOrReuseMatching() {
        active(); c.saveComparison(app, owner, 0); c.saveComparison(app, owner, 1); c.measure(app, owner)
        c.publishRuntime(app, listOf(reading(1024, 7, 4))); assertFalse(c.setMatch(app, true))
        c.publishRuntime(app, listOf(reading(1536, 7, 3))); assertTrue(c.setMatch(app, true))
        val before = c.epoch; assertTrue(c.startAudition(app, owner, 7)); assertTrue(c.epoch != before); assertFalse(c.match)
        c.saveComparison(app, owner, 1); assertFalse(c.matchingReady())
    }
    @Test fun preferenceEditsAcrossOtherModulesStopAuditionAndCapabilityLossClearsSnapshots() {
        active(); c.sync(app, caps, identity) { _, _ -> true }; c.saveComparison(app, owner, 0); c.startAudition(app, owner, 2)
        val viper = ReverbController.preferences(app); viper.edit().putInt(ReverbParameters.size.key, 713).commit()
        c.sync(app, caps, identity) { _, _ -> true }; assertEquals(0, c.audition)
        assertTrue(c.startAudition(app, owner, 3)); assertFalse(c.sync(app, caps, identity) { id, _ -> id != p.TOOLS }); assertEquals(0, c.audition)
        c.publishCapabilities(app, 16777215); assertFalse(c.hasComparison(0))
    }
    @Test fun sessionCloseAndOffRejectStalePollsAndRequireFreshAudioFrames() {
        active(); c.startAudition(app, owner, 4); val poll = c.pollState(); c.sessionClosed(app, identity)
        c.publishRuntime(app, listOf(reading(1024, 4)), poll); assertEquals(0, c.audition); assertFalse(c.canListen(app))
        c.sessionEnabled(app, identity, false); c.publishRuntime(app, listOf(reading(1536))); assertFalse(c.canListen(app))
        c.sessionEnabled(app, identity, true); c.publishRuntime(app, listOf(reading(2048))); assertFalse(c.canListen(app))
        c.publishRuntime(app, listOf(reading(2560))); assertTrue(c.canListen(app))
    }
    @Test fun samplingRateChangeStopsAuditionBeforeTheNewSessionCanListen() {
        active(); c.startAudition(app, owner, 2)
        c.publishRuntime(app, listOf(reading(1024, 2, rate = 44100))); assertEquals(0, c.audition); assertFalse(c.canListen(app))
        c.publishRuntime(app, listOf(reading(1536, rate = 44100))); assertTrue(c.canListen(app))
    }
}
