package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

class ReverbPresetCodecTest {
    private val settings = ReverbParameters.Settings(
        legacy = listOf(17, 68, 53, 29, 47), professional = true,
        size = 173, width = 687, damp = 531, wet = 292, dry = 476,
        predelayOn = true, predelay = 137, lowCutOn = true, lowCut = 143,
        highCutOn = true, highCut = 8431, linked = true, mix = 231,
        outputOn = true, output = -17, meters = true,
    )
    private val preset = ReverbPreset(UUID.randomUUID().toString(), "人声空间", settings)
    private val valid get() = ReverbPresetCodec.encode(listOf(preset))
    private fun changed(key: String, value: JsonElement): String {
        val root = Json.parseToJsonElement(valid).jsonObject
        val entry = root["presets"]!!.jsonArray.single().jsonObject
        val values = entry["settings"]!!.jsonObject
        val modified = JsonObject(entry + ("settings" to JsonObject(values + (key to value))))
        return JsonObject(root + ("presets" to JsonArray(listOf(modified)))).toString()
    }
    private fun rejects(json: String) = assertThrows(IllegalArgumentException::class.java) {
        ReverbPresetCodec.decode(json)
    }

    @Test fun allLegacyAndProfessionalValuesRoundTripWithoutPowerOrTemporaryState() {
        val restored = ReverbPresetCodec.decode(valid).single()
        assertEquals(preset, restored)
        val values = Json.parseToJsonElement(valid).jsonObject["presets"]!!.jsonArray.single()
            .jsonObject["settings"]!!.jsonObject
        assertEquals(ReverbParameters.legacyKeys + ReverbParameters.newKeys, values.keys)
        assertFalse(values.containsKey(ReverbParameters.ENABLED))
        listOf("audition", "snapshot", "match", "measurement", "peak", "rms").forEach { word ->
            assertFalse(values.keys.any { it.contains(word) })
        }
        val default = preset.copy(settings = ReverbParameters.Settings())
        assertEquals(default, ReverbPresetCodec.decode(ReverbPresetCodec.encode(listOf(default))).single())
    }

    @Test fun wrongModuleVersionUnitsUnknownAndMissingFieldsAreRejected() {
        listOf("", "[]", "{}", "not-json", valid.dropLast(1), valid + "{}",
            valid.replace(ReverbPresetCodec.FORMAT, CrossfeedPresetCodec.FORMAT),
            valid.replace(ReverbPresetCodec.FORMAT, PresetCrossfeedPresetCodec.FORMAT),
            valid.replace(ReverbPresetCodec.FORMAT, SurroundPresetCodec.FORMAT),
            valid.replace("\"version\":2", "\"version\":3"),
            valid.replace("\"version\":2", "\"version\":\"2\""),
            valid.replace("tenth-ms", "ms"),
            valid.replace("\"presets\":", "\"unknown\":null,\"presets\":"),
            valid.replace("\"settings\":", "\"enabled\":true,\"settings\":"),
            changed(ReverbParameters.ENABLED, JsonPrimitive(true)),
            changed("v_reverb_pro_unknown", JsonPrimitive(1)),
            valid.replace("\"${ReverbParameters.METERS}\":true", "\"missing\":true")
        ).forEach(::rejects)
        assertThrows(IllegalArgumentException::class.java) { CrossfeedPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { PresetCrossfeedPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { SurroundPresetCodec.decode(valid) }
    }

    @Test fun duplicateFieldsIncludingEscapedNamesAreNeverSilentlyOverwritten() {
        val key = ReverbParameters.predelay.key
        listOf(
            valid.replace("\"version\":2", "\"version\":2,\"version\":2"),
            valid.replace("\"version\":2", "\"version\":2,\"ver\\u0073ion\":2"),
            valid.replace("\"name\":\"人声空间\"", "\"name\":\"人声空间\",\"name\":\"另名\""),
            valid.replace("\"$key\":137", "\"$key\":1,\"$key\":137"),
            valid.replace("\"settings\":", "\"settings\":{},\"settings\":")
        ).forEach { document -> assertTrue(rejects(document).message!!.contains("重复字段")) }
    }

    @Test fun everyNumberRejectsOutOfRangeAndWrongPrimitiveTypesRatherThanNormalizing() {
        ReverbParameters.allNumbers.forEach { control ->
            listOf(JsonPrimitive(control.min - 1), JsonPrimitive(control.max + 1), JsonPrimitive(true),
                JsonPrimitive("${control.default}"), JsonPrimitive(1.5), JsonNull,
                JsonPrimitive(Long.MAX_VALUE)).forEach { rejects(changed(control.key, it)) }
        }
        ReverbParameters.legacyKeys.forEach { key ->
            listOf(JsonPrimitive(-1), JsonPrimitive(101), JsonPrimitive(false), JsonPrimitive("50"))
                .forEach { rejects(changed(key, it)) }
        }
        (ReverbParameters.switches+ReverbAdvancedParameters.switches).forEach { key ->
            listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive("true"), JsonNull)
                .forEach { rejects(changed(key, it)) }
        }
        val key = ReverbParameters.predelay.key
        listOf("NaN", "Infinity", "1e99", "137.0", "+137", "0137", "--137", "0x89").forEach {
            rejects(valid.replace("\"$key\":137", "\"$key\":$it"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReverbPresetCodec.encode(listOf(preset.copy(settings = settings.copy(predelay = 2001))))
        }
    }

    @Test fun entryLimitsCanonicalIdsAndUniqueNamesAreValidated() {
        val twenty = (1..20).map { preset.copy(id = UUID.randomUUID().toString(), name = "收藏 $it") }
        assertEquals(twenty, ReverbPresetCodec.decode(ReverbPresetCodec.encode(twenty)))
        assertThrows(IllegalArgumentException::class.java) { ReverbPresetCodec.encode(emptyList()) }
        assertEquals(emptyList<ReverbPreset>(), ReverbPresetCodec.decode(ReverbPresetCodec.encode(emptyList(), true), true))
        assertThrows(IllegalArgumentException::class.java) { ReverbPresetCodec.encode(twenty + preset) }
        assertThrows(IllegalArgumentException::class.java) {
            ReverbPresetCodec.encode(listOf(preset, preset.copy(name = "不同名称")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReverbPresetCodec.encode(listOf(preset, preset.copy(id = UUID.randomUUID().toString())))
        }
        listOf("not-an-id", "1-1-1-1-1", preset.id.uppercase()).forEach { invalid ->
            if (invalid != preset.id) rejects(valid.replace(preset.id, invalid))
        }
    }

    @Test fun namesAreTrimmedAndUnicodeControlsAndBrokenSurrogatesAreRejected() {
        assertEquals("人声", ReverbPresetCodec.name("  人声  "))
        listOf("", " ", "名".repeat(41), "名字\n", "名字\u0000", "\uD800", "\uDC00")
            .forEach { name -> assertThrows(IllegalArgumentException::class.java) { ReverbPresetCodec.name(name) } }
        val complex = preset.copy(name = "[]{}\\\"音🎵效")
        assertEquals(complex, ReverbPresetCodec.decode(ReverbPresetCodec.encode(listOf(complex))).single())
        rejects(valid.replace("人声空间", "\\uD800"))
        rejects("[".repeat(10000) + "]".repeat(10000))
    }

    @Test fun inputHasStrictUtf8AndByteBoundsAndTheCallerRetainsStreamOwnership() {
        var closed = false
        val stream = object : ByteArrayInputStream(valid.toByteArray(Charsets.UTF_8)) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(valid, ReverbPresetCodec.read(stream)); assertFalse(closed)
        assertThrows(IOException::class.java) {
            ReverbPresetCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReverbPresetCodec.read(ByteArrayInputStream(ByteArray(ReverbPresetCodec.MAX_BYTES + 1)))
        }
        val boundary = valid + " ".repeat(ReverbPresetCodec.MAX_BYTES - valid.toByteArray(Charsets.UTF_8).size)
        assertEquals(ReverbPresetCodec.MAX_BYTES, boundary.toByteArray(Charsets.UTF_8).size)
        assertEquals(preset, ReverbPresetCodec.decode(ReverbPresetCodec.read(
            ByteArrayInputStream(boundary.toByteArray(Charsets.UTF_8)))).single())
        rejects(valid + " ".repeat(ReverbPresetCodec.MAX_BYTES))
        val oversizedMultibyte = valid.dropLast(1) + ",\"extra\":\"" + "中".repeat(22000) + "\"}"
        assertTrue(oversizedMultibyte.length < ReverbPresetCodec.MAX_BYTES)
        assertTrue(rejects(oversizedMultibyte).message!!.contains("64 KiB"))
    }

    @Test fun currentVersionFilesCanBeReexportedWithoutDiscardingInactiveControlValues() {
        val inactive = preset.copy(settings = settings.copy(professional = false, predelayOn = false,
            lowCutOn = false, highCutOn = false, linked = false, outputOn = false, meters = false))
        val existing = ReverbPresetCodec.encode(listOf(inactive))
        assertEquals(inactive, ReverbPresetCodec.decode(existing).single())
        assertEquals(existing, ReverbPresetCodec.encode(ReverbPresetCodec.decode(existing)))
        assertTrue(ReverbSystemPresets.entries.all { it.second.valid() })
        assertTrue(ReverbSystemPresets.entries.all { !it.second.values().containsKey(ReverbParameters.ENABLED) })
        assertFalse(ReverbSystemPresets.entries.first().second.professional)
    }
}
