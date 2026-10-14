package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BassParametersTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val viper get() = BassController.preferences(app, true)
    private val dynamic get() = BassController.preferences(app, false)

    @Before fun clear() {
        listOf(ViperParameters.PREFS, Constants.PREF_BASS, BassController.STATUS_PREFS, SurroundController.PREFS)
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun defaultsMatchThePublishedWireContractAndEachDisplayedIncrementHasARealIntegerStep() {
        assertArrayEquals(intArrayOf(50, 0, 250, 200, 48, 192, 192), BassParameters.dynamicPacket(emptyMap<String, Any>()))
        assertEquals(51, BassParameters.amount.parse("0.51"))
        assertEquals(57, BassParameters.maxGain.parse("5.7"))
        assertEquals(-119, BassParameters.deepGain.parse("-11.9"))
        assertEquals(76, BassParameters.deepWidth.parse("0.76"))
        assertEquals("0.51", BassParameters.amount.label(51))
        assertEquals("5.7 dB", BassParameters.maxGain.label(57))
        listOf("NaN", "Infinity", "0.501", "-0.5", "6.01", "").forEach { assertNull(BassParameters.amount.parse(it)) }
    }

    @Test fun migratingEveryOldViperStepPreservesItsExactDriverCoefficientWithoutChangingTheOldKey() {
        for (old in 0..11) {
            viper.edit().clear().putInt(BassParameters.LEGACY_AMOUNT, old).commit()
            BassParameters.migrate(viper, true)
            assertEquals(old * 50 + 50, viper.getInt(BassParameters.AMOUNT, -1))
            assertEquals(old, viper.getInt(BassParameters.LEGACY_AMOUNT, -1))
        }
        viper.edit().putInt(BassParameters.AMOUNT, 173).commit()
        BassParameters.migrate(viper, true)
        assertEquals(173, viper.getInt(BassParameters.AMOUNT, -1))
        // Importing an older namespace replaces its XML, including removing all new keys.
        viper.edit().clear().putInt(BassParameters.LEGACY_AMOUNT, 2).commit()
        BassParameters.migrate(viper, true)
        assertEquals(150, viper.getInt(BassParameters.AMOUNT, -1))
    }

    @Test fun legacyDynamicFloatsKeepThePreviouslyHeardRoundedGainWhileNewValuesStayPrecise() {
        dynamic.edit().putFloat(BassParameters.LEGACY_MAX_GAIN, 5.49f).commit()
        BassParameters.migrate(dynamic, false)
        assertEquals(50, dynamic.getInt(BassParameters.MAX_GAIN, -1))
        assertEquals(5.49f, dynamic.getFloat(BassParameters.LEGACY_MAX_GAIN, 0f), 0f)
        dynamic.edit().clear().putFloat(BassParameters.LEGACY_MAX_GAIN, 5.51f).commit()
        BassParameters.migrate(dynamic, false)
        assertEquals(60, dynamic.getInt(BassParameters.MAX_GAIN, -1))
        dynamic.edit().putInt(BassParameters.MAX_GAIN, 57).commit()
        BassParameters.migrate(dynamic, false)
        assertEquals(57, BassParameters.dynamicPacket(dynamic.all)[0])
    }

    @Test fun importedInvalidBoundsAndFractionalIntegerKeysAreNormalizedToTheSameVisibleAndSentValues() {
        dynamic.edit().putInt(BassParameters.trackLow.key, 200).putInt(BassParameters.trackHigh.key, 100)
            .putFloat(BassParameters.dynamicQ.key, 53.9f).putFloat(BassParameters.MAX_GAIN, Float.NaN).commit()
        BassParameters.migrate(dynamic, false)
        assertEquals(0, dynamic.getInt(BassParameters.trackLow.key, -1))
        assertEquals(250, dynamic.getInt(BassParameters.trackHigh.key, -1))
        assertEquals(200, dynamic.getInt(BassParameters.dynamicQ.key, -1))
        assertEquals(50, dynamic.getInt(BassParameters.MAX_GAIN, -1))
        val packet = BassParameters.dynamicPacket(dynamic.all)
        BassParameters.dynamic.forEachIndexed { index, control -> assertEquals(packet[index], BassParameters.value(dynamic.all, control)) }
        assertFalse(BassParameters.acceptsDynamic(mapOf(BassParameters.trackHigh.key to 60), BassParameters.trackLow, 60))
        assertTrue(BassParameters.acceptsDynamic(mapOf(BassParameters.trackHigh.key to 60), BassParameters.trackLow, 59))
    }

    @Test fun newDriverReceivesFineValuesBeforeEnableAndOldDriverNeverReceivesUnsupportedParameters() {
        viper.edit().putInt(BassParameters.AMOUNT, 173).putBoolean("v_bass", true).commit()
        val modern = mutableListOf<Pair<Int, IntArray>>()
        assertTrue(ViperController.sync(app, 63) { id, words -> modern += id to words; true })
        assertEquals(1, modern.count { it.first == 65577 })
        assertArrayEquals(intArrayOf(173), modern.single { it.first == 65577 }.second)
        assertTrue(modern.indexOfFirst { it.first == 65590 } < modern.indexOfFirst { it.first == 65574 })
        val old = mutableListOf<Int>()
        assertTrue(ViperController.sync(app, 31) { id, _ -> old += id; true })
        assertTrue(old.none { it in 65590..65596 })
        assertTrue(old.contains(65577))
        assertTrue(BassController.syncDynamic(app, 31) { _, _ -> fail("Old driver must not receive SET 1300"); false })
        dynamic.edit().putInt(BassParameters.MAX_GAIN, 57).commit()
        assertTrue(BassController.syncDynamic(app, 63) { id, words ->
            assertEquals(1300, id); assertEquals(57, words[0]); assertEquals(7, words.size); true
        })
    }

    @Test fun capabilitiesAreCurrentSessionOnlyAndNewPresetKeysProtectAgainstOldApkImporters() {
        BassController.statusPreferences(app).edit().putInt("capabilities", 63).putString("session", "stale").commit()
        assertFalse(BassController.supported(app))
        BassController.publishCapabilities(app, 63)
        assertTrue(BassController.supported(app))
        BassController.publishCapabilities(app, 31)
        assertFalse(BassController.supported(app))
        assertEquals("1010", Preset.minimumVersionCode(app))
        viper.edit().putInt(BassParameters.AMOUNT, 51).commit()
        assertEquals("1027", Preset.minimumVersionCode(app))
        viper.edit().clear().commit()
        dynamic.edit().putInt(BassParameters.rise.key, 193).commit()
        assertEquals("1027", Preset.minimumVersionCode(app))
    }
}
