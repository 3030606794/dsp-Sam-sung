package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

class RoomReverbPresetCodecTest {
    private val settings = RoomReverbParameters.defaults(4, true).copy(
        timeOn = true, rt60 = 237, earlyScale = 127, predelayOn = true, predelay = 173,
        levelsOn = true, dryTrim = -17, earlyTrim = 23, lateTrim = -31,
        linked = true, mix = 317, widthsOn = true, earlyWidth = -173, lateWidth = 731,
        toneOn = true, lowCut = 137, highCut = 11753, damp = 8371,
        outputOn = true, output = -29, meters = true,
    )
    private val preset = RoomReverbPreset(UUID.randomUUID().toString(), "人声空间", settings)
    private val valid get() = RoomReverbPresetCodec.encode(listOf(preset))
    private fun changed(key: String, value: JsonElement): String {
        val root = Json.parseToJsonElement(valid).jsonObject
        val entry = root["presets"]!!.jsonArray.single().jsonObject
        val values = entry["settings"]!!.jsonObject
        val modified = JsonObject(entry + ("settings" to JsonObject(values + (key to value))))
        return JsonObject(root + ("presets" to JsonArray(listOf(modified)))).toString()
    }
    private fun rejects(json: String) = assertThrows(IllegalArgumentException::class.java) {
        RoomReverbPresetCodec.decode(json)
    }

    @Test fun completeSettingsRoundTripWithoutPowerOrTemporaryState() {
        assertEquals(preset, RoomReverbPresetCodec.decode(valid).single())
        val values = Json.parseToJsonElement(valid).jsonObject["presets"]!!.jsonArray.single()
            .jsonObject["settings"]!!.jsonObject
        assertEquals(settings.values().keys, values.keys)
        assertFalse(values.containsKey("reverb_enable"))
        listOf("audition", "snapshot", "match", "measurement", "peak", "rms").forEach { word ->
            assertFalse(values.keys.any { it.contains(word) })
        }
        assertEquals("4", values["reverb_preset"]!!.jsonPrimitive.content)
        assertTrue(values["reverb_preset"]!!.jsonPrimitive.isString)
        assertEquals(valid, RoomReverbPresetCodec.encode(RoomReverbPresetCodec.decode(valid)))
    }

    @Test fun allThirteenOriginalBasesRoundTripWithEveryNewFeatureOff() {
        val bases = RoomReverbParameters.baseIds
        assertEquals(listOf(0, 1, 2, 4, 5, 7, 8, 9, 11, 15, 16, 17, 18), bases)
        val presets = bases.map { base ->
            val defaults = RoomReverbParameters.defaults(base)
            assertEquals(base, defaults.base); assertTrue(defaults.valid())
            assertTrue(defaults.values().values.filterIsInstance<Boolean>().all { !it })
            RoomReverbPreset(UUID.randomUUID().toString(), RoomReverbParameters.baseName(base), defaults)
        }
        assertEquals(presets, RoomReverbPresetCodec.decode(RoomReverbPresetCodec.encode(presets)))
        val inactive = preset.copy(settings = settings.copy(professional = false))
        assertEquals(inactive, RoomReverbPresetCodec.decode(RoomReverbPresetCodec.encode(listOf(inactive))).single())
    }

    @Test fun wrongModulesVersionsUnitsUnknownAndMissingFieldsAreRejected() {
        listOf("", "[]", "{}", "not-json", valid.dropLast(1), valid + "{}",
            valid.replace(RoomReverbPresetCodec.FORMAT, ReverbPresetCodec.FORMAT),
            valid.replace(RoomReverbPresetCodec.FORMAT, CrossfeedPresetCodec.FORMAT),
            valid.replace(RoomReverbPresetCodec.FORMAT, PresetCrossfeedPresetCodec.FORMAT),
            valid.replace(RoomReverbPresetCodec.FORMAT, SurroundPresetCodec.FORMAT),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("centisecond", "second"),
            valid.replace("\"presets\":", "\"unknown\":null,\"presets\":"),
            valid.replace("\"settings\":", "\"enabled\":true,\"settings\":"),
            changed("reverb_enable", JsonPrimitive(true)),
            changed("room_reverb_unknown", JsonPrimitive(1)),
            valid.replace("\"reverb_preset\":\"4\",", "")
        ).forEach(::rejects)
        assertThrows(IllegalArgumentException::class.java) { ReverbPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { CrossfeedPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { PresetCrossfeedPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { SurroundPresetCodec.decode(valid) }
    }

    @Test fun duplicateFieldsIncludingEscapedNamesAreNeverSilentlyOverwritten() {
        listOf(
            valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            valid.replace("\"name\":\"人声空间\"", "\"name\":\"人声空间\",\"name\":\"另名\""),
            valid.replace("\"reverb_preset\":\"4\"", "\"reverb_preset\":\"1\",\"reverb_preset\":\"4\""),
            valid.replace("\"settings\":", "\"settings\":{},\"settings\":")
        ).forEach { document -> assertTrue(rejects(document).message!!.contains("重复字段")) }
    }

    @Test fun allNumericAndBooleanValuesRejectInvalidTypesAndBounds() {
        RoomReverbParameters.numbers.forEach { control ->
            listOf(JsonPrimitive(control.min - 1), JsonPrimitive(control.max + 1), JsonPrimitive(true),
                JsonPrimitive("${control.default}"), JsonPrimitive(1.5), JsonNull,
                JsonPrimitive(Long.MAX_VALUE)).forEach { rejects(changed(control.key, it)) }
        }
        settings.values().filterValues { it is Boolean }.keys.forEach { key ->
            listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive("true"), JsonNull)
                .forEach { rejects(changed(key, it)) }
        }
        listOf(JsonPrimitive(4), JsonPrimitive("3"), JsonPrimitive("04"), JsonPrimitive("4.0"),
            JsonPrimitive("19"), JsonPrimitive("-1"), JsonPrimitive("NaN"), JsonNull)
            .forEach { rejects(changed("reverb_preset", it)) }
        rejects(changed(RoomReverbParameters.highCut.key, JsonPrimitive(100)))
        val numericKey = settings.values().entries.first { it.value == 237 }.key
        listOf("NaN", "Infinity", "1e99", "237.0", "+237", "0237", "--237", "0xed").forEach {
            rejects(valid.replace("\"$numericKey\":237", "\"$numericKey\":$it"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoomReverbPresetCodec.encode(listOf(preset.copy(settings = settings.copy(base = 3))))
        }
    }

    @Test fun entryLimitsCanonicalIdsAndUniqueNamesAreValidated() {
        val twenty = (1..20).map { preset.copy(id = UUID.randomUUID().toString(), name = "收藏 $it") }
        assertEquals(twenty, RoomReverbPresetCodec.decode(RoomReverbPresetCodec.encode(twenty)))
        assertThrows(IllegalArgumentException::class.java) { RoomReverbPresetCodec.encode(emptyList()) }
        assertEquals(emptyList<RoomReverbPreset>(), RoomReverbPresetCodec.decode(RoomReverbPresetCodec.encode(emptyList(), true), true))
        assertThrows(IllegalArgumentException::class.java) { RoomReverbPresetCodec.encode(twenty + preset) }
        assertThrows(IllegalArgumentException::class.java) {
            RoomReverbPresetCodec.encode(listOf(preset, preset.copy(name = "不同名称")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoomReverbPresetCodec.encode(listOf(preset, preset.copy(id = UUID.randomUUID().toString())))
        }
        listOf("not-an-id", "1-1-1-1-1", preset.id.uppercase()).forEach { invalid ->
            if (invalid != preset.id) rejects(valid.replace(preset.id, invalid))
        }
    }

    @Test fun namesAreTrimmedAndUnicodeControlsAndBrokenSurrogatesAreRejected() {
        assertEquals("人声", RoomReverbPresetCodec.name("  人声  "))
        listOf("", " ", "名".repeat(41), "名字\n", "名字\u0000", "\uD800", "\uDC00")
            .forEach { name -> assertThrows(IllegalArgumentException::class.java) { RoomReverbPresetCodec.name(name) } }
        val complex = preset.copy(name = "[]{}\\\"音🎵效")
        assertEquals(complex, RoomReverbPresetCodec.decode(RoomReverbPresetCodec.encode(listOf(complex))).single())
        rejects(valid.replace("人声空间", "\\uD800"))
        rejects("[".repeat(10000) + "]".repeat(10000))
    }

    @Test fun inputHasStrictUtf8AndByteBoundsAndCallerRetainsStreamOwnership() {
        var closed = false
        val stream = object : ByteArrayInputStream(valid.toByteArray(Charsets.UTF_8)) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(valid, RoomReverbPresetCodec.read(stream)); assertFalse(closed)
        assertThrows(IOException::class.java) {
            RoomReverbPresetCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoomReverbPresetCodec.read(ByteArrayInputStream(ByteArray(RoomReverbPresetCodec.MAX_BYTES + 1)))
        }
        val boundary = valid + " ".repeat(RoomReverbPresetCodec.MAX_BYTES - valid.toByteArray(Charsets.UTF_8).size)
        assertEquals(RoomReverbPresetCodec.MAX_BYTES, boundary.toByteArray(Charsets.UTF_8).size)
        assertEquals(preset, RoomReverbPresetCodec.decode(RoomReverbPresetCodec.read(
            ByteArrayInputStream(boundary.toByteArray(Charsets.UTF_8)))).single())
        rejects(valid + " ".repeat(RoomReverbPresetCodec.MAX_BYTES))
        val oversizedMultibyte = valid.dropLast(1) + ",\"extra\":\"" + "中".repeat(22000) + "\"}"
        assertTrue(oversizedMultibyte.length < RoomReverbPresetCodec.MAX_BYTES)
        assertTrue(rejects(oversizedMultibyte).message!!.contains("64 KiB"))
    }
}
