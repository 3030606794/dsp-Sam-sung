package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceFrameUpdate
import me.timschneeberger.rootlessjamesdsp.ui.BassProfessionalHeader
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Display snapshots only. The original preferences and runtime collector retain all audio ownership. */
internal class AdaptiveBassDashboardCoordinator(private val fragment:PreferenceFragmentCompat):AutoCloseable {
    private data class Request(val values:Map<String,*>,val enabled:Boolean,val rate:Int,val actual:Boolean,val key:String,val epoch:Long)
    private val context=fragment.requireContext()
    private val host=fragment.requireActivity() as ProfessionalEffectActivity
    private val display=context.applicationContext.getSharedPreferences("bass_monitor_display",Context.MODE_PRIVATE)
    private val effectKey=context.getString(R.string.key_bass_enable)
    private val parameterKeys=BassParameters.dynamic.map {it.key}.toSet()
    private val relevantKeys=parameterKeys+setOf(effectKey,BassParameters.LEGACY_MAX_GAIN)
    private val main=Handler(Looper.getMainLooper())
    private val executor=Executors.newSingleThreadExecutor {task->Thread(task,"adaptive-bass-linked-plots").apply {isDaemon=true}}
    private var preferences:SharedPreferences?=null
    private var closed=false
    private var resumed=false
    private var epoch=0L
    private var power=display.getBoolean("enabled",false)
    private var pinned=display.getBoolean("pinned",true)
    private var expanded=!display.getBoolean("realtime_collapsed",false)
    private var locked=display.getBoolean("locked",false)
    private var focusKey=AdaptiveBassLinkedPlots.focus(display.getString("focus",null)).key
    private var effectEnabled=false
    private var observedRate:Int?=null
    private var actualRate=false
    private var pausedLabel:String?=null
    private var desired:Request?=null
    private var pending:Request?=null
    private var inFlight:Request?=null
    private var worker:Future<*>?=null
    private var cachedRequest:Request?=null
    private var cachedConfiguration:AdaptiveBassPlotConfiguration?=null
    private var focusObserver:AdaptiveBassParameterFocusObserver?=null
    private val changedKeys=linkedSetOf<String>()
    private val monitoring=MutableStateFlow(false)
    private val frame=PreferenceFrameUpdate {refreshConfiguration()}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(!closed&&(key==null||key in relevantKeys)){
            if(key!=null&&key in parameterKeys)changedKeys+=key
            frame.request()
        }
    }
    private val receiver=object:BroadcastReceiver(){
        override fun onReceive(context:Context,intent:Intent){
            if(closed)return
            if(intent.action==Constants.ACTION_PRESET_LOADED)reloadPreferences()
            else {header.refreshOriginalStatus();frame.request()}
        }
    }
    private val header:BassProfessionalHeader=BassProfessionalHeader(context,power,pinned,expanded,
        onPowerChanged={value->
            if(!closed){power=value;display.edit().putBoolean("enabled",value).apply();renderDisplay();refreshConfiguration()}
        },onPinChanged={value->
            if(!closed){pinned=value;display.edit().putBoolean("pinned",value).apply();renderDisplay();host.setPinnedHeader(headerView(),value)}
        },onExpandedChanged={value->
            if(!closed){expanded=value;display.edit().putBoolean("realtime_collapsed",!value).apply();renderDisplay()}
        },locked=locked,onLockChanged={value->
            if(!closed){locked=value;display.edit().putBoolean("locked",value).apply();renderDisplay()}
        })
    private fun headerView():BassProfessionalHeader=header
    private fun renderDisplay():Unit=header.setDisplay(power,pinned,expanded,locked)

    init {
        reloadPreferences();renderDisplay();host.setPinnedHeader(header,pinned)
        host.setBassGraphActions(header,header.toolbarActions)
        focusObserver=AdaptiveBassParameterFocusObserver(fragment){key->
            if(!closed&&resumed&&selectFocus(key))frame.request()
        }
        context.registerLocalReceiver(receiver,IntentFilter().apply {
            addAction(Constants.ACTION_PRESET_LOADED);addAction(BassController.STATUS_ACTION)
        })
        ProfessionalRuntimeMonitor.bindBassMonitor(fragment.viewLifecycleOwner,context,monitoring){sample->
            if(!closed&&monitoring.value){
                pausedLabel=null;header.showSample(sample)
                val output=sample.output
                val rates=output.activeSessions.map {it.rate}.distinct().filter {it in 8000..768000}
                val validRate=rates.singleOrNull()?.takeIf {output.activeSessions.size==1&&output.connected&&
                    output.processing==OutputRuntimeStatus.Processing.ACTIVE&&output.globalIssue==null}
                val nextRate=validRate?:observedRate.takeIf {rates.size<=1}
                val nextActual=validRate!=null
                if(observedRate!=nextRate||actualRate!=nextActual){
                    observedRate=nextRate;actualRate=nextActual;frame.request()
                }
            }
        }
    }
    private fun selectFocus(key:String):Boolean {
        if(locked||key !in parameterKeys&&key!=BassParameters.LEGACY_MAX_GAIN&&key!="bass_legacy_gain_ui")return false
        val next=AdaptiveBassLinkedPlots.focus(key).key
        if(next==focusKey)return false
        focusKey=next;display.edit().putString("focus",next).apply();return true
    }
    private fun reloadPreferences(){
        epoch++;desired=null;pending=null;changedKeys.clear();observedRate=null;actualRate=false
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences=BassController.preferences(context,false).also {it.registerOnSharedPreferenceChangeListener(listener)}
        refreshConfiguration()
    }
    fun refreshConfiguration(){
        if(closed)return
        changedKeys.singleOrNull()?.let(::selectFocus);changedKeys.clear()
        val values=preferences?.all.orEmpty().filterKeys {it in relevantKeys}.toMap()
        effectEnabled=values[effectKey]==true
        val request=Request(values,effectEnabled,observedRate?:48000,
            actualRate&&power&&effectEnabled&&resumed,focusKey,epoch)
        if(request!=desired){
            desired=request
            if(request==inFlight)pending=null else pending=request
            startWork()
        }
        updateMonitoring()
    }
    private fun sameConfiguration(a:Request?,b:Request):Boolean=a!=null&&a.values==b.values&&a.enabled==b.enabled&&
        a.rate==b.rate&&a.actual==b.actual&&a.epoch==b.epoch
    private fun startWork(){
        if(closed||worker!=null)return
        val request=pending?:return;pending=null;inFlight=request
        val reuse=cachedConfiguration?.takeIf {sameConfiguration(cachedRequest,request)}
        worker=executor.submit {
            val result=runCatching {
                val configuration=reuse?:AdaptiveBassLinkedPlots.configuration(request.values,request.enabled,request.rate,request.actual)
                configuration to AdaptiveBassLinkedPlots.pair(configuration,request.key)
            }
            main.post {
                if(closed)return@post
                worker=null;inFlight=null
                result.onSuccess {(configuration,pair)->
                    cachedRequest=request;cachedConfiguration=configuration
                    val current=desired
                    // Ordered completed frames remain visible during a drag, so a steady stream
                    // of new values cannot starve both plots until the thumb is released.
                    if(current!=null&&request.epoch==current.epoch&&request.key==current.key&&
                        request.enabled==current.enabled&&request.rate==current.rate&&request.actual==current.actual){
                        header.setConfiguration(configuration,pair,request.key)
                    }
                }.onFailure {if(request==desired)header.showPaused("参数图暂不可用 · 原生调节保持有效")}
                startWork()
            }
        }
    }
    private fun updateMonitoring(){
        val before=monitoring.value
        monitoring.value=!closed&&resumed&&power&&effectEnabled
        val reason=when{
            !effectEnabled->"自适应低频增强已关闭 · 参数预览保留"
            !power->"实时采样已关闭 · 两张参数图保留"
            !resumed->"页面暂停 · 实时采样已暂停"
            !before->"等待实际跟踪频率与增强读数"
            else->null
        }
        if(reason!=null&&reason!=pausedLabel){pausedLabel=reason;header.showPaused(reason)}
    }
    fun onResume(){if(!closed){resumed=true;refreshConfiguration()}}
    fun onPause(){if(!closed){resumed=false;actualRate=false;updateMonitoring()}}
    override fun close(){
        if(closed)return
        closed=true;epoch++;monitoring.value=false;frame.close();focusObserver?.close();focusObserver=null
        worker?.cancel(true);worker=null;pending=null;executor.shutdownNow();main.removeCallbacksAndMessages(null)
        preferences?.unregisterOnSharedPreferenceChangeListener(listener);preferences=null
        context.unregisterLocalReceiver(receiver);header.close();host.clearBassGraphActions(header);host.clearPinnedHeader(header)
    }
}
