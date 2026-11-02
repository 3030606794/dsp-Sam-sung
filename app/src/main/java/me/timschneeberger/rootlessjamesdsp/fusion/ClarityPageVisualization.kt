package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence
import me.timschneeberger.rootlessjamesdsp.ui.ClarityPreviewModel
import me.timschneeberger.rootlessjamesdsp.ui.ClarityProfessionalHeader
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Owns display snapshots and an independent read-only lease; original controls still own audio. */
internal class ClarityPageVisualization(private val fragment:Fragment,
    @Suppress("UNUSED_PARAMETER") spectrum:Boolean=false) : AutoCloseable {
    private data class Request(val values:Map<String,*>,val rate:Int,val actual:Boolean,val key:String,val epoch:Long)
    private val context=fragment.requireContext()
    private val host=fragment.requireActivity() as ProfessionalEffectActivity
    private val display=context.applicationContext.getSharedPreferences("high_frequency_visualization_display",Context.MODE_PRIVATE)
    private val main=Handler(Looper.getMainLooper())
    private val executor=Executors.newSingleThreadExecutor {task->Thread(task,"clarity-linked-plots").apply {isDaemon=true}}
    private var preferences:SharedPreferences?=null
    private var closed=false
    private var resumed=false
    private var epoch=0L
    private var readEpoch=0L
    private var configurationRead:Job?=null
    private var power=display.getBoolean("clarity.monitor",false)
    private var pinned=display.getBoolean("clarity.pinned",true)
    private var expanded=!display.getBoolean("clarity.realtime_collapsed",false)
    private var locked=display.getBoolean("clarity.locked",false)
    private var focusKey=ClarityLinkedPlots.focus(display.getString("clarity.focus",null),emptyMap<String,Any>()).key
    private var effectEnabled=false
    private var observedRate:Int?=null
    private var pausedLabel:String?=null
    private var desired:Request?=null
    private var pending:Request?=null
    private var inFlight:Request?=null
    private var worker:Future<*>?=null
    private var cachedRequest:Request?=null
    private var cachedModel:ClarityPreviewModel?=null
    private var focusObserver:ClarityParameterFocusObserver?=null
    private val changedKeys=linkedSetOf<String>()
    private val parameterKeys=ClarityLinkedPlots.relevantKeys
    private val relevantKeys=parameterKeys+setOf("v_clarity","v_clarity_amount")
    private val stateKeys=HighFrequencyAdvancedParameters.switches.toSet()+setOf("v_clarity","v_clarity_mode")
    private val monitoring=MutableStateFlow(false)
    private val renderFrame=PreferenceFrameUpdate {refreshConfiguration()}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(!closed&&(key==null||key in relevantKeys)){
            if(key!=null&&key in parameterKeys)changedKeys+=key
            renderFrame.request()
        }
    }
    private val receiver=object:BroadcastReceiver(){
        override fun onReceive(context:Context,intent:Intent){
            if(closed)return
            if(intent.action==Constants.ACTION_PRESET_LOADED){reloadPreferences();if(resumed)readConfiguredRate()}
            else {header.refreshOriginalStatus();renderFrame.request()}
        }
    }
    private val header:ClarityProfessionalHeader=ClarityProfessionalHeader(context,power,pinned,expanded,
        onPowerChanged={value->
            if(!closed){power=value;display.edit().putBoolean("clarity.monitor",value).apply();renderDisplay();updateMonitoring()
                if(monitoring.value){readEpoch++;configurationRead?.cancel();configurationRead=null}else if(resumed)readConfiguredRate()}
        },onPinChanged={value->
            if(!closed){pinned=value;display.edit().putBoolean("clarity.pinned",value).apply();renderDisplay();host.setPinnedHeader(headerView(),value)}
        },onExpandedChanged={value->
            if(!closed){expanded=value;display.edit().putBoolean("clarity.realtime_collapsed",!value).apply();renderDisplay()}
        },locked=locked,onLockChanged={value->
            if(!closed){locked=value;display.edit().putBoolean("clarity.locked",value).apply();renderDisplay()}
        })
    private fun headerView():ClarityProfessionalHeader=header
    private fun renderDisplay():Unit=header.setDisplay(power,pinned,expanded,locked)

    init {
        reloadPreferences();renderDisplay();host.setPinnedHeader(header,pinned)
        host.setBassGraphActions(header,header.toolbarActions)
        (fragment as? PreferenceFragmentCompat)?.let {nativeFragment->
            focusObserver=ClarityParameterFocusObserver(nativeFragment){key->
                if(!closed&&resumed&&selectFocus(key))renderFrame.request()
            }
        }
        context.registerLocalReceiver(receiver,IntentFilter().apply {
            addAction(Constants.ACTION_PRESET_LOADED);addAction(HighFrequencyController.STATUS_ACTION)
        })
        ProfessionalRuntimeMonitor.bindClarityMonitor(fragment.viewLifecycleOwner,context,monitoring,
            spectrum=false,publishCache=true){sample->
            if(!closed&&monitoring.value){
                val enriched=withOutputEvidence(sample)
                pausedLabel=null;header.showRuntime(enriched)
                enriched.reading?.rate?.takeIf {it in 8000..768000}?.let {rate->
                    if(observedRate!=rate){observedRate=rate;renderFrame.request()}
                }
            }
        }
    }
    private fun withOutputEvidence(sample:ProfessionalRuntimeMonitor.ClaritySample):ProfessionalRuntimeMonitor.ClaritySample {
        val application=context.applicationContext as? MainApplication
        val mode=sample.output.mode?:if(application?.isPlayerSessionMode==true)OutputRuntimeStatus.Mode.PLAYER_SESSION else OutputRuntimeStatus.Mode.GLOBAL
        val issue=if(mode==OutputRuntimeStatus.Mode.GLOBAL)when(application?.rootSessionDatabase?.globalPlaybackIssue){
            GlobalPlaybackEvidence.Reason.SUSPENDED->OutputRuntimeStatus.GlobalIssue.SUSPENDED
            GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT->OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT
            null->sample.output.globalIssue
        }else null
        return sample.copy(output=sample.output.copy(mode=mode,globalIssue=issue))
    }
    private fun selectFocus(key:String):Boolean {
        if(locked||key !in parameterKeys)return false
        val next=ClarityLinkedPlots.focus(key,preferences?.all.orEmpty()).key
        if(next==focusKey)return false
        focusKey=next;display.edit().putString("clarity.focus",next).apply();return true
    }
    private fun reloadPreferences(){
        epoch++;readEpoch++;configurationRead?.cancel();configurationRead=null
        observedRate=null;desired=null;pending=null;changedKeys.clear()
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences=HighFrequencyController.preferences(context).also {it.registerOnSharedPreferenceChangeListener(listener)}
        refreshConfiguration()
    }
    fun refreshConfiguration(){
        if(closed)return
        changedKeys.singleOrNull()?.let(::selectFocus);changedKeys.clear()
        val values=preferences?.all.orEmpty().filterKeys {it in relevantKeys}.toMap()
        effectEnabled=values["v_clarity"]==true
        val readings=HighFrequencyController.runtime
        val active=readings.filter {it.enabled&&it.processing==OutputRuntimeStatus.Processing.ACTIVE}
            .map {it.rate}.filter {it in 8000..768000}.distinct()
        val configured=readings.map {it.rate}.filter {it in 8000..768000}.distinct()
        val currentRates=active.ifEmpty {configured}
        // A new configured/active rate supersedes the last sampled one. Never hide mixed rates
        // behind a cached single value and present that value as a current measurement.
        val rate=currentRates.singleOrNull()?:observedRate.takeIf {currentRates.isEmpty()}
        val request=Request(values,rate?:48000,rate!=null,focusKey,epoch)
        if(request!=desired){
            desired=request
            if(request==inFlight)pending=null else pending=request
            startWork()
        }
        updateMonitoring()
    }
    private fun sameModel(a:Request?,b:Request):Boolean=a!=null&&a.values==b.values&&a.rate==b.rate&&a.actual==b.actual&&a.epoch==b.epoch
    private fun startWork(){
        if(closed||worker!=null)return
        val request=pending?:return;pending=null;inFlight=request
        val reusable=cachedModel?.takeIf {sameModel(cachedRequest,request)}
        worker=executor.submit {
            val result=runCatching {
                val model=reusable?:ClarityPreviewModel.from(request.values,request.values,request.rate)
                model to ClarityLinkedPlots.pair(model,request.values,request.key,request.actual)
            }
            main.post {
                if(closed)return@post
                worker=null;inFlight=null
                result.onSuccess {(model,pair)->
                    cachedRequest=request;cachedModel=model
                    val current=desired
                    // Keep completed frames during a drag, but never reuse another preset, mode or focus.
                    if(current!=null&&request.epoch==current.epoch&&request.key==current.key&&
                        request.rate==current.rate&&request.actual==current.actual&&
                        stateKeys.all {request.values[it]==current.values[it]}){
                        header.setConfiguration(model,pair,request.key,request.actual)
                    }
                }.onFailure {
                    if(request==desired)header.showPaused("参数模型暂不可用 · 原生调节保持有效")
                }
                startWork()
            }
        }
    }
    private fun updateMonitoring(){
        val previous=monitoring.value
        monitoring.value=!closed&&resumed&&power&&effectEnabled
        val reason=when{
            !effectEnabled->"清晰度增强已关闭 · 参数模型预览"
            !power->"实时采样已关闭 · 两张参数图保留"
            !resumed->"页面暂停 · 采样已暂停"
            !previous->"等待真实增强与抑制读数"
            else->null
        }
        if(reason!=null&&reason!=pausedLabel){pausedLabel=reason;header.showPaused(reason)}
    }
    private fun readConfiguredRate(){
        val token=++readEpoch;configurationRead?.cancel()
        configurationRead=fragment.viewLifecycleOwner.lifecycleScope.launch {
            delay(350L)
            if(closed||!resumed||monitoring.value||readEpoch!=token)return@launch
            val readings=ProfessionalRuntimeMonitor.refreshHighFrequencyConfiguration(context){
                !closed&&resumed&&readEpoch==token&&!monitoring.value
            }
            if(!closed&&resumed&&readEpoch==token&&!monitoring.value){
                readings.map {it.rate}.distinct().singleOrNull()?.takeIf {it in 8000..768000}?.let {rate->
                    if(observedRate!=rate){observedRate=rate;renderFrame.request()}
                }
            }
        }
    }
    fun onResume(){if(!closed){resumed=true;refreshConfiguration();readConfiguredRate()}}
    fun onPause(){if(!closed){resumed=false;readEpoch++;configurationRead?.cancel();configurationRead=null;updateMonitoring()}}
    override fun close(){
        if(closed)return
        closed=true;epoch++;readEpoch++;configurationRead?.cancel();configurationRead=null
        monitoring.value=false;renderFrame.close();focusObserver?.close();focusObserver=null
        worker?.cancel(true);worker=null;pending=null;executor.shutdownNow();main.removeCallbacksAndMessages(null)
        preferences?.unregisterOnSharedPreferenceChangeListener(listener);preferences=null
        context.unregisterLocalReceiver(receiver);header.close();host.clearBassGraphActions(header);host.clearPinnedHeader(header)
    }
}
