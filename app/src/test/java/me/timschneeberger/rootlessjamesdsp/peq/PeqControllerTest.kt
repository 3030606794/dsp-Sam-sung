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
        assertTrue(PeqController.isParametric(app))
        val output = packet()
        assertEquals(1, output[0])
        assertEquals(0, output[1])
        assertEquals(10, output[2])
        PeqController.useParametric(app)
        assertFalse(prefs.getBoolean(enableKey, false))
        assertEquals(0, packet()[1])
    }

    @Test fun oldCurveSurvivesUpgradeEditingAndBothModeSwitches() {
        val curve = "GraphicEQ: 100 -3; 1000 6; 12000 -2;"
        prefs.edit().putString(nodesKey, curve).putBoolean(enableKey, true).commit()
        assertFalse(PeqController.isParametric(app))
        val state = PeqModel.defaultState(170).let {
            it.copy(bands = it.bands.mapIndexed { i, band -> band.copy(gain = if (i == 169) 4.0 else 0.0) })
        }
        assertTrue(PeqRepository(app).save(state))
        assertEquals(0, packet()[1])
        assertFalse(PeqController.isParametric(app))
        assertEquals(curve, prefs.getString(nodesKey, null))

        PeqController.useParametric(app)
        val active = packet()
        assertEquals(1, active[1])
        assertEquals(170, active[2])
        assertEquals(4000, active[3 + 169 * 5 + 3])
        PeqController.useLegacy(app)
        assertEquals(0, packet()[1])
        assertTrue(prefs.getBoolean(enableKey, false))
        assertEquals(curve, prefs.getString(nodesKey, null))
        assertEquals(state, PeqRepository(app).load())
    }

    @Test fun displaySettingsDoNotChangeTheDriverPacketOrEffectSwitch() {
        val state = PeqModel.defaultState().let { it.copy(bands = it.bands.map { b -> b.copy(gain = 17.3) }) }
        PeqRepository(app).save(state)
        val before = packet()
        PeqRepository(app).save(state.copy(gainRange = 6.0, gainStep = 0.25))
        assertArrayEquals(before, packet())
        assertFalse(prefs.getBoolean(enableKey, false))
    }

    @Test fun importingALegacyCurveDoesNotSilentlyTakeOverTheSelectedParametricMode() {
        assertTrue(PeqController.isParametric(app))
        prefs.edit().putString(nodesKey, "GraphicEQ: 100 4; 1000 -6;").commit()
        assertTrue(PeqController.hasLegacyCurve(app))
        assertTrue(PeqController.isParametric(app))
        PeqController.useLegacy(app)
        assertFalse(PeqController.isParametric(app))
        assertFalse(prefs.getBoolean(enableKey, false))
    }

    @Test fun invalidSavedDataIsNotOverwrittenDuringSynchronizationAndFailuresPropagate() {
        prefs.edit().putString(PeqRepository.KEY_STATE, "invalid-json").commit()
        assertFalse(PeqController.sync(app) { _, _ -> false })
        assertEquals("invalid-json", prefs.getString(PeqRepository.KEY_STATE, null))
        assertFalse(prefs.getBoolean(enableKey, false))
    }
}
