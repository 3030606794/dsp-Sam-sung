package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSwitchPreference
import me.timschneeberger.rootlessjamesdsp.session.root.RootProcessingPolicy

/** All three controls select one mode; one atomic edit prevents a transient dual attachment. */
class AudioProcessingModeSettings(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val onModeSelected: (Boolean) -> Unit
) {
    companion object {
        private const val VIVO_GLOBAL_DEFAULT_APPLIED = "vivo_global_mode_default_v1"

        /** Set the Vivo default once before service startup; preserve subsequent user choices. */
        fun applyVivoGlobalDefault(context: Context) {
            val preferences = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
            if (preferences.getBoolean(VIVO_GLOBAL_DEFAULT_APPLIED, false)) return
            preferences.edit()
                .putBoolean(context.getString(R.string.key_audioformat_processing), true)
                .putBoolean(context.getString(R.string.key_audioformat_enhanced_processing), false)
                .putBoolean(context.getString(R.string.key_audioformat_usb_rate_follow), false)
                .putBoolean(VIVO_GLOBAL_DEFAULT_APPLIED, true)
                .commit()
        }
    }

    fun bind(
        global: MaterialSwitchPreference,
        enhanced: MaterialSwitchPreference,
        player: MaterialSwitchPreference
    ) {
        fun select(playerSessions: Boolean, notify: Boolean) {
            preferences.edit()
                .putBoolean(context.getString(R.string.key_audioformat_processing), !playerSessions)
                .putBoolean(context.getString(R.string.key_audioformat_enhanced_processing), playerSessions)
                .putBoolean(context.getString(R.string.key_audioformat_usb_rate_follow), playerSessions)
                .apply()
            global.isChecked = !playerSessions
            enhanced.isChecked = playerSessions
            player.isChecked = playerSessions
            if (notify) onModeSelected(playerSessions)
        }
        global.setOnPreferenceChangeListener { _, value -> select(!(value as Boolean), true); false }
        enhanced.setOnPreferenceChangeListener { _, value -> select(value as Boolean, true); false }
        player.setOnPreferenceChangeListener { _, value -> select(value as Boolean, true); false }
        select(RootProcessingPolicy.playerSessions(
            preferences.getBoolean(context.getString(R.string.key_audioformat_processing), true),
            preferences.getBoolean(context.getString(R.string.key_audioformat_usb_rate_follow), false),
            preferences.getBoolean(context.getString(R.string.key_audioformat_enhanced_processing), false)
        ), false)
    }
}
