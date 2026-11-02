package me.timschneeberger.rootlessjamesdsp.fusion

/** Display routing. The original surround editors remain the only parameter and audition owners. */
enum class SurroundChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    BAND("抵消频段与响应", "处理频段示意", "实时电平与相关度"),
    CROSSFEED("双向抵消幅度", "左右抵消路径", "抵消处理监测"),
    DELAY("双向延迟与相位", "声波到达示意", "延迟处理监测"),
    LEVEL("输入与混合电平", "干湿与音量示意", "真实输入与输出电平"),
    RMS("RMS 匹配响应", "平均电平补偿", "实时 RMS 补偿"),
    PEAK("峰值上限与恢复", "峰值保护示意", "实时峰值保护"),
    GEOMETRY("摆位与路径差", "扬声器听音示意", "当前音频监测"),
    MATCH("A/B 测量与范围", "等响度对照示意", "A/B 电平测量"),
    CALIBRATION("校准信号目标", "定时与淡入淡出", "校准试听状态"),
    DIAGNOSTIC("相位与时间诊断", "抵消与时间示意", "处理电平与相关度"),
    MONITOR("监测位置与读数", "监测通道示意", "本模块实时监测"),
    AUDITION("试听路径与测点", "分量与 A/B 对照", "正常输出与试听状态");

    companion object {
        fun fromKey(key: String): SurroundChartFocus? = when {
            key in setOf("legacy_low", "legacy_high", "hp_slope", "lp_slope", "response_view") -> BAND
            key in setOf("enabled", "link", "legacy_attenuation", "lr_attenuation", "rl_attenuation") -> CROSSFEED
            key in setOf("legacy_delay", "lr_delay", "rl_delay") -> DELAY
            key in setOf("mix", "pre_db", "output_db") -> LEVEL
            key.startsWith("rms_") -> RMS
            key.startsWith("peak_") -> PEAK
            key in setOf("speaker_spacing", "listening_distance", "ear_spacing", "apply_geometry") -> GEOMETRY
            key.startsWith("match_") || key == "remeasure" -> MATCH
            key.startsWith("cal_") || key == "calibration" -> CALIBRATION
            key in setOf("diagnostics_on", "diagnostics_view", "impulse_window") -> DIAGNOSTIC
            key == "meters" -> MONITOR
            key in setOf("save_a", "save_b", "audition") -> AUDITION
            else -> null
        }
    }
}
