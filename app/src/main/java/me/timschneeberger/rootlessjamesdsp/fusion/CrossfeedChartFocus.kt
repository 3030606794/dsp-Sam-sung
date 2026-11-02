package me.timschneeberger.rootlessjamesdsp.fusion

/** Read-only focus groups for the headphone/Bauer module, independent of the six-preset module. */
internal enum class CrossfeedChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    RESPONSE("交馈频响与衰减", "双耳融合示意", "真实输出峰值"),
    PATH("双向交叉衰减", "左右声路示意", "左右输出峰值"),
    DELAY("附加延迟与相位", "到达时差示意", "延迟处理输出"),
    FILTER("支路与输出滤波", "频段保留示意", "滤波后真实电平"),
    CENTER("中央音色补偿", "中央与两侧示意", "补偿后输出电平"),
    LEVEL("混合与输出衰减", "原声与效果示意", "输出峰值与趋势"),
    MONITOR("诊断范围与测点", "监测通道示意", "真实电平诊断"),
    MATCH("A/B 测量与衰减", "等响度对照示意", "A/B 电平测量"),
    AUDITION("试听与快照路径", "当前试听示意", "实际试听输出");
    companion object {
        fun fromKey(key: String): CrossfeedChartFocus? = when (key) {
            CrossfeedParameters.LEGACY, CrossfeedParameters.ENABLED, CrossfeedParameters.cutoff.key,
            CrossfeedParameters.attenuation.key, "crossfeed_presets" -> RESPONSE
            CrossfeedAdvancedParameters.SEPARATE, CrossfeedAdvancedParameters.left.key,
            CrossfeedAdvancedParameters.right.key -> PATH
            CrossfeedParameters.delay.key, CrossfeedAdvancedParameters.leftDelay.key,
            CrossfeedAdvancedParameters.rightDelay.key -> DELAY
            CrossfeedParameters.FILTER, CrossfeedAdvancedParameters.FILTER, CrossfeedAdvancedParameters.SLOPE -> FILTER
            CrossfeedAdvancedParameters.COMPENSATION, CrossfeedAdvancedParameters.amount.key -> CENTER
            CrossfeedParameters.mix.key, CrossfeedParameters.output.key -> LEVEL
            CrossfeedAdvancedParameters.DIAGNOSTICS -> MONITOR
            "crossfeed_measure", "crossfeed_match" -> MATCH
            "crossfeed_save_a", "crossfeed_save_b", "crossfeed_audition", "crossfeed_stop" -> AUDITION
            else -> null
        }
    }
}
