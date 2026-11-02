package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.IdentityHashMap
import java.util.UUID

/** Listening state and A/B snapshots belong to this page/session, never to saved presets. */
object ToneAdvancedController {
    const val STATUS_PREFS = "tone_driver_status"
    const val STATUS_ACTION = "dsp.fusion.TONE_ADVANCED_RUNTIME"
    private val session = UUID.randomUUID().toString()
    @Volatile private var owner: Any? = null
    @Volatile private var snapshotOwner: Any? = null
    @Volatile private var a: ToneAdvancedParameters.Settings? = null
    @Volatile private var b: ToneAdvancedParameters.Settings? = null
    @Volatile var audition = 0
        private set
    @Volatile var match = false
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
        val snapshot: ToneAdvancedParameters.Runtime?, val active: Boolean = false, val response: ToneAdvancedParameters.Response? = null)
    @Volatile var runtime: List<Reading> = emptyList()
        private set
    fun preferences(c: Context) = ToneController.preferences(c)
    fun statusPreferences(c: Context) = c.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
    fun supported(c: Context) = statusPreferences(c).let {
        it.getString("session", null) == session && it.getInt("capabilities", 0) and ToneAdvancedParameters.CAPABILITY != 0
    }
    fun publishCapabilities(c: Context, caps: Int) {
        val p = statusPreferences(c)
        if (p.getString("session", null) != session || p.getInt("capabilities", -1) != caps)
            p.edit().putString("session", session).putInt("capabilities", caps).apply()
        if (caps and ToneAdvancedParameters.CAPABILITY == 0) stopAudition(c, clearSnapshots = true)
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
                if (namespace != ToneAdvancedParameters.NAMESPACE || key !in setOf(ToneAdvancedParameters.METERS, ToneAdvancedParameters.RESPONSE)) put("$namespace/$key", value)
            }
    }
    private fun nextEpoch() { epoch = if (epoch == Int.MAX_VALUE) 1 else epoch + 1; revision++ }
    @Synchronized fun hasComparison(slot: Int) = if (slot == 0) a != null else slot == 1 && b != null
    fun canListen(c: Context) = supported(c) && ToneAdvancedParameters.validSavedFoundation(preferences(c).all) && ToneAdvancedParameters.enabled(preferences(c).all) &&
        ToneAdvancedParameters.moduleEnabled(preferences(c).all) && runtime.any {
            it.enabled && it.active && it.snapshot?.let { s -> s.enabled && s.professional && s.ready } == true
        }
    fun matchingReady() = runtime.filter { it.enabled && it.active }.let { rows ->
        rows.isNotEmpty() && rows.all { it.snapshot?.let { s -> s.epoch == epoch && s.measurement == 3 } == true }
    }
    @Synchronized fun saveComparison(c: Context, owner: Any, slot: Int): Boolean {
        if (slot !in 0..1 || !supported(c) || !ToneAdvancedParameters.validSavedFoundation(preferences(c).all)) return false
        stopAudition(c); previous = audioValues(c); snapshotOwner = owner
        val saved = ToneAdvancedParameters.capture(preferences(c).all)
        val sound = saved
        if (slot == 0) a = sound else b = sound
        nextEpoch(); changed(c); return true
    }
    @Synchronized fun startAudition(c: Context, owner: Any, mode: Int): Boolean {
        if (mode == 6) return measure(c, owner)
        if (mode !in 0..6 || (mode != 0 && !canListen(c)) || (mode in 4..5 && (a == null || b == null)) ||
            (mode in 4..5 && match && !matchingReady())) return false
        if (audition == mode && this.owner === owner) return true
        previous = audioValues(c); this.owner = owner; audition = mode; toolsDirty = true; revision++; changed(c); return true
    }
    @Synchronized fun measure(c: Context, owner: Any): Boolean {
        if (!canListen(c) || a == null || b == null) return false
        previous = audioValues(c); match = false; toolsDirty = true; nextEpoch(); this.owner = owner; audition = 6; changed(c); return true
    }
    @Synchronized fun setMatch(c: Context, value: Boolean): Boolean {
        if (value && (!canListen(c) || !matchingReady())) return false
        if (match != value) { match = value; revision++; changed(c) }; return true
    }
    fun stopAudition(c: Context, owner: Any? = null, clearSnapshots: Boolean = false) {
        // Idle page setup/teardown must not wait behind sync's cross-process driver writes.
        if (audition == 0 && !match && !toolsDirty && this.owner == null &&
            (!clearSnapshots || (a == null && b == null && snapshotOwner == null))) return
        stopAuditionLocked(c, owner, clearSnapshots)
    }
    @Synchronized private fun stopAuditionLocked(c: Context, owner: Any?, clearSnapshots: Boolean) {
        if (owner != null && this.owner !== owner && snapshotOwner !== owner) return
        val had = audition != 0 || match || toolsDirty || (clearSnapshots && (a != null || b != null))
        this.owner = null; audition = 0; match = false; toolsDirty = false
        if (clearSnapshots) { a = null; b = null; snapshotOwner = null }
        if (had) { nextEpoch(); changed(c) }
    }
    @Synchronized fun sessionEnabled(c: Context, id: Any, enabled: Boolean) {
        runtimeRevision++; gates[id] = enabled
        runtime = runtime.map { if (it.identity === id) it.copy(enabled = false, active = false, frames = null) else it }
        if (!enabled || runtime.none { it.enabled }) stopAudition(c, clearSnapshots = true)
        c.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun sessionClosed(c: Context, id: Any) {
        runtimeRevision++; gates.remove(id); sent.remove(id); runtime = runtime.filterNot { it.identity === id }
        stopAudition(c, clearSnapshots = true)
        c.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun publishRuntime(c: Context, readings: List<Reading>, poll: Poll = pollState()) {
        if (poll.runtime != runtimeRevision) return
        if (readings.any {r -> runtime.none {it.identity === r.identity} || runtime.any {it.identity === r.identity && it.rate != r.rate}} || runtime.any {old -> readings.none {it.identity === old.identity}}) stopAudition(c, clearSnapshots = true)
        val next = readings.map { r ->
            val old = runtime.firstOrNull { it.identity === r.identity }
            val enabled = if (gates[r.identity] == false) false else r.enabled
            if (gates[r.identity] == true) gates.remove(r.identity)
            r.copy(enabled = enabled, active = enabled && r.frames != null && old?.frames != null && r.rate == old.rate && r.frames != old.frames)
        }
        if (next.none {it.enabled && it.active}) stopAudition(c, clearSnapshots = true)
        if (poll.revision == revision && audition != 0 &&
            (next.none { it.enabled && it.active && it.snapshot?.let { s -> s.enabled && s.professional && s.ready } == true } ||
                (poll.sent == sentToken && next.any { it.enabled && sent[it.identity] == revision &&
                    it.snapshot?.let { s -> s.mode != audition || s.epoch != epoch } == true }))) stopAudition(c)
        if (next != runtime) { runtime = next; c.sendLocalBroadcast(Intent(STATUS_ACTION)) }
    }
    @Synchronized fun sync(c: Context, caps: Int, target: Any, sender: (Int, IntArray) -> Boolean): Boolean {
        val p = preferences(c); ToneAdvancedParameters.normalize(p)
        if (!ToneAdvancedParameters.validSavedFoundation(p.all)) {
            stopAudition(c, clearSnapshots = true)
            if (caps and ToneAdvancedParameters.CAPABILITY == 0) return true
            val cleared=sender(ToneAdvancedParameters.CLEAR,intArrayOf(0))
            return if(!ToneAdvancedParameters.moduleEnabled(p.all)) sender(ToneAdvancedParameters.OFF,intArrayOf(0)) && cleared else cleared
        }
        val settings = ToneAdvancedParameters.capture(p.all)
        val fingerprint = audioValues(c)
        if (previous != null && previous != fingerprint) stopAudition(c)
        previous = fingerprint
        if (!ToneAdvancedParameters.moduleEnabled(p.all) || caps and ToneAdvancedParameters.CAPABILITY == 0) stopAudition(c, clearSnapshots = true)
        else if (!settings.professional) stopAudition(c, clearSnapshots = true)
        if (caps and ToneAdvancedParameters.CAPABILITY == 0) return true
        if (!sender(ToneAdvancedParameters.PACKET, settings.packet(ToneAdvancedParameters.moduleEnabled(p.all)))) { stopAudition(c); return false }
        val fallback = ToneAdvancedParameters.defaults()
        val tools = intArrayOf(audition, if (match) 1 else 0, epoch, 0) + (a ?: fallback).packet(ToneAdvancedParameters.moduleEnabled(p.all)) + (b ?: fallback).packet(ToneAdvancedParameters.moduleEnabled(p.all))
        val okay = sender(ToneAdvancedParameters.TOOLS, tools)
        if (okay && sent[target] != revision) { sent[target] = revision; sentToken++ }
        if (!okay) stopAudition(c)
        return okay
    }
}
