package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.IdentityHashMap
import java.util.UUID

object WidthController {
    const val STATUS_ACTION="dsp.fusion.WIDTH_RUNTIME"
    private val session=UUID.randomUUID().toString()
    private var owner: Any?=null
    private var revision=0L
    private var runtimeRevision=0L
    private val gates=IdentityHashMap<Any,Boolean>()
    private val sent=IdentityHashMap<Any,Long>()
    private var sentRevision=0L
    @Volatile var audition=0
        private set
    data class Reading(val identity: Any,val enabled: Boolean,val frames: Int?,val snapshot: WidthRuntime.Snapshot?,val active: Boolean=false,val advanced: WidthAdvancedRuntime.Snapshot?=null)
    private val comparisons=mutableMapOf<Int,Map<String,*>>()
    @Volatile var compareMatch=false
        private set
    @Synchronized fun hasComparison(slot: Int)=comparisons.containsKey(slot)
    @Synchronized fun captureComparison(context: Context,slot: Int): Boolean {
        if(slot !in 8..9 || !supportedAdvanced(context) || WidthParameters.choice(preferences(context).all,WidthParameters.MODE)==0)return false
        stopAudition(context);comparisons[slot]=preferences(context).all.toMap();changed(context);return true
    }
    @Synchronized fun clearComparisons(context: Context){stopAudition(context);comparisons.clear();compareMatch=false;changed(context)}
    @Synchronized fun setCompareMatch(context: Context,value: Boolean){compareMatch=value;revision++;changed(context,true)}
    @Volatile var runtime: List<Reading> = emptyList()
        private set
    data class Poll(val audition: Long,val runtime: Long,val sent: Long)
    @Synchronized fun pollState()=Poll(revision,runtimeRevision,sentRevision)
    fun preferences(context: Context)=PreferenceCache.getPreferences(context,WidthParameters.NAMESPACE)
    fun status(context: Context)=context.getSharedPreferences("width_driver_status",Context.MODE_PRIVATE)
    fun supported(context: Context)=status(context).let {it.getString("session",null)==session && it.getInt("capabilities",0) and WidthParameters.CAPABILITY!=0}
    fun supportedAdvanced(context: Context)=supported(context) && status(context).getInt("capabilities",0) and WidthAdvancedParameters.CAPABILITY!=0
    private fun changed(context: Context,settings: Boolean=false){context.sendLocalBroadcast(Intent(STATUS_ACTION));if(settings)context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))}
    fun publishCapabilities(context: Context,caps: Int) {
        status(context).let {if(it.getString("session",null)!=session || it.getInt("capabilities",-1)!=caps)it.edit().putString("session",session).putInt("capabilities",caps).apply()}
        if(caps and WidthParameters.CAPABILITY==0)stopAudition(context)
        else if(caps and WidthAdvancedParameters.CAPABILITY==0 && audition>=4)stopAudition(context)
    }
    @Synchronized fun startAudition(context: Context,owner: Any,selection: Int): Boolean {
        if(selection !in 1..9 || !supported(context) || !preferences(context).getBoolean(WidthParameters.ENABLED,false) || runtime.none {it.enabled})return false
        if(selection>=4 && (!supportedAdvanced(context) || WidthParameters.choice(preferences(context).all,WidthParameters.MODE)==0 || (selection in 4..6 && WidthParameters.choice(preferences(context).all,WidthParameters.MODE)!=2) || (selection>=8 && !hasComparison(selection))))return false
        if(this.owner===owner && audition==selection)return true
        this.owner=owner;audition=selection;revision++;changed(context,true);return true
    }
    fun stopAudition(context: Context,owner: Any?=null) {
        if(audition==0)return
        stopAuditionLocked(context,owner)
    }
    @Synchronized private fun stopAuditionLocked(context: Context,owner: Any?) {
        if(audition==0 || (owner!=null && this.owner!==owner))return
        this.owner=null;audition=0;revision++;changed(context,true)
    }
    @Synchronized fun sessionEnabled(context: Context,identity: Any,enabled: Boolean) {
        runtimeRevision++;gates[identity]=enabled;runtime=runtime.map {if(it.identity===identity)it.copy(enabled=false,active=false)else it}
        if(runtime.none {it.enabled})stopAudition(context);changed(context)
    }
    @Synchronized fun sessionClosed(context: Context,identity: Any) {
        runtimeRevision++;gates.remove(identity);sent.remove(identity);runtime=runtime.filterNot {it.identity===identity}
        if(runtime.none {it.enabled})stopAudition(context);changed(context)
    }
    @Synchronized fun publishRuntime(context: Context,readings: List<Reading>,poll: Poll=pollState()) {
        if(poll.runtime!=runtimeRevision)return
        val next=readings.map {reading->
            val previous=runtime.firstOrNull {it.identity===reading.identity}
            val enabled=reading.enabled && gates[reading.identity]!=false
            if(gates[reading.identity]==true)gates.remove(reading.identity)
            reading.copy(enabled=enabled,active=enabled && reading.frames!=null && previous?.frames!=null && previous.frames!=reading.frames)
        }
        if(poll.audition==revision && audition!=0 && (next.none {it.enabled} || (poll.sent==sentRevision && next.any {it.enabled && sent[it.identity]==revision && it.snapshot?.audition!=null && it.snapshot.audition!=audition})))stopAudition(context)
        if(next!=runtime){runtime=next;changed(context)}
    }
    /** Page observations update display cache only, without acknowledging or ending any audition. */
    @Synchronized fun publishPageRuntime(context: Context,readings: List<Reading>,poll: Poll): List<Reading>? {
        if(poll.audition!=revision || poll.runtime!=runtimeRevision || poll.sent!=sentRevision)return null
        val next=readings.map {reading->
            if(gates[reading.identity]==false)reading.copy(enabled=false,active=false)else reading
        }
        if(next!=runtime){runtime=next;changed(context)}
        return next
    }
    @Synchronized fun sync(context: Context,caps: Int,target: Any,sender: (Int,IntArray)->Boolean): Boolean {
        val prefs=preferences(context);WidthParameters.normalize(prefs)
        if(caps and WidthParameters.CAPABILITY==0){stopAudition(context);return true}
        if(!prefs.getBoolean(WidthParameters.ENABLED,false))stopAudition(context)
        WidthAdvancedParameters.normalize(prefs)
        val advanced=caps and WidthAdvancedParameters.CAPABILITY!=0
        if(!advanced && audition>=4)stopAudition(context)
        var values=if(audition>=8)comparisons[audition] ?: prefs.all else prefs.all
        if(audition>=8)values=values+mapOf("width2_meter_on" to (prefs.all["width2_meter_on"]==true))
        if(audition>=8 && compareMatch)values=values+mapOf(WidthParameters.AUTO to true,WidthParameters.autoMax.key to 30,WidthParameters.output.key to 0,"width2_auto_on" to false)
        val configured=sender(WidthParameters.PACKET,WidthParameters.packet(values)) && (!advanced || sender(WidthAdvancedParameters.PACKET,WidthAdvancedParameters.packet(values)))
        if(!configured){stopAudition(context);sender(WidthParameters.AUDITION,intArrayOf(0));return false}
        if(audition!=0 && sent[target]!=revision && !sender(WidthParameters.AUDITION,intArrayOf(0))){stopAudition(context);return false}
        if(advanced && audition!=0 && sent[target]!=revision && !sender(WidthAdvancedParameters.AUDITION,intArrayOf(0))){stopAudition(context);return false}
        val okay=sender(WidthParameters.AUDITION,intArrayOf(if(audition<=3)audition else 0)) && (!advanced || sender(WidthAdvancedParameters.AUDITION,intArrayOf(if(audition>=4)audition else 0)))
        if(okay && sent[target]!=revision){sent[target]=revision;sentRevision++}else if(!okay)stopAudition(context)
        return okay
    }
}
