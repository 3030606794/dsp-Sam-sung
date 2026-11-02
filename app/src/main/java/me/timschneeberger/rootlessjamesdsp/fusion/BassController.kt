package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.UUID

object BassController {
    const val CAPABILITY = 32
    const val STATUS_PREFS = "bass_driver_status"
    const val STATUS_ACTION = "dsp.fusion.BASS_RUNTIME"
    const val FREQUENCY_QUERY = 131084
    const val GAIN_QUERY = 131085
    private val processSession = UUID.randomUUID().toString()
    @Volatile var runtime = BassRuntimeStatus.Snapshot(BassRuntimeStatus.State.UNKNOWN)
        private set

    fun preferences(context: Context, viper: Boolean): SharedPreferences =
        PreferenceCache.getPreferences(context, if (viper) ViperParameters.PREFS else Constants.PREF_BASS)
    fun statusPreferences(context: Context): SharedPreferences = context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
    fun supported(context: Context): Boolean = statusPreferences(context).let {
        it.getString("session", null) == processSession && it.getInt("capabilities", 0) and CAPABILITY != 0
    }
    fun publishCapabilities(context: Context, capabilities: Int) {
        val prefs = statusPreferences(context)
        if (prefs.getString("session", null) != processSession || prefs.getInt("capabilities", -1) != capabilities)
            prefs.edit().putString("session", processSession).putInt("capabilities", capabilities).apply()
    }
    fun publishRuntime(context: Context, snapshot: BassRuntimeStatus.Snapshot) {
        if (runtime != snapshot) {
            runtime = snapshot
            context.sendLocalBroadcast(Intent(STATUS_ACTION))
        }
    }
    fun syncViper(context: Context, capabilities: Int, sender: (Int, IntArray) -> Boolean): Boolean {
        val prefs = preferences(context, true)
        BassParameters.migrate(prefs, true)
        val controls = listOf(BassParameters.amount) + if (capabilities and CAPABILITY != 0) BassParameters.viperAdvanced else emptyList()
        var success = true
        controls.forEach { success = sender(it.parameter!!, intArrayOf(BassParameters.value(prefs.all, it))) && success }
        return success
    }
    fun syncDynamic(context: Context, capabilities: Int, sender: (Int, IntArray) -> Boolean): Boolean {
        if (capabilities and CAPABILITY == 0) return true
        val prefs = preferences(context, false)
        BassParameters.migrate(prefs, false)
        return sender(BassParameters.DYNAMIC_PARAMETER, BassParameters.dynamicPacket(prefs.all))
    }
}
