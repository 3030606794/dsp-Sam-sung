package me.timschneeberger.rootlessjamesdsp.fusion

/** Page focus only. AnalogX parameters and all A/B operations keep their original owners. */
internal enum class AnalogChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    CORE("染色内核与三档", "基础与注入示意", "真实染色波形"),
    HARMONICS("二至五次系数目标", "奇偶与半波示意", "真实谐波染色波形"),
    FILTER("染色低切与整体高切", "频带参与示意", "真实干湿频谱"),
    TONE("峰形音色补偿", "厚薄明暗示意", "真实音色频谱"),
    MIX("干湿混合系数", "原声与染色比例", "真实干湿与输出"),
    LEVEL("输出衰减目标", "整体幅度示意", "真实输出与超限"),
    QUALITY("过采样重建参考", "采样与对齐示意", "实际倍率与频谱"),
    MONITOR("监测开关与测点", "本模块通道示意", "本模块真实电平"),
    MATCH("A/B 测量与衰减", "等电平对照示意", "A/B 实际测量"),
    AUDITION("试听与快照路径", "当前试听示意", "实际试听输出");

    companion object {
        fun fromKey(key: String): AnalogChartFocus? = when (key) {
            AnalogParameters.BASE, AnalogParameters.ENABLED, AnalogParameters.PROFESSIONAL,
            AnalogParameters.CORE_ON, AnalogParameters.drive.key, AnalogParameters.amount.key,
            "v_analog_pro_baseline", "v_analog_pro_help", "v_analog_pro_presets" -> CORE
            AnalogParameters.h2.key, AnalogParameters.h3.key, AnalogParameters.h4.key, AnalogParameters.h5.key,
            "v_analog_pro_advanced_harmonics" -> HARMONICS
            AnalogParameters.TONE_ON, AnalogParameters.hp.key, AnalogParameters.hpQ.key,
            AnalogParameters.lpDelta.key, AnalogParameters.lpQ.key -> FILTER
            AnalogParameters.peak.key, AnalogParameters.peakGain.key, AnalogParameters.peakBW.key -> TONE
            AnalogParameters.MIX_ON, AnalogParameters.mix.key -> MIX
            AnalogParameters.OUTPUT_ON, AnalogParameters.output.key -> LEVEL
            AnalogParameters.QUALITY_ON, AnalogParameters.FACTOR -> QUALITY
            AnalogParameters.METERS, "v_analog_pro_actual", "v_analog_pro_compatibility" -> MONITOR
            "v_analog_pro_measure", "v_analog_pro_match" -> MATCH
            "v_analog_pro_save_a", "v_analog_pro_save_b", "v_analog_pro_audition", "v_analog_pro_stop" -> AUDITION
            else -> null
        }
    }
}
