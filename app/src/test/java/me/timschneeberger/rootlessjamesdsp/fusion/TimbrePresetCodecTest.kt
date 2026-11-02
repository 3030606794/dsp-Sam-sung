package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

class TimbrePresetCodecTest {
    private val settings = TimbreParameters.Settings(
        professional = true, coreOn = true, drive = 73, amount = 617,
        band1 = 431, band2 = 937, band3 = 1237, band4 = 1731,
        toneOn = true, cut1 = 431, cut2 = 1373, cut3 = 3171, cut4 = 7317, cut5 = 13731,
        colorHP = 137, colorLP = 17317, dcOn = true, dcHz = 73,
        mixOn = true, mix = 317, outputOn = true, output = -29,
        compOn = true, comp = 731, qualityOn = true, factor = 4, meters = true,
    )
    private val preset = TimbrePreset(UUID.randomUUID().toString(), "轻暖人声", settings)
    private val valid get() = TimbrePresetCodec.encode(listOf(preset))
    private fun changed(vararg updates: Pair<String, JsonElement>): String {
        val root = Json.parseToJsonElement(valid).jsonObject
        val entry = root["presets"]!!.jsonArray.single().jsonObject
        val values = entry["settings"]!!.jsonObject
        val modified = JsonObject(entry + ("settings" to JsonObject(values + updates.toMap())))
        return JsonObject(root + ("presets" to JsonArray(listOf(modified)))).toString()
    }
    private fun rejects(json: String) = assertThrows(IllegalArgumentException::class.java) { TimbrePresetCodec.decode(json) }

    @Test fun completeSettingsRoundTripExcludesPowerLegacyAndTemporaryTools() {
        assertEquals(preset, TimbrePresetCodec.decode(valid).single())
        val values = Json.parseToJsonElement(valid).jsonObject["presets"]!!.jsonArray.single().jsonObject["settings"]!!.jsonObject
        assertEquals(TimbreParameters.newKeys, values.keys)
        assertFalse(values.containsKey(TimbreParameters.ENABLED)); assertFalse(values.containsKey(TimbreParameters.LEGACY))
        listOf("audition", "snapshot", "match", "measurement", "rms").forEach { word ->
            assertFalse(values.keys.any { it.contains(word) })
        }
        assertEquals(4, values[TimbreParameters.FACTOR]!!.jsonPrimitive.int)
        assertEquals(valid, TimbrePresetCodec.encode(TimbrePresetCodec.decode(valid)))
    }

    @Test fun originalAndInactiveSavedValuesRoundTripWithoutActivatingFeatures() {
        val defaults = TimbreParameters.defaults()
        assertTrue(defaults.valid()); assertEquals(0, defaults.drive)
        assertTrue(defaults.values().values.filterIsInstance<Boolean>().all { !it })
        val original = preset.copy(settings = defaults)
        assertEquals(original, TimbrePresetCodec.decode(TimbrePresetCodec.encode(listOf(original))).single())
        val inactive = preset.copy(settings = settings.copy(professional = false, coreOn = false, toneOn = false,
            dcOn = false, mixOn = false, outputOn = false, compOn = false, qualityOn = false, meters = false))
        assertEquals(inactive, TimbrePresetCodec.decode(TimbrePresetCodec.encode(listOf(inactive))).single())
    }

    @Test fun fiveDistinctFactoryDirectionsAreOptInAndDoNotChangeDefaults() {
        val schemes = TimbreSystemPresets.entries
        assertEquals(5, schemes.size); assertEquals(TimbreParameters.defaults(), schemes.first().second)
        assertEquals(5, schemes.map { it.second.packet().toList() }.toSet().size)
        schemes.drop(1).forEach { (_, value) ->
            assertTrue(value.valid()); assertTrue(value.professional); assertTrue(value.coreOn)
            assertTrue(value.toneOn); assertTrue(value.dcOn); assertTrue(value.mixOn); assertTrue(value.outputOn)
            assertTrue(value.compOn); assertTrue(value.qualityOn); assertFalse(value.meters)
        }
        val personal = schemes.map { (name, value) -> TimbrePreset(UUID.randomUUID().toString(), name, value) }
        assertEquals(personal, TimbrePresetCodec.decode(TimbrePresetCodec.encode(personal)))
        assertTrue(TimbreParameters.defaults().values().values.filterIsInstance<Boolean>().all { !it })
    }

    @Test fun wrongModulesVersionsUnitsUnknownAndMissingFieldsRejectTheWholeDocument() {
        val otherFormats = listOf(AnalogPresetCodec.FORMAT, TubePresetCodec.FORMAT, ReverbPresetCodec.FORMAT,
            RoomReverbPresetCodec.FORMAT, CrossfeedPresetCodec.FORMAT, PresetCrossfeedPresetCodec.FORMAT, SurroundPresetCodec.FORMAT)
        otherFormats.forEach { rejects(valid.replace(TimbrePresetCodec.FORMAT, it)) }
        listOf("", "[]", "{}", "not-json", valid.dropLast(1), valid + "{}",
            valid.replace("\"version\":2", "\"version\":3"),
            valid.replace("\"version\":2", "\"version\":\"2\""),
            valid.replace("tenth-dB", "dB"),
            valid.replace("\"presets\":", "\"unknown\":null,\"presets\":"),
            valid.replace("\"settings\":", "\"enabled\":true,\"settings\":"),
            changed(TimbreParameters.ENABLED to JsonPrimitive(true)),
            changed(TimbreParameters.LEGACY to JsonPrimitive(7.3)),
            changed("timbre_pro_unknown" to JsonPrimitive(1)),
            valid.replace("\"${TimbreParameters.drive.key}\":73,", "")
        ).forEach(::rejects)
        assertThrows(IllegalArgumentException::class.java) { AnalogPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { TubePresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { ReverbPresetCodec.decode(valid) }
        assertThrows(IllegalArgumentException::class.java) { RoomReverbPresetCodec.decode(valid) }
    }

    @Test fun duplicateFieldsIncludingEscapedIdentitiesNeverSilentlyOverwrite() {
        listOf(
            valid.replace("\"version\":2", "\"version\":2,\"version\":2"),
            valid.replace("\"version\":2", "\"version\":2,\"ver\\u0073ion\":2"),
            valid.replace("\"name\":\"轻暖人声\"", "\"name\":\"轻暖人声\",\"name\":\"另名\""),
            valid.replace("\"${TimbreParameters.drive.key}\":73", "\"${TimbreParameters.drive.key}\":1,\"${TimbreParameters.drive.key}\":73"),
            valid.replace("\"settings\":", "\"settings\":{},\"settings\":")
        ).forEach { document -> assertTrue(rejects(document).message!!.contains("重复字段")) }
    }

    @Test fun everyNumericBooleanAndFactorRejectsInvalidTypesAndBounds() {
        TimbreParameters.numbers.forEach { control ->
            listOf(JsonPrimitive(control.min - 1), JsonPrimitive(control.max + 1), JsonPrimitive(true),
                JsonPrimitive("${control.default}"), JsonPrimitive(1.5), JsonNull, JsonPrimitive(Long.MAX_VALUE))
                .forEach { rejects(changed(control.key to it)) }
        }
        settings.values().filterValues { it is Boolean }.keys.forEach { key ->
            listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive("true"), JsonNull).forEach { rejects(changed(key to it)) }
        }
        listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive(3), JsonPrimitive(5), JsonPrimitive(4.0),
            JsonPrimitive("4"), JsonPrimitive(true), JsonPrimitive(Long.MAX_VALUE), JsonNull)
            .forEach { rejects(changed(TimbreParameters.FACTOR to it)) }
        val key = TimbreParameters.drive.key
        listOf("NaN", "Infinity", "1e99", "73.0", "+73", "073", "--73", "0x49").forEach {
            rejects(valid.replace("\"$key\":73", "\"$key\":$it"))
        }
        assertThrows(IllegalArgumentException::class.java) { TimbrePresetCodec.encode(listOf(preset.copy(settings = settings.copy(drive = 121)))) }
        assertThrows(IllegalArgumentException::class.java) { TimbrePresetCodec.encode(listOf(preset.copy(settings = settings.copy(factor = 3)))) }
    }

    @Test fun unorderedOrCrowdedCrossoverPointsAreRejectedEvenWhenToneIsDisabled() {
        listOf(
            changed(TimbreParameters.cut2.key to JsonPrimitive(settings.cut1)),
            changed(TimbreParameters.cut2.key to JsonPrimitive(settings.cut1 - 1)),
            changed(TimbreParameters.cut2.key to JsonPrimitive(settings.cut1 + 49)),
            changed(TimbreParameters.cut4.key to JsonPrimitive(settings.cut5 + 50)),
            changed(TimbreParameters.TONE_ON to JsonPrimitive(false), TimbreParameters.cut3.key to JsonPrimitive(settings.cut4))
        ).forEach(::rejects)
        val touching = settings.copy(cut2 = settings.cut1 + 50)
        assertEquals(touching, TimbrePresetCodec.decode(TimbrePresetCodec.encode(listOf(preset.copy(settings = touching)))).single().settings)
    }

    @Test fun cutoffGapsAndReversedColorBandRejectWithoutSilentNormalization() {
        listOf(1, 19).forEach { rejects(changed(TimbreParameters.colorHP.key to JsonPrimitive(it))) }
        listOf(1, 999).forEach { rejects(changed(TimbreParameters.colorLP.key to JsonPrimitive(it))) }
        // Both values are in their individual ranges, but equality is not a valid active passband.
        rejects(changed(TimbreParameters.colorHP.key to JsonPrimitive(1000), TimbreParameters.colorLP.key to JsonPrimitive(1000)))
        rejects(changed(TimbreParameters.TONE_ON to JsonPrimitive(false), TimbreParameters.colorHP.key to JsonPrimitive(1000),
            TimbreParameters.colorLP.key to JsonPrimitive(1000)))
        listOf(settings.copy(colorHP = 0, colorLP = 0), settings.copy(colorHP = 20, colorLP = 1000)).forEach { value ->
            assertEquals(value, TimbrePresetCodec.decode(TimbrePresetCodec.encode(listOf(preset.copy(settings = value)))).single().settings)
        }
    }

    @Test fun entryLimitsCanonicalIdsAndUniqueNamesAreValidated() {
        val twenty = (1..20).map { preset.copy(id = UUID.randomUUID().toString(), name = "收藏 $it") }
        assertEquals(twenty, TimbrePresetCodec.decode(TimbrePresetCodec.encode(twenty)))
        assertThrows(IllegalArgumentException::class.java) { TimbrePresetCodec.encode(emptyList()) }
        assertEquals(emptyList<TimbrePreset>(), TimbrePresetCodec.decode(TimbrePresetCodec.encode(emptyList(), true), true))
        assertThrows(IllegalArgumentException::class.java) { TimbrePresetCodec.encode(twenty + preset) }
        assertThrows(IllegalArgumentException::class.java) { TimbrePresetCodec.encode(listOf(preset, preset.copy(name = "不同名称"))) }
        assertThrows(IllegalArgumentException::class.java) { TimbrePresetCodec.encode(listOf(preset, preset.copy(id = UUID.randomUUID().toString()))) }
        listOf("not-an-id", "1-1-1-1-1", preset.id.uppercase()).forEach { invalid -> if (invalid != preset.id) rejects(valid.replace(preset.id, invalid)) }
    }

    @Test fun namesAreTrimmedWhileControlsBrokenSurrogatesAndExcessiveNestingReject() {
        assertEquals("人声", TimbrePresetCodec.name("  人声  "))
        listOf("", " ", "名".repeat(41), "名字\n", "名字\u0000", "\uD800", "\uDC00")
            .forEach { name -> assertThrows(IllegalArgumentException::class.java) { TimbrePresetCodec.name(name) } }
        val complex = preset.copy(name = "[]{}\\\"音🎵效")
        assertEquals(complex, TimbrePresetCodec.decode(TimbrePresetCodec.encode(listOf(complex))).single())
        rejects(valid.replace("轻暖人声", "\\uD800")); rejects("[".repeat(10000) + "]".repeat(10000))
    }

    @Test fun versionOneDocumentsRemainReadableAndReexportAsVersionTwoWithoutChangingSettings() {
        val legacy = valid.replace("\"version\":2", "\"version\":1")
        val restored = TimbrePresetCodec.decode(legacy)
        assertEquals(listOf(preset), restored)
        val exported = TimbrePresetCodec.encode(restored)
        assertEquals(2, Json.parseToJsonElement(exported).jsonObject["version"]!!.jsonPrimitive.int)
        assertEquals(restored, TimbrePresetCodec.decode(exported))
        assertEquals(settings.values(), restored.single().settings.values())
    }

    @Test fun versionOneStrictlyRejectsExtendedAmountsEvenInInactiveGroupsOrAnotherEntry() {
        val boundary = preset.copy(settings = settings.copy(amount = 2000))
        assertEquals(boundary, TimbrePresetCodec.decode(TimbrePresetCodec.encode(listOf(boundary))
            .replace("\"version\":2", "\"version\":1")).single())
        for (amount in listOf(2001, 6000, 10000)) for (active in listOf(false, true)) {
            val extended = preset.copy(id = UUID.randomUUID().toString(), name = "高染色", settings =
                settings.copy(professional = active, coreOn = active, amount = amount))
            val old = TimbrePresetCodec.encode(listOf(preset, extended)).replace("\"version\":2", "\"version\":1")
            assertTrue(rejects(old).message!!.contains("200%"))
        }
    }

    @Test fun versionTwoRoundTripsFullExtendedRangeAndKeepsInactiveSavedValuesWithoutTools() {
        for (amount in listOf(2001, 6000, 10000)) for (active in listOf(false, true)) {
            val extended = preset.copy(settings = settings.copy(professional = active, coreOn = active, amount = amount))
            val document = TimbrePresetCodec.encode(listOf(extended))
            assertEquals(extended, TimbrePresetCodec.decode(document).single())
            assertEquals(2, Json.parseToJsonElement(document).jsonObject["version"]!!.jsonPrimitive.int)
            assertFalse(document.contains("soloGain")); assertFalse(document.contains("timbre_pro_solo"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimbrePresetCodec.encode(listOf(preset.copy(settings = settings.copy(amount = 10001))))
        }
    }

    @Test fun extendedFactoryIsAnAdditionalOptInDirectionAndOriginalThreePacketsStayUnchanged() {
        val expectedOriginal = listOf(
            intArrayOf(1,1,1,10,800,650,1100,850,400,1,600,1900,4400,8000,12000,100,14000,1,50,1,700,1,-5,1,1000,1,2,0,0,0,0,0),
            intArrayOf(1,1,1,20,900,1300,1200,650,300,1,500,1700,4000,7500,11500,70,12000,1,50,1,750,1,-10,1,1000,1,4,0,0,0,0,0),
            intArrayOf(1,1,1,0,600,250,500,900,1100,1,700,2200,4800,8500,12500,250,16000,1,50,1,600,1,0,1,1000,1,2,0,0,0,0,0),
        )
        expectedOriginal.zip(TimbreSystemPresets.entries.drop(1).take(3)).forEach { (packet, direction) ->
            assertArrayEquals(packet, direction.second.packet())
        }
        val extended = TimbreSystemPresets.entries.last()
        assertTrue(extended.first.startsWith("明显暖色"))
        assertArrayEquals(intArrayOf(1,1,1,60,6000,1000,1000,1000,1000,1,600,1900,4400,8000,12000,0,12000,1,50,1,850,1,-60,1,1000,1,4,0,0,0,0,0),
            extended.second.packet())
        assertEquals(1000, TimbreParameters.defaults().amount)
        assertFalse(TimbreParameters.defaults().professional)
    }

    @Test fun strictUtf8ByteLimitAndCallerStreamOwnershipAreEnforced() {
        var closed = false
        val stream = object : ByteArrayInputStream(valid.toByteArray(Charsets.UTF_8)) { override fun close() { closed = true; super.close() } }
        assertEquals(valid, TimbrePresetCodec.read(stream)); assertFalse(closed)
        assertThrows(IOException::class.java) { TimbrePresetCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertThrows(IllegalArgumentException::class.java) { TimbrePresetCodec.read(ByteArrayInputStream(ByteArray(TimbrePresetCodec.MAX_BYTES + 1))) }
        val boundary = valid + " ".repeat(TimbrePresetCodec.MAX_BYTES - valid.toByteArray(Charsets.UTF_8).size)
        assertEquals(TimbrePresetCodec.MAX_BYTES, boundary.toByteArray(Charsets.UTF_8).size)
        assertEquals(preset, TimbrePresetCodec.decode(TimbrePresetCodec.read(ByteArrayInputStream(boundary.toByteArray(Charsets.UTF_8)))).single())
        rejects(valid + " ".repeat(TimbrePresetCodec.MAX_BYTES))
        val oversized = valid.dropLast(1) + ",\"extra\":\"" + "中".repeat(22000) + "\"}"
        assertTrue(oversized.length < TimbrePresetCodec.MAX_BYTES); assertTrue(rejects(oversized).message!!.contains("64 KiB"))
    }
}
