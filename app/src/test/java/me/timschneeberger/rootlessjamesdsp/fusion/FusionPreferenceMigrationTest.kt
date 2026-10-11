package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FusionPreferenceMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun upgradeMovesBalanceWithoutChangingKeptEffects() {
        val old = context.getSharedPreferences("dsp_fusion_viper", 0)
        old.edit().putInt("v_pan", -35).putInt("v_volume", 0).putInt("v_limit", 20)
            .putBoolean("v_eq", true).putInt("v_eq_3", 8).putBoolean("v_speaker", true)
            .putBoolean("fusion_viper_enabled", false).putBoolean("fusion_james_enabled", false)
            .putBoolean("v_reverb", true).putInt("v_reverb_wet", 38).commit()
        FusionPreferenceMigration.migrate(context)
        assertEquals(-35, context.getSharedPreferences(Constants.PREF_OUTPUT, 0).getInt("balance", 0))
        assertEquals(setOf("v_reverb", "v_reverb_wet"), old.all.keys)
        assertTrue(old.getBoolean("v_reverb", false))
        assertEquals(38, old.getInt("v_reverb_wet", 0))
    }

    @Test fun importingOldKeysDoesNotOverwriteNewOutputBalance() {
        val output = context.getSharedPreferences(Constants.PREF_OUTPUT, 0)
        val old = context.getSharedPreferences("dsp_fusion_viper", 0)
        output.edit().putInt("balance", 42).commit()
        old.edit().putInt("v_pan", -80).commit()
        FusionPreferenceMigration.migrate(context)
        FusionPreferenceMigration.migrate(context)
        assertEquals(42, output.getInt("balance", 0))
        assertFalse(old.contains("v_pan"))
        // A later legacy profile has its own output namespace and is migrated too.
        output.edit().clear().commit()
        old.edit().putInt("v_pan", 200).commit()
        FusionPreferenceMigration.migrate(context)
        assertEquals(100, output.getInt("balance", 0))
    }
}
