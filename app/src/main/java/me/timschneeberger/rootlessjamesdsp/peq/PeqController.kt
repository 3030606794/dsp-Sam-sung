package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/** The parametric bank owns the EQ switch; retired curve data is never activated. */
object PeqController {
    const val PARAMETER = 1250

    fun sync(context: Context, sender: (Int, IntArray) -> Boolean): Boolean {
        val prefs = PreferenceCache.getPreferences(context, Constants.PREF_GEQ)
        val enabled = prefs.all[context.getString(R.string.key_geq_enable)] as? Boolean == true
        return sender(PARAMETER, PeqProtocol.payload(PeqRepository(context).load(), enabled))
    }
}
