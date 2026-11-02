package me.timschneeberger.rootlessjamesdsp.expansion

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object ExpansionEffectsController {
    const val DRIVER_REQUIRED = "新增音效需要配套驱动，请更新驱动并重启手机"
    const val MATCH_PROFILE = "match_learned_profile"
    private val captureEpochs = IntArray(2)
    private val learningMonitor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "match-eq-learning").apply { isDaemon = true }
    }
    private var learningTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var learningGeneration = 0L
    private val profileGeneration = java.util.concurrent.atomic.AtomicLong()
    private val profileWriteLock = Any()
    fun preferences(context: Context, kind: ExpansionEffectsKind): SharedPreferences =
        PreferenceCache.getPreferences(context, kind.prefsName).also {
            if (kind == ExpansionEffectsKind.SMART_ADAPT) SmartAdaptState.migrate(it)
        }
    fun notifyChanged(context: Context) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }
    fun restoreSpeakerClassic(context: Context) {
        val edit = preferences(context, ExpansionEffectsKind.SPEAKER_OPTIMIZATION).edit()
        SpeakerOptimizationProtocol.classicValues().forEach { (key, value) -> edit.putInt(key, value) }
        edit.apply()
        notifyChanged(context)
    }
    fun packet(context: Context, kind: ExpansionEffectsKind): IntArray {
        val packet = ExpansionEffectsParameters.packet(kind, preferences(context, kind).all)
        if (kind == ExpansionEffectsKind.LOUDNESS) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            packet[3] = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 1000.0 / maximum).toInt().coerceIn(0, 1000)
        }
        if (kind == ExpansionEffectsKind.MATCH_EQ) synchronized(captureEpochs) {
            packet[9] = captureEpochs[0] * 1000
            packet[10] = captureEpochs[1] * 1000
        }
        HeadTrackerBridge.patchPacket(context, kind, packet)
        if (kind == ExpansionEffectsKind.SPEAKER_OPTIMIZATION) SpeakerRouteBridge.patch(packet)
        if (kind == ExpansionEffectsKind.SMART_ADAPT) SpeakerRouteBridge.patchSmart(packet)
        return packet
    }
    fun supported(context: Context, kind: ExpansionEffectsKind): Boolean? {
        val states = engines(context).mapNotNull { it.expansionEffectsSupported(kind) }
        return if (states.isEmpty()) null else states.all { it }
    }
    fun sync(context: Context, supported: Boolean, speakerSupported: Boolean, smartSupported: Boolean, sender: (Int, IntArray) -> Boolean): Boolean {
        bindSmartState(context)
        if (!supported && !speakerSupported && !smartSupported) return true
        bindVolume(context)
        HeadTrackerBridge.update(context)
        var success = true
        if (supported) {
            val profile = matchProfile(preferences(context, ExpansionEffectsKind.MATCH_EQ).getString(MATCH_PROFILE, null)) ?: IntArray(64)
            success = sender(196761, profile) && success
        }
        ExpansionEffectsKind.values().filter {
            SpeakerOptimizationProtocol.supported(it, supported, speakerSupported, smartSupported) == true
        }.forEach { success = sender(it.parameter, packet(context, it)) && success }
        return success
    }

    private val smartGeneration = java.util.concurrent.atomic.AtomicLong()
    private var smartPreferences: SharedPreferences? = null
    private val smartListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == ExpansionEffectsKind.SMART_ADAPT.enableKey ||
            key in SmartAdaptState.PREFERENCE_KEYS || key.matches(Regex("p(?:[0-9]|1[0-6])"))) {
            smartGeneration.incrementAndGet()
            if (key == null || key == "p0" || key == SmartAdaptIntensity.KEY) SmartAdaptState.clearInvalid(prefs)
        }
    }
    private fun bindSmartState(context: Context) = synchronized(this) {
        val prefs = preferences(context, ExpansionEffectsKind.SMART_ADAPT)
        if (smartPreferences !== prefs) {
            smartPreferences?.unregisterOnSharedPreferenceChangeListener(smartListener)
            smartPreferences = prefs
            prefs.registerOnSharedPreferenceChangeListener(smartListener)
            smartGeneration.incrementAndGet()
            SmartAdaptState.clearInvalid(prefs)
        }
    }

    internal data class SmartCapture(val snapshot: SmartAdaptState.Snapshot, val packet: IntArray,
        val preferenceGeneration: Long, val routeGeneration: Long, val capturedAt: Long,
        val engine: JamesDspRemoteEngine, val identity: Any)

    private fun smartPlaybackBlocked(context: Context): Boolean {
        val app = context.applicationContext as? MainApplication ?: return true
        return !app.isPlayerSessionMode && app.rootSessionDatabase?.globalPlaybackIssue in
            listOf(GlobalPlaybackEvidence.Reason.SUSPENDED, GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT)
    }

    /** Two native observations establish progress without relying on a visible chart's polling. */
    internal suspend fun captureSmartAdapt(context: Context): SmartCapture? = withContext(Dispatchers.IO) {
        bindSmartState(context)
        val kind = ExpansionEffectsKind.SMART_ADAPT
        val revision = smartGeneration.get()
        val route = SpeakerRouteBridge.snapshot()
        val expected = packet(context, kind)
        if (expected[1] != 1 || expected[110] != 0 || smartPlaybackBlocked(context)) return@withContext null
        val initialEngines = engines(context)
        val first = initialEngines.mapNotNull { it.expansionEffectsRuntime(kind) }
        val start = SystemClock.elapsedRealtime()
        delay(240)
        val live = engines(context)
        val second = live.mapNotNull { engine -> engine.expansionEffectsRuntime(kind)?.let { engine to it } }
        val elapsed = SystemClock.elapsedRealtime() - start
        if (elapsed !in 1..2000 || live.size != initialEngines.size || live.any { candidate -> initialEngines.none { it === candidate } } ||
            revision != smartGeneration.get() ||
            route.generation != SpeakerRouteBridge.snapshot().generation ||
            !expected.contentEquals(packet(context, kind)) || smartPlaybackBlocked(context)) return@withContext null
        val selected = SmartAdaptCapture.select(first, second.map { it.second }, expected) ?: return@withContext null
        val engine = second.singleOrNull { it.second.first === selected.first }?.first ?: return@withContext null
        if (initialEngines.none { it === engine }) return@withContext null
        SmartCapture(selected.second, expected, revision, route.generation, SystemClock.elapsedRealtime(), engine, selected.first)
    }

    /** Called on the UI thread immediately after capture; changes made during the read invalidate it. */
    internal fun saveSmartCapture(context: Context, capture: SmartCapture): Boolean {
        if (capture.preferenceGeneration != smartGeneration.get() ||
            capture.routeGeneration != SpeakerRouteBridge.snapshot().generation ||
            SystemClock.elapsedRealtime() - capture.capturedAt !in 0..1500 ||
            !capture.packet.contentEquals(packet(context, ExpansionEffectsKind.SMART_ADAPT)) ||
            smartPlaybackBlocked(context) || engines(context).none { it === capture.engine } ||
            !capture.engine.expansionIdentityMatches(capture.identity)) return false
        SmartAdaptState.save(preferences(context, ExpansionEffectsKind.SMART_ADAPT), capture.snapshot)
        notifyChanged(context)
        return true
    }

    fun resumeSmartAdapt(context: Context) {
        SmartAdaptState.clear(preferences(context, ExpansionEffectsKind.SMART_ADAPT))
        notifyChanged(context)
    }

    fun restoreSmartDefaults(context: Context) {
        val prefs = preferences(context, ExpansionEffectsKind.SMART_ADAPT)
        val edit = prefs.edit()
        SmartAdaptState.PREFERENCE_KEYS.forEach(edit::remove)
        ExpansionEffectsParameters.controls(ExpansionEffectsKind.SMART_ADAPT).forEach { edit.putInt(it.key, it.default) }
        edit.apply()
        notifyChanged(context)
    }
    fun learn(context: Context, reference: Boolean) {
        synchronized(captureEpochs) {
            val index = if (reference) 0 else 1
            captureEpochs[index] = (captureEpochs.maxOrNull() ?: 0) % 1_999_999 + 1
        }
        preferences(context, ExpansionEffectsKind.MATCH_EQ).edit().putBoolean(ExpansionEffectsKind.MATCH_EQ.enableKey, true).apply()
        notifyChanged(context)
        val app = context.applicationContext
        val started = SystemClock.elapsedRealtime()
        synchronized(learningMonitor) {
            learningTask?.cancel(false)
            val generation = ++learningGeneration
            var observedCapture = false
            learningTask = learningMonitor.scheduleWithFixedDelay({
                runCatching {
                    val sample = currentRuntime(app, ExpansionEffectsKind.MATCH_EQ)
                    if (sample?.isProcessing == true && sample.meter(6) > .5f) observedCapture = true
                    val complete = observedCapture && sample?.isProcessing == true && sample.meter(6) == 0f
                    if (complete || SystemClock.elapsedRealtime() - started > 180_000 ||
                        !preferences(app, ExpansionEffectsKind.MATCH_EQ).getBoolean(ExpansionEffectsKind.MATCH_EQ.enableKey, false)) {
                        synchronized(learningMonitor) {
                            if (generation == learningGeneration) { learningTask?.cancel(false); learningTask = null }
                        }
                    }
                }
            }, 300, 400, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
    fun clearLearned(context: Context) {
        synchronized(learningMonitor) { ++learningGeneration; learningTask?.cancel(false); learningTask = null }
        synchronized(captureEpochs) { captureEpochs.fill(0) }
        engines(context).forEach { it.expansionInvalidateLearning() }
        synchronized(profileWriteLock) {
            profileGeneration.incrementAndGet()
            preferences(context, ExpansionEffectsKind.MATCH_EQ).edit().putString(MATCH_PROFILE, IntArray(64).joinToString(",")).apply()
        }
        notifyChanged(context)
    }
    fun resetTransient(context: Context) {
        synchronized(learningMonitor) { ++learningGeneration; learningTask?.cancel(false); learningTask = null }
        synchronized(captureEpochs) { captureEpochs.fill(0) }
        engines(context).forEach { it.expansionInvalidateLearning() }
        synchronized(profileWriteLock) { profileGeneration.incrementAndGet() }
    }
    fun matchProfile(value: String?): IntArray? {
        val words = value?.split(',')?.map { it.toIntOrNull() ?: return null }?.toIntArray() ?: return null
        if (words.size != 64 || words.any { !Float.fromBits(it).isFinite() || Float.fromBits(it) !in 0f..10000f }) return null
        return words
    }
    /** Save only actual completed native learning, never chart previews or transient capture tokens. */
    private fun preserveMatch(context: Context, runtime: ExpansionEffectsRuntime, expectedGeneration: Long) {
        if (!runtime.isProcessing || runtime.meter(6) != 0f || runtime.meter(4) == 0f && runtime.meter(5) == 0f) return
        val live = engines(context)
        if (live.size != 1) return
        val words = live.single().expansionMatchProfile() ?: return
        val encoded = words.joinToString(",")
        if (matchProfile(encoded) == null) return
        synchronized(profileWriteLock) {
            if (expectedGeneration != profileGeneration.get()) return
            val prefs = preferences(context, ExpansionEffectsKind.MATCH_EQ)
            if (prefs.getString(MATCH_PROFILE, null) != encoded) prefs.edit().putString(MATCH_PROFILE, encoded).apply()
        }
    }
    @Volatile private var volumeBound = false
    private fun bindVolume(context: Context) = synchronized(this) {
        if (volumeBound) return@synchronized
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (preferences(context, ExpansionEffectsKind.LOUDNESS).getBoolean(ExpansionEffectsKind.LOUDNESS.enableKey, false)) notifyChanged(context)
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(app, receiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        volumeBound = true
    }
    fun command(context: Context, parameter: Int, words: IntArray): Boolean {
        val live = engines(context)
        if (live.isEmpty()) return false
        return live.map { it.expansionCommand(parameter, words) }.all { it }
    }
    private data class Previous(val frames: Int, val rate: Int, val at: Long, val enabled: Boolean)
    private val history = ExpansionEffectsKind.values().associateWith { IdentityHashMap<Any, Previous>() }
    /** No synthetic activity: a current hash and advancing native frames are both required. */
    fun currentRuntime(context: Context, kind: ExpansionEffectsKind): ExpansionEffectsRuntime? = synchronized(history) {
        val app = context.applicationContext as? MainApplication ?: return@synchronized null
        val now = SystemClock.elapsedRealtime()
        val profileEpoch = profileGeneration.get()
        val expected = packet(context, kind)
        val previous = history.getValue(kind)
        val rows = engines(context).mapNotNull { it.expansionEffectsRuntime(kind) }
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
                runtime.parameterHash != 0 && runtime.parameterHash == ExpansionEffectsParameters.hash(kind, expected))
        }
        val active = snapshots.filter { it.isProcessing }
        val selected = if (active.size > 1) null else active.singleOrNull() ?: snapshots.singleOrNull()?.copy(isProcessing = false)
        if (kind == ExpansionEffectsKind.MATCH_EQ && selected != null && profileEpoch == profileGeneration.get()) preserveMatch(context, selected, profileEpoch)
        selected
    }
    private fun engines(context: Context): List<JamesDspRemoteEngine> =
        (context.applicationContext as? MainApplication)?.rootSessionDatabase?.sessionList?.toList()
            ?.mapNotNull { (_, session) -> (session as? RemoteEffectSession)?.effect }.orEmpty()
}
