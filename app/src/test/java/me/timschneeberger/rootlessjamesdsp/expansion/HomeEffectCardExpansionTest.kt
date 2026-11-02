package me.timschneeberger.rootlessjamesdsp.expansion

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsKind
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.ui.HomeEffectCardExpansion
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class)
class HomeEffectCardExpansionTest {
    private val context: Context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_RootlessJamesDSP)
    @Before fun clearDisplay() {
        context.getSharedPreferences(HomeEffectCardExpansion.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun newCard(key: String) = SwitchPreferenceGroup(context).also {
        it.key = key
        HomeEffectCardExpansion.bind(context, it, key)
    }

    @Test fun newCardsStartCollapsedAndIndependentChoicesSurviveNewInstances() {
        val expansion = ExpansionEffectsKind.SPEAKER_OPTIMIZATION
        val creative = CreativeEffectsKind.GRANULAR
        val audio = context.getSharedPreferences(expansion.prefsName, Context.MODE_PRIVATE)
        audio.edit().putBoolean(expansion.enableKey, true).putInt("p7", 1300).commit()
        val before = audio.all.toMap()
        val first = newCard(expansion.enableKey)
        val second = newCard(creative.enableKey)
        assertFalse(first.isExpanded); assertFalse(second.isExpanded)
        first.setExpanded(true)
        assertTrue(newCard(expansion.enableKey).isExpanded)
        assertFalse(newCard(creative.enableKey).isExpanded)
        first.setExpanded(false)
        second.setExpanded(true)
        assertFalse(newCard(expansion.enableKey).isExpanded)
        assertTrue(newCard(creative.enableKey).isExpanded)
        assertEquals(before, audio.all)
        assertFalse(HomeEffectCardExpansion.PREFERENCES.startsWith("dsp_"))
    }

    @Test fun staleSavedHierarchyCannotReopenACardCollapsedAfterThatSnapshot() {
        val key = ExpansionEffectsKind.MS_EQ.enableKey
        val card = newCard(key)
        card.setExpanded(true)
        card.setExpanded(false)
        // Preference restoration may carry an older expanded=true snapshot.
        val restored = SwitchPreferenceGroup(context).apply { setExpanded(true) }
        HomeEffectCardExpansion.bind(context, restored, key)
        assertFalse(restored.isExpanded)
        assertFalse(HomeEffectCardExpansion.read(context, key))
        restored.setExpanded(true)
        assertTrue(newCard(key).isExpanded)
    }
}
