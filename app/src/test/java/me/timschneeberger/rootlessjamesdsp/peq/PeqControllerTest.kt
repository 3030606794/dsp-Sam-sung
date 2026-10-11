package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
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
class PeqControllerTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val enableKey get() = app.getString(R.string.key_geq_enable)
    private val nodesKey get() = app.getString(R.string.key_geq_nodes)
    @Before fun clear() { prefs.edit().clear().commit() }

    private fun packet(): IntArray {
        var result = intArrayOf()
        assertTrue(PeqController.sync(app) { parameter, values ->
            assertEquals(1250, parameter)
            result = values
            true
        })
        return result
    }

    @Test fun freshEditorIsNeutralAndNeverEnablesItself() {
        val output = packet()
        assertEquals(1, output[0])
        assertEquals(0, output[1])
        assertEquals(10, output[2])
        assertFalse(prefs.getBoolean(enableKey, false))
    }

    @Test fun retiredCurveModeCannotBypassOrReplaceParametricBank() {
        val curve = "GraphicEQ: 100 -3; 1000 6; 12000 -2;"
        prefs.edit().putString(nodesKey, curve).putString("fusion_peq_mode", "legacy")
            .putBoolean(enableKey, true).commit()
        val state = PeqModel.defaultState(170).let {
            it.copy(bands = it.bands.mapIndexed { i, band -> band.copy(
                gain = if (i == 169) 100.0 else 0.0, q = 100.0) })
        }
        assertTrue(PeqRepository(app).save(state))
        val active = packet()
        assertEquals(1, active[1])
        assertEquals(170, active[2])
        assertEquals(100000, active[3 + 169 * 5 + 3])
        assertEquals(100000, active[3 + 169 * 5 + 4])
        assertEquals(curve, prefs.getString(nodesKey, null))
        assertEquals(state, PeqRepository(app).load())
    }

    @Test fun displaySettingsDoNotChangeDriverPacketOrEffectSwitch() {
        val state = PeqModel.defaultState().let { it.copy(bands = it.bands.map { b -> b.copy(gain = 17.3) }) }
        PeqRepository(app).save(state)
        val before = packet()
        PeqRepository(app).save(state.copy(gainRange = 100.0, gainStep = 1.5,
            showQSlider = true, showFrequencySlider = true))
        assertArrayEquals(before, packet())
        assertFalse(prefs.getBoolean(enableKey, false))
    }

    @Test fun onlyTheExistingEffectSwitchActivatesParametricBank() {
        PeqRepository(app).save(PeqModel.defaultState())
        assertEquals(0, packet()[1])
        prefs.edit().putBoolean(enableKey, true).commit()
        assertEquals(1, packet()[1])
        prefs.edit().putString(nodesKey, "GraphicEQ: 100 4; 1000 -6;")
            .putBoolean(enableKey, false).commit()
        assertEquals(0, packet()[1])
    }

    @Test fun invalidSavedDataIsNotOverwrittenDuringSynchronizationAndFailuresPropagate() {
        prefs.edit().putString(PeqRepository.KEY_STATE, "invalid-json").commit()
        assertFalse(PeqController.sync(app) { _, _ -> false })
        assertEquals("invalid-json", prefs.getString(PeqRepository.KEY_STATE, null))
        assertFalse(prefs.getBoolean(enableKey, false))
    }
}
