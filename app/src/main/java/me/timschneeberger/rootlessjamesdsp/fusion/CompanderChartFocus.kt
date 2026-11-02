package me.timschneeberger.rootlessjamesdsp.fusion

/** UI focus only: the legacy controls, two processing engines and audition storage remain unchanged. */
internal enum class CompanderChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    FOUNDATION("原基础与变换目标", "算法与更新示意", "真实压扩处理频谱"),
    CURVE("七点频率动态系数", "七点增减作用示意", "七点真实动态增益"),
    ENGINE("相对 / 标准引擎", "两种判据示意", "实际引擎与动态状态"),
    STANDARD("固定阈值 I/O 曲线", "四类压扩作用示意", "真实检测与压扩量"),
    RELATIVE("相对背景作用曲线", "背景与触发差示意", "真实检测与动态增益"),
    LIMITS("动态提升衰减边界", "增减封顶示意", "真实提升与衰减"),
    TIMING("背景 / 攻击保持释放", "起效与恢复示意", "真实动态包络"),
    DETECTOR("检测与弱信号目标", "左右检测与保护", "真实检测电平"),
    RANGE("处理频段作用范围", "原声与处理频段", "真实处理频谱"),
    SIDECHAIN("标准检测侧链滤波", "听音 / 检测分路", "真实侧链检测电平"),
    LOOKAHEAD("前瞻与对齐目标", "提前检测示意", "实际前瞻与处理电平"),
    HYSTERESIS("噪声门开闭回差", "开门 / 关门示意", "真实门控与动态量"),
    MIX("对齐原声与效果系数", "干湿混合示意", "真实混合处理频谱"),
    LEVEL("模块输出衰减目标", "整体幅度示意", "真实输入与输出电平"),
    RESPONSE("频率动态作用参考", "动态增益控制示意", "实际频率动态增益"),
    MONITOR("监测开关与测点", "本模块监测路径", "本模块真实电平"),
    MATCH("A/B 测量与衰减", "等电平对照示意", "A/B 实际测量"),
    AUDITION("七路试听与快照", "当前试听示意", "实际试听输出");

    companion object {
        fun fromKey(key: String): CompanderChartFocus? = when (key) {
            CompanderParameters.BASE_TIME, CompanderParameters.BASE_GRANULARITY, CompanderParameters.BASE_TRANSFORM,
            CompanderParameters.ENABLED, "compander_pro_baseline", "compander_pro_presets" -> FOUNDATION
            CompanderParameters.BASE_CURVE, CompanderParameters.CORE_ON, CompanderParameters.EXACT_FREQUENCY,
            CompanderParameters.amount.key -> CURVE
            in CompanderParameters.frequencies.map { it.key }, in CompanderParameters.coefficients.map { it.key } -> CURVE
            CompanderParameters.PROFESSIONAL, CompanderParameters.ENGINE, "compander_pro_help" -> ENGINE
            CompanderParameters.TYPE, CompanderParameters.threshold.key, CompanderParameters.ratio.key,
            CompanderParameters.standardKnee.key -> STANDARD
            CompanderParameters.relative.key, CompanderParameters.knee.key -> RELATIVE
            CompanderParameters.LIMITS_ON, CompanderParameters.maxBoost.key, CompanderParameters.maxCut.key -> LIMITS
            CompanderParameters.TIME_ON, CompanderParameters.backgroundDelta.key, CompanderParameters.attack.key,
            CompanderParameters.hold.key, CompanderParameters.release.key -> TIMING
            CompanderParameters.DETECT_ON, CompanderParameters.DETECTOR, CompanderParameters.LINKED,
            CompanderParameters.rmsWindow.key, CompanderParameters.weakFloor.key -> DETECTOR
            CompanderParameters.rangeLow.key, CompanderParameters.rangeHigh.key -> RANGE
            CompanderParameters.SIDECHAIN_ON, CompanderParameters.sideHP.key, CompanderParameters.sideLP.key -> SIDECHAIN
            CompanderParameters.LOOKAHEAD_ON, CompanderParameters.lookahead.key -> LOOKAHEAD
            CompanderParameters.hysteresis.key -> HYSTERESIS
            CompanderParameters.MIX_ON, CompanderParameters.mix.key -> MIX
            CompanderParameters.OUTPUT_ON, CompanderParameters.output.key -> LEVEL
            CompanderParameters.RESPONSE, "compander_pro_response_graph", "compander_pro_response_description" -> RESPONSE
            CompanderParameters.METERS, "compander_pro_actual", "compander_pro_compatibility" -> MONITOR
            "compander_pro_measure", "compander_pro_match" -> MATCH
            "compander_pro_save_a", "compander_pro_save_b", "compander_pro_audition", "compander_pro_stop" -> AUDITION
            else -> null
        }
    }
}
