package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast

object ViperController {
    const val STATUS_ACTION = "dsp.fusion.STATUS"
    const val STATUS_PREFS = "fusion_runtime"
    const val STATUS_KEY = "driver_status"

    fun sync(context: Context, capabilities: Int = 0, sender: (Int, IntArray) -> Boolean): Boolean {
        val prefs = PreferenceCache.getPreferences(context, ViperParameters.PREFS)
        var success = true
        fun send(id: Int, data: IntArray) { success = sender(id, data) && success }

        // Set coefficients before enabling a processor, including after device profile changes.
        ViperParameters.groups.forEach { group ->
            group.controls.forEach { control ->
                if (control.key != BassParameters.LEGACY_AMOUNT) {
                    val value = (prefs.all[control.key] as? Number)?.toInt() ?: control.default
                    send(control.parameter, control.packet(value))
                }
            }
        }
        val presetIndex = prefs.getString("v_dynamic_preset", "0")?.toIntOrNull() ?: 0
        val dynamic = ViperParameters.dynamicPresets.values.elementAtOrNull(presetIndex)
            ?: ViperParameters.dynamicPresets.values.first()
        send(65570, intArrayOf(dynamic[0], dynamic[1]))
        send(65571, intArrayOf(dynamic[2], dynamic[3]))
        send(65572, intArrayOf(dynamic[4], dynamic[5]))
        success = BassController.syncViper(context, capabilities, sender) && success
        ViperParameters.groups.forEach { group ->
            group.enableParameter?.let { send(it, intArrayOf(if (prefs.getBoolean(group.key, false)) 1 else 0)) }
        }
        // VDC and convolution use the JamesDSP implementation and its existing file library.
        send(65546, intArrayOf(0))
        send(65544, intArrayOf(0))
        // The one AudioEffect power switch owns the complete chain. Legacy stage-off
        // preferences must not silently bypass effects after upgrading or importing a preset.
        send(131072, intArrayOf(1))
        send(131073, intArrayOf(1))
        return success
    }

    fun publishStatus(context: Context, message: String) {
        val prefs = context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(STATUS_KEY, null) != message) {
            prefs.edit().putString(STATUS_KEY, message).apply()
            context.sendLocalBroadcast(Intent(STATUS_ACTION))
        }
    }
}
