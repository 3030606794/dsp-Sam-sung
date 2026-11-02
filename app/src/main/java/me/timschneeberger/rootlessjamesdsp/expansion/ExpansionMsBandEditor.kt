package me.timschneeberger.rootlessjamesdsp.expansion

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSwitchPreference

/** Select a channel and a band; edits still target one of 16 independent, persistent filter banks. */
internal object ExpansionMsBandEditor {
    fun add(fragment: PreferenceFragmentCompat, group: PreferenceGroup, preferences: SharedPreferences) {
        val context = fragment.requireContext()
        val kind = ExpansionEffectsKind.MS_EQ
        ExpansionEffectsParameters.basic(kind).forEach { group.addPreference(ExpansionEffectsRows.numeric(context, kind, it, preferences)) }
        var side = "mid"
        var band = 0
        val rendered = mutableListOf<Preference>()
        fun rebuild() {
            rendered.forEach(group::removePreference); rendered.clear()
            val prefix = "${side}_${band}_"
            val meta = ExpansionEffectsParameters.metadata(kind).filter { it.logicalKey.startsWith(prefix) }
            meta.forEach { item ->
                val row: Preference = if (item.logicalKey.endsWith("_enable")) MaterialSwitchPreference(context).apply {
                    key = "ms_band_power"; title = "${if (side == "mid") "中声道" else "侧声道"} · 第 ${band + 1} 段"
                    isPersistent = false; isIconSpaceReserved = false
                    layoutResource = R.layout.preference_liveprog_compact_row
                    isChecked = ExpansionEffectsParameters.value(preferences.all, item.control) != 0
                    setOnPreferenceChangeListener { _, candidate ->
                        preferences.edit().putInt(item.control.key, if (candidate == true) 1000 else 0).apply(); true
                    }
                } else ExpansionEffectsRows.numeric(context, kind, item.control, preferences)
                rendered += row; group.addPreference(row)
            }
            val reset = Preference(context).apply {
                key = "ms_reset_band"; title = "重置当前频段"; isPersistent = false; isIconSpaceReserved = false
                layoutResource = R.layout.preference_liveprog_compact_row
                setOnPreferenceClickListener {
                    preferences.edit().apply { meta.forEach { putInt(it.control.key, it.control.default) } }.apply()
                    rebuild(); true
                }
            }
            rendered += reset; group.addPreference(reset)
        }
        group.addPreference(ListPreference(context).apply {
            key = "ms_channel_editor"; title = "编辑声道"; isPersistent = false; isIconSpaceReserved = false
            layoutResource = R.layout.preference_liveprog_compact_row
            entries = arrayOf("中声道 M（居中声音）", "侧声道 S（左右差异）"); entryValues = arrayOf("mid", "side")
            value = side; summary = entries[0]
            setOnPreferenceChangeListener { _, candidate ->
                side = candidate.toString(); summary = entries[if (side == "mid") 0 else 1]; rebuild(); true
            }
        })
        group.addPreference(ListPreference(context).apply {
            key = "ms_band_editor"; title = "编辑频段"; isPersistent = false; isIconSpaceReserved = false
            layoutResource = R.layout.preference_liveprog_compact_row
            entries = Array(8) { "第 ${it + 1} 段" }; entryValues = Array(8) { it.toString() }
            value = "0"; summary = entries[0]
            setOnPreferenceChangeListener { _, candidate -> band = candidate.toString().toInt().coerceIn(0, 7); summary = entries[band]; rebuild(); true }
        })
        rebuild()
    }
}
