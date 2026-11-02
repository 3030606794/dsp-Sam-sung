package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.UUID
import java.util.IdentityHashMap

/** Audition belongs to a visible card, never to a profile or preset. */
object HighFrequencyController {
    const val CAPABILITY = 64
    const val STATUS_PREFS = "high_frequency_driver_status"
    const val STATUS_ACTION = "dsp.fusion.HIGH_FREQUENCY_RUNTIME"
    private val session = UUID.randomUUID().toString()
    @Volatile private var auditionOwner: Any? = null
    @Volatile var auditionRevision = 0L
        private set
    @Volatile var runtimeRevision = 0L
        private set
    @Volatile var sentRevisionToken = 0L
        private set
    private val sentAuditionRevisions = IdentityHashMap<Any, Long>()
    data class PollState(val auditionRevision: Long, val runtimeRevision: Long, val sentRevisionToken: Long)
    @Synchronized fun pollState() = PollState(auditionRevision, runtimeRevision, sentRevisionToken)
    // false: known disabled; true: enabled but waiting for a poll begun after that change.
    private val sessionGates = IdentityHashMap<Any, Boolean>()
    val audition: Boolean get() = auditionOwner != null
    data class Reading(val identity: Any, val enabled: Boolean, val rate: Int, val targets: List<Int>, val audition: Int?,
        val advancedTargets: List<Int> = emptyList(), val frames: Int? = null,
        val processing: OutputRuntimeStatus.Processing = OutputRuntimeStatus.Processing.UNKNOWN,
        val spectrumEnergyDbfs: Double? = null, val clarityEnhancementDb: Double? = null,
        val visualization: NativeVisualizationTelemetry.EnergySample? = null)
    @Volatile var runtime: List<Reading> = emptyList()
        private set
    val singleRate: Int? get() = runtime.map { it.rate }.distinct().singleOrNull()?.takeIf { it in 8000..768000 }
    val masterEnabled: Boolean get() = runtime.any { it.enabled }

    fun preferences(context: Context): SharedPreferences = BassController.preferences(context, true)
    fun statusPreferences(context: Context): SharedPreferences = context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
    fun supported(context: Context): Boolean = statusPreferences(context).let {
        it.getString("session", null) == session && it.getInt("capabilities", 0) and CAPABILITY != 0
    }
    fun advancedSupported(context: Context): Boolean = statusPreferences(context).let {
        it.getString("session", null) == session && it.getInt("capabilities", 0) and HighFrequencyAdvancedParameters.CAPABILITY != 0
    }
    fun publishCapabilities(context: Context, capabilities: Int) {
        val prefs = statusPreferences(context)
        if (prefs.getString("session", null) != session || prefs.getInt("capabilities", -1) != capabilities)
            prefs.edit().putString("session", session).putInt("capabilities", capabilities).apply()
        if (capabilities and CAPABILITY == 0) stopAudition(context)
    }
    @Synchronized fun startAudition(context: Context, owner: Any): Boolean {
        if (!supported(context) || !masterEnabled || !preferences(context).getBoolean("v_spectrum", false)) return false
        if (auditionOwner === owner) return true
        auditionOwner = owner
        auditionRevision++
        changed(context)
        return true
    }
    fun stopAudition(context: Context, owner: Any? = null) {
        if (auditionOwner == null) return
        stopAuditionLocked(context, owner)
    }
    @Synchronized private fun stopAuditionLocked(context: Context, owner: Any?) {
        if (auditionOwner == null || (owner != null && auditionOwner !== owner)) return
        auditionOwner = null
        auditionRevision++
        changed(context)
    }
    private fun changed(context: Context) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        context.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun sessionEnabled(context: Context, identity: Any, enabled: Boolean) {
        runtimeRevision++
        sessionGates[identity] = enabled
        runtime = runtime.map { if (it.identity === identity) it.copy(enabled = false, processing = OutputRuntimeStatus.Processing.IDLE) else it }
        if (runtime.none { it.enabled }) stopAudition(context)
        context.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun sessionClosed(context: Context, identity: Any) {
        runtimeRevision++
        sessionGates.remove(identity)
        sentAuditionRevisions.remove(identity)
        runtime = runtime.filterNot { it.identity === identity }
        if (runtime.none { it.enabled }) stopAudition(context)
        context.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun publishRuntime(context: Context, readings: List<Reading>, polledRevision: Long = auditionRevision,
        polledRuntimeRevision: Long = runtimeRevision, polledSentRevisionToken: Long = sentRevisionToken) {
        // In-flight reads from before an enable/disable/close must not revive a stale session.
        if (polledRuntimeRevision != runtimeRevision) return
        val effective = readings.map { reading ->
            val gated = when (sessionGates[reading.identity]) {
                false -> reading.copy(enabled = false)
                true -> { sessionGates.remove(reading.identity); reading }
                null -> reading
            }
            val before = runtime.firstOrNull { it.identity === reading.identity }
            gated.copy(processing = when {
                !gated.enabled -> OutputRuntimeStatus.Processing.IDLE
                gated.frames == null || before?.frames == null || before.rate != gated.rate -> OutputRuntimeStatus.Processing.UNKNOWN
                gated.frames != before.frames -> OutputRuntimeStatus.Processing.ACTIVE
                else -> OutputRuntimeStatus.Processing.IDLE
            })
        }
        // A poll before the request was sent (or spanning that send) is not its acknowledgement.
        if (polledRevision == auditionRevision && audition &&
            (effective.none { it.enabled } || (polledSentRevisionToken == sentRevisionToken && effective.any {
                it.enabled && sentAuditionRevisions[it.identity] == auditionRevision && it.audition == 0
            })))
            stopAudition(context)
        if (runtime != effective) {
            runtime = effective
            context.sendLocalBroadcast(Intent(STATUS_ACTION))
        }
    }
    /** Display-only cache publication: never acknowledge auditions or mutate session gates. */
    @Synchronized fun publishPageRuntime(context: Context, readings: List<Reading>, polled: PollState): Boolean {
        if (polled.auditionRevision != auditionRevision || polled.runtimeRevision != runtimeRevision ||
            polled.sentRevisionToken != sentRevisionToken) return false
        val effective = readings.map { reading ->
            if (sessionGates[reading.identity] == false)
                reading.copy(enabled = false, processing = OutputRuntimeStatus.Processing.IDLE)
            else reading
        }
        if (runtime != effective) {
            runtime = effective
            context.sendLocalBroadcast(Intent(STATUS_ACTION))
        }
        return true
    }

    fun sync(context: Context, capabilities: Int, sender: (Int, IntArray) -> Boolean): Boolean =
        sync(context, capabilities, context, sender)

    @Synchronized fun sync(context: Context, capabilities: Int, target: Any, sender: (Int, IntArray) -> Boolean): Boolean {
        val prefs = preferences(context)
        HighFrequencyParameters.migrate(prefs)
        if (!prefs.getBoolean("v_spectrum", false) || capabilities and CAPABILITY == 0) stopAudition(context)
        val values = prefs.all
        return if (capabilities and CAPABILITY != 0) {
            val clarity = sender(HighFrequencyParameters.CLARITY_PACKET, HighFrequencyParameters.clarityPacket(values))
            val freshChoice = audition && sentAuditionRevisions[target] != auditionRevision
            // Native cancellation intentionally requires a new explicit 0 -> 1 request edge.
            // Rearm once for this user choice; ordinary sync must not revive an expired audition.
            if (freshChoice && !sender(HighFrequencyParameters.SPECTRUM_PACKET,
                    HighFrequencyParameters.spectrumPacket(values, false))) {
                stopAudition(context)
                return false
            }
            val spectrum = sender(HighFrequencyParameters.SPECTRUM_PACKET,
                HighFrequencyParameters.spectrumPacket(values, audition))
            var result = spectrum && clarity
            if (capabilities and HighFrequencyAdvancedParameters.CAPABILITY != 0) {
                val a = HighFrequencyAdvancedParameters
                listOf(a.NATURAL_PACKET to a.naturalPacket(values), a.NONLINEAR_PACKET to a.nonlinearPacket(values),
                    a.DYNAMIC_PACKET to a.dynamicPacket(values), a.DEESS_PACKET to a.deessPacket(values)).forEach { (id, data) ->
                    result = sender(id, data) && result
                }
            }
            if (result && sentAuditionRevisions[target] != auditionRevision) {
                sentAuditionRevisions[target] = auditionRevision
                sentRevisionToken++
            } else if (!result && audition) stopAudition(context)
            result
        } else {
            val clarity = sender(65580, intArrayOf(HighFrequencyParameters.value(values, HighFrequencyParameters.clarityAmount)))
            val frequency = sender(65549, intArrayOf(HighFrequencyParameters.value(values, HighFrequencyParameters.spectrumFrequency)))
            sender(65550, intArrayOf(HighFrequencyParameters.legacySpectrumAmount(values))) && clarity && frequency
        }
    }
}
