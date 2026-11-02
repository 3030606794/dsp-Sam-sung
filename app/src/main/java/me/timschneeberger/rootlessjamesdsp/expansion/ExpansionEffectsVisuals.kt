package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.effectvisuals.*

/** Shares presentation primitives, not a one-size-fits-all effect diagram. */
internal object ExpansionEffectsVisuals {
    fun liveTitle(kind:ExpansionEffectsKind):String=when(kind) {
        ExpansionEffectsKind.MICROSHIFT->"双轨移调与读取位置"
        ExpansionEffectsKind.FORMANT->"处理后的频谱分布"
        ExpansionEffectsKind.FREQUENCY_SHIFT->"移频振荡器与左右偏移"
        ExpansionEffectsKind.VOCODER->"分析频带的实际包络"
        ExpansionEffectsKind.GATED_REVERB->"混响门的开启与收尾"
        ExpansionEffectsKind.PINGPONG->"左右回声路径与抽头"
        ExpansionEffectsKind.AUTO_WAH->"滤波峰随包络扫描"
        ExpansionEffectsKind.AUTOPAN->"左右声像与调制幅度"
        ExpansionEffectsKind.LOW_PHASE->"当前全通相位关系"
        ExpansionEffectsKind.TAPE->"磁带抖晃与录入状态"
        ExpansionEffectsKind.MS_EQ->"中声道／侧声道处理响应"
        ExpansionEffectsKind.MATCH_EQ->"学习进度与频段修正"
        ExpansionEffectsKind.TRANSIENT->"低中高频的瞬态增益"
        ExpansionEffectsKind.RESONANCE->"共振峰与实际压低"
        ExpansionEffectsKind.SUB_BASS->"基音跟踪与次谐波生成"
        ExpansionEffectsKind.LOUDNESS->"音量联动的补偿曲线"
        ExpansionEffectsKind.DEREVERB->"频段尾音与衰减分布"
        ExpansionEffectsKind.DECLICK->"爆点修复事件"
        ExpansionEffectsKind.DEHUM->"嗡声基频跟踪"
        ExpansionEffectsKind.HEADTRACK->"头部姿态与虚拟音箱"
        ExpansionEffectsKind.VOCAL->"两路声部与模型状态"
        ExpansionEffectsKind.SPEAKER_OPTIMIZATION->"扬声器优化前后的实际频谱"
        ExpansionEffectsKind.SMART_ADAPT->"实际低中高频修正"
    }
    fun static(kind: ExpansionEffectsKind, values: Map<String,*>, focusKey: String?): EffectVisualPair {
        val pair=when {
            kind==ExpansionEffectsKind.SMART_ADAPT->SmartAdaptVisuals.static(values,focusKey)
            kind==ExpansionEffectsKind.SPEAKER_OPTIMIZATION->SpeakerOptimizationVisuals.static(values,focusKey)
            kind.id<10->ExpansionVisualsA.static(kind,values,focusKey)
            else->ExpansionVisualsB.static(kind,values,focusKey)
        }
        val inactive=values[kind.enableKey]!=true
        return EffectVisualPair(pair.professional.copy(inactive=inactive),pair.illustration.copy(inactive=inactive))
    }
    fun live(kind: ExpansionEffectsKind,values:Map<String,*>,focusKey:String?,sample:ExpansionEffectsRuntime,
        history:List<EffectVisualFrame>):EffectVisual =
        when {
            kind==ExpansionEffectsKind.SMART_ADAPT->SmartAdaptVisuals.live(values,focusKey,sample,history)
            kind==ExpansionEffectsKind.SPEAKER_OPTIMIZATION->SpeakerOptimizationVisuals.live(values,focusKey,sample,history)
            kind.id<10->ExpansionVisualsA.live(kind,values,focusKey,sample,history)
            else->ExpansionVisualsB.live(kind,values,focusKey,sample,history)
        }
}
