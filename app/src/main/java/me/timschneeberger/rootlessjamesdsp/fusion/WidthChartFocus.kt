package me.timschneeberger.rootlessjamesdsp.fusion

/** UI selection only. Audio parameters, linked edits and comparison storage remain in their editors. */
enum class WidthChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    WIDTH("总体宽度与比例", "声场展开示意", "实时声场与相关度"),
    BANDS("三段宽度与分频", "频段宽度示意", "三频段实际声场"),
    MONO("低频收拢频响", "低频回到中央", "低频与整体兼容监测"),
    LEVEL("中置与输出电平", "能量与混合示意", "真实输入与输出电平"),
    AUTO("匹配响应与范围", "响度补偿示意", "实时电平匹配"),
    GUARD("相关度阈值与迟滞", "扩宽收回与恢复", "相关度与保护回收"),
    DYNAMIC("动态宽度曲线", "动态回收与恢复", "动态宽度实际回收"),
    TRANSIENT("起音阈值与包络", "起音扩宽保护", "瞬态实际回收"),
    EQ("扩宽增量均衡", "新增侧声染色", "增量处理与声场"),
    MONITOR("监测时间与频段", "监测通道示意", "分段相关度历史"),
    AUDITION("试听路径与测点", "分量与 A/B 对照", "正常输出与试听状态");

    companion object {
        fun fromKey(key: String): WidthChartFocus? = when {
            key in setOf("width1_audition", "width2_compare", "width2_save_a", "width2_save_b", "width2_compare_match") -> AUDITION
            key == WidthParameters.MODE || key == WidthParameters.LEGACY || key == WidthParameters.master.key -> WIDTH
            key in WidthParameters.bands.map { it.key } || key == WidthParameters.crossLow.key ||
                key == WidthParameters.crossHigh.key || key == WidthParameters.SPLIT_SLOPE || key == WidthParameters.LINK -> BANDS
            key == WidthParameters.MONO || key == WidthParameters.monoHz.key || key == WidthParameters.monoAmount.key || key == WidthParameters.MONO_SLOPE -> MONO
            key in setOf(WidthParameters.midGain.key, WidthParameters.mix.key, WidthParameters.output.key) -> LEVEL
            key in setOf(WidthParameters.AUTO, WidthParameters.autoWindow.key, WidthParameters.autoMax.key) -> AUTO
            key in setOf(WidthParameters.GUARD, WidthParameters.threshold.key, WidthParameters.hysteresis.key,
                WidthParameters.attack.key, WidthParameters.release.key, WidthParameters.strength.key) -> GUARD
            key == WidthParameters.meterWindow.key -> MONITOR
            else -> when (WidthAdvancedParameters.entries.firstOrNull { it.control.key == key }?.group) {
                1 -> DYNAMIC
                2 -> TRANSIENT
                3 -> GUARD
                4 -> EQ
                5 -> AUTO
                6 -> MONITOR
                else -> null
            }
        }
        fun dynamicBand(key: String): Int = (0..3).firstOrNull { key.startsWith("width2_dyn${it}_") } ?: 3
    }
}
