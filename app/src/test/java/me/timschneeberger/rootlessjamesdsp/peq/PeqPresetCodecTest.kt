package me.timschneeberger.rootlessjamesdsp.peq

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Pure format validation, independent of Android file access and UI. */
class PeqPresetCodecTest {
    private val valid = PeqPresetCodec.encode(listOf(PeqPreset(UUID.randomUUID().toString(), "耳机", PeqModel.defaultState(170))))
    private fun replaceItems(items: JsonArray): String = JsonObject(Json.parseToJsonElement(valid).jsonObject +
        ("presets" to items)).toString()

    @Test fun singleAndTwentyBankBundlesRoundTripAllFieldsWithoutExposingLocalIdentities() {
        val state = PeqModel.defaultState(170).copy(gainRange = 83.0, gainStep = 1.5,
            qStep = 17.75, frequencyStep = 333.5, pinResponse = false, showQSlider = true, showFrequencySlider = true)
        val presets = (1..20).map { PeqPreset(UUID.randomUUID().toString(), "配置 $it", state) }
        val encoded = PeqPresetCodec.encode(presets)
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size < PeqPresetStore.MAX_IMPORT_BYTES)
        val decoded = PeqPresetCodec.decode(encoded)
        assertEquals(presets.map { it.name }, decoded.map { it.name })
        assertEquals(presets.map { it.state }, decoded.map { it.state })
        assertEquals(20, decoded.map { it.id }.toSet().size)
        assertTrue(decoded.none { next -> presets.any { it.id == next.id } })
        assertEquals(state, PeqPresetCodec.decode(PeqPresetCodec.encode(presets.take(1))).single().state)
    }

    @Test fun badHeadersEmptyOversizedAndMalformedEntriesAreRejected() {
        val entry = Json.parseToJsonElement(valid).jsonObject["presets"]!!.jsonArray.single()
        val malformed = listOf("", "[]", "{}", "not json",
            valid.replace(PeqPresetCodec.FORMAT, "another-format"),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            replaceItems(JsonArray(emptyList())), replaceItems(JsonArray(List(21) { entry })),
            replaceItems(JsonArray(listOf(JsonPrimitive("not an object")))),
            valid.replace("\"schema\":2", "\"schema\":999"),
            valid.replace("\"q\":0.7071067811865476", "\"q\":0"),
            valid.replace("\"type\":\"PEAK\"", "\"type\":\"UNKNOWN\""))
        malformed.forEach { assertThrows(IllegalArgumentException::class.java) { PeqPresetCodec.decode(it) } }
    }

    @Test fun namesAreTrimmedAndRejectControlsBlankInvalidUnicodeAndOverlongValues() {
        assertEquals("我的耳机", PeqPresetCodec.normalizeName("  我的耳机  "))
        assertEquals("名".repeat(40), PeqPresetCodec.normalizeName("名".repeat(40)))
        listOf("", "   ", "名".repeat(41), "\n名称", "名称\t", "名\u0000称", "\uD800").forEach {
            assertThrows(IllegalArgumentException::class.java) { PeqPresetCodec.normalizeName(it) }
        }
        val stringWithBrackets = "[]{}\\\"名称"
        val preset = PeqPreset(UUID.randomUUID().toString(), stringWithBrackets, PeqModel.defaultState())
        assertEquals(stringWithBrackets, PeqPresetCodec.decode(PeqPresetCodec.encode(listOf(preset))).single().name)
    }

    @Test fun fourMiBLimitCountsUtf8BytesAndDeepDocumentsFailBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            PeqPresetCodec.decode(valid + " ".repeat(PeqPresetStore.MAX_IMPORT_BYTES))
        }
        val multibyte = valid.dropLast(1) + ",\"padding\":\"" + "中".repeat(PeqPresetStore.MAX_IMPORT_BYTES / 3 + 1) + "\"}"
        assertTrue(multibyte.length < PeqPresetStore.MAX_IMPORT_BYTES)
        val error = assertThrows(IllegalArgumentException::class.java) { PeqPresetCodec.decode(multibyte) }
        assertTrue(error.message!!.contains("4 MiB"))
        assertThrows(IllegalArgumentException::class.java) { PeqPresetCodec.decode("[".repeat(10000) + "]".repeat(10000)) }
    }
}
