package me.timschneeberger.rootlessjamesdsp.fusion

/** Wire units match WSTxda/ViPERFX_RE v0.6.3 ViPER::DispatchCommand. */
object ViperParameters {
    const val PREFS = "dsp_fusion_viper"

    data class Control(
        val key: String, val title: String, val parameter: Int,
        val min: Int, val max: Int, val default: Int,
        val scale: Int = 1, val offset: Int = 0, val unit: String = "",
        val choices: List<String> = emptyList(),
    ) {
        fun encode(value: Int) = (value.coerceIn(min, max) * scale) + offset
        fun packet(value: Int): IntArray = intArrayOf(encode(value))
        fun label(value: Int) = choices.getOrNull(value) ?: "$value$unit"
    }

    data class Group(val key: String, val title: String, val enableParameter: Int?, val controls: List<Control>, val summary: String = "")

    val groups = listOf(
        Group("v_spectrum", "频谱扩展", 65548, listOf(
            Control("v_spectrum_freq", "参考频率", 65549, 3000, 12000, 7600, unit = " Hz"),
            Control("v_spectrum_amount", "谐波重建强度", 65550, 0, 560, 56, unit = "%"),
        )),
        Group("v_field", "立体声场增强", 65553, listOf(
            Control("v_field_widen", "立体声拓展", 65554, 0, 100, 0, unit = "%"),
            Control("v_field_center", "中央声像", 65555, 100, 200, 150, unit = "%"),
            Control("v_field_depth", "声场深度", 65556, 200, 800, 200),
        )),
        Group("v_diff", "差分环绕", 65557, listOf(
            Control("v_diff_delay", "延迟", 65558, 1, 20, 5, scale = 100, unit = " ms"),
        )),
        Group("v_reverb", "ViPER 混响", 65559, listOf(
            Control("v_reverb_size", "房间大小", 65560, 0, 100, 0, unit = "%"),
            Control("v_reverb_width", "房间宽度", 65561, 0, 100, 0, unit = "%"),
            Control("v_reverb_damp", "阻尼", 65562, 0, 100, 0, unit = "%"),
            Control("v_reverb_wet", "湿声（混响声）", 65563, 0, 100, 0, unit = "%"),
            Control("v_reverb_dry", "干声（原声）", 65564, 0, 100, 50, unit = "%"),
        ), "房间大小与宽度现在可按 1% 微调；原版 0–10 档分别对应这里的 0–100%。"),
        Group("v_dynamic", "动态音色处理", 65569, listOf(
            Control("v_dynamic_strength", "低音动态强度", 65573, 0, 100, 0, scale = 20, offset = 100),
        ), "设备响应可选择预设，参数与原驱动一致。"),
        Group("v_tube", "电子管模拟器", 65583, emptyList()),
        Group("v_bass", "ViPER 低音", 65574, listOf(
            Control("v_bass_mode", "处理模式", 65575, 0, 2, 0, choices = listOf("自然低音", "Pure Bass+", "低音炮")),
            Control("v_bass_freq", "低音频率", 65576, 15, 150, 70, unit = " Hz"),
            Control("v_bass_amount", "增强强度", 65577, 0, 11, 0, scale = 50, offset = 50),
        )),
        Group("v_clarity", "ViPER 清晰度", 65578, listOf(
            Control("v_clarity_mode", "处理模式", 65579, 0, 2, 0, choices = listOf("自然", "Ozone", "XHiFi")),
            Control("v_clarity_amount", "增强强度", 65580, 0, 9, 0, scale = 50),
        )),
        Group("v_cure", "ViPER 耳机交叉馈送", 65581, listOf(
            Control("v_cure_level", "效果强度", 65582, 0, 2, 0, choices = listOf("轻度", "中度", "强")),
        )),
        Group("v_analog", "模拟谐波染色", 65584, listOf(
            Control("v_analog_mode", "模拟模式", 65585, 0, 2, 0, choices = listOf("轻度", "中度", "强")),
        )),
    )

    // Public driver tuning values, not a private user preset.
    val dynamicPresets = linkedMapOf(
        "均衡" to intArrayOf(140, 6200, 40, 60, 10, 80),
        "温和" to intArrayOf(180, 5800, 55, 80, 10, 70),
        "厚实" to intArrayOf(300, 5600, 60, 105, 10, 50),
        "清晰" to intArrayOf(600, 5400, 60, 105, 10, 20),
        "深低音" to intArrayOf(100, 5600, 40, 80, 50, 50),
        "开放声场" to intArrayOf(1200, 6200, 40, 80, 0, 20),
    )
}
