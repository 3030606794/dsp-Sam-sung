package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.IdentityHashMap
import java.util.UUID

/** Channel-delay audition has its own owner and lifecycle; it never changes either effect's persistent switch. */
object DiffController {
    const val CAPABILITY = DiffParameters.CAPABILITY
    const val STATUS_PREFS = "diff_driver_status"
    const val STATUS_ACTION = "dsp.fusion.DIFF_RUNTIME"
    private val session = UUID.randomUUID().toString()
    private var owner: Any? = null
    @Volatile var audition = 0
        private set
    @Volatile var auditionRevision = 0L
        private set
    @Volatile var runtimeRevision = 0L
        private set
    @Volatile var sentRevisionToken = 0L
        private set
    private val gates = IdentityHashMap<Any, Boolean>()
    private val sentAuditionRevisions = IdentityHashMap<Any, Long>()
    data class PollState(val auditionRevision: Long, val runtimeRevision: Long, val sentRevisionToken: Long)
    @Synchronized fun pollState() = PollState(auditionRevision,runtimeRevision,sentRevisionToken)
    data class Reading(val identity: Any, val enabled: Boolean, val rate: Int, val frames: Int?,
        val snapshot: DiffRuntimeReader.Snapshot?,
        val processing: OutputRuntimeStatus.Processing = OutputRuntimeStatus.Processing.UNKNOWN) {
        val actualAudition get() = snapshot?.audition
    }
    @Volatile var runtime: List<Reading> = emptyList()
        private set
    val masterEnabled get() = runtime.any { it.enabled }
    fun preferences(context: Context) = BassController.preferences(context,true)
    fun statusPreferences(context: Context) = context.getSharedPreferences(STATUS_PREFS,Context.MODE_PRIVATE)
    private fun hasCapability(context: Context, capability: Int) = statusPreferences(context).let {
        it.getString("session",null) == session && it.getInt("capabilities",0) and capability != 0
    }
    fun supported(context: Context) = hasCapability(context,CAPABILITY)
    fun advancedSupported(context: Context)=supported(context) && hasCapability(context,DiffAdvancedParameters.CAPABILITY)
    fun publishCapabilities(context: Context, capabilities: Int) {
        val prefs = statusPreferences(context)
        if (prefs.getString("session",null) != session || prefs.getInt("capabilities",-1) != capabilities)
            prefs.edit().putString("session",session).putInt("capabilities",capabilities).apply()
        if (capabilities and CAPABILITY == 0 || (audition>=3 && capabilities and DiffAdvancedParameters.CAPABILITY==0)) stopAudition(context)
    }
    private fun changed(context: Context) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        context.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun startAudition(context: Context, owner: Any, selection: Int): Boolean {
        if (selection !in 1..4 || !supported(context) || (selection>=3 && !advancedSupported(context)) ||
            !masterEnabled || !preferences(context).getBoolean("v_diff",false)) return false
        if (this.owner === owner && audition == selection) return true
        this.owner = owner; audition = selection; auditionRevision++; changed(context)
        return true
    }
    fun stopAudition(context: Context, owner: Any? = null) {
        if (audition == 0) return
        stopAuditionLocked(context, owner)
    }
    @Synchronized private fun stopAuditionLocked(context: Context, owner: Any?) {
        if (audition == 0 || (owner != null && this.owner !== owner)) return
        this.owner = null; audition = 0; auditionRevision++; changed(context)
    }
    @Synchronized fun sessionEnabled(context: Context, identity: Any, enabled: Boolean) {
        runtimeRevision++; gates[identity] = enabled
        runtime = runtime.map { if (it.identity === identity) it.copy(enabled=false,processing=OutputRuntimeStatus.Processing.IDLE) else it }
        if (runtime.none { it.enabled }) stopAudition(context)
        context.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun sessionClosed(context: Context, identity: Any) {
        runtimeRevision++; gates.remove(identity); sentAuditionRevisions.remove(identity)
        runtime = runtime.filterNot { it.identity === identity }
        if (runtime.none { it.enabled }) stopAudition(context)
        context.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun publishRuntime(context: Context, readings: List<Reading>, polledAuditionRevision: Long = auditionRevision,
        polledRuntimeRevision: Long = runtimeRevision, polledSentRevisionToken: Long = sentRevisionToken) {
        if (polledRuntimeRevision != runtimeRevision) return
        val next = readings.map { reading ->
            val gated = when (gates[reading.identity]) {
                false -> reading.copy(enabled=false)
                true -> { gates.remove(reading.identity); reading }
                null -> reading
            }
            val before = runtime.firstOrNull { it.identity === reading.identity }
            gated.copy(processing = when {
                !gated.enabled -> OutputRuntimeStatus.Processing.IDLE
                gated.frames == null || before?.frames == null || gated.rate != before.rate -> OutputRuntimeStatus.Processing.UNKNOWN
                gated.frames != before.frames -> OutputRuntimeStatus.Processing.ACTIVE
                else -> OutputRuntimeStatus.Processing.IDLE
            })
        }
        if (polledAuditionRevision == auditionRevision && audition != 0 &&
            (next.none { it.enabled } || (polledSentRevisionToken == sentRevisionToken && next.any {
                it.enabled && sentAuditionRevisions[it.identity] == auditionRevision &&
                    it.actualAudition != null && it.actualAudition != audition
            })))
            stopAudition(context)
        if (next != runtime) { runtime = next; context.sendLocalBroadcast(Intent(STATUS_ACTION)) }
    }
    /** Optional page telemetry updates display caches without acknowledging or ending auditions. */
    @Synchronized fun publishPageRuntime(context: Context, readings: List<Reading>, polled: PollState): Boolean {
        if (polled.auditionRevision != auditionRevision || polled.runtimeRevision != runtimeRevision ||
            polled.sentRevisionToken != sentRevisionToken) return false
        val next = readings.map { reading ->
            if (gates[reading.identity] == false)
                reading.copy(enabled = false, processing = OutputRuntimeStatus.Processing.IDLE)
            else reading
        }
        if (next != runtime) { runtime = next; context.sendLocalBroadcast(Intent(STATUS_ACTION)) }
        return true
    }

    @Synchronized fun sync(context: Context, capabilities: Int, target: Any = context, sender: (Int,IntArray) -> Boolean): Boolean {
        val prefs=preferences(context); DiffParameters.migrate(prefs);DiffAdvancedParameters.migrate(prefs)
        val supported=capabilities and CAPABILITY != 0
        val advanced=capabilities and DiffAdvancedParameters.CAPABILITY!=0
        if(!prefs.getBoolean("v_diff",false) || !supported || (audition>=3 && !advanced)) stopAudition(context)
        val values=prefs.all
        val legacy=sender(65558,intArrayOf(DiffParameters.value(values,DiffParameters.legacy)*100))
        if(!supported)return legacy
        val configured=sender(DiffParameters.PACKET,DiffParameters.packet(values))
        val second=if(advanced)sender(DiffAdvancedParameters.PACKET,DiffAdvancedParameters.packet(values)) else true
        // Configure before enabling, but enable before rearming a newly selected audition.
        val enabled=sender(65557,intArrayOf(if(prefs.getBoolean("v_diff",false))1 else 0))
        if((!legacy || !configured || !second || !enabled) && audition!=0) { stopAudition(context); return false }
        val fresh=audition!=0 && sentAuditionRevisions[target]!=auditionRevision
        val auditionPacket=if(advanced)DiffAdvancedParameters.AUDITION_PACKET else DiffParameters.AUDITION_PACKET
        if(fresh && !sender(auditionPacket,intArrayOf(0))) { stopAudition(context); return false }
        val sent=sender(auditionPacket,intArrayOf(audition))
        if(sent && sentAuditionRevisions[target]!=auditionRevision) {
            sentAuditionRevisions[target]=auditionRevision; sentRevisionToken++
        } else if(!sent && audition!=0) stopAudition(context)
        return legacy && configured && second && enabled && sent
    }
}
