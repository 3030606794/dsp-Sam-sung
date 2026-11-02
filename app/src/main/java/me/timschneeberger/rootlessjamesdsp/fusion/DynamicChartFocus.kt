package me.timschneeberger.rootlessjamesdsp.fusion

/** Display focus only. All preset, strength and professional editors retain ownership of their values. */
internal enum class DynamicChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    BASE("基础预设与合成响应", "当前音色基础示意", "真实动态音色频谱"),
    GAIN("低频整体增益目标", "低频支路强弱示意", "真实支路调节后频谱"),
    BRANCH("低段与高段比例", "支路比例示意", "真实分带处理频谱"),
    DEEP("深低音注入与共振", "同相低频注入示意", "真实深低音处理频谱"),
    CROSSOVER("主分带与支路分界", "频段覆盖示意", "真实分频后频谱"),
    FILTER("增强支路滤波目标", "增强覆盖频带示意", "真实增强滤波后频谱"),
    DETECTOR("电平检测选频目标", "双声道检测示意", "真实检测与增强回收"),
    DYNAMICS("增强回收阈值曲线", "支路回收示意", "真实增强支路回收"),
    TIMING("增强回收攻释包络", "启动与恢复示意", "真实检测与回收时序"),
    MIX("对齐原声与处理比例", "原声与效果路径", "真实混合输出频谱"),
    LEVEL("模块输出衰减目标", "整体幅度示意", "真实输出与超限"),
    RESPONSE("合成幅频参考", "合成相位参考", "驱动实际响应预测"),
    MONITOR("监测开关与测点", "本模块通道示意", "本模块真实电平"),
    MATCH("A/B 测量与衰减", "等电平对照示意", "A/B 实际测量"),
    AUDITION("九路试听与快照", "当前试听示意", "实际试听与支路");

    companion object {
        fun fromKey(key: String): DynamicChartFocus? = when (key) {
            DynamicParameters.BASE, DynamicParameters.LEGACY, DynamicParameters.ENABLED,
            DynamicParameters.PROFESSIONAL, "dynamic_pro_baseline", "dynamic_pro_help", "dynamic_pro_presets" -> BASE
            DynamicParameters.CORE_ON, DynamicParameters.bassDelta.key -> GAIN
            DynamicParameters.lowGainDelta.key, DynamicParameters.highGainDelta.key -> BRANCH
            DynamicParameters.injectDelta.key, DynamicParameters.qDelta.key, DynamicParameters.deepFreqDelta.key -> DEEP
            DynamicParameters.TONE_ON, DynamicParameters.xDelta.key, DynamicParameters.lowFreqDelta.key,
            DynamicParameters.highFreqDelta.key -> CROSSOVER
            DynamicParameters.hp.key, DynamicParameters.lp.key -> FILTER
            DynamicParameters.DETECTOR, DynamicParameters.detectorHP.key, DynamicParameters.detectorLP.key -> DETECTOR
            DynamicParameters.ADAPTIVE_ON, DynamicParameters.threshold.key, DynamicParameters.knee.key,
            DynamicParameters.maxReduction.key -> DYNAMICS
            DynamicParameters.attack.key, DynamicParameters.release.key -> TIMING
            DynamicParameters.MIX_ON, DynamicParameters.mix.key -> MIX
            DynamicParameters.OUTPUT_ON, DynamicParameters.output.key -> LEVEL
            DynamicParameters.RESPONSE, "dynamic_pro_response", "dynamic_pro_response_graph" -> RESPONSE
            DynamicParameters.METERS, "dynamic_pro_actual", "dynamic_pro_compatibility" -> MONITOR
            "dynamic_pro_measure", "dynamic_pro_match" -> MATCH
            "dynamic_pro_save_a", "dynamic_pro_save_b", "dynamic_pro_audition", "dynamic_pro_stop" -> AUDITION
            else -> null
        }
    }
}
