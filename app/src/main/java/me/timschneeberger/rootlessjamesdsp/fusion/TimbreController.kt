package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.IdentityHashMap
import java.util.UUID

/** Listening state and A/B snapshots belong to this page/session, never to saved presets. */
object TimbreController {
    const val STATUS_PREFS = "timbre_driver_status"
    const val STATUS_ACTION = "dsp.fusion.TIMBRE_RUNTIME"
    private val session = UUID.randomUUID().toString()
    @Volatile private var owner: Any? = null
    @Volatile private var snapshotOwner: Any? = null
    @Volatile private var a: TimbreParameters.Settings? = null
    @Volatile private var b: TimbreParameters.Settings? = null
    @Volatile var audition = 0
        private set
    @Volatile var match = false
        private set
    @Volatile var soloGain = 0
        private set
    @Volatile var epoch = 1
        private set
    @Volatile private var revision = 0L
    @Volatile private var runtimeRevision = 0L
    private var sentToken = 0L
    private var previous: Map<String, Any?>? = null
    @Volatile private var toolsDirty = false
    private val sent = IdentityHashMap<Any, Long>()
    private val gates = IdentityHashMap<Any, Boolean>()
    data class Poll(val revision: Long, val runtime: Long, val sent: Long)
    @Synchronized fun pollState() = Poll(revision, runtimeRevision, sentToken)
    data class Reading(val identity: Any, val enabled: Boolean, val frames: Int?, val rate: Int,
        val snapshot: TimbreParameters.Runtime?, val active: Boolean = false)
    @Volatile var runtime: List<Reading> = emptyList()
        private set
    fun preferences(c: Context) = PreferenceCache.getPreferences(c, TimbreParameters.NAMESPACE)
    fun statusPreferences(c: Context) = c.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
    fun supported(c: Context) = statusPreferences(c).let {
        it.getString("session", null) == session && it.getInt("capabilities", 0) and TimbreParameters.CAPABILITY != 0
    }
    fun supportedExtended(c: Context) = supported(c) && statusPreferences(c).getInt("capabilities", 0) and TimbreParameters.EXTENDED_CAPABILITY != 0
    fun publishCapabilities(c: Context, caps: Int) {
        val p = statusPreferences(c)
        if (p.getString("session", null) != session || p.getInt("capabilities", -1) != caps)
            p.edit().putString("session", session).putInt("capabilities", caps).apply()
        if (caps and TimbreParameters.CAPABILITY == 0) stopAudition(c, clearSnapshots = true)
        else if (caps and TimbreParameters.EXTENDED_CAPABILITY == 0 && (soloGain != 0 || audition == 11)) stopAudition(c)
    }
    private fun changed(c: Context) {
        c.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        c.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    private fun audioValues(c: Context): Map<String, Any?> = buildMap {
        for (namespace in listOf(Constants.PREF_BASS, Constants.PREF_COMPANDER, Constants.PREF_CONVOLVER,
            Constants.PREF_CROSSFEED, "dsp_fusion_viper", Constants.PREF_DDC, Constants.PREF_EQ, Constants.PREF_GEQ,
            Constants.PREF_LIVEPROG, Constants.PREF_OUTPUT, Constants.PREF_REVERB, Constants.PREF_STEREOWIDE,
            Constants.PREF_TUBE, "dsp_fusion_tone", "dsp_fusion_surround", "dsp_fusion_peq"))
            PreferenceCache.getPreferences(c, namespace).all.forEach { (key, value) ->
                if (namespace != TimbreParameters.NAMESPACE || key != TimbreParameters.METERS) put("$namespace/$key", value)
            }
    }
    private fun nextEpoch() { epoch = if (epoch == Int.MAX_VALUE) 1 else epoch + 1; revision++ }
    @Synchronized fun hasComparison(slot: Int) = if (slot == 0) a != null else slot == 1 && b != null
    fun canListen(c: Context) = supported(c) && TimbreParameters.enabled(preferences(c).all) &&
        TimbreParameters.moduleEnabled(preferences(c).all) && runtime.any {
            it.enabled && it.active && it.snapshot?.let { s -> s.enabled && s.professional && s.ready } == true
        }
    fun matchingReady() = runtime.filter { it.enabled && it.active }.let { rows ->
        rows.isNotEmpty() && rows.all { it.snapshot?.let { s -> s.epoch == epoch && s.measurement == 3 } == true }
    }
    @Synchronized fun saveComparison(c: Context, owner: Any, slot: Int): Boolean {
        if (slot !in 0..1 || !supported(c)) return false
        stopAudition(c); previous = audioValues(c); snapshotOwner = owner
        val saved = TimbreParameters.capture(preferences(c).all)
        val sound = if (saved.professional) saved else TimbreParameters.defaults()
        if (slot == 0) a = sound else b = sound
        nextEpoch(); changed(c); return true
    }
    @Synchronized fun startAudition(c: Context, owner: Any, mode: Int): Boolean {
        if (mode == 6) return measure(c, owner)
        if (mode !in 0..11 || (mode == 11 && !supportedExtended(c)) || (mode != 0 && !canListen(c)) || (mode in 4..5 && (a == null || b == null)) ||
            (mode in 4..5 && match && !matchingReady())) return false
        if (audition == mode && this.owner === owner) return true
        previous = audioValues(c); this.owner = owner; audition = mode
        if (mode !in 7..11) soloGain = 0
        toolsDirty = true; revision++; changed(c); return true
    }
    @Synchronized fun setSoloGain(c: Context, value: Int): Boolean {
        if (value !in listOf(0, 60, 120, 180) || (value != 0 && (!supportedExtended(c) || !canListen(c) || audition !in 7..11))) return false
        if (soloGain != value) { soloGain = value; toolsDirty = true; revision++; changed(c) }
        return true
    }
    @Synchronized fun measure(c: Context, owner: Any): Boolean {
        if (!canListen(c) || a == null || b == null) return false
        previous = audioValues(c); match = false; soloGain = 0; toolsDirty = true; nextEpoch(); this.owner = owner; audition = 6; changed(c); return true
    }
    @Synchronized fun setMatch(c: Context, value: Boolean): Boolean {
        if (value && (!canListen(c) || !matchingReady())) return false
        if (match != value) { match = value; revision++; changed(c) }; return true
    }
    fun stopAudition(c: Context, owner: Any? = null, clearSnapshots: Boolean = false) {
        if (audition == 0 && !match && soloGain == 0 && !toolsDirty && this.owner == null &&
            (!clearSnapshots || (a == null && b == null && snapshotOwner == null))) return
        stopAuditionLocked(c, owner, clearSnapshots)
    }
    @Synchronized private fun stopAuditionLocked(c: Context, owner: Any?, clearSnapshots: Boolean) {
        if (owner != null && this.owner !== owner && snapshotOwner !== owner) return
        val had = audition != 0 || match || soloGain != 0 || toolsDirty || (clearSnapshots && (a != null || b != null))
        this.owner = null; audition = 0; match = false; soloGain = 0; toolsDirty = false
        if (clearSnapshots) { a = null; b = null; snapshotOwner = null }
        if (had) { nextEpoch(); changed(c) }
    }
    @Synchronized fun sessionEnabled(c: Context, id: Any, enabled: Boolean) {
        runtimeRevision++; gates[id] = enabled
        runtime = runtime.map { if (it.identity === id) it.copy(enabled = false, active = false, frames = null) else it }
        if (runtime.none { it.enabled }) stopAudition(c)
        c.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun sessionClosed(c: Context, id: Any) {
        runtimeRevision++; gates.remove(id); sent.remove(id); runtime = runtime.filterNot { it.identity === id }
        if (runtime.none { it.enabled }) stopAudition(c)
        c.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun publishRuntime(c: Context, readings: List<Reading>, poll: Poll = pollState()) {
        if (poll.runtime != runtimeRevision) return
        val next = readings.map { r ->
            val old = runtime.firstOrNull { it.identity === r.identity }
            val enabled = if (gates[r.identity] == false) false else r.enabled
            if (gates[r.identity] == true) gates.remove(r.identity)
            r.copy(enabled = enabled, active = enabled && r.frames != null && old?.frames != null && r.rate == old.rate && r.frames != old.frames)
        }
        if (poll.revision == revision && audition != 0 &&
            (next.none { it.enabled && it.active && it.snapshot?.let { s -> s.enabled && s.professional && s.ready } == true } ||
                (poll.sent == sentToken && next.any { it.enabled && sent[it.identity] == revision &&
                    it.snapshot?.let { s -> s.mode != audition || s.epoch != epoch } == true }))) stopAudition(c)
        if (next != runtime) { runtime = next; c.sendLocalBroadcast(Intent(STATUS_ACTION)) }
    }
    @Synchronized fun sync(c: Context, caps: Int, target: Any, sender: (Int, IntArray) -> Boolean): Boolean {
        val p = preferences(c); TimbreParameters.normalize(p)
        val settings = TimbreParameters.capture(p.all)
        val fingerprint = audioValues(c)
        if (previous != null && previous != fingerprint) stopAudition(c)
        previous = fingerprint
        if (!TimbreParameters.moduleEnabled(p.all) || !settings.professional || caps and TimbreParameters.CAPABILITY == 0) stopAudition(c)
        if (caps and TimbreParameters.CAPABILITY == 0) return true
        val extended = caps and TimbreParameters.EXTENDED_CAPABILITY != 0
        if (!extended && (audition == 11 || soloGain != 0)) stopAudition(c)
        fun compatible(s: TimbreParameters.Settings) = if (extended) s else s.copy(amount = s.amount.coerceAtMost(2000))
        if (!sender(TimbreParameters.PACKET, compatible(settings).packet())) { stopAudition(c); return false }
        if (!sender(1206, intArrayOf(if (TimbreParameters.moduleEnabled(p.all)) 1 else 0))) { stopAudition(c); return false }
        val fallback = TimbreParameters.defaults()
        val tools = intArrayOf(audition, if (match) 1 else 0, epoch, if (extended && audition in 7..11) soloGain else 0) +
            compatible(a ?: fallback).packet() + compatible(b ?: fallback).packet()
        val okay = sender(TimbreParameters.TOOLS, tools)
        if (okay && sent[target] != revision) { sent[target] = revision; sentToken++ }
        if (!okay) stopAudition(c)
        return okay
    }
}
