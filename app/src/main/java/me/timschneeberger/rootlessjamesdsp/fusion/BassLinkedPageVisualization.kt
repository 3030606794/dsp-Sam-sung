package me.timschneeberger.rootlessjamesdsp.fusion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate
import me.timschneeberger.rootlessjamesdsp.ui.BassLinkedHeader
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Parameter snapshots and a separate system-mix observer; neither changes audio or audition state. */
internal class BassLinkedPageVisualization(private val fragment:PreferenceFragmentCompat,
    private val requestAudioPermission:()->Unit):AutoCloseable {
    private data class Request(val values:Map<String,*>,val rate:Int?,val key:String,val epoch:Long)
    private val context=fragment.requireContext()
    private val host=fragment.requireActivity() as ProfessionalEffectActivity
    private val display=context.applicationContext.getSharedPreferences("bass_linked_display",Context.MODE_PRIVATE)
    private val parameterKeys=BassParameters.viper.map {it.key}.toSet()+"v_bass_mode"
    private val relevantKeys=parameterKeys+setOf("v_bass",BassParameters.LEGACY_AMOUNT)
    private val main=Handler(Looper.getMainLooper())
    private val executor=Executors.newSingleThreadExecutor {task->Thread(task,"viper-bass-linked-plots").apply {isDaemon=true}}
    private var preferences:SharedPreferences?=null
    private var resumed=false
    private var closed=false
    private var pendingPermission=false
    private var epoch=0L
    private var powered=display.getBoolean("power",false)&&hasPermission()
    private var pinned=display.getBoolean("pinned",true)
    private var expanded=!display.getBoolean("realtimeCollapsed",false)
    private var locked=display.getBoolean("locked",false)
    private var currentFocus=ViperBassLinkedPlots.focus(display.getString("lastFocus",BassParameters.amount.key),emptyMap<String,Any>()).key
    private var lockedFocus=ViperBassLinkedPlots.focus(display.getString("lockedFocus",currentFocus),emptyMap<String,Any>()).key
    private var rate:Int?=null
    private var desired:Request?=null
    private var pending:Request?=null
    private var inFlight:Request?=null
    private var worker:Future<*>?=null
    private var cachedRequest:Request?=null
    private var cachedModel:BassLinkedModel?=null
    private val changedKeys=linkedSetOf<String>()
    private val realtime=MutableStateFlow(false)
    private val frame=PreferenceFrameUpdate {refreshConfiguration()}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(!closed&&(key==null||key in relevantKeys)){
            if(key!=null&&key in parameterKeys)changedKeys+=key
            frame.request()
        }
    }
    private val receiver=object:BroadcastReceiver(){
        override fun onReceive(context:Context,intent:Intent){
            if(!closed&&intent.action==Constants.ACTION_PRESET_LOADED)reloadPreferences()
        }
    }
    private val header:BassLinkedHeader=BassLinkedHeader(context,powered,pinned,expanded,
        onPowerChanged={value->selectPower(value)},
        onPinChanged={value->if(!closed){
            pinned=value;display.edit().putBoolean("pinned",value).apply();renderDisplay();host.setPinnedHeader(headerView(),value)
        }},onExpandedChanged={value->if(!closed){
            expanded=value;display.edit().putBoolean("realtimeCollapsed",!value).apply();renderDisplay()
        }},locked=locked,onLockChanged={value->if(!closed){
            locked=value;if(value)lockedFocus=currentFocus
            persistFocus();renderDisplay();refreshConfiguration()
        }})
    private fun headerView():BassLinkedHeader=header
    private val focusObserver=BassParameterFocusObserver(fragment){key->
        if(!closed&&resumed&&selectFocus(key))frame.request()
    }

    init {
        reloadPreferences();renderDisplay();host.setPinnedHeader(header,pinned)
        host.setBassGraphActions(header,header.toolbarActions)
        context.registerLocalReceiver(receiver,IntentFilter(Constants.ACTION_PRESET_LOADED))
        // This is the original configured DSP-rate source. Visualizer's capture rate never
        // substitutes for the processing rate in parameter-filter calculations.
        ProfessionalRuntimeMonitor.bind(fragment.viewLifecycleOwner,context,
            modules=setOf(ProfessionalRuntimeMonitor.Module.BASS)){output->
            if(!closed){
                val next=output.rates.distinct().singleOrNull()?.takeIf {it in 8000..768000}
                if(next!=rate){rate=next;frame.request()}
            }
        }
        BassEnergyMonitor.bind(fragment.viewLifecycleOwner,context,realtime,
            onSample={if(!closed&&realtime.value)header.showEnergy(it)},
            onState={state->if(!closed&&realtime.value){
                if(!hasPermission()){
                    powered=false;display.edit().putBoolean("power",false).apply();renderDisplay();updateSubscription()
                    header.showCaptureState("未获系统混音采样所需权限")
                }else header.showCaptureState(state.label)
            }})
    }
    private fun hasPermission():Boolean=ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun persistFocus(){
        display.edit().putBoolean("locked",locked).putString("lockedFocus",lockedFocus).putString("lastFocus",currentFocus).apply()
    }
    private fun selectFocus(key:String):Boolean {
        if(key !in parameterKeys&&key!=BassParameters.LEGACY_AMOUNT)return false
        val next=ViperBassLinkedPlots.focus(key,preferences?.all.orEmpty()).key
        if(next==currentFocus)return false
        currentFocus=next;persistFocus();return true
    }
    private fun reloadPreferences(){
        epoch++;desired=null;pending=null;changedKeys.clear()
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences=BassController.preferences(context,true).also {it.registerOnSharedPreferenceChangeListener(listener)}
        refreshConfiguration()
    }
    private fun refreshConfiguration(){
        if(closed)return
        changedKeys.singleOrNull()?.let(::selectFocus);changedKeys.clear()
        val values=preferences?.all.orEmpty().filterKeys {it in relevantKeys}.toMap()
        val focus=if(locked)lockedFocus else currentFocus
        val request=Request(values,rate,focus,epoch)
        if(request!=desired){
            desired=request
            if(request==inFlight)pending=null else pending=request
            startWork()
        }
    }
    private fun sameModel(a:Request?,b:Request):Boolean=a!=null&&a.values==b.values&&a.rate==b.rate&&a.epoch==b.epoch
    private fun startWork(){
        if(closed||worker!=null)return
        val request=pending?:return;pending=null;inFlight=request
        val reuse=cachedModel?.takeIf {sameModel(cachedRequest,request)}
        worker=executor.submit {
            val result=runCatching {
                val model=reuse?:BassLinkedModel.from(request.values,request.rate)
                model to ViperBassLinkedPlots.pair(model,request.key)
            }
            main.post {
                if(closed)return@post
                worker=null;inFlight=null
                result.onSuccess {(model,pair)->
                    cachedRequest=request;cachedModel=model
                    val current=desired
                    // Ordered completed frames keep the plots responsive throughout a drag.
                    // A change of preset, mode, rate, selected group or effect state rejects them.
                    if(current!=null&&request.epoch==current.epoch&&request.key==current.key&&request.rate==current.rate&&
                        request.values["v_bass"]==current.values["v_bass"]&&request.values["v_bass_mode"]==current.values["v_bass_mode"]){
                        header.setConfiguration(model,pair)
                    }
                }.onFailure {if(request==desired)header.showCaptureState("参数图暂不可用 · 原生调节保持有效")}
                startWork()
            }
        }
    }
    private fun selectPower(value:Boolean){
        if(closed)return
        if(!value){pendingPermission=false;powered=false}
        else if(hasPermission())powered=true
        else {if(!pendingPermission){pendingPermission=true;requestAudioPermission()};return}
        display.edit().putBoolean("power",powered).apply();renderDisplay();updateSubscription()
    }
    fun onPermissionResult(granted:Boolean){
        if(closed||!pendingPermission)return
        pendingPermission=false;powered=granted&&hasPermission()
        display.edit().putBoolean("power",powered).apply();renderDisplay();updateSubscription()
    }
    private fun renderDisplay():Unit=header.setDisplay(powered,pinned,expanded,locked)
    private fun updateSubscription(){
        // System-mix observation remains usable with the effect off; it is not module telemetry.
        realtime.value=!closed&&resumed&&powered&&hasPermission()
        if(!realtime.value)header.showCaptureState(if(!powered)"实时采样已关闭 · 两张参数图保留"else"页面暂停 · 实时采样已暂停")
    }
    fun onResume(){if(!closed){
        resumed=true
        if(!hasPermission()){powered=false;display.edit().putBoolean("power",false).apply()}
        renderDisplay();refreshConfiguration();updateSubscription()
    }}
    fun onPause(){if(!closed){resumed=false;updateSubscription()}}
    override fun close(){
        if(closed)return
        closed=true;epoch++;realtime.value=false;frame.close();focusObserver.close()
        worker?.cancel(true);worker=null;pending=null;executor.shutdownNow();main.removeCallbacksAndMessages(null)
        preferences?.unregisterOnSharedPreferenceChangeListener(listener);preferences=null
        context.unregisterLocalReceiver(receiver);host.clearBassGraphActions(header);host.clearPinnedHeader(header);header.close()
    }
}
