package me.timschneeberger.rootlessjamesdsp.fusion

/** Routes display focus only. All existing parameter and audition callbacks remain owners. */
enum class DiffChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    DELAY("延时与采样", "左右到达示意", "实时声场与相关度"),
    MIX("混合与合并响应", "原声与迟到声音", "实际混合 · 20 秒"),
    BAND("作用频带与窗长", "延时作用范围", "三频段兼容监测"),
    QUALITY("插值幅相模型", "采样点与插值", "声场与相关度"),
    LEVEL("支路与输出电平", "音量缩放示意", "真实输入与输出电平"),
    GUARD("兼容阈值与迟滞", "收回与恢复", "兼容回收 · 20 秒"),
    TRANSIENT("起音阈值与时间", "迟到声音保护", "瞬态保护 · 20 秒"),
    MONITOR("三频段监测分界", "监测通道示意", "三频段实际读数"),
    AUDITION("试听路径与测点", "对照路线示意", "兼容监测 · 试听之前");

    companion object {
        fun fromKey(key: String): DiffChartFocus? = when {
            key == "diff_audition" -> AUDITION
            key == DiffParameters.MODE || key == DiffParameters.legacy.key || key == DiffParameters.delay.key ||
                key == DiffAdvancedParameters.FINE_ENABLED || key == DiffAdvancedParameters.fine.key -> DELAY
            key == DiffParameters.CHANNEL || key == DiffParameters.mix.key -> MIX
            key == DiffParameters.BAND_ENABLED || key == DiffParameters.low.key || key == DiffParameters.high.key ||
                key == DiffAdvancedParameters.window.key -> BAND
            key == DiffAdvancedParameters.QUALITY -> QUALITY
            key == DiffAdvancedParameters.wet.key || key == DiffAdvancedParameters.output.key -> LEVEL
            key == DiffAdvancedParameters.GUARD_ENABLED || key == DiffAdvancedParameters.GUARD_MODE ||
                key in DiffAdvancedParameters.guard.map { it.key } -> GUARD
            key == DiffAdvancedParameters.TRANSIENT_ENABLED || key in DiffAdvancedParameters.transient.map { it.key } -> TRANSIENT
            key == DiffAdvancedParameters.low.key || key == DiffAdvancedParameters.high.key -> MONITOR
            else -> null
        }
    }
}
