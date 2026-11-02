package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

internal object DdcTestFixture {
    const val TEXT = "SR_44100:1,0,0,0,0\nSR_48000:1,0,0,0,0\n"
    val foundation get() = DdcFoundation.from("DDC/原校正.vdc", "原校正", DdcVdc.parse(TEXT))
    val settings get() = DdcParameters.defaults().copy(professional = true, ratioOn = true, ratio = 731,
        compensationOn = true, lowOn = true, lowFrequency = 173, lowGain = 17, lowSlope = 731,
        highOn = true, highFrequency = 12317, highGain = -13, highSlope = 831,
        peak1On = true, peak1Frequency = 1731, peak1Gain = 23, peak1Q = 173,
        peak2On = true, peak2Frequency = 5731, peak2Gain = -37, peak2Q = 317,
        earOn = true, linked = false, leftRatio = 831, rightRatio = 631, leftLevel = -7, rightLevel = -13,
        preampOn = true, preamp = -37, autoHeadroomOn = true, margin = 17, outputOn = true, output = -13,
        meters = true, response = true)
    val preset get() = DdcPreset("12345678-1234-4234-8234-123456789abc", "温暖自然", foundation, settings)
}

class DdcPresetCodecTest {
    private val preset get() = DdcTestFixture.preset
    private val valid get() = DdcPresetCodec.encode(listOf(preset))
    private fun changed(vararg updates: Pair<String, JsonElement>): String {
        val root = Json.parseToJsonElement(valid).jsonObject
        val entry = root["presets"]!!.jsonArray.single().jsonObject
        val values = entry["settings"]!!.jsonObject
        return JsonObject(root + ("presets" to JsonArray(listOf(JsonObject(entry +
            ("settings" to JsonObject(values + updates.toMap()))))))).toString()
    }
    private fun foundationChanged(vararg updates: Pair<String, JsonElement>): String {
        val root = Json.parseToJsonElement(valid).jsonObject
        val entry = root["presets"]!!.jsonArray.single().jsonObject
        return JsonObject(root + ("presets" to JsonArray(listOf(JsonObject(entry +
            ("foundation" to JsonObject(entry["foundation"]!!.jsonObject + updates.toMap()))))))).toString()
    }
    private fun rejects(document: String) = assertThrows(IllegalArgumentException::class.java) { DdcPresetCodec.decode(document) }

    @Test fun completeRoundTripEmbedsOriginalVdcBytesAndIdentityWithoutPowerOrTools() {
        assertEquals(preset, DdcPresetCodec.decode(valid).single())
        assertEquals(valid, DdcPresetCodec.encode(DdcPresetCodec.decode(valid)))
        val entry = Json.parseToJsonElement(valid).jsonObject["presets"]!!.jsonArray.single().jsonObject
        assertEquals(DdcParameters.newKeys, entry["settings"]!!.jsonObject.keys)
        assertEquals(preset.foundation.text, entry["foundation"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(preset.foundation.sha256, entry["foundation"]!!.jsonObject["sha256"]!!.jsonPrimitive.content)
        listOf("ddc_enable", "ddc_file", "audition", "snapshot", "measurement", "capabilities", "session").forEach { key ->
            assertFalse(entry["settings"]!!.jsonObject.containsKey(key))
        }
    }

    @Test fun allTwentyNumericControlsAndDormantEndpointsRoundTripExactly() {
        assertEquals(20, DdcParameters.numbers.size)
        DdcParameters.numbers.forEach { control ->
            listOf(control.min, control.default, control.max).forEach { value ->
                val values = DdcParameters.defaults().values() + (control.key to value)
                val settings = DdcParameters.capture(values)
                assertEquals(values, settings.values())
                assertEquals(settings, DdcPresetCodec.decode(DdcPresetCodec.encode(listOf(preset.copy(settings = settings)))).single().settings)
            }
        }
        assertTrue(DdcParameters.defaults().values().filterValues { it is Boolean }.all { (key, value) -> value == (key == DdcParameters.LINKED) })
    }

    @Test fun sixFactoryDirectionsKeepCurrentVdcAndLeaveDiagnosticsOff() {
        val options = DdcSystemPresets.entries(DdcTestFixture.settings)
        assertEquals(6, options.size); assertEquals(DdcParameters.defaults(), options.first().second)
        assertTrue(options.drop(1).all { it.second.professional })
        options.forEach { (_, settings) ->
            assertTrue(settings.valid()); assertFalse(settings.meters); assertFalse(settings.response)
            assertFalse(settings.values().keys.any { it == DdcParameters.FILE || it == DdcParameters.ENABLED })
        }
        val presets = options.map { (name, settings) -> preset.copy(id = UUID.randomUUID().toString(), name = name, settings = settings) }
        assertEquals(presets, DdcPresetCodec.decode(DdcPresetCodec.encode(presets)))
        assertTrue(DdcPresetCodec.decode(DdcPresetCodec.encode(presets)).all { it.foundation == preset.foundation })
        val expected = listOf(
            intArrayOf(1,0,0,0,1000,0,0,120,0,1000,0,8000,0,1000,0,1000,0,100,0,6000,0,100,0,1,1000,1000,0,0,0,0,0,0,0,10,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(1,1,0,1,750,0,0,120,0,1000,0,8000,0,1000,0,1000,0,100,0,6000,0,100,0,1,1000,1000,0,0,0,0,0,0,0,10,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(1,1,0,0,1000,1,1,120,15,1000,1,8000,-10,1000,0,1000,0,100,0,6000,0,100,0,1,1000,1000,0,0,0,0,0,0,1,10,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(1,1,0,0,1000,1,0,120,0,1000,1,10000,8,1000,1,1800,10,80,0,6000,0,100,0,1,1000,1000,0,0,0,0,0,0,1,10,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(1,1,0,0,1000,1,0,120,0,1000,0,8000,0,1000,0,1000,0,100,1,6000,-15,100,0,1,1000,1000,0,0,0,0,0,0,0,10,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(1,1,0,0,1000,0,0,120,0,1000,0,8000,0,1000,0,1000,0,100,0,6000,0,100,1,1,1000,1000,0,0,0,0,0,0,0,10,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
        )
        expected.zip(options).forEach { (packet, direction) -> assertArrayEquals(packet, direction.second.packet()) }
        assertEquals(750, options[1].second.ratio)
        assertTrue(options.last().second.earOn); assertTrue(options.last().second.linked)
    }

    @Test fun wrongModuleVersionUnitsShapeUnknownFieldsAndMissingFieldsRejectWholeDocument() {
        listOf(ToneAdvancedPresetCodec.FORMAT, CompanderPresetCodec.FORMAT, DynamicPresetCodec.FORMAT,
            TimbrePresetCodec.FORMAT, AnalogPresetCodec.FORMAT, TubePresetCodec.FORMAT,
            ReverbPresetCodec.FORMAT, RoomReverbPresetCodec.FORMAT).forEach { rejects(valid.replace(DdcPresetCodec.FORMAT, it)) }
        listOf("", "[]", "{}", "null", "broken", valid.dropLast(1), valid + "{}",
            valid.replace("\"version\":1", "\"version\":2"), valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("tenth-dB", "dB"), valid.replace("\"presets\":", "\"unknown\":0,\"presets\":"),
            valid.replace("\"settings\":", "\"enabled\":true,\"settings\":"),
            changed(DdcParameters.ENABLED to JsonPrimitive(true)), changed(DdcParameters.FILE to JsonPrimitive("test.vdc")),
            changed("ddc_pro_unknown" to JsonPrimitive(0)),
            valid.replace("\"${DdcParameters.PROFESSIONAL}\":true,", "")
        ).forEach(::rejects)
        assertThrows(IllegalArgumentException::class.java) { ToneAdvancedPresetCodec.decode(valid) }
    }

    @Test fun duplicateEscapedFieldsAreRejectedAtEveryDocumentLevel() {
        listOf(valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            valid.replace("\"name\":\"温暖自然\"", "\"name\":\"温暖自然\",\"name\":\"其他\""),
            valid.replace("\"settings\":", "\"settings\":{},\"settings\":"),
            valid.replace("\"sha256\":", "\"sha256\":\"0\",\"sha256\":"),
            valid.replace("\"${DdcParameters.ratio.key}\":731", "\"${DdcParameters.ratio.key}\":0,\"${DdcParameters.ratio.key}\":731")
        ).forEach { assertTrue(rejects(it).message!!.contains("重复字段")) }
    }

    @Test fun dormantIntegerAndBooleanValuesRejectCoercionFractionsOverflowAndNull() {
        DdcParameters.numbers.forEach { control ->
            listOf(JsonPrimitive(control.min - 1), JsonPrimitive(control.max + 1), JsonPrimitive(Int.MAX_VALUE),
                JsonPrimitive(Long.MAX_VALUE), JsonPrimitive(true), JsonPrimitive("0"), JsonPrimitive(1.5), JsonNull).forEach {
                rejects(changed(DdcParameters.PROFESSIONAL to JsonPrimitive(false), control.key to it))
            }
        }
        DdcParameters.switches.forEach { key ->
            listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive("false"), JsonNull).forEach { rejects(changed(key to it)) }
        }
        listOf("NaN", "Infinity", "+0", "00", "--1", "0x1", "1e100").forEach { token ->
            rejects(valid.replace("\"${DdcParameters.ratio.key}\":731", "\"${DdcParameters.ratio.key}\":$token"))
        }
    }

    @Test fun embeddedFoundationRequiresExactShaValidCoefficientsAndStrictMetadata() {
        listOf("0".repeat(64), preset.foundation.sha256.uppercase(), "not-a-hash", "").forEach { rejects(foundationChanged("sha256" to JsonPrimitive(it))) }
        listOf("", DdcTestFixture.TEXT + " ", DdcTestFixture.TEXT.replace("1,0,0", "NaN,0,0"),
            DdcTestFixture.TEXT.replace("1,0,0,0,0", "1,0,0,1,0")).forEach { rejects(foundationChanged("text" to JsonPrimitive(it))) }
        listOf(JsonPrimitive(0), JsonPrimitive(true), JsonNull).forEach { rejects(foundationChanged("text" to it)) }
        rejects(foundationChanged("unknown" to JsonPrimitive("value")))
        rejects(foundationChanged("path" to JsonPrimitive("bad\npath")))
        rejects(foundationChanged("label" to JsonPrimitive("")))
        val bom = DdcFoundation.from("DDC/bom.vdc", "含BOM", DdcVdc.parse("\uFEFF" + DdcTestFixture.TEXT))
        assertEquals(bom, DdcPresetCodec.decode(DdcPresetCodec.encode(listOf(preset.copy(foundation = bom)))).single().foundation)
    }

    @Test fun entryCountsUuidAndUniqueNamesAreStrict() {
        val twenty = (1..20).map { preset.copy(id = UUID.randomUUID().toString(), name = "收藏 $it") }
        assertEquals(twenty, DdcPresetCodec.decode(DdcPresetCodec.encode(twenty)))
        assertThrows(IllegalArgumentException::class.java) { DdcPresetCodec.encode(emptyList()) }
        assertTrue(DdcPresetCodec.decode(DdcPresetCodec.encode(emptyList(), true), true).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { DdcPresetCodec.encode(twenty + preset) }
        assertThrows(IllegalArgumentException::class.java) { DdcPresetCodec.encode(listOf(preset, preset.copy(name = "另一份"))) }
        assertThrows(IllegalArgumentException::class.java) { DdcPresetCodec.encode(listOf(preset, preset.copy(id = UUID.randomUUID().toString()))) }
        listOf("not-an-id", "1-1-1-1-1", preset.id.uppercase()).forEach { rejects(valid.replace(preset.id, it)) }
    }

    @Test fun namesAndNestedStructuresRejectMalformedUnicodeAndControls() {
        assertEquals("温暖", DdcPresetCodec.name("  温暖  "))
        listOf("", " ", "字".repeat(41), "换行\n", "名字\u0000", "\uD800", "\uDC00").forEach {
            assertThrows(IllegalArgumentException::class.java) { DdcPresetCodec.name(it) }
        }
        val special = preset.copy(name = "[]{}\\\"音🎵效")
        assertEquals(special, DdcPresetCodec.decode(DdcPresetCodec.encode(listOf(special))).single())
        rejects(valid.replace("温暖自然", "\\uD800")); rejects("[".repeat(10000) + "]".repeat(10000))
    }

    @Test fun strictUtf8BoundedReadingAndCallerStreamOwnershipAreEnforced() {
        var closed = false
        val input = object : ByteArrayInputStream(valid.toByteArray(Charsets.UTF_8)) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(valid, DdcPresetCodec.read(input)); assertFalse(closed)
        assertThrows(IOException::class.java) { DdcPresetCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertThrows(IllegalArgumentException::class.java) { DdcPresetCodec.read(ByteArrayInputStream(ByteArray(DdcPresetCodec.MAX_BYTES + 1))) }
        val boundary = valid + " ".repeat(DdcPresetCodec.MAX_BYTES - valid.toByteArray(Charsets.UTF_8).size)
        assertEquals(preset, DdcPresetCodec.decode(boundary).single()); rejects(boundary + " ")
    }

    @Test fun dormantIndependentRightValuesSurviveLinkedAndDisabledFavorites() {
        val settings = DdcTestFixture.settings.copy(professional = false, earOn = false, linked = true,
            leftRatio = 917, rightRatio = 317, leftLevel = -7, rightLevel = 31)
        val restored = DdcPresetCodec.decode(DdcPresetCodec.encode(listOf(preset.copy(settings = settings)))).single().settings
        assertEquals(settings, restored); assertEquals(317, restored.rightRatio); assertEquals(31, restored.rightLevel)
        assertFalse(restored.professional); assertFalse(restored.earOn)
    }

    @Test fun favoriteCannotSilentlyNormalizeAnInvalidClosedParameter() {
        val invalid = DdcParameters.defaults().copy(professional = false, compensationOn = false, lowGain = 121)
        assertThrows(IllegalArgumentException::class.java) { DdcPresetCodec.encode(listOf(preset.copy(settings = invalid))) }
        rejects(changed(DdcParameters.PROFESSIONAL to JsonPrimitive(false), DdcParameters.COMPENSATION_ON to JsonPrimitive(false),
            DdcParameters.lowGain.key to JsonPrimitive(121)))
        assertEquals(preset.foundation, DdcPresetCodec.decode(valid).single().foundation)
    }
}
