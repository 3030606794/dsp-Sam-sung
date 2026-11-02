package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.IdentityHashMap
import java.util.UUID

/** Field audition has its own owner and lifecycle; it never changes either effect's persistent switch. */
object FieldController {
    const val CAPABILITY = 256
    const val STATUS_PREFS = "field_driver_status"
    const val STATUS_ACTION = "dsp.fusion.FIELD_RUNTIME"
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
        val targets: List<Int>, val correlation: Int?, val midRms: Int?, val sideRms: Int?, val peak: Int?,
        val actualAudition: Int?, val meterReady: Boolean,
        val processing: OutputRuntimeStatus.Processing = OutputRuntimeStatus.Processing.UNKNOWN,
        val advanced: FieldAdvancedParameters.Runtime? = null,
        val visualization: NativeVisualizationTelemetry.FieldSample? = null)
    @Volatile var runtime: List<Reading> = emptyList()
        private set
    val masterEnabled get() = runtime.any { it.enabled }
    fun preferences(context: Context) = BassController.preferences(context,true)
    fun statusPreferences(context: Context) = context.getSharedPreferences(STATUS_PREFS,Context.MODE_PRIVATE)
    private fun hasCapability(context: Context, capability: Int) = statusPreferences(context).let {
        it.getString("session",null) == session && it.getInt("capabilities",0) and capability != 0
    }
    fun supported(context: Context) = hasCapability(context,CAPABILITY)
    fun advancedSupported(context: Context) = supported(context) && hasCapability(context,FieldAdvancedParameters.CAPABILITY)
    fun publishCapabilities(context: Context, capabilities: Int) {
        val prefs = statusPreferences(context)
        if (prefs.getString("session",null) != session || prefs.getInt("capabilities",-1) != capabilities)
            prefs.edit().putString("session",session).putInt("capabilities",capabilities).apply()
        if (capabilities and CAPABILITY == 0 || (audition == 4 && capabilities and FieldAdvancedParameters.CAPABILITY == 0)) stopAudition(context)
    }
    private fun changed(context: Context) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        context.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun startAudition(context: Context, owner: Any, selection: Int): Boolean {
        if (selection !in 1..4 || !supported(context) || (selection == 4 && !advancedSupported(context)) ||
            !masterEnabled || !preferences(context).getBoolean("v_field",false)) return false
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
    @Synchronized fun sync(context: Context, capabilities: Int, target: Any = context, sender: (Int,IntArray) -> Boolean): Boolean {
        val prefs = preferences(context); FieldParameters.migrate(prefs); FieldAdvancedParameters.migrate(prefs)
        val advanced = capabilities and FieldAdvancedParameters.CAPABILITY != 0
        if (!prefs.getBoolean("v_field",false) || capabilities and CAPABILITY == 0 || (audition == 4 && !advanced)) stopAudition(context)
        val values = prefs.all
        var result = true
        FieldParameters.legacy.forEachIndexed { index, control ->
            result = sender(65554+index,intArrayOf(FieldParameters.value(values,control))) && result
        }
        if (capabilities and CAPABILITY != 0) {
            if (advanced) {
                FieldAdvancedParameters.packets(values).forEach { (id,data) -> result = sender(id,data) && result }
                if (!result && audition != 0) { stopAudition(context); return false }
            }
            val packet = FieldParameters.packet(values,if (audition == 4) 0 else audition)
            val freshChoice = audition != 0 && sentAuditionRevisions[target] != auditionRevision
            // The old and difference audition protocols clear independently.
            if (advanced && audition != 4 && !sender(FieldAdvancedParameters.DIFFERENCE_PACKET,intArrayOf(0))) {
                result=false
                if (audition != 0) { stopAudition(context); return false }
            }
            // Rearm only a new explicit choice. An ordinary stale sync must not revive a native reset.
            if (audition in 1..3 && freshChoice) {
                if (!sender(FieldParameters.PACKET,packet.copyOf().apply { this[12] = 0 })) {
                    stopAudition(context)
                    return false
                }
            }
            var sent = sender(FieldParameters.PACKET,packet)
            if (sent && audition == 4) {
                if (freshChoice) sent = sender(FieldAdvancedParameters.DIFFERENCE_PACKET,intArrayOf(0))
                if (sent) sent = sender(FieldAdvancedParameters.DIFFERENCE_PACKET,intArrayOf(1))
            }
            if (sent && sentAuditionRevisions[target] != auditionRevision) {
                sentAuditionRevisions[target] = auditionRevision
                sentRevisionToken++
            } else if (!sent && audition != 0) stopAudition(context)
            result = sent && result
        }
        return result
    }

    /** Visible-page telemetry is a cache update, never an audition or session-gate mutation. */
    @Synchronized fun publishPageRuntime(context: Context, readings: List<Reading>, polled: PollState): List<Reading>? {
        if (polled.auditionRevision != auditionRevision || polled.runtimeRevision != runtimeRevision ||
            polled.sentRevisionToken != sentRevisionToken) return null
        val next = readings.map { reading ->
            if (gates[reading.identity] == false) reading.copy(enabled = false,
                processing = OutputRuntimeStatus.Processing.IDLE) else reading
        }
        if (next != runtime) {
            runtime = next
            context.sendLocalBroadcast(Intent(STATUS_ACTION))
        }
        return next
    }
}
