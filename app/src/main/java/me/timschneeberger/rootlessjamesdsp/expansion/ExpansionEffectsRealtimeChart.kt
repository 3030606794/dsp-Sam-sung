package me.timschneeberger.rootlessjamesdsp.expansion

import android.content.Context
import android.os.SystemClock
import me.timschneeberger.rootlessjamesdsp.effectvisuals.*

/** Bounded history of confirmed samples; visual providers choose their own domain and geometry. */
internal class ExpansionEffectsRealtimeChart(context:Context,private val kind:ExpansionEffectsKind):EffectVisualView(context,12f) {
    private val history=ArrayDeque<EffectVisualFrame>()
    private var values:Map<String,*> = emptyMap<String,Any>()
    private var focus:String?=null
    private var latest:ExpansionEffectsRuntime?=null
    private var rate=0
    private var parameterHash=0
    private var breakNext=true
    var visualTitle:String="实时处理状态"
        private set
    fun setConfiguration(configuration:Map<String,*>,focusKey:String?) {
        val changed=values!=configuration
        values=configuration;focus=focusKey
        if(changed){latest=null;history.clear();breakNext=true;setStatus("参数已更新 · 等待驱动实际处理")}
        else latest?.let(::render)
    }
    fun pause(reason:String) {latest=null;breakNext=true;setStatus(reason)}
    fun showSample(sample:ExpansionEffectsRuntime) {
        if(!sample.isProcessing||!sample.enabled){pause("等待正常播放与活动音频");return}
        val now=SystemClock.elapsedRealtime()
        if(rate!=sample.sampleRate||parameterHash!=sample.parameterHash){history.clear();breakNext=true;rate=sample.sampleRate;parameterHash=sample.parameterHash}
        val old=history.lastOrNull()
        history.addLast(EffectVisualFrame(now,sample.meters,sample.inputPeakDb,sample.outputPeakDb,sample.wetPeakDb,sample.sampleRate,
            breakNext||old==null||now-old.timeMs>1500))
        while(history.size>101||(history.isNotEmpty()&&history.first().timeMs<now-20000))history.removeFirst()
        latest=sample;breakNext=false;render(sample)
    }
    private fun render(sample:ExpansionEffectsRuntime) {
        val visual=ExpansionEffectsVisuals.live(kind,values,focus,sample,history.toList())
        visualTitle=visual.title;setVisual(visual)
        // Learning, loading and head-tracker availability are meaningful live states; providers draw them.
        setStatus(null)
    }
}
