package me.timschneeberger.rootlessjamesdsp.expansion

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ExpansionEffectsRepositoryTest {
    @get:Rule val temporary=TemporaryFolder()
    private val context:Context get()=RuntimeEnvironment.getApplication()
    @Test fun speakerClassicResetPreservesPowerScopeAndOtherModules() {
        val kind=ExpansionEffectsKind.SPEAKER_OPTIMIZATION
        val prefs=ExpansionEffectsController.preferences(context,kind)
        prefs.edit().clear().putBoolean(kind.enableKey,true).putInt("p0",200).putInt("p1",170000)
            .putInt("p9",-12000).putInt("p10",1000).commit()
        val other=ExpansionEffectsController.preferences(context,ExpansionEffectsKind.MS_EQ)
        other.edit().putInt("p5",6130).commit()
        val before=other.all.toMap()
        ExpansionEffectsController.restoreSpeakerClassic(context)
        assertTrue(prefs.getBoolean(kind.enableKey,false));assertEquals(1000,prefs.getInt("p10",0))
        assertEquals(1000,prefs.getInt("p0",0));assertEquals(80000,prefs.getInt("p1",0))
        assertEquals(0,prefs.getInt("p9",-1));assertFalse(prefs.contains("p11"))
        assertEquals(before,other.all)
    }
    @Test fun presetReplacementPreservesAllOtherEffectsAndOrdersQueuedEdits() {
        val kind=ExpansionEffectsKind.TRANSIENT
        val other=context.getSharedPreferences("dsp_graphiceq",Context.MODE_PRIVATE)
        other.edit().putString("user_static_eq","do_not_touch").commit()
        val before=other.all.toMap()
        val preferences=ExpansionEffectsController.preferences(context,kind)
        preferences.edit().putInt("p3",3000).apply()
        assertTrue(ExpansionEffectsRepository.replace(context,kind,mapOf("p3" to -7000,kind.enableKey to true)))
        assertEquals(-7000,preferences.getInt("p3",0));assertEquals(before,other.all)
        assertTrue(ExpansionEffectsRepository.replace(context,kind,null));assertTrue(preferences.all.isEmpty())
        assertEquals(before,other.all)
    }
    @Test fun validationRejectsCommandsInvalidSelectionsAndInvertedBandsWithoutWriting() {
        val cases=listOf(
            ExpansionEffectsKind.SPEAKER_OPTIMIZATION to "<map><int name=\"p11\" value=\"1000\"/></map>",
            ExpansionEffectsKind.SPEAKER_OPTIMIZATION to "<map><int name=\"p10\" value=\"500\"/></map>",
            ExpansionEffectsKind.HEADTRACK to "<map><int name=\"p5\" value=\"20000\"/></map>",
            ExpansionEffectsKind.MATCH_EQ to "<map><int name=\"p7\" value=\"1000\"/></map>",
            ExpansionEffectsKind.DEHUM to "<map><int name=\"p1\" value=\"55000\"/></map>",
            ExpansionEffectsKind.MATCH_EQ to "<map><int name=\"p3\" value=\"2000000\"/><int name=\"p4\" value=\"500000\"/></map>",
            ExpansionEffectsKind.TAPE to "<!DOCTYPE map [<!ENTITY e SYSTEM \"file:///missing\">]><map/>")
        cases.forEachIndexed { index,(kind,xml)->
            val before=ExpansionEffectsController.preferences(context,kind).all.toMap()
            val file=temporary.newFile("invalid$index.xml").apply {writeText(xml)}
            assertThrows(Exception::class.java){ExpansionEffectsRepository.read(kind,file)}
            assertEquals(before,ExpansionEffectsController.preferences(context,kind).all)
        }
    }
    @Test fun learnedReferenceRoundTripsAlongsideOrdinaryParameters() {
        val profile=IntArray(64){(.0001f*(it+1)).toBits()}.joinToString(",")
        val file=temporary.newFile("match.xml").apply {writeText("<map><string name=\"match_learned_profile\">$profile</string><int name=\"p1\" value=\"650\"/></map>")}
        val values=ExpansionEffectsRepository.read(ExpansionEffectsKind.MATCH_EQ,file)
        assertEquals(profile,values[ExpansionEffectsController.MATCH_PROFILE]);assertEquals(650,values["p1"])
    }
}
