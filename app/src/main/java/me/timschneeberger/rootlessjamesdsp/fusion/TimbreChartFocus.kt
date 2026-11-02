package me.timschneeberger.rootlessjamesdsp.fusion

/** Focus routing only; no sound parameter or temporary listening state is changed here. */
internal enum class TimbreChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    CORE("驱动与平方染色目标", "基础与染色示意", "真实输入与处理波形"),
    BANDS("四路染色增量目标", "四路染色强弱示意", "真实分段染色频谱"),
    CROSSOVER("五点六段分频模型", "四路与两端基础声", "真实分频后频谱"),
    FILTER("染色支路滤波目标", "染色覆盖频带示意", "真实支路滤波频谱"),
    DC("染色直流消除目标", "偏置与恢复示意", "实际输出直流均值"),
    MIX("基础声与染色系数", "相位匹配混合示意", "真实处理与试听输出"),
    LEVEL("模块输出衰减目标", "整体幅度示意", "真实输出与超限"),
    COMPENSATION("驱动与反向补偿", "基础与增量缩放", "真实补偿后电平"),
    QUALITY("IIR / FIR 倍率目标", "采样与共同对齐", "实际倍率与真实频谱"),
    MONITOR("监测开关与测点", "本模块通道示意", "四路混合前真实 RMS"),
    MATCH("A/B 测量与衰减", "等电平对照示意", "A/B 实际测量"),
    AUDITION("十二路试听与独听", "当前试听示意", "实际试听与独听");

    companion object {
        fun fromKey(key: String): TimbreChartFocus? = when (key) {
            TimbreParameters.LEGACY, TimbreParameters.ENABLED, TimbreParameters.PROFESSIONAL,
            TimbreParameters.CORE_ON, TimbreParameters.drive.key, TimbreParameters.amount.key,
            "timbre_pro_baseline", "timbre_pro_help", "timbre_pro_presets" -> CORE
            TimbreParameters.band1.key, TimbreParameters.band2.key, TimbreParameters.band3.key,
            TimbreParameters.band4.key, "timbre_pro_bands" -> BANDS
            TimbreParameters.TONE_ON, TimbreParameters.cut1.key, TimbreParameters.cut2.key,
            TimbreParameters.cut3.key, TimbreParameters.cut4.key, TimbreParameters.cut5.key -> CROSSOVER
            TimbreParameters.colorHP.key, TimbreParameters.colorLP.key -> FILTER
            TimbreParameters.DC_ON, TimbreParameters.dcHz.key -> DC
            TimbreParameters.MIX_ON, TimbreParameters.mix.key -> MIX
            TimbreParameters.OUTPUT_ON, TimbreParameters.output.key -> LEVEL
            TimbreParameters.COMP_ON, TimbreParameters.comp.key -> COMPENSATION
            TimbreParameters.QUALITY_ON, TimbreParameters.FACTOR -> QUALITY
            TimbreParameters.METERS, "timbre_pro_actual", "timbre_pro_compatibility" -> MONITOR
            "timbre_pro_measure", "timbre_pro_match" -> MATCH
            "timbre_pro_save_a", "timbre_pro_save_b", "timbre_pro_audition", "timbre_pro_stop",
            "timbre_pro_solo_gain", "timbre_pro_solo_help" -> AUDITION
            else -> null
        }
    }
}
