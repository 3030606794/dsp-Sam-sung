package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.IdentityHashMap

object DynamicPeqController {
    const val PARAMETER = DynamicPeqProtocol.PARAMETER
    const val DRIVER_REQUIRED = "动态 PEQ 需要配套 1084 或更新驱动，请更新驱动并重启手机"

    fun notifyChanged(context: Context) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }

    /** false is an observed old driver; null means no connected root processing engine. */
    fun supported(context: Context): Boolean? {
        val values = engines(context).mapNotNull { it.dynamicPeqSupported() }
        return if (values.isEmpty()) null else values.all { it }
    }

    /** Unavailable optional functionality never restarts or interrupts unrelated effects. */
    fun sync(context: Context, supported: Boolean, sender: (Int, IntArray) -> Boolean): Boolean {
        if (!supported) return true
        val loaded = DynamicPeqRepository(context).loadResult()
        // Corrupt state is explicitly bypassed; the editor exposes the preserved validation error.
        return sender(PARAMETER, DynamicPeqProtocol.payload(loaded.state))
    }

    private data class Before(val frames: Int, val rate: Int, val at: Long, val enabled: Boolean)
    private val previous = IdentityHashMap<Any, Before>()

    /** Read on a worker thread. Readouts only advance for the same live engine and stream rate. */
    fun currentRuntime(context: Context): DynamicPeqRuntime? = synchronized(previous) {
        val app = context.applicationContext as? MainApplication ?: return@synchronized null
        val now = SystemClock.elapsedRealtime()
        val expectedState = DynamicPeqRepository(context).load()
        val current = engines(context).mapNotNull { engine -> engine.dynamicPeqRuntime()?.let { engine to it } }
        val liveIdentities = current.map { it.second.first }.toSet()
        previous.keys.removeAll { it !in liveIdentities }
        val globallyBlocked = !app.isPlayerSessionMode && app.rootSessionDatabase?.globalPlaybackIssue in
            listOf(GlobalPlaybackEvidence.Reason.SUSPENDED, GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT)
        val rows = current.map { (_, pair) ->
            val (identity, runtime) = pair
            val old = previous[identity]
            val advanced = old != null && old.rate == runtime.sampleRate && old.enabled && runtime.engineEnabled &&
                now - old.at in 1..2000 && progressed(old.frames, runtime.processedFrames)
            previous[identity] = Before(runtime.processedFrames, runtime.sampleRate, now, runtime.engineEnabled)
            runtime.copy(isProcessing = advanced && !globallyBlocked && configurationMatches(expectedState, runtime))
        }
        val active = rows.filter { it.isProcessing }
        // Do not invent a merged detector/gain-reduction reading for multiple player sessions.
        if (active.size > 1) null else active.singleOrNull() ?: rows.singleOrNull()?.copy(isProcessing = false)
    }

    internal fun progressed(before: Int, after: Int): Boolean = after > before ||
        (before >= 0x70000000 && after <= 0x0fffffff)

    /** Native word 7 is zero during transitions; only the committed bank can own its meters. */
    internal fun configurationMatches(state: DynamicPeqState, runtime: DynamicPeqRuntime): Boolean =
        runtime.parameterHash != 0 && runtime.parameterHash == DynamicPeqProtocol.payload(state).contentHashCode() &&
            runtime.enabled == state.enabled && runtime.bandCount == state.bands.size

    private fun engines(context: Context): List<JamesDspRemoteEngine> =
        (context.applicationContext as? MainApplication)?.rootSessionDatabase?.sessionList?.toList()
            ?.mapNotNull { (_, session) -> (session as? RemoteEffectSession)?.effect }.orEmpty()
}
