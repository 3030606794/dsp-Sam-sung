package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

class TubePresetCodecTest {
    private val settings = TubeParameters.Settings(
        professional = true, satOn = true, drive = 137, softness = 617, asym = -213,
        toneOn = true, softenMix = 731, softenCut = 8431, preHP = 137, postLP = 11753,
        mixOn = true, mix = 317, outputOn = true, output = -29,
        qualityOn = true, factor = 4, meters = true,
    )
    private val preset = TubePreset(UUID.randomUUID().toString(), "轻暖人声", settings)
    private val valid get() = TubePresetCodec.encode(listOf(preset))
    private fun changed(key: String, value: JsonElement): String {
        val root = Json.parseToJsonElement(valid).jsonObject
        val entry = root["presets"]!!.jsonArray.single().jsonObject
        val values = entry["settings"]!!.jsonObject
        val modified = JsonObject(entry + ("settings" to JsonObject(values + (key to value))))
        return JsonObject(root + ("presets" to JsonArray(listOf(modified)))).toString()
    }
    private fun rejects(json: String) = assertThrows(IllegalArgumentException::class.java) {
        TubePresetCodec.decode(json)
    }

    @Test fun completeSettingsRoundTripWithoutPowerOrTemporaryState() {
        assertEquals(preset, TubePresetCodec.decode(valid).single())
        val values = Json.parseToJsonElement(valid).jsonObject["presets"]!!.jsonArray.single()
            .jsonObject["settings"]!!.jsonObject
        assertEquals(settings.values().keys, values.keys)
        assertFalse(values.containsKey(TubeParameters.ENABLED))
        listOf("audition", "snapshot", "match", "measurement", "peak", "rms").forEach { word ->
            assertFalse(values.keys.any { it.contains(word) })
        }
        assertEquals(settings.values().keys, TubeParameters.newKeys)
        assertEquals(4, values[TubeParameters.FACTOR]!!.jsonPrimitive.int)
        assertEquals(valid, TubePresetCodec.encode(TubePresetCodec.decode(valid)))
    }

    @Test fun originalDefaultsAndInactiveSettingsRoundTripWithEveryNewFeatureOff() {
        val defaults = TubeParameters.defaults()
        assertTrue(defaults.valid())
        assertTrue(defaults.values().values.filterIsInstance<Boolean>().all { !it })
        val original = preset.copy(settings = defaults)
        assertEquals(original, TubePresetCodec.decode(TubePresetCodec.encode(listOf(original))).single())
        val inactive = preset.copy(settings = settings.copy(professional = false, satOn = false, toneOn = false,
            mixOn = false, outputOn = false, qualityOn = false, meters = false))
        assertEquals(inactive, TubePresetCodec.decode(TubePresetCodec.encode(listOf(inactive))).single())
        assertEquals(TubeParameters.defaults(), TubeSystemPresets.entries.first().second)
    }

    @Test fun systemSoundSchemesAreDistinctValidSettingsAndNeverBecomeInitialDefaults() {
        val schemes = TubeSystemPresets.entries
        assertEquals(4, schemes.size)
        assertEquals(TubeParameters.defaults(), schemes.first().second)
        assertFalse(schemes.first().second.professional)
        assertEquals(4, schemes.map { it.second.packet().toList() }.toSet().size)
        schemes.drop(1).forEach { (_, value) ->
            assertTrue(value.valid()); assertTrue(value.professional); assertTrue(value.satOn)
            assertTrue(value.toneOn); assertTrue(value.mixOn); assertTrue(value.outputOn)
            assertTrue(value.qualityOn); assertFalse(value.meters)
        }
        val personal = schemes.map { (name, value) -> TubePreset(UUID.randomUUID().toString(), name, value) }
        assertEquals(personal, TubePresetCodec.decode(TubePresetCodec.encode(personal)))
        assertTrue(TubeParameters.defaults().values().values.filterIsInstance<Boolean>().all { !it })
    }

    @Test fun wrongModulesVersionsUnitsUnknownAndMissingFieldsAreRejected() {
        listOf("", "[]", "{}", "not-json", valid.dropLast(1), valid + "{}",
            valid.replace(TubePresetCodec.FORMAT, ReverbPresetCodec.FORMAT),
            valid.replace(TubePresetCodec.FORMAT, RoomReverbPresetCodec.FORMAT),
            valid.replace(TubePresetCodec.FORMAT, CrossfeedPresetCodec.FORMAT),
            valid.replace(TubePresetCodec.FORMAT, PresetCrossfeedPresetCodec.FORMAT),
            valid.replace(TubePresetCodec.FORMAT, SurroundPresetCodec.FORMAT),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("tenth-dB", "dB"),
            valid.replace("\"presets\":", "\"unknown\":null,\"presets\":"),
            valid.replace("\"settings\":", "\"enabled\":true,\"settings\":"),
            changed(TubeParameters.ENABLED, JsonPrimitive(true)),
            changed("v_tube_pro_unknown", JsonPrimitive(1)),
            valid.replace("\"${TubeParameters.drive.key}\":137,", "")
        ).forEach(::rejects)
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
            valid.replace("\"${TubeParameters.drive.key}\":137", "\"${TubeParameters.drive.key}\":1,\"${TubeParameters.drive.key}\":137"),
            valid.replace("\"settings\":", "\"settings\":{},\"settings\":")
        ).forEach { document -> assertTrue(rejects(document).message!!.contains("重复字段")) }
    }

    @Test fun allNumericAndBooleanValuesRejectInvalidTypesAndBounds() {
        TubeParameters.numbers.forEach { control ->
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
            .forEach { rejects(changed(TubeParameters.FACTOR, it)) }
        for (control in listOf(TubeParameters.softenCut, TubeParameters.preHP, TubeParameters.postLP)) {
            for (invalid in listOf(1, TubeParameters.minimumFrequency(control) - 1))
                rejects(changed(control.key, JsonPrimitive(invalid)))
        }
        val numericKey = TubeParameters.drive.key
        listOf("NaN", "Infinity", "1e99", "137.0", "+137", "0137", "--137", "0x89").forEach {
            rejects(valid.replace("\"$numericKey\":137", "\"$numericKey\":$it"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TubePresetCodec.encode(listOf(preset.copy(settings = settings.copy(drive = 181))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TubePresetCodec.encode(listOf(preset.copy(settings = settings.copy(factor = 3))))
        }
    }

    @Test fun entryLimitsCanonicalIdsAndUniqueNamesAreValidated() {
        val twenty = (1..20).map { preset.copy(id = UUID.randomUUID().toString(), name = "收藏 $it") }
        assertEquals(twenty, TubePresetCodec.decode(TubePresetCodec.encode(twenty)))
        assertThrows(IllegalArgumentException::class.java) { TubePresetCodec.encode(emptyList()) }
        assertEquals(emptyList<TubePreset>(), TubePresetCodec.decode(TubePresetCodec.encode(emptyList(), true), true))
        assertThrows(IllegalArgumentException::class.java) { TubePresetCodec.encode(twenty + preset) }
        assertThrows(IllegalArgumentException::class.java) {
            TubePresetCodec.encode(listOf(preset, preset.copy(name = "不同名称")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TubePresetCodec.encode(listOf(preset, preset.copy(id = UUID.randomUUID().toString())))
        }
        listOf("not-an-id", "1-1-1-1-1", preset.id.uppercase()).forEach { invalid ->
            if (invalid != preset.id) rejects(valid.replace(preset.id, invalid))
        }
    }

    @Test fun namesAreTrimmedAndUnicodeControlsAndBrokenSurrogatesAreRejected() {
        assertEquals("人声", TubePresetCodec.name("  人声  "))
        listOf("", " ", "名".repeat(41), "名字\n", "名字\u0000", "\uD800", "\uDC00")
            .forEach { name -> assertThrows(IllegalArgumentException::class.java) { TubePresetCodec.name(name) } }
        val complex = preset.copy(name = "[]{}\\\"音🎵效")
        assertEquals(complex, TubePresetCodec.decode(TubePresetCodec.encode(listOf(complex))).single())
        rejects(valid.replace("轻暖人声", "\\uD800"))
        rejects("[".repeat(10000) + "]".repeat(10000))
    }

    @Test fun inputHasStrictUtf8AndByteBoundsAndCallerRetainsStreamOwnership() {
        var closed = false
        val stream = object : ByteArrayInputStream(valid.toByteArray(Charsets.UTF_8)) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(valid, TubePresetCodec.read(stream)); assertFalse(closed)
        assertThrows(IOException::class.java) {
            TubePresetCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TubePresetCodec.read(ByteArrayInputStream(ByteArray(TubePresetCodec.MAX_BYTES + 1)))
        }
        val boundary = valid + " ".repeat(TubePresetCodec.MAX_BYTES - valid.toByteArray(Charsets.UTF_8).size)
        assertEquals(TubePresetCodec.MAX_BYTES, boundary.toByteArray(Charsets.UTF_8).size)
        assertEquals(preset, TubePresetCodec.decode(TubePresetCodec.read(
            ByteArrayInputStream(boundary.toByteArray(Charsets.UTF_8)))).single())
        rejects(valid + " ".repeat(TubePresetCodec.MAX_BYTES))
        val oversizedMultibyte = valid.dropLast(1) + ",\"extra\":\"" + "中".repeat(22000) + "\"}"
        assertTrue(oversizedMultibyte.length < TubePresetCodec.MAX_BYTES)
        assertTrue(rejects(oversizedMultibyte).message!!.contains("64 KiB"))
    }
}
