package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup

/** Removes irrelevant rows from the group so expanding/rebinding cannot reveal an inactive control. */
class ViperBassEditor(private val context: Context, private val group: SwitchPreferenceGroup) {
    private val preferences = BassController.preferences(context, true)
    private val status = BassController.statusPreferences(context)
    private val rows = linkedMapOf<String, BassNumericPreference>()
    private val help = Preference(context).apply { key = "bass_viper_help"; isSelectable = false; isIconSpaceReserved = false; order = 90 }
    private val driver = Preference(context).apply {
        key = "bass_viper_driver"; setTitle(R.string.bass_driver_required)
        isSelectable = false; isIconSpaceReserved = false; order = 100
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "v_bass_mode" || key == "capabilities" || key == "session") render()
    }

    init {
        BassParameters.migrate(preferences, true)
        BassParameters.viper.forEach { control ->
            rows[control.key] = BassNumericPreference(context, control, BassParameters.value(preferences.all, control))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        status.registerOnSharedPreferenceChangeListener(listener)
        render()
    }

    private fun render() {
        val mode = (preferences.all["v_bass_mode"] as? Number)?.toInt()?.coerceIn(0, 2) ?: 0
        val supported = BassController.supported(context)
        val controls = if (mode == 2) listOf(BassParameters.amount,
            BassParameters.deepFrequency, BassParameters.deepGain, BassParameters.deepWidth,
            BassParameters.punchFrequency, BassParameters.punchGain, BassParameters.punchWidth)
        else listOf(BassParameters.frequency, BassParameters.amount, BassParameters.naturalQ)
        val visibleKeys = controls.map { it.key }.toSet()
        rows.filterKeys { it !in visibleKeys }.values.forEach { group.removePreference(it) }
        controls.forEachIndexed { index, definition ->
            val row = rows.getValue(definition.key)
            row.order = (index + 1) * 10
            row.isEnabled = !definition.advanced || supported
            if (group.findPreference<Preference>(definition.key) == null) group.addPreference(row)
        }
        help.setSummary(if (mode == 2) R.string.bass_subwoofer_help else R.string.bass_natural_help)
        if (group.findPreference<Preference>(help.key) == null) group.addPreference(help)
        if (!supported && group.findPreference<Preference>(driver.key) == null) group.addPreference(driver)
        else if (supported) group.removePreference(driver)
    }

    fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        status.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
