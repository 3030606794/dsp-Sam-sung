package me.timschneeberger.rootlessjamesdsp.liveprog

import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltinLiveprogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assets = listOf(File("src/main/assets/Liveprog"), File("app/src/main/assets/Liveprog"))
        .first { it.isDirectory }
    private fun source(name: String) = File(assets, name).readText()
    private fun file(name: String, contents: String = source(name)): File =
        File(temporary.newFolder(), name).apply { writeText(contents) }
    private fun parse(file: File, locale: Locale = Locale.SIMPLIFIED_CHINESE) = EelParser(locale).apply {
        assertTrue(load(file.absolutePath))
    }
    private fun bass(parser: EelParser) = parser.properties.first { it.key == "dBassGain" } as EelNumberRangeProperty<*>
    private fun oldSource(name: String): String = source(name)
        .replace("dBassGain:5<", if (name.endsWith("_preset.eel")) "dBassGain:60<" else "dBassGain:100<")
        .replace("dBassGain = 5;", if (name.endsWith("_preset.eel")) "dBassGain = 100;" else "dBassGain = 100.00;")

    @Test fun bothBassScriptsStartAtFiveAndExposeChineseControlsAndOptions() {
        listOf("viper_dynamicbass.eel", "viper_dynamicbass_preset.eel").forEach { name ->
            val script = file(name)
            val parser = parse(script)
            assertTrue(parser.description!!.startsWith("动态低音"))
            assertEquals("低音强度（%）", bass(parser).description)
            assertEquals(5f, bass(parser).value.toFloat(), 0f)
            assertEquals(5f, bass(parser).default!!.toFloat(), 0f)
            assertEquals(source(name), script.readText()) // Opening/translating never rewrites code.
            if (name.endsWith("_preset.eel")) {
                val preset = parser.properties.first { it.key == "dPreset" } as EelListProperty
                assertEquals("预设", preset.description)
                assertEquals("极致头戴式耳机（v2）", preset.options.first())
                assertEquals(19, preset.options.size)
                assertEquals(18, preset.value) // Existing preset selection is unchanged.
            }
        }
    }

    @Test fun everyStockScriptHasAUniqueChineseDisplayNameAndTranslatedParameterLabels() {
        val names = mutableSetOf<String>()
        assets.listFiles()!!.filter { it.extension == "eel" }.forEach { original ->
            val parser = parse(file(original.name))
            assertNotNull(original.name, parser.localizedBuiltinTitle)
            assertTrue(original.name, names.add(parser.localizedBuiltinTitle!!))
            parser.properties.forEach { property ->
                assertTrue("${original.name}: ${property.description}", property.description.any { it in '\u4e00'..'\u9fff' })
            }
        }
        assertEquals(40, names.size)
    }

    @Test fun translationPreservesEveryBuiltinParameterAndItsValues() {
        assets.listFiles()!!.filter { it.extension == "eel" }.forEach { original ->
            val script = file(original.name)
            val english = parse(script, Locale.ENGLISH)
            val chinese = parse(script)
            assertEquals(original.name, english.contents, chinese.contents)
            assertEquals(original.name, english.properties.map { it.key }, chinese.properties.map { it.key })
            english.properties.zip(chinese.properties).forEach { (before, after) ->
                val context = "${original.name}: ${before.key}"
                assertEquals(context, before.javaClass, after.javaClass)
                before as EelNumberRangeProperty<*>
                after as EelNumberRangeProperty<*>
                assertEquals(context, before.default, after.default)
                assertEquals(context, before.value, after.value)
                assertEquals(context, before.minimum, after.minimum)
                assertEquals(context, before.maximum, after.maximum)
                assertEquals(context, before.step, after.step)
                if (before is EelListProperty && after is EelListProperty) {
                    assertEquals(context, before.options.size, after.options.size)
                }
            }
        }
    }

    @Test fun annotationTranslationPreservesNumericDefinitionAndListOrder() {
        assertEquals("dBassGain:5<0,100,1>低音强度（%）",
            BuiltinLiveprogMetadata.parameterLine("dBassGain:5<0,100,1>Bass strength (%)"))
        assertEquals("choice:1<0,2,1{关闭,开启,Custom option}>预设",
            BuiltinLiveprogMetadata.parameterLine("choice:1<0,2,1{Off, On, Custom option}>Preset"))
        assertEquals("gain:0<-12,12,0.01>增益（dB）",
            BuiltinLiveprogMetadata.parameterLine("gain:0<-12,12,0.01>Gain (dB)"))
    }

    @Test fun incompleteOrUnknownAnnotationIsLeftIntact() {
        listOf("", "@init", "choice:0<0,1,1{Off,On>Preset", "gain:0<-12,12,0.01",
            "choice:0<0,1,1{Custom A,Custom B}>User label {unchanged}").forEach { line ->
            assertEquals(line, BuiltinLiveprogMetadata.parameterLine(line))
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun savedBassAndPresetSurviveNewParserAndRestoreUsesFive() {
        val script = file("viper_dynamicbass_preset.eel")
        val parser = parse(script)
        val gain = bass(parser) as EelNumberRangeProperty<Float>
        gain.value = 27f
        assertTrue(parser.manipulateProperty(gain))
        val preset = parser.properties.first { it.key == "dPreset" } as EelListProperty
        preset.value = 3
        assertTrue(parser.manipulateProperty(preset))
        val reopened = parse(script)
        assertEquals(27f, bass(reopened).value.toFloat(), 0f)
        assertEquals(3, (reopened.properties.first { it.key == "dPreset" } as EelListProperty).value)
        assertEquals("低音强度（%）", bass(reopened).description)
        assertTrue(reopened.restoreDefaults())
        assertEquals(5f, bass(parse(script)).value.toFloat(), 0f)
    }

    @Test fun onlyUnmodifiedLegacyBassFilesMigrateAndMigrationIsIdempotent() {
        listOf("viper_dynamicbass.eel", "viper_dynamicbass_preset.eel").forEach { name ->
            val original = file(name, oldSource(name).replace("\n", "\r\n"))
            assertTrue(BuiltinLiveprogMetadata.upgradeUntouchedBass(original, source(name)))
            assertEquals(5f, bass(parse(original)).value.toFloat(), 0f)
            assertFalse(BuiltinLiveprogMetadata.upgradeUntouchedBass(original, source(name)))
            val saved = file(name, oldSource(name).replace(Regex("dBassGain = 100(?:\\.00)?;"), "dBassGain = 23;"))
            val before = saved.readText()
            assertFalse(BuiltinLiveprogMetadata.upgradeUntouchedBass(saved, source(name)))
            assertEquals(before, saved.readText())
            assertEquals(23f, bass(parse(saved)).value.toFloat(), 0f)
            assertEquals(5f, bass(parse(saved)).default!!.toFloat(), 0f)
        }
    }

    @Test fun customScriptsAndEditedCodeAreNeitherTranslatedNorMigrated() {
        val custom = file("my-bass.eel", oldSource("viper_dynamicbass.eel"))
        assertNull(parse(custom).localizedBuiltinTitle)
        assertEquals("Bass strength (%)", bass(parse(custom)).description)
        val modified = file("viper_dynamicbass.eel", oldSource("viper_dynamicbass.eel") + "\n// my changes\n")
        val before = modified.readText()
        assertNull(parse(modified).localizedBuiltinTitle)
        assertFalse(BuiltinLiveprogMetadata.upgradeUntouchedBass(modified, source(modified.name)))
        assertEquals(before, modified.readText())
        assertEquals("Bass strength (%)", bass(parse(file("viper_dynamicbass.eel"), Locale.ENGLISH)).description)
    }
}
