package me.timschneeberger.rootlessjamesdsp.fusion

/** Display focus only; the original five controls and every professional callback remain their owners. */
internal enum class ReverbChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    ROOM("房间反馈与尾音", "房间反馈示意", "输入与混响输出"),
    DECAY("分频尾音衰减预测", "低中高尾音示意", "尾音目标与真实电平"),
    TONE("阻尼与湿声滤波", "尾音明暗示意", "滤波后混响电平"),
    WIDTH("混响宽度矩阵", "立体声扩散示意", "左右混响峰值"),
    PREDELAY("预延迟与时序", "干湿到达示意", "预延迟处理电平"),
    MIX("干湿系数与混合", "原声与尾音示意", "输入湿声输出峰值"),
    DUCK("湿声避让阈值", "避让与恢复示意", "真实湿声避让"),
    LEVEL("输出增益目标", "整体电平示意", "真实输出与超限"),
    MONITOR("诊断开关与测点", "监测通道示意", "本模块真实峰值"),
    MATCH("A/B 测量与衰减", "等响度对照示意", "A/B 实际测量"),
    AUDITION("试听与快照路径", "当前试听示意", "实际试听与湿声");

    companion object {
        fun fromKey(key: String): ReverbChartFocus? = when (key) {
            "v_reverb_size", ReverbParameters.size.key, ReverbParameters.PROFESSIONAL,
            ReverbParameters.ENABLED, "v_reverb_pro_presets" -> ROOM
            ReverbAdvancedParameters.RT_ON, ReverbAdvancedParameters.rt60.key,
            ReverbAdvancedParameters.BANDS_ON, ReverbAdvancedParameters.bassRatio.key,
            ReverbAdvancedParameters.trebleRatio.key, ReverbAdvancedParameters.lowCrossover.key,
            ReverbAdvancedParameters.highCrossover.key, ReverbAdvancedParameters.GRAPH_ON -> DECAY
            "v_reverb_damp", ReverbParameters.damp.key, ReverbParameters.LOW_CUT_ON,
            ReverbParameters.HIGH_CUT_ON, ReverbParameters.lowCut.key, ReverbParameters.highCut.key -> TONE
            "v_reverb_width", ReverbParameters.width.key -> WIDTH
            ReverbParameters.PREDELAY_ON, ReverbParameters.predelay.key -> PREDELAY
            "v_reverb_wet", "v_reverb_dry", ReverbParameters.wet.key, ReverbParameters.dry.key,
            ReverbParameters.LINKED, ReverbParameters.mix.key -> MIX
            ReverbAdvancedParameters.DUCK_ON, ReverbAdvancedParameters.threshold.key,
            ReverbAdvancedParameters.depth.key, ReverbAdvancedParameters.attack.key,
            ReverbAdvancedParameters.release.key -> DUCK
            ReverbParameters.OUTPUT_ON, ReverbParameters.output.key -> LEVEL
            ReverbParameters.METERS, "v_reverb_pro_actual" -> MONITOR
            "v_reverb_pro_measure", "v_reverb_pro_match" -> MATCH
            "v_reverb_pro_save_a", "v_reverb_pro_save_b", "v_reverb_pro_audition", "v_reverb_pro_stop" -> AUDITION
            else -> null
        }
    }
}
