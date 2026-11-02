package me.timschneeberger.rootlessjamesdsp.fusion

/** Display routing only. Selecting a chart never changes a sound parameter. */
enum class ToneChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    CORE("十项有效作用量", "音色作用分布", "主配置实际十项作用量"),
    TEMPERATURE("温度双架式响应", "冷暖两端平衡", "主配置温度静态响应预测"),
    DEPTH("低频下潜架式响应", "低音深度作用区", "主配置低音静态响应预测"),
    ELASTIC("弹性静态与起音参数", "隆起与低音起音", "低音起音与静态响应"),
    PRESENCE("厚度与人声频段", "音色突出位置", "主配置音色静态响应预测"),
    SIBILANCE("双齿音频段响应", "明亮与柔和作用区", "主配置齿音静态响应预测"),
    AIR("空气感架式响应", "高频明暗作用区", "主配置高频静态响应预测"),
    TRANSIENT("瞬态快慢包络参数", "起音增强与柔化", "真实乐器瞬态增益"),
    WEAK("弱信号保护判据", "保留静态与原声", "真实动态增益与保护门限"),
    MIX("原声与效果系数", "干湿混合路径", "真实输入与试听输出"),
    LEVEL("完整混合后输出", "输出幅度缩放", "真实输入与试听输出"),
    HEADROOM("自动与额外余量", "余量与电平位置", "当前自动与额外余量"),
    RESPONSE("保存配置静态响应", "静态与动态作用区分", "驱动当前静态响应预测"),
    MONITOR("电平与动态测点", "音色处理监测路线", "真实输入输出电平"),
    MATCH("A/B等电平流程", "等电平对照", "真实A/B测量与衰减"),
    AUDITION("试听路径与测点", "原声与音色对照", "真实试听输出电平");

    companion object {
        fun fromKey(key: String): ToneChartFocus? {
            val p: ToneAdvancedParameters = ToneAdvancedParameters
            val macro: Int = p.baseKeys.indexOf(key).takeIf { it >= 0 }
                ?: p.delta.indexOfFirst { it.key == key }
            if (macro >= 0) return when (macro) {
                0 -> TEMPERATURE; 1 -> DEPTH; 2 -> ELASTIC; 3, 4, 5 -> PRESENCE
                6, 7 -> SIBILANCE; 8 -> TRANSIENT; else -> AIR
            }
            val frequency: Int = p.frequency.indexOfFirst { it.key == key }
            if (frequency >= 0) return when (frequency) {
                0, 1 -> TEMPERATURE; 2 -> DEPTH; 3 -> ELASTIC; 4, 5, 6 -> PRESENCE
                7, 8 -> SIBILANCE; else -> AIR
            }
            val q: Int = p.q.indexOfFirst { it.key == key }
            if (q >= 0) return when (q) { 0 -> ELASTIC; 1, 2, 3 -> PRESENCE; else -> SIBILANCE }
            val slope: Int = p.slope.indexOfFirst { it.key == key }
            if (slope >= 0) return when (slope) { 0, 1 -> TEMPERATURE; 2 -> DEPTH; else -> AIR }
            return when (key) {
                p.ENABLED, p.PROFESSIONAL, p.CORE_ON, p.SHAPE_ON, p.BASE_RANGE, p.amount.key,
                "tone_pro_baseline", "tone_pro_help", "tone_pro_presets", "tone_pro_local_window",
                "fusion_tone_reset", "tone_pro_reset_all" -> CORE
                p.temperatureLowWeight.key, p.temperatureHighWeight.key -> TEMPERATURE
                p.DYNAMIC_ON, p.elasticStaticWeight.key, p.bassDynamicWeight.key, p.bassSplit.key,
                p.bassFast.key, p.bassSlow.key, p.bassSensitivity.key, p.bassMax.key -> ELASTIC
                p.transientWeight.key, p.transientFast.key, p.transientSlow.key,
                p.transientSensitivity.key, p.transientMax.key -> TRANSIENT
                p.WEAK_ON, p.weakFloor.key -> WEAK
                p.MIX_ON, p.mix.key -> MIX
                p.OUTPUT_ON, p.output.key -> LEVEL
                p.HEADROOM_ON, p.headroom.key -> HEADROOM
                p.RESPONSE, "tone_pro_response_graph", "tone_pro_response_description" -> RESPONSE
                p.METERS, "tone_pro_actual", "tone_pro_compatibility" -> MONITOR
                "tone_pro_measure", "tone_pro_match" -> MATCH
                "tone_pro_save_a", "tone_pro_save_b", "tone_pro_audition", "tone_pro_stop" -> AUDITION
                else -> null
            }
        }
    }
}
