package me.timschneeberger.rootlessjamesdsp.fusion

/** Read-only display routing; no file contents or saved parameters are changed here. */
enum class DdcChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    FILE("原文件与个人补偿路径", "文件校正与附加音色", "真实文件交接与处理状态"),
    RATIO("校正线性混合系数", "相位与混合示意", "真实校正输入输出频谱"),
    COMPENSATION("个人四级PEQ合成", "个人补偿作用区", "真实个人补偿后频谱"),
    LOW("低频架式参数响应", "低频明暗作用区", "真实低频调节后频谱"),
    HIGH("高频架式参数响应", "高频明暗作用区", "真实高频调节后频谱"),
    PEAK1("峰式1参数响应", "峰式1突出与收敛", "真实峰式1调节后频谱"),
    PEAK2("峰式2参数响应", "峰式2突出与收敛", "真实峰式2调节后频谱"),
    EAR_RATIO("主比例与双耳权重", "左右校正强弱", "真实双耳校正后频谱"),
    EAR_LEVEL("左右耳固定电平", "双耳电平平衡", "真实左右处理电平"),
    PREAMP("共同前级衰减", "原声与校正共同衰减", "主配置前级与预测余量"),
    HEADROOM("静态余量预测判据", "削波余量与安全边际", "真实预测余量与电平"),
    OUTPUT("完整处理后输出", "输出幅度缩放", "真实输出调节后电平"),
    RESPONSE("个人PEQ与固定电平", "个人模型与完整响应", "驱动原文件与双耳响应预测"),
    MONITOR("输入输出与校正测点", "DDC处理监测路线", "真实输入输出电平"),
    MATCH("同文件A/B等电平", "快照等电平对照", "真实A/B测量与衰减"),
    AUDITION("七路试听与测点", "原声与校正对照", "真实试听输出电平");

    companion object {
        fun fromKey(key: String): DdcChartFocus? {
            val p: DdcParameters = DdcParameters
            return when (key) {
                p.ENABLED, p.FILE, p.PROFESSIONAL, "ddc_pro_baseline", "ddc_pro_help", "ddc_pro_presets" -> FILE
                p.RATIO_ON, p.ratio.key -> RATIO
                p.COMPENSATION_ON -> COMPENSATION
                p.LOW_ON, p.lowFrequency.key, p.lowGain.key, p.lowSlope.key -> LOW
                p.HIGH_ON, p.highFrequency.key, p.highGain.key, p.highSlope.key -> HIGH
                p.PEAK1_ON, p.peak1Frequency.key, p.peak1Gain.key, p.peak1Q.key -> PEAK1
                p.PEAK2_ON, p.peak2Frequency.key, p.peak2Gain.key, p.peak2Q.key -> PEAK2
                p.EAR_ON, p.LINKED, p.leftRatio.key, p.rightRatio.key -> EAR_RATIO
                p.leftLevel.key, p.rightLevel.key -> EAR_LEVEL
                p.PREAMP_ON, p.preamp.key -> PREAMP
                p.AUTO_HEADROOM_ON, p.margin.key -> HEADROOM
                p.OUTPUT_ON, p.output.key -> OUTPUT
                p.RESPONSE, "ddc_pro_response_graph", "ddc_pro_response_description" -> RESPONSE
                p.METERS, "ddc_pro_actual", "ddc_pro_compatibility" -> MONITOR
                "ddc_pro_measure", "ddc_pro_match" -> MATCH
                "ddc_pro_save_a", "ddc_pro_save_b", "ddc_pro_audition", "ddc_pro_stop" -> AUDITION
                else -> null
            }
        }
    }
}
