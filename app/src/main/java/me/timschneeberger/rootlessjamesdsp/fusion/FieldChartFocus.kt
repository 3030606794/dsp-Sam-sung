package me.timschneeberger.rootlessjamesdsp.fusion

/** Display routing only: no DSP parameter, preset or audition state is written here. */
enum class FieldChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    WIDTH("宽度与中央", "声场展开示意", "实时声场"),
    GAIN("输出增益", "能量缩放示意", "实时输出电平"),
    DEPTH("深度与混合", "相对纵深示意", "声场与相关度"),
    BAND("成像频带", "频段宽度示意", "声场与能量"),
    FOCUS("低频保留宽度", "低频收拢示意", "中央与侧向能量"),
    DYNAMIC("Side 压缩曲线", "启动与恢复", "Side 衰减 · 20 秒"),
    GUARD("相关度迟滞区间", "收回与恢复", "相关度与空间回收"),
    TRANSIENT("起音保护模型", "空间恢复包络", "瞬态保护 · 20 秒"),
    DELAY("微延时与采样", "相位与混合", "声场与相关度"),
    DIFFUSION("扩散相位模型", "混合频响模型", "扩散声场"),
    AUDITION("试听路径", "单声道合并", "兼容监测");

    companion object {
        fun fromKey(key: String): FieldChartFocus? = when {
            key == "field_audition" -> AUDITION
            key == FieldParameters.outputTrim.key -> GAIN
            key == FieldParameters.depth.key || key == FieldParameters.depthMix.key ||
                key == FieldParameters.LEGACY_DEPTH_ENABLED || key == FieldParameters.MS_DEPTH_ENABLED -> DEPTH
            key == FieldParameters.BAND_ENABLED || key == FieldParameters.bandLow.key || key == FieldParameters.bandHigh.key -> BAND
            key == FieldParameters.FOCUS_ENABLED || key == FieldParameters.focusFrequency.key || key == FieldParameters.focusWidth.key -> FOCUS
            key in FieldAdvancedParameters.dynamic.map { it.key } || key == FieldAdvancedParameters.DYNAMIC_ENABLED -> DYNAMIC
            key in FieldAdvancedParameters.guard.map { it.key } || key == FieldAdvancedParameters.GUARD_ENABLED -> GUARD
            key in FieldAdvancedParameters.transient.map { it.key } || key == FieldAdvancedParameters.TRANSIENT_ENABLED -> TRANSIENT
            key in FieldAdvancedParameters.delay.map { it.key } || key == FieldAdvancedParameters.DELAY_ENABLED -> DELAY
            key in FieldAdvancedParameters.diffusion.map { it.key } || key == FieldAdvancedParameters.DIFFUSION_ENABLED -> DIFFUSION
            key == FieldParameters.MODE || key in setOf(FieldParameters.legacyWidth.key,
                FieldParameters.legacyCenter.key, FieldParameters.width.key, FieldParameters.midTrim.key) -> WIDTH
            else -> null
        }
    }
}
