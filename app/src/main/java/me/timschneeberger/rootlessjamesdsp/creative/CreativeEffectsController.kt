package me.timschneeberger.rootlessjamesdsp.creative

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.IdentityHashMap

object CreativeEffectsController {
    const val DRIVER_REQUIRED = "这三项音效需要配套 1085 或更新驱动，请更新驱动并重启手机"
    const val ACTION_FREEZE_CHANGED = "dsp.fusion.FREEZE_CAPTURE_CHANGED"
    private val captureLock = Any()
    private var captureToken = 0
    @Volatile var isHolding = false
        private set

    fun preferences(context: Context, kind: CreativeEffectsKind): SharedPreferences =
        PreferenceCache.getPreferences(context, kind.prefsName)
    fun notifyChanged(context: Context) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }
    fun packet(context: Context, kind: CreativeEffectsKind): IntArray = synchronized(captureLock) {
        val values = preferences(context, kind).all
        if (kind == CreativeEffectsKind.FREEZE && values[kind.enableKey] != true) isHolding = false
        CreativeEffectsParameters.packet(kind, values, captureToken, isHolding)
    }
    /** The captured audio and commands are deliberately excluded from persistent presets. */
    fun capture(context: Context): Boolean {
        if (preferences(context, CreativeEffectsKind.FREEZE).all[CreativeEffectsKind.FREEZE.enableKey] != true ||
            supported(context) == false) return false
        synchronized(captureLock) {
            captureToken = if (captureToken == Int.MAX_VALUE) 1 else captureToken + 1
            isHolding = true
        }
        notifyChanged(context)
        context.sendLocalBroadcast(Intent(ACTION_FREEZE_CHANGED))
        return true
    }
    fun release(context: Context) = synchronized(captureLock) {
        isHolding = false
        notifyChanged(context)
        context.sendLocalBroadcast(Intent(ACTION_FREEZE_CHANGED))
    }
    fun resetTransient(context: Context) = synchronized(captureLock) {
        // Keep the monotonically increasing token to avoid replaying a previous capture.
        isHolding = false
        context.sendLocalBroadcast(Intent(ACTION_FREEZE_CHANGED))
    }
    fun supported(context: Context): Boolean? {
        val states = engines(context).mapNotNull { it.creativeEffectsSupported() }
        return if (states.isEmpty()) null else states.all { it }
    }
    fun sync(context: Context, supported: Boolean, sender: (Int, IntArray) -> Boolean): Boolean {
        if (!supported) return true
        var success = true
        CreativeEffectsKind.values().forEach { success = sender(it.parameter, packet(context, it)) && success }
        return success
    }

    private data class Previous(val frames: Int, val rate: Int, val at: Long, val enabled: Boolean)
    private val history = CreativeEffectsKind.values().associateWith { IdentityHashMap<Any, Previous>() }
    /** Worker-thread only; the history is separate for each effect and actual engine instance. */
    fun currentRuntime(context: Context, kind: CreativeEffectsKind): CreativeEffectsRuntime? = synchronized(history) {
        val app = context.applicationContext as? MainApplication ?: return@synchronized null
        val now = SystemClock.elapsedRealtime()
        val expected = packet(context, kind)
        val previous = history.getValue(kind)
        val rows = engines(context).mapNotNull { it.creativeEffectsRuntime(kind) }
        val live = rows.map { it.first }.toSet()
        previous.keys.removeAll { it !in live }
        val blocked = !app.isPlayerSessionMode && app.rootSessionDatabase?.globalPlaybackIssue in
            listOf(GlobalPlaybackEvidence.Reason.SUSPENDED, GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT)
        val snapshots = rows.map { (identity, runtime) ->
            val old = previous[identity]
            val advanced = old != null && old.rate == runtime.sampleRate && old.enabled && runtime.engineEnabled &&
                now - old.at in 1..2000 && (runtime.processedFrames > old.frames ||
                (old.frames >= 0x70000000 && runtime.processedFrames <= 0x0fffffff))
            previous[identity] = Previous(runtime.processedFrames, runtime.sampleRate, now, runtime.engineEnabled)
            runtime.copy(isProcessing = advanced && !blocked && runtime.enabled &&
                runtime.parameterHash != 0 && runtime.parameterHash == expected.contentHashCode())
        }
        val active = snapshots.filter { it.isProcessing }
        if (active.size > 1) null else active.singleOrNull() ?: snapshots.singleOrNull()?.copy(isProcessing = false)
    }
    private fun engines(context: Context): List<JamesDspRemoteEngine> =
        (context.applicationContext as? MainApplication)?.rootSessionDatabase?.sessionList?.toList()
            ?.mapNotNull { (_, session) -> (session as? RemoteEffectSession)?.effect }.orEmpty()
}
