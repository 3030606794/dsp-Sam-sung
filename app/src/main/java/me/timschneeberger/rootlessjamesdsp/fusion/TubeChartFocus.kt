package me.timschneeberger.rootlessjamesdsp.fusion

/** Display focus only; no parameter, audition or processing state is changed here. */
internal enum class TubeChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    SATURATION("归一化饱和转换", "软硬与半波示意", "真实波形与饱和"),
    SOFTEN("原版柔化与低通", "柔化前后示意", "真实干湿频谱"),
    FILTER("饱和与湿声滤波", "低频保留与高切", "真实滤波频谱"),
    MIX("干湿混合系数", "干声与效果比例", "真实干湿与输出"),
    LEVEL("输出衰减目标", "整体幅度示意", "真实输出与超限"),
    QUALITY("过采样重建参考", "采样与对齐示意", "实际倍率与音频"),
    MONITOR("监测开关与测点", "本模块通道示意", "本模块真实电平"),
    MATCH("A/B 测量与衰减", "等响度对照示意", "A/B 实际测量"),
    AUDITION("试听与快照路径", "当前试听示意", "实际试听输出");

    companion object {
        fun fromKey(key: String): TubeChartFocus? = when (key) {
            TubeParameters.PROFESSIONAL, TubeParameters.ENABLED, TubeParameters.SAT_ON,
            TubeParameters.drive.key, TubeParameters.softness.key, TubeParameters.asym.key,
            "v_tube_pro_presets", "v_tube_pro_help" -> SATURATION
            TubeParameters.TONE_ON, TubeParameters.softenMix.key, TubeParameters.softenCut.key -> SOFTEN
            TubeParameters.preHP.key, TubeParameters.postLP.key -> FILTER
            TubeParameters.MIX_ON, TubeParameters.mix.key -> MIX
            TubeParameters.OUTPUT_ON, TubeParameters.output.key -> LEVEL
            TubeParameters.QUALITY_ON, TubeParameters.FACTOR -> QUALITY
            TubeParameters.METERS, "v_tube_pro_actual", "v_tube_pro_compatibility" -> MONITOR
            "v_tube_pro_measure", "v_tube_pro_match" -> MATCH
            "v_tube_pro_save_a", "v_tube_pro_save_b", "v_tube_pro_audition", "v_tube_pro_stop" -> AUDITION
            else -> null
        }
    }
}
