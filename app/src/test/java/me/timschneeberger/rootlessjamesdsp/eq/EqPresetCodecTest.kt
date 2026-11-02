package me.timschneeberger.rootlessjamesdsp.eq

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class EqPresetCodecTest {
    private val preset = EqPreset("internal-only", "个人预设 🎵", List(15) { (it - 7) * 0.173 }, "3", "1")
    private fun change(key: String, value: kotlinx.serialization.json.JsonElement): String =
        JsonObject(Json.parseToJsonElement(EqPresetCodec.encode(preset)).jsonObject.toMutableMap().apply { put(key, value) }).toString()
    private fun rejected(text: String) {
        try { EqPresetCodec.decode(text); fail("Invalid preset must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun roundTripPreservesAllGainsAndOptionsWithoutExportingInternalIdentity() {
        val json = EqPresetCodec.encode(preset.copy(builtIn = true))
        val decoded = EqPresetCodec.decode(json)
        assertEquals(preset.name, decoded.name)
        assertEquals(preset.gains, decoded.gains)
        assertEquals(preset.filterType, decoded.filterType)
        assertEquals(preset.interpolation, decoded.interpolation)
        assertEquals("", decoded.id)
        assertFalse(decoded.builtIn)
        assertFalse(json.contains("internal-only"))
        assertFalse(json.contains("builtIn"))
        assertEquals(EqPresetCodec.KIND, Json.parseToJsonElement(json).jsonObject["kind"]?.let { (it as JsonPrimitive).content })
    }

    @Test fun gainOnlyPresetHasExplicitNullOptionsAndKeepsRangeEndpoints() {
        val gains = List(15) { if (it % 2 == 0) -12.0 else 12.0 }
        val original = EqPreset("builtin-1", "端点", gains, builtIn = true)
        val decoded = EqPresetCodec.decode(EqPresetCodec.encode(original))
        assertEquals(gains, decoded.gains)
        assertNull(decoded.filterType)
        assertNull(decoded.interpolation)
    }

    @Test fun wrongSchemasUnknownFieldsOptionsAndNonNumericGainsAreRejected() {
        listOf(
            change("kind", JsonPrimitive("other-equalizer")),
            change("version", JsonPrimitive(2)),
            change("version", JsonPrimitive("1")),
            change("unknown", JsonPrimitive(true)),
            change("filterType", JsonPrimitive("6")),
            change("filterType", JsonPrimitive(1)),
            change("interpolation", JsonPrimitive("2")),
            change("gains", JsonArray(List(14) { JsonPrimitive(0) })),
            change("gains", JsonArray(List(16) { JsonPrimitive(0) })),
            change("gains", JsonArray(List(15) { JsonPrimitive("0") })),
            change("gains", JsonArray(List(15) { JsonNull })),
            change("gains", JsonArray(List(15) { JsonPrimitive(12.01) })),
            change("gains", JsonArray(List(15) { JsonPrimitive(-12.01) })),
            change("gains", JsonArray(List(15) { JsonPrimitive(Double.NaN) })),
            change("gains", JsonArray(List(15) { JsonPrimitive(Double.POSITIVE_INFINITY) })),
            "{}", "[]", "not json", "{\"kind\":",
        ).forEach(::rejected)
    }

    @Test fun invalidNamesOversizedFilesAndExcessiveNestingAreRejected() {
        listOf("", "   ", "x".repeat(41), "bad\nname").forEach { rejected(change("name", JsonPrimitive(it))) }
        rejected(" ".repeat(EqPresetCodec.MAX_JSON_BYTES) + EqPresetCodec.encode(preset))
        rejected("[".repeat(500) + "0" + "]".repeat(500))
        assertEquals("x".repeat(40), EqPresetCodec.decode(change("name", JsonPrimitive("x".repeat(40)))).name)
    }
}
