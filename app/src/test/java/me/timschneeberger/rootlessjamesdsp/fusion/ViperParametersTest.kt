package me.timschneeberger.rootlessjamesdsp.fusion

import org.junit.Assert.*
import org.junit.Test

class ViperParametersTest {
    private val controls = ViperParameters.groups.flatMap { it.controls }
    private fun control(key: String) = controls.single { it.key == key }

    @Test fun oldViperReverbValuesRemainRepresentableExactly() {
        // Original 8.0 app: room/width slider 0..10 -> driver percent x10.
        for (oldValue in 0..10) {
            assertArrayEquals(intArrayOf(oldValue * 10), control("v_reverb_size").packet(oldValue * 10))
            assertArrayEquals(intArrayOf(oldValue * 10), control("v_reverb_width").packet(oldValue * 10))
        }
        assertArrayEquals(intArrayOf(50), control("v_reverb_dry").packet(50))
    }

    @Test fun removedProcessorsAreAbsentFromAllParameterDefinitions() {
        val removed = setOf(65551, 65552, 65586, 65587, 65588, 65603)
        assertTrue(controls.none { it.parameter in removed })
        assertTrue(ViperParameters.groups.none { it.enableParameter in removed })
        assertTrue(ViperParameters.groups.none { it.key in setOf("v_output", "v_eq", "v_speaker") })
    }

    @Test fun scalarMappingsMatchOriginalDriverUnits() {
        assertArrayEquals(intArrayOf(500), control("v_diff_delay").packet(5))
        assertArrayEquals(intArrayOf(50), control("v_bass_amount").packet(0))
        assertArrayEquals(intArrayOf(600), control("v_bass_amount").packet(11))
        assertArrayEquals(intArrayOf(200), control("v_clarity_amount").packet(4))
        assertArrayEquals(intArrayOf(1100), control("v_dynamic_strength").packet(50))
    }

    @Test fun malformedValuesClampAndDefaultsStayInRange() {
        controls.forEach {
            assertTrue(it.default in it.min..it.max)
            assertEquals(it.encode(it.min), it.encode(Int.MIN_VALUE))
            assertEquals(it.encode(it.max), it.encode(Int.MAX_VALUE))
        }
        assertEquals(controls.size, controls.map { it.key }.toSet().size)
        ViperParameters.dynamicPresets.values.forEach {
            assertEquals(6, it.size)
            assertTrue(it[0] < it[1])
            assertTrue(it[2] < it[3])
        }
    }
}
