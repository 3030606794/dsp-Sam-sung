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
class HighFrequencyAdvancedParametersTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = HighFrequencyController.preferences(app)
    private val p = HighFrequencyAdvancedParameters
    @Before fun prepare() {
        preferences.edit().clear().commit()
        HighFrequencyController.stopAudition(app)
        HighFrequencyController.publishRuntime(app, emptyList())
        HighFrequencyController.publishCapabilities(app, 255)
    }
    @Test fun fourPacketDefaultsPreserveThePreviousProcessingAndRealUnits() {
        val empty = emptyMap<String, Any>()
        assertArrayEquals(intArrayOf(0,3000,12000,707),p.naturalPacket(empty))
        assertArrayEquals(intArrayOf(0,0,100,1),p.nonlinearPacket(empty))
        assertArrayEquals(intArrayOf(0,6000,707,-180,40,60,50,120),p.dynamicPacket(empty))
        assertArrayEquals(intArrayOf(0,4500,10000,707,-240,60,60,10,80),p.deessPacket(empty))
        assertEquals(51,p.dynamicAttack.parse("5.1"))
        assertEquals(11,p.deessAttack.parse("1.1"))
        assertEquals(-179,p.dynamicThreshold.parse("-17.9"))
        assertEquals(41,p.dynamicRatio.parse("4.1"))
        assertEquals(708,p.naturalQ.parse("0.708"))
        assertNull(p.drive.parse("0.01")); assertNull(p.evenMix.parse("0.5"))
        assertNull(p.dynamicAttack.parse("0.0")); assertNull(p.deessAttack.parse("10.1"))
    }
    @Test fun invalidImportedBoundsAreCanonicalInStorageUiAndPackets() {
        preferences.edit().putInt(p.naturalLow.key,12000).putInt(p.naturalHigh.key,2000)
            .putInt(p.deessLow.key,12000).putInt(p.deessHigh.key,3000)
            .putFloat(p.drive.key,Float.NaN).putString(p.dynamicQ.key,"0.707")
            .putFloat(p.deessAttack.key,10.1f).putInt(p.OVERSAMPLING,3)
            .putString(p.DYNAMIC_ENABLED,"true").commit()
        p.migrate(preferences)
        for ((low,high) in p.frequencyPairs) {
            assertEquals(low.default,preferences.getInt(low.key,-1))
            assertEquals(high.default,preferences.getInt(high.key,-1))
        }
        assertArrayEquals(intArrayOf(0,3000,12000,707),p.naturalPacket(preferences.all))
        assertArrayEquals(intArrayOf(0,0,100,1),p.nonlinearPacket(preferences.all))
        assertEquals(707,preferences.getInt(p.dynamicQ.key,-1))
        assertEquals(10,preferences.getInt(p.deessAttack.key,-1))
        assertFalse(preferences.getBoolean(p.DYNAMIC_ENABLED,true))
        preferences.edit().clear().putInt(p.deessHigh.key,3000).commit()
        p.migrate(preferences)
        assertEquals(4500,preferences.getInt(p.deessLow.key,-1))
        assertEquals(10000,preferences.getInt(p.deessHigh.key,-1))
    }
    @Test fun linkedRangeValidationRejectsEqualOrCrossedBoundsAndAllowsOneHzSpacing() {
        assertFalse(p.acceptsFrequency(emptyMap<String,Any>(),p.naturalLow.key,12000))
        assertTrue(p.acceptsFrequency(emptyMap<String,Any>(),p.naturalLow.key,11999))
        assertFalse(p.acceptsFrequency(emptyMap<String,Any>(),p.deessHigh.key,4500))
        assertTrue(p.acceptsFrequency(emptyMap<String,Any>(),p.deessHigh.key,4501))
        assertFalse(p.acceptsFrequency(mapOf(p.deessHigh.key to 5000),p.deessLow.key,5001))
    }
    @Test fun newPacketsAreCapabilityGatedAndFirstRoundPacketsStillPrecedeTheParentSwitches() {
        preferences.edit().putInt(p.drive.key,31).putBoolean(p.DYNAMIC_ENABLED,true).commit()
        fun sent(capabilities: Int) = mutableListOf<Pair<Int,IntArray>>().also { output ->
            assertTrue(ViperController.sync(app,capabilities) { id,data -> output += id to data; true })
        }
        val modern = sent(255)
        assertEquals(31,modern.single { it.first == 65605 }.second[0])
        assertEquals(1,modern.single { it.first == 65606 }.second[0])
        for (packet in 65604..65607) assertTrue(modern.indexOfFirst { it.first == packet } < modern.indexOfFirst { it.first == 65578 })
        assertTrue(modern.any { it.first == 65600 }); assertTrue(modern.any { it.first == 65601 })
        for (caps in listOf(63,127)) assertTrue(sent(caps).none { it.first in 65604..65607 })
        assertTrue(sent(127).any { it.first == 65600 })
        assertEquals(31,preferences.getInt(p.drive.key,-1))
        val attempted = mutableListOf<Int>()
        assertFalse(HighFrequencyController.sync(app,255) { id,_ -> attempted += id; id != 65604 })
        assertTrue(attempted.containsAll(listOf(65604,65605,65606,65607)))
    }
    @Test fun secondGenerationPresetVersionAndReimportedOldValuesDoNotKeepNewProcessing() {
        preferences.edit().putInt(HighFrequencyParameters.CLARITY_AMOUNT,173).commit()
        assertEquals("1028",Preset.minimumVersionCode(app))
        preferences.edit().putInt(p.OVERSAMPLING,1).commit()
        assertEquals("1029",Preset.minimumVersionCode(app))
        repeat(2) {
            preferences.edit().putBoolean(p.DYNAMIC_ENABLED,true).putInt(p.drive.key,180).putInt(p.OVERSAMPLING,4).commit()
            preferences.edit().clear().putInt("v_clarity_amount",4).putInt("v_spectrum_amount",56).commit()
            HighFrequencyParameters.migrate(preferences)
            assertArrayEquals(intArrayOf(0,0,100,1),p.nonlinearPacket(preferences.all))
            assertEquals(0,p.dynamicPacket(preferences.all)[0])
            assertFalse(p.requiresNewApp(preferences.all))
            assertEquals("1028",Preset.minimumVersionCode(app))
        }
    }
    @Test fun reductionIsActiveOnlyWhenThisSessionsProcessedFrameCounterAdvances() {
        val identity = Any()
        fun reading(frames: Int, rate: Int = 48000, enabled: Boolean = true) =
            HighFrequencyController.Reading(identity,enabled,rate,listOf(8250,120,1200,240,120,7600,22000),0,
                listOf(3000,12000,6000,4500,10000,4,32,321,123),frames)
        fun publish(frames: Int, rate: Int = 48000, enabled: Boolean = true): OutputRuntimeStatus.Processing {
            HighFrequencyController.publishRuntime(app,listOf(reading(frames,rate,enabled)))
            return HighFrequencyController.runtime.single().processing
        }
        assertEquals(OutputRuntimeStatus.Processing.UNKNOWN,publish(64))
        assertEquals(OutputRuntimeStatus.Processing.ACTIVE,publish(128))
        assertEquals(OutputRuntimeStatus.Processing.IDLE,publish(128))
        assertEquals(OutputRuntimeStatus.Processing.UNKNOWN,publish(256,44100))
        assertEquals(OutputRuntimeStatus.Processing.IDLE,publish(512,44100,false))
        HighFrequencyController.publishRuntime(app,emptyList())
        assertEquals(OutputRuntimeStatus.Processing.UNKNOWN,publish(1024))
    }
}
