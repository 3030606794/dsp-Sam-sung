package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/** Also runs after importing old presets, so removed controls cannot reappear. */
object FusionPreferenceMigration {
    fun migrate(context: Context) {
        val old = PreferenceCache.getPreferences(context, "dsp_fusion_viper")
        val output = PreferenceCache.getPreferences(context, Constants.PREF_OUTPUT)
        val values = old.all
        if (!output.contains("balance") && values.containsKey("v_pan")) {
            val pan = (values["v_pan"] as? Number)?.toInt()?.coerceIn(-100, 100) ?: 0
            // Keep the old value if persistence fails, allowing the next launch to retry.
            if (!output.edit().putInt("balance", pan).commit()) return
        }
        val removed = values.keys.filter {
            it in setOf("v_volume", "v_pan", "v_limit", "v_eq", "v_speaker",
                "fusion_james_enabled", "fusion_viper_enabled") || it.matches(Regex("v_eq_\\d+"))
        }
        if (removed.isNotEmpty()) {
            old.edit().also { editor -> removed.forEach(editor::remove) }.commit()
        }
    }
}
