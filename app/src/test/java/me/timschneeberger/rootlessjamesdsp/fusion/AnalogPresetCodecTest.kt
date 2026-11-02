package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

class AnalogPresetCodecTest {
    private val settings = AnalogParameters.Settings(
        professional = true, baseMode = 2, coreOn = true, drive = 137, amount = 617,
        h2 = -213, h3 = 317, h4 = -731, h5 = 137,
        toneOn = true, hp = 431, hpQ = 937, lpDelta = -2753, lpQ = 1237,
        peak = 1373, peakGain = -129, peakBW = 3171,
        mixOn = true, mix = 317, outputOn = true, output = -29,
        qualityOn = true, factor = 4, meters = true,
    )
    private val preset = AnalogPreset(UUID.randomUUID().toString(), "轻暖人声", settings)
    private val valid get() = AnalogPresetCodec.encode(listOf(preset))
    private fun changed(key: String, value: JsonElement): String {
        val root = Json.parseToJsonElement(valid).jsonObject
        val entry = root["presets"]!!.jsonArray.single().jsonObject
        val values = entry["settings"]!!.jsonObject
        val modified = JsonObject(entry + ("settings" to JsonObject(values + (key to value))))
        return JsonObject(root + ("presets" to JsonArray(listOf(modified)))).toString()
    }
    private fun rejects(json: String) = assertThrows(IllegalArgumentException::class.java) {
        AnalogPresetCodec.decode(json)
    }

    @Test fun completeSettingsRoundTripWithoutPowerOrTemporaryState() {
        assertEquals(preset, AnalogPresetCodec.decode(valid).single())
        val values = Json.parseToJsonElement(valid).jsonObject["presets"]!!.jsonArray.single()
            .jsonObject["settings"]!!.jsonObject
        assertEquals(settings.values().keys, values.keys)
        assertFalse(values.containsKey(AnalogParameters.ENABLED))
        listOf("audition", "snapshot", "match", "measurement", "rms").forEach { word ->
            assertFalse(values.keys.any { it.contains(word) })
        }
        assertEquals(settings.values().keys, AnalogParameters.keys)
        assertTrue(values.containsKey(AnalogParameters.BASE))
        assertFalse(AnalogParameters.newKeys.contains(AnalogParameters.BASE))
        assertEquals(4, values[AnalogParameters.FACTOR]!!.jsonPrimitive.int)
        assertEquals(valid, AnalogPresetCodec.encode(AnalogPresetCodec.decode(valid)))
    }

    @Test fun originalDefaultsAndInactiveSettingsRoundTripWithEveryNewFeatureOff() {
        val defaults = AnalogParameters.defaults()
        assertTrue(defaults.valid())
        assertTrue(defaults.values().values.filterIsInstance<Boolean>().all { !it })
        val original = preset.copy(settings = defaults)
        assertEquals(original, AnalogPresetCodec.decode(AnalogPresetCodec.encode(listOf(original))).single())
        val inactive = preset.copy(settings = settings.copy(professional = false, coreOn = false, toneOn = false,
            mixOn = false, outputOn = false, qualityOn = false, meters = false))
        assertEquals(inactive, AnalogPresetCodec.decode(AnalogPresetCodec.encode(listOf(inactive))).single())
        assertEquals(AnalogParameters.defaults(), AnalogSystemPresets.entries.first().second)
    }

    @Test fun systemSoundSchemesAreDistinctValidSettingsAndNeverBecomeInitialDefaults() {
        val schemes = AnalogSystemPresets.entries
        assertEquals(6, schemes.size)
        assertEquals(AnalogParameters.defaults(), schemes.first().second)
        assertFalse(schemes.first().second.professional)
        assertEquals(6, schemes.map { it.second.packet().toList() }.toSet().size)
        for (base in 0..2) {
            assertEquals(AnalogParameters.defaults(base), schemes[base].second)
            assertFalse(schemes[base].second.professional)
            assertTrue(schemes[base].second.values().values.filterIsInstance<Boolean>().all { !it })
        }
        schemes.drop(3).forEach { (_, value) ->
            assertTrue(value.valid()); assertTrue(value.professional); assertTrue(value.coreOn)
            assertTrue(value.toneOn); assertTrue(value.mixOn); assertTrue(value.outputOn)
            assertTrue(value.qualityOn); assertFalse(value.meters)
        }
        val personal = schemes.map { (name, value) -> AnalogPreset(UUID.randomUUID().toString(), name, value) }
        assertEquals(personal, AnalogPresetCodec.decode(AnalogPresetCodec.encode(personal)))
        assertTrue(AnalogParameters.defaults().values().values.filterIsInstance<Boolean>().all { !it })
    }

    @Test fun wrongModulesVersionsUnitsUnknownAndMissingFieldsAreRejected() {
        listOf("", "[]", "{}", "not-json", valid.dropLast(1), valid + "{}",
            valid.replace(AnalogPresetCodec.FORMAT, TubePresetCodec.FORMAT),
            valid.replace(AnalogPresetCodec.FORMAT, ReverbPresetCodec.FORMAT),
            valid.replace(AnalogPresetCodec.FORMAT, RoomReverbPresetCodec.FORMAT),
            valid.replace(AnalogPresetCodec.FORMAT, CrossfeedPresetCodec.FORMAT),
            valid.replace(AnalogPresetCodec.FORMAT, PresetCrossfeedPresetCodec.FORMAT),
            valid.replace(AnalogPresetCodec.FORMAT, SurroundPresetCodec.FORMAT),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("tenth-dB", "dB"),
            valid.replace("\"presets\":", "\"unknown\":null,\"presets\":"),
            valid.replace("\"settings\":", "\"enabled\":true,\"settings\":"),
            changed(AnalogParameters.ENABLED, JsonPrimitive(true)),
            changed("v_analog_pro_unknown", JsonPrimitive(1)),
            valid.replace("\"${AnalogParameters.drive.key}\":137,", "")
        ).forEach(::rejects)
        assertThrows(IllegalArgumentException::class.java) { TubePresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { ReverbPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { RoomReverbPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { CrossfeedPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { PresetCrossfeedPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { SurroundPresetCodec.decode(valid) }
    }

    @Test fun duplicateFieldsIncludingEscapedNamesAreNeverSilentlyOverwritten() {
        listOf(
            valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            valid.replace("\"name\":\"轻暖人声\"", "\"name\":\"轻暖人声\",\"name\":\"另名\""),
            valid.replace("\"${AnalogParameters.drive.key}\":137", "\"${AnalogParameters.drive.key}\":1,\"${AnalogParameters.drive.key}\":137"),
            valid.replace("\"settings\":", "\"settings\":{},\"settings\":")
        ).forEach { document -> assertTrue(rejects(document).message!!.contains("重复字段")) }
    }

    @Test fun allNumericAndBooleanValuesRejectInvalidTypesAndBounds() {
        AnalogParameters.numbers.forEach { control ->
            listOf(JsonPrimitive(control.min - 1), JsonPrimitive(control.max + 1), JsonPrimitive(true),
                JsonPrimitive("${control.default}"), JsonPrimitive(1.5), JsonNull,
                JsonPrimitive(Long.MAX_VALUE)).forEach { rejects(changed(control.key, it)) }
        }
        settings.values().filterValues { it is Boolean }.keys.forEach { key ->
            listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive("true"), JsonNull)
                .forEach { rejects(changed(key, it)) }
        }
        listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive(3), JsonPrimitive(5), JsonPrimitive(4.0),
            JsonPrimitive("4"), JsonPrimitive(true), JsonPrimitive(Long.MAX_VALUE), JsonNull)
            .forEach { rejects(changed(AnalogParameters.FACTOR, it)) }
        listOf(JsonPrimitive(-1), JsonPrimitive(3), JsonPrimitive("2"), JsonPrimitive(2.0),
            JsonPrimitive(true), JsonNull, JsonPrimitive(Long.MAX_VALUE))
            .forEach { rejects(changed(AnalogParameters.BASE, it)) }
        val numericKey = AnalogParameters.drive.key
        listOf("NaN", "Infinity", "1e99", "137.0", "+137", "0137", "--137", "0x89").forEach {
            rejects(valid.replace("\"$numericKey\":137", "\"$numericKey\":$it"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalogPresetCodec.encode(listOf(preset.copy(settings = settings.copy(drive = 181))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalogPresetCodec.encode(listOf(preset.copy(settings = settings.copy(factor = 3))))
        }
    }

    @Test fun entryLimitsCanonicalIdsAndUniqueNamesAreValidated() {
        val twenty = (1..20).map { preset.copy(id = UUID.randomUUID().toString(), name = "收藏 $it") }
        assertEquals(twenty, AnalogPresetCodec.decode(AnalogPresetCodec.encode(twenty)))
        assertThrows(IllegalArgumentException::class.java) { AnalogPresetCodec.encode(emptyList()) }
        assertEquals(emptyList<AnalogPreset>(), AnalogPresetCodec.decode(AnalogPresetCodec.encode(emptyList(), true), true))
        assertThrows(IllegalArgumentException::class.java) { AnalogPresetCodec.encode(twenty + preset) }
        assertThrows(IllegalArgumentException::class.java) {
            AnalogPresetCodec.encode(listOf(preset, preset.copy(name = "不同名称")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalogPresetCodec.encode(listOf(preset, preset.copy(id = UUID.randomUUID().toString())))
        }
        listOf("not-an-id", "1-1-1-1-1", preset.id.uppercase()).forEach { invalid ->
            if (invalid != preset.id) rejects(valid.replace(preset.id, invalid))
        }
    }

    @Test fun namesAreTrimmedAndUnicodeControlsAndBrokenSurrogatesAreRejected() {
        assertEquals("人声", AnalogPresetCodec.name("  人声  "))
        listOf("", " ", "名".repeat(41), "名字\n", "名字\u0000", "\uD800", "\uDC00")
            .forEach { name -> assertThrows(IllegalArgumentException::class.java) { AnalogPresetCodec.name(name) } }
        val complex = preset.copy(name = "[]{}\\\"音🎵效")
        assertEquals(complex, AnalogPresetCodec.decode(AnalogPresetCodec.encode(listOf(complex))).single())
        rejects(valid.replace("轻暖人声", "\\uD800"))
        rejects("[".repeat(10000) + "]".repeat(10000))
    }

    @Test fun inputHasStrictUtf8AndByteBoundsAndCallerRetainsStreamOwnership() {
        var closed = false
        val stream = object : ByteArrayInputStream(valid.toByteArray(Charsets.UTF_8)) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(valid, AnalogPresetCodec.read(stream)); assertFalse(closed)
        assertThrows(IOException::class.java) {
            AnalogPresetCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalogPresetCodec.read(ByteArrayInputStream(ByteArray(AnalogPresetCodec.MAX_BYTES + 1)))
        }
        val boundary = valid + " ".repeat(AnalogPresetCodec.MAX_BYTES - valid.toByteArray(Charsets.UTF_8).size)
        assertEquals(AnalogPresetCodec.MAX_BYTES, boundary.toByteArray(Charsets.UTF_8).size)
        assertEquals(preset, AnalogPresetCodec.decode(AnalogPresetCodec.read(
            ByteArrayInputStream(boundary.toByteArray(Charsets.UTF_8)))).single())
        rejects(valid + " ".repeat(AnalogPresetCodec.MAX_BYTES))
        val oversizedMultibyte = valid.dropLast(1) + ",\"extra\":\"" + "中".repeat(22000) + "\"}"
        assertTrue(oversizedMultibyte.length < AnalogPresetCodec.MAX_BYTES)
        assertTrue(rejects(oversizedMultibyte).message!!.contains("64 KiB"))
    }
}
