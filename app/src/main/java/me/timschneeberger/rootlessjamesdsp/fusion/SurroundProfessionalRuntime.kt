package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.IdentityHashMap

/** Listening state lives in memory, never in a device preset or the independent library. */
object SurroundProfessionalRuntime {
    const val ACTION="dsp.fusion.SURROUND_RUNTIME"
    val auditionLabels=listOf("正常处理","本模块原声","仅处理增量","参数 A","参数 B")
    data class Snapshot(val data:List<Int>)
    data class Reading(val identity:Any,val enabled:Boolean,val frames:Int?,val hostRate:Int?,val snapshot:Snapshot?,val active:Boolean=false,val listening:List<Int>?=null)
    data class Poll(val revision:Long,val runtime:Long,val sent:Long)
    @Volatile var readings:List<Reading> = emptyList();private set
    @Volatile var audition=0;private set
    @Volatile var calibration=0;private set
    private var calibrationToken=1
    private var comparisonEpoch=1
    private fun increment(v:Int)=if(v==Int.MAX_VALUE)1 else v+1
    private var owner:Any?=null
    private var revision=0L
    private var runtimeRevision=0L
    private var sentRevision=0L
    private val gates=IdentityHashMap<Any,Boolean>()
    private val sent=IdentityHashMap<Any,Long>()
    private val comparisons=mutableMapOf<Int,SurroundController.Settings>()
    fun decode(v:IntArray):Snapshot? {
        if(v.size!=32 || v[0]!=1 || v[1]<0 || v[1]%2!=0 || v[2] !in 8000..768000 || v[3] !in 0..1 || v[4] !in 0..1 || v[5] !in 0..1 || v[6]<0 || v[24] !in 0..4)return null
        if((11..12).any {v[it]!=Int.MIN_VALUE && v[it] !in -10000..10000} || v[13] !in -60..60 || v[14]>0 || v[15] !in 0..1000 || (27..29).any {v[it] !in 0..1})return null
        if(v[5]==1 && (v[3]!=1 || v[29]!=1) || v[16]<0 || v[17]>v[2]*.45+1)return null
        if(v[16] !in 0..800 || v[17] !in 0..16000 || (22..23).any {v[it] !in 0..2} || v[25] !in -180..0 || v[26] !in -120..60 || v[30]<0 || v[31]!=0)return null
        if(v[3]==1 && ((18..19).any {v[it] !in 200..2100} || (20..21).any {v[it] !in 500..10000} || v[16]>=v[17]))return null
        return Snapshot(v.toList())
    }
    private fun changed(c:Context,settings:Boolean=false){c.sendLocalBroadcast(Intent(ACTION));if(settings)c.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))}
    @Synchronized fun pollState()=Poll(revision,runtimeRevision,sentRevision)
    @Synchronized fun curveRate():Int? {
        return readings.sortedBy {if(it.active && it.enabled)0 else if(it.enabled)1 else 2}.firstNotNullOfOrNull {r->
            r.snapshot?.data?.get(2)?.takeIf {it in 8000..768000} ?: r.hostRate?.takeIf {it in 8000..768000}
        }
    }
    @Synchronized fun hasComparison(slot:Int)=comparisons.containsKey(slot)
    @Synchronized fun clearMeasurements(c:Context){stop(c);comparisonEpoch=increment(comparisonEpoch);changed(c,true)}
    @Synchronized fun listeningReadings()=readings.filter {it.enabled && it.active && it.listening?.get(19)==comparisonEpoch}.mapNotNull {it.listening}
    @Synchronized fun capture(c:Context,slot:Int):Boolean {
        if(slot !in 3..4 || !SurroundController.professionalSupported(c))return false
        stop(c);comparisons[slot]=SurroundController.settings(c);comparisonEpoch=increment(comparisonEpoch);changed(c,true);return true
    }
    @Synchronized fun clearComparisons(c:Context){stop(c);comparisons.clear();comparisonEpoch=increment(comparisonEpoch);changed(c,true)}
    @Synchronized fun preview(c:Context)=if(audition in 3..4)comparisons[audition] ?: SurroundController.settings(c) else SurroundController.settings(c)
    @Synchronized fun start(c:Context,owner:Any,selection:Int):Boolean {
        if(selection !in 1..4 || !SurroundController.professionalSupported(c) || !SurroundController.enabled(c) || readings.none {it.enabled && it.active && it.snapshot?.data?.get(3)==1} || (selection>=3 && !hasComparison(selection)))return false
        stop(c);this.owner=owner;audition=selection;revision++;changed(c,true);return true
    }
    @Synchronized fun startCalibration(c:Context,owner:Any):Boolean {
        if(!SurroundController.listeningSupported(c) || !SurroundController.enabled(c) || !SurroundProfessionalParameters.on(SurroundController.settings(c),"cal_on") || readings.none {it.enabled && it.active && it.snapshot?.data?.get(3)==1})return false
        stop(c);this.owner=owner;calibration=SurroundProfessionalParameters.value(SurroundController.settings(c),"cal_mode")+1;calibrationToken=increment(calibrationToken);revision++;changed(c,true);return true
    }
    fun stop(c:Context,owner:Any?=null){
        if(audition==0 && calibration==0)return
        stopLocked(c,owner)
    }
    @Synchronized private fun stopLocked(c:Context,owner:Any?){if((audition==0 && calibration==0) || (owner!=null && this.owner!==owner))return;this.owner=null;audition=0;calibration=0;revision++;changed(c,true)}
    @Synchronized fun sessionEnabled(c:Context,identity:Any,enabled:Boolean){runtimeRevision++;gates[identity]=enabled;readings=readings.map {if(it.identity===identity)it.copy(enabled=false,active=false)else it};if(readings.none {it.enabled})stop(c);changed(c)}
    @Synchronized fun sessionClosed(c:Context,identity:Any){runtimeRevision++;gates.remove(identity);sent.remove(identity);readings=readings.filterNot {it.identity===identity};if(readings.none {it.enabled})stop(c);changed(c)}
    @Synchronized fun publish(c:Context,values:List<Reading>,poll:Poll=pollState()) {
        if(poll.runtime!=runtimeRevision)return
        val next=values.map {r->val prev=readings.firstOrNull {it.identity===r.identity};val enabled=r.enabled && gates[r.identity]!=false;if(gates[r.identity]==true)gates.remove(r.identity);r.copy(enabled=enabled,active=enabled && prev?.frames!=null && r.frames!=null && prev.frames!=r.frames)}
        if(poll.revision==revision && audition!=0 && (next.none {it.enabled && it.active} || (poll.sent==sentRevision && next.any {it.enabled && sent[it.identity]==revision && it.snapshot!=null && it.snapshot.data[24]!=audition})))stop(c)
        if(poll.revision==revision && calibration!=0 && (next.none {it.enabled && it.active} || (poll.sent==sentRevision && next.any {it.enabled && sent[it.identity]==revision && it.listening?.get(23)==calibrationToken && it.listening[15]==0})))stop(c)
        if(readings!=next){readings=next;changed(c)}
    }
    @Synchronized fun sync(c:Context,target:Any,sender:(Int,IntArray)->Boolean):Boolean {
        if(!SurroundController.enabled(c))stop(c)
        val normal=SurroundController.settings(c);var value=if(audition>=3)comparisons[audition] ?: normal else normal
        if(audition>=3)value=value.copy(professional=value.professional+mapOf("meters" to SurroundProfessionalParameters.value(normal,"meters")))
        fun fail():Boolean {stop(c);sender(SurroundProfessionalParameters.AUDITION,intArrayOf(0));if(SurroundController.listeningSupported(c))sender(SurroundListeningParameters.PACKET,SurroundListeningParameters.packet(normal,comparisonEpoch,0,0));sender(SurroundProfessionalParameters.PACKET,SurroundProfessionalParameters.packet(normal,SurroundController.enabled(c)));return false}
        if(!sender(SurroundProfessionalParameters.PACKET,SurroundProfessionalParameters.packet(value,SurroundController.enabled(c))))return fail()
        if(audition!=0 && sent[target]!=revision && !sender(SurroundProfessionalParameters.AUDITION,intArrayOf(0)))return fail()
        if(SurroundController.listeningSupported(c) && !sender(SurroundListeningParameters.PACKET,SurroundListeningParameters.packet(normal,comparisonEpoch,calibration,calibrationToken)))return fail()
        val success=sender(SurroundProfessionalParameters.AUDITION,intArrayOf(audition))
        if(success && sent[target]!=revision){sent[target]=revision;sentRevision++}else if(!success)return fail()
        return success
    }
    /** Display cache only: a page read must never acknowledge or cancel auditions/calibration. */
    @Synchronized fun publishPageRuntime(c:Context,values:List<Reading>,poll:Poll):List<Reading>? {
        if(poll.revision!=revision || poll.runtime!=runtimeRevision || poll.sent!=sentRevision)return null
        val next=values.map {reading->
            if(gates[reading.identity]==false)reading.copy(enabled=false,active=false)else reading
        }
        if(readings!=next){readings=next;changed(c)}
        return next
    }
}
