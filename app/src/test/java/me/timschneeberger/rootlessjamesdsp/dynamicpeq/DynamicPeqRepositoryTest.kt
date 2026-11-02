package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.app.Application
import android.content.Context
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
class DynamicPeqRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(Constants.PREF_DYNAMIC_PEQ, Context.MODE_PRIVATE)
    private val staticPrefs get() = context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = DynamicPeqRepository(context)

    @Before fun clear() {
        prefs.edit().clear().commit()
        staticPrefs.edit().clear().putBoolean("geq_enable", true).putString("fusion_peq_state", "static-test").commit()
    }

    @Test fun saveAndLoadAreIndependentFromExistingStaticBank() {
        val before = staticPrefs.all.toMap()
        val state = DynamicPeqState(true, List(16) { DynamicPeqBand("band-$it", type = it % 3,
            frequency = 222.125 + it, q = 2.345, threshold = -32.125, ratio = 4.125,
            gain = 3.125, range = 8.125, attack = 15.125, release = 999.125, knee = 9.125) })
        assertTrue(repository.save(state))
        assertEquals(state, repository.load())
        assertEquals(state, DynamicPeqRepository.decode(DynamicPeqRepository.encode(state)))
        assertEquals(before, staticPrefs.all)
        assertTrue(prefs.getBoolean(DynamicPeqRepository.KEY_ENABLED, false))
    }

    @Test fun defaultsAndMalformedDataAreReadOnlyAndBypassed() {
        assertFalse(repository.load().enabled)
        assertTrue(prefs.all.isEmpty())
        listOf("not-json", "{}", "null", "{\"schema\":2}").forEach { text ->
            prefs.edit().clear().putString(DynamicPeqRepository.KEY_STATE, text)
                .putBoolean(DynamicPeqRepository.KEY_ENABLED, true).commit()
            val before = prefs.all.toMap()
            val result = repository.loadResult()
            assertTrue(result.corrupt)
            assertFalse(result.state.enabled)
            assertEquals(before, prefs.all)
        }
    }

    @Test fun separateEnabledKeyIsObservedWithoutRewritingBank() {
        val state = DynamicPeqModel.defaultState()
        repository.save(state)
        prefs.edit().putBoolean(DynamicPeqRepository.KEY_ENABLED, true).commit()
        assertEquals(state.copy(enabled = true), repository.load())
        assertEquals(state, DynamicPeqRepository.decode(prefs.getString(DynamicPeqRepository.KEY_STATE, null)!!))
    }

    @Test fun invalidOrWrongEffectImportsCannotOverwriteSavedBank() {
        val working = DynamicPeqModel.defaultState().copy(enabled = true)
        repository.save(working)
        val valid = repository.exportJson(working)
        val before = prefs.all.toMap()
        listOf(valid.replace("\"ratio\":2.0", "\"ratio\":\"2\""),
            valid.replace("\"threshold\":-24.0", "\"threshold\":1.0"),
            valid.replace("\"enabled\":true", "\"enabled\":1"),
            valid.replace("\"schema\":1", "\"schema\":999"),
            me.timschneeberger.rootlessjamesdsp.peq.PeqRepository.encode(
                me.timschneeberger.rootlessjamesdsp.peq.PeqModel.defaultState())).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { repository.importJson(bad) }
        }
        assertEquals(before, prefs.all)
        assertEquals(working, repository.load())
    }

    @Test fun unsupportedDriverDoesNotReceiveNewPacketsEvenWhenEnabled() {
        repository.save(DynamicPeqModel.defaultState().copy(enabled = true))
        var calls = 0
        assertTrue(DynamicPeqController.sync(context, false) { _, _ -> calls++; false })
        assertEquals(0, calls)
        assertTrue(DynamicPeqController.sync(context, true) { id, packet ->
            calls++; assertEquals(196720, id); assertEquals(1, packet[1]); true
        })
        assertEquals(1, calls)
        prefs.edit().putString(DynamicPeqRepository.KEY_STATE, "broken").commit()
        assertTrue(DynamicPeqController.sync(context, true) { _, packet -> assertEquals(0, packet[1]); true })
    }
}
