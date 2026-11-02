package me.timschneeberger.rootlessjamesdsp.session.root

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.interop.ProcessorMessageHandler
import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.shared.BaseSessionDatabase
import me.timschneeberger.rootlessjamesdsp.utils.ProcessingAppPolicy
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap


class RootSessionDatabase(context: Context) : BaseSessionDatabase(context) {
    private val attachBackoff = RootSessionAttachBackoff()
    private val continuity = RootSessionContinuity()
    private val closingSessions = ConcurrentHashMap.newKeySet<Int>()
    private val releaseHandler = Handler(Looper.getMainLooper())
    private var effectsReleasedCallback: (() -> Unit)? = null
    private var activePlayers = emptyMap<Int, ActivePlaybackSession>()
    private var appPolicyEditPending = false
    @Volatile var processingAppPolicy = ProcessingAppPolicy.pending()
        private set
    @Volatile var globalPlaybackIssue: GlobalPlaybackEvidence.Reason? = null
        private set
    @Volatile var hasActiveGlobalPlayback: Boolean = false
        private set
    val usesPlayerAttachments: Boolean
        get() = app.isPlayerSessionMode

    private fun acceptsSession(id: Int) = RootProcessingPolicy.acceptsSession(usesPlayerAttachments, id)

    fun setEffectsReleasedCallback(callback: (() -> Unit)?) {
        effectsReleasedCallback = callback
    }

    private fun releaseEffect(effect: JamesDspRemoteEngine) {
        val id = effect.sessionId
        closingSessions.add(id)
        effect.closeWhenReleased {
            closingSessions.remove(id)
            releaseHandler.post { effectsReleasedCallback?.invoke() }
        }
    }

    private val app
        get() = context.applicationContext as MainApplication

    var enabled: Boolean = true
        set(value) {
            field = value
            sessionList.forEach { (_, session) ->
                (session as RemoteEffectSession).effect?.enabled = value
            }
        }

    override fun createSession(id: Int, uid: Int, packageName: String): IEffectSession? {
        if (sessionList[id] == null) {
            Timber.d("Creating effect for session $id (${packageName})")

            val effect = try {
                JamesDspRemoteEngine(context, id, 0, ProcessorMessageHandler())
            }
            catch (ex: Exception) {
                Timber.e("Failed to instantiate JamesDSP effect for session $id ($packageName)")
                Timber.e(ex.cause)

                attachBackoff.failed(id, SystemClock.elapsedRealtime())
                return null
            }

            if(!acceptsSession(id) ||
                (id > 0 && (!processingAppPolicy.ready || !processingAppPolicy.allows(uid, packageName)))) {
                releaseEffect(effect)
                return null
            }
            try {
                effect.syncWithPreferences()
                effect.enabled = enabled
            } catch(ex: Exception) {
                releaseEffect(effect)
                attachBackoff.failed(id,SystemClock.elapsedRealtime())
                Timber.w(ex,"Failed to initialize effect for session $id")
                return null
            }
            attachBackoff.succeeded(id)
            activePlayers[id]?.let(continuity::attached)

            // Positive sessions come only from background discovery with a confirmed owner UID.
            return RemoteEffectSession(packageName, uid, effect)
        } else {
            Timber.w("Session $id ($packageName) already existed")
            return null
        }
    }

    override fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry): Boolean {
        if (!usesPlayerAttachments || id !in activePlayers) return false
        if (!session.isUsageRecordable()) {
            Timber.d("Skipped session $id due to usage ($session)")
            return false
        }
        return true
    }
    override fun shouldAddSession(id: Int, uid: Int, packageName: String): Boolean =
        (!usesPlayerAttachments || (!appPolicyEditPending && processingAppPolicy.ready)) && acceptsSession(id) &&
            // Wait for actual AudioEffect release, including effects from the other mode.
            // No waiting or native transfer is performed on the service/main thread.
            RootSessionReleaseBarrier.permits(id, closingSessions) &&
            attachBackoff.permits(id, SystemClock.elapsedRealtime()) &&
            (id == 0 || (activePlayers[id]?.let { it.uid == uid && it.packageName == packageName } == true &&
                uid >= 0 && uid != android.os.Process.myUid() &&
                packageName != context.packageName && processingAppPolicy.allows(uid, packageName)))

    /** A cross-store list edit must not expose its intermediate policy or attach mixed output. */
    fun setAppPolicyEditPending(pending: Boolean, affectedUids: Set<Int> = emptySet()) {
        appPolicyEditPending = pending
        if (!pending || !usesPlayerAttachments) return
        sessionList.toMap().filter { (id, session) -> id > 0 && session.uid in affectedUids }
            .keys.forEach(::removeSession)
    }

    /** Apply scope edits without resetting mode, power or unrelated, still eligible effects. */
    fun applyAppPolicy(next: ProcessingAppPolicy) {
        if (processingAppPolicy == next) return
        processingAppPolicy = next
        // System mix Session 0 cannot separate applications. Lists apply only in player mode;
        // changing them must never silently replace or suspend the explicitly selected global chain.
        if (!usesPlayerAttachments) {
            setExcludedUids(emptyArray())
            ensureGlobalSession()
            return
        }
        setExcludedUids(next.excludedUids.toTypedArray())
        sessionList.toMap().filter { (id, session) ->
            !next.ready || (id > 0 && !next.allows(session.uid, session.packageName))
        }.keys.forEach(::removeSession)
        if (!next.ready) return
        reconcileActivePlayers(activePlayers.values.toList())
    }

    override fun onSessionRemoved(item: IEffectSession) {
        val session = item as RemoteEffectSession
        session.coroutineContext[Job]?.cancel()
        session.effect?.let { effect ->
            continuity.removed(effect.sessionId)
            releaseEffect(effect)
        }
    }

    /** Rebind the existing global mode after the system moves media to/from a USB output. */
    fun refreshAfterOutputRouteChange() {
        if(!usesPlayerAttachments) {
            globalPlaybackIssue = null
            hasActiveGlobalPlayback = false
            activePlayers = emptyMap()
            sessionList.keys.toList().forEach(::removeSession)
            ensureGlobalSession()
            // Connection creation is not proof that a direct/exclusive stream runs this effect.
            Timber.i("Requested global AudioFlinger effect rebind after output route change")
        } else {
            // A second global effect would double-process tracked sessions and bypass app exclusions.
            synchronizeUsbSampleRate()
        }
    }

    /** Follow a confirmed host-rate transition without replacing sessions or changing exclusions. */
    fun synchronizeUsbSampleRate() {
        sessionList.values.toList().filterIsInstance<RemoteEffectSession>().forEach { session ->
            // Use the session's cancellable background scope; never wait for IR preparation
            // on the service/main thread or invalidate unrelated effect parameter caches.
            session.launch { session.effect?.syncWithPreferences() }
        }
    }

    fun addSessionByIntent(intent: Intent) {
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        if (!acceptsSession(sessionId)) {
            return
        }
        if (sessionId == 0) { ensureGlobalSession(); return }

        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "unknown"
        val player = activePlayers[sessionId] ?: return
        if (player.packageName != packageName) return
        addSession(sessionId, player.uid, player.packageName, false)
    }

    /** Called before any new attachments, so global and player effects never overlap. */
    fun applyProcessingMode(playerSessions: Boolean) {
        globalPlaybackIssue = null
        hasActiveGlobalPlayback = false
        setExcludedUids(if (playerSessions) processingAppPolicy.excludedUids.toTypedArray() else emptyArray())
        attachBackoff.clear()
        sessionList.keys.toList().filter { !RootProcessingPolicy.acceptsSession(usesPlayerAttachments, it) }
            .forEach(::removeSession)
        if (!usesPlayerAttachments) ensureGlobalSession()
    }

    fun ensureGlobalSession() {
        if (usesPlayerAttachments) { removeSession(0); return }
        sessionList.keys.toList().filter { it > 0 }.forEach(::removeSession)
        if (0 !in sessionList) addSession(0, android.os.Process.myUid(), context.packageName)
    }

    /** Only real active output tracks discovered before attachment authorize positive SIDs. */
    fun reconcileActivePlayers(players: List<ActivePlaybackSession>,
        processes: Map<Int, ActivePlaybackSessionParser.ProcessIdentity>? = null) {
        if (!usesPlayerAttachments) return
        removeSession(0)
        activePlayers = players.filter { it.sessionId > 0 && it.uid >= 0 && it.uid != android.os.Process.myUid() }
            .associateBy { it.sessionId }
        attachBackoff.retain(activePlayers.keys)
        // Ownership and app-policy changes retire connections before any new SID is authorized.
        sessionList.toMap().filter { (id, session) ->
            val active = activePlayers[id]
            !processingAppPolicy.allows(session.uid, session.packageName) ||
                (active != null && (active.uid != session.uid || active.packageName != session.packageName))
        }.keys.forEach(::removeSession)
        // Remove dead/reused processes first; keep live inactive instances until a replacement exists.
        continuity.sessionsToRelease(activePlayers, processes).forEach(::removeSession)
        if (enabled) {
            activePlayers.values.forEach { player ->
                if (player.sessionId !in sessionList && processingAppPolicy.allows(player.uid, player.packageName))
                    addSession(player.sessionId, player.uid, player.packageName)
            }
        }
        continuity.sessionsToRelease(activePlayers, processes).forEach(::removeSession)
    }

    /** Unavailable probes do not count as reliable inactive-track samples. */
    fun playbackEvidenceUnavailable() {
        globalPlaybackIssue = null
        hasActiveGlobalPlayback = false
        // An unreadable root dump cannot authorize a new attachment, or prove an old one dead.
        activePlayers = emptyMap()
    }

    /** Diagnose the real global chain; never substitute a player SID for an explicit Session 0. */
    fun reconcileGlobalPlayback(snapshot: RootActivePlaybackDiscovery.Snapshot) {
        if (app.isPlayerSessionMode) return
        ensureGlobalSession()
        hasActiveGlobalPlayback = snapshot.players.isNotEmpty()
        val global = (sessionList[0] as? RemoteEffectSession)?.effect?.effect
        globalPlaybackIssue = if (enabled && closingSessions.isEmpty()) snapshot.globalEvidence?.processingIssue(
            snapshot.players.map { it.sessionId }.toSet(), runCatching { global?.id }.getOrNull()) else null
    }
}

typealias OnRootSessionChangeListener = BaseSessionDatabase.OnSessionChangeListener
