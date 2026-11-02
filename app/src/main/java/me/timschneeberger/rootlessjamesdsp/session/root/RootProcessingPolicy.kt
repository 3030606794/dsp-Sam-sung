package me.timschneeberger.rootlessjamesdsp.session.root

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/** Explicit global selection always means Session 0, including conflicting older preferences. */
object RootProcessingPolicy {
    fun playerSessions(hd: Boolean, enhanced: Boolean) = hd || enhanced
    fun playerSessions(global: Boolean, hd: Boolean, enhanced: Boolean) = !global && playerSessions(hd, enhanced)
    fun playerSessions(context: Context): Boolean {
        val prefs = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
        return playerSessions(
            prefs.getBoolean(context.getString(R.string.key_audioformat_processing), true),
            prefs.getBoolean(context.getString(R.string.key_audioformat_usb_rate_follow), false),
            prefs.getBoolean(context.getString(R.string.key_audioformat_enhanced_processing), false))
    }
    fun acceptsSession(playerSessions: Boolean, sessionId: Int) =
        if (playerSessions) sessionId > 0 else sessionId == 0
}

/** Repeated attach failures back off silently instead of creating a toast/rebind loop. */
class RootSessionAttachBackoff {
    private data class Failure(val attempts: Int, val retryAt: Long)
    private val failures = mutableMapOf<Int, Failure>()
    fun permits(sessionId: Int, now: Long) = now >= (failures[sessionId]?.retryAt ?: 0L)
    fun failed(sessionId: Int, now: Long) {
        val attempts = ((failures[sessionId]?.attempts ?: 0) + 1).coerceAtMost(6)
        val delay = (1000L shl (attempts - 1)).coerceAtMost(30000L)
        failures[sessionId] = Failure(attempts, now + delay)
    }
    fun succeeded(sessionId: Int) { failures.remove(sessionId) }
    fun retain(sessionIds: Set<Int>) { failures.keys.retainAll(sessionIds) }
    fun clear() { failures.clear() }
}
