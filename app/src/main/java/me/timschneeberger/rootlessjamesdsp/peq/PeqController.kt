package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.model.GraphicEqNodeList
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast

/** Exclusive choice of the new IIR bank or the user's unchanged legacy response curve. */
object PeqController {
    const val MODE_KEY = "fusion_peq_mode"
    const val PARAMETRIC = "parametric"
    const val LEGACY = "legacy"
    const val PARAMETER = 1250

    fun hasLegacyCurve(context: Context): Boolean {
        val prefs = PreferenceCache.getPreferences(context, Constants.PREF_GEQ)
        val text = prefs.all[context.getString(R.string.key_geq_nodes)] as? String ?: return false
        return GraphicEqNodeList().apply { deserialize(text) }
            .any { it.freq.isFinite() && it.freq > 0 && it.gain.isFinite() }
    }

    fun isParametric(context: Context): Boolean {
        val prefs = PreferenceCache.getPreferences(context, Constants.PREF_GEQ)
        return when (prefs.all[MODE_KEY] as? String) {
            PARAMETRIC -> true
            LEGACY -> false
            else -> {
                // Freeze the migration decision once. Importing/editing a curve later
                // must not silently take over a user's already selected parametric bank.
                val parametric = !hasLegacyCurve(context)
                prefs.edit().putString(MODE_KEY, if (parametric) PARAMETRIC else LEGACY).commit()
                parametric
            }
        }
    }

    fun useParametric(context: Context) = select(context, PARAMETRIC)
    fun useLegacy(context: Context) = select(context, LEGACY)

    private fun select(context: Context, mode: String) {
        PreferenceCache.getPreferences(context, Constants.PREF_GEQ).edit()
            .putString(MODE_KEY, mode).commit()
        // Selecting a mode never changes the existing effect enable switch.
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        context.sendLocalBroadcast(Intent(Constants.ACTION_GRAPHIC_EQ_CHANGED))
    }

    fun sync(context: Context, sender: (Int, IntArray) -> Boolean): Boolean {
        val prefs = PreferenceCache.getPreferences(context, Constants.PREF_GEQ)
        val enabled = isParametric(context) &&
            (prefs.all[context.getString(R.string.key_geq_enable)] as? Boolean == true)
        return sender(PARAMETER, PeqProtocol.payload(PeqRepository(context).load(), enabled))
    }
}
