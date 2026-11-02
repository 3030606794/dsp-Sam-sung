package me.timschneeberger.rootlessjamesdsp.creative

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CreativeEffectsRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun incomingProfileReplacesOnlyItsOwnNamespaceAfterQueuedEdits() {
        val kind = CreativeEffectsKind.GRANULAR
        val prefs = CreativeEffectsController.preferences(context, kind)
        val static = context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        static.edit().putString("static_sentinel", "preserve").commit()
        val original = static.all.toMap()
        prefs.edit().putInt("gr_pitch", 1200).apply()
        assertTrue(CreativeEffectsRepository.replace(context, kind, mapOf(kind.enableKey to false, "gr_pitch" to -700)))
        assertEquals(-700, prefs.getInt("gr_pitch", 0))
        assertEquals(original, static.all)
        assertTrue(CreativeEffectsRepository.replace(context, kind, null))
        assertTrue(prefs.all.isEmpty()); assertEquals(original, static.all)
    }
    @Test fun profileRejectsUnknownFieldsCommandsAndOutOfRangeValuesBeforeAnyWrite() {
        val kind = CreativeEffectsKind.FREEZE
        val invalid = listOf("<map><int name=\"captureToken\" value=\"3\" /></map>",
            "<map><boolean name=\"hold\" value=\"true\" /></map>",
            "<map><int name=\"fr_mix\" value=\"1001\" /></map>",
            "<map><boolean name=\"freeze_enable\" value=\"yes\" /></map>",
            "<!DOCTYPE map [<!ENTITY bad SYSTEM \"file:///missing\">]><map/>")
        invalid.forEachIndexed { index, xml ->
            val file = temporary.newFile("invalid$index.xml").apply { writeText(xml) }
            assertThrows(Exception::class.java) { CreativeEffectsRepository.read(kind, file) }
        }
        val valid = temporary.newFile("valid.xml").apply {
            writeText("<map><boolean name=\"freeze_enable\" value=\"true\" /><int name=\"fr_mix\" value=\"600\" /></map>")
        }
        assertEquals(mapOf("freeze_enable" to true, "fr_mix" to 600), CreativeEffectsRepository.read(kind, valid))
    }
}
