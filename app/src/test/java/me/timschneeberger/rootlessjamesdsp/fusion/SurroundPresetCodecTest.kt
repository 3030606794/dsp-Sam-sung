package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

class SurroundPresetCodecTest {
    private val settings = SurroundController.Settings(317, 13851, 8753, 97)
    private val preset = SurroundPreset(UUID.randomUUID().toString(), "我的扬声器", settings)
    private val valid = SurroundPresetCodec.encode(listOf(preset))

    @Test fun portableRoundTripPreservesAllFourUnitsButNotLocalIdsOrEffectSwitches() {
        val twenty = (1..20).map { preset.copy(id = UUID.randomUUID().toString(), name = "配置 $it") }
        val encoded = SurroundPresetCodec.encode(twenty)
        val restored = SurroundPresetCodec.decode(encoded)
        assertEquals(twenty.map { it.name }, restored.map { it.name })
        assertEquals(twenty.map { it.settings }, restored.map { it.settings })
        assertEquals(20, restored.map { it.id }.toSet().size)
        assertTrue(restored.map { it.id }.toSet().intersect(twenty.map { it.id }.toSet()).isEmpty())
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(SurroundPresetCodec.FORMAT, root["format"]!!.jsonPrimitive.content)
        assertEquals("milli-dB", root["units"]!!.jsonObject["attenuation"]!!.jsonPrimitive.content)
        val entry = root["presets"]!!.jsonArray[0].jsonObject
        assertFalse(entry.containsKey("id"))
        assertEquals(setOf("lowHz", "highHz", "attenuationMilliDb", "delayUs"), entry["settings"]!!.jsonObject.keys)
        assertEquals(settings, SurroundPresetCodec.decode(valid).single().settings)
    }

    @Test fun wrongSchemasUnitsTypesAndOutOfRangeNumbersRejectRatherThanClamp() {
        val invalid = listOf("", "[]", "{}", "not-json",
            valid.replace(SurroundPresetCodec.FORMAT, "dsp-audio-peq-presets"),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"Hz\"", "\"kHz\""),
            valid.replace("\"milli-dB\"", "\"dB\""),
            valid.replace("\"us\"", "\"ms\""),
            valid.replace("\"lowHz\":317", "\"lowHz\":9"),
            valid.replace("\"lowHz\":317", "\"lowHz\":801"),
            valid.replace("\"highHz\":13851", "\"highHz\":3999"),
            valid.replace("\"highHz\":13851", "\"highHz\":16001"),
            valid.replace("\"attenuationMilliDb\":8753", "\"attenuationMilliDb\":499"),
            valid.replace("\"attenuationMilliDb\":8753", "\"attenuationMilliDb\":10001"),
            valid.replace("\"delayUs\":97", "\"delayUs\":19"),
            valid.replace("\"delayUs\":97", "\"delayUs\":211"),
            valid.replace("\"delayUs\":97", "\"delayUs\":null"),
            valid.replace("\"delayUs\":97", "\"delayUs\":true"),
            valid.replace("\"delayUs\":97", "\"delayUs\":\"97\""),
            valid.replace("\"delayUs\":97", "\"delayUs\":97.5"),
            valid.replace("\"delayUs\":97", "\"delayUs\":NaN"),
            valid.replace("\"delayUs\":97", "\"delayUs\":1e999"),
            valid.replace("\"delayUs\":97", "\"delayUs\":2147483648"),
            valid.replace("\"delayUs\":97", "\"delayUs\":97,\"enabled\":true"))
        invalid.forEach { document ->
            assertThrows(IllegalArgumentException::class.java) { SurroundPresetCodec.decode(document) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SurroundPresetCodec.encode(listOf(preset.copy(settings = settings.copy(lowHz = 0))))
        }
    }

    @Test fun entryLimitsNamesAndExcessiveNestingAreValidated() {
        val root = Json.parseToJsonElement(valid).jsonObject
        val entry = root["presets"]!!.jsonArray[0]
        listOf(JsonArray(emptyList()), JsonArray(List(21) { entry }), JsonArray(listOf(JsonNull))).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                SurroundPresetCodec.decode(JsonObject(root + ("presets" to it)).toString())
            }
        }
        assertEquals("名称", SurroundPresetCodec.normalizeName("  名称  "))
        listOf("", " ", "名".repeat(41), "名称\n", "\u0000名称", "\uD800", "\uDC00").forEach {
            assertThrows(IllegalArgumentException::class.java) { SurroundPresetCodec.normalizeName(it) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SurroundPresetCodec.decode("[".repeat(10000) + "]".repeat(10000))
        }
        assertEquals("[]{}\\\"扬声器", SurroundPresetCodec.decode(SurroundPresetCodec.encode(
            listOf(preset.copy(name = "[]{}\\\"扬声器")))).single().name)
    }

    @Test fun readImportEnforcesUtf8And256KiBLimitWithoutClosingCallerStream() {
        val utf8 = valid.toByteArray(Charsets.UTF_8)
        var closed = false
        val stream = object : ByteArrayInputStream(utf8) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(valid, SurroundPresetCodec.readImport(stream))
        assertFalse(closed)
        assertThrows(IOException::class.java) {
            SurroundPresetCodec.readImport(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SurroundPresetCodec.readImport(ByteArrayInputStream(ByteArray(SurroundPresetStore.MAX_IMPORT_BYTES + 1)))
        }
        val boundary = valid + " ".repeat(SurroundPresetStore.MAX_IMPORT_BYTES - utf8.size)
        assertEquals(SurroundPresetStore.MAX_IMPORT_BYTES, boundary.toByteArray(Charsets.UTF_8).size)
        assertEquals(settings, SurroundPresetCodec.decode(SurroundPresetCodec.readImport(
            ByteArrayInputStream(boundary.toByteArray(Charsets.UTF_8)))).single().settings)
        val multibyte = valid.dropLast(1) + ",\"extra\":\"" + "中".repeat(SurroundPresetStore.MAX_IMPORT_BYTES / 3 + 1) + "\"}"
        assertTrue(multibyte.length < SurroundPresetStore.MAX_IMPORT_BYTES)
        val error = assertThrows(IllegalArgumentException::class.java) { SurroundPresetCodec.decode(multibyte) }
        assertTrue(error.message!!.contains("256 KiB"))
    }
}
