package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate
import me.timschneeberger.rootlessjamesdsp.ui.SpectrumDashboardHeader
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Read-only display snapshots. Native controls and the independent energy collector still own audio. */
internal class SpectrumDashboardCoordinator(private val fragment: Fragment) : AutoCloseable {
    private data class Request(val values:Map<String,*>,val rate:Int,val actual:Boolean,val key:String,val epoch:Long)
    private val context=fragment.requireContext()
    private val host=fragment.requireActivity() as ProfessionalEffectActivity
    private val display=context.applicationContext.getSharedPreferences("spectrum_dashboard_display",Context.MODE_PRIVATE)
    private val main=Handler(Looper.getMainLooper())
    private val executor=Executors.newSingleThreadExecutor {work->Thread(work,"spectrum-linked-plots").apply {isDaemon=true}}
    private var preferences:SharedPreferences?=null
    private var closed=false
    private var resumed=false
    private var epoch=0L
    private var power=display.getBoolean("power",false)
    private var pinned=display.getBoolean("pinned",true)
    private var expanded=display.getBoolean("expanded",true)
    private var locked=display.getBoolean("locked",false)
    private var focusKey=SpectrumLinkedPlots.focus(display.getString("focus",null)).key
    private var effectEnabled=false
    private var observedRate:Int?=null
    private var pausedLabel:String?=null
    private var desired:Request?=null
    private var pending:Request?=null
    private var inFlight:Request?=null
    private var worker:Future<*>?=null
    private var cachedRequest:Request?=null
    private var cachedModel:SpectrumDashboardModel?=null
    private var focusObserver:SpectrumParameterFocusObserver?=null
    private val changedKeys=linkedSetOf<String>()
    private val parameterKeys=HighFrequencyParameters.spectrum.map {it.key}.toSet()+
        HighFrequencyAdvancedParameters.nonlinear.map {it.key}+HighFrequencyAdvancedParameters.OVERSAMPLING+HighFrequencyParameters.LP_AUTO
    private val relevantKeys=parameterKeys+setOf("v_spectrum","v_spectrum_amount")
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
            if(intent.action==Constants.ACTION_PRESET_LOADED)reloadPreferences()
            else {header.refreshOriginalStatus();renderFrame.request()}
        }
    }
    private val header:SpectrumDashboardHeader=SpectrumDashboardHeader(context,power,pinned,expanded,
        onPowerChanged={value->
            if(!closed){power=value;display.edit().putBoolean("power",value).apply();renderDisplay();updateMonitoring()}
        },onPinChanged={value->
            if(!closed){pinned=value;display.edit().putBoolean("pinned",value).apply();renderDisplay();host.setPinnedHeader(headerView(),value)}
        },onExpandedChanged={value->
            if(!closed){expanded=value;display.edit().putBoolean("expanded",value).apply();renderDisplay()}
        },locked=locked,onLockChanged={value->
            if(!closed){locked=value;display.edit().putBoolean("locked",value).apply();renderDisplay()}
        })
    private fun headerView():SpectrumDashboardHeader=header
    private fun renderDisplay():Unit=header.setDisplay(power,pinned,expanded,locked)

    init {
        reloadPreferences();renderDisplay();host.setPinnedHeader(header,pinned)
        host.setBassGraphActions(header,header.toolbarActions)
        (fragment as? PreferenceFragmentCompat)?.let {nativeFragment->
            focusObserver=SpectrumParameterFocusObserver(nativeFragment){key->
                if(!closed&&resumed&&selectFocus(key))renderFrame.request()
            }
        }
        context.registerLocalReceiver(receiver,IntentFilter().apply {
            addAction(Constants.ACTION_PRESET_LOADED);addAction(HighFrequencyController.STATUS_ACTION)
        })
        SpectrumDashboardRuntimeMonitor.bind(fragment.viewLifecycleOwner,context,monitoring){sample->
            if(!closed&&monitoring.value){
                pausedLabel=null;header.showSample(sample)
                sample.reading?.rate?.takeIf {it in 8000..768000}?.let {rate->
                    if(observedRate!=rate){observedRate=rate;renderFrame.request()}
                }
            }
        }
    }
    private fun selectFocus(key:String):Boolean {
        if(locked||key !in parameterKeys)return false
        val next=SpectrumLinkedPlots.focus(key).key
        if(next==focusKey)return false
        focusKey=next;display.edit().putString("focus",next).apply();return true
    }
    private fun reloadPreferences(){
        epoch++;observedRate=null;desired=null;pending=null;changedKeys.clear()
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences=HighFrequencyController.preferences(context).also {it.registerOnSharedPreferenceChangeListener(listener)}
        refreshConfiguration()
    }
    private fun refreshConfiguration(){
        if(closed)return
        changedKeys.singleOrNull()?.let(::selectFocus);changedKeys.clear()
        val values=preferences?.all.orEmpty().filterKeys {it in relevantKeys}.toMap()
        effectEnabled=values["v_spectrum"]==true
        val readings=HighFrequencyController.runtime
        val activeRates=readings.filter {it.enabled&&it.processing==OutputRuntimeStatus.Processing.ACTIVE}
            .map {it.rate}.filter {it in 8000..768000}.distinct()
        val rate=observedRate?:activeRates.singleOrNull()?:readings.map {it.rate}.filter {it in 8000..768000}.distinct().singleOrNull()
        val request=Request(values,rate?:48000,rate!=null,focusKey,epoch)
        if(request!=desired){
            desired=request
            if(request==inFlight)pending=null else pending=request
            startWork()
        }
        updateMonitoring()
    }
    private fun sameModel(a:Request?,b:Request)=a!=null&&a.values==b.values&&a.rate==b.rate&&a.actual==b.actual&&a.epoch==b.epoch
    private fun startWork(){
        if(closed||worker!=null)return
        val request=pending?:return;pending=null;inFlight=request
        val reusable=cachedModel?.takeIf {sameModel(cachedRequest,request)}
        worker=executor.submit {
            val result=runCatching {
                val model=reusable?:SpectrumDashboardModel.from(request.values,request.rate,request.actual)
                model to SpectrumLinkedPlots.pair(model,request.key)
            }
            main.post {
                if(closed)return@post
                worker=null;inFlight=null
                result.onSuccess {(model,pair)->
                    cachedRequest=request;cachedModel=model
                    val current=desired
                    // Completed frames remain visible during a drag, but not across presets, focus or rate.
                    if(current!=null&&request.epoch==current.epoch&&request.key==current.key&&
                        request.rate==current.rate&&request.actual==current.actual&&
                        request.values["v_spectrum"]==current.values["v_spectrum"]){
                        header.setConfiguration(model,pair,request.key)
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
            !effectEnabled->"高频谐波激励已关闭 · 参数预览保留"
            !power->"实时采样已关闭 · 两张参数图保留"
            !resumed->"页面暂停 · 图纸采样已暂停"
            !previous->"等待真实激励支路能量"
            else->null
        }
        if(reason!=null&&reason!=pausedLabel){pausedLabel=reason;header.showPaused(reason)}
    }
    fun onResume(){if(!closed){resumed=true;refreshConfiguration()}}
    fun onPause(){if(!closed){resumed=false;updateMonitoring()}}
    override fun close(){
        if(closed)return
        closed=true;epoch++;monitoring.value=false;renderFrame.close();focusObserver?.close();focusObserver=null
        worker?.cancel(true);worker=null;pending=null;executor.shutdownNow();main.removeCallbacksAndMessages(null)
        preferences?.unregisterOnSharedPreferenceChangeListener(listener);preferences=null
        context.unregisterLocalReceiver(receiver);header.close();host.clearBassGraphActions(header);host.clearPinnedHeader(header)
    }
}
