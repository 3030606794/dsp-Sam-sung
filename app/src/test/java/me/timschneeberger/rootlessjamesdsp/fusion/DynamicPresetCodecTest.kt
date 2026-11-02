package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

class DynamicPresetCodecTest {
    private val settings = DynamicParameters.Settings(
        professional = true, base = 5, strength = 37, coreOn = true,
        bassDelta = 73, lowGainDelta = -217, highGainDelta = 731, injectDelta = 417, qDelta = -31,
        toneOn = true, xDelta = 137, lowFreqDelta = -17, highFreqDelta = 43, deepFreqDelta = 73,
        hp = 37, lp = 17317, adaptiveOn = true, detector = 0, detectorHP = 43, detectorLP = 731,
        threshold = -317, knee = 73, maxReduction = 117, attack = 731, release = 1373,
        mixOn = true, mix = 317, outputOn = true, output = -29, meters = true, response = true,
    )
    private val preset = DynamicPreset(UUID.randomUUID().toString(), "轻柔低频", settings)
    private val valid get() = DynamicPresetCodec.encode(listOf(preset))
    private fun changed(vararg updates: Pair<String, JsonElement>): String {
        val root = Json.parseToJsonElement(valid).jsonObject
        val entry = root["presets"]!!.jsonArray.single().jsonObject
        val values = entry["settings"]!!.jsonObject
        val modified = JsonObject(entry + ("settings" to JsonObject(values + updates.toMap())))
        return JsonObject(root + ("presets" to JsonArray(listOf(modified)))).toString()
    }
    private fun rejects(json: String) = assertThrows(IllegalArgumentException::class.java) { DynamicPresetCodec.decode(json) }

    @Test fun completeSettingsRoundTripIncludesFoundationButExcludesPowerAndTemporaryTools() {
        assertEquals(preset, DynamicPresetCodec.decode(valid).single())
        val values = Json.parseToJsonElement(valid).jsonObject["presets"]!!.jsonArray.single().jsonObject["settings"]!!.jsonObject
        assertEquals(DynamicParameters.keys, values.keys)
        assertFalse(values.containsKey(DynamicParameters.ENABLED))
        assertTrue(values[DynamicParameters.BASE]!!.jsonPrimitive.isString)
        assertEquals("5", values[DynamicParameters.BASE]!!.jsonPrimitive.content)
        assertFalse(values[DynamicParameters.LEGACY]!!.jsonPrimitive.isString)
        assertEquals(37, values[DynamicParameters.LEGACY]!!.jsonPrimitive.int)
        listOf("audition", "snapshot", "match", "measurement", "rms").forEach { word ->
            assertFalse(values.keys.any { it.contains(word) })
        }
        assertEquals(valid, DynamicPresetCodec.encode(DynamicPresetCodec.decode(valid)))
    }

    @Test fun allSixFoundationsAndInactiveValuesRoundTripWithoutActivatingFeatures() {
        val defaults = DynamicParameters.defaults()
        assertTrue(defaults.valid()); assertEquals(0, defaults.strength)
        assertTrue(defaults.values().values.filterIsInstance<Boolean>().all { !it })
        (0..5).forEach { base ->
            val original = preset.copy(settings = defaults.copy(base = base, strength = 53))
            assertEquals(original, DynamicPresetCodec.decode(DynamicPresetCodec.encode(listOf(original))).single())
        }
        val inactive = preset.copy(settings = settings.copy(professional = false, coreOn = false, toneOn = false,
            adaptiveOn = false, mixOn = false, outputOn = false, meters = false, response = false))
        assertEquals(inactive, DynamicPresetCodec.decode(DynamicPresetCodec.encode(listOf(inactive))).single())
    }

    @Test fun fourFactoryDirectionsAreOptInAndCoverBothAlgorithmsWithoutChangingDefaults() {
        val schemes = DynamicSystemPresets.entries
        assertEquals(4, schemes.size); assertEquals(DynamicParameters.defaults(), schemes.first().second)
        assertEquals(4, schemes.map { it.second.packet().toList() }.toSet().size)
        assertEquals(listOf(0, 0, 4, 2), schemes.map { it.second.base })
        val nativeMatrix = listOf(
            intArrayOf(1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,20,250,-180,60,60,100,250,0,1000,0,0,0,0,0,0,0,0),
            intArrayOf(1,1,0,12,1,-10,100,-20,0,0,1,40,0,20,0,20,500,1,1,25,220,-240,60,60,100,300,1,700,1,-20,0,0,0,0,0,0),
            intArrayOf(1,1,4,18,1,0,0,0,-100,-10,1,0,0,0,15,25,200,1,0,25,180,-180,30,80,30,180,1,650,1,-30,0,0,0,0,0,0),
            intArrayOf(1,1,2,15,1,0,150,0,0,0,1,0,0,15,0,20,650,1,1,20,250,-300,80,90,150,500,1,800,1,-30,0,0,0,0,0,0),
        )
        nativeMatrix.zip(schemes).forEach { (packet, direction) -> assertArrayEquals(packet, direction.second.packet()) }
        schemes.drop(1).forEach { (_, value) ->
            assertTrue(value.valid()); assertTrue(value.professional); assertTrue(value.coreOn)
            assertTrue(value.toneOn); assertTrue(value.adaptiveOn); assertTrue(value.mixOn); assertTrue(value.outputOn)
            assertFalse(value.meters); assertFalse(value.response); assertTrue(value.output < 0)
        }
        val personal = schemes.map { (name, value) -> DynamicPreset(UUID.randomUUID().toString(), name, value) }
        assertEquals(personal, DynamicPresetCodec.decode(DynamicPresetCodec.encode(personal)))
        assertTrue(DynamicParameters.defaults().values().values.filterIsInstance<Boolean>().all { !it })
    }

    @Test fun wrongModulesVersionsUnitsUnknownAndMissingFieldsRejectTheWholeDocument() {
        val otherFormats = listOf(TimbrePresetCodec.FORMAT, AnalogPresetCodec.FORMAT, TubePresetCodec.FORMAT,
            ReverbPresetCodec.FORMAT, RoomReverbPresetCodec.FORMAT, CrossfeedPresetCodec.FORMAT,
            PresetCrossfeedPresetCodec.FORMAT, SurroundPresetCodec.FORMAT)
        otherFormats.forEach { rejects(valid.replace(DynamicPresetCodec.FORMAT, it)) }
        listOf("", "[]", "{}", "not-json", valid.dropLast(1), valid + "{}",
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("tenth-dB", "dB"),
            valid.replace("\"presets\":", "\"unknown\":null,\"presets\":"),
            valid.replace("\"settings\":", "\"enabled\":true,\"settings\":"),
            changed(DynamicParameters.ENABLED to JsonPrimitive(true)),
            changed("dynamic_pro_unknown" to JsonPrimitive(1)),
            valid.replace("\"${DynamicParameters.bassDelta.key}\":73,", "")
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
            valid.replace("\"${DynamicParameters.bassDelta.key}\":73", "\"${DynamicParameters.bassDelta.key}\":1,\"${DynamicParameters.bassDelta.key}\":73"),
            valid.replace("\"settings\":", "\"settings\":{},\"settings\":"),
            valid.replace("\"${DynamicParameters.BASE}\":\"5\"", "\"${DynamicParameters.BASE}\":\"0\",\"${DynamicParameters.BASE}\":\"5\"")
        ).forEach { document -> assertTrue(rejects(document).message!!.contains("重复字段")) }
    }

    @Test fun everyNumericBooleanAndDetectorRejectsInvalidTypesAndBounds() {
        DynamicParameters.numbers.forEach { control ->
            listOf(JsonPrimitive(control.min - 1), JsonPrimitive(control.max + 1), JsonPrimitive(true),
                JsonPrimitive("${control.default}"), JsonPrimitive(1.5), JsonNull, JsonPrimitive(Long.MAX_VALUE))
                .forEach { rejects(changed(control.key to it)) }
        }
        settings.values().filterValues { it is Boolean }.keys.forEach { key ->
            listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive("true"), JsonNull).forEach { rejects(changed(key to it)) }
        }
        listOf(JsonPrimitive(-1), JsonPrimitive(2), JsonPrimitive(1.0), JsonPrimitive("1"), JsonPrimitive(true),
            JsonPrimitive(Long.MAX_VALUE), JsonNull).forEach { rejects(changed(DynamicParameters.DETECTOR to it)) }
        val key = DynamicParameters.bassDelta.key
        listOf("NaN", "Infinity", "1e99", "73.0", "+73", "073", "--73", "0x49").forEach {
            rejects(valid.replace("\"$key\":73", "\"$key\":$it"))
        }
        assertThrows(IllegalArgumentException::class.java) { DynamicPresetCodec.encode(listOf(preset.copy(settings = settings.copy(bassDelta = 121)))) }
        assertThrows(IllegalArgumentException::class.java) { DynamicPresetCodec.encode(listOf(preset.copy(settings = settings.copy(detector = 2)))) }
    }

    @Test fun canonicalStringFoundationAndIntegerStrengthAreValidatedWithoutCoercion() {
        listOf(JsonPrimitive(5), JsonPrimitive(true), JsonPrimitive("6"), JsonPrimitive("-1"), JsonPrimitive("05"),
            JsonPrimitive("+5"), JsonPrimitive("5.0"), JsonPrimitive(" 5"), JsonNull)
            .forEach { rejects(changed(DynamicParameters.BASE to it)) }
        listOf(JsonPrimitive(-1), JsonPrimitive(101), JsonPrimitive(37.0), JsonPrimitive("37"), JsonPrimitive(true), JsonNull)
            .forEach { rejects(changed(DynamicParameters.LEGACY to it)) }
        assertThrows(IllegalArgumentException::class.java) { DynamicPresetCodec.encode(listOf(preset.copy(settings = settings.copy(base = 6)))) }
        assertThrows(IllegalArgumentException::class.java) { DynamicPresetCodec.encode(listOf(preset.copy(settings = settings.copy(strength = 101)))) }
    }

    @Test fun cutoffGapsAndReversedAudioOrDetectorBandsRejectEvenWhenGroupsAreOff() {
        listOf(1, 9).forEach { rejects(changed(DynamicParameters.hp.key to JsonPrimitive(it))) }
        listOf(1, 99).forEach { rejects(changed(DynamicParameters.lp.key to JsonPrimitive(it))) }
        listOf(true, false).forEach { active ->
            rejects(changed(DynamicParameters.TONE_ON to JsonPrimitive(active), DynamicParameters.hp.key to JsonPrimitive(300),
                DynamicParameters.lp.key to JsonPrimitive(300)))
            rejects(changed(DynamicParameters.ADAPTIVE_ON to JsonPrimitive(active), DynamicParameters.detectorHP.key to JsonPrimitive(500),
                DynamicParameters.detectorLP.key to JsonPrimitive(500)))
        }
        listOf(settings.copy(hp = 0, lp = 0), settings.copy(hp = 10, lp = 100, detectorHP = 20, detectorLP = 60)).forEach { value ->
            assertEquals(value, DynamicPresetCodec.decode(DynamicPresetCodec.encode(listOf(preset.copy(settings = value)))).single().settings)
        }
    }

    @Test fun changingFoundationRetainsOffsetsForNativeSafeLimitsInsteadOfRejectingStoredSettings() {
        val offsets = settings.copy(base = 0, lowFreqDelta = 100, highFreqDelta = -100)
        assertTrue(offsets.valid())
        (0..5).forEach { base ->
            val value = offsets.copy(base = base)
            val restored = DynamicPresetCodec.decode(DynamicPresetCodec.encode(listOf(preset.copy(settings = value)))).single().settings
            assertEquals(value, restored)
            assertEquals(100, restored.lowFreqDelta); assertEquals(-100, restored.highFreqDelta)
        }
    }

    @Test fun entryLimitsCanonicalIdsAndUniqueNamesAreValidated() {
        val twenty = (1..20).map { preset.copy(id = UUID.randomUUID().toString(), name = "收藏 $it") }
        assertEquals(twenty, DynamicPresetCodec.decode(DynamicPresetCodec.encode(twenty)))
        assertThrows(IllegalArgumentException::class.java) { DynamicPresetCodec.encode(emptyList()) }
        assertEquals(emptyList<DynamicPreset>(), DynamicPresetCodec.decode(DynamicPresetCodec.encode(emptyList(), true), true))
        assertThrows(IllegalArgumentException::class.java) { DynamicPresetCodec.encode(twenty + preset) }
        assertThrows(IllegalArgumentException::class.java) { DynamicPresetCodec.encode(listOf(preset, preset.copy(name = "不同名称"))) }
        assertThrows(IllegalArgumentException::class.java) { DynamicPresetCodec.encode(listOf(preset, preset.copy(id = UUID.randomUUID().toString()))) }
        listOf("not-an-id", "1-1-1-1-1", preset.id.uppercase()).forEach { invalid -> if (invalid != preset.id) rejects(valid.replace(preset.id, invalid)) }
    }

    @Test fun namesAreTrimmedWhileControlsBrokenSurrogatesAndExcessiveNestingReject() {
        assertEquals("低频", DynamicPresetCodec.name("  低频  "))
        listOf("", " ", "名".repeat(41), "名字\n", "名字\u0000", "\uD800", "\uDC00")
            .forEach { name -> assertThrows(IllegalArgumentException::class.java) { DynamicPresetCodec.name(name) } }
        val complex = preset.copy(name = "[]{}\\\"音🎵效")
        assertEquals(complex, DynamicPresetCodec.decode(DynamicPresetCodec.encode(listOf(complex))).single())
        rejects(valid.replace("轻柔低频", "\\uD800")); rejects("[".repeat(10000) + "]".repeat(10000))
    }

    @Test fun strictUtf8ByteLimitAndCallerStreamOwnershipAreEnforced() {
        var closed = false
        val stream = object : ByteArrayInputStream(valid.toByteArray(Charsets.UTF_8)) { override fun close() { closed = true; super.close() } }
        assertEquals(valid, DynamicPresetCodec.read(stream)); assertFalse(closed)
        assertThrows(IOException::class.java) { DynamicPresetCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertThrows(IllegalArgumentException::class.java) { DynamicPresetCodec.read(ByteArrayInputStream(ByteArray(DynamicPresetCodec.MAX_BYTES + 1))) }
        val boundary = valid + " ".repeat(DynamicPresetCodec.MAX_BYTES - valid.toByteArray(Charsets.UTF_8).size)
        assertEquals(DynamicPresetCodec.MAX_BYTES, boundary.toByteArray(Charsets.UTF_8).size)
        assertEquals(preset, DynamicPresetCodec.decode(DynamicPresetCodec.read(ByteArrayInputStream(boundary.toByteArray(Charsets.UTF_8)))).single())
        rejects(valid + " ".repeat(DynamicPresetCodec.MAX_BYTES))
        val oversized = valid.dropLast(1) + ",\"extra\":\"" + "中".repeat(22000) + "\"}"
        assertTrue(oversized.length < DynamicPresetCodec.MAX_BYTES); assertTrue(rejects(oversized).message!!.contains("64 KiB"))
    }
}
