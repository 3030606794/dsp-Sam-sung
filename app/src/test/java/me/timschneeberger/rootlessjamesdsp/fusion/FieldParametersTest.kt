package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FieldParametersTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = FieldController.preferences(app)
    private val p = FieldParameters
    @Before fun prepare() {
        preferences.edit().clear().commit()
        FieldController.stopAudition(app); FieldController.publishRuntime(app,emptyList()); FieldController.publishCapabilities(app,511)
        HighFrequencyController.stopAudition(app); HighFrequencyController.publishRuntime(app,emptyList()); HighFrequencyController.publishCapabilities(app,511)
    }
    private fun reading(identity: Any, frames: Int = 0, audition: Int = 0, enabled: Boolean = true, rate: Int = 48000) =
        FieldController.Reading(identity,enabled,rate,frames,listOf(150,16000,120),10000,-600,Int.MIN_VALUE,-300,audition,true)
    @Test fun defaultsLegacyUnitsAndIndependentDepthModesMatchTheNativePacket() {
        assertArrayEquals(intArrayOf(0,1000,0,0,150,16000,0,120,1000,1,1000,0,0),p.packet(emptyMap<String,Any>()))
        assertArrayEquals(intArrayOf(1,1000,0,0,150,16000,0,120,1000,0,1000,0,0),p.packet(mapOf(p.MODE to 1)))
        assertEquals(1001,p.width.parse("100.1")); assertEquals(-119,p.midTrim.parse("-11.9"))
        assertEquals(999,p.focusWidth.parse("99.9")); assertEquals(201,p.depth.parse("201"))
        assertNull(p.legacyWidth.parse("0.1")); assertNull(p.legacyCenter.parse("150.1")); assertNull(p.depth.parse("200.1"))
        preferences.edit().putBoolean(p.LEGACY_DEPTH_ENABLED,false).putBoolean(p.MS_DEPTH_ENABLED,true).commit()
        assertEquals(0,p.packet(preferences.all)[9])
        preferences.edit().putInt(p.MODE,1).commit(); assertEquals(1,p.packet(preferences.all)[9])
        preferences.edit().putInt(p.MODE,0).commit(); assertEquals(0,p.packet(preferences.all)[9])
    }
    @Test fun malformedImportedTypesAndCrossedBoundsNormalizeBeforeBindingOrSending() {
        preferences.edit().putFloat(p.width.key,1000.5f).putString(p.midTrim.key,"0")
            .putInt(p.bandLow.key,2000).putInt(p.bandHigh.key,2000).putInt(p.MODE,2)
            .putString(p.LEGACY_DEPTH_ENABLED,"false").putLong(p.depth.key,500L).commit()
        p.migrate(preferences)
        assertEquals(1000,preferences.getInt(p.width.key,-1)); assertEquals(0,preferences.getInt(p.MODE,-1))
        assertEquals(150,preferences.getInt(p.bandLow.key,-1)); assertEquals(16000,preferences.getInt(p.bandHigh.key,-1))
        assertTrue(preferences.getBoolean(p.LEGACY_DEPTH_ENABLED,false)); assertEquals(500,preferences.getInt(p.depth.key,-1))
        assertEquals(preferences.getInt(p.bandLow.key,-1),p.packet(preferences.all)[4])
        assertFalse(p.acceptsFrequency(mapOf(p.bandHigh.key to 2000),p.bandLow.key,2000))
        assertTrue(p.acceptsFrequency(mapOf(p.bandHigh.key to 2000),p.bandLow.key,1999))
    }
    @Test fun professionalPacketIsGatedButOldThreeParametersRemainExactAndBeforeEnable() {
        preferences.edit().putInt(p.legacyWidth.key,37).putInt(p.legacyCenter.key,163).putInt(p.depth.key,555)
            .putInt(p.MODE,1).putInt(p.width.key,1234).commit()
        for (capabilities in listOf(255,511)) {
            val sent = mutableListOf<Pair<Int,IntArray>>()
            assertTrue(ViperController.sync(app,capabilities) { id,words -> sent += id to words; true })
            assertEquals(37,sent.single { it.first == 65554 }.second[0])
            assertEquals(163,sent.single { it.first == 65555 }.second[0])
            assertEquals(555,sent.single { it.first == 65556 }.second[0])
            if (capabilities == 511) {
                assertEquals(1234,sent.single { it.first == p.PACKET }.second[1])
                assertTrue(sent.indexOfFirst { it.first == p.PACKET } < sent.indexOfFirst { it.first == 65553 })
            } else assertTrue(sent.none { it.first == p.PACKET })
        }
        assertEquals(1,preferences.getInt(p.MODE,-1)); assertEquals(1234,preferences.getInt(p.width.key,-1))
    }
    @Test fun auditionArmsOnlyExplicitChoicesAndNeverRevivesOnOrdinaryParameterSync() {
        val identity = Any(); val owner = Any()
        preferences.edit().putBoolean("v_field",true).commit()
        FieldController.publishRuntime(app,listOf(reading(identity)))
        val oldRevision = FieldController.auditionRevision
        assertTrue(FieldController.startAudition(app,owner,3))
        FieldController.publishRuntime(app,listOf(reading(identity)),oldRevision)
        assertEquals(3,FieldController.audition)
        fun sent() = mutableListOf<Int>().also { values ->
            assertTrue(FieldController.sync(app,511,identity) { id,data -> if (id == p.PACKET) values += data[12]; true })
        }
        assertEquals(listOf(0,3),sent())
        preferences.edit().putInt(p.width.key,1234).commit()
        assertEquals(listOf(3),sent())
        assertTrue(FieldController.startAudition(app,owner,1)); assertEquals(listOf(0,1),sent())
        FieldController.publishRuntime(app,listOf(reading(identity,audition=0)))
        assertEquals(0,FieldController.audition); assertEquals(listOf(0),sent())
        assertTrue(FieldController.startAudition(app,owner,1)); assertEquals(listOf(0,1),sent())
        FieldController.stopAudition(app,Any()); assertEquals(1,FieldController.audition)
        FieldController.stopAudition(app,owner); assertEquals(0,FieldController.audition)
        FieldController.sessionClosed(app,identity)
    }
    @Test fun immediateSessionDisableRejectsStalePollsAndDoesNotAffectAnotherLiveSessionOrHfAudition() {
        val first = Any(); val second = Any(); val owner = Any()
        preferences.edit().putBoolean("v_field",true).putBoolean("v_spectrum",true).commit()
        HighFrequencyController.publishRuntime(app,listOf(HighFrequencyController.Reading(first,true,48000,listOf(8250,120,1200,240,120,7600,22000),0)))
        assertTrue(HighFrequencyController.startAudition(app,Any()))
        try {
            FieldController.publishRuntime(app,listOf(reading(first,audition=3)))
            assertTrue(FieldController.startAudition(app,owner,3))
            val beforeOff = FieldController.runtimeRevision
            FieldController.sessionEnabled(app,first,false)
            assertEquals(0,FieldController.audition); assertTrue(HighFrequencyController.audition)
            assertFalse(FieldController.startAudition(app,owner,3))
            FieldController.publishRuntime(app,listOf(reading(first,audition=3)),FieldController.auditionRevision,beforeOff)
            assertFalse(FieldController.masterEnabled)
            FieldController.publishRuntime(app,listOf(reading(first,audition=0),reading(second,audition=3)))
            assertTrue(FieldController.startAudition(app,owner,3))
            FieldController.publishRuntime(app,listOf(reading(first,audition=0),reading(second,audition=3)))
            assertEquals(3,FieldController.audition)
            FieldController.sessionClosed(app,first); assertEquals(3,FieldController.audition)
            FieldController.sessionClosed(app,second); assertEquals(0,FieldController.audition)
        } finally {
            FieldController.sessionClosed(app,first); FieldController.sessionClosed(app,second); HighFrequencyController.stopAudition(app)
        }
    }
    @Test fun auditionCancellationOnlyTrustsPollsStartedAfterSuccessfulDelivery() {
        val identity = Any(); val owner = Any()
        preferences.edit().putBoolean("v_field",true).commit()
        FieldController.publishRuntime(app,listOf(reading(identity)))
        try {
            assertTrue(FieldController.startAudition(app,owner,3))
            val poll = FieldController.pollState()
            val revision = poll.auditionRevision
            val runtimeRevision = poll.runtimeRevision
            val beforeSend = poll.sentRevisionToken
            // A poll can finish before the asynchronous preference broadcast has sent the choice.
            FieldController.publishRuntime(app,listOf(reading(identity)),revision,runtimeRevision,beforeSend)
            assertEquals(3,FieldController.audition)
            assertTrue(FieldController.sync(app,511,identity) { _, _ -> true })
            assertTrue(FieldController.sentRevisionToken > beforeSend)
            // A poll may instead read old zero, then return only after the successful send.
            FieldController.publishRuntime(app,listOf(reading(identity)),revision,runtimeRevision,beforeSend)
            assertEquals(3,FieldController.audition)
            val afterSend = FieldController.sentRevisionToken
            assertTrue(FieldController.sync(app,511,identity) { _, _ -> true })
            assertEquals("Ordinary synchronization does not invalidate fresh polls",afterSend,FieldController.sentRevisionToken)
            FieldController.publishRuntime(app,listOf(reading(identity,audition=3)),revision,runtimeRevision,afterSend)
            assertEquals(3,FieldController.audition)
            // A new poll after delivery must still recognize native lifecycle cancellation.
            FieldController.publishRuntime(app,listOf(reading(identity)),revision,runtimeRevision,afterSend)
            assertEquals(0,FieldController.audition)
        } finally { FieldController.sessionClosed(app,identity) }
    }
    @Test fun failedAuditionResetOrSelectionDeliveryImmediatelyClearsPendingChoice() {
        preferences.edit().putBoolean("v_field",true).commit()
        for (failedValue in listOf(0,3)) {
            val identity = Any()
            FieldController.publishRuntime(app,listOf(reading(identity)))
            try {
                assertTrue(FieldController.startAudition(app,Any(),3))
                val token = FieldController.sentRevisionToken
                val sent = mutableListOf<Int>()
                assertFalse(FieldController.sync(app,511,identity) { id,data ->
                    if (id == p.PACKET) { sent += data[12]; data[12] != failedValue } else true
                })
                assertEquals(if (failedValue == 0) listOf(0) else listOf(0,3),sent)
                assertEquals(0,FieldController.audition)
                assertEquals(token,FieldController.sentRevisionToken)
            } finally { FieldController.sessionClosed(app,identity) }
        }
    }
    @Test fun metersRequireAdvancingFramesAndNewPresetsRequire1030WithoutChangingOldKeys() {
        val identity = Any()
        FieldController.publishRuntime(app,listOf(reading(identity,64)))
        assertEquals(OutputRuntimeStatus.Processing.UNKNOWN,FieldController.runtime.single().processing)
        FieldController.publishRuntime(app,listOf(reading(identity,128)))
        assertEquals(OutputRuntimeStatus.Processing.ACTIVE,FieldController.runtime.single().processing)
        FieldController.publishRuntime(app,listOf(reading(identity,128)))
        assertEquals(OutputRuntimeStatus.Processing.IDLE,FieldController.runtime.single().processing)
        FieldController.publishRuntime(app,listOf(reading(identity,256,rate=44100)))
        assertEquals(OutputRuntimeStatus.Processing.UNKNOWN,FieldController.runtime.single().processing)
        preferences.edit().putInt(p.legacyWidth.key,37).commit(); assertFalse(p.requiresNewApp(preferences.all))
        preferences.edit().putInt(p.width.key,1234).commit(); assertEquals("1030",Preset.minimumVersionCode(app))
        preferences.edit().clear().putInt(p.legacyWidth.key,37).putInt(p.legacyCenter.key,163).putInt(p.depth.key,555).commit()
        p.migrate(preferences); assertFalse(p.requiresNewApp(preferences.all))
        assertEquals(37,p.value(preferences.all,p.legacyWidth)); assertEquals(1,p.packet(preferences.all)[9])
        FieldController.sessionClosed(app,identity)
    }
}
