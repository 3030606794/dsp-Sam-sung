package me.timschneeberger.rootlessjamesdsp.fusion

/** Display routing only; all audio/file editing remains in the original editors. */
enum class ConvolutionChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    FILE("原IR与工作核频响", "工作IR前后段分布", "真实卷积输入与输出频谱"),
    MIX("干湿混合系数", "原声与卷积混合", "真实原声与卷积输出频谱"),
    WET("湿声固定增益", "卷积分量强弱", "真实输入湿声与输出电平"),
    OUTPUT("混合输出固定衰减", "模块输出幅度", "真实输入湿声与输出电平"),
    PREDELAY("预延时与实际采样", "干湿到达时间", "真实数字音频波形"),
    LEGACY_TRIM("原版相对裁剪阈值", "保留IR头尾范围", "真实数字音频波形"),
    LEGACY_SHIFT("四核原版整数移位", "各IR核的相对位置", "真实数字音频波形"),
    WINDOW("IR保留窗口与淡化", "保留片段与边缘", "真实数字音频波形"),
    NORMALIZATION("原采样率峰值归一化", "整组IR同步缩放", "真实输入湿声与输出电平"),
    HIGH_PASS("湿声高通响应", "湿声低频收束", "真实湿声滤波后频谱"),
    LOW_PASS("湿声低通响应", "湿声高频柔化", "真实湿声滤波后频谱"),
    LOW_SHELF("湿声低频搁架响应", "低频厚薄作用区", "真实湿声滤波后频谱"),
    HIGH_SHELF("湿声高频搁架响应", "高频明暗作用区", "真实湿声滤波后频谱"),
    CHANNEL("湿声输出校准与极性", "湿声左右输出路线", "真实湿声相位与M/S关系"),
    MATRIX("四路径增益与极性", "输入输出四路径", "真实湿声相位与M/S关系"),
    FRACTIONAL("四路径分数延时", "采样点与插值", "真实数字音频波形"),
    SHAPE("前后段增益包络", "已有尾音收敛", "真实输入湿声与输出电平"),
    TRANSITION("新旧核过渡时间", "换核与旧尾退出", "真实换核方式与剩余时间"),
    ANALYSIS("工作核深度分析", "IR分析单位与来源", "真实卷积输入与输出频谱"),
    AUDITION("四路临时对照", "原声湿声与差分", "真实输入湿声与试听输出"),
    COMPARISON("参数A/B与临时补偿", "应用参数快照", "真实输入湿声与试听输出");

    companion object {
        fun fromKey(key: String): ConvolutionChartFocus? {
            val p: ConvolutionParameters = ConvolutionParameters
            val a: ConvolutionAdvancedParameters = ConvolutionAdvancedParameters
            if (key.startsWith("conv1_legacy_field_")) return when (key.removePrefix("conv1_legacy_field_").toIntOrNull()) {
                0, 1 -> LEGACY_TRIM; 2, 3, 4, 5 -> LEGACY_SHIFT; else -> null
            }
            return when (key) {
                p.FILE, p.ENABLED, "conv1_metadata", "conv1_driver" -> FILE
                p.mix.key, "conv1_level_help" -> MIX
                p.wet.key -> WET; p.output.key -> OUTPUT
                p.predelay.key, "conv1_time_units", "conv1_time_help" -> PREDELAY
                p.LEGACY_MODE, p.LEGACY_ADVANCED, "conv1_legacy_warning" -> LEGACY_TRIM
                p.WINDOW_ENABLED, p.start.key, p.length.key, p.fadeIn.key, p.fadeOut.key, "conv1_window_help" -> WINDOW
                p.NORM_MODE, p.NORM_ONLY_ATTENUATION, p.normTarget.key, "conv1_norm_help" -> NORMALIZATION
                a.enabledFilter(0), a.hp.key, a.slope(0), "conv2_filter_help" -> HIGH_PASS
                a.enabledFilter(1), a.lp.key, a.slope(1) -> LOW_PASS
                a.enabledFilter(2), a.lowHz.key, a.lowGain.key -> LOW_SHELF
                a.enabledFilter(3), a.highHz.key, a.highGain.key -> HIGH_SHELF
                p.CHANNEL_LINK, p.left.key, p.right.key, p.INVERT_LEFT, p.INVERT_RIGHT, p.SWAP, "conv1_channel_help" -> CHANNEL
                a.MATRIX, a.DIRECT_LINK, a.CROSS_LINK, a.ROUTE, "conv2_matrix_help" -> MATRIX
                in a.gain.map { it.key } -> MATRIX
                in (0..3).flatMap { listOf(a.mute(it), a.invert(it)) } -> MATRIX
                a.FRACTIONAL, a.METHOD, "conv2_fractional_help" -> FRACTIONAL
                in a.delay.map { it.key } -> FRACTIONAL
                a.SHAPE, "conv2_shape_help" -> SHAPE
                in a.shapeControls.map { it.key } -> SHAPE
                a.SWITCH, "conv2_transition_help", "conv2_runtime" -> TRANSITION
                in a.transitionControls.map { it.key } -> TRANSITION
                a.ANALYSIS, a.GRAPH, a.SMOOTH, "conv2_analysis_help", "conv2_response", "conv1_response", "conv1_analysis_help",
                "conv1_response_description", "conv2_response_description" -> ANALYSIS
                "conv1_actual", "conv1_audition" -> AUDITION
                "conv2_comparison_info", "conv2_capture_0", "conv2_capture_1", "conv2_comparison", a.comparison.key -> COMPARISON
                else -> null
            }
        }
    }
}
