package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

class ToneAdvancedPresetCodecTest {
    private val foundation = ToneController.KEYS.mapIndexed { i, key -> key to (i * 7 - 31) }.toMap() +
        (ToneController.RANGE to "100")
    private val settings get() = ToneAdvancedParameters.capture(ToneAdvancedParameters.defaults().values() + foundation)
    private val preset get() = ToneAdvancedPreset("12345678-1234-4234-8234-123456789abc", "温暖自然", settings)
    private val valid get() = ToneAdvancedPresetCodec.encode(listOf(preset))
    private fun changed(vararg updates: Pair<String, JsonElement>): String {
        val root = Json.parseToJsonElement(valid).jsonObject
        val entry = root["presets"]!!.jsonArray.single().jsonObject
        val values = entry["settings"]!!.jsonObject
        return JsonObject(root + ("presets" to JsonArray(listOf(JsonObject(entry +
            ("settings" to JsonObject(values + updates.toMap()))))))).toString()
    }
    private fun rejects(document: String) = assertThrows(IllegalArgumentException::class.java) {
        ToneAdvancedPresetCodec.decode(document)
    }

    @Test fun roundTripKeepsTenOriginalIntegersRangeStringAndCompleteProfessionalState() {
        assertEquals(preset, ToneAdvancedPresetCodec.decode(valid).single())
        val values = Json.parseToJsonElement(valid).jsonObject["presets"]!!.jsonArray.single().jsonObject["settings"]!!.jsonObject
        assertEquals(ToneAdvancedParameters.defaults().values().keys, values.keys)
        ToneController.KEYS.forEach { key ->
            assertFalse(values[key]!!.jsonPrimitive.isString)
            assertEquals(foundation.getValue(key), values[key]!!.jsonPrimitive.int)
        }
        assertTrue(values[ToneController.RANGE]!!.jsonPrimitive.isString)
        assertEquals("100", values[ToneController.RANGE]!!.jsonPrimitive.content)
        assertFalse(values.containsKey(ToneController.ENABLED))
        listOf("audition", "snapshot", "match", "measurement", "local_window", "window_scale").forEach { word ->
            assertFalse(values.keys.any { it.contains(word) })
        }
        assertEquals(valid, ToneAdvancedPresetCodec.encode(ToneAdvancedPresetCodec.decode(valid)))
    }

    @Test fun allThreeOriginalRangesAndEndpointsRoundTripWithoutNewFeaturesEnabled() {
        val defaults = ToneAdvancedParameters.defaults()
        assertTrue(defaults.valid())
        assertTrue(defaults.values().values.filterIsInstance<Boolean>().all { !it })
        ToneController.RANGES.forEach { range ->
            listOf(-range, 0, range).forEach { base ->
                val raw = defaults.values() + ToneController.KEYS.associateWith { base } + (ToneController.RANGE to range.toString())
                val state = ToneAdvancedParameters.capture(raw)
                assertEquals(raw, state.values())
                val document = ToneAdvancedPresetCodec.encode(listOf(preset.copy(settings = state)))
                assertEquals(state, ToneAdvancedPresetCodec.decode(document).single().settings)
            }
        }
    }

    @Test fun systemDirectionsPreserveEveryOriginalBasisAndAreOptIn() {
        val entries = ToneAdvancedSystemPresets.entries(settings)
        assertEquals(6, entries.size)
        assertEquals(ToneAdvancedSystemPresets.foundation(settings), entries.first().second)
        assertTrue(entries.first().second.values().values.filterIsInstance<Boolean>().all { !it })
        entries.forEach { (_, state) ->
            assertTrue(state.valid())
            foundation.forEach { (key, value) -> assertEquals(value, state.values()[key]) }
            assertEquals(false, state.values()["tone_pro_meters"])
            assertEquals(false, state.values()["tone_pro_response"])
        }
        assertTrue(entries.drop(1).all { it.second.values()["tone_pro_professional"] == true })
        val presets = entries.map { (name, state) -> ToneAdvancedPreset(UUID.randomUUID().toString(), name, state) }
        assertEquals(presets, ToneAdvancedPresetCodec.decode(ToneAdvancedPresetCodec.encode(presets)))
    }

    @Test fun factoryPacketsMatchIndependentNativeGoldenMatrix() {
        val expected = listOf(
            intArrayOf(1,0,0,100,0,0,0,0,0,0,0,0,0,0,0,1000,0,0,0,0,0,0,0,0,0,0,0,180,3500,60,110,350,1800,3500,5500,8500,12000,80,75,80,100,110,110,1000,1000,1000,1000,1000,1000,0,1000,1000,1000,150,50,70,20,45,1000,1000,30,30,0,-800,0,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(1,1,0,100,0,0,0,0,0,0,0,0,0,0,1,1000,200,100,0,150,-30,-50,-100,-100,-20,-80,1,160,3200,60,110,320,1800,3500,5500,8500,12000,80,75,80,100,110,110,1000,1000,1000,1000,1100,900,0,1000,1000,1000,150,50,70,20,45,1000,1000,30,30,0,-800,1,850,1,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(1,1,0,100,0,0,0,0,0,0,0,0,0,0,1,1000,0,-40,-20,-100,250,80,-20,-30,0,30,1,180,3500,60,110,350,2000,3300,5500,8500,12000,80,75,90,100,110,110,1000,1000,1000,1000,1000,1000,0,1000,1000,1000,150,50,70,20,45,1000,1000,30,30,0,-800,1,850,1,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(1,1,0,100,0,0,0,0,0,0,0,0,0,0,1,1000,50,180,250,20,0,0,0,0,120,0,0,180,3500,60,110,350,1800,3500,5500,8500,12000,80,75,80,100,110,110,1000,1000,1000,1000,1000,1000,1,700,1400,1000,130,30,90,20,55,1250,1000,40,30,1,-750,1,800,1,0,1,-10,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(1,1,0,100,0,0,0,0,0,0,0,0,0,0,1,1000,-80,-40,0,-50,50,60,-30,-50,50,250,1,180,4000,60,110,350,1800,3500,5500,8500,13000,80,75,80,100,110,110,1000,800,1000,800,1000,1000,0,1000,1000,1000,150,50,70,20,45,1000,1000,30,30,0,-800,1,800,1,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(1,1,0,100,0,0,0,0,0,0,0,0,0,0,1,1000,150,40,-60,70,-30,-60,-250,-200,-150,-80,0,180,3500,60,110,350,1800,3500,5500,8500,12000,80,75,80,100,110,110,1000,1000,1000,1000,1000,1000,1,800,700,800,150,50,70,40,65,1000,1000,20,20,1,-800,1,850,1,0,0,0,0,0,0,0,0,0,0,0,0,0),
        )
        val actual = ToneAdvancedSystemPresets.entries(ToneAdvancedParameters.defaults())
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (packet, direction) -> assertArrayEquals(packet, direction.second.packet()) }
    }

    @Test fun wrongModuleVersionUnitsFieldsAndShapeRejectWholeDocument() {
        listOf(CompanderPresetCodec.FORMAT, DynamicPresetCodec.FORMAT, TimbrePresetCodec.FORMAT,
            AnalogPresetCodec.FORMAT, TubePresetCodec.FORMAT, ReverbPresetCodec.FORMAT,
            RoomReverbPresetCodec.FORMAT, CrossfeedPresetCodec.FORMAT).forEach { format ->
            rejects(valid.replace(ToneAdvancedPresetCodec.FORMAT, format))
        }
        listOf("", "[]", "{}", "null", "broken", valid.dropLast(1), valid + "{}",
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("tenth-point", "point"),
            valid.replace("\"presets\":", "\"unknown\":0,\"presets\":"),
            valid.replace("\"settings\":", "\"enabled\":true,\"settings\":"),
            changed(ToneController.ENABLED to JsonPrimitive(true)),
            changed("tone_pro_unknown" to JsonPrimitive(0)),
            valid.replace("\"${ToneController.KEYS[0]}\":-31,", "")
        ).forEach(::rejects)
        assertThrows(IllegalArgumentException::class.java) { TimbrePresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { CompanderPresetCodec.decode(valid) }
    }

    @Test fun duplicateFieldsIncludingEscapedNamesAreRejectedInsteadOfOverwritten() {
        listOf(valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            valid.replace("\"name\":\"温暖自然\"", "\"name\":\"温暖自然\",\"name\":\"其他\""),
            valid.replace("\"settings\":", "\"settings\":{},\"settings\":"),
            valid.replace("\"${ToneController.KEYS[0]}\":-31", "\"${ToneController.KEYS[0]}\":0,\"${ToneController.KEYS[0]}\":-31")
        ).forEach { assertTrue(rejects(it).message!!.contains("重复字段")) }
    }

    @Test fun integerAndBooleanFieldsRejectCoercionFractionOverflowAndNullEvenWhenDisabled() {
        settings.values().filterValues { it is Int }.keys.forEach { key ->
            listOf(JsonPrimitive(Int.MAX_VALUE), JsonPrimitive(Int.MIN_VALUE), JsonPrimitive(Long.MAX_VALUE),
                JsonPrimitive(true), JsonPrimitive("0"), JsonPrimitive(1.5), JsonNull).forEach { rejects(changed(key to it)) }
        }
        settings.values().filterValues { it is Boolean }.keys.forEach { key ->
            listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive("false"), JsonNull).forEach { rejects(changed(key to it)) }
        }
        listOf("NaN", "Infinity", "+0", "00", "--1", "0x1", "1e100").forEach { token ->
            rejects(valid.replace("\"${ToneController.KEYS[0]}\":-31", "\"${ToneController.KEYS[0]}\":$token"))
        }
    }

    @Test fun originalRangeAndBaseValuesHaveStrictCanonicalTypesAndBounds() {
        listOf(JsonPrimitive(100), JsonPrimitive(true), JsonPrimitive("0"), JsonPrimitive("21"),
            JsonPrimitive("020"), JsonPrimitive(" 20"), JsonPrimitive("+20"), JsonPrimitive("20.0"), JsonNull)
            .forEach { rejects(changed(ToneController.RANGE to it)) }
        ToneController.KEYS.forEach { key ->
            listOf(-101, 101).forEach { rejects(changed(key to JsonPrimitive(it))) }
        }
        rejects(changed(ToneController.RANGE to JsonPrimitive("20"))) // The saved ten values exceed this original range.
    }

    @Test fun dynamicFastSlowRelationsAndAllFilterBoundsRejectEvenWithGroupsOff() {
        listOf(false, true).forEach { active ->
            rejects(changed(ToneAdvancedParameters.DYNAMIC_ON to JsonPrimitive(active),
                ToneAdvancedParameters.bassFast.key to JsonPrimitive(500),
                ToneAdvancedParameters.bassSlow.key to JsonPrimitive(50)))
            rejects(changed(ToneAdvancedParameters.DYNAMIC_ON to JsonPrimitive(active),
                ToneAdvancedParameters.transientFast.key to JsonPrimitive(500),
                ToneAdvancedParameters.transientSlow.key to JsonPrimitive(49)))
            ToneAdvancedParameters.frequency.forEach { control ->
                listOf(19, 20001).forEach { rejects(changed(ToneAdvancedParameters.SHAPE_ON to JsonPrimitive(active), control.key to JsonPrimitive(it))) }
            }
            ToneAdvancedParameters.q.forEach { control ->
                listOf(24, 401).forEach { rejects(changed(ToneAdvancedParameters.SHAPE_ON to JsonPrimitive(active), control.key to JsonPrimitive(it))) }
            }
            ToneAdvancedParameters.slope.forEach { control ->
                listOf(249, 1001).forEach { rejects(changed(ToneAdvancedParameters.SHAPE_ON to JsonPrimitive(active), control.key to JsonPrimitive(it))) }
            }
        }
        // The ten filters are separate bands: frequency order is intentionally unrestricted.
        val unordered = settings.copy(frequency = listOf(20000,20,60,20000,20,1800,3500,5500,8500,12000),
            bassFast = 49, bassSlow = 5, transientFast = 1, transientSlow = 5)
        assertEquals(unordered, ToneAdvancedPresetCodec.decode(ToneAdvancedPresetCodec.encode(listOf(preset.copy(settings = unordered)))).single().settings)
    }

    @Test fun entryCountIdentityAndUniqueNamesAreValidated() {
        val twenty = (1..20).map { preset.copy(id = UUID.randomUUID().toString(), name = "收藏 $it") }
        assertEquals(twenty, ToneAdvancedPresetCodec.decode(ToneAdvancedPresetCodec.encode(twenty)))
        assertThrows(IllegalArgumentException::class.java) { ToneAdvancedPresetCodec.encode(emptyList()) }
        assertEquals(emptyList<ToneAdvancedPreset>(), ToneAdvancedPresetCodec.decode(ToneAdvancedPresetCodec.encode(emptyList(), true), true))
        assertThrows(IllegalArgumentException::class.java) { ToneAdvancedPresetCodec.encode(twenty + preset) }
        assertThrows(IllegalArgumentException::class.java) { ToneAdvancedPresetCodec.encode(listOf(preset, preset.copy(name = "另一份"))) }
        assertThrows(IllegalArgumentException::class.java) { ToneAdvancedPresetCodec.encode(listOf(preset, preset.copy(id = UUID.randomUUID().toString()))) }
        listOf("not-an-id", "1-1-1-1-1", preset.id.uppercase()).forEach { rejects(valid.replace(preset.id, it)) }
    }

    @Test fun namesAndNestingRejectMalformedUnicodeAndControlCharacters() {
        assertEquals("温暖", ToneAdvancedPresetCodec.name("  温暖  "))
        listOf("", " ", "字".repeat(41), "换行\n", "名字\u0000", "\uD800", "\uDC00").forEach {
            assertThrows(IllegalArgumentException::class.java) { ToneAdvancedPresetCodec.name(it) }
        }
        val special = preset.copy(name = "[]{}\\\"音🎵效")
        assertEquals(special, ToneAdvancedPresetCodec.decode(ToneAdvancedPresetCodec.encode(listOf(special))).single())
        rejects(valid.replace("温暖自然", "\\uD800"))
        rejects("[".repeat(10000) + "]".repeat(10000))
    }

    @Test fun strictUtf8ByteLimitAndInputStreamOwnershipAreEnforced() {
        var closed = false
        val input = object : ByteArrayInputStream(valid.toByteArray(Charsets.UTF_8)) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(valid, ToneAdvancedPresetCodec.read(input)); assertFalse(closed)
        assertThrows(IOException::class.java) { ToneAdvancedPresetCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertThrows(IllegalArgumentException::class.java) { ToneAdvancedPresetCodec.read(ByteArrayInputStream(ByteArray(ToneAdvancedPresetCodec.MAX_BYTES + 1))) }
        val boundary = valid + " ".repeat(ToneAdvancedPresetCodec.MAX_BYTES - valid.toByteArray(Charsets.UTF_8).size)
        assertEquals(preset, ToneAdvancedPresetCodec.decode(ToneAdvancedPresetCodec.read(ByteArrayInputStream(boundary.toByteArray(Charsets.UTF_8)))).single())
        rejects(boundary + " ")
        val multibyte = valid.dropLast(1) + ",\"extra\":\"" + "中".repeat(22000) + "\"}"
        assertTrue(multibyte.length < ToneAdvancedPresetCodec.MAX_BYTES)
        assertTrue(rejects(multibyte).message!!.contains("64 KiB"))
    }
}
