package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.os.Bundle
import androidx.preference.*
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.preference.AppTheme
import me.timschneeberger.rootlessjamesdsp.model.preference.ThemeMode
import me.timschneeberger.rootlessjamesdsp.preference.ThemesPreference
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import me.timschneeberger.rootlessjamesdsp.utils.AppFontScale
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.isDynamicColorAvailable
import me.timschneeberger.rootlessjamesdsp.ui.glass.Glass3SettingsDialog
import me.timschneeberger.rootlessjamesdsp.ui.power.PowerMotionSettings
import me.timschneeberger.rootlessjamesdsp.ui.power.PowerMotionStyle
import me.timschneeberger.rootlessjamesdsp.ui.EffectIconStyle

class SettingsAppearanceFragment : SettingsBaseFragment() {

    private val themeMode by lazy { findPreference<ListPreference>(getString(R.string.key_appearance_theme_mode)) }
    private val amoledMode by lazy { findPreference<Preference>(getString(R.string.key_appearance_pure_black)) }
    private val appTheme by lazy { findPreference<ThemesPreference>(getString(R.string.key_appearance_app_theme)) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        EffectIconStyle.normalize(requireContext())
        // Repair only these presentation keys before Preference inflates typed persisted values.
        val motionPreferences = PowerMotionSettings.preferences(requireContext())
        val motionValues = motionPreferences.all
        val motionConfig = PowerMotionSettings.read(requireContext())
        motionPreferences.edit().apply {
            if (motionValues.containsKey(PowerMotionSettings.KEY_ENABLED) && motionValues[PowerMotionSettings.KEY_ENABLED] != motionConfig.enabled)
                putBoolean(PowerMotionSettings.KEY_ENABLED, motionConfig.enabled)
            if (motionValues.containsKey(PowerMotionSettings.KEY_STYLE) && motionValues[PowerMotionSettings.KEY_STYLE] != motionConfig.style.key)
                putString(PowerMotionSettings.KEY_STYLE, motionConfig.style.key)
        }.apply()
        setPreferencesFromResource(R.xml.app_appearance_preferences, rootKey)

        findPreference<ListPreference>(PowerMotionSettings.KEY_STYLE)?.summaryProvider =
            Preference.SummaryProvider<ListPreference> { preference ->
                getString(PowerMotionStyle.fromKey(preference.value).summaryResId)
            }

        appTheme?.let { picker ->
            picker.parent?.addPreference(Preference(requireContext()).apply {
                key = "appearance_glass3_settings"
                setTitle(R.string.glass3_settings_title)
                setSummary(R.string.glass3_settings_summary)
                isIconSpaceReserved = false
                order = picker.order + 1
                setOnPreferenceClickListener {
                    Glass3SettingsDialog.show(requireContext())
                    true
                }
            })
        }

        findPreference<Preference>("appearance_font_size_reset")?.setOnPreferenceClickListener {
            findPreference<MaterialSeekbarPreference>(AppFontScale.KEY)
                ?.setValue(AppFontScale.DEFAULT_PERCENT.toFloat())
            true
        }

        val appThemes = AppTheme.values().filter {
            val monetFilter = if (it == AppTheme.MONET) {
                isDynamicColorAvailable
            } else {
                true
            }
            it.titleResId != null && monetFilter
        }
        appTheme?.entries = appThemes

        themeMode?.setOnPreferenceChangeListener { _, _ ->
            updateViewStates()
            true
        }
        updateViewStates()

        savedInstanceState?.let {
            appTheme?.lastScrollPosition = it.getInt(STATE_THEMES_SCROLL_POSITION, 0)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        appTheme?.let {
            outState.putInt(STATE_THEMES_SCROLL_POSITION, it.lastScrollPosition ?: 0)
        }
        super.onSaveInstanceState(outState)
    }

    private fun updateViewStates(){
        amoledMode?.isVisible = appTheme?.value !in setOf(AppTheme.LIQUID_GLASS.name, AppTheme.LIQUID_GLASS_2.name, AppTheme.LIQUID_GLASS_3.name) &&
            themeMode?.value?.toIntOrNull()?.let { ThemeMode.fromInt(it) } != ThemeMode.Light
    }

    companion object {
        private const val STATE_THEMES_SCROLL_POSITION = "stateThemesScrollPosition"

        fun newInstance(): SettingsAppearanceFragment {
            return SettingsAppearanceFragment()
        }
    }
}
