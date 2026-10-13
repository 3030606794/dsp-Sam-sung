package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.UUID

/** Independent, preset-compatible settings for speaker crosstalk cancellation. */
object SurroundController {
    const val PREFS = "dsp_fusion_surround"
    const val STATUS_PREFS = "surround_driver_status"
    const val PARAMETER = 196609
    const val CAPABILITY = 16
    const val ENABLED = "surround_enabled"
    const val SELECTED = "surround_selected_slot"
    private val processSession = UUID.randomUUID().toString()

    data class Settings(val lowHz: Int, val highHz: Int, val attenuationMilliDb: Int, val delayUs: Int) {
        fun sanitized() = Settings(lowHz.coerceIn(10, 800), highHz.coerceIn(4000, 16000),
            attenuationMilliDb.coerceIn(500, 10000), delayUs.coerceIn(20, 210))
    }

    val defaults = listOf(
        Settings(200, 9000, 5500, 35), Settings(300, 9000, 5000, 35),
        Settings(400, 9000, 4500, 35), Settings(500, 9000, 4000, 35),
        Settings(600, 9000, 3500, 35), Settings(700, 9000, 3000, 35),
        Settings(800, 9000, 2500, 35), Settings(800, 9000, 2000, 30)
    )

    @Suppress("DEPRECATION")
    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)

    fun statusPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)

    private fun number(prefs: SharedPreferences, key: String, fallback: Int): Int =
        (prefs.all[key] as? Number)?.toInt() ?: fallback

    fun selected(context: Context): Int = number(preferences(context), SELECTED, 0).coerceIn(0, 7)
    fun enabled(context: Context): Boolean = preferences(context).all[ENABLED] == true

    fun settings(context: Context, slot: Int = selected(context)): Settings {
        require(slot in 0..7)
        val prefs = preferences(context)
        val fallback = defaults[slot]
        return Settings(number(prefs, "slot_${slot}_low", fallback.lowHz),
            number(prefs, "slot_${slot}_high", fallback.highHz),
            number(prefs, "slot_${slot}_attenuation", fallback.attenuationMilliDb),
            number(prefs, "slot_${slot}_delay", fallback.delayUs)).sanitized()
    }

    fun select(context: Context, slot: Int) {
        require(slot in 0..7)
        preferences(context).edit().putInt(SELECTED, slot).apply()
        changed(context)
    }

    fun save(context: Context, value: Settings, slot: Int = selected(context)) {
        require(slot in 0..7 && value == value.sanitized())
        preferences(context).edit().putInt("slot_${slot}_low", value.lowHz)
            .putInt("slot_${slot}_high", value.highHz)
            .putInt("slot_${slot}_attenuation", value.attenuationMilliDb)
            .putInt("slot_${slot}_delay", value.delayUs).apply()
        changed(context)
    }

    fun reset(context: Context) = save(context, defaults[selected(context)])

    /** Unknown or stale capabilities never turn the UI green. Disabling remains available. */
    fun supported(context: Context): Boolean = statusPreferences(context).let {
        it.getString("session", null) == processSession &&
            it.getInt("capabilities", 0) and CAPABILITY != 0
    }

    fun publishCapabilities(context: Context, capabilities: Int) {
        statusPreferences(context).edit().putString("session", processSession)
            .putInt("capabilities", capabilities).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && !supported(context)) return false
        preferences(context).edit().putBoolean(ENABLED, enabled).apply()
        changed(context)
        return true
    }

    fun payload(context: Context): IntArray {
        val value = settings(context)
        return intArrayOf(1, if (enabled(context)) 1 else 0,
            value.lowHz, value.highHz, value.attenuationMilliDb, value.delayUs)
    }

    fun sync(context: Context, capabilities: Int, sender: (Int, IntArray) -> Boolean): Boolean {
        publishCapabilities(context, capabilities)
        if (capabilities and CAPABILITY == 0) return !enabled(context)
        return sender(PARAMETER, payload(context))
    }

    private fun changed(context: Context) =
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
}
