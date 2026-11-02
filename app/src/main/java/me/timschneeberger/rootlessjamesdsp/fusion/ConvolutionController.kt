package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.IdentityHashMap
import java.util.UUID

/** Convolution audition has its own owner and lifecycle; persistent power and IR upload belong to the engine. */
object ConvolutionController {
    enum class Preparation { WAITING, PROCESSING, STAGED, READY, FAILED }
    /** All arrays describe the generated working IR, never an assumed realtime measurement. */
    data class PreparedIr(
        val sourcePath: String,
        val sourceSampleRate: Int,
        val processingSampleRate: Int,
        val channels: Int,
        val sourceFrames: Long,
        val workingFrames: Int,
        val crc: Int,
        val normalizationGainDb: Double = 0.0,
        val status: Preparation = Preparation.READY,
        val message: String = "",
        val waveform: List<FloatArray> = emptyList(),
        val frequencyHz: FloatArray = floatArrayOf(),
        val magnitudesDb: List<FloatArray> = emptyList(),
        val retainedStartFrames: Int = 0,
        val fadeInFrames: Int = 0,
        val fadeOutFrames: Int = 0,
        val originalPeakDb: Double = Double.NaN,
        val workingPeakDb: Double = Double.NaN,
        val peakFrames: List<Int> = emptyList(),
        val beforeWindowFrames: Int = workingFrames,
        val trimmedStartFrames: Int = retainedStartFrames,
        val sourceChannels: Int = channels,
        val commonDelaySamples: Int = 0,
        val advancedAnalysis: me.timschneeberger.rootlessjamesdsp.interop.ConvolutionAdvancedAnalysis.Data? = null,
        val sampleRateCompensationDb: Double = 0.0,
    ) {
        /** A staged work copy can be analysed without claiming that the driver has activated it. */
        val hasWorkingKernel get() = (status==Preparation.READY || status==Preparation.STAGED) &&
            processingSampleRate in 8000..768000 && workingFrames>0 && channels in listOf(1,2,4)
    }
    @Volatile var prepared: PreparedIr? = null
        private set
    fun publishPrepared(context: Context, next: PreparedIr?) {
        prepared=next
        context.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    const val CAPABILITY = ConvolutionParameters.CAPABILITY
    const val STATUS_PREFS = "convolution_driver_status"
    const val STATUS_ACTION = "dsp.fusion.CONVOLUTION_RUNTIME"
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
        val snapshot: ConvolutionRuntimeReader.Snapshot?,
        val processing: OutputRuntimeStatus.Processing = OutputRuntimeStatus.Processing.UNKNOWN,
        val advanced: ConvolutionAdvancedRuntime.Snapshot?=null) {
        val actualAudition get() = snapshot?.audition
    }
    @Volatile var runtime: List<Reading> = emptyList()
        private set
    /** Kernel acknowledgement is independent of signal-meter windows and idle host rate. */
    fun kernelConfirmed(expected: PreparedIr? = prepared): Boolean {
        val work=expected?.takeIf {it.hasWorkingKernel} ?: return false
        return runtime.any {it.snapshot?.let {snapshot->snapshot.loaded && snapshot.crc==work.crc}==true}
    }
    val masterEnabled get() = runtime.any { it.enabled }
    fun preferences(context: Context) = PreferenceCache.getPreferences(context,ConvolutionParameters.NAMESPACE)
    fun statusPreferences(context: Context) = context.getSharedPreferences(STATUS_PREFS,Context.MODE_PRIVATE)
    private fun hasCapability(context: Context, capability: Int) = statusPreferences(context).let {
        it.getString("session",null) == session && it.getInt("capabilities",0) and capability != 0
    }
    fun supported(context: Context) = hasCapability(context,CAPABILITY)
    fun advancedSupported(context: Context) = hasCapability(context,ConvolutionAdvancedParameters.CAPABILITY)
    @Volatile var comparisonTenths = 0
        private set
    @Volatile private var comparisonOwner: Any? = null
    fun setComparison(context: Context, owner: Any, tenths: Int) {
        if(!advancedSupported(context))return
        if(!masterEnabled || !preferences(context).getBoolean(ConvolutionParameters.ENABLED,false)){stopComparison(context,owner);return}
        comparisonOwner=owner;comparisonTenths=tenths.coerceIn(-120,120);changed(context)
    }
    fun stopComparison(context: Context,owner: Any?=null) {
        if(owner!=null && comparisonOwner!==owner)return
        comparisonOwner=null
        if(comparisonTenths!=0){comparisonTenths=0;changed(context)}
    }
    fun publishCapabilities(context: Context, capabilities: Int) {
        val prefs = statusPreferences(context)
        if (prefs.getString("session",null) != session || prefs.getInt("capabilities",-1) != capabilities)
            prefs.edit().putString("session",session).putInt("capabilities",capabilities).apply()
        if (capabilities and CAPABILITY == 0) stopAudition(context)
        if(capabilities and ConvolutionAdvancedParameters.CAPABILITY==0)stopComparison(context)
    }
    private fun changed(context: Context) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        context.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun startAudition(context: Context, owner: Any, selection: Int): Boolean {
        if (selection !in 1..3 || !supported(context) ||
            !masterEnabled || !preferences(context).getBoolean(ConvolutionParameters.ENABLED,false)) return false
        if (this.owner === owner && audition == selection) return true
        this.owner = owner; audition = selection; auditionRevision++; changed(context)
        return true
    }
    fun stopAudition(context: Context, owner: Any? = null,keepComparison: Boolean=false) {
        // A zero-gain comparison can still own temporary state, so its owner must be empty too.
        if (audition == 0 && (keepComparison || (comparisonOwner == null && comparisonTenths == 0))) return
        stopAuditionLocked(context, owner, keepComparison)
    }
    @Synchronized private fun stopAuditionLocked(context: Context, owner: Any?, keepComparison: Boolean) {
        if(!keepComparison)stopComparison(context,owner)
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

    /** The engine owns IR upload and parameter 1205. Never enable a module as a side effect of editing. */
    @Synchronized fun sync(context: Context, capabilities: Int, target: Any = context, sender: (Int,IntArray) -> Boolean): Boolean =
        sync(context,capabilities,target,prepared?.commonDelaySamples ?: 0,sender)
    @Synchronized fun sync(context: Context, capabilities: Int, target: Any,commonDelaySamples: Int,sender: (Int,IntArray) -> Boolean): Boolean {
        val prefs=preferences(context);ConvolutionParameters.normalize(prefs)
        if(capabilities and CAPABILITY==0){stopAudition(context);return true}
        if(!prefs.getBoolean(ConvolutionParameters.ENABLED,false))stopAudition(context)
        val configured=sender(ConvolutionParameters.PACKET_ID,ConvolutionParameters.runtimePacket(prefs.all))
        val advanced=capabilities and ConvolutionAdvancedParameters.CAPABILITY==0 || sender(ConvolutionAdvancedParameters.PACKET_ID,
            ConvolutionAdvancedParameters.packet(prefs.all,commonDelaySamples,comparisonTenths))
        if(!configured && audition!=0){stopAudition(context);return false}
        val fresh=audition!=0 && sentAuditionRevisions[target]!=auditionRevision
        if(fresh && !sender(ConvolutionParameters.AUDITION_ID,intArrayOf(0))){stopAudition(context);return false}
        val sent=sender(ConvolutionParameters.AUDITION_ID,intArrayOf(audition))
        if(sent && sentAuditionRevisions[target]!=auditionRevision){sentAuditionRevisions[target]=auditionRevision;sentRevisionToken++}
        else if(!sent && audition!=0)stopAudition(context)
        return configured && advanced && sent
    }
}

