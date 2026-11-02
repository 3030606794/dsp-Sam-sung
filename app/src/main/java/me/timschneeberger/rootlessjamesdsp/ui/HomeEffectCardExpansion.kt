package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup

/** Home presentation only: never exported with dsp_* profiles or sent to the driver. */
internal object HomeEffectCardExpansion {
    const val PREFERENCES = "effect_home_card_display"

    fun read(context: Context, effectKey: String): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all[effectKey] as? Boolean ?: false

    /** Call after hierarchy restoration so an older saved Bundle cannot overwrite the last choice. */
    fun bind(context: Context, group: SwitchPreferenceGroup, effectKey: String) {
        group.onExpandedChanged = null
        group.setExpanded(read(context, effectKey))
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        group.onExpandedChanged = { expanded -> preferences.edit().putBoolean(effectKey, expanded).apply() }
    }
}
