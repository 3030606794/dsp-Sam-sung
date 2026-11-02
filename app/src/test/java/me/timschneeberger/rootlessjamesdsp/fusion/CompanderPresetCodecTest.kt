package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

class CompanderPresetCodecTest {
    private val curve = "95;200;400;800;1600;3400;7500;-0.2;0.3;-0.4;0.5;-0.6;0.7;-0.8"
    private val settings = CompanderParameters.capture(CompanderParameters.defaults().values() + mapOf(
        "compander_timeconstant" to 0.173f, "compander_granularity" to 3f,
        "compander_tftransforms" to "2", "compander_response" to curve,
    )).copy(professional = true)
    private val preset = CompanderPreset(UUID.randomUUID().toString(), "轻柔低频", settings)
    private val valid get() = CompanderPresetCodec.encode(listOf(preset))
    private fun changed(vararg updates: Pair<String, JsonElement>): String {
        val root = Json.parseToJsonElement(valid).jsonObject
        val entry = root["presets"]!!.jsonArray.single().jsonObject
        val values = entry["settings"]!!.jsonObject
        val modified = JsonObject(entry + ("settings" to JsonObject(values + updates.toMap())))
        return JsonObject(root + ("presets" to JsonArray(listOf(modified)))).toString()
    }
    private fun rejects(json: String) = assertThrows(IllegalArgumentException::class.java) { CompanderPresetCodec.decode(json) }

    @Test fun completeSettingsRoundTripPreservesOriginalFloatAndStringTypesButExcludesPowerAndTools() {
        assertEquals(preset, CompanderPresetCodec.decode(valid).single())
        val values = Json.parseToJsonElement(valid).jsonObject["presets"]!!.jsonArray.single().jsonObject["settings"]!!.jsonObject
        assertEquals(CompanderParameters.keys, values.keys)
        assertFalse(values.containsKey(CompanderParameters.ENABLED))
        assertFalse(values["compander_timeconstant"]!!.jsonPrimitive.isString)
        assertEquals(0.173f, values["compander_timeconstant"]!!.jsonPrimitive.float, 0f)
        assertFalse(values["compander_granularity"]!!.jsonPrimitive.isString)
        assertEquals(3f, values["compander_granularity"]!!.jsonPrimitive.float, 0f)
        assertTrue(values["compander_tftransforms"]!!.jsonPrimitive.isString)
        assertEquals("2", values["compander_tftransforms"]!!.jsonPrimitive.content)
        assertEquals(curve, values["compander_response"]!!.jsonPrimitive.content)
        listOf("audition", "snapshot", "match", "measurement_result").forEach { word ->
            assertFalse(values.keys.any { it.contains(word) })
        }
        assertEquals(valid, CompanderPresetCodec.encode(CompanderPresetCodec.decode(valid)))
    }

    @Test fun allFourModesAndFiveGranularitiesRetainTheirFoundationWithAllNewFlagsOff() {
        val defaults = CompanderParameters.defaults()
        assertTrue(defaults.valid())
        assertTrue(defaults.values().values.filterIsInstance<Boolean>().all { !it })
        (0..3).forEach { mode -> (0..4).forEach { granularity ->
            val value = CompanderParameters.capture(defaults.values() + mapOf(
                "compander_tftransforms" to mode.toString(), "compander_granularity" to granularity.toFloat(),
                "compander_timeconstant" to 0.173f, "compander_response" to curve))
            val original = preset.copy(settings = value)
            assertEquals(original, CompanderPresetCodec.decode(CompanderPresetCodec.encode(listOf(original))).single())
        } }
        assertEquals(preset.copy(settings = settings.copy(professional = false)),
            CompanderPresetCodec.decode(CompanderPresetCodec.encode(listOf(preset.copy(settings = settings.copy(professional = false))))).single())
    }

    @Test fun systemDirectionsAreOptInPreserveEveryFoundationAndRoundTripCompletely() {
        val schemes = CompanderSystemPresets.entries(settings)
        assertTrue(schemes.size >= 6)
        assertEquals(CompanderSystemPresets.foundation(settings), schemes.first().second)
        val foundationKeys = setOf("compander_timeconstant", "compander_granularity", "compander_tftransforms", "compander_response")
        schemes.forEach { (_, value) ->
            assertTrue(value.valid())
            foundationKeys.forEach { assertEquals(settings.values()[it], value.values()[it]) }
        }
        assertTrue(schemes.first().second.values().values.filterIsInstance<Boolean>().all { !it })
        assertTrue(schemes.drop(1).all { it.second.professional })
        val personal = schemes.map { (name, value) -> CompanderPreset(UUID.randomUUID().toString(), name, value) }
        assertEquals(personal, CompanderPresetCodec.decode(CompanderPresetCodec.encode(personal)))
        assertTrue(CompanderParameters.defaults().values().values.filterIsInstance<Boolean>().all { !it })
    }

    @Test fun factoryPacketsAgreeWithNativeGoldenMatrixForTheOriginalFoundation() {
        val expected = listOf(
            intArrayOf(1,0,0,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,0,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,120,240,0,0,100,0,250,0,0,0,-800,0,0,0,0,1000,0,0,0,-180,200,60,1,20,0,0,0,0,0,30,0,0,0,0,0),
            intArrayOf(1,1,0,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,1000,0,0,0,0,0,0,0,-250,-250,-250,-250,-250,-250,-250,1,0,60,1,0,100,0,300,1,10,30,-800,1,0,0,1,800,1,0,0,-180,200,60,1,20,0,0,0,0,0,30,0,0,0,0,0),
            intArrayOf(1,1,0,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,750,0,0,0,0,0,0,0,150,350,250,100,50,50,100,1,40,60,1,0,20,8,140,1,10,20,-700,1,40,12000,1,700,1,-20,0,-180,200,60,1,20,0,0,0,0,0,30,0,0,0,0,0),
            intArrayOf(1,1,1,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,120,1,0,100,0,250,1,0,0,-800,1,0,0,1,1000,1,0,0,-180,200,60,1,20,1,80,0,1,50,30,0,0,0,0,0),
            intArrayOf(1,1,1,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,60,0,1,0,200,30,350,1,0,0,-700,1,0,0,1,1000,1,-30,1,-300,200,60,1,20,0,0,0,0,0,30,0,0,0,0,0),
            intArrayOf(1,1,1,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,180,1,0,50,50,150,0,0,0,-1000,1,0,0,1,1000,1,0,2,-400,200,60,1,20,1,80,0,0,0,30,0,0,0,0,0),
            intArrayOf(1,1,1,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,480,1,0,10,80,200,0,0,0,-1000,1,0,0,1,1000,1,0,3,-450,200,0,0,20,1,80,0,1,30,40,0,0,0,0,0),
        )
        val actual = CompanderSystemPresets.entries(CompanderParameters.defaults())
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (packet, direction) -> assertArrayEquals(packet, direction.second.packet()) }
    }

    @Test fun wrongModulesVersionsUnitsUnknownAndMissingFieldsRejectTheWholeDocument() {
        val otherFormats = listOf(DynamicPresetCodec.FORMAT, TimbrePresetCodec.FORMAT, AnalogPresetCodec.FORMAT, TubePresetCodec.FORMAT,
            ReverbPresetCodec.FORMAT, RoomReverbPresetCodec.FORMAT, CrossfeedPresetCodec.FORMAT,
            PresetCrossfeedPresetCodec.FORMAT, SurroundPresetCodec.FORMAT)
        otherFormats.forEach { rejects(valid.replace(CompanderPresetCodec.FORMAT, it)) }
        listOf("", "[]", "{}", "not-json", valid.dropLast(1), valid + "{}",
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("tenth-dB", "dB"),
            valid.replace("\"presets\":", "\"unknown\":null,\"presets\":"),
            valid.replace("\"settings\":", "\"enabled\":true,\"settings\":"),
            changed(CompanderParameters.ENABLED to JsonPrimitive(true)),
            changed("compander_pro_unknown" to JsonPrimitive(1)),
            valid.replace("\"compander_timeconstant\":0.173,", "")
        ).forEach(::rejects)
        assertThrows(IllegalArgumentException::class.java) { TimbrePresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { AnalogPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { TubePresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { ReverbPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { RoomReverbPresetCodec.decode(valid) }
    }

    @Test fun duplicateFieldsIncludingEscapedIdentitiesNeverSilentlyOverwrite() {
        listOf(
            valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            valid.replace("\"name\":\"轻柔低频\"", "\"name\":\"轻柔低频\",\"name\":\"另名\""),
            valid.replace("\"compander_timeconstant\":0.173", "\"compander_timeconstant\":0.22,\"compander_timeconstant\":0.173"),
            valid.replace("\"settings\":", "\"settings\":{},\"settings\":"),
            valid.replace("\"compander_tftransforms\":\"2\"", "\"compander_tftransforms\":\"0\",\"compander_tftransforms\":\"2\"")
        ).forEach { document -> assertTrue(rejects(document).message!!.contains("重复字段")) }
    }

    @Test fun professionalIntegersAndBooleansRejectTypesOverflowAndUnknownFields() {
        settings.values().filterValues { it is Int }.forEach { (key, _) ->
            listOf(JsonPrimitive(Int.MAX_VALUE), JsonPrimitive(Int.MIN_VALUE), JsonPrimitive(true),
                JsonPrimitive("0"), JsonPrimitive(1.5), JsonNull, JsonPrimitive(Long.MAX_VALUE))
                .forEach { rejects(changed(key to it)) }
        }
        settings.values().filterValues { it is Boolean }.keys.forEach { key ->
            listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive("true"), JsonNull).forEach { rejects(changed(key to it)) }
        }
        rejects(changed("compander_pro_misspelled" to JsonPrimitive(0)))
    }

    @Test fun disabledGroupsStillRejectRangeGapsReversalsAndInvalidSidechainBands() {
        listOf(true, false).forEach { active ->
            listOf(1, 19).forEach { gap ->
                rejects(changed(CompanderParameters.DETECT_ON to JsonPrimitive(active),
                    CompanderParameters.rangeLow.key to JsonPrimitive(gap)))
                rejects(changed(CompanderParameters.SIDECHAIN_ON to JsonPrimitive(active),
                    CompanderParameters.sideHP.key to JsonPrimitive(gap)))
            }
            listOf(1, 499).forEach { gap ->
                rejects(changed(CompanderParameters.SIDECHAIN_ON to JsonPrimitive(active),
                    CompanderParameters.sideLP.key to JsonPrimitive(gap)))
            }
            rejects(changed(CompanderParameters.DETECT_ON to JsonPrimitive(active),
                CompanderParameters.rangeLow.key to JsonPrimitive(15000),
                CompanderParameters.rangeHigh.key to JsonPrimitive(14999)))
            rejects(changed(CompanderParameters.SIDECHAIN_ON to JsonPrimitive(active),
                CompanderParameters.sideHP.key to JsonPrimitive(500),
                CompanderParameters.sideLP.key to JsonPrimitive(500)))
        }
        listOf(settings.copy(rangeLow = 0, rangeHigh = 0, sideHP = 0, sideLP = 0),
            settings.copy(rangeLow = 20, rangeHigh = 20000, sideHP = 20, sideLP = 500)).forEach { value ->
            assertEquals(value, CompanderPresetCodec.decode(CompanderPresetCodec.encode(listOf(preset.copy(settings = value)))).single().settings)
        }
    }

    @Test fun legacyFloatAndModeTypesAreValidatedWithoutTypeCoercion() {
        listOf(JsonPrimitive(-0.1), JsonPrimitive(0.059), JsonPrimitive(0.301), JsonPrimitive("0.173"),
            JsonPrimitive(true), JsonNull, JsonPrimitive(1e99))
            .forEach { rejects(changed("compander_timeconstant" to it)) }
        listOf(JsonPrimitive(-1), JsonPrimitive(5), JsonPrimitive(4.01), JsonPrimitive("3"),
            JsonPrimitive(true), JsonNull, JsonPrimitive(1e99))
            .forEach { rejects(changed("compander_granularity" to it)) }
        listOf(JsonPrimitive(2), JsonPrimitive(true), JsonPrimitive("4"), JsonPrimitive("-1"), JsonPrimitive("02"),
            JsonPrimitive("+2"), JsonPrimitive("2.0"), JsonPrimitive(" 2"), JsonNull)
            .forEach { rejects(changed("compander_tftransforms" to it)) }
        listOf("NaN", "Infinity", "1e99", "+0.173", "00.173", "--0.173", "0x1").forEach {
            rejects(valid.replace("\"compander_timeconstant\":0.173", "\"compander_timeconstant\":$it"))
        }
    }

    @Test fun malformedCurvesMissingValuesNonFiniteReversedOrOutOfBoundsRejectEntireDocument() {
        val tokens = curve.split(';')
        fun altered(index: Int, value: String) = tokens.toMutableList().apply { this[index] = value }.joinToString(";")
        val invalid = listOf("", "0", tokens.dropLast(1).joinToString(";"), curve + ";1", curve + ";;",
            altered(0, "NaN"), altered(0, "Infinity"), altered(0, "0"), altered(6, "1000000"),
            altered(1, "95"), altered(1, "94"), altered(1, ""), altered(7, "NaN"), altered(8, "1.201"),
            altered(9, "-1.201"), altered(10, "Infinity"))
        invalid.forEach { rejects(changed("compander_response" to JsonPrimitive(it))) }
        listOf(JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonNull)
            .forEach { rejects(changed("compander_response" to it)) }
    }

    @Test fun storedCurveFormattingAndFoundationFloatPrecisionSurviveRoundTrip() {
        val curveWithFormatting = "95.0;2e2;400.000;800;1600;3400;7500;-0.200;0.3;-4e-1;0.5;-0.6;0.7;-0.8"
        val foundation = CompanderParameters.capture(settings.values() + mapOf(
            "compander_response" to curveWithFormatting, "compander_timeconstant" to 0.173713f,
            "compander_granularity" to 1.7317f))
        assertTrue(foundation.valid())
        val restored = CompanderPresetCodec.decode(CompanderPresetCodec.encode(listOf(preset.copy(settings = foundation)))).single().settings
        assertEquals(foundation, restored)
        assertEquals(curveWithFormatting, restored.values()["compander_response"])
        assertEquals(0.173713f, restored.values()["compander_timeconstant"])
        assertEquals(1.7317f, restored.values()["compander_granularity"])
        assertEquals(1, restored.packet()[4]) // Original Float-to-Int processing semantics remain intact.
    }

    @Test fun entryLimitsCanonicalIdsAndUniqueNamesAreValidated() {
        val twenty = (1..20).map { preset.copy(id = UUID.randomUUID().toString(), name = "收藏 $it") }
        assertEquals(twenty, CompanderPresetCodec.decode(CompanderPresetCodec.encode(twenty)))
        assertThrows(IllegalArgumentException::class.java) { CompanderPresetCodec.encode(emptyList()) }
        assertEquals(emptyList<CompanderPreset>(), CompanderPresetCodec.decode(CompanderPresetCodec.encode(emptyList(), true), true))
        assertThrows(IllegalArgumentException::class.java) { CompanderPresetCodec.encode(twenty + preset) }
        assertThrows(IllegalArgumentException::class.java) { CompanderPresetCodec.encode(listOf(preset, preset.copy(name = "不同名称"))) }
        assertThrows(IllegalArgumentException::class.java) { CompanderPresetCodec.encode(listOf(preset, preset.copy(id = UUID.randomUUID().toString()))) }
        listOf("not-an-id", "1-1-1-1-1", preset.id.uppercase()).forEach { invalid -> if (invalid != preset.id) rejects(valid.replace(preset.id, invalid)) }
    }

    @Test fun namesAreTrimmedWhileControlsBrokenSurrogatesAndExcessiveNestingReject() {
        assertEquals("低频", CompanderPresetCodec.name("  低频  "))
        listOf("", " ", "名".repeat(41), "名字\n", "名字\u0000", "\uD800", "\uDC00")
            .forEach { name -> assertThrows(IllegalArgumentException::class.java) { CompanderPresetCodec.name(name) } }
        val complex = preset.copy(name = "[]{}\\\"音🎵效")
        assertEquals(complex, CompanderPresetCodec.decode(CompanderPresetCodec.encode(listOf(complex))).single())
        rejects(valid.replace("轻柔低频", "\\uD800")); rejects("[".repeat(10000) + "]".repeat(10000))
    }

    @Test fun strictUtf8ByteLimitAndCallerStreamOwnershipAreEnforced() {
        var closed = false
        val stream = object : ByteArrayInputStream(valid.toByteArray(Charsets.UTF_8)) { override fun close() { closed = true; super.close() } }
        assertEquals(valid, CompanderPresetCodec.read(stream)); assertFalse(closed)
        assertThrows(IOException::class.java) { CompanderPresetCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertThrows(IllegalArgumentException::class.java) { CompanderPresetCodec.read(ByteArrayInputStream(ByteArray(CompanderPresetCodec.MAX_BYTES + 1))) }
        val boundary = valid + " ".repeat(CompanderPresetCodec.MAX_BYTES - valid.toByteArray(Charsets.UTF_8).size)
        assertEquals(CompanderPresetCodec.MAX_BYTES, boundary.toByteArray(Charsets.UTF_8).size)
        assertEquals(preset, CompanderPresetCodec.decode(CompanderPresetCodec.read(ByteArrayInputStream(boundary.toByteArray(Charsets.UTF_8)))).single())
        rejects(valid + " ".repeat(CompanderPresetCodec.MAX_BYTES))
        val oversized = valid.dropLast(1) + ",\"extra\":\"" + "中".repeat(22000) + "\"}"
        assertTrue(oversized.length < CompanderPresetCodec.MAX_BYTES); assertTrue(rejects(oversized).message!!.contains("64 KiB"))
    }
}
