package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.IdentityHashMap
import java.util.UUID

/** Snapshots, auditions and measured attenuation live in memory, never in profiles or favorites. */
object CrossfeedController {
    const val STATUS_PREFS="crossfeed_driver_status"
    const val STATUS_ACTION="dsp.fusion.CROSSFEED_RUNTIME"
    private val session=UUID.randomUUID().toString()
    @Volatile private var owner:Any?=null
    @Volatile private var snapshotOwner:Any?=null
    @Volatile private var a:IntArray?=null
    @Volatile private var b:IntArray?=null
    @Volatile var audition=0
        private set
    @Volatile var match=false
        private set
    @Volatile var epoch=1
        private set
    @Volatile private var revision=0L
    @Volatile private var runtimeRevision=0L
    private var sentToken=0L
    private var previous:Map<String,Any?>?=null
    @Volatile private var toolsDirty=false
    private val sent=IdentityHashMap<Any,Long>()
    private val gates=IdentityHashMap<Any,Boolean>()
    data class Poll(val revision:Long,val runtime:Long,val sent:Long)
    @Synchronized fun pollState()=Poll(revision,runtimeRevision,sentToken)
    data class Reading(val identity:Any,val enabled:Boolean,val frames:Int?,val rate:Int,
        val snapshot:CrossfeedParameters.Runtime?,val active:Boolean=false,val diagnostics:CrossfeedAdvancedParameters.Diagnostic?=null)
    @Volatile var runtime:List<Reading> = emptyList()
        private set
    fun preferences(c:Context)=BassController.preferences(c,true)
    fun statusPreferences(c:Context)=c.getSharedPreferences(STATUS_PREFS,Context.MODE_PRIVATE)
    fun supported(c:Context)=statusPreferences(c).let {it.getString("session",null)==session && it.getInt("capabilities",0) and CrossfeedParameters.CAPABILITY!=0}
    fun advancedSupported(c:Context)=supported(c) && statusPreferences(c).getInt("capabilities",0) and CrossfeedAdvancedParameters.CAPABILITY!=0
    fun publishCapabilities(c:Context,caps:Int){
        val p=statusPreferences(c)
        if((p.getInt("capabilities",0) and CrossfeedAdvancedParameters.CAPABILITY)!=(caps and CrossfeedAdvancedParameters.CAPABILITY))stopAudition(c,clearSnapshots=true)
        if(p.getString("session",null)!=session || p.getInt("capabilities",-1)!=caps)p.edit().putString("session",session).putInt("capabilities",caps).apply()
        if(caps and CrossfeedParameters.CAPABILITY==0)stopAudition(c,clearSnapshots=true)
    }
    private fun changed(c:Context){c.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED));c.sendLocalBroadcast(Intent(STATUS_ACTION))}
    private fun audioValues(c:Context,viper:Map<String,*>):Map<String,Any?> = buildMap {
        viper.forEach {(key,value)->put("viper/$key",value)}
        for(namespace in listOf(Constants.PREF_BASS,Constants.PREF_COMPANDER,Constants.PREF_CONVOLVER,Constants.PREF_CROSSFEED,
            Constants.PREF_DDC,Constants.PREF_EQ,Constants.PREF_GEQ,Constants.PREF_LIVEPROG,Constants.PREF_OUTPUT,
            Constants.PREF_REVERB,Constants.PREF_STEREOWIDE,Constants.PREF_TUBE,"dsp_fusion_tone","dsp_fusion_surround","dsp_fusion_peq"))
            me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache.getPreferences(c,namespace).all.forEach {(key,value)->put("$namespace/$key",value)}
    }
    private fun nextEpoch(){epoch=if(epoch==Int.MAX_VALUE)1 else epoch+1;revision++}
    fun hasComparison(slot:Int)=if(slot==0)a!=null else b!=null
    fun canListen(c:Context)=supported(c) && CrossfeedParameters.enabled(preferences(c).all) &&
        preferences(c).getBoolean("v_cure",false) && runtime.any {it.enabled && it.active && it.snapshot?.let {s->s.enabled && s.professional}==true}
    fun matchingReady()=runtime.filter {it.enabled && it.active}.let {rows->rows.isNotEmpty() && rows.all {it.snapshot?.let {s->s.epoch==epoch && s.measurement==2}==true}}
    @Synchronized fun saveComparison(c:Context,owner:Any,slot:Int):Boolean{
        if(slot !in 0..1 || !supported(c) || !CrossfeedParameters.enabled(preferences(c).all))return false
        stopAudition(c);previous=audioValues(c,preferences(c).all);snapshotOwner=owner
        val value=CrossfeedParameters.packet(preferences(c).all)+(if(advancedSupported(c))CrossfeedAdvancedParameters.packet(preferences(c).all)else intArrayOf())
        if(slot==0)a=value else b=value
        nextEpoch();changed(c);return true
    }
    @Synchronized fun startAudition(c:Context,owner:Any,mode:Int):Boolean{
        if(mode !in 0..6 || (mode!=0 && !canListen(c)) || (mode>=4 && (a==null || b==null)) ||
            (mode in 4..5 && match && !matchingReady()))return false
        if(audition==mode && this.owner===owner)return true
        previous=audioValues(c,preferences(c).all);this.owner=owner;audition=mode;toolsDirty=true;revision++;changed(c);return true
    }
    @Synchronized fun measure(c:Context,owner:Any):Boolean {
        if(!canListen(c) || a==null || b==null)return false
        previous=audioValues(c,preferences(c).all);match=false;toolsDirty=true;nextEpoch();this.owner=owner;audition=6;changed(c);return true
    }
    @Synchronized fun setMatch(c:Context,value:Boolean):Boolean{
        if(value && !matchingReady())return false
        if(match!=value){match=value;revision++;changed(c)};return true
    }
    fun stopAudition(c:Context,owner:Any?=null,clearSnapshots:Boolean=false){
        // Idle cleanup must not contend with the background driver's parameter transaction.
        if(audition==0 && !match && !toolsDirty && this.owner==null &&
            (!clearSnapshots || (a==null && b==null && snapshotOwner==null)))return
        stopAuditionLocked(c,owner,clearSnapshots)
    }
    @Synchronized private fun stopAuditionLocked(c:Context,owner:Any?,clearSnapshots:Boolean){
        if(owner!=null && this.owner!==owner && snapshotOwner!==owner)return
        val hadState=audition!=0 || match || toolsDirty || (clearSnapshots && (a!=null || b!=null))
        this.owner=null;audition=0;match=false
        toolsDirty=false
        if(clearSnapshots){a=null;b=null;snapshotOwner=null}
        if(hadState){nextEpoch();changed(c)}
    }
    @Synchronized fun sessionEnabled(c:Context,id:Any,enabled:Boolean){
        runtimeRevision++;gates[id]=enabled;runtime=runtime.map {if(it.identity===id)it.copy(enabled=false,active=false)else it}
        if(runtime.none {it.enabled})stopAudition(c)
        c.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun sessionClosed(c:Context,id:Any){
        runtimeRevision++;gates.remove(id);sent.remove(id);runtime=runtime.filterNot {it.identity===id}
        if(runtime.none {it.enabled})stopAudition(c)
        c.sendLocalBroadcast(Intent(STATUS_ACTION))
    }
    @Synchronized fun publishRuntime(c:Context,readings:List<Reading>,poll:Poll=pollState()){
        if(poll.runtime!=runtimeRevision)return
        val next=readings.map {r->
            val old=runtime.firstOrNull {it.identity===r.identity}
            val enabled=if(gates[r.identity]==false)false else r.enabled
            if(gates[r.identity]==true)gates.remove(r.identity)
            r.copy(enabled=enabled,active=enabled && r.frames!=null && old?.frames!=null && r.rate==old.rate && r.frames!=old.frames)
        }
        if(poll.revision==revision && audition!=0 && (next.none {it.enabled && it.active} ||
                (poll.sent==sentToken && next.any {it.enabled && sent[it.identity]==revision && it.snapshot?.let {s->s.mode!=audition || s.epoch!=epoch}==true})))
            stopAudition(c)
        if(next!=runtime){runtime=next;c.sendLocalBroadcast(Intent(STATUS_ACTION))}
    }
    @Synchronized fun sync(c:Context,caps:Int,target:Any,sender:(Int,IntArray)->Boolean):Boolean{
        val p=preferences(c);CrossfeedParameters.normalize(p);val values=p.all;val settings=CrossfeedParameters.capture(values)
        val fingerprint=audioValues(c,values)
        if(previous!=null && previous!=fingerprint)stopAudition(c)
        previous=fingerprint
        if(!p.getBoolean("v_cure",false) || !settings.professional || caps and CrossfeedParameters.CAPABILITY==0)stopAudition(c)
        val legacy=sender(65582,intArrayOf(settings.legacy))
        if(caps and CrossfeedParameters.CAPABILITY==0)return legacy
        val advanced=caps and CrossfeedAdvancedParameters.CAPABILITY!=0
        val configured=(!advanced || sender(CrossfeedAdvancedParameters.PACKET,CrossfeedAdvancedParameters.packet(values))) && sender(CrossfeedParameters.PACKET,CrossfeedParameters.packet(values))
        val enabled=sender(65581,intArrayOf(if(p.getBoolean("v_cure",false))1 else 0))
        if(!legacy || !configured || !enabled){stopAudition(c);return false}
        val fallback=CrossfeedParameters.packet(emptyMap<String,Any>())
        fun snapshot(v:IntArray?)=(v ?: fallback).take(8).toIntArray()+(if(advanced)(v?.takeIf {it.size==19}?.drop(8)?.toIntArray() ?: CrossfeedAdvancedParameters.packet(emptyMap<String,Any>()))else intArrayOf())
        val packet=intArrayOf(audition,if(match)1 else 0,epoch)+snapshot(a)+snapshot(b)
        val okay=sender(if(advanced)CrossfeedAdvancedParameters.TOOLS else CrossfeedParameters.TOOLS,packet)
        if(okay && sent[target]!=revision){sent[target]=revision;sentToken++}
        if(!okay)stopAudition(c)
        return okay
    }
    /** Display/explicit-measurement cache only; never acknowledge, cancel or clear listening state. */
    @Synchronized fun publishPageRuntime(c:Context,readings:List<Reading>,poll:Poll):List<Reading>? {
        if(poll.revision!=revision || poll.runtime!=runtimeRevision || poll.sent!=sentToken)return null
        val next=readings.map {reading->
            if(gates[reading.identity]==false)reading.copy(enabled=false,active=false)else reading
        }
        if(next!=runtime){runtime=next;c.sendLocalBroadcast(Intent(STATUS_ACTION))}
        return next
    }
}
