package me.timschneeberger.rootlessjamesdsp.fusion

/** Display focus only; these keys never write processing parameters. */
internal enum class PresetCrossfeedChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    RESPONSE("交叉响应与衰减", "双耳融合示意", "真实输出电平"),
    SPACE("空间交叉支路", "声场融合示意", "空间处理输出电平"),
    PATH("左右混入增益", "双向声路示意", "左右输出电平"),
    DELAY("附加延迟与相位", "到达时差示意", "延迟处理输出"),
    FILTER("交叉支路低通", "频段保留示意", "滤波后真实电平"),
    CENTER("中央与侧向比例", "中央声音示意", "补偿后真实电平"),
    LEVEL("混合与输出衰减", "原声与效果示意", "输出峰值与趋势"),
    MATCH("A/B 测量与衰减", "等响度对照示意", "A/B 电平测量"),
    AUDITION("试听与快照路径", "当前试听示意", "实际试听输出");

    companion object {
        fun fromKey(key: String): PresetCrossfeedChartFocus? = when (key) {
            PresetCrossfeedParameters.BASE, PresetCrossfeedParameters.PROFESSIONAL,
            PresetCrossfeedParameters.cutoff.key, PresetCrossfeedParameters.attenuation.key,
            "crossfeed6_presets" -> RESPONSE
            PresetCrossfeedParameters.spatial.key -> SPACE
            PresetCrossfeedParameters.PATH_ON, PresetCrossfeedParameters.PATH_LINK,
            PresetCrossfeedParameters.leftToRight.key, PresetCrossfeedParameters.rightToLeft.key -> PATH
            PresetCrossfeedParameters.delay.key -> DELAY
            PresetCrossfeedParameters.FILTER_ON, PresetCrossfeedParameters.FILTER_SLOPE,
            PresetCrossfeedParameters.filterCutoff.key -> FILTER
            PresetCrossfeedParameters.CENTER_ON, PresetCrossfeedParameters.center.key -> CENTER
            PresetCrossfeedParameters.mix.key, PresetCrossfeedParameters.output.key -> LEVEL
            "crossfeed6_match", "crossfeed6_measure" -> MATCH
            "crossfeed6_save_a", "crossfeed6_save_b", "crossfeed6_audition", "crossfeed6_stop" -> AUDITION
            else -> null
        }
    }
}
